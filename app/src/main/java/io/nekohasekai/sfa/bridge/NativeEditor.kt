package io.nekohasekai.sfa.bridge

import android.graphics.Typeface
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * The system's own text field for the Slint UI (ui-slint/src/native_ime.rs):
 * while a NativeTextInput has focus, an [EditText] lies over it with the same
 * font size, colour and alignment and a transparent background, and the Slint
 * field stops drawing its text. Cursor placement, selection handles, the
 * context menu and the input method all belong to Android; each change goes
 * back to the field as an `ime` event.
 *
 * The editor closes when the UI ends it (the field lost focus), on a tap
 * outside it, on Back, and after the keyboard's Done on a single line.
 */
class NativeEditor(private val activity: BoxActivity) {
    private val layer = FrameLayout(activity)
    private val edit = EditText(activity)
    private var id = 0
    private var applying = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var downOutside = false
    private val slop = ViewConfiguration.get(activity).scaledTouchSlop
    private val imm = activity.getSystemService(InputMethodManager::class.java)
    private val closeAfterTap = Runnable { close(notify = true) }
    private val closeQuietly = Runnable { close(notify = false) }

    init {
        // Not clickable: touches outside the editor go on to the Slint surface.
        layer.isClickable = false
        layer.isFocusable = false
        edit.visibility = View.GONE
        edit.background = null
        edit.setPadding(0, 0, 0, 0)
        edit.includeFontPadding = false
        edit.minHeight = 0
        edit.minimumHeight = 0
        edit.minWidth = 0
        edit.minimumWidth = 0
        edit.isSaveEnabled = false
        layer.addView(edit, FrameLayout.LayoutParams(0, 0))
        activity.addContentView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!applying && id != 0) send("text", "text" to s.toString())
            }
        })
        edit.setOnEditorActionListener { _, action, event ->
            val done = action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_GO ||
                action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (done && id != 0 && edit.maxLines == 1) {
                send("accept")
                close(notify = true)
                true
            } else {
                false
            }
        }
        edit.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (id != 0) send("scroll", "y" to scrollY)
        }
    }

    val isOpen: Boolean get() = id != 0

    private fun send(kind: String, vararg extra: Pair<String, Any>) {
        val value = mutableMapOf<String, Any>("kind" to kind, "id" to id)
        extra.forEach { (k, v) -> value[k] = v }
        SlintHost.nativeEvent(SlintHost.wire(mapOf("type" to "ime", "value" to value)))
    }

    fun handle(method: String, args: JSONObject): Boolean {
        when (method) {
            "imeBegin" -> begin(args)
            "imeFrame" -> if (args.optInt("id") == id) place(args)
            "imeText" -> if (args.optInt("id") == id) {
                val text = args.optString("text")
                if (edit.text.toString() != text) {
                    val cursor = edit.selectionEnd.coerceIn(0, text.length)
                    applying = true
                    edit.setText(text)
                    edit.setSelection(cursor)
                    applying = false
                }
            }
            "imeSelect" -> if (args.optInt("id") == id) {
                val length = edit.text.length
                edit.setSelection(args.optInt("start").coerceIn(0, length), args.optInt("end").coerceIn(0, length))
            }
            // Later, so a focus that moves to another field keeps the keyboard up.
            "imeEnd" -> if (args.optInt("id") == id) layer.postDelayed(closeQuietly, 150)
            else -> return false
        }
        return true
    }

    private fun begin(args: JSONObject) {
        layer.removeCallbacks(closeAfterTap)
        layer.removeCallbacks(closeQuietly)
        id = args.optInt("id")
        val multiline = args.optBoolean("multiline")
        val wrap = args.optBoolean("wrap")
        applying = true
        // Lines first: setSingleLine() replaces the transformation, which the
        // password input type then sets again.
        if (multiline) {
            edit.setSingleLine(false)
            edit.maxLines = Int.MAX_VALUE
            edit.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        } else {
            edit.setSingleLine(true)
            edit.maxLines = 1
            edit.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        edit.inputType = when (args.optInt("input")) {
            1 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            2 -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            else -> if (multiline) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else {
                InputType.TYPE_CLASS_TEXT
            }
        }
        if (multiline) edit.setHorizontallyScrolling(!wrap)
        edit.isVerticalScrollBarEnabled = multiline
        edit.typeface = if (args.optBoolean("mono")) Typeface.MONOSPACE else Typeface.DEFAULT
        edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, args.optDouble("fontSize", 16.0).toFloat())
        edit.setTextColor(args.optLong("color", 0xff000000).toInt())
        val horizontal = when (args.optInt("align")) {
            2 -> Gravity.END
            1 -> Gravity.CENTER_HORIZONTAL
            else -> Gravity.START
        }
        val vertical = when (args.optInt("valign")) {
            0 -> Gravity.TOP
            2 -> Gravity.BOTTOM
            else -> Gravity.CENTER_VERTICAL
        }
        edit.gravity = horizontal or vertical
        edit.setText(args.optString("text"))
        edit.scrollTo(0, 0)
        applying = false
        place(args)
        edit.visibility = View.VISIBLE
        edit.setSelection(edit.text.length)
        edit.requestFocus()
        // The tap that focused the field puts the cursor where it landed.
        val tapped = SystemClock.uptimeMillis() - downTime < 800
        val tapX = downX
        val tapY = downY
        val begun = id
        edit.post {
            if (id != begun) return@post
            val left = edit.left.toFloat()
            val top = edit.top.toFloat()
            if (tapped && tapX >= left && tapX <= left + edit.width && tapY >= top && tapY <= top + edit.height) {
                val offset = edit.getOffsetForPosition(tapX - left, tapY - top)
                if (offset >= 0) edit.setSelection(offset.coerceIn(0, edit.text.length))
            }
            imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
        imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun place(args: JSONObject) {
        val params = edit.layoutParams as FrameLayout.LayoutParams
        params.leftMargin = args.optDouble("x").toInt()
        params.topMargin = args.optDouble("y").toInt()
        params.width = args.optDouble("width").toInt().coerceAtLeast(1)
        params.height = args.optDouble("height").toInt().coerceAtLeast(1)
        edit.layoutParams = params
    }

    /** Closes the editor; [notify] tells the UI, which then drops the field's focus. */
    fun close(notify: Boolean) {
        layer.removeCallbacks(closeAfterTap)
        layer.removeCallbacks(closeQuietly)
        if (id == 0) return
        if (notify) send("end")
        id = 0
        imm?.hideSoftInputFromWindow(edit.windowToken, 0)
        edit.clearFocus()
        edit.visibility = View.GONE
        applying = true
        edit.setText("")
        applying = false
        activity.restoreSurfaceFocus()
    }

    /**
     * Seen before the Slint surface gets it: remembers where a tap landed (the
     * cursor goes there when it opens a field) and closes the editor after a
     * tap outside it. The close waits a moment so a tap on another field hands
     * the keyboard straight over instead of hiding and showing it.
     */
    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = SystemClock.uptimeMillis()
                downOutside = id != 0 && !inside(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> {
                if (downOutside && id != 0 && Math.abs(event.x - downX) < slop && Math.abs(event.y - downY) < slop) {
                    layer.postDelayed(closeAfterTap, 150)
                }
                downOutside = false
            }
            MotionEvent.ACTION_CANCEL -> downOutside = false
        }
    }

    private fun inside(x: Float, y: Float): Boolean {
        if (edit.visibility != View.VISIBLE) return false
        return x >= edit.left && x <= edit.right && y >= edit.top && y <= edit.bottom
    }
}
