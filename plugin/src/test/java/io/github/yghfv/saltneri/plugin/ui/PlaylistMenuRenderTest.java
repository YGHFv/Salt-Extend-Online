package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.*;
import androidx.compose.ui.ImageComposeScene;
import androidx.compose.ui.semantics.*;
import androidx.compose.ui.unit.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import kotlin.Unit;
import kotlinx.coroutines.Dispatchers;
import static io.github.yghfv.saltneri.plugin.ui.HeaderSearchRenderTest.*;

/** Salt's real menu and confirmation with recording callbacks, never a real repository/view model. */
public final class PlaylistMenuRenderTest {
    private static int checks;
    private static void check(boolean ok) {checks++;if(!ok)throw new AssertionError("Playlist menu "+checks);}
    public static void main(String[] args) throws Exception {
        var binding=new SaltPlaylistMenuBindings(PlaylistMenuRenderTest.class.getClassLoader());
        var edits=new AtomicInteger();var deletes=new AtomicInteger();var state=new AtomicReference<Object>();
        Object playlist=Class.forName("com.xuncorp.voxzen.data.entity.Playlist").getConstructor(String.class,String.class,long.class,boolean.class,
                String.class,long.class,int.class,int.class,boolean.class,String.class,String.class,byte[].class,byte[].class)
                .newInstance("fixture-menu","Fixture playlist",0L,false,"",0L,0,0,false,"","",new byte[0],new byte[0]);
        var blur=Class.forName("com.xuncorp.voxzen.ui.component.\u052c");
        var haze=Class.forName("dev.chrisbanes.haze.HazeState").getConstructor().newInstance();
        @SuppressWarnings("unchecked")
        var local=(ProvidableCompositionLocal<Object>)blur.getMethod("\u037f").invoke(null);
        @SuppressWarnings("unchecked")
        var local2=(ProvidableCompositionLocal<Object>)blur.getMethod("\u0528").invoke(null);
        SwingUtilities.invokeAndWait(()->{
            var scene=new ImageComposeScene(700,550,DensityKt.Density(1f,1f),LayoutDirection.Ltr,Dispatchers.getUnconfined(),(composer,flags)->{
                CompositionLocalKt.CompositionLocalProvider(new ProvidedValue[]{local.provides(haze),local2.provides(haze)},(c,f)->{
                    Object popup=binding.state(c);state.set(popup);
                    binding.render(popup,playlist,()->{edits.incrementAndGet();return Unit.INSTANCE;},
                            ()->{deletes.incrementAndGet();return Unit.INSTANCE;},c);
                    return Unit.INSTANCE;
                },composer,0);return Unit.INSTANCE;
            });
            try {
                render(scene);binding.show(state.get());render(scene);
                check(find(scene,"\u7f16\u8f91",SemanticsActions.INSTANCE.getOnClick())!=null);
                check(find(scene,"\u5220\u9664",SemanticsActions.INSTANCE.getOnClick())!=null);
                click(scene,"\u7f16\u8f91");check(edits.get()==1 && deletes.get()==0);
                binding.show(state.get());render(scene);click(scene,"\u5220\u9664");
                check(deletes.get()==0);
                check(find(scene,"Fixture playlist",null)!=null);
                check(find(scene,"\u53d6\u6d88",SemanticsActions.INSTANCE.getOnClick())!=null);
                click(scene,"\u53d6\u6d88");check(deletes.get()==0);
                binding.show(state.get());render(scene);click(scene,"\u5220\u9664");
                click(scene,"\u786e\u8ba4");check(deletes.get()==1 && edits.get()==1);
            }catch(Exception failure){throw new AssertionError(failure);}
            finally {scene.close();}
        });
        System.out.println("PASS: "+checks+" native playlist edit/delete/cancel/confirmation render checks; recording callbacks, no real playlist writes");
    }
}
