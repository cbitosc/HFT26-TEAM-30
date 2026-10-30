package com.slackers.emergencymesh.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MeshColors = darkColorScheme(
    primary = Color(0xFFFFC857),
    onPrimary = Color(0xFF231A00),
    primaryContainer = Color(0xFF493800),
    onPrimaryContainer = Color(0xFFFFE3A1),
    secondary = Color(0xFF89D5C9),
    onSecondary = Color(0xFF002E28),
    background = Color(0xFF0C1520),
    onBackground = Color(0xFFEDF2F8),
    surface = Color(0xFF111E2C),
    onSurface = Color(0xFFEDF2F8),
    surfaceVariant = Color(0xFF233243),
    onSurfaceVariant = Color(0xFFBCC8D7),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF63231F),
    onErrorContainer = Color(0xFFFFDAD5),
)

@Composable
fun EmergencyMeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MeshColors, content = content)
}
