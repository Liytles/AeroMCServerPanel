#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

test -d target/classes || { echo "Önce Maven paketi oluşturulmalı." >&2; exit 1; }
TEST_HOME="$(mktemp -d)"
TEST_CLASSES="$(mktemp -d)"
cleanup() { rm -rf -- "$TEST_HOME" "$TEST_CLASSES"; }
trap cleanup EXIT

CLASSPATH="target/classes:target/package-input/lib/*"
javac -encoding UTF-8 -cp "$CLASSPATH" -d "$TEST_CLASSES" \
  work/HealthFeaturesSmoke.java \
  work/LanguageFeatureSmoke.java \
  work/MapParserSmoke.java \
  work/ModCenterSmoke.java \
  work/RemoteSmoke.java \
  work/SyncBackupSecuritySmoke.java

for TEST in \
  HealthFeaturesSmoke \
  LanguageFeatureSmoke \
  MapParserSmoke \
  ModCenterSmoke \
  SyncBackupSecuritySmoke; do
  java -Duser.home="$TEST_HOME" -cp "$TEST_CLASSES:$CLASSPATH" "com.aerogroup.mcpanel.$TEST"
done
java -Duser.home="$TEST_HOME" -cp "$TEST_CLASSES:$CLASSPATH" RemoteSmoke

echo "AeroMC çevrimdışı smoke testleri başarılı."
