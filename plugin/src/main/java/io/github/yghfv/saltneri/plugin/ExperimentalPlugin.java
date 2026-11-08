package io.github.yghfv.saltneri.plugin;

import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.PluginPermission;
import com.xuncorp.spw.workshop.api.SpwPlugin;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import com.xuncorp.spw.workshop.api.config.ConfigManager;
import io.github.yghfv.saltneri.plugin.bilibili.BiliAudioSelector;
import io.github.yghfv.saltneri.plugin.bilibili.BiliResolutionController;
import io.github.yghfv.saltneri.plugin.bilibili.BiliTarget;
import io.github.yghfv.saltneri.plugin.netease.NeteaseClient;
import io.github.yghfv.saltneri.plugin.netease.NeteaseResolutionController;
import io.github.yghfv.saltneri.plugin.netease.NeteaseTarget;
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.auth.AccountLoginController;
import io.github.yghfv.saltneri.plugin.auth.EncryptedAccountStore;
import io.github.yghfv.saltneri.plugin.ui.NativeDesk;
import io.github.yghfv.saltneri.plugin.ui.SaltNativeUi;
import io.github.yghfv.saltneri.plugin.host.Salt1193DiagnosticRuntime;
import io.github.yghfv.saltneri.plugin.host.Salt1193Probe;
import io.github.yghfv.saltneri.plugin.library.*;
import io.github.yghfv.saltneri.plugin.auth.AccountCredentials;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import java.io.IOException;
import java.util.function.Consumer;

/** Separate opt-in artifact. Native UI hooks do not start network requests or playback. */
public final class ExperimentalPlugin extends SpwPlugin {
    private static volatile ExperimentalPlugin active;
    private volatile Salt1193Probe.Result profile;
    private volatile String status = "Experimental plugin disabled.";
    private volatile Salt1193DiagnosticRuntime runtime;
    private volatile boolean stopped = true;
    private volatile Object generation;
    private volatile BiliResolutionController bili;
    private volatile NeteaseResolutionController netease;
    private ConfigManager configManager;
    private Consumer<ConfigHelper> configListener;
    private volatile AccountLoginController biliLogin, neteaseLogin;
    private volatile OnlinePlaybackController online;
    private volatile LibraryController library;
    private volatile SaltNativeUi nativeUi;
    private NativeDesk desk;
    private io.github.yghfv.saltneri.plugin.host.SavedTrackStore savedTracks;
    private SavedPlaylistController savedPlaylists;
    private java.util.concurrent.ScheduledExecutorService savedRestore;
    private io.github.yghfv.saltneri.plugin.host.SavedPlaybackRecovery savedRecovery;
    private io.github.yghfv.saltneri.plugin.host.WindowsMediaIdentity mediaIdentity;
    private volatile String mediaIdentityStatus="not registered";
    private volatile PlaybackSettings settings=PlaybackSettings.DEFAULT;
    private void applySettings(ConfigHelper config) {
        settings=PlaybackSettings.parse(config.get("bili.quality","high"),config.get("netease.quality","standard"),config.get("cache.limitMiB","2048"),config.get("cover.mode","fit"));
        Salt1193DiagnosticRuntime current=runtime; if(current!=null) current.cacheLimit(settings.cacheBytes());
        LibraryController catalog=library; if(catalog!=null) catalog.pageLimit(CatalogPaging.parse(config.get("catalog.pageLimit","30")));
    }

    public ExperimentalPlugin(PluginContext context) { super(context); }

    @Override public synchronized void start() {
        stopped = false;
        active = this;
        Object started = new Object(); generation = started;
        status = "Checking native UI capabilities; no network or queue changes.";
        configManager = WorkshopApi.manager().createConfigManager();
        ConfigHelper initialConfig = configManager.getConfig();
        applySettings(initialConfig);
        if (!initialConfig.get("auth.rememberPolicyV2", false)) {
            // Migrate the old default-off preference once, as requested; later opt-outs are preserved.
            initialConfig.set("auth.remember", true);
            initialConfig.set("auth.rememberPolicyV2", true);
            if (!initialConfig.save()) throw new IllegalStateException("Login preference migration could not be saved");
        }
        EncryptedAccountStore accounts = new EncryptedAccountStore(configManager.getConfig().getConfigPath().getParent());
        biliLogin = new AccountLoginController(AccountPlatform.BILIBILI, this::granted, accounts, () -> accountChanged(AccountPlatform.BILIBILI));
        neteaseLogin = new AccountLoginController(AccountPlatform.NETEASE, this::granted, accounts, () -> accountChanged(AccountPlatform.NETEASE));
        bili = new BiliResolutionController(this::granted, () -> biliLogin == null ? null : biliLogin.credentials());
        netease = new NeteaseResolutionController(this::granted, () -> neteaseLogin == null ? null : neteaseLogin.credentials());
        online = new OnlinePlaybackController(this::credentials, this::granted, this::adapterArmed, new OnlinePlaybackController.Player() {
            public void play(OnlinePlaybackController.Audio audio, java.util.function.BooleanSupplier active) throws Exception {
                submit(audio, active, OnlinePlaybackController.QueueAction.PLAY);
            }
            public void insert(OnlinePlaybackController.Audio audio, java.util.function.BooleanSupplier active, OnlinePlaybackController.QueueAction action) throws Exception {
                submit(audio, active, action);
            }
            private void submit(OnlinePlaybackController.Audio audio, java.util.function.BooleanSupplier activeAccount, OnlinePlaybackController.QueueAction action) throws Exception {
            Salt1193DiagnosticRuntime current = runtime;
            if (current == null || current.isClosed()) throw new IllegalStateException("Arm the adapter first");
            current.submitOnline(audio,activeAccount,action);
            }
        },()->settings);
        OnlinePlaybackController startedOnline = online;
        library = new LibraryController(this::credentials, this::granted, startedOnline::prepare,
                () -> startedOnline.invalidate("\u5217\u8868\u5df2\u5207\u6362\uff0c\u8bf7\u9009\u62e9\u66f2\u76ee\u51c6\u5907\u97f3\u8f68\uff08\u672a\u64ad\u653e\uff09"));
        library.pageLimit(CatalogPaging.parse(initialConfig.get("catalog.pageLimit","30")));
        desk = new NativeDesk(library, online, platform -> login(platform).snapshot(), (platform, operation) -> {
            try { nativeAction(platform, operation); }
            catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException("Native action failed", failure); }
        }, () -> generation == started && granted());
        desk.coverMode(()->settings.coverMode());
        desk.preferences(new io.github.yghfv.saltneri.plugin.ui.CatalogPreferences(configManager.getConfig("catalog-ui.json")));
        try {
            savedTracks=new io.github.yghfv.saltneri.plugin.host.SavedTrackStore(configManager.getConfig().getConfigPath().getParent().resolve("saved-online-tracks"));
            var gateway=new io.github.yghfv.saltneri.plugin.host.SaltPlaylistGateway(WorkshopApi.class.getClassLoader());
            savedPlaylists=new SavedPlaylistController(new SavedPlaylistController.Gateway() {
                public java.util.List<io.github.yghfv.saltneri.plugin.host.SaltPlaylistGateway.Choice> choices() throws Exception {return gateway.choices();}
                public void add(OnlinePlaybackController.Target target,Catalog.Item row,io.github.yghfv.saltneri.plugin.host.SaltPlaylistGateway.Choice choice,java.util.function.BooleanSupplier valid) throws Exception {
                    if(!valid.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                    io.github.yghfv.saltneri.plugin.host.SavedTrackStore.Entry entry;
                    synchronized(ExperimentalPlugin.this) {
                        if(generation!=started || !granted()) throw new java.util.concurrent.CancellationException();
                        entry=savedTracks.save(target,row); arm();
                    }
                    gateway.add(entry,choice,valid);
                }
            },()->generation==started && granted());
            desk.savedPlaylists(savedPlaylists);
        } catch(Exception failure) { status="Native saved playlists unavailable ("+failure.getClass().getSimpleName()+")"; }
        desk.playlistPlayer(list -> startedOnline.playPlaylist(list, () -> {
            try { arm(); } catch(Exception failure) { throw new IllegalStateException("Adapter unavailable"); }
        }, (playlist,valid) -> {
            Salt1193DiagnosticRuntime current=runtime;
            if(current==null) throw new IllegalStateException("Adapter unavailable");
            current.playPlaylist(playlist,valid);
        }));
        BiliResolutionController startedBili = bili;
        NeteaseResolutionController startedNetease = netease;
        configListener = config -> {
            PlaybackSettings previous=settings; applySettings(config);
            boolean remember = config.get("auth.remember", true);
            AccountLoginController a = biliLogin, b = neteaseLogin;
            if (a != null) a.setRememberEnabled(remember);
            if (b != null) b.setRememberEnabled(remember);
            if(!previous.qualityKey().equals(settings.qualityKey())) {
                startedBili.invalidate("Quality changed; prepare again explicitly.");
                startedNetease.invalidate("Quality changed; prepare again explicitly.");
                startedOnline.invalidate("Quality changed; prepare again explicitly.");
            }
        };
        configManager.addConfigChangeListener(configListener);
        profile = Salt1193Probe.inspect(WorkshopApi.class.getClassLoader(),
                getPluginContext().getSpwVersion(), getPluginContext().getSpwChannel().name());
        status = profile.detail();
        if(savedTracks!=null && profile.matched() && granted()) try {
            savedRecovery=new io.github.yghfv.saltneri.plugin.host.SavedPlaybackRecovery(
                    io.github.yghfv.saltneri.plugin.host.SavedPlaybackRecovery.nativeHost(WorkshopApi.class.getClassLoader(),profile),
                    io.github.yghfv.saltneri.plugin.host.Salt1193EngineLink.hooks(WorkshopApi.hookRegistrar()),
                    ()->generation==started && granted(),savedTracks::owns,()->{
                        if(!adapterArmed()) try {arm();} catch(Exception failure) {throw new IllegalStateException("Playback adapter unavailable");}
                    });
        } catch(ReflectiveOperationException | RuntimeException failure) {status="Saved playback recovery unavailable";}
        if (granted()) {
            try {
                mediaIdentity=io.github.yghfv.saltneri.plugin.host.WindowsMediaIdentity.install();
                mediaIdentityStatus=mediaIdentity==null?"not Windows":"Salt Player for Windows (Start Menu registered; restart for shell refresh)";
            }
            catch(RuntimeException | LinkageError failure) { mediaIdentityStatus="registration failed: "+failure.getClass().getSimpleName(); }
            try { ensureNativeUi(); }
            catch (Exception | LinkageError failure) { status = "Native UI unavailable (" + failure.getClass().getSimpleName() + "). No browser fallback."; }
        }
        if(savedTracks!=null && !savedTracks.entries().isEmpty()) {
            savedRestore=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"salt-online-playlist-restore");t.setDaemon(true);return t;});
            var attempts=new java.util.concurrent.atomic.AtomicInteger();
            savedRestore.scheduleWithFixedDelay(()->{
                synchronized(ExperimentalPlugin.this) {
                    if(generation!=started || stopped) return;
                    try { arm(); savedRestore.shutdown(); }
                    catch(Exception | LinkageError failure) {
                        if(attempts.incrementAndGet()>=60) {status="Saved playlist playback adapter unavailable; reopen the plugin page and retry.";savedRestore.shutdown();}
                    }
                }
            },0,1,java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private boolean granted() {
        return !stopped && WorkshopApi.manager().isPermissionGranted(PluginPermission.CLASS_TRANSFORM);
    }
    private void accountChanged(AccountPlatform platform) {
        OnlinePlaybackController player = online; if (player != null) player.accountChanged(platform);
        LibraryController browser = library; if (browser != null) browser.accountChanged(platform);
        Salt1193DiagnosticRuntime currentRuntime = runtime;
        if (currentRuntime != null) currentRuntime.revokePlatform(MediaLease.Platform.valueOf(platform.name()));
        if (platform == AccountPlatform.BILIBILI) {
            BiliResolutionController resolver = bili;
            if (resolver != null) resolver.invalidate("Bilibili account revision changed; old prepared resolution retired. Resolve explicitly again.");
        } else {
            NeteaseResolutionController resolver = netease;
            if (resolver != null) resolver.invalidate("NetEase account revision changed; old prepared resolution retired. Resolve explicitly again.");
        }
    }
    private AccountLoginController login(AccountPlatform platform) { return platform == AccountPlatform.BILIBILI ? biliLogin : neteaseLogin; }
    private AccountCredentials credentials(AccountPlatform platform) { AccountLoginController controller = login(platform); return controller == null ? null : controller.credentials(); }
    private boolean adapterArmed() { Salt1193DiagnosticRuntime current = runtime; return current != null && !current.isClosed(); }
    private synchronized void ensureNativeUi() throws Exception {
        if (stopped || !granted()) throw new SecurityException("Experimental permission required");
        if (nativeUi == null || !nativeUi.available()) {
            if (nativeUi != null) nativeUi.close();
            Object owner = generation;
            nativeUi = new SaltNativeUi(WorkshopApi.class.getClassLoader(), WorkshopApi.hookRegistrar(), desk,
                    () -> generation == owner && granted());
        }
        status = "0.7.6: Salt native UI requested; remembered plugin logins are verified in background. No automatic playback.";
        boolean remember = configManager.getConfig().get("auth.remember", true);
        biliLogin.restoreOnStartup(remember);
        neteaseLogin.restoreOnStartup(remember);
    }
    private synchronized void openListeningDesk() throws Exception {
        ensureNativeUi(); nativeUi.open(AccountPlatform.NETEASE);
        status = "\u5df2\u8bf7\u6c42\u8fdb\u5165\u7f51\u6613\u4e91\uff1b\u9875\u9762\u51fa\u73b0\u540e\u9009\u62e9\u680f\u76ee\u3002\u4e0d\u6253\u5f00\u6d4f\u89c8\u5668\u3002";
    }
    private synchronized void reinstallNativeUi() throws Exception {
        if (nativeUi != null) { nativeUi.close(); nativeUi = null; }
        ensureNativeUi();
    }
    private synchronized void startLogin(AccountPlatform platform) throws Exception {
        ensureNativeUi(); nativeUi.requestLogin(platform);
        status = (platform == AccountPlatform.BILIBILI ? "Bilibili" : "\u7f51\u6613\u4e91")
                + "\uff1a\u5df2\u8bf7\u6c42\u626b\u7801\u9875\uff1b\u9875\u9762\u51fa\u73b0\u540e\u624d\u7533\u8bf7\u4e8c\u7ef4\u7801\u3002";
    }
    private synchronized void nativeAction(AccountPlatform platform, NativeDesk.Action operation) throws Exception {
        if (stopped || !granted()) throw new SecurityException("Experimental permission required");
        switch (operation) {
            case LOGIN -> login(platform).start(configManager.getConfig().get("auth.remember", true));
            case WEB_LOGIN -> login(platform).startWebLogin(configManager.getConfig().get("auth.remember", true));
            case RESTORE -> restoreLogin(platform);
            case LOGOUT -> logout(platform);
            case CANCEL_LOGIN -> login(platform).cancelLogin();
            case SWITCH_ACCOUNT -> { logout(platform); login(platform).start(configManager.getConfig().get("auth.remember", true)); }
            case ARM -> arm();
            case STOP -> disarm();
        }
    }
    private synchronized void restoreLogin(AccountPlatform platform) {
        if (stopped || !granted()) throw new SecurityException("Experimental permission required");
        login(platform).restoreSaved(); status = platform + " explicit secure restore/account check running in background.";
    }
    private synchronized void logout(AccountPlatform platform) {
        if (stopped) throw new IllegalStateException("Plugin disabled");
        if (nativeUi != null) nativeUi.cancelPendingLogin(platform);
        login(platform).logout(); status = platform + " plugin account cleared. Check status for saved-file deletion result.";
    }
    private synchronized void cancelPendingLogins() {
        if (nativeUi != null) nativeUi.cancelPendingLogin(null);
        if (biliLogin != null) biliLogin.cancelLogin(); if (neteaseLogin != null) neteaseLogin.cancelLogin();
        status = "Pending QR logins cancelled; verified memory accounts, if any, remain. Use explicit logout to remove them.";
    }

    private synchronized void arm() throws IOException, ReflectiveOperationException {
        if (stopped) throw new IllegalStateException("Plugin disabled");
        if (profile == null || !profile.matched()) throw new IllegalStateException("Required playback capabilities unavailable");
        if (!granted()) throw new SecurityException("CLASS_TRANSFORM not granted");
        if (runtime == null || runtime.isClosed()) {
            runtime = Salt1193DiagnosticRuntime.open(WorkshopApi.class.getClassLoader(), profile,
                    WorkshopApi.hookRegistrar(), this::granted);
            runtime.settings(()->settings); runtime.cacheLimit(settings.cacheBytes());
        }
        if(savedTracks!=null) runtime.savedTracks(savedTracks,new io.github.yghfv.saltneri.plugin.host.SavedTrackSession.Resolver() {
            public Object revision(AccountPlatform platform) {return java.util.Arrays.asList(credentials(platform),settings.qualityKey());}
            public OnlinePlaybackController.Audio resolve(io.github.yghfv.saltneri.plugin.host.SavedTrackStore.Entry entry,io.github.yghfv.saltneri.plugin.net.RequestScope request) throws Exception {
                AccountCredentials account=credentials(entry.target().platform());
                return OnlinePlaybackController.resolveAudio(account,entry.target(),account==null?"anonymous:saved:"+java.util.UUID.randomUUID():account.accountScope(),request,settings);
            }
            public io.github.yghfv.saltneri.plugin.host.OnlineLyrics.Source lyrics(io.github.yghfv.saltneri.plugin.host.SavedTrackStore.Entry entry) {
                return entry.target().platform()==AccountPlatform.NETEASE?request->new io.github.yghfv.saltneri.plugin.netease.NeteaseLyricsClient(credentials(AccountPlatform.NETEASE)).load(Long.parseLong(entry.target().id()),request):null;
            }
        });
        status = "Experimental adapter armed. No audio enqueued. NetEase lyrics load only for owned playing items.";
    }

    private synchronized void play() throws ReflectiveOperationException {
        if (stopped || runtime == null || runtime.isClosed()) throw new IllegalStateException("Arm the adapter first");
        runtime.playTone();
        status = "2-second local test tone submitted to Salt. This changes playback; audible success must be checked manually.";
    }

    private synchronized void disarm() {
        try {
            if (bili != null) bili.invalidate("Bilibili operation cancelled; no platform audio played.");
            if (netease != null) netease.invalidate("NetEase operation cancelled; no platform audio played.");
            if (online != null) online.invalidate("Experimental playback stopped; prepare again explicitly.");
            if (runtime != null) runtime.close();
        }
        finally { runtime = null; status = "Adapter stopped; host queue left unchanged. Cached audio may briefly drain."; }
    }

    @Override public synchronized void stop() {
        if(savedRecovery!=null) {savedRecovery.close();savedRecovery=null;}
        if(savedRestore!=null) {savedRestore.shutdownNow();savedRestore=null;}
        if(savedPlaylists!=null) {savedPlaylists.close();savedPlaylists=null;}
        if(mediaIdentity!=null) { mediaIdentity.close(); mediaIdentity=null; }
        stopped = true;
        generation = null;
        try { disarm(); }
        finally {
            try {
                if (nativeUi != null) { nativeUi.close(); nativeUi = null; }
                desk = null;
                if (library != null) { library.close(); library = null; }
                if (online != null) { online.close(); online = null; }
                if (savedTracks != null) {savedTracks.close();savedTracks=null;}
                if (biliLogin != null) { biliLogin.close(); biliLogin = null; }
                if (neteaseLogin != null) { neteaseLogin.close(); neteaseLogin = null; }
                if (bili != null) { bili.close(); bili = null; }
                if (netease != null) { netease.close(); netease = null; }
                if (configManager != null && configListener != null) configManager.removeConfigChangeListener(configListener);
            } finally {
                configManager = null; configListener = null;
                if (active == this) active = null;
                profile = null; status = "Experimental plugin disabled.";
            }
        }
    }

    @FunctionalInterface private interface Action { void run(ExperimentalPlugin plugin) throws Exception; }
    private static void action(Action action) { action(action, false); }
    private static void action(Action action, boolean includeResolution) {
        ExperimentalPlugin plugin = active;
        if (plugin == null) { toast("Experimental plugin disabled.", WorkshopApi.Ui.ToastType.Warning); return; }
        try {
            action.run(plugin);
            String detail = plugin.status;
            BiliResolutionController bili = plugin.bili;
            if (includeResolution && bili != null) detail += "\n" + bili.snapshot().status();
            NeteaseResolutionController netease = plugin.netease;
            if (includeResolution && netease != null) detail += "\n" + netease.snapshot().status();
            OnlinePlaybackController online = plugin.online;
            if (includeResolution && online != null) detail += "\n" + online.snapshot().get("status");
            if (includeResolution) {
                AccountLoginController biliAccount = plugin.biliLogin, neteaseAccount = plugin.neteaseLogin;
                if (biliAccount != null) detail += "\nBilibili: " + biliAccount.snapshot().status();
                if (neteaseAccount != null) detail += "\nNetEase: " + neteaseAccount.snapshot().status();
            }
            toast(detail, WorkshopApi.Ui.ToastType.Warning);
        }
        catch (SecurityException denied) { toast("CLASS_TRANSFORM permission is required. The action was stopped.", WorkshopApi.Ui.ToastType.Error); }
        catch (Exception | LinkageError failure) {
            // Exceptions may contain host paths or future platform data; show only a safe category.
            toast("Experimental action failed: " + failure.getClass().getSimpleName() + ". Check host profile and permissions.",
                    WorkshopApi.Ui.ToastType.Error);
        }
    }
    private static void toast(String text, WorkshopApi.Ui.ToastType type) { WorkshopApi.ui().toast(text, type); }
    public static void showStatus() { action(plugin -> {
        Salt1193DiagnosticRuntime runtime = plugin.runtime;
        if (runtime != null && runtime.isClosed()) plugin.status = "Adapter closed, possibly due to permission revocation.";
        SaltNativeUi ui = plugin.nativeUi;
        plugin.status = (ui == null ? "Native UI: not installed" : ui.diagnostics())
                + "\nBilibili=" + (plugin.biliLogin == null ? "OFF" : plugin.biliLogin.snapshot().phase().name())
                + ", NetEase=" + (plugin.neteaseLogin == null ? "OFF" : plugin.neteaseLogin.snapshot().phase().name())
                + ", adapter=" + plugin.adapterArmed()
                + "\nWindows media app: " + plugin.mediaIdentityStatus;
    }); }
    private synchronized void resolveBili() {
        if (stopped || bili == null) throw new IllegalStateException("Plugin disabled");
        if (!granted()) throw new SecurityException("CLASS_TRANSFORM not granted");
        ConfigHelper config = configManager.getConfig();
        bili.start(BiliTarget.parse(config.get("bili.target", "")), BiliAudioSelector.Quality.fromKey(config.get("bili.quality", "high")));
        status = bili.snapshot().status();
    }
    public static void resolveBilibili() { action(ExperimentalPlugin::resolveBili); }
    private synchronized void resolveNeteaseSong() {
        if (stopped || netease == null) throw new IllegalStateException("Plugin disabled");
        if (!granted()) throw new SecurityException("CLASS_TRANSFORM not granted");
        ConfigHelper config = configManager.getConfig();
        netease.start(NeteaseTarget.parse(config.get("netease.target", "")), NeteaseClient.Quality.fromKey(config.get("netease.quality", "standard")));
        status = netease.snapshot().status();
    }
    public static void resolveNetease() { action(ExperimentalPlugin::resolveNeteaseSong); }
    public static void openLibrary() { action(ExperimentalPlugin::openListeningDesk); }
    public static void installNativeUi() { action(ExperimentalPlugin::reinstallNativeUi); }
    public static void prepareBilibili() { action(plugin -> {
        String target = plugin.configManager.getConfig().get("bili.target", ""); BiliTarget.parse(target);
        plugin.online.prepare(new OnlinePlaybackController.Target(AccountPlatform.BILIBILI, target)); plugin.status = "Preparing Bilibili for explicit playback; no queue change.";
    }); }
    public static void prepareNetease() { action(plugin -> {
        String target = plugin.configManager.getConfig().get("netease.target", ""); NeteaseTarget.parse(target);
        plugin.online.prepare(new OnlinePlaybackController.Target(AccountPlatform.NETEASE, target)); plugin.status = "Preparing NetEase standard audio for explicit playback; no queue change.";
    }); }
    public static void playPrepared() { action(plugin -> {
        plugin.online.play((String)plugin.online.snapshot().get("token")); plugin.status = (String)plugin.online.snapshot().get("status");
    }); }
    public static void loginBilibili() { action(plugin -> plugin.startLogin(AccountPlatform.BILIBILI)); }
    public static void loginNetease() { action(plugin -> plugin.startLogin(AccountPlatform.NETEASE)); }
    public static void restoreBilibili() { action(plugin -> plugin.restoreLogin(AccountPlatform.BILIBILI)); }
    public static void restoreNetease() { action(plugin -> plugin.restoreLogin(AccountPlatform.NETEASE)); }
    public static void logoutBilibili() { action(plugin -> plugin.logout(AccountPlatform.BILIBILI)); }
    public static void logoutNetease() { action(plugin -> plugin.logout(AccountPlatform.NETEASE)); }
    public static void cancelLogins() { action(ExperimentalPlugin::cancelPendingLogins); }
    public static void armAdapter() { action(ExperimentalPlugin::arm); }
    public static void playTestTone() { action(ExperimentalPlugin::play); }
    public static void stopAdapter() { action(ExperimentalPlugin::disarm); }
    public static void clearCache() { action(plugin -> {
        Salt1193DiagnosticRuntime current=plugin.runtime;
        if(current!=null) current.clearCache();
        plugin.status="\u5df2\u6e05\u7406\u672c\u63d2\u4ef6\u97f3\u9891\u7f13\u5b58\uff0c\u4e0d\u6e05\u7a7a\u64ad\u653e\u961f\u5217\u6216\u767b\u5f55\u3002";
    }); }
}
