package com.takeone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Tk {
    val Bg = Color(0xFF0A0A0C)
    val Card = Color(0xFF16171B)
    val CardHigh = Color(0xFF202127)
    val Line = Color(0xFF2A2B31)
    val Rec = Color(0xFFFF3B30)
    val Accent = Color(0xFF4FD1C5)
    val Wave = Color(0xFFE8E8EA)
    val WaveDim = Color(0xFF55565C)
    val Dim = Color(0xFF9A9BA1)
}

private val scheme = darkColorScheme(
    primary = Tk.Accent,
    onPrimary = Color.Black,
    secondary = Tk.Accent,
    background = Tk.Bg,
    onBackground = Color.White,
    surface = Tk.Bg,
    onSurface = Color.White,
    surfaceVariant = Tk.Card,
    onSurfaceVariant = Tk.Dim,
    surfaceContainerLow = Tk.Card,
    surfaceContainer = Tk.Card,
    surfaceContainerHigh = Tk.CardHigh,
    surfaceContainerHighest = Tk.CardHigh,
    outline = Tk.Line,
    outlineVariant = Tk.Line,
    error = Tk.Rec,
)

@Composable
fun TakeOneTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
