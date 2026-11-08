package io.github.yghfv.saltneri.plugin.ui;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.library.*;
import io.github.yghfv.saltneri.plugin.host.Salt1193PlaybackSession.Metadata;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import java.net.URI;
import java.time.*;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

/** Explicit combined arm/confirm tests; recording queue only, no native engine. */
public final class NativeDeskPlaybackContractTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Confirm check " + checks); }
    private static void ready(OnlinePlaybackController player) throws Exception {
        player.prepare(new OnlinePlaybackController.Target(AccountPlatform.NETEASE, "123"));
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!Boolean.TRUE.equals(player.snapshot().get("ready")) && System.nanoTime() < end) Thread.sleep(10);
        check(Boolean.TRUE.equals(player.snapshot().get("ready")));
    }
    @FunctionalInterface interface Action { void run() throws Exception; }
    private static void fails(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected confirm rejection"); }
        catch (IllegalStateException | SecurityException | java.io.IOException expected) { checks++; }
    }
    private static void waitFor(BooleanSupplier value) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!value.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        check(value.getAsBoolean());
    }
    @SuppressWarnings("unchecked")
    private static String trackToken(LibraryController library) {
        return (String)((List<Map<String,Object>>)library.snapshot().get("items")).getFirst().get("token");
    }
    public static void main(String[] args) throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true), armed = new AtomicBoolean(false), failArm = new AtomicBoolean(false), failQueue = new AtomicBoolean(false);
        AtomicInteger arms = new AtomicInteger(), queue = new AtomicInteger();
        var insertions = new java.util.concurrent.CopyOnWriteArrayList<OnlinePlaybackController.QueueAction>();
        Clock clock = Clock.systemUTC(); AtomicReference<Runnable> duringArm = new AtomicReference<>(() -> {});
        AtomicReference<CountDownLatch> gate = new AtomicReference<>(); AtomicInteger resolves = new AtomicInteger();
        try (OnlinePlaybackController player = new OnlinePlaybackController(p -> null, allowed::get, armed::get,
                new OnlinePlaybackController.Player() {
                    public void play(OnlinePlaybackController.Audio audio,BooleanSupplier active) throws Exception {
                        queue.incrementAndGet(); if(failQueue.get()) throw new java.io.IOException("COOKIE_SECRET https://example.invalid/URL_SECRET");
                    }
                    public void insert(OnlinePlaybackController.Audio audio,BooleanSupplier active,OnlinePlaybackController.QueueAction action) throws Exception {
                        check(active.getAsBoolean()); insertions.add(action);
                        if(failQueue.get()) throw new java.io.IOException("COOKIE_SECRET");
                    }
                }, clock,
                a -> (t,q,s,r) -> {
                    resolves.incrementAndGet(); CountDownLatch latch = gate.get(); if (latch != null) latch.await(); r.check();
                    Instant expires = clock.instant().plusSeconds(300);
                    return new OnlinePlaybackController.Audio(TrackKey.netease("123"), new Metadata("Song", "Artist", "Album", "Artist"),
                            new MediaLease(MediaLease.Platform.NETEASE, URI.create("https://m7.music.126.net/fixture?token=URL_SECRET"), Map.of(), s, expires, 4096), expires);
                });
             LibraryController library = new LibraryController(p -> null, allowed::get, player::prepare, () -> player.invalidate("Navigation"), clock,
                     a -> (q,k,s,r) -> new Catalog.Page("Synthetic", List.of(new Catalog.Item(Catalog.Kind.TRACK,"123","Song","Artist",1,true),
                             new Catalog.Item(Catalog.Kind.TRACK,"124","Deleted","Artist",1,false), new Catalog.Item(Catalog.Kind.FOLDER,"10","Folder","",1,true)), false, clock.instant().plusSeconds(300)))) {
            NativeDesk desk = new NativeDesk(library, player, p -> null, (p,a) -> {
                check(a == NativeDesk.Action.ARM && p == AccountPlatform.NETEASE); arms.incrementAndGet();
                if (failArm.get()) throw new IllegalStateException("COOKIE_SECRET");
                armed.set(true); duringArm.get().run();
            }, allowed::get);
            fails(() -> desk.confirmPlayback("stale")); check(arms.get() == 0 && queue.get() == 0);
            ready(player); String token = (String)player.snapshot().get("token");
            check(queue.get() == 0 && !armed.get());
            fails(() -> desk.confirmPlayback("foreign")); check(arms.get() == 0);
            allowed.set(false); fails(() -> desk.confirmPlayback(token)); check(arms.get() == 0 && queue.get() == 0); allowed.set(true);
            ready(player); String current = (String)player.snapshot().get("token"); desk.confirmPlayback(current);
            check(armed.get() && arms.get() == 1 && queue.get() == 1 && !Boolean.TRUE.equals(player.snapshot().get("ready")));
            check(player.snapshot().get("status").toString().contains("SUBMITTED") && !player.snapshot().toString().contains("URL_SECRET"));
            check(player.snapshot().get("error").equals(""));
            fails(() -> desk.confirmPlayback(current)); check(arms.get() == 1 && queue.get() == 1);
            ready(player); desk.confirmPlayback((String)player.snapshot().get("token")); check(arms.get() == 1 && queue.get() == 2);
            armed.set(false); failArm.set(true); ready(player); fails(() -> desk.confirmPlayback((String)player.snapshot().get("token")));
            check(queue.get() == 2 && player.snapshot().get("status").toString().contains("ADAPTER_SETUP") && !player.snapshot().toString().contains("SECRET"));
            check(player.snapshot().get("error").toString().contains("ADAPTER_SETUP"));
            failArm.set(false); duringArm.set(() -> player.invalidate("Revision changed")); ready(player);
            fails(() -> desk.confirmPlayback((String)player.snapshot().get("token"))); check(queue.get() == 2);
            duringArm.set(() -> {}); failQueue.set(true); ready(player); String failed = (String)player.snapshot().get("token");
            fails(() -> desk.confirmPlayback(failed)); check(queue.get() == 3 && !Boolean.TRUE.equals(player.snapshot().get("ready")));
            check(player.snapshot().get("status").toString().contains("QUEUE_SUBMIT") && !player.snapshot().toString().contains("SECRET"));
            check(player.snapshot().get("error").toString().contains("QUEUE_SUBMIT"));
            fails(() -> desk.confirmPlayback(failed)); check(queue.get() == 3);
            failQueue.set(false); armed.set(false);
            fails(() -> desk.playRow("stale")); check(queue.get() == 3);
            library.browse(AccountPlatform.NETEASE, Catalog.View.DISCOVER);
            waitFor(() -> !((List<?>)library.snapshot().get("items")).isEmpty());
            String row = trackToken(library);
            library.accountChanged(AccountPlatform.BILIBILI); check(library.currentRow(row));
            var items = (List<?>)library.snapshot().get("items");
            fails(() -> desk.playRow((String)((Map<?,?>)items.get(1)).get("token")));
            check(items.size() == 2 && items.stream().allMatch(item -> Boolean.TRUE.equals(((Map<?,?>)item).get("enabled"))));
            check(queue.get() == 3);
            desk.playRow(row); waitFor(() -> queue.get() == 4);
            check(armed.get() && player.snapshot().get("status").toString().contains("SUBMITTED"));
            Thread.sleep(250); check(queue.get() == 4);
            failQueue.set(true); desk.playRow(row); waitFor(() -> player.snapshot().get("status").toString().contains("QUEUE_SUBMIT"));
            check(queue.get() == 5); Thread.sleep(250); check(queue.get() == 5); failQueue.set(false);
            armed.set(false); failArm.set(true); desk.playRow(row);
            waitFor(() -> player.snapshot().get("status").toString().contains("ADAPTER_SETUP")); check(queue.get() == 5); failArm.set(false);
            for (int mode = 0; mode < 3; mode++) {
                CountDownLatch blocked = new CountDownLatch(1); gate.set(blocked); int previous = resolves.get(), previousArms = arms.get();
                desk.playRow(row); waitFor(() -> resolves.get() > previous);
                if (mode == 0) desk.leave();
                if (mode == 1) allowed.set(false);
                if (mode == 2) player.prepare(new OnlinePlaybackController.Target(AccountPlatform.NETEASE,"123"));
                gate.set(null); blocked.countDown(); Thread.sleep(250);
                check(queue.get() == 5 && arms.get() == previousArms);
                allowed.set(true);
                library.browse(AccountPlatform.NETEASE, Catalog.View.DISCOVER);
                waitFor(() -> !((List<?>)library.snapshot().get("items")).isEmpty()); row = trackToken(library);
            }
            duringArm.set(() -> player.invalidate("Changed during internal setup")); armed.set(false); desk.playRow(row);
            waitFor(armed::get); Thread.sleep(250); check(queue.get() == 5); duringArm.set(() -> {});
            for(var position:List.of(OnlinePlaybackController.QueueAction.AFTER_CURRENT,OnlinePlaybackController.QueueAction.AFTER_LAST_ADDED,OnlinePlaybackController.QueueAction.AT_QUEUE_END)) {
                int before=insertions.size(); desk.insertRow(row,position); waitFor(()->insertions.size()==before+1);
                check(insertions.getLast()==position && queue.get()==5);
                Thread.sleep(150); check(insertions.size()==before+1);
            }
            fails(()->desk.insertRow("stale",OnlinePlaybackController.QueueAction.AFTER_CURRENT));
            failQueue.set(true); desk.insertRow(row,OnlinePlaybackController.QueueAction.AFTER_CURRENT);
            waitFor(()->player.snapshot().get("status").toString().contains("QUEUE_SUBMIT"));
            check(insertions.size()==4 && !player.snapshot().toString().contains("SECRET"));
            Thread.sleep(150); check(insertions.size()==4); failQueue.set(false);
            for(int mode=0;mode<3;mode++) {
                CountDownLatch blocked=new CountDownLatch(1); gate.set(blocked); int before=resolves.get();
                desk.insertRow(row,OnlinePlaybackController.QueueAction.AT_QUEUE_END); waitFor(()->resolves.get()>before);
                if(mode==0) desk.leave();
                if(mode==1) allowed.set(false);
                if(mode==2) player.accountChanged(AccountPlatform.NETEASE);
                gate.set(null); blocked.countDown(); Thread.sleep(200);
                check(insertions.size()==4 && queue.get()==5); allowed.set(true);
            }
        }
        System.out.println("PASS: " + checks + " explicit arm/confirmation/stale/revoked/setup/submission checks (recording queue)");
    }
}
