#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$ROOT_DIR/scripts/prepare-package.sh"
VERSION="$(awk '/<version>/{line=$0; sub(/.*<version>/,"",line); sub(/<\\/version>.*/,"",line); print line; exit}' "$ROOT_DIR/pom.xml")"
test -n "$VERSION"

OUTPUT_DIR="$ROOT_DIR/release/macos"
if [[ "$OUTPUT_DIR" != "$ROOT_DIR/release/macos" ]]; then
  echo "Unsafe output path." >&2
  exit 1
fi
rm -rf -- "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

ICONSET_DIR="$ROOT_DIR/target/aeromc.iconset"
ICON_FILE="$ROOT_DIR/target/aeromc.icns"
mkdir -p "$ICONSET_DIR"
for SIZE in 16 32 128 256 512; do
  DOUBLE=$((SIZE * 2))
  sips -z "$SIZE" "$SIZE" "$ROOT_DIR/src/main/resources/icons/aeromc.png" --out "$ICONSET_DIR/icon_${SIZE}x${SIZE}.png" >/dev/null
  sips -z "$DOUBLE" "$DOUBLE" "$ROOT_DIR/src/main/resources/icons/aeromc.png" --out "$ICONSET_DIR/icon_${SIZE}x${SIZE}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET_DIR" -o "$ICON_FILE"

jpackage \
  --type dmg \
  --dest "$OUTPUT_DIR" \
  --input "$ROOT_DIR/target/package-input" \
  --name AeroMC \
  --app-version "$VERSION" \
  --vendor "The Aero Group" \
  --copyright "Copyright (c) 2026 The Aero Group" \
  --description "Cross-platform Minecraft server management panel" \
  --main-jar AeroMC.jar \
  --main-class com.aerogroup.mcpanel.Launcher \
  --icon "$ICON_FILE" \
  --java-options "-Dfile.encoding=UTF-8" \
  --license-file "$ROOT_DIR/LICENSE.txt" \
  --mac-package-identifier com.aerogroup.aeromc \
  --mac-package-name AeroMC

(cd "$OUTPUT_DIR" && shasum -a 256 *.dmg > SHA256SUMS.txt && for PACKAGE in *.dmg; do shasum -a 256 "$PACKAGE" > "$PACKAGE.sha256"; done)

echo "macOS package: $OUTPUT_DIR"
