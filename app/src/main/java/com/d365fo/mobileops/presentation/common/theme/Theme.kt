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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d365fo.mobileops.R

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
 *
 * Two families carry the whole identity. Rajdhani - a compressed, technical display face - for
 * anything that is read at a glance: item counts, quantities, order numbers, section titles.
 * JetBrains Mono - a coding monospace - for anything that reads as *data*: item codes,
 * timestamps, status chips, dimension labels. Body copy stays on the platform's own sans
 * (Roboto) rather than bundling a third family for text nobody scans at speed.
 */
private val RajdhaniFamily = FontFamily(
    Font(R.font.rajdhani_medium, FontWeight.Medium),
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    Font(R.font.rajdhani_bold, FontWeight.Bold),
)

private val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    // No separate semibold cut was bundled - Compose synthesizes it from Regular, which is
    // close enough at the small sizes this face is used at (labels, codes, chips).
    Font(R.font.jetbrains_mono_regular, FontWeight.SemiBold),
)

/**
 * The light industrial palette - the mockup's own `@media (prefers-color-scheme: light)` /
 * `[data-theme="light"]` values, carried over as flat colours. The "dim" tints there are CSS
 * `rgba(accent, 10-12%)` over a white surface; translated to flat colours by blending at the
 * same alpha over white, since Compose colour roles here are drawn as solid fills, not overlays.
 */
private val Bg = Color(0xFFE8ECF5)
private val Bg2 = Color(0xFFF0F3FA)
private val Surface = Color(0xFFFFFFFF)
private val Surface2 = Color(0xFFF5F7FC)
// Darker than the mockup's own value on both of these - the mockup renders on a backlit
// monitor at reading distance; this renders on a handheld read at arm's length, often outdoors
// or under warehouse sodium lighting. Text3 at the original #9CA3AF measured roughly 2:1
// against white, well under WCAG AA's 4.5:1 floor for body text - and it is the colour every
// item code, hint, count and timestamp on this app is set in, not an edge case. Border moved
// the same direction for the same reason: a card edge or field outline that all but disappears
// against Bg/Surface is a real "which one is my input" question, not just a look.
private val Border = Color(0xFFAEB9D4)
private val Accent = Color(0xFF0066CC)
private val AccentDim = Color(0xFFE6F0F9)
private val Green = Color(0xFF16A34A)
private val GreenDim = Color(0xFFE3F4E9)
private val Amber = Color(0xFFD97706)
private val AmberDim = Color(0xFFFAEFE1)
private val Red = Color(0xFFDC2626)
private val RedDim = Color(0xFFFCE9E9)
private val Text1 = Color(0xFF111827)
private val Text2 = Color(0xFF4B5563)
private val Text3 = Color(0xFF5B6472)

/**
 * The one thing a worker can personalise: which accent colour reads as "this app" to them.
 * Everything else - status colours (green=ok, amber=warning, red=error), backgrounds, square
 * shapes - stays fixed regardless, for the same fleet-consistency reason the class doc above
 * gives for not branching on system dark mode: a supervisor's screen and a picker's screen still
 * have to agree on what "blocked" or "posted" looks like. Accent is the one role that's purely
 * identity, not meaning, so it's the one exposed as a choice.
 */
enum class ColorPalette(val label: String, val accent: Color, val accentDim: Color) {
    BLUE("Blue", Color(0xFF0066CC), Color(0xFFE6F0F9)),
    GREEN("Green", Color(0xFF15803D), Color(0xFFE1F3E8)),
    AMBER("Amber", Color(0xFFB45309), Color(0xFFF8EADC)),
    PURPLE("Purple", Color(0xFF7C3AED), Color(0xFFEEE5FC)),
}

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
    var accent by mutableStateOf(ColorPalette.BLUE.accent)
        internal set
    var accentDim by mutableStateOf(ColorPalette.BLUE.accentDim)
        internal set
    val green = Green
    val greenDim = GreenDim
    val amber = Amber
    val amberDim = AmberDim
    val red = Red
    val redDim = RedDim
    val border = Border
    val surface2 = Surface2
    val text3 = Text3
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
    outline = Border,
)

/**
 * Scaled up from the mockup's own on-image sizes (that reference render several phone screens
 * at once inside a webpage, so its type is compressed for that layout) to what an actual device
 * needs - read under sodium lighting, through a scratched screen protector, often at arm's
 * length. The mockup's *relative* hierarchy and letter-spacing character carry over; the
 * absolute sizes do not. Item numbers, quantities and order numbers stay large - those are what
 * a wrong reading actually costs.
 */
val WarehouseTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = RajdhaniFamily, fontWeight = FontWeight.Bold,
        fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = 0.3.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = RajdhaniFamily, fontWeight = FontWeight.Bold,
        fontSize = 27.sp, lineHeight = 32.sp, letterSpacing = 0.3.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = RajdhaniFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp, lineHeight = 28.sp, letterSpacing = 0.2.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = RajdhaniFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = 0.2.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = RajdhaniFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.2.sp,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(
        fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 17.sp, letterSpacing = 0.4.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Normal,
        fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Normal,
        fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.6.sp,
    ),
)

/**
 * Square, not Material's own default pill/rounded set - a Compose `Button` is fully rounded
 * (`MaterialTheme.shapes.full`) unless a theme overrides it, and every other unshaped component
 * (Card, OutlinedTextField, dialogs) reaches for one of these five tokens too. Left at Compose's
 * defaults, this whole app would render soft rounded-corner shapes at every one of those call
 * sites without a single line here saying so - not a look this identity chose, just an
 * unclaimed default. Zero corners are also strictly cheaper to draw than a rounded rect: no
 * corner path, no extra anti-aliasing pass at each of the four corners - real, if usually small,
 * savings repeated on every button and card on a screen redrawn every scan.
 */
private val SquareShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

object WarehouseDimens {
    /** Minimum interactive size. Sized for a gloved fingertip, not a bare one. */
    val MinTouchTarget = 56.dp
    val ScreenPadding = 16.dp
    val ItemSpacing = 12.dp
    /** Square - see [SquareShapes]. Kept as its own token rather than folded away: call sites
     * that build a shape by hand (`RoundedCornerShape(WarehouseDimens.CardCorner)`, for a
     * `Box`/custom-drawn surface Material's own `Shapes` slots never reach) still name what they
     * want, they just all currently want zero. */
    val CardCorner = 0.dp
    val StepperButton = 64.dp

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
    val BottomBarHeight = MinTouchTarget
}

@Composable
fun D365MobileTheme(
    palette: ColorPalette = ColorPalette.BLUE,
    content: @Composable () -> Unit,
) {
    // SideEffect, not a plain assignment in the composable body directly: this runs after every
    // successful (re)composition rather than during composition itself, which is where Compose
    // expects a side effect that writes shared/global state (as opposed to a Composable-scoped
    // remember) to live - see the official guidance on SideEffect for publishing Compose state
    // to non-Compose code. WarehouseStatusColors is read all over this app as a plain object,
    // not through this composable's own scope, so this is what keeps it in sync with [palette].
    SideEffect {
        WarehouseStatusColors.accent = palette.accent
        WarehouseStatusColors.accentDim = palette.accentDim
    }

    MaterialTheme(
        colorScheme = industrialColors(palette),
        typography = WarehouseTypography,
        shapes = SquareShapes,
        content = content,
    )
}
