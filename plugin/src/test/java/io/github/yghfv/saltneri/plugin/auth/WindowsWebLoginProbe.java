package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CancellationException;

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
        String setup="$root=[Console]::ReadLine();try{Start-IsolatedEdge $root $true;"+
            "$target=(Send-Cdp 'Target.getTargets' @{}).targetInfos|Where-Object {$_.type -eq 'page'}|Select-Object -First 1;"+
            "if($target.url -ne 'about:blank'){throw 'Unexpected navigation'};"+
            "$session=(Send-Cdp 'Target.attachToTarget' @{targetId=$target.targetId;flatten=$true}).sessionId;";
        String finish="}finally{Stop-IsolatedEdge}";
        String success=setup+"Send-Cdp 'Network.setCookies' @{cookies=@(@{name='MUSIC_U';value='SYNTHETIC_SECRET';domain='.music.163.com';path='/';secure=$true;httpOnly=$true})} $session|Out-Null;"+
            "$value=Send-Cdp 'Network.getCookies' @{urls=@('https://music.163.com/')} $session;[Console]::WriteLine(($value|ConvertTo-Json -Depth 12 -Compress));"+finish;
        try(var request=new RequestScope(()->true,30000)) {
            var bytes=WindowsNeteaseWebLogin.runHelper(request,success);
            var cookies=WindowsNeteaseWebLogin.readCookies(bytes,Clock.systemUTC()); Arrays.fill(bytes,(byte)0);
            if(cookies.size()!=1 || !cookies.getFirst().value().equals("SYNTHETIC_SECRET")) throw new AssertionError("Bridge cookie mismatch");
        }
        if(!profiles().equals(before)) throw new AssertionError("Successful helper left browser profile behind");
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
        System.out.println("PASS: real hidden PowerShell/Edge anonymous-pipe bridge, synthetic HttpOnly/device cookies, official device script mapping/origin guard, deadline cancellation and profile cleanup; no website/account/image");
    }
}
