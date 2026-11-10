package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Menu reads are asynchronous; only explicit clicks write to the selected platform account. */
public final class PlatformPlaylistController implements AutoCloseable {
    public record Choice(String id,String writeId,String title) { }
    public record Entry(Choice choice,boolean checked,boolean contains) { }
    public record Snapshot(String token,List<Entry> entries,boolean loading,String status,boolean subscriptionKnown,boolean subscribed) {
        public Snapshot {entries=List.copyOf(entries);}
        public static Snapshot empty(){return new Snapshot("",List.of(),false,"",false,false);}
    }
    public interface Gateway {
        List<Choice> choices(AccountCredentials account,RequestScope request) throws IOException;
        Set<String> members(AccountCredentials account,Choice choice,RequestScope request) throws IOException;
        boolean subscribed(AccountCredentials account,Catalog.Item item,RequestScope request) throws IOException;
        void add(AccountCredentials account,Catalog.Item song,Choice choice,RequestScope request) throws IOException;
        void subscribe(AccountCredentials account,Catalog.Item item,RequestScope request) throws IOException;
    }
    private record Membership(Set<String> songs,Instant until) { }
    private final Gateway gateway;
    private final Function<AccountPlatform,AccountCredentials> accounts;
    private final BooleanSupplier authorized;
    private final Predicate<String> currentRow;
    private final Consumer<AccountPlatform> changed;
    private final Consumer<String> notify;
    private final Clock clock;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"salt-platform-playlists");t.setDaemon(true);return t;});
    private final ExecutorService readers=Executors.newFixedThreadPool(3,r->{var t=new Thread(r,"salt-playlist-members");t.setDaemon(true);return t;});
    private final LinkedHashMap<String,Membership> membership=new LinkedHashMap<>(32,0.75f,true);
    private AccountCredentials owner;
    private List<Choice> cachedChoices=List.of();
    private Instant choicesUntil=Instant.MIN;
    private volatile Snapshot state=Snapshot.empty();
    private volatile boolean closed;
    private volatile long revision;
    public PlatformPlaylistController(Gateway gateway,Function<AccountPlatform,AccountCredentials> accounts,BooleanSupplier authorized,
                                      Predicate<String> currentRow,Consumer<AccountPlatform> changed,Consumer<String> notify) {
        this(gateway,accounts,authorized,currentRow,changed,notify,Clock.systemUTC());
    }
    public PlatformPlaylistController(Gateway gateway,Function<AccountPlatform,AccountCredentials> accounts,BooleanSupplier authorized,
                                      Predicate<String> currentRow,Consumer<AccountPlatform> changed,Consumer<String> notify,Clock clock) {
        this.gateway=gateway;this.accounts=accounts;this.authorized=authorized;this.currentRow=currentRow;this.changed=changed;this.notify=notify;this.clock=clock;
    }
    private boolean valid(String token,LibraryController.Selection selection) {
        return !closed && authorized.getAsBoolean() && currentRow.test(token) && selection.account()!=null && selection.account().isActive()
                && selection.account().platform()==selection.platform() && accounts.apply(selection.platform())==selection.account();
    }
    public synchronized Snapshot snapshot(String token,LibraryController.Selection selection) {
        return !closed && authorized.getAsBoolean() && currentRow.test(token) && state.token().equals(token)
                && (selection.account()==null || accounts.apply(selection.platform())==selection.account() && selection.account().isActive())
                ?state:Snapshot.empty();
    }
    public synchronized void load(String token,LibraryController.Selection selection) {
        if(!valid(token,selection)){state=new Snapshot(token,List.of(),false,"请先登录"+selection.platform().label(),false,false);return;}
        long generation=++revision;
        if(owner!=selection.account()) {owner=selection.account();cachedChoices=List.of();choicesUntil=Instant.MIN;membership.clear();}
        state=new Snapshot(token,List.of(),true,"",false,false);
        worker.submit(()->{
            try(var request=new RequestScope(()->valid(token,selection) && generation==revision,120000)) {
                if(selection.item().kind()!=Catalog.Kind.TRACK) {
                    boolean subscribed=gateway.subscribed(selection.account(),selection.item(),request);request.check();
                    publish(generation,new Snapshot(token,List.of(),false,"",true,subscribed));return;
                }
                List<Choice> choices;
                synchronized(this){choices=clock.instant().isBefore(choicesUntil)?cachedChoices:null;}
                if(choices==null) {
                    choices=List.copyOf(gateway.choices(selection.account(),request));request.check();
                    synchronized(this){if(generation!=revision)return;cachedChoices=choices;choicesUntil=clock.instant().plusSeconds(120);}
                }
                var entries=new ArrayList<Entry>();for(var choice:choices)entries.add(new Entry(choice,false,false));
                publish(generation,new Snapshot(token,entries,true,"",false,false));
                String song=PlatformPlaylistGateway.normalize(selection.platform(),selection.item().target());
                boolean failed=false;
                for(int start=0;start<choices.size();start+=3) {
                    var pending=new ArrayList<Future<Set<String>>>();
                    for(int i=start;i<Math.min(start+3,choices.size());i++) {
                        Choice choice=choices.get(i);
                        pending.add(readers.submit(()->{
                            request.check();Membership cached;
                            synchronized(this){cached=membership.get(choice.id());}
                            if(cached!=null && clock.instant().isBefore(cached.until()))return cached.songs();
                            var members=Set.copyOf(gateway.members(selection.account(),choice,request));request.check();
                            synchronized(this) {
                                if(generation!=revision)throw new CancellationException();
                                membership.put(choice.id(),new Membership(members,clock.instant().plusSeconds(120)));
                                while(membership.size()>64)membership.remove(membership.keySet().iterator().next());
                            }
                            return members;
                        }));
                    }
                    try {
                        for(int i=0;i<pending.size();i++) {
                            int index=start+i;
                            try {entries.set(index,new Entry(choices.get(index),true,pending.get(i).get().contains(song)));}
                            catch(ExecutionException failure){request.check();failed=true;}
                            request.check();publish(generation,new Snapshot(token,entries,true,"",false,false));
                        }
                    } finally {for(var future:pending)if(!future.isDone())future.cancel(true);}
                }
                publish(generation,new Snapshot(token,entries,false,failed?"部分歌单读取失败，请重新打开菜单重试":"",false,false));
            } catch(Exception failure) {
                if(valid(token,selection))publish(generation,new Snapshot(token,state.entries(),false,"平台歌单读取失败，请重新打开菜单重试",false,false));
            }
        });
    }
    private synchronized void publish(long generation,Snapshot next){if(!closed && generation==revision)state=next;}
    public synchronized void add(String token,LibraryController.Selection selection,String id) {
        if(!valid(token,selection) || !state.token().equals(token))throw new IllegalStateException("Stale platform playlist");
        Entry entry=state.entries().stream().filter(e->e.choice().id().equals(id)).findFirst().orElseThrow();
        if(!entry.checked() || entry.contains())throw new IllegalStateException("Playlist unavailable or already contains song");
        mutate(token,selection,request->gateway.add(selection.account(),selection.item(),entry.choice(),request),"已添加到"+selection.platform().label()+"歌单");
    }
    public synchronized void subscribe(String token,LibraryController.Selection selection) {
        if(!valid(token,selection) || !state.token().equals(token) || !state.subscriptionKnown() || state.subscribed())
            throw new IllegalStateException("Playlist subscription unavailable");
        mutate(token,selection,request->gateway.subscribe(selection.account(),selection.item(),request),"已订阅"+selection.platform().label()+"歌单");
    }
    @FunctionalInterface private interface Mutation {void run(RequestScope request) throws IOException;}
    private void mutate(String token,LibraryController.Selection selection,Mutation action,String success) {
        long generation=++revision;
        state=new Snapshot(token,state.entries(),true,"正在保存",false,false);
        worker.submit(()->{
            try(var request=new RequestScope(()->valid(token,selection) && generation==revision,120000)) {
                action.run(request);request.check();
                synchronized(this){membership.clear();choicesUntil=Instant.MIN;}
                changed.accept(selection.platform());notify.accept(success);
                publish(generation,Snapshot.empty());
            } catch(Exception failure) {
                if(!closed && accounts.apply(selection.platform())==selection.account() && selection.account().isActive()) {
                    publish(generation,new Snapshot(token,List.of(),false,"平台未确认保存，请刷新后重试",false,false));
                    notify.accept("平台未确认保存，请刷新后重试");
                }
            }
        });
    }
    @Override public synchronized void close(){closed=true;revision++;state=Snapshot.empty();membership.clear();worker.shutdownNow();readers.shutdownNow();}
}
