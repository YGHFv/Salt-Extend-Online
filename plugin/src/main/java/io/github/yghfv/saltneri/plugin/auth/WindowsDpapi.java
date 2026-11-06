package io.github.yghfv.saltneri.plugin.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** CurrentUser DPAPI via the OS-bundled .NET helper. Secrets travel only in anonymous pipes. */
public final class WindowsDpapi implements EncryptedAccountStore.Protector {
    private static final String SCRIPT = "$ErrorActionPreference='Stop';try {"
            + "[void][Reflection.Assembly]::Load('System.Security, Version=4.0.0.0, Culture=neutral, PublicKeyToken=b03f5f7f11d50a3a');"
            + "$mode=[Console]::ReadLine();$inputBytes=[Convert]::FromBase64String([Console]::ReadLine());"
            + "$entropy=[Text.Encoding]::UTF8.GetBytes('io.github.yghfv.saltneri.online/auth/v1');"
            + "if($mode -eq 'protect'){$r=[Security.Cryptography.ProtectedData]::Protect($inputBytes,$entropy,[Security.Cryptography.DataProtectionScope]::CurrentUser)}"
            + "elseif($mode -eq 'unprotect'){$r=[Security.Cryptography.ProtectedData]::Unprotect($inputBytes,$entropy,[Security.Cryptography.DataProtectionScope]::CurrentUser)}else{exit 7};"
            + "[Console]::WriteLine([Convert]::ToBase64String($r));[Array]::Clear($inputBytes,0,$inputBytes.Length);[Array]::Clear($r,0,$r.Length)"
            + "}catch{exit 7}";
    @Override public byte[] protect(byte[] plain) throws IOException { return run("protect", plain); }
    @Override public byte[] unprotect(byte[] cipher) throws IOException { return run("unprotect", cipher); }
    private static byte[] run(String mode, byte[] bytes) throws IOException {
        if (!System.getProperty("os.name", "").startsWith("Windows") || bytes.length > 65536 || Thread.currentThread().isInterrupted()) throw new IOException("Windows secure storage unavailable");
        String system = System.getenv("SystemRoot");
        if (system == null) throw new IOException("Windows secure storage unavailable");
        Path binary = Path.of(system, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toAbsolutePath().normalize();
        if (!Files.isRegularFile(binary)) throw new IOException("Windows secure storage unavailable");
        String command = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        Process process = new ProcessBuilder(binary.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", command)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        Thread owner = Thread.currentThread();
        var watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> { Thread thread = new Thread(runnable, "salt-neri-dpapi-deadline"); thread.setDaemon(true); return thread; });
        watchdog.schedule(process::destroyForcibly, 8, TimeUnit.SECONDS);
        watchdog.scheduleWithFixedDelay(() -> { if (owner.isInterrupted()) process.destroyForcibly(); }, 50, 50, TimeUnit.MILLISECONDS);
        try {
            try (var input = process.getOutputStream()) { input.write((mode + "\n" + Base64.getEncoder().encodeToString(bytes) + "\n").getBytes(StandardCharsets.US_ASCII)); }
            byte[] output = process.getInputStream().readNBytes(128 * 1024 + 1);
            if (output.length > 128 * 1024 || !process.waitFor(8, TimeUnit.SECONDS) || process.exitValue() != 0 || owner.isInterrupted()) throw new IOException("Windows secure storage operation failed");
            try { return Base64.getDecoder().decode(new String(output, StandardCharsets.US_ASCII).trim()); }
            catch (IllegalArgumentException error) { throw new IOException("Windows secure storage operation failed"); }
        } catch (InterruptedException interrupted) { owner.interrupt(); throw new IOException("Windows secure storage operation cancelled"); }
        finally { process.destroyForcibly(); watchdog.shutdownNow(); }
    }
}
