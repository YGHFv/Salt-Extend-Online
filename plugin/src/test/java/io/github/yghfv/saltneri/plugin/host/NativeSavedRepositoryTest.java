package io.github.yghfv.saltneri.plugin.host;

import androidx.room.RoomDatabase;
import androidx.sqlite.SQLiteDriver;
import com.xuncorp.voxzen.data.AppDatabase;
import com.xuncorp.voxzen.data.AppDatabase_Impl;
import com.xuncorp.voxzen.data.entity.Playlist;
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.library.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import kotlin.coroutines.*;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.*;

/** Isolated native Room schema/repositories, never the user's AppDatabase singleton. */
public final class NativeSavedRepositoryTest {
    private static int checks;
    private static void check(boolean value,String message) {checks++;if(!value) throw new AssertionError(message);}
    private static Object suspend(Method method,Object receiver,Object... args) throws Exception {
        return BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,(scope,continuation)->{
            Object[] params=Arrays.copyOf(args,args.length+1);params[args.length]=continuation;
            try{return method.invoke(receiver,params);}catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
        });
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("salt-native-playlist-db-").toRealPath();ClassLoader host=NativeSavedRepositoryTest.class.getClassLoader();
        SQLiteDriver driver=(SQLiteDriver)Class.forName("androidx.sqlite.driver.bundled.\u0528").getConstructor().newInstance();
        AppDatabase database=new RoomDatabase.Builder<AppDatabase>(kotlin.jvm.internal.Reflection.getOrCreateKotlinClass(AppDatabase.class),root.resolve("fixture.db").toString(),AppDatabase_Impl::new)
                .setDriver(driver).setQueryCoroutineContext(Dispatchers.getIO()).build();
        try(var store=new SavedTrackStore(root.resolve("tracks"))) {
            Class<?> tracks=Class.forName("androidx.compose.ui.\u1005"),playlists=Class.forName("androidx.compose.ui.\u0b9a");
            Object trackRepo=tracks.getConstructor(AppDatabase.class).newInstance(database);
            var constructor=playlists.getDeclaredConstructor(AppDatabase.class);constructor.setAccessible(true);Object playlistRepo=constructor.newInstance(database);
            // Local fixtures have no synchronization binding or remote metadata preparation.
            for(String name:List.of("\u052a","\u052b")) {
                var field=playlists.getDeclaredField(name);field.setAccessible(true);
                field.set(playlistRepo,(Function2<Object,Continuation<?>,Object>)(value,continuation)->name.equals("\u052a")?null:value);
            }
            var gateway=new SaltPlaylistGateway(host,type->{
                if(type==tracks) return trackRepo;if(type==playlists)return playlistRepo;throw new AssertionError("Unexpected repository");
            });
            check(gateway.choices().isEmpty(),"Fixture not empty");
            Playlist playlist=new Playlist("fixture-playlist","Fixture playlist",0,false,"",0,0,0,false,"local","",new byte[0],new byte[0]);
            Object dao=AppDatabase.class.getMethod("\u0560").invoke(database);
            Method insert=Class.forName("androidx.compose.ui.\u04f0").getMethod("\u037f",Playlist[].class,Continuation.class);
            suspend(insert,dao,(Object)new Playlist[]{playlist});
            var choices=gateway.choices();check(choices.size()==1,"Native playlist list not found");
            var entry=store.save(new OnlinePlaybackController.Target(AccountPlatform.NETEASE,"123"),new Catalog.Item(Catalog.Kind.TRACK,"123","Fixture song","Fixture artist",1000,true));
            check(!Files.exists(Path.of(entry.path())),"Identity eagerly created a cover file");
            gateway.add(entry,choices.getFirst(),()->true);
            check(Files.size(Path.of(entry.path()))==42,"Native insertion failed to materialize lazy artwork");
            Object saved=suspend(tracks.getMethod("\u037f",String.class,Continuation.class),trackRepo,entry.trackId());
            check(saved!=null && entry.path().equals(saved.getClass().getMethod("getPath").invoke(saved)),"Track not saved");
            try(var connection=driver.open(root.resolve("fixture.db").toString());var statement=connection.prepare("UPDATE Track SET title = 'User title' WHERE id = ?")) {
                statement.bindText(1,entry.trackId());statement.step();
            }
            gateway.add(entry,choices.getFirst(),()->true);
            Object flow=playlists.getMethod("\u0528",String.class).invoke(playlistRepo,playlist.getId());
            Object contents=BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,(scope,continuation)->kotlinx.coroutines.flow.FlowKt.first((kotlinx.coroutines.flow.Flow<?>)flow,continuation));
            check(contents!=null,"Saved playlist content missing");
            var ordered=(List<?>)contents.getClass().getMethod("getTracks").invoke(contents);
            check(ordered.size()==1,"Duplicate playlist membership");
            Object restored=ordered.getFirst().getClass().getMethod("getTrack").invoke(ordered.getFirst());
            check(restored.getClass().getMethod("getTitle").invoke(restored).equals("User title"),"User metadata overwritten");
            var items=(List<?>)Class.forName("androidx.compose.ui.\u0b71").getMethod("\u037f",Collection.class).invoke(null,List.of(restored));
            var profile=Salt1193Probe.inspect(host,"1.19.3","Steam");String[] fields=profile.mediaFactory().read(items.getFirst());
            check(fields[0].equals(entry.trackId()) && fields[5].equals(entry.path()),"Native playlist reader lost playback mapping");
            try {gateway.add(entry,choices.getFirst(),()->false);throw new AssertionError("Revoked save accepted");}catch(java.util.concurrent.CancellationException expected){checks++;}
        } finally {database.close();}
        System.out.println("PASS: "+checks+" isolated native playlist repository checks; no user database or playback");
    }
}
