package dev.rosty.messages.theme

import androidx.compose.ui.unit.dp

/** The sizes of bubble media, the reply swipe and the composer bars (E6.4b) that are not design tokens. */
internal object MediaMetrics {
    /** A picture in a bubble is at most this wide, so a portrait one does not fill the screen. */
    val PictureMax = 232.dp

    /** The round mark over a picture (clock, spinner, play, download). */
    val Mark = 44.dp
    val MarkGlyph = 20.dp
    val MarkStroke = 2.5.dp

    /** The play button of a voice note and its bar. */
    val VoicePlay = 40.dp
    val VoiceGlyph = 16.dp
    val VoiceTrack = 4.dp
    val VoiceThumb = 12.dp
    val VoiceWidth = 220.dp

    /** The bar is this tall for the finger. */
    val VoiceTouch = 32.dp
    val SpeedPillWidth = 44.dp
    val SpeedPillHeight = 28.dp

    /** The tile of a file row. */
    val FileTile = 40.dp
    val FileAction = 40.dp

    /** M31: the swipe commits past this, and the bubble never travels further than [SwipeMax] (GPUI 64 / 96). */
    val SwipeCommit = 64.dp
    val SwipeMax = 96.dp
    val ReplyGlyph = 32.dp

    /** The quoted bar and the attachment bar above the composer. */
    val BarStripe = 3.dp
    val BarThumb = 40.dp
    val BarButton = 32.dp
    val AttachButton = 36.dp

    /** The composer pill is 44 dp tall: the attach button sits centred beside its last line. */
    val AttachLift = 4.dp

    /** The sender card (M50). */
    val CardAvatar = 48.dp
    val CardWidth = 280.dp
    val Hairline = 0.5.dp
}
