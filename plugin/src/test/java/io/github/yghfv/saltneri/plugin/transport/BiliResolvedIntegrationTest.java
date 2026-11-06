package io.github.yghfv.saltneri.plugin.transport;

import io.github.yghfv.saltneri.plugin.bilibili.BiliClientContractTest;
import io.github.yghfv.saltneri.plugin.host.HostRangeReader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

/** Fake Bilibili resolver -> actual lease -> localhost -> actual Salt HTTP reader, without BASS. */
public final class BiliResolvedIntegrationTest {
    public static void main(String[] args) throws Exception {
        var resolved = BiliClientContractTest.resolvedFixture();
        AtomicInteger calls = new AtomicInteger();
        try (LoopbackRangeServer server = new LoopbackRangeServer(Clock.fixed(Instant.parse("2026-11-06T00:00:01Z"), ZoneOffset.UTC),
                uri -> new TransportContractTest.FakeConnection(uri, resolved.lease().length()) {
                    @Override public int getResponseCode() {
                        calls.incrementAndGet();
                        if (getRequestProperty("Cookie") != null) throw new AssertionError("Anonymous lease forwarded Cookie");
                        if (!getRequestProperty("Referer").equals("https://www.bilibili.com/")) throw new AssertionError("Referer missing");
                        return super.getResponseCode();
                    }
                }, () -> true)) {
            var local = server.register(resolved.lease());
            try (HostRangeReader reader = HostRangeReader.open(local)) {
                if (reader.length() != 4096 || calls.get() != 1) throw new AssertionError("Resolved lease probe mismatch");
                if (!reader.seek(4000)) throw new AssertionError("Seek failed");
                byte[] tail = new byte[96];
                if (reader.read(tail) != tail.length) throw new AssertionError("Reader truncated resolved audio");
                for (int i = 0; i < tail.length; i++) if (tail[i] != (byte)(4000 + i)) throw new AssertionError("Wrong range bytes");
                if (reader.read(new byte[1]) != -1) throw new AssertionError("EOF failed");
            }
            server.revokeAccount("fixture-anonymous");
            try { HostRangeReader.open(local).close(); throw new AssertionError("Revoked resolved lease reopened"); }
            catch (java.io.IOException expected) { }
        }
        System.out.println("PASS: Bilibili fixture -> anonymous lease -> real loopback -> actual Salt reader/seek/revoke (no BASS or queue)");
    }
}
