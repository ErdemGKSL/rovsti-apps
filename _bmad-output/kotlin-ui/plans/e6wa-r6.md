# E6-WA R6: groups

Branch `messages-kotlin`. Epic plan: `e6wa-plan.md` (round table, row WA-R6). Research: `../research/e6wa-inventory.md`
(rows Group info / metadata, Create group, Participants add / remove / promote / demote, Invite link get / revoke /
join, Announcements-only, locked, approval, member-add mode, Leave group, Communities; ranked item 5). Base:
`messages-kotlin` at `0a277191` (R5 closed and merged with `ui-overhaul`).

Goal of the round: a WhatsApp group is a first-class thing in Messages. A tap on a group's header opens its info page:
picture, subject, description, when and by whom it was made, the community it belongs to, my role, the disappearing
timer (the R4 setting), and every member with their role and the best name we have (R1 order, never LID digits),
lazily listed so a group of 1000+ scrolls. When I am an admin the page also adds, removes, promotes and demotes
members, edits subject, description and picture, switches "only admins can send", "only admins can edit info" and
"approve new members", answers join requests, and shows, copies, shares and resets the invite link; a bulk action
reports each person's result (added, invited instead because of their privacy, not on WhatsApp, already in the group,
group full). Anyone can create a group, join one from a pasted or tapped `chat.whatsapp.com` link after a preview,
and leave one; a group I left or was removed from stays readable with a notice in place of the composer. Member,
subject, picture, admin and settings changes show as notice lines in the thread. A group in announcement mode
replaces the composer with "Only admins can send messages" when I am not an admin. Communities are read-only: a
sub-group's info page names its community and lists its groups, with the announcement group marked.

Library facts (whatsapp-rust 0.7.0 from crates.io, the version `Cargo.lock` pins; `LIB/` =
`~/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/whatsapp-rust-0.7.0/src/`, `WACORE/` = `.../wacore-0.7.0/src/`,
`PROTO/` = `.../waproto-0.7.0/src/whatsapp.proto`):

- Metadata: `groups().get_metadata(jid)` `LIB/features/groups.rs:689` (always from the network, members' numbers
  back-filled from known LID mappings, `:690-701`), `get_participating()` `:657`. `GroupMetadata` `:147-232`: `subject`,
  `participants`, `addressing_mode`, `creator`/`creator_pn`, `creation_time` (s), `subject_owner`/`subject_time`,
  `description`, `description_id` (the token a description change needs), `is_locked`, `is_announcement`, `ephemeral`,
  `membership_approval`, `member_add_mode`, `size`, `is_parent_group`, `parent_group_jid`, `is_default_sub_group`
  (the announcement group), `is_general_chat`. A member: `GroupParticipant {jid, phone_number, lid, participant_type}`
  `:236-253`, `ParticipantType::{Member, Admin, SuperAdmin}` `WACORE/iq/groups.rs:184-198`.
- Create: `create_group(GroupCreateOptions)` `LIB/features/groups.rs:703`; options `WACORE/iq/groups.rs:274-300`
  (subject, participants, add mode default all members, approval off, timer 0). A LID-only member needs a known
  number mapping or the call fails with `InvalidRequest` (`LIB/features/groups.rs:709-722`). Subject at most 100
  characters, description 2048 (`WACORE/iq/groups.rs:19, 22`; `GroupSubject`/`GroupDescription` `:213-225`).
- Members: `add_participants` `:833`, `remove_participants` `:871`, `promote_participants` `:949`,
  `demote_participants` `:961`, each answers one `ParticipantChangeResponse {jid, status, error, phone_number,
  add_request}` per person (`WACORE/iq/groups.rs:1690-1707`). `error == "403"` carries `add_request {code,
  expiration}` (`:1681-1688`): the person's privacy refuses adds, and the code is a V4 invite the adder may send
  them. The library does not send it: the gateway builds a `GroupInviteMessage {groupJid, inviteCode,
  inviteExpiration, groupName, caption}` (`PROTO:3283-3296`, field 28 of `Message`, `PROTO:2770`) and sends it. The
  other per-person codes are not named in the library; the mapping used (404 not on WhatsApp, 408 left recently, 409
  already a member, 500 group full, 401 or 406 not allowed) is WhatsApp Web's as remembered and is a table in
  `rosty_core::wa_group` that keeps unknown codes as "failed" with the code.
- Settings: `set_subject` `:748`, `set_description(jid, Option<GroupDescription>, PreviousDescription)` `:772`
  (`Resolve` reads the token first; `DescriptionConflict` on a race, `:75-104, 810-819`), `set_announce` `:997`,
  `set_locked` `:986`, `set_membership_approval` `:1029`, `set_member_add_mode` `:1121` (not offered this round),
  `set_ephemeral` `:1015` (R4), `set_profile_picture(jid, jpeg)` `:1308` (the caller sizes it; WhatsApp uses
  640x640), `remove_profile_picture` `:1321`.
- Invite link: `get_invite_link(jid, reset)` `:973` answers the full `https://chat.whatsapp.com/<code>`
  (`WACORE/iq/groups.rs:2202-2218`); `reset` revokes the old code. Preview: `get_invite_info(code)` `:1075` answers a
  `GroupMetadata` (subject, size, description, creator, parent) without joining. Join: `join_with_invite_code` `:1042`
  answers `JoinGroupResult::{Joined(jid), PendingApproval(jid)}` (`WACORE/iq/groups.rs:2894-2930`). Both accept a
  bare code or a link (`extract_invite_code`, private, `LIB/features/groups.rs:1537-1561`). A reset or unknown code
  is a server error (`IqError::ServerError {code}` `LIB/request.rs:113-121`); the codes are not named in the library.
- Approval: `get_membership_requests` `:1083` answers `MembershipRequest {jid, request_time}`
  (`WACORE/iq/groups.rs:3090-3095`); `approve_membership_requests` `:1095` and `reject_membership_requests` `:1108`
  answer per-person results like adds.
- Leave: `leave(jid)` `:822`. Remove from a community with its groups (`:931`) is not offered.
- Communities: `group_type(&GroupMetadata)` `LIB/features/community.rs:124-137` (`Community`, `LinkedSubgroup`,
  `LinkedAnnouncementGroup`, `LinkedGeneralGroup`, `Default`); `community().get_subgroups(parent)` `:297` answers
  `CommunitySubgroup {id, subject, participant_count, is_default_sub_group, is_general_chat}` (`:95-106`). Community
  admin calls (`create`, `link_subgroups`, `deactivate`, ...) are not used.
- Notifications: `Event::GroupUpdate(GroupUpdate {group_jid, notification_id, action_index, participant,
  participant_pn, timestamp, action, ..})` `WACORE/types/events.rs:1777-1810`, one event per action. Actions
  `GroupNotificationAction` `WACORE/stanza/groups.rs:143-300`: `Add {participants, reason}`, `Remove {participants,
  reason}`, `Promote`, `Demote`, `Modify` (number change), `Subject`, `Description {description: None deletes}`,
  `Locked`/`Unlocked`, `Announce`/`NotAnnounce`, `Ephemeral` (R4), `MembershipApprovalMode {enabled}`,
  `MembershipApprovalRequest`, `CreatedMembershipRequests`, `RevokedMembershipRequests`, `Invite {code}` (joined by
  link), `RevokeInvite`, `Create`, `Delete`, `Link`/`Unlink` (community). A participant: `GroupParticipantInfo {jid,
  phone_number, lid, type}` `:103-121`. Picture changes are `Event::PictureUpdate {jid, author, removed,
  picture_id}` `WACORE/types/events.rs:1694-1709` (the gateway handles it in `GW/wa/app_state.rs:363-372`).
- Size: `GROUP_SIZE_LIMIT = 257` `WACORE/iq/groups.rs:25` is an old A/B default (WhatsApp allows more today); the
  round does not enforce a limit, it reports the server's per-person "group full".

## Owner decisions taken into this round

- **R5 follow-ups** (2026-11-20, through the main thread): map tiles of place cards go to a small bounded disk cache
  (LRU, about 20 MB, in the app's `cacheDir` so the OS may evict it) through the existing `rosty_tiles` `DiskCache`,
  cleared whenever the WhatsApp data is forgotten, unlinked or wiped. Trade-off, noted in `docs/architecture.md`:
  these tiles show which areas were shared in chats, which sits against the vault rule (`wa-vault.md` 13.1); the cap,
  the cache directory and the clean-up on forget, unlink and wipe bound it. The tile server stays the default
  VersaTiles one (no setting, no sharing with Stops). Location permission: unchanged.

## Decisions made for this round (open to the owner, see questions)

- **One action route.** Every change of a group is `POST /v1/groups/action` with a tagged action, as
  `POST /v1/chats/action` does for chats; create, preview, join, requests and sub-groups have their own routes.
- **The gateway keeps no group table.** Metadata comes from WhatsApp on each `GET /v1/groups/{jid}`; the gateway
  only remembers that I left or was removed (`chat_settings.left_ms`, schema **v13**), so a chat stays read-only
  after a restart, and stores the notice lines as messages (as the R4 timer line).
- **The phone keeps the group.** `wa_sync` **v8** adds `groups` and `group_members`: the info page and the composer
  read from the store, open offline at once, and a notice line updates the stored members (the merge rule in
  `rosty_core`) before the next fetch.
- **Invite instead of add** when a person's privacy refuses adds: the add result says "invite available", the sheet
  offers "Send Invite", and the gateway sends WhatsApp's own group invite message with the code the server gave.
- **Picture**: the phone crops and scales the picked image to a 640 px JPEG (at most 1 MiB) before sending.
- **Communities on the info page**, not in the chat list: a sub-group names its community and lists the community's
  groups (announcement group marked). The chat list is unchanged.
- **Join from a link**: a pasted link (New Message, "Join Group With Link") or a tapped `chat.whatsapp.com` link in a
  bubble opens the preview sheet. No intent filter for `chat.whatsapp.com` (it would compete with WhatsApp itself).

## Wire additions (agreed up front; all additive)

- `Participant.role: Option<String>` (`superadmin`, `admin`, `member`; `admin` stays for older apps).
- `GroupInfo` gains (all optional or default false, left out when empty): `created_ms`, `creator`, `picture_id`,
  `locked`, `approval`, `admins_add` (only admins add), `ephemeral_s`, `size`, `description_id`, `community`
  (`{kind: parent | sub | announcement | general, parent?}`), `left` (I am not a member: answered from the gateway's
  record when WhatsApp refuses the metadata).
- `ChatMeta.left: bool`: I left or was removed. A join or add clears it.
- `SystemNote::Group {action, by?, participants: [jid], value?}` (`action` one of `add`, `remove`, `leave`,
  `join_link`, `promote`, `demote`, `number`, `subject`, `description`, `picture`, `announce`, `locked`,
  `approval`, `link_reset`, `create`, `delete`; `value` the new subject or `on`/`off`). An older app shows the
  `Other` kind (nothing new); an older gateway sends none.
- `POST /v1/groups/action {group, action}` -> `GroupActionDone {results: [ParticipantResult], link?}`; `action`
  tagged by `type`: `add | remove | promote | demote {participants}`, `subject {subject}`, `description
  {description}` (empty deletes), `picture {jpeg_b64?}` (none removes), `announce {on}`, `locked {on}`, `approval
  {on}`, `invite_link {reset}`, `approve | reject {participants}`, `send_invite {to, code, expiration}`, `leave`.
  `ParticipantResult {jid, error?, invite?: {code, expiration}}`. 400 for an empty list, a subject over 100 or a
  description over 2048 characters, a picture over 1 MiB; 403 when WhatsApp refuses (not an admin); 409 for a
  description changed elsewhere; an older gateway answers 404.
- `POST /v1/groups {subject, participants}` -> `GroupCreated {jid, results}`.
- `GET /v1/groups/invite?code=` -> `InvitePreview {jid, subject, size?, description?, approval, community?}`; 404 for
  an unknown or reset code.
- `POST /v1/groups/join {code}` -> `GroupJoined {jid, pending}`.
- `GET /v1/groups/{jid}/requests` -> `[JoinRequest {jid, jid_alt?, requested_ms?}]`.
- `GET /v1/groups/{jid}/subgroups` -> `[Subgroup {jid, subject, size?, announcement, general}]`.
- Gateway store **schema v13**: `chat_settings.left_ms`. Only a new nullable column.

## Steps (one story each)

| Step | What | Layers |
|---|---|---|
| 1 | R5 follow-ups (owner): place-card tiles in a bounded disk cache (`tiles.open` with a `dir` under `cacheDir`, 20 MiB), cleared on forget, unlink and wipe; docs, `CLAUDE.md`, status note. Own commit. | Messages, docs |
| 2 | Domain rules, `rosty_core::wa_group`: `role` (roles and what each may do: send in announcement mode, edit info when locked, add, remove, promote, demote whom, see the link, answer requests; the last admin seat), `notice` (a group note to a template with actor, targets through my ids, "you", at most three names then "+N", self-removal is "left", add by link is "joined"), `members` (merge add, remove, promote, demote, number change into a member list; my role and membership after it; ordering: me, admins, the rest by name), `invite` (find and parse `chat.whatsapp.com` links and bare codes), `bulk` (per-person outcome from the server code, the summary of a bulk action, who can get an invite), `community` (kind from the flags). Unit tests. | `rosty_core` |
| 3 | Wire and client: the fields and routes above in `rosty_wa_proto` (`group.rs`), `group_action`, `create_group`, `invite_preview`, `join_group`, `join_requests`, `subgroups` in `rosty_wa_client`; wire tests. | proto, client |
| 4 | Gateway (own commit, not deployed): schema v13; richer `GET /v1/groups/{jid}` (roles, settings, community, `left`); the action route (members with per-person results, settings, picture, link, requests, invite message, leave); create, preview, join, requests and sub-group routes; group notifications and group picture changes stored as notice lines; `left` set on leave and on my removal, cleared on add, join or create; `ChatMeta.left`. Tests. | `services/wa_gateway` |
| 5 | Device store `wa_sync` **v8**: tables `groups` and `group_members`; `group_store` (an info answer), `group_read` (paged and filtered members, me first), a stored group note merged into the members, `left` from the chat meta; rows carry `group_note`. Migration and tests incl. the edge cases. | `rosty_app_core` |
| 6 | FFI and bridge: ops `wa.group_action`, `wa.group_create`, `wa.group_invite_preview`, `wa.group_join`, `wa.group_requests`, `wa.group_subgroups`, `wa.group_view`, `wa.group_notice`, `wa.invite_find`, `wa.group_results`, `wa_sync.group_store`, `wa_sync.group_read`; fixtures, `OPS.md`, Kotlin models, facades and fakes. | `rosty_ffi`, `:core-bridge` |
| 7 | Messages UI: group info page (header tap), admin rows and toggles, member menu, add-members sheet (contacts and WhatsApp-known numbers), results sheet, invite link sheet, requests list, edit subject and description, picture pick, leave with the last-admin warning, join preview sheet (pasted and tapped links), create-group flow from New Message, the announcement-mode and left composer lines, notice lines, the community section. Goldens light and dark, strings en and tr. | Messages |

Order: 1 (own commit), 2, 3, 4 (gateway commit), 5, 6, 7.

Edge cases and the tests that cover them:
- I am removed while the info page is open: the remove notice names me, the store marks the group left, the page
  turns read-only and drops the admin rows (wa_sync and view model tests).
- I am demoted mid-action: the action answers 403; the page refetches and hides the admin rows (view model test,
  gateway 403 mapping test).
- An add refused by privacy: the result is "invite available" with WhatsApp's code; "Send Invite" sends the group
  invite message (rosty_core bulk test, gateway action test).
- Members known only by LID: a member with no number is named by push name, else "WhatsApp user", never digits
  (rosty_core members test, Kotlin names); creating a group with one fails per the library (`:709-722`) and is
  reported for that person.
- A group at its size limit: the server's per-person "full" outcome (rosty_core bulk test).
- An invite link reset or invalid: the preview answers 404, the sheet says the link is no longer valid (gateway and
  Kotlin tests); the parser refuses non-WhatsApp links and empty codes (rosty_core).
- Joining a group that needs approval: `pending` is shown as "Request sent" (gateway and Kotlin tests).
- Leaving the last admin seat: the leave confirm warns when I am the only admin and others remain (rosty_core).
- A very large group: members are read from the store in pages of 100 into a lazy list (wa_sync paging test).
- A community announcement group where I cannot send: announcement mode with me not an admin replaces the composer
  (rosty_core role test, Kotlin composer test).

## Out of this round

- Community admin actions (create, link, unlink, deactivate, remove from all groups), community join flows.
- Member add mode ("who can add members"), member labels, history sharing modes, "no frequently forwarded".
- Admin delete of other people's messages (gateway supports it; left for a later round).
- An intent filter for `chat.whatsapp.com` links opened from other apps.
- Group calls (epic decision 8).

## Open owner questions

1. Only admins see the invite link here (WhatsApp shows it to members when "all members can share link" is on; the
   library has `member_link_mode`, not offered). Fine?
2. Communities are shown on the info page only, not grouped in the chat list. Group them in the list later?
3. Joining from links opened in other apps (an intent filter for `chat.whatsapp.com`) would make Android ask which
   app opens WhatsApp links. Wanted?

## Evidence

Filled in when the round closes.
