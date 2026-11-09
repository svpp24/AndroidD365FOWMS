package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.common.theme.WarehouseType
import com.d365fo.mobileops.presentation.common.theme.breakableCode

/**
 * The one row every list in the redesign uses (brief §2) - lines, Activity, vendor lists - in
 * place of each screen's own card rows: a 28dp status circle, a title and any number of
 * subtitles, all wrapping, and an optional trailing value or chip.
 *
 * The circle carries the [StatusKind] icon on its tint, so a line's state reads without colour;
 * the trailing slot or a subtitle carries the word. At least
 * [WarehouseDimens.MinTouchTarget] tall; a long item name or document number grows the row
 * rather than being cut.
 *
 * @param codeTitle the title is a code (item, document, bin): set in CodeFont, like everywhere
 *   else a code is read to check it.
 * @param trailing e.g. "3 / 5" received/ordered, or a [StatusChip]. Laid out beside the text,
 *   top-aligned, at its own width.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitles: List<String> = emptyList(),
    status: StatusKind? = null,
    codeTitle: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = WarehouseDimens.MinTouchTarget)
            .background(WarehouseStatusColors.surface)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (status != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(status.band),
            ) {
                Icon(status.icon, contentDescription = status.spoken, tint = status.edge, modifier = Modifier.size(18.dp))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title.breakableCode(),
                style = if (codeTitle) WarehouseType.codeSmall else WarehouseType.body,
                color = WarehouseStatusColors.ink,
            )
            subtitles.forEach {
                Text(it.breakableCode(), style = WarehouseType.label, color = WarehouseStatusColors.inkMuted)
            }
        }
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

/** A plain trailing value for [ListRow] - "3 / 5", "12 pcs" - in the code font, kept on one line. */
@Composable
fun ListRowValue(text: String) {
    Text(text, style = WarehouseType.codeSmall, color = WarehouseStatusColors.ink, softWrap = false)
}

@Preview(name = "ListRow", widthDp = 360, showBackground = true)
@Composable
private fun ListRowPreview() = PreviewSurface { ListRowSamples() }

@Preview(name = "ListRow - 130%", widthDp = 360, showBackground = true)
@Composable
private fun ListRowPreviewLarge() = PreviewSurface(large = true) { ListRowSamples() }

@Composable
internal fun ListRowSamples() {
    Column(Modifier.clip(RoundedCornerShape(16.dp))) {
        ListRow(
            title = "BSR-11397-0",
            codeTitle = true,
            subtitles = listOf("Item description, pack of 21", "Location A-01-02"),
            status = StatusKind.Ok,
            trailing = { ListRowValue("5 / 5") },
            onClick = {},
        )
        HorizontalDivider(color = WarehouseStatusColors.divider)
        ListRow(
            title = "BSR-10113-0",
            codeTitle = true,
            subtitles = listOf("A long item description that has to wrap onto more than one line"),
            status = StatusKind.Idle,
            trailing = { ListRowValue("0 / 12") },
        )
        HorizontalDivider(color = WarehouseStatusColors.divider)
        ListRow(
            title = "Transfer BSP-TO-000000043 not shipped",
            subtitles = listOf("D365FO: not enough stock at AAC", "Today 09:41"),
            status = StatusKind.Bad,
            onClick = {},
        )
    }
}
