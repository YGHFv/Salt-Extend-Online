package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.*;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.graphics.painter.Painter;
import com.xuncorp.spw.workshop.api.hook.*;
import java.lang.reflect.*;
import java.util.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function3;
import kotlinx.coroutines.flow.Flow;

/** Optional caption integration. Resources and navigation objects belong to the installed host. */
public final class SaltSidebarBindings implements AutoCloseable {
    public static final String CAPTION = "com.xuncorp.voxzen.ui.component.\u0529";
    public static final String TOGGLE = "androidx.compose.ui.\u0476";
    public static final List<String> PARAMETERS = List.of("kotlin.jvm.functions.Function0", "java.lang.String",
            "androidx.compose.ui.graphics.painter.Painter", "androidx.compose.ui.Modifier", "boolean", "java.lang.String",
            "float", "long", "androidx.compose.foundation.layout.PaddingValues", "androidx.compose.ui.Modifier");
    public record Entry(Object route, String label, Object drawable, Object vector) { }
    private final Method caption, iconCaption, stateLocal, expanded, style, navigate, backStack, dismiss;
    private final Method painter, vectorPainter, string, playlistFlow, playlistId, playlistTitle;
    private final Class<?> repository;
    private final Constructor<?> playlistRoute;
    private final List<Entry> entries;
    private final java.util.function.Supplier<Flow<List<Object>>> playlistSource;
    private final MutableState<Boolean> enabled = SnapshotStateKt.mutableStateOf(false, SnapshotStateKt.structuralEqualityPolicy());
    private HookHandle handle;
    private boolean closed;

    public SaltSidebarBindings(ClassLoader host) throws ReflectiveOperationException {
        this(host, null);
    }
    SaltSidebarBindings(ClassLoader host, java.util.function.Supplier<Flow<List<Object>>> playlistSource) throws ReflectiveOperationException {
        this.playlistSource = playlistSource;
        Class<?> c = type(host, CAPTION);
        caption = c.getMethod("\u037f", kotlin.jvm.functions.Function0.class, String.class, Painter.class, Modifier.class,
                boolean.class, String.class, float.class, long.class, androidx.compose.foundation.layout.PaddingValues.class,
                Modifier.class, Composer.class, int.class, int.class);
        iconCaption = c.getMethod("\u037f", Function0.class, String.class, Modifier.class, boolean.class,
                String.class, float.class, Function3.class, Composer.class, int.class, int.class);
        stateLocal = type(host, "androidx.compose.ui.\u10eb").getMethod("\u037f");
        Class<?> state = type(host, "androidx.compose.ui.\u09b8");
        expanded = state.getMethod("\u037f");
        dismiss = state.getMethod("\u052e");
        style = type(host, "androidx.compose.ui.\u04cc").getMethod("\u037f", Composer.class);
        Class<?> nav = type(host, "com.xuncorp.voxzen.ui.navigation.Navigator");
        navigate = nav.getMethod("navigate", type(host, "com.xuncorp.voxzen.ui.navigation.ScreenRoute"));
        backStack = nav.getMethod("getNavBackStack");
        painter = type(host, "org.jetbrains.compose.resources.ImageResourcesKt").getMethod("painterResource",
                type(host, "org.jetbrains.compose.resources.DrawableResource"), Composer.class, int.class);
        vectorPainter = type(host, "androidx.compose.ui.graphics.vector.VectorPainterKt").getMethod("rememberVectorPainter",
                type(host, "androidx.compose.ui.graphics.vector.ImageVector"), Composer.class, int.class);
        string = type(host, "org.jetbrains.compose.resources.StringResourcesKt").getMethod("stringResource",
                type(host, "org.jetbrains.compose.resources.StringResource"), Composer.class, int.class);
        // Preserve the native Windows sidebar order, including create/import playlist actions.
        List<?> nativeItems = (List<?>) type(host, "androidx.compose.ui.\u05d0").getMethod("\u052a").invoke(null);
        Map<String, String[]> resources = Map.ofEntries(
                Map.entry("Track", new String[]{"\u0c0a", "\u0783", "\u0d90", "\u052d"}),
                Map.entry("Genre", new String[]{"\u01f1", "\u0793", "\u0d8f", "\u0861"}),
                Map.entry("Album", new String[]{"\u01f1", "\u052d", "\u0479", "\u0788"}),
                Map.entry("Artist", new String[]{"\u01f1", "\u052f", "\u0479", "\u0798"}),
                Map.entry("Folder", new String[]{"\u01f1", "\u079f", "\u0d8f", "\u0797"}),
                Map.entry("MusicLibrary", new String[]{"\u01f1", "\u079a", "\u02a3", "\u087f"}),
                Map.entry("SoundEffects", new String[]{"\u01f1", "\u0588", "\u0479", "\u0871"}),
                Map.entry("Settings", new String[]{"\u0c0a", "\u052d", "\u0b8a", "\u0885"}),
                Map.entry("NewPlaylist", new String[]{"\u01f1", "\u07a0", "\u1023", "\u052b"}),
                Map.entry("ImportPlaylist", new String[]{"", "", "\u02a3", "\u052b"}));
        List<Entry> values = new ArrayList<>();
        Class<?> item = type(host, SaltNativeUi.ROUTE_ITEM);
        for (Object value : nativeItems) {
            if (!item.isInstance(value)) continue;
            Object route = item.getMethod("\u0528").invoke(value);
            String[] spec = resources.get(route.getClass().getSimpleName());
            if (spec == null) throw new NoSuchMethodException("Unsupported sidebar route");
            Object drawable = spec[0].isEmpty() ? null : resource(host, spec[0], spec[1], "\u037f");
            Object vector = drawable != null ? null : type(host, "androidx.compose.ui.\u04e0").getMethod("\u037f",
                    type(host, "androidx.compose.ui.\u0146")).invoke(null, type(host, "androidx.compose.ui.\u0146").getField("\u037f").get(null));
            // Keep StringResource separate from labels so changing host language recomposes labels.
            labels.put(route, resource(host, spec[2], spec[3], "\u0529"));
            values.add(new Entry(route, "", drawable, vector));
        }
        if (values.size() != 10) throw new NoSuchMethodException("Unsupported sidebar entries");
        entries = List.copyOf(values);
        repository = type(host, "androidx.compose.ui.\u0b9a");
        playlistFlow = repository.getMethod("\u037f");
        Class<?> playlist = type(host, "com.xuncorp.voxzen.data.entity.Playlist");
        playlistId = playlist.getMethod("getId"); playlistTitle = playlist.getMethod("getTitle");
        playlistRoute = type(host, "com.xuncorp.voxzen.ui.navigation.ScreenRoute$PlaylistContent").getConstructor(String.class);
    }
    private final Map<Object, Object> labels = new HashMap<>();
    private static Class<?> type(ClassLoader host, String name) throws ClassNotFoundException { return Class.forName(name, false, host); }
    private static Object resource(ClassLoader host, String owner, String getter, String kind) throws ReflectiveOperationException {
        Class<?> companion = type(host, "androidx.compose.ui.\u0c18$" + kind);
        return type(host, "androidx.compose.ui." + owner).getMethod(getter, companion).invoke(null, companion.getField("\u037f").get(null));
    }
    private static Object invoke(Method m, Object receiver, Object... args) {
        try { return m.invoke(receiver, args); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error r) throw r;
            throw new IllegalStateException("Native sidebar invocation failed", e.getCause());
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Native sidebar binding failed", e); }
    }
    public synchronized void install(HookRegistrar hooks, SaltNativeUi ui) throws ReflectiveOperationException {
        if (closed || handle != null) return;
        ComposableHook content = (ComposableHook)Class.forName("io.github.yghfv.saltneri.plugin.ui.SaltSidebarContent")
                .getConstructor(SaltSidebarBindings.class, SaltNativeUi.class).newInstance(this, ui);
        handle = hooks.replaceComposable(CAPTION, "\u037f", TOGGLE, PARAMETERS, 50, content);
    }
    public synchronized void enabled(boolean value) { if (!closed) enabled.setValue(value); }
    public boolean enabled() { return enabled.getValue(); }
    public Object state(Composer composer) { return composer.consume((CompositionLocal<?>)invoke(stateLocal, null)); }
    public boolean expanded(Object state) { return (boolean)invoke(expanded, state); }
    public Object style(Composer composer) { return invoke(style, null, composer); }
    public List<Entry> entries() { return entries; }
    public String label(Entry entry, Composer composer) {
        Object label = labels.get(entry.route());
        return label == null ? entry.label() : (String)invoke(string, null, label, composer, 0);
    }
    public Painter painter(Entry entry, Composer composer) {
        return (Painter)(entry.drawable() != null ? invoke(painter, null, entry.drawable(), composer, 0)
                : invoke(vectorPainter, null, entry.vector(), composer, 0));
    }
    public Object selected(Object navigator) { List<?> stack = (List<?>)invoke(backStack, navigator); return stack.isEmpty() ? null : stack.getLast(); }
    public void open(Object navigator, Object state, Entry entry) {
        invoke(navigate, navigator, entry.route());
        if (!entry.route().getClass().getSimpleName().equals("NewPlaylist")) invoke(dismiss, state);
    }
    @SuppressWarnings("unchecked")
    public Flow<List<Object>> playlists() {
        if (playlistSource != null) return playlistSource.get();
        try {
            Object companion = repository.getField("\u037f").get(null);
            Object repo = companion.getClass().getMethod("\u037f").invoke(companion);
            return (Flow<List<Object>>)invoke(playlistFlow, repo);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Native playlist sidebar unavailable", e); }
    }
    public List<Entry> playlistEntries(List<?> values) {
        List<Entry> result = new ArrayList<>();
        for (Object value : values) {
            try { result.add(new Entry(playlistRoute.newInstance(invoke(playlistId, value)), (String)invoke(playlistTitle, value), entries.getFirst().drawable(), null)); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("Invalid native playlist route", e); }
        }
        return List.copyOf(result);
    }
    public void original(UiHookCall call, Composer composer) {
        Object[] args = Arrays.copyOf(call.getArguments().toArray(), 13);
        args[10] = composer; args[11] = 0;
        // Original SidebarCaptionButton omits enabled/tooltip/width/padding/icon modifier.
        args[12] = 880;
        invoke(caption, null, args);
    }
    public void button(String label, Modifier modifier, Function0<Unit> action, Object icon, Composer composer) {
        invoke(iconCaption, null, action, label, modifier, true, null, 40f, icon, composer, 0, 16);
    }
    @Override public synchronized void close() {
        if (closed) return;
        enabled.setValue(false); closed = true;
        if (handle != null) { handle.close(); handle = null; }
    }
}
