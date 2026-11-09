package com.d365fo.mobileops.presentation.common.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusShapes
import com.d365fo.mobileops.presentation.common.theme.FocusType
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens

/** The Focus design's fixed sizes (brief sections 3 and 6, and the mock-ups). */
object FocusDimens {
    /** Screen side margin. */
    val side: Dp = 20.dp
    val topBar: Dp = 56.dp
    val scanPill: Dp = 60.dp
    /** Bottom buttons: 64dp, or the touch target when a larger text size makes that bigger. */
    val button: Dp get() = maxOf(64.dp, WarehouseDimens.MinTouchTarget)
    val homeRow: Dp = 64.dp
    val listRow: Dp = 60.dp
    val menuItem: Dp = 68.dp
}

/** What a [ResultBand] says: kind (colour and icon), a title and an optional detail line. */
data class BandState(val kind: BandKind, val title: String, val detail: String? = null)

/** The four band kinds. Each is an icon plus words - never colour alone. */
enum class BandKind { Ok, Bad, Warn, Info }

private data class BandLook(val bg: Color, val icon: ImageVector, val iconTint: Color, val text: Color, val label: String)

private fun BandKind.look() = when (this) {
    BandKind.Ok -> BandLook(FocusColors.okBg, Icons.Filled.CheckCircle, FocusColors.okIcon, FocusColors.okText, "Done")
    BandKind.Bad -> BandLook(FocusColors.badBg, Icons.Filled.Warning, FocusColors.badIcon, FocusColors.badText, "Problem")
    BandKind.Warn -> BandLook(FocusColors.warnBg, Icons.Filled.Error, FocusColors.warnIcon, FocusColors.warnText, "Check")
    BandKind.Info -> BandLook(FocusColors.infoBg, Icons.Filled.Info, FocusColors.infoIcon, FocusColors.infoText, "Info")
}

/**
 * The result of the last scan or action: a rounded 16dp card, 20dp side margin, a 28dp icon, the
 * title and a detail line. It is the only place a flow says what happened - no dialogs, no toasts;
 * the next scan replaces it. Announced to TalkBack when it changes.
 */
@Composable
fun ResultBand(state: BandState, modifier: Modifier = Modifier) {
    val look = state.kind.look()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .padding(horizontal = FocusDimens.side)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(FocusShapes.band)
            .background(look.bg)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(look.icon, contentDescription = look.label, tint = look.iconTint, modifier = Modifier.size(28.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(state.title, style = FocusType.bandTitle, color = look.text)
            state.detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = FocusType.bandDetail, color = look.text)
            }
        }
    }
}

/**
 * The middle of a flow screen, centred in the space left: a caption ("LAST SCANNED", "TO
 * RECEIVE"), the code, the description, the big quantity and its unit line. When [onQuantityTap]
 * is set the number is tappable and the unit line ends with "· tap to change". In a "job opened"
 * state pass the job size as [code] / [description] and no [quantity].
 */
@Composable
fun ColumnScope.FocusCenter(
    caption: String,
    code: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    quantity: String? = null,
    quantityColor: Color = FocusColors.navy,
    unitLine: String? = null,
    onQuantityTap: (() -> Unit)? = null,
) {
    // Centred in the space left, and scrollable when the largest text size makes it taller than
    // that space - the 96sp number at 130% must never be cut (brief section 14).
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier
            .weight(1f)
            .fillMaxWidth(),
    ) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .heightIn(min = maxHeight)
            .padding(horizontal = FocusDimens.side),
    ) {
        FocusCaption(caption)
        Text(
            code.breakAfterHyphens(),
            style = FocusType.code,
            color = FocusColors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        description?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = FocusType.description,
                color = FocusColors.inkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (quantity != null) {
            val tap = onQuantityTap
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(FocusShapes.button)
                    .then(
                        if (tap != null) {
                            Modifier.clickable(role = Role.Button, onClickLabel = "Change quantity", onClick = tap)
                        } else {
                            Modifier
                        },
                    )
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp),
            ) {
                Text(quantity, style = FocusType.bigQuantity, color = quantityColor, textAlign = TextAlign.Center)
                val unit = listOfNotNull(unitLine?.takeIf { it.isNotBlank() }, "tap to change".takeIf { tap != null })
                    .joinToString(" · ")
                if (unit.isNotEmpty()) {
                    Text(unit, style = FocusType.unitLine, color = FocusColors.caption, textAlign = TextAlign.Center)
                }
            }
        }
    }
    }
}

/** An upper-case, letter-spaced caption ("LAST SCANNED", "WHERE", "QUANTITY"). */
@Composable
fun FocusCaption(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = FocusType.caption, color = FocusColors.caption, modifier = modifier)
}

/**
 * Codes and document numbers break only after a hyphen, never inside a number: a zero-width space
 * after each hyphen gives the line breaker that one place.
 */
fun String.breakAfterHyphens(): String = replace("-", "-​")
