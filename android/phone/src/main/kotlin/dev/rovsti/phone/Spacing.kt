@file:Suppress("ktlint:standard:filename")

package dev.rovsti.phone

import androidx.compose.ui.unit.dp

/** Dimensions of the Phone screens (convention: raw dp only in `Spacing.kt`). */
internal object PhoneMetrics {
    /** The status glyph of a Setup row. */
    val StatusIcon = 24.dp

    /** The thin progress bar under the Setup title. */
    val Bar = 5.dp
    val CardRadius = 16.dp
    val CardPad = 14.dp

    /** The notice line's shield tile. */
    val NoticeIcon = 22.dp

    /** The gap under the nav chrome of a root page that has a notice line. */
    val NoticeTop = 8.dp

    /** The tallest value column on the Diagnostics rows. */
    val ValueMax = 160.dp

    /** The log window height of a Diagnostics section with free lines. */
    val LogLine = 18.dp

    /** The gear button of the Keypad placeholder keeps the status bar clear by this margin. */
    val CornerInset = 8.dp
}

/** Font scale above which the Setup buttons stack instead of sitting in one row. */
internal const val STACK_FONT_SCALE = 1.3f

/** Opacity of a done Setup card (it recedes behind the open ones). */
internal const val DONE_CARD_ALPHA = 0.6f

/** Dimensions of the Keypad (E5.2); the sizes that depend on the screen come from `KeypadLayout`. */
internal object KeypadMetrics {
    /** Viewport of the 24-unit vector icons. */
    val IconViewport = 24.dp

    /** One suggestion row. */
    val SuggestionRow = 56.dp
    val SuggestionAvatar = 40.dp

    /** The line under the field with "Add to Contacts". */
    val AddRow = 36.dp

    /** Space between the lowest key row and the tab bar. */
    val BarGap = 8.dp

    /** The reserved top of the field row, under the status bar. */
    val FieldTop = 8.dp

    /** The field's side margin that keeps the gear clear. */
    val FieldSide = 68.dp

    /** The key's number is this share of its diameter (sp, not scaled by the font size: a key keeps its shape). */
    const val DigitRatio = 0.40f
    const val LettersRatio = 0.14f

    /** The glyph on the call and delete keys. */
    val CallGlyph = 30.dp
    val DeleteGlyph = 28.dp

    /** The dot of a SIM in menus and the chooser. */
    val SimDot = 12.dp
    val SimRowHeight = 56.dp
    val SimSheetBase = 120.dp

    /** The glyph in a menu row. */
    val MenuGlyph = 20.dp
}

/** The delete key repeats after this long, then every [DELETE_REPEAT_MS]; a hold of [DELETE_CLEAR_MS] clears. */
internal const val DELETE_REPEAT_AFTER_MS = 400L
internal const val DELETE_REPEAT_MS = 70L
internal const val DELETE_CLEAR_MS = 2_000L

/** A hold on a key counts after this long (the platform's long-press timeout is 400 ms). */
internal const val KEY_HOLD_MS = 450L

/** The Keypad font does not follow the system font scale past this factor (keys keep their shape). */
internal const val KEYPAD_FONT_SCALE_CAP = 1.3f

/** Dimensions of Recents (E5.3). */
internal object RecentsMetrics {
    /** The info glyph in its 48 dp touch target. */
    val InfoGlyph = 22.dp
    val SwipeGlyph = 22.dp

    /** No width: the select slot while not selecting, no gap before the time. */
    val None = 0.dp

    /** The SIM chip beside the time: a dot and the short label. */
    val SimDot = 8.dp
    val SimChipVertical = 2.dp

    /** The share of the viewport the empty state takes (it sits above the tab bar). */
    const val EmptyShare = 0.5f

    /** The select toolbar beside the folded tab-bar circle (the accessory's inline height) and in the expanded bar. */
    val ToolbarInline = 48.dp
    val ToolbarExpanded = 54.dp
    val ToolbarSide = 4.dp
}

/** The details sheets (E5.4): the header avatar, the glyph sizes of their rows and the QR code. */
internal object DetailsMetrics {
    val HeaderAvatar = 72.dp
    val CallAvatar = 56.dp
    val RowGlyph = 22.dp
    val SmallGlyph = 20.dp
    val QrSize = 224.dp
}

/** Dimensions of the Incoming Call screen and the waiting-call card (E5.6). */
internal object IncomingMetrics {
    val LabelTop = 28.dp
    val Avatar = 96.dp
    val AvatarGap = 24.dp
    val NameGap = 8.dp

    /** Answer and Decline. */
    val Button = 76.dp

    val PrimaryIcon = 32.dp
    val CaptionGap = 8.dp
    val SecondaryLift = 32.dp
    val BottomPad = 48.dp

    /** The locked variant: under the Decline text. */
    val LockedBottomPad = 24.dp
    val DeclineTarget = 48.dp
    val SidePad = 24.dp

    /** The SIM chip under the number. */
    val ChipHeight = 28.dp
    val ChipDot = 10.dp
    val ChipPadX = 12.dp

    /** The lock chip at the top of the lock-screen variant. */
    val LockChipGap = 6.dp

    /** The waiting-call card: 12 dp under the status bar, 2 x 2 pills. */
    val CardTop = 12.dp
    val CardSide = 16.dp
    val CardPad = 16.dp
    val CardGap = 12.dp
    val CardRadius = 28.dp
    val CardShadow = 16.dp
    val CardAvatar = 40.dp

    /** The aura's chrome mesh at the top of the screen. */
    val MeshHeight = 320.dp
}

/** The answer-button green and the decline-button red (the iOS system colours the call screens use). */
internal const val ANSWER_GREEN = 0xFF34C759
internal const val DECLINE_RED = 0xFFFF3B30

/** A screenshot draws the aura at this phase (radians), so a golden never shows a half-moved frame. */
internal const val AURA_SHOT_PHASE = 2.4f

/** Dimensions of the In-Call screen, the tone keypad, the route list and the mini pill (E5.7a). */
internal object InCallMetrics {
    /** Multi-call (E5.7b): the Held card, the conference avatars, the swap rise, the Add call sheet. */
    val HeldAvatar = 32.dp
    val ClusterAvatar = 64.dp
    val ClusterStepFar = 132.dp
    val ClusterStepNear = 76.dp
    val SwapRise = 72.dp
    val SmallGlyph = 20.dp
    val PillGlyph = 16.dp
    val AddKey = 60.dp
    val AddKeyGapX = 20.dp
    val AddKeyGapY = 10.dp
    val AddNumberHeight = 56.dp
    val PersonAvatar = 40.dp

    val Avatar = 112.dp
    val AvatarGap = 20.dp
    val LabelTop = 16.dp
    val NameGap = 6.dp
    val SidePad = 24.dp

    /** The control circles (Mute, Keypad, Audio, Add call, Hold, Note) and their grid. */
    val Control = 72.dp
    val ControlGap = 20.dp
    val RowGap = 16.dp
    val ControlIcon = 28.dp
    val CaptionGap = 6.dp

    /** The End button. */
    val End = 76.dp
    val EndIcon = 32.dp
    val EndTop = 28.dp
    val BottomPad = 80.dp

    /** The collapse button at the top-start. */
    val TopBar = 44.dp
    val TopIcon = 24.dp

    /** The SIM chip. */
    val ChipHeight = 28.dp
    val ChipDot = 10.dp
    val ChipPadX = 12.dp

    /** The tone keys. */
    val Key = 68.dp
    val KeyGap = 14.dp
    val DigitLine = 48.dp

    /** The route list rows in the popover. */
    val RouteRow = 48.dp
    val RouteGlyph = 22.dp
    val RouteWidth = 260.dp

    /** The mini pill over the app. */
    val PillHeight = 56.dp
    val PillSide = 12.dp
    val PillTop = 8.dp
    val PillAvatar = 36.dp
    val PillEnd = 40.dp
    val PillRadius = 28.dp

    /** The aura's chrome mesh at the top. */
    val MeshHeight = 320.dp
}

/** Dimensions of the Statistics page (E5.10). */
internal object StatsMetrics {
    /** The plot area of the bar chart, count labels and bars. */
    val ChartHeight = 176.dp

    /** The widest a bar gets; the narrowest slot of eight bars is about 40 dp. */
    val BarMaxWidth = 28.dp

    /** A bar with no calls still shows this stub, so the day is visible. */
    val BarStub = 3.dp
    val BarRadius = 6.dp

    /** The gap between a bar and its count. */
    val CountGap = 3.dp

    /** The month-day label row under the plot. */
    val LabelGap = 6.dp

    /** The avatar of a people row. */
    val PersonAvatar = 36.dp

    /** The direction glyph of a bucket row. */
    val Glyph = 20.dp
}

/** Spring of a growing bar, and the delay between neighbours. */
internal object StatsMotion {
    const val DAMPING = 0.8f
    const val STIFFNESS = 300f
    const val STAGGER_MS = 30L
}
