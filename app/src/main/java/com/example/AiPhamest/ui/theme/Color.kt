// ui/theme/Color.kt
package com.example.AiPhamest.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ColorScheme

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650A4)
val PurpleGrey40 = Color(0xFF625B71)
val Pink40 = Color(0xFF7D5260)

// App-specific status colors
val SuccessGreen = Color(0xFF4CAF50)
val ErrorRed    = Color(0xFFF44336)
val InfoBlue    = Color(0xFF2196F3)

// Extensions so you can do MaterialTheme.colorScheme.success, .error, .info
val ColorScheme.success: Color
    get() = SuccessGreen

val ColorScheme.error: Color
    get() = ErrorRed

val ColorScheme.info: Color
    get() = InfoBlue
