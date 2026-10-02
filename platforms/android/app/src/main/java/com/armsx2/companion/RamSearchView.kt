package com.armsx2.companion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/**
 * The RAM search tool: find the addresses a game profile needs, on the bottom screen, while the
 * game runs on the top one.
 *
 * Typical use, finding health: pause, pick the type (u16 is usual for health), First scan with the
 * number shown on screen; take damage; Next scan with the new number; repeat until a handful of
 * addresses are left; tap Watch on the ones that track the value; Save + copy.
 *
 * There is no text box on purpose. The system keyboard does not appear for a window on a second
 * display on many devices, so numbers go in through the keypad below.
 */
class RamSearchView(
    context: Context,
    private val theme: CompanionTheme,
    private val serial: () -> String,
    private val isPaused: () -> Boolean,
    private val onTogglePause: () -> Unit,
) : LinearLayout(context) {

    private data class Watch(val addr: Int, val type: RamScanner.Type)

    private val dp = context.resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())
    private val scanner = RamScanner { a, l -> CompanionMemory.readRange(a, l) }

    private var type = RamScanner.Type.U16
    private var mode = RamScanner.Mode.EXACT
    private var entry = ""
    private var busy = false
    private var hits: List<RamScanner.Hit> = emptyList()
    private val watches = ArrayList<Watch>()

    private val typeBtn = button("u16") { cycleType() }
    private val modeBtn = button("Exact value") { cycleMode() }
    private val valueText = TextView(context)
    private val scanBtn = button("First scan") { runScan() }
    private val pauseBtn = button("Pause") { onTogglePause(); tick() }
    private val status = TextView(context)
    private val hitsBox = LinearLayout(context).apply { orientation = VERTICAL }
    private val watchBox = LinearLayout(context).apply { orientation = VERTICAL }

    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, px(8))

        // Type / mode / value
        val top = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(typeBtn, LayoutParams(0, px(48), 1f).apply { rightMargin = px(6) })
        top.addView(modeBtn, LayoutParams(0, px(48), 2f).apply { rightMargin = px(6) })
        valueText.apply {
            setTextColor(theme.text); textSize = 18f; gravity = Gravity.CENTER
            background = round(theme.tile)
        }
        top.addView(valueText, LayoutParams(0, px(48), 2f))
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Keypad
        val keys = listOf("7", "8", "9", "⌫", "4", "5", "6", "C", "1", "2", "3", ".", "0", "-")
        val pad = GridLayout(context).apply { columnCount = 4 }
        keys.forEach { k ->
            pad.addView(
                button(k) { press(k) },
                GridLayout.LayoutParams().apply {
                    width = 0; height = px(46)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    setMargins(px(3), px(3), px(3), px(3))
                },
            )
        }
        addView(pad, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })

        // Actions
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
        actions.addView(scanBtn, LayoutParams(0, px(48), 2f).apply { rightMargin = px(6) })
        actions.addView(button("Reset") { reset() }, LayoutParams(0, px(48), 1f).apply { rightMargin = px(6) })
        actions.addView(pauseBtn, LayoutParams(0, px(48), 1f))
        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })

        status.apply { setTextColor(theme.dim); textSize = 12f; setPadding(0, px(8), 0, px(4)) }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(label("Results"), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(hitsBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val wHead = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        wHead.addView(label("Watching"), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        wHead.addView(button("Save + copy") { save() }, LayoutParams(LayoutParams.WRAP_CONTENT, px(40)))
        addView(wHead, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })
        addView(watchBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        render()
        say("Pause the game, enter the number you can see, then First scan.")
    }

    // ---- Controls -----------------------------------------------------------------------------
    private fun cycleType() {
        if (scanner.active) { say("Reset to change the value type."); return }
        val all = RamScanner.Type.values()
        type = all[(type.ordinal + 1) % all.size]
        render()
    }

    private fun cycleMode() {
        val order = if (scanner.active)
            listOf(RamScanner.Mode.EXACT, RamScanner.Mode.CHANGED, RamScanner.Mode.UNCHANGED, RamScanner.Mode.INCREASED, RamScanner.Mode.DECREASED)
        else listOf(RamScanner.Mode.EXACT, RamScanner.Mode.UNKNOWN)
        mode = order[(order.indexOf(mode) + 1) % order.size]
        render()
    }

    private fun press(k: String) {
        when (k) {
            "⌫" -> entry = entry.dropLast(1)
            "C" -> entry = ""
            "." -> if ('.' !in entry) entry += if (entry.isEmpty() || entry == "-") "0." else "."
            "-" -> entry = if (entry.startsWith("-")) entry.drop(1) else "-$entry"
            else -> if (entry.length < 12) entry += k
        }
        render()
    }

    private fun reset() {
        scanner.reset()
        hits = emptyList()
        mode = RamScanner.Mode.EXACT
        say("Search cleared. Watched addresses are kept.")
        render()
    }

    private fun runScan() {
        if (busy) return
        val value = entry.toDoubleOrNull()
        if (mode.needsValue && value == null) { say("Enter a number first."); return }
        if (!isPaused()) say("Tip: pause the game first so values hold still during the scan.")
        busy = true
        say("Scanning 32 MB…")
        render()
        val t = type
        val m = mode
        Thread {
            val ok = if (!scanner.active) {
                scanner.first(t, if (m == RamScanner.Mode.UNKNOWN) RamScanner.Mode.UNKNOWN else RamScanner.Mode.EXACT, value)
            } else {
                scanner.next(m, value)
            }
            val found = if (ok) scanner.hits(LIST_LIMIT) else emptyList()
            ui.post {
                busy = false
                hits = found
                if (!ok) say("No game memory to read. Start a game first.")
                else {
                    val n = scanner.count ?: 0
                    say("$n address${if (n == 1) "" else "es"} left." + if (n > LIST_LIMIT) " Showing the first $LIST_LIMIT. Change the value and Next scan to narrow." else "")
                    // After a first scan the useful follow-ups are the "next" modes.
                    mode = if (mode == RamScanner.Mode.UNKNOWN) RamScanner.Mode.CHANGED else RamScanner.Mode.EXACT
                }
                render()
            }
        }.start()
    }

    // ---- Live refresh (called by the panel's tick while this tab is showing) ----------------------
    fun tick() {
        pauseBtn.text = if (isPaused()) "Resume" else "Pause"
        if (hits.isEmpty() && watches.isEmpty()) return
        val shownHits = hits.take(LIST_LIMIT)
        val ranges = ArrayList<IntArray>()
        shownHits.forEach { ranges += intArrayOf(it.addr, scanner.type.bytes) }
        watches.forEach { ranges += intArrayOf(it.addr, it.type.bytes) }
        val snap = CompanionMemory.snapshot(ranges)
        if (!snap.valid) return
        rebuildLists(snap, shownHits)
    }

    private fun rebuildLists(snap: MemorySnapshot?, shownHits: List<RamScanner.Hit>) {
        hitsBox.removeAllViews()
        shownHits.forEach { h ->
            val v = snap?.read(h.addr, scanner.type.toValueType()) ?: h.value
            val already = watches.any { it.addr == h.addr && it.type == scanner.type }
            hitsBox.addView(
                row("0x%08X".format(h.addr), fmt(v, scanner.type), if (already) "Watching" else "Watch", !already) {
                    watches += Watch(h.addr, scanner.type); tick(); render()
                },
            )
        }
        watchBox.removeAllViews()
        watches.toList().forEach { w ->
            val v = snap?.read(w.addr, w.type.toValueType())
            watchBox.addView(
                row("0x%08X  ${w.type.label}".format(w.addr), v?.let { fmt(it, w.type) } ?: "—", "Remove", true) {
                    watches.remove(w); tick(); render()
                },
            )
        }
    }

    // ---- Save ------------------------------------------------------------------------------------
    private fun save() {
        if (watches.isEmpty()) { say("Nothing to save yet. Tap Watch on a result first."); return }
        val ranges = watches.map { intArrayOf(it.addr, it.type.bytes) }
        val snap = CompanionMemory.snapshot(ranges)
        val arr = JSONArray()
        watches.forEach { w ->
            arr.put(
                JSONObject()
                    .put("addr", "0x%08X".format(w.addr))
                    .put("type", w.type.label)
                    .put("value", if (snap.valid) snap.read(w.addr, w.type.toValueType()) ?: JSONObject.NULL else JSONObject.NULL),
            )
        }
        val json = JSONObject().put("serial", serial()).put("watch", arr).toString(2)
        val where = runCatching {
            val f = File(context.getExternalFilesDir(null) ?: context.filesDir, "companion/found/${serial().ifBlank { "unknown" }}.json")
            f.parentFile?.mkdirs()
            f.writeText(json)
            f.absolutePath
        }.getOrNull()
        runCatching {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("ARMSX2 RAM watch", json))
        }
        say("Copied to the clipboard" + if (where != null) " and saved to $where" else ".")
    }

    // ---- Rendering helpers ------------------------------------------------------------------------
    private fun render() {
        typeBtn.text = type.label
        modeBtn.text = mode.label
        valueText.text = if (entry.isEmpty()) (if (mode.needsValue) "enter a value" else "—") else entry
        valueText.setTextColor(if (entry.isEmpty()) theme.dim else theme.text)
        scanBtn.text = if (scanner.active) "Next scan" else "First scan"
        scanBtn.isEnabled = !busy
        scanBtn.alpha = if (busy) 0.5f else 1f
        pauseBtn.text = if (isPaused()) "Resume" else "Pause"
        rebuildLists(null, hits.take(LIST_LIMIT))
    }

    private fun say(s: String) { status.text = s }

    private fun label(s: String) = TextView(context).apply { text = s; setTextColor(theme.dim); textSize = 12f; setPadding(0, px(6), 0, px(4)) }

    private fun row(left: String, mid: String, action: String, enabled: Boolean, onClick: () -> Unit): View {
        val r = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = round(theme.tile); setPadding(px(10), px(4), px(6), px(4))
        }
        r.addView(TextView(context).apply { text = left; setTextColor(theme.text); textSize = 13f; typeface = android.graphics.Typeface.MONOSPACE }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 3f))
        r.addView(TextView(context).apply { text = mid; setTextColor(theme.accent); textSize = 14f; gravity = Gravity.END }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 2f).apply { rightMargin = px(8) })
        r.addView(button(action, onClick).apply { isEnabled = enabled; alpha = if (enabled) 1f else 0.5f; textSize = 12f }, LayoutParams(px(88), px(38)))
        r.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(4) }
        return r
    }

    private fun button(text: String, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        isAllCaps = false
        setTextColor(theme.text)
        textSize = 14f
        background = round(theme.tile)
        setPadding(px(4), 0, px(4), 0)
        setOnClickListener { onClick() }
    }

    private fun round(fill: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp * 10; setStroke(max(1, dp.toInt()), theme.border)
    }

    private fun fmt(v: Double, t: RamScanner.Type): String =
        if (t == RamScanner.Type.F32) String.format("%.3f", v) else v.toLong().toString()

    private fun px(v: Int) = (v * dp).toInt()

    private fun RamScanner.Type.toValueType() = when (this) {
        RamScanner.Type.U8 -> ValueType.U8
        RamScanner.Type.U16 -> ValueType.U16
        RamScanner.Type.U32 -> ValueType.U32
        RamScanner.Type.F32 -> ValueType.F32
    }

    private companion object { const val LIST_LIMIT = 20 }
}
