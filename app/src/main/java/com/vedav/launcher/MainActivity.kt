package com.vedav.launcher

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.*
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private class Entry(val label: String, val pkg: String, val cls: String, val info: ResolveInfo) {
        var art: Drawable? = null
        var isBanner = false
    }

    private companion object {
        const val COLS = 5
        const val NORMAL = Color.WHITE
        const val MOVING = 0xFFFFC107.toInt()
    }

    private val entries = mutableListOf<Entry>()
    private lateinit var grid: GridView
    private var movingPos = -1
    private var swallowUp = false

    // The app list is only rebuilt when something is installed, removed or changed.
    private var dirty = true
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { dirty = true }
    }

    private lateinit var stats: TextView
    private var sampler: ScheduledExecutorService? = null

    private val prefs by lazy { getSharedPreferences("layout", MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Backdrop()
            clipChildren = false
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

        top.addView(topButton("Clear RAM") { clearRam() }, buttonParams())
        top.addView(topButton("Settings") { openSettings() }, buttonParams())
        root.addView(top)

        grid = GridView(this).apply {
            numColumns = COLS
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(24)
            horizontalSpacing = dp(20)
            clipToPadding = false
            clipChildren = false
            setPadding(dp(8), dp(28), dp(8), dp(28))
            selector = ColorDrawable(Color.TRANSPARENT)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { styleAll() }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            setOnFocusChangeListener { _, _ -> styleAll() }
            adapter = AppAdapter()
            setOnItemClickListener { _, _, pos, _ -> launch(entries[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> menu(pos); true }
        }
        root.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(pkgReceiver)
    }

    override fun onResume() {
        super.onResume()
        if (dirty || entries.isEmpty()) {
            load()
            dirty = false
        }
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

    private fun buttonParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { marginStart = dp(16) }

    private fun topButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(8), dp(16), dp(8))
        isFocusable = true
        isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                setColor(0x44FFFFFF)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(2), Color.WHITE)
            })
            addState(intArrayOf(), GradientDrawable().apply {
                setColor(0x22FFFFFF)
                cornerRadius = dp(10).toFloat()
            })
        }
        setOnClickListener { onClick() }
    }

    private fun openSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "Settings unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    // The remote's own Settings key (gear button) opens Settings while the launcher is in front.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SETTINGS) {
            openSettings()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startStats() {
        stopStats()
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
        val fs = StatFs(Environment.getDataDirectory().path)
        val gb = 1024.0 * 1024 * 1024
        return String.format(Locale.US, "RAM %.2f / %.2f GB,  Free space %.2f / %.2f GB",
            (mi.totalMem - mi.availMem) / gb, mi.totalMem / gb,
            fs.availableBytes / gb, fs.totalBytes / gb)
    }

    // Asks Android to kill background processes of every other app. Foreground and
    // system-critical processes are left alone, and some apps will restart themselves.
    private fun clearRam() {
        Toast.makeText(this, "Clearing RAM...", Toast.LENGTH_SHORT).show()
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        Thread {
            for (ai in packageManager.getInstalledApplications(0)) {
                if (ai.packageName != packageName) am.killBackgroundProcesses(ai.packageName)
            }
            Thread.sleep(800)
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val text = try { statsText() } catch (_: Exception) { "" }
            runOnUiThread {
                stats.text = text
                Toast.makeText(this,
                    String.format(Locale.US, "Free RAM: %.2f GB", mi.availMem / 1024.0 / 1024 / 1024),
                    Toast.LENGTH_SHORT).show()
            }
        }.start()
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
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        Toast.makeText(this, "Move with arrows, press OK to drop", Toast.LENGTH_SHORT).show()
    }

    private fun stopMove() {
        movingPos = -1
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

    // 16:9 rounded tile that clips its artwork to the rounded outline.
    private inner class Tile(ctx: Context) : FrameLayout(ctx) {
        val art = ImageView(ctx)

        init {
            addView(art, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                    outline.setAlpha(1f)
                }
            }
            clipToOutline = true
        }

        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY))
        }
    }

    private fun makeCell(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        addView(Tile(this@MainActivity), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(this@MainActivity).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(10), 0, 0)
        })
    }

    // Prefer the app's TV banner, which is what Android TV apps supply for exactly this slot.
    private fun loadArt(e: Entry) {
        if (e.art != null) return
        val pm = packageManager
        val ai = e.info.activityInfo
        val banner = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
        if (banner != null) {
            e.art = banner
            e.isBanner = true
        } else {
            e.art = e.info.loadIcon(pm)
            e.isBanner = false
        }
    }

    private fun ring(color: Int) = GradientDrawable().apply {
        setStroke(dp(4), color)
        cornerRadius = dp(12).toFloat()
    }

    // Focused tile: zoom, lift above neighbours (with shadow), white ring, bright label.
    private fun applyStyle(cell: LinearLayout, focused: Boolean, animate: Boolean) {
        val tile = cell.getChildAt(0) as Tile
        val label = cell.getChildAt(1) as TextView
        val scale = if (focused) 1.12f else 1f
        val lift = if (focused) dp(20).toFloat() else 0f
        val labelAlpha = if (focused) 1f else 0.6f
        tile.foreground = if (focused) ring(if (movingPos >= 0) MOVING else Color.WHITE) else null
        cell.animate().cancel()
        label.animate().cancel()
        if (animate) {
            cell.animate().scaleX(scale).scaleY(scale).translationZ(lift).setDuration(140).start()
            label.animate().alpha(labelAlpha).setDuration(140).start()
        } else {
            cell.scaleX = scale
            cell.scaleY = scale
            cell.translationZ = lift
            label.alpha = labelAlpha
        }
    }

    private fun styleAll() {
        for (i in 0 until grid.childCount) {
            val pos = grid.firstVisiblePosition + i
            applyStyle(grid.getChildAt(i) as LinearLayout,
                pos == grid.selectedItemPosition && grid.hasFocus(), true)
        }
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(p: Int) = entries[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val cell = convert as? LinearLayout ?: makeCell()
            val tile = cell.getChildAt(0) as Tile
            val label = cell.getChildAt(1) as TextView
            val e = entries[p]
            loadArt(e)
            tile.art.setImageDrawable(e.art)
            if (e.isBanner) {
                tile.art.scaleType = ImageView.ScaleType.CENTER_CROP
                tile.art.setPadding(0, 0, 0, 0)
                tile.setBackgroundColor(Color.BLACK)
            } else {
                tile.art.scaleType = ImageView.ScaleType.FIT_CENTER
                tile.art.setPadding(dp(18), dp(18), dp(18), dp(18))
                tile.setBackgroundColor(0xFF262B3B.toInt())
            }
            label.text = e.label
            applyStyle(cell, p == grid.selectedItemPosition && grid.hasFocus(), false)
            return cell
        }
    }
}

// Smooth deep-blue to violet gradient with soft glows and a dark floor, dithered to avoid banding.
private class Backdrop : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private var shaders: List<Shader> = emptyList()

    override fun onBoundsChange(b: Rect) {
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        fun glow(cx: Float, cy: Float, r: Float, rgb: Int, a: Int): Shader =
            RadialGradient(cx, cy, r,
                intArrayOf((a shl 24) or rgb, ((a / 3) shl 24) or rgb, rgb),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        shaders = listOf(
            LinearGradient(0f, 0f, 0f, h,
                intArrayOf(0xFF1A2556.toInt(), 0xFF22184A.toInt(), 0xFF07080F.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP),
            glow(w * 0.15f, -h * 0.05f, w * 0.75f, 0x5B7CFF, 0x55),
            glow(w * 0.95f, h * 0.40f, w * 0.60f, 0xB06AFF, 0x38),
            glow(w * 0.50f, h * 1.05f, w * 0.80f, 0x000000, 0x90)
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
