package com.aerogroup.mcpanel;

import java.nio.file.*;
import java.util.List;

public final class LiveModrinthUpdateSmoke {
    public static void main(String[] args) throws Exception {
        ModrinthService api = new ModrinthService();
        var projects = api.search("Fabric API", "1.21.1", "fabric", "mod");
        require(!projects.isEmpty(), "live search");
        var resolution = api.resolve(projects.get(0).id(), "1.21.1", "fabric");
        require(!resolution.files().isEmpty(), "live version resolution");
        var file = resolution.files().get(resolution.files().size() - 1);
        Path temporary = Files.createTempDirectory("aeromc-live-modrinth-");
        try {
            Path downloaded = api.downloadVerified(file, temporary);
            String hash = ModrinthService.sha512(downloaded);
            var match = api.checkUpdates(List.of(hash), "1.21.1", "fabric").get(hash);
            require(match != null && match.current() != null, "hash recognition");
            require(match.latest() != null && match.latest().primaryFile() != null, "compatible latest version");
            System.out.println("live-modrinth-update-ok: " + file.filename());
        } finally { ModInstallManager.deleteTree(temporary); }
    }

    private static void require(boolean value, String name) { if (!value) throw new IllegalStateException("Live smoke failed: " + name); }
}
