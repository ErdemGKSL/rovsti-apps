package dev.rosty.messages.schedule

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.messages.conversation.ConversationActions
import dev.rosty.messages.conversation.ConversationSample
import dev.rosty.messages.conversation.ConversationScreen
import dev.rosty.messages.conversation.ConversationTags
import dev.rosty.testing.FixedClock
import dev.rosty.testing.ShotTest
import dev.rosty.testing.gradientImage
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId
import java.util.Locale

/** E6.10 screenshots (the Send later sheet, the Scheduled strip) and the long press on Send that opens the sheet. */
@Config(sdk = [35])
class E610ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    /** Files are drawn as they are on disk: the fake loader's hue follows the path, which changes run to run. */
    override val imageProducer: (Any, Int, Int) -> Bitmap? = { data, w, h ->
        if (data is File && data.exists()) BitmapFactory.decodeFile(data.path) else gradientImage(data, w, h)
    }

    private val picture by lazy { ConversationSample.picture(RuntimeEnvironment.getApplication().cacheDir) }

    /** 2026-01-15 10:30 UTC, the clock of every shot. */
    private val now = FixedClock.DEFAULT_MILLIS
    private val hour = 3_600_000L
    private val utc = ZoneId.of("UTC")
    private val english = ScheduleLabels(utc, Locale.US, true, ScheduleDayWords("Today", "Tomorrow"))
    private val turkish = ScheduleLabels(utc, Locale.forLanguageTag("tr"), true, ScheduleDayWords("Bugün", "Yarın"))

    private val quick =
        persistentListOf(
            QuickPick("In 1 hour", now + hour),
            QuickPick("This evening", now + 7 * hour + 30 * 60_000),
            QuickPick("Tomorrow morning", now + 22 * hour + 30 * 60_000),
        )

    @Composable
    private fun Thread(
        overlays: OverlayController,
        text: String = "",
        strip: ScheduledStripUi = ScheduledStripUi(),
        words: ScheduleWords = EnglishScheduleWords.words,
        onSend: (String) -> Unit = {},
        onSendLongPress: (String) -> Unit = {},
    ) {
        OverlayHost(overlays) {
            ConversationScreen(
                state = ConversationSample.state(picture),
                composer = rememberTextFieldState(text),
                listState = rememberLazyListState(),
                actions =
                    remember(onSend) {
                        ConversationActions(
                            onBack = {},
                            onSend = onSend,
                            onLoadOlder = {},
                            menuFor = { persistentListOf() },
                        )
                    },
                sendLabel = "Send",
                olderLabel = "Load Earlier",
                onSendLongPress = onSendLongPress,
                sendLaterLabel = words.title,
                aboveComposer = { ScheduledStrip(strip, words, {}, {}) },
            )
        }
    }

    private fun snap(
        name: String,
        fontScale: Float = 1f,
        content: @Composable (OverlayController) -> Unit,
        after: (OverlayController) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val overlays = OverlayController()
            composeFrame(dark = dark, fontScale = fontScale) { content(overlays) }
            advance(600)
            compose.runOnUiThread { after(overlays) }
            // The sheet's spring needs frames: one long advance runs only its first.
            repeat(15) { advance(100) }
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    private fun sheet(
        words: ScheduleWords,
        labels: ScheduleLabels,
        body: String,
        lateNote: Boolean,
        error: String? = null,
        tall: Boolean = false,
    ): (OverlayController) -> Unit =
        { overlays ->
            val state = ScheduleSheetState(labels.initialPick(now)).also { it.error = error }
            overlays.presentSchedule(ScheduleSheetUi(body, quick, lateNote, tall), state, words, labels, {}, {})
        }

    @Test fun scheduleSheet() =
        snap(
            "e6.10-schedule-sheet",
            content = { Thread(it, text = "See you at six, I'll bring the tickets") },
            after =
                sheet(
                    EnglishScheduleWords.words,
                    english,
                    "See you at six, I'll bring the tickets",
                    lateNote = false,
                ),
        )

    @Test fun scheduleSheetTurkishFontScale2WithTheLateNote() =
        snap(
            "e6.10-schedule-sheet-tr-fs2",
            fontScale = 2f,
            content = {
                Thread(
                    it,
                    text = "Altıda görüşürüz, biletleri ben getiririm",
                    words = TurkishScheduleWords.words,
                )
            },
            after =
                sheet(
                    TurkishScheduleWords.words,
                    turkish,
                    "Altıda görüşürüz, biletleri ben getiririm",
                    lateNote = true,
                    error = TurkishScheduleWords.words.tooSoon,
                    tall = true,
                ),
        )

    private fun strip(
        words: ScheduleWords,
        labels: ScheduleLabels,
        texts: List<String>,
    ): ScheduledStripUi {
        val items =
            texts.mapIndexed { i, text ->
                StripItem(StripKey.Gateway("s$i"), now + (i * 9 + 1) * hour, text, failed = i == 0)
            }
        return ScheduledStripUi.of(items, expanded = false, now, words, labels)
    }

    private val englishTexts =
        listOf(
            "Happy birthday! Have a wonderful day",
            "See you at six, I'll bring the tickets and the snacks for everyone",
            "Don't forget the keys",
            "Good morning",
            "Call me when you land",
        )

    private val turkishTexts =
        listOf(
            "Doğum günün kutlu olsun! Harika bir gün geçir",
            "Altıda görüşürüz, biletleri ve herkes için atıştırmalıkları ben getiririm",
            "Anahtarları unutma",
            "Günaydın",
            "İnince beni ara",
        )

    @Test fun scheduledStrip() =
        snap("e6.10-scheduled-strip", content = {
            Thread(it, strip = strip(EnglishScheduleWords.words, english, englishTexts))
        })

    @Test fun scheduledStripTurkish() =
        snap("e6.10-scheduled-strip-tr", content = {
            Thread(
                it,
                strip = strip(TurkishScheduleWords.words, turkish, turkishTexts),
                words = TurkishScheduleWords.words,
            )
        })

    @Test fun scheduledStripTurkishFontScale2() =
        snap("e6.10-scheduled-strip-tr-fs2", fontScale = 2f, content = {
            Thread(
                it,
                strip = strip(TurkishScheduleWords.words, turkish, turkishTexts),
                words = TurkishScheduleWords.words,
            )
        })

    @Test fun aLongPressOnSendAsksToScheduleAndATapStillSends() {
        val sent = ArrayList<String>()
        val later = ArrayList<String>()
        composeFrame {
            Thread(OverlayController(), text = "See you at six", onSend = { sent += it }, onSendLongPress = {
                later +=
                    it
            })
        }
        advance(600)
        val composer = compose.onNodeWithTag(ConversationTags.Composer)
        val density = compose.activity.resources.displayMetrics.density
        val inset = 22 * density

        composer.performTouchInput {
            longClick(
                Offset(right - left - inset, bottom - top - inset),
                durationMillis = 900,
            )
        }
        advance(300)
        assertEquals(listOf("See you at six"), later)
        assertTrue("the long press sends nothing", sent.isEmpty())

        // A long press on the text is the text field's own (selection), not Send later.
        composer.performTouchInput { longClick(Offset(inset, (bottom - top) / 2), durationMillis = 900) }
        advance(300)
        assertEquals(1, later.size)

        composer.performTouchInput { click(Offset(right - left - inset, bottom - top - inset)) }
        advance(300)
        assertEquals(listOf("See you at six"), sent)
        assertEquals(1, later.size)
    }

    @Test fun theSheetSchedulesAQuickTimeAndRefusesAPickInThePast() {
        val picked = ArrayList<Long>()
        val overlays = OverlayController()
        val state = ScheduleSheetState(CustomPick(0, 9 * 60))
        composeFrame { Thread(overlays) }
        compose.runOnUiThread {
            overlays.presentSchedule(
                ScheduleSheetUi("See you", quick),
                state,
                EnglishScheduleWords.words,
                english,
                { picked += it },
                {},
            )
        }
        repeat(15) { advance(100) }

        compose.onNodeWithTag(ScheduleTags.quick(0)).performClick()
        advance(200)
        assertEquals(listOf(now + hour), picked)

        // 09:00 today has passed (it is 10:30): the sheet says so and schedules nothing.
        compose.onNodeWithTag(ScheduleTags.Confirm).performClick()
        advance(200)
        assertEquals(EnglishScheduleWords.words.tooSoon, state.error)
        assertEquals(1, picked.size)

        // A day later the same time is fine.
        compose.onNodeWithTag(ScheduleTags.DayLater).performClick()
        advance(200)
        compose.onNodeWithTag(ScheduleTags.Confirm).performClick()
        advance(200)
        assertEquals(null, state.error)
        assertEquals(english.atMs(CustomPick(1, 9 * 60), now), picked.last())
    }
}
