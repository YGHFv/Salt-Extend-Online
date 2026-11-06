package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.net.URI;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** QR login and one-shot saved-session restore. All work is bounded and account-revision fenced. */
public final class AccountLoginController implements AutoCloseable {
    public enum Phase { IDLE, CREATING, WAITING_SCAN, WAITING_CONFIRMATION, LOGGED_IN, EXPIRED, ERROR, CLOSED }
    public record Snapshot(Phase phase, String status, URI qrContent, String userId, AccountCredentials.Profile profile, String revision) {
        public Snapshot(Phase phase, String status, URI qrContent, String userId) { this(phase, status, qrContent, userId, new AccountCredentials.Profile("", null), ""); }
        @Override public String toString() { return "LoginStatus[" + phase + ", QR and secrets redacted]"; }
    }
    private static final class Operation {
        final QrAuthClient client;
        final AtomicBoolean active = new AtomicBoolean(true);
        volatile RequestScope request;
        volatile Future<?> work;
        volatile ScheduledFuture<?> scheduled;
        volatile QrAuthClient.Session session;
        Operation(QrAuthClient client) { this.client = client; }
        void cancel() {
            active.set(false); if (scheduled != null) scheduled.cancel(false);
            if (request != null) request.close(); if (work != null) work.cancel(true); client.close(); session = null;
        }
    }
    private final AccountPlatform platform;
    private final Supplier<QrAuthClient> clients;
    private final BooleanSupplier authorized;
    private final EncryptedAccountStore store;
    private final Runnable onChange;
    private final Clock clock;
    private final long pollMillis;
    private final ThreadPoolExecutor worker;
    private final ScheduledThreadPoolExecutor timer;
    private final AtomicBoolean deletionQueued = new AtomicBoolean();
    private Operation current;
    private AccountCredentials account;
    private boolean closed, startupAttempted;
    private volatile boolean rememberEnabled;
    private Phase phase = Phase.IDLE;
    private String status = "Not logged in. Login is explicit; no existing account is imported.";
    public AccountLoginController(AccountPlatform platform, BooleanSupplier authorized, EncryptedAccountStore store, Runnable onChange) {
        this(platform, platform == AccountPlatform.BILIBILI ? BiliQrClient::new : NeteaseQrClient::new,
                authorized, store, onChange, Clock.systemUTC(), 3000);
    }
    public AccountLoginController(AccountPlatform platform, Supplier<QrAuthClient> clients, BooleanSupplier authorized,
                                  EncryptedAccountStore store, Runnable onChange, Clock clock, long pollMillis) {
        this.platform = Objects.requireNonNull(platform); this.clients = Objects.requireNonNull(clients); this.authorized = Objects.requireNonNull(authorized);
        this.store = store; this.onChange = Objects.requireNonNull(onChange); this.clock = Objects.requireNonNull(clock);
        if (pollMillis < 10 || pollMillis > 10000) throw new IllegalArgumentException("Invalid login polling interval"); this.pollMillis = pollMillis;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), runnable -> daemon(runnable, "salt-neri-" + platform + "-login"));
        timer = new ScheduledThreadPoolExecutor(1, runnable -> daemon(runnable, "salt-neri-" + platform + "-account-watch"));
        timer.setRemoveOnCancelPolicy(true); timer.scheduleWithFixedDelay(this::watch, 250, 250, TimeUnit.MILLISECONDS);
    }
    private static Thread daemon(Runnable runnable, String name) { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; }
    private boolean permitted() { try { return authorized.getAsBoolean(); } catch (RuntimeException error) { return false; } }
    private synchronized Operation begin() {
        if (closed) throw new IllegalStateException("Login controller closed");
        if (!permitted()) throw new SecurityException("Experimental permission required");
        discardOperation(); retireAccount(); worker.purge();
        current = new Operation(clients.get()); phase = Phase.CREATING;
        status = "Preparing explicit QR login in background. No audio or queue changes."; return current;
    }
    public void start(boolean remember) {
        Operation operation;
        synchronized (this) { rememberEnabled = remember; startupAttempted = true; operation = begin(); }
        changed();
        submit(operation, () -> {
            try (RequestScope request = request(operation)) {
                QrAuthClient.Session session = operation.client.create(request); request.check();
                synchronized (this) {
                    if (!valid(operation)) return; operation.session = session; phase = Phase.WAITING_SCAN;
                    status = "Scan with the official platform app, then confirm there. QR expires locally after 3 minutes.";
                }
                schedule(operation);
            } catch (Exception | LinkageError error) { fail(operation, error); }
        });
    }
    public void restoreSaved() {
        if (store == null) throw new IllegalStateException("Secure storage unavailable");
        startRestore(begin(), false);
    }
    public void restoreOnStartup(boolean remember) {
        Operation operation;
        synchronized (this) {
            rememberEnabled = remember;
            if (!remember || startupAttempted || closed || phase != Phase.IDLE || !permitted() || store == null) return;
            startupAttempted = true; operation = begin();
        }
        // Account-change callbacks acquire other controllers; never run them with the login lock held.
        startRestore(operation, true);
    }
    private void startRestore(Operation operation, boolean automatic) {
        synchronized (this) { if (!valid(operation)) return; status = "Restoring this plugin's encrypted login; verifying account in background."; }
        changed();
        submit(operation, () -> {
            AccountCredentials saved = null;
            try (RequestScope request = request(operation)) {
                if (automatic && !store.hasSaved(platform)) {
                    synchronized (this) { if (valid(operation)) { operation.cancel(); phase = Phase.IDLE;
                        status = "Not logged in. Scan once; login will be encrypted and remembered by default."; } }
                    return;
                }
                saved = store.load(platform); request.check();
                AccountCredentials verified = operation.client.verify(saved, request);
                try { request.check(); publish(operation, verified); } catch (Exception error) { verified.retire(); throw error; }
            } catch (Exception | LinkageError error) { fail(operation, error); }
            finally { if (saved != null) saved.retire(); }
        });
    }
    private void submit(Operation operation, Runnable action) {
        synchronized (this) {
            if (!valid(operation)) return;
            try { operation.work = worker.submit(action); }
            catch (RuntimeException error) { operation.cancel(); phase = Phase.ERROR; status = "Login worker busy or stopped; no credentials accepted."; }
        }
    }
    private RequestScope request(Operation operation) {
        RequestScope request = new RequestScope(() -> operation.active.get() && permitted()); operation.request = request; request.check(); return request;
    }
    private synchronized boolean valid(Operation operation) { return !closed && current == operation && operation.active.get(); }
    private void schedule(Operation operation) {
        synchronized (this) { if (valid(operation)) operation.scheduled = timer.schedule(() -> submit(operation, () -> poll(operation)), pollMillis, TimeUnit.MILLISECONDS); }
    }
    private void poll(Operation operation) {
        try (RequestScope request = request(operation)) {
            QrAuthClient.Session session = operation.session;
            if (session == null || !clock.instant().isBefore(session.expiresAt())) { expire(operation); return; }
            QrAuthClient.Poll result = operation.client.poll(session, request);
            try { request.check(); } catch (Exception error) { if (result.credentials() != null) result.credentials().retire(); throw error; }
            if (result.state() == QrAuthClient.State.CONFIRMED) { publish(operation, result.credentials()); return; }
            if (result.state() == QrAuthClient.State.EXPIRED) { expire(operation); return; }
            synchronized (this) {
                if (!valid(operation)) return;
                phase = result.state() == QrAuthClient.State.WAITING_SCAN ? Phase.WAITING_SCAN : Phase.WAITING_CONFIRMATION;
                status = phase == Phase.WAITING_SCAN ? "Waiting for scan in the official app." : "Scanned. Confirm login in the official app.";
            }
            schedule(operation);
        } catch (Exception | LinkageError error) { fail(operation, error); }
    }
    private void publish(Operation operation, AccountCredentials verified) throws java.io.IOException {
        if (verified == null || verified.platform() != platform || !verified.isActive()) {
            if (verified != null) verified.retire(); throw new java.io.IOException("Login confirmation unverified");
        }
        synchronized (this) {
            if (!valid(operation) || !permitted()) { verified.retire(); return; }
            account = verified; phase = Phase.LOGGED_IN; operation.session = null;
            status = "Account verified. Memory session only; no playback or automatic account import.";
        }
        changed();
        if (rememberEnabled && store != null) {
            try {
                store.save(verified, () -> rememberEnabled && operation.active.get() && verified.isActive() && permitted(), action -> {
                    synchronized (this) {
                        if (!rememberEnabled || !valid(operation) || account != verified || !permitted()) throw new java.io.IOException("Account save cancelled");
                        action.run();
                    }
                });
                synchronized (this) { if (valid(operation)) status = "Account verified and saved with Windows CurrentUser DPAPI. Automatic restore enabled."; }
            } catch (Exception error) {
                synchronized (this) { if (valid(operation)) status = "Account verified in memory; secure save failed. No plaintext fallback."; }
            }
        }
        operation.client.close();
    }
    private synchronized void expire(Operation operation) {
        if (!valid(operation)) return; operation.cancel(); phase = Phase.EXPIRED; status = "QR expired. Create a new QR explicitly; no automatic retries.";
    }
    private synchronized void fail(Operation operation, Throwable error) {
        if (!valid(operation)) return; operation.cancel(); phase = Phase.ERROR;
        status = "Login stopped (" + error.getClass().getSimpleName() + "). No risk-control retry, TLS bypass or secret logged.";
    }
    public synchronized void cancelLogin() {
        if (closed || phase == Phase.LOGGED_IN) return; startupAttempted = true; discardOperation(); phase = Phase.IDLE; status = "Pending login cancelled; QR invalid locally.";
    }
    public void logout() {
        synchronized (this) {
            startupAttempted = true;
            if (closed) return; discardOperation(); retireAccount(); worker.purge(); phase = Phase.IDLE;
            status = "Plugin account cleared; deleting this plugin's saved credential. Other apps are unaffected.";
            if (store != null) {
                try { store.markLoggedOut(platform); }
                catch (Exception failure) { status = "Memory session cleared; persistent sign-out marker failed. Saved storage must be removed manually if deletion fails."; }
            }
        }
        changed();
        if (store == null) return;
        if (deletionQueued.compareAndSet(false, true)) {
            try { worker.execute(() -> {
                try { store.delete(platform); synchronized (this) { if (!closed && phase == Phase.IDLE) status = "Plugin account and its saved credential removed. Other apps/server sessions unchanged."; } }
                catch (Exception error) { synchronized (this) { if (!closed && phase == Phase.IDLE) status = "Memory session cleared; saved credential deletion failed. Do not restore until removed."; } }
                finally { deletionQueued.set(false); }
            }); } catch (RuntimeException error) { deletionQueued.set(false); synchronized (this) { status = "Memory session cleared; saved credential deletion could not be queued."; } }
        }
    }
    public void setRememberEnabled(boolean enabled) {
        Operation operation;
        AccountCredentials verified;
        synchronized (this) {
            if (closed || rememberEnabled == enabled) return;
            rememberEnabled = enabled; operation = current; verified = account;
            if (!enabled && store != null) {
                try { store.markLoggedOut(platform); }
                catch (Exception failure) { status = "Remembered login disabled; sign-out marker failed. Saved-file removal is pending."; }
            }
        }
        if (store == null) return;
        if (!enabled) {
            try {
                worker.execute(() -> { try {
                    synchronized (this) {
                        if (rememberEnabled) return;
                        store.delete(platform);
                        if (account != null) status = "Account kept in memory only; saved login removed.";
                    }
                } catch (Exception ignored) { synchronized (this) { status = "Secure login removal failed; use logout before disabling the plugin."; } } });
            } catch (RuntimeException failure) { synchronized (this) { status = "Remembered login disabled; saved-file removal could not be queued."; } }
        } else if (operation != null && verified != null) {
            submit(operation, () -> { try {
                store.save(verified, () -> rememberEnabled && operation.active.get() && verified.isActive() && permitted(), action -> {
                    synchronized (this) {
                        if (!rememberEnabled || !valid(operation) || account != verified || !permitted()) throw new java.io.IOException("Account save cancelled");
                        action.run();
                    }
                });
            } catch (Exception ignored) { synchronized (this) { if (valid(operation)) status = "Secure save failed; session remains in memory only."; } } });
        }
    }
    private void watch() {
        boolean notify = false;
        synchronized (this) {
            if (closed) return;
            if (!permitted()) {
                boolean wasActive = account != null || (current != null && current.active.get());
                discardOperation(); retireAccount(); phase = Phase.IDLE; status = "Permission revoked; memory session and pending login closed."; notify = wasActive;
            } else if (account != null && !account.isActive()) {
                retireAccount(); phase = Phase.EXPIRED; status = "Account credential expired. Login again explicitly."; notify = true;
            } else if (current != null && current.session != null && !clock.instant().isBefore(current.session.expiresAt())) expire(current);
        }
        if (notify) changed();
    }
    public synchronized AccountCredentials credentials() { return account != null && account.isActive() && permitted() && !closed ? account : null; }
    public Snapshot snapshot() {
        watch(); synchronized (this) { return new Snapshot(phase, status, current != null && current.active.get() && current.session != null ? current.session.qrContent() : null,
                account == null ? "" : account.userId(), account == null ? new AccountCredentials.Profile("", null) : account.profile(), account == null ? "" : account.revision()); }
    }
    private void discardOperation() { if (current != null) current.cancel(); current = null; }
    private void retireAccount() { if (account != null) account.retire(); account = null; }
    private void changed() { try { onChange.run(); } catch (RuntimeException ignored) { } }
    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; discardOperation(); retireAccount(); phase = Phase.CLOSED; status = "Plugin disabled; memory cleared. Remembered login can be verified on the next start."; }
        changed(); timer.shutdownNow(); worker.shutdownNow();
    }
}
