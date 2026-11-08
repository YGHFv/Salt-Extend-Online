package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.Composer;
import java.lang.reflect.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

/** Reuse the native playlist flyout, including its edit action and delete confirmation. */
final class SaltPlaylistMenuBindings {
    private final Method rememberPopup,show,dismiss,menu,nativeDelete;
    private final Constructor<?> item;
    private volatile Object viewModel;
    SaltPlaylistMenuBindings(ClassLoader host) throws ReflectiveOperationException {
        Class<?> popup=Class.forName("androidx.compose.ui.\u05dc",false,host);
        Class<?> playlist=Class.forName("com.xuncorp.voxzen.data.entity.Playlist",false,host);
        Class<?> vm=Class.forName("androidx.compose.ui.\u05d0",false,host);
        item=Class.forName("androidx.compose.ui.\u063a$\u0529",false,host).getConstructor(playlist);
        rememberPopup=Class.forName("androidx.compose.ui.\u06c3",false,host).getMethod("\u037f",Composer.class);
        show=popup.getMethod("\u0528");dismiss=popup.getMethod("\u0529");
        Class<?> sidebar=Class.forName(SaltNativeUi.SIDEBAR,false,host);
        menu=sidebar.getDeclaredMethod("\u037f",popup,playlist,Function0.class,Function0.class,Composer.class,int.class);
        nativeDelete=sidebar.getDeclaredMethod("\u037f",popup,vm,item.getDeclaringClass());
        if(!menu.trySetAccessible() || !nativeDelete.trySetAccessible())throw new IllegalAccessException("Native playlist menu inaccessible");
    }
    void viewModel(Object value) {viewModel=value;}
    Object state(Composer composer) {return invoke(rememberPopup,null,composer);}
    void show(Object state) {invoke(show,state);}
    void menu(Object state,Object playlist,Object navigator,java.util.function.BooleanSupplier valid,Composer composer) {
        Object vm=viewModel;
        Function0<Unit> edit=()->{
            if(!valid.getAsBoolean()) {invoke(dismiss,state);return Unit.INSTANCE;}
            invoke(dismiss,state);
            try {
                Object route=Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute$EditPlaylist",false,navigator.getClass().getClassLoader())
                        .getConstructor(String.class).newInstance(playlist.getClass().getMethod("getId").invoke(playlist));
                navigator.getClass().getMethod("navigate",Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute",
                        false,navigator.getClass().getClassLoader())).invoke(navigator,route);
            } catch(ReflectiveOperationException failure) {throw new IllegalStateException("Playlist edit unavailable",failure);}
            return Unit.INSTANCE;
        };
        Function0<Unit> delete=()->{
            if(!valid.getAsBoolean() || vm==null || vm!=viewModel) {invoke(dismiss,state);return Unit.INSTANCE;}
            try {invoke(nativeDelete,null,state,vm,item.newInstance(playlist));}
            catch(ReflectiveOperationException failure) {throw new IllegalStateException("Playlist delete unavailable",failure);}
            return Unit.INSTANCE;
        };
        render(state,playlist,edit,delete,composer);
    }
    void render(Object state,Object playlist,Function0<Unit> edit,Function0<Unit> delete,Composer composer) {
        invoke(menu,null,state,playlist,edit,delete,composer,0);
    }
    void close() {viewModel=null;}
    private static Object invoke(Method method,Object receiver,Object...args) {
        try{return method.invoke(receiver,args);}
        catch(InvocationTargetException failure) {
            if(failure.getCause() instanceof RuntimeException r)throw r;
            throw new IllegalStateException("Native playlist menu failed",failure.getCause());
        } catch(ReflectiveOperationException failure) {throw new IllegalStateException("Native playlist menu failed",failure);}
    }
}
