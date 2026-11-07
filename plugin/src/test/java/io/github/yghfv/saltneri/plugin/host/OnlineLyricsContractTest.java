package io.github.yghfv.saltneri.plugin.host;

import io.github.yghfv.saltneri.plugin.OnlineLyricsExtension;
import io.github.yghfv.saltneri.plugin.netease.*;
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real host lyric parser and public callback, synthetic sources, never initializes playback. */
public final class OnlineLyricsContractTest {
    private static int checks;
    private static void check(boolean value) { checks++; if(!value) throw new AssertionError("Host lyric check "+checks); }
    private static MediaItem item(String id,String path) { return new MediaItem("Fixture","Artist","","",path,id,0,"",0,0,false,false); }
    public static void main(String[] args) throws Exception {
        var extension=new OnlineLyricsExtension(); var active=new AtomicBoolean(true); var calls=new AtomicInteger();
        String text=NeteaseLyricsText.convert(NeteaseLyricsContractTest.fixture());
        var nativeItem=item("saltneri:fixture","saltneri://media/fixture");
        check(extension.onBeforeLoadLyrics(nativeItem)==null);
        check(extension.onBeforeLoadLyrics(item("local","C:\\Music\\fixture.flac"))==null);
        try(var binding=OnlineLyrics.bind(nativeItem.getId(),nativeItem.getPath(),active::get,r->{calls.incrementAndGet();return text;})) {
            check(calls.get()==0);
            check(extension.onBeforeLoadLyrics(item(nativeItem.getId(),"wrong"))==null && calls.get()==0);
            check(extension.onBeforeLoadLyrics(nativeItem).equals(text));
            check(extension.onBeforeLoadLyrics(nativeItem).equals(text) && calls.get()==1);
            binding.path("new-cover.flac"); check(extension.onBeforeLoadLyrics(item(nativeItem.getId(),"new-cover.flac")).equals(text));
            var parse=Class.forName("androidx.compose.ui.\u0b0f").getMethod("\u037f",String.class);
            var doc=parse.invoke(null,extension.onBeforeLoadLyrics(nativeItem));
            var lines=(List<?>)doc.getClass().getMethod("\u0528").invoke(doc);
            check(lines.size()==2);
            Object first=lines.getFirst(); String rendered=first.toString();
            check(rendered.contains("startTime=1000") && rendered.contains("endTime=2500") && rendered.contains("pureMainText='OneTwo'"));
            check(rendered.contains("pureSubText=Translation"));
            check(rendered.contains("endTime=1400, text=One") && rendered.contains("startTime=1600, endTime=2500, text=Two"));
            active.set(false); check(extension.onBeforeLoadLyrics(nativeItem)==null);
        }
        active.set(true); check(extension.onBeforeLoadLyrics(nativeItem)==null);
        var started=new CountDownLatch(1); var release=new CountDownLatch(1);
        var binding=OnlineLyrics.bind("saltneri:late","late",active::get,r->{
            started.countDown(); try { release.await(3,TimeUnit.SECONDS); } catch(InterruptedException error) { Thread.currentThread().interrupt(); }
            r.check(); return text;
        });
        try(var worker=Executors.newSingleThreadExecutor()) {
            var pending=worker.submit(()->extension.onBeforeLoadLyrics(item("saltneri:late","late")));
            check(started.await(3,TimeUnit.SECONDS)); binding.close(); release.countDown();
            check(pending.get(3,TimeUnit.SECONDS)==null && OnlineLyrics.load("saltneri:late","late")==null);
        } finally { release.countDown(); binding.close(); }
        var bindings=new ArrayList<OnlineLyrics.Binding>(); var cacheCalls=new AtomicInteger();
        try {
            for(int i=0;i<33;i++) {
                String id="saltneri:cache:"+i;
                bindings.add(OnlineLyrics.bind(id,id,active::get,r->{cacheCalls.incrementAndGet();return text;}));
                check(OnlineLyrics.load(id,id).equals(text));
            }
            check(cacheCalls.get()==33); OnlineLyrics.load("saltneri:cache:0","saltneri:cache:0"); check(cacheCalls.get()==34);
        } finally { bindings.forEach(OnlineLyrics.Binding::close); }
        var attempts=new AtomicInteger();
        try(var failure=OnlineLyrics.bind("saltneri:error","error",active::get,r->{if(attempts.incrementAndGet()==1) throw new java.io.IOException("fixture");return text;})) {
            check(OnlineLyrics.load("saltneri:error","error")==null);
            check(OnlineLyrics.load("saltneri:error","error").equals(text));
        }
        try(var resource=OnlineLyricsExtension.class.getClassLoader().getResourceAsStream("META-INF/extensions.idx")) {
            check(resource!=null && new String(resource.readAllBytes(),java.nio.charset.StandardCharsets.US_ASCII).strip().equals(OnlineLyricsExtension.class.getName()));
        }
        var manager=(org.pf4j.PluginManager)java.lang.reflect.Proxy.newProxyInstance(OnlineLyricsContractTest.class.getClassLoader(),new Class<?>[]{org.pf4j.PluginManager.class},
                (proxy,method,values)->switch(method.getName()) {
                    case "getPlugins" -> List.of();
                    case "getRuntimeMode" -> org.pf4j.RuntimeMode.DEPLOYMENT;
                    case "getExtensionFactory" -> new org.pf4j.DefaultExtensionFactory();
                    default -> throw new AssertionError("Unexpected PF4J operation: "+method.getName());
                });
        var finder=new org.pf4j.LegacyExtensionFinder(manager); finder.setCheckForExtensionDependencies(true);
        var found=finder.find(com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.class);
        check(found.stream().anyMatch(w->w.getExtension() instanceof OnlineLyricsExtension));
        System.out.println("PASS: "+checks+" exact native lyric bindings, real Salt word/translation/gap parser, bounded cache, revocation and extension registration; no audio/network");
    }
}
