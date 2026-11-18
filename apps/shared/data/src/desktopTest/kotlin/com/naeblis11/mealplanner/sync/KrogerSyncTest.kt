package com.naeblis11.mealplanner.sync

import com.naeblis11.mealplanner.data.KrogerMatchRepository
import com.naeblis11.mealplanner.data.SyncSchema
import com.naeblis11.mealplanner.domain.KrogerMatch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kroger's remembered picks travel like the other records, and never to a device whose build doesn't know them. */
class KrogerSyncTest {
    private val h = SyncHarness()
    private val phoneMatches = KrogerMatchRepository(h.phone.db)
    private val pcMatches = KrogerMatchRepository(h.pc.db)

    @After
    fun tearDown() = h.close()

    private val chuck = KrogerMatch("ground chuck", "0001111", "0001111000", 2)

    @Test
    fun aMatchMadeOnOneDeviceReachesTheOtherAndSoDoesForgettingIt() = runBlocking {
        h.client.sync(FirstSync.MERGE)
        phoneMatches.keep(listOf(chuck))
        h.client.sync()
        assertEquals(listOf(chuck), pcMatches.all())

        pcMatches.keep(listOf(chuck.copy(productId = "0002222", upc = "0002222000", quantity = 1)))
        h.client.sync()
        assertEquals("0002222", phoneMatches.all().single().productId)

        phoneMatches.forget("ground chuck")
        h.client.sync()
        assertEquals(emptyList<KrogerMatch>(), pcMatches.all())
    }

    @Test
    fun theAisleThePcLookedUpReachesThePhone() = runBlocking {
        h.client.sync(FirstSync.MERGE)
        pcMatches.keep(listOf(chuck))
        h.client.sync()
        val product = com.naeblis11.mealplanner.domain.KrogerProduct(
            "0001111", "0001111000", "Ground Chuck", null, null, 4.99, null, null, true, true, true, aisle = "AISLE 12",
        )
        pcMatches.noteAisles(listOf(product), "01400943")
        h.client.sync()
        assertEquals(chuck.copy(aisle = "AISLE 12", aisleStoreId = "01400943"), phoneMatches.all().single())

        // Looking up again finds nothing new to send; the same product kept without an aisle keeps it.
        val seq = SyncSchema.changeSeq(h.pc.db)
        pcMatches.noteAisles(listOf(product), "01400943")
        pcMatches.keep(listOf(chuck.copy(quantity = 2)))
        assertEquals(seq, SyncSchema.changeSeq(h.pc.db))
        pcMatches.keep(listOf(chuck.copy(quantity = 3)))
        assertEquals("AISLE 12", pcMatches.all().single().aisle)
    }

    @Test
    fun theFirstSyncCatchesUpTheMatchesItCouldNotSendYet() = runBlocking {
        // The phone had picks before it ever paired; a first sync doesn't yet know what the hub understands.
        phoneMatches.keep(listOf(chuck))
        h.client.sync(FirstSync.MERGE)
        assertEquals(emptyList<KrogerMatch>(), pcMatches.all())
        h.client.sync()
        assertEquals(listOf(chuck), pcMatches.all())
    }

    @Test
    fun replaceForgetsThePhonesOwnMatches() = runBlocking {
        phoneMatches.keep(listOf(chuck))
        pcMatches.keep(listOf(chuck.copy(nameKey = "onion")))
        h.client.sync(FirstSync.REPLACE)
        assertEquals(listOf("onion"), phoneMatches.all().map { it.nameKey })
    }

    /**
     * A hub on a build before Kroger: it sends no table list, and its decoder refuses a whole exchange that holds a
     * record of a table it doesn't know. When it is updated, the phone's matches go at the next sync.
     */
    @Test
    fun aHubBeforeKrogerIsNeverSentAMatchAndGetsThemOnceUpdated() = runBlocking {
        var old = true
        val transport = object : SyncTransport {
            override suspend fun exchange(request: ExchangeRequest): ExchangeReply {
                val sent = SyncWire.encode(request)
                if (!old) return SyncWire.decodeReply(SyncWire.encode(h.hub.exchange(SyncSchema.deviceId(h.phone.db), SyncWire.decodeRequest(sent))))
                assertFalse("an old hub was sent a Kroger record", sent.contains("\"kroger_match\"") && recordTables(sent).contains(SyncTables.KROGER))
                val reply = h.hub.exchange(SyncSchema.deviceId(h.phone.db), SyncWire.decodeRequest(withoutTables(sent)))
                return SyncWire.decodeReply(withoutTables(SyncWire.encode(reply)))
            }
            override suspend fun photo(uuid: String): ByteArray? = h.hub.photo(uuid)
            override suspend fun sendPhoto(uuid: String, bytes: ByteArray) { h.hub.receivePhoto(uuid, bytes) }
        }
        val client = SyncClient(h.phone.db, h.phone.recipes, h.phone.images, transport)
        client.sync(FirstSync.MERGE)
        phoneMatches.keep(listOf(chuck))
        client.sync()
        client.sync()
        assertEquals(emptyList<KrogerMatch>(), pcMatches.all())

        old = false
        client.sync()
        client.sync()
        assertEquals(listOf(chuck), pcMatches.all())
    }

    /** A phone on a build before Kroger asks with no table list: the hub sends it no match, even in a full reply. */
    @Test
    fun aPhoneBeforeKrogerIsNeverSentAMatch() = runBlocking {
        pcMatches.keep(listOf(chuck))
        val request = SyncWire.decodeRequest(withoutTables(SyncWire.encode(ExchangeRequest(1L, 0L, true, emptyList()))))
        assertEquals(SyncTables.BASE, request.tables)
        val reply = h.hub.exchange("old-phone", request)
        assertTrue(reply.full)
        assertTrue(reply.records.none { it.table == SyncTables.KROGER })
        assertTrue(h.hub.exchange("new-phone", ExchangeRequest(1L, 0L, true, emptyList())).records.any { it.table == SyncTables.KROGER })
    }

    /** A later build's table is left out of what is read, never a failed exchange. */
    @Test
    fun aRecordOfATableThisBuildDoesntKnowIsLeftOut() {
        val reply = SyncWire.encode(
            ExchangeReply(1L, 2L, false, listOf(SyncRecord(SyncTables.PANTRY, "milk", 1L, "d", true, null)), emptyList(), emptyList(), "hub"),
        ).replace("\"table\":\"pantry_item\"", "\"table\":\"a_later_table\"")
        val decoded = SyncWire.decodeReply(reply)
        assertEquals(emptyList<SyncRecord>(), decoded.records)
        assertEquals(SyncTables.KNOWN, decoded.tables)
        assertNull(runCatching { SyncWire.decodeRecord(SyncWire.encodeRecord(SyncRecord("a_later_table", "k", 1L, "d", true, null))) }.getOrNull())
    }

    private fun withoutTables(json: String): String {
        val o = Json.parseToJsonElement(json).jsonObject
        return JsonObject(o - "tables").toString()
    }

    private fun recordTables(json: String): List<String> =
        (Json.parseToJsonElement(json).jsonObject["records"] as JsonArray).map { it.jsonObject.getValue("table").jsonPrimitive.content }
}
