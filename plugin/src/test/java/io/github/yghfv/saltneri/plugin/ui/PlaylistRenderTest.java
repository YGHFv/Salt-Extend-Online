package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.ImageComposeScene;
import androidx.compose.ui.semantics.*;
import androidx.compose.ui.text.AnnotatedString;
import androidx.compose.ui.text.TextLayoutResult;
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
    private static SemanticsNode unmerged(SemanticsNode node,String label,SemanticsPropertyKey<?> action) {
        for(var child:node.getChildren()) { var found=unmerged(child,label,action); if(found!=null)return found; }
        return node.getConfig().toString().contains(label) && (action==null || node.getConfig().contains(action))?node:null;
    }
    private static SemanticsNode unmerged(ImageComposeScene scene,String label,SemanticsPropertyKey<?> action) {
        for(var owner:scene.getSemanticsOwners()){var found=unmerged(owner.getUnmergedRootSemanticsNode(),label,action);if(found!=null)return found;}return null;
    }
    private static void centeredLabel(ImageComposeScene scene, String label, SemanticsNode button) {
        var action=SemanticsActions.INSTANCE.getGetTextLayoutResult();
        var text=find(scene,label,action); check(text!=null);
        var a=button.getBoundsInRoot(); var b=text.getBoundsInRoot();
        check(Math.abs((a.getLeft()+a.getRight())-(b.getLeft()+b.getRight()))<=2);
        check(Math.abs((a.getTop()+a.getBottom())-(b.getTop()+b.getBottom()))<=2);
        var layouts=new ArrayList<TextLayoutResult>();
        check(Boolean.TRUE.equals(text.getConfig().get(action).getAction().invoke(layouts)) && layouts.size()==1);
        var layout=layouts.getFirst();
        for(int line=0;line<layout.getLineCount();line++)
            check(Math.abs(layout.getLineLeft(line)+layout.getLineRight(line)-layout.getMultiParagraph().getWidth())<=2);
    }
    private static void selectedTab(ImageComposeScene scene, String label) {
        var node=find(scene,label,SemanticsActions.INSTANCE.getOnClick()); check(node!=null);
        check(Boolean.TRUE.equals(node.getConfig().get(SemanticsProperties.INSTANCE.getSelected())));
    }
    private static void navigationRows(ImageComposeScene scene, float density, int width) {
        var topLabels=List.of("\u53d1\u73b0","\u6536\u85cf","\u8ba2\u9605");
        var bottomLabels=List.of("\u70ed\u95e8\u89c6\u9891","\u97f3\u4e50\u70ed\u95e8\u699c","\u539f\u521b\u699c");
        check(find(scene,"\u521b\u5efa\u7684\u6536\u85cf",null)==null && find(scene,"\u6536\u85cf / \u8ba2\u9605",null)==null);
        float top=-1, bottom=-1, right=-1;
        for(int i=0;i<3;i++) {
            var upper=find(scene,topLabels.get(i),SemanticsActions.INSTANCE.getOnClick());
            var lower=find(scene,bottomLabels.get(i),SemanticsActions.INSTANCE.getOnClick());
            check(upper!=null && lower!=null);
            var a=upper.getBoundsInRoot(); var b=lower.getBoundsInRoot();
            if(i==0) { top=a.getTop(); bottom=b.getTop(); }
            check(Math.abs(a.getTop()-top)<=1 && Math.abs(b.getTop()-bottom)<=1);
            check(a.getLeft()>=right && a.getRight()<=width && a.getBottom()<b.getTop());
            check(Math.abs(a.getWidth()-36*density)<=1 && Math.abs(a.getHeight()-36*density)<=1);
            check(a.getTop()<56*density && a.getLeft()>width/3f && !hasText(upper));
            var icon=unmerged(scene,topLabels.get(i)+"\u56fe\u6807",null); check(icon!=null);
            var glyph=icon.getBoundsInRoot();
            check(Math.abs(a.getLeft()+a.getRight()-glyph.getLeft()-glyph.getRight())<=2);
            check(Math.abs(a.getTop()+a.getBottom()-glyph.getTop()-glyph.getBottom())<=2);
            centeredLabel(scene,bottomLabels.get(i),lower);
            check(Boolean.valueOf(i==0).equals(upper.getConfig().get(SemanticsProperties.INSTANCE.getSelected())));
            check(Boolean.valueOf(i==0).equals(lower.getConfig().get(SemanticsProperties.INSTANCE.getSelected())));
            right=a.getRight();
        }
    }
    private static boolean hasText(SemanticsNode node) {
        return node.getConfig().contains(SemanticsProperties.INSTANCE.getText()) || node.getChildren().stream().anyMatch(PlaylistRenderTest::hasText);
    }
    private static void visibleButtons(ImageComposeScene scene, float density) throws Exception {
        try(var image=scene.render(System.nanoTime()); var png=image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG,100,0)) {
            var pixels=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png.getBytes()));
            float right=-1, top=-1;
            for(String label:List.of("\u641c\u7d22\u5408\u96c6","\u5168\u90e8\u64ad\u653e","\u6392\u5e8f")) {
                var node=find(scene,label,SemanticsActions.INSTANCE.getOnClick()); check(node!=null); var bounds=node.getBoundsInRoot();
                check(!hasText(node));
                check(Math.abs(bounds.getWidth()-36*density)<=1 && Math.abs(bounds.getHeight()-36*density)<=1);
                if(!label.equals("\u5168\u90e8\u64ad\u653e")) check(unmerged(scene,label+"\u5b98\u65b9\u56fe\u6807",null)!=null);
                if(top<0) top=bounds.getTop();
                check(bounds.getLeft()>=right && bounds.getRight()<=pixels.getWidth() && Math.abs(bounds.getTop()-top)<=1);
                right=bounds.getRight();
                int ink=0;
                for(int y=Math.max(0,(int)bounds.getTop());y<Math.min(pixels.getHeight(),(int)bounds.getBottom());y++)
                    for(int x=Math.max(0,(int)bounds.getLeft());x<Math.min(pixels.getWidth(),(int)bounds.getRight());x++)
                        if((pixels.getRGB(x,y)>>>24)>200) ink++;
                check(ink>20*density*density);
            }
        }
    }
    @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
        AtomicReference<Throwable> asynchronousFailure=new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{ asynchronousFailure.compareAndSet(null,error); error.printStackTrace(); });
        AtomicInteger requests=new AtomicInteger(); AtomicReference<LibraryController.Playlist> submitted=new AtomicReference<>();
        try(var player=new OnlinePlaybackController(p->null,()->true,()->true,(a,v)->{});
            var library=new LibraryController(p->null,()->true,t->{},()->{},Clock.systemUTC(),a->(q,k,s,r)->{
                requests.incrementAndGet(); List<Catalog.Item> rows=new ArrayList<>();
                if(q.view()!=Catalog.View.FOLDER && q.view()!=Catalog.View.AUTHOR_VIDEOS) rows.add(new Catalog.Item(Catalog.Kind.FOLDER,"10","My collection","Uploader",21,true));
                else for(int i=(q.page()-1)*20+1;i<=Math.min(q.page()*20,21);i++) rows.add(new Catalog.Item(Catalog.Kind.TRACK,""+i,String.format("Song %02d",i)+(i==2?" - longer title to verify two-line card height and single-line list ellipsis":""),"Uploader",1000,true,null,99,i==20?9000000000L:i,1700000000));
                return new Catalog.Page(q.view()==Catalog.View.FOLDER?"My collection":"Catalog",rows,q.view()==Catalog.View.FOLDER && q.page()==1,Instant.now().plusSeconds(300),q.view()==Catalog.View.FOLDER?2:1,q.view()==Catalog.View.FOLDER?21:1);
            })) {
            var desk=new NativeDesk(library,player,p->new AccountLoginController.Snapshot(AccountLoginController.Phase.LOGGED_IN,"",null,"42"),(p,a)->{},()->true);
            desk.playlistPlayer(submitted::set);
            ClassLoader host=PlaylistRenderTest.class.getClassLoader();
            try(var ui=new SaltNativeUi(host,new NativePageRenderTest.Hooks(),desk,()->true)) {
                check(ui.nativeToolbarAvailable());
                var singleton=Class.forName(SaltPageContentBinding.SINGLETON);
                var cached=(Function3<Object,Composer,Integer,Object>)singleton.getMethod(SaltPageContentBinding.GETTER).invoke(singleton.getField("INSTANCE").get(null));
                Object route=Class.forName(SaltNativeUi.MOD_ROUTE).getConstructor(String.class).newInstance(SaltNativeUi.PLUGIN_ID+"/native/BILIBILI");
                var style=Class.forName("androidx.compose.ui.\u04cc").getMethod("\u037f",Composer.class);
                for(float density:new float[]{1f,1.5f}) for(int logicalWidth:new int[]{740,580,420}) {
                    int width=Math.round(logicalWidth*density), height=Math.round(820*density);
                    library.browse(AccountPlatform.BILIBILI,Catalog.View.MINE); submitted.set(null); Thread.sleep(100);
                    SwingUtilities.invokeAndWait(()->{
                        Function3<Object,Composer,Integer,Object> body=(key,composer,flags)->{
                            try { if(ui.sidebarStyle()==null) ui.publishSidebarStyle(style.invoke(null,composer));return cached.invoke(route,composer,flags); }
                            catch(Exception e){throw new AssertionError(e);}
                        };
                        var scene=new ImageComposeScene(width,height,DensityKt.Density(density,1f),LayoutDirection.Ltr,Dispatchers.getUnconfined(),(c,f)->{body.invoke(route,c,f);return Unit.INSTANCE;});
                        try {
                            render(scene); check(find(scene,"My collection (21)",null)!=null && find(scene,"Uploader",null)!=null);
                            click(scene,"My collection (21)");
                            int beforeLayout=requests.get();
                            for(String layout:List.of("\u53cc\u5217\u5217\u8868","\u5361\u7247\u5217\u8868","\u5355\u5217\u5217\u8868")) {
                                click(scene,"\u5207\u6362\u5217\u8868");click(scene,layout);render(scene);
                                var a=unmerged(scene,"Song 01",SemanticsActions.INSTANCE.getGetTextLayoutResult()).getBoundsInRoot();
                                var b=unmerged(scene,"Song 02",SemanticsActions.INSTANCE.getGetTextLayoutResult()).getBoundsInRoot();
                                boolean multiple=layout.equals("\u5361\u7247\u5217\u8868") || layout.equals("\u53cc\u5217\u5217\u8868") && logicalWidth>=600;
                                check(multiple?Math.abs(a.getTop()-b.getTop())<=2 && b.getLeft()>a.getLeft():b.getTop()>a.getTop());
                                check(requests.get()==beforeLayout);
                                var artist=unmerged(scene,"Uploader",SemanticsActions.INSTANCE.getGetTextLayoutResult()).getBoundsInRoot();
                                if(layout.equals("\u5361\u7247\u5217\u8868")) {
                                    check(Math.abs(a.getHeight()-b.getHeight())<=1);
                                    var cover=unmerged(scene,"\u5361\u7247\u5c01\u9762",null).getBoundsInRoot();
                                    var plays=unmerged(scene,"\u5c01\u9762\u64ad\u653e\u91cf",null).getBoundsInRoot();
                                    var duration=unmerged(scene,"\u5c01\u9762\u65f6\u957f",null).getBoundsInRoot();
                                    check(plays.getLeft()>cover.getLeft() && duration.getRight()<cover.getRight() && plays.getRight()<duration.getLeft());
                                    check(plays.getBottom()<cover.getBottom() && Math.abs(plays.getTop()-duration.getTop())<=1 && plays.getTop()>cover.getTop()+cover.getHeight()/2);
                                    check(a.getTop()>=cover.getBottom() && artist.getTop()>a.getTop());
                                } else {
                                    var duration=unmerged(scene,"00:01",SemanticsActions.INSTANCE.getGetTextLayoutResult()).getBoundsInRoot();
                                    if(Math.abs(artist.getTop()-duration.getTop())>1) throw new AssertionError("Artist/duration alignment: "+artist+" / "+duration); checks++;
                                }
                            }
                            check(unmerged(scene,"00:01",null)!=null);
                            check(unmerged(scene,"\u5408\u96c6",SemanticsActions.INSTANCE.getGetTextLayoutResult())==null);
                            var back=unmerged(scene,"\u8fd4\u56de\u5217\u8868",SemanticsActions.INSTANCE.getOnClick()).getBoundsInRoot();
                            var layoutBounds=unmerged(scene,"\u5207\u6362\u5217\u8868",SemanticsActions.INSTANCE.getOnClick()).getBoundsInRoot();
                            check(Math.abs(back.getTop()-layoutBounds.getTop())<=1);
                            click(scene,"\u4f5c\u8005\u4e3b\u9875");check(library.snapshot().get("view").equals("AUTHOR_VIDEOS") && submitted.get()==null);
                            check(unmerged(scene,"2023-11-15",null)!=null);
                            if(logicalWidth>=600) {
                                var header=unmerged(scene,"\u66f4\u65b0\u65f6\u95f4",SemanticsActions.INSTANCE.getGetTextLayoutResult()).getBoundsInRoot();
                                var date=unmerged(scene,"2023-11-15",SemanticsActions.INSTANCE.getGetTextLayoutResult()).getBoundsInRoot();
                                check(Math.abs(header.getLeft()-date.getLeft())<=1 && Math.abs(header.getWidth()-date.getWidth())<=1);
                            }
                            click(scene,"\u6392\u5e8f");
                            HeaderSearchRenderTest.menuIcons(scene,List.of("\u64ad\u653e\u91cf","\u6700\u65b0","\u9006\u5411\u6392\u5e8f"),density);
                            check(unmerged(scene,"\u5df2\u9009\u62e9\u6700\u65b0",null)!=null);click(scene,"\u6700\u65b0");
                            click(scene,"\u8fd4\u56de\u5217\u8868");check(library.snapshot().get("view").equals("FOLDER"));
                            visibleButtons(scene,density);
                            var play=find(scene,"\u5168\u90e8\u64ad\u653e",SemanticsActions.INSTANCE.getOnClick());
                            var search=find(scene,"\u641c\u7d22\u5408\u96c6",null); var sort=find(scene,"\u6392\u5e8f",SemanticsActions.INSTANCE.getOnClick());
                            check(play!=null && search!=null && sort!=null);
                            check(search.getBoundsInRoot().getRight()<=play.getBoundsInRoot().getLeft() && play.getBoundsInRoot().getRight()<=sort.getBoundsInRoot().getLeft());
                            check(sort.getBoundsInRoot().getRight()<=width && Math.abs(play.getBoundsInRoot().getTop()-sort.getBoundsInRoot().getTop())<5);
                            click(scene,"\u641c\u7d22\u5408\u96c6");
                            visibleButtons(scene,density);
                            var set=SemanticsActions.INSTANCE.getSetText(); var input=find(scene,null,set); check(input!=null);
                            var icon=find(scene,"\u641c\u7d22\u5408\u96c6",null).getBoundsInRoot();
                            check(input.getBoundsInRoot().getRight()<=icon.getLeft() && Math.abs(input.getBoundsInRoot().getTop()-icon.getTop())<15*density);
                            int beforeToggle=requests.get();
                            click(scene,"\u641c\u7d22\u5408\u96c6"); check(find(scene,null,set)==null && requests.get()==beforeToggle);
                            click(scene,"\u641c\u7d22\u5408\u96c6"); input=find(scene,null,set); check(input!=null);
                            input.getConfig().get(set).getAction().invoke(new AnnotatedString("Song 21",List.of(),List.of())); render(scene);
                            click(scene,"\u641c\u7d22\u5408\u96c6"); check(find(scene,null,set)==null && requests.get()==beforeToggle);
                            click(scene,"\u641c\u7d22\u5408\u96c6"); check(find(scene,"Song 21",null)!=null);
                            var ime=SemanticsActions.INSTANCE.getOnImeAction(); find(scene,null,ime).getConfig().get(ime).getAction().invoke(); render(scene);
                            check(find(scene,"Song 21",null)!=null && find(scene,"Song 01",null)==null);
                            beforeToggle=requests.get();
                            click(scene,"\u641c\u7d22\u5408\u96c6"); check(find(scene,null,set)==null && requests.get()==beforeToggle && library.snapshot().get("filter").equals("Song 21"));
                            visibleButtons(scene,density);
                            click(scene,"\u6392\u5e8f");
                            check(find(scene,"\u5df2\u9009\u62e9\u9ed8\u8ba4\u987a\u5e8f",null)!=null);
                            HeaderSearchRenderTest.menuIcons(scene,List.of("\u9ed8\u8ba4\u987a\u5e8f","\u5012\u5e8f","\u6807\u9898","\u4f5c\u8005","\u65f6\u957f","\u64ad\u653e\u91cf"),density);
                            click(scene,"\u5012\u5e8f");
                            check(library.snapshot().get("sort").equals("REVERSE"));
                            click(scene,"\u6392\u5e8f"); check(find(scene,"\u5df2\u9009\u62e9\u5012\u5e8f",null)!=null); click(scene,"\u5012\u5e8f");
                            click(scene,"\u5168\u90e8\u64ad\u653e"); render(scene);
                            check(submitted.get()!=null && submitted.get().items().size()==21 && submitted.get().items().getFirst().target().equals("21"));
                            click(scene,"\u6392\u5e8f"); click(scene,"\u64ad\u653e\u91cf");
                            check(library.snapshot().get("sort").equals("PLAY_COUNT") && find(scene,"Song 21",null)!=null);
                            click(scene,"\u5168\u90e8\u64ad\u653e"); render(scene);
                            check(submitted.get()!=null && submitted.get().items().size()==21 && submitted.get().items().getFirst().target().equals("20"));
                            int layoutRequests=requests.get();
                            var layoutButton=find(scene,"\u5207\u6362\u5217\u8868",SemanticsActions.INSTANCE.getOnClick());
                            check(layoutButton!=null && layoutButton.getBoundsInRoot().getRight()<=find(scene,"\u6392\u5e8f",SemanticsActions.INSTANCE.getOnClick()).getBoundsInRoot().getLeft());
                            for(String layout:List.of("\u53cc\u5217\u5217\u8868","\u5361\u7247\u5217\u8868","\u5355\u5217\u5217\u8868")) {
                                click(scene,"\u5207\u6362\u5217\u8868"); click(scene,layout); render(scene);
                                check(find(scene,"Song 21",null)!=null && find(scene,"00:01",null)!=null && requests.get()==layoutRequests);
                            }
                            int before=requests.get(); scene.close();
                            scene=new ImageComposeScene(width,height,DensityKt.Density(density,1f),LayoutDirection.Ltr,Dispatchers.getUnconfined(),(c,f)->{body.invoke(route,c,f);return Unit.INSTANCE;});
                            render(scene); check(find(scene,"Song 21",null)!=null && library.snapshot().get("filter").equals("Song 21") && requests.get()==before);
                            click(scene,"\u53d1\u73b0");
                            check(find(scene,"\u6682\u672a\u63a5\u5165",null)==null && find(scene,"\u70ed\u95e8\u89c6\u9891",null)!=null);
                            navigationRows(scene,density,width);
                            click(scene,"\u97f3\u4e50\u70ed\u95e8\u699c"); check(library.snapshot().get("view").equals("MUSIC_HOT"));
                            selectedTab(scene,"\u97f3\u4e50\u70ed\u95e8\u699c"); selectedTab(scene,"\u53d1\u73b0");
                            click(scene,"\u539f\u521b\u699c"); check(library.snapshot().get("view").equals("MUSIC_ORIGINAL"));
                            selectedTab(scene,"\u539f\u521b\u699c"); selectedTab(scene,"\u53d1\u73b0");
                            click(scene,"\u8ba2\u9605"); check(library.snapshot().get("view").equals("SAVED")); selectedTab(scene,"\u8ba2\u9605");
                            click(scene,"\u6536\u85cf"); check(library.snapshot().get("view").equals("MINE")); selectedTab(scene,"\u6536\u85cf");
                        } catch(Exception e){throw new AssertionError(e);} finally {scene.close();}
                    });
                }
            }
        }
        if(asynchronousFailure.get()!=null) throw new AssertionError("Asynchronous Compose failure",asynchronousFailure.get());
        System.out.println("PASS: "+checks+" real Compose collection counts/authors/header actions/full filter/sort/play/navigation restore checks");
    }
}
