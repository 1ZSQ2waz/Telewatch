package com.gohj99.telewatch.ui.chat

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import com.gohj99.telewatch.ui.InputBar
import com.gohj99.telewatch.ui.InputBarFrame

@Composable
internal fun MessageInputBar(
    draft: MutableState<String>,
    placeholder: String,
    selection: MutableState<TextRange>? = null
) {
    if (!LocalConfiguration.current.isScreenRound) {
        InputBar(query = draft.value, onQueryChange = {
            draft.value = it
            selection?.value = TextRange(it.length)
        }, placeholder = placeholder)
        return
    }

    val textSize = MaterialTheme.typography.titleMedium.fontSize.value
    InputBarFrame {
        AndroidView(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            factory = { DraftEditText(it) },
            onRelease = { it.onEdit = null; it.onSelection = null },
            update = { editor ->
                if (!placeholder.contentEquals(editor.hint ?: "")) editor.hint = placeholder
                editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
                editor.onEdit = { draft.value = it }
                editor.onSelection = { start, end -> selection?.value = TextRange(start, end) }
                editor.applyDraft(draft.value, selection?.value)
            }
        )
    }
}

private class DraftEditText(context: Context) : EditText(context) {
    var onEdit: ((String) -> Unit)? = null
    var onSelection: ((Int, Int) -> Unit)? = null
    private var applyingDraft = false

    init {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        imeOptions = EditorInfo.IME_ACTION_DONE
        gravity = Gravity.TOP or Gravity.START
        background = null
        setPadding(0, 0, 0, 0)
        includeFontPadding = false
        setTextColor(Color.WHITE)
        setHintTextColor(0xFF4E5C67.toInt())
        doAfterTextChanged { if (!applyingDraft) onEdit?.invoke(it?.toString().orEmpty()) }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!applyingDraft && selStart >= 0 && selEnd >= 0) onSelection?.invoke(selStart, selEnd)
    }

    fun applyDraft(draft: String, selection: TextRange?) {
        val editable = text
        // The IME owns the live Editable, selection and composing spans.
        if (draft.contentEquals(editable)) return

        applyingDraft = true
        beginBatchEdit()
        try {
            BaseInputConnection.removeComposingSpans(editable)
            editable.replace(0, editable.length, draft)
            setSelection(
                selection?.start?.coerceIn(0, editable.length) ?: editable.length,
                selection?.end?.coerceIn(0, editable.length) ?: editable.length
            )
        } finally {
            endBatchEdit()
            applyingDraft = false
        }
    }
}
