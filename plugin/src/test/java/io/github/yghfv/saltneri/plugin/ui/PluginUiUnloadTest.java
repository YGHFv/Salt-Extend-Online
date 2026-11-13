package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.ImageComposeScene;
import androidx.compose.ui.unit.DensityKt;
import androidx.compose.ui.unit.LayoutDirection;
import com.xuncorp.spw.workshop.api.hook.*;
import io.github.yghfv.saltneri.plugin.library.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.*;
import javax.swing.SwingUtilities;
import kotlin.Unit;
import kotlinx.coroutines.Dispatchers;
import org.pf4j.*;

/** Real PF4J loader closes before Compose forgets the old sidebar, as during a mod update. */
public final class PluginUiUnloadTest {
    private static final String TYPE = "io.github.yghfv.saltneri.plugin.ui.SaltSidebarToggleContent";
    private static final class Hooks extends HookRegistrar {
        private HookHandle handle() { return new HookHandle() { public void close() {} public void unhook() {} }; }
        public HookHandle hookMethod(String c,String m,List<String> t,MethodHook h) { return handle(); }
        public HookHandle afterComposable(String c,String m,String i,List<String> t,int p,ComposableHook h) { return handle(); }
        public HookHandle replaceComposable(String c,String m,String i,List<String> t,int p,ComposableHook h) { return handle(); }
        public <S> HookHandle appendContent(String c,String m,String i,String a,Class<S> s,List<String> t,int p,ContentHook<S> h) { throw new AssertionError(); }
    }
    public static void main(String[] args) throws Exception {
        ClassLoader host = PluginUiUnloadTest.class.getClassLoader();
        Path root = Path.of(System.getProperty("salt.test.toggleClasses",
                Path.of(SaltNativeUi.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()));
        Path jar = Files.createTempFile("salt-ui-unload-", ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar));
             var files = Files.list(root.resolve("io/github/yghfv/saltneri/plugin/ui"))) {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("SaltSidebarToggleContent")
                    && p.toString().endsWith(".class")).toList()) {
                output.putNextEntry(new JarEntry(root.relativize(file).toString().replace('\\', '/')));
                output.write(Files.readAllBytes(file)); output.closeEntry();
            }
        }
        ClassLoader parent = new ClassLoader(host) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(TYPE)) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        var failure = new AtomicReference<Throwable>();
        try (var loader = new PluginClassLoader(null, new DefaultPluginDescriptor(), parent);
             var player = new OnlinePlaybackController(p -> null, () -> true, () -> false, (a,v) -> { throw new AssertionError(); });
             var library = new LibraryController(p -> null, () -> true, t -> { throw new AssertionError(); }, () -> {});
             var ui = new SaltNativeUi(host, new Hooks(), new NativeDesk(library,player,p -> null,(p,a) -> {},() -> true), () -> true)) {
            loader.addFile(jar.toFile());
            Class<?> type = loader.loadClass(TYPE);
            if (type.getClassLoader() != loader) throw new AssertionError("Fixture bypassed PF4J");
            Object hook = type.getConstructor(SaltNativeUi.class).newInstance(ui);
            Method draw = type.getMethod("Content",UiHookCall.class,Composer.class,int.class);
            UiHookCall call = new UiHookCall() {
                public Object getThisObject() { return null; }
                public List<Object> getArguments() { return List.of(); }
                public Object getArgument(String name) { throw new AssertionError(); }
            };
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var scene = new ImageComposeScene(32,32,DensityKt.Density(1f,1f),LayoutDirection.Ltr,
                            Dispatchers.getUnconfined(), (composer,flags) -> {
                                try { draw.invoke(hook,call,composer,0); }
                                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                                return Unit.INSTANCE;
                            });
                    try {
                        try (var image = scene.render(0L)) { }
                        loader.close(); // The first disposal happens only after the loader loses its URLs.
                    } finally { scene.close(); }
                } catch (Throwable error) { failure.set(error); }
            });
            if (failure.get() != null) throw new AssertionError("Disposal after PF4J unload cancelled Compose", failure.get());
            if (!loader.isClosed()) throw new AssertionError("Loader remained open");
            SwingUtilities.invokeAndWait(() -> {});
        } finally { Files.deleteIfExists(jar); }
        System.out.println("PASS: real PF4J unload followed by Compose sidebar disposal");
    }
}
