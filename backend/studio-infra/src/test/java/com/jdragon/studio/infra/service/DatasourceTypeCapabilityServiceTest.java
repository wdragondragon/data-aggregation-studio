package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.entity.DatasourceTypeCapabilityEntity;
import com.jdragon.studio.infra.mapper.DatasourceTypeCapabilityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatasourceTypeCapabilityServiceTest {
    private final DatasourceTypeCapabilityMapper mapper = mock(DatasourceTypeCapabilityMapper.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final DatasourceTypeCapabilityService service =
            new DatasourceTypeCapabilityService(mapper, jdbcTemplate);

    @BeforeEach
    void setUp() {
        doReturn(Boolean.TRUE).when(jdbcTemplate).execute(any(ConnectionCallback.class));
    }

    @Test
    void canonicalizesFileTypeAliases() {
        assertThat(DatasourceTypeCapabilityService.normalizeTypeCode("file")).isEqualTo("local");
        assertThat(DatasourceTypeCapabilityService.normalizeTypeCode(" local_file ")).isEqualTo("local");
        assertThat(DatasourceTypeCapabilityService.normalizeTypeCode("aliyun")).isEqualTo("oss");
        assertThat(DatasourceTypeCapabilityService.normalizeTypeCode("ALIYUN_OSS")).isEqualTo("oss");
        assertThat(DatasourceTypeCapabilityService.normalizeTypeCode("aliyun-oss")).isEqualTo("oss");
    }

    @Test
    void missingRuntimeCapabilityIsFalse() {
        when(mapper.selectOne(any())).thenReturn(entity("ftp", null));

        assertThat(service.hasRuntimeCapability("ftp", "browse")).isFalse();
        assertThat(service.hasRuntimeCapability("ftp", "transferSource")).isFalse();
        assertThat(service.hasRuntimeCapability("ftp", " ")).isFalse();
    }

    @Test
    void fileAliasesReadTheCanonicalRuntimeCapabilities() {
        when(mapper.selectOne(any())).thenReturn(entity("local", runtimeCapabilities()));

        assertThat(service.hasRuntimeCapability("file", "browse")).isTrue();
        assertThat(service.hasRuntimeCapability("local_file", "transferTarget")).isTrue();
        assertThat(service.hasRuntimeCapability("aliyun-oss", "manage")).isTrue();
    }

    @Test
    void allSupportedFileTypesExposeTransferCapabilities() {
        for (String type : List.of("local", "ftp", "sftp", "minio", "oss")) {
            when(mapper.selectOne(any())).thenReturn(entity(type, runtimeCapabilities()));
            assertThat(service.hasRuntimeCapability(type, "browse")).as(type).isTrue();
            assertThat(service.hasRuntimeCapability(type, "read")).as(type).isTrue();
            assertThat(service.hasRuntimeCapability(type, "write")).as(type).isTrue();
            assertThat(service.hasRuntimeCapability(type, "manage")).as(type).isTrue();
            assertThat(service.hasRuntimeCapability(type, "transferSource")).as(type).isTrue();
            assertThat(service.hasRuntimeCapability(type, "transferTarget")).as(type).isTrue();
        }
    }

    @Test
    void nonFileRuntimeTypeDoesNotGainBinaryCapabilities() {
        when(mapper.selectOne(any())).thenReturn(entity("mysql8", Collections.emptyMap()));

        assertThat(service.hasRuntimeCapability("mysql8", "browse")).isFalse();
        assertThat(service.hasRuntimeCapability("mysql8", "transferTarget")).isFalse();
    }

    @Test
    void listsCanonicalTypesAndOptionalAliases() {
        when(mapper.selectList(any())).thenReturn(Arrays.asList(
                entity("local", runtimeCapabilities()),
                entity("ftp", runtimeCapabilities()),
                entity("oss", runtimeCapabilities()),
                entity("mysql8", Collections.emptyMap())));

        assertThat(service.typesWithRuntimeCapability("transferSource", false))
                .containsExactly("local", "ftp", "oss");
        assertThat(service.typesWithRuntimeCapability("transferTarget", true))
                .containsExactly("local", "ftp", "oss", "file", "local_file",
                        "aliyun", "aliyun_oss", "aliyun-oss");
    }

    private DatasourceTypeCapabilityEntity entity(String typeCode,
                                                  Map<String, Object> runtimeCapabilities) {
        DatasourceTypeCapabilityEntity entity = new DatasourceTypeCapabilityEntity();
        entity.setTenantId("default");
        entity.setTypeCode(typeCode);
        entity.setEnabled(1);
        entity.setRuntimeCapabilitiesJson(runtimeCapabilities);
        return entity;
    }

    private Map<String, Object> runtimeCapabilities() {
        Map<String, Object> binaryFile = new LinkedHashMap<String, Object>();
        binaryFile.put("browse", Boolean.TRUE);
        binaryFile.put("read", Boolean.TRUE);
        binaryFile.put("write", Boolean.TRUE);
        binaryFile.put("manage", Boolean.TRUE);
        binaryFile.put("transferSource", Boolean.TRUE);
        binaryFile.put("transferTarget", Boolean.TRUE);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("binaryFile", binaryFile);
        return result;
    }
}
