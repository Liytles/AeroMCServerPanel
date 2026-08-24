package com.aerogroup.mcpanel;

import java.io.IOException;
import java.nio.file.*;

/** Sunucu dosya işlemlerinin kök dışına ve simgesel bağlantılara kaçmasını engeller. */
final class SafePathGuard {
    private SafePathGuard() { }

    static Path serverJar(Path jar) throws IOException {
        if (jar == null) throw new IOException("Sunucu JAR yolu boş.");
        Path absolute = jar.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) throw new IOException("Güvenlik nedeniyle simgesel bağlantı olan sunucu JAR'ı kullanılamaz.");
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Geçerli bir server.jar seçilmedi.");
        Path real = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
        requireWithin(real.getParent(), real, false);
        return real;
    }

    static Path requireWithin(Path root, Path target, boolean allowMissingLeaf) throws IOException {
        if (root == null || target == null) throw new IOException("Güvenli dosya kökü belirlenemedi.");
        Path absoluteRoot = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(absoluteRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(absoluteRoot)) throw new IOException("Sunucu klasörü geçersiz veya simgesel bağlantı.");
        Path rootReal = absoluteRoot.toRealPath(LinkOption.NOFOLLOW_LINKS), absoluteTarget = target.toAbsolutePath().normalize();
        if (!absoluteTarget.startsWith(absoluteRoot)) throw new IOException("Sunucu klasörü dışındaki dosya işlemi engellendi.");
        Path relative = absoluteRoot.relativize(absoluteTarget), cursor = absoluteRoot;
        for (Path part : relative) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Simgesel bağlantı üzerinden dosya işlemi engellendi: " + part);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) break;
        }
        Path existing = absoluteTarget;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null || !existing.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(rootReal)) throw new IOException("Dosyanın gerçek yolu sunucu klasörü dışında.");
        if (!allowMissingLeaf && !Files.exists(absoluteTarget, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(absoluteTarget.toString());
        return absoluteTarget;
    }

    static Path resolve(Path root, String child, boolean allowMissingLeaf) throws IOException {
        if (child == null || child.isBlank() || Path.of(child).isAbsolute()) throw new IOException("Geçersiz göreli dosya yolu.");
        return requireWithin(root, root.resolve(child).normalize(), allowMissingLeaf);
    }
}
