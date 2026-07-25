package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class RuntimeEndpointSecurityService {
    private static final int MAX_ENDPOINT_URL_LENGTH = 2048;
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;
    private static final Set<String> METADATA_HOSTS = Set.of(
            "metadata.google.internal",
            "metadata.azure.internal",
            "instance-data.ec2.internal",
            "metadata.tencentyun.com");

    private final StudioPlatformProperties properties;

    public RuntimeEndpointSecurityService(StudioPlatformProperties properties) {
        this.properties = properties == null ? new StudioPlatformProperties() : properties;
    }

    public URI validate(String endpointUrl) {
        return validateTarget(endpointUrl, false).getUri();
    }

    public URI validateRequest(String requestUrl) {
        return validateRequestTarget(requestUrl).getUri();
    }

    public ValidatedRuntimeEndpoint validateRequestTarget(String requestUrl) {
        return validateTarget(requestUrl, true);
    }

    private ValidatedRuntimeEndpoint validateTarget(String endpointUrl, boolean allowQuery) {
        if (!StringUtils.hasText(endpointUrl)) {
            throw bad("Runtime endpoint URL is required");
        }
        String value = endpointUrl.trim();
        if (value.length() > MAX_ENDPOINT_URL_LENGTH) {
            throw bad("Runtime endpoint URL is too long");
        }
        final URI uri;
        try {
            uri = URI.create(value).normalize();
        } catch (Exception ex) {
            throw bad("Runtime endpoint URL is invalid");
        }
        String host = normalizeHost(uri.getHost());
        if (!StringUtils.hasText(host) || uri.getUserInfo() != null || (!allowQuery && uri.getQuery() != null)
                || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw bad("Runtime endpoint host is invalid");
        }
        if (isMetadataHost(host)) {
            throw bad("Runtime endpoint cannot target a cloud metadata service");
        }
        boolean allowed = allowedHost(host);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !(allowed && "http".equals(scheme))) {
            throw bad("Runtime endpoint must use HTTPS unless its host is explicitly allowed");
        }
        InetAddress[] addresses = resolveAddresses(host);
        for (InetAddress address : addresses) {
            if (isMetadataAddress(address)) {
                throw bad("Runtime endpoint cannot target a cloud metadata service");
            }
            if (!allowed && isDeniedAddress(address)) {
                throw bad("Runtime endpoint resolves to a private, local, or unsafe network address");
            }
        }
        return new ValidatedRuntimeEndpoint(uri, addresses);
    }

    public int maxResponseBytes() {
        StudioPlatformProperties.RuntimeEndpointProperties endpoint = properties.getRuntimeEndpoint();
        Integer configured = endpoint == null ? null : endpoint.getMaxResponseBytes();
        int value = configured == null ? DEFAULT_MAX_RESPONSE_BYTES : configured.intValue();
        return Math.min(MAX_RESPONSE_BYTES, Math.max(1024, value));
    }

    /** Allows response redirects only when they resolve outside the managed runtime and private networks. */
    public boolean isSafeExternalRedirect(URI location, ValidatedRuntimeEndpoint runtimeEndpoint) {
        if (location == null || !location.isAbsolute() || location.getUserInfo() != null
                || location.getPort() == 0 || location.getPort() > 65535) {
            return false;
        }
        String scheme = location.getScheme() == null ? "" : location.getScheme().toLowerCase(Locale.ROOT);
        String host = normalizeHost(location.getHost());
        if (!("http".equals(scheme) || "https".equals(scheme))
                || !StringUtils.hasText(host) || isMetadataHost(host)) {
            return false;
        }
        if (runtimeEndpoint != null
                && host.equals(normalizeHost(runtimeEndpoint.getUri().getHost()))) {
            return false;
        }
        final InetAddress[] redirectAddresses;
        try {
            redirectAddresses = resolveAddresses(host);
        } catch (StudioException ex) {
            return false;
        }
        InetAddress[] runtimeAddresses = runtimeEndpoint == null
                ? new InetAddress[0] : runtimeEndpoint.getAddresses();
        for (InetAddress address : redirectAddresses) {
            if (isMetadataAddress(address) || isDeniedAddress(address)) {
                return false;
            }
            for (InetAddress runtimeAddress : runtimeAddresses) {
                if (address.equals(runtimeAddress)) {
                    return false;
                }
            }
        }
        return true;
    }

    protected InetAddress[] resolveHost(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    protected boolean isLocalInterfaceAddress(InetAddress address) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return false;
            }
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                for (InetAddress localAddress : Collections.list(networkInterface.getInetAddresses())) {
                    if (localAddress.equals(address)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (SocketException ex) {
            return false;
        }
    }

    private InetAddress[] resolveAddresses(String host) {
        try {
            InetAddress[] addresses = resolveHost(host);
            if (addresses == null || addresses.length == 0) {
                throw new UnknownHostException(host);
            }
            return addresses;
        } catch (UnknownHostException ex) {
            throw bad("Runtime endpoint host cannot be resolved");
        }
    }

    private boolean allowedHost(String host) {
        StudioPlatformProperties.RuntimeEndpointProperties endpoint = properties.getRuntimeEndpoint();
        List<String> allowedHosts = endpoint == null ? null : endpoint.getAllowedHosts();
        if (allowedHosts == null) {
            return false;
        }
        for (String value : allowedHosts) {
            if (StringUtils.hasText(value) && host.equals(normalizeHost(value))) {
                return true;
            }
        }
        return false;
    }

    private boolean isMetadataHost(String host) {
        return METADATA_HOSTS.contains(host);
    }

    private boolean isMetadataAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            int third = bytes[2] & 0xff;
            int fourth = bytes[3] & 0xff;
            return (first == 169 && second == 254 && third == 169 && fourth == 254)
                    || (first == 169 && second == 254 && third == 0 && fourth == 23)
                    || (first == 100 && second == 100 && third == 100 && fourth == 200);
        }
        return bytes.length == 16
                && (bytes[0] & 0xff) == 0xfd
                && bytes[1] == 0 && bytes[2] == 0x0e && (bytes[3] & 0xff) == 0xc2
                && allZero(bytes, 4, 14) && (bytes[14] & 0xff) == 0x02 && (bytes[15] & 0xff) == 0x54;
    }

    private boolean isDeniedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()
                || isLocalInterfaceAddress(address)) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0 || first == 10 || first == 127
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || (first == 100 && second >= 64 && second <= 127)
                    || first >= 224;
        }
        return bytes.length == 16 && (((bytes[0] & 0xfe) == 0xfc) || isIpv6TransitionAddress(bytes));
    }

    private boolean isIpv6TransitionAddress(byte[] bytes) {
        boolean compatibleOrMapped = allZero(bytes, 0, 10)
                && ((bytes[10] == 0 && bytes[11] == 0)
                || ((bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff));
        boolean wellKnownNat64 = (bytes[0] & 0xff) == 0x00 && (bytes[1] & 0xff) == 0x64
                && (bytes[2] & 0xff) == 0xff && (bytes[3] & 0xff) == 0x9b
                && allZero(bytes, 4, 12);
        boolean localNat64 = (bytes[0] & 0xff) == 0x00 && (bytes[1] & 0xff) == 0x64
                && (bytes[2] & 0xff) == 0xff && (bytes[3] & 0xff) == 0x9b
                && bytes[4] == 0 && bytes[5] == 1;
        boolean sixToFour = (bytes[0] & 0xff) == 0x20 && (bytes[1] & 0xff) == 0x02;
        boolean teredo = (bytes[0] & 0xff) == 0x20 && (bytes[1] & 0xff) == 0x01
                && bytes[2] == 0 && bytes[3] == 0;
        boolean isatap = (bytes[8] == 0 || bytes[8] == 2) && bytes[9] == 0
                && (bytes[10] & 0xff) == 0x5e && (bytes[11] & 0xff) == 0xfe;
        return compatibleOrMapped || wellKnownNat64 || localNat64 || sixToFour || teredo || isatap;
    }

    private boolean allZero(byte[] bytes, int from, int to) {
        for (int index = from; index < to; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private String normalizeHost(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String host = value.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            host = host.substring(1, host.length() - 1);
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    private StudioException bad(String message) {
        return new StudioException(StudioErrorCode.BAD_REQUEST, message);
    }

    public static final class ValidatedRuntimeEndpoint {
        private final URI uri;
        private final InetAddress[] addresses;

        private ValidatedRuntimeEndpoint(URI uri, InetAddress[] addresses) {
            this.uri = uri;
            this.addresses = Arrays.copyOf(addresses, addresses.length);
        }

        public URI getUri() {
            return uri;
        }

        public InetAddress[] getAddresses() {
            return Arrays.copyOf(addresses, addresses.length);
        }
    }

    public static final class ResponseTooLargeException extends IOException {
        private final int maxBytes;

        public ResponseTooLargeException(int maxBytes) {
            super("Runtime endpoint response exceeds " + maxBytes + " bytes");
            this.maxBytes = maxBytes;
        }

        public int getMaxBytes() {
            return maxBytes;
        }
    }
}
