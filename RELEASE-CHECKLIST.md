# AeroMC 3.0 release checklist

## Build

- [ ] `mvn clean package` succeeds with no compilation errors.
- [ ] Linux `.deb`, Windows `.exe`, and macOS `.dmg` workflow jobs succeed.
- [ ] Set a real public `AEROMC_MAINTAINER_EMAIL` for the Debian package.
- [ ] Package filename and application title show version 3.0.1.
- [ ] The packaged app starts without a separately installed Java runtime.

## Clean-machine tests

- [ ] Install, start, close, reopen, and uninstall on Windows 10/11 x64.
- [ ] Install, start, close, reopen, and uninstall on current Ubuntu/Debian x64.
- [ ] Install, start, close, reopen, and uninstall on current macOS.
- [ ] Verify Turkish/English, light/dark theme, local JAR, Exaroton, and Aternos.
- [ ] Verify paths containing spaces and Turkish characters.
- [ ] Verify crash logs appear under `.aeromc-panel/logs` without secrets.

## Security and release

- [ ] Confirm no API key, password, webhook, private server address, or backup is included.
- [ ] Test encrypted Exaroton key storage and wrong-password behavior.
- [ ] Test remote access with LAN disabled by default and no router port forwarding.
- [ ] Add Windows Authenticode signing and macOS Developer ID/notarization credentials.
- [ ] Scan final installers and publish SHA-256 checksums.
- [ ] Publish license, third-party notices, version notes, and known limitations.
