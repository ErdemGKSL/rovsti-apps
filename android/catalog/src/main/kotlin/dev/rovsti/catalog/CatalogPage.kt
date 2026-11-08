package dev.rovsti.catalog

/** Every page of the catalog. Later E1 stories add one entry per component group. */
enum class CatalogPage(
    val title: String,
) {
    Home("Rovsti catalog"),
    Toolchain("Toolchain"),
    ThemePalette("Theme: palette"),
    ThemeType("Theme: type scale"),
    Motion("Motion: spring curves"),
    Press("Press feedback"),
    Glass("Glass"),
    Buttons("Buttons"),
    Rows("Rows"),
    GroupedSection("Grouped sections"),
    Avatars("Avatars"),
    Chips("Chips"),
    EmptyState("Empty states"),
}
