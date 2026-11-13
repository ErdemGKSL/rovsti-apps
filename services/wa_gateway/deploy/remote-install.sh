#!/usr/bin/env bash
# Runs ON the server, as the deploy user, and is uploaded by scripts/deploy-wa-gateway.sh.
# It builds the gateway with cargo (installing rustup for this user when cargo is missing),
# installs the binary and the systemd unit, and checks that the service answers. Do not run it by hand unless you
# know the layout under $REMOTE_WORK; the local script prepares it.
#
# Usage: remote-install.sh <command>
#   build-start     unpack bundle.tgz and start the cargo build in the background
#   build-log N     print build.log from byte N, then "@@STATUS=<RUNNING|OK|FAIL> @@SIZE=<bytes>"
#   install         wait until no call is live, then install the built binary, config, unit; restart;
#                   health check
#   live            how many calls the running gateway has live (0 when it is not running)
#   status          show the service, the listener and the health answer
#   logs            the last journal lines
#   rollback        put the previous binary back and restart
#
# Settings come from the environment (the local script sets them):
#   REMOTE_WORK BUILD_JOBS GATEWAY_BIND GATEWAY_MAX_INSTANCES
#   GATEWAY_TRUST_PROXY FCM_PROJECT FORCE_CONFIG HAVE_SERVICE_ACCOUNT LINES
#   CALL_WAIT       seconds a restart waits for live calls to end before it gives up (default 1800)
set -euo pipefail

WORK="$HOME/${REMOTE_WORK:-wa-gateway-build}"
BIN=/usr/local/bin/wa_gateway
ETC=/etc/wa-gateway
UNIT=/etc/systemd/system/wa-gateway.service
DROPIN_DIR=/etc/systemd/system/wa-gateway.service.d
BIND="${GATEWAY_BIND:-127.0.0.1:7218}"

say() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

health() {
  curl -fsS --max-time 3 "http://${BIND}/healthz" 2>/dev/null
}

# Calls the running gateway has live (ringing, connecting or active). The gateway keeps the number
# in <data_dir>/live-calls; a gateway from before that file is judged by its journal (it logs a
# "call audio ... live" line every 10 s while a call is active).
live_calls() {
  if ! systemctl is-active --quiet wa-gateway; then
    echo 0
    return
  fi
  local dir count
  dir="$(sudo sed -n 's/^data_dir *= *"\(.*\)"/\1/p' "$ETC/config.toml" 2>/dev/null | head -n 1)"
  count="$(sudo cat "${dir:-/var/lib/wa-gateway}/live-calls" 2>/dev/null | tr -dc 0-9 || true)"
  if [ -n "$count" ]; then
    echo "$count"
    return
  fi
  sudo journalctl -u wa-gateway --since "-25s" --no-pager -o cat 2>/dev/null \
    | grep -c ': call audio .* live [0-9]*s:' || true
}

# Waits until no call is live, so a restart never cuts one; gives up after CALL_WAIT seconds.
wait_for_calls() {
  local waited=0 limit="${CALL_WAIT:-1800}" live
  while :; do
    live="$(live_calls)"
    [ "${live:-0}" = 0 ] && return 0
    [ "$waited" -lt "$limit" ] || die "$live call(s) still live after ${limit} s; not restarting the gateway (try again later)"
    if [ $((waited % 60)) = 0 ]; then say "$live call(s) live on the gateway: waiting for them to end before the restart"; fi
    sleep 5
    waited=$((waited + 5))
  done
}

ensure_cargo() {
  export PATH="$HOME/.cargo/bin:$PATH"
  if command -v cargo >/dev/null 2>&1; then
    return
  fi
  command -v curl >/dev/null || die "curl is needed to install rustup"
  say "cargo is missing: installing rustup for $USER (in ~/.cargo, no sudo)"
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain stable
  command -v cargo >/dev/null 2>&1 || die "rustup finished but cargo is still not on PATH"
}

build_start() {
  command -v cc >/dev/null || die "no C compiler on the server (apt install build-essential)"
  [ -f "$WORK/bundle.tgz" ] || die "no $WORK/bundle.tgz; upload it first"
  ensure_cargo
  say "$(cargo --version)"
  mkdir -p "$WORK/cache/target" "$WORK/out"
  rm -rf "$WORK/src" "$WORK/out/wa_gateway"
  mkdir "$WORK/src"
  tar -xzf "$WORK/bundle.tgz" -C "$WORK/src"
  : > "$WORK/build.log"
  echo RUNNING > "$WORK/build.status"
  # The release profile in the workspace is tuned for Android size (fat LTO); a server build
  # trades that for a much shorter compile.
  cat > "$WORK/build.sh" <<BUILD
#!/usr/bin/env bash
export PATH="\$HOME/.cargo/bin:\$PATH"
export CARGO_TARGET_DIR="$WORK/cache/target"
export CARGO_BUILD_JOBS="${BUILD_JOBS:-3}"
export CARGO_PROFILE_RELEASE_LTO=off
export CARGO_PROFILE_RELEASE_CODEGEN_UNITS=8
export CARGO_PROFILE_RELEASE_OPT_LEVEL=2
cd "$WORK/src"
# Lowest CPU priority: the gateway on the same box may be carrying a call.
if nice -n 19 cargo build -p wa_gateway --release && cp "$WORK/cache/target/release/wa_gateway" "$WORK/out/wa_gateway"; then
  echo OK > "$WORK/build.status"
else
  echo FAIL > "$WORK/build.status"
fi
BUILD
  # Detached: the build outlives this SSH session, so a dropped connection costs nothing.
  setsid nohup nice -n 10 bash "$WORK/build.sh" >> "$WORK/build.log" 2>&1 < /dev/null &
  say "build started (log: $WORK/build.log)"
}

build_log() {
  local from="${1:-0}" size status
  size=$(stat -c %s "$WORK/build.log" 2>/dev/null || echo 0)
  status=$(cat "$WORK/build.status" 2>/dev/null || echo NONE)
  [ "$size" -gt "$from" ] && tail -c +$((from + 1)) "$WORK/build.log"
  printf '\n@@STATUS=%s @@SIZE=%s\n' "$status" "$size"
}

write_config() {
  if [ -f "$ETC/config.toml" ] && [ "${FORCE_CONFIG:-0}" != 1 ]; then
    say "keeping the existing $ETC/config.toml (FORCE_CONFIG=1 replaces it)"
    return
  fi
  say "writing $ETC/config.toml"
  {
    echo "# Written by scripts/deploy-wa-gateway.sh. Every key: services/wa_gateway/deploy/config.example.toml"
    echo "bind = \"${BIND}\""
    echo "data_dir = \"/var/lib/wa-gateway\""
    echo "max_instances = ${GATEWAY_MAX_INSTANCES:-4}"
    echo "# The Cloudflare tunnel in front appends the real client address to X-Forwarded-For."
    echo "trust_proxy = ${GATEWAY_TRUST_PROXY:-true}"
    if [ -n "${FCM_PROJECT:-}" ]; then
      echo "fcm_project_id = \"${FCM_PROJECT}\""
      echo "# fcm_service_account comes from the unit (WA_GATEWAY_FCM_SA), see the systemd drop-in."
    fi
  } | sudo tee "$ETC/config.toml" >/dev/null
  sudo chmod 0644 "$ETC/config.toml"
}

write_dropin() {
  sudo mkdir -p "$DROPIN_DIR"
  {
    echo "# Written by scripts/deploy-wa-gateway.sh."
    echo "[Service]"
    if [ "${HAVE_SERVICE_ACCOUNT:-0}" = 1 ] || sudo test -f "$ETC/service-account.json"; then
      echo "# The key stays root-only; systemd hands the service a private copy."
      echo "LoadCredential=fcm-sa:$ETC/service-account.json"
      echo "Environment=WA_GATEWAY_FCM_SA=/run/credentials/wa-gateway.service/fcm-sa"
    fi
  } | sudo tee "$DROPIN_DIR/10-rovsti.conf" >/dev/null
}

install_all() {
  [ -x "$WORK/out/wa_gateway" ] || die "no built binary at $WORK/out/wa_gateway; run the build first"
  "$WORK/out/wa_gateway" --help >/dev/null 2>&1 || die "the built binary does not run on this machine"
  sudo -n true 2>/dev/null || die "sudo needs a password for this user; the deploy cannot continue"
  wait_for_calls

  sudo install -d -m 0755 "$ETC"
  if [ -f "$WORK/service-account.json" ]; then
    say "installing the FCM service account (root only)"
    sudo install -m 0600 -o root -g root "$WORK/service-account.json" "$ETC/service-account.json"
    shred -u "$WORK/service-account.json" 2>/dev/null || rm -f "$WORK/service-account.json"
  fi

  if [ -f "$BIN" ]; then sudo cp -p "$BIN" "$BIN.prev"; fi
  say "installing $BIN"
  sudo install -m 0755 "$WORK/out/wa_gateway" "$BIN.new"
  sudo mv -f "$BIN.new" "$BIN"

  write_config
  sudo install -m 0644 "$WORK/src/services/wa_gateway/deploy/wa-gateway.service" "$UNIT"
  write_dropin

  say "starting the service"
  # Again, right before the restart: a call may have started while the files went in.
  wait_for_calls
  sudo systemctl daemon-reload
  sudo systemctl enable wa-gateway >/dev/null 2>&1
  sudo systemctl restart wa-gateway

  for _ in $(seq 1 30); do
    if health >/dev/null; then
      say "healthy: $(health) on http://${BIND}"
      systemctl is-enabled wa-gateway | sed 's/^/enabled: /'
      return 0
    fi
    sleep 1
  done
  printf 'error: the gateway did not answer on http://%s/healthz in 30 s\n' "$BIND" >&2
  sudo journalctl -u wa-gateway -n 40 --no-pager >&2 || true
  if [ -f "$BIN.prev" ]; then
    say "putting the previous binary back"
    sudo cp -p "$BIN.prev" "$BIN" && sudo systemctl restart wa-gateway || true
  fi
  exit 1
}

show_status() {
  systemctl --no-pager --lines=0 status wa-gateway 2>&1 | head -12 || true
  echo "--- listener"
  ss -ltn 2>/dev/null | grep ":${BIND##*:}\b" || echo "nothing listens on ${BIND}"
  echo "--- health"
  health || echo "no answer from http://${BIND}/healthz"
  echo "--- live calls: $(live_calls)"
  echo "--- config ($ETC/config.toml)"
  sudo cat "$ETC/config.toml" 2>/dev/null || echo "none"
  echo "--- binary"
  ls -l "$BIN" 2>/dev/null || echo "not installed"
  if [ -f "$BIN.prev" ]; then ls -l "$BIN.prev"; fi
}

case "${1:-}" in
  build-start) build_start ;;
  build-log) build_log "${2:-0}" ;;
  install) install_all ;;
  live) live_calls ;;
  status) show_status ;;
  logs) sudo journalctl -u wa-gateway -n "${LINES:-100}" --no-pager ;;
  rollback)
    [ -f "$BIN.prev" ] || die "no previous binary to go back to"
    wait_for_calls
    sudo cp -p "$BIN.prev" "$BIN"
    sudo systemctl restart wa-gateway
    say "rolled back"
    ;;
  *) die "unknown command '${1:-}'; see the header of this file" ;;
esac
