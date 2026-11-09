package io.github.yghfv.saltneri.plugin.applemusic;

import io.github.yghfv.saltneri.plugin.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Explicit single-song intents run on the official client; UIA intent is verified by SMTC. */
public final class AppleMusicPlayback implements AutoCloseable {
    private final BooleanSupplier permitted;
    private final Consumer<String> status;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"salt-apple-play");t.setDaemon(true);return t;});
    private volatile Process current;
    private volatile boolean closed;
    private volatile long revision;
    public AppleMusicPlayback(BooleanSupplier permitted,Consumer<String> status) {this.permitted=permitted;this.status=status;}
    public synchronized void play(String id,String title,String artist) {
        if(closed || !permitted.getAsBoolean())throw new IllegalStateException("External playback unavailable");
        if(!id.matches("[a-z]{2}:[1-9][0-9]{0,18}"))throw new IllegalArgumentException("Invalid Apple song");
        if(title==null || title.isBlank() || title.length()>512 || artist==null || artist.length()>512)
            throw new IllegalArgumentException("Invalid Apple metadata");
        if(current!=null || busy) {status.accept("Apple Music 正在切歌，请稍后重试");return;}
        busy=true;
        long captured=revision;
        worker.execute(()->{try {run(id,title,artist,captured);} finally {busy=false;}});
    }
    private volatile boolean busy;
    private void run(String id,String title,String artist,long captured) {
        Path root=null;
        try {
            if(!System.getProperty("os.name","").startsWith("Windows"))throw AppleMusicHttp.failure("WINDOWS_REQUIRED");
            root=Files.createTempDirectory("salt-apple-play-").toAbsolutePath().normalize();
            for(String resource:List.of("play.ps1","lib/am-common.ps1","lib/am-smtc.ps1","lib/am-uia.ps1","lib/am-play.ps1")) {
                Path file=root.resolve(resource);Files.createDirectories(file.getParent());
                try(var input=AppleMusicPlayback.class.getResourceAsStream("/applemusic/"+resource)) {
                    if(input==null)throw AppleMusicHttp.failure("HELPER_MISSING");
                    Files.copy(input,file);
                }
            }
            Path powershell=Path.of(System.getenv("SystemRoot"),"System32","WindowsPowerShell","v1.0","powershell.exe");
            synchronized(this) {
                if(closed || revision!=captured || !permitted.getAsBoolean())return;
                current=new ProcessBuilder(powershell.toString(),"-NoProfile","-NonInteractive","-WindowStyle","Hidden",
                        "-File",root.resolve("play.ps1").toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            }
            Process process=current;
            try(var output=process.getOutputStream()) {
                output.write(BoundedJson.encode(Map.of("id",id,"title",title,"artist",artist)));output.write('\n');
            }
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(75);
            while(!process.waitFor(100,TimeUnit.MILLISECONDS)) {
                if(closed || revision!=captured || !permitted.getAsBoolean() || System.nanoTime()>deadline) {process.destroyForcibly();throw AppleMusicHttp.failure("CANCELLED_OR_TIMEOUT");}
            }
            byte[] bytes=process.getInputStream().readNBytes(8193);
            if(bytes.length>8192 || process.exitValue()!=0)throw AppleMusicHttp.failure("HELPER");
            if(closed || revision!=captured || !permitted.getAsBoolean())return;
            String[] lines=new String(bytes,StandardCharsets.UTF_8).strip().split("\\R");
            var result=BoundedJson.object(BoundedJson.parse(lines[lines.length-1].getBytes(StandardCharsets.UTF_8)));
            if(!Boolean.TRUE.equals(result.get("ok"))) {
                String stage=result.get("stage") instanceof String s && s.matches("[A-Z0-9_]{1,64}")?s:"HELPER";
                status.accept(switch(stage) {
                    case "APP_NOT_INSTALLED" -> "Apple Music 客户端未安装，请安装并登录 Windows Apple Music 后播放";
                    case "APP_LAUNCH_FAILED" -> "Apple Music 客户端启动失败，请手动打开并登录后重试";
                    case "APP_WINDOW_TIMEOUT" -> "Apple Music 客户端窗口尚未就绪，请完成首次启动或登录后重试";
                    default -> "Apple Music 播放未完成 ["+stage+"]";
                });
            }
        } catch(Exception error) {
            if(!closed && revision==captured)status.accept("Apple Music 播放未完成，请确认官方客户端已安装并登录");
        } finally {
            synchronized(this) {if(current!=null && current.isAlive())current.destroyForcibly();current=null;}
            if(root!=null)try(var paths=Files.walk(root)) {
                for(Path file:paths.sorted(Comparator.reverseOrder()).toList()) {
                    if(!file.toAbsolutePath().normalize().startsWith(root))throw new IOException("Unsafe helper path");
                    Files.deleteIfExists(file);
                }
            } catch(IOException ignored) {}
        }
    }
    public synchronized void cancel() {revision++;if(current!=null)current.destroyForcibly();}
    public synchronized void close() {closed=true;cancel();worker.shutdownNow();}
}
