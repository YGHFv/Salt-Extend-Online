package io.github.yghfv.saltneri.plugin.ui;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.library.CoverSource;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;

/** Synthetic PNG/JPEG only, fake HTTPS, real decoder; never requests a user's image or audio. */
public final class CoverLoaderContractTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Cover check " + checks); }
    private static final URI SOURCE = URI.create("https://i0.hdslb.com/bfs/archive/fixture.png");
    public static byte[] png() throws IOException {
        return png(240, 120);
    }
    public static byte[] png(int width, int height) throws IOException {
        return picture(width, height, "png");
    }
    static byte[] jpeg() throws IOException { return picture(240, 120, "jpeg"); }
    private static byte[] picture(int width, int height, String format) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, x < width / 2 ? 0xffdd5500 : 0xff008866);
        try {
            var bytes = new ByteArrayOutputStream(); ImageIO.write(image, format, bytes); return bytes.toByteArray();
        } finally { image.flush(); }
    }
    static final class Response extends HttpURLConnection {
        byte[] bytes; int status = 200; String type = "image/png", encoding; long length = -1; boolean closed;
        Response(URI uri, byte[] bytes) throws IOException { super(uri.toURL()); this.bytes = bytes; }
        public void connect() { } public boolean usingProxy() { return false; } public void disconnect() { closed = true; }
        public int getResponseCode() { return status; }
        public String getHeaderField(String name) { return name.equalsIgnoreCase("Content-Type") ? type : name.equalsIgnoreCase("Content-Encoding") ? encoding : null; }
        public long getContentLengthLong() { return length; }
        public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
    }
    @FunctionalInterface interface Action { void run() throws Exception; }
    private static void rejects(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected image rejection"); }
        catch (IOException expected) { check(!expected.toString().contains("SECRET")); }
    }
    public static void main(String[] args) throws Exception {
        check(SOURCE.equals(CoverSource.parse(AccountPlatform.BILIBILI, "//i0.hdslb.com/bfs/archive/fixture.png")));
        check(SOURCE.equals(CoverSource.parse(AccountPlatform.BILIBILI, SOURCE.toString().replace("https:", "http:"))));
        check(CoverSource.parse(AccountPlatform.NETEASE, "http://p1.music.126.net/abc/fixture.jpg") != null);
        check(CoverSource.parse(AccountPlatform.NETEASE, "http://p1.music.126.net/fixture_base64==/fixture.jpg") != null);
        for(String source:List.of("https://music-file.y.qq.com/songlist/u/fixture.jpg?imageView2/4/w/300/h/300",
                "http://qpic.y.qq.com/music_cover/fixture/300?n=1","https://p.qpic.cn/music_cover/fixture/600?n=1&max_age=2592000",
                "https://y.gtimg.cn/music/photo_new/T002R500x500M000fixture.jpg?max_age=2592000",
                "https://q.qlogo.cn/headimg_dl?dst_uin=42&spec=640")) {
            URI qq=CoverSource.parse(AccountPlatform.QQMUSIC,source);
            check(qq!=null && qq.getScheme().equals("https"));
            check(qq.getHost().equals("q.qlogo.cn") || qq.getRawQuery()==null);
            try(var loader=new CoverLoader(uri->new Response(uri,png()));var scope=new RequestScope(()->true)) {
                check(loader.load(qq,scope).length>0);
            }
        }
        for(String bad:List.of("https://music-file.y.qq.com/songlist/u/fixture.jpg?vkey=SECRET",
                "https://music-file.y.qq.com/songlist/u/fixture.jpg?imageView2/4/w/300/h/300&token=SECRET",
                "https://qpic.y.qq.com/music_cover/fixture/300?n=1&token=SECRET",
                "https://music-file.y.qq.com.evil/songlist/fixture.jpg",
                "https://q.qlogo.cn/headimg_dl?dst_uin=42&spec=640&token=SECRET",
                "https://u.y.qq.com/cgi-bin/musicu.fcg"))check(CoverSource.parse(AccountPlatform.QQMUSIC,bad)==null);
        for (String input : List.of("", "fixture", "https://i0.hdslb.com/fixture?token=URL_SECRET", "https://user:COOKIE_SECRET@i0.hdslb.com/a",
                "https://i0.hdslb.com:443/a", "https://i0.hdslb.com/a#URL_SECRET", "https://i0.hdslb.com.evil.example/a", "https://127.0.0.1/a",
                "https://m7.music.126.net/audio", "file:///a", "//evil.example/a")) {
            check(CoverSource.parse(AccountPlatform.BILIBILI, input) == null);
            check(CoverSource.parse(AccountPlatform.NETEASE, input) == null);
        }
        byte[] fixture = png(); Response response = new Response(SOURCE, fixture);
        URI album=URI.create("https://y.qq.com/music/photo_new/T002R800x800M00000123456789abc.jpg");
        URI albumCdn=URI.create("https://y.gtimg.cn/music/photo_new/T002R800x800M00000123456789abc.jpg");
        URI songPicture=URI.create("https://y.qq.com/music/photo_new/T062R800x800M00000123456789abc.jpg");
        var songRequests=new ArrayList<URI>();
        try(var loader=new CoverLoader(uri->{
            songRequests.add(uri);var songResponse=new Response(uri,fixture);
            if(uri.getHost().equals("y.qq.com"))songResponse.status=404;
            return songResponse;
        });var scope=new RequestScope(()->true)) {
            check(loader.load(songPicture,scope).length>0);
            check(songRequests.size()==2 && songRequests.getLast().equals(CoverSource.playbackFallback(songPicture)));
            check(loader.load(songPicture,scope).length>0 && songRequests.size()==2);
            check(loader.loadPlayback(songPicture,scope).length>0 && songRequests.size()==4);
        }
        check(albumCdn.equals(CoverSource.playbackFallback(album)));
        for(URI bad:List.of(SOURCE,URI.create("https://y.qq.com/other.jpg"),URI.create(album+"?token=SECRET"),
                URI.create("https://y.qq.com/music/photo_new/T002R800x800M000wrong.jpg")))
            check(CoverSource.playbackFallback(bad)==null);
        var albumRequests=new ArrayList<URI>();
        try(var loader=new CoverLoader(uri->{
            albumRequests.add(uri);var result=new Response(uri,fixture);
            if(uri.equals(album))result.status=404;return result;
        });var scope=new RequestScope(()->true)) {
            check(loader.loadPlayback(album,scope).length>0);
            check(albumRequests.equals(List.of(album,albumCdn)));
        }
        var loads=new AtomicInteger();
        try(var loader=new CoverLoader(uri->{loads.incrementAndGet();return new Response(uri,fixture);});var scope=new RequestScope(()->true)) {
            byte[] first=loader.load(SOURCE,scope);
            check(Arrays.equals(first,loader.load(URI.create(SOURCE+"@96w.webp"),scope)) && loads.get()==1);
            loader.clear(); loader.load(SOURCE,scope); check(loads.get()==2);
            for(int i=0;i<=CoverLoader.THUMBNAIL_ENTRIES;i++) loader.load(URI.create("https://i0.hdslb.com/"+i+".png"),scope);
            int before=loads.get(); loader.load(SOURCE,scope); check(loads.get()==before+1);
            try(var cancelled=new RequestScope(()->true)) {
                cancelled.close(); try {loader.load(SOURCE,cancelled);throw new AssertionError("Cancelled thumbnail reused");}
                catch(CancellationException expected){checks++;}
            }
        }
        for(CoverMode mode:CoverMode.values()) {
            try(var loader=new CoverLoader(uri->new Response(uri,fixture)); var scope=new RequestScope(()->true)) {
                var image=ImageIO.read(new ByteArrayInputStream(loader.loadPlayback(SOURCE,scope,mode)));
                check(image.getWidth()==(mode==CoverMode.FIT?240:120) && image.getHeight()==120);
                if(mode==CoverMode.LEFT) check((image.getRGB(119,60)&0xffffff)==0xdd5500);
                if(mode==CoverMode.RIGHT) check((image.getRGB(0,60)&0xffffff)==0x008866);
                if(mode==CoverMode.CENTER) check((image.getRGB(0,60)&0xffffff)==0xdd5500 && (image.getRGB(119,60)&0xffffff)==0x008866);
            }
        }
        check(CoverMode.parse(null)==CoverMode.FIT && CoverMode.parse("invalid")==CoverMode.FIT);
        check(CoverSource.thumbnail(SOURCE,480).toString().equals(SOURCE+"@480w.webp"));
        check(CoverSource.thumbnail(URI.create(SOURCE+"@96w_96h_1c.webp"),480).toString().equals(SOURCE+"@480w.webp"));
        URI originalNetease=URI.create("https://p1.music.126.net/fixture.jpg");
        check(CoverSource.thumbnail(URI.create(originalNetease+"?param=96y96"),480).equals(originalNetease));
        check(CoverSource.thumbnail(URI.create("https://evil.example/fixture.jpg"),480)==null);
        for(int edge:new int[]{0,1025})try {CoverSource.thumbnail(SOURCE,edge);throw new AssertionError("Unbounded thumbnail");}
        catch(IllegalArgumentException expected){checks++;}
        check(SOURCE.equals(CoverSource.original(URI.create(SOURCE + "@96w_96h_1c.webp"))));
        check(CoverSource.original(URI.create("https://p1.music.126.net/fixture.jpg?param=96y96")).toString().equals("https://p1.music.126.net/fixture.jpg"));
        check(CoverSource.original(URI.create("https://i0.hdslb.com/fixture.jpg?token=SECRET")) == null);
        check(CoverSource.parse(AccountPlatform.BILIBILI, " https://i1.biliimg.com/fixture.jpg@100w_100h.webp ").toString().equals("https://i1.biliimg.com/fixture.jpg"));
        check(CoverSource.parse(AccountPlatform.NETEASE, "https://p1.music.126.net/fixture.jpg?param=200y200").toString().endsWith("/fixture.jpg"));
        for (int[] shape : new int[][]{{1600,900,1024,576}, {300,1200,256,1024}, {80,120,80,120}}) {
            byte[] original = png(shape[0], shape[1]);
            try (CoverLoader loader = new CoverLoader(uri -> new Response(uri, original)); RequestScope scope = new RequestScope(() -> true)) {
                var image = ImageIO.read(new ByteArrayInputStream(loader.loadPlayback(SOURCE, scope)));
                check(image.getWidth() == shape[2] && image.getHeight() == shape[3]);
                check((image.getRGB(0,0) & 0xffffff) == 0xdd5500 && (image.getRGB(image.getWidth()-1,image.getHeight()-1) & 0xffffff) == 0x008866);
            }
        }
        byte[] webp;
        try (var image = org.jetbrains.skia.Image.Companion.makeFromEncoded(fixture);
             var bytes = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.WEBP,100,0)) { webp = bytes.getBytes(); }
        try (CoverLoader loader = new CoverLoader(uri -> {
            check(uri.equals(CoverSource.thumbnail(SOURCE,480))); Response result = new Response(uri, webp); result.type = "image/webp"; return result;
        }); RequestScope scope = new RequestScope(() -> true)) {
            var image = ImageIO.read(new ByteArrayInputStream(loader.load(URI.create(SOURCE + "@100w_100h.webp"),scope)));
            check(image.getWidth() == 240 && image.getHeight() == 120);
            check((image.getRGB(0,0) >>> 24) == 255);
        }
        var hd = new BufferedImage(1200, 1200, BufferedImage.TYPE_INT_RGB);
        // Fine stripes would disappear if playback accidentally reused the 96px list thumbnail.
        for (int y = 0; y < 1200; y++) for (int x = 0; x < 1200; x++) hd.setRGB(x, y, (x / 6) % 2 == 0 ? 0xff000000 : 0xffffffff);
        var hdBytes = new ByteArrayOutputStream(); ImageIO.write(hd, "png", hdBytes); hd.flush();
        var hdRequests=new ArrayList<URI>();
        try (CoverLoader loader = new CoverLoader(uri -> { hdRequests.add(uri); return new Response(uri, hdBytes.toByteArray()); }); RequestScope scope = new RequestScope(() -> true)) {
            var image = ImageIO.read(new ByteArrayInputStream(loader.loadPlayback(URI.create(SOURCE + "@96w_96h_1c.webp"), scope)));
            check(image.getWidth() == 1024 && image.getHeight() == 1024);
            int transitions = 0, previous = 0;
            for (int x = 0; x < 1024; x++) { int pixel = (image.getRGB(x, 512) & 255) > 128 ? 1 : 0; if (pixel != previous) transitions++; previous = pixel; }
            check(transitions >= 190);
            check(ImageIO.read(new ByteArrayInputStream(loader.load(SOURCE, scope))).getWidth() == CoverLoader.LIST_EDGE);
            check(hdRequests.equals(List.of(SOURCE,CoverSource.thumbnail(SOURCE,480))));
        }
        try (CoverLoader loader = new CoverLoader(uri -> new Response(uri, fixture)); RequestScope scope = new RequestScope(() -> true)) {
            check(ImageIO.read(new ByteArrayInputStream(loader.loadPlayback(SOURCE, scope))).getWidth() == 240);
        }
        Response hdOversize = new Response(SOURCE, fixture); hdOversize.length = 8 * 1048576L + 1;
        try (CoverLoader loader = new CoverLoader(uri -> hdOversize); RequestScope scope = new RequestScope(() -> true)) {
            rejects(() -> loader.loadPlayback(SOURCE, scope)); check(hdOversize.closed);
        }
        try (CoverLoader loader = new CoverLoader(uri -> response); RequestScope scope = new RequestScope(() -> true)) {
            byte[] bytes = loader.load(SOURCE, scope); var image = ImageIO.read(new ByteArrayInputStream(bytes));
            check(image.getWidth() == 240 && image.getHeight() == 120 && bytes.length < 65536);
            check((image.getRGB(10, 40) & 0xffffff) == 0xdd5500 && (image.getRGB(155, 40) & 0xffffff) == 0x008866);
            check(response.closed && !response.getInstanceFollowRedirects() && response.getRequestProperty("Cookie") == null && response.getRequestProperty("Authorization") == null);
            rejects(() -> loader.load(URI.create("https://evil.example/a"), scope));
        }
        for (int mode = 0; mode < 6; mode++) {
            Response bad = new Response(SOURCE, fixture);
            switch (mode) { case 0 -> bad.status = 302; case 1 -> bad.type = "text/html"; case 2 -> bad.length = 8388609;
                case 3 -> bad.bytes = new byte[8388609]; case 4 -> bad.bytes = "COOKIE_SECRET".getBytes(); case 5 -> bad.encoding = "gzip"; }
            try (CoverLoader loader = new CoverLoader(uri -> bad); RequestScope scope = new RequestScope(() -> true)) { rejects(() -> loader.load(SOURCE, scope)); check(bad.closed); }
        }
        var huge = new BufferedImage(4097, 1, BufferedImage.TYPE_INT_RGB); var hugeBytes = new ByteArrayOutputStream(); ImageIO.write(huge, "png", hugeBytes); huge.flush();
        try (CoverLoader loader = new CoverLoader(uri -> new Response(uri, hugeBytes.toByteArray())); RequestScope scope = new RequestScope(() -> true)) {
            rejects(() -> loader.load(SOURCE, scope));
        }
        var jpegImage = new BufferedImage(150, 120, BufferedImage.TYPE_INT_RGB); var jpegBytes = new ByteArrayOutputStream(); ImageIO.write(jpegImage, "jpeg", jpegBytes); jpegImage.flush();
        Response jpeg = new Response(SOURCE, jpegBytes.toByteArray()); jpeg.type = "image/jpeg; charset=binary";
        try (CoverLoader loader = new CoverLoader(uri -> jpeg); RequestScope scope = new RequestScope(() -> true)) {
            check(ImageIO.read(new ByteArrayInputStream(loader.load(SOURCE, scope))).getWidth() == 150);
        }
        URI netease = URI.create("https://p2.music.126.net/fixture.jpg");
        for (String type : List.of("image/jpg", "IMAGE/JPG", " image/jpg ; charset=binary", "image/jpeg")) {
            Response jpg = new Response(netease, jpeg()); jpg.type = type;
            try (CoverLoader loader = new CoverLoader(uri -> jpg); RequestScope scope = new RequestScope(() -> true)) {
                var list = ImageIO.read(new ByteArrayInputStream(loader.load(netease,scope)));
                check(list.getWidth() == 240 && list.getHeight() == 120);
                var playback = ImageIO.read(new ByteArrayInputStream(loader.loadPlayback(netease,scope)));
                check(playback.getWidth() == 240 && playback.getHeight() == 120);
                check(jpg.closed && jpg.getRequestProperty("Cookie") == null && jpg.getRequestProperty("Authorization") == null);
                check(jpg.getRequestProperty("Referer").equals("https://music.163.com/"));
            }
        }
        for (int mode = 0; mode < 7; mode++) {
            Response bad = new Response(netease, jpeg()); bad.type = "image/jpg";
            switch (mode) {
                case 0 -> bad.bytes = "<html>SECRET</html>".getBytes();
                case 1 -> bad.bytes = new byte[]{(byte)0xff, (byte)0xd8, (byte)0xff};
                case 2 -> bad.type = "application/octet-stream";
                case 3 -> bad.type = "image/jpg-extra";
                case 4 -> bad.length = 8388609;
                case 5 -> bad.bytes = new byte[8388609];
                case 6 -> bad.bytes = hugeBytes.toByteArray();
            }
            try (CoverLoader loader = new CoverLoader(uri -> bad); RequestScope scope = new RequestScope(() -> true)) {
                rejects(() -> loader.load(netease,scope)); check(bad.closed);
            }
        }
        AtomicInteger opens = new AtomicInteger(); AtomicBoolean active = new AtomicBoolean(true);
        try (CoverLoader loader = new CoverLoader(uri -> { opens.incrementAndGet(); return new Response(uri, fixture); })) {
            check(loader.request("row", SOURCE, active::get) == null);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); byte[] loaded;
            do { Thread.sleep(10); loaded = loader.request("row", SOURCE, active::get); } while (loaded == null && System.nanoTime() < end);
            check(loaded != null && opens.get() == 1);
            check(loader.request("row", SOURCE, active::get) == loaded && opens.get() == 1);
            active.set(false); check(loader.request("row", SOURCE, active::get) == null);
            check(loader.request("no-image", null, () -> true) == null && opens.get() == 1);
            loader.clear(); loader.close(); check(loader.request("closed", SOURCE, () -> true) == null);
        }
        AtomicInteger lookups = new AtomicInteger(), lazyOpens = new AtomicInteger();
        try (CoverLoader loader = new CoverLoader(uri -> { lazyOpens.incrementAndGet(); return new Response(uri,fixture); })) {
            CoverLoader.Source lookup = scope -> { lookups.incrementAndGet(); return SOURCE; };
            check(lookups.get() == 0);
            loader.requestSource("lazy", lookup, () -> true);
            long end = System.nanoTime()+3_000_000_000L; byte[] result;
            do { Thread.sleep(10); result = loader.requestSource("lazy", lookup, () -> true); } while (result == null && System.nanoTime() < end);
            check(result != null && lookups.get() == 1 && lazyOpens.get() == 1);
            loader.requestSource("lazy", lookup, () -> true); check(lookups.get() == 1);
        }
        URI alternate = URI.create("https://i1.hdslb.com/fallback.jpg");
        AtomicInteger fallbackLookups = new AtomicInteger(), fallbackOpens = new AtomicInteger();
        try (CoverLoader loader = new CoverLoader(uri -> {
            fallbackOpens.incrementAndGet(); var result = new Response(uri,fixture); if (CoverSource.original(uri).equals(SOURCE)) result.status=404; return result;
        })) {
            CoverLoader.Source lookup = scope -> { fallbackLookups.incrementAndGet(); return alternate; };
            loader.requestSource("broken", scope -> SOURCE, lookup, () -> true);
            long end=System.nanoTime()+3_000_000_000L; byte[] result;
            do { Thread.sleep(10); result=loader.requestSource("broken",scope -> SOURCE,lookup,() -> true); } while(result==null && System.nanoTime()<end);
            check(result!=null && fallbackLookups.get()==1 && fallbackOpens.get()==3);
            loader.requestSource("broken",scope -> SOURCE,lookup,() -> true);
            check(fallbackLookups.get()==1 && fallbackOpens.get()==3);
        }
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger cancelledOpens = new AtomicInteger();
        try (CoverLoader loader = new CoverLoader(uri -> {
            cancelledOpens.incrementAndGet(); started.countDown();
            try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            return new Response(uri, fixture);
        })) {
            loader.request("stale", SOURCE, () -> true); check(started.await(2, TimeUnit.SECONDS));
            loader.clear(); release.countDown(); loader.close();
            check(loader.request("stale", SOURCE, () -> true) == null && cancelledOpens.get() == 1);
        } finally { release.countDown(); }
        parallelCovers(fixture);
        scrollingCovers(fixture);
        retainedCovers(fixture);
        sharedCovers(fixture);
        largeOriginal();
        System.out.println("PASS: " + checks + " cover source/HTTPS/bounds/decoder/cache/revocation checks (fake image HTTP)");
    }
    private static void largeOriginal() throws Exception {
        var image=new BufferedImage(1600,1000,BufferedImage.TYPE_INT_RGB);var random=new Random(42);
        for(int y=0;y<1000;y++)for(int x=0;x<1600;x++)image.setRGB(x,y,random.nextInt());
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);image.flush();byte[] bytes=out.toByteArray();
        check(bytes.length>4*1048576 && bytes.length<8*1048576);
        for(boolean declared:List.of(true,false)) {
            try(var loader=new CoverLoader(uri->{var response=new Response(uri,bytes);if(declared)response.length=bytes.length;return response;});var scope=new RequestScope(()->true)) {
                var list=ImageIO.read(new ByteArrayInputStream(loader.load(SOURCE,scope)));
                var playback=ImageIO.read(new ByteArrayInputStream(loader.loadPlayback(SOURCE,scope)));
                check(list.getWidth()==480 && list.getHeight()==300 && playback.getWidth()==1024 && playback.getHeight()==640);
            }
        }
    }
    private static void parallelCovers(byte[] fixture) throws Exception {
        var started=new CountDownLatch(CoverLoader.PARALLEL_LOADS); var release=new CountDownLatch(1);
        var running=new AtomicInteger(); var peak=new AtomicInteger(); var opens=new AtomicInteger();
        try(CoverLoader loader=new CoverLoader(uri->{
            opens.incrementAndGet(); int count=running.incrementAndGet(); peak.accumulateAndGet(count,Math::max); started.countDown();
            try { release.await(); } catch(InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { running.decrementAndGet(); }
            return new Response(uri,fixture);
        })) {
            for(int i=0;i<20;i++) loader.request("parallel-"+i,URI.create("https://i0.hdslb.com/parallel-"+i+".png"),()->true);
            check(started.await(2,TimeUnit.SECONDS)); check(peak.get()==CoverLoader.PARALLEL_LOADS && opens.get()==CoverLoader.PARALLEL_LOADS);
            release.countDown(); long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            boolean complete;
            do {
                complete=true;
                for(int i=0;i<20;i++) complete &= loader.request("parallel-"+i,URI.create("https://i0.hdslb.com/parallel-"+i+".png"),()->true)!=null;
                if(!complete) Thread.sleep(5);
            } while(!complete && System.nanoTime()<end);
            check(complete && opens.get()==20 && peak.get()<=CoverLoader.PARALLEL_LOADS);
        } finally { release.countDown(); }
        var slowStarted=new CountDownLatch(1); var unblock=new CountDownLatch(1);
        URI fast=URI.create("https://i1.hdslb.com/fast.png");
        try(CoverLoader loader=new CoverLoader(uri->{
            if(CoverSource.original(uri).equals(SOURCE)) { slowStarted.countDown(); try { unblock.await(); } catch(InterruptedException error) { Thread.currentThread().interrupt(); } }
            return new Response(uri,fixture);
        })) {
            loader.request("slow",SOURCE,()->true); check(slowStarted.await(2,TimeUnit.SECONDS));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2); byte[] result;
            do { result=loader.request("fast",fast,()->true); if(result==null) Thread.sleep(5); } while(result==null && System.nanoTime()<end);
            check(result!=null && loader.request("slow",SOURCE,()->true)==null);
            loader.clear(); unblock.countDown();
        } finally { unblock.countDown(); }
    }
    private static void retainedCovers(byte[] fixture) throws Exception {
        var opens=new AtomicInteger();var started=new CountDownLatch(CoverLoader.PARALLEL_LOADS);var release=new CountDownLatch(1);
        try(var loader=new CoverLoader(uri->{
            opens.incrementAndGet();
            if(!CoverSource.original(uri).equals(SOURCE)) {
                started.countDown();try{release.await();}catch(InterruptedException error){Thread.currentThread().interrupt();}
            }
            return new Response(uri,fixture);
        });var scope=new RequestScope(()->true)) {
            byte[] first=loader.load(SOURCE,scope);
            loader.releaseRequests();
            check(loader.request("return-new-token",SOURCE,()->true)==first && opens.get()==1);
            for(int i=0;i<20;i++)loader.request("busy-"+i,URI.create("https://i0.hdslb.com/busy-"+i+".png"),()->true);
            check(started.await(2,TimeUnit.SECONDS));
            check(loader.request("warm-full-capacity",SOURCE,()->true)==first && opens.get()==1+CoverLoader.PARALLEL_LOADS);
            check(loader.requestSource("warm-no-lookup",SOURCE,r->{throw new AssertionError("Cached cover resolved again");},null,()->true)==first);
            check(loader.request("revoked",SOURCE,()->false)==null);
            loader.releaseRequests();release.countDown();
            check(loader.request("return-again",SOURCE,()->true)==first);
            loader.clear();
            loader.load(SOURCE,scope);check(opens.get()>=2+CoverLoader.PARALLEL_LOADS);
        }finally{release.countDown();}
    }
    private static void scrollingCovers(byte[] fixture) throws Exception {
        var opens=new AtomicInteger();
        try(var loader=new CoverLoader(uri->{opens.incrementAndGet();return new Response(uri,fixture);})) {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean complete;
            do {
                complete=true;
                for(int i=0;i<100;i++)complete &= loader.request("retained-row-"+i,URI.create("https://i0.hdslb.com/scroll-"+i+".png"),()->true)!=null;
                if(!complete)Thread.sleep(5);
            }while(!complete && System.nanoTime()<end);
            check(complete && opens.get()==100);
            check(loader.request("retained-row-0",URI.create("https://i0.hdslb.com/scroll-0.png"),()->true)!=null && opens.get()==100);
        }
    }
    private static void sharedCovers(byte[] fixture) throws Exception {
        var opens=new AtomicInteger();var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var loader=new CoverLoader(uri->{
            opens.incrementAndGet();started.countDown();
            try{release.await();}catch(InterruptedException error){Thread.currentThread().interrupt();}
            return new Response(uri,fixture);
        })) {
            for(int i=0;i<20;i++)loader.request("shared-"+i,SOURCE,()->true);
            check(started.await(2,TimeUnit.SECONDS));Thread.sleep(100);check(opens.get()==1);
            release.countDown();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);byte[] result;
            do{result=loader.request("shared-19",SOURCE,()->true);if(result==null)Thread.sleep(5);}while(result==null && System.nanoTime()<end);
            check(result!=null && opens.get()==1);
        }finally{release.countDown();}
        var ownerActive=new AtomicBoolean(true);var ownerStarted=new CountDownLatch(1);var ownerRelease=new CountDownLatch(1);
        var handoverOpens=new AtomicInteger();
        try(var loader=new CoverLoader(uri->{
            if(handoverOpens.incrementAndGet()==1){
                ownerStarted.countDown();try{ownerRelease.await();}catch(InterruptedException error){Thread.currentThread().interrupt();}
            }
            return new Response(uri,fixture);
        });var executor=Executors.newFixedThreadPool(2);var ownerScope=new RequestScope(ownerActive::get);var followerScope=new RequestScope(()->true)) {
            var owner=executor.submit(()->loader.load(SOURCE,ownerScope));check(ownerStarted.await(2,TimeUnit.SECONDS));
            var follower=executor.submit(()->loader.load(SOURCE,followerScope));Thread.sleep(100);
            check(handoverOpens.get()==1);ownerActive.set(false);ownerRelease.countDown();
            try{owner.get(2,TimeUnit.SECONDS);throw new AssertionError("Retired owner published");}
            catch(ExecutionException expected){check(expected.getCause() instanceof CancellationException);}
            check(follower.get(2,TimeUnit.SECONDS)!=null && handoverOpens.get()==2);
        }finally{ownerRelease.countDown();}
        var clearStarted=new CountDownLatch(1);var clearRelease=new CountDownLatch(1);var clearOpens=new AtomicInteger();
        try(var loader=new CoverLoader(uri->{
            if(clearOpens.incrementAndGet()==1){clearStarted.countDown();try{clearRelease.await();}catch(InterruptedException error){Thread.currentThread().interrupt();}}
            return new Response(uri,fixture);
        });var executor=Executors.newSingleThreadExecutor();var scope=new RequestScope(()->true)) {
            var stale=executor.submit(()->loader.load(SOURCE,scope));check(clearStarted.await(2,TimeUnit.SECONDS));
            loader.clear();clearRelease.countDown();
            try{stale.get(2,TimeUnit.SECONDS);throw new AssertionError("Cleared flight published");}
            catch(ExecutionException expected){check(expected.getCause() instanceof CancellationException);}
            check(loader.load(SOURCE,scope)!=null && clearOpens.get()==2);
        }finally{clearRelease.countDown();}
    }
}
