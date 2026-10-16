#!/usr/bin/env bash
# Build the fork's macOS app: the official CrossPaste release with this branch's patched
# classes layered in, Sparkle auto-update switched off, re-signed with the fork's fixed
# self-signed certificate.
#
#   ry/mac/package.sh <official-mac-zip> <built-app-desktop.jar> <out-dir>
#
# Environment:
#   RY_SIGN_KEYCHAIN   keychain holding the signing identity (default: search list)
#   RY_GIT_COMMIT      commit recorded in Info.plist (default: git rev-parse HEAD)
#
# Version and base release come from ry/fork.env. Output: <out-dir>/<zip> and <zip>.sha256.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=../fork.env
. "$here/../fork.env"

official_zip="$1"
built_jar="$2"
out_dir="$3"

fork_version="${UPSTREAM_TAG}-ry.${FORK_REV}"
short_version="${UPSTREAM_VERSION}-ry.${FORK_REV}"
commit="${RY_GIT_COMMIT:-$(git -C "$here" rev-parse HEAD)}"
cert="$here/codesign-cert.pem"
identity="$(openssl x509 -in "$cert" -noout -fingerprint -sha1 | sed 's/.*=//; s/://g')"
asset="crosspaste-${fork_version}-mac-aarch64.zip"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
plist() { /usr/libexec/PlistBuddy "$@" "$work/CrossPaste.app/Contents/Info.plist"; }

echo "== unpack official $UPSTREAM_TAG"
ditto -x -k "$official_zip" "$work"
app="$work/CrossPaste.app"
codesign --verify --deep --strict "$app"
got="$(plist -c 'Print :CFBundleVersion')"
[ "$got" = "$UPSTREAM_TAG" ] || { echo "official app is $got, ry/fork.env expects $UPSTREAM_TAG"; exit 1; }
[ "$(plist -c 'Print :CFBundleIdentifier')" = "com.crosspaste.mac" ] || { echo "unexpected bundle id"; exit 1; }

echo "== overlay patched classes"
jar="$app/Contents/Resources/app-desktop-${UPSTREAM_VERSION}.jar"
[ -f "$jar" ] || { echo "missing $jar"; exit 1; }
python3 "$here/overlay.py" "$jar" "$built_jar" "$here/overlay-classes.txt" "$work/app-desktop.jar"
mv "$work/app-desktop.jar" "$jar"

echo "== Info.plist: fork version, auto-update off"
plist -c "Set :CFBundleShortVersionString $short_version"
plist -c "Add :RyForkVersion string $fork_version"
plist -c "Add :RyForkCommit string $commit"
# Sparkle would otherwise replace the fork with the official build on the next release.
# The feed points at a closed local port, so even a manual "check for updates" goes nowhere.
plist -c "Set :SUFeedURL https://127.0.0.1:9/omnicraft-updates-disabled.rss"
for k in SUEnableAutomaticChecks SUAutomaticallyUpdate SUAllowsAutomaticUpdates; do
  plist -c "Set :$k false"
done

echo "== sign with $identity"
# Only the outer bundle is re-signed. Nested code (JBR, Sparkle, native libs) keeps its
# official signature; without hardened runtime there is no library validation to trip on.
sign_args=(--force --sign "$identity" --timestamp=none)
[ -n "${RY_SIGN_KEYCHAIN:-}" ] && sign_args+=(--keychain "$RY_SIGN_KEYCHAIN")
codesign "${sign_args[@]}" "$app"
codesign --verify --deep --strict --verbose=1 "$app"
req="$(codesign -d -r- "$app" 2>&1 | grep '^designated')"
echo "$req"
want_hash="$(echo "$identity" | tr 'A-F' 'a-f')"
case "$req" in
  *"identifier \"com.crosspaste.mac\""*"H\"$want_hash\""*) ;;
  *) echo "designated requirement does not pin the fork certificate"; exit 1 ;;
esac

echo "== package"
mkdir -p "$out_dir"
(cd "$work" && ditto -c -k --sequesterRsrc --keepParent CrossPaste.app "$out_dir/$asset")
(cd "$out_dir" && shasum -a 256 "$asset" > "$asset.sha256")
cat "$out_dir/$asset.sha256"
echo "fork_version=$fork_version"
