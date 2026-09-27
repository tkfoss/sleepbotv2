package com.sleepbot.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.sleepbot.app.R

/** Palette lifted from the 3.2.8 resources (colors.xml + hard-coded layout colors). */
object SB {
    val MainBg = Color(0xFF0B1932)
    val NamebarFill = Color(0xFF061228)
    val TabStrip = Color(0xFFD6DBE6)
    val EditorBg = Color(0xFF18243A)
    val TabColor = Color(0xFF101B2F)
    val PlotBg = Color(0xFF020B1C)
    val ListBg = Color(0xFF020C1E)
    val Overlay = Color(0xCC020C1E)
    val Divider = Color(0xFF1C557D)
    val TableHeader = Color(0xFF70CCFF)
    val GraphLine = Color(0xFF93DAFF)
    val Highlighted = Color(0xFF2790C9)
    val Baseline = Color(0x88C9F5FF)
    val GraphTitle = Color(0xFFC8ECFF)
    val GraphX = Color(0xFFB0B8C6)
    val DisplayHours = Color(0xFF8CE3FF)
    val DebtGreen = Color(0xFF599CE6)
    val DebtRed = Color(0xFFEDEDED)
    val HiddenText = Color(0xFF222B3D)
    val SpinnerLine = Color(0xFF2790CA)
    val HelpTitleBand = Color(0x55000000)
    val RatingOverlay = Color(0x80FFFF33)
    val GradientEdge = Color(0xFF091123)
    val GradientCenter = Color(0xFF1F2B40)
    val Error = Color(0xFFFF0000)
    val HoloBlue = Color(0xFF33B5E5)
}

val RobotoThin = FontFamily(Font(R.font.roboto_thin))
val RobotoRegular = FontFamily(Font(R.font.roboto_regular))

private val scheme = darkColorScheme(
    primary = SB.HoloBlue,
    onPrimary = Color.White,
    secondary = SB.GraphLine,
    background = SB.MainBg,
    onBackground = Color.White,
    surface = SB.EditorBg,
    onSurface = Color.White,
    surfaceContainerHigh = SB.EditorBg,
    surfaceContainer = SB.EditorBg,
    onSurfaceVariant = Color(0xFFBCC3D2),
    outline = SB.Divider,
)

@Composable
fun SleepBotTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
