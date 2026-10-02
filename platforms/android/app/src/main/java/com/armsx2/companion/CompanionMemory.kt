package com.armsx2.companion

import kr.co.iefriends.pcsx2.NativeApp
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A frozen copy of the ranges one panel refresh needs. The refresh asks the native side for every
 * range in a SINGLE call, then decodes from this copy, so a panel with forty fields costs one JNI
 * crossing, not forty.
 */
class MemorySnapshot(
    private val ranges: List<IntArray>,      // [addr, len]
    private val offsets: IntArray,           // start of each range inside [data]
    private val data: ByteArray,
) {
    /** True when the native read actually produced bytes (a VM was running). */
    val valid: Boolean get() = data.isNotEmpty()

    private fun locate(addr: Int, size: Int): Int {
        for (i in ranges.indices) {
            val a = ranges[i][0]
            val l = ranges[i][1]
            if (addr >= a && addr + size <= a + l) return offsets[i] + (addr - a)
        }
        return -1
    }

    fun raw(addr: Int, size: Int): ByteArray? {
        val at = locate(addr, size)
        if (at < 0 || at + size > data.size) return null
        return data.copyOfRange(at, at + size)
    }

    /** Read a typed value, or null if the address was never requested. */
    fun read(addr: Int, type: ValueType): Double? {
        val at = locate(addr, type.bytes)
        if (at < 0 || at + type.bytes > data.size) return null
        val b = ByteBuffer.wrap(data, at, type.bytes).order(ByteOrder.LITTLE_ENDIAN)
        return when (type) {
            ValueType.U8 -> (b.get().toInt() and 0xFF).toDouble()
            ValueType.S8 -> b.get().toDouble()
            ValueType.U16 -> (b.short.toInt() and 0xFFFF).toDouble()
            ValueType.S16 -> b.short.toDouble()
            ValueType.U32 -> (b.int.toLong() and 0xFFFFFFFFL).toDouble()
            ValueType.S32 -> b.int.toDouble()
            ValueType.F32 -> b.float.toDouble()
        }
    }

    fun readScaled(f: FieldRef): Double? = read(f.addr, f.type)?.times(f.scale)

    fun readText(addr: Int, length: Int, sjis: Boolean): String? {
        val bytes = raw(addr, length) ?: return null
        val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
        val cs = if (sjis) charset("Shift_JIS") else Charsets.ISO_8859_1
        return String(bytes, 0, end, cs).trim()
    }
}

object CompanionMemory {
    /** PS2 EE physical RAM is 32 MB; masking drops the cheat-code / KSEG prefix bits. */
    private const val ADDRESS_MASK = 0x01FFFFFF

    /** 0x2012AB34 (cheat style), 0x8012AB34 (KSEG0) and 0x0012AB34 all become 0x0012AB34. */
    fun normalize(addr: Int): Int = addr and ADDRESS_MASK

    /** Every range the profile reads, so one batched call can fetch them all. */
    fun rangesFor(p: CompanionProfile): List<IntArray> {
        val r = ArrayList<IntArray>()
        p.stats.forEach { s ->
            r += intArrayOf(s.value.addr, s.value.type.bytes)
            s.max?.let { r += intArrayOf(it.addr, it.type.bytes) }
        }
        p.party?.let { r += intArrayOf(it.base, it.count * it.stride) }
        p.inventory?.let { r += intArrayOf(it.base, it.count * it.stride) }
        p.map?.let { m ->
            r += intArrayOf(m.x.addr, m.x.type.bytes)
            r += intArrayOf(m.y.addr, m.y.type.bytes)
            m.heading?.let { r += intArrayOf(it.addr, it.type.bytes) }
            m.areaId?.let { r += intArrayOf(it.addr, it.type.bytes) }
        }
        return r
    }

    /** Size of EE main RAM, which is all the reader exposes. */
    const val RAM_SIZE = 0x02000000

    /** One contiguous range, or null if the read produced nothing (no game running). */
    fun readRange(addr: Int, len: Int): ByteArray? {
        val data = runCatching {
            NativeApp.readEeMemory(intArrayOf(addr), intArrayOf(len))
        }.getOrNull() ?: return null
        return if (data.size == len) data else null
    }

    /** One JNI call for the whole profile. Never throws; an unreadable game yields an invalid snapshot. */
    fun snapshot(ranges: List<IntArray>): MemorySnapshot {
        if (ranges.isEmpty()) return MemorySnapshot(ranges, IntArray(0), ByteArray(0))
        val offsets = IntArray(ranges.size)
        var pos = 0
        ranges.forEachIndexed { i, r -> offsets[i] = pos; pos += r[1] }
        val data = runCatching {
            NativeApp.readEeMemory(IntArray(ranges.size) { ranges[it][0] }, IntArray(ranges.size) { ranges[it][1] })
        }.getOrNull() ?: ByteArray(0)
        return MemorySnapshot(ranges, offsets, data)
    }
}
