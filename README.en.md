# AeroMC Server Panel

[Türkçe](README.md) · **English**

> Current development release: **3.0.3** · [Release notes in English and Turkish](release-notes/v3.0.3.md)

AeroMC is a JavaFX desktop application for managing Minecraft servers through three provider modes:

- **Local JAR:** start/stop, live console, online players, scheduled tasks, and ZIP backups.
- **Exaroton:** list and control servers through the official API, follow the live console and players, protect credits, and automate server operation.
- **Aternos:** read public online state, version, player count, and latency with SRV and custom-port support; remember addresses and open the official panel for management.

Aternos browser automation is deliberately not used because it would violate the service rules. Exaroton API keys and Discord webhooks stay in memory by default. Optional vault modes encrypt them with AES-256-GCM either from a user-supplied master password or a device/user-bound secret. Master passwords are never saved.

## Highlights

- Turkish and English interface with a persistent language preference
- First-launch feature tour and a persistent Notification Center
- Local, Exaroton, and Aternos server views organized under one Servers area
- Health score, Crisis Mode, Crash Doctor, incident chains, and smart threshold suggestions
- One-click Spark lag analysis for Paper 1.21+ servers
- Exaroton readiness checks, fleet view, credit protection, schedules, budgets, and crash recovery
- Discord embeds, event filters, role mentions, retry handling, and encrypted webhook storage
- One-click Modrinth installation, dependency resolution, SHA-512 verification, update scanning, and rollback
- Cross-platform update center with GitHub Releases and SHA-256 package verification
- Security Shield, command risk classification, path/symlink protection, crash-loop protection, and a hardened remote-control page

## Setup and server templates

The one-click server wizard downloads Paper, Fabric, or Vanilla only from their official sources. It provides Survival, SMP, Creative, SkyBlock starter, and modded-server templates, carries RAM settings into launch scripts, and checks the required Java version.

The wizard links directly to the current official [Minecraft EULA](https://aka.ms/MinecraftEULA) and [Minecraft Usage Guidelines](https://www.minecraft.net/usage-guidelines). AeroMC writes `eula=true` only after the user explicitly checks the acceptance box. The EULA text is not embedded as a stale copy in the application.

Before a local server starts, **Preflight Check** inspects the selected JAR, Java runtime, EULA state, port, RAM, free disk space, backups, and mod/plugin files. Critical findings block startup. Fixable EULA state still requires explicit user acceptance.

## Notification Center

The Dashboard combines crash, credit, backup, update, player, performance, and automation events in one persistent center.

- Master enable/disable and collapse controls
- Read/unread state, severity filters, and safe history clearing
- Per-server source selection and per-event rules
- Quiet hours that may cross midnight
- Duplicate-notification cooldown and platform test notification
- One-hour summary for crashes, joins, shutdowns, and performance warnings
- Two-result online/offline confirmation to prevent false state changes after one failed ping

On Linux, desktop notifications use `notify-send` without mixing AWT SystemTray with JavaFX/GDK. Windows and macOS load their tray integration only when required.

## Control Center and server health

- Live 0–100 health score based on TPS, RAM, CPU, latency, overload warnings, and crashes
- Time-based Crisis Mode with configurable TPS/RAM thresholds, trigger duration, recovery duration, and cooldown
- Recovery hysteresis (+1 TPS and -5% RAM), task pausing, temporary safe settings, and controlled restoration
- Smart threshold suggestions based on seven days of sparse performance history and applied only after user approval
- Crash Doctor analysis of the last 300 console lines for memory, port, Java, mod, plugin, and watchdog failures
- Incident chains that group the final 10 minutes of TPS, RAM, CPU, console symptoms, and diagnosis
- Crash Loop Shield that blocks automatic restart for 15 minutes after the third local crash in five minutes
- One-click Spark profiling with Quick, Normal, and Detailed durations and automatic capture of verified `spark.lucko.me` reports
- Player achievement cards built from play time, joins, deaths, and advancements

## Exaroton management

- Official API status, address, software/version, RAM, players, console, and account credits
- Readiness check with a strict 12-second timeout, approved bypass on failure, and a complete Settings toggle
- Cost calculated from the official `1 credit / GiB / hour` rate for the selected server
- Persistent credit history, account-spending observation, remaining-time estimate, and low-credit alerts
- Optional threshold disable, threshold-triggered stop, and player-safe automatic stop
- Fleet dashboard showing total online/crashed servers, players, allocated RAM, and hourly cost
- Weekday/weekend schedules, midnight-crossing windows, bounded crash recovery, and no-player stop
- Daily and weekly credit budgets that prevent automatic restarts until the budget resets
- Local automation log and a master switch to disable all Exaroton automations

## Mods, plugins, files, and worlds

The One-Click Mod Center searches server-compatible Modrinth projects for Fabric, Forge, NeoForge, Quilt, Paper, Purpur, Spigot, and Bukkit.

- Required dependencies are resolved automatically.
- Every downloaded JAR is checked with its Modrinth SHA-512 hash and expected size.
- Local `mods`/`plugins` folders receive a server-specific safety backup before changes.
- Installed JARs are identified by hash, including files installed outside AeroMC.
- Duplicate IDs, missing dependencies, incompatible projects, loader/version conflicts, and filename collisions are detected before changes.
- Critical conflicts block automatic updates; rollback restores local or Exaroton content where the provider API permits it.

The safe file editor limits editing to known server configuration files, creates `.bak` copies, and saves atomically. World ZIP restoration uses a staging folder, path-containment checks, a 100,000-entry limit, and a 20 GiB decompressed-size limit before replacing an existing world.

## Remote access

Create a user under **Tools → Remote Access**, choose the `VIEWER`, `MODERATOR`, or `ADMIN` role, and start the service. It binds to `127.0.0.1` by default. Enable LAN access only for a trusted private network and never port-forward its local HTTP port to the internet.

- Passwords use PBKDF2-HMAC-SHA256 hashes.
- Failed logins and actions are rate-limited and recorded without secrets.
- POST actions require CSRF tokens and have a 4 KiB body limit and 30-action-per-minute IP limit.
- The page uses a nonce-based Content Security Policy, anti-framing headers, no-store caching, and no-referrer policy.
- Generic remote console input permits only low-risk commands; sensitive management actions use dedicated role-checked endpoints.

## Security Shield

**Settings → AeroMC Security Shield** scores local data permissions, credential vault state, updater settings, server JAR paths, and remote-access artifacts. On POSIX systems it can restrict the data directory to `700` and sensitive files to `600` with one action.

All local server, backup, file, and world operations share canonical path validation. Parent traversal and symbolic-link escape are blocked. Critical desktop console commands require a second confirmation. Secrets are not placed back into visible text fields when automatic vault use is enabled, and copy/cut, context-menu, and drag extraction are disabled on secret fields.

These protections reduce accidental disclosure and common local attacks; they cannot provide absolute protection against malware already running with the same operating-system user permissions.

## Update Center

**Settings → AeroMC Update Center** checks the fixed public `Liytles/AeroMCServerPanel` GitHub Releases source and supports Stable or Beta channels. It selects the correct `.exe`, `.deb`, or `.dmg`, displays progress and release notes, and opens an installer only after its matching `.sha256` file verifies the package. File size and SHA-256 are checked again immediately before launch.

Installer opening runs outside the JavaFX UI task through Windows `rundll32`, macOS `open`, or Linux `xdg-open`/`gio`. AeroMC does not close itself automatically, and updates do not modify `.aeromc-panel`, credential vaults, or Minecraft server folders. See [INSTALLATION-LAYOUT.md](INSTALLATION-LAYOUT.md) for every installation and data location.

## Running from source

Requirements: Java 17+ and Maven.

```bash
mvn javafx:run
```

On first launch, choose a local `server.jar`, Paper server JAR, or Fabric server JAR. Local controls require the server process to be running. Exaroton controls require an account connection and selected server.

## Installers

Release packages include their own Java runtime; end users do not need to install Java or Maven.

- **Windows 10/11:** run `AeroMC-3.0.3.exe`.
- **Ubuntu/Debian:** open `aeromc_3.0.3_amd64.deb` or install it with a package manager.
- **macOS:** open `AeroMC-3.0.3.dmg` and move AeroMC to Applications.

Early packages are unsigned and may trigger Windows SmartScreen or macOS Gatekeeper publisher warnings. Source code is publicly readable for inspection, but the project is distributed under an all-rights-reserved license. Copying, modifying, or redistributing it requires separate written permission; see [LICENSE.txt](LICENSE.txt).

The installer displays the current bilingual [INSTALLER-EULA.txt](INSTALLER-EULA.txt). It does not replace the Minecraft EULA, which must be read and accepted separately when preparing a Minecraft server.

Build packages locally with:

```bash
# Ubuntu/Debian
./scripts/package-linux.sh

# macOS
./scripts/package-macos.sh
```

```powershell
# Windows PowerShell
.\scripts\package-windows.ps1
```

Packaging requires a full JDK 21+, Maven, `fakeroot` on Linux, and WiX Toolset 3 on Windows. The GitHub workflow builds all three operating-system packages independently and publishes matching SHA-256 files for version tags.

Before publishing, review [RELEASE-CHECKLIST.md](RELEASE-CHECKLIST.md), [LICENSE.txt](LICENSE.txt), [INSTALLER-EULA.txt](INSTALLER-EULA.txt), and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## Safe backups

When the local server is running, AeroMC sends `save-off` and `save-all flush`, writes world data into a ZIP under the server-specific `backups/` directory, and then restores saving with `save-on`.
