#!/usr/bin/env bash
# Neue Version bauen und veröffentlichen.
#   ./release.sh "Was neu ist"
# Braucht den Release-Schlüssel in $WOLOF_SIGNING_DIR (Standard: /home/claude/signing).
set -euo pipefail
cd "$(dirname "$0")"
NOTES="${1:-Verbesserungen}"
REPO_RAW="https://raw.githubusercontent.com/wolfawidmer-sudo/wolof-health/main"
G=app/build.gradle.kts

code=$(grep -oP 'versionCode = \K[0-9]+' $G)
name=$(grep -oP 'versionName = "\K[^"]+' $G)
if [ "${BUMP:-1}" = "1" ]; then
  code=$((code + 1))
  major=${name%%.*}; minor=${name#*.}
  name="$major.$((minor + 1))"
  sed -i "s/versionCode = [0-9]*/versionCode = $code/; s/versionName = \"[^\"]*\"/versionName = \"$name\"/" $G
fi

gradle assembleRelease --no-daemon -q
apk=app/build/outputs/apk/release/app-release.apk
mkdir -p updates && rm -f updates/*.apk
cp "$apk" "updates/wolof-health-$code.apk"
sha=$(sha256sum "updates/wolof-health-$code.apk" | cut -d' ' -f1)
python3 - "$code" "$name" "$sha" "$NOTES" "$REPO_RAW" <<'PY'
import json, sys
code, name, sha, notes, raw = sys.argv[1:]
json.dump({"versionCode": int(code), "versionName": name, "sha256": sha, "notes": notes,
           "apk": f"{raw}/updates/wolof-health-{code}.apk"}, open("updates/version.json", "w"), ensure_ascii=False, indent=2)
PY
git add -A && git commit -qm "Version $name: $NOTES

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" && git push -q
echo "Veröffentlicht: $name ($code)"
