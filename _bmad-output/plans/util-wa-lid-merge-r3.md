# util-wa-lid-merge: plan (round 3)

baseSha: 79abdef54f95d55d6aadb96d42a9b56807a94a38

## State
All code work is committed (d31f512..79abdef), working tree clean:
- Gateway resolver `services/wa_gateway/src/wa/lid.rs` (over `Client::get_lid_pn_entry`, alt
  fallback), used in convert/history/session/status/groups/app_state/calls; store merge
  `services/wa_gateway/src/store/merge.rs` with tests; `GatewayEvent::ChatMerged`; startup sweep
  logging `lid-merge: startup sweep merged N of M LID chats`; sends accept PN or legacy LID.
- Proto/apps: `is_pn_jid`/`is_lid_jid`, "WhatsApp user" label; every
  `format!("+{}", jid_user(..))` call site (conversation.rs, main.rs, inbox.rs, status_ui.rs,
  rovsti_core inbox.rs, rovsti_wa_ui view.rs, rovsti_wa_proto api.rs) is guarded by `is_pn_jid`;
  ChatMerged rekeys drafts/pins/mutes in Messages, Phone, Contacts.
- Round-2 S1 (status author guard + test `author_number_only_for_phone_jids`) is done.
Only the gateway deploy is left.

## Steps

### S1 Re-verify and deploy the gateway (last)
- If `df` shows < 6 GB free, `rm -rf target/debug/incremental`. Re-run all checks once (fmt,
  clippy `-D warnings`, `cargo test --workspace`, the three `aarch64-linux-android` checks); they
  must be green and `git status` clean before deploying. Do not change source.
- `CLOUDFLARED=/tmp/claude-0/-home-user-rovsti-apps/fec9d351-b8cd-5c7f-af54-f98d09255a4a/scratchpad/cloudflared bash scripts/deploy-wa-gateway.sh deploy --if-changed`,
  then the same with `status` and `logs` (`LINES=300` if the sweep line is not in the last 100).
- Confirm `/healthz` ok and that the journal shows `lid-merge: startup sweep merged N of M LID chats`
  with no `lid-merge: ... failed` warnings or errors.
- On failure: `... rollback`, then `... status`, and report. Never touch /etc/wa-gateway/config.toml,
  never set FORCE_CONFIG.
- Update the status line of `_bmad-output/plans/util-wa-lid-merge.md` ("deployed <date>") and
  commit that doc change only.
