package io.github.yghfv.saltneri.plugin.host;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Synthetic artwork through the actual host tag reader/Skia; no playback controller initialization. */
public final class PlaybackCoverContractTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Playback cover check " + checks); }
    public static byte[] png() throws IOException {
        return png(96);
    }
    private static byte[] png(int edge) throws IOException {
        BufferedImage image = new BufferedImage(edge,edge,BufferedImage.TYPE_INT_ARGB);
        for (int y=0;y<edge;y++) for (int x=0;x<edge;x++) image.setRGB(x,y,x<edge/2?0xffdd5500:0xff008866);
        try (var bytes = new ByteArrayOutputStream()) { ImageIO.write(image,"png",bytes); return bytes.toByteArray(); }
    }
    public static void main(String[] args) throws Exception {
        byte[] png = png(); Path path;
        String reserved;
        try(PlaybackCoverFile cover=PlaybackCoverFile.reserve()) {
            reserved=cover.path(); Path future=Path.of(reserved);
            check(PlaybackCoverFile.isPath(reserved) && !Files.exists(future.getParent()));
            cover.clear(); check(!Files.exists(future.getParent()));
            cover.replace(png); check(Files.isRegularFile(future) && cover.path().equals(reserved));
            cover.clear(); check(Files.size(future)==42);
            cover.replace(png); check(Files.size(future)>42);
        }
        check(!PlaybackCoverFile.isPath(reserved));
        try(PlaybackCoverFile cover=PlaybackCoverFile.reserve()) { reserved=cover.path(); }
        check(!PlaybackCoverFile.isPath(reserved) && !Files.exists(Path.of(reserved).getParent()));
        try (PlaybackCoverFile cover = PlaybackCoverFile.create(png)) {
            path = Path.of(cover.path()); check(PlaybackCoverFile.isPath(cover.path()));
            byte[] flac = Files.readAllBytes(path); check(flac.length < 132000 && new String(flac,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("fLaC"));
            check(!PlaybackCoverFile.isPath(path.getParent().resolve("../elsewhere.flac").toString()));
            check(!PlaybackCoverFile.isPath("saltneri://media/fake"));
            Class<?> tags = Class.forName("com.xuncorp.voxzen.tag.TagParser"); Object parser = tags.getField("INSTANCE").get(null);
            var read = tags.getDeclaredMethod("readArtworkBinaryData", File.class); check(read.trySetAccessible());
            byte[] extracted = (byte[])read.invoke(parser, path.toFile());
            if (!Arrays.equals(extracted,png)) throw new AssertionError("Synthetic artwork mismatch: expected " + png.length + ", got " + (extracted == null ? "null" : extracted.length));
            check(Arrays.equals(extracted,png));
            Object image = tags.getMethod("readImage-0E7RQCE",String.class,int.class,int.class).invoke(parser,cover.path(),96,96);
            kotlin.ResultKt.throwOnFailure(image); check(image instanceof androidx.compose.ui.graphics.ImageBitmap);
            var bitmap = androidx.compose.ui.graphics.SkiaImageAsset_skikoKt.asSkiaBitmap((androidx.compose.ui.graphics.ImageBitmap)image);
            try { check(bitmap.getWidth()==96 && bitmap.getHeight()==96); check(bitmap.getColor(12,12)==0xffdd5500); }
            finally { bitmap.close(); }
            try (PlaybackCoverFile second = PlaybackCoverFile.create(png)) { check(!second.path().equals(cover.path())); }
        }
        check(!Files.exists(path) && !Files.exists(path.getParent()));
        try (PlaybackCoverFile cover = PlaybackCoverFile.create(png(1024))) {
            Class<?> tags = Class.forName("com.xuncorp.voxzen.tag.TagParser"); Object parser = tags.getField("INSTANCE").get(null);
            Object image = tags.getMethod("readImage-0E7RQCE",String.class,int.class,int.class).invoke(parser,cover.path(),800,800);
            kotlin.ResultKt.throwOnFailure(image);
            var bitmap = androidx.compose.ui.graphics.SkiaImageAsset_skikoKt.asSkiaBitmap((androidx.compose.ui.graphics.ImageBitmap)image);
            try { check(bitmap.getWidth() >= 800 && bitmap.getHeight() >= 800); check(bitmap.getColor(12,12) == 0xffdd5500); }
            finally { bitmap.close(); }
        }
        byte[] wrongSize = png.clone(); java.nio.ByteBuffer.wrap(wrongSize,16,4).putInt(2048);
        byte[] landscape = io.github.yghfv.saltneri.plugin.ui.CoverLoaderContractTest.png(1024,576);
        try (PlaybackCoverFile cover = PlaybackCoverFile.create(landscape)) {
            Class<?> tags = Class.forName("com.xuncorp.voxzen.tag.TagParser"); Object parser = tags.getField("INSTANCE").get(null);
            var read = tags.getDeclaredMethod("readArtworkBinaryData", File.class); check(read.trySetAccessible());
            byte[] extracted = (byte[])read.invoke(parser, Path.of(cover.path()).toFile());
            check(Arrays.equals(landscape, extracted));
            var image = ImageIO.read(new ByteArrayInputStream(extracted));
            check(image.getWidth() == 1024 && image.getHeight() == 576);
        }
        for (byte[] invalid : new byte[][]{new byte[0], new byte[PlaybackCoverFile.MAX_PNG_BYTES + 1], Arrays.copyOf(png,23), wrongSize}) {
            try { PlaybackCoverFile.create(invalid); throw new AssertionError("Bad cover accepted"); }
            catch (IOException expected) { checks++; }
        }
        System.out.println("PASS: " + checks + " metadata-only artwork files, actual Salt tag extraction and Skia decode; no audio/queue");
    }
}
