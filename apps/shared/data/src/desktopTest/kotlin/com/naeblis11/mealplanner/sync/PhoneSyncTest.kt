package com.naeblis11.mealplanner.sync

import com.naeblis11.mealplanner.data.AppMetaEntity
import com.naeblis11.mealplanner.data.Household
import com.naeblis11.mealplanner.sync.HubConnector.Companion.FP
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAN sync stage 2c (D1, D2, D3, D6): the phone's pairing, finding its PC, syncing and leaving, against a real hub in-process. */
class PhoneSyncTest {
    private val harness = SyncHarness()
    private val connector = HubConnector(harness)
    private val store = MemoryPairingStore()
    private val finder = FakePcFinder()
    private val phone = PhoneSync(harness.phone.db, harness.phone.recipes, harness.phone.images, store, finder, "Pixel", connector)
    private val meta = harness.phone.db.appMetaDao()

    @After
    fun tearDown() {
        harness.close()
    }

    private suspend fun pairAt(address: String = HOME): PhonePairing {
        connector.answerAt(address)
        return phone.pairScanned(HubConnector.qr(address))
    }

    @Test
    fun aScannedCodePairsAtTheFirstAddressThatAnswersAndKeepsIt() = runBlocking<Unit> {
        connector.answerAt(HOME)
        val pairing = phone.pairScanned(HubConnector.qr("198.51.100.9", HOME))
        assertEquals(listOf(HOME, "198.51.100.9"), pairing.addresses)
        assertEquals(HubConnector.PORT, pairing.port)
        assertEquals(FP, pairing.fingerprint)
        assertEquals(HubConnector.PC_NAME, pairing.pcName)
        assertEquals(HubConnector.KEY, pairing.deviceKey)
        assertEquals(harness.hub.hubId(), pairing.hubId)
        assertEquals(pairing, store.load())
        assertEquals(HubConnector.HOUSEHOLD, meta.get(Household.KEY))
        assertEquals(listOf("$HOME:5001 $FP Pixel"), connector.pairs)
    }

    @Test
    fun aScannedCodeShownByAnotherPcIsRefused() = runBlocking<Unit> {
        connector.answerAt(HOME, fingerprint = HubConnector.OTHER_FP)
        assertThrows(WrongPcException::class.java) { runBlocking { phone.pairScanned(HubConnector.qr(HOME)) } }
        assertNull(store.load())
    }

    @Test
    fun aCodeNoAddressAnswersIsUnreachable() = runBlocking<Unit> {
        val e = assertThrows(SyncHttpException::class.java) { runBlocking { phone.pairScanned(HubConnector.qr(HOME, "198.51.100.9")) } }
        assertEquals(SyncHttp.UNREACHABLE, e.message)
        assertNull(store.load())
    }

    @Test
    fun aTypedCodePairsUnpinnedAndKeepsTheFingerprintItLearnt() = runBlocking<Unit> {
        connector.answerAt(MOVED)
        val pairing = phone.pairTyped(HubConnector.CODE, FoundPc("KITCHEN", MOVED, HubConnector.PORT))
        assertEquals(listOf("$MOVED:5001 unpinned Pixel"), connector.pairs)
        assertEquals(FP, pairing.fingerprint)
        assertEquals(listOf(MOVED), store.load()!!.addresses)
    }

    @Test
    fun pairingStartsAgainWithTheFirstSyncQuestion() = runBlocking<Unit> {
        meta.put(AppMetaEntity(SyncClient.CURSOR_KEY, "7"))
        meta.put(AppMetaEntity(SyncClient.LAST_SYNC_KEY, "123"))
        assertTrue(phone.hasSynced())
        pairAt()
        assertFalse(phone.hasSynced())
        assertNull(phone.lastSyncAt())
    }

    @Test
    fun theFirstSyncMergesThenSyncsGoOnAtTheSameAddress() = runBlocking<Unit> {
        harness.pc.recipes.save(harness.doc("Soup", "u-pc"))
        harness.phone.recipes.save(harness.doc("Stew", "u-phone"))
        pairAt()
        assertEquals(1, phone.sync(FirstSync.MERGE).sent)
        assertEquals(listOf("Soup", "Stew"), harness.phone.recipes.allRecipes().map { it.name }.sorted())
        assertTrue(phone.hasSynced())
        assertNotNull(phone.lastSyncAt())
        phone.sync()
        assertEquals(listOf("$HOME:5001", "$HOME:5001"), connector.exchanges.toList())
        assertTrue(finder.asked.isEmpty())
    }

    @Test
    fun aPcThatMovedIsFoundByMdnsPinnedAndItsNewAddressKept() = runBlocking<Unit> {
        pairAt()
        connector.hosts.clear()
        connector.answerAt("203.0.113.30", fingerprint = HubConnector.OTHER_FP)
        connector.answerAt(MOVED)
        finder.found = listOf(FoundPc("OFFICE", "203.0.113.30", 5001), FoundPc("DEN", MOVED, 5001))
        phone.sync(FirstSync.MERGE)
        assertEquals(listOf("$MOVED:5001"), connector.exchanges.toList())
        assertEquals(listOf(PhoneSync.FIND_MILLIS), finder.asked.toList())
        assertEquals(listOf(MOVED, HOME), store.load()!!.addresses)
        // D6: every connection a sync made was pinned to the stored fingerprint.
        assertEquals(setOf(FP), connector.transportPins.toSet())
    }

    @Test
    fun aPcFoundElsewhereByTheCertificateGivesItsNameToo() = runBlocking<Unit> {
        // Stage 4b, R6: the main PC was handed over to another PC, which answers with the same certificate.
        pairAt()
        connector.hosts.clear()
        connector.answerAt(MOVED)
        finder.found = listOf(FoundPc("STUDY", MOVED, 5001))
        phone.sync(FirstSync.MERGE)
        assertEquals("STUDY", store.load()!!.pcName)
        assertEquals(listOf(MOVED, HOME), store.load()!!.addresses)
    }

    @Test
    fun aSyncResultPassesThroughAfterSyncWithThePairingItUsed() = runBlocking<Unit> {
        val seen = mutableListOf<String>()
        val pc = PhoneSync(
            harness.phone.db, harness.phone.recipes, harness.phone.images, store, finder, "Pixel", connector,
            afterSync = { pairing, result ->
                seen += pairing.pcName
                result.copy(note = "noted")
            },
        )
        pairAt()
        assertEquals("noted", pc.sync(FirstSync.MERGE).note)
        assertEquals(1, seen.size)
    }

    @Test
    fun aNewAddressThatCannotBeStoredStillFinishesTheSync() = runBlocking<Unit> {
        // F5/M3: keeping the new address is best effort; a store that fails doesn't undo an exchange already applied.
        val failing = object : PairingStore by store {
            @Volatile
            var fail = false

            override suspend fun save(pairing: PhonePairing) {
                if (fail) throw java.io.IOException("disk full")
                store.save(pairing)
            }
        }
        val phone = PhoneSync(harness.phone.db, harness.phone.recipes, harness.phone.images, failing, finder, "Pixel", connector)
        connector.answerAt(HOME)
        phone.pairScanned(HubConnector.qr(HOME))
        failing.fail = true
        connector.hosts.clear()
        connector.answerAt(MOVED)
        finder.found = listOf(FoundPc("DEN", MOVED, 5001))
        val result = phone.sync(FirstSync.MERGE)
        assertEquals(0, result.sent)
        assertEquals(listOf("$MOVED:5001"), connector.exchanges.toList())
        assertTrue(phone.hasSynced())
        assertEquals(listOf(HOME), store.load()!!.addresses)
    }

    @Test
    fun withThePcNowhereTheSyncIsUnreachableAndNothingChanges() = runBlocking<Unit> {
        val pairing = pairAt()
        connector.hosts.clear()
        val e = assertThrows(SyncHttpException::class.java) { runBlocking { phone.sync(FirstSync.MERGE) } }
        assertEquals(SyncHttp.UNREACHABLE, e.message)
        assertEquals(pairing, store.load())
        assertFalse(phone.hasSynced())
    }

    @Test
    fun aPhoneRemovedOnThePcForgetsItsPairingAndMarksButKeepsItsData() = runBlocking<Unit> {
        harness.phone.recipes.save(harness.doc("Stew", "u-phone"))
        pairAt()
        phone.sync(FirstSync.MERGE)
        connector.removed = true
        assertThrows(UnpairedException::class.java) { runBlocking { phone.sync() } }
        assertNull(store.load())
        assertTrue(SyncMarks.KEYS.all { meta.get(it) == null })
        assertEquals(listOf("Stew"), harness.phone.recipes.allRecipes().map { it.name })
    }

    @Test
    fun leaveTellsThePcAndForgetsButKeepsTheData() = runBlocking<Unit> {
        harness.phone.recipes.save(harness.doc("Stew", "u-phone"))
        pairAt()
        phone.sync(FirstSync.MERGE)
        phone.leave()
        assertEquals(listOf("$HOME:5001"), connector.left.toList())
        assertNull(store.load())
        assertTrue(SyncMarks.KEYS.all { meta.get(it) == null })
        assertEquals(listOf("Stew"), harness.phone.recipes.allRecipes().map { it.name })
    }

    @Test
    fun leaveForgetsEvenWhenThePcCantBeTold() = runBlocking<Unit> {
        pairAt()
        connector.leaveFailure = SyncHttpException(SyncHttp.UNREACHABLE)
        phone.leave()
        assertNull(store.load())
    }

    @Test
    fun withoutAPairingThereIsNoSync() = runBlocking<Unit> {
        assertThrows(NotPairedException::class.java) { runBlocking { phone.sync(FirstSync.MERGE) } }
        assertTrue(connector.exchanges.isEmpty())
    }

    @Test
    fun aScannedAddressWhereAnotherMachineAnswersIsPassedOver() = runBlocking<Unit> {
        connector.answerAt("198.51.100.9", fingerprint = HubConnector.OTHER_FP)
        connector.answerAt(HOME)
        val pairing = phone.pairScanned(HubConnector.qr("198.51.100.9", HOME))
        assertEquals(listOf(HOME, "198.51.100.9"), pairing.addresses)
        // The other machine never got the code's proof through: only the pinned pairing at HOME went through.
        assertEquals(listOf("$HOME:5001 $FP Pixel"), connector.pairs)
    }

    @Test
    fun aWrongCodeOnAScannedPairingIsRefusedAndNothingKept() = runBlocking<Unit> {
        connector.answerAt(HOME)
        val qr = PairingQr.encode(listOf(HOME), HubConnector.PORT, "654321", FP)
        assertThrows(WrongCodeException::class.java) { runBlocking { phone.pairScanned(qr) } }
        assertNull(store.load())
    }

    @Test
    fun clearingTheMarksKeepsEveryOtherRow() = runBlocking<Unit> {
        val device = com.naeblis11.mealplanner.data.SyncSchema.deviceId(harness.phone.db)
        meta.put(AppMetaEntity(Household.KEY, "h"))
        SyncMarks.KEYS.forEach { meta.put(AppMetaEntity(it, "1")) }
        SyncMarks.clear(harness.phone.db)
        assertTrue(SyncMarks.KEYS.all { meta.get(it) == null })
        assertEquals(device, com.naeblis11.mealplanner.data.SyncSchema.deviceId(harness.phone.db))
        assertEquals("h", meta.get(Household.KEY))
        assertEquals(
            setOf(SyncClient.CURSOR_KEY, SyncClient.CURSOR_HUB_KEY, SyncClient.SENT_MARK_KEY, SyncClient.LAST_SYNC_KEY, SyncClient.FULL_NEXT_KEY,
                SyncClient.TABLES_KEY, SyncClient.MY_TABLES_KEY),
            SyncMarks.KEYS.toSet(),
        )
    }

    @Test
    fun aTypedPairingSyncsPinnedToTheFingerprintItLearnt() = runBlocking<Unit> {
        connector.answerAt(MOVED)
        phone.pairTyped(HubConnector.CODE, FoundPc("KITCHEN", MOVED, HubConnector.PORT))
        phone.sync(FirstSync.MERGE)
        assertEquals(listOf(FP), connector.transportPins.toList())
        assertEquals(listOf("$MOVED:5001"), connector.exchanges.toList())
    }

    @Test
    fun anUnpinnedPairingIsOnlyTheTypedPath() = runBlocking<Unit> {
        pairAt()
        phone.leave()
        connector.answerAt(MOVED)
        phone.pairTyped(HubConnector.CODE, FoundPc("KITCHEN", MOVED, HubConnector.PORT))
        assertEquals(listOf("$HOME:5001 $FP Pixel", "$MOVED:5001 unpinned Pixel"), connector.pairs.toList())
    }

    @Test
    fun aPairingThatCantBeStoredLeavesTheOldStateAlone() = runBlocking<Unit> {
        // The Keystore refusing the save (say) must not cost the phone its marks or household: nothing was kept.
        val refusing = object : PairingStore {
            override suspend fun load(): PhonePairing? = null
            override suspend fun save(pairing: PhonePairing) = throw java.io.IOException("keystore")
            override suspend fun clear() {}
        }
        val phone = PhoneSync(harness.phone.db, harness.phone.recipes, harness.phone.images, refusing, finder, "Pixel", connector)
        meta.put(AppMetaEntity(Household.KEY, "old-household"))
        SyncMarks.KEYS.forEach { meta.put(AppMetaEntity(it, "7")) }
        connector.answerAt(HOME)
        assertThrows(java.io.IOException::class.java) { runBlocking { phone.pairScanned(HubConnector.qr(HOME)) } }
        assertEquals("old-household", meta.get(Household.KEY))
        assertTrue(SyncMarks.KEYS.all { meta.get(it) == "7" })
    }

    // Stage 4a: a client PC is the same client, listed as a PC, with hooks for its role.
    private val joined = mutableListOf<String>()
    private var left = 0
    private val pc = PhoneSync(
        harness.phone.db, harness.phone.recipes, harness.phone.images, store, finder, "OFFICE", connector,
        kind = PairingWire.KIND_PC, onJoined = { joined += it }, onLeft = { left++ },
    )

    @Test
    fun theDefaultIsAPhoneAndAPcSaysItIsOne() = runBlocking<Unit> {
        pairAt()
        assertEquals(listOf(PairingWire.KIND_PHONE), connector.kinds.toList())
        connector.answerAt(MOVED)
        pc.pairTyped(HubConnector.CODE, FoundPc("KITCHEN", MOVED, HubConnector.PORT))
        assertEquals(listOf(PairingWire.KIND_PHONE, PairingWire.KIND_PC), connector.kinds.toList())
    }

    @Test
    fun aPcThatJoinsIsToldTheHouseholdItTookAfterItIsKept() = runBlocking<Unit> {
        connector.answerAt(HOME)
        pc.pairTyped(HubConnector.CODE, FoundPc("KITCHEN", HOME, HubConnector.PORT))
        assertEquals(listOf(HubConnector.HOUSEHOLD), joined)
        assertEquals(HubConnector.HOUSEHOLD, meta.get(Household.KEY))
        assertNotNull(store.load())
        assertEquals(0, left)
    }

    @Test
    fun aPcThatLeavesOrIsRemovedIsToldAfterTheForgetting() = runBlocking<Unit> {
        connector.answerAt(HOME)
        pc.pairTyped(HubConnector.CODE, FoundPc("KITCHEN", HOME, HubConnector.PORT))
        pc.sync(FirstSync.MERGE)
        pc.leave()
        assertEquals(1, left)
        assertNull(store.load())

        pc.pairTyped(HubConnector.CODE, FoundPc("KITCHEN", HOME, HubConnector.PORT))
        pc.sync(FirstSync.MERGE)
        connector.removed = true
        assertThrows(UnpairedException::class.java) { runBlocking { pc.sync() } }
        assertEquals(2, left)
        assertNull(store.load())
    }

    @Test
    fun aFailedSyncThatIsNotARemovalForgetsNothing() = runBlocking<Unit> {
        connector.answerAt(HOME)
        pc.pairTyped(HubConnector.CODE, FoundPc("KITCHEN", HOME, HubConnector.PORT))
        connector.hosts.clear()
        assertThrows(SyncHttpException::class.java) { runBlocking { pc.sync(FirstSync.MERGE) } }
        assertEquals(0, left)
        assertNotNull(store.load())
    }

    private companion object {
        const val HOME = "192.0.2.20"
        const val MOVED = "192.0.2.31"
    }
}
