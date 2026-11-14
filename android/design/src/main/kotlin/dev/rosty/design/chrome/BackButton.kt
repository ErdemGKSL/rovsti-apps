// Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/FrostedTopBar.kt (GlassCircleButton with a chevron.left symbol); the mirrored chevron and
// the 48 dp touch target around the 44 dp circle are Rosty's own.
package dev.rosty.design.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.rosty.design.R
import dev.rosty.design.controls.GlassCircleButton
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.foundation.LocalIosContentColor
import dev.rosty.design.theme.Sizes

/**
 * The circular glass back button of a pushed page: Convx's [GlassCircleButton] (a 44 dp circle in a 48 dp touch target)
 * with a chevron that points to the start edge, so it is mirrored in right-to-left layouts. Its accessibility name is
 * [label] ("Back").
 */
@Composable
fun BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.design_back),
    onLongClick: (() -> Unit)? = null,
) {
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.graphicsLayer { scaleX = if (mirrored) -1f else 1f }) {
        GlassCircleButton(
            onClick = onClick,
            onLongClick = onLongClick,
            size = Sizes.button,
            touchTarget = Sizes.touch,
            debugTag = "button.icon",
            modifier = Modifier.semantics { contentDescription = label }.testTag("back-button"),
        ) {
            RostyIcon(RostyIcons.Back, null, tint = LocalIosContentColor.current, size = BackIconSize)
        }
    }
}

private val BackIconSize = 20.dp
