# Op inventory (E2 R2)

Every public item of `rovsti_core`, `rovsti_geo`, `rovsti_tiles` and `rovsti_app_core` that the deprecated GPUI apps call
(`apps/*/src`, `crates/rovsti_ui/src`, `crates/rovsti_wa_ui/src`), and what becomes of it in the Kotlin apps. Generated once from
`use` paths and inline paths; methods fold into their type. `tests/inventory.rs` parses the table below and checks it
against the op registry: the ops named by `op` rows are exactly the registered ops (`core.panic` and `core.emit_test` are
test-only), and no row of a finished domain is `todo` (`DONE_DOMAINS` in that test grows with each step).

Classes:

- `op <name>[, <name>]`: exposed through `call` (or `tile` / audio); one `OpSpec` per name in `src/ops/<domain>.rs`.
- `kotlin <target>`: per-keystroke or per-frame behaviour that must answer synchronously on the main thread; ported to Kotlin in
  the app epic, the Rust unit tests mirrored as JVM tests. The target names the planned module and file.
- `none <reason>`: not needed by the Kotlin apps.
- `todo`: not yet decided or built; allowed only for domains that are not in `DONE_DOMAINS`.

Handles (E2.9): `search.index_new` and `phone.wa_tracker_new` answer `{"handle": <long>}`; every later op of that
object sends the handle back, and `RustNative.release` frees it (a released or foreign handle answers `not_found`). The
contact index owns its `T9Scratch`, so `t9.query` narrows the previous digits' candidates. Every other op is stateless: the
request carries the data (call-log entries, notes, speed-dial map) and the answer the new data.

Contacts (E2.10): `contacts.deep_index_new` answers `{"handle", "count"}`, the handle of the detail-search index (`contacts.deep_find`
takes it back; a released or foreign handle answers `not_found`). Every other `contacts.*` op is stateless: the request carries the
drafts, contacts or marks and the answer the new data. The trash (`contacts-trash.json`) and the preferences (`contacts-prefs.json`)
are files in the data directory: a request may name `dir`, else `core.init`'s data dir is used. Drafts travel as
`rovsti_core::contact_edit::ContactDraft` (empty optional lists left out). The editor's per-keystroke state, the select-mode set and
the sheet state of the screens are Kotlin (E4); the ops here run per event.

QR (E2.10, AC0): `qr.encode {text, ecc: "l"|"m"|"q"|"h" (default m)}` answers `{side, modules}`: `modules` is the standard base64
(padded) of a bitset of `side * side` bits, row-major, the first module of the first row in the most significant bit of the first
byte, a set bit a dark module, unused trailing bits zero; the quiet zone (4 modules) is not part of the matrix. `qr.decode {w, h, luma}`
takes `w * h` 8-bit luma bytes (row-major, base64, at most 1600 x 1600) and answers `{texts}`, every code it reads without repeats; a
size mismatch is `invalid`, bad base64 `parse`. The decoder is `rqrr` (through `contact_scan::decode_luma`); there is no ZXing or ML Kit.
Kotlin: `QrMatrix` in `:core-bridge` reads the bitset (`QrMatrix.get(x, y)`).

| Item | Used by | Domain | Class | Target |
|---|---|---|---|---|
| `(bridge) core.version` | - | bridge | op | core.version |
| `(bridge) core.echo` | - | bridge | op | core.echo |
| `(bridge) core.data_dir` | - | bridge | op | core.data_dir |
| `(bridge) core.fail` | - | bridge | op | core.fail |
| `(bridge) core.panic` | - | bridge | op | core.panic |
| `(bridge) core.emit_test` | - | bridge | op | core.emit_test |
| `(qr) qr.encode` | contacts | qr | op | qr.encode |
| `(qr) qr.decode` | contacts | qr | op | qr.decode |
| `rovsti_app_core::contacts::prefs` | contacts | contacts | op | contacts.prefs_load, contacts.prefs_save |
| `rovsti_app_core::contacts::trash_store` | contacts | contacts | op | contacts.trash_list, contacts.trash_push, contacts.trash_restore, contacts.trash_delete |
| `rovsti_app_core::contacts::whatsapp` | contacts | wa | todo | - |
| `rovsti_app_core::messages::access` | messages | messages | todo | - |
| `rovsti_app_core::messages::drafts` | messages | messages | todo | - |
| `rovsti_app_core::messages::headless` | messages | platform | none | served by the headless kinds (E2.6, fixtures headless.*) |
| `rovsti_app_core::messages::prefs` | messages | messages | todo | - |
| `rovsti_app_core::messages::tags` | messages | messages | todo | - |
| `rovsti_app_core::permission::Access` | messages | platform | todo | - |
| `rovsti_app_core::phone::notes` | phone | phone | op | phone.notes_load, phone.notes_save, call_notes.set, call_notes.remove, call_notes.restore |
| `rovsti_app_core::phone::wa_call` | phone | phone | op | phone.wa_tracker_new, phone.wa_tracker_update, phone.wa_tracker_placed, phone.wa_tracker_view, phone.wa_tracker_mute, phone.wa_tracker_clear, phone.wa_tracker_can_place, phone.wa_tracker_format_elapsed |
| `rovsti_app_core::stops::headless` | stops | platform | none | served by the headless kinds (E2.6, fixtures headless.*) |
| `rovsti_app_core::stops::pick` | stops | stops | todo | - |
| `rovsti_app_core::stops::snap` | stops | stops | todo | - |
| `rovsti_app_core::stops::store` | stops | stops | todo | - |
| `rovsti_app_core::stops::types` | stops | stops | todo | - |
| `rovsti_app_core::storage::BackupRefusal` | rovsti_ui | platform | todo | - |
| `rovsti_app_core::storage::Loaded` | rovsti_ui | platform | todo | - |
| `rovsti_app_core::storage::Origin` | rovsti_ui | platform | todo | - |
| `rovsti_app_core::storage::PREFS_FILE` | rovsti_ui | platform | todo | - |
| `rovsti_app_core::storage::Prefs` | rovsti_ui | platform | todo | - |
| `rovsti_app_core::storage::backup_json` | rovsti_ui | platform | todo | - |
| `rovsti_app_core::storage::copy_aside` | rovsti_ui | platform | todo | - |
| `rovsti_app_core::storage::load_json` | rovsti_ui | platform | todo | - |
| `rovsti_app_core::storage::load_prefs` | rovsti_ui | platform | todo | - |
| `rovsti_app_core::storage::parse_backup` | rovsti_ui | platform | todo | - |
| `rovsti_app_core::storage::save_json` | rovsti_ui | platform | todo | - |
| `rovsti_app_core::storage::save_prefs` | rovsti_ui | platform | todo | - |
| `rovsti_app_core::wa_link::avatars` | rovsti_wa_ui | wa | todo | - |
| `rovsti_app_core::wa_link::link` | rovsti_wa_ui | wa | todo | - |
| `rovsti_app_core::wa_link::names` | rovsti_wa_ui | wa | todo | - |
| `rovsti_app_core::wa_link::send` | rovsti_wa_ui | wa | todo | - |
| `rovsti_core::Channel` | messages | messages | todo | wire types of the messages ops |
| `rovsti_core::Message` | messages | messages | todo | wire types of the messages ops |
| `rovsti_core::MessageDirection` | messages | messages | todo | wire types of the messages ops |
| `rovsti_core::PhoneNumber` | messages,phone | shared | op | phone_number.parse, phone_number.same |
| `rovsti_core::ThreadId` | messages | messages | todo | wire types of the messages ops |
| `rovsti_core::audio::Decision` | phone | phone | op | audio.rows, audio.diff |
| `rovsti_core::audio::Diff` | phone | phone | op | audio.rows, audio.diff |
| `rovsti_core::audio::EndpointList` | phone | phone | op | audio.rows, audio.diff |
| `rovsti_core::audio::MAX_ROWS` | phone | phone | op | audio.rows, audio.diff |
| `rovsti_core::audio::Row` | phone | phone | op | audio.rows, audio.diff |
| `rovsti_core::audio::RowKind` | phone | phone | op | audio.rows, audio.diff |
| `rovsti_core::avatar::AvatarKey` | phone | contacts | op | contacts.avatar |
| `rovsti_core::avatar::avatar_hue` | phone | contacts | op | contacts.avatar |
| `rovsti_core::avatar::hue_index` | phone | contacts | op | contacts.avatar |
| `rovsti_core::avatar::initials` | messages | contacts | op | contacts.avatar |
| `rovsti_core::avatar::monogram` | phone | contacts | op | contacts.avatar |
| `rovsti_core::birthday_reminder::BirthdayAlarm` | contacts | contacts | op | contacts.birthday_reminders |
| `rovsti_core::birthday_reminder::DEFAULT_HOUR` | contacts | contacts | op | contacts.birthday_reminders |
| `rovsti_core::birthday_reminder::HOUR_CHOICES` | contacts | contacts | op | contacts.birthday_reminders |
| `rovsti_core::birthday_reminder::hour_label` | contacts | contacts | op | contacts.birthday_reminders |
| `rovsti_core::blocklist::BlockInputError` | phone | phone | op | blocklist.parse_input |
| `rovsti_core::blocklist::BlockRefusal` | messages,phone | phone | op | blocklist.check |
| `rovsti_core::blocklist::BlockSet` | messages,phone | phone | op | blocklist.check |
| `rovsti_core::blocklist::can_block` | messages,phone | phone | op | blocklist.check |
| `rovsti_core::bubble_gesture::SwipeReply` | messages | input | kotlin | :messages BubbleGesture.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::call::CallModel` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::call::CallState` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::call::after_call_text` | phone | phone | op | scene.texts |
| `rovsti_core::call::caller_label` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::call::format_elapsed` | phone | phone | op | scene.texts |
| `rovsti_core::call::format_number` | phone | shared | op | format.render |
| `rovsti_core::call::incoming_announcement` | phone | phone | op | scene.texts |
| `rovsti_core::call::is_missed` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::call::missed_headline` | phone | phone | op | scene.texts |
| `rovsti_core::call::status_text` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::call_log::CallType` | contacts,phone | phone | op | recents.group, recents.build |
| `rovsti_core::call_log::DateOrder` | phone | phone | op | recents.group, recents.build |
| `rovsti_core::call_log::EntryRef` | contacts,phone | phone | op | recents.group, recents.build |
| `rovsti_core::call_log::FREQUENT_LIMIT` | phone | phone | op | call_log.frequent, call_log.counts |
| `rovsti_core::call_log::FREQUENT_WINDOW_DAYS` | phone | phone | op | call_log.frequent, call_log.counts |
| `rovsti_core::call_log::RecentsRow` | phone | phone | op | recents.group, recents.build |
| `rovsti_core::call_log::TimeFormat` | phone | phone | op | recents.group, recents.build |
| `rovsti_core::call_log::badge_label` | phone | phone | op | recents.missed_extras |
| `rovsti_core::call_log::blocked_ids_for_numbers` | phone | phone | op | call_log.ids_for_numbers |
| `rovsti_core::call_log::deleted_text` | phone | phone | kotlin | :phone res/values/strings.xml (user-facing sentences live in Android resources, localised) |
| `rovsti_core::call_log::entries_by_id` | phone | phone | kotlin | :phone RecentsRepository.kt (a filter by id over the list Kotlin already holds) |
| `rovsti_core::call_log::frequent` | phone | phone | op | call_log.frequent, call_log.counts |
| `rovsti_core::call_log::ids_for_numbers` | phone | phone | op | call_log.ids_for_numbers |
| `rovsti_core::call_log::missed_badge` | phone | phone | op | recents.missed_extras |
| `rovsti_core::call_log::relative_time` | messages,phone | phone | op | recents.build, recents.history_text |
| `rovsti_core::call_log_query::PruneAge` | phone | phone | op | call_log.prune_plan |
| `rovsti_core::call_log_query::Query` | phone | phone | op | call_log.query |
| `rovsti_core::call_log_query::RecentsFilter` | phone | phone | op | call_log.query |
| `rovsti_core::call_log_query::csv_file_name` | phone | phone | op | call_log.csv |
| `rovsti_core::call_log_query::delete_selected_text` | phone | phone | kotlin | :phone res/values/strings.xml (user-facing sentences live in Android resources, localised) |
| `rovsti_core::call_log_query::ids_older_than` | phone | phone | op | call_log.prune_plan |
| `rovsti_core::call_log_query::person_export` | phone | phone | op | call_log.csv |
| `rovsti_core::call_log_query::prune_none_text` | phone | phone | op | call_log.prune_plan |
| `rovsti_core::call_log_query::prune_text` | phone | phone | op | call_log.prune_plan |
| `rovsti_core::call_log_query::to_csv_with_notes` | phone | phone | op | call_log.csv |
| `rovsti_core::call_notes::CallDetails` | phone | phone | op | call_notes.details |
| `rovsti_core::call_notes::CallNote` | phone | phone | op | call_notes.set, call_notes.remove, call_notes.restore, call_notes.rows, call_notes.for_calls |
| `rovsti_core::call_notes::Notes` | phone | phone | op | call_notes.set, call_notes.remove, call_notes.restore, call_notes.rows, call_notes.for_calls |
| `rovsti_core::call_notes::SetOutcome` | phone | phone | op | call_notes.set, call_notes.remove, call_notes.restore, call_notes.rows, call_notes.for_calls |
| `rovsti_core::call_notes::details` | phone | phone | op | call_notes.details |
| `rovsti_core::call_notes::notes_csv_file_name` | phone | phone | op | call_notes.export |
| `rovsti_core::call_stats::DayBar` | phone | phone | op | call_stats.overview, call_stats.person, call_stats.bucket |
| `rovsti_core::call_stats::Range` | phone | phone | op | call_stats.overview, call_stats.person, call_stats.bucket |
| `rovsti_core::call_stats::StatCall` | phone | phone | op | call_stats.overview, call_stats.person, call_stats.bucket |
| `rovsti_core::call_stats::StatDir` | phone | phone | op | call_stats.overview, call_stats.person, call_stats.bucket |
| `rovsti_core::call_stats::Totals` | phone | phone | op | call_stats.overview, call_stats.person, call_stats.bucket |
| `rovsti_core::calling_code::country_line` | phone | shared | op | calling_code.line |
| `rovsti_core::chat_export::ExportLine` | messages | messages | todo | - |
| `rovsti_core::chat_find::FindCursor` | messages | messages | todo | - |
| `rovsti_core::chat_gallery::DocEntry` | messages | messages | todo | - |
| `rovsti_core::chat_gallery::Gallery` | messages | messages | todo | - |
| `rovsti_core::chat_gallery::GalleryInput` | messages | messages | todo | - |
| `rovsti_core::chat_gallery::GalleryKind` | messages | messages | todo | - |
| `rovsti_core::chat_gallery::GalleryTab` | messages | messages | todo | - |
| `rovsti_core::chat_gallery::LinkEntry` | messages | messages | todo | - |
| `rovsti_core::clock::now_ms` | contacts,messages,phone,rovsti_wa_ui | platform | none | ops take now_ms; Kotlin owns the clock |
| `rovsti_core::clock::set_fixed_now_ms` | contacts,messages,phone,stops | platform | none | ops take now_ms; Kotlin owns the clock |
| `rovsti_core::contact_card::ContactCard` | contacts | contacts | op | contacts.card |
| `rovsti_core::contact_cleanup::Cleanup` | contacts | contacts | op | contacts.cleanup |
| `rovsti_core::contact_csv` | contacts | contacts | op | contacts.csv_export, contacts.csv_import |
| `rovsti_core::contact_dates::Upcoming` | contacts | contacts | op | contacts.dates, contacts.event_line, contacts.maps_url |
| `rovsti_core::contact_dates::local_ymd` | contacts,stops | contacts | op | contacts.local_ymd |
| `rovsti_core::contact_dates::parse` | contacts | contacts | op | contacts.date_parse, contacts.age |
| `rovsti_core::contact_dupes::DupGroup` | contacts | contacts | op | contacts.dupes, contacts.merge_plan |
| `rovsti_core::contact_dupes::find` | contacts | contacts | op | contacts.dupes |
| `rovsti_core::contact_edit::Account` | contacts | contacts | op | contacts.trash_restore, contacts.account_label |
| `rovsti_core::contact_edit::AccountsPayload` | contacts | contacts | none | payload of the GPUI provider bridge; Kotlin reads ContactsContract into model.contacts.ContactDraft directly |
| `rovsti_core::contact_edit::Addition` | contacts | contacts | op | contacts.draft_from_query, contacts.draft_add |
| `rovsti_core::contact_edit::ContactDraft` | contacts | contacts | op | contacts.draft_validate, contacts.draft_diff, contacts.draft_edit |
| `rovsti_core::contact_edit::DetailPayload` | contacts | contacts | none | payload of the GPUI provider bridge; Kotlin reads ContactsContract into model.contacts.ContactDraft directly |
| `rovsti_core::contact_edit::DraftEvent` | contacts | contacts | op | contacts.draft_validate |
| `rovsti_core::contact_edit::DraftField` | contacts | contacts | op | contacts.draft_validate |
| `rovsti_core::contact_edit::DraftRelation` | contacts | contacts | op | contacts.draft_validate |
| `rovsti_core::contact_edit::EventKind` | contacts | contacts | op | contacts.draft_validate |
| `rovsti_core::contact_edit::FieldKind` | contacts | contacts | op | contacts.draft_validate |
| `rovsti_core::contact_edit::FullPayload` | contacts | contacts | none | payload of the GPUI provider bridge; Kotlin reads ContactsContract into model.contacts.ContactDraft directly |
| `rovsti_core::contact_edit::Group` | contacts | contacts | op | contacts.groups_title, contacts.groups_targets |
| `rovsti_core::contact_edit::GroupsPayload` | contacts | contacts | none | payload of the GPUI provider bridge; Kotlin reads groups into model.contacts.Group directly |
| `rovsti_core::contact_edit::ImportPayload` | contacts | contacts | none | payload of the GPUI provider bridge; Kotlin reads ContactsContract into model.contacts.ContactDraft directly |
| `rovsti_core::contact_edit::MAX_FIELDS` | contacts | contacts | op | contacts.draft_edit |
| `rovsti_core::contact_edit::MembersPayload` | contacts | contacts | op | contacts.groups_summary |
| `rovsti_core::contact_edit::RelationKind` | contacts | contacts | op | contacts.draft_validate |
| `rovsti_core::contact_edit::RelationTap` | contacts | contacts | op | contacts.relation_tap |
| `rovsti_core::contact_edit::SavedPayload` | contacts | contacts | none | payload of the GPUI provider bridge; Kotlin reads ContactsContract into model.contacts.ContactDraft directly |
| `rovsti_core::contact_edit::SelectionChange` | contacts | contacts | op | contacts.groups_change |
| `rovsti_core::contact_edit::account_label` | contacts | contacts | op | contacts.account_label |
| `rovsti_core::contact_edit::default_action` | contacts | contacts | op | contacts.field_menu |
| `rovsti_core::contact_edit::draft_from_query` | contacts | contacts | op | contacts.draft_from_query |
| `rovsti_core::contact_edit::group_join_hint` | contacts | contacts | op | contacts.groups_labels |
| `rovsti_core::contact_edit::group_subtitle` | contacts | contacts | op | contacts.groups_labels |
| `rovsti_core::contact_edit::group_title` | contacts | contacts | op | contacts.groups_title |
| `rovsti_core::contact_edit::primary_of` | contacts | contacts | op | contacts.draft_edit |
| `rovsti_core::contact_edit::relation_tap` | contacts | contacts | op | contacts.relation_tap |
| `rovsti_core::contact_edit::retain_account_groups` | contacts | contacts | op | contacts.draft_edit |
| `rovsti_core::contact_edit::set_primary` | contacts | contacts | op | contacts.draft_edit |
| `rovsti_core::contact_edit::web_url` | contacts | contacts | op | contacts.web_url |
| `rovsti_core::contact_find::DeepField` | contacts | contacts | op | contacts.deep_find |
| `rovsti_core::contact_find::DeepHit` | contacts | contacts | op | contacts.deep_find |
| `rovsti_core::contact_find::DeepIndex` | contacts | contacts | op | contacts.deep_index_new, contacts.deep_find |
| `rovsti_core::contact_frequent::Frequent` | contacts | contacts | op | contacts.frequent |
| `rovsti_core::contact_history::CARD_LIMIT` | contacts | contacts | op | contacts.history |
| `rovsti_core::contact_history::PULL_LIMIT` | contacts | contacts | op | contacts.history |
| `rovsti_core::contact_ics` | contacts | contacts | op | contacts.ics |
| `rovsti_core::contact_intake::Intake` | contacts | contacts | op | contacts.intake |
| `rovsti_core::contact_me` | contacts | contacts | op | contacts.me_state, contacts.me_toggle |
| `rovsti_core::contact_options::ContactOptions` | contacts | contacts | op | contacts.options |
| `rovsti_core::contact_options::PinShortcut` | contacts | contacts | op | contacts.pin_shortcut |
| `rovsti_core::contact_photo::CropView` | contacts | contacts | op | contacts.photo_crop |
| `rovsti_core::contact_photo::PhotoError` | contacts,phone | contacts | op | contacts.photo_plan |
| `rovsti_core::contact_photo::accepts` | phone | contacts | op | contacts.photo_accepts |
| `rovsti_core::contact_photo::plan_for` | contacts | contacts | op | contacts.photo_plan |
| `rovsti_core::contact_photo::rotated_size` | contacts | contacts | op | contacts.photo_plan |
| `rovsti_core::contact_quick::QuickAction` | contacts | contacts | op | contacts.quick |
| `rovsti_core::contact_recent::AddedMark` | contacts | contacts | op | contacts.recent_note_pull, contacts.recent_note_created |
| `rovsti_core::contact_recent::CLEAR_VIEWED` | contacts | contacts | op | contacts.recent_lists |
| `rovsti_core::contact_recent::RecentTab` | contacts | contacts | op | contacts.recent_lists |
| `rovsti_core::contact_recent::VIEWED_KEEP` | contacts | contacts | op | contacts.recent_viewed |
| `rovsti_core::contact_recent::note_created` | contacts | contacts | op | contacts.recent_note_created |
| `rovsti_core::contact_recent::note_pull` | contacts | contacts | op | contacts.recent_note_pull |
| `rovsti_core::contact_recent::show_entry` | contacts | contacts | op | contacts.recent_lists |
| `rovsti_core::contact_scan::Scanned` | contacts | contacts | op | contacts.scan |
| `rovsti_core::contact_select::GroupTarget` | contacts | contacts | op | contacts.groups_targets |
| `rovsti_core::contact_select::SHARE_FORMATS` | contacts | contacts | op | contacts.select_bar |
| `rovsti_core::contact_select::SelectAction` | contacts | contacts | op | contacts.select_bar |
| `rovsti_core::contact_select::ShareFormat` | contacts | contacts | op | contacts.select_export |
| `rovsti_core::contact_select::cap_notice` | contacts | contacts | op | contacts.select_bar |
| `rovsti_core::contact_select::delete_confirm` | contacts | contacts | op | contacts.select_bar |
| `rovsti_core::contact_select::export_ids` | contacts | contacts | op | contacts.select_export |
| `rovsti_core::contact_stats::Summary` | contacts | contacts | op | contacts.stats |
| `rovsti_core::contact_trash::Trash` | contacts | contacts | op | contacts.trash_list, contacts.trash_push, contacts.trash_restore, contacts.trash_delete, contacts.trash_confirm |
| `rovsti_core::contact_trash::moved_notice` | contacts | contacts | op | contacts.trash_push |
| `rovsti_core::contacts_prefs::ContactsPrefs` | contacts | contacts | op | contacts.prefs_load, contacts.prefs_save |
| `rovsti_core::contacts_prefs::MAX_EMERGENCY` | contacts | contacts | op | contacts.me_state |
| `rovsti_core::contacts_view::CallTarget` | contacts,phone | contacts | op | contacts.card |
| `rovsti_core::contacts_view::ContactVm` | contacts,phone | contacts | op | contacts.view |
| `rovsti_core::contacts_view::NameStyle` | contacts,phone | contacts | op | contacts.view |
| `rovsti_core::contacts_view::NumberVm` | contacts,phone | contacts | op | contacts.view, contacts.card |
| `rovsti_core::contacts_view::build_rows_styled` | contacts | contacts | op | contacts.view |
| `rovsti_core::contacts_view::call_target` | phone | contacts | op | contacts.card |
| `rovsti_core::contacts_view::without` | contacts | contacts | op | contacts.view, contacts.favorites_move, contacts.scrubber |
| `rovsti_core::copy_choices::CopyChoice` | messages | shared | op | copy.header_choices |
| `rovsti_core::copy_choices::header_choices` | messages | shared | op | copy.header_choices |
| `rovsti_core::date_detect::calendar_end_ms` | messages | messages | todo | - |
| `rovsti_core::date_detect::detect` | messages | messages | todo | - |
| `rovsti_core::date_detect::event_title` | messages | messages | todo | - |
| `rovsti_core::date_detect::matched_text` | messages | messages | todo | - |
| `rovsti_core::date_jump::DateStep` | messages | messages | todo | - |
| `rovsti_core::date_jump::DayPick` | messages | messages | todo | - |
| `rovsti_core::date_jump::date_notice` | messages | messages | todo | - |
| `rovsti_core::date_jump::date_step` | messages | messages | todo | - |
| `rovsti_core::date_jump::later_notice` | messages | messages | todo | - |
| `rovsti_core::date_jump::quick_picks` | messages | messages | todo | - |
| `rovsti_core::date_jump::starts_later` | messages | messages | todo | - |
| `rovsti_core::dial::FieldAction` | phone | input | op | dial.field_menu |
| `rovsti_core::dial::Push` | phone | input | kotlin | :phone Dial.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::dial::TonePlan` | phone | input | op | dial.tone_plan, dial.tones_text, dial.tone_preview, dial.paste_tones_label |
| `rovsti_core::dial::ToneStep` | phone | input | op | dial.tone_plan, dial.tones_text, dial.tone_preview, dial.paste_tones_label |
| `rovsti_core::dial::from_tel_uri` | phone | input | op | dial.from_tel_uri |
| `rovsti_core::forward::ForwardBody` | messages | messages | todo | - |
| `rovsti_core::forward::ForwardPlan` | messages | messages | todo | - |
| `rovsti_core::forward::ForwardTo` | messages | messages | todo | - |
| `rovsti_core::forward::notice` | messages | messages | todo | - |
| `rovsti_core::forward::plan` | messages | messages | todo | - |
| `rovsti_core::forward::target_label` | messages | messages | todo | - |
| `rovsti_core::gesture::Dir` | phone | input | kotlin | :design Gesture.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::gesture::Release` | phone | input | kotlin | :design Gesture.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::gesture::Tick` | phone | input | kotlin | :design Gesture.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::global_search::JumpStep` | messages | messages | todo | - |
| `rovsti_core::global_search::JumpTarget` | messages | messages | todo | - |
| `rovsti_core::global_search::MIN_QUERY_CHARS` | messages | messages | todo | - |
| `rovsti_core::global_search::SearchCandidate` | messages | messages | todo | - |
| `rovsti_core::global_search::SearchHit` | messages | messages | todo | - |
| `rovsti_core::global_search::Source` | messages | messages | todo | - |
| `rovsti_core::global_search::WA_CHATS` | messages | messages | todo | - |
| `rovsti_core::global_search::jump_notice` | messages | messages | todo | - |
| `rovsti_core::global_search::jump_step` | messages | messages | todo | - |
| `rovsti_core::group_tools::Kind` | contacts | contacts | op | contacts.groups_recipients |
| `rovsti_core::import_plan::number_key` | contacts | contacts | op | contacts.number_key |
| `rovsti_core::import_plan::photo_targets` | contacts | contacts | op | contacts.import_photos |
| `rovsti_core::inbox::InboxRow` | messages | messages | todo | - |
| `rovsti_core::inbox::InboxThread` | messages | messages | todo | - |
| `rovsti_core::inbox::PersonKey` | messages | messages | todo | - |
| `rovsti_core::inbox::merge` | messages | messages | todo | - |
| `rovsti_core::inbox_bulk::BulkAction` | messages | messages | todo | - |
| `rovsti_core::inbox_bulk::BulkFacts` | messages | messages | todo | - |
| `rovsti_core::inbox_filter::InboxFilter` | messages | messages | todo | - |
| `rovsti_core::inbox_filter::RowFacts` | messages | messages | todo | - |
| `rovsti_core::keypad_layout::Insets` | phone | input | kotlin | :phone KeypadLayout.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::keypad_layout::KeypadInput` | phone | input | kotlin | :phone KeypadLayout.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::keypad_layout::KeypadLayout` | phone | input | kotlin | :phone KeypadLayout.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::keypad_layout::Rect` | phone | input | kotlin | :phone KeypadLayout.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::keypad_layout::key_style` | phone | input | kotlin | :phone KeypadLayout.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::keypad_layout::keypad_layout` | phone | input | kotlin | :phone KeypadLayout.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::location_link::coords_text` | messages | messages | todo | - |
| `rovsti_core::location_link::find_places` | messages | messages | todo | - |
| `rovsti_core::location_link::geo_url` | messages | messages | todo | - |
| `rovsti_core::lru::Lru` | contacts,messages,phone | platform | todo | - |
| `rovsti_core::message_info::InfoChannel` | messages | messages | todo | - |
| `rovsti_core::message_info::InfoInput` | messages | messages | todo | - |
| `rovsti_core::message_info::InfoRow` | messages | messages | todo | - |
| `rovsti_core::message_info::InfoState` | messages | messages | todo | - |
| `rovsti_core::message_info::info_text` | messages | messages | todo | - |
| `rovsti_core::message_info::rows` | messages | messages | todo | - |
| `rovsti_core::message_reminder::MessageReminder` | messages | messages | todo | - |
| `rovsti_core::message_reminder::arm_json` | messages | messages | todo | - |
| `rovsti_core::message_reminder::fired_id` | messages | messages | todo | - |
| `rovsti_core::message_reminder::parse_list` | messages | messages | todo | - |
| `rovsti_core::msg_backup::BackupBody` | messages | messages | todo | - |
| `rovsti_core::msg_backup::BackupError` | messages | messages | todo | - |
| `rovsti_core::msg_backup::RestorePlan` | messages | messages | todo | - |
| `rovsti_core::msg_backup::Strength` | messages | messages | todo | - |
| `rovsti_core::msg_backup::passphrase_strength` | messages | messages | todo | - |
| `rovsti_core::multi_select::Selection` | contacts,messages | messages | todo | - |
| `rovsti_core::mute::MuteFor` | messages | messages | todo | - |
| `rovsti_core::mute::header_line` | messages | messages | todo | - |
| `rovsti_core::mute::muted_notice` | messages | messages | todo | - |
| `rovsti_core::names::display` | phone | platform | todo | - |
| `rovsti_core::notify::Incoming` | messages | messages | todo | - |
| `rovsti_core::notify::MAX_LINES` | messages | messages | todo | - |
| `rovsti_core::notify::NotificationSpec` | messages | messages | todo | - |
| `rovsti_core::notify::build` | messages | messages | todo | - |
| `rovsti_core::number_format::NumberForm` | phone | shared | op | format.render |
| `rovsti_core::number_format::QR_MAX_BYTES` | contacts | shared | op | format.contact_qr |
| `rovsti_core::number_format::QrTooBig` | contacts | shared | op | format.contact_qr |
| `rovsti_core::number_format::contact_qr` | contacts,phone | shared | op | format.contact_qr |
| `rovsti_core::number_format::file_slug` | messages | shared | op | format.file_name |
| `rovsti_core::number_format::file_slug_opt` | contacts,messages,phone,stops | shared | op | format.file_name |
| `rovsti_core::number_format::infer_home_cc` | contacts,phone | shared | op | format.infer_home_cc |
| `rovsti_core::number_format::qr_file_name` | phone | shared | op | format.file_name |
| `rovsti_core::otp::detect` | messages | messages | todo | - |
| `rovsti_core::pending_delete::PendingDeletes` | messages | messages | todo | - |
| `rovsti_core::pending_delete::notice` | messages | messages | todo | - |
| `rovsti_core::perf::FrameStats` | phone,rovsti_ui | input | kotlin | :design FramePerf.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::perf::FrameSummary` | rovsti_ui | input | kotlin | :design FramePerf.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::quick_replies::ReplyError` | messages | messages | todo | - |
| `rovsti_core::quick_replies::insert_into` | messages | messages | todo | - |
| `rovsti_core::quick_replies::restore` | messages | messages | todo | - |
| `rovsti_core::recents::Direction` | phone | phone | op | recents.build |
| `rovsti_core::recents::ExtraCall` | phone | phone | op | recents.merge_extras |
| `rovsti_core::recents::HistoryLine` | contacts,phone | phone | op | recents.history, recents.blocked_history, recents.history_text |
| `rovsti_core::recents::LocaleTime` | contacts,messages,phone,stops | phone | op | recents.build |
| `rovsti_core::recents::RowView` | phone | phone | op | recents.build |
| `rovsti_core::recents::extra_ids_for_numbers` | phone | phone | op | call_log.ids_for_numbers |
| `rovsti_core::recents::split_refs` | phone | phone | kotlin | :phone RecentsRepository.kt (a when over the two ref kinds) |
| `rovsti_core::reminder::Reminder` | phone | messages | todo | - |
| `rovsti_core::reminder::Slot` | messages,phone | messages | todo | - |
| `rovsti_core::reminder::arm_json` | phone | messages | todo | - |
| `rovsti_core::reminder::clock` | messages | messages | todo | - |
| `rovsti_core::reminder::parse_list` | phone | messages | todo | - |
| `rovsti_core::scene::CallScene` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::scene::Control` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::scene::DisabledReason` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::scene::Layout` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::scene::Participants` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::scene::SceneDiff` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::schedule::FiredSms` | messages | messages | todo | - |
| `rovsti_core::schedule::MIN_LEAD_MS` | messages | messages | todo | - |
| `rovsti_core::schedule::ScheduleError` | messages | messages | todo | - |
| `rovsti_core::schedule::ScheduleQueue` | messages | messages | todo | - |
| `rovsti_core::schedule::ScheduleTarget` | messages | messages | todo | - |
| `rovsti_core::schedule::ScheduledSms` | messages | messages | todo | - |
| `rovsti_core::search::ContactIndex` | contacts,messages,phone | search | op | search.index_new, search.index_replace, search.set_call_counts, search.query, search.query_loose, search.match_number |
| `rovsti_core::search::fold` | contacts,phone,stops | search | op | search.fold |
| `rovsti_core::settings::BACKUP_KIND` | phone | platform | todo | - |
| `rovsti_core::settings::DEFAULT_REPLIES` | phone | phone | op | phone.reply_choices |
| `rovsti_core::settings::HapticKind` | rovsti_ui | phone | kotlin | :design Haptics.kt (per-tap check, main thread; Rust table mirrored as a JVM test) |
| `rovsti_core::settings::ImportPreview` | phone | platform | todo | - |
| `rovsti_core::settings::MAX_REPLIES` | phone | phone | op | phone.reply_choices |
| `rovsti_core::settings::MAX_REPLY_CHARS` | phone | phone | op | phone.reply_choices |
| `rovsti_core::settings::PrefsV2` | phone | phone | op | phone.prefs_load, phone.prefs_save |
| `rovsti_core::settings::REPLY_COUNTER_AT` | phone | phone | op | phone.reply_choices |
| `rovsti_core::settings::SPEED_DIAL_DIGITS` | phone | phone | op | speed_dial.assign, speed_dial.free_digits |
| `rovsti_core::settings::Settings` | phone | phone | op | phone.prefs_load, phone.prefs_save |
| `rovsti_core::settings::SortBy` | contacts,phone | phone | op | phone.prefs_load, phone.prefs_save |
| `rovsti_core::settings::SpeedDialEntry` | phone | phone | op | phone.prefs_load, phone.prefs_save |
| `rovsti_core::settings::backup_file_name` | phone | platform | todo | - |
| `rovsti_core::settings::effective_keypad_tones` | phone | phone | op | phone.keypad_tones |
| `rovsti_core::settings::haptic_allowed` | rovsti_ui | phone | kotlin | :design Haptics.kt (per-tap check, main thread; Rust table mirrored as a JVM test) |
| `rovsti_core::settings::reply_choices` | phone | phone | op | phone.reply_choices |
| `rovsti_core::setup::ItemState` | phone | phone | op | setup.steps, setup.checklist |
| `rovsti_core::setup::ItemStatus` | phone | phone | op | setup.steps, setup.checklist |
| `rovsti_core::setup::OpenedThisSession` | phone | phone | op | setup.xiaomi |
| `rovsti_core::setup::Reading` | phone | phone | op | setup.steps, setup.checklist |
| `rovsti_core::setup::SetupChecklist` | phone | phone | op | setup.steps, setup.checklist |
| `rovsti_core::setup::SetupFacts` | phone | phone | op | setup.steps, setup.checklist |
| `rovsti_core::setup::SetupItem` | phone | phone | op | setup.steps, setup.checklist |
| `rovsti_core::setup::XiaomiConfirmations` | phone | phone | op | setup.xiaomi |
| `rovsti_core::setup::can_confirm` | phone | phone | op | setup.xiaomi |
| `rovsti_core::setup::changed_title` | phone | phone | op | setup.reopened |
| `rovsti_core::setup::still_reopened` | phone | phone | op | setup.reopened |
| `rovsti_core::shared_card::CardField` | messages | messages | todo | - |
| `rovsti_core::shared_card::CardView` | messages | messages | todo | - |
| `rovsti_core::shared_card::insert_json` | messages | messages | todo | - |
| `rovsti_core::shared_card::primary_number` | messages | messages | todo | - |
| `rovsti_core::sim::DOT_MIN_CONTRAST` | phone | phone | op | sim.dot |
| `rovsti_core::sim::SimAccount` | phone | phone | op | sim.build |
| `rovsti_core::sim::SimInfo` | phone | phone | op | sim.build |
| `rovsti_core::sim::argb_rgb` | phone | phone | op | sim.dot |
| `rovsti_core::sim::clamp_dot` | phone | phone | op | sim.dot |
| `rovsti_core::small_text::SmallText` | phone | input | kotlin | :design SmallText.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::sms::DeliveryState` | messages | messages | todo | - |
| `rovsti_core::sms::MmsPart` | messages | messages | todo | - |
| `rovsti_core::sms::MmsRow` | messages | messages | todo | - |
| `rovsti_core::sms::SmsBox` | messages | messages | todo | - |
| `rovsti_core::sms::SmsMessagesPayload` | messages | messages | todo | - |
| `rovsti_core::sms::SmsRow` | messages | messages | todo | - |
| `rovsti_core::sms::SmsSend` | messages | messages | todo | - |
| `rovsti_core::sms::SmsSim` | messages | messages | todo | - |
| `rovsti_core::sms::SmsSimsPayload` | messages | messages | todo | - |
| `rovsti_core::sms::SmsStatus` | messages | messages | todo | - |
| `rovsti_core::sms::SmsThreadRow` | messages | messages | todo | - |
| `rovsti_core::sms::SmsThreadsPayload` | messages | messages | todo | - |
| `rovsti_core::sms::segments` | messages | messages | todo | - |
| `rovsti_core::sms_timeline::SmsItem` | messages | messages | todo | - |
| `rovsti_core::sms_timeline::SmsKey` | messages | messages | todo | - |
| `rovsti_core::sms_timeline::SmsTimeline` | messages | messages | todo | - |
| `rovsti_core::spam::RuleError` | messages | messages | todo | - |
| `rovsti_core::spam::SpamRule` | messages | messages | todo | - |
| `rovsti_core::spam::SpamRules` | messages | messages | todo | - |
| `rovsti_core::spam::contains_link` | messages | messages | todo | - |
| `rovsti_core::spam::open_links` | messages | messages | todo | - |
| `rovsti_core::speed_dial::HoldDigit` | phone | phone | op | speed_dial.on_hold, speed_dial.assign, speed_dial.unassign, speed_dial.free_digits, speed_dial.digit_for |
| `rovsti_core::speed_dial::badges` | phone | phone | op | speed_dial.badges |
| `rovsti_core::starred::LocalStar` | messages | messages | todo | - |
| `rovsti_core::starred::StarCandidate` | messages | messages | todo | - |
| `rovsti_core::starred::StarredRow` | messages | messages | todo | - |
| `rovsti_core::t9::MAX_HITS` | phone | t9 | op | t9.query |
| `rovsti_core::t9::T9Hit` | phone | t9 | op | t9.query |
| `rovsti_core::t9::T9Scratch` | phone | t9 | op | t9.query |
| `rovsti_core::text_detect::DetectAction` | messages | messages | todo | - |
| `rovsti_core::text_edit::EditBuffer` | rovsti_ui | input | kotlin | :design TextEdit.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_edit::History` | rovsti_ui | input | kotlin | :design TextEdit.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_edit::Kind` | rovsti_ui | input | kotlin | :design TextEdit.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_scale::ChatTextSize` | messages | input | kotlin | :design TextScale.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::ECHO_MS` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::EDGE_MAX` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::EDGE_ZONE` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::HANDLE_HIT` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::HANDLE_SIZE` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::INSERTION_MS` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::MENU_H` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::MENU_ITEM_PAD` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::MenuItem` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::MenuState` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::TapAction` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::drag_selection` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::edge_scroll` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::handle_visible` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::hits_caret` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::in_selection` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::keep_over_field` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::menu_items` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::menu_width` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::place_menu` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::resolve_tap` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::tap_byte` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::teardrop` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::CARET_MARGIN` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::ML_LINE` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::ML_PAD_V` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::box_height` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::capsule_radius` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::caret_visible` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::clamp_scroll` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::clamp_scroll_y` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::locate_paragraph` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::masked_index` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::next_toggle_ms` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::paragraph_for_row` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::paragraph_starts` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::scroll_for_caret` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::scroll_y_for_caret` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::selection_rects` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::unmasked_index` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::visible_lines` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::thread_marks::SmsMarks` | messages | messages | todo | - |
| `rovsti_core::thread_marks::TOO_MANY_PINS` | messages | messages | todo | - |
| `rovsti_core::thread_marks::archive_notice` | messages | messages | todo | - |
| `rovsti_core::thread_marks::pin_notice` | messages | messages | todo | - |
| `rovsti_core::thread_marks::unread_notice` | messages | messages | todo | - |
| `rovsti_core::unread_jump::JumpState` | messages | messages | todo | - |
| `rovsti_core::unread_jump::next_unread_label` | messages | messages | todo | - |
| `rovsti_core::vcard::CardPhoto` | contacts | contacts | op | contacts.vcard_parse, contacts.vcard_write, contacts.import_plan |
| `rovsti_core::vcard::PhotoFormat` | contacts | contacts | op | contacts.vcard_parse, contacts.vcard_write |
| `rovsti_core::voice_player::PlayerState` | messages | messages | todo | - |
| `rovsti_core::voice_player::Speed` | messages | messages | todo | - |
| `rovsti_core::voice_player::TogglePlan` | messages | messages | todo | - |
| `rovsti_core::wire::AudioRoute` | phone | wa | todo | - |
| `rovsti_core::wire::AudioState` | phone | wa | todo | - |
| `rovsti_core::wire::BlockReason` | phone | wa | todo | - |
| `rovsti_core::wire::BlockedNumber` | messages,phone | wa | todo | - |
| `rovsti_core::wire::BlocklistPayload` | messages,phone | wa | todo | - |
| `rovsti_core::wire::CallLogEntry` | contacts,phone | wa | todo | - |
| `rovsti_core::wire::CallLogKind` | contacts,phone | wa | todo | - |
| `rovsti_core::wire::CallLogPayload` | contacts,phone | wa | todo | - |
| `rovsti_core::wire::CallSnapshot` | phone | wa | todo | - |
| `rovsti_core::wire::ContactsPayload` | contacts,messages,phone | wa | todo | - |
| `rovsti_core::wire::DisconnectCause` | phone | wa | todo | - |
| `rovsti_core::wire::DocumentOp` | contacts,messages,phone,stops | wa | todo | - |
| `rovsti_core::wire::DocumentResult` | contacts,messages,phone,stops | wa | todo | - |
| `rovsti_core::wire::EmergencyList` | messages,phone | wa | todo | - |
| `rovsti_core::wire::Endpoint` | phone | wa | todo | - |
| `rovsti_core::wire::EndpointHint` | phone | wa | todo | - |
| `rovsti_core::wire::EndpointType` | phone | wa | todo | - |
| `rovsti_core::wire::Envelope` | contacts,messages,phone | wa | todo | - |
| `rovsti_core::wire::FavoritesPayload` | phone | wa | todo | - |
| `rovsti_core::wire::Handoff` | messages,phone | wa | todo | - |
| `rovsti_core::wire::HandoffAction` | messages | wa | todo | - |
| `rovsti_core::wire::HapticsLevel` | phone,rovsti_ui | wa | todo | - |
| `rovsti_core::wire::NameFormat` | contacts,phone | wa | todo | - |
| `rovsti_core::wire::OpenSystemResponse` | phone | wa | todo | - |
| `rovsti_core::wire::PendingOp` | phone | wa | todo | - |
| `rovsti_core::wire::PhoneAccountInfo` | phone | wa | todo | - |
| `rovsti_core::wire::PhoneAccounts` | phone | wa | todo | - |
| `rovsti_core::wire::PhoneAccountsResponse` | phone | wa | todo | - |
| `rovsti_core::wire::SettingsSync` | phone | wa | todo | - |
| `rovsti_core::wire::SilencedBy` | phone | wa | todo | - |
| `rovsti_core::wire::UiRequest` | phone | wa | todo | - |
| `rovsti_core::wire::UiRequestKind` | phone | wa | todo | - |
| `rovsti_core::wire::WireCall` | phone | wa | todo | - |
| `rovsti_core::wire::WireCallState` | phone | wa | todo | - |
| `rovsti_core::wire::WireContact` | contacts,messages,phone | wa | todo | - |
| `rovsti_core::wire::WireError` | contacts,messages,phone | wa | todo | - |
| `rovsti_core::wire::WireNumber` | contacts,messages,phone | wa | todo | - |
| `rovsti_core::wire::WriteError` | phone | wa | todo | - |
| `rovsti_core::wire::caps` | phone | wa | todo | - |
| `rovsti_core::wire::parse_handoff` | messages,phone | wa | todo | - |
| `rovsti_core::wire::props` | phone | wa | todo | - |
| `rovsti_core::zoom::Point` | contacts,messages,phone | input | kotlin | :design Zoom.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::zoom::Size` | contacts,messages,phone | input | kotlin | :design Zoom.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::zoom::ZoomView` | contacts,messages,phone | input | kotlin | :design Zoom.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_geo::ActiveTrip` | stops | stops | todo | - |
| `rovsti_geo::AlarmPlan` | stops | stops | todo | - |
| `rovsti_geo::Appearance` | stops | stops | todo | - |
| `rovsti_geo::Coord` | stops | stops | todo | - |
| `rovsti_geo::DAY_MS` | stops | stops | todo | - |
| `rovsti_geo::Diameter` | stops | stops | todo | - |
| `rovsti_geo::DiameterError` | stops | stops | todo | - |
| `rovsti_geo::EARTH_RADIUS_M` | stops | stops | todo | - |
| `rovsti_geo::Endpoint` | stops | stops | todo | - |
| `rovsti_geo::Feed` | stops | stops | todo | - |
| `rovsti_geo::FeedReport` | stops | stops | todo | - |
| `rovsti_geo::Fix` | stops | stops | todo | - |
| `rovsti_geo::FixIn` | stops | stops | todo | - |
| `rovsti_geo::FixOut` | stops | stops | todo | - |
| `rovsti_geo::FixPos` | stops | stops | todo | - |
| `rovsti_geo::History` | stops | stops | todo | - |
| `rovsti_geo::Measure` | stops | stops | todo | - |
| `rovsti_geo::NoticePrefs` | stops | stops | todo | - |
| `rovsti_geo::NoticeState` | stops | stops | todo | - |
| `rovsti_geo::NoticeWhen` | stops | stops | todo | - |
| `rovsti_geo::Outcome` | stops | stops | todo | - |
| `rovsti_geo::OutcomeFilter` | stops | stops | todo | - |
| `rovsti_geo::PRUNE_CHOICES_DAYS` | stops | stops | todo | - |
| `rovsti_geo::Pattern` | stops | stops | todo | - |
| `rovsti_geo::Route` | stops | stops | todo | - |
| `rovsti_geo::SavedStop` | stops | stops | todo | - |
| `rovsti_geo::SavedStopError` | stops | stops | todo | - |
| `rovsti_geo::SavedStops` | stops | stops | todo | - |
| `rovsti_geo::Side` | stops | stops | todo | - |
| `rovsti_geo::Stats` | stops | stops | todo | - |
| `rovsti_geo::Stop` | stops | stops | todo | - |
| `rovsti_geo::StopColour` | stops | stops | todo | - |
| `rovsti_geo::StopsPrefs` | stops | stops | todo | - |
| `rovsti_geo::TripRecord` | stops | stops | todo | - |
| `rovsti_geo::TripState` | stops | stops | todo | - |
| `rovsti_geo::alarm::MIN_ALARM_VOLUME_PCT` | stops | stops | todo | - |
| `rovsti_geo::arrival_clock` | stops | stops | todo | - |
| `rovsti_geo::backup::Backup` | stops | stops | todo | - |
| `rovsti_geo::coord_format::CoordFormat` | stops | stops | todo | - |
| `rovsti_geo::coord_format::cardinal` | stops | stops | todo | - |
| `rovsti_geo::coord_format::from_you_text` | stops | stops | todo | - |
| `rovsti_geo::coord_format::map_view_share_text` | stops | stops | todo | - |
| `rovsti_geo::coord_format::osm_view_url` | stops | stops | todo | - |
| `rovsti_geo::dest::SharedPlace` | stops | stops | todo | - |
| `rovsti_geo::dest::parse_location` | stops | stops | todo | - |
| `rovsti_geo::dest::parse_stop_share` | stops | stops | todo | - |
| `rovsti_geo::dest::point_qr_payload` | stops | stops | todo | - |
| `rovsti_geo::dest::stop_share_text` | stops | stops | todo | - |
| `rovsti_geo::history::format_duration` | stops | stops | todo | - |
| `rovsti_geo::history_io::export_file_name` | stops | stops | todo | - |
| `rovsti_geo::history_io::share_text` | stops | stops | todo | - |
| `rovsti_geo::history_io::to_csv` | stops | stops | todo | - |
| `rovsti_geo::history_io::to_gpx` | stops | stops | todo | - |
| `rovsti_geo::measure::MAX_POINTS` | stops | stops | todo | - |
| `rovsti_geo::next_volume` | stops | stops | todo | - |
| `rovsti_geo::notice::DEFAULT_TEMPLATE` | stops | stops | todo | - |
| `rovsti_geo::notice::render` | stops | stops | todo | - |
| `rovsti_geo::notice::validate` | stops | stops | todo | - |
| `rovsti_geo::places_io::MergePlan` | stops | stops | todo | - |
| `rovsti_geo::poll::MAX_POLL` | stops | stops | todo | - |
| `rovsti_geo::poll::MIN_POLL` | stops | stops | todo | - |
| `rovsti_geo::poll::retry_after` | stops | stops | todo | - |
| `rovsti_geo::prefs::AlarmSound` | stops | stops | todo | - |
| `rovsti_geo::prune_label` | stops | stops | todo | - |
| `rovsti_geo::read_zip` | stops | stops | todo | - |
| `rovsti_geo::saved_stops::FORMAT_VERSION` | stops | stops | todo | - |
| `rovsti_geo::saved_stops::NEAREST_CHIP_M` | stops | stops | todo | - |
| `rovsti_geo::saved_stops::SavedSort` | stops | stops | todo | - |
| `rovsti_geo::saved_stops::load_saved_stops` | stops | stops | todo | - |
| `rovsti_geo::saved_stops::suggest_name` | stops | stops | todo | - |
| `rovsti_geo::share_text` | stops | stops | todo | - |
| `rovsti_geo::shortcut_spec` | stops | stops | todo | - |
| `rovsti_geo::status_share_text` | stops | stops | todo | - |
| `rovsti_geo::tiles::AREA_MIN_ZOOM` | stops | tiles | todo | - |
| `rovsti_geo::tiles::AREA_TILE_BUDGET` | stops | tiles | todo | - |
| `rovsti_geo::tiles::ATTRIBUTION` | stops | tiles | todo | - |
| `rovsti_geo::tiles::AreaDownload` | stops | tiles | todo | - |
| `rovsti_geo::tiles::AreaPlan` | stops | tiles | todo | - |
| `rovsti_geo::tiles::ClassGeometry` | stops | tiles | todo | - |
| `rovsti_geo::tiles::DEFAULT_TILE_URL` | stops | tiles | todo | - |
| `rovsti_geo::tiles::DrawClass` | stops | tiles | todo | - |
| `rovsti_geo::tiles::FetchQueue` | stops | tiles | todo | - |
| `rovsti_geo::tiles::LruIndex` | stops | tiles | todo | - |
| `rovsti_geo::tiles::MAX_TILE_ZOOM` | stops | tiles | todo | - |
| `rovsti_geo::tiles::MapStyle` | stops | tiles | todo | - |
| `rovsti_geo::tiles::Priority` | stops | tiles | todo | - |
| `rovsti_geo::tiles::Rgba8` | stops | tiles | todo | - |
| `rovsti_geo::tiles::Shape` | stops | tiles | todo | - |
| `rovsti_geo::tiles::TileCover` | stops | tiles | todo | - |
| `rovsti_geo::tiles::TileGeometry` | stops | tiles | todo | - |
| `rovsti_geo::tiles::TileId` | stops | tiles | todo | - |
| `rovsti_geo::tiles::TileLayer` | stops | tiles | todo | - |
| `rovsti_geo::tiles::TileSource` | stops | tiles | todo | - |
| `rovsti_geo::tiles::TileTransform` | stops | tiles | todo | - |
| `rovsti_geo::tiles::TileUrlError` | stops | tiles | todo | - |
| `rovsti_geo::tiles::build_layer` | stops | tiles | todo | - |
| `rovsti_geo::tiles::display_zoom` | stops | tiles | todo | - |
| `rovsti_geo::tiles::estimate_bytes` | stops | tiles | todo | - |
| `rovsti_geo::tiles::frac_to_coord` | stops | tiles | todo | - |
| `rovsti_geo::tiles::line_width` | stops | tiles | todo | - |
| `rovsti_geo::tiles::stand_in` | stops | tiles | todo | - |
| `rovsti_geo::tiles::tiles_for_area` | stops | tiles | todo | - |
| `rovsti_geo::tiles::tiles_for_circle` | stops | tiles | todo | - |
| `rovsti_geo::tiles::tiles_for_view` | stops | tiles | todo | - |
| `rovsti_geo::usage_text` | stops | stops | todo | - |
| `rovsti_geo::view::FollowMode` | stops | stops | todo | - |
| `rovsti_geo::view::Viewport` | stops | stops | todo | - |
| `rovsti_tiles::DEFAULT_CAP_BYTES` | stops | tiles | todo | - |
| `rovsti_tiles::DiskCache` | stops | tiles | todo | - |
| `rovsti_tiles::TileClient` | stops | tiles | todo | - |
| `rovsti_tiles::TileError` | stops | tiles | todo | - |
| `rovsti_tiles::clear_all` | stops | tiles | todo | - |
| `rovsti_tiles::clear_except` | stops | tiles | todo | - |
| `rovsti_tiles::disk_usage` | stops | tiles | todo | - |
| `rovsti_tiles::load` | stops | tiles | todo | - |
| `rovsti_tiles::usage_by_source` | stops | tiles | todo | - |
| `rovsti_core::phone_number::PhoneNumber::matches` | - | shared | op | phone_number.same |
| `rovsti_core::calling_code::COUNTRIES` | - | shared | op | calling_code.list |
| `rovsti_core::number_format::available` | - | shared | op | format.available |
| `rovsti_core::number_format::copy_forms` | - | shared | op | format.copy_forms |
| `rovsti_core::number_format::copied_notice` | - | shared | op | format.copied_notice |
| `rovsti_core::number_format::render` | - | shared | op | format.render |
| `rovsti_core::calling_code::country_of_cc` | - | shared | op | calling_code.of_cc |
| `rovsti_core::calling_code::country_of` | - | shared | op | calling_code.country_of |
| `rovsti_core::dial::normalise_paste` | phone | input | op | dial.normalise_paste |
| `rovsti_core::dial::validate` | phone | input | op | dial.validate |
| `rovsti_core::dial::extract_numbers` | phone | input | op | dial.extract_numbers |
| `rovsti_core::dial::paste_plan` | phone | input | op | dial.paste_plan |
| `rovsti_core::dial::redial_target` | phone | input | op | dial.redial_target |
| `rovsti_core::dial::is_emergency` | phone | input | op | dial.is_emergency |
| `rovsti_core::blocklist::parse_many` | phone | phone | op | blocklist.parse_many |
| `rovsti_core::blocklist::parse_file` | phone | phone | op | blocklist.parse_file |
| `rovsti_core::blocklist::numbers_line` | phone | phone | op | blocklist.lines |
| `rovsti_core::blocklist::call_line` | phone | phone | op | blocklist.call_line |
| `rovsti_core::recents::refresh_delta` | phone | phone | op | recents.refresh_delta |
| `rovsti_core::recents::section_starts` | phone | phone | op | recents.section_starts |
