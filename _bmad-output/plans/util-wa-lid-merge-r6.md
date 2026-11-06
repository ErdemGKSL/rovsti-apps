# util-wa-lid-merge: plan (round 6)

baseSha: 2fde37565877a1b954959cfcc8be8e9822f0abfa

## State
All code is committed (d31f512..2fde375) and the working tree is clean:
- Gateway: LID resolver `services/wa_gateway/src/wa/lid.rs` (uses `Client::get_lid_pn_entry`,
  falls back to the alt fields), used for chat, sender, author and participant ids in convert,
  history, session, status, groups, app_state and calls.
- Gateway store: merge of a LID chat into its PN chat (moves messages, dedupes by id, sums
  unread, deletes the LID chat, idempotent), merge triggers, startup sweep
  (`lid-merge: startup sweep merged N of M LID chats`), and a `GatewayEvent::ChatMerged`
  notification. Sends work with either a PN id or a legacy LID id.
- Proto, client and apps: `is_pn_jid` guards, so a LID is never shown as `+digits` (the
  fallback is the push or contact name, else "WhatsApp user"). Apps merge by number only for
  PN JIDs, and `ChatMerged` moves drafts, pins and mutes to the surviving id.
- Tests cover the resolver, the DB merge and the display rule.

Checks: the full run (scratchpad `checks-r5.log`) passed. The only later commit (2fde375,
`apps/messages/src/settings_ui.rs`) was re-checked this round: `cargo fmt --check` passed,
`cargo clippy -p rovsti_messages --all-targets -D warnings` passed, and
`cargo test -p rovsti_messages` passed (219 tests).

## Deploy
Round 5 tried the deploy. The session's permission system denied it (auto-mode classifier:
Production Deploy), so `scripts/deploy-wa-gateway.sh` never ran. `util-wa-lid-merge.md` line 3
records this. That denial is the user's permission layer saying no, so the loop must not plan
the deploy again. The owner has to run it, or approve it, themselves:

```
CLOUDFLARED=/tmp/claude-0/-home-user-rovsti-apps/fec9d351-b8cd-5c7f-af54-f98d09255a4a/scratchpad/cloudflared \
  bash scripts/deploy-wa-gateway.sh deploy --if-changed
# then: ... status   and   ... logs  (check /healthz ok and the "lid-merge: startup sweep" line)
# on failure: ... rollback
```

## Steps
None. The code goal is done. The deploy waits on the owner.
