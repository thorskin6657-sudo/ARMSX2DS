package com.armsx2.companion

import java.util.BitSet

/**
 * A Cheat-Engine-style value search over EE RAM, for finding the addresses a game profile needs.
 *
 * The workflow: First scan (a value you can see on screen, or "unknown"), change the value in the
 * game, Next scan (the new value, or just "changed" / "increased" / "decreased"), repeat until a
 * few addresses are left. The game should be PAUSED while a scan runs, otherwise values move under
 * the scan and good candidates get thrown away.
 *
 * Memory cost: one 32 MB snapshot plus a bit per candidate slot. Candidates are only ever the
 * addresses aligned to the value size, which is where the game keeps a value of that size.
 */
class RamScanner(
    /** Returns [len] bytes of EE RAM at [addr], or null when no game is running. */
    private val read: (addr: Int, len: Int) -> ByteArray?,
) {
    enum class Type(val label: String, val bytes: Int) {
        U8("u8", 1), U16("u16", 2), U32("u32", 4), F32("f32", 4)
    }

    enum class Mode(val label: String, val needsValue: Boolean) {
        EXACT("Exact value", true),
        UNKNOWN("Unknown start", false),
        CHANGED("Changed", false),
        UNCHANGED("Unchanged", false),
        INCREASED("Increased", false),
        DECREASED("Decreased", false),
    }

    data class Hit(val addr: Int, val value: Double)

    private var prev: ByteArray? = null
    private var candidates: BitSet? = null
    var type: Type = Type.U16
        private set

    /** Null before the first scan. */
    val count: Int? get() = candidates?.cardinality()
    val active: Boolean get() = candidates != null

    fun reset() {
        prev = null
        candidates = null
    }

    /** Start a new search. Returns false if there is no game memory to read. */
    fun first(type: Type, mode: Mode, value: Double?): Boolean {
        require(mode == Mode.EXACT || mode == Mode.UNKNOWN) { "first scan is exact or unknown" }
        if (mode == Mode.EXACT && value == null) return false
        val snap = snapshot() ?: return false
        this.type = type
        val slots = CompanionMemory.RAM_SIZE / type.bytes
        val set = BitSet(slots)
        if (mode == Mode.UNKNOWN) {
            set.set(0, slots)
        } else {
            for (i in 0 until slots) if (equalsValue(valueAt(snap, i, type), value!!, type)) set.set(i)
        }
        prev = snap
        candidates = set
        return true
    }

    /** Narrow the current candidates. Returns false if there is no search or no game memory. */
    fun next(mode: Mode, value: Double?): Boolean {
        val set = candidates ?: return false
        val before = prev ?: return false
        if (mode.needsValue && value == null) return false
        val now = snapshot() ?: return false
        var i = set.nextSetBit(0)
        while (i >= 0) {
            val cur = valueAt(now, i, type)
            val old = valueAt(before, i, type)
            val keep = when (mode) {
                Mode.EXACT -> equalsValue(cur, value!!, type)
                Mode.UNKNOWN -> true
                Mode.CHANGED -> !equalsValue(cur, old, type)
                Mode.UNCHANGED -> equalsValue(cur, old, type)
                Mode.INCREASED -> cur > old
                Mode.DECREASED -> cur < old
            }
            if (!keep) set.clear(i)
            i = set.nextSetBit(i + 1)
        }
        prev = now
        return true
    }

    /** The first [limit] surviving candidates with their value as of the latest scan. */
    fun hits(limit: Int): List<Hit> {
        val set = candidates ?: return emptyList()
        val cur = prev ?: return emptyList()
        val out = ArrayList<Hit>(limit)
        var i = set.nextSetBit(0)
        while (i >= 0 && out.size < limit) {
            out += Hit(i * type.bytes, valueAt(cur, i, type))
            i = set.nextSetBit(i + 1)
        }
        return out
    }

    private fun snapshot(): ByteArray? {
        val total = CompanionMemory.RAM_SIZE
        val out = ByteArray(total)
        val chunk = 4 * 1024 * 1024
        var at = 0
        while (at < total) {
            val part = read(at, minOf(chunk, total - at)) ?: return null
            System.arraycopy(part, 0, out, at, part.size)
            at += part.size
        }
        return out
    }

    companion object {
        fun valueAt(b: ByteArray, slot: Int, type: Type): Double {
            val o = slot * type.bytes
            return when (type) {
                Type.U8 -> (b[o].toInt() and 0xFF).toDouble()
                Type.U16 -> ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)).toDouble()
                Type.U32 -> (rawInt(b, o).toLong() and 0xFFFFFFFFL).toDouble()
                Type.F32 -> java.lang.Float.intBitsToFloat(rawInt(b, o)).toDouble()
            }
        }

        private fun rawInt(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        /** Integers must match exactly; floats match within a small tolerance (and NaN never matches). */
        fun equalsValue(a: Double, b: Double, type: Type): Boolean =
            if (type == Type.F32) !a.isNaN() && !b.isNaN() && Math.abs(a - b) <= 0.0005 * maxOf(1.0, Math.abs(b))
            else a == b
    }
}
