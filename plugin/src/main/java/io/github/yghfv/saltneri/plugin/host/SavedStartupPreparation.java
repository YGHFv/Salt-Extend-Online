package io.github.yghfv.saltneri.plugin.host;

import com.xuncorp.spw.workshop.api.hook.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Restore an online selection without decoding its artwork-only FLAC as local audio. */
public final class SavedStartupPreparation implements AutoCloseable {
    public static final String RESTORE = "com.xuncorp.voxzen.service.PlaybackService$initFromDisk$1$1$1$1";
    public record Selection(String id, String path) { }
    public interface Host {
        Selection selection(Object restore) throws ReflectiveOperationException;
        Object player(Object restore) throws ReflectiveOperationException;
    }
    private final Path directory;
    private final Host host;
    private final BooleanSupplier active;
    private final List<HookHandle> hooks = new ArrayList<>();
    // Values contain only host player references/JDK objects, never a plugin class.
    private final ThreadLocal<Deque<Object>> restoring = new ThreadLocal<>();
    private static final Object FOREIGN = new Object();
    private volatile boolean closed;

    public SavedStartupPreparation(Path directory, Host host, Salt1193EngineLink.HookInstaller installer,
                                   BooleanSupplier active) {
        this.directory = directory.toAbsolutePath().normalize();
        this.host = Objects.requireNonNull(host);
        this.active = Objects.requireNonNull(active);
        try {
        hooks.add(installer.install(Salt1193EngineLink.PLAYER, "\u037f", List.of("long"), new MethodHook() {
            @Override public void before(MethodHookParam call) {
                if (closed || !active.getAsBoolean()) return;
                Deque<Object> scopes = restoring.get();
                if (scopes != null && scopes.peek() == call.getThisObject())
                    call.setResult(null); // Unboxed ChannelResult success(null); no Prepare command submitted.
            }
        }));
        hooks.add(installer.install(RESTORE, "invokeSuspend", List.of("java.lang.Object"), new MethodHook() {
            @Override public void before(MethodHookParam call) {
                Object player = FOREIGN;
                try {
                    if (!closed && active.getAsBoolean() && owns(host.selection(call.getThisObject())))
                        player = Objects.requireNonNull(host.player(call.getThisObject()));
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
                Deque<Object> scopes = restoring.get();
                if (scopes == null) { scopes = new ArrayDeque<>(); restoring.set(scopes); }
                scopes.push(player);
                // Run the host's original Select + boxed-result path. Its method Advice discards
                // replacement results, so skipping this coroutine itself would return null.
            }
            @Override public void after(MethodHookParam call) {
                Deque<Object> scopes = restoring.get();
                if (scopes == null) return;
                scopes.pop();
                if (scopes.isEmpty()) restoring.remove();
            }
        }));
        } catch (RuntimeException | LinkageError failure) { close(); throw failure; }
    }

    boolean owns(Selection selection) {
        if (selection == null || selection.id() == null || selection.path() == null
                || !selection.id().matches("saltneri:saved:[a-f0-9]{64}")) return false;
        try {
            Path path = Path.of(selection.path());
            String hash = selection.id().substring("saltneri:saved:".length());
            return path.isAbsolute() && path.equals(path.normalize())
                    && path.equals(directory.resolve(hash + ".flac"));
        } catch (RuntimeException invalid) { return false; }
    }

    public static Host nativeHost(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> restore = Class.forName(RESTORE, false, loader);
        Field label = restore.getDeclaredField("label"), item = restore.getDeclaredField("$it"),
                service = restore.getDeclaredField("this$0");
        label.setAccessible(true); item.setAccessible(true); service.setAccessible(true);
        Class<?> media = Class.forName("com.xuncorp.pisces.PiscesMediaItem", false, loader);
        Method id = media.getMethod("\u037f"), path = media.getMethod("\u052c");
        Method player = Class.forName(Salt1193EngineLink.SERVICE, false, loader).getMethod("getPlayer");
        Class.forName(Salt1193EngineLink.PLAYER, false, loader).getMethod("\u037f", long.class);
        return new Host() {
            public Selection selection(Object call) throws ReflectiveOperationException {
                if (!restore.isInstance(call) || label.getInt(call) != 0) return null;
                Object current = item.get(call);
                return current == null ? null : new Selection((String)id.invoke(current), (String)path.invoke(current));
            }
            public Object player(Object call) throws ReflectiveOperationException {
                return player.invoke(service.get(call));
            }
        };
    }

    public static Path defaultDirectory() {
        String appData = System.getenv("APPDATA");
        Path root = appData == null ? Path.of(System.getProperty("user.home"), "AppData", "Roaming") : Path.of(appData);
        return root.resolve("Salt Player for Windows/workshop/data/io.github.yghfv.saltneri.online/saved-online-tracks");
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        for (var hook : hooks) hook.close();
        hooks.clear();
        restoring.remove();
    }
}
