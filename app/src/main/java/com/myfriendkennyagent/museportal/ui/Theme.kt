package com.myfriendkennyagent.museportal.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Portal platform tokens, from the portal skill's compose-theme.md. Never pure black or white. */
object PortalColors {
  val Blue = Color(0xFF1990FF)
  val BlueDark = Color(0xFF004CB0)
  val OnBlue = Color(0xFFF0F0F0)
  val Background = Color(0xFF1A1A1A)
  val Surface = Color(0xFF2B2B2B)
  val Body = Color(0xFFDADADA)
  val Dim = Color(0xFF9AA0AC)
  val Success = Color(0xFF6CD64F)
  val Error = Color(0xFFFA484E)
  val Amber = Color(0xFFFFB547)
  val Violet = Color(0xFF8B6CFF)
}

private val Scheme =
  darkColorScheme(
    primary = PortalColors.Blue,
    onPrimary = PortalColors.OnBlue,
    primaryContainer = PortalColors.BlueDark,
    onPrimaryContainer = Color(0xFFD4E3FF),
    secondary = Color(0xFFBEC6DC),
    onSecondary = PortalColors.OnBlue,
    error = PortalColors.Error,
    onError = PortalColors.OnBlue,
    background = PortalColors.Background,
    surface = PortalColors.Surface,
    onBackground = PortalColors.Body,
    onSurface = PortalColors.Body,
  )

/** Room-distance type: nothing under 14sp, body 18sp, captions large. */
private val PortalType =
  Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 44.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
  )

@Composable
fun PortalTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = Scheme, typography = PortalType, content = content)
}
