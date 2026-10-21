package com.vedav.launcher

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private class Entry(val label: String, val pkg: String, val cls: String, val info: ResolveInfo)

    private companion object {
        const val COLS = 6
        const val NORMAL = Color.WHITE
        const val MOVING = 0xFFFFC107.toInt()
    }

    private val entries = mutableListOf<Entry>()
    private lateinit var grid: GridView
    private var movingPos = -1
    private var swallowUp = false

    private lateinit var stats: TextView
    private var sampler: ScheduledExecutorService? = null
    private var prevBusy = -1L
    private var prevTotal = -1L
    private var dumpsysFailed = false

    private val prefs by lazy { getSharedPreferences("layout", MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun selector(color: Int) = GradientDrawable().apply {
        setColor(0x33FFFFFF)
        cornerRadius = dp(12).toFloat()
        setStroke(dp(2), color)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Backdrop()
            setPadding(dp(48), dp(32), dp(48), dp(16))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextClock(this).apply {
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            setTextColor(Color.WHITE)
            textSize = 36f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stats = TextView(this).apply {
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 16f
            gravity = Gravity.END
        }
        top.addView(stats)
        root.addView(top)

        grid = GridView(this).apply {
            numColumns = COLS
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(16)
            horizontalSpacing = dp(16)
            clipToPadding = false
            setPadding(0, dp(24), 0, dp(24))
            selector = selector(NORMAL)
            adapter = AppAdapter()
            setOnItemClickListener { _, _, pos, _ -> launch(entries[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> menu(pos); true }
        }
        root.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        load()
        grid.requestFocus()
        startStats()
    }

    override fun onPause() {
        super.onPause()
        stopStats()
        if (movingPos >= 0) stopMove()
    }

    // Home is the launcher, so Back does nothing outside move mode.
    override fun onBackPressed() {}

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (ev.action == KeyEvent.ACTION_UP && swallowUp) {
            swallowUp = false
            return true
        }
        if (movingPos < 0) return super.dispatchKeyEvent(ev)

        if (ev.action == KeyEvent.ACTION_DOWN) {
            val delta = when (ev.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                KeyEvent.KEYCODE_DPAD_UP -> -COLS
                KeyEvent.KEYCODE_DPAD_DOWN -> COLS
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BACK -> {
                    stopMove()
                    swallowUp = true
                    return true
                }
                else -> return super.dispatchKeyEvent(ev)
            }
            val to = movingPos + delta
            if (to in entries.indices) {
                Collections.swap(entries, movingPos, to)
                movingPos = to
                (grid.adapter as BaseAdapter).notifyDataSetChanged()
                grid.setSelection(to)
            }
        }
        return true
    }

    private fun startStats() {
        stopStats()
        prevBusy = -1L
        prevTotal = -1L
        dumpsysFailed = false
        sampler = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({
                val text = try { statsText() } catch (_: Exception) { "" }
                runOnUiThread { stats.text = text }
            }, 0, 3, TimeUnit.SECONDS)
        }
    }

    private fun stopStats() {
        sampler?.shutdownNow()
        sampler = null
    }

    private fun statsText(): String {
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val gb = 1024.0 * 1024 * 1024
        val ram = String.format(Locale.US, "RAM %.2f / %.2f GB",
            (mi.totalMem - mi.availMem) / gb, mi.totalMem / gb)
        return ram + ",  " + cpuText()
    }

    private fun cpuText(): String {
        // 1) /proc/stat: instant usage, but Android 8+ usually blocks apps from reading it.
        val s = readProcStat()
        if (s != null) {
            val pct = if (prevTotal >= 0 && s[1] > prevTotal)
                ((s[0] - prevBusy) * 100 / (s[1] - prevTotal)).toInt() else null
            prevBusy = s[0]
            prevTotal = s[1]
            return if (pct != null) "CPU $pct%" else "CPU ..."
        }
        // 2) dumpsys cpuinfo: needs: adb shell pm grant com.vedav.launcher android.permission.DUMP
        if (!dumpsysFailed) {
            val d = cpuFromDumpsys()
            if (d != null) return "CPU $d (recent avg)"
            dumpsysFailed = true
        }
        // 3) Load average as a rough proxy.
        return try {
            val l = java.io.File("/proc/loadavg").readText().trim().split(" ")[0]
            "CPU load $l (${Runtime.getRuntime().availableProcessors()} cores)"
        } catch (_: Exception) {
            "CPU n/a"
        }
    }

    private fun readProcStat(): LongArray? = try {
        val line = java.io.File("/proc/stat").bufferedReader().use { it.readLine() }
        val p = line?.trim()?.split(Regex("\\s+"))
        if (p == null || p.size < 6 || p[0] != "cpu") null else {
            val v = p.drop(1).take(8).map { it.toLong() }
            val total = v.sum()
            val idle = v[3] + v[4]
            longArrayOf(total - idle, total)
        }
    } catch (_: Exception) {
        null
    }

    private fun cpuFromDumpsys(): String? = try {
        val proc = ProcessBuilder("dumpsys", "cpuinfo").redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.destroy()
        Regex("""([\d.]+)%\s+TOTAL""").find(out)?.groupValues?.get(1)?.let { "$it%" }
    } catch (_: Exception) {
        null
    }

    private fun load() {
        val pm = packageManager
        val found = LinkedHashMap<String, ResolveInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val key = ri.activityInfo.packageName
                if (key != packageName && !found.containsKey(key)) found[key] = ri
            }
        }
        entries.clear()
        found.values.mapTo(entries) {
            Entry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name, it)
        }

        // Saved order first. Newly installed apps go at the end, alphabetically.
        val saved = (prefs.getString("order", "") ?: "").split("\n").filter { it.isNotEmpty() }
        val idx = saved.withIndex().associate { it.value to it.index }
        entries.sortWith(compareBy({ idx[it.pkg] ?: Int.MAX_VALUE }, { it.label.lowercase() }))

        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun saveOrder() {
        prefs.edit().putString("order", entries.joinToString("\n") { it.pkg }).apply()
    }

    private fun startMove(pos: Int) {
        movingPos = pos
        grid.selector = selector(MOVING)
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        Toast.makeText(this, "Move with arrows, press OK to drop", Toast.LENGTH_SHORT).show()
    }

    private fun stopMove() {
        movingPos = -1
        grid.selector = selector(NORMAL)
        saveOrder()
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun menu(pos: Int) {
        val e = entries[pos]
        AlertDialog.Builder(this)
            .setTitle(e.label)
            .setItems(arrayOf("Move", "App info")) { _, which ->
                if (which == 0) startMove(pos) else details(e)
            }
            .show()
    }

    private fun launch(e: Entry) {
        try {
            startActivity(Intent(Intent.ACTION_MAIN)
                .setComponent(ComponentName(e.pkg, e.cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "Can't open ${e.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun details(e: Entry) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${e.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "App info unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(p: Int) = entries[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = convert as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(16), dp(8), dp(12))
                background = GradientDrawable().apply {
                    setColor(0x14FFFFFF)
                    cornerRadius = dp(12).toFloat()
                }
                addView(ImageView(context), LinearLayout.LayoutParams(dp(72), dp(72)))
                addView(TextView(context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(8), 0, 0)
                })
            }
            val e = entries[p]
            (v.getChildAt(0) as ImageView).setImageDrawable(e.info.loadIcon(packageManager))
            (v.getChildAt(1) as TextView).text = e.label
            v.alpha = if (p == movingPos) 0.7f else 1f
            return v
        }
    }
}

// Dark diagonal gradient with two soft colour glows, so the screen isn't flat black.
private class Backdrop : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shaders: List<Shader> = emptyList()

    override fun onBoundsChange(b: Rect) {
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        fun glow(cx: Float, cy: Float, r: Float, color: Int): Shader =
            RadialGradient(cx, cy, r, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        shaders = listOf(
            LinearGradient(0f, 0f, w, h, 0xFF0B1020.toInt(), 0xFF1E1236.toInt(), Shader.TileMode.CLAMP),
            glow(w * 0.88f, h * 0.05f, w * 0.50f, 0x40FFC107),
            glow(w * 0.08f, h * 0.95f, w * 0.60f, 0x483D5AFE)
        )
    }

    override fun draw(canvas: Canvas) {
        for (sh in shaders) {
            paint.shader = sh
            canvas.drawRect(bounds, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.OPAQUE
}
