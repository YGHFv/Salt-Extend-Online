package io.github.yghfv.saltneri.plugin.host;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.library.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

/** Real native queue serialization, not a hand-reconstructed media-item roundtrip. */
public final class SavedArtistRestoreTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static Item row(String target,String title,String artist){return new Item(Kind.TRACK,target,title,artist,60000,true);}
    private static void ready(LibraryController library)throws Exception{
        long until=System.nanoTime()+3_000_000_000L;
        while(System.nanoTime()<until){var state=library.snapshot();if(state.get("loaded").equals(true) && state.get("loadingAll").equals(false))return;Thread.sleep(10);}
        throw new AssertionError("Catalog fixture timed out");
    }
    public static void main(String[] args)throws Exception{
        var profile=Salt1193Probe.inspect(SavedArtistRestoreTest.class.getClassLoader(),"1.19.3","Steam");
        Path root=Files.createTempDirectory("salt-author-restore-").toRealPath();var blank=row("BV17x411w7KC","Original title","");
        var filled=row(blank.target(),"Upstream changed title","Uploader");String path,id;
        try(var store=new SavedTrackStore(root)){
            var legacy=store.save(new OnlinePlaybackController.Target(AccountPlatform.BILIBILI,blank.target()),blank);path=legacy.path();id=legacy.trackId();
            var repaired=store.saveAll(AccountPlatform.BILIBILI,List.of(filled),()->true).getFirst();
            check(repaired==legacy && repaired.artist().equals("Uploader"),"Live binding retained blank author");
            check(repaired.title().equals("Original title") && repaired.trackId().equals(id) && repaired.path().equals(path),"Repair changed title/identity");
            check(store.saveAll(AccountPlatform.BILIBILI,List.of(row(blank.target(),"Changed","Foreign")),()->true).getFirst().artist().equals("Uploader"),"Nonblank author overwritten");
        }
        try(var store=new SavedTrackStore(root)){
            var entry=store.entries().getFirst();check(entry.artist().equals("Uploader") && entry.trackId().equals(id),"Legacy plus patch restart lost author");
            Object item=profile.mediaFactory().create(entry.trackId(),entry.title(),entry.artist(),"","",entry.path());
            Object restored=roundtrip(item);
            check(Arrays.equals(profile.mediaFactory().read(item),profile.mediaFactory().read(restored)),"Native disk queue roundtrip lost metadata");
        }
        Path batchRoot=Files.createTempDirectory("salt-author-batch-").toRealPath();
        try(var store=new SavedTrackStore(batchRoot)){
            store.saveAll(AccountPlatform.BILIBILI,List.of(blank),()->true);
            store.save(new OnlinePlaybackController.Target(AccountPlatform.BILIBILI,blank.target()),filled);
        }
        try(var store=new SavedTrackStore(batchRoot)){check(store.entries().getFirst().artist().equals("Uploader"),"Batch-only repair depended on file enumeration order");}
        workflow(profile);
        System.out.println("PASS: "+checks+" missing-author enrichment/title preservation/batch+legacy restart/actual native queue serialization and visible-author propagation checks");
    }
    private static Object roundtrip(Object media)throws Exception{
        Class<?> queueItem=Class.forName("com.xuncorp.spc.core.queue.\u037f");
        Object wrapper=queueItem.getConstructor(int.class,Object.class).newInstance(137,media);
        Class<?> conversions=Class.forName("com.xuncorp.voxzen.service.queue.PlayQueueSaveKt");
        Object saved=conversions.getMethod("toPlayQueueSave",List.class).invoke(null,List.of(wrapper));
        var bytes=new ByteArrayOutputStream();try(var out=new ObjectOutputStream(bytes)){out.writeObject(saved);}
        Object decoded;try(var in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))){decoded=in.readObject();}
        var queue=(List<?>)conversions.getMethod("toPlayQueue",saved.getClass()).invoke(null,decoded);
        check(queue.size()==1 && queueItem.getMethod("\u037f").invoke(queue.getFirst()).equals(137),"Queue wrapper ID/order lost");
        return queueItem.getMethod("\u0528").invoke(queue.getFirst());
    }
    private static void workflow(Salt1193Probe.Result profile)throws Exception{
        try(var library=new LibraryController(p->null,()->true,t->{},()->{},Clock.systemUTC(),account->(q,k,s,r)->{
            if(q.view()!=View.COLLECTION)return new Page("Subscriptions",List.of(new Item(Kind.COLLECTION,"30","Set","Collection uploader",21,true,null,99)),false,Instant.MAX,1,1);
            var items=new ArrayList<Item>();for(int i=(q.page()-1)*20;i<Math.min(q.page()*20,21);i++)items.add(row("BV"+String.format(Locale.ROOT,"%010d",i),"Video "+i,i==10?"Actual author":""));
            return new Page("Set",items,q.page()==1,Instant.MAX,2,21);
        })){
            library.browse(AccountPlatform.BILIBILI,View.SAVED);ready(library);
            var folders=(List<?>)library.snapshot().get("items");library.choose((String)((Map<?,?>)folders.getFirst()).get("token"));ready(library);
            var first=(Map<?,?>)((List<?>)library.snapshot().get("items")).getFirst();
            library.saveRow((String)first.get("token"),(target,row)->check(row.subtitle().equals("Collection uploader"),"Context-menu save lost visible fallback author"));
            library.requestPlaylist();ready(library);var list=library.takePlaylist();
            check(list.items().size()==21,"Full list changed");
            for(int i=0;i<21;i++){
                var row=list.items().get(i);check(row.subtitle().equals(i==10?"Actual author":"Collection uploader"),"Queue did not receive displayed author");
                Object item=profile.mediaFactory().create("fixture:"+i,row.title(),row.subtitle(),"","","fixture-path-"+i);
                check(profile.mediaFactory().read(roundtrip(item))[2].equals(row.subtitle()),"Restored author changed");
            }
        }
    }
}
