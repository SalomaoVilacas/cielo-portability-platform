package com.example.central_mock.shared;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
public final class Integrity {
    private Integrity() {
    }
    public static String sign(String secret,String value) {
        try {
            var m=Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return HexFormat.of().formatHex(m.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch(java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
    public static boolean valid(String s,String v,String sig) {
        return sig!=null&&MessageDigest.isEqual(sign(s,v).getBytes(StandardCharsets.US_ASCII),sig.getBytes(StandardCharsets.US_ASCII));
    }
    public static String request(String m,String p,String t,String b) {
        return m+"\n"+p+"\n"+t+"\n"+b;
    }
    public static String response(int s,String retry,String b) {
        return s+"\n"+retry+"\n"+b;
    }
    public static boolean fresh(String t) {
        try {
            return Math.abs(Instant.now().getEpochSecond()-Long.parseLong(t))<=300;
        }
        catch(RuntimeException e) {
            return false;
        }
    }
}
