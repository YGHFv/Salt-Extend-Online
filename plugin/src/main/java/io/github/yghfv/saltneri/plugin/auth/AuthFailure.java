package io.github.yghfv.saltneri.plugin.auth;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** Only fixed stages/reasons and numeric status codes may reach login UI diagnostics. */
public final class AuthFailure extends IOException {
    public enum Stage { NET_CREATE, NET_POLL, NET_VERIFY, BILI_CREATE, BILI_POLL, BILI_VERIFY, UNKNOWN }
    public enum Reason { HTTP, API, RESPONSE, COOKIE, CREDENTIAL, IDENTITY, DNS, TLS, TIMEOUT, NETWORK }
    private final Stage stage;
    private final Reason reason;
    private AuthFailure(Stage stage, Reason reason, Long code) {
        super(stage.name()+"/"+reason.name()+(code==null?"":"="+code)); this.stage=stage; this.reason=reason;
    }
    public static AuthFailure of(Stage stage, Reason reason) { return new AuthFailure(stage,reason,null); }
    public static AuthFailure code(Stage stage, Reason reason, long code) { return new AuthFailure(stage,reason,code); }
    public static AuthFailure wrap(Stage stage, Reason fallback, IOException error) {
        if(error instanceof AuthFailure safe) return safe;
        return of(stage,error instanceof SocketTimeoutException?Reason.TIMEOUT:error instanceof UnknownHostException?Reason.DNS:error instanceof SSLException?Reason.TLS:fallback);
    }
    public static Stage stage(String path) {
        return switch(path) {
            case "/weapi/login/qrcode/unikey" -> Stage.NET_CREATE;
            case "/weapi/login/qrcode/client/login" -> Stage.NET_POLL;
            case "/weapi/w/nuser/account/get" -> Stage.NET_VERIFY;
            case "/x/passport-login/web/qrcode/generate" -> Stage.BILI_CREATE;
            case "/x/passport-login/web/qrcode/poll" -> Stage.BILI_POLL;
            case "/x/web-interface/nav" -> Stage.BILI_VERIFY;
            default -> Stage.UNKNOWN;
        };
    }
    public static String diagnostic(Throwable error) {
        AuthFailure safe=error instanceof AuthFailure known?known:of(Stage.UNKNOWN,Reason.RESPONSE);
        return "\u767b\u5f55\u5931\u8d25 ["+safe.getMessage()+"]\uff1b"+
            (safe.reason==Reason.TIMEOUT || safe.reason==Reason.NETWORK || safe.reason==Reason.DNS || safe.reason==Reason.TLS
                ? "\u8bf7\u68c0\u67e5\u7f51\u7edc\u540e\u70b9\u51fb\u8d26\u53f7\u56fe\u6807\u91cd\u8bd5"
                : "\u8bf7\u70b9\u51fb\u8d26\u53f7\u56fe\u6807\u91cd\u65b0\u626b\u7801\uff0c\u5e76\u5728\u5b98\u65b9 App \u786e\u8ba4");
    }
}
