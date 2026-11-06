package io.github.yghfv.saltneri.plugin.transport;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict single byte ranges; no multipart responses or unchecked arithmetic. */
public record ByteRange(long first, long last) {
    public static final long MAX_REQUEST_BYTES = 4L * 1024 * 1024;
    private static final Pattern RANGE = Pattern.compile("bytes=([0-9]*)-([0-9]*)");

    public ByteRange {
        if (first < 0 || last < first || last - first >= MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Range outside request bounds");
        }
    }

    public long length() { return last - first + 1; }
    public String requestHeader() { return "bytes=" + first + "-" + last; }
    public String contentHeader(long total) { return "bytes " + first + "-" + last + "/" + total; }

    public static ByteRange parse(String text, long total) {
        if (text == null || text.length() > 80 || total < 1) throw new IllegalArgumentException("Missing range");
        Matcher matcher = RANGE.matcher(text);
        if (!matcher.matches() || (matcher.group(1).isEmpty() && matcher.group(2).isEmpty())) {
            throw new IllegalArgumentException("Malformed range");
        }
        try {
            long first;
            long last;
            if (matcher.group(1).isEmpty()) {
                long suffix = Long.parseLong(matcher.group(2));
                if (suffix == 0) throw new IllegalArgumentException("Empty suffix");
                first = Math.max(0, total - suffix);
                last = total - 1;
            } else {
                first = Long.parseLong(matcher.group(1));
                last = matcher.group(2).isEmpty() ? total - 1
                        : Math.min(Long.parseLong(matcher.group(2)), total - 1);
            }
            if (first >= total) throw new IllegalArgumentException("Unsatisfiable range");
            return new ByteRange(first, last);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Range integer overflow");
        }
    }
}
