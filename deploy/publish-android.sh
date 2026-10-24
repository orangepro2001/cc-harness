#!/usr/bin/env bash
# Builds the Android APK and publishes it to the server's releases directory so
# installed apps can find it (GET /api/client/android/latest).
#
#   deploy/publish-android.sh [--no-build] [--notes "what changed"]
#
# versionCode/versionName come from android/gradle.properties; bump
# appVersionCode there for every release (installed apps only update when the
# published versionCode is higher). Every release must be signed with the same
# key, otherwise Android refuses the update: keep ~/.config/cc-harness/cc-harness-release.jks
# (and android-signing.properties next to it) and back them up.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
build=1
notes=""
while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) build=0 ;;
    --notes) notes="${2:?--notes needs a value}"; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

# shellcheck disable=SC1091
. "$root/android/env.sh"

if [ "$build" = 1 ]; then
  (cd "$root/android" && ./gradlew --no-daemon -q :app:assembleRelease)
fi
apk="$root/android/app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$apk" ]; then
  [ -f "$root/android/app/build/outputs/apk/release/app-release-unsigned.apk" ] \
    && echo "release APK is unsigned: create ~/.config/cc-harness/android-signing.properties (see README)" >&2 \
    || echo "APK not found: $apk" >&2
  exit 1
fi
apksigner="$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -1)"
cert="$("$apksigner" verify --print-certs "$apk" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)"
[ -n "$cert" ] || { echo "APK signature does not verify" >&2; exit 1; }
# Every release must carry the same signing certificate, otherwise installed apps cannot update.
pinned="${HARNESS_DATA:-$HOME/.local/share/cc-harness}/android-signer.sha256"
if [ -f "$pinned" ] && [ "$(cat "$pinned")" != "$cert" ]; then
  echo "signing certificate changed (expected $(cat "$pinned")): installed apps would refuse this update" >&2
  exit 1
fi
mkdir -p "$(dirname "$pinned")" && echo "$cert" > "$pinned"

aapt2="$(ls -d "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)"
badging="$("$aapt2" dump badging "$apk")"
code="$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging" | head -1)"
name="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
[ -n "$code" ] && [ -n "$name" ] || { echo "cannot read version from the APK" >&2; exit 1; }

dir="${HARNESS_RELEASES:-${HARNESS_DATA:-$HOME/.local/share/cc-harness}/releases}"
mkdir -p "$dir"
if [ -f "$dir/android.json" ]; then
  prev="$(sed -n 's/.*"versionCode": *\([0-9]*\).*/\1/p' "$dir/android.json" | head -1)"
  if [ -n "$prev" ] && [ "$code" -le "$prev" ]; then
    echo "versionCode $code is not higher than the published $prev; bump appVersionCode in android/gradle.properties" >&2
    exit 1
  fi
fi

file="cc-harness-$name-$code.apk"
sum="$(sha256sum "$apk" | cut -d' ' -f1)"
cp "$apk" "$dir/$file.tmp" && mv "$dir/$file.tmp" "$dir/$file"
python3 - "$dir/android.json" "$code" "$name" "$sum" "$file" "$notes" <<'PY'
import json, os, sys, datetime
path, code, name, sha, file, notes = sys.argv[1:]
doc = {"versionCode": int(code), "versionName": name, "sha256": sha, "file": file,
       "notes": notes, "publishedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")}
tmp = path + ".tmp"
with open(tmp, "w") as f:
    json.dump(doc, f, indent=2)
os.replace(tmp, path)
PY
echo "signer sha256=$cert"
echo "published $name ($code) -> $dir/$file  sha256=$sum"
