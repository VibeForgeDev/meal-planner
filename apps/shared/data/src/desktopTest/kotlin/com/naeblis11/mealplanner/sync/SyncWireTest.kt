package com.naeblis11.mealplanner.sync

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncWireTest {
    private val soup = SyncRecord(
        SyncTables.RECIPE, "u-1", 1_700_000_000_123L, "dev-a", false,
        buildJsonObject { put("raw_yaml", "recipe_name: Soup\n"); put("image_hash", null as String?) },
    )
    private val gone = SyncRecord(SyncTables.SHOPPING, "abc", 5L, "dev-b", true, null)

    @Test
    fun aRequestAndAReplyComeBackAsTheyWent() {
        val request = ExchangeRequest(42L, 7L, false, listOf(soup, gone), hubId = "hub-1")
        assertEquals(request, SyncWire.decodeRequest(SyncWire.encode(request)))
        val first = ExchangeRequest(42L, 0L, true, emptyList(), hubId = null)
        assertEquals(first, SyncWire.decodeRequest(SyncWire.encode(first)))
        val reply = ExchangeReply(43L, 9L, true, listOf(gone), listOf("Bad stew"), listOf(PhotoWant("u-1", "ab12")), hubId = "hub-1")
        assertEquals(reply, SyncWire.decodeReply(SyncWire.encode(reply)))
        assertEquals(soup, SyncWire.decodeRecord(SyncWire.encodeRecord(soup)))
    }

    @Test
    fun malformedMessagesAreRefused() {
        val bad = listOf(
            "not json",
            // A table this build doesn't know is left out (KrogerSyncTest), but a record of one must still be well formed.
            """{"deviceTime":1,"cursor":0,"full":false,"hubId":null,"records":[{"table":7,"key":"k","updatedAt":1,"updatedBy":"d","deleted":true}]}""",
            """{"deviceTime":1,"cursor":0,"full":false,"hubId":null,"records":[],"tables":"recipe"}""",
            """{"deviceTime":1,"cursor":0,"full":false,"hubId":null,"records":[{"table":"recipe","key":"k","updatedAt":1,"updatedBy":"d","deleted":false}]}""",
            """{"deviceTime":1,"cursor":0,"full":false,"hubId":null,"records":[{"table":"recipe","key":"","updatedAt":1,"updatedBy":"d","deleted":true}]}""",
            """{"deviceTime":"x","cursor":0,"full":false,"hubId":null,"records":[]}""",
            """{"deviceTime":1,"cursor":0,"full":false,"records":[]}""",
            """{"deviceTime":1,"cursor":0,"full":false,"hubId":7,"records":[]}""",
        )
        bad.forEach { text -> assertThrows(text, IllegalArgumentException::class.java) { SyncWire.decodeRequest(text) } }
        val badReplies = listOf(
            """{"hubTime":1,"cursor":0,"full":false,"records":[],"skipped":[],"wantPhotos":[]}""",
            """{"hubTime":1,"cursor":0,"full":false,"hubId":null,"records":[],"skipped":[],"wantPhotos":[]}""",
            """{"hubTime":1,"cursor":0,"full":false,"hubId":"h","records":[],"skipped":[],"wantPhotos":["u-1"]}""",
            """{"hubTime":1,"cursor":0,"full":false,"hubId":"h","records":[],"skipped":[],"wantPhotos":[{"uuid":"u-1"}]}""",
            """{"hubTime":1,"cursor":0,"full":false,"hubId":"h","records":[],"skipped":[],"wantPhotos":[{"uuid":"","hash":"ab"}]}""",
        )
        badReplies.forEach { text -> assertThrows(text, IllegalArgumentException::class.java) { SyncWire.decodeReply(text) } }
    }
}
