package dev.rovsti.shell.permissions

import androidx.compose.ui.unit.dp

/** Sizes of the permission UI that the design system has no token for. */
internal object PermissionMetrics {
    /** The round icon of the rationale sheet, and its glyph. */
    val heroTile = 64.dp
    val heroGlyph = 32.dp

    /** The rounded tile that leads a row, and its glyph. */
    val rowTile = 32.dp
    val rowTileCorner = 8.dp
    val rowGlyph = 20.dp
}

/** Test tags of the rationale sheet. */
internal object PermissionTags {
    const val Rationale = "permission-rationale"
    const val Continue = "permission-continue"
    const val NotNow = "permission-not-now"
}
