package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class AuthLifecycleContractTest {
    private static int checks;
    static final Clock CLOCK=AuthProtocolContractTest.CLOCK;
    static void check(boolean value){checks++;if(!value)throw new AssertionError("Lifecycle check "+checks);}
    static void waitFor(java.util.function.BooleanSupplier condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(10);check(condition.getAsBoolean());}
    static final class FakeClient implements QrAuthClient {
        final AccountCredentials credential; final CountDownLatch polling=new CountDownLatch(1),verifying=new CountDownLatch(1),release;
        volatile boolean closed;
        FakeClient(CountDownLatch release)throws IOException{this.release=release;credential=AuthProtocolContractTest.credentials(AccountPlatform.BILIBILI);}
        public Session create(RequestScope request){return new Session("abcdefghijklmnop0123456789abcdef",URI.create("https://passport.bilibili.com/h5-app/passport/login/scan?qrcode_key=abcdefghijklmnop0123456789abcdef"),"",CLOCK.instant().plusSeconds(180));}
        public Poll poll(Session session,RequestScope request){polling.countDown();if(release!=null)for(;;){try{release.await();break;}catch(InterruptedException ignored){}}return new Poll(State.CONFIRMED,credential);}
        public AccountCredentials verify(AccountCredentials saved,RequestScope request){verifying.countDown();if(release!=null)for(;;){try{release.await();break;}catch(InterruptedException ignored){}}return credential;}
        public void close(){closed=true;}
    }
    static EncryptedAccountStore.Protector protector(){return new EncryptedAccountStore.Protector(){
        final byte[] key=new byte[16];
        public byte[] protect(byte[] plain)throws IOException{return crypt(plain,true);}
        public byte[] unprotect(byte[] sealed)throws IOException{return crypt(sealed,false);}
        byte[] crypt(byte[] bytes,boolean encrypt)throws IOException{try{var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");cipher.init(encrypt?1:2,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.GCMParameterSpec(128,new byte[12]));return cipher.doFinal(bytes);}catch(Exception error){throw new IOException("Fixture protection failed");}}
    };}
    public static void main(String[] args)throws Exception{
        var directory=Files.createTempDirectory("saltneri-auth-test-"); var store=new EncryptedAccountStore(directory,protector(),CLOCK);
        var account=AuthProtocolContractTest.credentials(AccountPlatform.BILIBILI);store.save(account,()->true);
        byte[] disk=Files.readAllBytes(directory.resolve("saltneri-auth/bilibili.dpapi"));check(!new String(disk,StandardCharsets.ISO_8859_1).contains("COOKIE_SECRET"));
        var restored=store.load(AccountPlatform.BILIBILI);check(restored.userId().equals("42")&&restored.isActive());check(!restored.accountScope().equals(account.accountScope()));restored.retire();
        store.markLoggedOut(AccountPlatform.BILIBILI);
        try{store.load(AccountPlatform.BILIBILI);throw new AssertionError("Signout resurrected");}catch(IOException expected){checks++;}
        store.save(account,()->true);check(store.load(AccountPlatform.BILIBILI).isActive());
        AtomicBoolean current=new AtomicBoolean(false);try{store.save(account,current::get);throw new AssertionError("Stale save");}catch(IOException expected){checks++;}
        disk=Files.readAllBytes(directory.resolve("saltneri-auth/bilibili.dpapi"));disk[disk.length-1]^=1;Files.write(directory.resolve("saltneri-auth/bilibili.dpapi"),disk);
        try{store.load(AccountPlatform.BILIBILI);throw new AssertionError("Tamper accepted");}catch(IOException expected){checks++;}
        store.save(account,()->true);
        CountDownLatch release=new CountDownLatch(1);FakeClient old=new FakeClient(release),fresh=new FakeClient(null);AtomicInteger clients=new AtomicInteger(),changes=new AtomicInteger();AtomicBoolean permitted=new AtomicBoolean(true);
        try(AccountLoginController controller=new AccountLoginController(AccountPlatform.BILIBILI,()->clients.getAndIncrement()==0?old:fresh,permitted::get,store,changes::incrementAndGet,CLOCK,10)){
            controller.start(false);check(old.polling.await(2,TimeUnit.SECONDS));check(controller.snapshot().qrContent()!=null);
            controller.start(true);release.countDown();waitFor(()->controller.credentials()!=null);check(!old.credential.isActive());
            waitFor(()->controller.snapshot().status().contains("saved with Windows"));check(changes.get()>=3);check(controller.credentials()==fresh.credential);
            controller.logout();check(!fresh.credential.isActive()&&controller.credentials()==null);waitFor(()->!Files.exists(directory.resolve("saltneri-auth/bilibili.dpapi")));
            try{store.load(AccountPlatform.BILIBILI);throw new AssertionError("Logged out restored");}catch(IOException expected){checks++;}
        }finally{release.countDown();}
        FakeClient revoked=new FakeClient(null);permitted.set(true);
        try(AccountLoginController controller=new AccountLoginController(AccountPlatform.BILIBILI,()->revoked,permitted::get,null,()->{},CLOCK,10)){
            controller.start(false);waitFor(()->controller.credentials()!=null);permitted.set(false);waitFor(()->!revoked.credential.isActive());check(controller.credentials()==null);
        }
        FakeClient cancelled=new FakeClient(new CountDownLatch(1));
        AccountLoginController controller=new AccountLoginController(AccountPlatform.BILIBILI,()->cancelled,()->true,null,()->{},CLOCK,10);
        controller.start(false);check(cancelled.polling.await(2,TimeUnit.SECONDS));controller.close();cancelled.release.countDown();waitFor(()->!cancelled.credential.isActive());check(controller.snapshot().phase()==AccountLoginController.Phase.CLOSED);
        store.save(account,()->true);
        AtomicInteger automaticClients = new AtomicInteger();
        var currentController = new java.util.concurrent.atomic.AtomicReference<AccountLoginController>();
        AtomicBoolean callbackLocked = new AtomicBoolean();
        try (AccountLoginController automatic = new AccountLoginController(AccountPlatform.BILIBILI, () -> {
            automaticClients.incrementAndGet(); try { return new FakeClient(null); } catch(IOException e) { throw new IllegalStateException(e); }
        }, () -> true, store, () -> { if (Thread.holdsLock(currentController.get())) callbackLocked.set(true); }, CLOCK, 10)) {
            currentController.set(automatic);
            automatic.restoreOnStartup(false); Thread.sleep(100); check(automaticClients.get()==0 && automatic.credentials()==null);
            automatic.restoreOnStartup(true); waitFor(()->automatic.credentials()!=null);
            check(automatic.snapshot().qrContent()==null); waitFor(()->automatic.snapshot().status().contains("Automatic restore"));
            automatic.restoreOnStartup(true); check(automaticClients.get()==1);
            automatic.setRememberEnabled(false); waitFor(()->!Files.exists(directory.resolve("saltneri-auth/bilibili.dpapi")));
            check(automatic.credentials()!=null && !store.hasSaved(AccountPlatform.BILIBILI));
            automatic.setRememberEnabled(true); waitFor(()->Files.exists(directory.resolve("saltneri-auth/bilibili.dpapi")));
            check(store.hasSaved(AccountPlatform.BILIBILI));
            automatic.logout(); automatic.restoreOnStartup(true); check(automatic.credentials()==null && automaticClients.get()==1);
            check(!callbackLocked.get());
            waitFor(()->!Files.exists(directory.resolve("saltneri-auth/bilibili.dpapi")));
        }
        try (AccountLoginController signedOut = new AccountLoginController(AccountPlatform.BILIBILI, () -> {
            automaticClients.incrementAndGet(); try { return new FakeClient(null); } catch(IOException e) { throw new IllegalStateException(e); }
        }, () -> true, store, () -> {}, CLOCK, 10)) {
            signedOut.restoreOnStartup(true); waitFor(()->signedOut.snapshot().phase()==AccountLoginController.Phase.IDLE);
            check(signedOut.credentials()==null && signedOut.snapshot().qrContent()==null);
            signedOut.restoreOnStartup(true); check(automaticClients.get()==2);
        }
        store.save(account,()->true);
        byte[] damaged=Files.readAllBytes(directory.resolve("saltneri-auth/bilibili.dpapi"));damaged[damaged.length-1]^=1;Files.write(directory.resolve("saltneri-auth/bilibili.dpapi"),damaged);
        try (AccountLoginController broken = new AccountLoginController(AccountPlatform.BILIBILI, () -> {
            automaticClients.incrementAndGet(); try { return new FakeClient(null); } catch(IOException e) { throw new IllegalStateException(e); }
        }, () -> true, store, () -> {}, CLOCK, 10)) {
            broken.restoreOnStartup(true); waitFor(()->broken.snapshot().phase()==AccountLoginController.Phase.ERROR);
            broken.restoreOnStartup(true); check(automaticClients.get()==3 && broken.credentials()==null);
            check(!broken.snapshot().status().contains("SECRET"));
        }
        store.save(account,()->true); FakeClient lateRestore = new FakeClient(new CountDownLatch(1));
        try (AccountLoginController pending = new AccountLoginController(AccountPlatform.BILIBILI, () -> lateRestore, () -> true, store, () -> {}, CLOCK,10)) {
            pending.restoreOnStartup(true); check(lateRestore.verifying.await(2,TimeUnit.SECONDS));
            pending.logout(); check(!store.hasSaved(AccountPlatform.BILIBILI)); lateRestore.release.countDown();
            waitFor(()->!lateRestore.credential.isActive()); check(pending.credentials()==null);
            pending.restoreOnStartup(true); check(pending.credentials()==null);
        } finally { lateRestore.release.countDown(); }
        account.retire();System.out.println("PASS: "+checks+" login revision/logout/persistence checks (synthetic credentials)");
    }
}
