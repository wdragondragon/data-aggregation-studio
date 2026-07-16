package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class AlertWebhookSecurityService {

    private static final int MAX_ENDPOINT_URL_LENGTH = 2048;

    private final StudioPlatformProperties properties;

    public AlertWebhookSecurityService(StudioPlatformProperties properties) {
        this.properties = properties;
    }

    public URI validate(String endpointUrl) {
        try {
            return validateAndResolve(endpointUrl).getUri();
        } catch (WebhookDnsResolutionException ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Webhook endpoint host cannot be resolved");
        }
    }

    public ValidatedWebhookTarget validateAndResolve(String endpointUrl) {
        if (!StringUtils.hasText(endpointUrl)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Webhook endpoint URL is required");
        }
        if (endpointUrl.trim().length() > MAX_ENDPOINT_URL_LENGTH) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Webhook endpoint URL is too long");
        }
        final URI uri;
        try {
            uri = URI.create(endpointUrl.trim());
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Webhook endpoint URL is invalid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean allowHttp = properties.getAlert() != null
                && properties.getAlert().getWebhook() != null
                && properties.getAlert().getWebhook().isAllowHttp();
        if (!"https".equals(scheme) && !(allowHttp && "http".equals(scheme))) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Webhook endpoint must use HTTPS");
        }
        if (!StringUtils.hasText(uri.getHost()) || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Webhook endpoint host is invalid");
        }
        if (uri.getPort() > 65535 || uri.getPort() == 0) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Webhook endpoint port is invalid");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        InetAddress[] addresses = resolveAddresses(host);
        if (!allowedHost(host)) {
            validateResolvedAddresses(addresses);
        }
        return new ValidatedWebhookTarget(uri, addresses);
    }

    private boolean allowedHost(String host) {
        List<String> allowedHosts = properties.getAlert() == null || properties.getAlert().getWebhook() == null
                ? null : properties.getAlert().getWebhook().getAllowedHosts();
        if (allowedHosts == null) {
            return false;
        }
        for (String pattern : allowedHosts) {
            if (!StringUtils.hasText(pattern)) {
                continue;
            }
            String normalized = pattern.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals(host)) {
                return true;
            }
            if (normalized.startsWith("*.") && host.endsWith(normalized.substring(1))
                    && host.length() > normalized.length() - 1) {
                return true;
            }
        }
        return false;
    }

    private InetAddress[] resolveAddresses(String host) {
        try {
            InetAddress[] addresses = resolveHost(host);
            if (addresses.length == 0) {
                throw new WebhookDnsResolutionException();
            }
            return addresses;
        } catch (WebhookDnsResolutionException ex) {
            throw ex;
        } catch (UnknownHostException ex) {
            throw new WebhookDnsResolutionException();
        }
    }

    protected InetAddress[] resolveHost(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    private void validateResolvedAddresses(InetAddress[] addresses) {
        for (InetAddress address : addresses) {
            if (isDenied(address)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "Webhook endpoint resolves to a private or local network address");
            }
        }
    }

    private boolean isDenied(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
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
        if (bytes.length != 16) {
            return false;
        }
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
        boolean isatap = (bytes[8] == 0 || bytes[8] == 2) && bytes[9] == 0 && (bytes[10] & 0xff) == 0x5e
                && (bytes[11] & 0xff) == 0xfe;
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

    public static final class ValidatedWebhookTarget {
        private final URI uri;
        private final InetAddress[] addresses;

        private ValidatedWebhookTarget(URI uri, InetAddress[] addresses) {
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

    static final class WebhookDnsResolutionException extends RuntimeException {
        WebhookDnsResolutionException() {
            super(null, null, false, false);
        }
    }
}
