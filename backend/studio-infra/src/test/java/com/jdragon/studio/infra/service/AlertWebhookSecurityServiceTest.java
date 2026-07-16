package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AlertWebhookSecurityServiceTest {

    @Test
    void shouldRejectHttpAndPrivateAddressesByDefault() {
        AlertWebhookSecurityService service = new AlertWebhookSecurityService(new StudioPlatformProperties());

        assertThrows(StudioException.class, () -> service.validate("http://example.com/hook"));
        assertThrows(StudioException.class, () -> service.validate("https://127.0.0.1/hook"));
        assertThrows(StudioException.class, () -> service.validate("https://169.254.169.254/latest/meta-data"));
        assertThrows(StudioException.class, () -> service.validate("https://[::ffff:127.0.0.1]/hook"));
        assertThrows(StudioException.class, () -> service.validate("https://example.com/hook#secret"));
    }

    @Test
    void shouldRejectIpv6TransitionAddressesThatCanBypassPrivateAddressChecks() throws Exception {
        assertRejectedResolvedAddress("64:ff9b::7f00:1");
        assertRejectedResolvedAddress("2002:7f00:1::");
        assertRejectedResolvedAddress("2001:0:4136:e378:8000:63bf:3fff:fdd2");
    }

    @Test
    void explicitAllowlistShouldPermitInternalTestEndpoint() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getAlert().getWebhook().setAllowHttp(true);
        properties.getAlert().getWebhook().setAllowedHosts(Collections.singletonList("127.0.0.1"));
        AlertWebhookSecurityService service = new AlertWebhookSecurityService(properties);

        assertEquals("127.0.0.1", service.validate("http://127.0.0.1:19090/hook").getHost());
    }

    @Test
    void saveValidationShouldRejectDnsFailureButDeliveryValidationShouldExposeRetryableFailure() {
        AlertWebhookSecurityService service = new AlertWebhookSecurityService(new StudioPlatformProperties()) {
            @Override
            protected InetAddress[] resolveHost(String host) throws UnknownHostException {
                throw new UnknownHostException(host);
            }
        };

        assertThrows(StudioException.class, () -> service.validate("https://hooks.example.com/alert"));
        assertThrows(AlertWebhookSecurityService.WebhookDnsResolutionException.class,
                () -> service.validateAndResolve("https://hooks.example.com/alert"));
    }

    private void assertRejectedResolvedAddress(String address) throws Exception {
        InetAddress resolved = InetAddress.getByName(address);
        AlertWebhookSecurityService service = new AlertWebhookSecurityService(new StudioPlatformProperties()) {
            @Override
            protected InetAddress[] resolveHost(String host) {
                return new InetAddress[]{resolved};
            }
        };

        assertThrows(StudioException.class, () -> service.validate("https://hooks.example.com/alert"));
    }
}
