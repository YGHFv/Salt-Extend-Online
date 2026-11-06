package io.github.yghfv.saltneri.plugin.host;

import com.xuncorp.spw.workshop.api.hook.HookHandle;
import com.xuncorp.spw.workshop.api.hook.MethodHook;
import com.xuncorp.spw.workshop.api.hook.MethodHookParam;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Records actual ordinary-method hook contracts without installing hooks or constructing native audio. */
public final class EngineLinkContractTest {
    private static int checks;
    public static void main(String[] args) throws Throwable {
        ClassLoader loader = EngineLinkContractTest.class.getClassLoader();
        Salt1193Probe.Result profile = Salt1193Probe.inspect(loader, "1.19.3", "Steam");
        require(profile.matched(), profile.detail());
        Salt1193EngineLink.nativeHost(loader, profile); // Resolving fields/methods must not read controller service.
        checks++;
        AtomicBoolean grant = new AtomicBoolean(true);
        FakeBinding binding = new FakeBinding();
        FakeHost host = new FakeHost();
        Hooks hooks = new Hooks();
        Salt1193EngineLink link = new Salt1193EngineLink(profile, binding, host, grant::get);
        require(host.reads == 0, "Constructor initialized host");
        link.connect(hooks);
        require(hooks.installs == 1 && binding.attaches == 1, "Initial attach missing");
        require(binding.player == host.player, "Wrong native player selected");
        link.connect(hooks);
        require(hooks.installs == 1 && binding.attaches == 2, "Connect duplicated hook or skipped recheck");
        hooks.callback.after(new Call(new Object(), new Object(), null));
        require(binding.attaches == 2, "Foreign service intercepted");
        hooks.callback.after(new Call(host.service, new Object(), new IllegalStateException("Original failed")));
        require(binding.attaches == 2, "Failed original callback intercepted");
        host.player = new Object();
        hooks.callback.after(new Call(host.service, host.player, null));
        require(binding.player == host.player && binding.attaches == 3, "Selected callback didn't attach supplied player");
        link.ensureAttached();
        require(binding.attaches == 4, "Explicit enqueue check did not re-resolve player");
        link.close(); link.close();
        require(hooks.unhooks == 1 && binding.closes == 1, "Cleanup not idempotent");
        hooks.callback.after(new Call(host.service, new Object(), null));
        require(binding.attaches == 4, "Stale callback resurrected link");
        fails(IllegalStateException.class, link::ensureAttached);

        Hooks deniedHooks = new Hooks();
        FakeHost deniedHost = new FakeHost();
        FakeBinding deniedBinding = new FakeBinding();
        Salt1193EngineLink denied = new Salt1193EngineLink(profile, deniedBinding, deniedHost, () -> false);
        fails(SecurityException.class, () -> denied.connect(deniedHooks));
        require(deniedHooks.installs == 0 && deniedHost.reads == 0, "Denied action had host side effects");
        require(deniedBinding.closes == 1, "Denied connect didn't fail closed");

        FakeBinding failedBinding = new FakeBinding();
        failedBinding.fail = true;
        Hooks failedHooks = new Hooks();
        Salt1193EngineLink failed = new Salt1193EngineLink(profile, failedBinding, new FakeHost(), () -> true);
        fails(IllegalAccessException.class, () -> failed.connect(failedHooks));
        require(failedHooks.unhooks == 1 && failedBinding.closes == 1, "Partial connect leaked hook or binding");

        FakeHost changedHost = new FakeHost();
        FakeBinding changedBinding = new FakeBinding();
        Hooks changedHooks = new Hooks();
        Salt1193EngineLink changed = new Salt1193EngineLink(profile, changedBinding, changedHost, () -> true);
        changed.connect(changedHooks);
        changedHost.service = new Object();
        fails(IllegalStateException.class, changed::ensureAttached);
        require(changed.isClosed() && changedHooks.unhooks == 1 && changedBinding.closes == 1, "Identity change did not fail closed");

        FakeBinding revokedBinding = new FakeBinding();
        Hooks revokedHooks = new Hooks();
        Salt1193EngineLink revoked = new Salt1193EngineLink(profile, revokedBinding, new FakeHost(), grant::get);
        revoked.connect(revokedHooks);
        grant.set(false);
        fails(SecurityException.class, revoked::ensureAttached);
        require(revoked.isClosed() && revokedHooks.unhooks == 1, "Permission revoke left hook installed");

        FakeBinding callbackBinding = new FakeBinding();
        FakeHost callbackHost = new FakeHost();
        Hooks callbackHooks = new Hooks();
        Salt1193EngineLink callbackFailure = new Salt1193EngineLink(profile, callbackBinding, callbackHost, () -> true);
        callbackFailure.connect(callbackHooks);
        callbackBinding.fail = true;
        callbackHooks.callback.after(new Call(callbackHost.service, callbackHost.player, null));
        require(callbackFailure.isClosed() && callbackBinding.closes == 1, "Hook failure left native adapter active");
        System.out.println("PASS: " + checks + " engine-link checks (real hook API; simulated service/engine, no native initialization)");
    }

    public static final class FakeHost implements Salt1193EngineLink.HostAccess {
        Object service = new Object();
        Object player = new Object();
        int reads;
        @Override public Object service() { reads++; return service; }
        @Override public Object player(Object owner) { if (owner != service) throw new AssertionError(); return player; }
    }
    public static final class FakeBinding implements StreamBinding {
        Object player;
        int attaches;
        int closes;
        boolean fail;
        boolean installed;
        @Override public void register(String path, URI uri) { }
        @Override public void unregister(String path) { }
        @Override public ReversibleInterceptorLists attach(Object engine) throws IllegalAccessException {
            if (fail) throw new IllegalAccessException("Simulated final field write rejected");
            attaches++; player = engine; installed = true;
            return null;
        }
        @Override public boolean isAttached() { return installed; }
        @Override public void close() { closes++; installed = false; }
    }
    public static final class Hooks implements Salt1193EngineLink.HookInstaller {
        public MethodHook callback;
        public int installs;
        public int unhooks;
        @Override public HookHandle install(String type, String method, List<String> parameters, MethodHook hook) {
            require(type.equals(Salt1193EngineLink.SERVICE), "Wrong hook class");
            require(method.equals("onPlayerSet") && parameters.equals(List.of(Salt1193EngineLink.PLAYER)), "Wrong hook ABI");
            installs++; callback = hook;
            return new HookHandle() {
                boolean closed;
                @Override public void unhook() { if (!closed) { closed = true; unhooks++; } }
            };
        }
    }
    public static final class Call extends MethodHookParam {
        private final Object owner;
        private final Object player;
        private final Throwable throwable;
        public Call(Object owner, Object player, Throwable throwable) { this.owner = owner; this.player = player; this.throwable = throwable; }
        @Override public Method getMethod() { return null; }
        @Override public Object getThisObject() { return owner; }
        @Override public Object[] getArgs() { return new Object[]{player}; }
        @Override public Object getResult() { return null; }
        @Override public Throwable getThrowable() { return throwable; }
        @Override public boolean isOriginalSkipped() { return false; }
        @Override public void setResult(Object result) { throw new AssertionError("Hook changed original result"); }
        @Override public void setThrowable(Throwable value) { throw new AssertionError("Hook changed original error"); }
        @Override public Object invokeOriginal() { throw new AssertionError("Hook repeated original method"); }
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void fails(Class<? extends Throwable> expected, Action action) {
        try { action.run(); }
        catch (Throwable failure) { if (expected.isInstance(failure)) { checks++; return; } throw new AssertionError(failure); }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
