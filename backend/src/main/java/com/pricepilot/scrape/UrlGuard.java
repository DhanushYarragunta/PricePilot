package com.pricepilot.scrape;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether the server is allowed to fetch a URL at all.
 *
 * <p>This exists because users paste URLs and the server then fetches them. Without a guard, that
 * is a server-side request forgery hole: someone could submit
 * {@code http://169.254.169.254/latest/meta-data/iam/security-credentials/} and have PricePilot
 * fetch the EC2 instance's IAM credentials and store the response in the database, where the
 * "price extraction failed, here is the raw page" debug view would hand them over.
 *
 * <p>So every URL — including every hop of a redirect chain, which is why the fetcher follows
 * redirects manually — is checked here first. Blocked: anything that is not http/https, anything
 * with embedded credentials, non-standard ports, and any hostname that resolves to a loopback,
 * link-local, site-local, multicast or wildcard address.
 *
 * <p>One honest limitation: the check resolves DNS and then the HTTP client resolves it again, so
 * a determined attacker with control of a DNS server could in principle return a public address to
 * us and a private one to the client. Closing that properly means pinning the resolved address
 * into the connection, which the JDK HTTP client does not expose. For a price tracker whose users
 * are invited by hand, the check below is the right trade-off, and it is documented rather than
 * pretended away.
 */
public final class UrlGuard {

    private static final Logger log = LoggerFactory.getLogger(UrlGuard.class);

    private UrlGuard() {
    }

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost",
            "localhost.localdomain",
            "ip6-localhost",
            "metadata",
            "metadata.google.internal",
            "instance-data",
            "169.254.169.254",
            "[::ffff:a9fe:a9fe]",
            "fd00:ec2::254");

    /** Ports a retailer would plausibly serve on. Anything else is someone probing the network. */
    private static final Set<Integer> ALLOWED_PORTS = Set.of(-1, 80, 443, 8080, 8443);

    /**
     * @throws UnsupportedUrlException if this URL must not be fetched
     */
    public static void assertFetchable(URI uri) {
        if (uri == null) {
            throw new UnsupportedUrlException("No URL supplied.");
        }

        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new UnsupportedUrlException(
                    "Only http and https links can be tracked. Paste the product page URL.");
        }

        if (uri.getUserInfo() != null) {
            throw new UnsupportedUrlException("URLs containing a username or password are not allowed.");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new UnsupportedUrlException("That URL has no hostname. Paste the full product page URL.");
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".")) {
            lower = lower.substring(0, lower.length() - 1);
        }

        if (BLOCKED_HOSTS.contains(lower)
                || lower.endsWith(".localhost")
                || lower.endsWith(".internal")
                || lower.endsWith(".local")) {
            throw new UnsupportedUrlException("That host is not reachable from PricePilot.");
        }

        if (!ALLOWED_PORTS.contains(uri.getPort())) {
            throw new UnsupportedUrlException(
                    "Only standard web ports are allowed (got port " + uri.getPort() + ").");
        }

        assertPublicAddress(lower);
    }

    private static void assertPublicAddress(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new UnsupportedUrlException(
                    "Could not resolve '" + host + "'. Check the URL and try again.");
        }

        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isAnyLocalAddress()
                    || address.isMulticastAddress()
                    || isCarrierGradeNat(address)
                    || isUniqueLocalIpv6(address)) {
                log.warn("Refusing to fetch {} — resolves to non-public address {}",
                        host, address.getHostAddress());
                throw new UnsupportedUrlException("That host is not reachable from PricePilot.");
            }
        }
    }

    /** 100.64.0.0/10 — RFC 6598. Not covered by {@code isSiteLocalAddress()}. */
    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] a = address.getAddress();
        if (a.length != 4) {
            return false;
        }
        int first = a[0] & 0xFF;
        int second = a[1] & 0xFF;
        return first == 100 && second >= 64 && second <= 127;
    }

    /** fc00::/7 — IPv6 unique local addresses, the v6 equivalent of 10.0.0.0/8. */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] a = address.getAddress();
        if (a.length != 16) {
            return false;
        }
        return (a[0] & 0xFE) == 0xFC;
    }
}
