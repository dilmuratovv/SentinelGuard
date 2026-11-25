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
        int slash = s.indexOf('/');
        if (slash >= 0) {
            // only the IPv6 /64 network form that IP bans use is accepted
            if (!s.substring(slash + 1).equals("64") || s.indexOf(':') < 0) return null;
            return network(s.substring(0, slash));
        }
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

    /**
     * The address a ban or alt link applies to: an IPv4 address itself, or the /64 network of an IPv6 address
     * ("2001:db8:1:2::/64"). One IPv6 subscriber usually holds a whole /64 and can pick a new address in it at
     * will, so an exact-address IPv6 ban is trivially escaped.
     */
    public static String network(String raw) {
        String n = raw == null ? null : (raw.endsWith("/64") ? raw.trim() : normalize(raw));
        if (n == null) return null;
        if (n.endsWith("/64")) {
            String base = normalize(n.substring(0, n.length() - 3));
            if (base == null || base.indexOf(':') < 0) return null;
            n = base;
        }
        if (n.indexOf(':') < 0) return n;
        try {
            byte[] bytes = InetAddress.getByName(n).getAddress();
            if (bytes.length != 16) return n;
            StringBuilder out = new StringBuilder(24);
            for (int i = 0; i < 8; i += 2) {
                out.append(Integer.toHexString(((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff))).append(':');
            }
            return out.append(":/64").toString();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Whether two addresses fall under the same {@link #network(String)}. */
    public static boolean sameNetwork(String a, String b) {
        String na = network(a);
        return na != null && na.equals(network(b));
    }

    /** The forms a stored IP ban for this address may have: the exact address and its network. */
    public static java.util.List<String> banForms(String raw) {
        String exact = normalize(raw), net = network(raw);
        if (exact == null) return java.util.List.of();
        return exact.equals(net) || net == null ? java.util.List.of(exact) : java.util.List.of(exact, net);
    }
}
