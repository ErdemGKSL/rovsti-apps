package dev.rovsti.contacts.people

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.people.AvatarResolver
import dev.rovsti.people.PeopleIndex
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * X.1c: a picture that is in the cache when the avatar is first composed is there on the first frame (no fade, no
 * initials flash); one that arrives while the avatar is on screen fades in over 220 ms, alpha only; under reduce
 * motion it just appears.
 */
@Config(sdk = [35])
class FirstFrameAvatarTest : ShotTest() {
    private val number = "+90 532 111 22 33"
    private val face = AvatarFace(own = null, numbers = listOf(number))

    private fun nodeKind() =
        compose
            .onNodeWithTag("avatar", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(AvatarKind)

    private fun nodeAlpha() =
        compose
            .onNodeWithTag("avatar", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(AvatarPictureAlpha)

    private fun show(
        resolver: AvatarResolver,
        reduceMotion: Boolean = false,
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalAvatarResolver provides resolver,
                LocalReduceMotion provides reduceMotion,
                dev.rovsti.design.media.LocalRovstiImageLoader provides images.loader,
            ) {
                ContactAvatar("Ayşe Yılmaz", face, Modifier.testTag("avatar"))
            }
        }
        compose.waitForIdle()
    }

    private fun warmIndex(): PeopleIndex =
        PeopleIndex().also {
            it.replace(listOf(waPerson(number))) { "file:///people/a.jpg" }
        }

    @Test fun aCachedPictureIsOnTheFirstFrame() {
        show(AvatarResolver(warmIndex()))
        assertEquals("whatsapp", nodeKind())
        assertEquals("no fade for a picture that was there", 1f, nodeAlpha())
    }

    @Test fun anEmptyCacheShowsInitials() {
        show(AvatarResolver(PeopleIndex()))
        assertEquals("initials", nodeKind())
    }

    @Test fun aPictureThatArrivesLaterFadesInOver220Ms() {
        val index = PeopleIndex()
        show(AvatarResolver(index))
        assertEquals("initials", nodeKind())
        compose.runOnUiThread { index.replace(listOf(waPerson(number))) { "file:///people/a.jpg" } }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals("whatsapp", nodeKind())
        assertEquals("starts transparent over the initials", 0f, nodeAlpha())
        compose.mainClock.advanceTimeBy(110)
        compose.waitForIdle()
        val mid = checkNotNull(nodeAlpha())
        assertTrue("half way: $mid", mid > 0f && mid < 1f)
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertEquals(1f, nodeAlpha())
    }

    @Test fun underReduceMotionAPictureThatArrivesLaterJustAppears() {
        val index = PeopleIndex()
        show(AvatarResolver(index), reduceMotion = true)
        compose.runOnUiThread { index.replace(listOf(waPerson(number))) { "file:///people/a.jpg" } }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals("whatsapp", nodeKind())
        assertEquals(1f, nodeAlpha())
    }

    @Test fun theOwnPhotoNeverFadesAndWins() {
        val own = AvatarFace(own = "content://contacts/photo/1", numbers = listOf(number))
        compose.setContent {
            CompositionLocalProvider(
                LocalAvatarResolver provides AvatarResolver(warmIndex()),
                dev.rovsti.design.media.LocalRovstiImageLoader provides images.loader,
            ) { ContactAvatar("Ayşe Yılmaz", own, Modifier.testTag("avatar")) }
        }
        compose.waitForIdle()
        assertEquals("own", nodeKind())
        assertEquals(1f, nodeAlpha())
    }
}
