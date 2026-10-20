package com.d365fo.mobileops.presentation.common.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The design mock's own line icons (20 Oct restyle: "icons in the app is different from mock
 * screens") - the same 24-unit paths, drawn as round-capped strokes, not Material's filled set.
 * Badge icons (white on a coloured square) use the mock's heavier 2.4 stroke; the bottom bar and
 * settings rows its lighter 1.8. Tint them like any icon.
 */
object LineIcons {
    private const val COUNT = "M9 4h6v3H9zM6 6h12v15H6zM9 12h6M9 16h4"
    private const val RECEIVE = "M12 4v10M7 9l5 5 5-5M4 18h16"
    private const val SHIP = "M3 7h11v9H3zM14 10h4l3 3v3h-7M7 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM17 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"
    private const val ACTIVITY = "M4 12h4l3-7 4 14 3-7h2"
    private const val STORE = "M3 9l9-5 9 5v11H3zM9 20v-6h6v6"
    private const val SEARCH = "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-3.5-3.5"
    private const val CREATE = "M12 5v14M5 12h14"
    private const val HOME = "M4 11l8-7 8 7v9H4zM10 20v-5h4v5"
    private const val SERVICES = "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z"
    private const val MORE = "M8 6h12M8 12h12M8 18h12M4 6h0M4 12h0M4 18h0"
    private const val POSTING = "M4 17l6-6 4 4 6-8"
    private const val FEEDBACK = "M4 5h16v11H8l-4 4z"
    private const val LINK = "M9 15l6-6M10 6l1-1a4 4 0 0 1 6 6l-1 1M14 18l-1 1a4 4 0 0 1-6-6l1-1"
    private const val TOUR = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 11v6M12 7h0"
    private const val ABOUT = "M6 3h9l3 3v15H6zM9 9h6M9 13h6M9 17h4"

    // The same family for the app's other modules, which the mock doesn't show.
    private const val RETURN = "M9 14l-5-5 5-5M4 9h10a6 6 0 0 1 0 12h-3"
    private const val LOCATION = "M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11zM12 12.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z"
    private const val TODAY = "M4 6h16v14H4zM4 10h16M8 3v5M16 3v5"
    private const val LIST = "M5 4h14v16H5zM9 9h6M9 13h6M9 17h3"
    private const val SWAP = "M4 8h14M14 4l4 4-4 4M20 16H6M10 12l-4 4 4 4"
    private const val SERIAL = "M4 6v12M7 6v12M10 6v12M14 6v12M17 6v12M20 6v12"
    private const val LOCK = "M6 11h12v9H6zM9 11V8a3 3 0 0 1 6 0v3"
    private const val PALETTE = "M12 3a9 9 0 1 0 0 18c1.1 0 1.5-.8 1.5-1.5 0-1.4-1.2-1.5-1.2-2.8 0-1 .8-1.7 1.8-1.7H17a4 4 0 0 0 4-4c0-4.4-4-8-9-8zM7.5 11.5h0M10 7.5h0M14.5 7.5h0"
    private const val UPLOAD = "M12 16V6M7 11l5-5 5 5M4 20h16"
    private const val FOLDER = "M3 6h6l2 2h10v11H3z"

    private fun line(name: String, path: String, stroke: Float): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(path),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()

    private const val BADGE = 2.4f
    private const val THIN = 1.8f

    // Badge weight - module, widget and group badges.
    val Count = line("Count", COUNT, BADGE)
    val Receive = line("Receive", RECEIVE, BADGE)
    val Ship = line("Ship", SHIP, BADGE)
    val Activity = line("Activity", ACTIVITY, BADGE)
    val Store = line("Store", STORE, BADGE)
    val Search = line("Search", SEARCH, BADGE)
    val Create = line("Create", CREATE, BADGE)
    val Return = line("Return", RETURN, BADGE)
    val Location = line("Location", LOCATION, BADGE)
    val Today = line("Today", TODAY, BADGE)
    val ListLines = line("List", LIST, BADGE)
    val Swap = line("Swap", SWAP, BADGE)
    val Serial = line("Serial", SERIAL, BADGE)
    val Folder = line("Folder", FOLDER, BADGE)

    // Thin weight - bottom bar, settings rows, search field.
    val Home = line("Home", HOME, THIN)
    val Services = line("Services", SERVICES, THIN)
    val ActivityThin = line("ActivityThin", ACTIVITY, THIN)
    val More = line("More", MORE, THIN)
    val Warehouse = line("Warehouse", STORE, THIN)
    val Posting = line("Posting", POSTING, THIN)
    val Feedback = line("Feedback", FEEDBACK, THIN)
    val Link = line("Link", LINK, THIN)
    val Tour = line("Tour", TOUR, THIN)
    val About = line("About", ABOUT, THIN)
    val Lock = line("Lock", LOCK, THIN)
    val Palette = line("Palette", PALETTE, THIN)
    val Upload = line("Upload", UPLOAD, THIN)
    val SearchThin = line("SearchThin", SEARCH, THIN)
}
