package com.d365fo.mobileops.presentation.common.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d365fo.mobileops.data.security.DisplayScale

/**
 * A fixed light industrial identity, not a light/dark pair.
 *
 * Replaces the earlier Fluent Design (D365FO desktop lookalike) palette on request - a
 * deliberate visual break, not a tweak of the same design. The reference mockup this is built
 * from ships both a dark and a light palette (the same layout, same fonts, two colour sets); this
 * is the light one. Rendered the same regardless of the device's system theme, for the same
 * reason dynamic colour was always rejected here: a warehouse fleet has to look identical screen
 * to screen, so a supervisor's device matches a picker's and status colours cannot be recoloured
 * by a system setting. See [D365MobileTheme] - there is deliberately no `darkTheme` parameter to
 * branch on any more.
 */

/**
 * The light industrial palette - the mockup's own `@media (prefers-color-scheme: light)` /
 * `[data-theme="light"]` values, carried over as flat colours. The "dim" tints there are CSS
 * `rgba(accent, 10-12%)` over a white surface; translated to flat colours by blending at the
 * same alpha over white, since Compose colour roles here are drawn as solid fills, not overlays.
 */
// Very light blue-white page, so white cards read as cards without needing heavy borders - the layout
// reference this app was restyled to match (white cards, blue accents) uses the same.
// Light neutral grey with borderless-looking white cards - the 20 Oct restyle (navy on grey).
// 21 Oct: own identity - deep petrol on a slightly warm grey, not the reference app's navy.
private val Bg = Color(0xFFF2F4F3)
private val Surface = Color(0xFFFFFFFF)
private val Surface2 = Color(0xFFF4F7F7)
// Darker than the mockup's own value on both of these - the mockup renders on a backlit
// monitor at reading distance; this renders on a handheld read at arm's length, often outdoors
// or under warehouse sodium lighting. Text3 at the original #9CA3AF measured roughly 2:1
// against white, well under WCAG AA's 4.5:1 floor for body text - and it is the colour every
// item code, hint, count and timestamp on this app is set in, not an edge case. Border moved
// the same direction for the same reason: a card edge or field outline that all but disappears
// against Bg/Surface is a real "which one is my input" question, not just a look.
private val Border = Color(0xFFC2CDCF)
private val Green = Color(0xFF16A34A)
private val GreenDim = Color(0xFFE3F4E9)
private val Amber = Color(0xFFD97706)
private val AmberDim = Color(0xFFFAEFE1)
private val Red = Color(0xFFDC2626)
private val RedDim = Color(0xFFFCE9E9)
private val Text1 = Color(0xFF10282D)
private val Text2 = Color(0xFF34484D)
private val Text3 = Color(0xFF55686C)

/**
 * The one thing a worker can personalise: which accent colour reads as "this app" to them.
 * Everything else - status colours (green=ok, amber=warning, red=error), backgrounds, square
 * shapes - stays fixed regardless, for the same fleet-consistency reason the class doc above
 * gives for not branching on system dark mode: a supervisor's screen and a picker's screen still
 * have to agree on what "blocked" or "posted" looks like. Accent is the one role that's purely
 * identity, not meaning, so it's the one exposed as a choice.
 *
 * BLUE's own tone was retuned to match a reference WMS app's own brand blue directly - its
 * primary CTA colour (`rgb(41,175,232)`) and link colour (`rgb(2,116,190)`), read straight off
 * its live site rather than approximated from a screenshot. `accent` takes the darker of the
 * two (better contrast for text/icons on white); `accentDim` is the lighter one blended to
 * roughly 10% over white, the same "flat colour standing in for a translucent tint" technique
 * this file's own class doc already uses for every other dim token.
 */
enum class ColorPalette(
    val label: String,
    val accent: Color,
    val accentDim: Color,
    /**
     * A real second hue for a two-tone gradient identity - null for every flat entry below,
     * which instead gets an auto-generated light-to-full sweep of its own single [accent] (see
     * [WarehouseStatusColors.accentGradient]). Set only by the dedicated gradient entries at the
     * end of this list, so picking one of *those* is the one way to get a genuinely two-coloured
     * identity rather than one colour faded toward white.
     */
    val gradientStart: Color? = null,
) {
    /**
     * Deep industrial navy on a pale blue tint - the default from the 9 Nov handheld redesign
     * (docs/ui-redesign-brief.md, §1.1). Same hue as [WarehouseStatusColors.infoEdge], so the
     * "document found" band reads as this app's own colour rather than a second blue.
     */
    INDUSTRIAL("Industrial", Color(0xFF1E3A8A), Color(0xFFDBEAFE)),
    /** Deep indigo-navy, the 20 Oct restyle's identity (navy text and outlined buttons on grey). */
    INDIGO("Indigo", Color(0xFF232A7C), Color(0xFFE1E0F6)),
    /** Deep petrol - the app's own identity from 21 Oct, so it isn't a copy of the reference app. */
    PETROL("Petrol", Color(0xFF0B5563), Color(0xFFDCEBEE)),
    MICROSOFT("Microsoft blue", Color(0xFF0078D4), Color(0xFFEFF6FC)),
    BLUE("Blue", Color(0xFF0274BE), Color(0xFFEAF7FD)),
    GREEN("Green", Color(0xFF15803D), Color(0xFFE1F3E8)),
    AMBER("Amber", Color(0xFFB45309), Color(0xFFF8EADC)),
    PURPLE("Purple", Color(0xFF7C3AED), Color(0xFFEEE5FC)),

    // Added from D365 Finance & Operations' own "User options > Visual > Color theme" picker -
    // approximated by eye from a screenshot, not pixel-sampled/colour-picked the way BLUE's own
    // tone above was read straight off a live site. Close enough for an accent choice; nudge any
    // of these if one reads off once seen live. Named distinctly from the four above (SKY/PINE
    // rather than reusing "Blue"/"Green") since they're visually close but not the same tone -
    // this app's own BLUE and GREEN are tuned to a different reference and kept as-is, not
    // replaced. The picker's black/white "high contrast" swatch isn't included here - it's an
    // accessibility mode, not a colour, and doesn't fit a single-accent-Color model.
    SKY("Sky", Color(0xFF3B82C4), Color(0xFFEBF3F9)),
    MAROON("Maroon", Color(0xFF9B2242), Color(0xFFF5E9EC)),
    ORCHID("Orchid", Color(0xFFA83FAE), Color(0xFFF6ECF7)),
    SIENNA("Sienna", Color(0xFFC1501E), Color(0xFFF9EEE8)),
    PINE("Pine", Color(0xFF1E8A1E), Color(0xFFE8F3E8)),
    NAVY("Navy", Color(0xFF1B2A47), Color(0xFFE8EAEC)),
    PLUM("Plum", Color(0xFF452659), Color(0xFFECE9EE)),
    COBALT("Cobalt", Color(0xFF1D7FE0), Color(0xFFE8F2FB)),
    OLIVE("Olive", Color(0xFF7C8C1E), Color(0xFFF2F4E8)),
    MAGENTA("Magenta", Color(0xFFBE1FAE), Color(0xFFF8E8F7)),
    TEAL("Teal", Color(0xFF048C8C), Color(0xFFE6F3F3)),
    STEEL("Steel", Color(0xFF08566B), Color(0xFFE6EEF0)),
    WINE("Wine", Color(0xFF7A1022), Color(0xFFF2E7E9)),
    ESPRESSO("Espresso", Color(0xFF2E0C0C), Color(0xFFEAE6E6)),

    // Dedicated gradient identities - a real second hue via [gradientStart], not one accent faded
    // toward white the way every flat entry above gets automatically. `accent` on each of these
    // is still a real, solid, white-contrasting colour in its own right (used everywhere a single
    // flat fill is needed - status bar tint, a plain icon tint, etc.); `gradientStart` only comes
    // into play on the surfaces that already draw [WarehouseStatusColors.accentGradient] (hero
    // cards, tile arrow bars). Picked as light-start -> accent-end sweeps in the same direction
    // every flat entry's auto-gradient already runs, so nothing about *where* the gradient goes
    // changes, only how many real hues are in it.
    SUNSET("Sunset", Color(0xFFD6266B), Color(0xFFFCEAF1), Color(0xFFF4A340)),
    OCEAN("Ocean", Color(0xFF0C6FA8), Color(0xFFE7F3FA), Color(0xFF23C2B7)),
    DUSK("Dusk", Color(0xFF3A2A66), Color(0xFFECE8F5), Color(0xFF8A5FC4)),
    FOREST("Forest", Color(0xFF0B4F3C), Color(0xFFE6F2ED), Color(0xFF6FBE63)),
    ;

}

/**
 * A darker shade of [ColorPalette.accent], scaled toward black rather than looked up from a
 * second per-palette colour - so every flat [ColorPalette] entry gets a
 * matching dark variant for free, instead of needing an `accentDark` hand-tuned alongside each
 * `accent`/`accentDim` pair the way the picker screenshot's own colours already were.
 */
private fun Color.darken(factor: Float = 0.72f): Color = copy(
    red = red * factor,
    green = green * factor,
    blue = blue * factor,
)

/**
 * The "dim" tints (badge backgrounds behind a status colour) aren't part of Material3's own
 * ColorScheme shape, so they're exposed separately rather than forced into a role that doesn't
 * fit (`primaryContainer` etc. carry their own contrast contract Material components assume).
 * Call sites building a status chip/badge reach for [WarehouseStatusColors] directly.
 *
 * [accent]/[accentDim] are `mutableStateOf`-backed, not plain `val`s, so [D365MobileTheme] can
 * update them when the worker's chosen [ColorPalette] changes and every composable already
 * reading `WarehouseStatusColors.accent` (there is no `CompositionLocal` plumbed through this
 * app for it - it predates the palette picker) recomposes, the same as reading any other Compose
 * `State` during composition.
 */
object WarehouseStatusColors {
    // Start on the default palette, so the first frame (before D365MobileTheme's SideEffect has
    // run) is already the colour the app settles on rather than a one-frame flash of another.
    var accent by mutableStateOf(ColorPalette.INDUSTRIAL.accent)
        internal set
    var accentDim by mutableStateOf(ColorPalette.INDUSTRIAL.accentDim)
        internal set
    /** The top and bottom bars' fill - a shade darker than [accent] so the chrome framing a
     * screen reads as distinct from the accent-filled tiles/buttons inside it, rather than the
     * whole screen bleeding into one flat colour top to bottom. */
    var accentDark by mutableStateOf(ColorPalette.INDUSTRIAL.accent.darken())
        internal set
    /** [ColorPalette.gradientStart] of whichever palette is active - a real second hue for a
     *  dedicated gradient entry, or null for a flat one (in which case [accentGradient] falls
     *  back to fading [accent] toward white, as it always did). */
    var accentGradientStart by mutableStateOf<Color?>(null)
        internal set
    /** Light-to-full accent sweep, top-left to bottom-right - the hero cards' and tile arrow
     * bars' fill. Two real hues for a gradient palette ([accentGradientStart]); one colour faded
     * toward white for a flat one - either way it follows the chosen palette like everything
     * else. */
    val accentGradient: Brush
        get() = Brush.linearGradient(listOf(accentGradientStart ?: lerp(accent, Color.White, 0.30f), accent))
    /**
     * The "not available" tile bar - deliberately not palette-derived like [accentGradient].
     * A greyed-out card reads as "unavailable" precisely because it steps outside whichever
     * palette is active; tinting it toward the chosen accent would make it look like a dim
     * version of an enabled tile instead. Shared by both reasons a tile can be unusable - not
     * built yet, and blocked by an admin - see HomeScreen's ModuleTileCard.
     */
    val mutedGradient: Brush = Brush.linearGradient(listOf(Color(0xFFB8C0CC), Color(0xFF8E98A6)))
    val green = Green
    val greenDim = GreenDim
    val amber = Amber
    val amberDim = AmberDim
    val red = Red
    val redDim = RedDim
    val border = Border
    val surface2 = Surface2
    val text3 = Text3

    // Comfort colours (9 Nov redesign, docs/ui-redesign-brief.md §1.2). Fixed, not palette-
    // derived, for the same fleet-consistency reason as the status colours above: a worker who
    // picked a different accent must still see the same green "done" and red "wrong item" bands.
    // Kept alongside the older names rather than replacing them - existing screens still read
    // green/amber/red/border/text3, and move over screen by screen as each is redesigned.

    /** Page background on flow screens - a cool off-white, never a full-screen pure white. */
    val pageComfort = Color(0xFFF1F5F9)
    /** Cards and bars sitting on [pageComfort]. */
    val surface = Surface
    /** Main text. */
    val ink = Color(0xFF1E293B)
    /** Labels and secondary text - 7.6:1 on [pageComfort], well clear of WCAG AA. */
    val inkMuted = Color(0xFF475569)
    /** Dividers and card borders. */
    val divider = Color(0xFFE2E8F0)
    /** Primary action buttons, with white text - deliberately not the accent, so "press this"
     *  reads the same whichever palette is chosen. */
    val action = Color(0xFFEA6A12)
    /** [action] at about 12% over white - the highlighted FlowActionBar button's background, the
     *  same flat-tint technique as every other dim token in this file. */
    val actionDim = Color(0xFFFDEDE3)
    /** [action] pressed further - HoldToConfirmButton's fill as it is held. Dark enough that the
     *  white label still reads over it. */
    val actionDark = Color(0xFFB04E0B)

    /** Success band: tint, 10dp left edge, text. */
    val okBand = Color(0xFFDCFCE7)
    val okEdge = Green
    val okText = Color(0xFF14532D)
    /** Info / document found. */
    val infoBand = Color(0xFFDBEAFE)
    val infoEdge = Color(0xFF1E3A8A)
    val infoText = Color(0xFF1E3A8A)
    /** Warning - needs a choice. */
    val warnBand = Color(0xFFFEF3C7)
    val warnEdge = Amber
    val warnText = Color(0xFF78350F)
    /** Error - wrong item, nothing added. */
    val badBand = Color(0xFFFEE2E2)
    val badEdge = Red
    val badText = Color(0xFF7F1D1D)
    /** Waiting for the first scan. */
    val idleBand = Color(0xFFE2E8F0)
    val idleEdge = Color(0xFF64748B)

    /** The red scan FAB - the logo's red, unchanged from the 21 Oct rounded-square button. */
    val scanButton = Color(0xFFB5222D)
}

private fun industrialColors(palette: ColorPalette) = lightColorScheme(
    primary = palette.accent,
    onPrimary = Color.White,
    secondary = Green,
    onSecondary = Color.White,
    tertiary = Amber,
    onTertiary = Color.White,
    error = Red,
    onError = Color.White,
    background = Bg,
    onBackground = Text1,
    surface = Surface,
    onSurface = Text1,
    surfaceVariant = Surface2,
    onSurfaceVariant = Text2,
    // Material draws cards, dialogs, menus and switch tracks from the surfaceContainer roles,
    // and selected filter chips from secondaryContainer. Left unset they fall back to
    // Material's stock lavender-grey, which made every list card look disabled. White here,
    // like the Home tiles, with the border doing the separating.
    surfaceContainerLowest = Surface,
    surfaceContainerLow = Surface,
    surfaceContainer = Surface,
    surfaceContainerHigh = Surface,
    surfaceContainerHighest = Surface,
    primaryContainer = palette.accentDim,
    onPrimaryContainer = palette.accent,
    secondaryContainer = palette.accentDim,
    onSecondaryContainer = palette.accent,
    tertiaryContainer = AmberDim,
    onTertiaryContainer = Color(0xFF92400E),
    errorContainer = RedDim,
    onErrorContainer = Color(0xFF991B1B),
    outline = Border,
    outlineVariant = Color(0xFFE3E5EE),
)

/**
 * Scaled up from the mockup's own on-image sizes (that reference render several phone screens
 * at once inside a webpage, so its type is compressed for that layout) to what an actual device
 * needs - read under sodium lighting, through a scratched screen protector, often at arm's
 * length. The mockup's *relative* hierarchy and letter-spacing character carry over; the
 * absolute sizes do not. Item numbers, quantities and order numbers stay large - those are what
 * a wrong reading actually costs.
 *
 * Roboto throughout ([FontFamily.Default] resolves to it on Android without bundling anything -
 * it already ships with the OS), but not one uniform weight any more. Light (300) was tried
 * everywhere on explicit request and then partly reverted: this app's own founding reason for
 * its type choices is legibility at arm's length, through a scratched screen protector, under
 * dock/sodium lighting - conditions where thin strokes lose first. Display/headline/title roles
 * (read as headers and at-a-glance numbers, often a size or a bold override away from needing
 * the extra weight anyway) keep a light-ish Medium; body copy (paragraphs, descriptions - the
 * text most likely to actually be read start to finish in bad light) goes back to Normal; label
 * roles (small text: codes, chips, timestamps) get Medium too since small-and-thin is the worst
 * combination of the three.
 *
 * Takes [scale] rather than being a fixed `val` so the Settings "Appearance" size picker
 * (see [DisplayScale]) can grow every size at once - called fresh from [D365MobileTheme] on
 * every recomposition, the same as [industrialColors] already is for the accent colour.
 * Letter-spacing is left unscaled: it's already small and isn't what "text size" means here.
 */
/**
 * Nunito Sans (20 Oct restyle: the mock's rounded sans), bundled as one variable font -
 * res/font/nunito_sans.ttf, SIL Open Font License (assets/licenses/NunitoSans-OFL.txt). Each
 * weight is an instance of the weight axis.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val AppFont: FontFamily = FontFamily(
    listOf(400, 500, 600, 700, 800).map { w ->
        androidx.compose.ui.text.font.Font(
            resId = com.d365fo.mobileops.R.font.nunito_sans,
            weight = FontWeight(w),
            variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
                androidx.compose.ui.text.font.FontVariation.weight(w),
            ),
        )
    },
)

/**
 * Barlow Condensed (9 Nov redesign) for what a worker reads to check they have the right thing:
 * item codes, document numbers, locations/bins and quantities. Condensed so a long code like
 * BSP-PO-000161763 or BSR-11397-0 still fits big on one line of a portrait handheld, and its
 * open, distinct figures (1/7, 0/O, 5/S) are what a misread costs. Two static files, not a
 * variable font - res/font/barlow_condensed_semibold.ttf (600) and barlow_condensed_bold.ttf
 * (700), SIL Open Font License (assets/licenses/BarlowCondensed-OFL.txt). Nunito Sans stays for
 * everything else.
 */
val CodeFont: FontFamily = FontFamily(
    androidx.compose.ui.text.font.Font(com.d365fo.mobileops.R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    androidx.compose.ui.text.font.Font(com.d365fo.mobileops.R.font.barlow_condensed_bold, FontWeight.Bold),
)

/**
 * A zero-width break opportunity after every hyphen, so a code too long for its line breaks
 * between its parts (BSP-PO- / 000161763) instead of mid-number (BSP-PO-0001617 / 63). Android's
 * line breaker never breaks at a hyphen followed by a digit - it reads "-5" as a negative number -
 * so without this, document numbers and item codes at large text sizes split wherever the width
 * ran out (caught by ComponentLayoutTest's screenshots, 9 Nov). Invisible, and not read by
 * TalkBack; a test matching the displayed text should match on `code.breakableCode()`.
 */
fun String.breakableCode(): String = replace("-", "-​")

/**
 * The redesign's own type sizes (brief §1.3), before [DisplayScale]. Not Material [Typography]
 * roles - those are shared by every existing screen, which keep their current sizes until each is
 * redesigned; these are read only by the new flow components (FlowHeader, StatusBand, NextCard
 * and the rest). Nothing here is below the brief's 12sp floor.
 *
 * Getters over a `mutableStateOf` scale, the same pattern as [WarehouseDimens.MinTouchTarget]:
 * [D365MobileTheme] sets [scale] from the Settings text size, and every composable reading one
 * of these styles recomposes with it.
 */
object WarehouseType {
    var scale by mutableStateOf(1f)
        internal set

    /** Flow screen title in the header. */
    val title: TextStyle
        get() = TextStyle(
            fontFamily = AppFont, fontWeight = FontWeight.Bold,
            fontSize = 20.sp * scale, lineHeight = 25.sp * scale,
        )
    /** Body text - descriptions, the band's second line, list row titles (brief: 17-19sp). */
    val body: TextStyle
        get() = TextStyle(
            fontFamily = AppFont, fontWeight = FontWeight.Normal,
            fontSize = 18.sp * scale, lineHeight = 24.sp * scale,
        )
    /** The status band's second line (brief: 15-16sp). */
    val line: TextStyle
        get() = TextStyle(
            fontFamily = AppFont, fontWeight = FontWeight.Medium,
            fontSize = 16.sp * scale, lineHeight = 21.sp * scale,
        )
    /** Labels and subtitles (brief: at least 15sp). */
    val label: TextStyle
        get() = TextStyle(
            fontFamily = AppFont, fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp * scale, lineHeight = 20.sp * scale, letterSpacing = 0.2.sp,
        )
    /** Hardware key hints (F1-F4) - the smallest text the redesign allows. */
    val keyHint: TextStyle
        get() = TextStyle(
            fontFamily = AppFont, fontWeight = FontWeight.Bold,
            fontSize = 12.sp * scale, lineHeight = 15.sp * scale, letterSpacing = 0.5.sp,
        )

    /** Item and location/bin code on the next-item card. */
    val code: TextStyle
        get() = TextStyle(
            fontFamily = CodeFont, fontWeight = FontWeight.Bold,
            fontSize = 42.sp * scale, lineHeight = 46.sp * scale,
        )
    /** The big quantity on the next-item card (brief: 66-72sp). */
    val quantity: TextStyle
        get() = TextStyle(
            fontFamily = CodeFont, fontWeight = FontWeight.Bold,
            fontSize = 68.sp * scale, lineHeight = 72.sp * scale,
        )
    /** Document numbers and smaller codes inside lists and headers. */
    val codeSmall: TextStyle
        get() = TextStyle(
            fontFamily = CodeFont, fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp * scale, lineHeight = 26.sp * scale, letterSpacing = 0.3.sp,
        )
    /** Status band title (brief: 22-24sp, bold) - Nunito Sans, not [CodeFont]: it's a sentence. */
    val bandTitle: TextStyle
        get() = TextStyle(
            fontFamily = AppFont, fontWeight = FontWeight.Bold,
            fontSize = 23.sp * scale, lineHeight = 28.sp * scale,
        )
}

/** Every style in [AppFont] - the roles [warehouseTypography] leaves at Material's defaults too. */
private fun Typography.withFont(font: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = font), displayMedium = displayMedium.copy(fontFamily = font),
    displaySmall = displaySmall.copy(fontFamily = font), headlineLarge = headlineLarge.copy(fontFamily = font),
    headlineMedium = headlineMedium.copy(fontFamily = font), headlineSmall = headlineSmall.copy(fontFamily = font),
    titleLarge = titleLarge.copy(fontFamily = font), titleMedium = titleMedium.copy(fontFamily = font),
    titleSmall = titleSmall.copy(fontFamily = font), bodyLarge = bodyLarge.copy(fontFamily = font),
    bodyMedium = bodyMedium.copy(fontFamily = font), bodySmall = bodySmall.copy(fontFamily = font),
    labelLarge = labelLarge.copy(fontFamily = font), labelMedium = labelMedium.copy(fontFamily = font),
    labelSmall = labelSmall.copy(fontFamily = font),
)

private fun warehouseTypography(scale: Float) = Typography(
    displaySmall = TextStyle(
        fontFamily = AppFont, fontWeight = FontWeight.Medium,
        fontSize = 34.sp * scale, lineHeight = 38.sp * scale, letterSpacing = 0.3.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = AppFont, fontWeight = FontWeight.Medium,
        fontSize = 27.sp * scale, lineHeight = 32.sp * scale, letterSpacing = 0.3.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = AppFont, fontWeight = FontWeight.Medium,
        fontSize = 23.sp * scale, lineHeight = 28.sp * scale, letterSpacing = 0.2.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = AppFont, fontWeight = FontWeight.Medium,
        fontSize = 20.sp * scale, lineHeight = 25.sp * scale, letterSpacing = 0.2.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = AppFont, fontWeight = FontWeight.Medium,
        fontSize = 17.sp * scale, lineHeight = 22.sp * scale, letterSpacing = 0.2.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = AppFont, fontWeight = FontWeight.Normal,
        fontSize = 16.sp * scale, lineHeight = 22.sp * scale,
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFont, fontWeight = FontWeight.Normal,
        fontSize = 14.sp * scale, lineHeight = 19.sp * scale,
    ),
    labelLarge = TextStyle(
        fontFamily = AppFont, fontWeight = FontWeight.Medium,
        fontSize = 13.sp * scale, lineHeight = 17.sp * scale, letterSpacing = 0.4.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = AppFont, fontWeight = FontWeight.Medium,
        fontSize = 11.sp * scale, lineHeight = 15.sp * scale, letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = AppFont, fontWeight = FontWeight.Medium,
        fontSize = 10.sp * scale, lineHeight = 13.sp * scale, letterSpacing = 0.6.sp,
    ),
)

/**
 * Softly rounded (8-20dp), replacing the square-everywhere set this app used to carry, on
 * request: the layout it was restyled to match (white cards, rounded inputs, pill-ish buttons)
 * is rounded throughout. Every unshaped component (Card, OutlinedTextField, dialogs) reaches for
 * one of these five tokens; buttons and hand-built surfaces use [WarehouseDimens.CardCorner]
 * instead, which is kept in step with `medium` here.
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** Unscaled bases for [WarehouseDimens.MinTouchTarget]/[WarehouseDimens.StepperButton] - see
 * [D365MobileTheme]'s `SideEffect`, which multiplies these by the worker's chosen [DisplayScale]. */
private val BaseMinTouchTarget = 56.dp
private val BaseStepperButton = 64.dp

object WarehouseDimens {
    /**
     * Minimum interactive size. Sized for a gloved fingertip, not a bare one.
     *
     * `mutableStateOf`-backed, not a plain `val` - same reason as
     * [WarehouseStatusColors.accent]: [D365MobileTheme] updates this when the worker's chosen
     * [DisplayScale] changes, and every composable already reading `WarehouseDimens.MinTouchTarget`
     * recomposes, without needing a `CompositionLocal` plumbed through the app for it. Never goes
     * below [BaseMinTouchTarget] - [DisplayScale] only ever scales up from 100%, so this floor
     * can't be shrunk out from under a gloved fingertip by the setting meant to help reach it.
     */
    var MinTouchTarget by mutableStateOf(BaseMinTouchTarget)
        internal set
    val ScreenPadding = 16.dp
    val ItemSpacing = 12.dp
    /** Card/button corner - see [AppShapes]. Kept as its own token: call sites that build a
     * shape by hand (`RoundedCornerShape(WarehouseDimens.CardCorner)`, for a `Box`/custom-drawn
     * surface Material's own `Shapes` slots never reach, and the button wrappers) all follow it. */
    val CardCorner = 12.dp

    /** Same `mutableStateOf` treatment as [MinTouchTarget], and the same reason. */
    var StepperButton by mutableStateOf(BaseStepperButton)
        internal set

    /**
     * Chrome, not content - shrunk below Material3's own defaults (64dp / 80dp) on the same
     * "5,000-line scale" reasoning as the rest of this redesign: every dp the top bar and bottom
     * nav take is a dp the scan box, the line list or the matched-line card doesn't get, on a
     * screen an operator is staring at all shift. [TopBarHeight] is passed straight to
     * `TopAppBar`'s own `expandedHeight` param. [BottomBarHeight] matches [MinTouchTarget] rather
     * than Material3's 80dp `NavigationBar` default - still a full gloved-fingertip target, just
     * without the extra padding around it - because the bottom bar is rebuilt by hand in
     * [com.d365fo.mobileops.presentation.common.AppBottomBar] rather than using `NavigationBar`,
     * whose own container only takes a *minimum* height (`defaultMinSize`), not a settable one.
     */
    val TopBarHeight = 52.dp
    /** A `get()`, not a one-time copy of [MinTouchTarget] - [MinTouchTarget] itself changes live
     * with [DisplayScale], and a plain `val` here would have frozen at whatever it was on first
     * read instead of tracking it. */
    val BottomBarHeight: Dp get() = MinTouchTarget
}

@Composable
fun D365MobileTheme(
    palette: ColorPalette = ColorPalette.INDUSTRIAL,
    scale: DisplayScale = DisplayScale.STANDARD,
    content: @Composable () -> Unit,
) {
    // SideEffect, not a plain assignment in the composable body directly: this runs after every
    // successful (re)composition rather than during composition itself, which is where Compose
    // expects a side effect that writes shared/global state (as opposed to a Composable-scoped
    // remember) to live - see the official guidance on SideEffect for publishing Compose state
    // to non-Compose code. WarehouseStatusColors/WarehouseDimens are read all over this app as
    // plain objects, not through this composable's own scope, so this is what keeps them in sync
    // with [palette]/[scale].
    SideEffect {
        WarehouseStatusColors.accent = palette.accent
        WarehouseStatusColors.accentDim = palette.accentDim
        WarehouseStatusColors.accentDark = palette.accent.darken()
        WarehouseStatusColors.accentGradientStart = palette.gradientStart
        WarehouseDimens.MinTouchTarget = BaseMinTouchTarget * scale.factor
        WarehouseDimens.StepperButton = BaseStepperButton * scale.factor
        WarehouseType.scale = scale.factor
    }

    MaterialTheme(
        colorScheme = industrialColors(palette),
        typography = warehouseTypography(scale.factor).withFont(AppFont),
        shapes = AppShapes,
        content = content,
    )
}
