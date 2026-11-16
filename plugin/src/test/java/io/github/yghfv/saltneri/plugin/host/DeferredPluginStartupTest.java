package io.github.yghfv.saltneri.plugin.host;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;

/** A blocked disk/hook initializer must not hold up startup or the AWT event queue. */
public final class DeferredPluginStartupTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Startup " + checks); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Startup latch timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    public static void main(String[] args) throws Exception {
        Object lifecycle = new Object();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var done = new CountDownLatch(1); var startupReturned = new CountDownLatch(1);
        var startup = new AtomicReference<DeferredPluginStartup>();
        var initialized = new AtomicBoolean(); var onUiThread = new AtomicBoolean(true);
        var problem = new AtomicReference<Throwable>();
        SwingUtilities.invokeLater(() -> {
            synchronized (lifecycle) {
                startup.set(new DeferredPluginStartup(lifecycle, () -> {
                    onUiThread.set(SwingUtilities.isEventDispatchThread()); entered.countDown();
                    await(release); initialized.set(true); done.countDown();
                }, problem::set));
            }
            startupReturned.countDown();
        });
        await(startupReturned); await(entered);
        var uiTick = new CountDownLatch(1); SwingUtilities.invokeLater(uiTick::countDown); await(uiTick);
        check(!onUiThread.get() && !initialized.get());
        var closing = new CountDownLatch(1); var closed = new CountDownLatch(1);
        Thread stopper = new Thread(() -> { closing.countDown(); startup.get().close(); closed.countDown(); });
        stopper.start(); await(closing);
        check(closed.getCount() == 1); // Loader retirement waits for the in-flight initializer.
        release.countDown(); await(done); await(closed); stopper.join(5000);
        check(initialized.get() && problem.get() == null);
        startup.get().close();

        var calls = new AtomicInteger();
        synchronized (lifecycle) {
            try (var pending = new DeferredPluginStartup(lifecycle, calls::incrementAndGet, problem::set)) {
                pending.close(); // Stop before the worker acquires the lifecycle monitor.
            }
        }
        var barrier = new CountDownLatch(1);
        try (var next = new DeferredPluginStartup(lifecycle, barrier::countDown, problem::set)) { await(barrier); }
        check(calls.get() == 0); // No stale initialization can publish after stop/restart.

        var failed = new CountDownLatch(1);
        try (var broken = new DeferredPluginStartup(lifecycle, () -> { throw new NoClassDefFoundError("fixture"); },
                error -> { problem.set(error); failed.countDown(); })) { await(failed); }
        check(problem.get() instanceof NoClassDefFoundError);
        nativeActivation();
        permissionLatency();
        pendingActions();
        supportIsolation();
        realStartup();
        System.out.println("PASS: " + checks + " deferred startup/UI responsiveness and loader retirement checks");
    }
    private static void until(java.util.function.BooleanSupplier done) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!done.getAsBoolean() && System.nanoTime()<end)Thread.sleep(5);
        check(done.getAsBoolean());
    }
    private static void nativeActivation() throws Exception {
        Object lifecycle=new Object();
        var permitted=new AtomicBoolean();var installed=new AtomicBoolean();var installs=new AtomicInteger();
        var notices=new AtomicReference<DeferredNativeActivation.Snapshot>();
        try(var activation=new DeferredNativeActivation(lifecycle,()->true,permitted::get,installed::get,
                ()->{check(permitted.get());installs.incrementAndGet();installed.set(true);},notices::set,100,10)) {
            until(()->activation.snapshot().attempts()>=3);
            check(installs.get()==0 && activation.snapshot().phase()==DeferredNativeActivation.Phase.WAITING_PERMISSION);
            var tick=new CountDownLatch(1);SwingUtilities.invokeLater(tick::countDown);await(tick);
            permitted.set(true);
            until(()->activation.snapshot().phase()==DeferredNativeActivation.Phase.READY);
            check(installed.get() && installs.get()==1 && notices.get().failure()==DeferredNativeActivation.Failure.NONE);
        }
        installs.set(0);installed.set(false);
        try(var activation=new DeferredNativeActivation(lifecycle,()->true,()->true,installed::get,()->{
            if(installs.incrementAndGet()==1)throw new IllegalStateException("Synthetic host registration not ready");
            installed.set(true);
        },s->{},100,10)) {
            until(()->activation.snapshot().phase()==DeferredNativeActivation.Phase.READY);
            check(installs.get()==2);
        }
        installs.set(0);
        try(var activation=new DeferredNativeActivation(lifecycle,()->true,()->true,()->false,()->{
            installs.incrementAndGet();throw new NoClassDefFoundError("Synthetic incompatible UI");
        },s->{},100,10)) {
            until(()->activation.snapshot().phase()==DeferredNativeActivation.Phase.FAILED);
            check(installs.get()==3 && activation.snapshot().failure()==DeferredNativeActivation.Failure.LINKAGE);
        }
        var active=new AtomicBoolean(true);installs.set(0);permitted.set(false);
        try(var activation=new DeferredNativeActivation(lifecycle,active::get,permitted::get,()->false,
                installs::incrementAndGet,s->{},100,10)) {
            until(()->activation.snapshot().phase()==DeferredNativeActivation.Phase.WAITING_PERMISSION);
            synchronized(lifecycle) {active.set(false);permitted.set(true);}
            until(()->activation.snapshot().phase()==DeferredNativeActivation.Phase.CLOSED);
            check(installs.get()==0);
        }
        synchronized(lifecycle) {
            try(var activation=new DeferredNativeActivation(lifecycle,()->true,()->true,()->false,
                    installs::incrementAndGet,s->{},100,10)) {activation.close();}
        }
        check(installs.get()==0);
        try(var activation=new DeferredNativeActivation(lifecycle,()->true,()->false,()->false,
                installs::incrementAndGet,s->{},3,10)) {
            until(()->activation.snapshot().attempts()==3);
            check(activation.snapshot().phase()==DeferredNativeActivation.Phase.WAITING_PERMISSION && installs.get()==0);
        }
    }
    private static void permissionLatency() throws Exception {
        Object lifecycle=new Object();var permission=new AtomicBoolean();var installed=new AtomicBoolean();
        var waiting=new CountDownLatch(1);var ready=new CountDownLatch(1);
        try(var activation=new DeferredNativeActivation(lifecycle,()->true,permission::get,installed::get,
                ()->installed.set(true),state->{
                    if(state.phase()==DeferredNativeActivation.Phase.WAITING_PERMISSION)waiting.countDown();
                    if(state.phase()==DeferredNativeActivation.Phase.READY)ready.countDown();
                })) {
            await(waiting);long began;
            synchronized(lifecycle){began=System.nanoTime();permission.set(true);}
            check(ready.await(750,TimeUnit.MILLISECONDS));
            check(activation.snapshot().attempts()==2);
            System.out.println("Authorization fixture: default retry resumed after "+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)+" ms");
        }
    }
    private static void pendingActions() throws Exception {
        var plugin=new io.github.yghfv.saltneri.plugin.ExperimentalPlugin(new com.xuncorp.spw.workshop.api.PluginContext(
                "io.github.yghfv.saltneri.online","1.0.5","fixture","1.19.3",com.xuncorp.spw.workshop.api.Channel.values()[0]));
        var active=plugin.getClass().getDeclaredField("active");active.setAccessible(true);Object previous=active.get(null);
        var apiField=com.xuncorp.spw.workshop.api.WorkshopApi.Companion.getClass().getDeclaredField("instance");
        apiField.setAccessible(true);Object previousApi=apiField.get(null);
        var toasts=new AtomicInteger();ClassLoader host=plugin.getClass().getClassLoader();
        var ui=java.lang.reflect.Proxy.newProxyInstance(host,new Class<?>[]{com.xuncorp.spw.workshop.api.WorkshopApi.Ui.class},
                (p,m,a)->{if(m.getName().equals("toast"))toasts.incrementAndGet();return null;});
        var api=java.lang.reflect.Proxy.newProxyInstance(host,new Class<?>[]{com.xuncorp.spw.workshop.api.WorkshopApi.class},
                (p,m,a)->m.getName().equals("getUi")?ui:null);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var returned=new CountDownLatch(1);
        Thread installer=new Thread(()->{synchronized(plugin){entered.countDown();await(release);}});
        try {
            apiField.set(null,api);active.set(null,plugin);installer.start();await(entered);
            SwingUtilities.invokeLater(()->{
                try {
                    io.github.yghfv.saltneri.plugin.ExperimentalPlugin.openSidebarSettings();
                    io.github.yghfv.saltneri.plugin.ExperimentalPlugin.openLibrary();
                } finally {returned.countDown();}
            });
            await(returned);check(toasts.get()==2 && release.getCount()==1);
        } finally {release.countDown();installer.join(5000);active.set(null,previous);apiField.set(null,previousApi);}
    }
    private static void supportIsolation() throws Exception {
        var plugin=new io.github.yghfv.saltneri.plugin.ExperimentalPlugin(new com.xuncorp.spw.workshop.api.PluginContext(
                "io.github.yghfv.saltneri.online","1.0.5","fixture","1.19.3",com.xuncorp.spw.workshop.api.Channel.values()[0]));
        Class<?> type=plugin.getClass();
        var stopped=type.getDeclaredField("stopped");stopped.setAccessible(true);stopped.setBoolean(plugin,false);
        var ready=type.getDeclaredField("startupReady");ready.setAccessible(true);
        var active=type.getDeclaredField("active");active.setAccessible(true);Object previous=active.get(null);
        var apiField=com.xuncorp.spw.workshop.api.WorkshopApi.Companion.getClass().getDeclaredField("instance");
        apiField.setAccessible(true);Object previousApi=apiField.get(null);
        var toasts=new AtomicInteger();ClassLoader host=type.getClassLoader();
        var ui=java.lang.reflect.Proxy.newProxyInstance(host,new Class<?>[]{com.xuncorp.spw.workshop.api.WorkshopApi.Ui.class},
                (p,m,a)->{if(m.getName().equals("toast"))toasts.incrementAndGet();return null;});
        var api=java.lang.reflect.Proxy.newProxyInstance(host,new Class<?>[]{com.xuncorp.spw.workshop.api.WorkshopApi.class},
                (p,m,a)->m.getName().equals("getUi")?ui:null);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var publication=new CountDownLatch(1);
        var closed=new CountDownLatch(1);var problem=new AtomicReference<Throwable>();
        DeferredPluginStartup support=null;Thread stopper=null;
        try {
            apiField.set(null,api);active.set(null,plugin);
            support=new DeferredPluginStartup(plugin,()->{try {ready.setBoolean(plugin,true);}catch(IllegalAccessException error){throw new AssertionError(error);}},()->{
                entered.countDown();
                try {release.await();}catch(InterruptedException cancelled) {Thread.currentThread().interrupt();}
                // A stopped initializer must still be able to take the publication lock and retire its result.
                synchronized(plugin) {publication.countDown();}
            },problem::set);
            var supportField=type.getDeclaredField("supportStartup");supportField.setAccessible(true);supportField.set(plugin,support);
            await(entered);
            var returned=new CountDownLatch(1);
            SwingUtilities.invokeLater(()->{io.github.yghfv.saltneri.plugin.ExperimentalPlugin.showStatus();returned.countDown();});
            await(returned);check(toasts.get()==1 && release.getCount()==1);
            stopper=new Thread(()->{try {plugin.stop();}catch(Throwable error){problem.set(error);}finally{closed.countDown();}});
            stopper.start();await(publication);await(closed);stopper.join(5000);
            check(stopped.getBoolean(plugin) && !ready.getBoolean(plugin) && problem.get()==null);
        } finally {
            release.countDown();if(stopper!=null)stopper.join(5000);if(support!=null)support.close();
            active.set(null,previous);apiField.set(null,previousApi);
        }
    }
    private static void realStartup() throws Exception {
        java.nio.file.Path directory=java.nio.file.Files.createTempDirectory("salt-fast-start-");
        var configs=new java.util.concurrent.ConcurrentHashMap<String,com.xuncorp.spw.workshop.api.config.ConfigHelper>();
        var manager=(com.xuncorp.spw.workshop.api.WorkshopApi.Manager)java.lang.reflect.Proxy.newProxyInstance(
                DeferredPluginStartupTest.class.getClassLoader(),new Class<?>[]{com.xuncorp.spw.workshop.api.WorkshopApi.Manager.class},
                (p,m,a)->switch(m.getName()) {
                    case "isPermissionGranted" -> true;
                    case "createConfigManager" -> java.lang.reflect.Proxy.newProxyInstance(
                            DeferredPluginStartupTest.class.getClassLoader(),new Class<?>[]{com.xuncorp.spw.workshop.api.config.ConfigManager.class},
                            (cp,cm,ca)->cm.getName().equals("getConfig")?configs.computeIfAbsent(ca==null?"config.json":(String)ca[0],name->
                                    (com.xuncorp.spw.workshop.api.config.ConfigHelper)java.lang.reflect.Proxy.newProxyInstance(
                                            DeferredPluginStartupTest.class.getClassLoader(),new Class<?>[]{com.xuncorp.spw.workshop.api.config.ConfigHelper.class},
                                            (hp,hm,ha)->switch(hm.getName()) {
                                                case "get" -> ha[1];
                                                case "getConfigPath" -> directory.resolve(name);
                                                case "reload","save" -> true;
                                                default -> null;
                                            })):null);
                    default -> null;
                });
        var sidebar=new CountDownLatch(1);var support=new CountDownLatch(1);var hold=new CountDownLatch(1);
        var closedHooks=new AtomicInteger();var hooks=new com.xuncorp.spw.workshop.api.hook.HookRegistrar() {
            private com.xuncorp.spw.workshop.api.hook.HookHandle handle() {
                return new com.xuncorp.spw.workshop.api.hook.HookHandle() {
                    public void close(){closedHooks.incrementAndGet();}public void unhook(){close();}
                };
            }
            public com.xuncorp.spw.workshop.api.hook.HookHandle hookMethod(String c,String m,java.util.List<String> t,
                    com.xuncorp.spw.workshop.api.hook.MethodHook h) {
                if(c.equals(Salt1193EngineLink.SERVICE) && m.equals("play")) {
                    support.countDown();
                    try {hold.await();}catch(InterruptedException cancelled){Thread.currentThread().interrupt();}
                }
                return handle();
            }
            public com.xuncorp.spw.workshop.api.hook.HookHandle afterComposable(String c,String m,String i,java.util.List<String> t,int priority,
                    com.xuncorp.spw.workshop.api.hook.ComposableHook h) {
                if(c.equals(io.github.yghfv.saltneri.plugin.ui.SaltNativeUi.SIDEBAR))sidebar.countDown();
                return handle();
            }
            public com.xuncorp.spw.workshop.api.hook.HookHandle replaceComposable(String c,String m,String i,java.util.List<String> t,int priority,
                    com.xuncorp.spw.workshop.api.hook.ComposableHook h) {return handle();}
            public <S> com.xuncorp.spw.workshop.api.hook.HookHandle appendContent(String c,String m,String i,String anchor,Class<S> state,
                    java.util.List<String> t,int priority,com.xuncorp.spw.workshop.api.hook.ContentHook<S> h){return handle();}
        };
        var toasts=new AtomicInteger();ClassLoader host=DeferredPluginStartupTest.class.getClassLoader();
        var ui=java.lang.reflect.Proxy.newProxyInstance(host,new Class<?>[]{com.xuncorp.spw.workshop.api.WorkshopApi.Ui.class},
                (p,m,a)->{if(m.getName().equals("toast"))toasts.incrementAndGet();return null;});
        var api=java.lang.reflect.Proxy.newProxyInstance(host,new Class<?>[]{com.xuncorp.spw.workshop.api.WorkshopApi.class},
                (p,m,a)->switch(m.getName()){case "getManager"->manager;case "getHookRegistrar"->hooks;case "getUi"->ui;default->null;});
        var field=com.xuncorp.spw.workshop.api.WorkshopApi.Companion.getClass().getDeclaredField("instance");field.setAccessible(true);
        Object previous=field.get(null);
        var plugin=new io.github.yghfv.saltneri.plugin.ExperimentalPlugin(new com.xuncorp.spw.workshop.api.PluginContext(
                "io.github.yghfv.saltneri.online","1.0.5","fixture","1.19.3",com.xuncorp.spw.workshop.api.Channel.values()[0]));
        var ready=plugin.getClass().getDeclaredField("startupReady");ready.setAccessible(true);
        long began=System.nanoTime();
        try {
            field.set(null,api);
            SwingUtilities.invokeAndWait(plugin::start);
            await(sidebar);await(support); // Real UI is installed before the deliberately blocked playback hook.
            check(ready.getBoolean(plugin) && hold.getCount()==1);
            long millis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);
            var returned=new CountDownLatch(1);
            SwingUtilities.invokeLater(()->{io.github.yghfv.saltneri.plugin.ExperimentalPlugin.showStatus();returned.countDown();});
            await(returned);check(toasts.get()>0 && hold.getCount()==1);
            SwingUtilities.invokeAndWait(plugin::stop);check(closedHooks.get()>0 && !ready.getBoolean(plugin));
            SwingUtilities.invokeAndWait(()->{});
            System.out.println("Startup fixture: real platform hooks ready before blocked playback support ("+millis+" ms, synthetic manager/hooks)");
        } finally {hold.countDown();plugin.stop();field.set(null,previous);}
    }
}
