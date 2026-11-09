package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.AccountCredentials;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.time.*;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

/** Account/day snapshots and a bounded FM buffer preserve 20-slot presentation pagination. */
final class NeteaseRecommendations {
    private AccountCredentials owner;
    private LocalDate day;
    private long revision;
    private final EnumMap<View,List<Item>> snapshots=new EnumMap<>(View.class);
    private List<Item> radio=List.of();
    private boolean radioEnded;
    synchronized void clear() {revision++;owner=null;day=null;snapshots.clear();radio=List.of();radioEnded=false;}
    @FunctionalInterface interface Fetch {List<Item> load() throws IOException;}
    Page load(AccountCredentials account,Query query,Clock clock,RequestScope request,Fetch fetch) throws IOException {
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        boolean fm=query.view()==View.PERSONAL_RADIO;
        long captured;List<Item> items;boolean ended;
        synchronized(this) {
            if(owner!=account || !today.equals(day)) {clear();owner=account;day=today;}
            captured=revision;items=fm?radio:snapshots.get(query.view());ended=radioEnded;
        }
        int desired=query.page()*20;
        if(fm && items.size()<desired && !ended || !fm && items==null) {
            var next=new ArrayList<Item>(items==null?List.of():items);
            var seen=new HashSet<String>();next.forEach(item->seen.add(item.target()));
            int before=next.size();
            for(int attempt=0;attempt<(fm?10:1);attempt++) {
                request.check();
                List<Item> batch=fetch.load();request.check();
                int added=0;
                for(Item item:batch) if(seen.add(item.target())) {next.add(item);added++;}
                if(!fm || next.size()>=desired || next.size()>=10000) break;
                if(added==0) {ended=true;break;}
            }
            // Small FM batches carry their remainder into the next API page.
            if(fm && next.size()<desired && !ended) throw new IOException("NetEase FM batch incomplete; refresh");
            items=List.copyOf(next.subList(0,Math.min(10000,next.size())));
            synchronized(this) {
                request.check();
                if(captured!=revision || owner!=account || account!=null && !account.isActive()
                        || fm && radio.size()!=before) throw new IOException("NetEase recommendations changed; refresh");
                if(fm) {radio=items;radioEnded=ended;} else snapshots.put(query.view(),items);
            }
        }
        request.check();
        if(account!=null && !account.isActive()) throw new java.util.concurrent.CancellationException("Catalog account retired");
        int start=Math.min(items.size(),(query.page()-1)*20),end=Math.min(items.size(),start+20);
        boolean more=fm?query.page()<500 && (!ended || end<items.size()):end<items.size();
        long total=fm && !ended?-1:items.size();
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
