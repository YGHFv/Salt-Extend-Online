package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;
import java.lang.reflect.Method;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;

/** Reuse installed Salt components; no host drawable or implementation is redistributed. */
final class SaltToolbarBindings {
    private final Method button;
    private final Function2<Composer, Integer, Unit> search, sort;
    @SuppressWarnings("unchecked")
    SaltToolbarBindings(ClassLoader host) throws ReflectiveOperationException {
        button = Class.forName("com.xuncorp.voxzen.ui.component.\u0793", false, host).getMethod("\u037f",
                Function0.class, String.class, Modifier.class, boolean.class, Function2.class, String.class,
                Function2.class, Function2.class, Composer.class, int.class, int.class);
        search = (Function2<Composer, Integer, Unit>)Class.forName("com.xuncorp.voxzen.ui.component.\u058f", false, host)
                .getMethod("\u037f").invoke(null);
        sort = (Function2<Composer, Integer, Unit>)Class.forName("androidx.compose.ui.\u0a89", false, host)
                .getMethod("\u0529").invoke(null);
    }
    void button(String label, Modifier modifier, Function0<Unit> click, Object content, Composer composer) {
        try { button.invoke(null, click, label, modifier, true, null, null, null, content, composer, 0, 112); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Native toolbar invocation failed", error); }
    }
    void icon(boolean sorting, Composer composer) { (sorting ? sort : search).invoke(composer, 0); }
}
