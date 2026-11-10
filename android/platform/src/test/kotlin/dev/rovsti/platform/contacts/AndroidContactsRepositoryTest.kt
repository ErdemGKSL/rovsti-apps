package dev.rovsti.platform.contacts

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
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.DraftEvent
import dev.rovsti.core.model.contacts.DraftField
import dev.rovsti.core.model.contacts.DraftRelation
import dev.rovsti.core.model.contacts.EventKind
import dev.rovsti.core.model.contacts.FieldKind
import dev.rovsti.core.model.contacts.GroupMembersResult
import dev.rovsti.core.model.contacts.RelationKind
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.platform.RecordingProvider
import dev.rovsti.platform.RecordingProvider.Kind
import dev.rovsti.platform.RecordingProvider.Op
import dev.rovsti.platform.RecordingProvider.QueryCall
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidContactsRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private val provider = Robolectric.setupContentProvider(RecordingProvider::class.java, ContactsContract.AUTHORITY)

    private fun TestScope.repository() = AndroidContactsRepository(context, StandardTestDispatcher(testScheduler))

    private val everyMime =
        ContactDraft(
            id = 42,
            given = "Ada",
            family = "Lovelace",
            phoneticGiven = "AY-da",
            phoneticFamily = "LUV-lace",
            org = "Analytical Engines",
            title = "Programmer",
            nickname = "Countess",
            note = "First programmer",
            numbers =
                listOf(
                    DraftField("0151 111", FieldKind.Mobile, primary = true),
                    DraftField("0151 222", FieldKind.Custom, "Lab"),
                ),
            emails = listOf(DraftField("ada@example.org", FieldKind.Work)),
            addresses = listOf(DraftField("12 St James's Square", FieldKind.Home)),
            websites = listOf("https://example.org/ada"),
            events = listOf(DraftEvent("1815-12-10", EventKind.Birthday)),
            relations = listOf(DraftRelation("Annabella", RelationKind.Mother)),
            starred = true,
        )

    /** A provider that knows contact 42: raw contact 7 in the phone account, no groups. */
    private fun knowContact42() {
        provider.respond(Data.CONTENT_URI) { call ->
            when {
                call.projection == listOf(Data.RAW_CONTACT_ID) -> listOf(mapOf(Data.RAW_CONTACT_ID to 7L))
                else -> emptyList()
            }
        }
        provider.respond(RawContacts.CONTENT_URI) { call ->
            when (call.projection) {
                listOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME) -> listOf(emptyMap())
                listOf(RawContacts.CONTACT_ID) -> listOf(mapOf(RawContacts.CONTACT_ID to 42L))
                else -> emptyList()
            }
        }
    }

    private fun Op.string(column: String): String? = values.getAsString(column)

    @Test
    fun savingEveryEditedMimeIsOneBatchWithAnOperationPerMime() =
        runTest {
            knowContact42()
            val result = repository().save(everyMime)
            assertEquals(ContactResult.Ok(42L), result)

            assertEquals("one applyBatch", 1, provider.batches.size)
            val ops = provider.batches.single()
            // The first operation clears the edited mimes of raw contact 7, naming every one of them.
            val clear = ops.first()
            assertEquals(Kind.Delete, clear.kind)
            assertEquals(listOf("7") + ContactsEdit.EDITED_MIMES, clear.args)
            // Then one insert per mime (every list holds one entry, but numbers hold two).
            val inserts = ops.drop(1)
            assertTrue(inserts.all { it.kind == Kind.Insert && it.values.getAsLong(Data.RAW_CONTACT_ID) == 7L })
            val byMime = inserts.groupBy { it.mime }
            assertEquals(ContactsEdit.EDITED_MIMES.toSet(), byMime.keys)
            for (mime in ContactsEdit.EDITED_MIMES) {
                val expected = if (mime == Phone.CONTENT_ITEM_TYPE) 2 else 1
                assertEquals("operations for $mime", expected, byMime.getValue(mime).size)
            }

            fun row(mime: String) = byMime.getValue(mime).first()
            assertEquals("Ada", row(StructuredName.CONTENT_ITEM_TYPE).string(StructuredName.GIVEN_NAME))
            assertEquals("Lovelace", row(StructuredName.CONTENT_ITEM_TYPE).string(StructuredName.FAMILY_NAME))
            assertEquals("AY-da", row(StructuredName.CONTENT_ITEM_TYPE).string(StructuredName.PHONETIC_GIVEN_NAME))
            assertEquals("LUV-lace", row(StructuredName.CONTENT_ITEM_TYPE).string(StructuredName.PHONETIC_FAMILY_NAME))
            assertEquals("Analytical Engines", row(Organization.CONTENT_ITEM_TYPE).string(Organization.COMPANY))
            assertEquals("Programmer", row(Organization.CONTENT_ITEM_TYPE).string(Organization.TITLE))
            assertEquals("Countess", row(Nickname.CONTENT_ITEM_TYPE).string(Nickname.NAME))
            assertEquals("First programmer", row(Note.CONTENT_ITEM_TYPE).string(Note.NOTE))
            assertEquals("ada@example.org", row(Email.CONTENT_ITEM_TYPE).string(Email.ADDRESS))
            assertEquals(Email.TYPE_WORK, row(Email.CONTENT_ITEM_TYPE).values.getAsInteger(Email.TYPE))
            assertEquals(
                "12 St James's Square",
                row(StructuredPostal.CONTENT_ITEM_TYPE).string(StructuredPostal.FORMATTED_ADDRESS),
            )
            assertEquals(
                StructuredPostal.TYPE_HOME,
                row(StructuredPostal.CONTENT_ITEM_TYPE).values.getAsInteger(StructuredPostal.TYPE),
            )
            assertEquals("https://example.org/ada", row(Website.CONTENT_ITEM_TYPE).string(Website.URL))
            assertEquals("1815-12-10", row(Event.CONTENT_ITEM_TYPE).string(Event.START_DATE))
            assertEquals(Event.TYPE_BIRTHDAY, row(Event.CONTENT_ITEM_TYPE).values.getAsInteger(Event.TYPE))
            assertEquals("Annabella", row(Relation.CONTENT_ITEM_TYPE).string(Relation.NAME))
            assertEquals(Relation.TYPE_MOTHER, row(Relation.CONTENT_ITEM_TYPE).values.getAsInteger(Relation.TYPE))

            val numbers = byMime.getValue(Phone.CONTENT_ITEM_TYPE)
            assertEquals("0151 111", numbers[0].string(Phone.NUMBER))
            assertEquals(Phone.TYPE_MOBILE, numbers[0].values.getAsInteger(Phone.TYPE))
            assertEquals(
                "a default number is primary and super primary",
                1,
                numbers[0].values.getAsInteger(Data.IS_SUPER_PRIMARY),
            )
            assertEquals(1, numbers[0].values.getAsInteger(Data.IS_PRIMARY))
            assertEquals(Phone.TYPE_CUSTOM, numbers[1].values.getAsInteger(Phone.TYPE))
            assertEquals("Lab", numbers[1].string(Phone.LABEL))
            assertEquals(null, numbers[1].values.get(Data.IS_SUPER_PRIMARY))

            // The star follows in a plain update of the aggregate contact.
            val star = provider.updates.single()
            assertEquals(
                ContactsContract.Contacts.CONTENT_URI
                    .buildUpon()
                    .appendPath("42")
                    .build(),
                star.uri,
            )
            assertEquals(1, star.values.getAsInteger(Contacts.STARRED))
        }

    @Test
    fun aNewContactIsOneBatchWhoseInsertsPointBackAtTheRawContact() =
        runTest {
            knowContact42()
            val result = repository().save(everyMime.copy(id = null, account = "com.google|ada@example.org"))
            assertEquals(ContactResult.Ok(42L), result)
            val ops = provider.batches.single()
            val raw = ops.first()
            assertEquals(RawContacts.CONTENT_URI, raw.uri)
            assertEquals("com.google", raw.string(RawContacts.ACCOUNT_TYPE))
            assertEquals("ada@example.org", raw.string(RawContacts.ACCOUNT_NAME))
            assertEquals(1, raw.values.getAsInteger(RawContacts.STARRED))
            val inserts = ops.drop(1)
            assertEquals(ContactsEdit.EDITED_MIMES.toSet(), inserts.map { it.mime }.toSet())
            // 100 is the id the provider answered for the raw-contact insert.
            assertTrue(inserts.all { it.values.getAsLong(Data.RAW_CONTACT_ID) == 100L })
        }

    @Test
    fun aMissingContactCannotBeSaved() =
        runTest {
            // The default provider answers no rows: contact 42 has no raw contact.
            assertEquals(ContactResult.failed(ContactResult.Reason.NotFound), repository().save(everyMime))
            assertTrue(provider.batches.isEmpty())
        }

    @Test
    fun readsComeFromTheSeededRows() =
        runTest {
            provider.seed(
                Phone.CONTENT_URI,
                phoneRow(1, "Ada Lovelace", "0151 111", starred = 1, superPrimary = 0),
                phoneRow(1, "Ada Lovelace", "0151 222", starred = 1, superPrimary = 1),
                phoneRow(1, "Ada Lovelace", "01511 11", starred = 1, superPrimary = 0),
                phoneRow(2, "Bob", "0152 333", starred = 0, superPrimary = 0),
            )
            provider.seed(
                Contacts.CONTENT_URI,
                contactRow(1, "Ada Lovelace", starred = 1),
                contactRow(2, "Bob", starred = 0),
                contactRow(3, "Cleo, no number", starred = 0),
            )
            provider.seed(Email.CONTENT_URI, mapOf(Email.CONTACT_ID to 3L, Email.ADDRESS to " cleo@example.org "))
            val repository = repository()

            val withNumbers = mutableListOf<List<WireContact>>()
            val everyone = mutableListOf<List<WireContact>>()
            backgroundScope.launch { repository.withNumbers().collect { withNumbers += it } }
            backgroundScope.launch { repository.all().collect { everyone += it } }
            runCurrent()

            val ada = withNumbers.single().first { it.id == 1L }
            assertEquals("Ada Lovelace", ada.displayName)
            // The default number comes first and the formatted repeat of 0151 111 is dropped.
            assertEquals(listOf("0151 222", "0151 111"), ada.numbers.map { it.raw })
            assertTrue(ada.numbers.first().default)
            assertTrue(ada.starred)
            assertEquals("content://photos/1", ada.photoUri)
            assertEquals(listOf(1L, 2L), withNumbers.single().map { it.id })

            val cleo = everyone.single().first { it.id == 3L }
            assertTrue(cleo.numbers.isEmpty())
            assertEquals("cleo@example.org", cleo.email)
            assertEquals(listOf(1L, 2L, 3L), everyone.single().map { it.id })
        }

    @Test
    fun theFullListAndOneDetailAreReadFromDataRows() =
        runTest {
            knowContact42()
            val columns =
                listOf(
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

            fun data(
                contact: Long?,
                mime: String,
                d1: String?,
                d2: Any? = null,
                d3: String? = null,
                d5: String? = null,
                d4: String? = null,
                primary: Int = 0,
            ): Map<String, Any?> =
                buildMap {
                    if (contact != null) put(Data.CONTACT_ID, contact)
                    put(Data.MIMETYPE, mime)
                    put(Data.DATA1, d1)
                    put(Data.DATA2, d2)
                    put(Data.DATA3, d3)
                    put(Data.DATA5, d5)
                    put(Data.DATA4, d4)
                    put(Data.IS_SUPER_PRIMARY, primary)
                }
            provider.respond(Data.CONTENT_URI) { call ->
                when {
                    call.projection == listOf(Data.RAW_CONTACT_ID) -> {
                        listOf(mapOf(Data.RAW_CONTACT_ID to 7L))
                    }

                    call.projection == listOf(Data.CONTACT_ID) + columns -> {
                        listOf(
                            data(42, StructuredName.CONTENT_ITEM_TYPE, "Ada Lovelace", "Ada", "Lovelace"),
                            data(42, Phone.CONTENT_ITEM_TYPE, "0151 111", Phone.TYPE_WORK, primary = 1),
                            data(43, StructuredName.CONTENT_ITEM_TYPE, "Bob", "Bob", ""),
                        )
                    }

                    call.projection == columns -> {
                        listOf(
                            data(null, StructuredName.CONTENT_ITEM_TYPE, "Ada Lovelace", "Ada", "Lovelace"),
                            data(null, Organization.CONTENT_ITEM_TYPE, "Analytical Engines", d4 = "Programmer"),
                            data(null, Email.CONTENT_ITEM_TYPE, "ada@example.org", Email.TYPE_HOME),
                            data(null, Relation.CONTENT_ITEM_TYPE, " Annabella ", Relation.TYPE_MOTHER),
                            data(null, Event.CONTENT_ITEM_TYPE, "1815-12-10", Event.TYPE_BIRTHDAY),
                            data(null, GroupMembership.CONTENT_ITEM_TYPE, "5"),
                        )
                    }

                    else -> {
                        emptyList()
                    }
                }
            }
            provider.seed(Contacts.CONTENT_URI, mapOf(Contacts.STARRED to 1))
            val repository = repository()
            val full = mutableListOf<List<ContactDraft>>()
            val detail = mutableListOf<ContactDraft?>()
            backgroundScope.launch { repository.full().collect { full += it } }
            backgroundScope.launch { repository.detail(42).collect { detail += it } }
            runCurrent()

            assertEquals(listOf(42L, 43L), full.single().map { it.id })
            val ada = full.single().first()
            assertEquals("Ada", ada.given)
            assertEquals(listOf(DraftField("0151 111", FieldKind.Work, primary = true)), ada.numbers)

            val one = detail.single()!!
            assertEquals("Analytical Engines", one.org)
            assertEquals("Programmer", one.title)
            assertEquals(listOf(DraftField("ada@example.org", FieldKind.Home)), one.emails)
            assertEquals(listOf(DraftRelation("Annabella", RelationKind.Mother)), one.relations)
            assertEquals(listOf(DraftEvent("1815-12-10", EventKind.Birthday)), one.events)
            assertEquals(listOf(5L), one.groups)
            assertTrue(one.starred)
        }

    @Test
    fun starringWritesTheStarredColumnOfTheContact() =
        runTest {
            val repository = repository()
            assertEquals(ContactResult.Done, repository.setStarred(5, true))
            assertEquals(ContactResult.Done, repository.setStarred(5, false))
            val (on, off) = provider.updates
            assertEquals(
                Contacts.CONTENT_URI
                    .buildUpon()
                    .appendPath("5")
                    .build(),
                on.uri,
            )
            assertEquals(1, on.values.getAsInteger(Contacts.STARRED))
            assertEquals(0, off.values.getAsInteger(Contacts.STARRED))
        }

    @Test
    fun groupMembersAreADiffInOneBatch() =
        runTest {
            val account = mapOf(Groups.ACCOUNT_TYPE to "com.google", Groups.ACCOUNT_NAME to "ada@example.org")
            provider.respond(Groups.CONTENT_URI) { listOf(account) }
            provider.respond(RawContacts.CONTENT_URI) { call ->
                val own =
                    mapOf(
                        RawContacts._ID to 70L,
                        RawContacts.ACCOUNT_TYPE to "com.google",
                        RawContacts.ACCOUNT_NAME to "ada@example.org",
                    )
                val other =
                    mapOf(
                        RawContacts._ID to 71L,
                        RawContacts.ACCOUNT_TYPE to "com.other",
                        RawContacts.ACCOUNT_NAME to "x",
                    )
                when (call.args?.firstOrNull()) {
                    "10", "12" -> listOf(own)
                    "11" -> listOf(other)
                    else -> emptyList()
                }
            }
            // Contact 12 is in the group already.
            provider.respond(Data.CONTENT_URI) { call ->
                if (call.args?.first() ==
                    "12"
                ) {
                    listOf(mapOf(Data._ID to 900L))
                } else {
                    emptyList()
                }
            }

            val result = repository().setMembers(groupId = 3, add = listOf(10, 11, 12), remove = listOf(20))

            assertEquals(ContactResult.Ok(GroupMembersResult(added = 1, removed = 1, skipped = 1)), result)
            val ops = provider.batches.single()
            assertEquals(listOf(Kind.Insert, Kind.Delete), ops.map { it.kind })
            val join = ops[0]
            assertEquals(GroupMembership.CONTENT_ITEM_TYPE, join.mime)
            assertEquals(70L, join.values.getAsLong(Data.RAW_CONTACT_ID))
            assertEquals(3L, join.values.getAsLong(GroupMembership.GROUP_ROW_ID))
            assertEquals(listOf("20", GroupMembership.CONTENT_ITEM_TYPE, "3"), ops[1].args)
        }

    @Test
    fun aGroupOfAnUnknownIdCannotChange() =
        runTest {
            assertEquals(
                ContactResult.failed(ContactResult.Reason.NotFound),
                repository().setMembers(3, listOf(1), emptyList()),
            )
            assertTrue(provider.batches.isEmpty())
        }

    @Test
    fun deletingAContactDeletesItsAggregate() =
        runTest {
            val repository = repository()
            assertEquals(ContactResult.Done, repository.delete(5))
            assertEquals(
                Contacts.CONTENT_URI
                    .buildUpon()
                    .appendPath("5")
                    .build(),
                provider.deletes.single().uri,
            )
            assertEquals(ContactResult.Done, repository.deleteGroup(3))
            assertEquals(
                Groups.CONTENT_URI
                    .buildUpon()
                    .appendPath("3")
                    .build(),
                provider.deletes.last().uri,
            )
        }

    @Test
    fun aNewGroupIsInsertedInItsAccountAndARenameIsAnUpdate() =
        runTest {
            val repository = repository()
            assertEquals(ContactResult.Ok(100L), repository.saveGroup(0, "Family", "com.google|ada@example.org"))
            val made = provider.inserts.single()
            assertEquals("Family", made.string(Groups.TITLE))
            assertEquals("com.google", made.string(Groups.ACCOUNT_TYPE))
            assertEquals(ContactResult.Ok(3L), repository.saveGroup(3, "Relatives", ""))
            assertEquals("Relatives", provider.updates.single().string(Groups.TITLE))
        }

    @Test
    fun aPhoneGroupHasNoAccountWhereTheDeviceNamesNoLocalAccount() =
        runTest {
            assertEquals(ContactResult.Ok(100L), repository().saveGroup(0, "Family", ""))
            val made = provider.inserts.single()
            assertEquals(null, made.string(Groups.ACCOUNT_TYPE))
            assertEquals(null, made.string(Groups.ACCOUNT_NAME))
        }

    @Test
    fun aRefusedGroupInsertIsRefusedAndLogged() =
        runTest {
            ShadowLog.clear()
            provider.refuseInserts = true
            assertEquals(
                ContactResult.failed(ContactResult.Reason.Refused),
                repository().saveGroup(0, "Family", ""),
            )
            val logged = ShadowLog.getLogsForTag(CONTACTS_TAG).single()
            assertEquals(android.util.Log.WARN, logged.type)
            assertTrue(logged.msg, logged.msg.contains("group insert"))
        }

    @Test
    fun aGroupWriteWithoutWriteContactsIsAPermissionFailureAndLogsTheException() =
        runTest {
            ShadowLog.clear()
            val denied = SecurityException("Permission Denial: requires android.permission.WRITE_CONTACTS")
            provider.failWith = denied
            val repository = repository()
            assertEquals(ContactResult.failed(ContactResult.Reason.Permission), repository.saveGroup(0, "Family", ""))
            assertEquals(
                ContactResult.failed(ContactResult.Reason.Permission),
                repository.setMembers(3, listOf(1), emptyList()),
            )
            val logged = ShadowLog.getLogsForTag(CONTACTS_TAG)
            assertEquals(2, logged.size)
            assertTrue(logged.all { it.type == android.util.Log.WARN && it.throwable === denied })
        }

    @Test
    fun aFailedMemberChangeLogsItsException() =
        runTest {
            ShadowLog.clear()
            provider.failWith = IllegalStateException("provider broke")
            assertEquals(
                ContactResult.failed(ContactResult.Reason.Unknown),
                repository().setMembers(3, listOf(1), emptyList()),
            )
            assertEquals(
                "provider broke",
                ShadowLog
                    .getLogsForTag(CONTACTS_TAG)
                    .single()
                    .throwable
                    ?.message,
            )
        }

    @Test
    fun rawContactCountsAreLiveRawContactsPerAccount() =
        runTest {
            provider.seed(
                RawContacts.CONTENT_URI,
                mapOf(RawContacts.ACCOUNT_TYPE to "com.google", RawContacts.ACCOUNT_NAME to "ada@example.org"),
                mapOf(RawContacts.ACCOUNT_TYPE to "com.google", RawContacts.ACCOUNT_NAME to "ada@example.org"),
                mapOf(RawContacts.ACCOUNT_TYPE to null, RawContacts.ACCOUNT_NAME to null),
            )
            assertEquals(
                ContactResult.Ok(mapOf("com.google|ada@example.org" to 2, "" to 1)),
                repository().rawContactCounts(),
            )
            assertEquals("${RawContacts.DELETED} = 0", provider.queriesTo(RawContacts.CONTENT_URI).single().selection)
        }

    @Test
    fun importWritesChunksOfFortyAndAnswersTheIds() =
        runTest {
            provider.respond(RawContacts.CONTENT_URI) { listOf(mapOf(RawContacts.CONTACT_ID to 500L)) }
            val drafts =
                List(
                    45,
                ) { ContactDraft(given = "Person $it", numbers = listOf(DraftField("0151 $it", FieldKind.Mobile))) }
            val result = repository().import(drafts) as ContactResult.Ok
            assertEquals(45, result.value.added)
            assertEquals(List(45) { 500L }, result.value.ids)
            assertEquals(listOf(40 * 3, 5 * 3), provider.batches.map { it.size })
        }

    @Test
    fun aBurstOfFiveChangesIsOneRequery() =
        runTest {
            provider.seed(Phone.CONTENT_URI, phoneRow(1, "Ada", "0151 111", starred = 0, superPrimary = 0))
            val seen = mutableListOf<List<WireContact>>()
            backgroundScope.launch { repository().withNumbers().collect { seen += it } }
            runCurrent()
            assertEquals("the first collect reads once", 1, provider.queriesTo(Phone.CONTENT_URI).size)
            assertEquals(1, seen.size)

            repeat(5) { context.contentResolver.notifyChange(Contacts.CONTENT_URI, null) }
            shadowOf(android.os.Looper.getMainLooper()).idle()
            runCurrent()
            assertEquals("nothing reads before the burst settles", 1, provider.queriesTo(Phone.CONTENT_URI).size)
            advanceTimeBy(AndroidContactsRepository.CHANGE_DEBOUNCE_MS + 1)
            runCurrent()

            assertEquals("five notifications read once more", 2, provider.queriesTo(Phone.CONTENT_URI).size)
            assertEquals(2, seen.size)
        }

    @Test
    fun theReadsObserveTheContactsAuthority() =
        runTest {
            backgroundScope.launch { repository().all().collect { } }
            runCurrent()
            val queries: List<QueryCall> = provider.queries
            assertTrue(queries.isNotEmpty())
            assertEquals(ContactsContract.AUTHORITY_URI.authority, Phone.CONTENT_URI.authority)
        }

    @Test
    fun optionsReadTheRingtoneAndVoicemailColumns() =
        runTest {
            provider.seed(
                Contacts.CONTENT_URI,
                mapOf(Contacts.CUSTOM_RINGTONE to null, Contacts.SEND_TO_VOICEMAIL to 1),
            )
            val repository = repository()
            val options = (repository.options(5) as ContactResult.Ok).value
            assertEquals(null, options.ringtoneUri)
            assertTrue(options.toVoicemail)
            assertEquals(ContactResult.Done, repository.setRingtone(5, ""))
            assertEquals(ContactResult.Done, repository.setVoicemail(5, true))
            assertEquals("", provider.updates[0].string(Contacts.CUSTOM_RINGTONE))
            assertEquals(1, provider.updates[1].values.getAsInteger(Contacts.SEND_TO_VOICEMAIL))
        }

    private fun phoneRow(
        id: Long,
        name: String,
        number: String,
        starred: Int,
        superPrimary: Int,
    ) = mapOf(
        Phone.CONTACT_ID to id,
        Phone.DISPLAY_NAME_PRIMARY to name,
        Phone.NUMBER to number,
        Phone.TYPE to Phone.TYPE_MOBILE,
        Phone.IS_SUPER_PRIMARY to superPrimary,
        Phone.STARRED to starred,
        Phone.PHOTO_THUMBNAIL_URI to "content://photos/$id",
        Phone.LOOKUP_KEY to "key$id",
    )

    private fun contactRow(
        id: Long,
        name: String,
        starred: Int,
    ) = mapOf(
        Contacts._ID to id,
        Contacts.DISPLAY_NAME_PRIMARY to name,
        Contacts.STARRED to starred,
        Contacts.LOOKUP_KEY to "key$id",
        Contacts.CONTACT_LAST_UPDATED_TIMESTAMP to 1000L + id,
    )
}
