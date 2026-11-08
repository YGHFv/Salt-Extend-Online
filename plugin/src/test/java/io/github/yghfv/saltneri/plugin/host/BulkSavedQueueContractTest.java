package io.github.yghfv.saltneri.plugin.host;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.library.*;
import io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.*;
import io.github.yghfv.saltneri.plugin.transport.*;
import java.nio.file.*;
import java.time.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Operation counts, full native queue semantics and restart safety; no user data or platform traffic. */
public final class BulkSavedQueueContractTest {
    private static int checks;
    private static void check(boolean ok,String message) {checks++;if(!ok)throw new AssertionError(message);}
    private static List<Catalog.Item> rows(int count) {
        var rows=new ArrayList<Catalog.Item>();
        for(int i=1;i<=count;i++) rows.add(new Catalog.Item(Catalog.Kind.TRACK,""+i,"Fixture "+i,"Artist",1000,true));
        return rows;
    }
    private static long files(Path root) throws Exception {try(var files=Files.list(root)){return files.count();}}
    private static Audio audio(SavedTrackStore.Entry entry) {
        Instant expiry=Instant.now().plusSeconds(600);
        return new Audio(TrackKey.netease(entry.target().id()),new Salt1193PlaybackSession.Metadata(entry.title(),entry.artist(),"",""),
                new MediaLease(MediaLease.Platform.NETEASE,URI.create("https://m7.music.126.net/fixture"),Map.of(),"fixture",expiry,100),expiry,entry.target());
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("salt-bulk-saved-").toRealPath();var rows=rows(1000);
        List<String> paths;long fresh,warm;
        try(var store=new SavedTrackStore(root)) {
            long start=System.nanoTime();var entries=store.saveAll(AccountPlatform.NETEASE,rows,()->true);fresh=(System.nanoTime()-start)/1000000;
            check(entries.size()==1000 && files(root)==1,"Fresh bulk queue did not use exactly one metadata file");
            paths=entries.stream().map(SavedTrackStore.Entry::path).toList();
            for(int i=0;i<1000;i++) {
                var entry=entries.get(i);
                check(entry.target().id().equals(""+(i+1)),"Order changed");
                check(store.owns(entry.trackId(),entry.path()) && PlaybackCoverFile.isPath(entry.path()) && !Files.exists(Path.of(entry.path())),"Lazy owned cover path rejected/materialized");
            }
            start=System.nanoTime();check(store.saveAll(AccountPlatform.NETEASE,rows,()->true).equals(entries),"Repeat changed identities");warm=(System.nanoTime()-start)/1000000;
            check(files(root)==1,"Warm queue wrote another batch");
            entries.getFirst().cover().replace(PlaybackCoverContractTest.png());entries.getFirst().cover().ensureFile();
            check(Files.size(Path.of(paths.getFirst()))>42,"Ensure erased retained cover");
        }
        try(var store=new SavedTrackStore(root)) {
            check(store.entries().size()==1000 && files(root)==2,"Restart eagerly materialized covers or lost identities");
            check(store.saveAll(AccountPlatform.NETEASE,rows,()->true).stream().map(SavedTrackStore.Entry::path).toList().equals(paths),"Restart changed queue paths");
            // Legacy single-track records coexist without metadata or ID churn.
            var row=rows(1001).getLast();var legacy=store.save(new Target(AccountPlatform.NETEASE,row.target()),row);
            check(store.saveAll(AccountPlatform.NETEASE,List.of(row,row),()->true).equals(List.of(legacy,legacy)),"Legacy duplicate changed");
            check(files(root)==3,"Legacy save created an empty cover");
        }
        try(var store=new SavedTrackStore(root)) {check(store.entries().size()==1001,"Mixed legacy/batch restart failed");}
        cancellation();corruption();runtime();
        System.out.println("PASS: "+checks+" bulk durable queue checks; 1000 identities fresh="+fresh+"ms / repeated="+warm+"ms, one metadata file, no eager covers; synthetic only");
    }
    private static void cancellation() throws Exception {
        Path root=Files.createTempDirectory("salt-bulk-cancel-").toRealPath();var rows=rows(1000);var polls=new AtomicInteger();
        try(var store=new SavedTrackStore(root)) {
            try {store.saveAll(AccountPlatform.NETEASE,rows,()->polls.incrementAndGet()<1200);throw new AssertionError("Cancelled save succeeded");}
            catch(java.util.concurrent.CancellationException expected){checks++;}
            check(store.entries().isEmpty() && files(root)==0,"Cancelled batch left visible records/files");
            var invalid=new ArrayList<>(rows);invalid.set(999,new Catalog.Item(Catalog.Kind.TRACK,"1000","Bad\nmetadata","Artist",1,true));
            try {store.saveAll(AccountPlatform.NETEASE,invalid,()->true);throw new AssertionError("Invalid batch succeeded");}
            catch(IllegalArgumentException expected){checks++;}
            check(store.entries().isEmpty() && files(root)==0,"Rejected batch published partial entries");
            store.saveAll(AccountPlatform.NETEASE,rows,()->true);check(files(root)==1,"Retry failed");
        }
    }
    private static void corruption() throws Exception {
        Path root=Files.createTempDirectory("salt-bulk-corrupt-").toRealPath();Path batch;
        try(var store=new SavedTrackStore(root)) {store.saveAll(AccountPlatform.NETEASE,rows(10),()->true);}
        try(var files=Files.list(root)){batch=files.findFirst().orElseThrow();}
        byte[] bytes=Files.readAllBytes(batch);bytes[bytes.length-1]^=1;Files.write(batch,bytes);
        try(var store=new SavedTrackStore(root)) {
            check(store.entries().isEmpty(),"Corrupt atomic batch partially restored");
            var row=rows(11).getLast();store.save(new Target(AccountPlatform.NETEASE,row.target()),row);
        }
        try(var store=new SavedTrackStore(root)) {check(store.entries().size()==1,"Corrupt batch suppressed healthy record");}
    }
    private static void runtime() throws Exception {
        var profile=Salt1193Probe.inspect(BulkSavedQueueContractTest.class.getClassLoader(),"1.19.3","Steam");
        Path root=Files.createTempDirectory("salt-bulk-runtime-").toRealPath();var binding=new LazyPlaylistContractTest.Binding();
        var queued=new ArrayList<Object>();var resolves=new AtomicInteger();var fail=new AtomicBoolean();var active=new AtomicBoolean(true);
        try(var store=new SavedTrackStore(root);var runtime=new Salt1193DiagnosticRuntime(profile,binding,
                TransportContractTest.offlineServer(Clock.systemUTC(),()->true),
                new EngineLinkContractTest.FakeHost(),new EngineLinkContractTest.Hooks(),new Salt1193PlaybackSession.QueueSink() {
                    public void addToPlay(Object item){throw new AssertionError("Must replace full queue");}
                    public int firstIndex(int size){check(size==1000,"Random selection used partial list");return 937;}
                    public void replace(List<Object> items,int first){check(first==937 && items.size()==1000,"Native random order lost");queued.addAll(items);}
                },()->true)) {
            runtime.savedTracks(store,new SavedTrackSession.Resolver() {
                public Object revision(AccountPlatform platform){return "fixture";}
                public Audio resolve(SavedTrackStore.Entry entry,io.github.yghfv.saltneri.plugin.net.RequestScope request) throws Exception {
                    resolves.incrementAndGet();check(entry.target().id().equals("938"),"Wrong first song resolved");
                    check(store.entries().size()==1000 && files(root)==1,"Audio resolution preceded durable transaction/eager covers");
                    if(fail.get())throw new java.io.IOException("Fixture failed");
                    return audio(entry);
                }
            });
            var list=new LibraryController.Playlist(AccountPlatform.NETEASE,rows(1000),null);
            fail.set(true);
            try {runtime.playPlaylist(list,active::get);throw new AssertionError("First failure accepted");}catch(java.io.IOException expected){checks++;}
            check(queued.isEmpty(),"First failure destroyed old queue");
            fail.set(false);runtime.playPlaylist(list,active::get);
            check(queued.size()==1000 && resolves.get()==2 && files(root)==2,"Full queue fetched every track or materialized all covers");
            for(int i=0;i<1000;i++) {var fields=profile.mediaFactory().read(queued.get(i));check(fields[1].equals("Fixture "+(i+1)) && store.owns(fields[0],fields[5]),"Queue order/identity lost");}
            String[] first=profile.mediaFactory().read(queued.get(937));
            check(binding.pending.get(first[5]).resolve()!=null && resolves.get()==2,"Warm first song redundantly resolved");
            runtime.playPlaylist(list,active::get);check(queued.size()==2000 && resolves.get()==2 && files(root)==2,"Repeat play-all performed redundant work");
            active.set(false);
            try {runtime.playPlaylist(list,active::get);throw new AssertionError("Retired queue accepted");}catch(java.util.concurrent.CancellationException expected){checks++;}
            check(queued.size()==2000,"Cancellation replaced queue");
        }
        try(var store=new SavedTrackStore(root);var server=TransportContractTest.offlineServer(Clock.systemUTC(),()->true);
                var session=new SavedTrackSession(binding,server,()->true,new SavedTrackSession.Resolver() {
                    public Object revision(AccountPlatform platform){return "fixture";}
                    public Audio resolve(SavedTrackStore.Entry entry,io.github.yghfv.saltneri.plugin.net.RequestScope r){return audio(entry);}
                })) {
            store.entries().forEach(session::register);var fields=profile.mediaFactory().read(queued.getFirst());
            check(!Files.exists(Path.of(fields[5])) && PlaybackCoverFile.isPath(fields[5]),"Unopened restored path rejected");
            check(binding.pending.get(fields[5]).resolve()!=null && Files.size(Path.of(fields[5]))==42,"Restored unopened song failed to materialize on open");
        }
    }
}
