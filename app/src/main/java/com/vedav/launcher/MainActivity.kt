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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
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
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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

    private class Movie(
        val id: Long,
        val title: String,
        val year: String,
        val rating: Double,
        val overview: String,
        val backdrop: String?
    ) {
        var bmp: Bitmap? = null
    }

    private companion object {
        const val COLS = 6
        const val MOVIE_SLOTS = COLS * 2          // the two rows above the apps
        const val MOVING = 0xFFFFC107.toInt()
        const val TTL_MS = 12L * 60 * 60 * 1000   // refetch the list at most twice a day
        const val LIST_URL = "https://api.themoviedb.org/3/trending/movie/week?language=en-US"
        const val IMG_URL = "https://image.tmdb.org/t/p/w500"
    }

    private val entries = mutableListOf<Entry>()
    private var movies: List<Movie> = emptyList()
    private val movieCells = mutableListOf<LinearLayout>()

    private lateinit var grid: GridView
    private lateinit var stats: TextView
    private lateinit var movieBox: LinearLayout
    private lateinit var moviesBtn: TextView

    private var movingPos = -1
    private var swallowUp = false
    private var showMovies = true
    @Volatile private var fetching = false
    private var sampler: ScheduledExecutorService? = null

    // The app list is only rebuilt when something is installed, removed or changed.
    private var dirty = true
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { dirty = true }
    }

    private val prefs by lazy { getSharedPreferences("layout", MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showMovies = prefs.getBoolean("movies", true)

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
        moviesBtn = topButton(moviesLabel()) { toggleMovies() }
        top.addView(topButton("Clear RAM") { clearRam() }, buttonParams())
        top.addView(topButton("Settings") { openSettings() }, buttonParams())
        top.addView(moviesBtn, buttonParams())
        root.addView(top)

        movieBox = buildMovieBox()
        root.addView(movieBox)

        grid = GridView(this).apply {
            numColumns = COLS
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(24)
            horizontalSpacing = dp(20)
            clipToPadding = false
            clipChildren = false
            setPadding(dp(8), dp(16), dp(8), dp(24))
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
        bindMovies()

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
        grid.requestFocus() // focus starts on the first app, not on the movie rows
        startStats()
        refreshMovies()
    }

    override fun onPause() {
        super.onPause()
        stopStats()
        if (movingPos >= 0) stopMove()
    }

    // Pressing Home while the launcher is already open goes back to the first app.
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (movingPos >= 0) stopMove()
        grid.setSelection(0)
        grid.requestFocus()
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

    // The remote's own Settings key (gear button) opens Settings while the launcher is in front.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SETTINGS) {
            openSettings()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- top bar ----------

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

    // ---------- apps ----------

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

    // ---------- TMDB movie rows ----------

    private fun moviesLabel() = if (showMovies) "Movies: On" else "Movies: Off"

    private fun toggleMovies() {
        showMovies = !showMovies
        prefs.edit().putBoolean("movies", showMovies).apply() // remembered across restarts
        moviesBtn.text = moviesLabel()
        if (!showMovies) movies = emptyList() // frees the poster bitmaps
        bindMovies()
        if (showMovies) refreshMovies()
    }

    private fun buildMovieBox(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(12), 0, 0)
        }
        for (r in 0 until 2) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                setPadding(dp(8), 0, dp(8), 0)
            }
            for (c in 0 until COLS) {
                val i = r * COLS + c
                val cell = makeCell().apply {
                    isFocusable = true
                    setOnFocusChangeListener { v, has -> applyStyle(v as LinearLayout, has, true) }
                    setOnClickListener { showMovie(i) }
                }
                movieCells.add(cell)
                row.addView(cell, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { if (c > 0) marginStart = dp(20) })
            }
            box.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        box.addView(TextView(this).apply {
            text = "Movie data from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB."
            setTextColor(0x88FFFFFF.toInt())
            textSize = 10f
            setPadding(dp(8), dp(6), 0, 0)
        })
        return box
    }

    private fun bindMovies() {
        movieBox.visibility = if (showMovies) View.VISIBLE else View.GONE
        for ((i, cell) in movieCells.withIndex()) {
            val tile = cell.getChildAt(0) as Tile
            val label = cell.getChildAt(1) as TextView
            val m = if (showMovies) movies.getOrNull(i) else null
            tile.setBackgroundColor(0xFF262B3B.toInt())
            tile.art.scaleType = ImageView.ScaleType.CENTER_CROP
            tile.art.setPadding(0, 0, 0, 0)
            tile.art.setImageBitmap(m?.bmp)
            tile.badge.visibility = if (m != null) View.VISIBLE else View.GONE
            tile.badge.text = if (m != null) String.format(Locale.US, "\u2605 %.1f", m.rating) else ""
            label.text = when {
                m != null -> m.title
                i == 0 && showMovies && BuildConfig.TMDB_TOKEN.isEmpty() -> "TMDB token not set"
                i == 0 && showMovies -> "Loading movies..."
                else -> ""
            }
            applyStyle(cell, cell.hasFocus(), false)
        }
    }

    private fun refreshMovies() {
        if (!showMovies || fetching) return
        val fresh = System.currentTimeMillis() - prefs.getLong("tmdb_time", 0L) < TTL_MS
        if (movies.isNotEmpty() && fresh) return
        fetching = true
        Thread {
            try {
                var json = prefs.getString("tmdb_json", null)
                if ((json == null || !fresh) && BuildConfig.TMDB_TOKEN.isNotEmpty()) {
                    try {
                        json = httpText(LIST_URL)
                        prefs.edit().putString("tmdb_json", json)
                            .putLong("tmdb_time", System.currentTimeMillis()).apply()
                    } catch (_: Exception) {
                        // offline or rejected: keep showing the cached list
                    }
                }
                val list = if (json != null) parseMovies(json) else emptyList()
                for (m in list) m.bmp = loadBackdrop(m)
                val keep = list.map { "tmdb_${it.id}.jpg" }.toSet()
                filesDir.listFiles()
                    ?.filter { it.name.startsWith("tmdb_") && it.name !in keep }
                    ?.forEach { it.delete() }
                runOnUiThread {
                    movies = if (showMovies) list else emptyList()
                    bindMovies()
                }
            } catch (_: Exception) {
            } finally {
                fetching = false
            }
        }.start()
    }

    private fun parseMovies(json: String): List<Movie> {
        val arr = JSONObject(json).getJSONArray("results")
        val out = ArrayList<Movie>()
        var i = 0
        while (i < arr.length() && out.size < MOVIE_SLOTS) {
            val o = arr.getJSONObject(i++)
            out.add(Movie(
                o.getLong("id"),
                o.optString("title"),
                o.optString("release_date").take(4),
                o.optDouble("vote_average", 0.0),
                o.optString("overview"),
                if (o.isNull("backdrop_path")) null else o.getString("backdrop_path")
            ))
        }
        return out
    }

    private fun connect(url: String, auth: Boolean): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        if (auth) {
            c.setRequestProperty("Authorization", "Bearer ${BuildConfig.TMDB_TOKEN}")
            c.setRequestProperty("accept", "application/json")
        }
        if (c.responseCode != 200) {
            c.disconnect()
            throw IOException("HTTP ${c.responseCode}")
        }
        return c
    }

    private fun httpText(url: String): String {
        val c = connect(url, true)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun loadBackdrop(m: Movie): Bitmap? {
        val path = m.backdrop ?: return null
        val f = File(filesDir, "tmdb_${m.id}.jpg")
        return try {
            if (!f.exists()) {
                val c = connect(IMG_URL + path, false)
                try {
                    c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                } finally {
                    c.disconnect()
                }
            }
            BitmapFactory.decodeFile(f.path)
        } catch (_: Exception) {
            f.delete()
            null
        }
    }

    private fun showMovie(i: Int) {
        val m = movies.getOrNull(i)
        if (m == null) {
            Toast.makeText(this, "Movies are still loading", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("${m.title} (${m.year})")
            .setMessage(String.format(Locale.US, "TMDB rating %.1f / 10\n\n%s", m.rating, m.overview))
            .setPositiveButton("Trailer") { _, _ -> openTrailer(m) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openTrailer(m: Movie) {
        val q = Uri.encode("${m.title} ${m.year} trailer")
        val url = Uri.parse("https://www.youtube.com/results?search_query=$q")
        for (pkg in listOf<String?>("com.google.android.youtube.tv", null)) {
            try {
                val i = Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (pkg != null) i.setPackage(pkg)
                startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, "No app can open the trailer", Toast.LENGTH_SHORT).show()
    }

    // ---------- tiles ----------

    // 16:9 rounded tile that clips its artwork to the rounded outline.
    private inner class Tile(ctx: Context) : FrameLayout(ctx) {
        val art = ImageView(ctx)
        val badge = TextView(ctx)

        init {
            addView(art, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            badge.apply {
                setTextColor(Color.WHITE)
                textSize = 11f
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = GradientDrawable().apply {
                    setColor(0xAA000000.toInt())
                    cornerRadius = dp(8).toFloat()
                }
                visibility = View.GONE
            }
            addView(badge, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply { setMargins(0, 0, dp(6), dp(6)) })
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
        setPadding(dp(8), dp(8), dp(8), dp(8))
        addView(Tile(this@MainActivity), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(this@MainActivity).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, 0)
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
        setStroke(dp(3), color)
        cornerRadius = dp(12).toFloat()
    }

    // Dashed frame around tile and label, with a light fill so it shows on any artwork.
    private fun frame(color: Int) = GradientDrawable().apply {
        setColor(0x30FFFFFF)
        cornerRadius = dp(16).toFloat()
        setStroke(dp(3), color, dp(10).toFloat(), dp(6).toFloat())
    }

    // Focused tile: zoom, lift above neighbours, dashed frame, inner ring, bright label.
    private fun applyStyle(cell: LinearLayout, focused: Boolean, animate: Boolean) {
        val tile = cell.getChildAt(0) as Tile
        val label = cell.getChildAt(1) as TextView
        val color = if (movingPos >= 0) MOVING else Color.WHITE
        val scale = if (focused) 1.12f else 1f
        val lift = if (focused) dp(20).toFloat() else 0f
        val labelAlpha = if (focused) 1f else 0.6f
        cell.background = if (focused) frame(color) else null
        tile.foreground = if (focused) ring(color) else null
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
            tile.badge.visibility = View.GONE
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
