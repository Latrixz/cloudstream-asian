package android.util;

/**
 * JVM stand-in for android.util.Base64, used only by the source watcher. The android.jar on the
 * unit-test classpath is a stub that returns null; this real version comes first on the classpath.
 */
public class Base64 {
    public static final int DEFAULT = 0, NO_PADDING = 1, NO_WRAP = 2, CRLF = 4, URL_SAFE = 8, NO_CLOSE = 16;

    public static byte[] decode(String str, int flags) { return decode(str.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), flags); }
    public static byte[] decode(byte[] input, int flags) { return decode(input, 0, input.length, flags); }
    public static byte[] decode(byte[] input, int offset, int len, int flags) {
        String s = new String(input, offset, len, java.nio.charset.StandardCharsets.ISO_8859_1).replaceAll("[\\s=]", "");
        s = s.replace('-', '+').replace('_', '/');
        try { return java.util.Base64.getDecoder().decode(s); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("bad base-64", e); }
    }

    public static String encodeToString(byte[] input, int flags) { return new String(encode(input, flags), java.nio.charset.StandardCharsets.US_ASCII); }
    public static String encodeToString(byte[] input, int offset, int len, int flags) { return new String(encode(input, offset, len, flags), java.nio.charset.StandardCharsets.US_ASCII); }
    public static byte[] encode(byte[] input, int flags) { return encode(input, 0, input.length, flags); }
    public static byte[] encode(byte[] input, int offset, int len, int flags) {
        byte[] slice = java.util.Arrays.copyOfRange(input, offset, offset + len);
        java.util.Base64.Encoder enc = (flags & URL_SAFE) != 0 ? java.util.Base64.getUrlEncoder() : java.util.Base64.getEncoder();
        if ((flags & NO_PADDING) != 0) enc = enc.withoutPadding();
        String out = enc.encodeToString(slice);
        if ((flags & NO_WRAP) == 0) {
            String nl = (flags & CRLF) != 0 ? "\r\n" : "\n";
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < out.length(); i += 76) b.append(out, i, Math.min(out.length(), i + 76)).append(nl);
            out = b.toString();
        }
        return out.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
