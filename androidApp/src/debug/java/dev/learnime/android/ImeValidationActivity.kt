package dev.learnime.android

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Debug-only host for validating the real IME against EditorInfo scenarios. */
class ImeValidationActivity : Activity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && currentFocus is EditText) {
            if (android.os.Build.VERSION.SDK_INT >= 30)
                window.insetsController?.show(android.view.WindowInsets.Type.ime())
            else (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(currentFocus, InputMethodManager.SHOW_IMPLICIT)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val scene = intent.getStringExtra("scene") ?: "text"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 64, 24, 0)
        }
        root.addView(TextView(this).apply { text = "IME validation · $scene"; textSize = 20f })
        val edit = EditText(this).apply {
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            inputType = when (scene) {
                "number" -> InputType.TYPE_CLASS_NUMBER
                "decimal" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                "phone" -> InputType.TYPE_CLASS_PHONE
                "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                "url" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                "visible-password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                "web-password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                "number-password" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                "datetime" -> InputType.TYPE_CLASS_DATETIME
                "raw" -> InputType.TYPE_NULL
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (scene in listOf("english", "unsupported-language")) {
                imeHintLocales = android.os.LocaleList.forLanguageTags(if (scene == "english") "en-US" else "ja-JP")
            }
            imeOptions = if (scene == "url") EditorInfo.IME_ACTION_GO else EditorInfo.IME_ACTION_DONE
            if (scene == "private") imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            hint = "Validation input"
            contentDescription = "Validation input"
            setText(intent.getStringExtra("text").orEmpty())
            setSelection(text.length)
            setOnEditorActionListener { _, action, _ ->
                android.util.Log.i("LEARNIME_EDITOR_CHECK", "action=$action")
                true
            }
        }
        root.addView(edit, LinearLayout.LayoutParams(-1, 160))
        setContentView(root)
        edit.requestFocus()
        edit.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) }
    }
}
