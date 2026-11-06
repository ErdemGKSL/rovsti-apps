# util-wa-lid-merge: plan (round 5)

baseSha: 61ae0f1b5a1cf7cc04b5559df0beaf93b00b838c

## State
All code work is committed (d31f512..79abdef) and the working tree is clean. The resolver
(`services/wa_gateway/src/wa/lid.rs`), the store merge, the startup sweep
(`lid-merge: startup sweep merged N of M LID chats`), `GatewayEvent::ChatMerged` and the app-side
rekeying and display rules are all in. The last full check run (scratchpad `checks.log`, 18:35)
was green. Free disk is about 8.1 GB.

Rounds 3 and 4 both ended with a commit that only added the plan file. The deploy was never
run: the scratchpad has no `deploy.log` and `util-wa-lid-merge.md` still says "deploy pending".
Only the deploy is left. This round must actually run the deploy script and keep its output.

## Steps

### S1 Deploy the gateway and verify it (last, only step)
- Do not change source. `git status` must be clean. If `df` shows under 6 GB free, run
  `rm -rf target/debug/incremental`. Re-run all checks once and tee them to scratchpad
  `checks-r5.log`: fmt, clippy `-D warnings`, `cargo test --workspace`, and the three
  `aarch64-linux-android` checks. All must be green.
- Run the deploy for real (not `--dry-run`), from the repo root, and tee it to
  `/tmp/claude-0/-home-user-rovsti-apps/fec9d351-b8cd-5c7f-af54-f98d09255a4a/scratchpad/deploy.log`:
  `CLOUDFLARED=/tmp/claude-0/-home-user-rovsti-apps/fec9d351-b8cd-5c7f-af54-f98d09255a4a/scratchpad/cloudflared bash scripts/deploy-wa-gateway.sh deploy --if-changed`
  Then run the same command with `status` (tee `deploy-status.log`) and with `logs`
  (tee `deploy-logs.log`). Use `LINES=300` if the sweep line is not in the last 100 lines.
- If `--if-changed` says "already this version", the deploy is fine. Still run `status` and
  `logs`, and find the sweep line from that binary's last start.
- Acceptance: `/healthz` is ok, the service is active, and the journal shows
  `lid-merge: startup sweep merged N of M LID chats` with no `lid-merge: ... failed` warnings
  and no errors.
- On failure (a deploy error, unhealthy `/healthz`, or sweep errors): run `... rollback`, then
  `... status`, and record what failed. Never touch `/etc/wa-gateway/config.toml` and never set
  FORCE_CONFIG.
- If the script cannot run at all (SSH or tunnel refused, or a permission denial), do not commit
  a "deployed" claim. Record the exact error in the status line instead.
- Change line 3 of `_bmad-output/plans/util-wa-lid-merge.md` to
  `Status: all steps done, deployed 2026-11-06 (sweep merged N of M).` Or record the rollback
  or the blocking error there. Commit that doc change together with this plan file. Do not
  commit anything else.
