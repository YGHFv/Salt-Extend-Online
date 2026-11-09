package io.github.yghfv.saltneri.plugin.auth;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** Only fixed stages/reasons and numeric status codes may reach login UI diagnostics. */
public final class AuthFailure extends IOException {
    public enum Stage { NET_CREATE, NET_POLL, NET_VERIFY, NET_WEB, NET_DEVICE, BILI_CREATE, BILI_POLL, BILI_VERIFY, QQ_CREATE, QQ_POLL, QQ_VERIFY, QQ_SIGNATURE, QQ_OAUTH, QQ_EXCHANGE, QQ_WEB, UNKNOWN }
    public enum Reason { HTTP, API, RESPONSE, COOKIE, CREDENTIAL, IDENTITY, DNS, TLS, TIMEOUT, NETWORK, BROWSER_MISSING,
        BROWSER_POLICY, BROWSER_LAUNCH, BROWSER_EXIT, BROWSER_CONNECT, BROWSER_TIMEOUT, HELPER,
        HELPER_MISSING, HELPER_RESOURCE, HELPER_START, HELPER_POLICY, HELPER_VERSION, HELPER_SCRIPT, HELPER_IO, HELPER_EXIT, HELPER_OUTPUT,
        PROFILE, CANCELLED, DEVICE, DEVICE_PAGE, DEVICE_API, DEVICE_TOKEN, DEVICE_COOKIE, DEVICE_CONTEXT }
    private final Stage stage;
    private final Reason reason;
    private final Long code;
    private AuthFailure(Stage stage, Reason reason, Long code) {
        super(stage.name()+"/"+reason.name()+(code==null?"":"="+code)); this.stage=stage; this.reason=reason; this.code=code;
    }
    public static AuthFailure of(Stage stage, Reason reason) { return new AuthFailure(stage,reason,null); }
    public static AuthFailure code(Stage stage, Reason reason, long code) { return new AuthFailure(stage,reason,code); }
    public AuthFailure atStage(Stage target) { return new AuthFailure(target,reason,code); }
    static Reason webHelperReason(Object error) {
        return switch(String.valueOf(error)) {
            case "EDGE_MISSING" -> Reason.BROWSER_MISSING;
            case "EDGE_POLICY" -> Reason.BROWSER_POLICY;
            case "EDGE_LAUNCH" -> Reason.BROWSER_LAUNCH;
            case "EDGE_EARLY_EXIT" -> Reason.BROWSER_EXIT;
            case "EDGE_CDP" -> Reason.BROWSER_CONNECT;
            case "EDGE_START_TIMEOUT" -> Reason.BROWSER_TIMEOUT;
            case "EDGE_PROFILE" -> Reason.PROFILE;
            case "HELPER_POLICY" -> Reason.HELPER_POLICY;
            case "HELPER_VERSION" -> Reason.HELPER_VERSION;
            case "HELPER_SCRIPT" -> Reason.HELPER_SCRIPT;
            case "HELPER_IO" -> Reason.HELPER_IO;
            case "WEB_TIMEOUT" -> Reason.TIMEOUT;
            case "WEB_CLOSED" -> Reason.CANCELLED;
            default -> Reason.RESPONSE;
        };
    }
    public boolean requiresWebVerification() {
        if (stage == Stage.NET_DEVICE) return true;
        return (stage==Stage.NET_CREATE || stage==Stage.NET_POLL || stage==Stage.NET_VERIFY) && reason==Reason.API
            && (Long.valueOf(8821).equals(code) || Long.valueOf(-462).equals(code));
    }
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
        if(safe.stage==Stage.NET_DEVICE) return "\u65e0\u6cd5\u51c6\u5907\u7f51\u6613\u4e91\u626b\u7801\u5b89\u5168\u73af\u5883 ["+safe.getMessage()+"]\uff1b"+
                webHint(safe.reason)+"\u3002\u53ef\u4f7f\u7528\u201c\u5b98\u65b9\u7f51\u9875\u767b\u5f55\u201d\u624b\u52a8\u9a8c\u8bc1\uff1b\u672a\u4f7f\u7528\u7a7a\u8bbe\u5907\u4ee4\u724c\u91cd\u8bd5";
        if (safe.stage==Stage.NET_WEB || safe.stage==Stage.QQ_WEB) {
            String platform=safe.stage==Stage.QQ_WEB?"QQ \u97f3\u4e50":safe.stage==Stage.NET_DEVICE?"\u7f51\u6613\u4e91\u5b89\u5168\u73af\u5883":"\u7f51\u6613\u4e91";
            return platform+" \u7f51\u9875\u767b\u5f55\u672a\u5b8c\u6210 ["+safe.getMessage()+"]\uff1b"+webHint(safe.reason)+
                "\u3002\u672a\u5bfc\u5165\u672a\u9a8c\u8bc1\u8d26\u53f7";
        }
        if(safe.requiresWebVerification()) return "\u7f51\u6613\u4e91\u8981\u6c42\u5b89\u5168\u9a8c\u8bc1 ["+safe.getMessage()+"]\uff1b\u8bf7\u70b9\u51fb\u201c\u5b98\u65b9\u7f51\u9875\u767b\u5f55\u201d\uff0c\u5728\u7f51\u6613\u4e91\u9875\u9762\u624b\u52a8\u5b8c\u6210\u9a8c\u8bc1\u548c\u767b\u5f55";
        if(safe.stage.name().startsWith("QQ_")) return "QQ \u97f3\u4e50\u767b\u5f55\u672a\u5b8c\u6210 ["+safe.getMessage()+"]\uff1b\u53ef\u91cd\u65b0\u626b\u7801\u6216\u4f7f\u7528\u201c\u5b98\u65b9\u7f51\u9875\u767b\u5f55\u201d\u624b\u52a8\u9a8c\u8bc1\uff08\u9700 Microsoft Edge\uff09\u3002\u672a\u5bfc\u5165\u672a\u9a8c\u8bc1\u8d26\u53f7";
        return "\u767b\u5f55\u5931\u8d25 ["+safe.getMessage()+"]\uff1b"+
            (safe.reason==Reason.TIMEOUT || safe.reason==Reason.NETWORK || safe.reason==Reason.DNS || safe.reason==Reason.TLS
                ? "\u8bf7\u68c0\u67e5\u7f51\u7edc\u540e\u70b9\u51fb\u8d26\u53f7\u56fe\u6807\u91cd\u8bd5"
                : "\u8bf7\u70b9\u51fb\u8d26\u53f7\u56fe\u6807\u91cd\u65b0\u626b\u7801\uff0c\u5e76\u5728\u5b98\u65b9 App \u786e\u8ba4");
    }
    private static String webHint(Reason reason) {
        return switch(reason) {
            case DEVICE -> "\u626b\u7801\u8bbe\u5907\u73af\u5883\u672a\u5c31\u7eea\uff0c\u9700 Microsoft Edge \u52a0\u8f7d\u7f51\u6613\u4e91\u5b98\u65b9\u9875\u9762";
            case DEVICE_PAGE -> "\u7f51\u6613\u4e91\u5b98\u65b9\u9875\u9762\u672a\u5728\u65f6\u9650\u5185\u52a0\u8f7d\u5b8c\u6210";
            case DEVICE_API -> "\u5b98\u65b9\u9875\u9762\u5df2\u52a0\u8f7d\uff0c\u4f46\u8bbe\u5907\u5b89\u5168\u63a5\u53e3\u672a\u5c31\u7eea";
            case DEVICE_TOKEN -> "\u5b98\u65b9\u8bbe\u5907\u5b89\u5168\u63a5\u53e3\u672a\u8fd4\u56de\u6709\u6548\u4ee4\u724c";
            case DEVICE_COOKIE -> "\u5b98\u65b9\u8bbe\u5907 Cookie \u672a\u5c31\u7eea\u6216\u4e0e\u4ee4\u724c\u73af\u5883\u4e0d\u4e00\u81f4";
            case DEVICE_CONTEXT -> "\u8bbe\u5907\u51c6\u5907\u65f6\u9875\u9762\u5df2\u5207\u6362\u6216\u51fa\u73b0\u8d26\u53f7\u4e0a\u4e0b\u6587\uff0c\u5df2\u5b89\u5168\u505c\u6b62";
            case BROWSER_MISSING -> "\u672a\u627e\u5230 Microsoft Edge\uff0c\u8bf7\u786e\u8ba4\u5df2\u5b89\u88c5\u6b63\u5f0f\u7248";
            case BROWSER_POLICY -> "Edge \u7b56\u7565\u9650\u5236\u8c03\u8bd5\u6216\u72ec\u7acb\u914d\u7f6e\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\uff1b\u672a\u7ed5\u8fc7\u7b56\u7565";
            case BROWSER_LAUNCH -> "\u5df2\u627e\u5230 Edge \u4f46\u542f\u52a8\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5 Edge \u662f\u5426\u53ef\u624b\u52a8\u6253\u5f00\u53ca\u5b89\u5168\u8f6f\u4ef6\u62e6\u622a";
            case BROWSER_EXIT -> "Edge \u5728\u767b\u5f55\u8fde\u63a5\u5efa\u7acb\u524d\u9000\u51fa\uff0c\u8bf7\u68c0\u67e5 Edge \u662f\u5426\u53ef\u624b\u52a8\u6253\u5f00\u6216\u4fee\u590d\u5b89\u88c5";
            case BROWSER_CONNECT -> "Edge \u5df2\u542f\u52a8\u4f46\u65e0\u6cd5\u5efa\u7acb\u5b89\u5168\u767b\u5f55\u8fde\u63a5\uff0c\u8bf7\u68c0\u67e5\u6d4f\u89c8\u5668\u7b56\u7565\u53ca\u5b89\u5168\u8f6f\u4ef6";
            case BROWSER_TIMEOUT -> "Edge \u542f\u52a8\u8fde\u63a5\u8d85\u65f6\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u5e76\u68c0\u67e5 Edge \u662f\u5426\u53ef\u624b\u52a8\u6253\u5f00";
            case HELPER -> "Windows PowerShell \u767b\u5f55\u8f85\u52a9\u7a0b\u5e8f\u65e0\u6cd5\u8fd0\u884c\uff0c\u8bf7\u68c0\u67e5\u7cfb\u7edf\u7ec4\u4ef6\u53ca\u5b89\u5168\u8f6f\u4ef6";
            case HELPER_MISSING -> "\u672a\u627e\u5230 Windows PowerShell 5.1\uff0c\u8bf7\u68c0\u67e5 Windows \u7cfb\u7edf\u7ec4\u4ef6";
            case HELPER_RESOURCE -> "\u6a21\u7ec4\u767b\u5f55\u811a\u672c\u7f3a\u5931\u6216\u65e0\u6cd5\u8bfb\u53d6\uff0c\u8bf7\u91cd\u65b0\u5bfc\u5165\u5b8c\u6574\u6a21\u7ec4\u5305";
            case HELPER_START -> "Windows PowerShell \u8fdb\u7a0b\u542f\u52a8\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7cfb\u7edf\u7ec4\u4ef6\u548c\u5b89\u5168\u8f6f\u4ef6\u62e6\u622a";
            case HELPER_POLICY -> "PowerShell \u8bed\u8a00\u6a21\u5f0f\u6216\u5b89\u5168\u7b56\u7565\u9650\u5236\u767b\u5f55\u811a\u672c\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\uff1b\u672a\u7ed5\u8fc7\u7b56\u7565";
            case HELPER_VERSION -> "\u767b\u5f55\u9700\u8981 Windows PowerShell 5.1 \u6216\u66f4\u65b0\u7cfb\u7edf\u7ec4\u4ef6";
            case HELPER_SCRIPT -> "\u767b\u5f55\u8f85\u52a9\u811a\u672c\u521d\u59cb\u5316\u5931\u8d25\uff0c\u8bf7\u63d0\u4f9b\u6b64\u9519\u8bef\u7801\u53ca Windows \u7248\u672c";
            case HELPER_IO -> "\u767b\u5f55\u8f85\u52a9\u7a0b\u5e8f\u6587\u4ef6\u6216\u7ba1\u9053\u8bfb\u5199\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u4e34\u65f6\u76ee\u5f55\u548c\u5b89\u5168\u8f6f\u4ef6";
            case HELPER_EXIT -> "Windows PowerShell \u8f85\u52a9\u8fdb\u7a0b\u5f02\u5e38\u9000\u51fa\uff0c\u8bf7\u63d0\u4f9b\u6b64\u9519\u8bef\u7801\u53ca Windows \u7248\u672c";
            case HELPER_OUTPUT -> "\u767b\u5f55\u8f85\u52a9\u8fdb\u7a0b\u672a\u8fd4\u56de\u6709\u6548\u7ed3\u679c\uff0c\u8bf7\u63d0\u4f9b\u6b64\u9519\u8bef\u7801";
            case PROFILE -> "\u65e0\u6cd5\u521b\u5efa\u53d7\u4fdd\u62a4\u7684\u4e34\u65f6\u914d\u7f6e\uff0c\u8bf7\u68c0\u67e5\u4e34\u65f6\u76ee\u5f55\u6743\u9650\u548c\u78c1\u76d8\u7a7a\u95f4";
            case CANCELLED -> "\u767b\u5f55\u7a97\u53e3\u5df2\u5173\u95ed\uff0c\u53ef\u518d\u6b21\u70b9\u51fb\u201c\u5b98\u65b9\u7f51\u9875\u767b\u5f55\u201d";
            case TIMEOUT -> "\u767b\u5f55\u8d85\u65f6\uff0c\u8bf7\u91cd\u8bd5\u5e76\u5728\u5b98\u65b9\u9875\u9762\u5b8c\u6210\u9a8c\u8bc1";
            default -> "\u53ef\u518d\u6b21\u70b9\u51fb\u201c\u5b98\u65b9\u7f51\u9875\u767b\u5f55\u201d\u5b8c\u6210\u624b\u52a8\u9a8c\u8bc1";
        };
    }
}
