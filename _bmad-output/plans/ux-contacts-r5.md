# UX completeness pass: Rovsti Contacts (round 5)

Base: `da24d21b9769519b3e8333d2dbfdb5fb87d05df3` (main, "Rovsti contacts: Docs and plan status").
The tree is clean and there is no stash. Rounds 1 to 4 (`ux-contacts.md` U1 to U7,
`ux-contacts-r2.md` V1 to V5, `ux-contacts-r3.md` W1 to W3, `ux-contacts-r4.md` X1 to X3) are
done.

## Re-audit (round 5)

Checked from the user's side again: Contacts tab (empty book, No Results with Create Contact
prefilled from the query, + button), Favorites (empty state, + picker, star toggle), Groups
(create, rename, delete with confirm, members picker, empty group, account hint), Settings
(name format, sort, Save to, export and import, WhatsApp), the card (call, message, share,
email, per-field sheet with copy/share/email/note copy, Edit, Open in Android Contacts, Delete
with Undo), the editor (names, numbers, emails, note, groups, photo choose/remove/WhatsApp
photo, Save to, discard confirm), permission recovery and Try Again, and Back from every layer.

Every `rovsti_android::contacts_edit` seam (detail, full, save, delete, import, groups,
save_group, set_group_members, delete_group, accounts, photo_size, set_photo, remove_photo) and
`providers::set_starred` has a UI entry point. The `rovsti_core::contact_edit` helpers are all
used by the app (`draft_from_query` by the No Results state).

| # | Sev | Finding |
|---|-----|---------|
| Y1 | Info (cosmetic, not planned) | `apps/contacts/src/picker.rs` module doc still says "(and, later, to the Favorites)" although the Favorites picker exists. Doc only, no user impact; fix it with any later picker change. |
| Y2 | Info (not planned) | The T2/T3 list from round 4 stays out of scope as new features: custom Android labels in the kind toggle, answering other apps' insert/view intents, default number, import into a chosen account, vCard file share, row quick actions, Undo for Delete Group, custom favorites order. |

## Result

No meaningful gap remains. Round 5 returns done with no steps.
