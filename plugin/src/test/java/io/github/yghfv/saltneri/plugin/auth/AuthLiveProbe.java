package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.RequestScope;

/** Explicit fresh QR creation, optionally one unscanned poll; never exposes the QR or persists credentials. */
public final class AuthLiveProbe {
    public static void main(String[] args) {
        if(args.length!=2||!(args[0].equals("--explicit-create-only") || args[0].equals("--explicit-session-check"))){System.err.println("Requires explicit create-only or session-check platform argument.");System.exit(2);return;}
        try {
            AccountPlatform platform=AccountPlatform.valueOf(args[1]);
            try(QrAuthClient client=switch(platform) {case BILIBILI -> new BiliQrClient();case NETEASE -> new NeteaseQrClient();case QQMUSIC -> new QqMusicQrClient();case APPLEMUSIC -> new AppleMusicAuthClient();};
                RequestScope scope=new RequestScope(()->true, platform==AccountPlatform.NETEASE?90000:30000)){
                var session=client.create(scope);
                if(platform==AccountPlatform.QQMUSIC) {
                    byte[] image=client.qrImage(session);
                    if(image==null || image.length<8)throw new IllegalStateException("Official QR image missing");
                    java.util.Arrays.fill(image,(byte)0);
                } else {
                    var qr=io.nayuki.qrcodegen.QrCode.encodeText(session.qrContent().toASCIIString(), io.nayuki.qrcodegen.QrCode.Ecc.MEDIUM);
                    if(qr.size<21)throw new IllegalStateException("Local QR generation failed");
                }
                System.out.println("PASS: "+platform+(platform==AccountPlatform.QQMUSIC
                        ?" official QR image + scoped signature Cookie over verified TLS."
                        :" official QR generation + validated payload + local QR encoding over verified TLS."));
                if(args[0].equals("--explicit-session-check")) {
                    var result=client.poll(session,scope);
                    if(result.credentials()!=null) result.credentials().retire();
                    if(result.state()!=QrAuthClient.State.WAITING_SCAN) throw new IllegalStateException("Unexpected fresh unscanned QR state");
                    System.out.println("PASS: one fresh unscanned status check returned WAITING_SCAN; no automatic loop.");
                }
                System.out.println("No QR key/URL printed, visible browser opened, confirmation performed, real account saved or Salt queue touched. NetEase prepares its device context in isolated headless Edge.");
            }
        } catch(Exception error){
            if(error instanceof AuthFailure) { System.err.println("STOP: "+AuthFailure.diagnostic(error)); System.exit(3); return; }
            String message=error.getMessage();
            boolean numeric=message!=null&&message.matches("(Login HTTP rejected \\(status |Bilibili login rejected \\(code |NetEase login rejected \\(code )-?[0-9]+\\)");
            boolean known=java.util.Set.of("Invalid Bilibili login response","Invalid NetEase login response","Invalid platform cookie response", "Login response is not JSON", "Unsupported login response encoding", "Private upstream address rejected", "WEAPI encryption unavailable").contains(message==null?"":message);
            System.err.println("STOP: QR create-only probe failed ("+error.getClass().getSimpleName()+")"+((numeric||known)?": "+message:"")+"; no risk retry or TLS bypass.");System.exit(3);
        }
    }
}
