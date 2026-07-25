package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeEndpointSecurityServiceTest {

    @Test
    void shouldRequireHttpsAndRejectPrivateOrMetadataTargetsByDefault() {
        RuntimeEndpointSecurityService service = new RuntimeEndpointSecurityService(new StudioPlatformProperties());

        assertThrows(StudioException.class, () -> service.validate("http://example.com/runtime"));
        assertThrows(StudioException.class, () -> service.validate("https://127.0.0.1/runtime"));
        assertThrows(StudioException.class,
                () -> service.validate("https://169.254.169.254/latest/meta-data"));
        assertThrows(StudioException.class,
                () -> service.validate("https://metadata.google.internal/computeMetadata/v1"));
        assertThrows(StudioException.class,
                () -> service.validate("https://user:secret@example.com/runtime"));
        assertThrows(StudioException.class,
                () -> service.validate("https://example.com/runtime?token=secret"));
    }

    @Test
    void exactAllowlistShouldPermitInternalHttpButNeverMetadata() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getRuntimeEndpoint().getAllowedHosts().add("127.0.0.1");
        properties.getRuntimeEndpoint().getAllowedHosts().add("::1");
        properties.getRuntimeEndpoint().getAllowedHosts().add("169.254.169.254");
        properties.getRuntimeEndpoint().getAllowedHosts().add("169.254.0.23");
        RuntimeEndpointSecurityService service = new RuntimeEndpointSecurityService(properties);

        assertEquals("127.0.0.1", service.validate("http://127.0.0.1:19090/runtime").getHost());
        assertEquals("[::1]", service.validate("http://[::1]:19090/runtime").getHost());
        assertThrows(StudioException.class,
                () -> service.validate("http://169.254.169.254/latest/meta-data"));
        assertThrows(StudioException.class,
                () -> service.validate("http://169.254.0.23/latest/meta-data"));
    }

    @Test
    void shouldValidateEveryResolvedAddressAndNotExpandWildcardAllowlist() throws Exception {
        InetAddress publicAddress = InetAddress.getByName("203.0.113.10");
        InetAddress privateAddress = InetAddress.getByName("10.0.0.8");
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getRuntimeEndpoint().getAllowedHosts().add("*.example.internal");
        RuntimeEndpointSecurityService service = new RuntimeEndpointSecurityService(properties) {
            @Override
            protected InetAddress[] resolveHost(String host) throws UnknownHostException {
                return new InetAddress[]{publicAddress, privateAddress};
            }
        };

        assertThrows(StudioException.class,
                () -> service.validate("https://worker.example.internal/runtime"));
    }

    @Test
    void shouldAllowResolvedPublicHttpsTarget() throws Exception {
        InetAddress publicAddress = InetAddress.getByName("203.0.113.10");
        RuntimeEndpointSecurityService service = new RuntimeEndpointSecurityService(new StudioPlatformProperties()) {
            @Override
            protected InetAddress[] resolveHost(String host) {
                return new InetAddress[]{publicAddress};
            }

            @Override
            protected boolean isLocalInterfaceAddress(InetAddress address) {
                return false;
            }
        };

        assertEquals("worker.example.com",
                service.validate("https://worker.example.com/runtime").getHost());
    }

    @Test
    void shouldRejectIpv6TransitionAddress() throws Exception {
        InetAddress transitionAddress = InetAddress.getByName("64:ff9b::7f00:1");
        RuntimeEndpointSecurityService service = new RuntimeEndpointSecurityService(new StudioPlatformProperties()) {
            @Override
            protected InetAddress[] resolveHost(String host) {
                return new InetAddress[]{transitionAddress};
            }
        };

        assertThrows(StudioException.class,
                () -> service.validate("https://worker.example.com/runtime"));
    }

    @Test
    void shouldOnlyAllowPublicRedirectsOutsideTheManagedRuntime() throws Exception {
        InetAddress runtimeAddress = InetAddress.getByAddress(new byte[]{(byte) 203, 0, 113, 10});
        InetAddress externalAddress = InetAddress.getByAddress(new byte[]{(byte) 198, 51, 100, 20});
        InetAddress privateAddress = InetAddress.getByAddress(new byte[]{10, 46, 0, 12});
        RuntimeEndpointSecurityService service = new RuntimeEndpointSecurityService(new StudioPlatformProperties()) {
            @Override
            protected InetAddress[] resolveHost(String host) throws UnknownHostException {
                if ("runtime.example.com".equals(host) || "runtime-alias.example.com".equals(host)) {
                    return new InetAddress[]{runtimeAddress};
                }
                if ("login.example.com".equals(host)) {
                    return new InetAddress[]{externalAddress};
                }
                if ("worker.internal".equals(host)) {
                    return new InetAddress[]{privateAddress};
                }
                throw new UnknownHostException(host);
            }

            @Override
            protected boolean isLocalInterfaceAddress(InetAddress address) {
                return false;
            }
        };
        RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint runtime =
                service.validateRequestTarget("https://runtime.example.com/internal/runtime/health");

        assertTrue(service.isSafeExternalRedirect(
                URI.create("https://login.example.com/oauth"), runtime));
        assertFalse(service.isSafeExternalRedirect(
                URI.create("https://runtime-alias.example.com/login"), runtime));
        assertFalse(service.isSafeExternalRedirect(
                URI.create("https://worker.internal/login"), runtime));
        assertFalse(service.isSafeExternalRedirect(
                URI.create("//login.example.com/oauth"), runtime));
    }

    @Test
    void shouldClampMisconfiguredResponseLimit() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getRuntimeEndpoint().setMaxResponseBytes(Integer.MAX_VALUE);

        assertEquals(64 * 1024 * 1024,
                new RuntimeEndpointSecurityService(properties).maxResponseBytes());
        properties.getRuntimeEndpoint().setMaxResponseBytes(1);
        assertEquals(1024, new RuntimeEndpointSecurityService(properties).maxResponseBytes());
    }
}
