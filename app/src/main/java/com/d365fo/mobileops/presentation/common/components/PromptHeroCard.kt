package com.d365fo.mobileops.presentation.common.components

import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * The scan step's "what do I do now" card: a gradient accent block with the prompt in bold white
 * across the top and the input sitting inside it, so the one instruction and the one place to
 * act on it read as a single unit rather than a heading floating above a plain field.
 */


/** The field inside [PromptHeroCard]: white, outlined in the accent, dark text. */
@Composable
fun heroFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White,
    disabledContainerColor = Color.White,
    focusedBorderColor = WarehouseStatusColors.accent,
    unfocusedBorderColor = WarehouseStatusColors.accent,
    disabledBorderColor = WarehouseStatusColors.border,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    disabledTextColor = MaterialTheme.colorScheme.onSurface,
    cursorColor = WarehouseStatusColors.accent,
)

val HeroFieldShape = RoundedCornerShape(10.dp)

/** The round accent "enter" badge at the end of a hero field - purely a visual cue; the field
 * around it is what takes the tap. */
@Composable
fun HeroEnterBadge() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(38.dp)
            .background(WarehouseStatusColors.accent, RoundedCornerShape(8.dp)),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardReturn,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * A barcode symbol for fields that accept a scan - drawn here (vertical bars of varied width)
 * because material-icons has no plain barcode glyph.
 */
val BarcodeGlyph: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder(
        name = "Barcode",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // x, width of each bar; the bars run from y=5 to y=19.
        listOf(3f to 1.5f, 5.5f to 1f, 7.5f to 2f, 10.5f to 1f, 12.5f to 1.5f, 15f to 2.5f, 18.5f to 1f, 20f to 1.5f)
            .forEach { (x, w) ->
                addPath(
                    pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                        "M$x,5h${w}v14h-${w}z",
                    ).toNodes(),
                    fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
                )
            }
    }.build()
}

/** The leading barcode symbol on a scan field - tells the worker a scan goes here. */
@Composable
fun BarcodeFieldIcon() {
    Icon(
        BarcodeGlyph,
        contentDescription = "Scan a barcode",
        tint = WarehouseStatusColors.accent,
        modifier = Modifier.size(26.dp),
    )
}

/**
 * "Scan with camera" inside a [PromptHeroCard] - a quiet accent link under the field (Harbour:
 * the scanner trigger is the main way in, so the camera no longer takes a full-width button).
 * [extra] sits on the same line at the other end - "Search PO items".
 */
@Composable
fun HeroCameraButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    extraLabel: String? = null,
    onExtra: (() -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        HeroLink(Icons.Default.PhotoCamera, "Scan with camera", enabled, onClick)
        Box(Modifier.weight(1f))
        if (extraLabel != null && onExtra != null) {
            HeroLink(Icons.Default.Search, extraLabel, enabled, onExtra)
        }
    }
}

@Composable
private fun HeroLink(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = WarehouseStatusColors.accent, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = WarehouseStatusColors.accent)
    }
}
