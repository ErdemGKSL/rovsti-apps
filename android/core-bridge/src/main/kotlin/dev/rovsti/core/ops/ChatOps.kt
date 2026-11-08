package dev.rovsti.core.ops

import dev.rovsti.core.NativeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustCore
import dev.rovsti.core.call
import dev.rovsti.core.model.chat.CallReminderAnswer
import dev.rovsti.core.model.chat.CallReminderListAnswer
import dev.rovsti.core.model.chat.CallReminderListRequest
import dev.rovsti.core.model.chat.CallReminderRequest
import dev.rovsti.core.model.chat.ChatFindAnswer
import dev.rovsti.core.model.chat.ChatFindRequest
import dev.rovsti.core.model.chat.DateDetectAnswer
import dev.rovsti.core.model.chat.DateDetectRequest
import dev.rovsti.core.model.chat.DatePickAnswer
import dev.rovsti.core.model.chat.DatePickRequest
import dev.rovsti.core.model.chat.DateStepAnswer
import dev.rovsti.core.model.chat.DateStepRequest
import dev.rovsti.core.model.chat.ExportAnswer
import dev.rovsti.core.model.chat.ExportBlockRequest
import dev.rovsti.core.model.chat.ExportNoticeRequest
import dev.rovsti.core.model.chat.ExportRequest
import dev.rovsti.core.model.chat.FindCursorAnswer
import dev.rovsti.core.model.chat.FindCursorRequest
import dev.rovsti.core.model.chat.ForwardPlanAnswer
import dev.rovsti.core.model.chat.ForwardPlanRequest
import dev.rovsti.core.model.chat.ForwardWordsAnswer
import dev.rovsti.core.model.chat.ForwardWordsRequest
import dev.rovsti.core.model.chat.GalleryAnswer
import dev.rovsti.core.model.chat.GalleryRequest
import dev.rovsti.core.model.chat.GalleryTabsAnswer
import dev.rovsti.core.model.chat.GalleryTabsRequest
import dev.rovsti.core.model.chat.GlobalSearchAnswer
import dev.rovsti.core.model.chat.GlobalSearchRequest
import dev.rovsti.core.model.chat.InsertReplyAnswer
import dev.rovsti.core.model.chat.InsertReplyRequest
import dev.rovsti.core.model.chat.LocalStarsAnswer
import dev.rovsti.core.model.chat.LocalStarsRequest
import dev.rovsti.core.model.chat.MakeReminderAnswer
import dev.rovsti.core.model.chat.MakeReminderRequest
import dev.rovsti.core.model.chat.MarksAnswer
import dev.rovsti.core.model.chat.MarksRequest
import dev.rovsti.core.model.chat.MessageInfoAnswer
import dev.rovsti.core.model.chat.MessageInfoRequest
import dev.rovsti.core.model.chat.MimeAnswer
import dev.rovsti.core.model.chat.MimeRequest
import dev.rovsti.core.model.chat.MultiSelectPlan
import dev.rovsti.core.model.chat.MultiSelectRequest
import dev.rovsti.core.model.chat.MuteChoicesAnswer
import dev.rovsti.core.model.chat.MuteChoicesRequest
import dev.rovsti.core.model.chat.MuteStatusAnswer
import dev.rovsti.core.model.chat.MuteStatusRequest
import dev.rovsti.core.model.chat.NextUnreadAnswer
import dev.rovsti.core.model.chat.NextUnreadRequest
import dev.rovsti.core.model.chat.PlacesAnswer
import dev.rovsti.core.model.chat.PlacesRequest
import dev.rovsti.core.model.chat.ReminderListAnswer
import dev.rovsti.core.model.chat.ReminderListRequest
import dev.rovsti.core.model.chat.ReminderSlotsAnswer
import dev.rovsti.core.model.chat.ReminderSlotsRequest
import dev.rovsti.core.model.chat.RepliesAnswer
import dev.rovsti.core.model.chat.RepliesRequest
import dev.rovsti.core.model.chat.SearchFooterRequest
import dev.rovsti.core.model.chat.SearchJumpAnswer
import dev.rovsti.core.model.chat.SearchJumpRequest
import dev.rovsti.core.model.chat.SearchPrepareAnswer
import dev.rovsti.core.model.chat.SearchPrepareRequest
import dev.rovsti.core.model.chat.ShareLocationAnswer
import dev.rovsti.core.model.chat.ShareLocationRequest
import dev.rovsti.core.model.chat.SharedCardAnswer
import dev.rovsti.core.model.chat.SharedCardRequest
import dev.rovsti.core.model.chat.SpeedsAnswer
import dev.rovsti.core.model.chat.SpeedsRequest
import dev.rovsti.core.model.chat.StarredRowsAnswer
import dev.rovsti.core.model.chat.StarredRowsRequest
import dev.rovsti.core.model.chat.TextAnswer
import dev.rovsti.core.model.chat.TextDetectAnswer
import dev.rovsti.core.model.chat.TextDetectRequest
import dev.rovsti.core.model.chat.UnreadDividerAnswer
import dev.rovsti.core.model.chat.UnreadDividerRequest
import dev.rovsti.core.model.chat.UnreadStateAnswer
import dev.rovsti.core.model.chat.UnreadStateRequest
import dev.rovsti.core.model.chat.VoiceToggleAnswer
import dev.rovsti.core.model.chat.VoiceToggleRequest
import dev.rovsti.core.model.chat.VoiceViewAnswer
import dev.rovsti.core.model.chat.VoiceViewRequest

/**
 * The chat tools of the Messages app (E2.11b): in-chat find, the per-chat gallery, export, global search, forward,
 * message info and reminders, starred and local marks, mute, quick replies, the jumps, voice speeds, shared cards, and
 * the detectors for numbers, dates and places. Every call is stateless: the request carries the data and the answer
 * the new data. Offsets into a body or a snippet are UTF-16 units. The core half is [MessagesOps].
 */
interface ChatOps {
    /** The messages of a chat that contain the folded query, as indices oldest first. */
    suspend fun find(req: ChatFindRequest): Outcome<ChatFindAnswer>

    /** Steps through the hits: start on the newest, older/newer with wrap, and the resume/retarget after older pages loaded. */
    suspend fun findCursor(req: FindCursorRequest): Outcome<FindCursorAnswer>

    /** Matching messages across chats, newest first, with a one-line snippet and its highlight (UTF-16). */
    suspend fun globalSearch(req: GlobalSearchRequest): Outcome<GlobalSearchAnswer>

    /** Whether a query is long enough, its SQL `LIKE` pattern and the recent chats whose history is read. */
    suspend fun searchPrepare(req: SearchPrepareRequest): Outcome<SearchPrepareAnswer>

    /** The line under the hits: the count, then why a source is missing. */
    suspend fun searchFooter(req: SearchFooterRequest): Outcome<TextAnswer>

    /** What a chat page does next while it works towards a search hit. */
    suspend fun searchJump(req: SearchJumpRequest): Outcome<SearchJumpAnswer>

    /** Sorts a chat into media, links and documents, newest first, with month sections. */
    suspend fun gallery(req: GalleryRequest): Outcome<GalleryAnswer>

    /** The three tabs with their labels and empty-state texts. */
    suspend fun galleryTabs(req: GalleryTabsRequest): Outcome<GalleryTabsAnswer>

    /** A chat as plain text for the share sheet, and the file name for a saved copy. */
    suspend fun exportText(req: ExportRequest): Outcome<ExportAnswer>

    /** Messages as a day-grouped text block, without a header. */
    suspend fun exportBlock(req: ExportBlockRequest): Outcome<TextAnswer>

    /** The pill after the Save-as sheet answered. */
    suspend fun exportNotice(req: ExportNoticeRequest): Outcome<TextAnswer>

    /** The title, clipboard block, star direction and pill texts of a message selection. */
    suspend fun multiSelect(req: MultiSelectRequest): Outcome<MultiSelectPlan>

    /** The day picker value after a step, its label and the three quick picks. */
    suspend fun datePick(req: DatePickRequest): Outcome<DatePickAnswer>

    /** What to do once a day is chosen: reveal a message, load older pages, give up. */
    suspend fun dateStep(req: DateStepRequest): Outcome<DateStepAnswer>

    /** The jump-to-latest button and its badge after a scroll or an arrival. */
    suspend fun unreadState(req: UnreadStateRequest): Outcome<UnreadStateAnswer>

    /** Where the unread divider goes and what it says. */
    suspend fun unreadDivider(req: UnreadDividerRequest): Outcome<UnreadDividerAnswer>

    /** The next chat with unread messages, wrapping, and the label of the button. */
    suspend fun nextUnread(req: NextUnreadRequest): Outcome<NextUnreadAnswer>

    /** The speed cycle (1x, 1.5x, 2x) and the speed after the current one. */
    suspend fun voiceSpeeds(req: SpeedsRequest): Outcome<SpeedsAnswer>

    /** What a tap on a note's play button does. */
    suspend fun voiceToggle(req: VoiceToggleRequest): Outcome<VoiceToggleAnswer>

    /** Progress fraction, time label and the seek target of a scrub. */
    suspend fun voiceView(req: VoiceViewRequest): Outcome<VoiceViewAnswer>

    /** Pins, archives or marks an SMS thread on this device; the pin cap is 5. */
    suspend fun marks(req: MarksRequest): Outcome<MarksAnswer>

    /** The mute durations of the header bell and when each ends. */
    suspend fun muteChoices(req: MuteChoicesRequest): Outcome<MuteChoicesAnswer>

    /** The "Muted until ..." pill and header line, and whether a mute is still in force. */
    suspend fun muteStatus(req: MuteStatusRequest): Outcome<MuteStatusAnswer>

    /** The starred messages of a chat, newest first, with one-line previews. */
    suspend fun starredRows(req: StarredRowsRequest): Outcome<StarredRowsAnswer>

    /** Stars kept on this device for SMS/MMS: star, unstar, forget a thread, read a thread's rows. */
    suspend fun localStars(req: LocalStarsRequest): Outcome<LocalStarsAnswer>

    /** The editable quick-reply list: add (refused with the sentence to show), remove, undo, reorder. */
    suspend fun replies(req: RepliesRequest): Outcome<RepliesAnswer>

    /** The draft after a reply chip is tapped. */
    suspend fun insertReply(req: InsertReplyRequest): Outcome<InsertReplyAnswer>

    /** What a forward sends on the target channel; blank or over-long SMS text sends nothing. */
    suspend fun forwardPlan(req: ForwardPlanRequest): Outcome<ForwardPlanAnswer>

    /** The chooser label and the pill after a forward. */
    suspend fun forwardWords(req: ForwardWordsRequest): Outcome<ForwardWordsAnswer>

    /** Phone numbers and addresses in a body, with the tap pills for them. */
    suspend fun textDetect(req: TextDetectRequest): Outcome<TextDetectAnswer>

    /** Dates and times in a body, with calendar start/end and a title (UTF-16 spans). */
    suspend fun dateDetect(req: DateDetectRequest): Outcome<DateDetectAnswer>

    /** Coordinates in a body (map links, `geo:` URIs), with a maps-app URI each. */
    suspend fun places(req: PlacesRequest): Outcome<PlacesAnswer>

    /** The share text and URLs of a point. */
    suspend fun shareLocation(req: ShareLocationRequest): Outcome<ShareLocationAnswer>

    /** A received vCard as a card view with every export (save, share, insert). */
    suspend fun sharedCard(req: SharedCardRequest): Outcome<SharedCardAnswer>

    /** Whether an MMS part type is a vCard, and the size limit for reading one. */
    suspend fun cardMime(req: MimeRequest): Outcome<MimeAnswer>

    /** The rows of a message's Info sheet and the text Copy puts on the clipboard. */
    suspend fun messageInfo(req: MessageInfoRequest): Outcome<MessageInfoAnswer>

    /** A reminder on a message or chat; a blank tag or delay is `invalid`. */
    suspend fun makeReminder(req: MakeReminderRequest): Outcome<MakeReminderAnswer>

    /** The four reminder choices with their labels and due times. */
    suspend fun reminderSlots(req: ReminderSlotsRequest): Outcome<ReminderSlotsAnswer>

    /** Reads the stored reminder list tolerantly, finds the one for a message and the id in a fired payload. */
    suspend fun reminders(req: ReminderListRequest): Outcome<ReminderListAnswer>

    /** The Phone app's call-back reminder; a private or empty number is `invalid`. */
    suspend fun callReminder(req: CallReminderRequest): Outcome<CallReminderAnswer>

    /** Reads the stored call-reminder list and finds the one for a number. */
    suspend fun callReminders(req: CallReminderListRequest): Outcome<CallReminderListAnswer>

    companion object {
        /** The Rust op behind each method. */
        val ops: List<String> =
            listOf(
                "chat_find.find",
                "chat_find.cursor",
                "global_search.search",
                "global_search.prepare",
                "global_search.footer",
                "global_search.jump",
                "chat_gallery.build",
                "chat_gallery.tabs",
                "chat_export.text",
                "chat_export.block",
                "chat_export.notice",
                "multi_select.plan",
                "date_jump.pick",
                "date_jump.step",
                "unread_jump.state",
                "unread_jump.divider",
                "unread_jump.next",
                "voice_player.speeds",
                "voice_player.toggle",
                "voice_player.view",
                "thread_marks.edit",
                "mute.choices",
                "mute.status",
                "starred.rows",
                "starred.local",
                "quick_replies.edit",
                "quick_replies.insert",
                "forward.plan",
                "forward.words",
                "text_detect.detect",
                "date_detect.detect",
                "location_link.find",
                "location_link.share",
                "shared_card.read",
                "shared_card.mime",
                "message_info.rows",
                "message_reminder.make",
                "message_reminder.slots",
                "message_reminder.list",
                "call_reminder.make",
                "call_reminder.list",
            )
    }
}

/** [ChatOps] over a [RustCore]; the real core runs every call on `RustDispatcher`. */
class NativeChatOps(
    private val core: RustCore = NativeRustCore(),
) : ChatOps {
    override suspend fun find(req: ChatFindRequest) =
        core.call("chat_find.find", req, ChatFindRequest.serializer(), ChatFindAnswer.serializer())

    override suspend fun findCursor(req: FindCursorRequest) =
        core.call("chat_find.cursor", req, FindCursorRequest.serializer(), FindCursorAnswer.serializer())

    override suspend fun globalSearch(req: GlobalSearchRequest) =
        core.call("global_search.search", req, GlobalSearchRequest.serializer(), GlobalSearchAnswer.serializer())

    override suspend fun searchPrepare(req: SearchPrepareRequest) =
        core.call("global_search.prepare", req, SearchPrepareRequest.serializer(), SearchPrepareAnswer.serializer())

    override suspend fun searchFooter(req: SearchFooterRequest) =
        core.call("global_search.footer", req, SearchFooterRequest.serializer(), TextAnswer.serializer())

    override suspend fun searchJump(req: SearchJumpRequest) =
        core.call("global_search.jump", req, SearchJumpRequest.serializer(), SearchJumpAnswer.serializer())

    override suspend fun gallery(req: GalleryRequest) =
        core.call("chat_gallery.build", req, GalleryRequest.serializer(), GalleryAnswer.serializer())

    override suspend fun galleryTabs(req: GalleryTabsRequest) =
        core.call("chat_gallery.tabs", req, GalleryTabsRequest.serializer(), GalleryTabsAnswer.serializer())

    override suspend fun exportText(req: ExportRequest) =
        core.call("chat_export.text", req, ExportRequest.serializer(), ExportAnswer.serializer())

    override suspend fun exportBlock(req: ExportBlockRequest) =
        core.call("chat_export.block", req, ExportBlockRequest.serializer(), TextAnswer.serializer())

    override suspend fun exportNotice(req: ExportNoticeRequest) =
        core.call("chat_export.notice", req, ExportNoticeRequest.serializer(), TextAnswer.serializer())

    override suspend fun multiSelect(req: MultiSelectRequest) =
        core.call("multi_select.plan", req, MultiSelectRequest.serializer(), MultiSelectPlan.serializer())

    override suspend fun datePick(req: DatePickRequest) =
        core.call("date_jump.pick", req, DatePickRequest.serializer(), DatePickAnswer.serializer())

    override suspend fun dateStep(req: DateStepRequest) =
        core.call("date_jump.step", req, DateStepRequest.serializer(), DateStepAnswer.serializer())

    override suspend fun unreadState(req: UnreadStateRequest) =
        core.call("unread_jump.state", req, UnreadStateRequest.serializer(), UnreadStateAnswer.serializer())

    override suspend fun unreadDivider(req: UnreadDividerRequest) =
        core.call("unread_jump.divider", req, UnreadDividerRequest.serializer(), UnreadDividerAnswer.serializer())

    override suspend fun nextUnread(req: NextUnreadRequest) =
        core.call("unread_jump.next", req, NextUnreadRequest.serializer(), NextUnreadAnswer.serializer())

    override suspend fun voiceSpeeds(req: SpeedsRequest) =
        core.call("voice_player.speeds", req, SpeedsRequest.serializer(), SpeedsAnswer.serializer())

    override suspend fun voiceToggle(req: VoiceToggleRequest) =
        core.call("voice_player.toggle", req, VoiceToggleRequest.serializer(), VoiceToggleAnswer.serializer())

    override suspend fun voiceView(req: VoiceViewRequest) =
        core.call("voice_player.view", req, VoiceViewRequest.serializer(), VoiceViewAnswer.serializer())

    override suspend fun marks(req: MarksRequest) =
        core.call("thread_marks.edit", req, MarksRequest.serializer(), MarksAnswer.serializer())

    override suspend fun muteChoices(req: MuteChoicesRequest) =
        core.call("mute.choices", req, MuteChoicesRequest.serializer(), MuteChoicesAnswer.serializer())

    override suspend fun muteStatus(req: MuteStatusRequest) =
        core.call("mute.status", req, MuteStatusRequest.serializer(), MuteStatusAnswer.serializer())

    override suspend fun starredRows(req: StarredRowsRequest) =
        core.call("starred.rows", req, StarredRowsRequest.serializer(), StarredRowsAnswer.serializer())

    override suspend fun localStars(req: LocalStarsRequest) =
        core.call("starred.local", req, LocalStarsRequest.serializer(), LocalStarsAnswer.serializer())

    override suspend fun replies(req: RepliesRequest) =
        core.call("quick_replies.edit", req, RepliesRequest.serializer(), RepliesAnswer.serializer())

    override suspend fun insertReply(req: InsertReplyRequest) =
        core.call("quick_replies.insert", req, InsertReplyRequest.serializer(), InsertReplyAnswer.serializer())

    override suspend fun forwardPlan(req: ForwardPlanRequest) =
        core.call("forward.plan", req, ForwardPlanRequest.serializer(), ForwardPlanAnswer.serializer())

    override suspend fun forwardWords(req: ForwardWordsRequest) =
        core.call("forward.words", req, ForwardWordsRequest.serializer(), ForwardWordsAnswer.serializer())

    override suspend fun textDetect(req: TextDetectRequest) =
        core.call("text_detect.detect", req, TextDetectRequest.serializer(), TextDetectAnswer.serializer())

    override suspend fun dateDetect(req: DateDetectRequest) =
        core.call("date_detect.detect", req, DateDetectRequest.serializer(), DateDetectAnswer.serializer())

    override suspend fun places(req: PlacesRequest) =
        core.call("location_link.find", req, PlacesRequest.serializer(), PlacesAnswer.serializer())

    override suspend fun shareLocation(req: ShareLocationRequest) =
        core.call("location_link.share", req, ShareLocationRequest.serializer(), ShareLocationAnswer.serializer())

    override suspend fun sharedCard(req: SharedCardRequest) =
        core.call("shared_card.read", req, SharedCardRequest.serializer(), SharedCardAnswer.serializer())

    override suspend fun cardMime(req: MimeRequest) =
        core.call("shared_card.mime", req, MimeRequest.serializer(), MimeAnswer.serializer())

    override suspend fun messageInfo(req: MessageInfoRequest) =
        core.call("message_info.rows", req, MessageInfoRequest.serializer(), MessageInfoAnswer.serializer())

    override suspend fun makeReminder(req: MakeReminderRequest) =
        core.call("message_reminder.make", req, MakeReminderRequest.serializer(), MakeReminderAnswer.serializer())

    override suspend fun reminderSlots(req: ReminderSlotsRequest) =
        core.call("message_reminder.slots", req, ReminderSlotsRequest.serializer(), ReminderSlotsAnswer.serializer())

    override suspend fun reminders(req: ReminderListRequest) =
        core.call("message_reminder.list", req, ReminderListRequest.serializer(), ReminderListAnswer.serializer())

    override suspend fun callReminder(req: CallReminderRequest) =
        core.call("call_reminder.make", req, CallReminderRequest.serializer(), CallReminderAnswer.serializer())

    override suspend fun callReminders(req: CallReminderListRequest) =
        core.call("call_reminder.list", req, CallReminderListRequest.serializer(), CallReminderListAnswer.serializer())
}
