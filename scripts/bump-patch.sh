#!/usr/bin/env bash
#
# Bumps the app's versionName patch component and versionCode by 1.
#
# Used in two ways:
#
#   1. Locally:   ./scripts/bump-patch.sh
#                 Updates app/build.gradle.kts in place. Commit yourself.
#
#   2. CI (build-release.yml on push to main):
#                 Runs at the very start of the build job so the
#                 produced APK already carries the bumped version,
#                 then commits the bump back to main with [skip ci]
#                 so the build workflow does NOT re-trigger.
#
# If $GITHUB_OUTPUT is set (running inside GitHub Actions) the new
# versionName and versionCode are exported so subsequent steps can
# reference them via steps.<id>.outputs.new_version / new_code.

set -euo pipefail

FILE="app/build.gradle.kts"
if [[ ! -f "$FILE" ]]; then
    echo "ERROR: $FILE not found. Run from the repo root." >&2
    exit 1
fi

old_name=$(grep -oP 'versionName = "\K[^"]+' "$FILE")
old_code=$(grep -oP 'versionCode = \K\d+' "$FILE")

if [[ -z "$old_name" || -z "$old_code" ]]; then
    echo "ERROR: could not parse current versionName / versionCode in $FILE" >&2
    exit 2
fi

IFS='.' read -r maj min pat <<< "$old_name"
if [[ -z "${maj:-}" || -z "${min:-}" || -z "${pat:-}" ]]; then
    echo "ERROR: versionName '$old_name' is not in MAJOR.MINOR.PATCH form" >&2
    exit 3
fi

new_pat=$((pat + 1))
new_code=$((old_code + 1))
new_name="${maj}.${min}.${new_pat}"

# Use a temp file so we don't risk a half-written gradle.kts on disk
# if sed gets killed mid-write.
tmp=$(mktemp)
sed -E \
    -e "s/versionName = \"${old_name}\"/versionName = \"${new_name}\"/" \
    -e "s/versionCode = ${old_code}/versionCode = ${new_code}/" \
    "$FILE" > "$tmp"
mv "$tmp" "$FILE"

echo "Bumped: ${old_name} (code ${old_code}) -> ${new_name} (code ${new_code})"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    {
        echo "new_version=${new_name}"
        echo "new_code=${new_code}"
        echo "old_version=${old_name}"
    } >> "$GITHUB_OUTPUT"
fi
