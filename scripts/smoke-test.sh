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
  work/AternosStatusSmoke.java \
  work/PterodactylApiSmoke.java \
  work/ExarotonReadinessSmoke.java \
  work/ExarotonCreditSmoke.java \
  work/ExarotonFleetSmoke.java \
  work/ExarotonAutomationSmoke.java \
  work/DiscordNotificationSmoke.java \
  work/DeviceCredentialSmoke.java \
  work/UpdateServiceSmoke.java \
  work/SparkAnalysisSmoke.java \
  work/CrisisHistorySmoke.java \
  work/SparkInstallerSmoke.java \
  work/WeeklyReportSmoke.java \
  work/NotificationCenterSmoke.java \
  work/DesktopNotifierSmoke.java \
  work/ServerAvailabilitySmoke.java \
  work/SmartInsightsSmoke.java \
  work/SecurityHardeningSmoke.java \
  work/JavaRuntimeSmoke.java \
  work/PreflightSmoke.java \
  work/LanguageFeatureSmoke.java \
  work/MapParserSmoke.java \
  work/ModCenterSmoke.java \
  work/RemoteSmoke.java \
  work/SyncBackupSecuritySmoke.java

for TEST in \
  AternosStatusSmoke \
  PterodactylApiSmoke \
  ExarotonReadinessSmoke \
  ExarotonCreditSmoke \
  ExarotonFleetSmoke \
  ExarotonAutomationSmoke \
  DiscordNotificationSmoke \
  DeviceCredentialSmoke \
  UpdateServiceSmoke \
  SparkAnalysisSmoke \
  CrisisHistorySmoke \
  SparkInstallerSmoke \
  WeeklyReportSmoke \
  NotificationCenterSmoke \
  DesktopNotifierSmoke \
  ServerAvailabilitySmoke \
  SmartInsightsSmoke \
  SecurityHardeningSmoke \
  JavaRuntimeSmoke \
  PreflightSmoke \
  HealthFeaturesSmoke \
  LanguageFeatureSmoke \
  MapParserSmoke \
  ModCenterSmoke \
  SyncBackupSecuritySmoke; do
  java -Duser.home="$TEST_HOME" -cp "$TEST_CLASSES:$CLASSPATH" "com.aerogroup.mcpanel.$TEST"
done
java -Duser.home="$TEST_HOME" -cp "$TEST_CLASSES:$CLASSPATH" RemoteSmoke

echo "AeroMC çevrimdışı smoke testleri başarılı."
