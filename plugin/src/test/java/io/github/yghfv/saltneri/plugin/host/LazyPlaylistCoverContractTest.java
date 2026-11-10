package io.github.yghfv.saltneri.plugin.host;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.library.*;
import io.github.yghfv.saltneri.plugin.transport.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import kotlin.Unit;
import kotlin.coroutines.*;

/** Retain the ORIGINAL native item while queue updates wait, as Salt's onLoaded callback does. */
public final class LazyPlaylistCoverContractTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if(!ok) throw new AssertionError("Playlist artwork "+checks); }
    private static final class Time extends Clock {
        Instant now=Instant.parse("2026-11-07T00:00:00Z");
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
    private static byte[] png(int color) throws IOException {
        var image=new BufferedImage(160,90,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<90;y++) for(int x=0;x<160;x++) image.setRGB(x,y,color);
        try(var bytes=new ByteArrayOutputStream()) { ImageIO.write(image,"png",bytes); return bytes.toByteArray(); }
    }
    private static Object open(Salt1193StreamAdapter adapter,Object item) throws Exception {
        var create=Class.forName("androidx.compose.ui.\u015b").getMethods()[0];
        Continuation<Unit> continuation=new Continuation<>() {
            public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }
            public void resumeWith(Object result) { throw new AssertionError("Unexpected suspension"); }
        };
        Object box=create.invoke(adapter.interceptor(),null,item,0L,0L,0,continuation);
        Object raw=Class.forName("kotlin.Result").getMethod("unbox-impl").invoke(box);
        kotlin.ResultKt.throwOnFailure(raw); return raw;
    }
    public static void main(String[] args) throws Exception {
        ClassLoader loader=LazyPlaylistCoverContractTest.class.getClassLoader();
        var profile=Salt1193Probe.inspect(loader,"1.19.3","Steam"); check(profile.matched());
        Class<?> tags=Class.forName("com.xuncorp.voxzen.tag.TagParser"); Object parser=tags.getField("INSTANCE").get(null);
        var extract=tags.getDeclaredMethod("readArtworkBinaryData",File.class); check(extract.trySetAccessible());
        for(MediaLease.Platform platform:MediaLease.Platform.values()) {
            Time time=new Time(); var allowed=new AtomicBoolean(true); var resolutions=new AtomicInteger();
            var nativeItem=new AtomicReference<Object>(); var expected=new AtomicReference<byte[]>();
            List<Object> queued=new ArrayList<>(), pendingUpdates=new ArrayList<>();
            List<Catalog.Item> rows=new ArrayList<>(); List<byte[]> pictures=new ArrayList<>();
            for(int i=1;i<=12;i++) {
                rows.add(new Catalog.Item(Catalog.Kind.TRACK,""+i,"Row "+i,"Artist",1000,true));
                pictures.add(png(0xff000000 | (i*17)<<16 | (255-i*17)<<8 | i));
            }
            List<String> paths=new ArrayList<>();
            try(var server=TransportContractTest.offlineServer(time,allowed::get);
                var adapter=new Salt1193StreamAdapter(loader,profile,allowed::get,(uri,flags)->{
                    // The callback reads the original selected object, NOT the updated queue snapshot.
                    String path=profile.mediaFactory().read(nativeItem.get())[5];
                    check(Arrays.equals(expected.get(),(byte[])extract.invoke(parser,Path.of(path).toFile())));
                    return 321;
                })) {
                var queue=new Salt1193PlaybackSession.QueueSink() {
                    public void addToPlay(Object item) { throw new AssertionError("Queue append"); }
                    public void replace(List<Object> items) { queued.addAll(items); }
                    public void update(Object item) { pendingUpdates.add(item); }
                };
                try(var session=new LazyPlaylistSession(profile,adapter,server,queue,rows,"fixture",allowed::get,(row,scope,request)->{
                    resolutions.incrementAndGet(); int number=Integer.parseInt(row.target());
                    TrackKey key=switch(platform) {
                        case NETEASE -> TrackKey.netease(row.target());
                        case BILIBILI -> TrackKey.bilibili("BV17x411w7KC",number);
                        case QQMUSIC -> TrackKey.qqmusic(String.format(Locale.ROOT,"00%012d",number));
                        case KUGOU -> TrackKey.kugou(String.format(Locale.ROOT,"%032X",number));
                        case APPLEMUSIC -> TrackKey.appleMusic("us:"+number);
                    };
                    Instant expiry=time.instant().plusSeconds(60);
                    URI source=URI.create(switch(platform) {
                        case NETEASE -> "https://m7.music.126.net/fixture";
                        case BILIBILI -> "https://test.bilivideo.com/fixture";
                        case QQMUSIC -> "https://dl.stream.qqmusic.qq.com/fixture";
                        case KUGOU -> "https://fs.kugou.com/fixture";
                        case APPLEMUSIC -> "https://music.apple.com/fixture";
                    });
                    MediaLease lease=platform==MediaLease.Platform.APPLEMUSIC?
                        MediaLease.appleMusic(new io.github.yghfv.saltneri.plugin.applemusic.AppleMusicPcmSource(1000,allowed::get,
                            ()->{throw new java.io.IOException("Artwork-only fixture");}),scope,expiry,allowed::get):
                        new MediaLease(platform,source,Map.of(),scope,expiry,100);
                    return new OnlinePlaybackController.Audio(key,new Salt1193PlaybackSession.Metadata("Resolved "+row.target(),"Artist","","",pictures.get(number-1)),
                            lease,expiry);
                },row->request->"[00:01.000]"+row.title())) {
                    check(resolutions.get()==0 && queued.isEmpty());
                    session.play(); check(resolutions.get()==1 && queued.size()==12);
                    List<Object> originals=List.copyOf(queued);
                    for(int i=0;i<12;i++) {
                        String[] fields=profile.mediaFactory().read(originals.get(i)); paths.add(fields[5]);
                        check(PlaybackCoverFile.isPath(fields[5]));
                        if(i>0) check(!Files.exists(Path.of(fields[5]).getParent()));
                    }
                    for(int i=0;i<12;i++) {
                        nativeItem.set(originals.get(i)); expected.set(pictures.get(i));
                        check(open(adapter,nativeItem.get()).equals(321));
                        check(profile.mediaFactory().read(originals.get(i))[5].equals(paths.get(i)));
                        check(queued.get(i)==originals.get(i));
                        if(i>0) {
                            Object updated=pendingUpdates.getLast();
                            check(profile.mediaFactory().read(updated)[5].equals(paths.get(i)));
                            check(!profile.mediaFactory().read(updated)[1].equals(profile.mediaFactory().read(originals.get(i))[1]));
                        }
                    }
                    check(resolutions.get()==12 && pendingUpdates.size()==11);
                    check(Files.size(Path.of(paths.getFirst()))==42);
                    nativeItem.set(originals.getFirst()); expected.set(pictures.getFirst());
                    check(open(adapter,nativeItem.get()).equals(321) && resolutions.get()==13);
                    check(open(adapter,nativeItem.get()).equals(321) && resolutions.get()==13);
                    time.now=time.now.plusSeconds(61);
                    nativeItem.set(originals.get(1)); expected.set(pictures.get(1));
                    check(open(adapter,nativeItem.get()).equals(321) && resolutions.get()==14);
                    String[] selected=profile.mediaFactory().read(nativeItem.get());
                    check(OnlineLyrics.load(selected[0],selected[5]).contains("Row 2"));
                    Object image=tags.getMethod("readImage-0E7RQCE",String.class,int.class,int.class).invoke(parser,selected[5],160,90);
                    kotlin.ResultKt.throwOnFailure(image);
                    var bitmap=androidx.compose.ui.graphics.SkiaImageAsset_skikoKt.asSkiaBitmap((androidx.compose.ui.graphics.ImageBitmap)image);
                    try { check(bitmap.getWidth()==160 && bitmap.getHeight()==90); check(bitmap.getColor(0,0)==0xff22dd02); }
                    finally { bitmap.close(); }
                    allowed.set(false);
                    try { open(adapter,nativeItem.get()); throw new AssertionError("Retired account opened"); }
                    catch(IllegalStateException expectedFailure) { checks++; }
                }
                for(String path:paths) check(!PlaybackCoverFile.isPath(path) && !Files.exists(Path.of(path).getParent()));
            }
        }
        System.out.println("PASS: "+checks+" lazy playlist original-item artwork, deferred queue updates, next/back/expiry/LRU, aspect ratio and revocation checks; real Salt tag reader, synthetic images, no audio/network");
    }
}
