package moe.hellobookmark

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 网页启动器兼书签
 *
 * 主界面仿照 AOSP 原生 Launcher：
 *  - 上半部分为搜索框（纯输入、无联想），左侧可切换百度/谷歌；
 *  - 下半部分为 5x5 图标网格，图标为书签名称首字符大字体 + 透明背景；
 *  - 点击图标/搜索均通过系统默认浏览器打开网址，本应用自身不联网；
 *  - 未使用的空位隐藏，最后一个图标后面显示 “+” 新增按钮，满 25 个后隐藏；
 *  - 跟随系统深浅色模式切换背景。
 */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val bookmarks = mutableListOf<Bookmark>()
    private var engine: String = ENGINE_BAIDU

    private lateinit var searchEdit: EditText
    private lateinit var engineBtn: TextView
    private lateinit var gridContainer: LinearLayout
    private lateinit var cells: Array<Array<CellViews>>

    private data class Bookmark(val name: String, val url: String)

    private class CellViews(val root: LinearLayout, val char: TextView, val label: TextView)

    companion object {
        private const val PREFS = "hellobookmark_prefs"
        private const val KEY_BOOKMARKS = "bookmarks"
        private const val KEY_ENGINE = "engine"
        private const val ENGINE_BAIDU = "baidu"
        private const val ENGINE_GOOGLE = "google"
        private const val COLS = 5
        private const val ROWS = 5
        private const val MAX_BOOKMARKS = COLS * ROWS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        loadBookmarks()
        engine = prefs.getString(KEY_ENGINE, ENGINE_BAIDU) ?: ENGINE_BAIDU
        buildUi()
        renderGrid()
    }

    // ------------------------------------------------------------------ UI 构建

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(18), dp(12), dp(14))
        }
        setContentView(root)

        // ---- 搜索框（上半部分）----
        val searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(getColor(R.color.search_bg), dp(24))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)
            )
        }
        root.addView(searchBar)

        // 左侧：搜索引擎切换按钮（默认百度，点击切换谷歌）
        engineBtn = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.accent))
            layoutParams = LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { toggleEngine() }
        }
        searchBar.addView(engineBtn)

        // 输入框：只负责打字，无联想
        searchEdit = EditText(this).apply {
            setTextColor(getColor(R.color.icon_color))
            setHintTextColor(getColor(R.color.hint_color))
            hint = getString(R.string.search_hint)
            background = null
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    doSearch()
                    true
                } else {
                    false
                }
            }
        }
        searchBar.addView(searchEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        // 右侧搜索图标
        val searchIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(getColor(R.color.label_color))
            val lp = LinearLayout.LayoutParams(dp(20), dp(20))
            lp.setMargins(0, 0, dp(14), 0)
            layoutParams = lp
        }
        searchBar.addView(searchIcon)

        // 弹性空白，把图标网格推到下半部分
        root.addView(Space(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ---- 5x5 图标网格（下半部分）----
        gridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(gridContainer)

        cells = Array(ROWS) { row ->
            val rowLay = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(88)
                )
            }
            gridContainer.addView(rowLay)
            Array(COLS) { col ->
                val cv = createCell()
                rowLay.addView(cv.root, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                cv
            }
        }
    }

    private fun createCell(): CellViews {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val char = TextView(this).apply {
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.icon_color))
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        val label = TextView(this).apply {
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(getColor(R.color.label_color))
            gravity = Gravity.CENTER
        }
        root.addView(char, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(label, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })
        return CellViews(root, char, label)
    }

    /**
     * 渲染网格：前 count 个为书签图标，其后跟一个 “+” 新增图标，其余空位隐藏。
     */
    private fun renderGrid() {
        for (row in 0 until ROWS) {
            val rowLay = gridContainer.getChildAt(row) as LinearLayout
            var anyVisible = false
            for (col in 0 until COLS) {
                val idx = row * COLS + col
                val cv = cells[row][col]
                when {
                    idx < bookmarks.size -> {
                        val b = bookmarks[idx]
                        cv.char.text = firstChar(b.name)
                        cv.char.setTextColor(getColor(R.color.icon_color))
                        cv.label.text = b.name
                        cv.char.visibility = View.VISIBLE
                        cv.label.visibility = View.VISIBLE
                        cv.root.setOnClickListener { openBookmark(b) }
                        cv.root.setOnLongClickListener { showBookmarkDialog(idx); true }
                        cv.root.visibility = View.VISIBLE
                        anyVisible = true
                    }
                    idx == bookmarks.size && bookmarks.size < MAX_BOOKMARKS -> {
                        cv.char.text = "+"
                        cv.char.setTextColor(getColor(R.color.accent))
                        cv.label.text = getString(R.string.add_label)
                        cv.char.visibility = View.VISIBLE
                        cv.label.visibility = View.VISIBLE
                        cv.root.setOnClickListener { showBookmarkDialog(null) }
                        cv.root.setOnLongClickListener { showBookmarkDialog(null); true }
                        cv.root.visibility = View.VISIBLE
                        anyVisible = true
                    }
                    else -> cv.root.visibility = View.GONE
                }
            }
            rowLay.visibility = if (anyVisible) View.VISIBLE else View.GONE
        }
        engineBtn.text = if (engine == ENGINE_GOOGLE) "G" else "百"
    }

    // ------------------------------------------------------------------ 数据

    private fun loadBookmarks() {
        bookmarks.clear()
        val raw = prefs.getString(KEY_BOOKMARKS, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                bookmarks.add(Bookmark(o.optString("name", ""), o.optString("url", "")))
            }
        } catch (e: Exception) {
            bookmarks.clear()
        }
    }

    private fun saveBookmarks() {
        val arr = JSONArray()
        bookmarks.forEach { b ->
            arr.put(JSONObject().put("name", b.name).put("url", b.url))
        }
        prefs.edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
    }

    // ------------------------------------------------------------------ 交互

    private fun toggleEngine() {
        engine = if (engine == ENGINE_BAIDU) ENGINE_GOOGLE else ENGINE_BAIDU
        prefs.edit().putString(KEY_ENGINE, engine).apply()
        engineBtn.text = if (engine == ENGINE_GOOGLE) "G" else "百"
        Toast.makeText(
            this,
            if (engine == ENGINE_GOOGLE) R.string.engine_google else R.string.engine_baidu,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun doSearch() {
        val q = searchEdit.text.toString().trim()
        if (q.isEmpty()) {
            Toast.makeText(this, R.string.search_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val enc = URLEncoder.encode(q, "UTF-8")
        val url = if (engine == ENGINE_GOOGLE) {
            "https://www.google.com/search?q=$enc"
        } else {
            "https://www.baidu.com/s?wd=$enc"
        }
        openUrl(url)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchEdit.windowToken, 0)
        searchEdit.clearFocus()
    }

    private fun openBookmark(b: Bookmark) = openUrl(b.url)

    /** 用系统默认浏览器打开网址（本应用不联网，相当于点击网页中的蓝色链接）。 */
    private fun openUrl(raw: String) {
        var url = raw.trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")) {
            url = "https://$url"
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 添加（index == null）或编辑（index != null）书签。
     * 编辑时提供删除按钮。
     */
    private fun showBookmarkDialog(index: Int?) {
        val isEdit = index != null
        val idx = index // 便于在闭包中使用非空下标
        val nameInput = EditText(this).apply {
            hint = getString(R.string.hint_name)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(getColor(R.color.icon_color))
            setHintTextColor(getColor(R.color.hint_color))
            if (isEdit) setText(bookmarks[idx!!].name)
        }
        val urlInput = EditText(this).apply {
            hint = getString(R.string.hint_url)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(getColor(R.color.icon_color))
            setHintTextColor(getColor(R.color.hint_color))
            if (isEdit) setText(bookmarks[idx!!].url)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val m = dp(20)
            setPadding(m, m / 2, m, 0)
            addView(nameInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(urlInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(getString(if (isEdit) R.string.edit_bookmark else R.string.add_bookmark))
            .setView(container)
            .setPositiveButton(getString(R.string.save), null)
            .setNeutralButton(getString(R.string.cancel), null)
        if (isEdit) {
            builder.setNegativeButton(getString(R.string.delete), null)
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isEmpty() || url.isEmpty()) {
                    Toast.makeText(this, R.string.fill_all, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (isEdit) {
                    bookmarks[idx!!] = Bookmark(name, url)
                } else {
                    bookmarks.add(Bookmark(name, url))
                }
                saveBookmarks()
                renderGrid()
                dialog.dismiss()
            }
            if (isEdit) {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    bookmarks.removeAt(idx!!)
                    saveBookmarks()
                    renderGrid()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------------ 工具

    private fun firstChar(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return "?"
        return String(Character.toChars(t.codePointAt(0)))
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
