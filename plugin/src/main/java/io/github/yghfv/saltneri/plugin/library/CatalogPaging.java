package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static io.github.yghfv.saltneri.plugin.library.Catalog.*;

/** Presentation windows over unchanged 20-slot platform pages; never fetch the whole catalog for scrolling. */
public final class CatalogPaging {
    public static final int DEFAULT = 30, CHUNK = 30, MAX_ITEMS = 10000;
    private CatalogPaging() { }
    public static int parse(String value) {
        return value!=null && Set.of("20","30","50","60","100","0").contains(value)?Integer.parseInt(value):DEFAULT;
    }
    public static int width(int limit) { return limit==0?CHUNK:limit; }
    public record Window(String title,List<Item> items,boolean hasMore,int totalPages,long totalCount) {
        public Window {items=List.copyOf(items);}
    }
    @FunctionalInterface interface Fetch {Page page(Query query) throws Exception;}
    static Window load(Query query,int width,RequestScope request,Fetch fetch) throws Exception {
        // Radio endpoints return a variable batch, not a positional 20-slot page.
        // Publish it immediately; padding a UI window used to trigger 7-14 serial FM requests.
        if(width==20 || query.view()==View.PERSONAL_RADIO) {
            request.check();Page page=fetch.page(query);
            return new Window(page.title(),page.items(),page.hasMore(),page.totalPages()>0?page.totalPages():page.hasMore()?0:query.page(),page.totalCount());
        }
        int start=(query.page()-1)*width,end=Math.min(MAX_ITEMS,start+width);
        var items=new ArrayList<Item>();String title="";long count=-1;int pages=0;boolean more=false;
        if(start>=MAX_ITEMS)return new Window(title,items,false,(MAX_ITEMS+width-1)/width,-1);
        for(int index=start/20+1;index<=(end-1)/20+1;index++) {
            request.check();
            Page part=fetch.page(new Query(query.platform(),query.view(),query.folder(),index,query.owner(),query.keyword(),query.reverse(),query.order(),query.resource()));
            title=part.title();
            if(part.totalCount()>=0)count=part.totalCount();
            long slots=count>=0?count:part.totalPages()>0?part.totalPages()*20L:-1;
            if(slots>=0)pages=(int)Math.max(1,(Math.min(MAX_ITEMS,slots)+width-1)/width);
            int offset=(index-1)*20,from=Math.min(part.items().size(),Math.max(0,start-offset));
            int to=Math.min(part.items().size(),end-offset);
            if(to>from)items.addAll(part.items().subList(from,to));
            more=end<MAX_ITEMS && (to<part.items().size() || part.hasMore());
            if(!part.hasMore()) {
                pages=Math.max(1,(offset+part.items().size()+width-1)/width);
                break;
            }
        }
        return new Window(title,items,more,pages,count);
    }
}
