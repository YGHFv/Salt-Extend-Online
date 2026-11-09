package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CancellationException;
import io.github.yghfv.saltneri.plugin.net.BoundedJson;

/** Explicit installed-Edge bridge QA: only about:blank and synthetic cookies; no visible window. */
public final class WindowsWebLoginProbe {
    private static Set<Path> profiles() throws Exception {
        Set<Path> paths=new HashSet<>();
        try(var entries=Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")),"salt-netease-web-*")) { entries.forEach(paths::add); }
        return paths;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1 || !args[0].equals("--explicit-headless-fixture")) throw new IllegalArgumentException("Explicit headless fixture required");
        var before=profiles();
        var bootstrapCases=Map.of(
            "throw [IO.IOException]::new('SYNTHETIC_SECRET')","HELPER_IO",
            "throw 'SYNTHETIC_SECRET'","HELPER_SCRIPT",
            "function {","HELPER_SCRIPT",
            "throw [Management.Automation.PSSecurityException]::new('SYNTHETIC_SECRET')","HELPER_POLICY");
        for(var fixture:bootstrapCases.entrySet()) {
            try(var request=new RequestScope(()->true,15000)) {
                byte[] output=WindowsNeteaseWebLogin.runHelper(request,fixture.getKey(),10000);
                try {
                    String result=new String(output,java.nio.charset.StandardCharsets.UTF_8);
                    if(!result.contains(fixture.getValue()) || result.contains("SYNTHETIC_SECRET"))
                        throw new AssertionError("Bootstrap failure classification/redaction");
                } finally {Arrays.fill(output,(byte)0);}
            }
        }
        try(var request=new RequestScope(()->true,15000)) {
            try {WindowsNeteaseWebLogin.runHelper(request,"[Console]::Error.WriteLine('SYNTHETIC_SECRET');exit 7",10000);throw new AssertionError("Nonzero exit ignored");}
            catch(AuthFailure safe) {if(!safe.getMessage().equals("NET_WEB/HELPER_EXIT=7"))throw new AssertionError("Helper exit misclassified");}
        }
        try(var request=new RequestScope(()->true,15000)) {
            try {WindowsNeteaseWebLogin.runHelper(request,"$root=[Console]::ReadLine()",10000);throw new AssertionError("Empty helper result ignored");}
            catch(AuthFailure safe) {if(!safe.getMessage().equals("NET_WEB/HELPER_OUTPUT"))throw new AssertionError("Empty output misclassified");}
        }
        String large="$root=[Console]::ReadLine();# "+"fixture ".repeat(6000)+"\n"+
            "for($i=0;$i -lt 128;$i++){[Console]::Error.WriteLine(('SYNTHETIC_SECRET'*128))};[Console]::WriteLine('{\"passed\":true}')";
        try(var request=new RequestScope(()->true,15000)) {
            byte[] output=WindowsNeteaseWebLogin.runHelper(request,large,10000);
            try {
                if(!Boolean.TRUE.equals(BoundedJson.object(BoundedJson.parse(output)).get("passed")))
                    throw new AssertionError("Large script/stderr drain failed");
            } finally {Arrays.fill(output,(byte)0);}
        }
        // LanguageMode is changed only inside this subprocess; the loader must refuse it.
        String bootstrap="$root=[Console]::ReadLine();$command="+
            "'$ExecutionContext.SessionState.LanguageMode=''ConstrainedLanguage'';"+
            WindowsWebLoginHelper.loader(Path.of("unused-helper.ps1")).replace("'","''")+"';"+
            "& powershell.exe -NoLogo -NoProfile -NonInteractive -Command $command";
        try(var request=new RequestScope(()->true,15000)) {
            byte[] output=WindowsNeteaseWebLogin.runHelper(request,bootstrap,10000);
            try {
                if(!new String(output,java.nio.charset.StandardCharsets.UTF_8).contains("HELPER_POLICY"))
                    throw new AssertionError("Restricted language not rejected");
            } finally {Arrays.fill(output,(byte)0);}
        }
        if(!profiles().equals(before)) throw new AssertionError("Bootstrap fixture left files behind");
        String discovery=Files.readString(Path.of("plugin/src/test/resources/auth/edge-discovery-fixture.ps1"));
        try(var request=new RequestScope(()->true,30000)) {
            byte[] output=WindowsNeteaseWebLogin.runHelper(request,discovery,25000);
            try {
                var result=BoundedJson.object(BoundedJson.parse(output));
                if(!Boolean.TRUE.equals(result.get("passed")) || !(result.get("checks") instanceof java.math.BigDecimal count) || count.intValue()<20)
                    throw new AssertionError("Edge discovery fixtures failed");
                System.out.println("PASS: "+count.intValue()+" child-only Edge discovery/policy/launch/bootstrap checks");
            } finally {Arrays.fill(output,(byte)0);}
        }
        if(!profiles().equals(before)) throw new AssertionError("Discovery helper left files behind");
        String setup="$root=[Console]::ReadLine();try{Start-IsolatedEdge $root $true;"+
            "$target=(Send-Cdp 'Target.getTargets' @{}).targetInfos|Where-Object {$_.type -eq 'page'}|Select-Object -First 1;"+
            "if($target.url -ne 'about:blank'){throw 'Unexpected navigation'};"+
            "$session=(Send-Cdp 'Target.attachToTarget' @{targetId=$target.targetId;flatten=$true}).sessionId;";
        String finish="}finally{Stop-IsolatedEdge}";
        String success="${env:ProgramFiles(x86)}=$null;$env:ProgramFiles=$null;$env:ProgramW6432=$null;$env:LOCALAPPDATA=$null;"+
            setup+"Send-Cdp 'Network.setCookies' @{cookies=@(@{name='MUSIC_U';value='SYNTHETIC_SECRET';domain='.music.163.com';path='/';secure=$true;httpOnly=$true})} $session|Out-Null;"+
            "$value=Send-Cdp 'Network.getCookies' @{urls=@('https://music.163.com/')} $session;[Console]::WriteLine(($value|ConvertTo-Json -Depth 12 -Compress));"+finish;
        try(var request=new RequestScope(()->true,30000)) {
            var bytes=WindowsNeteaseWebLogin.runHelper(request,success);
            var cookies=WindowsNeteaseWebLogin.readCookies(bytes,Clock.systemUTC()); Arrays.fill(bytes,(byte)0);
            if(cookies.size()!=1 || !cookies.getFirst().value().equals("SYNTHETIC_SECRET")) throw new AssertionError("Bridge cookie mismatch");
        }
        if(!profiles().equals(before)) throw new AssertionError("Successful helper left browser profile behind");
        String qq=setup+"Send-Cdp 'Network.setCookies' @{cookies=@("+
            "@{name='qm_keyst';value='SYNTHETIC_SECRET';domain='y.qq.com';path='/';secure=$true;httpOnly=$true},"+
            "@{name='uin';value='o00042';domain='.qq.com';path='/';secure=$true},"+
            "@{name='tracking';value='auxiliary, value';domain='y.qq.com';path='/';secure=$true},"+
            "@{name='p_skey';value='NOT_IMPORTED';domain='.qq.com';path='/';secure=$true})} $session|Out-Null;"+
            "$cookies=@(Get-QqMusicCookies);if(-not (Test-QqMusicWebReady $cookies)){throw 'QQ fixture not ready'};"+
            "[Console]::WriteLine((@{cookies=$cookies}|ConvertTo-Json -Depth 12 -Compress));"+finish;
        try(var request=new RequestScope(()->true,30000)) {
            var bytes=WindowsNeteaseWebLogin.runHelper(request,qq);
            try {
                var account=QqMusicWebCookies.decode(bytes,Clock.systemUTC());
                try {
                    if(!account.userId().equals("42") || !account.header(io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.GATEWAY).contains("qm_keyst=SYNTHETIC_SECRET")
                            || account.cookieSnapshot().stream().anyMatch(e->e.name().equals("p_skey") || e.name().equals("tracking")))
                        throw new AssertionError("QQ bridge cookie mismatch");
                } finally {account.retire();}
            } finally {Arrays.fill(bytes,(byte)0);}
        }
        if(!profiles().equals(before)) throw new AssertionError("QQ helper left browser profile behind");
        String device=setup+"$expression='(function(location,createNEFingerprint){return '+(Get-NeteaseDeviceScript)+'})({origin:\"https://music.163.com\"},function(options){if(options.appId!==\"9d0ef7e0905d422cba1ecf7e73d77e67\")throw 1;return {getToken:()=>Promise.resolve({token:\"SYNTHETIC_TOKEN\"})}})';"+
            "$value=(Send-Cdp 'Runtime.evaluate' @{expression=$expression;returnByValue=$true;awaitPromise=$true} $session).result.value;"+
            "Send-Cdp 'Network.setCookies' @{cookies=@(@{name='sDeviceId';value='fixture-device';domain='.music.163.com';path='/';secure=$true})} $session|Out-Null;"+
            "$cookies=(Send-Cdp 'Network.getCookies' @{urls=@('https://music.163.com/')} $session).cookies;"+
            "[Console]::WriteLine((@{token=$value.token;deviceId='fixture-device';userAgent=$value.userAgent;cookies=@($cookies)}|ConvertTo-Json -Depth 12 -Compress));"+finish;
        try(var request=new RequestScope(()->true,30000)) {
            var bytes=WindowsNeteaseWebLogin.runHelper(request,device,25000);
            var snapshot=NeteaseDeviceSnapshot.read(bytes,Clock.systemUTC()); Arrays.fill(bytes,(byte)0);
            if(!snapshot.token().equals("SYNTHETIC_TOKEN")) throw new AssertionError("Device script mapping mismatch");
        }
        if(!profiles().equals(before)) throw new AssertionError("Device helper left browser profile behind");
        String denied=setup+"$expression='(function(location,createNEFingerprint){return '+(Get-NeteaseDeviceScript)+'})({origin:\"https://evil.invalid\"},function(){throw 1})';"+
            "$value=(Send-Cdp 'Runtime.evaluate' @{expression=$expression;returnByValue=$true;awaitPromise=$true} $session).result.value;"+
            "[Console]::WriteLine(($value|ConvertTo-Json -Compress));"+finish;
        try(var request=new RequestScope(()->true,30000)) {
            var bytes=WindowsNeteaseWebLogin.runHelper(request,denied,25000);
            if(!new String(bytes,java.nio.charset.StandardCharsets.UTF_8).contains("DEVICE_ORIGIN")) throw new AssertionError("Device script origin guard failed");
            Arrays.fill(bytes,(byte)0);
        }
        if(!profiles().equals(before)) throw new AssertionError("Rejected device helper left browser profile behind");
        try(var request=new RequestScope(()->true,5000)) {
            try { WindowsNeteaseWebLogin.runHelper(request,setup+"Start-Sleep -Seconds 60;"+finish);throw new AssertionError("Cancellation ignored"); }
            catch(CancellationException expected) { }
        }
        if(!profiles().equals(before)) throw new AssertionError("Cancelled helper left browser profile behind");
        try(var request=new RequestScope(()->true,15000)) {
            try { WindowsNeteaseWebLogin.runHelper(request,"$root=[Console]::ReadLine();Start-Sleep -Seconds 60",1500);throw new AssertionError("Helper deadline ignored"); }
            catch(AuthFailure expected) {if(!expected.getMessage().equals("NET_WEB/TIMEOUT"))throw new AssertionError("Helper timeout misclassified");}
        }
        if(!profiles().equals(before)) throw new AssertionError("Timed-out helper left files behind");
        System.out.println("PASS: plain bootstrap syntax/IO/policy/exit/empty output/large script/stderr drain fixtures; Edge discovery/path/policy/launch fixtures; real hidden PowerShell/Edge anonymous-pipe bridge with missing environment variables; synthetic HttpOnly/device cookies, origin guard, request cancellation/helper timeout and profile cleanup; no website/account/image");
    }
}
