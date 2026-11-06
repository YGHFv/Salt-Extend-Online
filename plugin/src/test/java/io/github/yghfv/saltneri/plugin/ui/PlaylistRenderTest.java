package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.ImageComposeScene;
import androidx.compose.ui.semantics.SemanticsActions;
import androidx.compose.ui.text.AnnotatedString;
import androidx.compose.ui.unit.*;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.library.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import kotlin.Unit;
import kotlin.jvm.functions.Function3;
import kotlinx.coroutines.Dispatchers;
import static io.github.yghfv.saltneri.plugin.ui.HeaderSearchRenderTest.*;

public final class PlaylistRenderTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if(!ok) throw new AssertionError("Playlist render "+checks); }
    @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
        AtomicReference<Throwable> asynchronousFailure=new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{ asynchronousFailure.compareAndSet(null,error); error.printStackTrace(); });
        AtomicInteger requests=new AtomicInteger(); AtomicReference<LibraryController.Playlist> submitted=new AtomicReference<>();
        try(var player=new OnlinePlaybackController(p->null,()->true,()->true,(a,v)->{});
            var library=new LibraryController(p->null,()->true,t->{},()->{},Clock.systemUTC(),a->(q,k,s,r)->{
                requests.incrementAndGet(); List<Catalog.Item> rows=new ArrayList<>();
                if(q.view()!=Catalog.View.FOLDER) rows.add(new Catalog.Item(Catalog.Kind.FOLDER,"10","My collection","Uploader",21,true));
                else for(int i=(q.page()-1)*20+1;i<=Math.min(q.page()*20,21);i++) rows.add(new Catalog.Item(Catalog.Kind.TRACK,""+i,String.format("Song %02d",i),"Uploader",1000,true));
                return new Catalog.Page(q.view()==Catalog.View.FOLDER?"My collection":"Catalog",rows,q.view()==Catalog.View.FOLDER && q.page()==1,Instant.now().plusSeconds(300),q.view()==Catalog.View.FOLDER?2:1,q.view()==Catalog.View.FOLDER?21:1);
            })) {
            var desk=new NativeDesk(library,player,p->new AccountLoginController.Snapshot(AccountLoginController.Phase.LOGGED_IN,"",null,"42"),(p,a)->{},()->true);
            desk.playlistPlayer(submitted::set);
            ClassLoader host=PlaylistRenderTest.class.getClassLoader();
            try(var ui=new SaltNativeUi(host,new NativePageRenderTest.Hooks(),desk,()->true)) {
                var singleton=Class.forName(SaltPageContentBinding.SINGLETON);
                var cached=(Function3<Object,Composer,Integer,Object>)singleton.getMethod(SaltPageContentBinding.GETTER).invoke(singleton.getField("INSTANCE").get(null));
                Object route=Class.forName(SaltNativeUi.MOD_ROUTE).getConstructor(String.class).newInstance(SaltNativeUi.PLUGIN_ID+"/native/BILIBILI");
                var style=Class.forName("androidx.compose.ui.\u04cc").getMethod("\u037f",Composer.class);
                for(int width:new int[]{740,420}) {
                    library.browse(AccountPlatform.BILIBILI,Catalog.View.MINE); submitted.set(null); Thread.sleep(100);
                    SwingUtilities.invokeAndWait(()->{
                        Function3<Object,Composer,Integer,Object> body=(key,composer,flags)->{
                            try { if(ui.sidebarStyle()==null) ui.publishSidebarStyle(style.invoke(null,composer));return cached.invoke(route,composer,flags); }
                            catch(Exception e){throw new AssertionError(e);}
                        };
                        var scene=new ImageComposeScene(width,820,DensityKt.Density(1f,1f),LayoutDirection.Ltr,Dispatchers.getUnconfined(),(c,f)->{body.invoke(route,c,f);return Unit.INSTANCE;});
                        try {
                            render(scene); check(find(scene,"My collection (21)",null)!=null && find(scene,"Uploader",null)!=null);
                            click(scene,"My collection (21)");
                            var play=find(scene,"\u64ad\u653e",SemanticsActions.INSTANCE.getOnClick());
                            var search=find(scene,"\u641c\u7d22\u5408\u96c6",null); var sort=find(scene,"\u6392\u5e8f",SemanticsActions.INSTANCE.getOnClick());
                            check(play!=null && search!=null && sort!=null);
                            check(play.getBoundsInRoot().getLeft()<search.getBoundsInRoot().getLeft() && search.getBoundsInRoot().getRight()<=sort.getBoundsInRoot().getRight());
                            check(sort.getBoundsInRoot().getRight()<=width && Math.abs(play.getBoundsInRoot().getTop()-sort.getBoundsInRoot().getTop())<5);
                            click(scene,"\u641c\u7d22\u5408\u96c6");
                            var set=SemanticsActions.INSTANCE.getSetText(); var input=find(scene,null,set); check(input!=null);
                            var icon=find(scene,"\u641c\u7d22\u5408\u96c6",null).getBoundsInRoot();
                            check(input.getBoundsInRoot().getRight()<=icon.getLeft() && Math.abs(input.getBoundsInRoot().getTop()-icon.getTop())<15);
                            input.getConfig().get(set).getAction().invoke(new AnnotatedString("Song 21",List.of(),List.of())); render(scene);
                            var ime=SemanticsActions.INSTANCE.getOnImeAction(); find(scene,null,ime).getConfig().get(ime).getAction().invoke(); render(scene);
                            check(find(scene,"Song 21",null)!=null && find(scene,"Song 01",null)==null);
                            click(scene,"\u6392\u5e8f"); click(scene,"\u5012\u5e8f");
                            check(library.snapshot().get("sort").equals("REVERSE"));
                            click(scene,"\u64ad\u653e"); render(scene);
                            check(submitted.get()!=null && submitted.get().items().size()==21 && submitted.get().items().getFirst().target().equals("21"));
                            int before=requests.get(); scene.close();
                            scene=new ImageComposeScene(width,820,DensityKt.Density(1f,1f),LayoutDirection.Ltr,Dispatchers.getUnconfined(),(c,f)->{body.invoke(route,c,f);return Unit.INSTANCE;});
                            render(scene); check(find(scene,"Song 21",null)!=null && library.snapshot().get("filter").equals("Song 21") && requests.get()==before);
                            click(scene,"\u53d1\u73b0");
                            check(find(scene,"\u6682\u672a\u63a5\u5165",null)==null && find(scene,"\u70ed\u95e8\u89c6\u9891",null)!=null);
                            click(scene,"\u97f3\u4e50\u70ed\u95e8\u699c"); check(library.snapshot().get("view").equals("MUSIC_HOT"));
                            click(scene,"\u539f\u521b\u699c"); check(library.snapshot().get("view").equals("MUSIC_ORIGINAL"));
                        } catch(Exception e){throw new AssertionError(e);} finally {scene.close();}
                    });
                }
            }
        }
        if(asynchronousFailure.get()!=null) throw new AssertionError("Asynchronous Compose failure",asynchronousFailure.get());
        System.out.println("PASS: "+checks+" real Compose collection counts/authors/header actions/full filter/sort/play/navigation restore checks");
    }
}
