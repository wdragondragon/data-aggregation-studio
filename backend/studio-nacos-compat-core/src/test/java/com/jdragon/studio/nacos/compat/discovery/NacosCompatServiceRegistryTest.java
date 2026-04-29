package com.jdragon.studio.nacos.compat.discovery;

import com.jdragon.studio.nacos.compat.props.NacosCompatProperties;
import com.jdragon.studio.nacos.compat.props.NacosDiscoveryProperties;
import com.jdragon.studio.nacos.compat.props.NacosRootProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NacosCompatServiceRegistryTest {

    @Test
    void shouldReRegisterWhenLegacyBeatFails() throws Exception {
        NacosDiscoveryAccessor accessor = mock(NacosDiscoveryAccessor.class);
        NacosRootProperties rootProperties = new NacosRootProperties();
        NacosDiscoveryProperties discoveryProperties = new NacosDiscoveryProperties();
        NacosRegistration registration = new NacosRegistration("instance-1", "dfs-service", "10.0.0.10", 15001,
                false, Map.of(), "ZCYY", "ZCYY_GROUP", "DEFAULT", 1.0D, true);
        doThrow(new IllegalStateException("missing instance")).when(accessor)
                .beatLegacy(rootProperties, discoveryProperties, registration);
        NacosCompatServiceRegistry registry = new NacosCompatServiceRegistry(accessor, rootProperties,
                discoveryProperties, new NacosCompatProperties());

        Method sendLegacyBeat = NacosCompatServiceRegistry.class.getDeclaredMethod("sendLegacyBeat",
                NacosRegistration.class);
        sendLegacyBeat.setAccessible(true);
        sendLegacyBeat.invoke(registry, registration);

        verify(accessor).register(rootProperties, discoveryProperties, registration);
    }
}
