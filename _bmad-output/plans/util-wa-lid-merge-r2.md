# util-wa-lid-merge: plan (round 2)

baseSha: c2d54aa8de38793f7dca62891531955514d67797

## State
Round 1 (`util-wa-lid-merge.md`) S1-S7 are implemented and committed (d31f512..c2d54aa):
- Gateway: pure resolver `services/wa_gateway/src/wa/lid.rs` (`LidMap`, `resolve`, `Resolver` over
  `Client::get_lid_pn_entry`, `sweep`/`sweep_with`), used in convert/history/session/status/groups/
  app_state/calls; store merge `services/wa_gateway/src/store/merge.rs` (`lid_chats`, `merge_chat`,
  5 tests); `EventSink::merge_chat` publishes `GatewayEvent::ChatMerged`; sweeps on connect (startup
  migration), after history sync, on `DeviceListUpdate`/`ContactNumberChanged`, and a per-message
  merge in on_message; end-to-end test in `services/wa_gateway/tests/api.rs`.
- Proto/apps: `is_pn_jid`/`is_lid_jid`, "WhatsApp user" label, `ChatMerged` handled in timeline,
  Messages (drafts/prefs/inbox rekey), Phone, Contacts, rovsti_wa_ui.

Remaining gap found in review: `apps/messages/src/status_ui.rs::author_title` (line ~181) still
calls `contacts.match_number(&format!("+{}", jid_user(&author.author)))` for every status author,
so a LID author (`96413459456093@lid`) is matched as the number +96413459456093. All other
`format!("+{}", jid_user(..))` call sites are already guarded by `is_pn_jid`.

## Steps

### S1 Guard the status author lookup and finish green
- `apps/messages/src/status_ui.rs::author_title`: only call `match_number` when
  `rovsti_wa_proto::is_pn_jid(&author.author)` (same `is_pn_jid(..).then(..).flatten()` pattern as
  `apps/messages/src/inbox.rs:420`). Extract the pure part as
  `fn author_number(author: &str) -> Option<String>` (`Some("+<digits>")` for PN JIDs, device suffix
  dropped via `jid_user`, `None` for `@lid`/groups/broadcast) and add a unit test in the file's
  test module: `author_number("905551234567@s.whatsapp.net") == Some("+905551234567")`,
  `author_number("96413459456093@lid") == None`, and
  `rovsti_wa_ui::display_name(None, None, "96413459456093@lid")` contains no '+'.
- Run all checks (rm -rf target/debug/incremental first if df < 6 GB): fmt, clippy
  `-D warnings`, `cargo test --workspace`, the three `aarch64-linux-android` checks.
- Update the status line of `_bmad-output/plans/util-wa-lid-merge.md` and this file; commit
  ("Rovsti wa-lid-merge: status author lookup only for phone JIDs"). Working tree clean.
- Acceptance: all checks green, `git status` clean.

### S2 Deploy the gateway (last)
- `CLOUDFLARED=/tmp/claude-0/-home-user-rovsti-apps/fec9d351-b8cd-5c7f-af54-f98d09255a4a/scratchpad/cloudflared bash scripts/deploy-wa-gateway.sh deploy --if-changed`
  then the same with `status` and `logs` (LINES=300 if needed).
- Confirm `/healthz` ok, and the journal shows `lid-merge: startup sweep merged N of M LID chats`
  with no `lid-merge: ... failed` warnings / errors.
- On failure: `... rollback`, then `status`, and report. Never touch /etc/wa-gateway/config.toml,
  never set FORCE_CONFIG.
