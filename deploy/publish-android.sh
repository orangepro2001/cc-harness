#!/usr/bin/env bash
# Builds the Android APK and publishes it to the server's releases directory so
# installed apps can find it (GET /api/client/android/latest).
#
#   deploy/publish-android.sh [--no-build] [--notes "what changed"]
#
# versionCode/versionName come from android/gradle.properties; bump
# appVersionCode there for every release (installed apps only update when the
# published versionCode is higher). Every release must be signed with the same
# key, otherwise Android refuses the update: keep ~/.android/debug.keystore.
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
  (cd "$root/android" && ./gradlew --no-daemon -q :app:assembleDebug)
fi
apk="$root/android/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$apk" ] || { echo "APK not found: $apk" >&2; exit 1; }

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
echo "published $name ($code) -> $dir/$file  sha256=$sum"
