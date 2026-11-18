package com.naeblis11.mealplanner.sync

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The synced tables, as sync_change names them, in the order an exchange applies them (a meal needs its recipe). */
object SyncTables {
    const val RECIPE = "recipe"
    const val MEAL = "meal_plan"
    const val PANTRY = "pantry_item"
    const val AISLE = "ingredient_aisle"
    const val SHOPPING = "shopping_list_item"

    /** Kroger's remembered picks (plan 2026-11-17). */
    const val KROGER = "kroger_match"
    val ORDER = listOf(RECIPE, MEAL, PANTRY, AISLE, SHOPPING, KROGER)

    /**
     * The tables every build since stage 2a knows, and what a side that sends no `tables` list (one before Kroger)
     * understands. Such a side refuses a whole exchange that holds a record of any other table, so nothing else is
     * ever sent to it (SyncClient, SyncHub).
     */
    val BASE: Set<String> = setOf(RECIPE, MEAL, PANTRY, AISLE, SHOPPING)

    /** The tables this build understands, as it says in each exchange. */
    val KNOWN: Set<String> = ORDER.toSet()
}

/**
 * One record's state (LAN sync spec, section 3): its table and sync key, its last change (when, by which device), and
 * its fields, or none for a tombstone. [data]'s fields per table are SyncRecords'.
 */
data class SyncRecord(
    val table: String,
    val key: String,
    val updatedAt: Long,
    val updatedBy: String,
    val deleted: Boolean,
    val data: JsonObject?,
)

/**
 * A client's half of one exchange: its clock, the hub cursor it has, whether it sends everything, its changes, and the
 * id of the hub that gave the cursor ([hubId], null before the first sync).
 */
data class ExchangeRequest(
    val deviceTime: Long,
    val cursor: Long,
    val full: Boolean,
    val records: List<SyncRecord>,
    val hubId: String? = null,
    /** The tables the client understands; a request without the list is from a build before Kroger ([SyncTables.BASE]). */
    val tables: Set<String> = SyncTables.KNOWN,
)

/** A photo the hub wants: the recipe's uuid and the image_hash the bytes must have. */
data class PhotoWant(val uuid: String, val hash: String)

/**
 * The hub's half: its clock, the new cursor, whether this is everything, the changes, the names of records it couldn't
 * apply, the photos it wants, and its sync identity ([hubId], which the cursor belongs to).
 */
data class ExchangeReply(
    val hubTime: Long,
    val cursor: Long,
    val full: Boolean,
    val records: List<SyncRecord>,
    val skipped: List<String>,
    val wantPhotos: List<PhotoWant>,
    val hubId: String,
    /**
     * Stage 4b, R1: the main PC offers this device its role. Sent only when true, and read as false when absent, so
     * readers and hubs before 4b are unaffected.
     */
    val handover: Boolean = false,
    /** The tables the hub understands; a reply without the list is from a build before Kroger ([SyncTables.BASE]). */
    val tables: Set<String> = SyncTables.KNOWN,
)

/** The exchange as JSON. Decoding trusts nothing: any malformed part throws IllegalArgumentException. */
object SyncWire {
    private const val MAX_KEY = 512
    private const val MAX_DEVICE = 64
    private const val MAX_HASH = 128
    private const val MAX_TABLES = 64

    fun encode(request: ExchangeRequest): String = buildJsonObject {
        put("deviceTime", request.deviceTime)
        put("cursor", request.cursor)
        put("full", request.full)
        put("hubId", request.hubId)
        put("records", records(request.records))
        put("tables", buildJsonArray { request.tables.sorted().forEach { add(JsonPrimitive(it)) } })
    }.toString()

    fun encode(reply: ExchangeReply): String = buildJsonObject {
        put("hubTime", reply.hubTime)
        put("cursor", reply.cursor)
        put("full", reply.full)
        put("records", records(reply.records))
        put("skipped", buildJsonArray { reply.skipped.forEach { add(JsonPrimitive(it)) } })
        put("wantPhotos", buildJsonArray { reply.wantPhotos.forEach { w -> add(buildJsonObject { put("uuid", w.uuid); put("hash", w.hash) }) } })
        put("hubId", reply.hubId)
        if (reply.handover) put("handover", true)
        put("tables", buildJsonArray { reply.tables.sorted().forEach { add(JsonPrimitive(it)) } })
    }.toString()

    fun encodeRecord(record: SyncRecord): String = record(record).toString()

    fun decodeRequest(text: String): ExchangeRequest {
        val o = parse(text)
        if ("hubId" !in o) bad("hubId is missing")
        val hubId = textOrNull(o, "hubId")?.also { if (it.isEmpty() || it.length > MAX_DEVICE) bad("bad hubId") }
        return ExchangeRequest(long(o, "deviceTime"), long(o, "cursor"), flag(o, "full"), records(o), hubId, tables(o))
    }

    fun decodeReply(text: String): ExchangeReply {
        val o = parse(text)
        return ExchangeReply(
            long(o, "hubTime"), long(o, "cursor"), flag(o, "full"), records(o), strings(o, "skipped"), photoWants(o),
            text(o, "hubId").takeIf { it.isNotEmpty() && it.length <= MAX_DEVICE } ?: bad("bad hubId"),
            if (o["handover"] == null || o["handover"] is JsonNull) false else flag(o, "handover"),
            tables(o),
        )
    }

    fun decodeRecord(text: String): SyncRecord = record(parse(text)) ?: bad("unknown table")

    /** A required text field of a record's data. */
    fun text(o: JsonObject, name: String): String = textOrNull(o, name) ?: bad("$name is missing")

    fun textOrNull(o: JsonObject, name: String): String? = when (val e = o[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (e.isString) e.content else bad("$name is not text")
        else -> bad("$name is not text")
    }

    /** A required whole number of a record's data, within Int. */
    fun int(o: JsonObject, name: String): Int =
        (o[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            ?: bad("$name is not a number")

    fun flag(o: JsonObject, name: String): Boolean =
        (o[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: bad("$name is not true or false")

    private fun long(o: JsonObject, name: String): Long =
        (o[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: bad("$name is not a number")

    private fun records(list: List<SyncRecord>) = buildJsonArray { list.forEach { add(record(it)) } }

    private fun record(r: SyncRecord): JsonObject = buildJsonObject {
        put("table", r.table)
        put("key", r.key)
        put("updatedAt", r.updatedAt)
        put("updatedBy", r.updatedBy)
        put("deleted", r.deleted)
        r.data?.let { put("data", it) }
    }

    private fun records(o: JsonObject): List<SyncRecord> =
        (o["records"] as? JsonArray ?: bad("records is not a list")).mapNotNull { record(it as? JsonObject ?: bad("a record is not an object")) }

    // The `tables` list, or BASE when a side before Kroger sent none.
    private fun tables(o: JsonObject): Set<String> {
        val list = o["tables"]?.takeUnless { it is JsonNull } ?: return SyncTables.BASE
        val names = (list as? JsonArray ?: bad("tables is not a list")).map { e ->
            (e as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= MAX_KEY }?.content ?: bad("tables holds a non-text")
        }
        if (names.size > MAX_TABLES) bad("too many tables")
        return names.toSet()
    }

    // A record of a table this build doesn't know (a later build's) is null: left out, never a failed exchange.
    private fun record(o: JsonObject): SyncRecord? {
        val table = text(o, "table")
        if (table !in SyncTables.ORDER) return null
        val key = text(o, "key").takeIf { it.isNotEmpty() && it.length <= MAX_KEY } ?: bad("bad key")
        val by = text(o, "updatedBy").takeIf { it.length <= MAX_DEVICE } ?: bad("bad device")
        val deleted = flag(o, "deleted")
        val data = o["data"]?.let { it as? JsonObject ?: bad("data is not an object") }
        if (deleted != (data == null)) bad("a record has data exactly when it isn't deleted")
        return SyncRecord(table, key, long(o, "updatedAt"), by, deleted, data)
    }

    private fun strings(o: JsonObject, name: String): List<String> =
        (o[name] as? JsonArray ?: bad("$name is not a list")).map { e: JsonElement ->
            (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("$name holds a non-text")
        }

    private fun photoWants(o: JsonObject): List<PhotoWant> =
        (o["wantPhotos"] as? JsonArray ?: bad("wantPhotos is not a list")).map { e ->
            val w = e as? JsonObject ?: bad("a wanted photo is not an object")
            PhotoWant(
                text(w, "uuid").takeIf { it.isNotEmpty() && it.length <= MAX_KEY } ?: bad("bad photo uuid"),
                text(w, "hash").takeIf { it.isNotEmpty() && it.length <= MAX_HASH } ?: bad("bad photo hash"),
            )
        }

    private fun parse(text: String): JsonObject = try {
        Json.parseToJsonElement(text) as? JsonObject ?: bad("not an object")
    } catch (e: SerializationException) {
        bad("not JSON")
    }

    private fun bad(why: String): Nothing = throw IllegalArgumentException("Bad sync message: $why.")
}
