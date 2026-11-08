package dev.rovsti.testing

/** Extra renderings of a screenshot on top of the always-present light and dark ones. */
enum class ShotVariant(
    val suffix: String,
) {
    /** System font scale 2.0. */
    Fs2("fs2"),

    /** Right-to-left layout direction. */
    Rtl("rtl"),

    /** 891 x 411 dp landscape window. */
    Landscape("landscape"),
}
