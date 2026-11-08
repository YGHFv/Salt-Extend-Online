package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.foundation.layout.PaddingValues;
import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;
import java.lang.reflect.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;

/** Invoke the installed BasicScreen titlebar and application back button, not a visual copy. */
final class SaltSecondaryHeaderBindings {
    private final Method titlebar, back;
    private final Object defaults;
    SaltSecondaryHeaderBindings(ClassLoader host) throws ReflectiveOperationException {
        titlebar=Class.forName("androidx.compose.ui.\u09bd",false,host).getDeclaredMethod("\u037f",
                Function2.class,Modifier.class,String.class,String.class,Function2.class,PaddingValues.class,
                Composer.class,int.class,int.class);
        Class<?> type=Class.forName("androidx.compose.ui.\u0f51",false,host);
        defaults=type.getField("\u037f").get(null);
        back=type.getMethod("\u037f",Function0.class,Modifier.class,boolean.class,Composer.class,int.class,int.class);
        if(!titlebar.trySetAccessible()) throw new IllegalAccessException("Native titlebar inaccessible");
    }
    void back(Function0<Unit> action,Modifier modifier,Composer composer) {
        try {back.invoke(defaults,action,modifier,true,composer,0,0);}
        catch(ReflectiveOperationException failure) {throw new IllegalStateException("Native back button failed",failure);}
    }
    void title(String title,String subtitle,Modifier modifier,Object action,Object tools,Composer composer) {
        invoke(titlebar,action,modifier,title,subtitle,tools,null,composer,0,32);
    }
    private static void invoke(Method method,Object... args) {
        try {method.invoke(null,args);}
        catch(InvocationTargetException error) {
            if(error.getCause() instanceof RuntimeException failure) throw failure;
            if(error.getCause() instanceof Error failure) throw failure;
            throw new IllegalStateException("Native secondary header failed",error.getCause());
        } catch(ReflectiveOperationException error) {throw new IllegalStateException("Native secondary header failed",error);}
    }
}
