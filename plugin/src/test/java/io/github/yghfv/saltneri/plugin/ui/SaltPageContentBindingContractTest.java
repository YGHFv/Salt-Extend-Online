package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.Composer;
import java.lang.reflect.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import kotlin.Unit;
import kotlin.jvm.functions.Function3;

/** Real mutable Salt page object, isolated replacements; no composition, account or network. */
public final class SaltPageContentBindingContractTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Page binding check " + checks); }
    public static void main(String[] args) throws Exception {
        ClassLoader host = SaltPageContentBindingContractTest.class.getClassLoader();
        Class<?> singleton = Class.forName(SaltPageContentBinding.SINGLETON, true, host);
        Object cached = singleton.getMethod(SaltPageContentBinding.GETTER).invoke(singleton.getField("INSTANCE").get(null));
        Field block = cached.getClass().getDeclaredField("_block"); block.setAccessible(true);
        Method update = cached.getClass().getMethod("update", Object.class);
        Object original = block.get(cached);
        AtomicBoolean permitted = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        Function3<Object,Composer,Integer,Unit> replacement = (key,c,flags) -> Unit.INSTANCE;
        try {
            try (SaltPageContentBinding binding = new SaltPageContentBinding(host, before -> {
                check(before == original); check(SwingUtilities.isEventDispatchThread()); calls.incrementAndGet();
                try { return cached.getClass().getConstructor(int.class, boolean.class, Object.class).newInstance(1234, false, replacement); }
                catch (Exception error) { throw new AssertionError(error); }
            }, permitted::get)) {
                check(!binding.isInstalled()); binding.install(); SwingUtilities.invokeAndWait(() -> {});
                check(binding.isInstalled() && block.get(cached) == replacement && calls.get() == 1);
                binding.install(); SwingUtilities.invokeAndWait(() -> {}); check(calls.get() == 1);
                Function3<Object,Composer,Integer,Unit> later = (key,c,flags) -> Unit.INSTANCE;
                SwingUtilities.invokeAndWait(() -> { try { update.invoke(cached, later); } catch (Exception error) { throw new AssertionError(error); } });
                check(!binding.isInstalled()); binding.install(); SwingUtilities.invokeAndWait(() -> {});
                check(binding.failure().equals("IllegalStateException") && block.get(cached) == later && calls.get() == 1);
                binding.close(); SwingUtilities.invokeAndWait(() -> {}); check(block.get(cached) == later);
            }
            SwingUtilities.invokeAndWait(() -> { try { update.invoke(cached, original); } catch (Exception error) { throw new AssertionError(error); } });
            try (SaltPageContentBinding binding = new SaltPageContentBinding(host, before -> replacement, permitted::get)) {
                binding.install(); SwingUtilities.invokeAndWait(() -> {}); check(binding.isInstalled());
                binding.close(); binding.close(); SwingUtilities.invokeAndWait(() -> {}); check(block.get(cached) == original);
            }
            permitted.set(false);
            try (SaltPageContentBinding denied = new SaltPageContentBinding(host, before -> { throw new AssertionError("Denied install"); }, permitted::get)) {
                denied.install(); SwingUtilities.invokeAndWait(() -> {}); check(!denied.isInstalled() && block.get(cached) == original);
            }
            permitted.set(true);
            try (SaltPageContentBinding cancelled = new SaltPageContentBinding(host, before -> { throw new AssertionError("Closed install"); }, permitted::get)) {
                cancelled.close(); cancelled.install(); SwingUtilities.invokeAndWait(() -> {});
                check(!cancelled.isInstalled() && block.get(cached) == original);
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> { try { update.invoke(cached, original); } catch (Exception error) { throw new AssertionError(error); } });
        }
        System.out.println("PASS: " + checks + " actual page-content binding checks; raw body unwrap, EDT updates, cached object, idempotence, restore and later-owner preservation");
    }
}
