package com.aerogroup.mcpanel;

import com.google.gson.JsonParser;
import java.nio.file.*;

public final class UpdateServiceSmoke {
    public static void main(String[] args) throws Exception {
        String json = """
                {"tag_name":"v3.2.1","name":"AeroMC 3.2.1","body":"Güvenli güncelleme","draft":false,"prerelease":false,"assets":[
                  {"name":"AeroMC-3.2.1.exe","browser_download_url":"https://github.com/Liytles/AeroMCServerPanel/releases/download/v3.2.1/AeroMC-3.2.1.exe","size":120},
                  {"name":"AeroMC-3.2.1.exe.sha256","browser_download_url":"https://github.com/Liytles/AeroMCServerPanel/releases/download/v3.2.1/AeroMC-3.2.1.exe.sha256","size":90},
                  {"name":"aeromc_3.2.1_amd64.deb","browser_download_url":"https://github.com/Liytles/AeroMCServerPanel/releases/download/v3.2.1/aeromc_3.2.1_amd64.deb","size":130},
                  {"name":"aeromc_3.2.1_amd64.deb.sha256","browser_download_url":"https://github.com/Liytles/AeroMCServerPanel/releases/download/v3.2.1/aeromc_3.2.1_amd64.deb.sha256","size":90}
                ]}
                """;
        var release = UpdateService.parseRelease(JsonParser.parseString(json).getAsJsonObject(), "windows", "x64");
        require("3.2.1".equals(release.version()), "tag normalized");
        require(release.installer().name().endsWith(".exe"), "Windows installer selected");
        require(release.checksum().name().equals(release.installer().name() + ".sha256"), "matching checksum selected");
        require(release.isNewerThan("3.1.9"), "newer release detected");
        require(UpdateService.compareVersions("3.2.0", "3.2.0-beta.2") > 0, "stable newer than beta");
        require(UpdateService.compareVersions("3.2.0-beta.10", "3.2.0-beta.2") > 0, "numeric prerelease ordering");
        String hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        require(hash.equals(UpdateService.expectedChecksum(hash + "  AeroMC-3.2.1.exe", "AeroMC-3.2.1.exe")), "checksum parsed");
        boolean unrelatedHashRejected = false; try { UpdateService.expectedChecksum(hash + "  other.exe", "AeroMC-3.2.1.exe"); } catch (java.io.IOException expected) { unrelatedHashRejected = true; }
        require(unrelatedHashRejected, "checksum for unrelated asset rejected");
        Path file = Files.createTempFile("aeromc-update-hash-", ".bin"); Files.writeString(file, "abc");
        require(hash.equals(UpdateService.sha256(file)), "SHA-256 calculated");
        require(UpdateService.selectInstaller(java.util.List.of(release.installer()), "other", "x64") == null, "unsupported OS rejected");
        Path windowsInstaller = Files.createTempFile("AeroMC-update-", ".exe"); Files.writeString(windowsInstaller, "test");
        var launch = InstallerLauncher.commandFor(windowsInstaller, "windows");
        require("rundll32.exe".equals(launch.get(0)) && launch.get(2).equals(windowsInstaller.toAbsolutePath().normalize().toString()), "safe Windows installer launch plan");
        boolean wrongPlatformRejected = false;
        try { InstallerLauncher.commandFor(windowsInstaller, "macos"); } catch (java.io.IOException expected) { wrongPlatformRejected = true; }
        require(wrongPlatformRejected, "wrong installer extension rejected");
        System.out.println("application-update-service-ok");
    }
    private static void require(boolean condition, String feature) { if (!condition) throw new IllegalStateException("Smoke test failed: " + feature); }
}
