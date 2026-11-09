package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.AccountCredentials;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.time.*;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

/** Account/day snapshots; FM publishes one upstream batch per user-requested page. */
final class NeteaseRecommendations {
    private AccountCredentials owner;
    private LocalDate day;
    private long revision;
    private final EnumMap<View,List<Item>> snapshots=new EnumMap<>(View.class);
    private final Map<Integer,List<Item>> radio=new LinkedHashMap<>();
    private final Set<String> radioIds=new HashSet<>();
    private boolean radioEnded;
    synchronized void clear() {revision++;owner=null;day=null;snapshots.clear();radio.clear();radioIds.clear();radioEnded=false;}
    @FunctionalInterface interface Fetch {List<Item> load() throws IOException;}
    Page load(AccountCredentials account,Query query,Clock clock,RequestScope request,Fetch fetch) throws IOException {
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        boolean fm=query.view()==View.PERSONAL_RADIO;
        long captured;List<Item> items;boolean ended;
        synchronized(this) {
            if(owner!=account || !today.equals(day)) {clear();owner=account;day=today;}
            captured=revision;items=fm?radio.get(query.page()):snapshots.get(query.view());ended=radioEnded;
        }
        if(items==null) {
            request.check();
            List<Item> batch=fm && ended?List.of():fetch.load();request.check();
            var next=new ArrayList<Item>();
            var seen=new HashSet<String>();
            synchronized(this) {
                request.check();
                if(captured!=revision || owner!=account || account!=null && !account.isActive())
                    throw new IOException("NetEase recommendations changed; refresh");
                for(Item item:batch) if(seen.add(item.target()) && (!fm || !radioIds.contains(item.target()))) next.add(item);
                if(fm) {
                    items=List.copyOf(next.stream().limit(20).toList());
                    radioIds.addAll(items.stream().map(Item::target).toList());
                    ended=items.isEmpty();radioEnded=ended;radio.put(query.page(),items);
                    while(radio.size()>500)radio.remove(radio.keySet().iterator().next());
                } else {items=List.copyOf(next);snapshots.put(query.view(),items);}
            }
        }
        request.check();
        if(account!=null && !account.isActive()) throw new java.util.concurrent.CancellationException("Catalog account retired");
        int start=fm?0:Math.min(items.size(),(query.page()-1)*20),end=Math.min(items.size(),start+20);
        boolean more=fm?query.page()<500 && (!ended || end<items.size()):end<items.size();
        long total=fm?-1:items.size();
        String title=switch(query.view()) {
            case DAILY -> "每日推荐";
            case PERSONAL_RADIO -> "音乐漫游 · 私人 FM";
            case PERSONAL_PLAYLISTS -> "私人推荐歌单";
            case NEW_SONGS -> "推荐新歌";
            default -> throw new IllegalArgumentException("Invalid recommendation view");
        };
        return new Page(title,items.subList(start,end),more,clock.instant().plusSeconds(120),
                total<0?0:(int)Math.max(1,(total+19)/20),total);
    }
}
