#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

command -v java >/dev/null 2>&1 || { echo "Java bulunamadı (JDK 21 veya üzeri gerekli)." >&2; exit 1; }
command -v mvn >/dev/null 2>&1 || { echo "Maven bulunamadı." >&2; exit 1; }
command -v jpackage >/dev/null 2>&1 || { echo "jpackage bulunamadı (tam JDK gerekli)." >&2; exit 1; }

JAVA_VERSION="$(java -version 2>&1 | awk -F '"' '/version/ { print $2; exit }')"
JAVA_MAJOR="${JAVA_VERSION%%.*}"
if [[ "$JAVA_MAJOR" == "1" ]]; then JAVA_MAJOR="$(printf '%s' "$JAVA_VERSION" | cut -d. -f2)"; fi
[[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] && (( JAVA_MAJOR >= 21 )) || { echo "Dağıtım paketi için tam JDK 21 veya üzeri gerekli (bulunan: ${JAVA_VERSION:-bilinmiyor})." >&2; exit 1; }

MAVEN_COMMAND=(mvn -B)
if [[ -n "${AEROMC_MAVEN_REPO:-}" ]]; then
  MAVEN_COMMAND+=("-Dmaven.repo.local=$AEROMC_MAVEN_REPO")
fi
"${MAVEN_COMMAND[@]}" clean package
install -m 0644 "$ROOT_DIR/target/AeroMC.jar" "$ROOT_DIR/target/package-input/AeroMC.jar"
install -m 0644 "$ROOT_DIR/LICENSE.txt" "$ROOT_DIR/target/package-input/LICENSE.txt"
install -m 0644 "$ROOT_DIR/THIRD-PARTY-NOTICES.md" "$ROOT_DIR/target/package-input/THIRD-PARTY-NOTICES.md"

test -f "$ROOT_DIR/target/package-input/AeroMC.jar"
test -d "$ROOT_DIR/target/package-input/lib"
