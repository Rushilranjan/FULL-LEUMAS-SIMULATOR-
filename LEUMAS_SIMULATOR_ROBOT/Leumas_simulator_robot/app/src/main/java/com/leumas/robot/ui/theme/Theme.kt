package com.leumas.robot.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Industrial-console palette. Deliberately dark and low-chroma so the red robot and the status
 * colours are the only saturated things on screen (spec §22: this must not look like a generic
 * Android utility app).
 */
object LeumasColors {
    val Background = Color(0xFF0B0D10)
    val Surface = Color(0xFF14181D)
    val SurfaceRaised = Color(0xFF1C2229)
    val Outline = Color(0xFF2C343D)
    val OnSurface = Color(0xFFD7DEE6)
    val OnSurfaceDim = Color(0xFF8A96A3)

    /** Status accents. */
    val Running = Color(0xFF35C46A)
    val Transfer = Color(0xFFE8B23A)
    val Idle = Color(0xFFCC3B3B)
    val Info = Color(0xFF4FA8E8)

    /** Robot livery, taken from the reference machine. */
    val RobotRed = Color(0xFFC41E24)
    val RobotRedHi = Color(0xFFF04A50)
    val RobotRedLo = Color(0xFF7E1015)
    val Black = Color(0xFF1E1E1E)
    val BlackHi = Color(0xFF4A4A4A)
    val Chrome = Color(0xFFC8CDD2)
    val ChromeHi = Color(0xFFF2F5F8)
    val ChromeLo = Color(0xFF78808A)
    val BearingRing = Color(0xFFE6E6E4)
}

private val DarkScheme = darkColorScheme(
    primary = LeumasColors.Info,
    background = LeumasColors.Background,
    surface = LeumasColors.Surface,
    surfaceVariant = LeumasColors.SurfaceRaised,
    outline = LeumasColors.Outline,
    onPrimary = Color.Black,
    onBackground = LeumasColors.OnSurface,
    onSurface = LeumasColors.OnSurface,
    onSurfaceVariant = LeumasColors.OnSurfaceDim,
    error = LeumasColors.Idle,
)

@Composable
fun LeumasRobotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = Typography(),
        content = content,
    )
}
