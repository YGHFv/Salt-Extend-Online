package io.github.yghfv.saltneri.plugin.host;

import java.lang.reflect.*;
import java.util.*;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowKt;

/** Native repositories, used on a worker only; never scans/parses a remote track as local audio. */
public final class SaltPlaylistGateway {
    public record Choice(String id,String title,Object nativePlaylist) { }
    private final Class<?> playlistRepo,trackRepo,track;
    private final Method list,find,insert,add,database,trackDao;
    private final java.util.function.Function<Class<?>,Object> repositories;
    public SaltPlaylistGateway(ClassLoader host) throws ReflectiveOperationException {
        this(host,null);
    }
    SaltPlaylistGateway(ClassLoader host,java.util.function.Function<Class<?>,Object> repositories) throws ReflectiveOperationException {
        this.repositories=repositories;
        playlistRepo=Class.forName("androidx.compose.ui.\u0b9a",false,host);
        trackRepo=Class.forName("androidx.compose.ui.\u1005",false,host);
        track=Class.forName("com.xuncorp.voxzen.data.entity.Track",false,host);
        Class<?> playlist=Class.forName("com.xuncorp.voxzen.data.entity.Playlist",false,host);
        list=playlistRepo.getMethod("\u037f");
        find=trackRepo.getMethod("\u037f",String.class,Continuation.class);
        database=trackRepo.getMethod("\u037f");
        trackDao=Class.forName("com.xuncorp.voxzen.data.AppDatabase",false,host).getMethod("\u0529");
        // TrackRepo's Track[] overload is DELETE. Use the DAO's @Insert(onConflict=IGNORE).
        insert=Class.forName("androidx.compose.ui.\u04b9",false,host).getMethod("\u037f",List.class,Continuation.class);
        add=playlistRepo.getMethod("\u037f",List.class,playlist,Continuation.class);
    }
    private Object repository(Class<?> type) throws ReflectiveOperationException {
        if(repositories!=null) return repositories.apply(type);
        Object companion=type.getField("\u037f").get(null);
        return companion.getClass().getMethod("\u037f").invoke(companion);
    }
    @SuppressWarnings("unchecked")
    public List<Choice> choices() throws Exception {
        return choices(0);
    }
    @SuppressWarnings("unchecked")
    public List<Choice> choices(long timeoutMillis) throws Exception {
        Flow<List<Object>> flow=(Flow<List<Object>>)list.invoke(repository(playlistRepo));
        List<Object> values=BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,(scope,continuation)->
                timeoutMillis>0?kotlinx.coroutines.TimeoutKt.withTimeout(timeoutMillis,(s,c)->FlowKt.first(flow,c),continuation)
                        :FlowKt.first(flow,continuation));
        List<Choice> result=new ArrayList<>();
        for(Object value:values) result.add(new Choice((String)value.getClass().getMethod("getId").invoke(value),
                (String)value.getClass().getMethod("getTitle").invoke(value),value));
        return List.copyOf(result);
    }
    public Object nativeTrack(SavedTrackStore.Entry entry) throws Exception {
        // Explicit library insertion needs a real local file size; bulk playback does not.
        entry.cover().ensureFile();
        Constructor<?> constructor=Arrays.stream(track.getConstructors()).filter(c->c.getParameterCount()==23).findFirst().orElseThrow();
        long now=System.currentTimeMillis();
        Class<?> gain=constructor.getParameterTypes()[21]; Object companion=gain.getField("Companion").get(null);
        Object empty=companion.getClass().getMethod("getEmpty").invoke(companion);
        return constructor.newInstance(entry.trackId(),entry.title(),entry.artist(),"","","",0,0,false,0,0,entry.path(),true,
                java.nio.file.Files.size(java.nio.file.Path.of(entry.path())),now,now,0,entry.duration(),0,0,0f,empty,"");
    }
    public void add(SavedTrackStore.Entry entry,Choice choice,java.util.function.BooleanSupplier valid) throws Exception {
        if(!valid.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        // Refresh identity before writing, so a removed playlist cannot be recreated by a stale menu.
        Choice current=choices().stream().filter(p->p.id().equals(choice.id())).findFirst().orElseThrow(()->new IllegalStateException("Playlist removed"));
        Object repository=repository(trackRepo), existing=suspend(find,repository,entry.trackId());
        if(existing==null) {
            if(!valid.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            suspend(insert,trackDao.invoke(database.invoke(repository)),List.of(nativeTrack(entry)));
            existing=suspend(find,repository,entry.trackId());
        }
        if(existing==null) throw new IllegalStateException("Native track insertion did not persist the saved ID");
        if(!entry.path().equals(track.getMethod("getPath").invoke(existing))) throw new IllegalStateException("Saved track path conflict");
        if(!valid.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        suspend(add,repository(playlistRepo),List.of(existing),current.nativePlaylist());
    }
    private static Object suspend(Method method,Object receiver,Object... args) throws Exception {
        return BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,(scope,continuation)->{
            Object[] params=Arrays.copyOf(args,args.length+1); params[args.length]=continuation;
            try { return method.invoke(receiver,params); }
            catch(ReflectiveOperationException failure) { throw new IllegalStateException("Native playlist operation failed",failure); }
        });
    }
}
