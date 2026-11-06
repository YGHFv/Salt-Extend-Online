package io.github.yghfv.saltneri.plugin;

import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.SpwPlugin;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import io.github.yghfv.saltneri.core.SaltHostBridge;
import io.github.yghfv.saltneri.plugin.host.Salt1193Probe;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Passive host preview: no queue mutation, network requests or native hook installation. */
public final class MainPlugin extends SpwPlugin {
    private static volatile SaltHostBridge bridge;
    private static volatile String status = "Salt Extend Online is disabled.";
    private static volatile Object activeGeneration;
    private ExecutorService probeExecutor;

    public MainPlugin(PluginContext context) {
        super(context);
    }

    @Override
    public void start() {
        bridge = SaltHostBridge.unavailable();
        Object generation = new Object();
        activeGeneration = generation;
        status = "Checking the installed Salt host; online playback is not connected.";
        probeExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "salt-neri-host-probe");
            thread.setDaemon(true);
            return thread;
        });
        probeExecutor.submit(() -> {
            Salt1193Probe.Result result = Salt1193Probe.inspect(WorkshopApi.class.getClassLoader(),
                    getPluginContext().getSpwVersion(), getPluginContext().getSpwChannel().name());
            synchronized (MainPlugin.class) {
                if (activeGeneration == generation) status = result.detail();
            }
        });
        showStatus();
    }

    @Override
    public void stop() {
        synchronized (MainPlugin.class) {
            activeGeneration = null;
            bridge = null;
            status = "Salt Extend Online is disabled.";
        }
        if (probeExecutor != null) probeExecutor.shutdownNow();
    }

    public static void showStatus() {
        WorkshopApi.ui().toast(status, WorkshopApi.Ui.ToastType.Warning);
    }
}
