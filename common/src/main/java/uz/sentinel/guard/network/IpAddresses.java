package uz.sentinel.guard.network;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;
/** IPv4/IPv6 literal normalization. Never performs DNS lookups. */
public final class IpAddresses {
    private static final Pattern IPV4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");
    private IpAddresses() {}
    /**
     * Accepts "1.2.3.4", "/1.2.3.4:25565", "::1", "[2001:db8::1]:25565", "fe80::1%eth0".
     * Returns the canonical textual form, or null when the input is not an IP literal.
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("/")) s = s.substring(1);
        if (s.isEmpty()) return null;
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end < 0) return null;
            s = s.substring(1, end);
        } else if (s.indexOf(':') >= 0 && s.indexOf(':') == s.lastIndexOf(':') && s.indexOf('.') >= 0) {
            s = s.substring(0, s.indexOf(':')); // IPv4 with port
        }
        int zone = s.indexOf('%');
        if (zone >= 0) s = s.substring(0, zone);
        if (IPV4.matcher(s).matches()) return s;
        if (s.indexOf(':') < 0 || !IPV6_CHARS.matcher(s).matches()) return null;
        try {
            // Only colon/hex/dot characters reach here, so this is parsed as a literal without DNS.
            InetAddress address = InetAddress.getByName(s);
            return address.getHostAddress().toLowerCase(Locale.ROOT);
        } catch (UnknownHostException e) {
            return null;
        }
    }
    public static boolean isIp(String raw) { return normalize(raw) != null; }
}
