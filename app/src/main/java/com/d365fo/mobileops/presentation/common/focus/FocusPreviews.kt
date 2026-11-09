package com.d365fo.mobileops.presentation.common.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.data.security.DisplayScale
import com.d365fo.mobileops.presentation.common.theme.D365MobileTheme
import com.d365fo.mobileops.presentation.common.theme.FocusColors

/*
 * Previews of every Focus component state (brief section 7). The whole-screen previews mirror the
 * mock-ups' PO receiving and store count states with sample data - no ViewModel involved.
 */

@Composable
internal fun FocusPreviewSurface(large: Boolean = false, content: @Composable () -> Unit) {
    D365MobileTheme(scale = if (large) DisplayScale.EXTRA_LARGE else DisplayScale.STANDARD) {
        Box(Modifier.background(FocusColors.page)) { content() }
    }
}

@Composable
private fun LinesUndo() {
    ActionRow {
        FocusButton("Lines", onClick = {}, icon = Icons.AutoMirrored.Filled.ListAlt, modifier = Modifier.weight(1f))
        FocusButton("Undo", onClick = {}, icon = Icons.AutoMirrored.Filled.Undo, modifier = Modifier.weight(1f))
    }
}

// ---- Bands ------------------------------------------------------------------------------------

@Preview(name = "ResultBand - all kinds", widthDp = 360, showBackground = true)
@Composable
private fun BandsPreview() = FocusPreviewSurface { FocusBandSamples() }

@Composable
internal fun FocusBandSamples() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 12.dp)) {
        ResultBand(BandState(BandKind.Ok, "Scanned", "Batch E0927 filled in"))
        ResultBand(BandState(BandKind.Bad, "Not on this PO", "HSN010 · nothing added"))
        ResultBand(BandState(BandKind.Warn, "More than ordered", "2 scanned, 1 ordered"))
        ResultBand(BandState(BandKind.Info, "PO found", "BSP-PO-000161763 · 360 IOMC FZE"))
    }
}

@Preview(name = "ResultBand - 130%", widthDp = 360, showBackground = true)
@Composable
private fun BandsLargePreview() = FocusPreviewSurface(large = true) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 12.dp)) {
        ResultBand(BandState(BandKind.Info, "PO found", "BSP-PO-000161763 · Gulf Pharma Distribution LLC"))
        ResultBand(BandState(BandKind.Bad, "Not on this journal", "BIN-F7 · nothing counted"))
    }
}

// ---- Bars, pill, buttons ----------------------------------------------------------------------

@Preview(name = "Top bar, progress, remaining, pill, buttons", widthDp = 360, showBackground = true)
@Composable
private fun ChromePreview() = FocusPreviewSurface { FocusChromeSamples() }

@Composable
internal fun FocusChromeSamples() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowTopBar(title = "PO receiving", onBack = {}, counter = "1 / 2")
        ProgressLine(0.5f)
        FlowTopBar(title = "Transfer - Receive BSP-TO-000000043 from ABB", onBack = {}, counter = "12 / 140")
        RemainingLine("1 line left")
        Box(Modifier.padding(horizontal = FocusDimens.side)) { ScanPill(onTap = {}) }
        LinesUndo()
        ActionRow {
            FocusButton("Keep 1", onClick = {}, modifier = Modifier.weight(1f))
            FocusButton("Accept 2", onClick = {}, primary = true, modifier = Modifier.weight(1f))
        }
        Box(Modifier.padding(horizontal = FocusDimens.side)) { HoldToConfirmButton("Hold to end count", onConfirm = {}) }
    }
}

// ---- Rows, Home -------------------------------------------------------------------------------

@Preview(name = "List rows and Home", widthDp = 360, showBackground = true)
@Composable
private fun RowsPreview() = FocusPreviewSurface { FocusRowSamples() }

@Composable
internal fun FocusRowSamples() {
    Column {
        FocusListRow("HSN002", state = RowState.Done, subtitle = "Handset charger 20W", count = "1/1", titleIsCode = true)
        FocusListRow("BSR-11397-0", state = RowState.Open, subtitle = "Panadol Extra 24s", count = "0/5", titleIsCode = true)
        FocusListRow("BSR-11397-0", state = RowState.Warn, subtitle = "No stock", count = "0/1", titleIsCode = true)
        FocusListRow("BSP-TO-000000043", icon = Icons.Filled.LocalShipping, subtitle = "AAC → ABB · 2 lines", count = "Ship", titleIsCode = true, chevron = true, onClick = {})
        Box(Modifier.padding(FocusDimens.side)) { HomeOnHandPanel(onPillTap = {}, icon = Icons.Filled.Inventory2) }
        HomeRow(icon = Icons.Filled.LocalShipping, title = "Receive", count = "4", onClick = {})
        HomeRow(icon = Icons.Filled.Settings, title = "Activity", count = "1", countColor = FocusColors.badIcon, onClick = {})
        HomeRow(icon = Icons.Filled.Search, title = "Enquiry", iconColor = FocusColors.muted, onClick = {})
        Box(Modifier.padding(16.dp)) { HamburgerButton(open = false, onClick = {}) }
        Box(Modifier.padding(16.dp)) { HamburgerButton(open = true, onClick = {}) }
    }
}

// ---- Whole screens ----------------------------------------------------------------------------

@Preview(name = "Screen - PO found", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun PoFoundPreview() = FocusPreviewSurface {
    FocusScreen(
        title = "PO receiving",
        onBack = {},
        counter = "0 / 2",
        progress = 0f,
        band = BandState(BandKind.Info, "PO found", "BSP-PO-000161763 · 360 IOMC FZE"),
        scanPill = { ScanPill(onTap = {}) },
        actions = {
            FocusButton("Lines", onClick = {}, icon = Icons.AutoMirrored.Filled.ListAlt, modifier = Modifier.weight(1f))
            FocusButton("Undo", onClick = {}, icon = Icons.AutoMirrored.Filled.Undo, modifier = Modifier.weight(1f))
        },
    ) {
        FocusCenter(caption = "To receive", code = "2 lines", description = "6 units")
    }
}

@Preview(name = "Screen - scanned", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun ScannedPreview() = FocusPreviewSurface { FocusScannedSample() }

@Composable
internal fun FocusScannedSample() {
    FocusScreen(
        title = "PO receiving",
        onBack = {},
        counter = "1 / 2",
        progress = 0.5f,
        band = BandState(BandKind.Ok, "Scanned", "Batch E0927 filled in"),
        remaining = "1 line left",
        scanPill = { ScanPill(onTap = {}) },
        actions = {
            FocusButton("Lines", onClick = {}, icon = Icons.AutoMirrored.Filled.ListAlt, modifier = Modifier.weight(1f))
            FocusButton("Undo", onClick = {}, icon = Icons.AutoMirrored.Filled.Undo, modifier = Modifier.weight(1f))
        },
    ) {
        FocusCenter(
            caption = "Last scanned",
            code = "HSN002",
            description = "Handset charger 20W",
            quantity = "1",
            unitLine = "Each",
            onQuantityTap = {},
        )
    }
}

@Preview(name = "Screen - over-receipt", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun OverReceiptPreview() = FocusPreviewSurface {
    FocusScreen(
        title = "PO receiving",
        onBack = {},
        counter = "1 / 2",
        progress = 0.5f,
        band = BandState(BandKind.Warn, "More than ordered", "2 scanned, 1 ordered"),
        actions = {
            FocusButton("Keep 1", onClick = {}, modifier = Modifier.weight(1f))
            FocusButton("Accept 2", onClick = {}, primary = true, modifier = Modifier.weight(1f))
        },
    ) {
        FocusCenter(
            caption = "Last scanned",
            code = "HSN002",
            description = "Handset charger 20W",
            quantity = "2",
            quantityColor = FocusColors.warnIcon,
            unitLine = "Each · ordered 1",
        )
    }
}

@Preview(name = "Screen - change quantity", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun QuantityPadPreview() = FocusPreviewSurface { FocusQuantityPadSample() }

@Composable
internal fun FocusQuantityPadSample() {
    FocusScreen(
        title = "Store count",
        onBack = {},
        counter = "BIN-A1",
        band = BandState(BandKind.Ok, "Counted", "BSR-11397-0 · now 3"),
        overlay = { QuantityPadSheet(initial = "3", onSet = {}, onDismiss = {}) },
    ) {
        FocusCenter(caption = "In BIN-A1", code = "BSR-11397-0", description = "Panadol Extra 24s", quantity = "3", unitLine = "Pack")
    }
}

@Preview(name = "Screen - decimal pad", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun DecimalPadPreview() = FocusPreviewSurface {
    Box(Modifier.fillMaxSize()) { QuantityPadSheet(initial = "2.5", onSet = {}, onDismiss = {}, allowDecimal = true) }
}

@Preview(name = "Screen - end count", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun EndCountPreview() = FocusPreviewSurface { FocusEndCountSample() }

@Composable
internal fun FocusEndCountSample() {
    FocusScreen(
        title = "Store count",
        onBack = {},
        band = BandState(BandKind.Ok, "Ready to end", "2 bins · 7 lines sent"),
        actions = { HoldToConfirmButton("Hold to end count", onConfirm = {}, modifier = Modifier.weight(1f)) },
    ) {
        FocusCenter(caption = "Count", code = "AAC-20261108-3", description = "Supervisor reviews in D365FO")
    }
}

@Preview(name = "Screen - lines", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun LinesPreview() = FocusPreviewSurface {
    FocusScreen(title = "PO lines", onBack = {}, counter = "1 / 2", progress = 0.5f) {
        FocusListRow("HSN002", state = RowState.Done, subtitle = "Handset charger 20W", count = "1/1", titleIsCode = true)
        FocusListRow("BSR-11397-0", state = RowState.Open, subtitle = "Panadol Extra 24s", count = "0/5", titleIsCode = true)
    }
}

@Preview(name = "Menu items", widthDp = 360, showBackground = true)
@Composable
private fun MenuItemsPreview() = FocusPreviewSurface {
    // The dropdown itself is a Popup (not drawn in previews); its items are the same rows.
    Column {
        FocusListRow("Settings", icon = Icons.Filled.Settings)
        FocusListRow("Sign out", icon = Icons.AutoMirrored.Filled.Logout)
    }
}
