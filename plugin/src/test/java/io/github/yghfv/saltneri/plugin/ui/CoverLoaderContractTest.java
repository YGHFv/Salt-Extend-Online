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
        var image = new BufferedImage(240, 120, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 120; y++) for (int x = 0; x < 240; x++) image.setRGB(x, y, x < 120 ? 0xffdd5500 : 0xff008866);
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); return bytes.toByteArray();
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
        for (String input : List.of("", "fixture", "https://i0.hdslb.com/fixture?token=URL_SECRET", "https://user:COOKIE_SECRET@i0.hdslb.com/a",
                "https://i0.hdslb.com:443/a", "https://i0.hdslb.com/a#URL_SECRET", "https://i0.hdslb.com.evil.example/a", "https://127.0.0.1/a",
                "https://m7.music.126.net/audio", "file:///a", "//evil.example/a")) {
            check(CoverSource.parse(AccountPlatform.BILIBILI, input) == null);
            check(CoverSource.parse(AccountPlatform.NETEASE, input) == null);
        }
        byte[] fixture = png(); Response response = new Response(SOURCE, fixture);
        check(SOURCE.equals(CoverSource.original(URI.create(SOURCE + "@96w_96h_1c.webp"))));
        check(CoverSource.original(URI.create("https://p1.music.126.net/fixture.jpg?param=96y96")).toString().equals("https://p1.music.126.net/fixture.jpg"));
        check(CoverSource.original(URI.create("https://i0.hdslb.com/fixture.jpg?token=SECRET")) == null);
        var hd = new BufferedImage(1200, 1200, BufferedImage.TYPE_INT_RGB);
        // Fine stripes would disappear if playback accidentally reused the 96px list thumbnail.
        for (int y = 0; y < 1200; y++) for (int x = 0; x < 1200; x++) hd.setRGB(x, y, (x / 6) % 2 == 0 ? 0xff000000 : 0xffffffff);
        var hdBytes = new ByteArrayOutputStream(); ImageIO.write(hd, "png", hdBytes); hd.flush();
        try (CoverLoader loader = new CoverLoader(uri -> { check(uri.equals(SOURCE)); return new Response(uri, hdBytes.toByteArray()); }); RequestScope scope = new RequestScope(() -> true)) {
            var image = ImageIO.read(new ByteArrayInputStream(loader.loadPlayback(URI.create(SOURCE + "@96w_96h_1c.webp"), scope)));
            check(image.getWidth() == 1024 && image.getHeight() == 1024);
            int transitions = 0, previous = 0;
            for (int x = 0; x < 1024; x++) { int pixel = (image.getRGB(x, 512) & 255) > 128 ? 1 : 0; if (pixel != previous) transitions++; previous = pixel; }
            check(transitions >= 190);
            check(ImageIO.read(new ByteArrayInputStream(loader.load(SOURCE, scope))).getWidth() == 96);
        }
        try (CoverLoader loader = new CoverLoader(uri -> new Response(uri, fixture)); RequestScope scope = new RequestScope(() -> true)) {
            check(ImageIO.read(new ByteArrayInputStream(loader.loadPlayback(SOURCE, scope))).getWidth() == 120);
        }
        Response hdOversize = new Response(SOURCE, fixture); hdOversize.length = 8 * 1048576L + 1;
        try (CoverLoader loader = new CoverLoader(uri -> hdOversize); RequestScope scope = new RequestScope(() -> true)) {
            rejects(() -> loader.loadPlayback(SOURCE, scope)); check(hdOversize.closed);
        }
        try (CoverLoader loader = new CoverLoader(uri -> response); RequestScope scope = new RequestScope(() -> true)) {
            byte[] bytes = loader.load(SOURCE, scope); var image = ImageIO.read(new ByteArrayInputStream(bytes));
            check(image.getWidth() == 96 && image.getHeight() == 96 && bytes.length < 65536);
            check((image.getRGB(10, 40) & 0xffffff) == 0xdd5500 && (image.getRGB(85, 40) & 0xffffff) == 0x008866);
            check(response.closed && !response.getInstanceFollowRedirects() && response.getRequestProperty("Cookie") == null && response.getRequestProperty("Authorization") == null);
            rejects(() -> loader.load(URI.create("https://evil.example/a"), scope));
        }
        for (int mode = 0; mode < 6; mode++) {
            Response bad = new Response(SOURCE, fixture);
            switch (mode) { case 0 -> bad.status = 302; case 1 -> bad.type = "text/html"; case 2 -> bad.length = 1048577;
                case 3 -> bad.bytes = new byte[1048577]; case 4 -> bad.bytes = "COOKIE_SECRET".getBytes(); case 5 -> bad.encoding = "gzip"; }
            try (CoverLoader loader = new CoverLoader(uri -> bad); RequestScope scope = new RequestScope(() -> true)) { rejects(() -> loader.load(SOURCE, scope)); check(bad.closed); }
        }
        var huge = new BufferedImage(4097, 1, BufferedImage.TYPE_INT_RGB); var hugeBytes = new ByteArrayOutputStream(); ImageIO.write(huge, "png", hugeBytes); huge.flush();
        try (CoverLoader loader = new CoverLoader(uri -> new Response(uri, hugeBytes.toByteArray())); RequestScope scope = new RequestScope(() -> true)) {
            rejects(() -> loader.load(SOURCE, scope));
        }
        var jpegImage = new BufferedImage(150, 120, BufferedImage.TYPE_INT_RGB); var jpegBytes = new ByteArrayOutputStream(); ImageIO.write(jpegImage, "jpeg", jpegBytes); jpegImage.flush();
        Response jpeg = new Response(SOURCE, jpegBytes.toByteArray()); jpeg.type = "image/jpeg; charset=binary";
        try (CoverLoader loader = new CoverLoader(uri -> jpeg); RequestScope scope = new RequestScope(() -> true)) {
            check(ImageIO.read(new ByteArrayInputStream(loader.load(SOURCE, scope))).getWidth() == 96);
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
        System.out.println("PASS: " + checks + " cover source/HTTPS/bounds/decoder/cache/revocation checks (fake image HTTP)");
    }
}
