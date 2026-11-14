#!/usr/bin/env bash
# Creates a NEW release signing key for the Rosty apps (one key signs all four).
# The apps already on the phone are signed with ~/ownCloud/Personal/rovsti (see
# scripts/signing.sh); a new key cannot update them, so only use this for a fresh
# setup. The keystore and passwords stay outside the repo.
set -euo pipefail

DIR="${ROSTY_SIGNING_DIR:-$HOME/ownCloud/Personal/rosty}"
KEYSTORE="$DIR/rosty-release.jks"
ENV_FILE="$DIR/signing.env"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
KEYTOOL="$JAVA_HOME/bin/keytool"
[[ -x "$KEYTOOL" ]] || KEYTOOL="$(command -v keytool)"

if [[ -e "$KEYSTORE" ]]; then
  echo "Keystore already exists: $KEYSTORE" >&2
  echo "Delete it yourself if you really want a new one (apps signed with the old key could not be updated)." >&2
  exit 1
fi

read -r -p "Your name (or nickname): " CN
read -r -p "Organisation [Rosty]: " ORG; ORG="${ORG:-Rosty}"
read -r -p "Country code, 2 letters [TR]: " COUNTRY; COUNTRY="${COUNTRY:-TR}"
read -r -p "Key alias [rosty]: " ALIAS; ALIAS="${ALIAS:-rosty}"
while :; do
  read -r -s -p "Keystore password (min 6 chars): " PASS; echo
  read -r -s -p "Repeat password: " PASS2; echo
  [[ "$PASS" == "$PASS2" && ${#PASS} -ge 6 ]] && break
  echo "Passwords differ or are too short, try again."
done

[[ "$PASS" != *[\'\"\\]* ]] || { echo "Password must not contain quotes or backslashes." >&2; exit 1; }
umask 077
mkdir -p "$DIR"; chmod 700 "$DIR"
# Passwords go through the environment, not argv, so they never show in ps.
export ROSTY_KS_PASS="$PASS"
"$KEYTOOL" -genkeypair -v -keystore "$KEYSTORE" -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass:env ROSTY_KS_PASS -keypass:env ROSTY_KS_PASS \
  -dname "CN=$CN, O=$ORG, C=$COUNTRY"

cat > "$ENV_FILE" <<ENV
export ANDROID_STORE_PASSWORD='$PASS'
export ANDROID_KEY_PASSWORD='$PASS'
export ROSTY_KEYSTORE='$KEYSTORE'
export ROSTY_KEY_ALIAS='$ALIAS'
ENV
chmod 600 "$KEYSTORE" "$ENV_FILE"
echo
echo "Done."
echo "  keystore: $KEYSTORE"
echo "  passwords: $ENV_FILE (chmod 600)"
echo "BACK UP the keystore: if you lose it you can't update installed apps."
