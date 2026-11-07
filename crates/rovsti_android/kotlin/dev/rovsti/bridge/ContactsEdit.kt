package dev.rovsti.bridge

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
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rovsti Contacts: opens, saves, deletes and imports whole contacts, and manages groups.
 * Rust sees a contact as `rovsti_core::contact_edit::ContactDraft`; this object converts it to
 * and from the provider's rows. Every call runs off the main thread and never throws: failures
 * come back as `{ok:false, error}` like the other pulls.
 *
 * A contact merged from several raw contacts is opened and saved through its primary raw
 * contact (the first one that holds a phone number). Rows of the others, which may belong to a
 * sync adapter, are never touched.
 */
object ContactsEdit {
    /** Contacts per `applyBatch` while importing; keeps each transaction small. */
    private const val IMPORT_CHUNK = 40

    private val EDITED_MIMES = arrayOf(
        StructuredName.CONTENT_ITEM_TYPE,
        Phone.CONTENT_ITEM_TYPE,
        Email.CONTENT_ITEM_TYPE,
        Organization.CONTENT_ITEM_TYPE,
        Note.CONTENT_ITEM_TYPE,
        Event.CONTENT_ITEM_TYPE,
        StructuredPostal.CONTENT_ITEM_TYPE,
        Website.CONTENT_ITEM_TYPE,
        Nickname.CONTENT_ITEM_TYPE,
    )

    private inline fun guard(block: () -> String): String = try {
        block()
    } catch (_: SecurityException) {
        ProviderJson.error("permission")
    } catch (_: Exception) {
        ProviderJson.error("unknown")
    }

    /** The account of a raw contact as Rust keeps it: `type|name`, empty for a phone-only contact. */
    private fun accountKey(type: String?, name: String?): String =
        if (type.isNullOrEmpty() && name.isNullOrEmpty()) "" else "${type.orEmpty()}|${name.orEmpty()}"

    /**
     * Splits a Rust account key at the first `|` into (type, name); an empty key, or one with
     * no type and no name, is the phone-only (null, null) account.
     */
    fun splitAccount(key: String): Pair<String?, String?> {
        if (key.isEmpty()) return null to null
        val bar = key.indexOf('|')
        val type = if (bar < 0) "" else key.substring(0, bar)
        val name = if (bar < 0) key else key.substring(bar + 1)
        if (type.isEmpty() && name.isEmpty()) return null to null
        return type.ifEmpty { null } to name.ifEmpty { null }
    }

    private class Raw(val id: Long, val account: String)

    // ---- Reading ------------------------------------------------------------

    /** The raw contact a contact is edited through (see the class comment), or null when it is gone. */
    fun primaryRawId(context: Context, contactId: Long): Long? = primaryRaw(context, contactId)?.id

    private fun primaryRaw(context: Context, contactId: Long): Raw? {
        val resolver = context.contentResolver
        val withPhone = resolver.query(
            Data.CONTENT_URI, arrayOf(Data.RAW_CONTACT_ID),
            "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), Phone.CONTENT_ITEM_TYPE),
            "${Data.RAW_CONTACT_ID} ASC",
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        val rawId = withPhone ?: resolver.query(
            RawContacts.CONTENT_URI, arrayOf(RawContacts._ID),
            "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0",
            arrayOf(contactId.toString()), "${RawContacts._ID} ASC",
        )?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        return resolver.query(
            RawContacts.CONTENT_URI, arrayOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
            "${RawContacts._ID} = ?", arrayOf(rawId.toString()), null,
        )?.use { if (it.moveToFirst()) Raw(rawId, accountKey(it.getString(0), it.getString(1))) else null }
    }

    private fun kindOfPhone(type: Int) = when (type) {
        Phone.TYPE_MOBILE, Phone.TYPE_WORK_MOBILE -> "mobile"
        Phone.TYPE_HOME -> "home"
        Phone.TYPE_WORK -> "work"
        Phone.TYPE_CUSTOM -> "custom"
        else -> "other"
    }

    private fun kindOfEmail(type: Int) = when (type) {
        Email.TYPE_HOME -> "home"
        Email.TYPE_WORK -> "work"
        Email.TYPE_CUSTOM -> "custom"
        else -> "other"
    }

    private fun kindOfEvent(type: Int) = when (type) {
        Event.TYPE_BIRTHDAY -> "birthday"
        Event.TYPE_ANNIVERSARY -> "anniversary"
        Event.TYPE_CUSTOM -> "custom"
        else -> "other"
    }

    private fun typeOfEvent(kind: String) = when (kind) {
        "birthday" -> Event.TYPE_BIRTHDAY
        "anniversary" -> Event.TYPE_ANNIVERSARY
        "custom" -> Event.TYPE_CUSTOM
        else -> Event.TYPE_OTHER
    }

    private fun kindOfPostal(type: Int) = when (type) {
        StructuredPostal.TYPE_HOME -> "home"
        StructuredPostal.TYPE_WORK -> "work"
        StructuredPostal.TYPE_CUSTOM -> "custom"
        else -> "other"
    }

    private fun typeOfPostal(kind: String) = when (kind) {
        "home" -> StructuredPostal.TYPE_HOME
        "work" -> StructuredPostal.TYPE_WORK
        "custom" -> StructuredPostal.TYPE_CUSTOM
        else -> StructuredPostal.TYPE_OTHER
    }

    private fun typeOfPhone(kind: String) = when (kind) {
        "mobile" -> Phone.TYPE_MOBILE
        "home" -> Phone.TYPE_HOME
        "work" -> Phone.TYPE_WORK
        "custom" -> Phone.TYPE_CUSTOM
        else -> Phone.TYPE_OTHER
    }

    private fun typeOfEmail(kind: String) = when (kind) {
        "home" -> Email.TYPE_HOME
        "work" -> Email.TYPE_WORK
        "custom" -> Email.TYPE_CUSTOM
        else -> Email.TYPE_OTHER
    }

    private fun field(value: String, kind: String, label: String?, primary: Boolean = false): JSONObject {
        val o = JSONObject().put("value", value).put("kind", kind)
        if (kind == "custom" && !label.isNullOrBlank()) o.put("label", label)
        if (primary) o.put("primary", true)
        return o
    }

    /**
     * One draft being filled from `Data` rows. [add] reads a row whose columns are
     * MIMETYPE, DATA1, DATA2, DATA3, DATA5, DATA4, IS_SUPER_PRIMARY starting at index `base`. A name, organisation or
     * note already set wins, so a contact merged from several raw contacts keeps the first.
     */
    private class DraftBuilder(val contactId: Long, val account: String = "") {
        var given = ""
        var family = ""
        var org = ""
        var title = ""
        var nickname = ""
        val websites = JSONArray()
        var note = ""
        var starred = false
        val numbers = JSONArray()
        val emails = JSONArray()
        val events = JSONArray()
        val addresses = JSONArray()
        val groups = JSONArray()

        fun add(c: Cursor, base: Int) {
            val data1 = c.getString(base + 1)
            when (c.getString(base)) {
                StructuredName.CONTENT_ITEM_TYPE -> if (given.isEmpty() && family.isEmpty()) {
                    given = listOf(c.getString(base + 2), c.getString(base + 4))
                        .filter { !it.isNullOrBlank() }.joinToString(" ")
                    family = c.getString(base + 3).orEmpty()
                }
                Phone.CONTENT_ITEM_TYPE -> if (!data1.isNullOrBlank()) {
                    numbers.put(field(data1, kindOfPhone(c.getInt(base + 2)), c.getString(base + 3), c.getInt(base + 6) != 0))
                }
                Email.CONTENT_ITEM_TYPE -> if (!data1.isNullOrBlank()) {
                    emails.put(field(data1, kindOfEmail(c.getInt(base + 2)), c.getString(base + 3), c.getInt(base + 6) != 0))
                }
                Event.CONTENT_ITEM_TYPE -> if (!data1.isNullOrBlank()) {
                    val kind = kindOfEvent(c.getInt(base + 2))
                    val o = JSONObject().put("date", data1).put("kind", kind)
                    val label = c.getString(base + 3)
                    if (kind == "custom" && !label.isNullOrBlank()) o.put("label", label)
                    events.put(o)
                }
                StructuredPostal.CONTENT_ITEM_TYPE -> if (!data1.isNullOrBlank()) {
                    addresses.put(field(data1, kindOfPostal(c.getInt(base + 2)), c.getString(base + 3)))
                }
                Organization.CONTENT_ITEM_TYPE -> {
                    if (org.isEmpty()) org = data1.orEmpty()
                    if (title.isEmpty()) title = c.getString(base + 5).orEmpty()
                }
                Website.CONTENT_ITEM_TYPE -> if (!data1.isNullOrBlank()) websites.put(data1.trim())
                Nickname.CONTENT_ITEM_TYPE -> if (nickname.isEmpty()) nickname = data1.orEmpty()
                Note.CONTENT_ITEM_TYPE -> if (note.isEmpty()) note = data1.orEmpty()
                GroupMembership.CONTENT_ITEM_TYPE -> c.getLong(base + 1).takeIf { it > 0 }?.let { groups.put(it) }
            }
        }

        fun toJson(): JSONObject {
            val o = JSONObject()
                .put("id", contactId)
                .put("given", given).put("family", family).put("org", org).put("note", note)
                .put("numbers", numbers).put("emails", emails).put("groups", groups)
                .put("starred", starred)
            if (events.length() > 0) o.put("events", events)
            if (addresses.length() > 0) o.put("addresses", addresses)
            if (websites.length() > 0) o.put("websites", websites)
            if (nickname.isNotEmpty()) o.put("nickname", nickname)
            if (title.isNotEmpty()) o.put("title", title)
            if (account.isNotEmpty()) o.put("account", account)
            return o
        }
    }

    private val DATA_PROJECTION = arrayOf(Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA5, Data.DATA4, Data.IS_SUPER_PRIMARY)
    private val DATA_MIMES = EDITED_MIMES + GroupMembership.CONTENT_ITEM_TYPE

    private fun starred(context: Context, contactId: Long): Boolean =
        context.contentResolver.query(
            ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId),
            arrayOf(Contacts.STARRED), null, null, null,
        )?.use { it.moveToFirst() && it.getInt(0) != 0 } ?: false

    /** `{contact: ContactDraft}` for one contact, read from its primary raw contact. */
    fun detailJson(context: Context, contactId: Long): String = guard {
        val raw = primaryRaw(context, contactId) ?: return ProviderJson.error("not_found")
        val builder = DraftBuilder(contactId, raw.account)
        builder.starred = starred(context, contactId)
        context.contentResolver.query(
            Data.CONTENT_URI, DATA_PROJECTION,
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} IN (${DATA_MIMES.joinToString(",") { "?" }})",
            arrayOf(raw.id.toString(), *DATA_MIMES), "${Data._ID} ASC",
        )?.use { c -> while (c.moveToNext()) builder.add(c, 0) }
        ProviderJson.ok(JSONObject().put("contact", builder.toJson()))
    }

    /** `{contacts: [ContactDraft]}` for every contact, with or without a number: the export. */
    fun fullJson(context: Context): String = guard {
        val builders = LinkedHashMap<Long, DraftBuilder>()
        context.contentResolver.query(
            Data.CONTENT_URI, arrayOf(Data.CONTACT_ID) + DATA_PROJECTION,
            "${Data.MIMETYPE} IN (${EDITED_MIMES.joinToString(",") { "?" }})",
            EDITED_MIMES, "${Data.CONTACT_ID} ASC, ${Data._ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) builders.getOrPut(c.getLong(0)) { DraftBuilder(c.getLong(0)) }.add(c, 1)
        }
        val contacts = JSONArray()
        for (b in builders.values) contacts.put(b.toJson())
        ProviderJson.ok(JSONObject().put("contacts", contacts))
    }

    // ---- Writing ------------------------------------------------------------

    private fun str(o: JSONObject, key: String): String = o.optString(key, "").trim()

    /** A default field is written as both IS_PRIMARY and IS_SUPER_PRIMARY (the update path re-inserts rows). */
    private fun ContentProviderOperation.Builder.withPrimary(primary: Boolean): ContentProviderOperation.Builder =
        if (primary) withValue(Data.IS_PRIMARY, 1).withValue(Data.IS_SUPER_PRIMARY, 1) else this

    /** Appends the inserts for [draft]; `rawId` for an existing raw contact, else a back reference. */
    private fun insertData(ops: ArrayList<ContentProviderOperation>, draft: JSONObject, rawId: Long?, backRef: Int) {
        fun insert(mime: String): ContentProviderOperation.Builder {
            val b = ContentProviderOperation.newInsert(Data.CONTENT_URI)
            if (rawId != null) b.withValue(Data.RAW_CONTACT_ID, rawId) else b.withValueBackReference(Data.RAW_CONTACT_ID, backRef)
            return b.withValue(Data.MIMETYPE, mime)
        }
        val given = str(draft, "given")
        val family = str(draft, "family")
        if (given.isNotEmpty() || family.isNotEmpty()) {
            ops.add(
                insert(StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(StructuredName.GIVEN_NAME, given.ifEmpty { null })
                    .withValue(StructuredName.FAMILY_NAME, family.ifEmpty { null })
                    .build(),
            )
        }
        val org = str(draft, "org")
        val title = str(draft, "title")
        if (org.isNotEmpty() || title.isNotEmpty()) {
            ops.add(
                insert(Organization.CONTENT_ITEM_TYPE)
                    .withValue(Organization.COMPANY, org.ifEmpty { null })
                    .withValue(Organization.TITLE, title.ifEmpty { null })
                    .build(),
            )
        }
        str(draft, "nickname").takeIf { it.isNotEmpty() }?.let {
            ops.add(
                insert(Nickname.CONTENT_ITEM_TYPE)
                    .withValue(Nickname.NAME, it)
                    .withValue(Nickname.TYPE, Nickname.TYPE_DEFAULT)
                    .build(),
            )
        }
        draft.optJSONArray("websites")?.let { sites ->
            for (i in 0 until sites.length()) {
                val url = sites.optString(i, "").trim().takeIf { it.isNotEmpty() } ?: continue
                ops.add(
                    insert(Website.CONTENT_ITEM_TYPE)
                        .withValue(Website.URL, url)
                        .withValue(Website.TYPE, Website.TYPE_OTHER)
                        .build(),
                )
            }
        }
        str(draft, "note").takeIf { it.isNotEmpty() }?.let {
            ops.add(insert(Note.CONTENT_ITEM_TYPE).withValue(Note.NOTE, it).build())
        }
        draft.optJSONArray("numbers")?.let { numbers ->
            for (i in 0 until numbers.length()) {
                val n = numbers.optJSONObject(i) ?: continue
                val value = str(n, "value").takeIf { it.isNotEmpty() } ?: continue
                val kind = n.optString("kind", "other")
                ops.add(
                    insert(Phone.CONTENT_ITEM_TYPE)
                        .withValue(Phone.NUMBER, value)
                        .withValue(Phone.TYPE, typeOfPhone(kind))
                        .withValue(Phone.LABEL, if (kind == "custom") n.optString("label", "").ifEmpty { null } else null)
                        .withPrimary(n.optBoolean("primary", false))
                        .build(),
                )
            }
        }
        draft.optJSONArray("emails")?.let { emails ->
            for (i in 0 until emails.length()) {
                val e = emails.optJSONObject(i) ?: continue
                val value = str(e, "value").takeIf { it.isNotEmpty() } ?: continue
                val kind = e.optString("kind", "other")
                ops.add(
                    insert(Email.CONTENT_ITEM_TYPE)
                        .withValue(Email.ADDRESS, value)
                        .withValue(Email.TYPE, typeOfEmail(kind))
                        .withValue(Email.LABEL, if (kind == "custom") e.optString("label", "").ifEmpty { null } else null)
                        .withPrimary(e.optBoolean("primary", false))
                        .build(),
                )
            }
        }
        draft.optJSONArray("events")?.let { events ->
            for (i in 0 until events.length()) {
                val e = events.optJSONObject(i) ?: continue
                val date = str(e, "date").takeIf { it.isNotEmpty() } ?: continue
                val kind = e.optString("kind", "birthday")
                ops.add(
                    insert(Event.CONTENT_ITEM_TYPE)
                        .withValue(Event.START_DATE, date)
                        .withValue(Event.TYPE, typeOfEvent(kind))
                        .withValue(Event.LABEL, if (kind == "custom") e.optString("label", "").ifEmpty { null } else null)
                        .build(),
                )
            }
        }
        draft.optJSONArray("addresses")?.let { addresses ->
            for (i in 0 until addresses.length()) {
                val a = addresses.optJSONObject(i) ?: continue
                val value = str(a, "value").takeIf { it.isNotEmpty() } ?: continue
                val kind = a.optString("kind", "other")
                ops.add(
                    insert(StructuredPostal.CONTENT_ITEM_TYPE)
                        .withValue(StructuredPostal.FORMATTED_ADDRESS, value)
                        .withValue(StructuredPostal.TYPE, typeOfPostal(kind))
                        .withValue(StructuredPostal.LABEL, if (kind == "custom") a.optString("label", "").ifEmpty { null } else null)
                        .build(),
                )
            }
        }
    }

    private fun longs(array: JSONArray?): List<Long> =
        if (array == null) emptyList() else (0 until array.length()).map { array.optLong(it, 0) }.filter { it > 0 }

    /** Ids of the visible groups of [account] (the ones a contact of that account may join). */
    private fun groupIdsOf(context: Context, account: String): Set<Long> =
        queryGroups(context).filter { it.account == account }.map { it.id }.toSet()

    private fun contactIdOfRaw(context: Context, rawId: Long): Long? = context.contentResolver.query(
        RawContacts.CONTENT_URI, arrayOf(RawContacts.CONTACT_ID),
        "${RawContacts._ID} = ?", arrayOf(rawId.toString()), null,
    )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    /** Creates or updates a contact from a `ContactDraft`; the answer is `{id}`. */
    fun saveJson(context: Context, json: String): String = guard {
        val draft = JSONObject(json)
        val id = draft.optLong("id", 0)
        if (id > 0) update(context, id, draft) else create(context, draft)
    }

    private fun update(context: Context, contactId: Long, draft: JSONObject): String {
        val resolver = context.contentResolver
        val raw = primaryRaw(context, contactId) ?: return ProviderJson.error("not_found")
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newDelete(Data.CONTENT_URI)
                .withSelection(
                    "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} IN (${EDITED_MIMES.joinToString(",") { "?" }})",
                    arrayOf(raw.id.toString(), *EDITED_MIMES),
                ).build(),
        )
        insertData(ops, draft, raw.id, 0)
        // Only the groups of this account can change; memberships in hidden system groups stay.
        val visible = groupIdsOf(context, raw.account)
        val wanted = longs(draft.optJSONArray("groups")).filter { it in visible }.toSet()
        val existing = HashMap<Long, Long>() // group id to the membership row's _ID
        resolver.query(
            Data.CONTENT_URI, arrayOf(Data._ID, Data.DATA1),
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(raw.id.toString(), GroupMembership.CONTENT_ITEM_TYPE), null,
        )?.use { c -> while (c.moveToNext()) existing[c.getLong(1)] = c.getLong(0) }
        for ((group, row) in existing) {
            if (group in visible && group !in wanted) {
                ops.add(ContentProviderOperation.newDelete(ContentUris.withAppendedId(Data.CONTENT_URI, row)).build())
            }
        }
        for (group in wanted) {
            if (group !in existing) {
                ops.add(
                    ContentProviderOperation.newInsert(Data.CONTENT_URI)
                        .withValue(Data.RAW_CONTACT_ID, raw.id)
                        .withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
                        .withValue(GroupMembership.GROUP_ROW_ID, group)
                        .build(),
                )
            }
        }
        resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        val values = ContentValues().apply { put(Contacts.STARRED, if (draft.optBoolean("starred")) 1 else 0) }
        // The aggregate may have changed id while its name changed; follow the raw contact.
        val now = contactIdOfRaw(context, raw.id) ?: contactId
        resolver.update(ContentUris.withAppendedId(Contacts.CONTENT_URI, now), values, null, null)
        return ProviderJson.ok(JSONObject().put("id", now))
    }

    private fun create(context: Context, draft: JSONObject): String {
        val ops = ArrayList<ContentProviderOperation>()
        val rawIndex = appendNew(ops, draft, context, account = str(draft, "account"))
        val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        val rawId = ContentUris.parseId(results[rawIndex].uri ?: return ProviderJson.error("unknown"))
        val id = contactIdOfRaw(context, rawId) ?: return ProviderJson.error("unknown")
        return ProviderJson.ok(JSONObject().put("id", id))
    }

    /** Appends the operations for a new phone-only contact; returns the index of its raw-contact insert. */
    private fun appendNew(
        ops: ArrayList<ContentProviderOperation>, draft: JSONObject, context: Context,
        local: Set<Long>? = null, account: String = "",
    ): Int {
        val rawIndex = ops.size
        val (accountType, accountName) = splitAccount(account)
        ops.add(
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, accountType)
                .withValue(RawContacts.ACCOUNT_NAME, accountName)
                .withValue(RawContacts.STARRED, if (draft.optBoolean("starred")) 1 else 0)
                .build(),
        )
        insertData(ops, draft, null, rawIndex)
        val visible = local ?: groupIdsOf(context, accountKey(accountType, accountName))
        for (group in longs(draft.optJSONArray("groups")).toSet()) {
            if (group !in visible) continue
            ops.add(
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, rawIndex)
                    .withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
                    .withValue(GroupMembership.GROUP_ROW_ID, group)
                    .build(),
            )
        }
        return rawIndex
    }

    /** Deletes the whole contact (all its raw contacts). */
    fun delete(context: Context, contactId: Long): Boolean = try {
        context.contentResolver.delete(ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId), null, null) > 0
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }

    /** Adds phone-only contacts from `{contacts: [ContactDraft]}`; the answer is `{added}`. */
    fun importJson(context: Context, json: String): String = guard {
        val drafts = JSONObject(json).optJSONArray("contacts") ?: JSONArray()
        val local = groupIdsOf(context, "")
        var added = 0
        var start = 0
        while (start < drafts.length()) {
            val end = minOf(start + IMPORT_CHUNK, drafts.length())
            val ops = ArrayList<ContentProviderOperation>()
            for (i in start until end) {
                val draft = drafts.optJSONObject(i) ?: continue
                appendNew(ops, draft, context, local)
            }
            if (ops.isNotEmpty()) {
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                added += end - start
            }
            start = end
        }
        ProviderJson.ok(JSONObject().put("added", added))
    }

    // ---- Groups -------------------------------------------------------------

    private class GroupRow(val id: Long, val title: String, val account: String)

    private fun queryGroups(context: Context): List<GroupRow> {
        val out = ArrayList<GroupRow>()
        context.contentResolver.query(
            Groups.CONTENT_URI,
            arrayOf(
                Groups._ID, Groups.TITLE, Groups.ACCOUNT_TYPE, Groups.ACCOUNT_NAME,
                Groups.SYSTEM_ID, Groups.AUTO_ADD, Groups.FAVORITES,
            ),
            "${Groups.DELETED} = 0 AND ${Groups.GROUP_VISIBLE} = 1", null, "${Groups.TITLE} COLLATE LOCALIZED ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                // "My Contacts", Android's Starred group and auto-add groups are not labels the owner made.
                if (c.getString(4) == "Contacts" || c.getInt(5) != 0 || c.getInt(6) != 0) continue
                val title = c.getString(1)?.removePrefix("System Group: ")?.trim().orEmpty()
                if (title.isEmpty()) continue
                out.add(GroupRow(c.getLong(0), title, accountKey(c.getString(2), c.getString(3))))
            }
        }
        return out
    }

    /** `{groups: [Group]}` with the members of each. */
    fun groupsJson(context: Context): String = guard {
        val rows = queryGroups(context)
        val members = HashMap<Long, JSONArray>()
        context.contentResolver.query(
            Data.CONTENT_URI, arrayOf(Data.CONTACT_ID, Data.DATA1),
            "${Data.MIMETYPE} = ?", arrayOf(GroupMembership.CONTENT_ITEM_TYPE), null,
        )?.use { c -> while (c.moveToNext()) members.getOrPut(c.getLong(1)) { JSONArray() }.put(c.getLong(0)) }
        val groups = JSONArray()
        for (g in rows) {
            val o = JSONObject().put("id", g.id).put("title", g.title).put("members", members[g.id] ?: JSONArray())
            if (g.account.isNotEmpty()) o.put("account", g.account)
            groups.put(o)
        }
        ProviderJson.ok(JSONObject().put("groups", groups))
    }

    /**
     * Creates a group in [account] (`type|name`, empty for the phone) when [groupId] is 0, or
     * renames one; the answer is `{id}`.
     */
    fun saveGroup(context: Context, groupId: Long, title: String, account: String): String = guard {
        val resolver = context.contentResolver
        if (groupId > 0) {
            val values = ContentValues().apply { put(Groups.TITLE, title) }
            val n = resolver.update(ContentUris.withAppendedId(Groups.CONTENT_URI, groupId), values, null, null)
            if (n > 0) ProviderJson.ok(JSONObject().put("id", groupId)) else ProviderJson.error("not_found")
        } else {
            val (accountType, accountName) = splitAccount(account)
            val values = ContentValues().apply {
                put(Groups.ACCOUNT_TYPE, accountType)
                put(Groups.ACCOUNT_NAME, accountName)
                put(Groups.TITLE, title)
                put(Groups.GROUP_VISIBLE, 1)
            }
            val uri = resolver.insert(Groups.CONTENT_URI, values)
            if (uri != null) ProviderJson.ok(JSONObject().put("id", ContentUris.parseId(uri))) else ProviderJson.error("refused")
        }
    }

    /**
     * Applies `{add:[contactId], remove:[contactId]}` to the group [groupId] in one `applyBatch`;
     * the answer is `{added, removed, skipped}`. A contact without a live raw contact in the
     * group's account is skipped, and one already in the group is left alone.
     */
    fun setGroupMembers(context: Context, groupId: Long, json: String): String = guard {
        val resolver = context.contentResolver
        val account = resolver.query(
            Groups.CONTENT_URI, arrayOf(Groups.ACCOUNT_TYPE, Groups.ACCOUNT_NAME),
            "${Groups._ID} = ?", arrayOf(groupId.toString()), null,
        )?.use { if (it.moveToFirst()) accountKey(it.getString(0), it.getString(1)) else null }
            ?: return ProviderJson.error("not_found")
        val change = JSONObject(json)
        val ops = ArrayList<ContentProviderOperation>()
        var added = 0
        var removed = 0
        var skipped = 0
        for (contactId in longs(change.optJSONArray("add"))) {
            var target: Long? = null
            var member = false
            resolver.query(
                RawContacts.CONTENT_URI,
                arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
                "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0", arrayOf(contactId.toString()),
                "${RawContacts._ID} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    if (target == null && accountKey(c.getString(1), c.getString(2)) == account) target = c.getLong(0)
                }
            }
            val rawId = target
            if (rawId == null) {
                skipped++
                continue
            }
            resolver.query(
                Data.CONTENT_URI, arrayOf(Data._ID),
                "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${GroupMembership.GROUP_ROW_ID} = ?",
                arrayOf(contactId.toString(), GroupMembership.CONTENT_ITEM_TYPE, groupId.toString()), null,
            )?.use { member = it.moveToFirst() }
            if (member) continue
            ops.add(
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValue(Data.RAW_CONTACT_ID, rawId)
                    .withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
                    .withValue(GroupMembership.GROUP_ROW_ID, groupId)
                    .build(),
            )
            added++
        }
        for (contactId in longs(change.optJSONArray("remove"))) {
            ops.add(
                ContentProviderOperation.newDelete(Data.CONTENT_URI)
                    .withSelection(
                        "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${GroupMembership.GROUP_ROW_ID} = ?",
                        arrayOf(contactId.toString(), GroupMembership.CONTENT_ITEM_TYPE, groupId.toString()),
                    )
                    .build(),
            )
            removed++
        }
        if (ops.isNotEmpty()) resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        ProviderJson.ok(JSONObject().put("added", added).put("removed", removed).put("skipped", skipped))
    }

    fun deleteGroup(context: Context, groupId: Long): Boolean = try {
        context.contentResolver.delete(ContentUris.withAppendedId(Groups.CONTENT_URI, groupId), null, null) > 0
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }

    // ---- Accounts -----------------------------------------------------------

    /** Accounts whose sync adapter for the contacts authority uploads changes, as `type` to `name`. */
    private fun accountsOf(context: Context): List<Pair<String, String>> {
        val uploading = ContentResolver.getSyncAdapterTypes()
            .filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() }
            .map { it.accountType }.toSet()
        val found = LinkedHashSet<Pair<String, String>>()
        fun collect(uri: android.net.Uri, typeColumn: String, nameColumn: String) {
            context.contentResolver.query(
                uri, arrayOf(typeColumn, nameColumn), "$typeColumn IS NOT NULL AND $nameColumn IS NOT NULL", null, null,
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
     * `{accounts: [{key, name, kind}]}`: the phone itself (empty key) first, then every account
     * seen in the Groups and RawContacts tables whose sync adapter uploads contacts. No
     * `GET_ACCOUNTS` is needed because the accounts come from the providers.
     */
    fun accountsJson(context: Context): String = guard {
        val accounts = JSONArray()
        accounts.put(JSONObject().put("key", "").put("name", "").put("kind", ""))
        for ((type, name) in accountsOf(context).sortedWith(compareBy({ it.first }, { it.second }))) {
            accounts.put(JSONObject().put("key", accountKey(type, name)).put("name", name).put("kind", type))
        }
        ProviderJson.ok(JSONObject().put("accounts", accounts))
    }
}
