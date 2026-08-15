#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$ROOT_DIR/scripts/prepare-package.sh"
VERSION="$(awk '/<version>/{line=$0; sub(/.*<version>/,"",line); sub(/<\\/version>.*/,"",line); print line; exit}' "$ROOT_DIR/pom.xml")"
test -n "$VERSION"
MAINTAINER_EMAIL="${AEROMC_MAINTAINER_EMAIL:-aeromc@localhost}"

OUTPUT_DIR="$ROOT_DIR/release/linux"
if [[ "$OUTPUT_DIR" != "$ROOT_DIR/release/linux" ]]; then
  echo "Güvensiz çıktı yolu." >&2
  exit 1
fi
rm -rf -- "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

jpackage \
  --type deb \
  --dest "$OUTPUT_DIR" \
  --input "$ROOT_DIR/target/package-input" \
  --name AeroMC \
  --app-version "$VERSION" \
  --vendor "The Aero Group" \
  --copyright "Copyright (c) 2026 The Aero Group" \
  --description "Cross-platform Minecraft server management panel" \
  --main-jar AeroMC.jar \
  --main-class com.aerogroup.mcpanel.Launcher \
  --icon "$ROOT_DIR/src/main/resources/icons/aeromc.png" \
  --java-options "-Dfile.encoding=UTF-8" \
  --license-file "$ROOT_DIR/LICENSE.txt" \
  --linux-package-name aeromc \
  --linux-deb-maintainer "$MAINTAINER_EMAIL" \
  --linux-menu-group Game \
  --linux-app-category Game \
  --linux-shortcut

(cd "$OUTPUT_DIR" && sha256sum *.deb > SHA256SUMS.txt)

echo "Linux paketi: $OUTPUT_DIR"
