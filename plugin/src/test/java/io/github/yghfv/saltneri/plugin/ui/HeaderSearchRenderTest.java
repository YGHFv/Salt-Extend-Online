package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.ImageComposeScene;
import androidx.compose.ui.semantics.*;
import androidx.compose.ui.text.AnnotatedString;
import androidx.compose.ui.unit.DensityKt;
import androidx.compose.ui.unit.LayoutDirection;
import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.library.*;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import io.github.yghfv.saltneri.plugin.host.Salt1193PlaybackSession.Metadata;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import kotlin.Unit;
import kotlin.jvm.functions.Function3;
import kotlinx.coroutines.Dispatchers;
import org.jetbrains.skia.Image;

/** Synthetic signed-in header, account menu and editable search through real Compose. */
public final class HeaderSearchRenderTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Header/search check " + checks); }
    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+3_000_000_000L;
        while(!condition.getAsBoolean() && System.nanoTime()<end) Thread.sleep(10);
        check(condition.getAsBoolean());
    }
    private static AccountLoginController.Snapshot signedIn(String revision) {
        return new AccountLoginController.Snapshot(AccountLoginController.Phase.LOGGED_IN,"Internal status",null,"42",
                AccountCredentials.Profile.from(AccountPlatform.BILIBILI,"Fixture user","https://i0.hdslb.com/avatar.png"),revision);
    }
    private static SemanticsNode find(SemanticsNode node, String label, SemanticsPropertyKey<?> action) {
        for(var child:node.getChildren()) { var found=find(child,label,action); if(found!=null) return found; }
        return (label==null || node.getConfig().toString().contains(label)) && (action==null || node.getConfig().contains(action)) ? node : null;
    }
    static SemanticsNode find(ImageComposeScene scene,String label,SemanticsPropertyKey<?> action) {
        for(var owner:scene.getSemanticsOwners()) { var found=find(owner.getRootSemanticsNode(),label,action); if(found!=null) return found; }
        return null;
    }
    static void render(ImageComposeScene scene) throws Exception {
        Thread.sleep(400);
        for(int i=0;i<4;i++) try(Image ignored=scene.render(System.nanoTime())) { }
    }
    static void click(ImageComposeScene scene,String label) throws Exception {
        var key=SemanticsActions.INSTANCE.getOnClick(); var node=find(scene,label,key); check(node!=null);
        check(Boolean.TRUE.equals(node.getConfig().get(key).getAction().invoke())); render(scene);
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Clock clock=Clock.systemUTC(); var login=new AtomicReference<>(signedIn("revision-one"));
        var lastAction=new AtomicReference<NativeDesk.Action>(); var queried=new AtomicReference<Catalog.Query>();
        var queues=new AtomicInteger(); var avatarRequests=new AtomicInteger(); var armed=new AtomicBoolean();
        try(OnlinePlaybackController player=new OnlinePlaybackController(p->null,()->true,armed::get,(audio,active)->queues.incrementAndGet(),clock,
                    a->(target,quality,scope,request)->{
                        var expiry=clock.instant().plusSeconds(300);
                        return new OnlinePlaybackController.Audio(TrackKey.bilibili(target.id(),123),new Metadata("Search hit","Artist","",""),
                                new MediaLease(MediaLease.Platform.BILIBILI,URI.create("https://upos-fixture.bilivideo.com/audio"),Map.of(),scope,expiry,4096),expiry);
                    });
            LibraryController library=new LibraryController(p->null,()->true,t->{},()->player.invalidate("Navigation"),clock,
                    a->(query,quality,scope,request)->{
                        queried.set(query);
                        return new Catalog.Page("Search results",List.of(new Catalog.Item(Catalog.Kind.TRACK,"BV17x411w7KC","Search hit","Artist",120000,true)),false,clock.instant().plusSeconds(300),1);
                    })) {
            NativeDesk desk=new NativeDesk(library,player,p->login.get(),(p,action)->{
                lastAction.set(action);
                if(action==NativeDesk.Action.ARM) armed.set(true);
                if(action==NativeDesk.Action.LOGOUT || action==NativeDesk.Action.SWITCH_ACCOUNT) {
                    login.set(new AccountLoginController.Snapshot(action==NativeDesk.Action.LOGOUT ? AccountLoginController.Phase.IDLE : AccountLoginController.Phase.WAITING_SCAN,
                            "Fixture login",action==NativeDesk.Action.LOGOUT ? null : URI.create("https://example.invalid/fixture-qr"),""));
                    library.accountChanged(p); player.accountChanged(p);
                }
            },()->true);
            byte[] png=CoverLoaderContractTest.png();
            ClassLoader host=HeaderSearchRenderTest.class.getClassLoader();
            try(SaltNativeUi ui=new SaltNativeUi(host,new NativePageRenderTest.Hooks(),desk,()->true,
                    new CoverLoader(uri->new CoverLoaderContractTest.Response(uri,png)),
                    new CoverLoader(uri->{avatarRequests.incrementAndGet();return new CoverLoaderContractTest.Response(uri,png);}))) {
                waitFor(()->ui.avatar(AccountPlatform.BILIBILI,"revision-one")!=null); check(avatarRequests.get()==1);
                Class<?> singleton=Class.forName(SaltPageContentBinding.SINGLETON);
                Function3<Object,Composer,Integer,Object> cached=(Function3<Object,Composer,Integer,Object>)singleton.getMethod(SaltPageContentBinding.GETTER).invoke(singleton.getField("INSTANCE").get(null));
                Object route=Class.forName(SaltNativeUi.MOD_ROUTE).getConstructor(String.class).newInstance(SaltNativeUi.PLUGIN_ID+"/native/BILIBILI");
                var style=Class.forName("androidx.compose.ui.\u04cc").getMethod("\u037f",Composer.class);
                for(int width:new int[]{740,420}) {
                    login.set(signedIn("revision-"+width)); lastAction.set(null); queried.set(null);
                    SwingUtilities.invokeAndWait(()->{
                        ImageComposeScene scene=new ImageComposeScene(width,820,DensityKt.Density(1f,1f),LayoutDirection.Ltr,Dispatchers.getUnconfined(),
                                (composer,flags)->{
                                    try { if(ui.sidebarStyle()==null) ui.publishSidebarStyle(style.invoke(null,composer)); cached.invoke(route,composer,flags);return Unit.INSTANCE; }
                                    catch(ReflectiveOperationException error){throw new AssertionError(error);}
                                });
                        try {
                            render(scene);
                            check(find(scene,"\u626b\u7801\u767b\u5f55",null)==null && find(scene,"\u9000\u51fa\u767b\u5f55",null)==null);
                            var avatar=find(scene,"\u8d26\u53f7\u83dc\u5355",null); var search=find(scene,"\u641c\u7d22B\u7ad9",null);
                            check(avatar!=null && search!=null && search.getBoundsInRoot().getRight() < avatar.getBoundsInRoot().getRight());
                            check(avatar.getBoundsInRoot().getTop()<100 && avatar.getBoundsInRoot().getRight()<=width);
                            click(scene,"\u8d26\u53f7\u83dc\u5355");
                            check(find(scene,"Fixture user",null)!=null && find(scene,"\u5207\u6362\u8d26\u6237",null)!=null);
                            click(scene,"\u5207\u6362\u8d26\u6237");
                            check(lastAction.get()==NativeDesk.Action.SWITCH_ACCOUNT && find(scene,"\u5b98\u65b9 App",null)!=null);
                            check(ui.avatar(AccountPlatform.BILIBILI,"revision-"+width)==null);
                            login.set(signedIn("replacement-"+width)); render(scene);
                            click(scene,"\u8d26\u53f7\u83dc\u5355"); click(scene,"\u9000\u51fa\u767b\u5f55");
                            check(lastAction.get()==NativeDesk.Action.LOGOUT && find(scene,"\u767b\u5f55\u8d26\u53f7",null)!=null);
                            login.set(signedIn("search-"+width)); render(scene);
                            var setText=SemanticsActions.INSTANCE.getSetText();
                            if(find(scene,null,setText)==null) click(scene,"\u641c\u7d22B\u7ad9");
                            click(scene,"\u641c\u7d22B\u7ad9"); check(find(scene,null,setText)==null && queried.get()==null);
                            click(scene,"\u641c\u7d22B\u7ad9");
                            var input=find(scene,null,setText); check(input!=null);
                            var icon=find(scene,"\u641c\u7d22B\u7ad9",null).getBoundsInRoot();
                            check(input.getBoundsInRoot().getRight()<=icon.getLeft() && Math.abs(input.getBoundsInRoot().getTop()-icon.getTop())<15);
                            check(Boolean.TRUE.equals(input.getConfig().get(setText).getAction().invoke(new AnnotatedString("\u6d1b\u5929\u4f9d music",List.of(),List.of()))));
                            render(scene);
                            check(queried.get()==null);
                            click(scene,"\u641c\u7d22B\u7ad9"); check(find(scene,null,setText)==null && queried.get()==null);
                            click(scene,"\u641c\u7d22B\u7ad9"); check(find(scene,"\u6d1b\u5929\u4f9d music",null)!=null);
                            var submit=SemanticsActions.INSTANCE.getOnImeAction(); var field=find(scene,null,submit); check(field!=null);
                            check(Boolean.TRUE.equals(field.getConfig().get(submit).getAction().invoke())); render(scene);
                            check(queried.get()!=null && queried.get().view()==Catalog.View.SEARCH && queried.get().keyword().equals("\u6d1b\u5929\u4f9d music"));
                            check(find(scene,"Search hit",null)!=null);
                            var completedQuery=queried.get();
                            click(scene,"\u641c\u7d22B\u7ad9"); check(find(scene,null,setText)==null && queried.get()==completedQuery);
                            int before=queues.get(); var hit=find(scene,"Search hit",null).getBoundsInRoot();
                            NativePageRenderTest.tap(scene,(hit.getLeft()+hit.getRight())/2,(hit.getTop()+hit.getBottom())/2,1000); Thread.sleep(70);
                            NativePageRenderTest.tap(scene,(hit.getLeft()+hit.getRight())/2,(hit.getTop()+hit.getBottom())/2,1110);
                            waitFor(()->queues.get()==before+1);
                        } catch(Exception error){throw new AssertionError(error);} finally{scene.close();}
                    });
                }
                check(queues.get()==2);
            }
        }
        System.out.println("PASS: "+checks+" real Compose avatar/menu/account replacement/search input/IME/double-click checks; synthetic account/API/queue");
    }
}
