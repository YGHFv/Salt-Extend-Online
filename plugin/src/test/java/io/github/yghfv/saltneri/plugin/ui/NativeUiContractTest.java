package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.Composer;
import com.xuncorp.spw.workshop.api.hook.*;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.library.*;
import java.io.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function3;

/** Real host reflection and entry builder, fake registrar; never installs or renders a hook. */
public final class NativeUiContractTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Native UI check " + checks); }
    private static final class Hooks extends HookRegistrar {
        int uiCalls, methodCalls, closed;
        boolean failUi;
        ComposableHook content;
        private HookHandle handle() { return new HookHandle() {
            boolean done;
            public void unhook() { if (!done) { done = true; closed++; } }
            public void close() { unhook(); }
        }; }
        public HookHandle afterComposable(String owner, String name, String inClass, List<String> types, int priority, ComposableHook hook) {
            check(owner.equals(SaltNativeUi.SIDEBAR) && inClass.equals(SaltNativeUi.SIDEBAR_CALL));
            check(name.equals("\u037f") && types.equals(List.of(SaltNativeUi.ROUTE_ITEM)));
            if (failUi) throw new IllegalStateException("Fixture UI registration failure");
            uiCalls++; content = hook; return handle();
        }
        public HookHandle hookMethod(String owner, String name, List<String> types, MethodHook hook) {
            methodCalls++; throw new AssertionError("Native page must not depend on an ordinary method Hook");
        }
        public HookHandle replaceComposable(String c, String m, String i, List<String> t, int p, ComposableHook h) { throw new AssertionError("Original UI must not be replaced"); }
        public <S> HookHandle appendContent(String c, String m, String i, String a, Class<S> s, List<String> t, int p, ContentHook<S> h) { throw new AssertionError("Unexpected DSL append"); }
    }
    private static Composer composerFixture() {
        return (Composer)Proxy.newProxyInstance(Composer.class.getClassLoader(), new Class<?>[]{Composer.class}, (self, method, args) -> {
            String name = method.getName();
            if (name.equals("startRestartGroup")) return self;
            if (name.equals("rememberedValue")) return Composer.Companion.getEmpty();
            if (name.equals("shouldExecute") || name.startsWith("changed")) return true;
            if (name.equals("toString")) return "OfflineComposerFixture";
            if (method.getReturnType() == void.class || name.equals("endRestartGroup")) return null;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == long.class) return 0L;
            throw new AssertionError("Unexpected composer call: " + name);
        });
    }
    public static void main(String[] args) throws Exception {
        ClassLoader host = NativeUiContractTest.class.getClassLoader();
        SaltNativeUi.verify(host); checks++;
        ClassLoader tampered = new ClassLoader(host) {
            public InputStream getResourceAsStream(String name) {
                return name.equals(SaltNativeUi.SIDEBAR_CALL.replace('.', '/') + ".class")
                        ? new ByteArrayInputStream(new byte[]{0}) : super.getResourceAsStream(name);
            }
        };
        try { SaltNativeUi.verify(tampered); throw new AssertionError("Changed UI accepted"); }
        catch (IOException expected) { checks++; }
        AtomicBoolean permitted = new AtomicBoolean(true);
        AtomicInteger upstream = new AtomicInteger(), prepare = new AtomicInteger(), queue = new AtomicInteger(), actions = new AtomicInteger();
        Clock clock = Clock.systemUTC();
        try (OnlinePlaybackController player = new OnlinePlaybackController(p -> null, permitted::get, () -> false, (a,s) -> queue.incrementAndGet());
             LibraryController library = new LibraryController(p -> null, permitted::get, target -> prepare.incrementAndGet(), () -> player.invalidate("Navigation"), clock,
                     account -> (query, quality, scope, request) -> {
                         upstream.incrementAndGet();
                         return new Catalog.Page("Fixture", List.of(new Catalog.Item(Catalog.Kind.TRACK, "123", "Song", "Artist", 1, true,
                                 java.net.URI.create("https://p1.music.126.net/fixture.jpg"))), false, clock.instant().plusSeconds(300));
                     })) {
            NativeDesk desk = new NativeDesk(library, player,
                    p -> new AccountLoginController.Snapshot(AccountLoginController.Phase.IDLE, "Not logged in", null, ""),
                    (p,a) -> actions.incrementAndGet(), permitted::get);
            Hooks hooks = new Hooks();
            Class<?> singleton = Class.forName(SaltPageContentBinding.SINGLETON, true, host);
            Object cachedContent = singleton.getMethod(SaltPageContentBinding.GETTER).invoke(singleton.getField("INSTANCE").get(null));
            Field blockField = cachedContent.getClass().getDeclaredField("_block"); blockField.setAccessible(true);
            Method update = cachedContent.getClass().getMethod("update", Object.class);
            Object hostBlock = blockField.get(cachedContent);
            AtomicInteger fallback = new AtomicInteger();
            Object fixtureBlock = (Function3<Object,Composer,Integer,Unit>)(key,c,flags) -> { fallback.incrementAndGet(); return Unit.INSTANCE; };
            javax.swing.SwingUtilities.invokeAndWait(() -> { try { update.invoke(cachedContent, fixtureBlock); } catch (Exception error) { throw new AssertionError(error); } });
            try (SaltNativeUi ui = new SaltNativeUi(host, hooks, desk, permitted::get)) {
                javax.swing.SwingUtilities.invokeAndWait(() -> {});
                check(ui.diagnostics().contains("bound=true"));
                check(hooks.uiCalls == 1 && hooks.methodCalls == 0);
                check(upstream.get() == 0 && queue.get() == 0 && actions.get() == 0);
                check(hooks.content.getClass().getMethod("Content", UiHookCall.class, Composer.class, int.class).getReturnType() == void.class);
                check(desk.snapshot(AccountPlatform.NETEASE).login().qrContent() == null);
                check(upstream.get() == 0 && prepare.get() == 0);
                check(ui.platform(null) == null && ui.platform("unrelated") == null);
                try { ui.requestLogin(AccountPlatform.BILIBILI); throw new AssertionError("Login without sidebar navigator"); }
                catch (IllegalStateException expected) { checks++; }
                Object lease = ui.pageEntered(AccountPlatform.BILIBILI);
                check(actions.get() == 0 && ui.diagnostics().contains("rendered=1") && ui.diagnostics().contains("pendingLogin=false"));
                ui.pageLeft(lease); check(ui.diagnostics().contains("visible=none"));
                Class<?> route = Class.forName(SaltNativeUi.MOD_ROUTE, false, host);
                check(ui.platform(route.getConstructor(String.class).newInstance("unrelated.plugin")) == null);
                for (AccountPlatform platform : AccountPlatform.values()) {
                    check(ui.platform(route.getConstructor(String.class).newInstance(SaltNativeUi.PLUGIN_ID + "/native/" + platform.name())) == platform);
                }
                check(ui.isMusicLibrary(Class.forName(SaltNativeUi.ROUTE_ITEM, false, host)
                        .getConstructor(Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute", false, host))
                        .newInstance(Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute$MusicLibrary", false, host).getField("INSTANCE").get(null))));
                // Use Salt's own resolver to prove this is a unique supported composable call site.
                Class<?> resolverType = Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052a", true, host);
                Object resolver = resolverType.getField("\u037f").get(null);
                Object site = resolverType.getMethod("\u037f", String.class, String.class, String.class, List.class)
                        .invoke(resolver, SaltNativeUi.SIDEBAR, "\u037f", SaltNativeUi.SIDEBAR_CALL, List.of(SaltNativeUi.ROUTE_ITEM));
                check(site != null);
                Class<?> entryScope = Class.forName("androidx.navigation3.runtime.EntryProviderScope", false, host);
                Object scope = entryScope.getConstructor(Function1.class).newInstance((Function1<Object,Object>)key -> { throw new AssertionError("Missing native entry"); });
                Object unrelated = route.getConstructor(String.class).newInstance("unrelated.plugin");
                // This provider keeps the actual host lambda cached before page binding.
                entryScope.getMethod("addEntryProvider", kotlin.reflect.KClass.class, Function1.class, Map.class, Function3.class)
                        .invoke(scope, kotlin.jvm.internal.Reflection.getOrCreateKotlinClass(route),
                                (Function1<Object,Object>)key -> key, Map.of("fixture", true), cachedContent);
                @SuppressWarnings("unchecked") Function1<Object,Object> built = (Function1<Object,Object>)entryScope.getMethod("build").invoke(scope);
                Field entriesField = SaltNativeUi.class.getDeclaredField("entries"); entriesField.setAccessible(true);
                @SuppressWarnings("unchecked") Map<AccountPlatform,Object> entries = (Map<AccountPlatform,Object>)entriesField.get(ui);
                AtomicInteger biliPage = new AtomicInteger(), neteasePage = new AtomicInteger();
                entries.put(AccountPlatform.BILIBILI, (Function3<Object,Composer,Integer,Unit>)(key,c,flags) -> { biliPage.incrementAndGet(); return Unit.INSTANCE; });
                entries.put(AccountPlatform.NETEASE, (Function3<Object,Composer,Integer,Unit>)(key,c,flags) -> { neteasePage.incrementAndGet(); return Unit.INSTANCE; });
                Composer composer = composerFixture();
                for (AccountPlatform platform : AccountPlatform.values()) {
                    Object key = route.getConstructor(String.class).newInstance(SaltNativeUi.PLUGIN_ID + "/native/" + platform.name());
                    Object navEntry = built.invoke(key);
                    check(navEntry.getClass().getMethod("getContentKey").invoke(navEntry).equals(key));
                    check(navEntry.getClass().getMethod("getMetadata").invoke(navEntry).equals(Map.of("fixture", true)));
                    javax.swing.SwingUtilities.invokeAndWait(() -> {
                        try { navEntry.getClass().getMethod("Content", Composer.class, int.class).invoke(navEntry, composer, 0); }
                        catch (Exception error) { throw new AssertionError(error); }
                    });
                }
                check(biliPage.get() == 1 && neteasePage.get() == 1 && fallback.get() == 0);
                Object modEntry = built.invoke(unrelated);
                javax.swing.SwingUtilities.invokeAndWait(() -> {
                    try { modEntry.getClass().getMethod("Content", Composer.class, int.class).invoke(modEntry, composer, 0); }
                    catch (Exception error) { throw new AssertionError(error); }
                });
                check(fallback.get() == 1 && biliPage.get() == 1 && neteasePage.get() == 1);
                check(upstream.get() == 0 && queue.get() == 0 && actions.get() == 0);
                Object companion = route.getField("Companion").get(null);
                Object serializer = companion.getClass().getMethod("serializer").invoke(companion);
                for (AccountPlatform platform : AccountPlatform.values()) {
                    Object key = route.getConstructor(String.class).newInstance(SaltNativeUi.PLUGIN_ID + "/native/" + platform.name());
                    @SuppressWarnings("unchecked") var strategy = (kotlinx.serialization.KSerializer<Object>)serializer;
                    String encoded = kotlinx.serialization.json.Json.Default.encodeToString(strategy, key);
                    check(kotlinx.serialization.json.Json.Default.decodeFromString(strategy, encoded).equals(key));
                }
                desk.browse(AccountPlatform.NETEASE, Catalog.View.DISCOVER);
                long deadline = System.nanoTime() + 3_000_000_000L;
                while (((List<?>)desk.snapshot(AccountPlatform.NETEASE).catalog().get("items")).isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
                var rows = (List<?>)desk.snapshot(AccountPlatform.NETEASE).catalog().get("items");
                check(upstream.get() == 1 && rows.size() == 1 && queue.get() == 0);
                String token = (String)((Map<?,?>)rows.getFirst()).get("token"); desk.choose(token);
                check(desk.cover(token).getHost().equals("p1.music.126.net") && desk.currentRow(token));
                check(!desk.snapshot(AccountPlatform.NETEASE).catalog().toString().contains("https://"));
                check(prepare.get() == 1 && queue.get() == 0);
                desk.leave();
                check(desk.cover(token) == null && !desk.currentRow(token));
                try { desk.choose(token); throw new AssertionError("Stale native row accepted"); }
                catch (IllegalStateException expected) { checks++; }
                desk.action(AccountPlatform.BILIBILI, NativeDesk.Action.LOGIN); check(actions.get() == 1);
                Field pendingField = SaltNativeUi.class.getDeclaredField("pendingLogin"); pendingField.setAccessible(true);
                @SuppressWarnings("unchecked") AtomicReference<AccountPlatform> pending = (AtomicReference<AccountPlatform>)pendingField.get(ui);
                pending.set(AccountPlatform.BILIBILI);
                Object firstPage = ui.pageEntered(AccountPlatform.BILIBILI), secondPage = ui.pageEntered(AccountPlatform.BILIBILI);
                check(actions.get() == 2 && pending.get() == null);
                ui.pageLeft(firstPage); check(ui.diagnostics().contains("visible=[BILIBILI]"));
                ui.pageLeft(secondPage); check(ui.diagnostics().contains("visible=none"));
                pending.set(AccountPlatform.NETEASE); ui.cancelPendingLogin(AccountPlatform.BILIBILI);
                check(pending.get() == AccountPlatform.NETEASE);
                ui.cancelPendingLogin(null); Object cancelledPage = ui.pageEntered(AccountPlatform.NETEASE);
                check(actions.get() == 2); ui.pageLeft(cancelledPage);
                Hooks failing = new Hooks(); failing.failUi = true;
                try { new SaltNativeUi(host, failing, desk, permitted::get); throw new AssertionError("Partial hook install accepted"); }
                catch (IllegalStateException expected) { checks++; }
                javax.swing.SwingUtilities.invokeAndWait(() -> {});
                check(failing.closed == 0 && ui.diagnostics().contains("bound=true"));
                permitted.set(false);
                try { desk.browse(AccountPlatform.BILIBILI, Catalog.View.MINE); throw new AssertionError("Unauthorized native action"); }
                catch (SecurityException expected) { checks++; }
                check(!ui.available() && queue.get() == 0);
                permitted.set(true); check(!ui.available());
                ui.close(); ui.close(); javax.swing.SwingUtilities.invokeAndWait(() -> {});
                check(hooks.closed == 1 && blockField.get(cachedContent) == fixtureBlock);
                Object oldKey = route.getConstructor(String.class).newInstance(SaltNativeUi.PLUGIN_ID + "/native/NETEASE");
                Object retired = built.invoke(oldKey);
                javax.swing.SwingUtilities.invokeAndWait(() -> {
                    try { retired.getClass().getMethod("Content", Composer.class, int.class).invoke(retired, composer, 0); }
                    catch (Exception error) { throw new AssertionError(error); }
                });
                check(fallback.get() == 2 && biliPage.get() == 1 && neteasePage.get() == 1);
                library.close(); player.close(); desk.leave(); checks++;
            } finally {
                javax.swing.SwingUtilities.invokeAndWait(() -> { try { update.invoke(cachedContent, hostBlock); } catch (Exception error) { throw new AssertionError(error); } });
            }
        }
        System.out.println("PASS: " + checks + " native UI contracts; actual cached host NavEntry/Compose content dispatch, fake sidebar registrar/composer; no visual UI rendered, host queue changed or account accessed");
    }
}
