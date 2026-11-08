package dev.rovsti.catalog

/** Every page of the catalog. Later E1 stories add one entry per component group. */
enum class CatalogPage(
    val title: String,
) {
    Home("Rovsti catalog"),
    Toolchain("Toolchain"),
}
