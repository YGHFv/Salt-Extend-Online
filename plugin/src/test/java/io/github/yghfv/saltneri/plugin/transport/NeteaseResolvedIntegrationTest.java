package io.github.yghfv.saltneri.plugin.transport;

import io.github.yghfv.saltneri.plugin.netease.NeteaseClientContractTest;
import io.github.yghfv.saltneri.plugin.host.HostRangeReader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

/** Fake NetEase API -> real lease/loopback -> actual Salt HTTP reader, without BASS. */
public final class NeteaseResolvedIntegrationTest {
    public static void main(String[] args) throws Exception {
        var resolved = NeteaseClientContractTest.resolvedFixture(); AtomicInteger calls = new AtomicInteger();
        try (LoopbackRangeServer server = new LoopbackRangeServer(Clock.fixed(Instant.parse("2026-11-06T00:00:01Z"), ZoneOffset.UTC),
                uri -> new TransportContractTest.FakeConnection(uri, resolved.lease().length()) {
                    @Override public int getResponseCode() {
                        calls.incrementAndGet();
                        if (getRequestProperty("Cookie") != null) throw new AssertionError("Account Cookie forwarded to NetEase CDN");
                        if (!getRequestProperty("Referer").equals("https://music.163.com/")) throw new AssertionError("Referer missing");
                        return super.getResponseCode();
                    }
                }, () -> true)) {
            var local = server.register(resolved.lease());
            try (HostRangeReader reader = HostRangeReader.open(local)) {
                if (reader.length() != 4096 || calls.get() != 1) throw new AssertionError("Length probe mismatch");
                if (!reader.seek(4000)) throw new AssertionError("Seek failed");
                byte[] tail = new byte[96]; if (reader.read(tail) != 96) throw new AssertionError("Truncated range");
                for (int i = 0; i < tail.length; i++) if (tail[i] != (byte)(4000 + i)) throw new AssertionError("Wrong range byte");
                if (reader.read(new byte[1]) != -1) throw new AssertionError("EOF failed");
            }
            server.revokeAccount("fixture-anonymous");
            try { HostRangeReader.open(local).close(); throw new AssertionError("Revoked lease reopened"); }
            catch (java.io.IOException expected) { }
        }
        System.out.println("PASS: NetEase fixture -> real lease/loopback -> actual Salt reader/seek/revoke (no BASS or queue)");
    }
}
