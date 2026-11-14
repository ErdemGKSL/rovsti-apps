package dev.rovsti.contacts.settings

import dev.rovsti.contacts.R
import dev.rovsti.contacts.data.BirthdayAlarms
import dev.rovsti.contacts.data.Book
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.data.ReminderPort
import dev.rovsti.contacts.data.WaMarks
import dev.rovsti.contacts.en
import dev.rovsti.contacts.favorites.NOW
import dev.rovsti.contacts.intake.VcfExport
import dev.rovsti.contacts.list.ContactsListViewModel
import dev.rovsti.contacts.list.ListPhase
import dev.rovsti.contacts.me.PeopleRig
import dev.rovsti.contacts.people.PeopleSyncStatus
import dev.rovsti.contacts.trash.ContactTrash
import dev.rovsti.core.Rust
import dev.rovsti.core.model.contacts.BirthdayAlarm
import dev.rovsti.core.model.contacts.PinShortcut
import dev.rovsti.core.model.people.SyncWire
import dev.rovsti.core.model.phone.SortBy
import dev.rovsti.core.model.search.NameFormat
import dev.rovsti.core.ops.NativeContactsWaOps
import dev.rovsti.core.ops.NativeSearchOps
import dev.rovsti.design.text.UiText
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.contacts.ShortcutsRepository
import dev.rovsti.platform.files.FakeClipboardRepository
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.wa.FakeWaRepository
import dev.rovsti.wa.WaLinkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E4.15 Settings on the REAL Rust core over the 40 people of the Favorites tests: the choices are written to
 * `contacts-prefs.json` and the list re-sorts from the same flow, the summary and the hours are the core's words, the
 * reminder switch asks for the notification permission first, and the alarms follow the switch.
 */
class ContactsSettingsViewModelTest {
    private var rig: PeopleRig? = null
    private var permission: PermissionState = PermissionState.NeverAsked

    @After fun stop() {
        rig?.stop()
    }

    private fun rig(): PeopleRig = PeopleRig().also { rig = it }

    private fun PeopleRig.settings() =
        ContactsSettingsViewModel(book, store, repo, host.ops, notifications = { permission })

    private fun PeopleRig.ready(vm: ContactsSettingsViewModel) =
        wait { vm.state.first { it.hasContacts && it.hourLabel.isNotEmpty() && it.accounts.isNotEmpty() } }

    private fun PeopleRig.next(vm: ContactsSettingsViewModel) = wait { vm.effects.first() }

    private class FakeSync : dev.rovsti.contacts.people.PeopleSyncControl {
        val flow = MutableStateFlow<PeopleSyncStatus>(PeopleSyncStatus.NotLinked)
        val calls = mutableListOf<String>()
        override val status = flow

        override fun syncNow() {
            calls += "sync"
        }

        override fun resume() {
            calls += "resume"
        }

        override fun stop() {
            calls += "stop"
        }
    }

    @Test fun theSyncRowsFollowTheStatusAndTheButtonsAct() {
        val r = rig()
        val sync = FakeSync()
        var now = 1_000_000L
        val vm =
            ContactsSettingsViewModel(
                r.book,
                r.store,
                r.repo,
                r.host.ops,
                notifications = { permission },
                people = sync,
                formatTime = { "5 min. ago" },
                clock = { now },
                tickMs = 50,
            )
        r.ready(vm)
        assertEquals("not linked: no rows", null, vm.state.value.peopleSync)

        sync.flow.value = PeopleSyncStatus.Idle(null)
        assertEquals(
            UiText.Res(R.string.settings_people_never),
            r.wait { vm.state.first { it.peopleSync != null } }.peopleSync!!.last,
        )
        sync.flow.value = PeopleSyncStatus.Running(SyncWire.STAGE_LOOKUP, 15, 60)
        assertEquals(0.25f, r.wait { vm.state.first { it.peopleSync?.running == true } }.peopleSync!!.progress)

        // A limit's ETA: the ticker words it again once it has passed.
        sync.flow.value = PeopleSyncStatus.RateLimited(now + 60_000, SyncWire.STAGE_LOOKUP, 15, 60)
        val limited = r.wait { vm.state.first { it.peopleSync?.continueEnabled == false } }.peopleSync!!
        assertEquals(false, limited.syncEnabled)
        now += 61_000
        val ready = r.wait { vm.state.first { it.peopleSync?.continueEnabled == true } }.peopleSync!!
        assertEquals(UiText.Res(R.string.settings_people_limited_ready), ready.last)

        vm.onAction(SettingsAction.ContinuePeople)
        vm.onAction(SettingsAction.StopPeople)
        vm.onAction(SettingsAction.SyncPeopleNow)
        assertEquals(listOf("resume", "stop", "sync"), sync.calls)
        sync.flow.value = PeopleSyncStatus.NotLinked
        assertEquals(
            "unlinked again: the rows go",
            null,
            r.wait { vm.state.first { it.peopleSync == null } }.peopleSync,
        )
    }

    @Test fun theDefaultsAreFirstLastByFirstNameWithRemindersOff() {
        val r = rig()
        val vm = r.settings()
        val s = r.ready(vm)
        assertEquals(NameFormat.FirstLast, s.nameFormat)
        assertEquals(SortBy.FirstName, s.sortBy)
        assertFalse(s.reminders)
        assertEquals("one account: no Save To row", false, s.showSaveTo)
        assertTrue("the core words the total", s.summaryText.startsWith("40"))
        assertEquals(9, s.hour)
        assertTrue(s.hours.size > 3)
    }

    @Test fun aChangedNameFormatAndSortAreSavedAndReSortTheList() {
        val r = rig()
        // A provider that also knows "Last, First" and the last-name sort key, like ContactsContract does.
        val withAlt = AltNames(r.repo)
        val book =
            BookRepository(
                withAlt,
                MutableStateFlow(PermissionState.Granted),
                NativeSearchOps(Rust.core()),
                r.host.ops,
                r.scope,
            )
        val vm = ContactsSettingsViewModel(book, r.store, withAlt, r.host.ops, notifications = { permission })
        r.wait { vm.state.first { it.hasContacts } }
        val list =
            ContactsListViewModel(
                book = book,
                store = r.store,
                ops = r.host.ops,
                waMarks =
                    WaMarks(
                        FakeWaRepository(WaLinkState.None),
                        book.book,
                        NativeContactsWaOps(Rust.core()),
                        r.scope,
                    ),
                contacts = withAlt,
                clipboard = FakeClipboardRepository(),
                shortcuts =
                    object : ShortcutsRepository {
                        override fun canPin() = false

                        override fun pinContact(spec: PinShortcut) = false

                        override fun pinStop(spec: dev.rovsti.core.model.stops.ShortcutAnswer) = false
                    },
                trash = ContactTrash(withAlt, r.store),
                clock = { NOW },
            )
        val before =
            r
                .wait {
                    list.state.first { it.phase == ListPhase.Ready && it.rows.isNotEmpty() }
                }.rows
                .map { it.name }
        vm.onAction(SettingsAction.SetNameFormat(NameFormat.LastFirst))
        vm.onAction(SettingsAction.SetSortBy(SortBy.LastName))
        val s = r.wait { vm.state.first { it.nameFormat == NameFormat.LastFirst && it.sortBy == SortBy.LastName } }
        assertEquals(SortBy.LastName, s.sortBy)
        val after =
            r
                .wait {
                    // The list publishes the new order from the same preference flow.
                    kotlinx.coroutines.delay(1_500)
                    list.state.first { it.rows.isNotEmpty() }
                }.rows
                .map { it.name }
        assertNotEquals("before=${before.take(6)} after=${after.take(6)}", before, after)
        val fresh = r.reloaded()
        r.wait { fresh.load() }
        assertEquals(NameFormat.LastFirst, fresh.prefs.value.nameFormat)
        assertEquals(SortBy.LastName, fresh.prefs.value.sortBy)
    }

    @Test fun turningRemindersOnWithoutThePermissionAsksFirstAndChangesNothingYet() {
        val r = rig()
        val vm = r.settings()
        r.ready(vm)
        vm.onAction(SettingsAction.SetReminders(true))
        assertEquals(SettingsEffect.AskNotifications, r.next(vm))
        assertFalse(r.store.prefs.value.birthdayReminders)
        vm.onAction(SettingsAction.NotificationsAnswered(true))
        val toast = r.next(vm) as SettingsEffect.Toast
        assertTrue(toast.text.en(), toast.text.en().startsWith("Birthday reminders on, at "))
        assertTrue(r.wait { vm.state.first { it.reminders } }.reminders)
        assertTrue(r.store.prefs.value.birthdayReminders)
    }

    @Test fun aRefusedPermissionLeavesTheSwitchOffAndSaysSo() {
        val r = rig()
        val vm = r.settings()
        r.ready(vm)
        vm.onAction(SettingsAction.SetReminders(true))
        assertEquals(SettingsEffect.AskNotifications, r.next(vm))
        vm.onAction(SettingsAction.NotificationsAnswered(false))
        assertEquals(SettingsEffect.Toast(ContactsSettingsViewModel.DENIED), r.next(vm))
        assertFalse(r.store.prefs.value.birthdayReminders)
    }

    @Test fun aGrantedPermissionTurnsTheSwitchOnWithoutAsking() {
        permission = PermissionState.Granted
        val r = rig()
        val vm = r.settings()
        r.ready(vm)
        vm.onAction(SettingsAction.SetReminders(true))
        assertTrue((r.next(vm) as SettingsEffect.Toast).text.en().startsWith("Birthday reminders on"))
        assertTrue(r.wait { vm.state.first { it.reminders } }.reminders)
        vm.onAction(SettingsAction.SetReminders(false))
        assertEquals(SettingsEffect.Toast(UiText.Res(R.string.contacts_birthday_reminders_off)), r.next(vm))
    }

    @Test fun aPermissionOnlyAndroidSettingsCanChangeIsExplained() {
        permission = PermissionState.DeniedForGood
        val r = rig()
        val vm = r.settings()
        r.ready(vm)
        vm.onAction(SettingsAction.SetReminders(true))
        assertEquals(SettingsEffect.Toast(ContactsSettingsViewModel.DENIED), r.next(vm))
    }

    @Test fun theHourIsSavedAndLabelled() {
        val r = rig()
        val vm = r.settings()
        val first = r.ready(vm)
        val other = first.hours.first { it.hour != first.hour }
        vm.onAction(SettingsAction.SetHour(other.hour))
        val s = r.wait { vm.state.first { it.hour == other.hour } }
        assertEquals(other.label, s.hourLabel)
        assertEquals(other.hour, r.store.prefs.value.reminderHour)
    }

    @Test fun exportAllWritesEveryContactAsOneVcf() {
        val r = rig()
        r.waitBook()
        val file = r.wait { VcfExport(r.host.ops, r.book) { NOW }.build() }
        assertNotNull(file)
        assertTrue(file!!.fileName, file.fileName.endsWith(".vcf"))
        assertEquals(40, file.count)
        assertEquals(40, Regex("BEGIN:VCARD").findAll(file.text).count())
    }

    @Test fun alarmsAreArmedWhileTheSwitchIsOnAndCancelledWhenItGoesOff() {
        val r = rig()
        val port = RecordingPort()
        val alarms = BirthdayAlarms(r.book, r.store, r.host.ops, port, r.scope, clock = { NOW }, zoneOffsetS = { 0 })
        val ready = r.waitBook() as Book.Ready
        r.wait { r.store.update { it.copy(birthdayReminders = true) } }
        r.wait { alarms.sync(ready, 9) }
        assertTrue("birthdays within the horizon are armed", port.armed.isNotEmpty())
        val armed = port.armed.toList()
        r.wait { alarms.sync(ready, 9) }
        assertEquals("a second pass changes nothing", armed, port.armed)
        alarms.cancelAllForTest()
        assertTrue(port.armed.isEmpty())
    }

    private class AltNames(
        private val inner: ContactsRepository,
    ) : ContactsRepository by inner {
        override fun all() =
            inner.all().map { list ->
                list.map { c ->
                    val parts = c.displayName.split(' ')
                    val alt = parts.last() + ", " + parts.dropLast(1).joinToString(" ")
                    c.copy(displayNameAlt = alt, sortKeyAlt = alt.lowercase())
                }
            }
    }

    private class RecordingPort : ReminderPort {
        val armed = mutableListOf<Long>()

        override fun armed(): List<Long> = armed.toList()

        override fun arm(alarm: BirthdayAlarm) {
            if (alarm.id !in armed) armed += alarm.id
        }

        override fun disarm(id: Long) {
            armed -= id
        }

        override fun hour12() = false
    }
}
