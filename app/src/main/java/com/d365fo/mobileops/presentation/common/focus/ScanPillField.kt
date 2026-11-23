package com.d365fo.mobileops.presentation.common.focus

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.components.BarcodeGlyph
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusType

/**
 * The app's one scan bridge, provided by MainActivity. Previews and tests get a throwaway one, so
 * a typed code there goes nowhere.
 */
val LocalScanBridge = staticCompositionLocalOf { HidScannerBridge() }

/**
 * The scan pill, working: tapping it opens the keyboard in the pill itself (white, orange border,
 * no blinking cursor), and Enter sends what was typed down the very same path as a trigger scan -
 * so a screen handles a typed code and a scanned one identically (brief section 4, rule 4). Back
 * or leaving the field closes it without sending.
 *
 * While the worker types, the wedge interception is paused so a keyboard isn't mistaken for a
 * scanner; the vendor scan broadcast keeps working, and a trigger scan still arrives as a scan.
 *
 * [onSubmit] defaults to the bridge; a screen that takes typed text for something other than a
 * scan (a vendor search) passes its own.
 */
@Composable
fun ScanPillField(
    modifier: Modifier = Modifier,
    label: String = "Scan or type",
    onPanel: Boolean = false,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Ascii,
    onSubmit: ((String) -> Unit)? = null,
) {
    val bridge = LocalScanBridge.current
    val submit = onSubmit ?: bridge::submitTypedScan
    var typing by rememberSaveable { mutableStateOf(false) }
    // Set when the typing field closes. With a hardware keyboard or wedge the screen is in key
    // mode, where Compose gives focus to the first control once the field is gone - Back, or the
    // Home header, showed a grey focus ring, and a stray Enter there would have pressed it. Focus
    // is parked on an inert target on the resting pill instead: nothing lights, nothing clicks.
    var parkFocus by remember { mutableStateOf(false) }

    if (!typing) {
        val park = remember { FocusRequester() }
        ScanPill(
            onTap = { typing = true },
            modifier = if (parkFocus) modifier.focusRequester(park).focusable() else modifier,
            label = label,
            onPanel = onPanel,
            enabled = enabled,
        )
        if (parkFocus) LaunchedEffect(Unit) { runCatching { park.requestFocus() } }
        return
    }

    // Kept across a rotation or process death, as the rememberSaveable string was.
    val text = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var hadFocus by remember { mutableStateOf(false) }
    /** [park]: the worker closed it by key (Enter, Back) - not by tapping something else, which keeps its focus. */
    fun close(park: Boolean = false) {
        keyboard?.hide()
        text.clearText()
        parkFocus = park
        typing = false
    }
    fun send() {
        val value = text.text.toString().trim()
        close(park = true)
        if (value.isNotEmpty()) submit(value)
    }

    DisposableEffect(Unit) {
        bridge.enabled = false
        onDispose { bridge.enabled = true }
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    BackHandler { close(park = true) }

    // State-based (Compose 1.13 deprecated the value-based one). Single line: no newline gets in.
    BasicTextField(
        state = text,
        lineLimits = TextFieldLineLimits.SingleLine,
        textStyle = FocusType.fieldValue.copy(color = FocusColors.ink),
        // No blinking cursor - the pill's orange border says where the typing goes.
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = keyboardType,
            imeAction = ImeAction.Done,
        ),
        onKeyboardAction = { send() },
        modifier = modifier
            .fillMaxWidth()
            // A hardware Enter (keyboard or wedge) sends on key-up, not key-down, so its key-up
            // is not left over for whatever holds focus once the pill is gone.
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) send()
                true
            }
            .focusRequester(focus)
            .onFocusChanged {
                if (it.isFocused) hadFocus = true else if (hadFocus && typing) close()
            }
            .semantics { contentDescription = label },
        decorator = TextFieldDecorator { field ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .typingPill()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(BarcodeGlyph, contentDescription = null, tint = FocusColors.navy, modifier = Modifier.size(26.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (text.text.isEmpty()) Text(label, style = FocusType.pill, color = FocusColors.muted)
                    field()
                }
                Icon(Icons.Filled.Keyboard, contentDescription = null, tint = FocusColors.action, modifier = Modifier.size(24.dp))
            }
        },
    )
}
