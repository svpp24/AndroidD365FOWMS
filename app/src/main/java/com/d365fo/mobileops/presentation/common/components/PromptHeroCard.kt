package com.d365fo.mobileops.presentation.common.components

import androidx.compose.material.icons.filled.PhotoCamera
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
@Composable
fun PromptHeroCard(
    prompt: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // A white card with the prompt as its heading and an outlined field (20 Oct restyle, the
    // mock's "Scan item" card) - it was a full accent gradient with white text.
    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Text(
            text = prompt,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = WarehouseStatusColors.text3,
            )
        }
        content()
    }
}

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

/** "Scan with camera" inside a [PromptHeroCard] - accent outline on the white card. */
@Composable
fun HeroCameraButton(onClick: () -> Unit, enabled: Boolean = true) {
    SquareOutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, WarehouseStatusColors.accent),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = WarehouseStatusColors.accent),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.PhotoCamera, contentDescription = null)
        Text("Scan with camera", modifier = Modifier.padding(start = 8.dp))
    }
}
