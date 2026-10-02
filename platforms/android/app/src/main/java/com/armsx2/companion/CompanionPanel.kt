package com.armsx2.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.armsx2.ui.touch.TouchButtonId
import kr.co.iefriends.pcsx2.NativeApp
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Colours handed over by the host panel so the companion matches the app's live theme. */
data class CompanionTheme(
    val tile: Int,
    val text: Int,
    val dim: Int,
    val accent: Int,
    val border: Int,
)

/**
 * The bottom-screen companion for one game. Built from plain Views for the same reason the rest of
 * the second-screen panel is: it lives in a Presentation window, where a ComposeView needs lifecycle
 * owners attached by hand and fails at inflate time on hardware nobody tests on.
 *
 * Layout: a tab row (only the tabs the profile actually has), the active tab's content, and a row of
 * touch buttons along the bottom that stays visible on every tab.
 */
class CompanionPanel(
    private val context: Context,
    private val profile: CompanionProfile,
    private val theme: CompanionTheme,
    /** Adds the RAM search tab, for finding the addresses a profile needs. */
    private val devTools: Boolean = false,
    private val serial: () -> String = { "" },
    private val isPaused: () -> Boolean = { false },
    private val onTogglePause: () -> Unit = {},
    /** Called when the user taps the switch-to-tiles button. */
    private val onShowTiles: () -> Unit,
) : LinearLayout(context) {

    private enum class Tab(val label: String) { STATS("Stats"), PARTY("Party"), ITEMS("Items"), MAP("Map"), SEARCH("Search") }

    private val dp = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val ranges = CompanionMemory.rangesFor(profile)
    private val tabs = buildList {
        if (profile.stats.isNotEmpty()) add(Tab.STATS)
        if (profile.party != null) add(Tab.PARTY)
        if (profile.inventory != null) add(Tab.ITEMS)
        if (profile.map != null) add(Tab.MAP)
        if (devTools) add(Tab.SEARCH)
    }
    private var current: Tab = tabs.firstOrNull() ?: Tab.STATS
    private val tabViews = HashMap<Tab, TextView>()
    private val content = FrameLayout(context)
    private val statsBox = LinearLayout(context).apply { orientation = VERTICAL }
    private val partyBox = LinearLayout(context).apply { orientation = VERTICAL }
    private val itemsGrid = GridLayout(context).apply { columnCount = 3 }
    private val mapView = MapView(context, theme)
    private val search: RamSearchView? =
        if (devTools) RamSearchView(context, theme, serial, isPaused, onTogglePause) else null
    private val pages = LinkedHashMap<Tab, View>()
    /** Releases every latched button; run when the panel stops so nothing stays held down.
     *  Declared before `init`, which builds the buttons and registers into it. */
    private val latchReleasers = ArrayList<() -> Unit>()
    private val statusLine = TextView(context)
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            handler.postDelayed(this, profile.pollMs)
        }
    }

    init {
        orientation = VERTICAL

        // ---- Header: title, unverified flag, tab row, tiles switch -------------------------------
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, px(8))
        }
        header.addView(
            TextView(context).apply {
                text = profile.title
                setTextColor(theme.text)
                textSize = 15f
                setSingleLine(true)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        if (!profile.verified) {
            header.addView(
                chip("UNVERIFIED", theme.dim).apply { setPadding(px(8), px(2), px(8), px(2)) },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = px(8) },
            )
        }
        header.addView(
            chip("Tiles", theme.dim).apply {
                setPadding(px(10), px(4), px(10), px(4))
                setOnClickListener { onShowTiles() }
            },
        )
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        if (tabs.size > 1) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            tabs.forEach { t ->
                val v = chip(t.label, theme.dim).apply {
                    gravity = Gravity.CENTER
                    setPadding(px(10), px(8), px(10), px(8))
                    setOnClickListener { select(t) }
                }
                tabViews[t] = v
                row.addView(v, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = px(6) })
            }
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8) })
        }

        // ---- Content --------------------------------------------------------------------------
        pages[Tab.STATS] = scroll(statsBox)
        pages[Tab.PARTY] = scroll(partyBox)
        pages[Tab.ITEMS] = scroll(itemsGrid)
        pages[Tab.MAP] = mapView
        search?.let { pages[Tab.SEARCH] = scroll(it) }
        pages.values.forEach {
            content.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        statusLine.apply { setTextColor(theme.dim); textSize = 11f; setPadding(0, px(6), 0, px(6)) }
        addView(statusLine, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ---- Touch buttons ----------------------------------------------------------------------
        if (profile.buttons.isNotEmpty()) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            profile.buttons.forEach { def ->
                val codes = def.keys.mapNotNull { k ->
                    runCatching { TouchButtonId.valueOf(k.trim().uppercase()).keycode }.getOrNull()?.takeIf { it != 0 }
                }
                if (codes.isEmpty()) return@forEach
                row.addView(padButton(def.label, codes, def.latch), LayoutParams(0, px(56), 1f).apply { setMargins(px(3), 0, px(3), 0) })
            }
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        select(current)
    }

    // ---- Lifecycle ----------------------------------------------------------------------------
    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        latchReleasers.forEach { it() }
    }

    // ---- Tabs ---------------------------------------------------------------------------------
    private fun select(t: Tab) {
        current = t
        tabViews.forEach { (tab, v) ->
            val on = tab == t
            v.setTextColor(if (on) theme.text else theme.dim)
            v.background = round(if (on) theme.accent and 0x00FFFFFF or 0x55000000 else theme.tile, theme.border)
        }
        pages.forEach { (tab, view) -> view.visibility = if (tab == t) VISIBLE else GONE }
        refresh()
    }

    // ---- Refresh ------------------------------------------------------------------------------
    private fun refresh() {
        // The search tab does its own reading (and works with no profile ranges at all).
        if (current == Tab.SEARCH) {
            statusLine.text = ""
            search?.tick()
            return
        }
        val snap = CompanionMemory.snapshot(ranges)
        if (!snap.valid) {
            statusLine.text = "Waiting for game memory…"
            return
        }
        statusLine.text = ""
        when (current) {
            Tab.STATS -> drawStats(snap)
            Tab.PARTY -> drawParty(snap)
            Tab.ITEMS -> drawItems(snap)
            Tab.MAP -> drawMap(snap)
        }
    }

    private fun drawStats(snap: MemorySnapshot) {
        statsBox.removeAllViews()
        profile.stats.forEach { s ->
            val v = snap.readScaled(s.value)
            val m = s.max?.let { snap.readScaled(it) }
            val shown = when {
                v == null -> "—"
                s.names.isNotEmpty() -> s.names[v.toLong()] ?: v.toLong().toString()
                m != null -> "${fmt(v)} / ${fmt(m)}"
                else -> fmt(v)
            } + if (s.unit.isNotEmpty() && v != null) " ${s.unit}" else ""
            statsBox.addView(row(s.label, shown, if (v != null && m != null && m > 0) (v / m).toFloat() else null))
        }
    }

    private fun drawParty(snap: MemorySnapshot) {
        val def = profile.party ?: return
        partyBox.removeAllViews()
        for (i in 0 until def.count) {
            val recordBase = def.base + i * def.stride
            val first = def.fields.firstOrNull()
            if (def.skipEmpty && first != null) {
                val probe = if (first.text != null) snap.readText(recordBase + first.offset, first.text.length, first.text.sjis).orEmpty()
                else snap.read(recordBase + first.offset, first.type ?: ValueType.U8)?.toLong()?.takeIf { it != 0L }?.toString().orEmpty()
                if (probe.isEmpty()) continue
            }
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                background = round(theme.tile, theme.border)
                setPadding(px(12), px(8), px(12), px(8))
            }
            def.fields.forEachIndexed { idx, f ->
                val at = recordBase + f.offset
                val shown: String
                var frac: Float? = null
                if (f.text != null) {
                    shown = snap.readText(at, f.text.length, f.text.sjis) ?: "—"
                } else {
                    val v = f.type?.let { snap.read(at, it) }?.times(f.scale)
                    val mx = if (f.maxOffset != null && f.type != null) snap.read(recordBase + f.maxOffset, f.type)?.times(f.scale) else null
                    shown = when {
                        v == null -> "—"
                        f.names.isNotEmpty() -> f.names[v.toLong()] ?: v.toLong().toString()
                        mx != null -> "${fmt(v)} / ${fmt(mx)}"
                        else -> fmt(v)
                    }
                    if (v != null && mx != null && mx > 0) frac = (v / mx).toFloat()
                }
                // First field is the member's name, drawn as a heading rather than a label/value row.
                if (idx == 0) {
                    card.addView(TextView(context).apply { text = shown; setTextColor(theme.text); textSize = 15f })
                } else {
                    card.addView(row(f.label, shown, frac))
                }
            }
            partyBox.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8) })
        }
    }

    private fun drawItems(snap: MemorySnapshot) {
        val def = profile.inventory ?: return
        itemsGrid.removeAllViews()
        for (i in 0 until def.count) {
            val at = def.base + i * def.stride
            val id = snap.read(at + def.idOffset, def.idType)?.toLong() ?: continue
            if (id == def.emptyId) continue
            val qty = def.qtyOffset?.let { snap.read(at + it, def.qtyType)?.toLong() }
            if (qty != null && qty <= 0L) continue
            val name = def.names[id] ?: "#$id"
            val cell = chip(if (qty != null) "$name ×$qty" else name, theme.text).apply { setPadding(px(10), px(8), px(10), px(8)) }
            itemsGrid.addView(
                cell,
                GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    setMargins(px(3), px(3), px(3), px(3))
                },
            )
        }
    }

    private fun drawMap(snap: MemorySnapshot) {
        val def = profile.map ?: return
        val x = snap.readScaled(def.x) ?: return
        val y = snap.readScaled(def.y) ?: return
        val heading = def.heading?.let { snap.readScaled(it) }
        val areaKey = def.areaId?.let { snap.read(it.addr, it.type)?.toLong() }
        val area = def.areas.firstOrNull { areaKey == null || it.id == areaKey } ?: def.areas.first()
        mapView.show(area, x, y, heading, def.trail, def.invertY)
    }

    // ---- Small view helpers ----------------------------------------------------------------------
    private fun row(label: String, value: String, frac: Float?): View {
        val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, px(4), 0, px(4)) }
        val line = LinearLayout(context).apply { orientation = HORIZONTAL }
        line.addView(TextView(context).apply { text = label; setTextColor(theme.dim); textSize = 13f }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        line.addView(TextView(context).apply { text = value; setTextColor(theme.text); textSize = 14f; gravity = Gravity.END })
        box.addView(line)
        if (frac != null) box.addView(Bar(context, frac.coerceIn(0f, 1f), theme), LayoutParams(LayoutParams.MATCH_PARENT, px(6)).apply { topMargin = px(3) })
        return box
    }

    private fun chip(label: String, color: Int) = TextView(context).apply {
        text = label
        setTextColor(color)
        textSize = 12f
        background = round(theme.tile, theme.border)
    }

    private fun round(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp * 10
        setStroke(max(1, dp.toInt()), stroke)
    }

    private fun scroll(inner: View) = ScrollView(context).apply {
        isFillViewport = true
        addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun padButton(label: String, codes: List<Int>, latch: Boolean = false) = Button(context).apply {
        text = label
        isAllCaps = false
        setTextColor(theme.text)
        background = round(theme.tile, theme.border)
        if (latch) {
            // Tap to hold, tap again to let go. Lit with the accent colour while held.
            var held = false
            fun set(on: Boolean) {
                held = on
                codes.forEach { runCatching { NativeApp.setPadButton(it, 0, on) } }
                background = round(if (on) (theme.accent and 0x00FFFFFF) or 0x66000000 else theme.tile, theme.border)
            }
            latchReleasers.add { if (held) set(false) }
            setOnClickListener { set(!held) }
        } else {
            // Press on touch-down and release on up/cancel, exactly like the on-screen controls, so a
            // held button is held and a dragged-off finger cannot leave it stuck down.
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        codes.forEach { runCatching { NativeApp.setPadButton(it, 0, true) } }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        codes.forEach { runCatching { NativeApp.setPadButton(it, 0, false) } }
                        if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    }
                }
                true
            }
        }
    }

    private fun px(v: Int) = (v * dp).toInt()

    private fun fmt(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else String.format("%.2f", v)

    /** A thin filled bar for current/max values. */
    private class Bar(context: Context, private val frac: Float, private val theme: CompanionTheme) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = height / 2f
            paint.color = theme.border
            c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, paint)
            paint.color = theme.accent
            c.drawRoundRect(RectF(0f, 0f, width * frac, height.toFloat()), r, r, paint)
        }
    }
}

/** The live map: an optional background image, markers, a fading trail, and the player. */
private class MapView(context: Context, private val theme: CompanionTheme) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trail = ArrayDeque<Pair<Double, Double>>()
    private var area: MapArea? = null
    private var bitmap: Bitmap? = null
    private var bitmapFor: String? = null
    private var px = 0.0
    private var py = 0.0
    private var heading: Double? = null
    private var showTrail = true
    private var invertY = false
    private val dp = context.resources.displayMetrics.density

    fun show(a: MapArea, x: Double, y: Double, h: Double?, trailOn: Boolean, invert: Boolean) {
        if (area?.id != a.id) trail.clear()
        area = a
        px = x; py = y; heading = h; showTrail = trailOn; invertY = invert
        if (bitmapFor != a.image) {
            bitmapFor = a.image
            bitmap = a.image?.let { load(it) }
        }
        val last = trail.lastOrNull()
        if (showTrail && (last == null || last.first != x || last.second != y)) {
            trail.addLast(x to y)
            while (trail.size > 60) trail.removeFirst()
        }
        invalidate()
    }

    private fun load(path: String): Bitmap? = runCatching {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }
    }.getOrNull() ?: runCatching {
        val f = File(context.getExternalFilesDir(null) ?: context.filesDir, "companion/$path")
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }.getOrNull()

    private fun toScreen(x: Double, y: Double, box: RectF, a: MapArea): Pair<Float, Float> {
        val nx = ((x - a.minX) / (a.maxX - a.minX)).coerceIn(0.0, 1.0)
        var ny = ((y - a.minY) / (a.maxY - a.minY)).coerceIn(0.0, 1.0)
        if (invertY) ny = 1.0 - ny
        return (box.left + nx * box.width()).toFloat() to (box.top + ny * box.height()).toFloat()
    }

    override fun onDraw(c: Canvas) {
        val a = area ?: return
        // Fit the map into the view, keeping the image's shape when there is one.
        val bw = bitmap?.width?.toFloat() ?: 1f
        val bh = bitmap?.height?.toFloat() ?: 1f
        val scale = if (bitmap != null) min(width / bw, height / bh) else 1f
        val w = if (bitmap != null) bw * scale else width.toFloat()
        val h = if (bitmap != null) bh * scale else height.toFloat()
        val box = RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)

        paint.style = Paint.Style.FILL
        paint.color = theme.tile
        c.drawRoundRect(box, dp * 10, dp * 10, paint)
        bitmap?.let { c.drawBitmap(it, null, box, null) }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp
        paint.color = theme.border
        c.drawRoundRect(box, dp * 10, dp * 10, paint)

        paint.style = Paint.Style.FILL
        paint.textSize = 11f * dp
        a.markers.forEach { m ->
            val (sx, sy) = toScreen(m.x, m.y, box, a)
            paint.color = theme.dim
            c.drawCircle(sx, sy, 3f * dp, paint)
            if (m.label.isNotEmpty()) c.drawText(m.label, sx + 6f * dp, sy + 4f * dp, paint)
        }

        trail.forEachIndexed { i, p ->
            val (sx, sy) = toScreen(p.first, p.second, box, a)
            paint.color = (theme.accent and 0x00FFFFFF) or ((40 + 150 * i / max(1, trail.size)) shl 24)
            c.drawCircle(sx, sy, 2.5f * dp, paint)
        }

        val (sx, sy) = toScreen(px, py, box, a)
        paint.color = Color.WHITE
        c.drawCircle(sx, sy, 7f * dp, paint)
        paint.color = theme.accent
        c.drawCircle(sx, sy, 5f * dp, paint)
        heading?.let { deg ->
            val rad = Math.toRadians(deg)
            paint.color = theme.accent
            paint.strokeWidth = 2.5f * dp
            c.drawLine(sx, sy, sx + (sin(rad) * 14 * dp).toFloat(), sy - (cos(rad) * 14 * dp).toFloat(), paint)
        }
        if (a.name.isNotEmpty()) {
            paint.color = theme.text
            paint.textSize = 13f * dp
            c.drawText(a.name, box.left + 10f * dp, box.top + 20f * dp, paint)
        }
    }
}
