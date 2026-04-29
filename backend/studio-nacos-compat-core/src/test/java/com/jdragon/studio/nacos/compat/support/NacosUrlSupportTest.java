package com.jdragon.studio.nacos.compat.support;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NacosUrlSupportTest {

    @Test
    void shouldBuildUriForEachCommaSeparatedServerAddress() {
        List<URI> uris = NacosUrlSupport.buildAll("https://nacos-a.example.com:8848,nacos-b.example.com:8848/",
                "/nacos/v1/ns/service/list", Map.of("groupName", "ZCYY_GROUP"));

        assertEquals(2, uris.size());
        assertEquals("https://nacos-a.example.com:8848/nacos/v1/ns/service/list?groupName=ZCYY_GROUP",
                uris.get(0).toString());
        assertEquals("https://nacos-b.example.com:8848/nacos/v1/ns/service/list?groupName=ZCYY_GROUP",
                uris.get(1).toString());
    }
}
