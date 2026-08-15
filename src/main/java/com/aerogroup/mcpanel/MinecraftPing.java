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
            socket.connect(new InetSocketAddress(target.host(), target.port()), 4000); socket.setSoTimeout(4000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream()); DataInputStream in = new DataInputStream(socket.getInputStream());
            ByteArrayOutputStream handshakeBytes = new ByteArrayOutputStream(); DataOutputStream handshake = new DataOutputStream(handshakeBytes);
            writeVarInt(handshake, 0); writeVarInt(handshake, 767); writeString(handshake, target.host()); handshake.writeShort(target.port()); writeVarInt(handshake, 1);
            writeVarInt(out, handshakeBytes.size()); out.write(handshakeBytes.toByteArray()); out.writeByte(1); out.writeByte(0); out.flush();
            readVarInt(in); readVarInt(in); int length = readVarInt(in); byte[] jsonBytes = in.readNBytes(length); String json = new String(jsonBytes, StandardCharsets.UTF_8);
            int online = number(json, "\\\"online\\\"\\s*:\\s*(\\d+)"); int max = number(json, "\\\"max\\\"\\s*:\\s*(\\d+)");
            String version = text(json, "\\\"version\\\"\\s*:\\s*\\{.*?\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
            return new Result(target.host(), target.port(), online, max, version, (System.nanoTime() - started) / 1_000_000);
        }
    }
    private static Host resolve(String input) {
        String host = input; int port = 25565;
        int colon = input.lastIndexOf(':');
        if (colon > 0 && input.indexOf(':') == colon) { try { port = Integer.parseInt(input.substring(colon + 1)); host = input.substring(0, colon); } catch (NumberFormatException ignored) { } }
        if (port == 25565) {
            try {
                Hashtable<String, String> env = new Hashtable<>(); env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
                DirContext context = new InitialDirContext(env); Attributes attrs = context.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"});
                Attribute records = attrs.get("SRV"); if (records != null) { String[] parts = records.get().toString().trim().split("\\s+"); port = Integer.parseInt(parts[2]); host = parts[3].replaceFirst("\\.$", ""); }
            } catch (Exception ignored) { }
        }
        return new Host(host, port);
    }
    private static int number(String json, String regex) { Matcher m = Pattern.compile(regex).matcher(json); return m.find() ? Integer.parseInt(m.group(1)) : 0; }
    private static String text(String json, String regex) { Matcher m = Pattern.compile(regex).matcher(json); return m.find() ? m.group(1) : "Bilinmiyor"; }
    private static int readVarInt(DataInputStream in) throws IOException { int value = 0, position = 0, current; do { current = in.readUnsignedByte(); value |= (current & 0x7F) << position; position += 7; if (position >= 32) throw new IOException("Geçersiz VarInt"); } while ((current & 0x80) != 0); return value; }
    private static void writeVarInt(DataOutputStream out, int value) throws IOException { do { int current = value & 0x7F; value >>>= 7; if (value != 0) current |= 0x80; out.writeByte(current); } while (value != 0); }
    private static void writeString(DataOutputStream out, String value) throws IOException { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); writeVarInt(out, bytes.length); out.write(bytes); }
    private record Host(String host, int port) { }
}
