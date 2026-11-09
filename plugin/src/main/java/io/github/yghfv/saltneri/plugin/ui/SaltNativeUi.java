package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.Composer;
import androidx.compose.runtime.CompositionLocal;
import androidx.compose.runtime.ProvidableCompositionLocal;
import androidx.compose.runtime.MutableState;
import androidx.compose.runtime.SnapshotStateKt;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.graphics.Shape;
import androidx.compose.ui.text.TextStyle;
import com.xuncorp.spw.workshop.api.hook.*;
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function3;

/** Capability-checked host-only bindings. No second Compose runtime or invented host classes. */
public final class SaltNativeUi implements AutoCloseable {
    public static final String PLUGIN_ID = "io.github.yghfv.saltneri.online";
    public static final String SIDEBAR = "androidx.compose.ui.\u0b1a";
    public static final String SIDEBAR_CALL = SIDEBAR + "$\u052e";
    public static final String ROUTE_ITEM = "androidx.compose.ui.\u063a$\u052a";
    public static final String MOD_ROUTE = "com.xuncorp.voxzen.ui.navigation.ScreenRoute$ModConfigContent";
    public record Palette(TextStyle main, TextStyle sub, long text, long secondary, long background, long pageBackground,
                          long accent, Shape shape, float padding) { }
    private final NativeDesk desk;
    private final java.util.Map<AccountPlatform,Object> pageMemory = new java.util.EnumMap<>(AccountPlatform.class);
    public synchronized Object pageMemory(AccountPlatform platform, java.util.function.Supplier<Object> create) { return pageMemory.computeIfAbsent(platform,p -> create.get()); }
    private final CoverLoader covers;
    private final CoverLoader avatars;
    private final SaltToolbarBindings toolbar;
    private final SaltSecondaryHeaderBindings secondaryHeader;
    private final SaltSearchBindings search;
    private final SaltContextMenuBindings contextMenu;
    private final SaltPlaylistMenuBindings playlistMenu;
    private final BooleanSupplier permitted;
    private final ClassLoader host;
    private final HookRegistrar hooks;
    private volatile SaltSidebarBindings sidebar;
    private final SidebarPolicy sidebarPolicy=new SidebarPolicy();
    private SaltSidebarSettingsBindings sidebarSettings;
    private String sidebarSettingsFailure = "none";
    private String sidebarFailure = "none";
    private final List<HookHandle> handles = new ArrayList<>();
    private final Method routeGetter, pluginIdGetter, nativeRow, text, canvas;
    private final Method colors, styles, shapes, dimens, localNavigator, navigate;
    private final Constructor<?> routeConstructor;
    private final SaltPageContentBinding pageBinding;
    private final Map<Object, AccountPlatform> pages = new IdentityHashMap<>();
    private final AtomicReference<AccountPlatform> pendingLogin = new AtomicReference<>();
    private final AtomicInteger dispatched = new AtomicInteger(), selected = new AtomicInteger(), rendered = new AtomicInteger();
    private volatile String failure = "none";
    private final Map<AccountPlatform, Object> routes = new EnumMap<>(AccountPlatform.class);
    private final Map<AccountPlatform, Object> entries = new EnumMap<>(AccountPlatform.class);
    private final Object musicLibrary;
    private final ProvidableCompositionLocal<Object> styleLocal;
    private final MutableState<Object> sidebarStyle = SnapshotStateKt.mutableStateOf(null, SnapshotStateKt.structuralEqualityPolicy());
    private volatile Object navigator;
    private com.xuncorp.spw.workshop.api.config.ConfigManager configManager;
    private NavigationPreferences navigationPreferences;
    private final java.util.concurrent.atomic.AtomicBoolean homeScheduled=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean homePending;
    private final java.util.concurrent.ExecutorService homeWorker=java.util.concurrent.Executors.newSingleThreadExecutor(r->{
        Thread thread=new Thread(r,"salt-extend-home");thread.setDaemon(true);return thread;
    });
    public static final String SIDEBAR_CONFIG=PLUGIN_ID+"/settings/sidebar";
    public void configuration(com.xuncorp.spw.workshop.api.config.ConfigManager manager,NavigationPreferences preferences) {
        configManager=manager;navigationPreferences=preferences;
    }
    public boolean settingsPage(Object route) {
        return available() && route!=null && routeConstructor.getDeclaringClass().isInstance(route)
                && SIDEBAR_CONFIG.equals(invoke(pluginIdGetter,route));
    }
    public void openSidebarConfiguration() {
        if(!available() || navigator==null)throw new IllegalStateException("Native navigation unavailable");
        SaltPageContentBinding.onUiThread(()->{
            try {pageBinding.requireInstalled();invoke(navigate,navigator,routeConstructor.newInstance(SIDEBAR_CONFIG));}
            catch(ReflectiveOperationException error) {throw new IllegalStateException("Sidebar settings unavailable",error);}
        });
    }
    public void settingsBack() {if(available() && navigator!=null)invoke(valueMethod(navigator,"back"),navigator);}
    private static Method valueMethod(Object owner,String name) {
        try {return owner.getClass().getMethod(name);}
        catch(NoSuchMethodException failure) {throw new IllegalStateException("Navigation capability missing",failure);}
    }
    public String sidebarLabel(String button) {return sidebarSettings==null?button:sidebarSettings.label(button);}
    public void sidebarConfigurationScreen(String title,Object back,Object content,Composer composer) {
        sidebarSettings.settingsScreen(title,back,content,composer);
    }
    public void sidebarConfigurationCaption(String caption,Composer composer) {sidebarSettings.settingsCaption(caption,composer);}
    public void sidebarConfigurationColumn(Object content,Composer composer) {sidebarSettings.settingsColumn(content,composer);}
    public void sidebarSwitch(String button,boolean visible,Composer composer) {
        if(sidebarSettings==null || "Settings".equals(button))return;
        sidebarSettings.switchSetting(visible,sidebarLabel(button),value->{
            if(!available() || configManager==null)return kotlin.Unit.INSTANCE;
            var config=configManager.getConfig();String key="ui.sidebar."+button;
            boolean previous=config.get(key,true);config.set(key,value);
            if(!config.save())config.set(key,previous);
            else {
                Set<String> invisible=new HashSet<>();
                for(String name:SidebarPolicy.BUTTONS)if(!config.get("ui.sidebar."+name,true))invisible.add(name);
                sidebarSettings(sidebarOptimized(),hiddenSidebar(),hiddenSidebarToggle(),invisible);
            }
            return kotlin.Unit.INSTANCE;
        },composer);
    }
    public List<io.github.yghfv.saltneri.plugin.host.SaltPlaylistGateway.Choice> nativePlaylistChoices() {
        if(!available())return List.of();
        try{return new io.github.yghfv.saltneri.plugin.host.SaltPlaylistGateway(host).choices(3000);}
        catch(Exception failure) {return List.of();}
    }
    public void homePlaylistChoices(List<io.github.yghfv.saltneri.plugin.host.SaltPlaylistGateway.Choice> choices) {
        if(sidebarSettings!=null)sidebarSettings.playlistChoices(choices);
    }
    private volatile boolean closed;

    public SaltNativeUi(ClassLoader host, HookRegistrar hooks, NativeDesk desk, BooleanSupplier permitted) throws Exception {
        this(host, hooks, desk, permitted, new CoverLoader());
    }
    @SuppressWarnings("unchecked")
    public SaltNativeUi(ClassLoader host, HookRegistrar hooks, NativeDesk desk, BooleanSupplier permitted, CoverLoader covers) throws Exception {
        this(host, hooks, desk, permitted, covers, new CoverLoader());
    }
    @SuppressWarnings("unchecked")
    public SaltNativeUi(ClassLoader host, HookRegistrar hooks, NativeDesk desk, BooleanSupplier permitted, CoverLoader covers, CoverLoader avatars) throws Exception {
        this.covers = covers;
        this.host = host; this.hooks = hooks;
        this.avatars = avatars;
        this.desk = desk; this.permitted = permitted;
        if (!permitted.getAsBoolean()) throw new SecurityException("Native UI permission required");
        verify(host);
        SaltToolbarBindings optionalToolbar;
        try { optionalToolbar = new SaltToolbarBindings(host); }
        catch (ReflectiveOperationException | LinkageError unsupported) { optionalToolbar = null; }
        toolbar = optionalToolbar;
        SaltSecondaryHeaderBindings optionalHeader;
        try {optionalHeader=new SaltSecondaryHeaderBindings(host);}
        catch(ReflectiveOperationException | LinkageError unsupported) {optionalHeader=null;}
        secondaryHeader=optionalHeader;
        SaltSearchBindings optionalSearch;
        try { optionalSearch = new SaltSearchBindings(host); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) { optionalSearch = null; }
        search = optionalSearch;
        SaltContextMenuBindings optionalMenu;
        try { optionalMenu = new SaltContextMenuBindings(host); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) { optionalMenu = null; }
        contextMenu = optionalMenu;
        SaltPlaylistMenuBindings optionalPlaylistMenu;
        try {optionalPlaylistMenu=new SaltPlaylistMenuBindings(host);}
        catch(ReflectiveOperationException | RuntimeException | LinkageError unsupported) {optionalPlaylistMenu=null;}
        playlistMenu=optionalPlaylistMenu;
        Class<?> item = type(host, ROUTE_ITEM), modRoute = type(host, MOD_ROUTE);
        type(host, SIDEBAR).getMethod("\u037f", item, Composer.class, int.class);
        routeGetter = item.getMethod("\u0528"); pluginIdGetter = modRoute.getMethod("getPluginId");
        routeConstructor = modRoute.getConstructor(String.class);
        musicLibrary = type(host, "com.xuncorp.voxzen.ui.navigation.ScreenRoute$MusicLibrary").getField("INSTANCE").get(null);
        localNavigator = type(host, "com.xuncorp.voxzen.ui.navigation.AppNavigationKt").getMethod("getLocalNavigator");
        navigate = type(host, "com.xuncorp.voxzen.ui.navigation.Navigator").getMethod("navigate", type(host, "com.xuncorp.voxzen.ui.navigation.ScreenRoute"));
        nativeRow = Arrays.stream(type(host, "androidx.compose.ui.\u04cc").getMethods())
                .filter(m -> m.getName().equals("\u037f") && m.getParameterCount() == 11).findFirst().orElseThrow();
        styleLocal = (ProvidableCompositionLocal<Object>)type(host, "androidx.compose.ui.\u04cc").getMethod("\u037f").invoke(null);
        text = type(host, "androidx.compose.foundation.text.BasicTextKt").getMethod("BasicText-4YKlhWE",
                String.class, Modifier.class, TextStyle.class, Function1.class, int.class, boolean.class,
                int.class, int.class, Composer.class, int.class, int.class);
        canvas = type(host, "androidx.compose.foundation.CanvasKt").getMethod("Canvas", Modifier.class, Function1.class, Composer.class, int.class);
        Class<?> theme = type(host, "androidx.compose.ui.\u02b0");
        colors = theme.getMethod("\u0528", Composer.class); styles = theme.getMethod("\u0529", Composer.class);
        shapes = theme.getMethod("\u052b", Composer.class); dimens = theme.getMethod("\u052a", Composer.class);
        validatePalette();
        Class<?> content = Class.forName("io.github.yghfv.saltneri.plugin.ui.SaltNativeContent", true, getClass().getClassLoader());
        Constructor<?> factory = content.getConstructor(SaltNativeUi.class);
        Method entry = content.getMethod("entry", SaltNativeUi.class, AccountPlatform.class);
        Method contentFactory = content.getMethod("provider", SaltNativeUi.class, Object.class);
        pageBinding = new SaltPageContentBinding(host, original -> invoke(contentFactory, null, this, original), this::available);
        for (AccountPlatform platform : AccountPlatform.values()) {
            routes.put(platform, routeConstructor.newInstance(PLUGIN_ID + "/native/" + platform.name()));
            entries.put(platform, pageBinding.callableBlock(entry.invoke(null, this, platform)));
        }
        try {
            pageBinding.install();
            handles.add(hooks.afterComposable(SIDEBAR, "\u037f", SIDEBAR_CALL, List.of(ROUTE_ITEM), 50,
                    (ComposableHook)factory.newInstance(this)));
            handles.add(hooks.hookMethod("com.xuncorp.voxzen.ui.navigation.Navigator", "back", List.of(), new MethodHook() {
                @Override public void before(MethodHookParam call) {
                    if(internalBack(call.getThisObject())) call.setResult(null);
                }
            }));
        } catch (Exception | LinkageError failure) { close(); throw failure; }
        installSidebarSettings();
    }
    private void installSidebarSettings() {
        if(sidebarSettings!=null)return;
        try {
            sidebarSettings=new SaltSidebarSettingsBindings(host,hooks,this);
            sidebarSettingsFailure="none";
        } catch(ReflectiveOperationException | RuntimeException | LinkageError failure) {
            sidebarSettingsFailure="settings-hooks/"+failure.getClass().getSimpleName();
        }
    }
    public synchronized void repairSidebar() {
        if(!available())throw new IllegalStateException("Native UI unavailable");
        installSidebarSettings();
        sidebarOptimization(sidebarOptimized());
    }
    private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    public static void verify(ClassLoader host) throws Exception {
        Properties pins = new Properties();
        try (InputStream input = SaltNativeUi.class.getResourceAsStream("/host-profiles/windows-steam-1.19.3-ui.properties")) {
            if (input == null) throw new IOException("Missing native UI profile"); pins.load(input);
        }
        if (pins.size() != 21) throw new IOException("Incomplete native UI profile");
        // Runtime compatibility follows classes/signatures, not version labels or byte-for-byte code.
        for (String name : pins.stringPropertyNames()) type(host,name);
        Class<?> nav=type(host,"com.xuncorp.voxzen.ui.navigation.Navigator");
        if(nav.getMethod("back").getReturnType()!=void.class || !List.class.isAssignableFrom(nav.getMethod("getNavBackStack").getReturnType()))
            throw new IOException("Unsupported navigation capability");
        if(type(host,"com.xuncorp.voxzen.ui.component.PlayerSheetState").getMethod("\u0528").getReturnType()!=boolean.class)
            throw new IOException("Unsupported player sheet capability");
    }
    private void validatePalette() throws Exception {
        for (String name : List.of("\u037f", "\u0528", "\u0529", "\u052a", "\u052b")) {
            if (colors.getReturnType().getMethod(name).getReturnType() != long.class) throw new IOException("Unsupported colors");
        }
        if (styles.getReturnType().getMethod("\u037f").getReturnType() != TextStyle.class
                || styles.getReturnType().getMethod("\u0528").getReturnType() != TextStyle.class
                || shapes.getReturnType().getMethod("\u0528").getReturnType() != Shape.class
                || dimens.getReturnType().getMethod("\u0529").getReturnType() != float.class) throw new IOException("Unsupported theme");
    }
    private static Object invoke(Method method, Object receiver, Object... args) {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException error) throw error;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Native UI invocation failed");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native UI binding failed"); }
    }
    private static Object value(Object owner, String name) {
        try { return invoke(owner.getClass().getMethod(name), owner); }
        catch (NoSuchMethodException error) { throw new IllegalStateException("Native theme binding missing"); }
    }
    public Palette palette(Composer composer) {
        Object c = invoke(colors, null, composer), s = invoke(styles, null, composer);
        return new Palette((TextStyle)value(s, "\u037f"), (TextStyle)value(s, "\u0528"),
                (long)value(c, "\u0528"), (long)value(c, "\u0529"), (long)value(c, "\u052b"), (long)value(c, "\u052a"),
                (long)value(c, "\u037f"), (Shape)value(invoke(shapes, null, composer), "\u0528"),
                (float)value(invoke(dimens, null, composer), "\u0529"));
    }
    public boolean available() {
        if (closed) return false;
        if (!permitted.getAsBoolean()) {
            SaltSidebarBindings compact = sidebar;
            if (compact != null) compact.enabled(false);
            closed = true; pendingLogin.set(null); covers.close(); pageBinding.close(); return false;
        }
        return true;
    }
    public NativeDesk desk() { return desk; }
    public void sidebarSettings(boolean optimized,boolean hidden,Set<String> invisible) {
        sidebarSettings(optimized,hidden,false,invisible);
    }
    public void sidebarSettings(boolean optimized,boolean hidden,boolean hideToggle,Set<String> invisible) {
        if(closed)return;
        sidebarPolicy.update(optimized,hidden,hideToggle,invisible);
        sidebarOptimization(optimized);
    }
    public boolean sidebarVisible(String button) {return sidebarPolicy.visible(button);}
    public boolean sidebarItemVisible(Object item) {return sidebarVisible(invoke(routeGetter,item).getClass().getSimpleName());}
    public boolean sidebarOptimized() {return sidebarPolicy.optimized();}
    public boolean hiddenSidebar() {return sidebarPolicy.hidden();}
    public boolean hiddenSidebarToggle() {return sidebarPolicy.hideToggle();}
    public SaltSidebarBindings sidebarBinding() {return sidebar;}
    public void sidebarViewModel(Object value) {if(playlistMenu!=null)playlistMenu.viewModel(value);}
    public Object playlistMenuState(Composer composer) {return playlistMenu==null?null:playlistMenu.state(composer);}
    public void showPlaylistMenu(Object state) {if(available() && state!=null)playlistMenu.show(state);}
    public void playlistMenu(Object state,Object playlist,Object navigator,BooleanSupplier current,Composer composer) {
        if(available() && state!=null && playlist!=null)playlistMenu.menu(state,playlist,navigator,
                ()->available() && sidebarOptimized() && current.getAsBoolean(),composer);
    }
    public float sidebarCaptionHeight() {return sidebarSettings.captionHeight();}
    public Object captionNode(Object current) {
        if(!(current instanceof androidx.compose.ui.node.NodeCoordinator coordinates))return null;
        var marker=coordinates.getLayoutNode();
        var parent=marker.getParent$ui();
        if(parent==null)return null;
        var children=parent.getChildren$ui();
        int index=children.indexOf(marker);
        for(int i=index-1;i>=0;i--) if(!children.get(i).getChildren$ui().isEmpty())return children.get(i);
        return null;
    }
    public androidx.compose.ui.layout.LayoutCoordinates captionCoordinates(Object marker) {
        var node=captionNode(marker);
        return node==null?null:((androidx.compose.ui.node.LayoutNode)node).getOuterCoordinator$ui();
    }
    public Modifier captionModifier(Object node) {return ((androidx.compose.ui.node.LayoutNode)node).getModifier();}
    public void captionModifier(Object node,Modifier modifier) {((androidx.compose.ui.node.LayoutNode)node).setModifier(modifier);}
    public List<Object> captionTree(Object node) {
        List<Object> result=new ArrayList<>();
        result.add(node);
        for(var child:((androidx.compose.ui.node.LayoutNode)node).getChildren$ui()) result.addAll(captionTree(child));
        return result;
    }
    public void hiddenToggleAnchor(float x,float y) {
        var binding=sidebar;if(binding!=null && binding.enabled())binding.anchor(new SaltSidebarBindings.Anchor(x,y));
    }
    public void collapseHiddenSidebar(Object state) {if(available() && hiddenSidebar() && sidebarSettings!=null)sidebarSettings.collapse(state);}
    public synchronized void sidebarOptimization(boolean enabled) {
        if (closed) return;
        if (enabled && sidebar == null) {
            SaltSidebarBindings candidate = null;
            try {
                candidate = new SaltSidebarBindings(host);
                candidate.install(hooks, this);
                sidebar = candidate; sidebarFailure = "none";
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                if (candidate != null) candidate.close();
                sidebarFailure = failure.getClass().getSimpleName();
            }
        }
        if (sidebar != null) sidebar.enabled(enabled);
    }
    public boolean nativeToolbarAvailable() { return toolbar != null; }
    public boolean nativeSecondaryHeaderAvailable() {return secondaryHeader!=null;}
    public void secondaryBack(Function0<kotlin.Unit> action,Modifier modifier,Composer composer) {secondaryHeader.back(action,modifier,composer);}
    public void secondaryHeader(String title,String subtitle,Modifier modifier,Object action,Object tools,Composer composer) {
        secondaryHeader.title(title,subtitle,modifier,action,tools,composer);
    }
    public void toolbarButton(String label, Modifier modifier, Function0<kotlin.Unit> click,
                              Object content, Composer composer) {
        toolbar.button(label, modifier, click, content, composer);
    }
    public void toolbarIcon(boolean sorting, Composer composer) { toolbar.icon(sorting, composer); }
    public boolean nativeSearchAvailable() { return search != null; }
    public boolean nativeContextMenuAvailable() { return contextMenu != null; }
    public void trackContextMenu(androidx.compose.ui.window.PopupPositionProvider position, Function0<kotlin.Unit> dismiss,
                                 java.util.function.Consumer<io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.QueueAction> select,
                                 io.github.yghfv.saltneri.plugin.library.SavedPlaylistController.Snapshot playlists,
                                 java.util.function.Consumer<String> save,Composer composer) {
        contextMenu.menu(position, dismiss, select, playlists, save, ()->{
            if(!available() || navigator==null) throw new IllegalStateException("Navigation unavailable");
            try {invoke(navigate,navigator,Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute$NewPlaylist",true,navigate.getDeclaringClass().getClassLoader()).getField("INSTANCE").get(null));}
            catch(ReflectiveOperationException failure) {throw new IllegalStateException("Native new playlist unavailable",failure);}
        },composer);
    }
    public androidx.compose.foundation.text.input.TextFieldDecorator searchDecorator(
            androidx.compose.foundation.text.input.TextFieldState state, androidx.compose.runtime.State<Boolean> hasText,
            androidx.compose.ui.focus.FocusRequester focus) { return search.decorator(state, hasText, focus); }
    public androidx.compose.animation.ContentTransform searchTransition(androidx.compose.animation.AnimatedContentTransitionScope<Boolean> scope) {
        return search.transition(scope);
    }
    public void searchClose(Modifier modifier, Function0<kotlin.Unit> action, Composer composer) { search.close(modifier, action, composer); }
    public byte[] cover(String token) {
        if (!available()) return null;
        return covers.requestSource(token, desk.cover(token), scope -> desk.resolveCover(token, scope), scope -> desk.fallbackCover(token, scope),
                () -> !closed && permitted.getAsBoolean() && desk.currentRow(token));
    }
    public void clearCovers() { covers.clear(); }
    public void releaseCovers() { covers.releaseRequests(); }
    public void prefetchCovers(java.util.List<String> tokens) {
        for(String token:tokens.stream().limit(20).toList()) cover(token);
    }
    public byte[] avatar(AccountPlatform platform, String revision) {
        if (!available()) return null;
        var account = desk.account(platform);
        if (!account.revision().equals(revision) || account.phase() != io.github.yghfv.saltneri.plugin.auth.AccountLoginController.Phase.LOGGED_IN) return null;
        return avatars.request("avatar:" + platform + ":" + revision, account.profile().avatar(), () -> {
            if (!available()) return false;
            var current = desk.account(platform);
            return current.phase() == io.github.yghfv.saltneri.plugin.auth.AccountLoginController.Phase.LOGGED_IN && current.revision().equals(revision);
        });
    }
    public record Thumbnail(androidx.compose.ui.graphics.ImageBitmap bitmap, org.jetbrains.skia.Bitmap owner) implements AutoCloseable {
        @Override public void close() { owner.close(); }
    }
    public Thumbnail thumbnail(byte[] png) {
        org.jetbrains.skia.Bitmap bitmap = new org.jetbrains.skia.Bitmap();
        try (org.jetbrains.skia.Image image = org.jetbrains.skia.Image.Companion.makeFromEncoded(png)) {
            int width = image.getWidth(), height = image.getHeight();
            if (width < 1 || height < 1 || width > CoverLoader.LIST_EDGE || height > CoverLoader.LIST_EDGE
                    || !bitmap.allocN32Pixels(width, height, false) || !image.readPixels(bitmap)) {
                throw new IllegalArgumentException("Invalid thumbnail");
            }
            return new Thumbnail(androidx.compose.ui.graphics.SkiaImageAsset_skikoKt.asComposeImageBitmap(bitmap), bitmap);
        } catch (RuntimeException error) { bitmap.close(); throw error; }
    }
    public ProvidableCompositionLocal<Object> styleLocal() { return styleLocal; }
    public Object readSidebarStyle(Composer composer) { return composer.consume(styleLocal); }
    public Object sidebarStyle() { return sidebarStyle.getValue(); }
    public void publishSidebarStyle(Object style) { if (available()) sidebarStyle.setValue(style); }
    public boolean isMusicLibrary(Object item) { return musicLibrary.equals(invoke(routeGetter, item)); }
    @SuppressWarnings("unchecked")
    public Object navigator(Composer composer) {
        Object current = composer.consume((CompositionLocal<Object>)invoke(localNavigator, null));
        if (available()) navigator = current; return current;
    }
    public List<?> navigationStack(Object current) {return List.copyOf((List<?>)value(current,"getNavBackStack"));}
    public void observeNavigation(Object current) {
        if(!available() || navigationPreferences==null)return;
        if(homeScheduled.compareAndSet(false,true)) {
            List<?> initialStack=List.copyOf((List<?>)value(current,"getNavBackStack"));
            // Enabling/reloading the plugin from settings must not redirect an active page.
            if(initialStack.size()>1 || !initialStack.isEmpty()
                    && !Set.of("Track","MusicLibrary").contains(initialStack.getLast().getClass().getSimpleName()))return;
            String home=navigationPreferences.initial();
            homePending=true;
            Runnable schedule=()->{
                String target=home;
                if(home.startsWith("playlist:") && nativePlaylistChoices().stream().noneMatch(p->p.id().equals(home.substring(9))))
                    target=NavigationPreferences.TRACK;
                String resolved=target;
                javax.swing.SwingUtilities.invokeLater(()->{
                    homePending=false;
                    if(available() && navigator==current && initialStack.equals(value(current,"getNavBackStack")))
                        openInitialHome(current,resolved);
                });
            };
            if(home.startsWith("playlist:"))homeWorker.submit(schedule);
            else javax.swing.SwingUtilities.invokeLater(schedule);
            return;
        }
        if(homePending)return;
        var stack=(List<?>)value(current,"getNavBackStack");
        if(!stack.isEmpty()) {
            String id=stablePage(stack.getLast());
            if(id!=null)javax.swing.SwingUtilities.invokeLater(()->{
                if(available() && navigator==current && !homePending) {
                    var latest=(List<?>)value(current,"getNavBackStack");
                    if(!latest.isEmpty() && id.equals(stablePage(latest.getLast())))navigationPreferences.remember(id);
                }
            });
        }
    }
    private String stablePage(Object route) {
        var platform=platform(route);if(platform!=null)return platform.name();
        String simple=route.getClass().getSimpleName();
        if("Track".equals(simple))return "Track";
        if("PlaylistContent".equals(simple))return "playlist:"+value(route,"getId");
        return null;
    }
    private void openInitialHome(Object current,String home) {
        try {
            pageBinding.requireInstalled();
            if("BILIBILI".equals(home) || "NETEASE".equals(home) || "QQMUSIC".equals(home) || "APPLEMUSIC".equals(home)) {
                invoke(navigate,current,routes.get(AccountPlatform.valueOf(home)));return;
            }
            Object route;
            if(home.startsWith("playlist:")) {
                String id=home.substring(9);
                route=type(host,"com.xuncorp.voxzen.ui.navigation.ScreenRoute$PlaylistContent").getConstructor(String.class).newInstance(id);
            } else route=type(host,"com.xuncorp.voxzen.ui.navigation.ScreenRoute$Track").getField("INSTANCE").get(null);
            var stack=(List<?>)value(current,"getNavBackStack");
            if(stack.isEmpty() || !route.equals(stack.getLast()))invoke(navigate,current,route);
        } catch(Exception failure) {this.failure="home/"+failure.getClass().getSimpleName();}
    }
    public AccountPlatform platform(Object route) {
        if (route == null || !routeConstructor.getDeclaringClass().isInstance(route)) return null;
        String id = (String)invoke(pluginIdGetter, route);
        for (AccountPlatform p : AccountPlatform.values()) if (id.equals(PLUGIN_ID + "/native/" + p.name())) return p;
        return null;
    }
    public AccountPlatform dispatch(Object route) {
        dispatched.incrementAndGet();
        AccountPlatform platform = available() ? platform(route) : null;
        if (platform != null) selected.incrementAndGet();
        return platform;
    }
    public Object entry(AccountPlatform platform) { return entries.get(platform); }
    public Object pageEntered(AccountPlatform platform) {
        Object lease = new Object();
        synchronized (this) {
            if (!available()) return lease;
            pages.put(lease, platform); rendered.incrementAndGet();
        }
        beginPendingLogin(platform);
        return lease;
    }
    private void beginPendingLogin(AccountPlatform platform) {
        if (!available() || !pendingLogin.compareAndSet(platform, null)) return;
        // Do not hold the UI lock while entering the plugin/controller lifecycle lock.
        try { desk.action(platform, NativeDesk.Action.LOGIN); }
        catch (RuntimeException error) { failure = error.getClass().getSimpleName(); }
    }
    public synchronized void pageLeft(Object lease) { pages.remove(lease); }
    /** Salt routes side buttons, keyboard and its back button through this same void method. */
    boolean internalBack(Object owner) {
        if(!available() || owner==null || owner!=navigator) return false;
        List<?> stack=(List<?>)value(owner,"getNavBackStack");
        AccountPlatform platform=stack.isEmpty()?null:platform(stack.getLast());
        if(platform==null) return false;
        synchronized(this) { if(!pages.containsValue(platform)) return false; }
        // An expanded native player must collapse before navigating the catalog underneath it.
        if(Boolean.TRUE.equals(value(value(owner,"getPlayerSheetState"),"\u0528"))) return false;
        return desk.back(platform);
    }
    public synchronized String diagnostics() {
        String visible = pages.isEmpty() ? "none" : new TreeSet<>(pages.values().stream().map(Enum::name).toList()).toString();
        return "Native UI 0.7.6: bound=" + pageBinding.isInstalled() + ", dispatch=" + dispatched.get()
                + ", platform=" + selected.get() + ", rendered=" + rendered.get() + ", visible=" + visible
                + ", pendingLogin=" + (pendingLogin.get() != null) + ", sidebar=" + (sidebar == null ? sidebarFailure : sidebar.diagnostics())
                + ", sidebarSettings=" + sidebarSettingsFailure
                + ", error=" + (failure.equals("none") ? pageBinding.failure() : failure);
    }
    public void requestLogin(AccountPlatform platform) {
        pendingLogin.set(platform);
        try {
            open(platform);
        }
        catch (RuntimeException error) { pendingLogin.compareAndSet(platform, null); throw error; }
    }
    public void cancelPendingLogin(AccountPlatform platform) {
        if (platform == null) pendingLogin.set(null); else pendingLogin.compareAndSet(platform, null);
    }
    public void open(AccountPlatform platform) {
        if (!available() || navigator == null) throw new IllegalStateException("Re-enter the Salt sidebar after enabling native UI");
        AccountPlatform pending = pendingLogin.get();
        if (pending != platform) pendingLogin.compareAndSet(pending, null);
        SaltPageContentBinding.onUiThread(() -> {
            try {
                if (!available()) return;
                pageBinding.requireInstalled(); desk.leave();
                invoke(navigate, navigator, routes.get(platform));
                boolean visible;
                synchronized (this) { visible = pages.containsValue(platform); }
                if (visible) beginPendingLogin(platform);
            } catch (RuntimeException error) { failure = error.getClass().getSimpleName(); pendingLogin.compareAndSet(platform, null); }
        });
    }
    public void row(String label, boolean selected, Modifier modifier, Object icon, Composer composer) {
        invoke(nativeRow, null, label, selected, null, false, modifier, null, null, icon, composer, 0, 108);
    }
    public void text(String label, Modifier modifier, TextStyle style, Composer composer) {
        text(label,modifier,style,Integer.MAX_VALUE,1,composer);
    }
    public void text(String label, Modifier modifier, TextStyle style, int maxLines, int minLines, Composer composer) {
        invoke(text, null, label, modifier, style, null, 2, true, maxLines, minLines, composer, 0, 0);
    }
    public void canvas(Modifier modifier, Object draw, Composer composer) { invoke(canvas, null, modifier, draw, composer, 0); }
    @Override public synchronized void close() {
        closed = true; navigator = null; sidebarStyle.setValue(null); pendingLogin.set(null); pages.clear();
        homeWorker.shutdownNow();homePending=false;
        if(playlistMenu!=null)playlistMenu.close();
        covers.close(); avatars.close(); pageMemory.clear();
        if (sidebar != null) { sidebar.close(); sidebar = null; }
        if (sidebarSettings != null) {sidebarSettings.close();sidebarSettings=null;}
        pageBinding.close();
        for (int i = handles.size() - 1; i >= 0; i--) {
            try { handles.get(i).close(); }
            catch (RuntimeException ignored) { /* Keep cleaning; Salt also reclaims plugin-owned hooks. */ }
        }
        handles.clear();
    }
}
