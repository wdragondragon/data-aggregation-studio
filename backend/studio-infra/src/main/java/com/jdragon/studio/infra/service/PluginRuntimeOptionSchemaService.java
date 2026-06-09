package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.DatasourceTypeCapabilityView;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.PluginRuntimeOptionSchemaView;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class PluginRuntimeOptionSchemaService {

    private static final String ROLE_READER = "reader";
    private static final String ROLE_WRITER = "writer";
    private static final String CATEGORY_DATABASE = "DATABASE";

    private final DatasourceTypeCapabilityService datasourceTypeCapabilityService;
    private final MetadataSchemaService metadataSchemaService;

    public PluginRuntimeOptionSchemaService(DatasourceTypeCapabilityService datasourceTypeCapabilityService,
                                            MetadataSchemaService metadataSchemaService) {
        this.datasourceTypeCapabilityService = datasourceTypeCapabilityService;
        this.metadataSchemaService = metadataSchemaService;
    }

    public PluginRuntimeOptionSchemaView schema(String role, String datasourceType) {
        return schema(role, datasourceType, null);
    }

    public PluginRuntimeOptionSchemaView schema(String role, String datasourceType, String protocolMode) {
        String normalizedRole = normalizeRole(role);
        String normalizedType = normalize(datasourceType);
        boolean virtualFusionReader = isVirtualFusionReader(normalizedRole, normalizedType);
        DatasourceTypeCapabilityView capability = virtualFusionReader ? null : findCapability(normalizedType);
        String sourceCategory = capability == null ? null : capability.getSourceCategory();
        String pluginType = virtualFusionReader ? "fusion" : resolvePluginType(normalizedType, normalizedRole);
        String schemaPluginType = resolveRuntimeSchemaPluginType(normalizedType, pluginType, protocolMode);
        MetadataSchemaDefinition schema = metadataSchemaService.findRuntimeOptionSchema(normalizedRole, schemaPluginType);

        PluginRuntimeOptionSchemaView view = new PluginRuntimeOptionSchemaView();
        view.setRole(normalizedRole);
        view.setDatasourceType(normalizedType);
        view.setSourceCategory(sourceCategory);
        view.setPluginType(pluginType);
        view.setRuntimeSupported(virtualFusionReader || runtimeSupported(normalizedRole, capability, pluginType));
        view.setIncrementalSupported(!virtualFusionReader && incrementalSupported(normalizedType, normalizedRole));
        view.setReservedKeys(reservedKeys(normalizedRole));
        view.setFields(schema == null ? Collections.<MetadataFieldDefinition>emptyList() : filterConfigurableFields(schema.getFields(), normalizedRole));
        view.setDescription(description(view, schema));
        return view;
    }

    public String resolvePluginType(String datasourceType, String role) {
        String normalizedType = normalize(datasourceType);
        String normalizedRole = normalizeRole(role);
        if (isVirtualFusionReader(normalizedRole, normalizedType)) {
            return "fusion";
        }
        DatasourceTypeCapabilityView capability = findCapability(normalizedType);
        if (capability != null) {
            List<String> plugins = ROLE_WRITER.equals(normalizedRole)
                    ? capability.getWriterPlugins()
                    : capability.getReaderPlugins();
            if (plugins != null && !plugins.isEmpty()) {
                return normalizePlugin(plugins.get(0));
            }
        }
        return normalizedType;
    }

    public String sourceCategory(String datasourceType) {
        DatasourceTypeCapabilityView capability = findCapability(normalize(datasourceType));
        return capability == null ? null : capability.getSourceCategory();
    }

    public boolean incrementalSupported(String datasourceType, String role) {
        if (!ROLE_READER.equals(normalizeRole(role))) {
            return false;
        }
        return CATEGORY_DATABASE.equalsIgnoreCase(sourceCategory(datasourceType));
    }

    public List<String> reservedKeys(String role) {
        if (ROLE_WRITER.equals(normalizeRole(role))) {
            return Arrays.asList("connect", "table", "topic", "measurement", "columns", "sourceAlias",
                    "rootPath", "fileName", "fileType", "encoding", "delimiter", "efile",
                    "url", "mode", "protocolMode", "payloadFormat", "responseType");
        }
        return Arrays.asList("connect", "config", "table", "topic", "measurement", "columns", "sourceAlias",
                "sources", "join", "fieldMappings", "incrColumn", "incrModel", "pkValue", "dataTag",
                "rootPath", "partitionType", "partition", "pattern", "fileType", "encoding", "delimiter",
                "url", "mode", "protocolMode", "resultType", "responseStatus", "totalCodePath");
    }

    private String resolveRuntimeSchemaPluginType(String datasourceType, String pluginType, String protocolMode) {
        if ("http".equals(normalize(datasourceType)) && "http".equals(normalizePlugin(pluginType))
                && "SOAP".equalsIgnoreCase(protocolMode == null ? "" : protocolMode.trim())) {
            return "http-soap";
        }
        return pluginType;
    }

    public boolean runtimeSupported(String role, DatasourceTypeCapabilityView capability, String pluginType) {
        if (capability == null || pluginType == null || pluginType.trim().isEmpty()) {
            return false;
        }
        String normalizedRole = normalizeRole(role);
        String normalizedPlugin = normalizePlugin(pluginType);
        if (ROLE_WRITER.equals(normalizedRole)) {
            return Boolean.TRUE.equals(capability.getWritable()) && containsPlugin(capability.getWriterPlugins(), normalizedPlugin);
        }
        return Boolean.TRUE.equals(capability.getReadable()) && containsPlugin(capability.getReaderPlugins(), normalizedPlugin);
    }

    private boolean containsPlugin(List<String> plugins, String pluginType) {
        if (plugins == null || plugins.isEmpty()) {
            return false;
        }
        for (String plugin : plugins) {
            if (pluginType.equals(normalizePlugin(plugin))) {
                return true;
            }
        }
        return false;
    }

    private boolean isVirtualFusionReader(String role, String datasourceType) {
        return ROLE_READER.equals(normalizeRole(role)) && "fusion".equals(normalize(datasourceType));
    }

    private List<MetadataFieldDefinition> filterConfigurableFields(List<MetadataFieldDefinition> fields, String role) {
        if (fields == null || fields.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> reserved = new LinkedHashSet<String>();
        for (String key : reservedKeys(role)) {
            reserved.add(normalize(key));
        }
        List<MetadataFieldDefinition> result = new ArrayList<MetadataFieldDefinition>();
        for (MetadataFieldDefinition field : fields) {
            if (field == null || field.getFieldKey() == null || field.getFieldKey().trim().isEmpty()) {
                continue;
            }
            if (reserved.contains(normalize(field.getFieldKey()))) {
                continue;
            }
            result.add(field);
        }
        return result;
    }

    private DatasourceTypeCapabilityView findCapability(String datasourceType) {
        for (DatasourceTypeCapabilityView capability : datasourceTypeCapabilityService.listEnabled()) {
            if (capability.getTypeCode() != null
                    && capability.getTypeCode().trim().equalsIgnoreCase(datasourceType)) {
                return capability;
            }
        }
        return null;
    }

    private String description(PluginRuntimeOptionSchemaView view, MetadataSchemaDefinition schema) {
        if (!Boolean.TRUE.equals(view.getRuntimeSupported())) {
            return "No runtime job plugin is registered for this role.";
        }
        if (schema == null) {
            return "No runtime option meta model is configured for this plugin.";
        }
        if (view.getFields() == null || view.getFields().isEmpty()) {
            return "The runtime option meta model has no configurable fields.";
        }
        return "Advanced options are loaded from the runtime option meta model.";
    }

    private String normalizeRole(String role) {
        String normalized = normalize(role);
        if (ROLE_WRITER.equals(normalized)) {
            return ROLE_WRITER;
        }
        return ROLE_READER;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ENGLISH);
    }

    private String normalizePlugin(String value) {
        String normalized = normalize(value);
        if (normalized.endsWith("reader")) {
            return normalized.substring(0, normalized.length() - "reader".length());
        }
        if (normalized.endsWith("writer")) {
            return normalized.substring(0, normalized.length() - "writer".length());
        }
        return normalized;
    }
}
