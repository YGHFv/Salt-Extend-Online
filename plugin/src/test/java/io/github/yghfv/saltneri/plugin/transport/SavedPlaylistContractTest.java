package io.github.yghfv.saltneri.plugin.transport;

import io.github.yghfv.saltneri.plugin.host.*;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.library.*;
import io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.*;
import io.github.yghfv.saltneri.plugin.transport.*;
import io.github.yghfv.saltneri.core.TrackKey;
import java.nio.file.*;
import java.time.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import kotlin.Unit;
import kotlin.coroutines.*;

/** Real native Track conversion/interceptor with durable fixtures and fake audio handles only. */
public final class SavedPlaylistContractTest {
    private static int checks;
    private static void check(boolean value,String message) {checks++;if(!value) throw new AssertionError(message);}
    private static final class Binding implements StreamBinding {
        final Salt1193StreamAdapter adapter;
        Binding(Salt1193StreamAdapter adapter) {this.adapter=adapter;}
        public void register(String path,URI uri) {adapter.register(path,uri);}
        public void registerDeferred(String path,Deferred source) {adapter.registerDeferred(path,source);}
        public void unregister(String path) {adapter.unregister(path);}
        public ReversibleInterceptorLists attach(Object engine) {return null;}
        public boolean isAttached() {return true;}
        public void close() {adapter.close();}
    }
    private static Object open(Salt1193StreamAdapter adapter,Object item) throws Exception {
        var method=Class.forName("androidx.compose.ui.\u015b").getMethods()[0];
        Continuation<Unit> continuation=new Continuation<>() {
            public CoroutineContext getContext() {return EmptyCoroutineContext.INSTANCE;}
            public void resumeWith(Object value) {throw new AssertionError("Unexpected suspension");}
        };
        Object result=method.invoke(adapter.interceptor(),null,item,0L,0L,0,continuation);
        return Class.forName("kotlin.Result").getMethod("unbox-impl").invoke(result);
    }
    public static void main(String[] args) throws Exception {
        ClassLoader host=SavedPlaylistContractTest.class.getClassLoader(); var profile=Salt1193Probe.inspect(host,"1.19.3","Steam");
        var gateway=new SaltPlaylistGateway(host); check(profile.matched(),"Native profile missing");
        Path root=Files.createTempDirectory("salt-saved-fixture-").toRealPath();
        byte[] png=PlaybackCoverContractTest.png();
        List<String> paths=new ArrayList<>(),ids=new ArrayList<>();
        try(var store=new SavedTrackStore(root)) {
            for(var platform:AccountPlatform.values()) {
                String remote=platform==AccountPlatform.QQMUSIC?"00123456789abc":platform==AccountPlatform.NETEASE?"123":"BV17x411w7KC";
                var row=new Catalog.Item(Catalog.Kind.TRACK,remote,"Fixture title","Fixture artist",120000,true);
                var entry=store.save(new Target(platform,remote),row);
                check(store.save(new Target(platform,remote),new Catalog.Item(Catalog.Kind.TRACK,remote,"Changed","Changed",1,true))==entry,"Duplicate save changed metadata");
                entry.cover().replace(png);paths.add(entry.path());ids.add(entry.trackId());
                check(Files.size(Path.of(entry.path()))>42,"Saved cover not materialized");
                Object track=gateway.nativeTrack(entry);
                var nativeItems=(List<?>)Class.forName("androidx.compose.ui.\u0b71").getMethod("\u037f",Collection.class).invoke(null,List.of(track));
                String[] values=profile.mediaFactory().read(nativeItems.getFirst());
                check(values[0].equals(entry.trackId()) && values[5].equals(entry.path()),"Native playlist conversion lost stable ID/path");
                check((long)track.getClass().getMethod("getDuration").invoke(track)==120000,"Native duration lost");
            }
            try {store.save(new Target(AccountPlatform.NETEASE,"https://cdn.invalid/?token=secret"),new Catalog.Item(Catalog.Kind.TRACK,"bad","Bad","",0,true));throw new AssertionError("URL persisted");}
            catch(IllegalArgumentException expected) {checks++;}
        }
        check(paths.stream().allMatch(p->Files.isRegularFile(Path.of(p))),"Shutdown deleted saved tracks");
        // Corrupt entries must not suppress the remaining library.
        Files.writeString(root.resolve("f".repeat(64)+".track"),"bad fixture");
        AtomicInteger revision=new AtomicInteger(),calls=new AtomicInteger(),streams=new AtomicInteger();AtomicBoolean active=new AtomicBoolean(true);
        AtomicReference<Instant> now=new AtomicReference<>(Instant.now());
        Clock clock=new Clock() {public Instant instant(){return now.get();}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}};
        try(var store=new SavedTrackStore(root);var server=new LoopbackRangeServer(clock,uri->{throw new AssertionError("No upstream access");},active::get);
            var adapter=new Salt1193StreamAdapter(host,profile,active::get,(uri,flags)->{check(server.isAvailable(uri),"Lease unavailable");streams.incrementAndGet();return 321;});
            var session=new SavedTrackSession(new Binding(adapter),server,active::get,new SavedTrackSession.Resolver() {
                public Object revision(AccountPlatform platform) {return revision.get();}
                public Audio resolve(SavedTrackStore.Entry entry,io.github.yghfv.saltneri.plugin.net.RequestScope request) {
                    calls.incrementAndGet();request.check();var target=entry.target();boolean net=target.platform()==AccountPlatform.NETEASE;
                    Instant expiry=clock.instant().plusSeconds(600);
                    boolean qq=target.platform()==AccountPlatform.QQMUSIC;
                    return new Audio(qq?TrackKey.qqmusic(target.id()):net?TrackKey.netease(target.id()):TrackKey.bilibili(target.id(),123),new Salt1193PlaybackSession.Metadata("Fixture","Artist","","",png),
                            new MediaLease(qq?MediaLease.Platform.QQMUSIC:net?MediaLease.Platform.NETEASE:MediaLease.Platform.BILIBILI,URI.create(qq?"https://dl.stream.qqmusic.qq.com/fixture":net?"https://m7.music.126.net/fixture":"https://test.bilivideo.com/fixture"),Map.of(),"fixture-"+revision.get(),expiry,100),expiry,target);
                }
                public OnlineLyrics.Source lyrics(SavedTrackStore.Entry entry) {return r->"[00:01.000]Fixture lyrics";}
            })) {
            check(store.entries().size()==AccountPlatform.values().length,"Restart lost entries");
            store.entries().forEach(session::register);check(calls.get()==0,"Registering saved playlist resolved audio");
            for(var entry:store.entries()) {
                check(paths.contains(entry.path()) && ids.contains(entry.trackId()),"Restart changed logical identity");
                Object nativeTrack=gateway.nativeTrack(entry);
                Object item=((List<?>)Class.forName("androidx.compose.ui.\u0b71").getMethod("\u037f",Collection.class).invoke(null,List.of(nativeTrack))).getFirst();
                int before=calls.get();check(open(adapter,item).equals(321) && calls.get()==before+1,"Native saved song did not resolve");
                check(open(adapter,item).equals(321) && calls.get()==before+1,"Saved replay did not reuse lease");
                now.updateAndGet(t->t.plusSeconds(601));check(open(adapter,item).equals(321) && calls.get()==before+2,"Expired saved URL not renewed");
                revision.incrementAndGet();session.revoke(entry.target().platform());
                check(open(adapter,item).equals(321) && calls.get()==before+3,"Account change lost saved binding");
                check(OnlineLyrics.load(entry.trackId(),entry.path()).contains("Fixture lyrics"),"Saved song lyrics missing");
            }
            var queue=new ArrayList<Object>();var rows=new ArrayList<Catalog.Item>();
            for(int i=200;i<212;i++) rows.add(new Catalog.Item(Catalog.Kind.TRACK,""+i,"Queue "+i,"Artist",1000,true));
            var sink=new Salt1193PlaybackSession.QueueSink() {
                public void addToPlay(Object item) {throw new AssertionError("Unexpected single play");}
                public int firstIndex(int size) {return 3;}
                public void replace(List<Object> items,int index) {check(index==3,"Native random index lost");queue.addAll(items);}
            };
            int beforeQueue=calls.get();
            try(var list=new LazyPlaylistSession(profile,new Binding(adapter),server,sink,rows,"fixture",active::get,
                    (row,scope,request)->{throw new AssertionError("Temporary playlist resolver used");},row->null,session,row->{
                        try{return store.save(new Target(AccountPlatform.NETEASE,row.target()),row);}catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
                    })) {
                check(calls.get()==beforeQueue,"Building queue fetched all audio");list.play();
                check(queue.size()==12 && calls.get()==beforeQueue+1,"Persistent playlist is not lazy");
                for(Object item:queue) check(open(adapter,item).equals(321),"Persistent queue open failed");
            }
            check(open(adapter,queue.getFirst()).equals(321),"Leaving playlist retired a saved queue binding");
            String[] queueFields=profile.mediaFactory().read(queue.getFirst());
            check(store.owns(queueFields[0],queueFields[5]) && !store.owns(queueFields[0],"foreign"),"Queue did not publish a restorable owned identity");
            Object restored=profile.mediaFactory().create(queueFields[0],queueFields[1],queueFields[2],queueFields[3],queueFields[4],queueFields[5]);
            check(open(adapter,restored).equals(321),"Native persisted media roundtrip failed");
            int before=streams.get(); active.set(false);
            try {var e=store.entries().getFirst();kotlin.ResultKt.throwOnFailure(open(adapter,profile.mediaFactory().create(e.trackId(),e.title(),e.artist(),"","",e.path())));throw new AssertionError("Permission bypass");}
            catch(IllegalStateException expected) {checks++;}
            check(streams.get()==before,"Revoked stream opened");
        }
        check(paths.stream().allMatch(p->Files.isRegularFile(Path.of(p))),"Revocation deleted native playlist files");
        runtime(profile);
        System.out.println("PASS: "+checks+" durable native playlist identity, restart, lease renewal, cover, lyrics and permission checks; no real playlists/network/playback");
    }
    private static void runtime(Salt1193Probe.Result profile) throws Exception {
        Path root=Files.createTempDirectory("salt-restart-runtime-").toRealPath();
        var queue=new ArrayList<Object>();var actions=new ArrayList<QueueAction>();var resolves=new AtomicInteger();
        var target=new Target(AccountPlatform.NETEASE,"987");var expiry=Instant.now().plusSeconds(600);
        var audio=new Audio(TrackKey.netease("987"),new Salt1193PlaybackSession.Metadata("Fixture","Artist","Album","Artist"),
                new MediaLease(MediaLease.Platform.NETEASE,URI.create("https://m7.music.126.net/fixture"),Map.of(),"fixture",expiry,100),expiry,target);
        for(int launch=0;launch<2;launch++) {
            int pass=launch;
            var server=new LoopbackRangeServer(Clock.systemUTC(),uri->{throw new AssertionError("No upstream");},()->true);
            var adapter=new Salt1193StreamAdapter(SavedPlaylistContractTest.class.getClassLoader(),profile,()->true,(uri,flags)->321);
            try(var store=new SavedTrackStore(root);var runtime=new Salt1193DiagnosticRuntime(profile,new Binding(adapter),server,
                    new EngineLinkContractTest.FakeHost(),new EngineLinkContractTest.Hooks(),new Salt1193PlaybackSession.QueueSink() {
                        public void addToPlay(Object item) {queue.add(item);actions.add(QueueAction.PLAY);}
                        public void insert(Object item,QueueAction action) {queue.add(item);actions.add(action);}
                    },()->true)) {
                runtime.savedTracks(store,new SavedTrackSession.Resolver() {
                    public Object revision(AccountPlatform platform) {return "fixture";}
                    public Audio resolve(SavedTrackStore.Entry entry,io.github.yghfv.saltneri.plugin.net.RequestScope request) {resolves.incrementAndGet();return audio;}
                });
                if(pass==0) {
                    for(var action:QueueAction.values()) runtime.submitOnline(audio,()->true,action);
                    check(actions.equals(List.of(QueueAction.values())),"Single play/insertion native dispatch changed");
                    check(open(adapter,queue.getFirst()).equals(321) && resolves.get()==0,"Primed playback redundantly resolved");
                    String[] fields=profile.mediaFactory().read(queue.getFirst());check(store.owns(fields[0],fields[5]),"Single play retained temporary path");
                    try {runtime.submitOnline(audio,()->false,QueueAction.PLAY);throw new AssertionError("Revoked submit");}catch(IllegalStateException expected){checks++;}
                } else {
                    check(actions.size()==4 && resolves.get()==0,"Startup fetched audio or changed queue");
                    check(open(adapter,queue.getFirst()).equals(321) && resolves.get()==1,"Fresh runtime failed to open restored native queue item");
                }
            }
        }
    }
}
