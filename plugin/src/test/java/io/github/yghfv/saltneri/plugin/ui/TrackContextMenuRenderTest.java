package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.ImageComposeScene;
import androidx.compose.ui.semantics.*;
import androidx.compose.ui.unit.*;
import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.library.*;
import io.github.yghfv.saltneri.plugin.host.Salt1193PlaybackSession.Metadata;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import kotlin.Unit;
import kotlin.jvm.functions.Function3;
import kotlinx.coroutines.Dispatchers;
import static io.github.yghfv.saltneri.plugin.ui.HeaderSearchRenderTest.*;

/** Installed Salt popup/submenu widgets and real secondary-click dispatch; recording queue only. */
public final class TrackContextMenuRenderTest {
    private static int checks;
    private static void check(boolean ok,String message) {if(!ok) throw new AssertionError(message); checks++;}
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var failure=new AtomicReference<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler((t,e)->{failure.compareAndSet(null,e);e.printStackTrace();});
        Clock clock=Clock.systemUTC(); var requests=new AtomicInteger(); var resolves=new AtomicInteger();
        var inserted=new java.util.concurrent.CopyOnWriteArrayList<OnlinePlaybackController.QueueAction>();
        var played=new AtomicInteger(); var armed=new AtomicBoolean();
        var saved=new AtomicInteger();
        try(var playlists=new SavedPlaylistController(new SavedPlaylistController.Gateway() {
            public List<io.github.yghfv.saltneri.plugin.host.SaltPlaylistGateway.Choice> choices() {return List.of(new io.github.yghfv.saltneri.plugin.host.SaltPlaylistGateway.Choice("fixture","Fixture playlist",new Object()));}
            public void add(OnlinePlaybackController.Target target,Catalog.Item row,io.github.yghfv.saltneri.plugin.host.SaltPlaylistGateway.Choice choice,java.util.function.BooleanSupplier valid) {
                check(valid.getAsBoolean() && choice.id().equals("fixture") && row.kind()==Catalog.Kind.TRACK,"Invalid saved selection");saved.incrementAndGet();
            }
        },()->true); var player=new OnlinePlaybackController(p->null,()->true,armed::get,new OnlinePlaybackController.Player() {
            public void play(OnlinePlaybackController.Audio audio,java.util.function.BooleanSupplier active) {played.incrementAndGet();}
            public void insert(OnlinePlaybackController.Audio audio,java.util.function.BooleanSupplier active,OnlinePlaybackController.QueueAction action) {
                if(!active.getAsBoolean()) throw new AssertionError("Invalid account"); inserted.add(action);
            }
        },clock,a->(target,quality,scope,request)->{
            resolves.incrementAndGet(); var expiry=clock.instant().plusSeconds(600); boolean net=target.platform()==AccountPlatform.NETEASE;
            boolean qq=target.platform()==AccountPlatform.QQMUSIC;
            return new OnlinePlaybackController.Audio(qq?TrackKey.qqmusic(target.id()):net?TrackKey.netease(target.id()):TrackKey.bilibili(target.id(),123),new Metadata("Fixture track","Artist","",""),
                    new MediaLease(qq?MediaLease.Platform.QQMUSIC:net?MediaLease.Platform.NETEASE:MediaLease.Platform.BILIBILI,URI.create(qq?"https://dl.stream.qqmusic.qq.com/fixture":net?"https://m7.music.126.net/fixture":"https://test.bilivideo.com/fixture"),Map.of(),scope,expiry,4096),expiry);
        }); var library=new LibraryController(p->null,()->true,t->{},()->player.invalidate("Navigation"),clock,a->(query,quality,scope,request)->{
            requests.incrementAndGet(); return new Catalog.Page("Fixture list",List.of(
                    new Catalog.Item(Catalog.Kind.TRACK,query.platform()==AccountPlatform.QQMUSIC?"00123456789abc":query.platform()==AccountPlatform.NETEASE?"123":"BV17x411w7KC","Fixture track","Artist",1000,true),
                    new Catalog.Item(Catalog.Kind.FOLDER,"77","Fixture folder","Artist",2,true)),false,clock.instant().plusSeconds(300),1);
        })) {
            var desk=new NativeDesk(library,player,p->null,(p,a)->{if(a==NativeDesk.Action.ARM) armed.set(true);},()->true);
            desk.savedPlaylists(playlists);
            ClassLoader host=TrackContextMenuRenderTest.class.getClassLoader();
            try(var ui=new SaltNativeUi(host,new NativePageRenderTest.Hooks(),desk,()->true,
                    new CoverLoader(uri->{throw new AssertionError("No covers requested");}),new CoverLoader(uri->{throw new AssertionError("No avatars requested");}))) {
                check(ui.nativeContextMenuAvailable(),"Native track menu binding unavailable");
                Class<?> singleton=Class.forName(SaltPageContentBinding.SINGLETON);
                var cached=(Function3<Object,Composer,Integer,Object>)singleton.getMethod(SaltPageContentBinding.GETTER).invoke(singleton.getField("INSTANCE").get(null));
                var style=Class.forName("androidx.compose.ui.\u04cc").getMethod("\u037f",Composer.class);
                var blur=Class.forName("com.xuncorp.voxzen.ui.component.\u052c");
                var haze=Class.forName("dev.chrisbanes.haze.HazeState").getConstructor().newInstance();
                var hazeValues=new androidx.compose.runtime.ProvidedValue[]{
                        ((androidx.compose.runtime.ProvidableCompositionLocal<Object>)blur.getMethod("\u037f").invoke(null)).provides(haze),
                        ((androidx.compose.runtime.ProvidableCompositionLocal<Object>)blur.getMethod("\u0528").invoke(null)).provides(haze)};
                for(var platform:AccountPlatform.values()) for(String layout:List.of("CARDS","SINGLE","DOUBLE")) {
                    desk.layout(platform,layout);
                    // Clear page memory between independent layout fixtures, not user settings.
                    var memory=SaltNativeUi.class.getDeclaredField("pageMemory"); memory.setAccessible(true); ((Map<?,?>)memory.get(ui)).clear();
                    int width=layout.equals("CARDS")?420:740;
                    Object route=Class.forName(SaltNativeUi.MOD_ROUTE).getConstructor(String.class).newInstance(SaltNativeUi.PLUGIN_ID+"/native/"+platform.name());
                    SwingUtilities.invokeAndWait(()->{
                        var scene=new ImageComposeScene(width,820,DensityKt.Density(1f,1f),LayoutDirection.Ltr,Dispatchers.getUnconfined(),(composer,flags)->{
                            try {if(ui.sidebarStyle()==null) ui.publishSidebarStyle(style.invoke(null,composer));
                                androidx.compose.runtime.CompositionLocalKt.CompositionLocalProvider(hazeValues,(c,f)->{cached.invoke(route,c,f);return Unit.INSTANCE;},composer,0);
                                return Unit.INSTANCE;}
                            catch(ReflectiveOperationException error){throw new AssertionError(error);}
                        });
                        try {
                            render(scene);
                            var refresh=find(scene,"\u5237\u65b0\u5f53\u524d\u5217\u8868",SemanticsActions.INSTANCE.getOnClick());
                            check(refresh!=null,"Refresh missing"); var b=refresh.getBoundsInRoot();
                            check(b.getTop()>=0 && b.getBottom()<=56 && b.getRight()<=width,"Refresh not in fixed top toolbar");
                            int before=requests.get(); click(scene,"\u5237\u65b0\u5f53\u524d\u5217\u8868");
                            check(requests.get()==before+1,"Refresh did not reload the current list once");
                            int resolved=resolves.get(), count=inserted.size();
                            secondary(scene,"Fixture track");
                            check(find(scene,"\u6dfb\u52a0\u5230\u961f\u5217",null)!=null,"Native insertion parent missing");
                            check(find(scene,"\u6dfb\u52a0\u5230\u6b4c\u5355",null)!=null,"Native add-to-playlist parent missing");
                            check(resolves.get()==resolved && inserted.size()==count && played.get()==0,"Opening menu changed playback");
                            String[] labels={"\u5f53\u524d\u64ad\u653e\u540e","\u4e0a\u6b21\u63d2\u64ad\u540e","\u961f\u5217\u672b\u5c3e"};
                            var positions=List.of(OnlinePlaybackController.QueueAction.AFTER_CURRENT,OnlinePlaybackController.QueueAction.AFTER_LAST_ADDED,OnlinePlaybackController.QueueAction.AT_QUEUE_END);
                            for(int i=0;i<labels.length;i++) {
                                if(i>0) secondary(scene,"Fixture track");
                                click(scene,"\u6dfb\u52a0\u5230\u961f\u5217"); click(scene,labels[i]);
                                check(inserted.size()==count+i+1 && inserted.getLast()==positions.get(i) && played.get()==0,"Wrong native insert action");
                                check(find(scene,"\u6dfb\u52a0\u5230\u961f\u5217",null)==null,"Menu did not dismiss");
                            }
                            int saves=saved.get(); secondary(scene,"Fixture track");click(scene,"\u6dfb\u52a0\u5230\u6b4c\u5355");click(scene,"Fixture playlist");
                            check(saved.get()==saves+1 && played.get()==0 && inserted.size()==count+3,"Saving changed playback");
                            secondary(scene,"Fixture folder");
                            check(find(scene,"\u6dfb\u52a0\u5230\u961f\u5217",null)==null,"Folder exposed track commands");
                        } catch(Exception error) {throw new AssertionError(error);}
                        finally {scene.close();}
                    });
                }
            }
        }
        if(failure.get()!=null) throw new AssertionError("Background Compose failure",failure.get());
        System.out.println("PASS: "+checks+" native track menu/toolbar checks; synthetic data, no playback/network");
    }
    private static void secondary(ImageComposeScene scene,String label) throws Exception {
        var row=find(scene,label,SemanticsActions.INSTANCE.getGetTextLayoutResult()); check(row!=null,"Row missing");
        var b=row.getBoundsInRoot(); NativePageRenderTest.tap(scene,(b.getLeft()+b.getRight())/2,(b.getTop()+b.getBottom())/2,System.currentTimeMillis(),1); render(scene);
    }
    private static void collect(SemanticsNode node,Set<String> labels) {
        var key=SemanticsProperties.INSTANCE.getText();
        if(node.getConfig().contains(key)) node.getConfig().get(key).forEach(text->labels.add(text.getText()));
        node.getChildren().forEach(child->collect(child,labels));
    }
}
