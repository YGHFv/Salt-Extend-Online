package io.github.yghfv.saltneri.plugin.ui;

import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.net.PinnedHttpsContractTest;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;

/** Exercise the actual pinned HTTPS cancellation path with a blocked socket close. */
public final class UiCancellationContractTest {
    private static void check(boolean value,String text) {if(!value)throw new AssertionError(text);}
    public static void main(String[] args) throws Exception {
        var reading=new CountDownLatch(1);var unblock=new CountDownLatch(1);var disconnected=new CountDownLatch(1);
        var oldPage=new AtomicBoolean(true);var finished=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        URI cover=URI.create("https://i0.hdslb.com/bfs/archive/fixture.jpg");
        try(var covers=new CoverLoader(uri->PinnedHttpsContractTest.slowClosingFixture(uri,reading,unblock,disconnected))) {
            covers.request("old-page",cover,oldPage::get);
            check(reading.await(3,TimeUnit.SECONDS),"Fixture did not enter network read");
            oldPage.set(false);
            SwingUtilities.invokeLater(()->{
                try {covers.request("new-page",cover,()->true);covers.releaseRequests();}
                catch(Throwable e){failure.set(e);}
                finally {finished.countDown();}
            });
            boolean responsive=finished.await(700,TimeUnit.MILLISECONDS);
            unblock.countDown();
            check(finished.await(3,TimeUnit.SECONDS),"EDT did not recover after releasing read");
            check(responsive,"Pagination/cover disposal blocked the EDT on HttpURLConnection.disconnect");
            check(failure.get()==null,"EDT failed: "+failure.get());
            check(disconnected.await(3,TimeUnit.SECONDS),"Cancelled socket was never disconnected");
        } finally {unblock.countDown();}
        try(var request=new RequestScope(()->true,30)) {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean expired=false;
            while(System.nanoTime()<end) {
                try {request.check();}catch(RequestScope.DeadlineExceeded expected){expired=true;break;}
                Thread.sleep(5);
            }
            check(expired,"Shared scheduler failed to enforce deadline");
        }
        System.out.println("PASS: slow-read pagination and disposal keep the EDT responsive; sockets retire and deadlines still fire");
    }
}
