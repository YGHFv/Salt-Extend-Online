package io.github.yghfv.saltneri.plugin.transport;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.host.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.*;

/** Real native media/ABI/HTTP reader; fake audio handles, host service and recording queue. */
public final class PlatformPlaybackContractTest {
    private static int checks;
    private static void check(boolean v) { checks++; if (!v) throw new AssertionError("Platform play check " + checks); }
    private static final class Binding implements StreamBinding {
        final Salt1193StreamAdapter adapter; boolean attached, closed;
        Binding(Salt1193StreamAdapter adapter) { this.adapter = adapter; }
        public void register(String p,URI u) { adapter.register(p,u); } public void unregister(String p) { adapter.unregister(p); }
        public ReversibleInterceptorLists attach(Object player) { attached=true; return null; }
        public boolean isAttached() { return attached&&!closed; } public void close() { closed=true; adapter.close(); }
    }
    private static Object create(Salt1193StreamAdapter adapter,Object item) throws Exception {
        var method=Class.forName("androidx.compose.ui.\u015b").getMethods()[0];
        Continuation<Unit> continuation=new Continuation<>() { public CoroutineContext getContext(){return EmptyCoroutineContext.INSTANCE;} public void resumeWith(Object result){throw new AssertionError("Unexpected resume");} };
        Object box=method.invoke(adapter.interceptor(),null,item,0L,0L,0,continuation);
        return Class.forName("kotlin.Result").getMethod("unbox-impl").invoke(box);
    }
    private static void retired(Salt1193StreamAdapter adapter,Object item) throws Exception {
        Object result=create(adapter,item); try { ResultKt.throwOnFailure(result); throw new AssertionError("Retired mapping played"); } catch (IllegalStateException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        ClassLoader loader=PlatformPlaybackContractTest.class.getClassLoader(); var profile=Salt1193Probe.inspect(loader,"1.19.3","Steam");
        Clock clock=Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"),ZoneOffset.UTC); AtomicInteger outbound=new AtomicInteger();
        LoopbackRangeServer server=new LoopbackRangeServer(clock,uri->new TransportContractTest.FakeConnection(uri,100){ public int getResponseCode(){outbound.incrementAndGet();check(getRequestProperty("Cookie")==null);return super.getResponseCode();}},()->true);
        Salt1193StreamAdapter adapter=new Salt1193StreamAdapter(loader,profile,()->true,(local,flags)->{try(HostRangeReader reader=HostRangeReader.open(local)){check(reader.length()==100);reader.seek(90);byte[] tail=new byte[10];check(reader.read(tail)==10&&tail[9]==99);}return 321;});
        Binding binding=new Binding(adapter); EngineLinkContractTest.Hooks hooks=new EngineLinkContractTest.Hooks(); List<Object> queue=new ArrayList<>();
        var metadata=new Salt1193PlaybackSession.Metadata("Fixture title","Artist","Album","Artist");
        AtomicBoolean netActive=new AtomicBoolean(true); var expiry=clock.instant().plusSeconds(300);
        var net=new MediaLease(MediaLease.Platform.NETEASE,URI.create("https://m7.music.126.net/audio"),Map.of(),"netease-A",expiry,100);
        var bili=new MediaLease(MediaLease.Platform.BILIBILI,URI.create("https://upos-fixture.bilivideo.com/audio"),Map.of(),"bili-A",expiry,100);
        try(Salt1193DiagnosticRuntime runtime=new Salt1193DiagnosticRuntime(profile,binding,server,new EngineLinkContractTest.FakeHost(),hooks,queue::add,()->true)) {
            check(queue.isEmpty()); runtime.playTrack(TrackKey.netease("123"),metadata,net,netActive::get); runtime.playTrack(TrackKey.bilibili("BV17x411w7KC",279786),metadata,bili,()->true);
            check(queue.size()==2); Object netItem=queue.get(0),biliItem=queue.get(1); check(create(adapter,netItem).equals(321));check(create(adapter,biliItem).equals(321));
            runtime.revokePlatform(MediaLease.Platform.NETEASE); retired(adapter,netItem); check(!runtime.isClosed()&&binding.isAttached());check(create(adapter,biliItem).equals(321));check(queue.size()==2);
            runtime.playTrack(TrackKey.netease("123"),metadata,net,netActive::get); Object nextNet=queue.getLast(); check(!profile.mediaFactory().read(nextNet)[0].equals(profile.mediaFactory().read(netItem)[0]));
            netActive.set(false); Thread.sleep(400); retired(adapter,nextNet); check(create(adapter,biliItem).equals(321));
            runtime.close();check(hooks.unhooks==1&&binding.closed);check(queue.size()==3);check(outbound.get()>=4);
        }
        System.out.println("PASS: "+checks+" dual-platform native preparation/reader/scoped-revoke checks (fake engine/queue; no BASS)");
    }
}
