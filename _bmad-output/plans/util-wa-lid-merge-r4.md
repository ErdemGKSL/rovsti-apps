# util-wa-lid-merge: plan (round 4)

baseSha: b4009b3f5c297d79e3492244eb0bcc35d4725ff9

## State
All code work is committed (d31f512..79abdef) and the working tree is clean. Commit b4009b3
("Re-verify checks and deploy the gateway") only added the round-3 plan file: there is no deploy
log in the scratchpad and `util-wa-lid-merge.md` still says "deploy pending". The last full check
run (scratchpad `checks.log`, 18:30) was green. Free disk is about 8 GB.
Only the gateway deploy is left.

## Steps

### S1 Re-verify and deploy the gateway (last)
- If `df` shows < 6 GB free, `rm -rf target/debug/incremental`. Re-run all checks once (fmt,
  clippy `-D warnings`, `cargo test --workspace`, the three `aarch64-linux-android` checks). They
  must be green and `git status` clean before deploying. Do not change source.
- Run `CLOUDFLARED=/tmp/claude-0/-home-user-rovsti-apps/fec9d351-b8cd-5c7f-af54-f98d09255a4a/scratchpad/cloudflared bash scripts/deploy-wa-gateway.sh deploy --if-changed`
  and tee the output to scratchpad `deploy.log`. Then run the same command with `status` and
  with `logs` (`LINES=300` if the sweep line is not in the last 100).
- Confirm `/healthz` is ok and that the journal shows
  `lid-merge: startup sweep merged N of M LID chats` with no `lid-merge: ... failed` warnings
  or errors.
- On failure: run `... rollback`, then `... status`, and report. Never touch
  /etc/wa-gateway/config.toml, and never set FORCE_CONFIG.
- Change the status line of `_bmad-output/plans/util-wa-lid-merge.md` to
  "Status: all steps done, deployed 2026-11-06 (sweep merged N of M)", or record the rollback,
  and commit only that doc change.
