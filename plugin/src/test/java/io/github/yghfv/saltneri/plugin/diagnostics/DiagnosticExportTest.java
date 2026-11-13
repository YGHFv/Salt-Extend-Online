package io.github.yghfv.saltneri.plugin.diagnostics;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.auth.AccountLoginController;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;

/** File contents, failure cleanup, concurrency and UI responsiveness with synthetic data only. */
public final class DiagnosticExportTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Export " + checks); }
    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Export timed out");
    }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("export-test-");
        String secret = "ACCOUNT_FIXTURE_ec1297";
        var state = new DiagnosticExport.State("1.0.2", secret,
                Map.of(DiagnosticExport.Flag.STARTUP_READY,false,DiagnosticExport.Flag.UI_BOUND,true),
                Map.of(DiagnosticExport.Counter.UI_RENDERED,17L),
                Map.of(AccountPlatform.QQMUSIC,AccountLoginController.Phase.LOGGED_IN));
        ClassLoader symbols = DiagnosticExportTest.class.getClassLoader();
        String originalThreadName=Thread.currentThread().getName();
        Thread.currentThread().setName(secret);
        try {
            Path source = directory.resolve(secret + ".log"), exports = directory.resolve("exports");
            String raw = "Cookie: qqmusic_key=" + secret + "; uin=567891234\n"
                    + "java.lang.NoClassDefFoundError: io.github.yghfv.saltneri.plugin.ui.SaltSidebarToggleContent$Content$2$1$1$1\n"
                    + "    at androidx.compose.runtime.DisposableEffectImpl.onForgotten(Effects.kt:1)\n"
                    + "    at org.pf4j.PluginClassLoader.loadClass(" + secret + ":1)\n"
                    + "{\"account\":{\"userId\":567891234,\"nickname\":\"" + secret + "\"}}\n";
            Files.writeString(source,raw);
            var workerEntered = new CountDownLatch(1); var releaseWorker = new CountDownLatch(1);
            var done = new CountDownLatch(1); var filename = new AtomicReference<String>(); var failed = new AtomicBoolean();
            var onUi = new AtomicBoolean(true);
            try (var exporter = new DiagnosticExport(symbols)) {
                SwingUtilities.invokeAndWait(() -> check(exporter.start(source,exports,()->{
                    onUi.set(SwingUtilities.isEventDispatchThread()); workerEntered.countDown();
                    try { await(releaseWorker); } catch (InterruptedException error) { throw new CancellationException(); }
                    return state;
                },name->{filename.set(name);done.countDown();},()->{failed.set(true);done.countDown();})));
                await(workerEntered);
                var tick = new CountDownLatch(1); SwingUtilities.invokeLater(tick::countDown); await(tick);
                check(!onUi.get());
                check(!exporter.start(source,exports,()->state,name->{},()->{})); // No duplicate worker/file.
                releaseWorker.countDown(); await(done);
                check(!failed.get() && filename.get().matches("salt-extend-diagnostics-[0-9]{8}-[0-9]{6}-[a-f0-9]{8}\\.txt"));
            }
            Path file = exports.resolve(filename.get()); String output = Files.readString(file);
            check(!output.contains(secret) && !output.contains("567891234") && !output.contains(directory.toString()));
            check(output.contains("QQMUSIC phase=LOGGED_IN credentials=qqcookie"));
            check(output.contains("Host version: unknown") && output.contains("STARTUP_READY=false") && output.contains("UI_RENDERED=17"));
            check(output.contains("java.lang.NoClassDefFoundError") && output.contains("DisposableEffectImpl.onForgotten") && output.contains("PluginClassLoader.loadClass"));
            check(output.contains("THREAD SNAPSHOT 1") && output.contains("THREAD SNAPSHOT 2") && output.contains("AWT_EVENT_QUEUE"));
            check(output.contains("END OF SAFE DIAGNOSTIC LOG") && Files.readString(source).equals(raw));
            try(var files=Files.list(exports)) { check(files.count()==1); }

            Path missing = DiagnosticExport.write(directory.resolve("missing"),exports,state,symbols);
            check(Files.readString(missing).contains("Host log unavailable."));
            Path occupied = directory.resolve("occupied"); Files.writeString(occupied,"untouched");
            var failedDone = new CountDownLatch(1);
            try(var exporter = new DiagnosticExport(symbols)) {
                check(exporter.start(source,occupied,()->state,name->{throw new AssertionError("Unexpected success");},failedDone::countDown));
                await(failedDone);
            }
            check(Files.readString(occupied).equals("untouched"));
            var entered = new CountDownLatch(1); var cancelled = new CountDownLatch(1); var callback = new AtomicBoolean();
            var exporter = new DiagnosticExport(symbols);
            check(exporter.start(source,exports,()-> {
                entered.countDown(); try { new CountDownLatch(1).await(); } catch(InterruptedException error) { cancelled.countDown(); throw new CancellationException(); }
                return state;
            },name->callback.set(true),()->callback.set(true)));
            await(entered); exporter.close(); await(cancelled);
            check(!exporter.start(source,exports,()->state,name->{},()->{}) && !callback.get());
            Thread.currentThread().interrupt();
            try { DiagnosticExport.write(source,exports,state,symbols); throw new AssertionError("Cancellation ignored"); }
            catch(java.io.InterruptedIOException expected) { check(true); } finally { Thread.interrupted(); }
            try(var files=Files.list(exports)) { check(files.count()==2); }

            // Interrupt after opening the temporary file, while reading trusted symbol metadata.
            var symbolEntered = new CountDownLatch(1);
            ClassLoader delayed = new ClassLoader(symbols) {
                @Override public java.io.InputStream getResourceAsStream(String name) {
                    symbolEntered.countDown();
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    return null;
                }
            };
            var writeFailure = new AtomicReference<Throwable>();
            Thread writing = new Thread(()->{
                try { DiagnosticExport.write(source,exports,state,delayed); }
                catch(Throwable error) { writeFailure.set(error); }
            }, secret);
            writing.start(); await(symbolEntered);
            try(var files=Files.list(exports)) {
                List<Path> partial=files.filter(p->p.getFileName().toString().endsWith(".tmp")).toList();
                check(partial.size()==1 && !Files.readString(partial.getFirst()).contains(secret));
            }
            writing.interrupt(); writing.join(10000);
            check(!writing.isAlive() && writeFailure.get() instanceof java.io.InterruptedIOException);
            try(var files=Files.list(exports)) { check(files.count()==2); }
        } finally {
            Thread.currentThread().setName(originalThreadName);
            try(var paths=Files.walk(directory)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
        System.out.println("PASS: " + checks + " safe file export, real host symbols, AWT responsiveness and lifecycle checks");
    }
}
