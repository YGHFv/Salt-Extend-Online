package io.github.yghfv.saltneri.plugin.ui;

import java.awt.Window;
import java.lang.reflect.*;
import java.nio.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.jetbrains.skia.*;

/** Bounded D3D11 frame readback avoids the host's incompatible Skiko texture-sharing ABI. */
public final class SaltVideoSurface implements AutoCloseable {
    private final Object player;
    private final Consumer<Throwable> failure;
    private final Runnable redraw;
    private final Method configure,readPixels,releaseSurface,dimension,frameState;
    private final Object handle;
    private final long pointer;
    private final ScheduledExecutorService reader;
    private final Object nativeLock=new Object();
    private volatile boolean closed,reported;
    private int configuredWidth,configuredHeight;
    private Image image; // Owned and retired only on the Compose/Swing UI thread.
    private final AtomicReference<Image> pending=new AtomicReference<>();
    private byte[] bgra;
    private IntBuffer conversion;
    private final int[] dims=new int[2];
    private long lastFrame,lastState=Long.MIN_VALUE,lastCapture;
    private volatile long publishedFrames,drawnFrames;

    public SaltVideoSurface(Object player,Window window,Runnable redraw,Consumer<Throwable> failure) throws Exception {
        this.player=player;this.failure=failure;this.redraw=redraw;
        handle=player.getClass().getMethod("\u052b").invoke(player);
        pointer=(long)handle.getClass().getMethod("\u037f").invoke(handle);
        dimension=handle.getClass().getMethod("\u037f",String.class);
        Class<?> nativeType=Class.forName("org.openani.mediamp.mpv.MPVHandleDesktop",false,player.getClass().getClassLoader());
        configure=nativeType.getMethod("nSetSurfaceConfigD3D11",long.class,int.class,int.class,long.class);
        readPixels=nativeType.getMethod("nReadSurfacePixelsD3D11",long.class,int[].class);
        frameState=nativeType.getMethod("nGetFrameStateD3D11",long.class);
        releaseSurface=player.getClass().getMethod("\u052f");
        reader=Executors.newSingleThreadScheduledExecutor(r->{
            Thread thread=new Thread(r,"salt-video-frames");thread.setDaemon(true);return thread;
        });
        lastFrame=System.nanoTime();
        reader.scheduleAtFixedRate(this::capture,0,16_666_667,TimeUnit.NANOSECONDS);
    }
    private void capture() {
        synchronized(nativeLock) {
            if(closed || reported)return;
            try {
                long now=System.nanoTime();
                // A late poll must not trigger a burst of catch-up copies.
                if(now-lastCapture<TimeUnit.MILLISECONDS.toNanos(15))return;
                lastCapture=now;
                if(configuredWidth==0) {
                    int sourceWidth=(int)dimension.invoke(handle,"width"),sourceHeight=(int)dimension.invoke(handle,"height");
                    if(sourceWidth<1 || sourceHeight<1)return;
                    // Keep one bounded native ring for the player lifetime. Compose scales its raster
                    // to the window; native resize/retirement acknowledgements can hang while paused.
                    double scale=Math.min(1,Math.min(1280d/sourceWidth,720d/sourceHeight));
                    int w=Math.max(1,(int)(sourceWidth*scale)),h=Math.max(1,(int)(sourceHeight*scale));
                    if(!Boolean.TRUE.equals(configure.invoke(null,pointer,w,h,0L)))
                        throw new IllegalStateException("Video frame configuration rejected");
                    configuredWidth=w;configuredHeight=h;
                    lastState=Long.MIN_VALUE;
                }
                long state=(long)frameState.invoke(null,pointer);
                if(state==lastState)return;
                int[] pixels=(int[])readPixels.invoke(null,pointer,dims);
                if(pixels==null) {
                    if(System.nanoTime()-lastFrame>TimeUnit.SECONDS.toNanos(20))
                        throw new IllegalStateException("Video frame timed out");
                    return;
                }
                if(dims[0]<1 || dims[1]<1 || dims[0]>1280 || dims[1]>720
                        || pixels.length!=dims[0]*dims[1])throw new IllegalStateException("Invalid video pixels");
                lastFrame=System.nanoTime();lastState=state;
                if(bgra==null || bgra.length!=pixels.length*4) {
                    bgra=new byte[pixels.length*4];
                    conversion=ByteBuffer.wrap(bgra).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                }
                conversion.clear();conversion.put(pixels);
                // makeRaster copies into immutable Skia storage. Conversion/allocation stays off the UI thread.
                Image next=Image.Companion.makeRaster(
                        new ImageInfo(dims[0],dims[1],ColorType.BGRA_8888,ColorAlphaType.OPAQUE),bgra,dims[0]*4);
                Image superseded=pending.getAndSet(next);
                if(superseded!=null)superseded.close();
                else SwingUtilities.invokeLater(this::publish);
            } catch(Exception | LinkageError error) {fail(error);}
        }
    }
    private void publish() {
        Image next=pending.getAndSet(null);
        if(next==null)return;
        try {
            if(closed || reported){next.close();return;}
            Image retired=image;image=next;if(retired!=null)retired.close();
            publishedFrames++;redraw.run();
        } catch(Exception | LinkageError error) {fail(error);}
    }
    public Image frame(int width,int height) {
        if(closed || reported || width<1 || height<1)return null;
        if(image!=null)drawnFrames++;return image;
    }
    long publishedFrames(){return publishedFrames;}
    long drawnFrames(){return drawnFrames;}
    public void fail(Throwable error) {
        if(closed || reported)return;
        reported=true;failure.accept(error);
    }
    @Override public void close() {
        synchronized(nativeLock) {
            if(closed)return;closed=true;reader.shutdownNow();
            Image unpublished=pending.getAndSet(null);if(unpublished!=null)unpublished.close();
            // Stop native reads before the player can destroy its mpv handle.
            try {releaseSurface.invoke(player);}catch(Exception ignored){}
        }
        SaltPageContentBinding.onUiThread(()->{if(image!=null){image.close();image=null;}});
    }
}
