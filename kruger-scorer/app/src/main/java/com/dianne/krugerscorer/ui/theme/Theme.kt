package com.dianne.krugerscorer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dianne.krugerscorer.R

val DianneDisplay = FontFamily(
    Font(R.font.bebas_neue_regular, FontWeight.Normal)
)

val DianneBody = FontFamily(
    Font(R.font.source_sans3_regular, FontWeight.Normal),
    Font(R.font.source_sans3_semibold, FontWeight.SemiBold),
    Font(R.font.source_sans3_bold, FontWeight.Bold)
)

private val Forest = Color(0xFF1A1F16)
private val Moss = Color(0xFF2C3824)
private val Leaf = Color(0xFF7CB342)
private val Cream = Color(0xFFE8E0D0)
private val Sand = Color(0xFFC4B8A0)
private val Amber = Color(0xFFE6A23C)

private val ColorScheme = darkColorScheme(
    primary = Leaf,
    onPrimary = Forest,
    secondary = Amber,
    onSecondary = Forest,
    background = Forest,
    onBackground = Cream,
    surface = Moss,
    onSurface = Cream,
    surfaceVariant = Color(0xFF3A4532),
    onSurfaceVariant = Sand,
    outline = Color(0xFF5A6650)
)

@Composable
fun DianneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        typography = MaterialTheme.typography.copy(
            displayLarge = TextStyle(
                fontFamily = DianneDisplay,
                fontWeight = FontWeight.Normal,
                fontSize = 56.sp,
                letterSpacing = 1.5.sp,
                color = Cream
            ),
            headlineMedium = TextStyle(
                fontFamily = DianneDisplay,
                fontWeight = FontWeight.Normal,
                fontSize = 32.sp,
                letterSpacing = 1.sp,
                color = Cream
            ),
            titleLarge = TextStyle(
                fontFamily = DianneBody,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = Cream
            ),
            bodyLarge = TextStyle(
                fontFamily = DianneBody,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                color = Cream
            ),
            bodyMedium = TextStyle(
                fontFamily = DianneBody,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                color = Sand
            ),
            labelLarge = TextStyle(
                fontFamily = DianneBody,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Cream
            )
        ),
        content = content
    )
}
