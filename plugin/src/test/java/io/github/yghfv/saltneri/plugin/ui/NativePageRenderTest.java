package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.ImageComposeScene;
import androidx.compose.ui.unit.DensityKt;
import androidx.compose.ui.unit.LayoutDirection;
import com.xuncorp.spw.workshop.api.hook.*;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.library.*;
import java.lang.reflect.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import javax.imageio.ImageIO;
import kotlin.Unit;
import kotlinx.coroutines.Dispatchers;
import kotlin.jvm.functions.Function3;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.Data;
import org.jetbrains.skia.EncodedImageFormat;

/** Real offscreen Compose/Skia rendering of cached host page content; synthetic account/list only. */
public final class NativePageRenderTest {
    static final class Hooks extends HookRegistrar {
        public HookHandle afterComposable(String o, String m, String i, List<String> t, int p, ComposableHook c) {
            return new HookHandle() { public void unhook() {} public void close() {} };
        }
        public HookHandle hookMethod(String o, String m, List<String> t, MethodHook h) { throw new AssertionError("No ordinary page Hook"); }
        public HookHandle replaceComposable(String o, String m, String i, List<String> t, int p, ComposableHook c) { throw new AssertionError(); }
        public <S> HookHandle appendContent(String o, String m, String i, String a, Class<S> s, List<String> t, int p, ContentHook<S> c) { throw new AssertionError(); }
    }
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Offscreen render check " + checks); }
    private static void backdrop(Image layer, Path path, boolean alternate) throws Exception {
        // Composite the actual transparent Compose result over two changing synthetic host backdrops.
        try (var surface = org.jetbrains.skia.Surface.Companion.makeRasterN32Premul(layer.getWidth(), layer.getHeight());
             var paint = new org.jetbrains.skia.Paint()) {
            for (int y=0; y<layer.getHeight(); y++) {
                float t = (float)y/layer.getHeight();
                int red = (int)(alternate ? 235-18*t : 237-8*t), green = (int)(alternate ? 232+8*t : 236+4*t), blue = (int)(alternate ? 225+18*t : 236-3*t);
                paint.setColor(0xff000000 | red<<16 | green<<8 | blue);
                surface.getCanvas().drawRect(0,y,layer.getWidth(),y+1,paint);
            }
            try (Image under = surface.makeImageSnapshot(); Data encoded = under.encodeToData(EncodedImageFormat.PNG,100,0)) {
                var background = ImageIO.read(new ByteArrayInputStream(encoded.getBytes()));
                surface.getCanvas().drawImage(layer,0,0);
                try (Image composite = surface.makeImageSnapshot(); Data png = composite.encodeToData(EncodedImageFormat.PNG,100,0)) {
                    byte[] bytes=png.getBytes(); var pixels=ImageIO.read(new ByteArrayInputStream(bytes));
                    check(pixels.getRGB(0,0)==background.getRGB(0,0));
                    check(pixels.getRGB(layer.getWidth()/2,790)==background.getRGB(layer.getWidth()/2,790));
                    check(pixels.getRGB(0,0)!=pixels.getRGB(0,790));
                    Files.write(path,bytes);
                }
            }
        }
    }
    private static androidx.compose.ui.semantics.SemanticsNode findSong(androidx.compose.ui.semantics.SemanticsNode node) {
        if (node.getConfig().toString().contains("Synthetic song")) return node;
        for (var child : node.getChildren()) { var found = findSong(child); if (found != null) return found; }
        return null;
    }
    private static androidx.compose.ui.semantics.SemanticsNode findText(androidx.compose.ui.semantics.SemanticsNode node, String text) {
        for (var child : node.getChildren()) { var found = findText(child, text); if (found != null) return found; }
        return node.getConfig().toString().contains(text) ? node : null;
    }
    static void tap(ImageComposeScene scene, float x, float y, long time) throws Exception {
        Class<?> buttons = Class.forName("androidx.compose.ui.input.pointer.PointerButtons"), modifiers = Class.forName("androidx.compose.ui.input.pointer.PointerKeyboardModifiers"),
                button = Class.forName("androidx.compose.ui.input.pointer.PointerButton"), event = Class.forName("androidx.compose.ui.input.pointer.PointerEventType"),
                pointer = Class.forName("androidx.compose.ui.input.pointer.PointerType");
        Object eventCompanion = event.getField("Companion").get(null), pointerCompanion = pointer.getField("Companion").get(null);
        int mouse = (int)pointerCompanion.getClass().getMethod("getMouse-T8wyACA").invoke(pointerCompanion);
        Method send = ImageComposeScene.class.getMethod("sendPointerEvent-BGSDPeU", int.class,long.class,long.class,long.class,int.class,buttons,modifiers,Object.class,button);
        long position = androidx.compose.ui.geometry.OffsetKt.Offset(x,y);
        Object keys = modifiers.getMethod("box-impl",int.class).invoke(null,0), left = button.getMethod("box-impl",int.class).invoke(null,0);
        for (int i = 0; i < 2; i++) {
            int type = (int)eventCompanion.getClass().getMethod(i == 0 ? "getPress-7fucELk" : "getRelease-7fucELk").invoke(eventCompanion);
            send.invoke(scene,type,position,0L,time + i * 10,mouse,buttons.getMethod("box-impl",int.class).invoke(null,i == 0 ? 1 : 0),keys,null,left);
        }
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        ClassLoader host = NativePageRenderTest.class.getClassLoader();
        Class<?> singleton = Class.forName(SaltPageContentBinding.SINGLETON, true, host);
        // Cache Salt's actual content before binding; render through this same object, not ui.entry() directly.
        Function3<Object,Composer,Integer,Object> cached = (Function3<Object,Composer,Integer,Object>)singleton.getMethod(SaltPageContentBinding.GETTER)
                .invoke(singleton.getField("INSTANCE").get(null));
        Method sidebarStyle = Class.forName("androidx.compose.ui.\u04cc", false, host).getMethod("\u037f", Composer.class);
        Class<?> route = Class.forName(SaltNativeUi.MOD_ROUTE, false, host);
        AtomicInteger requests = new AtomicInteger(), queues = new AtomicInteger(), actions = new AtomicInteger();
        AtomicInteger qr = new AtomicInteger();
        AtomicInteger catalogSize = new AtomicInteger(1);
        AtomicBoolean invalidItems = new AtomicBoolean(false);
        AtomicBoolean armed = new AtomicBoolean(false);
        Clock clock = Clock.systemUTC();
        Path output = Path.of(System.getProperty("java.io.tmpdir")).resolve("native-ui-render"); Files.createDirectories(output);
        try (OnlinePlaybackController player = new OnlinePlaybackController(p -> null, () -> true, armed::get, (a,s) -> queues.incrementAndGet(), clock,
                     account -> (target, quality, scope, request) -> {
                         var expires = clock.instant().plusSeconds(300);
                         return new OnlinePlaybackController.Audio(io.github.yghfv.saltneri.core.TrackKey.netease("123"),
                                 new io.github.yghfv.saltneri.plugin.host.Salt1193PlaybackSession.Metadata("Synthetic prepared song", "Artist", "Album", "Artist"),
                                 new io.github.yghfv.saltneri.plugin.transport.MediaLease(io.github.yghfv.saltneri.plugin.transport.MediaLease.Platform.NETEASE,
                                         URI.create("https://m7.music.126.net/fixture?token=URL_SECRET"), Map.of(), scope, expires, 4096), expires);
                     });
             LibraryController library = new LibraryController(p -> null, () -> true, target -> {}, () -> {}, clock,
                     account -> (query, quality, scope, request) -> {
                         requests.incrementAndGet(); int offset = (query.page() - 1) * 20, total = catalogSize.get();
                         var items = java.util.stream.IntStream.range(offset, Math.min(offset + 20, total)).mapToObj(index ->
                                 new Catalog.Item(Catalog.Kind.TRACK, "123", index == 0 ? "Synthetic song" : "Song " + index, "Artist", 1, !invalidItems.get(),
                                         URI.create("https://p2.music.126.net/fixture.jpg"))).toList();
                         return new Catalog.Page("Synthetic playlist", items, offset + 20 < total, clock.instant().plusSeconds(300), Math.max(1,(total+19)/20));
                     })) {
            NativeDesk desk = new NativeDesk(library, player,
                    p -> new AccountLoginController.Snapshot(qr.get() == 0 ? AccountLoginController.Phase.IDLE : AccountLoginController.Phase.WAITING_SCAN,
                            qr.get() == 0 ? "Offline fixture - not logged in" : "Offline synthetic QR - do not scan",
                            qr.get() == 0 ? null : URI.create("https://example.invalid/offline-render-fixture"), ""),
                    (p,a) -> { actions.incrementAndGet(); if (a == NativeDesk.Action.ARM) armed.set(true); }, () -> true);
            byte[] fixture = CoverLoaderContractTest.jpeg();
            try (SaltNativeUi ui = new SaltNativeUi(host, new Hooks(), desk, () -> true, new CoverLoader(uri -> {
                var response = new CoverLoaderContractTest.Response(uri, fixture); response.type = "image/jpg"; return response;
            }))) {
                SwingUtilities.invokeAndWait(() -> {});
                for (AccountPlatform platform : AccountPlatform.values()) for (int width : new int[]{740, 420}) {
                    qr.set(width == 420 ? 1 : 0);
                    Object key = route.getConstructor(String.class).newInstance(SaltNativeUi.PLUGIN_ID + "/native/" + platform.name());
                    SwingUtilities.invokeAndWait(() -> {
                        ImageComposeScene scene = new ImageComposeScene(width, 820, DensityKt.Density(1f, 1f), LayoutDirection.Ltr,
                                Dispatchers.getUnconfined(), (composer, flags) -> {
                                    try {
                                        if (ui.sidebarStyle() == null) ui.publishSidebarStyle(sidebarStyle.invoke(null, composer));
                                        cached.invoke(key, composer, flags);
                                        return Unit.INSTANCE;
                                    } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                                });
                        try {
                            for (int frame = 1; frame <= 3; frame++) try (Image image = scene.render(frame * 16_000_000L)) {
                                check(image.getWidth() == width && image.getHeight() == 820);
                                if (frame == 3) try (Data png = image.encodeToData(EncodedImageFormat.PNG, 100, 0)) {
                                    check(png != null && png.getSize() > 3000);
                                    byte[] bytes = png.getBytes();
                                    var pixels = ImageIO.read(new ByteArrayInputStream(bytes));
                                    check((pixels.getRGB(0, 0) >>> 24) == 0);
                                    check((pixels.getRGB(width/2, 790) >>> 24) == 0);
                                    int ink = 0;
                                    for (int y = 28; y < 62; y++) for (int x = 20; x < 240; x++) {
                                        int color = pixels.getRGB(x, y);
                                        if ((color >>> 24) == 255 && (color & 255) < 160) ink++;
                                    }
                                    check(ink > 80); // Heading is genuinely painted, not just present in a semantics tree.
                                    Files.write(output.resolve(platform.name().toLowerCase(Locale.ROOT) + "-" + width + ".png"), bytes);
                                    backdrop(image,output.resolve(platform.name().toLowerCase(Locale.ROOT) + "-" + width + "-backdrop.png"),false);
                                    backdrop(image,output.resolve(platform.name().toLowerCase(Locale.ROOT) + "-" + width + "-alternate.png"),true);
                                }
                            }
                            check(!scene.getSemanticsOwners().isEmpty());
                            check(ui.diagnostics().contains("visible=[" + platform.name() + "]"));
                        } catch (Exception error) { throw new AssertionError(error); }
                        finally { scene.close(); }
                    });
                }
                check(requests.get() == 0 && queues.get() == 0 && actions.get() == 0);
                library.browse(AccountPlatform.NETEASE, Catalog.View.DISCOVER);
                long deadline = System.nanoTime() + 3_000_000_000L;
                while (((List<?>)library.snapshot().get("items")).isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
                String token = (String)((Map<?,?>)((List<?>)library.snapshot().get("items")).getFirst()).get("token");
                while (ui.cover(token) == null && System.nanoTime() < deadline) Thread.sleep(10);
                check(ui.cover(token) != null);
                player.prepare(new OnlinePlaybackController.Target(AccountPlatform.NETEASE, "123"));
                while (!Boolean.TRUE.equals(player.snapshot().get("ready")) && System.nanoTime() < deadline) Thread.sleep(10);
                check(Boolean.TRUE.equals(player.snapshot().get("ready")) && !Boolean.TRUE.equals(player.snapshot().get("armed")));
                qr.set(0);
                Object coverKey = route.getConstructor(String.class).newInstance(SaltNativeUi.PLUGIN_ID + "/native/NETEASE");
                SwingUtilities.invokeAndWait(() -> {
                    ImageComposeScene scene = new ImageComposeScene(740, 820, DensityKt.Density(1f, 1f), LayoutDirection.Ltr, Dispatchers.getUnconfined(),
                            (composer, flags) -> { cached.invoke(coverKey, composer, flags); return Unit.INSTANCE; });
                    try {
                        for (int frame = 1; frame <= 4; frame++) try (Image image = scene.render(frame * 16_000_000L)) {
                            if (frame == 4) try (Data png = image.encodeToData(EncodedImageFormat.PNG, 100, 0)) {
                                byte[] bytes = png.getBytes(); var pixels = ImageIO.read(new ByteArrayInputStream(bytes));
                                int colored = 0;
                                for (int y = 70; y < 720; y++) for (int x = 20; x < 140; x++) {
                                    int rgb = pixels.getRGB(x,y);
                                    int red = (rgb >> 16) & 255, green = (rgb >> 8) & 255, blue = rgb & 255;
                                    if ((rgb >>> 24) > 240 && (Math.abs(red-221)<8 && Math.abs(green-85)<8 && blue<8
                                            || red<8 && Math.abs(green-136)<8 && Math.abs(blue-102)<8)) colored++;
                                }
                                check(colored > 400); Files.write(output.resolve("cover-prepared-740.png"), bytes);
                                backdrop(image,output.resolve("cover-prepared-740-backdrop.png"),false);
                            }
                        }
                        check(!scene.getSemanticsOwners().isEmpty());
                        var song = findSong(scene.getSemanticsOwners().iterator().next().getRootSemanticsNode()); check(song != null);
                        var root = scene.getSemanticsOwners().iterator().next().getUnmergedRootSemanticsNode();
                        var titleNode = findText(root,"Synthetic song"); var artistNode = findText(root,"Artist");
                        check(titleNode != null && artistNode != null);
                        check(findText(root, "\u5217\u8868\u5df2\u8bfb\u53d6") == null && findText(root, "Offline fixture") == null);
                        check(findText(root, "Resolution does not play") == null);
                        check(findText(root, "\u4e0a\u4e00\u9875") == null && findText(root, "\u4e0b\u4e00\u9875") == null);
                        check(Math.abs(titleNode.getBoundsInRoot().getLeft() - artistNode.getBoundsInRoot().getLeft()) < 1f);
                        try (Image image = scene.render(90_000_000L); Data png = image.encodeToData(EncodedImageFormat.PNG,100,0)) {
                            var pixels = ImageIO.read(new ByteArrayInputStream(png.getBytes()));
                            int rowY = (int)titleNode.getBoundsInRoot().getTop();
                            check((pixels.getRGB(700,rowY) >>> 24) == 0); // Unselected row cannot paint over the backdrop.
                        }
                        var bounds = song.getBoundsInRoot(); float x = (bounds.getLeft() + bounds.getRight()) / 2, y = (bounds.getTop() + bounds.getBottom()) / 2;
                        tap(scene,x,y,1000); Thread.sleep(600);
                        try (Image ignored = scene.render(700_000_000L)) { check(queues.get() == 0 && actions.get() == 0); }
                        tap(scene,x,y,2000); Thread.sleep(70); tap(scene,x,y,2110);
                        long until = System.nanoTime() + 3_000_000_000L;
                        while (queues.get() == 0 && System.nanoTime() < until) Thread.sleep(10);
                        check(queues.get() == 1 && actions.get() == 1 && armed.get());
                        Thread.sleep(450);
                        try (Image ignored = scene.render(900_000_000L)) {
                            check(findText(scene.getSemanticsOwners().iterator().next().getUnmergedRootSemanticsNode(), "SUBMITTED") == null);
                        }
                    } catch (Exception error) { throw new AssertionError(error); }
                    finally { scene.close(); }
                });
                check(requests.get() == 1 && queues.get() == 1 && actions.get() == 1);
                for (int width : new int[]{740, 420}) for (int count : new int[]{0, 2, 20, 21}) {
                    catalogSize.set(count);
                    library.invalidate();
                    library.browse(AccountPlatform.NETEASE, Catalog.View.DISCOVER);
                    long ready = System.nanoTime() + 3_000_000_000L;
                    while (!library.snapshot().get("title").equals("Synthetic playlist") && System.nanoTime() < ready) Thread.sleep(10);
                    check(library.snapshot().get("title").equals("Synthetic playlist"));
                    assertPager(cached, coverKey, width, count > 20, library);
                    if (count > 20) {
                        // Disposal invalidates the catalog; reopen before exercising the last-page route.
                        library.browse(AccountPlatform.NETEASE, Catalog.View.DISCOVER);
                        ready = System.nanoTime() + 3_000_000_000L;
                        while (!Boolean.TRUE.equals(library.snapshot().get("more")) && System.nanoTime() < ready) Thread.sleep(10);
                        library.turn(1);
                        ready = System.nanoTime() + 3_000_000_000L;
                        while (!library.snapshot().get("title").equals("Synthetic playlist") && System.nanoTime() < ready) Thread.sleep(10);
                        assertPager(cached, coverKey, width, true, library);
                    }
                }
                invalidItems.set(true); catalogSize.set(21);
                library.invalidate();
                library.browse(AccountPlatform.NETEASE, Catalog.View.DISCOVER);
                long hiddenReady = System.nanoTime()+3_000_000_000L;
                while (!Boolean.TRUE.equals(library.snapshot().get("loaded")) && System.nanoTime()<hiddenReady) Thread.sleep(10);
                check(((List<?>)library.snapshot().get("items")).isEmpty() && Boolean.TRUE.equals(library.snapshot().get("more")));
                assertPager(cached, coverKey, 420, true, library);
                invalidItems.set(false);
                for (AccountPlatform platform : AccountPlatform.values()) SwingUtilities.invokeAndWait(() -> {
                    ImageComposeScene scene = new ImageComposeScene(40, 40, DensityKt.Density(1f, 1f), LayoutDirection.Ltr, Dispatchers.getUnconfined(),
                            (composer, flags) -> { PlatformIconsKt.PlatformIcon(ui, platform, composer, 0); return Unit.INSTANCE; });
                    try (Image image = scene.render(16_000_000L); Data png = image.encodeToData(EncodedImageFormat.PNG,100,0)) {
                        var pixels = ImageIO.read(new ByteArrayInputStream(png.getBytes())); int branded = 0;
                        int color = platform == AccountPlatform.BILIBILI ? 0x00a1d6 : 0xd43c33;
                        for (int y=0; y<20; y++) for (int x=0; x<20; x++) if ((pixels.getRGB(x,y) & 0xffffff) == color && (pixels.getRGB(x,y) >>> 24) > 200) branded++;
                        check(branded > 70);
                    } catch (Exception error) { throw new AssertionError(error); }
                    finally { scene.close(); }
                });
            }
            SwingUtilities.invokeAndWait(() -> {});
        }
        System.out.println("PASS: " + checks + " offscreen Compose/Skia renders and single/double mouse gesture checks; fake image/queue, no browser/window, real account/network/audio; synthetic PNGs in " + output);
    }
    private static void assertPager(Function3<Object,Composer,Integer,Object> cached, Object key, int width, boolean expected, LibraryController library) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            // Long catalogs scroll to the footer; it must not float over the middle of the list.
            ImageComposeScene scene = new ImageComposeScene(width, 768, DensityKt.Density(1f,1f), LayoutDirection.Ltr, Dispatchers.getUnconfined(),
                    (composer, flags) -> { cached.invoke(key, composer, flags); return Unit.INSTANCE; });
            try {
                for (int frame=1; frame<=4; frame++) try (Image ignored = scene.render(System.nanoTime())) { }
                var root = scene.getSemanticsOwners().iterator().next().getUnmergedRootSemanticsNode();
                int rows = ((List<?>)library.snapshot().get("items")).size();
                if (rows == 20) check(findText(root,"\u4e0b\u4e00\u9875") == null);
                if (expected) {
                    scrollTo(scene, rows + 4);
                    root = scene.getSemanticsOwners().iterator().next().getUnmergedRootSemanticsNode();
                }
                check((findText(root,"\u4e0a\u4e00\u9875") != null) == expected);
                check((findText(root,"\u4e0b\u4e00\u9875") != null) == expected);
                if (expected) {
                    boolean first = library.snapshot().get("page").equals(1);
                    var target = findText(root, first ? "\u4e0b\u4e00\u9875" : "\u4e0a\u4e00\u9875");
                    var counter = findText(root, first ? "1/2" : "2/2"); check(counter != null);
                    check(counter.getBoundsInRoot().getLeft() > findText(root,"\u4e0a\u4e00\u9875").getBoundsInRoot().getLeft());
                    check(counter.getBoundsInRoot().getRight() < findText(root,"\u4e0b\u4e00\u9875").getBoundsInRoot().getRight());
                    var bounds = target.getBoundsInRoot();
                    check(bounds.getTop() > 0 && bounds.getBottom() <= 768 && bounds.getLeft() >= 0 && bounds.getRight() <= width);
                    tap(scene, (bounds.getLeft()+bounds.getRight())/2, (bounds.getTop()+bounds.getBottom())/2, 3000);
                    long until = System.nanoTime()+3_000_000_000L;
                    while ((!library.snapshot().get("page").equals(first ? 2 : 1) || !Boolean.TRUE.equals(library.snapshot().get("loaded"))) && System.nanoTime()<until) Thread.sleep(10);
                    check(library.snapshot().get("page").equals(first ? 2 : 1) && Boolean.TRUE.equals(library.snapshot().get("loaded")));
                    Thread.sleep(450);
                    scrollTo(scene, ((List<?>)library.snapshot().get("items")).size() + 4);
                    try (Image ignored = scene.render(System.nanoTime())) {
                        var after = scene.getSemanticsOwners().iterator().next().getUnmergedRootSemanticsNode();
                        check(findText(after,"\u4e0a\u4e00\u9875") != null && findText(after,"\u4e0b\u4e00\u9875") != null);
                    }
                }
            } catch (Exception error) { throw new AssertionError(error);
            } finally { scene.close(); }
        });
    }
    private static androidx.compose.ui.semantics.SemanticsNode findAction(androidx.compose.ui.semantics.SemanticsNode node, androidx.compose.ui.semantics.SemanticsPropertyKey<?> key) {
        if (node.getConfig().contains(key)) return node;
        for (var child : node.getChildren()) { var found = findAction(child,key); if (found != null) return found; }
        return null;
    }
    private static void scrollTo(ImageComposeScene scene, int index) throws Exception {
        for (int frame=1; frame<=4; frame++) try (Image ignored=scene.render(System.nanoTime())) { }
        var key = androidx.compose.ui.semantics.SemanticsActions.INSTANCE.getScrollToIndex();
        var root = scene.getSemanticsOwners().iterator().next().getUnmergedRootSemanticsNode();
        var list = findAction(root,key); check(list != null);
        check(Boolean.TRUE.equals(list.getConfig().get(key).getAction().invoke(index)));
        for (int frame=1; frame<=4; frame++) { Thread.sleep(30); try (Image ignored=scene.render(System.nanoTime())) { } }
    }
}
