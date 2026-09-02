package com.leumas.robot.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Industrial-console palette.
 *
 * **Change 13.1** flipped the arena from a near-black console to a light studio grey — a
 * photographic cyclorama rather than a dark room. The red machine is the only strongly saturated
 * thing in the frame and now reads against a bright ground instead of disappearing into a dark one.
 * The HUD moved with it: the cards are light glass with dark text, so they stay legible over the
 * pale backdrop without becoming heavy blocks sitting on the animation (spec §22).
 */
object LeumasColors {

    /**
     * Studio backdrop — one flat, even tone (change 14.1).
     *
     * This was a wall gradient over a floor gradient with a darker apron, which left a hard horizon
     * seam across the middle of the arena. The requirement is an even field that "perfectly
     * contrasts the robot", so all of it collapses to this single colour: dark enough that the
     * chrome rods and the white slewing ring read against it, light enough that the near-black
     * joints stay clearly separate.
     */
    val Backdrop = Color(0xFFBFC5CC)

    val Background = Backdrop
    val Surface = Color(0xFFEFF3F7)
    val SurfaceRaised = Color(0xFFE2E8EF)
    val Outline = Color(0xFFA9B4C0)
    val OnSurface = Color(0xFF1B222B)
    val OnSurfaceDim = Color(0xFF5A6675)

    /**
     * Status accents, darkened from the old dark-theme values so they hold contrast as *text* on
     * light glass rather than as glowing fills on black.
     */
    val Running = Color(0xFF1E9E52)
    val Transfer = Color(0xFFB87708)
    val Idle = Color(0xFFC2352F)
    val Info = Color(0xFF1E74B8)

    /** Robot livery, taken from the reference machine. Unchanged — this is what must pop. */
    val RobotRed = Color(0xFFC41E24)
    val RobotRedHi = Color(0xFFF04A50)
    val RobotRedLo = Color(0xFF7E1015)
    val Black = Color(0xFF1E1E1E)
    val BlackHi = Color(0xFF4A4A4A)
    val Chrome = Color(0xFFC8CDD2)
    val ChromeHi = Color(0xFFF2F5F8)
    val ChromeLo = Color(0xFF78808A)
    val BearingRing = Color(0xFFE6E6E4)

    /**
     * Sequence-intro gradient (change 13.6), iOS-18-Siri style.
     *
     * Change 14.6 dropped the pink and pulled the whole ring toward blue: it is now blue, azure and
     * cyan with a single cool indigo, so the border reads as a luminous blue halo rather than a
     * rainbow.
     */
    val GlowBlue = Color(0xFF2E6BFF)
    val GlowAzure = Color(0xFF4FA8FF)
    val GlowCyan = Color(0xFF25D9E8)

    /** Luminous neon for the recipe name on the intro card (change 38). */
    val NeonAqua = Color(0xFF19F0FF)
    val GlowIndigo = Color(0xFF6C63FF)
}

/**
 * The one glass look shared by every floating panel.
 *
 * Change 13.4: the calibrate and panels boxes were opaque while the task card was already
 * translucent, so they blocked the arena. They all pull from here now, so the HUD is one family and
 * the machine stays visible through it.
 */
object LeumasGlass {

    /** Card fill. Translucent enough to see the robot through, opaque enough to read text on. */
    const val PANEL_ALPHA = 0.62f

    /** Buttons sit slightly more solid than panels so they still read as controls. */
    const val BUTTON_ALPHA = 0.70f

    /** Alpha a panel fades to while the arm is working behind it. */
    const val GHOST_ALPHA = 0.14f

    fun panel(alpha: Float = PANEL_ALPHA): Brush = Brush.verticalGradient(
        0f to Color(0xFFFFFFFF).copy(alpha = alpha),
        1f to Color(0xFFDCE4EC).copy(alpha = alpha),
    )

    fun button(alpha: Float = BUTTON_ALPHA): Brush = Brush.verticalGradient(
        0f to Color(0xFFFFFFFF).copy(alpha = alpha),
        1f to Color(0xFFD5DEE7).copy(alpha = alpha),
    )
}

private val LightScheme = lightColorScheme(
    primary = LeumasColors.Info,
    background = LeumasColors.Background,
    surface = LeumasColors.Surface,
    surfaceVariant = LeumasColors.SurfaceRaised,
    outline = LeumasColors.Outline,
    onPrimary = Color.White,
    onBackground = LeumasColors.OnSurface,
    onSurface = LeumasColors.OnSurface,
    onSurfaceVariant = LeumasColors.OnSurfaceDim,
    error = LeumasColors.Idle,
)

@Composable
fun LeumasRobotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightScheme,
        typography = Typography(),
        content = content,
    )
}
