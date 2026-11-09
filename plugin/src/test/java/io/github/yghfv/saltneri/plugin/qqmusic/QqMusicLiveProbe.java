package io.github.yghfv.saltneri.plugin.qqmusic;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.auth.QqMusicQrClient;
import io.github.yghfv.saltneri.plugin.library.*;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.time.Clock;
import java.util.List;

/** Explicit anonymous read-only smoke probe; no account imports, browser, audio engine or raw response logs. */
public final class QqMusicLiveProbe {
    public static void main(String[] args) throws Exception {
        try(var request=new RequestScope(()->true,60000)) {
            var catalog=new QqMusicCatalog(null,Clock.systemUTC(),QqMusicHttp::openPinned);
            var discover=catalog.load(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.DISCOVER,0,1),request);
            System.out.println("QQ discover: items="+discover.items().size()+", more="+discover.hasMore());
            if(java.util.Arrays.asList(args).contains("--covers"))try(var loader=new io.github.yghfv.saltneri.plugin.ui.CoverLoader()) {
                int present=0,loaded=0;
                for(var item:discover.items().subList(0,Math.min(6,discover.items().size()))) {
                    if(item.cover()==null)continue;present++;
                    if(loader.load(item.cover(),request).length>0)loaded++;
                }
                System.out.println("QQ public covers: present="+present+", decoded="+loaded);
                if(loaded==0 || loaded!=present)throw new AssertionError("Public cover loading incomplete");
            }
            if(java.util.Arrays.asList(args).contains("--covers-only"))return;
            if(java.util.Arrays.asList(args).contains("--playlist") && !discover.items().isEmpty()) {
                var all=catalog.loadAll(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.FOLDER,
                        Long.parseLong(discover.items().getFirst().target()),1),request);
                System.out.println("QQ public playlist: items="+all.items().size()+", complete="+(all.items().size()==all.totalCount()));
            }
            var search=catalog.load(new Catalog.Query(AccountPlatform.QQMUSIC,Catalog.View.SEARCH,0,1,0,"Jay Chou"),request);
            System.out.println("QQ search: items="+search.items().size()+", more="+search.hasMore());
            if(!search.items().isEmpty()) {
                var target=QqMusicTarget.parse(search.items().getFirst().target());
                var song=new QqMusicClient(null).song(target,request);
                String lyrics=new QqMusicClient(null).lyrics(target,request);
                System.out.println("QQ song: identity="+song.mid().equals(target.mid())+", duration="+(song.duration()>0)+", cover="+(song.cover()!=null)+", lyrics="+(lyrics!=null && !lyrics.isBlank())+", wordTimed="+(lyrics!=null && lyrics.contains("<")));
                // Resolution validates a single byte and does not download or play the track.
                if(java.util.Arrays.asList(args).contains("--range")) try {
                    var audio=new QqMusicClient(null).resolve(target,QqMusicClient.Quality.STANDARD,"probe-anonymous",request);
                    System.out.println("QQ full audio: quality="+audio.quality()+", bytes="+audio.lease().length());
                } catch(QqMusicClient.TrackUnavailable denied) {System.out.println("QQ full audio: unavailable to anonymous account (expected permission boundary)");}
            }
            if(java.util.Arrays.asList(args).contains("--qr")) try(var client=new QqMusicQrClient()) {
                var session=client.create(request);
                var image=client.qrImage(session);
                var result=client.poll(session,request);
                System.out.println("QQ QR: image="+(image!=null && image.length>0)+", state="+result.state());
                if(result.credentials()!=null) result.credentials().retire();
            }
            if(java.util.Arrays.asList(args).contains("--qrc"))for(String mid:List.of("0039MnYb0qxYhV","002pXw073bnbSj")) {
                String text=new QqMusicClient(null).lyrics(QqMusicTarget.parse(mid),request);
                System.out.println("QQ QRC sample: present="+(text!=null && !text.isBlank())+
                        ", wordTimed="+(text!=null && text.contains("<"))+", lines="+(text==null?0:text.lines().count()));
            }
        }
    }
}
