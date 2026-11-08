package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.window.PopupPositionProvider;
import io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.QueueAction;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;

/** Salt's own blurred track menu, submenu labels and icons; no host assets are bundled. */
final class SaltContextMenuBindings {
    private final Method popup, item, submenu, text;
    private final Function2<Composer, Integer, Unit> addToPlaylist, newPlaylist;
    private final Function2<Composer, Integer, Unit> insertion;
    private final java.util.List<Function2<Composer, Integer, Unit>> positions;

    @SuppressWarnings("unchecked")
    SaltContextMenuBindings(ClassLoader host) throws ReflectiveOperationException {
        popup = Class.forName("com.xuncorp.voxzen.ui.component.\u052c", false, host).getMethod("\u037f",
                PopupPositionProvider.class, Function0.class, Function1.class, Composer.class, int.class, int.class);
        Class<?> scope = Class.forName("androidx.compose.ui.\u0924", false, host);
        item = scope.getMethod("\u037f", Function0.class, boolean.class, Function2.class);
        submenu = scope.getMethod("\u037f", Function1.class, boolean.class, Function2.class);
        Class<?> content = Class.forName("androidx.compose.ui.\u0515", false, host);
        insertion = (Function2<Composer, Integer, Unit>)content.getMethod("\u058f").invoke(null);
        positions = java.util.List.of(
                (Function2<Composer, Integer, Unit>)content.getMethod("\u052f").invoke(null),
                (Function2<Composer, Integer, Unit>)content.getMethod("\u0560").invoke(null),
                (Function2<Composer, Integer, Unit>)content.getMethod("\u0588").invoke(null));
        addToPlaylist=(Function2<Composer,Integer,Unit>)content.getMethod("\u0780").invoke(null);
        newPlaylist=(Function2<Composer,Integer,Unit>)content.getMethod("\u0620").invoke(null);
        text=popup.getDeclaringClass().getMethod("\u037f",String.class,androidx.compose.ui.Modifier.class,Function2.class,boolean.class,Composer.class,int.class,int.class);
    }

    void menu(PopupPositionProvider position, Function0<Unit> dismiss, Consumer<QueueAction> select,
              io.github.yghfv.saltneri.plugin.library.SavedPlaylistController.Snapshot playlists,
              Consumer<String> save,Runnable create,Composer composer) {
        Function1<Object, Unit> children = scope -> {
            QueueAction[] actions = {QueueAction.AFTER_CURRENT, QueueAction.AFTER_LAST_ADDED, QueueAction.AT_QUEUE_END};
            for (int index = 0; index < actions.length; index++) {
                QueueAction action = actions[index];
                Function0<Unit> click = () -> { dismiss.invoke(); select.accept(action); return Unit.INSTANCE; };
                invoke(item, scope, click, true, positions.get(index));
            }
            return Unit.INSTANCE;
        };
        Function1<Object,Unit> playlistChildren=scope->{
            if(playlists.loading() || playlists.choices().isEmpty()) {
                String label=playlists.loading()?"\u6b63\u5728\u8bfb\u53d6\u6b4c\u5355":playlists.status().isEmpty()?"\u6682\u65e0\u6b4c\u5355":playlists.status();
                invoke(item,scope,(Function0<Unit>)()->Unit.INSTANCE,false,label(label));
            }
            for(var playlist:playlists.choices()) invoke(item,scope,(Function0<Unit>)()->{
                dismiss.invoke();save.accept(playlist.id());return Unit.INSTANCE;
            },!playlists.loading(),label(playlist.title()));
            invoke(item,scope,(Function0<Unit>)()->{dismiss.invoke();create.run();return Unit.INSTANCE;},true,newPlaylist);
            return Unit.INSTANCE;
        };
        Function1<Object, Unit> content = scope -> {
            invoke(submenu, scope, children, true, insertion);
            invoke(submenu,scope,playlistChildren,true,addToPlaylist);
            return Unit.INSTANCE;
        };
        invoke(popup, null, position, dismiss, content, composer, 0, 0);
    }
    private Function2<Composer,Integer,Unit> label(String label) {
        return (composer,flags)->{invoke(text,null,label,null,null,false,composer,0,14);return Unit.INSTANCE;};
    }

    private static void invoke(Method method, Object receiver, Object... args) {
        try { method.invoke(receiver, args); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native track menu unavailable", failure); }
    }
}
