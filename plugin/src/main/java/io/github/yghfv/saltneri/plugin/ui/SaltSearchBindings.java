package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.animation.AnimatedContentTransitionScope;
import androidx.compose.animation.ContentTransform;
import androidx.compose.foundation.text.input.TextFieldDecorator;
import androidx.compose.foundation.text.input.TextFieldState;
import androidx.compose.runtime.Composer;
import androidx.compose.runtime.State;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.focus.FocusRequester;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;

/** Official search visuals without registering Salt's global local-library search shortcuts. */
final class SaltSearchBindings {
    private final Constructor<?> decorator;
    private final Method close, transition;
    private final Object closeIcon;
    SaltSearchBindings(ClassLoader host) throws ReflectiveOperationException {
        decorator = Class.forName("com.xuncorp.voxzen.ui.component.\u078f$\u037f", false, host)
                .getDeclaredConstructor(TextFieldState.class, State.class, FocusRequester.class);
        decorator.setAccessible(true);
        Class<?> search = Class.forName("com.xuncorp.voxzen.ui.component.\u078f", false, host);
        transition = search.getDeclaredMethod("\u037f", AnimatedContentTransitionScope.class);
        transition.setAccessible(true);
        if (!TextFieldDecorator.class.isAssignableFrom(decorator.getDeclaringClass())
                || transition.getReturnType() != ContentTransform.class) throw new NoSuchMethodException("Unsupported native search visuals");
        close = Class.forName("androidx.compose.ui.\u0671", false, host).getMethod("\u037f",
                Function0.class, Modifier.class, boolean.class, Function2.class,
                Class.forName("androidx.compose.ui.\u10cd", false, host), Function2.class, Composer.class, int.class, int.class);
        closeIcon = Class.forName("com.xuncorp.voxzen.ui.component.\u058f", false, host).getMethod("\u0528").invoke(null);
        if (close.getReturnType() != void.class || !(closeIcon instanceof Function2)) throw new NoSuchMethodException("Unsupported native search close button");
    }
    TextFieldDecorator decorator(TextFieldState state, State<Boolean> hasText, FocusRequester focus) {
        try { return (TextFieldDecorator)decorator.newInstance(state, hasText, focus); }
        catch (ReflectiveOperationException error) { throw failure(error); }
    }
    ContentTransform transition(AnimatedContentTransitionScope<Boolean> scope) {
        try { return (ContentTransform)transition.invoke(null, scope); }
        catch (ReflectiveOperationException error) { throw failure(error); }
    }
    void close(Modifier modifier, Function0<Unit> action, Composer composer) {
        try { close.invoke(null, action, modifier, false, null, null, closeIcon, composer, 0, 28); }
        catch (ReflectiveOperationException error) { throw failure(error); }
    }
    private static RuntimeException failure(ReflectiveOperationException error) {
        if (error instanceof InvocationTargetException invoked) {
            if (invoked.getCause() instanceof RuntimeException cause) return cause;
            if (invoked.getCause() instanceof Error cause) throw cause;
        }
        return new IllegalStateException("Native search binding failed", error);
    }
}
