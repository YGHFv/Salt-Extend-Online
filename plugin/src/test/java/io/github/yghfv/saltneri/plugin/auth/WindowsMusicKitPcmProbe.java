package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.applemusic.*;
import io.github.yghfv.saltneri.plugin.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.sun.net.httpserver.HttpServer;

/** Explicit real-browser AudioWorklet test. Synthetic PCM only; no user account or audible output. */
public final class WindowsMusicKitPcmProbe {
    private static Map<String,Object> eval(WindowsEdgeProcess browser,String session,String expression,boolean promise) throws Exception {
        var reply=browser.call("Runtime.evaluate",Map.of("expression",expression,"awaitPromise",promise,"returnByValue",true,"userGesture",true),session,25000);
        if(reply.containsKey("exceptionDetails"))throw new AssertionError("Browser JavaScript exception");
        return BoundedJson.object(BoundedJson.object(reply.get("result")).get("value"));
    }
    public static void main(String[] args) throws Exception {
        if(args.length<1 || args.length>2 || !args[0].equals("--explicit-headless-fixture"))throw new IllegalArgumentException("Explicit fixture required");
        HttpServer page=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        page.createContext("/",exchange->{try(exchange){byte[] body="<html><head></head><body></body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}});
        page.start();
        try(var lifetime=RequestScope.playback(()->true);var browser=WindowsEdgeProcess.open(lifetime,true,null)) {
            var target=WindowsEdgeWebLogin.pages(browser).getFirst();
            String session=BoundedJson.string(browser.call("Target.attachToTarget",Map.of("targetId",target.get("targetId"),"flatten",true)).get("sessionId"));
            browser.call("Page.navigate",Map.of("url","http://127.0.0.1:"+page.getAddress().getPort()+"/"),session);
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(true) {
                try {if(Boolean.TRUE.equals(eval(browser,session,"({ready:location.protocol==='http:'&&document.readyState==='complete'})",false).get("ready")))break;}
                catch(NeteaseDeviceCollector.ContextUnavailable navigation) {}
                if(System.nanoTime()>end)throw new AssertionError("Fixture page timeout");
                Thread.sleep(20);
            }
            String script;
            try(var input=WindowsMusicKitPcmProbe.class.getResourceAsStream("/musickit/pcm.js")) {script=new String(input.readAllBytes(),StandardCharsets.UTF_8);}
            eval(browser,session,"(async()=>{"+script+";"+
                "const count=48000*16, wave=new ArrayBuffer(44+count*4), v=new DataView(wave);"+
                "const text=(p,s)=>{for(let i=0;i<s.length;i++)v.setUint8(p+i,s.charCodeAt(i));};"+
                "text(0,'RIFF');v.setUint32(4,wave.byteLength-8,true);text(8,'WAVEfmt ');v.setUint32(16,16,true);v.setUint16(20,1,true);v.setUint16(22,2,true);"+
                "v.setUint32(24,48000,true);v.setUint32(28,192000,true);v.setUint16(32,4,true);v.setUint16(34,16,true);text(36,'data');v.setUint32(40,count*4,true);"+
                "for(let i=0;i<count;i++){const n=Math.round(12000*Math.sin(i*2*Math.PI*440/48000));v.setInt16(44+i*4,n,true);v.setInt16(46+i*4,n,true);}"+
                "const url=URL.createObjectURL(new Blob([wave],{type:'audio/wav'}));"+
                "const mk={isAuthorized:true,nowPlayingItem:{id:'123',playbackType:2},currentPlaybackDuration:16,"+
                "async setQueue(){},async play(){if(!this.audio){this.audio=document.createElement('audio');this.audio.id='apple-music-player';document.body.appendChild(this.audio);this.audio.src=url;}await this.audio.play();},"+
                "async pause(){this.audio?.pause();},async seekToTime(t){this.audio.currentTime=t;}};"+
                "globalThis.MusicKit={async configure(){},getInstance:()=>mk,PlaybackType:{unencryptedFull:2,encryptedFull:3}};"+
                "return saltApple.init({song:'123',duration:16000,userToken:'synthetic',developerToken:'synthetic',storefront:'us'});})()",true);
            var ready=eval(browser,session,"({ready:saltApple.ready,error:saltApple.error})",false);
            if(!Boolean.TRUE.equals(ready.get("ready")))throw new AssertionError("PCM init: "+ready);
            for(long frame: new long[]{0,48000*7,48000}) {
                var seek=eval(browser,session,"saltApple.seek("+frame+")",true);
                if(!"".equals(seek.get("error")))throw new AssertionError("PCM seek failed");
                java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(output.size()<192000) {
                    var result=eval(browser,session,"saltApple.pull(32768)",false);
                    if(!"".equals(result.get("error")))throw new AssertionError("PCM pull failed");
                    output.write(Base64.getDecoder().decode((String)result.get("pcm")));
                    if(System.nanoTime()>deadline)throw new AssertionError("Browser PCM timed out");
                    Thread.sleep(10);
                }
                eval(browser,session,"saltApple.pause()",true);
                byte[] pcm=output.toByteArray();long nonzero=0,energy=0;
                var data=java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                while(data.remaining()>1){int sample=data.getShort();if(sample!=0)nonzero++;energy+=(long)sample*sample;}
                if(nonzero<pcm.length/8 || energy==0)throw new AssertionError("Browser returned silence");
                System.out.println("PASS: real headless AudioWorklet PCM, seek "+frame/48000+"s, samples="+pcm.length/4);
            }
            var drm=eval(browser,session,"(async()=>{const r={};for(const key of ['com.widevine.alpha','com.microsoft.playready.recommendation'])try{"+
                "await navigator.requestMediaKeySystemAccess(key,[{initDataTypes:['cenc'],audioCapabilities:[{contentType:'audio/mp4;codecs=\"mp4a.40.2\"'}]}]);r[key]=true;}catch(_){r[key]=false;}return r;})()",true);
            System.out.println("Browser EME availability (synthetic capability probe): "+drm);
            if(args.length==2) io.github.yghfv.saltneri.plugin.host.AppleMusicBassProbe.verify(new AppleMusicPcmSource.Decoder() {
                boolean running;
                public void seek(long frame) throws java.io.IOException {try{eval(browser,session,"saltApple.seek("+frame+")",true);running=true;}catch(Exception error){throw new java.io.IOException(error);}}
                public byte[] pull(int maximum) throws java.io.IOException {try{
                    if(!running){eval(browser,session,"saltApple.resume()",true);running=true;}
                    var value=eval(browser,session,"saltApple.pull("+Math.min(maximum,32768)+")",false);
                    return Base64.getDecoder().decode((String)value.get("pcm"));
                }catch(Exception error){throw new java.io.IOException(error);}}
                public void pause() throws java.io.IOException {try{eval(browser,session,"saltApple.pause()",true);running=false;}catch(Exception error){throw new java.io.IOException(error);}}
                public void close() {}
            },args[1]);
        } finally {page.stop(0);}
    }
}
