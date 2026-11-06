package io.github.yghfv.saltneri.plugin.transport;

import io.github.yghfv.saltneri.plugin.host.HostRangeReader;
import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.host.ReversibleInterceptorLists;
import io.github.yghfv.saltneri.plugin.host.Salt1193PlaybackSession;
import io.github.yghfv.saltneri.plugin.host.Salt1193Probe;
import io.github.yghfv.saltneri.plugin.host.Salt1193StreamAdapter;
import io.github.yghfv.saltneri.plugin.host.StreamBinding;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;

/** Full session assembly with real media/ABI/reader and fake engine + recording queue. */
public final class PlaybackSessionContractTest {
    private static int checks;
    private static final Salt1193PlaybackSession.Metadata META = new Salt1193PlaybackSession.Metadata("Title", "Artist", "Album", "Artist");
    private static final Instant EXPIRY = Instant.parse("2026-11-06T00:01:00Z");

    public static void main(String[] args) throws Exception {
        ClassLoader loader = PlaybackSessionContractTest.class.getClassLoader();
        Salt1193Probe.Result profile = Salt1193Probe.inspect(loader, "1.19.3", "Steam");
        require(profile.matched(), profile.detail());
        AtomicBoolean authorized = new AtomicBoolean(true);
        AtomicInteger streams = new AtomicInteger();
        LoopbackRangeServer transport = TransportContractTest.server(uri -> new TransportContractTest.FakeConnection(uri, 100));
        Salt1193StreamAdapter adapter = new Salt1193StreamAdapter(loader, profile, authorized::get, (local, flags) -> {
            try (HostRangeReader source = HostRangeReader.open(local)) {
                source.seek(90);
                byte[] bytes = new byte[10];
                require(source.read(bytes) == 10, "Session source could not seek/read");
                require(bytes[0] == 90 && bytes[9] == 99, "Session resolved wrong content");
            }
            streams.incrementAndGet();
            return 321; // Synthetic handle only: this test deliberately does not construct BASS.
        });
        FakeBinding binding = new FakeBinding(adapter, authorized);
        Method create = Class.forName("androidx.compose.ui.\u015b", false, loader).getMethods()[0];
        Method unbox = Class.forName("kotlin.Result").getMethod("unbox-impl");
        try (Salt1193PlaybackSession session = new Salt1193PlaybackSession(profile, "account-a", authorized::get, binding, transport)) {
            TrackKey key = TrackKey.bilibili("BV1xx411c7mD", 123);
            Salt1193PlaybackSession.PreparedTrack track = session.prepare(key, META,
                    TransportContractTest.lease("account-a", EXPIRY, 100));
            String[] fields = profile.mediaFactory().read(track.mediaItem());
            require(fields[0].startsWith("saltneri:" + key.stableId() + ":"), "Logical media ID mismatch");
            require(fields[1].equals("Title") && fields[5].equals(track.path()), "Native metadata mismatch");
            require(track.path().matches("saltneri://media/[a-z0-9-]+"), "Private path format mismatch");
            fails(IllegalArgumentException.class, () -> session.prepare(key, META,
                    TransportContractTest.lease("other-account", EXPIRY, 100)));
            fails(IllegalArgumentException.class, () -> session.prepare(TrackKey.netease("123"), META,
                    TransportContractTest.lease("account-a", EXPIRY, 100)));
            List<Object> queue = new ArrayList<>();
            fails(IllegalStateException.class, () -> session.enqueue(track, queue::add));
            require(queue.isEmpty(), "Unattached session modified queue");
            Object fakeEngine = new Object();
            ReversibleInterceptorLists attachment = session.attach(fakeEngine);
            require(session.attach(fakeEngine) == attachment, "Repeated attach duplicated interceptors");
            session.enqueue(track, queue::add);
            require(queue.size() == 1 && queue.getFirst() == track.mediaItem(), "Queue didn't receive prepared native object");
            Object result = create.invoke(adapter.interceptor(), null, queue.getFirst(), 0L, 0L, 0, continuation());
            require(unbox.invoke(result).equals(321), "Session returned wrong nullable Result<Int>");
            require(streams.get() == 1, "Session didn't resolve through transport");
            var art = new Salt1193PlaybackSession.Metadata("Title", "Artist", "Album", "Artist",
                    io.github.yghfv.saltneri.plugin.host.PlaybackCoverContractTest.png());
            var covered = session.prepare(key, art, TransportContractTest.lease("account-a", EXPIRY, 100));
            java.nio.file.Path coverPath = java.nio.file.Path.of(covered.path());
            require(java.nio.file.Files.isRegularFile(coverPath), "Playback cover file missing");
            session.enqueue(covered, queue::add);
            require(unbox.invoke(create.invoke(adapter.interceptor(), null, covered.mediaItem(), 0L, 0L, 0, continuation())).equals(321), "Cover path did not resolve online audio");
            require(streams.get() == 2, "Cover file was played instead of remote audio");
            covered.close(); require(!java.nio.file.Files.exists(coverPath), "Retired cover file retained");
            Object retiredCover = unbox.invoke(create.invoke(adapter.interceptor(), null, covered.mediaItem(), 0L, 0L, 0, continuation()));
            try { ResultKt.throwOnFailure(retiredCover); throw new AssertionError("Retired cover path fell through to local audio"); }
            catch (IllegalStateException expected) { checks++; }
            Salt1193PlaybackSession.PreparedTrack second = session.prepare(key, META,
                    TransportContractTest.lease("account-a", EXPIRY, 100));
            require(!second.path().equals(track.path()), "Preparations reused stale media path");
            require(!profile.mediaFactory().read(second.mediaItem())[0].equals(fields[0]), "Preparations reused a host queue identity");
            track.close();
            track.close();
            fails(IllegalArgumentException.class, () -> session.enqueue(track, queue::add));
            Object missing = unbox.invoke(create.invoke(adapter.interceptor(), null, track.mediaItem(), 0L, 0L, 0, continuation()));
            try { ResultKt.throwOnFailure(missing); throw new AssertionError("Retired mapping still played"); }
            catch (IllegalStateException expected) { checks++; }
            attachment.close();
            fails(IllegalStateException.class, () -> session.enqueue(second, queue::add));
            session.attach(new Object()); // Simulated engine recreation; restores only the test-owned overlays.
            session.enqueue(second, queue::add);
            session.revokeAccount("unrelated-account");
            require(!session.isClosed(), "Unrelated account closed this session");
            session.revokeAccount("account-a");
            require(session.isClosed(), "Account switch didn't close session");
            require(binding.slot.value.equals(List.of("other-mod")), "Session shutdown lost another mod");
            fails(IllegalStateException.class, () -> session.enqueue(second, queue::add));
            fails(IllegalStateException.class, () -> session.prepare(key, META,
                    TransportContractTest.lease("account-a", EXPIRY, 100)));
        }
        LoopbackRangeServer watched = TransportContractTest.server(uri -> new TransportContractTest.FakeConnection(uri, 100));
        Salt1193StreamAdapter watchedAdapter = new Salt1193StreamAdapter(loader, profile, authorized::get, (uri, flags) -> 1);
        try (Salt1193PlaybackSession session = new Salt1193PlaybackSession(profile, "account-a", authorized::get,
                new FakeBinding(watchedAdapter, authorized), watched)) {
            authorized.set(false);
            long until = System.nanoTime() + 3_000_000_000L;
            while (!session.isClosed() && System.nanoTime() < until) Thread.sleep(20);
            require(session.isClosed(), "Idle permission revoke didn't stop session");
            fails(IllegalStateException.class, () -> watched.register(TransportContractTest.lease("account-a", EXPIRY, 100)));
        }
        System.out.println("PASS: " + checks + " playback-session checks (real media/ABI/HTTP reader; fake engine and queue)");
    }

    private static final class Slot implements ReversibleInterceptorLists.Slot {
        List<?> value = List.of("other-mod");
        @Override public List<?> read() { return value; }
        @Override public void write(List<?> next) { value = next; }
    }

    private static final class FakeBinding implements StreamBinding {
        final Salt1193StreamAdapter adapter;
        final AtomicBoolean authorized;
        final Slot slot = new Slot();
        ReversibleInterceptorLists transaction;
        FakeBinding(Salt1193StreamAdapter adapter, AtomicBoolean authorized) { this.adapter = adapter; this.authorized = authorized; }
        @Override public void register(String path, URI uri) { adapter.register(path, uri); }
        @Override public void unregister(String path) { adapter.unregister(path); }
        @Override public ReversibleInterceptorLists attach(Object engine) throws ReflectiveOperationException {
            if (transaction == null || !transaction.isInstalled()) {
                transaction = ReversibleInterceptorLists.install(List.of(slot), adapter.interceptor(), authorized::get);
            }
            return transaction;
        }
        @Override public boolean isAttached() { return transaction != null && transaction.isInstalled(); }
        @Override public void close() { adapter.close(); if (transaction != null) transaction.close(); }
    }

    private static Continuation<Unit> continuation() {
        return new Continuation<>() {
            @Override public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }
            @Override public void resumeWith(Object result) { throw new AssertionError("Unexpected suspend completion"); }
        };
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void fails(Class<? extends Throwable> expected, Action action) {
        try { action.run(); }
        catch (Throwable failure) { if (expected.isInstance(failure)) { checks++; return; } throw new AssertionError(failure); }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
