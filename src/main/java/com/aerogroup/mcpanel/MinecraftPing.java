package com.aerogroup.mcpanel;

import javax.naming.directory.*;
import javax.naming.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Hashtable;
import java.util.regex.*;

/** Minecraft Server List Ping protokolüyle yalnızca herkese açık durum bilgisini okur. */
public final class MinecraftPing {
    public record Result(String host, int port, int online, int max, String version, long latencyMs) { }
    public static Result ping(String input) throws Exception {
        Host target = resolve(input.trim()); long started = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.connectHost(), target.port()), 5000); socket.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream()); DataInputStream in = new DataInputStream(socket.getInputStream());
            ByteArrayOutputStream handshakeBytes = new ByteArrayOutputStream(); DataOutputStream handshake = new DataOutputStream(handshakeBytes);
            writeVarInt(handshake, 0); writeVarInt(handshake, 767); writeString(handshake, target.handshakeHost()); handshake.writeShort(target.port()); writeVarInt(handshake, 1);
            writeVarInt(out, handshakeBytes.size()); out.write(handshakeBytes.toByteArray()); out.writeByte(1); out.writeByte(0); out.flush();
            int packetLength = readVarInt(in); if (packetLength <= 0 || packetLength > 2_000_000) throw new IOException("Geçersiz durum yanıtı.");
            if (readVarInt(in) != 0) throw new IOException("Sunucu durum yanıtı desteklenmiyor.");
            int length = readVarInt(in); if (length < 0 || length > packetLength) throw new IOException("Geçersiz durum verisi.");
            byte[] jsonBytes = in.readNBytes(length); if (jsonBytes.length != length) throw new EOFException("Durum yanıtı eksik kaldı."); String json = new String(jsonBytes, StandardCharsets.UTF_8);
            int online = number(json, "\\\"online\\\"\\s*:\\s*(\\d+)"); int max = number(json, "\\\"max\\\"\\s*:\\s*(\\d+)");
            String version = text(json, "\\\"version\\\"\\s*:\\s*\\{.*?\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
            return new Result(target.handshakeHost(), target.port(), online, max, version, (System.nanoTime() - started) / 1_000_000);
        }
    }
    private static Host resolve(String input) {
        String normalized = normalizeInput(input); String host = normalized; int port = 25565;
        int colon = normalized.lastIndexOf(':');
        if (colon > 0 && normalized.indexOf(':') == colon) { try { port = Integer.parseInt(normalized.substring(colon + 1)); host = normalized.substring(0, colon); } catch (NumberFormatException ignored) { } }
        String handshakeHost = host;
        if (port == 25565) {
            try {
                Hashtable<String, String> env = new Hashtable<>(); env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
                DirContext context = new InitialDirContext(env); Attributes attrs = context.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"});
                Attribute records = attrs.get("SRV"); if (records != null) { String[] parts = records.get().toString().trim().split("\\s+"); port = Integer.parseInt(parts[2]); host = parts[3].replaceFirst("\\.$", ""); }
            } catch (Exception ignored) { }
        }
        return new Host(host, handshakeHost, port);
    }
    static String normalizeInput(String input) {
        String value = input == null ? "" : input.trim().replaceFirst("^https?://", "").replaceFirst("/.*$", "");
        if (value.startsWith("[") && value.contains("]")) return value.substring(1, value.indexOf(']')) + value.substring(value.indexOf(']') + 1);
        return value;
    }
    private static int number(String json, String regex) { Matcher m = Pattern.compile(regex).matcher(json); return m.find() ? Integer.parseInt(m.group(1)) : 0; }
    private static String text(String json, String regex) { Matcher m = Pattern.compile(regex).matcher(json); return m.find() ? m.group(1) : "Bilinmiyor"; }
    private static int readVarInt(DataInputStream in) throws IOException { int value = 0, position = 0, current; do { current = in.readUnsignedByte(); value |= (current & 0x7F) << position; position += 7; if (position >= 32) throw new IOException("Geçersiz VarInt"); } while ((current & 0x80) != 0); return value; }
    private static void writeVarInt(DataOutputStream out, int value) throws IOException { do { int current = value & 0x7F; value >>>= 7; if (value != 0) current |= 0x80; out.writeByte(current); } while (value != 0); }
    private static void writeString(DataOutputStream out, String value) throws IOException { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); writeVarInt(out, bytes.length); out.write(bytes); }
    private record Host(String connectHost, String handshakeHost, int port) { }
}
