package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.ImageComposeScene;
import androidx.compose.ui.semantics.SemanticsActions;
import androidx.compose.ui.unit.DensityKt;
import androidx.compose.ui.unit.LayoutDirection;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.library.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function3;
import kotlinx.coroutines.Dispatchers;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;

/** Real Compose lazy scrolling and pager actions, with synthetic metadata and no media/image traffic. */
public final class CatalogPagingRenderTest {
    private static int checks;
    private static void check(boolean ok) {checks++;if(!ok)throw new AssertionError("Paging render "+checks);}
    private static int count(LibraryController c) {return ((List<?>)c.snapshot().get("items")).size();}
    private static void ready(LibraryController c,int count) throws Exception {
        long end=System.nanoTime()+3_000_000_000L;while(count(c)!=count && System.nanoTime()<end)Thread.sleep(5);check(count(c)==count);
    }
    private static void scroll(ImageComposeScene scene,int index) throws Exception {
        var key=SemanticsActions.INSTANCE.getScrollToIndex();var list=HeaderSearchRenderTest.find(scene,null,key);check(list!=null);
        check(Boolean.TRUE.equals(list.getConfig().get(key).getAction().invoke(index)));HeaderSearchRenderTest.render(scene);
    }
    @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
        var asynchronous=new AtomicReference<Throwable>();Thread.setDefaultUncaughtExceptionHandler((thread,error)->{asynchronous.compareAndSet(null,error);error.printStackTrace();});
        for(AccountPlatform platform:AccountPlatform.values())for(String layout:List.of("SINGLE","DOUBLE","CARDS")) {
            var calls=new AtomicInteger();
            try(var player=new OnlinePlaybackController(p->null,()->true,()->true,(a,v)->{throw new AssertionError("No playback");});
                var library=new LibraryController(p->null,()->true,t->{throw new AssertionError("No play");},()->{},Clock.systemUTC(),a->(q,k,s,r)->{
                    calls.incrementAndGet();var items=new ArrayList<Catalog.Item>();
                    for(int i=(q.page()-1)*20+1;i<=Math.min(q.page()*20,137);i++)items.add(new Catalog.Item(Catalog.Kind.TRACK,""+i,"Track "+i,"Artist",1000,true));
                    return new Catalog.Page("Synthetic catalog",items,q.page()<7,Instant.MAX,7,137);
                })) {
                library.pageLimit(0);library.browse(platform,Catalog.View.DISCOVER);ready(library,30);
                var desk=new NativeDesk(library,player,p->new AccountLoginController.Snapshot(AccountLoginController.Phase.LOGGED_IN,"",null,"42"),(p,a)->{},()->true);
                desk.layout(platform,layout);ClassLoader host=CatalogPagingRenderTest.class.getClassLoader();
                try(var ui=new SaltNativeUi(host,new NativePageRenderTest.Hooks(),desk,()->true,
                        new CoverLoader(uri->{throw new AssertionError("No image HTTP");}))) {
                    var singleton=Class.forName(SaltPageContentBinding.SINGLETON);
                    var cached=(Function3<Object,Composer,Integer,Object>)singleton.getMethod(SaltPageContentBinding.GETTER).invoke(singleton.getField("INSTANCE").get(null));
                    var style=Class.forName("androidx.compose.ui.\u04cc").getMethod("\u037f",Composer.class);
                    Object route=Class.forName(SaltNativeUi.MOD_ROUTE).getConstructor(String.class).newInstance(SaltNativeUi.PLUGIN_ID+"/native/"+platform.name());
                    SwingUtilities.invokeAndWait(()->{
                        var scene=new ImageComposeScene(740,820,DensityKt.Density(1f,1f),LayoutDirection.Ltr,Dispatchers.getUnconfined(),(composer,flags)->{
                            try {if(ui.sidebarStyle()==null)ui.publishSidebarStyle(style.invoke(null,composer));cached.invoke(route,composer,flags);return Unit.INSTANCE;}
                            catch(Exception error){throw new AssertionError(error);}
                        });try {
                            HeaderSearchRenderTest.render(scene);check(count(library)==30 && calls.get()==2);
                            String first=(String)((Map<?,?>)((List<?>)library.snapshot().get("items")).getFirst()).get("token");
                            int columns=layout.equals("SINGLE")?1:layout.equals("DOUBLE")?2:3;
                            int header=platform==AccountPlatform.BILIBILI?1:0;
                            scroll(scene,30/columns+header+(layout.equals("SINGLE")?1:0));
                            ready(library,60);HeaderSearchRenderTest.render(scene);
                            check(count(library)==60 && calls.get()==3 && library.currentRow(first));
                            check(HeaderSearchRenderTest.find(scene,"\u4e0a\u4e00\u9875",null)==null && HeaderSearchRenderTest.find(scene,"\u4e0b\u4e00\u9875",null)==null);
                            // No automatic full-catalog drain after an append; another down-scroll is required.
                            HeaderSearchRenderTest.render(scene);check(count(library)==60 && calls.get()==3);
                            for(int limit:new int[]{20,30,50,60,100}) {
                                library.pageLimit(limit);ready(library,limit);HeaderSearchRenderTest.render(scene);
                                scroll(scene,(limit+columns-1)/columns+header+(layout.equals("SINGLE")?1:0));
                                check(HeaderSearchRenderTest.find(scene,"1/"+((137+limit-1)/limit),null)!=null);
                                var next=HeaderSearchRenderTest.find(scene,"\u4e0b\u4e00\u9875",SemanticsActions.INSTANCE.getOnClick());check(next!=null);
                                check(Boolean.TRUE.equals(next.getConfig().get(SemanticsActions.INSTANCE.getOnClick()).getAction().invoke()));
                                ready(library,Math.min(limit,137-limit));HeaderSearchRenderTest.render(scene);
                                check(library.snapshot().get("page").equals(2));
                                check(((Map<?,?>)((List<?>)library.snapshot().get("items")).getFirst()).get("title").equals("Track "+(limit+1)));
                            }
                        } catch(Exception error){throw new AssertionError(error);}finally{scene.close();}
                    });
                    check(ui.diagnostics().contains("visible=none"));
                }
            }
        }
        if(asynchronous.get()!=null)throw new AssertionError("Asynchronous failure",asynchronous.get());
        System.out.println("PASS: "+checks+" real lazy scroll/30-item initial load/stable append/no auto-drain/configured pager/default page counter checks; all layouts, both platforms, fake metadata only");
    }
}
