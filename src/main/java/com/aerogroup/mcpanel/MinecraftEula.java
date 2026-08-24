package com.aerogroup.mcpanel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Minecraft EULA bağlantısını ve açık kullanıcı kabulünün eula.txt kaydını tek merkezde tutar. */
public final class MinecraftEula {
    public static final String EULA_URL = "https://aka.ms/MinecraftEULA";
    public static final String USAGE_GUIDELINES_URL = "https://www.minecraft.net/usage-guidelines";

    private MinecraftEula() { }

    public static void writeAccepted(Path serverFolder) throws IOException {
        if (serverFolder == null) throw new IOException("Sunucu klasörü bulunamadı.");
        Path target = SafePathGuard.resolve(serverFolder, "eula.txt", true);
        String content = "# AeroMC: Kullanıcı güncel Minecraft EULA koşullarını açıkça kabul etti.\n"
                + "# EULA: " + EULA_URL + "\n"
                + "# Kullanım Kuralları / Usage Guidelines: " + USAGE_GUIDELINES_URL + "\n"
                + "eula=true\n";
        Files.writeString(target, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
