package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldLabelScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.flow.drop

/**
 * Compose 1.13 deprecated the value-based BasicTextField and the VisualTransformation that masked
 * passwords; their replacements keep the text in a [TextFieldState]. These helpers let a field
 * whose text lives elsewhere (a ViewModel's state) use them unchanged for its callers.
 */

/**
 * Which values coming back from a field's owner are only the owner catching up with what was
 * typed, and which are the owner's own change (a PIN cleared after a failed sign-in) that the
 * field has to show. Without this, a late echo of "1" arriving after "12" was typed would put
 * the field back to "1" and lose a keystroke.
 */
internal class TypedEchoes {
    private val sent = ArrayDeque<String>()

    fun sent(text: String) {
        sent.addLast(text)
        while (sent.size > MAX_PENDING) sent.removeFirst()
    }

    /** True when [value] is one the field sent (it and anything older are done with). */
    fun isEcho(value: String): Boolean {
        val at = sent.indexOf(value)
        if (at < 0) {
            sent.clear()
            return false
        }
        repeat(at + 1) { sent.removeFirst() }
        return true
    }

    private companion object {
        const val MAX_PENDING = 64
    }
}

/**
 * A [TextFieldState] kept in step with [value]: typing goes to [onValueChange]; a change the owner
 * makes itself (clearing, prefilling) is shown in the field. See [TypedEchoes].
 */
@Composable
fun rememberSyncedTextFieldState(value: String, onValueChange: (String) -> Unit): TextFieldState {
    val state = rememberTextFieldState(value)
    val echoes = remember { TypedEchoes() }
    val onChange by rememberUpdatedState(onValueChange)
    LaunchedEffect(value) {
        if (!echoes.isEcho(value) && state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.drop(1).collect { text ->
            echoes.sent(text)
            onChange(text)
        }
    }
    return state
}

/** Refuses an edit that would leave anything but digits (a PIN, typed on the number pad). */
private object DigitsOnly : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (!asCharSequence().all(Char::isDigit)) revertAllChanges()
    }
}

/**
 * A masked PIN or secret field - what OutlinedTextField with PasswordVisualTransformation was.
 * Always fully hidden (no flash of the last digit typed), on the number pad, copy and cut off.
 */
@Composable
fun PinField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    digitsOnly: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    onDone: (() -> Unit)? = null,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    textStyle: TextStyle = LocalTextStyle.current,
) {
    val labelContent: (@Composable TextFieldLabelScope.() -> Unit)? = label?.let { text -> { Text(text) } }
    // The secure field hides the digits on screen but still reports them as its text to
    // accessibility services (a UI dump read the PIN back, 23 Nov 2026). Report dots instead,
    // as a platform password field does; the outer semantics win over the field's own.
    val masked = AnnotatedString("•".repeat(state.text.length))
    OutlinedSecureTextField(
        state = state,
        modifier = modifier.semantics { editableText = masked },
        enabled = enabled,
        textStyle = textStyle,
        label = labelContent,
        inputTransformation = if (digitsOnly) DigitsOnly else null,
        textObfuscationMode = TextObfuscationMode.Hidden,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        onKeyboardAction = onDone?.let { done -> KeyboardActionHandler { done() } },
        shape = shape,
        colors = colors,
    )
}
