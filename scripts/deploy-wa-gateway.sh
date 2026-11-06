#!/usr/bin/env bash
# Deploys the WhatsApp gateway (services/wa_gateway) to a Linux server as a systemd service.
#
# It reaches the server over SSH through Cloudflare (cloudflared as the ProxyCommand), sends a
# small source bundle (the gateway and the three workspace crates it needs, not the apps),
# builds it ON the server with cargo (rustup is installed for the deploy user if missing; no
# Docker), installs /usr/local/bin/wa_gateway plus the hardened unit from
# services/wa_gateway/deploy, and checks /healthz.
#
# Usage: scripts/deploy-wa-gateway.sh [deploy|status|logs|rollback] [--dry-run]
#   deploy    build and (re)install the service (default)
#   status    service state, listener, health answer, config
#   logs      the last 100 journal lines (LINES=300 for more)
#   rollback  put the previous binary back
#   --dry-run check tools, key and connection, build the bundle, change nothing
#
# Settings (environment; the defaults are this project's server):
#   DEPLOY_HOST=ssh.erdemdev.tr   DEPLOY_USER=erdem
#   DEPLOY_KEY=~/.ssh/erdem_rpi_deploy.pem   (on Windows the file must be readable by you only)
#   CLOUDFLARED=<path>            default: cloudflared on PATH, else ~/Downloads/cloudflared*
#   GATEWAY_BIND=127.0.0.1:7218   where the gateway listens; point the tunnel at it
#   GATEWAY_MAX_INSTANCES=4       GATEWAY_TRUST_PROXY=true (a tunnel/proxy sits in front)
#   FCM_PROJECT=rovsti-598d7      FCM_SERVICE_ACCOUNT=~/.config/rovsti/wa-gateway/service-account.json
#   FORCE_CONFIG=1                replace /etc/wa-gateway/config.toml (kept otherwise)
#   BUILD_JOBS=3                  cargo jobs on the server (the box is shared)
#   SKIP_LOCAL_CHECK=1            skip the local `cargo metadata` check of the bundle
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_HOST="${DEPLOY_HOST:-ssh.erdemdev.tr}"
DEPLOY_USER="${DEPLOY_USER:-erdem}"
DEPLOY_KEY="${DEPLOY_KEY:-$HOME/.ssh/erdem_rpi_deploy.pem}"
CLOUDFLARED="${CLOUDFLARED:-}"
GATEWAY_BIND="${GATEWAY_BIND:-127.0.0.1:7218}"
GATEWAY_MAX_INSTANCES="${GATEWAY_MAX_INSTANCES:-4}"
GATEWAY_TRUST_PROXY="${GATEWAY_TRUST_PROXY:-true}"
FCM_PROJECT="${FCM_PROJECT:-rovsti-598d7}"
FCM_SERVICE_ACCOUNT="${FCM_SERVICE_ACCOUNT:-$HOME/.config/rovsti/wa-gateway/service-account.json}"
FORCE_CONFIG="${FORCE_CONFIG:-0}"
BUILD_JOBS="${BUILD_JOBS:-3}"
REMOTE_WORK="${REMOTE_WORK:-wa-gateway-build}"
SKIP_LOCAL_CHECK="${SKIP_LOCAL_CHECK:-0}"
LINES="${LINES:-100}"

# The workspace crates the gateway needs (its path dependencies, direct and indirect).
# `cargo metadata` on the bundle (below) fails if this list falls behind.
CRATES="rovsti_core rovsti_wa_proto rovsti_wa_client"

REMOTE_SCRIPT="$ROOT/services/wa_gateway/deploy/remote-install.sh"

say() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# ---- arguments ----------------------------------------------------------------------------

COMMAND=deploy
DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    deploy|status|logs|rollback) COMMAND="$arg" ;;
    --dry-run) DRY_RUN=1 ;;
    -h|--help) sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument '$arg' (try --help)" ;;
  esac
done

# Values go into a remote command line: keep them to plain characters.
for pair in "DEPLOY_HOST=$DEPLOY_HOST" "DEPLOY_USER=$DEPLOY_USER" "GATEWAY_BIND=$GATEWAY_BIND" \
            "FCM_PROJECT=$FCM_PROJECT" "REMOTE_WORK=$REMOTE_WORK" "BUILD_JOBS=$BUILD_JOBS" \
            "GATEWAY_MAX_INSTANCES=$GATEWAY_MAX_INSTANCES" "GATEWAY_TRUST_PROXY=$GATEWAY_TRUST_PROXY"; do
  case "${pair#*=}" in
    *[!A-Za-z0-9._:/-]*) die "${pair%%=*} has a character that is not allowed: ${pair#*=}" ;;
  esac
done

# ---- tools ----------------------------------------------------------------------------------

find_tools() {
  local windows=0
  case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) windows=1 ;; esac

  if [ "$windows" = 1 ]; then
    # Git Bash's own ssh rejects keys on Windows ACLs; the Windows OpenSSH client does not.
    SSH=/c/Windows/System32/OpenSSH/ssh.exe
    [ -x "$SSH" ] || die "Windows OpenSSH client not found at $SSH (Settings > Optional features)"
    export MSYS2_ARG_CONV_EXCL='*'   # do not rewrite /c/... style arguments for ssh.exe
    KEY_ARG="$(cygpath -w "$DEPLOY_KEY")"
  else
    SSH=ssh
    KEY_ARG="$DEPLOY_KEY"
  fi
  [ -f "$DEPLOY_KEY" ] || die "SSH key not found: $DEPLOY_KEY (set DEPLOY_KEY)"

  if [ -z "$CLOUDFLARED" ]; then
    local candidate
    for candidate in "$(command -v cloudflared 2>/dev/null || true)" \
                     "$HOME/Downloads/cloudflared-windows-amd64.exe" "$HOME/Downloads/cloudflared"; do
      if [ -n "$candidate" ] && [ -x "$candidate" ]; then CLOUDFLARED="$candidate"; break; fi
    done
  fi
  [ -n "$CLOUDFLARED" ] || die "cloudflared not found: install it or set CLOUDFLARED=/path/to/cloudflared"

  local cf="$CLOUDFLARED" proxy
  if [ "$windows" = 1 ]; then
    cf="$(cygpath -w "$CLOUDFLARED")"
    proxy="\"$cf\" access ssh --hostname $DEPLOY_HOST"
  else
    proxy="'$cf' access ssh --hostname $DEPLOY_HOST"
  fi
  SSH_OPTS=(-i "$KEY_ARG" -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=40
            -o ServerAliveInterval=30 -o StrictHostKeyChecking=accept-new
            -o "ProxyCommand=$proxy")
  TARGET="$DEPLOY_USER@$DEPLOY_HOST"
}

# Runs a command on the server. Output has its carriage returns removed.
ssh_run() { "$SSH" "${SSH_OPTS[@]}" "$TARGET" "$@" | tr -d '\r'; return "${PIPESTATUS[0]}"; }

# Sends a local file to a path under the remote home (stdin to `cat`, so no scp is needed).
send_file() {  # send_file <local file> <remote path relative to ~> [mode]
  local mode="${3:-0644}"
  "$SSH" "${SSH_OPTS[@]}" "$TARGET" "mkdir -p \"\$(dirname \"\$HOME/$2\")\" && umask 077 && cat > \"\$HOME/$2\" && chmod $mode \"\$HOME/$2\"" < "$1"
}

REMOTE_ENV=""
build_remote_env() {
  REMOTE_ENV="REMOTE_WORK=$REMOTE_WORK BUILD_JOBS=$BUILD_JOBS GATEWAY_BIND=$GATEWAY_BIND"
  REMOTE_ENV="$REMOTE_ENV GATEWAY_MAX_INSTANCES=$GATEWAY_MAX_INSTANCES GATEWAY_TRUST_PROXY=$GATEWAY_TRUST_PROXY"
  REMOTE_ENV="$REMOTE_ENV FCM_PROJECT=$FCM_PROJECT FORCE_CONFIG=$FORCE_CONFIG LINES=$LINES"
  if [ -f "$FCM_SERVICE_ACCOUNT" ]; then REMOTE_ENV="$REMOTE_ENV HAVE_SERVICE_ACCOUNT=1"; fi
}

# Runs one subcommand of remote-install.sh on the server.
remote() { ssh_run "$REMOTE_ENV bash \"\$HOME/$REMOTE_WORK/remote-install.sh\" $*"; }

push_script() { send_file "$REMOTE_SCRIPT" "$REMOTE_WORK/remote-install.sh" 0755; }

# ---- the source bundle --------------------------------------------------------------------

make_bundle() {  # make_bundle <dir>: writes <dir>/bundle.tgz
  local dir="$1" pkg="$1/pkg" members="\"services/wa_gateway\"" crate
  mkdir -p "$pkg/services" "$pkg/crates"
  cp -r "$ROOT/services/wa_gateway" "$pkg/services/"
  rm -rf "$pkg/services/wa_gateway/target"
  for crate in $CRATES; do
    [ -d "$ROOT/crates/$crate" ] || die "crates/$crate is missing"
    cp -r "$ROOT/crates/$crate" "$pkg/crates/"
    rm -rf "$pkg/crates/$crate/target"
    members="$members, \"crates/$crate\""
  done
  cp "$ROOT/Cargo.lock" "$pkg/Cargo.lock"
  # The workspace root, cut down to the gateway's crates. [patch.crates-io] holds git forks that
  # only the GPUI apps need; leaving it in would make cargo clone them on the server.
  awk -v members="$members" '
    /^members *=/         { print "members = [" members "]"; next }
    /^default-members *=/ { print "default-members = [\"services/wa_gateway\"]"; next }
    /^\[patch\.crates-io\]/ { skip = 1; next }
    /^\[/                 { skip = 0 }
    skip                  { next }
                          { print }
  ' "$ROOT/Cargo.toml" > "$pkg/Cargo.toml"

  if [ "$SKIP_LOCAL_CHECK" != 1 ]; then
    local cargo
    cargo="$(PATH="$HOME/.cargo/bin:$PATH" command -v cargo 2>/dev/null || true)"
    if [ -n "$cargo" ]; then
      say "checking the bundle resolves (cargo metadata)"
      (cd "$pkg" && "$cargo" metadata --format-version 1 >/dev/null 2>"$dir/metadata.err") \
        || { cat "$dir/metadata.err" >&2; die "the cut-down workspace does not resolve; update CRATES in this script"; }
    else
      say "no local cargo: skipping the bundle check"
    fi
  fi
  tar -czf "$dir/bundle.tgz" -C "$pkg" .
  say "bundle: $(du -h "$dir/bundle.tgz" | cut -f1)"
}

# ---- commands -------------------------------------------------------------------------------

cmd_deploy() {
  local tmp offset out status size body fails=0
  tmp="$(mktemp -d)"
  trap "rm -rf '$tmp'" EXIT
  make_bundle "$tmp"

  if [ "$DRY_RUN" = 1 ]; then
    say "dry run: would build on $TARGET, install /usr/local/bin/wa_gateway, listen on $GATEWAY_BIND"
    say "dry run: FCM project $FCM_PROJECT, service account $([ -f "$FCM_SERVICE_ACCOUNT" ] && echo "found" || echo "not found (push stays off)")"
    return
  fi

  say "uploading to $TARGET:~/$REMOTE_WORK"
  push_script
  send_file "$tmp/bundle.tgz" "$REMOTE_WORK/bundle.tgz"
  if [ -f "$FCM_SERVICE_ACCOUNT" ]; then
    send_file "$FCM_SERVICE_ACCOUNT" "$REMOTE_WORK/service-account.json" 0600
  fi

  remote build-start
  say "building on the server (a first build on a Raspberry Pi takes a while; Ctrl-C only stops watching)"
  offset=0
  while :; do
    if ! out="$(remote build-log "$offset")"; then
      fails=$((fails + 1))
      [ "$fails" -le 10 ] || die "lost the connection to $TARGET ten times in a row"
      printf '(connection hiccup %s, retrying)\n' "$fails"
      sleep 10
      continue
    fi
    fails=0
    status="$(printf '%s\n' "$out" | tail -n 1)"
    body="$(printf '%s\n' "$out" | sed '$d')"
    if [ -n "${body//[[:space:]]/}" ]; then printf '%s\n' "$body"; fi
    size="${status##*@@SIZE=}"
    status="${status#*@@STATUS=}"
    status="${status%% *}"
    offset="$size"
    case "$status" in
      OK) break ;;
      FAIL) die "the build failed; the log is $REMOTE_WORK/build.log on the server" ;;
      RUNNING) ;;
      *) die "unexpected build status '$status'" ;;
    esac
    sleep 20
  done

  say "installing"
  remote install
  cat <<EOF

The gateway is running on $DEPLOY_HOST at http://$GATEWAY_BIND (loopback only).
Next: in Cloudflare Zero Trust > Networks > Tunnels > (your tunnel) > Public hostname, send
https://rovsti.erdemdev.tr to  HTTP  localhost:${GATEWAY_BIND##*:}  and enter that address in the apps.
Check later with:  scripts/deploy-wa-gateway.sh status
EOF
}

find_tools
build_remote_env

say "connecting to $TARGET through $(basename "$CLOUDFLARED")"
ssh_run "echo connected as \$(whoami) on \$(uname -m)" || die "cannot reach $TARGET (is the key right? is cloudflared logged in?)"

case "$COMMAND" in
  deploy) cmd_deploy ;;
  status) push_script; remote status ;;
  logs) push_script; remote logs ;;
  rollback)
    [ "$DRY_RUN" = 1 ] && { say "dry run: would put the previous binary back"; exit 0; }
    push_script; remote rollback ;;
esac
