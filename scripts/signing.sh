# Shared release-signing setup for scripts/build-apks.sh and scripts/build-release.sh.
# Source it; it never prints a password.
#
# The release key is the one the installed apps carry (first made on the Windows PC,
# 2026-11-03): $HOME/ownCloud/Personal/rovsti/{rovsti-release.jks,signing.env}.
# The apps' gpuiforge.json files point at that keystore.
#
# Overrides (environment):
#   ROSTY_SIGNING_DIR  folder with rovsti-release.jks + signing.env
#                       (old Linux key: ROSTY_SIGNING_DIR=$HOME/.config/rovsti)
#   ROSTY_SIGNING_ENV  env file defining ANDROID_STORE_PASSWORD / ANDROID_KEY_PASSWORD
#   ROSTY_KEYSTORE     keystore file      ROSTY_KEY_ALIAS  key alias (default rovsti)
# When the selected keystore or alias differs from gpuiforge.json, the build uses a
# temporary copy of gpuiforge.json (.gpuiforge-signing.json, paths only, gitignored).

ROSTY_SIGNING_DIR="${ROSTY_SIGNING_DIR:-$HOME/ownCloud/Personal/rovsti}"
ROSTY_SIGNING_ENV="${ROSTY_SIGNING_ENV:-$ROSTY_SIGNING_DIR/signing.env}"
[[ -r "$ROSTY_SIGNING_ENV" ]] || {
  echo "Missing $ROSTY_SIGNING_ENV (set ROSTY_SIGNING_DIR or ROSTY_SIGNING_ENV)" >&2
  exit 1
}
# The env file may set ROSTY_KEYSTORE / ROSTY_KEY_ALIAS itself (scripts/make-keystore.sh does).
# shellcheck disable=SC1090
source "$ROSTY_SIGNING_ENV"
ROSTY_KEYSTORE="${ROSTY_KEYSTORE:-$ROSTY_SIGNING_DIR/rovsti-release.jks}"
ROSTY_KEY_ALIAS="${ROSTY_KEY_ALIAS:-rovsti}"
[[ -r "$ROSTY_KEYSTORE" ]] || { echo "Missing keystore $ROSTY_KEYSTORE" >&2; exit 1; }
[[ -n "${ANDROID_STORE_PASSWORD:-}" && -n "${ANDROID_KEY_PASSWORD:-}" ]] || {
  echo "$ROSTY_SIGNING_ENV must export ANDROID_STORE_PASSWORD and ANDROID_KEY_PASSWORD" >&2
  exit 1
}
export ANDROID_STORE_PASSWORD ANDROID_KEY_PASSWORD
echo "Signing with $ROSTY_KEYSTORE (alias $ROSTY_KEY_ALIAS)"

# Prints the config file name to pass to `gpuiforge --config` for the app in $1.
signing_config() {
  local dir="$1" keystore alias
  keystore="$(jq -r '.platforms.android.signing.keystore' "$dir/gpuiforge.json")"
  alias="$(jq -r '.platforms.android.signing."key-alias"' "$dir/gpuiforge.json")"
  if [[ "$keystore" == "$ROSTY_KEYSTORE" && "$alias" == "$ROSTY_KEY_ALIAS" ]]; then
    echo gpuiforge.json
    return
  fi
  jq --arg ks "$ROSTY_KEYSTORE" --arg alias "$ROSTY_KEY_ALIAS" \
    '.platforms.android.signing.keystore = $ks | .platforms.android.signing."key-alias" = $alias' \
    "$dir/gpuiforge.json" >"$dir/.gpuiforge-signing.json"
  echo .gpuiforge-signing.json
}
