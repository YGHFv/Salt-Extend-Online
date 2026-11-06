package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.RequestScope;

/** Explicit QR creation only: no browser, automatic confirmation, polling or real-account persistence. */
public final class AuthLiveProbe {
    public static void main(String[] args) {
        if(args.length!=2||!args[0].equals("--explicit-create-only")){System.err.println("Requires explicit create-only platform argument.");System.exit(2);return;}
        try {
            AccountPlatform platform=AccountPlatform.valueOf(args[1]);
            try(QrAuthClient client=platform==AccountPlatform.BILIBILI?new BiliQrClient():new NeteaseQrClient();RequestScope scope=new RequestScope(()->true)){
                var session=client.create(scope);
                var qr=io.nayuki.qrcodegen.QrCode.encodeText(session.qrContent().toASCIIString(), io.nayuki.qrcodegen.QrCode.Ecc.MEDIUM);
                if(qr.size<21)throw new IllegalStateException("Local QR generation failed");
                System.out.println("PASS: "+platform+" official QR generation + validated payload + local QR encoding over verified TLS.");
                System.out.println("No QR key/URL printed, browser opened, poll/confirmation performed, real account saved or Salt queue touched.");
            }
        } catch(Exception error){
            String message=error.getMessage();
            boolean numeric=message!=null&&message.matches("(Login HTTP rejected \\(status |Bilibili login rejected \\(code |NetEase login rejected \\(code )-?[0-9]+\\)");
            boolean known=java.util.Set.of("Invalid Bilibili login response","Invalid NetEase login response","Invalid platform cookie response", "Login response is not JSON", "Unsupported login response encoding", "Private upstream address rejected", "WEAPI encryption unavailable").contains(message==null?"":message);
            System.err.println("STOP: QR create-only probe failed ("+error.getClass().getSimpleName()+")"+((numeric||known)?": "+message:"")+"; no risk retry or TLS bypass.");System.exit(3);
        }
    }
}
