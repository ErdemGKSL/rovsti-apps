# Shared release-signing setup for scripts/build-apks.sh and scripts/build-release.sh.
# Source it; it never prints a password.
#
# The release key is the one the installed apps carry (first made on the Windows PC,
# 2026-11-03): $HOME/ownCloud/Personal/rovsti/{rovsti-release.jks,signing.env}.
# The apps' gpuiforge.json files point at that keystore.
#
# Overrides (environment):
#   ROVSTI_SIGNING_DIR  folder with rovsti-release.jks + signing.env
#                       (old Linux key: ROVSTI_SIGNING_DIR=$HOME/.config/rovsti)
#   ROVSTI_SIGNING_ENV  env file defining ANDROID_STORE_PASSWORD / ANDROID_KEY_PASSWORD
#   ROVSTI_KEYSTORE     keystore file      ROVSTI_KEY_ALIAS  key alias (default rovsti)
# When the selected keystore or alias differs from gpuiforge.json, the build uses a
# temporary copy of gpuiforge.json (.gpuiforge-signing.json, paths only, gitignored).

ROVSTI_SIGNING_DIR="${ROVSTI_SIGNING_DIR:-$HOME/ownCloud/Personal/rovsti}"
ROVSTI_SIGNING_ENV="${ROVSTI_SIGNING_ENV:-$ROVSTI_SIGNING_DIR/signing.env}"
[[ -r "$ROVSTI_SIGNING_ENV" ]] || {
  echo "Missing $ROVSTI_SIGNING_ENV (set ROVSTI_SIGNING_DIR or ROVSTI_SIGNING_ENV)" >&2
  exit 1
}
# The env file may set ROVSTI_KEYSTORE / ROVSTI_KEY_ALIAS itself (scripts/make-keystore.sh does).
# shellcheck disable=SC1090
source "$ROVSTI_SIGNING_ENV"
ROVSTI_KEYSTORE="${ROVSTI_KEYSTORE:-$ROVSTI_SIGNING_DIR/rovsti-release.jks}"
ROVSTI_KEY_ALIAS="${ROVSTI_KEY_ALIAS:-rovsti}"
[[ -r "$ROVSTI_KEYSTORE" ]] || { echo "Missing keystore $ROVSTI_KEYSTORE" >&2; exit 1; }
[[ -n "${ANDROID_STORE_PASSWORD:-}" && -n "${ANDROID_KEY_PASSWORD:-}" ]] || {
  echo "$ROVSTI_SIGNING_ENV must export ANDROID_STORE_PASSWORD and ANDROID_KEY_PASSWORD" >&2
  exit 1
}
export ANDROID_STORE_PASSWORD ANDROID_KEY_PASSWORD
echo "Signing with $ROVSTI_KEYSTORE (alias $ROVSTI_KEY_ALIAS)"

# Prints the config file name to pass to `gpuiforge --config` for the app in $1.
signing_config() {
  local dir="$1" keystore alias
  keystore="$(jq -r '.platforms.android.signing.keystore' "$dir/gpuiforge.json")"
  alias="$(jq -r '.platforms.android.signing."key-alias"' "$dir/gpuiforge.json")"
  if [[ "$keystore" == "$ROVSTI_KEYSTORE" && "$alias" == "$ROVSTI_KEY_ALIAS" ]]; then
    echo gpuiforge.json
    return
  fi
  jq --arg ks "$ROVSTI_KEYSTORE" --arg alias "$ROVSTI_KEY_ALIAS" \
    '.platforms.android.signing.keystore = $ks | .platforms.android.signing."key-alias" = $alias' \
    "$dir/gpuiforge.json" >"$dir/.gpuiforge-signing.json"
  echo .gpuiforge-signing.json
}
