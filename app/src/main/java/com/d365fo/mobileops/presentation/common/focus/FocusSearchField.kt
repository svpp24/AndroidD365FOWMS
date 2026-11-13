package com.d365fo.mobileops.presentation.common.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusShapes
import com.d365fo.mobileops.presentation.common.theme.FocusType

/**
 * A list's search box in the Focus look (user, 13 Nov), the scan pill's shape: grey pill at rest,
 * white with the orange typing border while typing, a navy search glyph, a clear button once
 * something is typed. Filters as you type; Done just closes the keyboard.
 */
@Composable
fun FocusSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val shell = if (focused) {
        Modifier.clip(FocusShapes.pill).background(FocusColors.surface).border(3.dp, FocusColors.action, FocusShapes.pill)
    } else {
        Modifier.clip(FocusShapes.pill).background(FocusColors.divider)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(shell)
            .padding(start = 16.dp, end = 4.dp),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = FocusColors.navy, modifier = Modifier.size(24.dp))
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (value.isEmpty()) Text(placeholder, style = FocusType.pill, color = FocusColors.muted)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = FocusType.pill.copy(color = FocusColors.ink),
                cursorBrush = SolidColor(FocusColors.action),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Clear, contentDescription = "Clear search", tint = FocusColors.muted)
            }
        }
    }
}
