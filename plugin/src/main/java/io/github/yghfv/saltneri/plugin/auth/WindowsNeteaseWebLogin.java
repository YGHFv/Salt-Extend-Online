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
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit official web login in a fresh Edge profile; never imports an existing browser session. */
public final class WindowsNeteaseWebLogin {
    private WindowsNeteaseWebLogin() { }
    public static AccountCredentials login(RequestScope request) throws IOException {
        var clock=Clock.systemUTC();
        try (var client=new NeteaseQrClient()) {
            return NativeWindowsLogin.login(AccountPlatform.NETEASE,request,(output,scope)-> {
                try {
                    var root=BoundedJson.object(BoundedJson.parse(output));
                    String agent=BoundedJson.string(root.get("userAgent"));
                    return client.verifyWebCookies(readCookies(output,clock),agent,scope);
                } finally { Arrays.fill(output,(byte)0); }
            });
        } catch(IOException error) { throw AuthFailure.wrap(AuthFailure.Stage.NET_WEB,AuthFailure.Reason.RESPONSE,error); }
    }
    static List<ScopedCookies.Entry> readCookies(byte[] output, Clock clock) throws IOException {
        return readCookies(output, clock, true);
    }
    static List<ScopedCookies.Entry> readCookies(byte[] output, Clock clock, boolean requireCredential) throws IOException {
        return readCookies(output,clock,requireCredential,AccountPlatform.NETEASE);
    }
    static List<ScopedCookies.Entry> readCookies(byte[] output, Clock clock, boolean requireCredential,AccountPlatform platform) throws IOException {
        if(output.length>65536) throw failure(AuthFailure.Reason.RESPONSE);
        var root=BoundedJson.object(BoundedJson.parse(output));
        if(root.get("error")!=null) {
            throw failure(AuthFailure.webHelperReason(root.get("error")));
        }
        var rows=BoundedJson.array(root.get("cookies"));
        if(rows.size()>64) throw failure(AuthFailure.Reason.COOKIE);
        List<ScopedCookies.Entry> entries=new ArrayList<>(); Set<String> identities=new HashSet<>();
        for(Object row:rows) {
            var value=BoundedJson.object(row);
            String name=BoundedJson.string(value.get("name")), domain=BoundedJson.string(value.get("domain")), path=BoundedJson.string(value.get("path"));
            boolean hostOnly=!domain.startsWith("."); domain=domain.replaceFirst("^\\.","").toLowerCase(Locale.ROOT);
            if(!platform.allows(domain) || !identities.add(name+"\n"+domain+"\n"+path)) throw failure(AuthFailure.Reason.COOKIE);
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
        var jar=new ScopedCookies(platform,clock);
        try {
            jar.restore(entries);
            if(requireCredential && jar.value(platform.accountUri(),platform.loginCookie()).isEmpty()) throw failure(AuthFailure.Reason.CREDENTIAL);
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
        Path binary=WindowsWebLoginHelper.findBinary(System.getenv(),Files::isRegularFile);
        String script;
        try(var input=WindowsNeteaseWebLogin.class.getResourceAsStream("/auth/netease-web-login.ps1")) {
            if(input==null) throw failure(AuthFailure.Reason.HELPER_RESOURCE);
            script=new String(input.readAllBytes(),StandardCharsets.UTF_8)+"\n"+entryPoint+"\n";
        } catch(IOException error) { throw AuthFailure.wrap(AuthFailure.Stage.NET_WEB,AuthFailure.Reason.HELPER_RESOURCE,error); }
        Path root;
        try { root=Files.createTempDirectory("salt-netease-web-").toAbsolutePath().normalize(); }
        catch(IOException error) { throw failure(AuthFailure.Reason.PROFILE); }
        Process process=null;
        ExecutorService errorReader=null;
        Future<byte[]> errorOutput=null;
        byte[] output=null,stderr=null;
        var ioReason=AuthFailure.Reason.HELPER_IO;
        var timedOut=new AtomicBoolean();
        Set<ProcessHandle> children=ConcurrentHashMap.newKeySet();
        var watchdog=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"salt-netease-web-lifetime");t.setDaemon(true);return t;});
        try {
            var acl=Files.getFileAttributeView(root,AclFileAttributeView.class);
            if(acl==null) throw failure(AuthFailure.Reason.PROFILE);
            try {
                acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(root))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT).build()));
            } catch(IOException error) { throw failure(AuthFailure.Reason.PROFILE); }
            // The ACL-owned file contains code only; credentials still travel through pipes.
            Path helper=root.resolve("helper.ps1");
            Files.writeString(helper,script,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            request.check();
            ioReason=AuthFailure.Reason.HELPER_START;
            process=new ProcessBuilder(WindowsWebLoginHelper.command(binary,helper)).start();
            ioReason=AuthFailure.Reason.HELPER_IO;
            Process running=process; Thread owner=Thread.currentThread();
            errorReader=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"salt-netease-web-stderr");t.setDaemon(true);return t;});
            errorOutput=errorReader.submit(()->{
                try(var stream=running.getErrorStream()) {
                    byte[] prefix=stream.readNBytes(8192),buffer=new byte[4096];
                    try {while(stream.read(buffer)!=-1) { }}
                    catch(IOException error) {Arrays.fill(prefix,(byte)0);throw error;}
                    finally {Arrays.fill(buffer,(byte)0);}
                    return prefix;
                }
            });
            watchdog.scheduleWithFixedDelay(()->{
                running.descendants().forEach(children::add);
                try { request.check(); if(owner.isInterrupted()) throw new CancellationException(); }
                catch(CancellationException stopped) { children.forEach(ProcessHandle::destroyForcibly); running.destroyForcibly(); }
            },0,100,TimeUnit.MILLISECONDS);
            watchdog.schedule(()->{timedOut.set(true);children.forEach(ProcessHandle::destroyForcibly);running.destroyForcibly();},timeoutMillis,TimeUnit.MILLISECONDS);
            try(var input=process.getOutputStream()) { input.write((root+"\n").getBytes(StandardCharsets.UTF_8)); }
            output=process.getInputStream().readNBytes(65537);
            request.check();
            if(timedOut.get()) throw failure(AuthFailure.Reason.TIMEOUT);
            if(output.length>65536) throw failure(AuthFailure.Reason.HELPER_OUTPUT);
            if(!process.waitFor(5,TimeUnit.SECONDS)) throw failure(AuthFailure.Reason.HELPER_EXIT);
            try {stderr=errorOutput.get(1,TimeUnit.SECONDS);}
            catch(ExecutionException | TimeoutException ignored) { }
            if(process.exitValue()!=0) throw WindowsWebLoginHelper.exited(process.exitValue(),stderr==null?new byte[0]:stderr);
            if(output.length==0) throw failure(AuthFailure.Reason.HELPER_OUTPUT);
            byte[] result=output;output=null;return result;
        } catch(IOException error) {
            request.check();
            if(timedOut.get()) throw failure(AuthFailure.Reason.TIMEOUT);
            throw AuthFailure.wrap(AuthFailure.Stage.NET_WEB,ioReason,error);
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new CancellationException("Web login cancelled");
        } finally {
            watchdog.shutdownNow();
            if(process!=null) { process.descendants().forEach(children::add); process.destroyForcibly(); }
            children.forEach(ProcessHandle::destroyForcibly);
            if(errorReader!=null) errorReader.shutdownNow();
            if(output!=null) Arrays.fill(output,(byte)0);
            if(stderr!=null) Arrays.fill(stderr,(byte)0);
            if(errorOutput!=null && stderr==null) {
                try {Arrays.fill(errorOutput.get(1,TimeUnit.SECONDS),(byte)0);}
                catch(InterruptedException stopped) {Thread.currentThread().interrupt();}
                catch(ExecutionException | TimeoutException ignored) { }
            }
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
