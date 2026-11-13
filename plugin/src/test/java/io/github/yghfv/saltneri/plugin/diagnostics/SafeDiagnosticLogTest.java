package io.github.yghfv.saltneri.plugin.diagnostics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Adversarial input cannot introduce arbitrary output, even by impersonating a stack frame. */
public final class SafeDiagnosticLogTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Log privacy " + checks); }
    private static final class NoInitialization {
        static { System.setProperty("salt.test.symbol.initialized", "yes"); }
        static void work() {}
    }
    public static void main(String[] args) throws Exception {
        var filter = new SafeDiagnosticLog(SafeDiagnosticLogTest.class.getClassLoader());
        String secret = "PRIVATE_FIXTURE_7f9321";
        var inputs = List.of(
                "Cookie: qqmusic_key=" + secret + "; uin=123456789",
                "{\"unknownNested\":{\"profile\":{\"nickname\":\"" + secret + "\",\"id\":123456789}}}",
                "Authorization: Bearer " + secret,
                "Cookie: SESSDATA=" + secret + "; bili_jct=" + secret,
                "Cookie: MUSIC_U=" + secret + "; __csrf=" + secret,
                "Music-User-Token: " + secret,
                "kugou sessionId=" + secret,
                "https://cn.bilivideo.com/media?token=" + secret + "&w_rid=" + secret,
                "https://qq.com/?qr=" + secret,
                "C:\\Users\\" + secret + "\\accounts.dat",
                Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)),
                java.net.URLEncoder.encode(java.net.URLEncoder.encode("Cookie: qqmusic_key=" + secret, StandardCharsets.UTF_8), StandardCharsets.UTF_8),
                "Cookie: QQ; \"broken=\"" + secret + "%zz",
                "\u001b[31m" + secret + "\u0000\t" + secret,
                "2026-11-13 12:00:00 ERROR [" + secret + "] login failed: " + secret,
                "java.io.IOException: " + secret,
                "Exception in thread \"" + secret + "\" java.lang.NoClassDefFoundError: " + secret,
                "    at java.lang.Thread.run(" + secret + ".java:123456789)",
                "    at java.lang.Thread." + secret + "(Thread.java:1)",
                "    at account." + secret + ".run(User.java:1)",
                "    at java.lang.Thread.run(Thread.java:1) Cookie=" + secret,
                "a".repeat(8192), "a".repeat(9000) + secret);
        for (String input : inputs) {
            String output = filter.line(input);
            check(!output.contains(secret) && !output.contains("123456789") && !output.contains("https://") && !output.contains("C:\\Users"));
        }
        check(filter.line(inputs.get(0)).equals("qqcookie"));
        check(filter.line(inputs.get(3)).equals("bilicookie"));
        check(filter.line(inputs.get(4)).equals("neteasecookie"));
        check(filter.line(inputs.get(5)).equals("applecookie"));
        check(filter.line(inputs.get(6)).equals("kugoucookie"));
        check(filter.line(inputs.get(11)).equals("qqcookie"));
        check(filter.line(inputs.get(17)).contains("java.lang.Thread.run"));
        check(filter.line(inputs.get(18)).equals("private-data"));
        check(filter.line("Caused by: java.io.IOException: " + secret).equals("Caused by: java.io.IOException: private-data"));
        check(filter.line("    at java.base/java.lang.Thread.run(Thread.java:1)").contains("java.lang.Thread.run"));
        check(filter.line("19:49:02.631 [" + secret + "] INFO com.xuncorp.voxzen.tag.TagParser - " + secret).equals("INFO private-data"));
        check(filter.line("19:49:02.631 [" + secret + "] ERROR host - java.net.SocketException: " + secret).equals("ERROR java.net.SocketException: private-data"));
        check(filter.line("2026-11-13 19:49:02.631 [" + secret + "] WARN Cookie: qqmusic_key=" + secret).equals("WARN qqcookie"));
        check(filter.line("    at " + secret + "/java.base@25/java.lang.Thread.run(Thread.java:1)").equals("    at java.lang.Thread.run(source omitted)"));
        check(filter.line("    at " + SafeDiagnosticLogTest.class.getName() + "$NoInitialization.work(NoInitialization.java:1)").contains("NoInitialization.work"));
        check(System.getProperty("salt.test.symbol.initialized") == null);
        check(filter.line("    at java.lang.Thread.run(Thread.java:1)\n" + secret).equals("private-data"));

        Path directory = Files.createTempDirectory("safe-log-fixture-");
        try {
            Path input = directory.resolve(secret + ".txt");
            String raw = String.join("\r\n", inputs) + "\r\nCookie: QQ=\r\n" + secret + "\r\n{\n\"cookie\":\n\"" + secret + "\"\n}\n";
            Files.writeString(input, raw);
            var output = new StringWriter(); var result = filter.project(input, output);
            check(result.withheld() > 0 && output.toString().contains("java.io.IOException"));
            check(!output.toString().contains(secret) && !output.toString().contains("123456789"));
            check(Files.readString(input).equals(raw));
            // A huge unterminated credential line is skipped before the trailing error; memory is bounded.
            try (Writer writer = Files.newBufferedWriter(input)) {
                for (int i = 0; i < 600; i++) writer.write(secret.repeat(400));
                writer.write("\nCaused by: java.net.SocketException: " + secret + "\n    at java.lang.Thread.run(" + secret + ")\n");
            }
            output = new StringWriter(); result = filter.project(input, output);
            check(result.truncated() && output.toString().contains("java.net.SocketException"));
            check(!output.toString().contains(secret) && output.toString().length() < 1000);
            Files.write(input, new byte[]{(byte)0xff, (byte)0xc0, '\n', 'x'});
            output = new StringWriter(); filter.project(input, output);
            check(output.toString().equals("private-data\nprivate-data\n"));
            output = new StringWriter(); filter.project(directory.resolve("missing"), output);
            check(output.toString().equals("Host log unavailable.\n"));
            Thread.currentThread().interrupt();
            try { filter.project(input, new StringWriter()); throw new AssertionError("Cancellation ignored"); }
            catch (InterruptedIOException expected) { check(true); } finally { Thread.interrupted(); }
        } finally {
            try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
        // Fuzz all syntax, including fake exception prefixes; output never copies arbitrary names or text.
        var random = new Random(19231);
        for (int i = 0; i < 1000; i++) {
            String payload = "SECRET_" + UUID.randomUUID();
            String input = switch(random.nextInt(4)) {
                case 0 -> "    at java.lang.Thread." + payload + "(" + payload + ":1)";
                case 1 -> "java.lang.IllegalArgumentException: " + payload;
                case 2 -> "\"unknown\": \"" + payload + "\"";
                default -> "    at java.lang.Thread.run(" + payload + ")";
            };
            check(!filter.line(input).contains(payload));
        }
        System.out.println("PASS: " + checks + " log projection/privacy, encoding, forged-symbol and bounded-tail checks");
    }
}
