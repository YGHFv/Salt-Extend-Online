package io.github.yghfv.saltneri.plugin.transport;

import io.github.yghfv.saltneri.plugin.host.HostRangeReader;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Actual Salt random-access reader -> real localhost server -> deterministic fake CDN. */
public final class HostRangeIntegrationTest {
    public static void main(String[] args) throws Exception {
        long total = 2 * 1024 * 1024 + 123;
        List<String> ranges = new CopyOnWriteArrayList<>();
        try (LoopbackRangeServer server = TransportContractTest.server(uri -> new TransportContractTest.FakeConnection(uri, total) {
            @Override public int getResponseCode() { ranges.add(getRequestProperty("Range")); return super.getResponseCode(); }
        })) {
            URI local = server.register(TransportContractTest.lease("test", Instant.parse("2026-11-06T00:01:00Z"), total));
            try (HostRangeReader source = HostRangeReader.open(local)) {
                require(source.length() == total, "Host size probe failed");
                require(ranges.equals(List.of("bytes=0-0")), "Host initial probe changed");
                read(source, 0, 1000);
                read(source, 900000, 1000);
                int beforeCache = ranges.size();
                read(source, 100, 200);
                require(ranges.size() == beforeCache, "Host did not retain already-read sparse chunk");
                read(source, total - 50, 50);
                require(source.read(new byte[1]) == -1, "Host EOF changed");
                require(ranges.stream().allMatch(text -> ByteRange.parse(text, total).length() <= ByteRange.MAX_REQUEST_BYTES),
                        "Host requested beyond transport bound");
            }
            server.revoke(local);
            try {
                HostRangeReader.open(local).close();
                throw new AssertionError("Host reopened revoked lease");
            } catch (Exception expected) {
                require(expected instanceof java.io.IOException, "Unexpected revoked lease failure");
            }
        }
        System.out.println("PASS: actual Salt HttpRangeSource probe, random seek, sparse cache, EOF and revoked lease");
        System.out.println("No BASS audio stream, playback queue, platform request or account accessed.");
    }

    private static void read(HostRangeReader source, long offset, int length) throws Exception {
        require(source.seek(offset), "Seek failed");
        byte[] bytes = new byte[length];
        require(source.read(bytes) == length, "Host range read incomplete");
        for (int index = 0; index < length; index++) require(bytes[index] == (byte)(offset + index), "Wrong byte after seek");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
