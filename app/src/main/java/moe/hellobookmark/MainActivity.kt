package moe.hellobookmark

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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

    private var activeDialog: AlertDialog? = null
    private var lastOpenMs = 0L
    private var lastWindowH = 0

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

        // 搜索框上方留白占窗口高度的比例（弹性，小屏自动收缩不占空）
        private const val UPPER_RATIO = 0.20f

        // 允许原样放行的协议：http/https 网页，以及浏览器内置协议页（edge://flags、chrome://、about: 等）。
        // 其余协议（intent://、javascript:、file: 等）一律加 https:// 前缀中和，阻断非网页协议。
        private val PASSTHROUGH_SCHEMES = setOf(
            "http", "https",
            "about", "chrome", "edge", "brave", "vivaldi", "opera", "moz", "firefox", "view-source"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        loadBookmarks()
        engine = prefs.getString(KEY_ENGINE, ENGINE_BAIDU) ?: ENGINE_BAIDU
        buildUi()
        renderGrid()
    }

    override fun onDestroy() {
        // 旋转/深色切换导致 Activity 销毁时，主动关闭可能正显示的书签对话框，避免 WindowLeaked
        activeDialog?.takeIf { it.isShowing }?.dismiss()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI 构建

    private fun buildUi() {
        // 外层滚动容器：窗口高度不足（小屏、输入法弹出）时整体可上下滚动
        val rootScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        setContentView(rootScroll)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(18), dp(12), dp(14))
        }
        rootScroll.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        // 搜索框上方留白：按窗口高度比例弹性调整（小屏自动收缩不占空）。
        // 高度只取决于窗口大小，不随图标数量变动；窗口尺寸变化（如输入法弹出）时自动重算。
        val upperSpacer = Space(this)
        root.addView(upperSpacer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0
        ))

        // ---- 搜索框 ----
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
            id = R.id.search_edit // 固定 ID：旋转/深色切换重建时自动恢复已输入内容
            setTextColor(getColor(R.color.icon_color))
            setHintTextColor(getColor(R.color.hint_color))
            hint = getString(R.string.search_hint)
            background = null
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(500))
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

        // 右侧搜索图标：点击即搜索
        val searchIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(getColor(R.color.label_color))
            val lp = LinearLayout.LayoutParams(dp(20), dp(20))
            lp.setMargins(0, 0, dp(14), 0)
            layoutParams = lp
            contentDescription = getString(R.string.search_icon_desc)
            setOnClickListener { doSearch() }
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

        cells = Array(ROWS) { _ ->
            val rowLay = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(88)
                )
            }
            gridContainer.addView(rowLay)
            Array(COLS) { _ ->
                val cv = createCell()
                rowLay.addView(cv.root, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                cv
            }
        }

        // 布局完成后按窗口高度比例设置搜索框上方留白（窗口尺寸变化时自动重算）
        rootScroll.viewTreeObserver.addOnGlobalLayoutListener {
            val h = rootScroll.height
            if (h > 0 && h != lastWindowH) {
                lastWindowH = h
                val target = (h * UPPER_RATIO).toInt().coerceIn(dp(8), dp(220))
                val lp = upperSpacer.layoutParams
                if (lp.height != target) {
                    lp.height = target
                    upperSpacer.layoutParams = lp
                }
            }
        }
    }

    private fun createCell(): CellViews {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = pressBackground() // 点击/长按的灰色按压反馈
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

    private fun openBookmark(b: Bookmark) {
        // 300ms 防抖，避免快速双击重复打开浏览器
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenMs < 300L) return
        lastOpenMs = now
        openUrl(b.url)
    }

    /** 用系统默认浏览器打开网址（本应用不联网，相当于点击网页中的蓝色链接）。 */
    private fun openUrl(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        // 协议策略：
        //  - 无协议（example.com）→ 自动补 https://
        //  - http/https 与浏览器内置协议（edge://flags、chrome:// 等）→ 原样放行
        //  - 其余（intent://、javascript:、file: 等）→ 加 https:// 前缀中和，避免 URL 被解析成其他协议
        // Uri.parse 会把 scheme 规范化为小写，因此 HTTP:// 也能被正确识别
        val scheme = Uri.parse(trimmed).scheme
        val url = when {
            scheme == null -> "https://$trimmed"
            scheme in PASSTHROUGH_SCHEMES -> trimmed
            else -> "https://$trimmed"
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            // 打开成功后立即退出本应用：浏览器按返回键不会回到 hellobookmark
            finish()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
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
            filters = arrayOf(InputFilter.LengthFilter(50))
            setTextColor(getColor(R.color.icon_color))
            setHintTextColor(getColor(R.color.hint_color))
            if (isEdit) setText(bookmarks[idx!!].name)
        }
        val urlInput = EditText(this).apply {
            hint = getString(R.string.hint_url)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            filters = arrayOf(InputFilter.LengthFilter(2048))
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
        activeDialog = dialog
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
        val cp = t.codePointAt(0)
        // 孤立代理项（畸形文本）直接返回占位符，避免 Character.toChars 抛异常
        return if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else "?"
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    /** 图标按压反馈：按下/长按时显示灰色圆角底色，其余时间透明 */
    private fun pressBackground(): StateListDrawable {
        val pressed = GradientDrawable().apply {
            setColor(getColor(R.color.press_bg))
            cornerRadius = dp(16).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
