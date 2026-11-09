package dev.rovsti.platform.contacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import dev.rovsti.core.model.contacts.Account
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.DraftEvent
import dev.rovsti.core.model.contacts.DraftField
import dev.rovsti.core.model.contacts.DraftRelation
import dev.rovsti.core.model.contacts.EventKind
import dev.rovsti.core.model.contacts.FieldKind
import dev.rovsti.core.model.contacts.Group
import dev.rovsti.core.model.contacts.GroupMembersResult
import dev.rovsti.core.model.contacts.ImportResult
import dev.rovsti.core.model.contacts.RelationKind

/**
 * Opens, saves, deletes and imports whole contacts, and manages groups. A contact is a [ContactDraft] (the Rust
 * `ContactDraft`); this object converts it to and from the provider's rows. Nothing here throws for a provider
 * failure: callers get a [ContactResult].
 *
 * A contact merged from several raw contacts is opened and saved through its primary raw contact (the first one that
 * holds a phone number). Rows of the others, which may belong to a sync adapter, are never touched.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/ContactsEdit.kt (the JSON envelope is replaced by the
 * typed models of `:core-bridge`; the provider calls, columns and orders are the same).
 */
object ContactsEdit {
    /** Contacts per `applyBatch` while importing; keeps each transaction small. */
    private const val IMPORT_CHUNK = 40

    /** The data mimetypes a save replaces on the primary raw contact; group memberships are diffed instead. */
    internal val EDITED_MIMES: Array<String> =
        arrayOf(
            StructuredName.CONTENT_ITEM_TYPE,
            Phone.CONTENT_ITEM_TYPE,
            Email.CONTENT_ITEM_TYPE,
            Organization.CONTENT_ITEM_TYPE,
            Note.CONTENT_ITEM_TYPE,
            Event.CONTENT_ITEM_TYPE,
            StructuredPostal.CONTENT_ITEM_TYPE,
            Website.CONTENT_ITEM_TYPE,
            Nickname.CONTENT_ITEM_TYPE,
            Relation.CONTENT_ITEM_TYPE,
        )

    /** The account of a raw contact as Rust keeps it: `type|name`, empty for a phone-only contact. */
    private fun accountKey(
        type: String?,
        name: String?,
    ): String = if (type.isNullOrEmpty() && name.isNullOrEmpty()) "" else "${type.orEmpty()}|${name.orEmpty()}"

    /** Splits an account key at the first `|` into (type, name); an empty key is the phone-only (null, null) account. */
    fun splitAccount(key: String): Pair<String?, String?> {
        if (key.isEmpty()) return null to null
        val bar = key.indexOf('|')
        val type = if (bar < 0) "" else key.substring(0, bar)
        val name = if (bar < 0) key else key.substring(bar + 1)
        if (type.isEmpty() && name.isEmpty()) return null to null
        return type.ifEmpty { null } to name.ifEmpty { null }
    }

    private class Raw(
        val id: Long,
        val account: String,
    )

    // ---- Reading ------------------------------------------------------------

    /** The raw contact a contact is edited through (see the class comment), or null when it is gone. */
    fun primaryRawId(
        context: Context,
        contactId: Long,
    ): Long? = primaryRaw(context, contactId)?.id

    private fun primaryRaw(
        context: Context,
        contactId: Long,
    ): Raw? {
        val resolver = context.contentResolver
        val withPhone =
            resolver
                .query(
                    Data.CONTENT_URI,
                    arrayOf(Data.RAW_CONTACT_ID),
                    "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
                    arrayOf(contactId.toString(), Phone.CONTENT_ITEM_TYPE),
                    "${Data.RAW_CONTACT_ID} ASC",
                )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        val rawId =
            withPhone ?: resolver
                .query(
                    RawContacts.CONTENT_URI,
                    arrayOf(RawContacts._ID),
                    "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0",
                    arrayOf(contactId.toString()),
                    "${RawContacts._ID} ASC",
                )?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        return resolver
            .query(
                RawContacts.CONTENT_URI,
                arrayOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
                "${RawContacts._ID} = ?",
                arrayOf(rawId.toString()),
                null,
            )?.use { if (it.moveToFirst()) Raw(rawId, accountKey(it.getString(0), it.getString(1))) else null }
    }

    private fun kindOfPhone(type: Int) =
        when (type) {
            Phone.TYPE_MOBILE, Phone.TYPE_WORK_MOBILE -> FieldKind.Mobile
            Phone.TYPE_HOME -> FieldKind.Home
            Phone.TYPE_WORK -> FieldKind.Work
            Phone.TYPE_CUSTOM -> FieldKind.Custom
            else -> FieldKind.Other
        }

    private fun typeOfPhone(kind: FieldKind) =
        when (kind) {
            FieldKind.Mobile -> Phone.TYPE_MOBILE
            FieldKind.Home -> Phone.TYPE_HOME
            FieldKind.Work -> Phone.TYPE_WORK
            FieldKind.Custom -> Phone.TYPE_CUSTOM
            FieldKind.Other -> Phone.TYPE_OTHER
        }

    private fun kindOfEmail(type: Int) =
        when (type) {
            Email.TYPE_HOME -> FieldKind.Home
            Email.TYPE_WORK -> FieldKind.Work
            Email.TYPE_CUSTOM -> FieldKind.Custom
            else -> FieldKind.Other
        }

    private fun typeOfEmail(kind: FieldKind) =
        when (kind) {
            FieldKind.Home -> Email.TYPE_HOME
            FieldKind.Work -> Email.TYPE_WORK
            FieldKind.Custom -> Email.TYPE_CUSTOM
            else -> Email.TYPE_OTHER
        }

    private fun kindOfPostal(type: Int) =
        when (type) {
            StructuredPostal.TYPE_HOME -> FieldKind.Home
            StructuredPostal.TYPE_WORK -> FieldKind.Work
            StructuredPostal.TYPE_CUSTOM -> FieldKind.Custom
            else -> FieldKind.Other
        }

    private fun typeOfPostal(kind: FieldKind) =
        when (kind) {
            FieldKind.Home -> StructuredPostal.TYPE_HOME
            FieldKind.Work -> StructuredPostal.TYPE_WORK
            FieldKind.Custom -> StructuredPostal.TYPE_CUSTOM
            else -> StructuredPostal.TYPE_OTHER
        }

    private fun kindOfEvent(type: Int) =
        when (type) {
            Event.TYPE_BIRTHDAY -> EventKind.Birthday
            Event.TYPE_ANNIVERSARY -> EventKind.Anniversary
            Event.TYPE_CUSTOM -> EventKind.Custom
            else -> EventKind.Other
        }

    private fun typeOfEvent(kind: EventKind) =
        when (kind) {
            EventKind.Birthday -> Event.TYPE_BIRTHDAY
            EventKind.Anniversary -> Event.TYPE_ANNIVERSARY
            EventKind.Custom -> Event.TYPE_CUSTOM
            EventKind.Other -> Event.TYPE_OTHER
        }

    private val RELATION_TYPES =
        listOf(
            RelationKind.Spouse to Relation.TYPE_SPOUSE,
            RelationKind.Partner to Relation.TYPE_DOMESTIC_PARTNER,
            RelationKind.Child to Relation.TYPE_CHILD,
            RelationKind.Mother to Relation.TYPE_MOTHER,
            RelationKind.Father to Relation.TYPE_FATHER,
            RelationKind.Parent to Relation.TYPE_PARENT,
            RelationKind.Brother to Relation.TYPE_BROTHER,
            RelationKind.Sister to Relation.TYPE_SISTER,
            RelationKind.Relative to Relation.TYPE_RELATIVE,
            RelationKind.Friend to Relation.TYPE_FRIEND,
            RelationKind.Manager to Relation.TYPE_MANAGER,
            RelationKind.Assistant to Relation.TYPE_ASSISTANT,
        )

    private fun kindOfRelation(type: Int) =
        RELATION_TYPES.firstOrNull { it.second == type }?.first ?: RelationKind.Custom

    private fun typeOfRelation(kind: RelationKind) =
        RELATION_TYPES.firstOrNull { it.first == kind }?.second ?: Relation.TYPE_CUSTOM

    private fun field(
        value: String,
        kind: FieldKind,
        label: String?,
        primary: Boolean = false,
    ) = DraftField(
        value = value,
        kind = kind,
        label = label?.takeIf { kind == FieldKind.Custom && it.isNotBlank() },
        primary = primary,
    )

    /**
     * One draft being filled from `Data` rows. [add] reads a row whose columns are MIMETYPE, DATA1, DATA2, DATA3,
     * DATA5, DATA4, IS_SUPER_PRIMARY, DATA7, DATA9 starting at index `base`. A name, organisation or note already set
     * wins, so a contact merged from several raw contacts keeps the first.
     */
    private class DraftBuilder(
        val contactId: Long,
        val account: String = "",
    ) {
        var given = ""
        var family = ""
        var org = ""
        var title = ""
        var nickname = ""
        var phoneticGiven = ""
        var phoneticFamily = ""
        var note = ""
        var starred = false
        val relations = ArrayList<DraftRelation>()
        val websites = ArrayList<String>()
        val numbers = ArrayList<DraftField>()
        val emails = ArrayList<DraftField>()
        val events = ArrayList<DraftEvent>()
        val addresses = ArrayList<DraftField>()
        val groups = ArrayList<Long>()

        fun add(
            c: Cursor,
            base: Int,
        ) {
            val data1 = c.getString(base + 1)
            when (c.getString(base)) {
                StructuredName.CONTENT_ITEM_TYPE -> {
                    if (given.isEmpty() && family.isEmpty()) {
                        given =
                            listOf(c.getString(base + 2), c.getString(base + 4))
                                .filter { !it.isNullOrBlank() }
                                .joinToString(" ")
                        family = c.getString(base + 3).orEmpty()
                        phoneticGiven = c.getString(base + 7).orEmpty()
                        phoneticFamily = c.getString(base + 8).orEmpty()
                    }
                }

                Phone.CONTENT_ITEM_TYPE -> {
                    if (!data1.isNullOrBlank()) {
                        numbers +=
                            field(
                                data1,
                                kindOfPhone(c.getInt(base + 2)),
                                c.getString(base + 3),
                                c.getInt(base + 6) != 0,
                            )
                    }
                }

                Email.CONTENT_ITEM_TYPE -> {
                    if (!data1.isNullOrBlank()) {
                        emails +=
                            field(
                                data1,
                                kindOfEmail(c.getInt(base + 2)),
                                c.getString(base + 3),
                                c.getInt(base + 6) != 0,
                            )
                    }
                }

                Event.CONTENT_ITEM_TYPE -> {
                    if (!data1.isNullOrBlank()) {
                        val kind = kindOfEvent(c.getInt(base + 2))
                        val label = c.getString(base + 3)?.takeIf { kind == EventKind.Custom && it.isNotBlank() }
                        events += DraftEvent(date = data1, kind = kind, label = label)
                    }
                }

                StructuredPostal.CONTENT_ITEM_TYPE -> {
                    if (!data1.isNullOrBlank()) {
                        addresses += field(data1, kindOfPostal(c.getInt(base + 2)), c.getString(base + 3))
                    }
                }

                Organization.CONTENT_ITEM_TYPE -> {
                    if (org.isEmpty()) org = data1.orEmpty()
                    if (title.isEmpty()) title = c.getString(base + 5).orEmpty()
                }

                Relation.CONTENT_ITEM_TYPE -> {
                    if (!data1.isNullOrBlank()) {
                        val kind = kindOfRelation(c.getInt(base + 2))
                        val label =
                            c
                                .getString(
                                    base + 3,
                                )?.takeIf { kind == RelationKind.Custom && it.isNotBlank() }
                                .orEmpty()
                        relations += DraftRelation(name = data1.trim(), kind = kind, label = label)
                    }
                }

                Website.CONTENT_ITEM_TYPE -> {
                    if (!data1.isNullOrBlank()) websites += data1.trim()
                }

                Nickname.CONTENT_ITEM_TYPE -> {
                    if (nickname.isEmpty()) nickname = data1.orEmpty()
                }

                Note.CONTENT_ITEM_TYPE -> {
                    if (note.isEmpty()) note = data1.orEmpty()
                }

                GroupMembership.CONTENT_ITEM_TYPE -> {
                    c.getLong(base + 1).takeIf { it > 0 }?.let { groups += it }
                }
            }
        }

        fun build() =
            ContactDraft(
                id = contactId,
                given = given,
                family = family,
                org = org,
                nickname = nickname,
                title = title,
                phoneticGiven = phoneticGiven,
                phoneticFamily = phoneticFamily,
                note = note,
                numbers = numbers,
                emails = emails,
                addresses = addresses,
                websites = websites,
                events = events,
                relations = relations,
                groups = groups,
                starred = starred,
                account = account,
            )
    }

    private val DATA_PROJECTION =
        arrayOf(
            Data.MIMETYPE,
            Data.DATA1,
            Data.DATA2,
            Data.DATA3,
            Data.DATA5,
            Data.DATA4,
            Data.IS_SUPER_PRIMARY,
            Data.DATA7,
            Data.DATA9,
        )
    private val DATA_MIMES = EDITED_MIMES + GroupMembership.CONTENT_ITEM_TYPE

    private fun starred(
        context: Context,
        contactId: Long,
    ): Boolean =
        context.contentResolver
            .query(
                ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId),
                arrayOf(Contacts.STARRED),
                null,
                null,
                null,
            )?.use { it.moveToFirst() && it.getInt(0) != 0 } ?: false

    /** One contact, read from its primary raw contact; null when it is gone. */
    fun detail(
        context: Context,
        contactId: Long,
    ): ContactDraft? {
        val raw = primaryRaw(context, contactId) ?: return null
        val builder = DraftBuilder(contactId, raw.account)
        builder.starred = starred(context, contactId)
        context.contentResolver
            .query(
                Data.CONTENT_URI,
                DATA_PROJECTION,
                "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} IN (${DATA_MIMES.joinToString(",") { "?" }})",
                arrayOf(raw.id.toString(), *DATA_MIMES),
                "${Data._ID} ASC",
            )?.use { c -> while (c.moveToNext()) builder.add(c, 0) }
        return builder.build()
    }

    /** Every contact, with or without a number: the export. */
    fun full(context: Context): List<ContactDraft> {
        val builders = LinkedHashMap<Long, DraftBuilder>()
        context.contentResolver
            .query(
                Data.CONTENT_URI,
                arrayOf(Data.CONTACT_ID) + DATA_PROJECTION,
                "${Data.MIMETYPE} IN (${EDITED_MIMES.joinToString(",") { "?" }})",
                EDITED_MIMES,
                "${Data.CONTACT_ID} ASC, ${Data._ID} ASC",
            )?.use { c ->
                while (c.moveToNext()) builders.getOrPut(c.getLong(0)) { DraftBuilder(c.getLong(0)) }.add(c, 1)
            }
        return builders.values.map { it.build() }
    }

    // ---- Writing ------------------------------------------------------------

    /** A default field is written as both IS_PRIMARY and IS_SUPER_PRIMARY (the update path re-inserts rows). */
    private fun ContentProviderOperation.Builder.withPrimary(primary: Boolean): ContentProviderOperation.Builder =
        if (primary) withValue(Data.IS_PRIMARY, 1).withValue(Data.IS_SUPER_PRIMARY, 1) else this

    private fun String.orNull(): String? = ifEmpty { null }

    private fun labelOf(
        kind: FieldKind,
        label: String?,
    ): String? = if (kind == FieldKind.Custom) label.orEmpty().orNull() else null

    /** Appends the inserts for [draft]; `rawId` for an existing raw contact, else a back reference to op [backRef]. */
    private fun insertData(
        ops: MutableList<ContentProviderOperation>,
        draft: ContactDraft,
        rawId: Long?,
        backRef: Int,
    ) {
        fun insert(mime: String): ContentProviderOperation.Builder {
            val b = ContentProviderOperation.newInsert(Data.CONTENT_URI)
            if (rawId !=
                null
            ) {
                b.withValue(Data.RAW_CONTACT_ID, rawId)
            } else {
                b.withValueBackReference(Data.RAW_CONTACT_ID, backRef)
            }
            return b.withValue(Data.MIMETYPE, mime)
        }
        val given = draft.given.trim()
        val family = draft.family.trim()
        val phoneticGiven = draft.phoneticGiven.trim()
        val phoneticFamily = draft.phoneticFamily.trim()
        if (given.isNotEmpty() || family.isNotEmpty() || phoneticGiven.isNotEmpty() || phoneticFamily.isNotEmpty()) {
            ops +=
                insert(StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(StructuredName.GIVEN_NAME, given.orNull())
                    .withValue(StructuredName.FAMILY_NAME, family.orNull())
                    .withValue(StructuredName.PHONETIC_GIVEN_NAME, phoneticGiven.orNull())
                    .withValue(StructuredName.PHONETIC_FAMILY_NAME, phoneticFamily.orNull())
                    .build()
        }
        val org = draft.org.trim()
        val title = draft.title.trim()
        if (org.isNotEmpty() || title.isNotEmpty()) {
            ops +=
                insert(Organization.CONTENT_ITEM_TYPE)
                    .withValue(Organization.COMPANY, org.orNull())
                    .withValue(Organization.TITLE, title.orNull())
                    .build()
        }
        draft.nickname.trim().takeIf { it.isNotEmpty() }?.let {
            ops +=
                insert(Nickname.CONTENT_ITEM_TYPE)
                    .withValue(Nickname.NAME, it)
                    .withValue(Nickname.TYPE, Nickname.TYPE_DEFAULT)
                    .build()
        }
        for (site in draft.websites) {
            val url = site.trim().takeIf { it.isNotEmpty() } ?: continue
            ops +=
                insert(Website.CONTENT_ITEM_TYPE)
                    .withValue(Website.URL, url)
                    .withValue(Website.TYPE, Website.TYPE_OTHER)
                    .build()
        }
        draft.note.trim().takeIf { it.isNotEmpty() }?.let {
            ops += insert(Note.CONTENT_ITEM_TYPE).withValue(Note.NOTE, it).build()
        }
        for (n in draft.numbers) {
            val value = n.value.trim().takeIf { it.isNotEmpty() } ?: continue
            ops +=
                insert(Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, value)
                    .withValue(Phone.TYPE, typeOfPhone(n.kind))
                    .withValue(Phone.LABEL, labelOf(n.kind, n.label))
                    .withPrimary(n.primary)
                    .build()
        }
        for (e in draft.emails) {
            val value = e.value.trim().takeIf { it.isNotEmpty() } ?: continue
            ops +=
                insert(Email.CONTENT_ITEM_TYPE)
                    .withValue(Email.ADDRESS, value)
                    .withValue(Email.TYPE, typeOfEmail(e.kind))
                    .withValue(Email.LABEL, labelOf(e.kind, e.label))
                    .withPrimary(e.primary)
                    .build()
        }
        for (ev in draft.events) {
            val date = ev.date.trim().takeIf { it.isNotEmpty() } ?: continue
            ops +=
                insert(Event.CONTENT_ITEM_TYPE)
                    .withValue(Event.START_DATE, date)
                    .withValue(Event.TYPE, typeOfEvent(ev.kind))
                    .withValue(Event.LABEL, if (ev.kind == EventKind.Custom) ev.label.orEmpty().orNull() else null)
                    .build()
        }
        for (rel in draft.relations) {
            val name = rel.name.trim().takeIf { it.isNotEmpty() } ?: continue
            ops +=
                insert(Relation.CONTENT_ITEM_TYPE)
                    .withValue(Relation.NAME, name)
                    .withValue(Relation.TYPE, typeOfRelation(rel.kind))
                    .withValue(Relation.LABEL, if (rel.kind == RelationKind.Custom) rel.label.orNull() else null)
                    .build()
        }
        for (a in draft.addresses) {
            val value = a.value.trim().takeIf { it.isNotEmpty() } ?: continue
            ops +=
                insert(StructuredPostal.CONTENT_ITEM_TYPE)
                    .withValue(StructuredPostal.FORMATTED_ADDRESS, value)
                    .withValue(StructuredPostal.TYPE, typeOfPostal(a.kind))
                    .withValue(StructuredPostal.LABEL, labelOf(a.kind, a.label))
                    .build()
        }
    }

    /** Ids of the visible groups of [account] (the ones a contact of that account may join). */
    private fun groupIdsOf(
        context: Context,
        account: String,
    ): Set<Long> = queryGroups(context).filter { it.account == account }.map { it.id }.toSet()

    private fun contactIdOfRaw(
        context: Context,
        rawId: Long,
    ): Long? =
        context.contentResolver
            .query(
                RawContacts.CONTENT_URI,
                arrayOf(RawContacts.CONTACT_ID),
                "${RawContacts._ID} = ?",
                arrayOf(rawId.toString()),
                null,
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    /** Creates (no id) or updates a contact; the answer is its id. */
    fun save(
        context: Context,
        draft: ContactDraft,
    ): ContactResult<Long> =
        guarded {
            val id = draft.id ?: 0L
            if (id > 0) update(context, id, draft) else create(context, draft)
        }

    private fun update(
        context: Context,
        contactId: Long,
        draft: ContactDraft,
    ): ContactResult<Long> {
        val resolver = context.contentResolver
        val raw = primaryRaw(context, contactId) ?: return ContactResult.failed(ContactResult.Reason.NotFound)
        val ops = ArrayList<ContentProviderOperation>()
        ops +=
            ContentProviderOperation
                .newDelete(Data.CONTENT_URI)
                .withSelection(
                    "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} IN (${EDITED_MIMES.joinToString(",") { "?" }})",
                    arrayOf(raw.id.toString(), *EDITED_MIMES),
                ).build()
        insertData(ops, draft, raw.id, 0)
        // Only the groups of this account can change; memberships in hidden system groups stay.
        val visible = groupIdsOf(context, raw.account)
        val wanted = draft.groups.filter { it in visible }.toSet()
        val existing = HashMap<Long, Long>() // group id to the membership row's _ID
        resolver
            .query(
                Data.CONTENT_URI,
                arrayOf(Data._ID, Data.DATA1),
                "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
                arrayOf(raw.id.toString(), GroupMembership.CONTENT_ITEM_TYPE),
                null,
            )?.use { c -> while (c.moveToNext()) existing[c.getLong(1)] = c.getLong(0) }
        for ((group, row) in existing) {
            if (group in visible && group !in wanted) {
                ops += ContentProviderOperation.newDelete(ContentUris.withAppendedId(Data.CONTENT_URI, row)).build()
            }
        }
        for (group in wanted) {
            if (group !in existing) ops += membership(raw.id, group)
        }
        resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        val values = ContentValues().apply { put(Contacts.STARRED, if (draft.starred) 1 else 0) }
        // The aggregate may have changed id while its name changed; follow the raw contact.
        val now = contactIdOfRaw(context, raw.id) ?: contactId
        resolver.update(ContentUris.withAppendedId(Contacts.CONTENT_URI, now), values, null, null)
        return ContactResult.Ok(now)
    }

    private fun membership(
        rawId: Long,
        group: Long,
    ): ContentProviderOperation =
        ContentProviderOperation
            .newInsert(Data.CONTENT_URI)
            .withValue(Data.RAW_CONTACT_ID, rawId)
            .withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
            .withValue(GroupMembership.GROUP_ROW_ID, group)
            .build()

    private fun create(
        context: Context,
        draft: ContactDraft,
    ): ContactResult<Long> {
        val ops = ArrayList<ContentProviderOperation>()
        val rawIndex = appendNew(ops, draft, context, account = draft.account.trim())
        val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        val uri = results.getOrNull(rawIndex)?.uri ?: return ContactResult.failed(ContactResult.Reason.Unknown)
        val id =
            contactIdOfRaw(context, ContentUris.parseId(uri))
                ?: return ContactResult.failed(ContactResult.Reason.Unknown)
        return ContactResult.Ok(id)
    }

    /** Appends the operations for a new contact in [account]; returns the index of its raw-contact insert. */
    private fun appendNew(
        ops: MutableList<ContentProviderOperation>,
        draft: ContactDraft,
        context: Context,
        local: Set<Long>? = null,
        account: String = "",
    ): Int {
        val rawIndex = ops.size
        val (accountType, accountName) = splitAccount(account)
        ops +=
            ContentProviderOperation
                .newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, accountType)
                .withValue(RawContacts.ACCOUNT_NAME, accountName)
                .withValue(RawContacts.STARRED, if (draft.starred) 1 else 0)
                .build()
        insertData(ops, draft, null, rawIndex)
        val visible = local ?: groupIdsOf(context, accountKey(accountType, accountName))
        for (group in draft.groups.toSet()) {
            if (group !in visible) continue
            ops +=
                ContentProviderOperation
                    .newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, rawIndex)
                    .withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
                    .withValue(GroupMembership.GROUP_ROW_ID, group)
                    .build()
        }
        return rawIndex
    }

    /** Deletes the whole contact (all its raw contacts). */
    fun delete(
        context: Context,
        contactId: Long,
    ): ContactResult<Unit> =
        guarded {
            val n =
                context.contentResolver.delete(
                    ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId),
                    null,
                    null,
                )
            if (n > 0) ContactResult.Done else ContactResult.failed(ContactResult.Reason.NotFound)
        }

    /** Adds phone-only contacts; chunks of [IMPORT_CHUNK] per `applyBatch`. */
    fun import(
        context: Context,
        drafts: List<ContactDraft>,
    ): ContactResult<ImportResult> =
        guarded {
            val local = groupIdsOf(context, "")
            val ids = LongArray(drafts.size)
            var added = 0
            for (start in drafts.indices step IMPORT_CHUNK) {
                val end = minOf(start + IMPORT_CHUNK, drafts.size)
                val ops = ArrayList<ContentProviderOperation>()
                val rawIndexes = LinkedHashMap<Int, Int>()
                for (i in start until end) rawIndexes[i] = appendNew(ops, drafts[i], context, local)
                if (ops.isEmpty()) continue
                val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                added += rawIndexes.size
                for ((i, opIndex) in rawIndexes) {
                    val uri = results.getOrNull(opIndex)?.uri ?: continue
                    val rawId = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: continue
                    ids[i] = contactIdOfRaw(context, rawId) ?: 0L
                }
            }
            ContactResult.Ok(ImportResult(added, ids.toList()))
        }

    // ---- Groups -------------------------------------------------------------

    private class GroupRow(
        val id: Long,
        val title: String,
        val account: String,
    )

    private fun queryGroups(context: Context): List<GroupRow> {
        val out = ArrayList<GroupRow>()
        context.contentResolver
            .query(
                Groups.CONTENT_URI,
                arrayOf(
                    Groups._ID,
                    Groups.TITLE,
                    Groups.ACCOUNT_TYPE,
                    Groups.ACCOUNT_NAME,
                    Groups.SYSTEM_ID,
                    Groups.AUTO_ADD,
                    Groups.FAVORITES,
                ),
                "${Groups.DELETED} = 0 AND ${Groups.GROUP_VISIBLE} = 1",
                null,
                "${Groups.TITLE} COLLATE LOCALIZED ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    // "My Contacts", Android's Starred group and auto-add groups are not labels the owner made.
                    if (c.getString(4) == "Contacts" || c.getInt(5) != 0 || c.getInt(6) != 0) continue
                    val title =
                        c
                            .getString(1)
                            ?.removePrefix("System Group: ")
                            ?.trim()
                            .orEmpty()
                    if (title.isEmpty()) continue
                    out.add(GroupRow(c.getLong(0), title, accountKey(c.getString(2), c.getString(3))))
                }
            }
        return out
    }

    /** The labels with the members of each. */
    fun groups(context: Context): List<Group> {
        val rows = queryGroups(context)
        val members = HashMap<Long, MutableList<Long>>()
        context.contentResolver
            .query(
                Data.CONTENT_URI,
                arrayOf(Data.CONTACT_ID, Data.DATA1),
                "${Data.MIMETYPE} = ?",
                arrayOf(GroupMembership.CONTENT_ITEM_TYPE),
                null,
            )?.use { c -> while (c.moveToNext()) members.getOrPut(c.getLong(1)) { ArrayList() }.add(c.getLong(0)) }
        return rows.map {
            Group(
                id = it.id,
                title = it.title,
                members = members[it.id].orEmpty(),
                account = it.account,
            )
        }
    }

    /** Creates a group in [account] (`type|name`, empty for the phone) when [groupId] is 0, or renames one. */
    fun saveGroup(
        context: Context,
        groupId: Long,
        title: String,
        account: String,
    ): ContactResult<Long> =
        guarded {
            val resolver = context.contentResolver
            if (groupId > 0) {
                val values = ContentValues().apply { put(Groups.TITLE, title) }
                val n = resolver.update(ContentUris.withAppendedId(Groups.CONTENT_URI, groupId), values, null, null)
                if (n > 0) ContactResult.Ok(groupId) else ContactResult.failed(ContactResult.Reason.NotFound)
            } else {
                val (accountType, accountName) = splitAccount(account)
                val values =
                    ContentValues().apply {
                        put(Groups.ACCOUNT_TYPE, accountType)
                        put(Groups.ACCOUNT_NAME, accountName)
                        put(Groups.TITLE, title)
                        put(Groups.GROUP_VISIBLE, 1)
                    }
                val uri = resolver.insert(Groups.CONTENT_URI, values)
                if (uri !=
                    null
                ) {
                    ContactResult.Ok(ContentUris.parseId(uri))
                } else {
                    ContactResult.failed(ContactResult.Reason.Refused)
                }
            }
        }

    /**
     * Adds [add] to and removes [remove] from the group in one `applyBatch`. A contact without a live raw contact in
     * the group's account is skipped, and one already in the group is left alone.
     */
    fun setGroupMembers(
        context: Context,
        groupId: Long,
        add: List<Long>,
        remove: List<Long>,
    ): ContactResult<GroupMembersResult> =
        guarded {
            val resolver = context.contentResolver
            val account =
                resolver
                    .query(
                        Groups.CONTENT_URI,
                        arrayOf(Groups.ACCOUNT_TYPE, Groups.ACCOUNT_NAME),
                        "${Groups._ID} = ?",
                        arrayOf(groupId.toString()),
                        null,
                    )?.use { if (it.moveToFirst()) accountKey(it.getString(0), it.getString(1)) else null }
                    ?: return@guarded ContactResult.failed(ContactResult.Reason.NotFound)
            val ops = ArrayList<ContentProviderOperation>()
            var added = 0
            var skipped = 0
            for (contactId in add.filter { it > 0 }) {
                var target: Long? = null
                resolver
                    .query(
                        RawContacts.CONTENT_URI,
                        arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
                        "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0",
                        arrayOf(contactId.toString()),
                        "${RawContacts._ID} ASC",
                    )?.use { c ->
                        while (c.moveToNext()) {
                            if (target == null &&
                                accountKey(c.getString(1), c.getString(2)) == account
                            ) {
                                target = c.getLong(0)
                            }
                        }
                    }
                val rawId = target
                if (rawId == null) {
                    skipped++
                    continue
                }
                val member =
                    resolver
                        .query(
                            Data.CONTENT_URI,
                            arrayOf(Data._ID),
                            "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${GroupMembership.GROUP_ROW_ID} = ?",
                            arrayOf(contactId.toString(), GroupMembership.CONTENT_ITEM_TYPE, groupId.toString()),
                            null,
                        )?.use { it.moveToFirst() } ?: false
                if (member) continue
                ops += membership(rawId, groupId)
                added++
            }
            var removed = 0
            for (contactId in remove.filter { it > 0 }) {
                ops +=
                    ContentProviderOperation
                        .newDelete(Data.CONTENT_URI)
                        .withSelection(
                            "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${GroupMembership.GROUP_ROW_ID} = ?",
                            arrayOf(contactId.toString(), GroupMembership.CONTENT_ITEM_TYPE, groupId.toString()),
                        ).build()
                removed++
            }
            if (ops.isNotEmpty()) resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            ContactResult.Ok(GroupMembersResult(added, removed, skipped))
        }

    fun deleteGroup(
        context: Context,
        groupId: Long,
    ): ContactResult<Unit> =
        guarded {
            val n = context.contentResolver.delete(ContentUris.withAppendedId(Groups.CONTENT_URI, groupId), null, null)
            if (n > 0) ContactResult.Done else ContactResult.failed(ContactResult.Reason.NotFound)
        }

    // ---- Accounts -----------------------------------------------------------

    /** Accounts whose sync adapter for the contacts authority uploads changes, as `type` to `name`. */
    private fun accountsOf(context: Context): List<Pair<String, String>> {
        val uploading =
            ContentResolver
                .getSyncAdapterTypes()
                .filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() }
                .map { it.accountType }
                .toSet()
        val found = LinkedHashSet<Pair<String, String>>()

        fun collect(
            uri: android.net.Uri,
            typeColumn: String,
            nameColumn: String,
        ) {
            context.contentResolver
                .query(
                    uri,
                    arrayOf(typeColumn, nameColumn),
                    "$typeColumn IS NOT NULL AND $nameColumn IS NOT NULL",
                    null,
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val type = c.getString(0).orEmpty()
                        val name = c.getString(1).orEmpty()
                        if (type in uploading && name.isNotEmpty()) found.add(type to name)
                    }
                }
        }
        collect(Groups.CONTENT_URI, Groups.ACCOUNT_TYPE, Groups.ACCOUNT_NAME)
        collect(RawContacts.CONTENT_URI, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME)
        return found.toList()
    }

    /**
     * The phone itself (empty key) first, then every account seen in the Groups and RawContacts tables whose sync
     * adapter uploads contacts. No `GET_ACCOUNTS` is needed because the accounts come from the providers.
     */
    fun accounts(context: Context): List<Account> =
        listOf(Account(key = "", name = "", kind = "")) +
            accountsOf(context)
                .sortedWith(compareBy({ it.first }, { it.second }))
                .map { (type, name) -> Account(key = accountKey(type, name), name = name, kind = type) }
}
