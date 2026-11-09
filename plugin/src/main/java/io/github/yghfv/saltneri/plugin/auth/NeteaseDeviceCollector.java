package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.*;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.concurrent.TimeUnit;

/** NeriPlayer's page/API/token stages, with an isolated CDP context and strict cookie boundary. */
final class NeteaseDeviceCollector {
    interface Page {
        Map<String,Object> call(String method,Map<String,Object> params) throws IOException;
        void pause(long milliseconds);
    }
    static final class ContextUnavailable extends IOException {
        ContextUnavailable() {super("Device page context is changing");}
    }
    static final String READY_SCRIPT="""
        (() => ({
            origin: location.origin,
            loaded: document.readyState === 'interactive' || document.readyState === 'complete',
            ready: typeof createNEFingerprint === 'function'
        }))()
        """;
    static final String START_SCRIPT="""
        (() => {
            if (location.origin !== 'https://music.163.com') return {error: 'DEVICE_CONTEXT'};
            if (typeof createNEFingerprint !== 'function') return {error: 'DEVICE_API'};
            if (window.__saltNeteaseDevice) return {started: true};
            const state = {pending: true};
            window.__saltNeteaseDevice = state;
            try {
                const instance = createNEFingerprint({appId: '9d0ef7e0905d422cba1ecf7e73d77e67', timeout: 6000});
                Promise.resolve(instance.getToken()).then(result => {
                    if (location.origin !== 'https://music.163.com') state.error = 'DEVICE_CONTEXT';
                    else if (!result || typeof result.token !== 'string' ||
                        !/^[\\x21-\\x7e]{1,4096}$/.test(result.token)) state.error = 'DEVICE_TOKEN';
                    else state.token = result.token;
                    state.pending = false;
                }).catch(() => {state.error = 'DEVICE_TOKEN'; state.pending = false;});
            } catch (_) {state.error = 'DEVICE_TOKEN'; state.pending = false;}
            return {started: true};
        })()
        """;
    static final String POLL_SCRIPT="""
        (() => {
            if (location.origin !== 'https://music.163.com') return {error: 'DEVICE_CONTEXT'};
            const state = window.__saltNeteaseDevice;
            if (!state) return {error: 'DEVICE_CONTEXT'};
            if (state.pending) return {pending: true};
            if (state.error) return {error: state.error};
            return {token: state.token, userAgent: navigator.userAgent};
        })()
        """;
    private final Page page;
    private final LongSupplier now;
    private final Clock clock;
    NeteaseDeviceCollector(Page page) {this(page,System::nanoTime,Clock.systemUTC());}
    NeteaseDeviceCollector(Page page,LongSupplier now,Clock clock) {this.page=page;this.now=now;this.clock=clock;}
    byte[] collect(RequestScope request) throws IOException {
        request.check();
        var navigation=page.call("Page.navigate",Map.of("url","https://music.163.com/"));
        if(navigation.get("errorText")!=null)throw failure(AuthFailure.Reason.DEVICE_PAGE);
        waitReady(request,15000,false);
        waitReady(request,12000,true);
        var start=evaluate(START_SCRIPT);
        if(!Boolean.TRUE.equals(start.get("started")))throw scriptFailure(start,AuthFailure.Reason.DEVICE_TOKEN);
        long end=deadline(12000);
        Map<String,Object> token=null;
        while(now.getAsLong()<end) {
            request.check();var result=evaluate(POLL_SCRIPT);
            if(result.get("error")!=null)throw scriptFailure(result,AuthFailure.Reason.DEVICE_TOKEN);
            if(result.get("token") instanceof String value && value.matches("[!-~]{1,4096}")
                    && result.get("userAgent") instanceof String userAgent && AuthHttp.validUserAgent(userAgent)) {token=result;break;}
            if(!Boolean.TRUE.equals(result.get("pending")))throw failure(AuthFailure.Reason.DEVICE_TOKEN);
            page.pause(200);
        }
        if(token==null)throw failure(AuthFailure.Reason.DEVICE_TOKEN);
        end=deadline(5000);
        while(now.getAsLong()<end) {
            request.check();
            var context=evaluate(READY_SCRIPT);
            if(!"https://music.163.com".equals(context.get("origin")))throw failure(AuthFailure.Reason.DEVICE_CONTEXT);
            var cookies=filterCookies(BoundedJson.array(page.call("Network.getCookies",Map.of("urls",List.of("https://music.163.com/"))).get("cookies")));
            String device=null;
            for(Object row:cookies) {
                var cookie=BoundedJson.object(row);
                if("MUSIC_U".equals(cookie.get("name")))throw failure(AuthFailure.Reason.DEVICE_CONTEXT);
                if("sDeviceId".equals(cookie.get("name"))) {
                    if(device!=null)throw failure(AuthFailure.Reason.DEVICE_COOKIE);
                    device=BoundedJson.string(cookie.get("value"));
                }
            }
            if(device!=null && !device.isEmpty()) {
                var stillCurrent=evaluate(POLL_SCRIPT);
                if(!Objects.equals(token.get("token"),stillCurrent.get("token")))
                    throw failure(AuthFailure.Reason.DEVICE_CONTEXT);
                byte[] output=BoundedJson.encode(Map.of("token",token.get("token"),"deviceId",device,"userAgent",token.get("userAgent"),"cookies",cookies));
                try {
                    NeteaseDeviceSnapshot.read(output,clock);
                    request.check();return output;
                } catch(IOException | RuntimeException invalid) {
                    Arrays.fill(output,(byte)0);
                    if(invalid instanceof java.util.concurrent.CancellationException cancelled)throw cancelled;
                    throw failure(AuthFailure.Reason.DEVICE_COOKIE);
                }
            }
            page.pause(200);
        }
        throw failure(AuthFailure.Reason.DEVICE_COOKIE);
    }
    private void waitReady(RequestScope request,long milliseconds,boolean api) throws IOException {
        long end=deadline(milliseconds);
        while(now.getAsLong()<end) {
            request.check();
            try {
                var state=evaluate(READY_SCRIPT);
                if("https://music.163.com".equals(state.get("origin")) && Boolean.TRUE.equals(state.get("loaded"))
                        && (!api || Boolean.TRUE.equals(state.get("ready"))))return;
                if(api && !"https://music.163.com".equals(state.get("origin")))throw failure(AuthFailure.Reason.DEVICE_CONTEXT);
            } catch(ContextUnavailable changing) { /* A navigation destroys its old JavaScript world before commit. */ }
            page.pause(400);
        }
        throw failure(api?AuthFailure.Reason.DEVICE_API:AuthFailure.Reason.DEVICE_PAGE);
    }
    private Map<String,Object> evaluate(String script) throws IOException {
        var response=page.call("Runtime.evaluate",Map.of("expression",script,"returnByValue",true));
        if(response.get("exceptionDetails")!=null)throw failure(AuthFailure.Reason.DEVICE_CONTEXT);
        return BoundedJson.object(BoundedJson.object(response.get("result")).get("value"));
    }
    static List<Object> filterCookies(List<Object> rows) throws IOException {
        if(rows.size()>4096)throw failure(AuthFailure.Reason.DEVICE_COOKIE);
        var result=new ArrayList<Object>();
        for(Object row:rows) {
            var cookie=BoundedJson.object(row);
            if(!(cookie.get("domain") instanceof String raw))throw failure(AuthFailure.Reason.DEVICE_COOKIE);
            String domain=raw.replaceFirst("^\\.","").toLowerCase(Locale.ROOT);
            // Browser returns parent-domain analytics too. Never widen the authenticated jar to .163.com.
            if(AccountPlatform.NETEASE.allows(domain))result.add(cookie);
        }
        if(result.size()>64)throw failure(AuthFailure.Reason.DEVICE_COOKIE);
        return result;
    }
    private long deadline(long milliseconds) {return now.getAsLong()+TimeUnit.MILLISECONDS.toNanos(milliseconds);}
    private static AuthFailure scriptFailure(Map<String,Object> result,AuthFailure.Reason fallback) {
        return failure("DEVICE_CONTEXT".equals(result.get("error"))?AuthFailure.Reason.DEVICE_CONTEXT:
                "DEVICE_API".equals(result.get("error"))?AuthFailure.Reason.DEVICE_API:fallback);
    }
    private static AuthFailure failure(AuthFailure.Reason reason) {return AuthFailure.of(AuthFailure.Stage.NET_DEVICE,reason);}
}
