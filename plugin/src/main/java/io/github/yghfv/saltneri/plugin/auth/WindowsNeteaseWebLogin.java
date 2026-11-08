package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Explicit official web login in a fresh Edge profile; never imports an existing browser session. */
public final class WindowsNeteaseWebLogin {
    private WindowsNeteaseWebLogin() { }
    public static AccountCredentials login(RequestScope request) throws IOException {
        var clock=Clock.systemUTC();
        byte[] output=runHelper(request);
        try {
            var entries=readCookies(output,clock);
            try (var client=new NeteaseQrClient()) { return client.verifyWebCookies(entries,request); }
        } catch(IOException error) { throw AuthFailure.wrap(AuthFailure.Stage.NET_WEB,AuthFailure.Reason.RESPONSE,error); }
        finally { Arrays.fill(output,(byte)0); }
    }
    static List<ScopedCookies.Entry> readCookies(byte[] output, Clock clock) throws IOException {
        return readCookies(output, clock, true);
    }
    static List<ScopedCookies.Entry> readCookies(byte[] output, Clock clock, boolean requireCredential) throws IOException {
        if(output.length>65536) throw failure(AuthFailure.Reason.RESPONSE);
        var root=BoundedJson.object(BoundedJson.parse(output));
        if(root.get("error")!=null) {
            throw failure(switch(String.valueOf(root.get("error"))) {
                case "EDGE_MISSING" -> AuthFailure.Reason.BROWSER_MISSING;
                case "WEB_TIMEOUT" -> AuthFailure.Reason.TIMEOUT;
                case "WEB_CLOSED" -> AuthFailure.Reason.CANCELLED;
                default -> AuthFailure.Reason.RESPONSE;
            });
        }
        var rows=BoundedJson.array(root.get("cookies"));
        if(rows.size()>64) throw failure(AuthFailure.Reason.COOKIE);
        List<ScopedCookies.Entry> entries=new ArrayList<>(); Set<String> identities=new HashSet<>();
        for(Object row:rows) {
            var value=BoundedJson.object(row);
            String name=BoundedJson.string(value.get("name")), domain=BoundedJson.string(value.get("domain")), path=BoundedJson.string(value.get("path"));
            boolean hostOnly=!domain.startsWith("."); domain=domain.replaceFirst("^\\.","").toLowerCase(Locale.ROOT);
            if(!AccountPlatform.NETEASE.allows(domain) || !identities.add(name+"\n"+domain+"\n"+path)) throw failure(AuthFailure.Reason.COOKIE);
            long seconds=30*86400L;
            if(!Boolean.TRUE.equals(value.get("session"))) {
                if(!(value.get("expires") instanceof java.math.BigDecimal expiry)) throw failure(AuthFailure.Reason.COOKIE);
                try { seconds=Math.min(seconds,Math.subtractExact(expiry.setScale(0,java.math.RoundingMode.FLOOR).longValueExact(),clock.instant().getEpochSecond())); }
                catch(ArithmeticException invalid) { throw failure(AuthFailure.Reason.COOKIE); }
                if(seconds<=0) continue;
            }
            entries.add(new ScopedCookies.Entry(name,BoundedJson.string(value.get("value")),domain,path,hostOnly,
                Boolean.TRUE.equals(value.get("secure")),clock.instant().plusSeconds(seconds)));
        }
        // Keep the same domain/path validation and credential requirements as native QR login.
        var jar=new ScopedCookies(AccountPlatform.NETEASE,clock);
        try {
            jar.restore(entries);
            if(requireCredential && jar.value(java.net.URI.create("https://music.163.com/"),"MUSIC_U").isEmpty()) throw failure(AuthFailure.Reason.CREDENTIAL);
            return jar.snapshot();
        } finally { jar.clear(); }
    }
    static byte[] runHelper(RequestScope request) throws IOException {
        return runHelper(request,"Invoke-NeteaseWebLogin");
    }
    static byte[] runHelper(RequestScope request, String entryPoint) throws IOException {
        return runHelper(request, entryPoint, 290000);
    }
    static byte[] runHelper(RequestScope request, String entryPoint, long timeoutMillis) throws IOException {
        request.check();
        if(!System.getProperty("os.name","").startsWith("Windows")) throw failure(AuthFailure.Reason.BROWSER_MISSING);
        String system=System.getenv("SystemRoot");
        if(system==null) throw failure(AuthFailure.Reason.BROWSER_MISSING);
        Path binary=Path.of(system,"System32","WindowsPowerShell","v1.0","powershell.exe");
        if(!Files.isRegularFile(binary)) throw failure(AuthFailure.Reason.BROWSER_MISSING);
        String script;
        try(var input=WindowsNeteaseWebLogin.class.getResourceAsStream("/auth/netease-web-login.ps1")) {
            if(input==null) throw failure(AuthFailure.Reason.RESPONSE);
            script=new String(input.readAllBytes(),StandardCharsets.UTF_8)+"\n"+entryPoint+"\n";
        }
        Path root=Files.createTempDirectory("salt-netease-web-").toAbsolutePath().normalize();
        Process process=null;
        Set<ProcessHandle> children=ConcurrentHashMap.newKeySet();
        var watchdog=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"salt-netease-web-lifetime");t.setDaemon(true);return t;});
        try {
            var acl=Files.getFileAttributeView(root,AclFileAttributeView.class);
            if(acl==null) throw failure(AuthFailure.Reason.RESPONSE);
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(root))
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT).build()));
            request.check();
            process=new ProcessBuilder(binary.toString(),"-NoLogo","-NoProfile","-NonInteractive","-WindowStyle","Hidden","-EncodedCommand",
                Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process running=process; Thread owner=Thread.currentThread();
            watchdog.scheduleWithFixedDelay(()->{
                running.descendants().forEach(children::add);
                try { request.check(); if(owner.isInterrupted()) throw new CancellationException(); }
                catch(CancellationException stopped) { children.forEach(ProcessHandle::destroyForcibly); running.destroyForcibly(); }
            },0,100,TimeUnit.MILLISECONDS);
            watchdog.schedule(()->{children.forEach(ProcessHandle::destroyForcibly);running.destroyForcibly();},timeoutMillis,TimeUnit.MILLISECONDS);
            try(var input=process.getOutputStream()) { input.write((root+"\n").getBytes(StandardCharsets.UTF_8)); }
            byte[] output=process.getInputStream().readNBytes(65537);
            request.check();
            if(output.length>65536 || !process.waitFor(5,TimeUnit.SECONDS) || process.exitValue()!=0) throw failure(AuthFailure.Reason.RESPONSE);
            return output;
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new CancellationException("Web login cancelled");
        } finally {
            watchdog.shutdownNow();
            if(process!=null) { process.descendants().forEach(children::add); process.destroyForcibly(); }
            children.forEach(ProcessHandle::destroyForcibly);
            // Only this freshly-created directory is eligible for cleanup; never follow links.
            boolean interrupted=Thread.interrupted();
            try { cleanup(root); } finally { if(interrupted) Thread.currentThread().interrupt(); }
        }
    }
    private static void cleanup(Path root) {
        Path temporary=Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if(!root.isAbsolute() || !root.getParent().equals(temporary) || !root.getFileName().toString().startsWith("salt-netease-web-")) return;
        for(int attempt=0;attempt<20;attempt++) {
            try(var paths=Files.walk(root)) {
                for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) {
                    if(!path.normalize().startsWith(root)) return;
                    Files.deleteIfExists(path);
                }
                return;
            } catch(IOException ignored) {
                try { Thread.sleep(100); } catch(InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
            }
        }
    }
    private static AuthFailure failure(AuthFailure.Reason reason) { return AuthFailure.of(AuthFailure.Stage.NET_WEB,reason); }
}
