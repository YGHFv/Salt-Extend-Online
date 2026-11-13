package io.github.yghfv.saltneri.plugin.diagnostics;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** A projection, not a raw-log copy. Only symbols verified against installed class files survive. */
public final class SafeDiagnosticLog {
    public static final long MAX_INPUT_BYTES = 4L * 1024 * 1024;
    private static final int MAX_LINE = 8192, MAX_LINES = 65536;
    private static final Pattern FRAME = Pattern.compile("\\s*at ([\\p{javaJavaIdentifierPart}.$/@-]+)\\.([\\p{javaJavaIdentifierPart}<>-]+)\\(([^\\r\\n()]*)\\)\\s*");
    private static final Pattern ERROR = Pattern.compile("\\s*(?:(Caused by|Suppressed):\\s*)?([\\p{javaJavaIdentifierPart}.$]+(?:Exception|Error))(?::(.*))?\\s*");
    private static final Pattern MORE = Pattern.compile("\\s*\\.\\.\\. ([0-9]{1,4}) more\\s*");
    private static final Pattern EMBEDDED_ERROR = Pattern.compile("(?<![\\p{javaJavaIdentifierPart}.$])([\\p{javaJavaIdentifierPart}.$]+(?:Exception|Error))(?=[:\\s]|$)");
    private static final Pattern PREFIX = Pattern.compile("^\\[?(?:[0-9]{4}-[0-9]{2}-[0-9]{2}[ T])?[0-9]{2}:[0-9]{2}:[0-9]{2}(?:[.,][0-9]{1,3})?\\]?\\s+(?:\\[[^\\]\\r\\n]*\\]\\s+)?\\[?(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\]?\\s+.*$");
    private final ClassLoader symbols;
    private final Map<String, Symbol> cache = new HashMap<>();
    private record Symbol(String name, Set<String> methods) {}
    public record Result(int lines, int withheld, boolean truncated) {}

    public SafeDiagnosticLog(ClassLoader symbols) { this.symbols = Objects.requireNonNull(symbols); }

    /** Neither filenames, loader/module names, messages nor thread names are taken from input. */
    public String frame(String className, String method) {
        Symbol symbol = symbol(className);
        return symbol != null && symbol.methods.contains(method) ? symbol.name + "." + method + "(source omitted)" : "private-data";
    }

    public String line(String input) {
        if (input.length() > MAX_LINE) return "private-data";
        Matcher prefix = PREFIX.matcher(input);
        String severity = prefix.matches() ? prefix.group(1) + " " : "";
        Matcher frame = FRAME.matcher(input);
        if (frame.matches()) {
            String owner = frame.group(1);
            // JDK stack traces can contain java.base@25/java.lang.Thread; never export that prefix.
            int slash = owner.lastIndexOf('/');
            if (slash >= 0) owner = owner.substring(slash + 1);
            String safe = frame(owner, frame.group(2));
            return safe.equals("private-data") ? safe : "    at " + safe;
        }
        Matcher error = ERROR.matcher(input);
        if (error.matches() && symbol(error.group(2)) != null) {
            String errorPrefix = error.group(1) == null ? "" : error.group(1) + ": ";
            return errorPrefix + symbol(error.group(2)).name + (error.group(3) == null ? "" : ": " + placeholder(error.group(3)));
        }
        if (MORE.matcher(input).matches()) return "    ... additional frames";
        // Includes Exception in thread "private name" and logger-decorated exception headers.
        Matcher embedded = EMBEDDED_ERROR.matcher(input);
        for (int attempts = 0; attempts < 8 && embedded.find(); attempts++) {
            Symbol symbol = symbol(embedded.group(1));
            if (symbol != null) return severity + symbol.name + ": " + placeholder(input);
        }
        // Keep severity, but never copy arbitrary logger prefixes or formatted messages.
        return severity + placeholder(input);
    }

    public static String placeholder(String input) {
        // Decoding only selects a fixed marker. Encoded/unrecognised payloads are still discarded.
        String text = input.toLowerCase(Locale.ROOT);
        for (int i = 0; i < 3; i++) {
            try { text = java.net.URLDecoder.decode(text, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT); }
            catch (IllegalArgumentException ignored) { break; }
        }
        if (text.contains("qq") || text.contains("p_skey") || text.contains("uin=") || text.contains("qm_keyst")) return "qqcookie";
        if (text.contains("bili") || text.contains("sessdata") || text.contains("bili_jct") || text.contains("dedeuserid")) return "bilicookie";
        if (text.contains("netease") || text.contains("music_u") || text.contains("nmtid") || text.contains("__csrf")) return "neteasecookie";
        if (text.contains("apple") || text.contains("music-user-token")) return "applecookie";
        if (text.contains("kugou") || text.contains("ku_gou")) return "kugoucookie";
        if (text.contains("cookie")) return "cookie";
        if (text.contains("token") || text.contains("authorization") || text.contains("bearer") || text.contains("password")) return "account-data";
        return "private-data";
    }

    public Result project(Path path, Writer output) throws IOException {
        interrupted();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            output.write("Host log unavailable.\n"); return new Result(0, 0, false);
        }
        try (SeekableByteChannel channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            long size = channel.size(), start = Math.max(0, size - MAX_INPUT_BYTES);
            channel.position(start);
            boolean skip = start != 0, oversized = false;
            int lines = 0, withheld = 0;
            long read = 0;
            var buffer = ByteBuffer.allocate(8192);
            var line = new ByteArrayOutputStream();
            while (read < MAX_INPUT_BYTES && lines < MAX_LINES) {
                interrupted(); buffer.clear(); buffer.limit((int)Math.min(buffer.capacity(), MAX_INPUT_BYTES - read));
                int n = channel.read(buffer); if (n < 0) break; read += n; buffer.flip();
                while (buffer.hasRemaining() && lines < MAX_LINES) {
                    byte b = buffer.get();
                    if (b == '\n') {
                        if (!skip) {
                            String safe = oversized ? "private-data" : line(line.toString(StandardCharsets.UTF_8).replace("\r", ""));
                            output.write(safe); output.write('\n'); lines++;
                            if (!safe.startsWith("    at ") && !safe.contains("Exception") && !safe.contains("Error")) withheld++;
                        }
                        skip = false; oversized = false; line.reset();
                    } else if (!skip && !oversized) {
                        if (line.size() >= MAX_LINE) { oversized = true; line.reset(); } else line.write(b);
                    }
                }
            }
            if (!skip && lines < MAX_LINES && (line.size() != 0 || oversized)) {
                output.write(oversized ? "private-data" : line(line.toString(StandardCharsets.UTF_8).replace("\r", "")));
                output.write('\n'); lines++; withheld++;
            }
            return new Result(lines, withheld, start != 0 || lines >= MAX_LINES || channel.size() > size);
        }
    }

    static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Export cancelled");
    }

    private Symbol symbol(String name) {
        if (name.length() > 512 || !name.matches("[\\p{javaJavaIdentifierPart}.$]+")) return null;
        if (cache.containsKey(name)) return cache.get(name);
        // Bound both retained symbol metadata and arbitrary resource lookups from a hostile log.
        if (cache.size() >= 2048) return null;
        Symbol result = null;
        try (InputStream raw = symbols.getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (raw != null) result = readSymbol(raw, name);
        } catch (IOException | RuntimeException | LinkageError ignored) { }
        cache.put(name, result); return result;
    }

    /** Parse class metadata without initializing classes, resolving method types or executing host code. */
    private static Symbol readSymbol(InputStream raw, String requested) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(raw.readNBytes(2 * 1024 * 1024)));
        if (in.readInt() != 0xcafebabe) return null;
        in.readInt(); int count = in.readUnsignedShort(); Object[] pool = new Object[count];
        for (int i = 1; i < count; i++) {
            switch (in.readUnsignedByte()) {
                case 1 -> pool[i] = in.readUTF();
                case 7 -> pool[i] = in.readUnsignedShort();
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                case 5, 6 -> { in.skipNBytes(8); i++; }
                case 8, 16, 19, 20 -> in.skipNBytes(2);
                case 15 -> in.skipNBytes(3);
                default -> throw new IOException("Invalid class metadata");
            }
        }
        in.readUnsignedShort(); int owner = in.readUnsignedShort(); in.readUnsignedShort();
        String name = ((String)pool[(Integer)pool[owner]]).replace('/', '.');
        if (!name.equals(requested)) return null;
        int interfaces = in.readUnsignedShort(); in.skipNBytes(2L * interfaces);
        members(in, pool, null); Set<String> methods = new HashSet<>(); members(in, pool, methods);
        return new Symbol(name, Set.copyOf(methods));
    }
    private static void members(DataInputStream in, Object[] pool, Set<String> names) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort(); String name = (String)pool[in.readUnsignedShort()]; in.readUnsignedShort();
            if (names != null) names.add(name);
            int attributes = in.readUnsignedShort();
            for (int j = 0; j < attributes; j++) { in.readUnsignedShort(); in.skipNBytes(Integer.toUnsignedLong(in.readInt())); }
        }
    }
}
