package com.armsx2.companion

import org.json.JSONArray
import org.json.JSONObject

/**
 * One game's bottom-screen companion, described as data. Nothing here is game code: a profile is a
 * JSON file that says WHERE in EE RAM to find the numbers and HOW to show them, so adding a game
 * means adding a file, not changing the emulator.
 *
 * Addresses are physical EE RAM addresses, the numbering cheat codes and RetroAchievements use.
 * Writing them with the usual prefixes is fine: 0x0012AB34, 0x2012AB34 and 0x8012AB34 all mean the
 * same byte (see [CompanionMemory.normalize]).
 */
data class CompanionProfile(
    val id: String,
    val title: String,
    /** Disc serials this profile applies to, e.g. "SLUS-20000". Upper-case, dash included. */
    val serials: List<String>,
    /** False until someone has checked every address against a running game. Shown on the panel. */
    val verified: Boolean,
    val pollMs: Long,
    val stats: List<StatDef>,
    val party: PartyDef?,
    val inventory: InventoryDef?,
    val map: MapDef?,
    val buttons: List<ButtonDef>,
    val notes: String,
    /** A stand-in for games with no profile: shows only the RAM search tool. Never parsed from JSON. */
    val searchOnly: Boolean = false,
) {
    val hasAnyPanel: Boolean get() = stats.isNotEmpty() || party != null || inventory != null || map != null

    companion object {
        fun searchOnly(serial: String, title: String) = CompanionProfile(
            id = "ram-search", title = title, serials = listOf(serial), verified = true, pollMs = 250L,
            stats = emptyList(), party = null, inventory = null, map = null, buttons = emptyList(),
            notes = "", searchOnly = true,
        )
    }
}

enum class ValueType(val bytes: Int) {
    U8(1), S8(1), U16(2), S16(2), U32(4), S32(4), F32(4);

    companion object {
        fun parse(raw: String?): ValueType? = when (raw?.lowercase()) {
            "u8" -> U8
            "s8", "i8" -> S8
            "u16" -> U16
            "s16", "i16" -> S16
            "u32" -> U32
            "s32", "i32" -> S32
            "f32", "float" -> F32
            else -> null
        }
    }
}

/** A number (or short text) at a fixed address. */
data class FieldRef(
    val addr: Int,
    val type: ValueType,
    /** Multiplier applied after reading, for fixed-point values. */
    val scale: Double = 1.0,
)

data class StatDef(
    val label: String,
    val value: FieldRef,
    /** When set, the stat is drawn as "value / max" with a bar. */
    val max: FieldRef?,
    /** Optional lookup so a raw value can show as a word: {"0":"Day","1":"Night"}. */
    val names: Map<Long, String>,
    val unit: String,
)

/** A text field inside a repeating record. */
data class TextDef(val offset: Int, val length: Int, val sjis: Boolean)

/** One column of a party row, read at [offset] bytes into each record. */
data class MemberField(
    val key: String,
    val label: String,
    val offset: Int,
    val type: ValueType?,
    val text: TextDef?,
    val maxOffset: Int?,
    val scale: Double,
    val names: Map<Long, String>,
)

data class PartyDef(
    val base: Int,
    val count: Int,
    val stride: Int,
    val fields: List<MemberField>,
    /** Skip records whose first field reads 0 / empty, so an empty slot is not drawn. */
    val skipEmpty: Boolean,
)

data class InventoryDef(
    val base: Int,
    val count: Int,
    val stride: Int,
    val idOffset: Int,
    val idType: ValueType,
    val qtyOffset: Int?,
    val qtyType: ValueType,
    /** Item id -> display name. Ids with no entry show as "#id". */
    val names: Map<Long, String>,
    /** An id that means "empty slot". */
    val emptyId: Long,
)

data class MapMarker(val label: String, val x: Double, val y: Double)

data class MapArea(
    val id: Long,
    val name: String,
    /** Asset path ("companion/maps/x.png") or an absolute/user-dir path. Optional. */
    val image: String?,
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
    val markers: List<MapMarker>,
)

data class MapDef(
    val x: FieldRef,
    val y: FieldRef,
    /** Heading in degrees, 0 = up, clockwise. Optional. */
    val heading: FieldRef?,
    /** Which area the player is in. Null = a single, always-current area. */
    val areaId: FieldRef?,
    val areas: List<MapArea>,
    /** Keep and draw the last few positions as a trail. */
    val trail: Boolean,
    /** Flip the vertical axis, for games whose Z/Y grows toward the top of the map. */
    val invertY: Boolean,
)

/** [latch] makes the button a toggle: tap once to hold it down, tap again to let go. For
 *  hold-to-use buttons (grip, run, aim) that are awkward to keep a finger on. */
data class ButtonDef(val label: String, val keys: List<String>, val latch: Boolean = false)

object CompanionProfileParser {

    /** Parse one profile file. Returns null (never throws) so one bad file cannot take the panel down. */
    fun parse(json: String): CompanionProfile? = runCatching { parseOrThrow(JSONObject(json)) }.getOrNull()

    fun parseOrThrow(o: JSONObject): CompanionProfile {
        val serials = o.optJSONArray("serials").strings().map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        require(serials.isNotEmpty()) { "profile needs at least one serial" }
        return CompanionProfile(
            id = o.optString("id", serials.first()),
            title = o.optString("title", serials.first()),
            serials = serials,
            verified = o.optBoolean("verified", false),
            pollMs = o.optLong("pollMs", 250L).coerceIn(100L, 2000L),
            stats = o.optJSONArray("stats").objects().map { stat(it) },
            party = o.optJSONObject("party")?.let { party(it) },
            inventory = o.optJSONObject("inventory")?.let { inventory(it) },
            map = o.optJSONObject("map")?.let { map(it) },
            buttons = o.optJSONArray("buttons").objects().map {
                ButtonDef(it.optString("label", "?"), it.optJSONArray("keys").strings(), it.optBoolean("latch", false))
            },
            notes = o.optString("notes", ""),
        )
    }

    private fun stat(o: JSONObject) = StatDef(
        label = o.optString("label", "?"),
        value = field(o),
        max = o.optJSONObject("max")?.let { field(it) },
        names = names(o.optJSONObject("names")),
        unit = o.optString("unit", ""),
    )

    private fun party(o: JSONObject) = PartyDef(
        base = addr(o.get("base")),
        count = o.getInt("count").coerceIn(1, 64),
        stride = num(o.get("stride")),
        fields = o.getJSONArray("fields").objects().map { f ->
            val textObj = f.optJSONObject("text")
            MemberField(
                key = f.optString("key", f.optString("label", "?")),
                label = f.optString("label", f.optString("key", "?")),
                offset = num(f.get("offset")),
                type = ValueType.parse(f.optString("type", "")),
                text = textObj?.let {
                    TextDef(num(f.get("offset")), it.getInt("length").coerceIn(1, 64), it.optString("charset", "ascii") == "sjis")
                },
                maxOffset = if (f.has("maxOffset")) num(f.get("maxOffset")) else null,
                scale = f.optDouble("scale", 1.0),
                names = names(f.optJSONObject("names")),
            )
        },
        skipEmpty = o.optBoolean("skipEmpty", true),
    )

    private fun inventory(o: JSONObject) = InventoryDef(
        base = addr(o.get("base")),
        count = o.getInt("count").coerceIn(1, 512),
        stride = if (o.has("stride")) num(o.get("stride")) else 4,
        idOffset = if (o.has("idOffset")) num(o.get("idOffset")) else 0,
        idType = ValueType.parse(o.optString("idType", "u16")) ?: ValueType.U16,
        qtyOffset = if (o.has("qtyOffset")) num(o.get("qtyOffset")) else null,
        qtyType = ValueType.parse(o.optString("qtyType", "u16")) ?: ValueType.U16,
        names = names(o.optJSONObject("names")),
        emptyId = o.optLong("emptyId", 0L),
    )

    private fun map(o: JSONObject): MapDef {
        val areas = o.optJSONArray("areas").objects().map { a ->
            val b = a.optJSONObject("bounds") ?: a
            MapArea(
                id = a.optLong("id", 0L),
                name = a.optString("name", ""),
                image = a.optString("image", "").ifEmpty { null },
                minX = b.optDouble("minX", 0.0), maxX = b.optDouble("maxX", 1.0),
                minY = b.optDouble("minY", 0.0), maxY = b.optDouble("maxY", 1.0),
                markers = a.optJSONArray("markers").objects().map {
                    MapMarker(it.optString("label", ""), it.optDouble("x"), it.optDouble("y"))
                },
            )
        }
        require(areas.isNotEmpty()) { "map needs at least one area" }
        return MapDef(
            x = field(o.getJSONObject("x")),
            y = field(o.getJSONObject("y")),
            heading = o.optJSONObject("heading")?.let { field(it) },
            areaId = o.optJSONObject("areaId")?.let { field(it) },
            areas = areas,
            trail = o.optBoolean("trail", true),
            invertY = o.optBoolean("invertY", false),
        )
    }

    private fun field(o: JSONObject) = FieldRef(
        addr = addr(o.get("addr")),
        type = ValueType.parse(o.optString("type", "u16")) ?: error("bad type '${o.optString("type")}'"),
        scale = o.optDouble("scale", 1.0),
    )

    private fun names(o: JSONObject?): Map<Long, String> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<Long, String>()
        o.keys().forEach { k -> k.toLongOrNull()?.let { out[it] = o.optString(k) } }
        return out
    }

    /** Accepts 123, "123", "0x7B" or "7B"-less hex with a 0x prefix; always returns a plain Int. */
    internal fun num(v: Any): Int = when (v) {
        is Number -> v.toLong().toInt()
        is String -> {
            val t = v.trim()
            if (t.startsWith("0x", true)) t.substring(2).toLong(16).toInt() else t.toLong().toInt()
        }
        else -> error("not a number: $v")
    }

    internal fun addr(v: Any): Int = CompanionMemory.normalize(num(v))

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
