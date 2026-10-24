#!/usr/bin/env bash
# One-shot Android release: bump the version, test, deploy the server, then
# build and publish the APK so installed apps offer the update.
#
#   deploy/release-android.sh [options]
#
#   --minor | --major     bump x.Y.0 / X.0.0 instead of the default patch x.y.Z
#   --name 1.4.0          set versionName explicitly (versionCode is always +1)
#   --no-bump             publish the version already in android/gradle.properties
#   --notes "text"        release notes shown in the app (default: commit subjects
#                         since the previous release, up to 6 lines)
#   --skip-tests          skip the Go and Android test suites
#   --no-server           do not rebuild/restart the cc-harness service
#   --commit              commit the version bump (only android/gradle.properties)
#
# The server is deployed first because new app versions may rely on new API
# endpoints; restarting it stops running claude processes, and sessions resume
# on their next message. Nothing is pushed to git.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
props="$root/android/gradle.properties"
level=patch name="" bump=1 notes="" tests=1 server=1 commit=0
while [ $# -gt 0 ]; do
  case "$1" in
    --minor) level=minor ;;
    --major) level=major ;;
    --name) name="${2:?--name needs a value}"; shift ;;
    --no-bump) bump=0 ;;
    --notes) notes="${2:?--notes needs a value}"; shift ;;
    --skip-tests) tests=0 ;;
    --no-server) server=0 ;;
    --commit) commit=1 ;;
    -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

prop() { sed -n "s/^$1=//p" "$props" | head -1; }
code="$(prop appVersionCode)"; cur="$(prop appVersionName)"
[ -n "$code" ] && [ -n "$cur" ] || { echo "appVersionCode/appVersionName missing in $props" >&2; exit 1; }

backup="$(mktemp)"; cp "$props" "$backup"
trap 'rm -f "$backup"' EXIT
restore() { cp "$backup" "$props"; echo "release failed: version files restored to $cur ($code)" >&2; }

if [ "$bump" = 1 ]; then
  if [ -z "$name" ]; then
    IFS=. read -r maj min pat <<<"$cur"
    case "$level" in
      major) name="$((maj + 1)).0.0" ;;
      minor) name="$maj.$((min + 1)).0" ;;
      patch) name="$maj.$min.$((pat + 1))" ;;
    esac
  fi
  [[ "$name" =~ ^[0-9]+(\.[0-9]+){1,2}$ ]] || { echo "bad version name: $name" >&2; exit 2; }
  newcode=$((code + 1))
  trap 'restore; rm -f "$backup"' ERR
  sed -i "s/^appVersionCode=.*/appVersionCode=$newcode/; s/^appVersionName=.*/appVersionName=$name/" "$props"
  echo "version: $cur ($code) -> $name ($newcode)"
else
  name="$cur"; newcode="$code"
  trap 'rm -f "$backup"' EXIT
fi

if [ -z "$notes" ]; then
  dir="${HARNESS_RELEASES:-${HARNESS_DATA:-$HOME/.local/share/cc-harness}/releases}"
  since="$(sed -n 's/.*"publishedAt": *"\([^"]*\)".*/\1/p' "$dir/android.json" 2>/dev/null | head -1)"
  notes="$(git -C "$root" log ${since:+--since="$since"} --no-merges --format='- %s' -n 6 2>/dev/null || true)"
fi

if [ "$tests" = 1 ]; then
  echo "== tests"
  (cd "$root" && go vet ./... && go test -count=1 ./...)
  # shellcheck disable=SC1091
  (. "$root/android/env.sh" && cd "$root/android" && ./gradlew --offline --no-daemon -q :core:test :app:testDebugUnitTest)
fi

if [ "$server" = 1 ]; then
  echo "== deploy server"
  "$root/deploy/install.sh"
  sleep 2
  systemctl --user is-active --quiet cc-harness.service || { echo "cc-harness service is not running after deploy" >&2; false; }
fi

echo "== build and publish APK"
"$root/deploy/publish-android.sh" --notes "$notes"

if [ "$commit" = 1 ] && [ "$bump" = 1 ]; then
  git -C "$root" add android/gradle.properties
  git -C "$root" commit -q -m "Release Android $name ($newcode)" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>" -- android/gradle.properties
  echo "committed version bump"
fi
echo "done: Android $name ($newcode) is published; open the app and tap 检查更新 (or it checks on login)."
