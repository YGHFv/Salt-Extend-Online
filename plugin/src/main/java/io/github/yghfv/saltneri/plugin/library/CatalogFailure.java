package io.github.yghfv.saltneri.plugin.library;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Objects;
import javax.net.ssl.SSLException;

/** Only allowlisted stages and numeric codes cross into the UI; never retain upstream messages. */
public final class CatalogFailure extends IOException {
    public enum Stage { SESSION, BILI_VERIFY, NET_VERIFY, NET_CSRF, BILI_CREATED, BILI_SAVED, BILI_CONTENTS, BILI_COLLECTION, BILI_SEARCH,
        NET_DISCOVER, NET_PLAYLISTS, NET_CONTENTS, NET_SONGS, UNKNOWN }
    public enum Reason { HTTP, API, RESPONSE, AUTH, LOGIN_REQUIRED, DNS, TLS, TIMEOUT, NETWORK, POLICY, CSRF }
    private final Stage stage;
    private final Reason reason;
    private final Long code;

    private CatalogFailure(Stage stage, Reason reason, Long code) {
        super(Objects.requireNonNull(stage).name() + "/" + Objects.requireNonNull(reason).name()
                + (code == null ? "" : "=" + code));
        this.stage = stage; this.reason = reason; this.code = code;
    }
    public static CatalogFailure of(Stage stage, Reason reason) { return new CatalogFailure(stage, reason, null); }
    public static CatalogFailure code(Stage stage, Reason reason, long code) {
        if (reason != Reason.HTTP && reason != Reason.API) throw new IllegalArgumentException("Not a numeric rejection");
        return new CatalogFailure(stage, reason, code);
    }
    public static CatalogFailure wrap(Stage stage, Reason fallback, IOException error) {
        if (error instanceof CatalogFailure safe) return safe;
        Reason reason = error instanceof SocketTimeoutException ? Reason.TIMEOUT : error instanceof UnknownHostException ? Reason.DNS
                : error instanceof SSLException ? Reason.TLS : fallback;
        return of(stage, reason);
    }
    public Stage stage() { return stage; }
    public Reason reason() { return reason; }
    public Long code() { return code; }
    public static String diagnostic(Throwable error) {
        CatalogFailure safe = error instanceof CatalogFailure known ? known : of(Stage.UNKNOWN, Reason.RESPONSE);
        return "\u5217\u8868\u8bfb\u53d6\u5931\u8d25 [" + safe.getMessage() + "]\uff1b"
                + (safe.reason == Reason.LOGIN_REQUIRED ? "\u8bf7\u5148\u626b\u7801\u767b\u5f55\uff1b" : "")
                + "\u672a\u64ad\u653e\uff0c\u4e0d\u81ea\u52a8\u91cd\u8bd5";
    }
}
