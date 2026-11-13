package io.github.yghfv.saltneri.plugin.diagnostics;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.auth.AccountLoginController.Phase;
import java.io.*;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** One bounded, background export. Account stores/configuration/raw messages are never serialized. */
public final class DiagnosticExport implements AutoCloseable {
    public enum Flag { STARTUP_READY, STOPPED, HOST_MATCHED, ADAPTER_PRESENT, VIDEO_PRESENT, UI_PRESENT, UI_BOUND, LOGIN_PENDING }
    public enum Counter { UI_DISPATCHED, UI_SELECTED, UI_RENDERED, VIDEO_RESOLUTION, CACHE_MIB }
    public record State(String pluginVersion, String hostVersion, Map<Flag, Boolean> flags,
                        Map<Counter, Long> counters, Map<AccountPlatform, Phase> phases) {
        public State { flags = Map.copyOf(flags); counters = Map.copyOf(counters); phases = Map.copyOf(phases); }
    }
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean closed;
    private Thread worker;
    private final ClassLoader symbols;
    public DiagnosticExport(ClassLoader symbols) { this.symbols = Objects.requireNonNull(symbols); }

    public synchronized boolean start(Path source, Path directory, Supplier<State> state,
                                      Consumer<String> success, Runnable failure) {
        if (closed || !busy.compareAndSet(false, true)) return false;
        worker = new Thread(() -> {
            try {
                SafeDiagnosticLog.interrupted();
                Path result = write(source, directory, state.get(), symbols);
                if (!closed) success.accept(result.getFileName().toString());
            } catch (Exception | LinkageError error) {
                if (!closed) failure.run(); // Never show or log error messages, paths or raw input.
            } finally { busy.set(false); }
        }, "salt-extend-log-export");
        worker.setDaemon(true); worker.start(); return true;
    }
    @Override public synchronized void close() { closed = true; if (worker != null) worker.interrupt(); }

    public static Path write(Path source, Path directory, State state, ClassLoader symbols) throws IOException {
        SafeDiagnosticLog.interrupted(); Files.createDirectories(directory);
        String name = "salt-extend-diagnostics-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
                + "-" + UUID.randomUUID().toString().substring(0, 8) + ".txt";
        Path destination = directory.resolve(name);
        Path temporary = Files.createTempFile(directory, ".salt-diagnostics-", ".tmp");
        boolean committed = false;
        try {
            var filter = new SafeDiagnosticLog(symbols);
            try (Writer output = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                output.write("Salt Extend Online diagnostic log / schema 1\n");
                output.write("Exported UTC: " + Instant.now() + "\n");
                output.write("Privacy: only typed state and verified program symbols; all free text is withheld.\n");
                output.write("Cookie/account placeholders: qqcookie, bilicookie, neteasecookie, applecookie, kugoucookie.\n");
                output.write("Account IDs, nicknames, QR data, tokens, URLs, local paths and original thread names are omitted.\n");
                output.write("Plugin version: " + version(state.pluginVersion) + "\nHost version: " + version(state.hostVersion) + "\n");
                output.write("Java feature: " + Runtime.version().feature() + "\nCPU count: " + Runtime.getRuntime().availableProcessors() + "\n");
                for (Flag flag : Flag.values()) if (state.flags.containsKey(flag)) output.write(flag.name() + "=" + state.flags.get(flag) + "\n");
                for (Counter counter : Counter.values()) if (state.counters.containsKey(counter)) output.write(counter.name() + "=" + Math.max(0, state.counters.get(counter)) + "\n");
                for (AccountPlatform platform : AccountPlatform.values()) {
                    Phase phase = state.phases.get(platform);
                    output.write(platform.name() + " phase=" + (phase == null ? "OFF" : phase.name()) + " credentials=" + marker(platform) + "\n");
                }
                output.write("\nTHREAD SNAPSHOT 1\n"); threads(output, filter);
                try { Thread.sleep(120); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                SafeDiagnosticLog.interrupted();
                output.write("\nTHREAD SNAPSHOT 2\n"); threads(output, filter);
                output.write("\nHOST LOG (latest bounded 4 MiB; arbitrary messages withheld)\n");
                try {
                    SafeDiagnosticLog.Result result = filter.project(source, output);
                    output.write("Projection: lines=" + result.lines() + ", withheld=" + result.withheld() + ", truncated=" + result.truncated() + "\n");
                } catch (InterruptedIOException cancelled) { throw cancelled; }
                catch (IOException | SecurityException unavailable) { output.write("Host log could not be read.\n"); }
                SafeDiagnosticLog.interrupted(); output.write("\nEND OF SAFE DIAGNOSTIC LOG\n");
            }
            SafeDiagnosticLog.interrupted();
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, destination); }
            committed = true; return destination;
        } finally { if (!committed) Files.deleteIfExists(temporary); }
    }
    private static String version(String input) {
        return input != null && input.matches("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}") ? input : "unknown";
    }
    private static String marker(AccountPlatform platform) {
        return switch (platform) { case QQMUSIC -> "qqcookie"; case BILIBILI -> "bilicookie";
            case NETEASE -> "neteasecookie"; case APPLEMUSIC -> "applecookie"; case KUGOU -> "kugoucookie"; };
    }
    private static void threads(Writer output, SafeDiagnosticLog filter) throws IOException {
        SafeDiagnosticLog.interrupted();
        try {
            // Names may contain titles/account identifiers. Use numeric JVM thread IDs for correlation instead.
            ThreadInfo[] all = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true, 64);
            Arrays.sort(all, Comparator.nullsLast(Comparator.comparingLong(ThreadInfo::getThreadId)));
            int count = 0;
            for (ThreadInfo info : all) {
                SafeDiagnosticLog.interrupted(); if (info == null || count++ >= 512) continue;
                String role = info.getThreadName().startsWith("AWT-EventQueue-") ? "AWT_EVENT_QUEUE" : "THREAD";
                output.write(role + " id=" + info.getThreadId() + " state=" + info.getThreadState().name()
                        + " owner=" + info.getLockOwnerId() + " blockedCount=" + info.getBlockedCount() + "\n");
                for (StackTraceElement frame : info.getStackTrace()) output.write("    at " + filter.frame(frame.getClassName(), frame.getMethodName()) + "\n");
                for (MonitorInfo monitor : info.getLockedMonitors()) {
                    // No identity hashes or LockInfo.toString(), just verified owning code location.
                    StackTraceElement frame = monitor.getLockedStackFrame();
                    if (frame != null) output.write("    holds monitor at " + filter.frame(frame.getClassName(), frame.getMethodName()) + "\n");
                }
            }
        } catch (RuntimeException | LinkageError unavailable) { output.write("Thread snapshot unavailable.\n"); }
    }
}
