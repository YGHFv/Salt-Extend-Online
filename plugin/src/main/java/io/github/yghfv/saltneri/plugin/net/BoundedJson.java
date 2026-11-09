package io.github.yghfv.saltneri.plugin.net;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict UTF-8 JSON with bounded bytes, depth, nodes and exact numeric conversion. */
public final class BoundedJson {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private final String text;
    private int cursor, nodes;
    private BoundedJson(String text) { this.text = text; }
    public static Object parse(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw invalid();
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            BoundedJson parser = new BoundedJson(text);
            Object result = parser.value(0); parser.whitespace();
            if (parser.cursor != text.length()) throw invalid();
            return result;
        } catch (CharacterCodingException failure) { throw invalid(); }
    }
    private Object value(int depth) throws IOException {
        whitespace();
        if (depth > 32 || ++nodes > 100000 || cursor >= text.length()) throw invalid();
        char next = text.charAt(cursor);
        if (next == '"') return string();
        if (next == '{') {
            cursor++; Map<String, Object> result = new LinkedHashMap<>(); whitespace();
            if (consume('}')) return result;
            do {
                whitespace(); String key = string(); whitespace(); require(':');
                if (result.containsKey(key)) throw invalid();
                result.put(key, value(depth + 1)); whitespace();
                if (consume('}')) return result;
                require(',');
            } while (true);
        }
        if (next == '[') {
            cursor++; List<Object> result = new ArrayList<>(); whitespace();
            if (consume(']')) return result;
            do {
                result.add(value(depth + 1)); whitespace();
                if (consume(']')) return result;
                require(',');
            } while (true);
        }
        for (String literal : List.of("true", "false", "null")) {
            if (text.startsWith(literal, cursor)) {
                cursor += literal.length(); return literal.equals("null") ? null : Boolean.valueOf(literal);
            }
        }
        int start = cursor;
        consume('-');
        if (!consume('0')) digits();
        if (consume('.')) digits();
        if (consume('e') || consume('E')) { if (!consume('+')) consume('-'); digits(); }
        if (cursor - start < 1 || cursor - start > 128) throw invalid();
        try { return new BigDecimal(text.substring(start, cursor)); }
        catch (NumberFormatException failure) { throw invalid(); }
    }
    private String string() throws IOException {
        require('"'); StringBuilder result = new StringBuilder();
        while (cursor < text.length()) {
            char next = text.charAt(cursor++);
            if (next == '"') {
                for (int i = 0; i < result.length(); i++) {
                    char ch = result.charAt(i);
                    if (Character.isHighSurrogate(ch)) {
                        if (++i >= result.length() || !Character.isLowSurrogate(result.charAt(i))) throw invalid();
                    } else if (Character.isLowSurrogate(ch)) throw invalid();
                }
                return result.toString();
            }
            if (next < 32 || result.length() >= 65536) throw invalid();
            if (next == '\\') {
                if (cursor >= text.length()) throw invalid();
                next = text.charAt(cursor++);
                switch (next) {
                    case '"', '\\', '/' -> result.append(next);
                    case 'b' -> result.append('\b'); case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n'); case 'r' -> result.append('\r'); case 't' -> result.append('\t');
                    case 'u' -> {
                        if (cursor + 4 > text.length()) throw invalid();
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            char hex = text.charAt(cursor++);
                            int digit = hex < 128 ? Character.digit(hex, 16) : -1;
                            if (digit < 0) throw invalid(); code = code * 16 + digit;
                        }
                        result.append((char)code);
                    }
                    default -> throw invalid();
                }
            } else result.append(next);
        }
        throw invalid();
    }
    private void digits() throws IOException {
        int start = cursor;
        while (cursor < text.length() && text.charAt(cursor) >= '0' && text.charAt(cursor) <= '9') cursor++;
        if (start == cursor) throw invalid();
    }
    private void whitespace() { while (cursor < text.length() && " \t\r\n".indexOf(text.charAt(cursor)) >= 0) cursor++; }
    private boolean consume(char ch) { if (cursor < text.length() && text.charAt(cursor) == ch) { cursor++; return true; } return false; }
    private void require(char ch) throws IOException { if (!consume(ch)) throw invalid(); }
    private static IOException invalid() { return new IOException("Invalid or oversized platform JSON"); }
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) throws IOException {
        if (!(value instanceof Map<?, ?>)) throw invalid(); return (Map<String, Object>)value;
    }
    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) throws IOException {
        if (!(value instanceof List<?>)) throw invalid(); return (List<Object>)value;
    }
    public static String string(Object value) throws IOException { if (!(value instanceof String text)) throw invalid(); return text; }
    public static long integer(Object value) throws IOException {
        if (!(value instanceof BigDecimal number)) throw invalid();
        try { return number.longValueExact(); } catch (ArithmeticException failure) { throw invalid(); }
    }
    public static byte[] encode(Object value) throws IOException {
        StringBuilder text = new StringBuilder(); encode(value, text, 0);
        byte[] result = text.toString().getBytes(StandardCharsets.UTF_8);
        if (result.length > MAX_BYTES) throw invalid(); return result;
    }
    private static void encode(Object value, StringBuilder text, int depth) throws IOException {
        if (depth > 32 || text.length() > MAX_BYTES) throw invalid();
        if (value == null || value instanceof Boolean || value instanceof Integer || value instanceof Long) { text.append(value); return; }
        if (value instanceof BigDecimal number) {
            if (number.precision() > 128 || number.scale() < -128 || number.scale() > 128) throw invalid();
            String literal = number.toString();
            if (literal.length() > 128) throw invalid();
            text.append(literal); return;
        }
        if (value instanceof String string) {
            if (string.length() > 65536) throw invalid(); text.append('"');
            for (int i = 0; i < string.length(); i++) {
                char ch = string.charAt(i);
                if (ch == '"' || ch == '\\') text.append('\\').append(ch);
                else if (ch < 32) text.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int)ch));
                else if (Character.isHighSurrogate(ch)) {
                    if (i + 1 >= string.length() || !Character.isLowSurrogate(string.charAt(i + 1))) throw invalid();
                    text.append(ch).append(string.charAt(++i));
                } else if (Character.isLowSurrogate(ch)) throw invalid(); else text.append(ch);
            }
            text.append('"'); return;
        }
        if (value instanceof Map<?, ?> map) {
            text.append('{'); boolean first = true;
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) throw invalid();
                if (!first) text.append(','); first = false;
                encode(entry.getKey(), text, depth + 1); text.append(':'); encode(entry.getValue(), text, depth + 1);
            }
            text.append('}'); return;
        }
        if (value instanceof List<?> list) {
            text.append('['); boolean first = true;
            for (Object item : list) { if (!first) text.append(','); first = false; encode(item, text, depth + 1); }
            text.append(']'); return;
        }
        throw invalid();
    }
}
