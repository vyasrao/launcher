package com.vedav.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {

    private class Entry(val label: String, val pkg: String, val cls: String, val info: ResolveInfo)

    private val entries = mutableListOf<Entry>()
    private lateinit var grid: GridView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(48), dp(32), dp(48), dp(16))
        }

        val clock = TextClock(this).apply {
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            setTextColor(Color.WHITE)
            textSize = 36f
        }
        root.addView(clock)

        grid = GridView(this).apply {
            numColumns = 6
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(16)
            horizontalSpacing = dp(16)
            clipToPadding = false
            setPadding(0, dp(24), 0, dp(24))
            selector = GradientDrawable().apply {
                setColor(0x33FFFFFF)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(2), Color.WHITE)
            }
            adapter = AppAdapter()
            setOnItemClickListener { _, _, pos, _ -> launch(entries[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> details(entries[pos]); true }
        }
        root.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        load()
        grid.requestFocus()
    }

    // Home is the launcher, so Back does nothing.
    override fun onBackPressed() {}

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
        entries.sortBy { it.label.lowercase() }
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
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

    // Long press: app info page (uninstall, disable, clear cache).
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
            return v
        }
    }
}
