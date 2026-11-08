package dev.rovsti.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The Rovsti palette. iOS-like system values, with the two accessibility fixes the old UI lacked:
 * [labelSecondary] reaches 4.5:1 on every page, grouped and glass fill (X5), and a selected state is a
 * filled [accent] with [onAccent] text, also at least 4.5:1 (X7). `ContrastTest` measures all of it.
 *
 * [glassScrim] sits between the backdrop and a glass fill. Glass is translucent, so a label on it would otherwise
 * meet whatever is behind (white under dark glass, black under light glass); the scrim pulls the extremes toward
 * the surface so the labels keep their ratio. Glass drawing (E1.5) paints it under the fill.
 */
@Immutable
data class RovstiColors(
    val page: Color,
    val groupedBackground: Color,
    val groupedSurface: Color,
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val separator: Color,
    val opaqueSeparator: Color,
    val fillPrimary: Color,
    val fillSecondary: Color,
    val fillTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val destructive: Color,
    val success: Color,
    val glassScrim: Color,
    val isDark: Boolean,
)

val LightColors =
    RovstiColors(
        page = Color(0xFFF2F2F7),
        groupedBackground = Color(0xFFF2F2F7),
        groupedSurface = Color(0xFFFFFFFF),
        labelPrimary = Color(0xFF000000),
        labelSecondary = Color(0xE63C3C43),
        labelTertiary = Color(0xB33C3C43),
        separator = Color(0x4A3C3C43),
        opaqueSeparator = Color(0xFFC6C6C8),
        fillPrimary = Color(0x33787880),
        fillSecondary = Color(0x29787880),
        fillTertiary = Color(0x1F767680),
        accent = Color(0xFF0051D5),
        onAccent = Color(0xFFFFFFFF),
        destructive = Color(0xFFC8202A),
        success = Color(0xFF18732D),
        glassScrim = Color(0x66FFFFFF),
        isDark = false,
    )

val DarkColors =
    RovstiColors(
        page = Color(0xFF000000),
        groupedBackground = Color(0xFF000000),
        groupedSurface = Color(0xFF1C1C1E),
        labelPrimary = Color(0xFFFFFFFF),
        labelSecondary = Color(0xD9EBEBF5),
        labelTertiary = Color(0x99EBEBF5),
        separator = Color(0x99545458),
        opaqueSeparator = Color(0xFF38383A),
        fillPrimary = Color(0x5C787880),
        fillSecondary = Color(0x52787880),
        fillTertiary = Color(0x3D767680),
        accent = Color(0xFF5AB0FF),
        onAccent = Color(0xFF001A3A),
        destructive = Color(0xFFFF8A84),
        success = Color(0xFF5AE17F),
        glassScrim = Color(0x66000000),
        isDark = true,
    )
