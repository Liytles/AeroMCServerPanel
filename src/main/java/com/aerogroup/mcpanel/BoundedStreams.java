package com.aerogroup.mcpanel;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;

/** Ağ kaynaklarının belleği veya diski sınırsız büyütmesini engelleyen ortak akış yardımcıları. */
final class BoundedStreams {
    private BoundedStreams() { }

    static String readString(InputStream input, int maximumBytes, Charset charset) throws IOException {
        return new String(readBytes(input, maximumBytes), charset);
    }

    static byte[] readBytes(InputStream input, int maximumBytes) throws IOException {
        if (maximumBytes < 1) throw new IllegalArgumentException("Geçersiz akış sınırı.");
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 16_384))) {
            byte[] buffer = new byte[16_384]; int read; long total = 0;
            while ((read = source.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > maximumBytes) throw new IOException("Ağ yanıtı güvenli boyut sınırını aştı.");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    static long copyToFile(InputStream input, Path output, long maximumBytes) throws IOException {
        if (maximumBytes < 1) throw new IllegalArgumentException("Geçersiz dosya sınırı.");
        long total = 0;
        try (InputStream source = input; OutputStream target = Files.newOutputStream(output, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[64 * 1024]; int read;
            while ((read = source.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > maximumBytes) throw new IOException("İndirilen dosya güvenli boyut sınırını aştı.");
                target.write(buffer, 0, read);
            }
        } catch (Exception error) {
            Files.deleteIfExists(output);
            throw error;
        }
        return total;
    }
}
