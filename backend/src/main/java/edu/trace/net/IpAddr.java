package edu.trace.net;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Strict IP literal parsing. Never resolves host names (no DNS), which also prevents a lookup request from being turned
 * into a request to an arbitrary host. Classifies addresses so that private, loopback and reserved ranges are never sent
 * to external reputation services.
 */
public final class IpAddr {

    public enum Scope { PUBLIC, PRIVATE, LOOPBACK, LINK_LOCAL, MULTICAST, CGNAT, RESERVED }

    private IpAddr() {}

    /** Canonical text of a valid IPv4 or IPv6 literal, or null if {@code s} is not one. */
    public static String canonical(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        if (t.startsWith("[") && t.endsWith("]")) {
            t = t.substring(1, t.length() - 1);
        }
        int zone = t.indexOf('%');
        if (zone >= 0) {
            t = t.substring(0, zone); // drop IPv6 zone index (fe80::1%12)
        }
        if (t.isEmpty() || t.length() > 45) {
            return null;
        }
        long v4 = ipv4(t);
        if (v4 >= 0) {
            return format(v4);
        }
        if (!t.matches("[0-9A-Fa-f:.]+") || !t.contains(":")) {
            return null;
        }
        try {
            InetAddress a = InetAddress.getByName(t); // a literal of hex digits, ':' and '.' is parsed locally, no DNS
            if (a instanceof Inet6Address) {
                byte[] b = a.getAddress();
                boolean mapped = true;
                for (int i = 0; i < 10; i++) {
                    mapped &= b[i] == 0;
                }
                if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                    return format(((b[12] & 0xffL) << 24) | ((b[13] & 0xffL) << 16) | ((b[14] & 0xffL) << 8) | (b[15] & 0xffL));
                }
                return a.getHostAddress(); // expanded form, e.g. 2001:db8:0:0:0:0:0:1
            }
            return a.getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** IPv4 dotted quad as an unsigned 32-bit value, or -1. */
    public static long ipv4(String s) {
        String[] p = s.split("\\.", -1);
        if (p.length != 4) {
            return -1;
        }
        long v = 0;
        for (String part : p) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                return -1;
            }
            int x = Integer.parseInt(part);
            if (x > 255 || (part.length() > 1 && part.charAt(0) == '0')) {
                return -1;
            }
            v = (v << 8) | x;
        }
        return v;
    }

    public static String format(long v) {
        return ((v >> 24) & 255) + "." + ((v >> 16) & 255) + "." + ((v >> 8) & 255) + "." + (v & 255);
    }

    public static boolean isIpv4(String canonical) {
        return canonical != null && ipv4(canonical) >= 0;
    }

    public static Scope scope(String canonical) {
        long v = ipv4(canonical);
        if (v >= 0) {
            int a = (int) (v >> 24);
            int b = (int) ((v >> 16) & 255);
            if (a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)) {
                return Scope.PRIVATE;
            }
            if (a == 127) {
                return Scope.LOOPBACK;
            }
            if (a == 169 && b == 254) {
                return Scope.LINK_LOCAL;
            }
            if (a == 100 && b >= 64 && b <= 127) {
                return Scope.CGNAT;
            }
            if (a >= 224 && a <= 239) {
                return Scope.MULTICAST;
            }
            if (a == 0 || a >= 240 || (a == 192 && b == 0 && ((v >> 8) & 255) == 2) || (a == 198 && (b == 18 || b == 19))
                    || (a == 198 && b == 51 && ((v >> 8) & 255) == 100) || (a == 203 && b == 0 && ((v >> 8) & 255) == 113)) {
                return Scope.RESERVED;
            }
            return Scope.PUBLIC;
        }
        String l = canonical.toLowerCase();
        if (l.equals("0:0:0:0:0:0:0:1")) {
            return Scope.LOOPBACK;
        }
        if (l.equals("0:0:0:0:0:0:0:0") || l.startsWith("2001:db8:")) {
            return Scope.RESERVED;
        }
        if (l.startsWith("fe8") || l.startsWith("fe9") || l.startsWith("fea") || l.startsWith("feb")) {
            return Scope.LINK_LOCAL;
        }
        if (l.startsWith("fc") || l.startsWith("fd")) {
            return Scope.PRIVATE;
        }
        if (l.startsWith("ff")) {
            return Scope.MULTICAST;
        }
        return Scope.PUBLIC;
    }
}
