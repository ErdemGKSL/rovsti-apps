package dev.rosty.messages.wa

import android.content.Context
import dev.rosty.core.Outcome
import dev.rosty.core.getOrNull
import dev.rosty.core.model.wa.Card
import dev.rosty.core.model.wa.CardNumber
import dev.rosty.core.model.wa.FoundUrl
import dev.rosty.core.model.wa.HeaderPresence
import dev.rosty.core.model.wa.LinkFindRequest
import dev.rosty.core.model.wa.LinkMeta
import dev.rosty.core.model.wa.LinkParseRequest
import dev.rosty.core.model.wa.LinkPreviewOut
import dev.rosty.core.model.wa.MentionEncodeRequest
import dev.rosty.core.model.wa.MentionEncoded
import dev.rosty.core.model.wa.MentionPick
import dev.rosty.core.model.wa.MentionQuery
import dev.rosty.core.model.wa.MentionQueryRequest
import dev.rosty.core.model.wa.PlaceView
import dev.rosty.core.model.wa.PlaceViewRequest
import dev.rosty.core.model.wa.Poll
import dev.rosty.core.model.wa.PollCheckAnswer
import dev.rosty.core.model.wa.PollCheckRequest
import dev.rosty.core.model.wa.PollView
import dev.rosty.core.model.wa.PollViewRequest
import dev.rosty.core.model.wa.PresenceViewRequest
import dev.rosty.core.model.wa.ReceiptMember
import dev.rosty.core.model.wa.ReceiptSectionsRequest
import dev.rosty.core.model.wa.SectionRows
import dev.rosty.core.model.wa.StaticMap
import dev.rosty.core.model.wa.StaticMapRequest
import dev.rosty.core.model.wa.VcardBuildRequest
import dev.rosty.core.model.wa.VcardParseRequest
import dev.rosty.core.ops.WaOps
import dev.rosty.platform.web.LinkThumbnails
import dev.rosty.platform.web.PagePreviewFetcher
import dev.rosty.platform.web.PagePreviewRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Base64

/**
 * The rules of the message actions (E6-WA R4) as the thread needs them: Rust's `rosty_core::wa_actions` through the
 * pure `wa.*` ops. Tests pass a fake.
 */
interface WaActionRules {
    /** The `@` the caret at [caret] is typing; null: none. */
    suspend fun mentionQuery(
        text: String,
        caret: Int,
    ): MentionQuery?

    /** The composer's `@Name`s as wire tokens and the JIDs they name; null: the rule could not run. */
    suspend fun encode(
        text: String,
        picks: List<MentionPick>,
    ): MentionEncoded?

    /** The first link of [text]. */
    suspend fun firstLink(text: String): FoundUrl?

    /** The sections of message info. */
    suspend fun sections(
        members: List<ReceiptMember>,
        voice: Boolean,
    ): List<SectionRows>

    /** What a page says about itself (`wa.link_parse`). */
    suspend fun parse(
        html: String,
        url: String,
    ): LinkMeta?

    /** E6-WA R5: what a poll bubble draws (`wa.poll_view`); [own] are my ids. Null: the rule could not run. */
    suspend fun pollView(
        poll: Poll,
        own: List<String>,
    ): PollView? = null

    /** E6-WA R5: a poll draft as it is sent, or what keeps it from going (`wa.poll_check`). */
    suspend fun pollCheck(draft: PollCheckRequest): PollCheckAnswer? = null

    /** E6-WA R5: a place's title, subtitle and `geo:` link (`wa.place_view`); null for coordinates that are none. */
    suspend fun placeView(req: PlaceViewRequest): PlaceView? = null

    /** E6-WA R5: the cards of a vCard text (`wa.vcard_parse`). */
    suspend fun cards(vcard: String): List<Card> = emptyList()

    /** E6-WA R5: the vCard of one contact (`wa.vcard_build`). */
    suspend fun vcard(
        name: String,
        numbers: List<CardNumber>,
    ): String? = null

    /** E6-WA R5: the tiles of a small map (`wa.static_map`). */
    suspend fun staticMap(req: StaticMapRequest): Outcome<StaticMap> =
        Outcome.Err(
            dev.rosty.core.RustError
                .Unsupported("wa.static_map"),
        )

    /** E6-WA R5: the presence line (`wa.presence_view`). */
    suspend fun presenceView(req: PresenceViewRequest): Outcome<HeaderPresence> = Outcome.Ok(HeaderPresence.NONE)

    /** E6-WA R6: the notice line of a group change (`wa.group_notice`); [own] are my ids. Null: the rule could not run. */
    suspend fun groupNotice(
        note: dev.rosty.core.model.wa.GroupNote,
        own: List<String>,
    ): dev.rosty.core.model.wa.GroupNotice? = null

    /** E6-WA R6: an invite link in [text], and [text] read whole as a link or code (`wa.invite_find`). */
    suspend fun inviteFind(text: String): dev.rosty.core.model.wa.InviteFindAnswer? = null

    /** E6-WA R6: what a bulk action did per person (`wa.group_results`). */
    suspend fun groupResults(
        kind: String,
        results: List<dev.rosty.core.model.wa.ParticipantResult>,
    ): dev.rosty.core.model.wa.BulkSummary? = null
}

/** [WaActionRules] over [WaOps]. */
class NativeWaActionRules(
    private val ops: WaOps,
) : WaActionRules {
    override suspend fun mentionQuery(
        text: String,
        caret: Int,
    ): MentionQuery? = ops.mentionQuery(MentionQueryRequest(text, caret)).getOrNull()?.query

    override suspend fun encode(
        text: String,
        picks: List<MentionPick>,
    ): MentionEncoded? = ops.mentionEncode(MentionEncodeRequest(text, picks)).getOrNull()

    override suspend fun firstLink(text: String): FoundUrl? = ops.linkFind(LinkFindRequest(text)).getOrNull()?.found

    override suspend fun sections(
        members: List<ReceiptMember>,
        voice: Boolean,
    ): List<SectionRows> =
        ops
            .receiptSections(ReceiptSectionsRequest(members, voice))
            .getOrNull()
            ?.sections
            .orEmpty()

    override suspend fun parse(
        html: String,
        url: String,
    ): LinkMeta? = (ops.linkParse(LinkParseRequest(html, url)) as? Outcome.Ok)?.value

    override suspend fun pollView(
        poll: Poll,
        own: List<String>,
    ): PollView? = ops.pollView(PollViewRequest(poll, own)).getOrNull()

    override suspend fun pollCheck(draft: PollCheckRequest): PollCheckAnswer? = ops.pollCheck(draft).getOrNull()

    override suspend fun placeView(req: PlaceViewRequest): PlaceView? = ops.placeView(req).getOrNull()

    override suspend fun cards(vcard: String): List<Card> =
        ops
            .vcardParse(VcardParseRequest(vcard))
            .getOrNull()
            ?.cards
            .orEmpty()

    override suspend fun vcard(
        name: String,
        numbers: List<CardNumber>,
    ): String? = ops.vcardBuild(VcardBuildRequest(name, numbers)).getOrNull()?.vcard

    override suspend fun staticMap(req: StaticMapRequest): Outcome<StaticMap> = ops.staticMap(req)

    override suspend fun presenceView(req: PresenceViewRequest): Outcome<HeaderPresence> = ops.presenceView(req)

    override suspend fun groupNotice(
        note: dev.rosty.core.model.wa.GroupNote,
        own: List<String>,
    ) = ops
        .groupNotice(
            dev.rosty.core.model.wa
                .GroupNoticeRequest(note, own),
        ).getOrNull()

    override suspend fun inviteFind(text: String) =
        ops
            .inviteFind(
                dev.rosty.core.model.wa
                    .InviteFindRequest(text),
            ).getOrNull()

    override suspend fun groupResults(
        kind: String,
        results: List<dev.rosty.core.model.wa.ParticipantResult>,
    ) = ops
        .groupResults(
            dev.rosty.core.model.wa
                .GroupResultsRequest(kind, results),
        ).getOrNull()
}

/** The host a link card names (`www.` and the port left out), for a card or chip that has no host from the page. */
object WaLinkHosts {
    fun of(url: String): String? =
        runCatching { java.net.URL(url).host }
            .getOrNull()
            ?.lowercase()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotEmpty() }
}

/** A link card made on the phone: what goes on the wire, and the picture as a local model for the chip. */
class LinkDraft(
    val wire: LinkPreviewOut,
    val host: String?,
    val picture: ByteArray?,
)

/** The "Link previews" switch (Messages > WhatsApp settings), on by default. */
interface LinkPreviewSetting {
    val enabled: StateFlow<Boolean>

    fun set(on: Boolean)

    /** A setting in memory (tests, previews). */
    class InMemory(
        initial: Boolean = true,
    ) : LinkPreviewSetting {
        private val flow = MutableStateFlow(initial)
        override val enabled: StateFlow<Boolean> = flow.asStateFlow()

        override fun set(on: Boolean) {
            flow.value = on
        }
    }
}

/** [LinkPreviewSetting] in the `rosty_wa_links` preferences: the switch only, no content. */
class PrefsLinkPreviewSetting(
    context: Context,
) : LinkPreviewSetting {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(prefs.getBoolean(KEY, true))
    override val enabled: StateFlow<Boolean> = flow.asStateFlow()

    override fun set(on: Boolean) {
        prefs.edit().putBoolean(KEY, on).apply()
        flow.value = on
    }

    private companion object {
        const val PREFS = "rosty_wa_links"
        const val KEY = "link_previews"
    }
}

/**
 * Makes the link card of a message on the phone (owner decision, `plans/e6wa-r4.md`): the page is read up to Rust's
 * cap, its `og:` tags and `<title>` read in Rust, its picture fetched and downscaled into the JPEG WhatsApp expects.
 * Nothing is fetched while the setting is off.
 */
class WaLinkPreviews(
    private val fetcher: PagePreviewFetcher,
    private val rules: WaActionRules,
    val setting: LinkPreviewSetting,
    private val thumbnail: (ByteArray) -> ByteArray? = LinkThumbnails::jpeg,
) {
    /** The card of [url] (the link as written); null when off, not a page, or the page says nothing. */
    suspend fun draft(url: String): LinkDraft? {
        if (!setting.enabled.value) return null
        val page = fetcher.page(url, MAX_HTML_BYTES) ?: return null
        val meta = rules.parse(page.html, page.url)?.takeIf { !it.isEmpty } ?: return null
        val picture =
            meta.image
                ?.let { fetcher.image(it, PagePreviewRules.MAX_IMAGE_BYTES) }
                ?.let(thumbnail)
        return LinkDraft(
            wire =
                LinkPreviewOut(
                    url = url,
                    title = meta.title,
                    description = meta.description,
                    thumbnailB64 = picture?.let { Base64.getEncoder().encodeToString(it) },
                ),
            host = meta.host,
            picture = picture,
        )
    }

    private companion object {
        /** `rosty_core::wa_actions::MAX_HTML_BYTES`. */
        const val MAX_HTML_BYTES = 512 * 1024
    }
}
