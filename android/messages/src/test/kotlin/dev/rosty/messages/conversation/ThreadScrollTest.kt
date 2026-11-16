package dev.rosty.messages.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The thread scroll model (plan `thread-scroll.md`, table 8.1). Rows are written newest first: `i` incoming, `o`
 * outgoing, `p` outgoing pending, `U` the unread divider, `L` a label. Slop 100 px.
 */
class ThreadScrollTest {
    private val limits = ScrollLimits(slopPx = 100)

    private class Rows(
        spec: String,
        /** Pending bubbles that went out: same key, now plain outgoing. */
        private val sent: Set<String> = emptySet(),
    ) : ScrollRows {
        val keys = spec.split(' ').filter { it.isNotBlank() }

        override val size get() = keys.size

        override fun key(index: Int) = keys[index]

        override fun kind(index: Int) =
            when (if (keys[index] in sent) 'o' else keys[index].first()) {
                'i' -> RowKind.Incoming
                'o' -> RowKind.Outgoing
                'p' -> RowKind.OutgoingPending
                'U' -> RowKind.Unread
                else -> RowKind.Label
            }
    }

    private fun rows(spec: String) = Rows(spec)

    private var now = 1_000_000L
    private var state = ThreadScroll()
    private var command: ScrollCommand? = null

    private fun env(
        resumed: Boolean = true,
        held: Boolean = false,
        ready: Boolean = true,
        atTarget: Boolean = false,
    ) = ScrollEnv(now, resumed, held, ready, atTarget)

    private fun send(event: ScrollEvent): ScrollCommand? {
        val (next, cmd) = reduce(state, event, limits)
        state = next
        command = cmd
        return cmd
    }

    private fun items(
        spec: String,
        env: ScrollEnv = env(),
    ) = send(ScrollEvent.Items(rows(spec), env))

    private fun moved(
        index: Int,
        offset: Int,
        spec: String,
        scrolling: Boolean = false,
        held: Boolean = false,
        key: String? = rows(spec).keys.getOrNull(index),
    ) = send(ScrollEvent.Moved(ScrollSpot(index, offset, key), scrolling, rows(spec), held))

    /** Live, Following, rows `[i3 i2 i1]`, settled. */
    private fun live(spec: String = BASE) {
        state = ThreadScroll()
        items(spec)
        now += 10_000
        assertEquals(ScrollPhase.Live, state.phase)
        assertEquals(ScrollMode.Following, state.mode)
    }

    private fun anchoredOnI2() {
        live()
        moved(1, 20, BASE, scrolling = true)
        assertEquals(ScrollMode.Anchored("i2", 20), state.mode)
    }

    @Test fun t01_oneNewRowSlidesIn() {
        live()
        assertEquals(ScrollCommand.ToNewest(animated = true), items("i4 $BASE"))
        assertEquals(ScrollMode.Following, state.mode)
        assertEquals(0, state.unseen)
    }

    @Test fun t02_manyNewRowsAppearAtOnce() {
        live()
        assertEquals(ScrollCommand.ToNewest(animated = false), items("i7 i6 i5 i4 $BASE"))
    }

    @Test fun t03_notResumedAppearsAtOnce() {
        live()
        assertEquals(ScrollCommand.ToNewest(animated = false), items("i4 $BASE", env(resumed = false)))
    }

    @Test fun t04_rightAfterReturnAppearsAtOnce() {
        live()
        send(ScrollEvent.Left(now))
        now += 60_000
        send(ScrollEvent.Returned(now))
        now += 100
        assertEquals(ScrollCommand.ToNewest(animated = false), items("i4 $BASE"))
    }

    @Test fun t05_scrolledUpKeepsThePlace() {
        live()
        moved(3, 0, "i3 i2 i1 L", scrolling = true)
        assertNull(items("i4 i3 i2 i1 L"))
        assertTrue(state.mode is ScrollMode.Anchored)
        assertEquals(1, state.unseen)
    }

    @Test fun t06_myMessageFromAnotherDeviceDoesNotPull() {
        t05_scrolledUpKeepsThePlace()
        assertNull(items("o5 i4 i3 i2 i1 L"))
        assertEquals(1, state.unseen)
        assertTrue(state.mode is ScrollMode.Anchored)
    }

    @Test fun t07_myNewSendPulls() {
        t05_scrolledUpKeepsThePlace()
        assertEquals(ScrollCommand.ToNewest(animated = true), items("p5 i4 i3 i2 i1 L"))
        assertEquals(ScrollMode.Following, state.mode)
    }

    @Test fun t08_aPendingBubbleThatTurnsSentIsNoSend() {
        live("p3 i2 i1")
        moved(2, 0, "p3 i2 i1", scrolling = true)
        // The state changes, the key stays: nothing is added.
        assertNull(send(ScrollEvent.Items(Rows("p3 i2 i1", sent = setOf("p3")), env())))
        assertTrue(state.mode is ScrollMode.Anchored)
    }

    @Test fun t09_pagingKeepsTheAnchor() {
        anchoredOnI2()
        assertNull(items("$BASE i0 L i-1 i-2"))
        assertEquals(ScrollMode.Anchored("i2", 20), state.mode)
    }

    @Test fun t10_aDeletedAnchorReanchorsAtRest() {
        anchoredOnI2()
        assertNull(items("i3 i1"))
        assertNull(moved(1, 0, "i3 i1"))
        assertEquals(ScrollMode.Anchored("i1", 0), state.mode)
    }

    @Test fun t11_newestDeletedRestsAtTheNewest() {
        anchoredOnI2()
        assertNull(items("i2 i1"))
        moved(0, 0, "i2 i1")
        assertEquals(ScrollMode.Following, state.mode)
    }

    @Test fun t12_insideTheSlopStillFollows() {
        live()
        moved(0, 60, BASE, scrolling = true)
        assertEquals(ScrollMode.Following, state.mode)
    }

    @Test fun t13_aTallNewestMessageScrolledInsideIsAnchored() {
        live()
        moved(0, 160, BASE, scrolling = true)
        assertEquals(ScrollMode.Anchored("i3", 160), state.mode)
    }

    @Test fun t14_anInstantScrollBySomeoneElseAnchors() {
        live(LONG)
        moved(12, 0, LONG)
        assertTrue(state.mode is ScrollMode.Anchored)
        assertTrue(state.jumpVisible)
    }

    @Test fun t15_aHoldDelaysTheFollow() {
        live()
        assertNull(items("i4 $BASE", env(held = true)))
        assertTrue(state.followPending)
        assertNull(moved(1, 0, "i4 $BASE", held = true))
        assertEquals(ScrollMode.Following, state.mode)
        assertEquals(ScrollCommand.ToNewest(animated = false), send(ScrollEvent.Released(rows("i4 $BASE"))))
        assertFalse(state.followPending)
    }

    @Test fun t16_aFingerOnTheListDelaysTheFollow() {
        live()
        moved(0, 10, BASE, scrolling = true, held = true)
        assertNull(items("i4 $BASE", env(held = true)))
        assertTrue(state.followPending)
        // The keyed list keeps i3 first visible while the finger is still down.
        moved(1, 10, "i4 $BASE", scrolling = true, held = true)
        assertEquals(ScrollMode.Following, state.mode)
        assertEquals(ScrollCommand.ToNewest(animated = false), moved(1, 10, "i4 $BASE"))
        assertFalse(state.followPending)
    }

    @Test fun t17_aScrollThatEndsUpTheListAnchors() {
        live()
        moved(0, 10, BASE, scrolling = true, held = true)
        items("i4 $BASE", env(held = true))
        assertNull(moved(3, 0, "i4 $BASE", scrolling = true, held = true))
        assertNull(moved(3, 0, "i4 $BASE"))
        assertTrue(state.mode is ScrollMode.Anchored)
        assertFalse(state.followPending)
        assertEquals(1, state.unseen)
    }

    @Test fun t18_rowsComeBackWhileFollowing() {
        live()
        assertNull(items(""))
        assertEquals(ScrollCommand.ToNewest(animated = false), items(BASE))
        assertEquals(ScrollMode.Following, state.mode)
    }

    @Test fun t19_rowsComeBackWhileAnchored() {
        anchoredOnI2()
        assertNull(items(""))
        // A move while the rows are gone changes nothing.
        assertNull(moved(0, 0, ""))
        assertEquals(ScrollCommand.ToKey("i2", 20), items(BASE))
    }

    @Test fun t20_openingWithoutUnread() {
        assertNull(items(""))
        assertEquals(ScrollPhase.Opening, state.phase)
        assertNull(items(BASE))
        assertEquals(ScrollPhase.Live, state.phase)
        assertEquals(ScrollMode.Following, state.mode)
    }

    @Test fun t21_openingWithUnread() {
        val spec = "i5 i4 U i3 i2 i1"
        assertEquals(ScrollCommand.ToUnread("U"), items(spec))
        assertTrue(state.mode is ScrollMode.Anchored)
        // The unread run fitted: the request ended at the bottom.
        moved(0, 0, spec)
        assertEquals(ScrollMode.Following, state.mode)
        // Or it did not: the divider is the bottom-most row on screen.
        state = ThreadScroll()
        items(spec)
        moved(2, 0, spec)
        assertEquals(ScrollMode.Anchored("U", 0), state.mode)
        assertEquals(2, state.unseen)
    }

    @Test fun t22_openingWaitsForItsDivider() {
        val spec = "i5 i4 U i3 i2 i1"
        assertNull(items(spec, env(ready = false)))
        assertEquals(ScrollPhase.Opening, state.phase)
        assertEquals(ScrollCommand.ToUnread("U"), items(spec))
    }

    @Test fun t23_aRevealTargetWinsOverTheDivider() {
        assertNull(items("i5 i4 U i3 i2 i1", env(atTarget = true)))
        assertEquals(ScrollMode.Following, state.mode)
        assertEquals(ScrollPhase.Live, state.phase)
    }

    private fun restoring(
        mode: ScrollMode,
        leftAgoMs: Long,
    ) {
        state = ThreadScroll(mode = mode, phase = ScrollPhase.Restoring, leftAtMs = now - leftAgoMs)
    }

    @Test fun t24_restoredAnchor() {
        restoring(ScrollMode.Anchored("i2", 30), 5 * 60_000L)
        assertEquals(ScrollCommand.ToKey("i2", 30), items(BASE))
        assertEquals(ScrollPhase.Live, state.phase)
        assertEquals(NOT_LEFT, state.leftAtMs)
    }

    @Test fun t25_restoredAfterFifteenMinutes() {
        restoring(ScrollMode.Anchored("i2", 30), THREAD_STALE_MS)
        assertEquals(ScrollCommand.ToNewest(animated = false), items(BASE))
        assertEquals(ScrollMode.Following, state.mode)
    }

    @Test fun t26_restoredAnchorThatIsGone() {
        restoring(ScrollMode.Anchored("i9", 30), 1_000L)
        assertEquals(ScrollCommand.ToNewest(animated = false), items(BASE))
        assertEquals(ScrollMode.Following, state.mode)
    }

    @Test fun t27_restoredFollowing() {
        restoring(ScrollMode.Following, 1_000L)
        assertEquals(ScrollCommand.ToNewest(animated = false), items(BASE))
    }

    @Test fun t28_backWithinFifteenMinutesKeepsThePlace() {
        anchoredOnI2()
        send(ScrollEvent.Left(now))
        assertNull(send(ScrollEvent.Returned(now + THREAD_STALE_MS - 1_000)))
        assertTrue(state.mode is ScrollMode.Anchored)
    }

    @Test fun t29_backAfterFifteenMinutesOrClockBack() {
        anchoredOnI2()
        send(ScrollEvent.Left(now))
        assertEquals(ScrollCommand.ToNewest(animated = false), send(ScrollEvent.Returned(now + THREAD_STALE_MS)))
        assertEquals(ScrollMode.Following, state.mode)
        anchoredOnI2()
        send(ScrollEvent.Left(now))
        assertEquals(ScrollCommand.ToNewest(animated = false), send(ScrollEvent.Returned(now - 1)))
    }

    @Test fun t30_followingNeedsNoTimer() {
        live()
        send(ScrollEvent.Left(now))
        assertNull(send(ScrollEvent.Returned(now + 3 * 3_600_000L)))
        assertEquals(ScrollMode.Following, state.mode)
    }

    @Test fun t31_theFirstLeaveCounts() {
        live()
        send(ScrollEvent.Left(now))
        val first = state.leftAtMs
        send(ScrollEvent.Left(now + 5_000))
        assertEquals(first, state.leftAtMs)
    }

    @Test fun t32_aNotificationTapGoesToTheNewest() {
        anchoredOnI2()
        assertEquals(ScrollCommand.ToNewest(animated = false), send(ScrollEvent.OpenedAgain))
        assertEquals(ScrollMode.Following, state.mode)
    }

    @Test fun t33_theJumpButton() {
        anchoredOnI2()
        items("i4 $BASE")
        assertEquals(1, state.unseen)
        assertEquals(ScrollCommand.ToNewest(animated = true), send(ScrollEvent.JumpToNewest))
        moved(0, 0, "i4 $BASE")
        assertEquals(ScrollMode.Following, state.mode)
        assertEquals(0, state.unseen)
        assertFalse(state.jumpVisible)
    }

    @Test fun t34_theSeenKeyOnlyMovesTowardsNewer() {
        val spec = "i6 i5 i4 i3 i2 i1"
        live("i3 i2 i1")
        moved(2, 0, "i3 i2 i1", scrolling = true)
        items(spec)
        assertEquals(3, state.unseen)
        moved(2, 0, spec, scrolling = true)
        assertEquals(2, state.unseen)
        moved(1, 0, spec, scrolling = true)
        assertEquals(1, state.unseen)
        moved(4, 0, spec, scrolling = true)
        assertEquals(1, state.unseen)
    }

    @Test fun t35_onlyIncomingBubblesCount() {
        state =
            ThreadScroll(
                mode = ScrollMode.Anchored("i1", 0),
                phase = ScrollPhase.Live,
                seenKey = "i1",
                newestKey = "i5",
                wasEmpty = false,
            )
        assertNull(items("i5 L o4 p3 U i2 i1"))
        assertEquals(2, state.unseen)
    }

    @Test fun t35_returnsToNewest() {
        assertFalse(returnsToNewest(leftAtMs = 1_000L, nowMs = 1_000L + THREAD_STALE_MS - 1))
        assertTrue(returnsToNewest(leftAtMs = 1_000L, nowMs = 1_000L + THREAD_STALE_MS))
        assertTrue(returnsToNewest(leftAtMs = 1_000L, nowMs = 1_000L + 3 * THREAD_STALE_MS))
        assertFalse(returnsToNewest(leftAtMs = NOT_LEFT, nowMs = 10 * THREAD_STALE_MS))
        assertTrue(returnsToNewest(leftAtMs = 5_000L, nowMs = 1_000L))
    }

    /** I1 to I3 over random event sequences. */
    @Test fun t36_invariantsHoldOverRandomSequences() {
        val random = Random(7)
        repeat(2_000) {
            state = ThreadScroll()
            var keys = (1..5).map { "i$it" }.reversed()
            var next = 6
            repeat(40) {
                val before = state
                val spec = keys.joinToString(" ")
                val event: ScrollEvent =
                    when (random.nextInt(9)) {
                        0, 1 -> {
                            val kind = listOf("i", "o", "p").random(random)
                            keys = listOf("$kind${next++}") + keys
                            ScrollEvent.Items(Rows(keys.joinToString(" ")), env(held = random.nextInt(4) == 0))
                        }

                        2 -> {
                            // A pending bubble goes out: same keys, another kind.
                            ScrollEvent.Items(Rows(spec, sent = keys.filter { it.startsWith("p") }.toSet()), env())
                        }

                        3 -> {
                            keys = keys + "i${-next++}"
                            ScrollEvent.Items(Rows(keys.joinToString(" ")), env())
                        }

                        4 -> {
                            val index = random.nextInt(keys.size)
                            ScrollEvent.Moved(
                                ScrollSpot(index, random.nextInt(300), keys[index]),
                                scrolling = true,
                                Rows(spec),
                                held = true,
                            )
                        }

                        5 -> {
                            ScrollEvent.Released(Rows(spec))
                        }

                        6 -> {
                            ScrollEvent.Left(now)
                        }

                        7 -> {
                            ScrollEvent.Returned(now + random.nextLong(THREAD_STALE_MS * 2))
                        }

                        else -> {
                            ScrollEvent.JumpToNewest
                        }
                    }
                val (after, cmd) = reduce(before, event, limits)
                if (event is ScrollEvent.Items && before.phase == ScrollPhase.Live) {
                    // I1: a data change alone never un-pins.
                    if (before.mode == ScrollMode.Following) assertEquals(ScrollMode.Following, after.mode)
                    // I2: in Anchored only my own new send moves the list.
                    if (before.mode is ScrollMode.Anchored && cmd != null) {
                        assertEquals(ScrollCommand.ToNewest(animated = true), cmd)
                        assertEquals(RowKind.OutgoingPending, event.rows.kind(0))
                    }
                    // I3: hidden button means new rows are shown (now or when the hold ends).
                    val added = event.rows.size > 0 && event.rows.key(0) != before.newestKey
                    if (!after.jumpVisible && added && !before.wasEmpty) {
                        assertTrue(cmd is ScrollCommand.ToNewest || after.followPending)
                    }
                }
                assertEquals(after.phase == ScrollPhase.Live && after.mode is ScrollMode.Anchored, after.jumpVisible)
                state = after
                now += 1_000
            }
        }
    }

    private companion object {
        const val BASE = "i3 i2 i1"
        val LONG = (20 downTo 1).joinToString(" ") { "i$it" }
    }
}
