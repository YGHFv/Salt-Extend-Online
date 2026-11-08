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
        var config=node.getConfig();
        var descriptions=SemanticsProperties.INSTANCE.getContentDescription();
        boolean searchLabel=label!=null && (label.startsWith("\u641c\u7d22") || label.startsWith("\u5173\u95ed\u641c\u7d22"));
        boolean matches=label==null || (searchLabel ? config.contains(descriptions) && config.get(descriptions).contains(label) : config.toString().contains(label));
        return matches && (action==null || config.contains(action)) ? node : null;
    }
    static SemanticsNode find(ImageComposeScene scene,String label,SemanticsPropertyKey<?> action) {
        for(var owner:scene.getSemanticsOwners()) { var found=find(owner.getRootSemanticsNode(),label,action); if(found!=null) return found; }
        return null;
    }
    static void render(ImageComposeScene scene) throws Exception {
        for(int i=0;i<6;i++) { try(Image ignored=scene.render(System.nanoTime())) { } Thread.sleep(90); }
        try(Image ignored=scene.render(System.nanoTime())) { }
    }
    static void click(ImageComposeScene scene,String label) throws Exception {
        var key=SemanticsActions.INSTANCE.getOnClick(); var node=find(scene,label,key); check(node!=null);
        check(Boolean.TRUE.equals(node.getConfig().get(key).getAction().invoke())); render(scene);
    }
    static String draft(ImageComposeScene scene) {
        return find(scene,null,SemanticsActions.INSTANCE.getSetText()).getConfig().get(SemanticsProperties.INSTANCE.getEditableText()).getText();
    }
    static void type(ImageComposeScene scene,String text) throws Exception {
        var set=SemanticsActions.INSTANCE.getSetText();
        check(Boolean.TRUE.equals(find(scene,null,set).getConfig().get(set).getAction().invoke(new AnnotatedString(text,List.of(),List.of())))); render(scene);
    }
    static void clearDraft(ImageComposeScene scene) throws Exception {
        var field=find(scene,null,SemanticsActions.INSTANCE.getSetText());
        var click=SemanticsActions.INSTANCE.getOnClick(); SemanticsNode clear=null;
        for(var child:field.getChildren()) { var candidate=find(child,null,click); if(candidate!=null) {clear=candidate;break;} }
        check(clear!=null); var a=field.getBoundsInRoot(); var b=clear.getBoundsInRoot();
        check(b.getLeft()>=a.getLeft() && b.getRight()<=a.getRight() && b.getWidth()<a.getWidth());
        check(Boolean.TRUE.equals(clear.getConfig().get(click).getAction().invoke())); render(scene);
        check(draft(scene).isEmpty());
    }
    private static void escape(ImageComposeScene scene) throws Exception {
        var awt=new java.awt.event.KeyEvent(new javax.swing.JPanel(),java.awt.event.KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),0,java.awt.event.KeyEvent.VK_ESCAPE,java.awt.event.KeyEvent.CHAR_UNDEFINED);
        Object key=androidx.compose.ui.input.key.KeyEvent_desktopKt.toComposeEvent(awt);
        check(Boolean.TRUE.equals(ImageComposeScene.class.getMethod("sendKeyEvent-ZmokQxo",Object.class).invoke(scene,key))); render(scene);
    }
    private static void compactHeader(ImageComposeScene scene, int width, boolean searching) {
        var click=SemanticsActions.INSTANCE.getOnClick();
        var search=find(scene,searching?"\u5173\u95ed\u641c\u7d22B\u7ad9":"\u641c\u7d22B\u7ad9",click); check(search!=null);
        var searchBounds=search.getBoundsInRoot();
        float size=searching?32:36;
        if(Math.abs(searchBounds.getWidth()-size)>=1 || Math.abs(searchBounds.getHeight()-size)>=1) throw new AssertionError("Search button bounds: "+searchBounds);
        check(Math.abs(searchBounds.getWidth()-size)<1 && Math.abs(searchBounds.getHeight()-size)<1);
        if(!searching) check(find(scene,"\u641c\u7d22B\u7ad9\u5b98\u65b9\u56fe\u6807",null)!=null);
        if(searching && width<640) {
            check(find(scene,"\u53d1\u73b0",click)==null && find(scene,"\u6536\u85cf",click)==null && find(scene,"\u8ba2\u9605",click)==null);
            return;
        }
        float right=0;
        for(String label:List.of("\u53d1\u73b0","\u6536\u85cf","\u8ba2\u9605")) {
            var node=find(scene,label,click); check(node!=null); var bounds=node.getBoundsInRoot();
            check(Math.abs(bounds.getWidth()-36)<1 && Math.abs(bounds.getHeight()-36)<1);
            check(bounds.getLeft()>=right && bounds.getRight()<searchBounds.getLeft() && Math.abs(bounds.getTop()+bounds.getBottom()-searchBounds.getTop()-searchBounds.getBottom())<1);
            check(!node.getConfig().contains(SemanticsProperties.INSTANCE.getText())); right=bounds.getRight();
        }
        check(searchBounds.getTop()<56 && searchBounds.getRight()<=width);
    }
    private static void accountIcons(ImageComposeScene scene) throws Exception {
        menuIcons(scene,List.of("\u5207\u6362\u8d26\u6237","\u9000\u51fa\u767b\u5f55"));
    }
    static void menuIcons(ImageComposeScene scene,List<String> labels) throws Exception {
        menuIcons(scene,labels,1f);
    }
    static void menuIcons(ImageComposeScene scene,List<String> labels,float density) throws Exception {
        try(var image=scene.render(System.nanoTime()); var data=image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG,100,0)) {
            var pixels=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data.getBytes()));
            for(String label:labels) {
                SemanticsNode icon=null,text=null;
                for(var owner:scene.getSemanticsOwners()) {
                    var candidate=find(owner.getUnmergedRootSemanticsNode(),label+"\u56fe\u6807",null);
                    if(candidate!=null) {
                        icon=candidate;
                        text=find(owner.getUnmergedRootSemanticsNode(),label,SemanticsActions.INSTANCE.getGetTextLayoutResult());
                        break;
                    }
                }
                check(icon!=null); check(text!=null);
                var bounds=icon.getBoundsInRoot(); var title=text.getBoundsInRoot();
                if(Math.abs(bounds.getWidth()-20*density)>1 || Math.abs(bounds.getHeight()-20*density)>1 || bounds.getRight()>title.getLeft()) throw new AssertionError("Menu icon/text bounds: "+bounds+" / "+title);
                check(Math.abs(bounds.getWidth()-20*density)<=1 && Math.abs(bounds.getHeight()-20*density)<=1 && bounds.getRight()<=title.getLeft());
                check(Math.abs(bounds.getTop()+bounds.getBottom()-title.getTop()-title.getBottom())<=4*density);
                int ink=0;
                for(int y=Math.max(0,(int)bounds.getTop());y<Math.min(pixels.getHeight(),(int)bounds.getBottom());y++)
                    for(int x=Math.max(0,(int)bounds.getLeft());x<Math.min(pixels.getWidth(),(int)bounds.getRight());x++) {
                        int rgb=pixels.getRGB(x,y);
                        if((rgb>>>24)>200 && ((rgb>>16)&255)<150 && ((rgb>>8)&255)<150 && (rgb&255)<150) ink++;
                    }
                check(ink>15);
            }
        }
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var asynchronousFailure=new AtomicReference<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{asynchronousFailure.compareAndSet(null,error);error.printStackTrace();});
        Clock clock=Clock.systemUTC(); var login=new AtomicReference<>(signedIn("revision-one"));
        var lastAction=new AtomicReference<NativeDesk.Action>(); var queried=new AtomicReference<Catalog.Query>();
        var queues=new AtomicInteger(); var avatarRequests=new AtomicInteger(); var armed=new AtomicBoolean();
        var failCatalog=new AtomicBoolean();
        try(OnlinePlaybackController player=new OnlinePlaybackController(p->null,()->true,armed::get,(audio,active)->queues.incrementAndGet(),clock,
                    a->(target,quality,scope,request)->{
                        var expiry=clock.instant().plusSeconds(300);
                        boolean net=target.platform()==AccountPlatform.NETEASE;
                        return new OnlinePlaybackController.Audio(net ? TrackKey.netease(target.id()) : TrackKey.bilibili(target.id(),123),new Metadata("Search hit","Artist","",""),
                                new MediaLease(net ? MediaLease.Platform.NETEASE : MediaLease.Platform.BILIBILI,
                                        URI.create(net ? "https://m7.music.126.net/audio" : "https://upos-fixture.bilivideo.com/audio"),Map.of(),scope,expiry,4096),expiry);
                    });
            LibraryController library=new LibraryController(p->null,()->true,t->{},()->player.invalidate("Navigation"),clock,
                    a->(query,quality,scope,request)->{
                        queried.set(query);
                        if(failCatalog.get()) throw CatalogFailure.code(CatalogFailure.Stage.NET_SEARCH,CatalogFailure.Reason.HTTP,429);
                        if(query.keyword().equals("empty")) return new Catalog.Page("Search results",List.of(),false,clock.instant().plusSeconds(300),1);
                        boolean net=query.platform()==AccountPlatform.NETEASE;
                        return new Catalog.Page("Search results",List.of(new Catalog.Item(Catalog.Kind.TRACK,net ? "123" : "BV17x411w7KC","Search hit","Artist",120000,true)),net && query.page()==1,clock.instant().plusSeconds(300),net ? 2 : 1);
                    })) {
            NativeDesk desk=new NativeDesk(library,player,p->login.get(),(p,action)->{
                lastAction.set(action);
                if(action==NativeDesk.Action.WEB_LOGIN) login.set(new AccountLoginController.Snapshot(AccountLoginController.Phase.WEB_LOGIN,"Official web login fixture",null,""));
                if(action==NativeDesk.Action.CANCEL_LOGIN) login.set(new AccountLoginController.Snapshot(AccountLoginController.Phase.IDLE,"",null,""));
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
                            check(ui.nativeToolbarAvailable() && ui.nativeSearchAvailable()); compactHeader(scene,width,false);
                            check(find(scene,"\u626b\u7801\u767b\u5f55",null)==null && find(scene,"\u9000\u51fa\u767b\u5f55",null)==null);
                            var avatar=find(scene,"\u8d26\u53f7\u83dc\u5355",null); var search=find(scene,"\u641c\u7d22B\u7ad9",null);
                            check(avatar!=null && search!=null && search.getBoundsInRoot().getRight() < avatar.getBoundsInRoot().getRight());
                            check(avatar.getBoundsInRoot().getTop()<100 && avatar.getBoundsInRoot().getRight()<=width);
                            click(scene,"\u8d26\u53f7\u83dc\u5355");
                            accountIcons(scene);
                            check(find(scene,"Fixture user",null)!=null && find(scene,"\u5207\u6362\u8d26\u6237",null)!=null);
                            click(scene,"\u5207\u6362\u8d26\u6237");
                            check(lastAction.get()==NativeDesk.Action.SWITCH_ACCOUNT && find(scene,"\u5b98\u65b9 App",null)!=null);
                            check(ui.avatar(AccountPlatform.BILIBILI,"revision-"+width)==null);
                            login.set(signedIn("replacement-"+width)); render(scene);
                            click(scene,"\u8d26\u53f7\u83dc\u5355"); click(scene,"\u9000\u51fa\u767b\u5f55");
                            check(lastAction.get()==NativeDesk.Action.LOGOUT && find(scene,"\u767b\u5f55\u8d26\u53f7",null)!=null);
                            login.set(signedIn("search-"+width)); render(scene); queried.set(null);
                            var setText=SemanticsActions.INSTANCE.getSetText();
                            if(find(scene,null,setText)==null) click(scene,"\u641c\u7d22B\u7ad9");
                            click(scene,"\u5173\u95ed\u641c\u7d22B\u7ad9"); check(find(scene,null,setText)==null && queried.get()==null);
                            click(scene,"\u641c\u7d22B\u7ad9");
                            compactHeader(scene,width,true);
                            var input=find(scene,null,setText); check(input!=null);
                            var icon=find(scene,"\u5173\u95ed\u641c\u7d22B\u7ad9",SemanticsActions.INSTANCE.getOnClick()).getBoundsInRoot();
                            check(input.getBoundsInRoot().getRight()<=icon.getLeft() && Math.abs(input.getBoundsInRoot().getTop()-icon.getTop())<15);
                            var submit=SemanticsActions.INSTANCE.getOnImeAction();
                            check(Boolean.TRUE.equals(find(scene,null,submit).getConfig().get(submit).getAction().invoke())); render(scene);
                            check(queried.get()==null);
                            type(scene,"x".repeat(101)); check(draft(scene).isEmpty() && queried.get()==null);
                            type(scene,"bad\u0001control"); check(draft(scene).isEmpty() && queried.get()==null);
                            type(scene,"draft to clear"); clearDraft(scene); check(queried.get()==null);
                            type(scene,"escape draft"); escape(scene); check(find(scene,null,setText)==null && queried.get()==null);
                            click(scene,"\u641c\u7d22B\u7ad9"); check(draft(scene).isEmpty());
                            input=find(scene,null,setText);
                            check(Boolean.TRUE.equals(input.getConfig().get(setText).getAction().invoke(new AnnotatedString("\u6d1b\u5929\u4f9d music",List.of(),List.of()))));
                            render(scene);
                            check(queried.get()==null);
                            click(scene,"\u5173\u95ed\u641c\u7d22B\u7ad9"); check(find(scene,null,setText)==null && queried.get()==null);
                            click(scene,"\u641c\u7d22B\u7ad9"); check(find(scene,"\u6d1b\u5929\u4f9d music",null)==null);
                            input=find(scene,null,setText);
                            check(Boolean.TRUE.equals(input.getConfig().get(setText).getAction().invoke(new AnnotatedString("\u6d1b\u5929\u4f9d music",List.of(),List.of())))); render(scene);
                            var field=find(scene,null,submit); check(field!=null);
                            check(Boolean.TRUE.equals(field.getConfig().get(submit).getAction().invoke())); render(scene);
                            check(queried.get()!=null && queried.get().view()==Catalog.View.SEARCH && queried.get().keyword().equals("\u6d1b\u5929\u4f9d music"));
                            check(find(scene,"Search hit",null)!=null);
                            var completedQuery=queried.get();
                            clearDraft(scene); check(queried.get()==completedQuery && find(scene,"Search hit",null)!=null);
                            click(scene,"\u5173\u95ed\u641c\u7d22B\u7ad9"); check(find(scene,null,setText)==null && queried.get()==completedQuery);
                            compactHeader(scene,width,false);
                            int before=queues.get(); var hit=find(scene,"Search hit",null).getBoundsInRoot();
                            NativePageRenderTest.tap(scene,(hit.getLeft()+hit.getRight())/2,(hit.getTop()+hit.getBottom())/2,1000); Thread.sleep(70);
                            NativePageRenderTest.tap(scene,(hit.getLeft()+hit.getRight())/2,(hit.getTop()+hit.getBottom())/2,1110);
                            waitFor(()->queues.get()==before+1);
                        } catch(Exception error){throw new AssertionError(error);} finally{scene.close();}
                    });
                }
                check(queues.get()==2);
                Object netRoute=Class.forName(SaltNativeUi.MOD_ROUTE).getConstructor(String.class).newInstance(SaltNativeUi.PLUGIN_ID+"/native/NETEASE");
                for(int width:new int[]{740,420}) {
                    login.set(new AccountLoginController.Snapshot(AccountLoginController.Phase.VERIFICATION_REQUIRED,
                        AuthFailure.diagnostic(AuthFailure.code(AuthFailure.Stage.NET_POLL,AuthFailure.Reason.API,8821)),null,""));
                    lastAction.set(null);
                    SwingUtilities.invokeAndWait(()->{
                        var scene=new ImageComposeScene(width,820,DensityKt.Density(1f,1f),LayoutDirection.Ltr,Dispatchers.getUnconfined(),
                            (composer,flags)->{cached.invoke(netRoute,composer,flags);return Unit.INSTANCE;});
                        try {
                            render(scene); check(find(scene,"NET_POLL/API=8821",null)!=null);
                            check(find(scene,"\u5b98\u65b9\u7f51\u9875\u767b\u5f55",SemanticsActions.INSTANCE.getOnClick())!=null);
                            check(find(scene,"\u5b98\u65b9 App \u767b\u5f55\u4e8c\u7ef4\u7801",null)==null && lastAction.get()==null);
                            click(scene,"\u5b98\u65b9\u7f51\u9875\u767b\u5f55"); check(lastAction.get()==NativeDesk.Action.WEB_LOGIN);
                            check(find(scene,"\u53d6\u6d88\u7f51\u9875\u767b\u5f55",SemanticsActions.INSTANCE.getOnClick())!=null);
                            check(find(scene,"\u5b98\u65b9\u7f51\u9875\u767b\u5f55",SemanticsActions.INSTANCE.getOnClick())==null);
                            click(scene,"\u53d6\u6d88\u7f51\u9875\u767b\u5f55"); check(lastAction.get()==NativeDesk.Action.CANCEL_LOGIN);
                            check(find(scene,"\u53d6\u6d88\u7f51\u9875\u767b\u5f55",SemanticsActions.INSTANCE.getOnClick())==null);
                            login.set(new AccountLoginController.Snapshot(AccountLoginController.Phase.CREATING,"Preparing fixture device context",null,"")); render(scene);
                            check(find(scene,"\u53d6\u6d88\u767b\u5f55",SemanticsActions.INSTANCE.getOnClick())!=null);
                            check(find(scene,"\u5b98\u65b9 App \u767b\u5f55\u4e8c\u7ef4\u7801",null)==null);
                            click(scene,"\u53d6\u6d88\u767b\u5f55"); check(lastAction.get()==NativeDesk.Action.CANCEL_LOGIN);
                            check(find(scene,"\u53d6\u6d88\u767b\u5f55",SemanticsActions.INSTANCE.getOnClick())==null);
                            queried.set(null);
                            var setText=SemanticsActions.INSTANCE.getSetText();
                            if(find(scene,null,setText)!=null) click(scene,"\u5173\u95ed\u641c\u7d22\u7f51\u6613\u4e91");
                            click(scene,"\u641c\u7d22\u7f51\u6613\u4e91");
                            var field=find(scene,null,setText); check(field!=null);
                            var icon=find(scene,"\u5173\u95ed\u641c\u7d22\u7f51\u6613\u4e91",SemanticsActions.INSTANCE.getOnClick()).getBoundsInRoot();
                            check(field.getBoundsInRoot().getRight()<=icon.getLeft() && icon.getRight()<=width);
                            check(Boolean.TRUE.equals(field.getConfig().get(setText).getAction().invoke(new AnnotatedString("netease music",List.of(),List.of())))); render(scene);
                            var submit=SemanticsActions.INSTANCE.getOnImeAction();
                            check(Boolean.TRUE.equals(find(scene,null,submit).getConfig().get(submit).getAction().invoke())); render(scene);
                            check(queried.get().platform()==AccountPlatform.NETEASE && queried.get().keyword().equals("netease music"));
                            click(scene,"\u5173\u95ed\u641c\u7d22\u7f51\u6613\u4e91"); check(find(scene,null,setText)==null);
                            check(find(scene,"1/2",null)!=null && find(scene,"Search hit",null)!=null);
                            click(scene,"\u4e0b\u4e00\u9875"); check(library.snapshot().get("page").equals(2) && find(scene,"2/2",null)!=null);
                            failCatalog.set(true); click(scene,"\u5237\u65b0\u5f53\u524d\u5217\u8868");
                            check(find(scene,"NET_SEARCH/HTTP=429",null)!=null && find(scene,"Search hit",null)==null);
                            check(find(scene,"\u91cd\u8bd5\u5f53\u524d\u5217\u8868",SemanticsActions.INSTANCE.getOnClick())!=null);
                            failCatalog.set(false); click(scene,"\u91cd\u8bd5\u5f53\u524d\u5217\u8868");
                            check(queried.get().page()==2 && queried.get().keyword().equals("netease music") && find(scene,"2/2",null)!=null);
                            int before=queues.get(); var hit=find(scene,"Search hit",null).getBoundsInRoot();
                            NativePageRenderTest.tap(scene,(hit.getLeft()+hit.getRight())/2,(hit.getTop()+hit.getBottom())/2,2000); Thread.sleep(70);
                            NativePageRenderTest.tap(scene,(hit.getLeft()+hit.getRight())/2,(hit.getTop()+hit.getBottom())/2,2110);
                            waitFor(()->queues.get()==before+1);
                            click(scene,"\u641c\u7d22\u7f51\u6613\u4e91"); field=find(scene,null,setText);
                            check(Boolean.TRUE.equals(field.getConfig().get(setText).getAction().invoke(new AnnotatedString("empty",List.of(),List.of())))); render(scene);
                            check(Boolean.TRUE.equals(find(scene,null,submit).getConfig().get(submit).getAction().invoke())); render(scene);
                            check(find(scene,"\u5f53\u9875\u6ca1\u6709\u53ef\u64ad\u653e\u7684\u641c\u7d22\u7ed3\u679c",null)!=null);
                            check(find(scene,"\u4e0b\u4e00\u9875",null)==null && find(scene,"\u4e0a\u4e00\u9875",null)==null);
                            login.set(signedIn("netease-menu-"+width)); render(scene);
                            click(scene,"\u8d26\u53f7\u83dc\u5355"); accountIcons(scene);
                            click(scene,"\u9000\u51fa\u767b\u5f55"); check(lastAction.get()==NativeDesk.Action.LOGOUT);
                        } catch(Exception error){throw new AssertionError(error);} finally {scene.close();}
                    });
                }
            }
        }
        if(asynchronousFailure.get()!=null) throw new AssertionError("Asynchronous Compose failure",asynchronousFailure.get());
        System.out.println("PASS: "+checks+" real Compose avatar/menu/account replacement/search input/IME/double-click checks; synthetic account/API/queue");
    }
}
