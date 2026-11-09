package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.ImageComposeScene;
import androidx.compose.ui.unit.*;
import androidx.compose.ui.semantics.*;
import com.xuncorp.spw.workshop.api.hook.*;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.library.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import kotlin.Unit;
import kotlinx.coroutines.Dispatchers;
import kotlin.jvm.functions.Function3;
import org.jetbrains.skia.*;

/** Actual Salt Compose cookie form and recommendation navigation; synthetic input, no login or playback. */
public final class CookieLoginRenderTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("Cookie render check "+checks);}
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var clock=Clock.systemUTC();var submissions=new AtomicInteger();var last=new AtomicReference<String>();
        var query=new AtomicReference<Catalog.Query>();
        var logged=new AtomicBoolean();
        var output=java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).resolve("cookie-ui-render");Files.createDirectories(output);
        try(var player=new OnlinePlaybackController(p->null,()->true,()->false,(a,s)->{throw new AssertionError("No queue");},clock,
                a->(t,q,s,r)->{throw new AssertionError("No audio");});
            var library=new LibraryController(p->null,()->true,t->{throw new AssertionError("No prepare");},()->{},clock,
                a->(q,k,s,r)->{query.set(q);return new Catalog.Page("发现",List.of(),false,clock.instant().plusSeconds(300),1);})) {
            var desk=new NativeDesk(library,player,p->new AccountLoginController.Snapshot(logged.get()?AccountLoginController.Phase.LOGGED_IN:AccountLoginController.Phase.IDLE,
                    "",null,logged.get()?"42":""),(p,a)->{},()->true);
            desk.cookieLogin((p,c)->{check(c.equals("MUSIC_U=SYNTHETIC_SECRET;"));last.set(c);submissions.incrementAndGet();logged.set(true);});
            try(var ui=new SaltNativeUi(CookieLoginRenderTest.class.getClassLoader(),new NativePageRenderTest.Hooks(),desk,()->true)) {
                SwingUtilities.invokeAndWait(()->{});
                Class<?> singleton=Class.forName(SaltPageContentBinding.SINGLETON);
                var cached=(Function3<Object,Composer,Integer,Object>)singleton.getMethod(SaltPageContentBinding.GETTER).invoke(singleton.getField("INSTANCE").get(null));
                var style=Class.forName("androidx.compose.ui.\u04cc").getMethod("\u037f",Composer.class);
                for(var platform:List.of(AccountPlatform.NETEASE,AccountPlatform.QQMUSIC)) for(int width:new int[]{740,420}) {
                    logged.set(false);
                    var route=Class.forName(SaltNativeUi.MOD_ROUTE).getConstructor(String.class).newInstance(SaltNativeUi.PLUGIN_ID+"/native/"+platform.name());
                    SwingUtilities.invokeAndWait(()->{
                        var scene=new ImageComposeScene(width,820,DensityKt.Density(1f,1f),LayoutDirection.Ltr,Dispatchers.getUnconfined(),(composer,flags)->{
                            try {if(ui.sidebarStyle()==null)ui.publishSidebarStyle(style.invoke(null,composer));cached.invoke(route,composer,flags);return Unit.INSTANCE;}
                            catch(ReflectiveOperationException error){throw new AssertionError(error);}
                        });
                        try {
                            HeaderSearchRenderTest.render(scene);
                            var labels=platform==AccountPlatform.NETEASE?List.of("推荐歌单","每日推荐","音乐漫游","私人歌单","推荐新歌"):List.of("推荐歌单","每日推荐","猜你喜欢");
                            for(String label:labels) {
                                var tab=HeaderSearchRenderTest.find(scene,label,SemanticsActions.INSTANCE.getOnClick());check(tab!=null);
                                var b=tab.getBoundsInRoot();check(b.getLeft()>=0 && b.getRight()<=width && b.getHeight()>=40);
                            }
                            HeaderSearchRenderTest.click(scene,"每日推荐");
                            check(query.get().platform()==platform && query.get().view()==Catalog.View.DAILY);
                            HeaderSearchRenderTest.click(scene,"填写 Cookie 登录（临时方式）");
                            var field=HeaderSearchRenderTest.find(scene,"Cookie 登录输入",SemanticsActions.INSTANCE.getSetText());
                            check(field!=null && field.getConfig().contains(SemanticsProperties.INSTANCE.getPassword()));
                            check(field.getBoundsInRoot().getLeft()>=0 && field.getBoundsInRoot().getRight()<=width);
                            HeaderSearchRenderTest.type(scene,"MUSIC_U=SYNTHETIC_SECRET;");
                            try(var image=scene.render(System.nanoTime());var data=image.encodeToData(EncodedImageFormat.PNG,100,0)) {
                                Files.write(output.resolve(platform.name().toLowerCase(Locale.ROOT)+"-cookie-"+width+".png"),data.getBytes());
                            }
                            HeaderSearchRenderTest.click(scene,"取消");
                            HeaderSearchRenderTest.click(scene,"填写 Cookie 登录（临时方式）");
                            check(HeaderSearchRenderTest.draft(scene).isEmpty());
                            HeaderSearchRenderTest.type(scene,"MUSIC_U=SYNTHETIC_SECRET;");
                            int before=submissions.get();
                            HeaderSearchRenderTest.click(scene,"验证并登录");
                            check(submissions.get()==before+1 && last.get().equals("MUSIC_U=SYNTHETIC_SECRET;"));
                            check(HeaderSearchRenderTest.find(scene,"Cookie 登录输入",SemanticsActions.INSTANCE.getSetText())==null);
                            HeaderSearchRenderTest.click(scene,"账号菜单");
                            check(HeaderSearchRenderTest.find(scene,"填写 Cookie 登录",SemanticsActions.INSTANCE.getOnClick())!=null);
                            HeaderSearchRenderTest.click(scene,"填写 Cookie 登录");
                            check(HeaderSearchRenderTest.draft(scene).isEmpty());
                        } catch(Exception error){throw new AssertionError(error);}
                        finally {scene.close();}
                    });
                }
            }
        }
        System.out.println("PASS: "+checks+" actual Compose masked cookie input/cancel/submit/account menu and recommendation tabs at 740/420px; synthetic only");
    }
}
