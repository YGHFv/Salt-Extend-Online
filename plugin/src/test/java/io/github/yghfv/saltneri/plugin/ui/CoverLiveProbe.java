package io.github.yghfv.saltneri.plugin.ui;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.library.CatalogHttp;
import io.github.yghfv.saltneri.plugin.library.CoverSource;
import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Clock;
import javax.imageio.ImageIO;

/** Opt-in public cover check. Never emits/stores image bytes, URLs, account data or audio. */
public final class CoverLiveProbe {
    public static void main(String[] args) {
        if (args.length != 1 || !args[0].equals("--explicit-public-cover")) {
            System.err.println("Requires --explicit-public-cover."); System.exit(2); return;
        }
        try (RequestScope request = new RequestScope(() -> true);
             CatalogHttp api = new CatalogHttp(AccountPlatform.NETEASE,null,Clock.systemUTC(),CatalogHttp::openPinned);
             CoverLoader loader = new CoverLoader()) {
            var root = api.json(URI.create("https://music.163.com/api/song/detail?ids=%5B347230%5D"),null,request);
            if (BoundedJson.integer(root.get("code")) != 200) throw new java.io.IOException();
            var song = BoundedJson.object(BoundedJson.array(root.get("songs")).getFirst());
            var album = BoundedJson.object(song.get("album"));
            URI cover = CoverSource.parse(AccountPlatform.NETEASE,BoundedJson.string(album.get("picUrl")));
            if (cover == null) throw new java.io.IOException();
            var image = ImageIO.read(new ByteArrayInputStream(loader.load(cover,request)));
            System.out.println("PASS: public NetEase list cover decoded to " + image.getWidth() + "x" + image.getHeight());
            image.flush();
            System.out.println("No image bytes/URL saved or printed; no account, host, audio or queue accessed.");
        } catch (Exception failure) {
            System.err.println("STOP: public cover could not load (" + failure.getClass().getSimpleName() + "); no response data printed.");
            System.exit(3);
        }
    }
}
