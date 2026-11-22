package io.nekohasekai.sfa.bridge

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

/**
 * The platform's own menus, dialogs, messages and text selection for the
 * Slint UI (ui-slint/src/nav.rs): Rust keeps what each one means and what
 * happens on a pick; this only shows them and reports back as `ui` events.
 */
class NativeUi(private val activity: BoxActivity) {
    private val layer = FrameLayout(activity)
    private val anchor = View(activity)
    private var menu: PopupMenu? = null
    // A menu waiting for the layout pass, cancelled when another request comes first.
    private var pendingMenu: Runnable? = null
    private var viewer: Dialog? = null
    private var menuId = 0
    private var picked = false
    private var dialog: AlertDialog? = null
    private var dialogId = 0
    private var dialogError: TextView? = null
    private var toast: Toast? = null

    init {
        layer.isClickable = false
        layer.isFocusable = false
        layer.addView(anchor, FrameLayout.LayoutParams(1, 1))
        activity.addContentView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun handle(method: String, args: JSONObject): Boolean {
        when (method) {
            "menuShow" -> showMenu(args)
            "menuHide" -> hideMenu()
            "dialogShow" -> showDialog(args)
            "dialogHide" -> hideDialog()
            "dialogError" -> if (args.optInt("id") == dialogId) setError(args.optString("error"))
            "toast" -> showToast(args.optString("text"), args.optBoolean("long"))
            "textViewer" -> showText(args)
            else -> return false
        }
        return true
    }

    private fun send(kind: String, id: Int, vararg extra: Pair<String, Any>) {
        val value = mutableMapOf<String, Any>("kind" to kind, "id" to id)
        extra.forEach { (k, v) -> value[k] = v }
        SlintHost.nativeEvent(SlintHost.wire(mapOf("type" to "ui", "value" to value)))
    }

    private fun themed(args: JSONObject) = ContextThemeWrapper(
        activity,
        if (args.optBoolean("dark")) {
            com.google.android.material.R.style.Theme_Material3_Dark
        } else {
            com.google.android.material.R.style.Theme_Material3_Light
        },
    )

    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics).toInt()

    private fun color(args: JSONObject, key: String, fallback: Long) = args.optLong(key, fallback).toInt()

    // -- Menus ---------------------------------------------------------------

    private fun showMenu(args: JSONObject) {
        hideMenu()
        val id = args.optInt("id")
        val params = anchor.layoutParams as FrameLayout.LayoutParams
        params.leftMargin = args.optDouble("x").toInt()
        params.topMargin = args.optDouble("y").toInt()
        anchor.layoutParams = params
        val primary = color(args, "primary", 0xff6750a4)
        val error = color(args, "error", 0xffb3261e)
        val items = args.optJSONArray("items") ?: JSONArray()
        // After the layout pass, so the menu opens at the anchor's new place.
        val show = Runnable {
            pendingMenu = null
            val popup = PopupMenu(themed(args), anchor, Gravity.NO_GRAVITY)
            var group = 0
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val section = item.optString("section")
                if (item.optBoolean("divider") || section.isNotEmpty()) group++
                if (section.isNotEmpty() && item.optString("label").isEmpty()) {
                    val title = SpannableString(section)
                    title.setSpan(ForegroundColorSpan(primary), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    title.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    title.setSpan(RelativeSizeSpan(0.85f), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    popup.menu.add(group, i, i, title).isEnabled = false
                    continue
                }
                var label = item.optString("label")
                if (item.optBoolean("back")) label = "‹  $label"
                if (item.optBoolean("submenu")) label = "$label  ›"
                val text = SpannableString(label)
                if (item.optBoolean("danger") && item.optBoolean("enabled", true)) {
                    text.setSpan(ForegroundColorSpan(error), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                val entry = popup.menu.add(group, i, i, text)
                entry.isEnabled = item.optBoolean("enabled", true)
                if (item.optBoolean("checked")) {
                    entry.isCheckable = true
                    entry.isChecked = true
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 28) popup.menu.setGroupDividerEnabled(true)
            popup.setOnMenuItemClickListener { entry ->
                picked = true
                send("menuPick", id, "index" to entry.itemId)
                true
            }
            popup.setOnDismissListener {
                if (menu === popup) menu = null
                if (!picked && menuId == id) send("menuDismiss", id)
            }
            picked = false
            menuId = id
            menu = popup
            anchor.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            popup.show()
        }
        pendingMenu = show
        anchor.post(show)
    }

    private fun hideMenu() {
        pendingMenu?.let { anchor.removeCallbacks(it) }
        pendingMenu = null
        val popup = menu ?: return
        menu = null
        picked = true
        popup.dismiss()
    }

    // -- Dialogs -------------------------------------------------------------

    private fun showDialog(args: JSONObject) {
        hideDialog()
        val id = args.optInt("id")
        dialogId = id
        val context = themed(args)
        val onSurface = color(args, "onSurface", 0xff1d1b20)
        val variant = color(args, "onSurfaceVariant", 0xff49454f)
        val primary = color(args, "primary", 0xff6750a4)
        val errorColor = color(args, "error", 0xffb3261e)
        val actions = args.optJSONArray("actions") ?: JSONArray()
        val options = args.optJSONArray("options") ?: JSONArray()
        val input = args.optJSONObject("input")
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val message = args.optString("message")
        if (message.isNotEmpty()) {
            content.addView(TextView(context).apply {
                text = message
                setTextIsSelectable(true)
                setTextColor(variant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (args.optBoolean("mono")) 13f else 14f)
                if (args.optBoolean("mono")) typeface = Typeface.MONOSPACE
                setLineSpacing(0f, 1.15f)
            })
        }
        var field: EditText? = null
        if (input != null) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val multiline = input.optBoolean("multiline")
            val edit = EditText(context).apply {
                setText(input.optString("text"))
                setTextColor(onSurface)
                inputType = when {
                    input.optBoolean("number") -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                    multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    else -> InputType.TYPE_CLASS_TEXT
                }
                if (multiline) {
                    minLines = 4
                    maxLines = 12
                    gravity = Gravity.TOP or Gravity.START
                } else {
                    setSingleLine(true)
                    imeOptions = EditorInfo.IME_ACTION_DONE
                }
                backgroundTintList = ColorStateList.valueOf(primary)
                setSelection(text.length)
            }
            row.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val suffix = input.optString("suffix")
            if (suffix.isNotEmpty()) {
                row.addView(TextView(context).apply {
                    text = suffix
                    setTextColor(variant)
                    setPadding(dp(8), 0, 0, 0)
                })
            }
            content.addView(row)
            val error = TextView(context).apply {
                setTextColor(errorColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                visibility = View.GONE
            }
            content.addView(error)
            dialogError = error
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (dialogId == id) send("dialogEdited", id, "text" to s.toString())
                }
            })
            edit.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE && dialogId == id && actions.length() > 0) {
                    send("dialogAction", id, "index" to actions.length() - 1, "text" to edit.text.toString())
                    true
                } else {
                    false
                }
            }
            field = edit
        }
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(args.optString("title"))
            .setBackground(GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(color(args, "surface", 0xffece6f0))
            })
        if (content.childCount > 0) {
            builder.setView(ScrollView(context).apply { addView(content) })
        }
        if (options.length() > 0) {
            val labels = Array(options.length()) { options.getString(it) }
            builder.setSingleChoiceItems(labels, args.optInt("option", -1)) { _, which ->
                if (dialogId == id) send("dialogPick", id, "index" to which)
            }
        }
        val count = actions.length()
        val buttons = when (count) {
            0 -> emptyList()
            1 -> listOf(DialogInterface.BUTTON_POSITIVE)
            2 -> listOf(DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_POSITIVE)
            3 -> listOf(DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL, DialogInterface.BUTTON_POSITIVE)
            else -> null
        }
        if (buttons == null) {
            // More actions than a dialog has buttons: a list of them.
            val labels = Array(count) { actions.getString(it) }
            builder.setItems(labels) { _, which ->
                if (dialogId == id) send("dialogAction", id, "index" to which, "text" to (field?.text?.toString() ?: ""))
            }
        } else {
            buttons.forEachIndexed { index, which ->
                when (which) {
                    DialogInterface.BUTTON_POSITIVE -> builder.setPositiveButton(actions.getString(index), null)
                    DialogInterface.BUTTON_NEGATIVE -> builder.setNegativeButton(actions.getString(index), null)
                    else -> builder.setNeutralButton(actions.getString(index), null)
                }
            }
        }
        builder.setOnCancelListener { if (dialogId == id) send("dialogDismiss", id) }
        val shown = builder.create()
        shown.setOnShowListener {
            buttons?.forEachIndexed { index, which ->
                val button = shown.getButton(which) ?: return@forEachIndexed
                val danger = args.optBoolean("danger") && index == count - 1
                button.setTextColor(if (danger) errorColor else primary)
                // Rust closes the dialog (dialogHide), or keeps it with an error.
                button.setOnClickListener {
                    if (dialogId == id) send("dialogAction", id, "index" to index, "text" to (field?.text?.toString() ?: ""))
                }
            }
            field?.requestFocus()
        }
        if (field != null) shown.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog = shown
        shown.show()
    }

    private fun setError(error: String) {
        val view = dialogError ?: return
        view.text = error
        view.visibility = if (error.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun hideDialog() {
        val shown = dialog ?: return
        dialog = null
        dialogError = null
        dialogId = 0
        shown.dismiss()
    }

    // -- Messages and text ---------------------------------------------------

    private fun showToast(text: String, long: Boolean) {
        if (text.isEmpty()) return
        toast?.cancel()
        toast = Toast.makeText(activity, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).also { it.show() }
    }

    /** Read-only text in a native selectable view: system handles, copy, share. */
    private fun showText(args: JSONObject) {
        val text = args.optString("text")
        if (text.isEmpty()) return
        val context = themed(args)
        viewer?.dismiss()
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val view = TextView(context).apply {
            this.text = text
            setTextIsSelectable(true)
            setTextColor(color(args, "onSurface", 0xff1d1b20))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(24), dp(16), dp(24), dp(8))
            setLineSpacing(0f, 1.15f)
        }
        val shown: Dialog = MaterialAlertDialogBuilder(context)
            .setView(ScrollView(context).apply { addView(view) })
            .setBackground(GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(color(args, "surface", 0xffece6f0))
            })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.copy) { _, _ ->
                activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, text))
            }
            .create()
        viewer = shown
        shown.setOnDismissListener { if (viewer === shown) viewer = null }
        shown.show()
    }

    /** The activity is going away: no window may stay attached to it. */
    fun close() {
        hideMenu()
        hideDialog()
        viewer?.dismiss()
        viewer = null
    }
}
