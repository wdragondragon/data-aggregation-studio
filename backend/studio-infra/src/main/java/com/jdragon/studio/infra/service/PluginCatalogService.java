package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.dto.model.PluginAssetDescriptor;
import com.jdragon.studio.infra.entity.CatalogPluginEntity;
import com.jdragon.studio.infra.mapper.CatalogPluginMapper;
import com.jdragon.studio.infra.service.plugin.PluginCatalogScanner;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PluginCatalogService {

    private final CatalogPluginMapper catalogPluginMapper;
    private final PluginCatalogScanner scanner;

    public PluginCatalogService(CatalogPluginMapper catalogPluginMapper, PluginCatalogScanner scanner) {
        this.catalogPluginMapper = catalogPluginMapper;
        this.scanner = scanner;
    }

    public void bootstrapCatalog() {
        List<PluginAssetDescriptor> descriptors = scanner.scan();
        Set<String> scannedPaths = new LinkedHashSet<String>();
        for (PluginAssetDescriptor descriptor : descriptors) {
            scannedPaths.add(descriptor.getAssetPath());
            CatalogPluginEntity existing = catalogPluginMapper.selectOne(new LambdaQueryWrapper<CatalogPluginEntity>()
                    .eq(CatalogPluginEntity::getAssetPath, descriptor.getAssetPath())
                    .last("limit 1"));
            CatalogPluginEntity entity = existing == null ? new CatalogPluginEntity() : existing;
            entity.setPluginName(descriptor.getPluginName());
            entity.setPluginCategory(descriptor.getPluginCategory().name());
            entity.setAssetType(descriptor.getAssetType());
            entity.setAssetPath(descriptor.getAssetPath());
            entity.setExecutable(descriptor.isExecutable() ? 1 : 0);
            entity.setMetadata(descriptor.getMetadata());
            entity.setTemplate(descriptor.getTemplate());
            if (entity.getId() == null) {
                catalogPluginMapper.insert(entity);
            } else {
                catalogPluginMapper.updateById(entity);
            }
        }

        List<CatalogPluginEntity> existingEntries = catalogPluginMapper.selectList(new LambdaQueryWrapper<CatalogPluginEntity>()
                .select(CatalogPluginEntity::getId, CatalogPluginEntity::getAssetPath));
        List<Long> staleIds = new ArrayList<Long>();
        for (CatalogPluginEntity entry : existingEntries) {
            if (entry.getAssetPath() == null || !scannedPaths.contains(entry.getAssetPath())) {
                staleIds.add(entry.getId());
            }
        }
        if (!staleIds.isEmpty()) {
            catalogPluginMapper.deleteByIds(staleIds);
        }
    }

    public List<CatalogPluginEntity> list() {
        return catalogPluginMapper.selectList(new LambdaQueryWrapper<CatalogPluginEntity>()
                .orderByAsc(CatalogPluginEntity::getPluginCategory)
                .orderByAsc(CatalogPluginEntity::getPluginName));
    }

    public List<CatalogPluginEntity> listByCategory(String category) {
        return catalogPluginMapper.selectList(new LambdaQueryWrapper<CatalogPluginEntity>()
                .eq(CatalogPluginEntity::getPluginCategory, category));
    }

    public List<String> executableSourceTypes() {
        List<String> supported = new ArrayList<String>();
        for (Map<String, Object> row : sourceCapabilities()) {
            if (Boolean.TRUE.equals(row.get("readable"))) {
                supported.add(String.valueOf(row.get("typeCode")));
            }
        }
        return supported;
    }

    public List<String> executableTargetTypes() {
        List<String> supported = new ArrayList<String>();
        for (Map<String, Object> row : sourceCapabilities()) {
            if (Boolean.TRUE.equals(row.get("writable"))) {
                supported.add(String.valueOf(row.get("typeCode")));
            }
        }
        return supported;
    }

    public List<String> executableDatasourceTypes() {
        List<String> supported = new ArrayList<String>();
        for (Map<String, Object> row : sourceCapabilities()) {
            if (Boolean.TRUE.equals(row.get("executable"))) {
                supported.add(String.valueOf(row.get("typeCode")));
            }
        }
        return supported;
    }

    public List<Map<String, Object>> sourceCapabilities() {
        List<CatalogPluginEntity> sources = deduplicateByPlugin(listByCategory("SOURCE"));
        Map<String, PluginFamily> readerFamilies = buildFamilies(listByCategory("READER"), "READER");
        Map<String, PluginFamily> writerFamilies = buildFamilies(listByCategory("WRITER"), "WRITER");
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (CatalogPluginEntity source : sources) {
            String typeCode = normalizeCapabilityType(source.getPluginName());
            List<String> readerPlugins = matchingPlugins(readerFamilies, typeCode);
            List<String> writerPlugins = matchingPlugins(writerFamilies, typeCode);
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("typeCode", source.getPluginName());
            row.put("sourcePlugin", source.getPluginName());
            row.put("readable", !readerPlugins.isEmpty());
            row.put("writable", !writerPlugins.isEmpty());
            row.put("executable", !readerPlugins.isEmpty() || !writerPlugins.isEmpty());
            row.put("readerPlugins", readerPlugins);
            row.put("writerPlugins", writerPlugins);
            rows.add(row);
        }
        Collections.sort(rows, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                return String.valueOf(left.get("typeCode")).compareToIgnoreCase(String.valueOf(right.get("typeCode")));
            }
        });
        return rows;
    }

    public List<CatalogPluginEntity> distinctPlugins() {
        return deduplicateByPlugin(list());
    }

    public List<String> sourceTypes() {
        List<String> types = new ArrayList<String>();
        for (CatalogPluginEntity entry : deduplicateByPlugin(listByCategory("SOURCE"))) {
            if (entry.getPluginName() == null || entry.getPluginName().trim().isEmpty()) {
                continue;
            }
            if (!types.contains(entry.getPluginName())) {
                types.add(entry.getPluginName());
            }
        }
        return types;
    }

    private List<CatalogPluginEntity> deduplicateByPlugin(List<CatalogPluginEntity> entries) {
        Map<String, CatalogPluginEntity> deduplicated = new LinkedHashMap<String, CatalogPluginEntity>();
        for (CatalogPluginEntity entry : entries) {
            String key = String.valueOf(entry.getPluginCategory()) + "::" + String.valueOf(entry.getPluginName());
            CatalogPluginEntity existing = deduplicated.get(key);
            if (existing == null || shouldReplace(existing, entry)) {
                deduplicated.put(key, entry);
            }
        }
        return new ArrayList<CatalogPluginEntity>(deduplicated.values());
    }

    private boolean shouldReplace(CatalogPluginEntity existing, CatalogPluginEntity candidate) {
        int existingExecutable = existing.getExecutable() == null ? 0 : existing.getExecutable();
        int candidateExecutable = candidate.getExecutable() == null ? 0 : candidate.getExecutable();
        if (candidateExecutable != existingExecutable) {
            return candidateExecutable > existingExecutable;
        }
        int existingRank = assetRank(existing.getAssetType());
        int candidateRank = assetRank(candidate.getAssetType());
        if (candidateRank != existingRank) {
            return candidateRank < existingRank;
        }
        return String.valueOf(candidate.getAssetPath()).compareTo(String.valueOf(existing.getAssetPath())) < 0;
    }

    private int assetRank(String assetType) {
        if ("plugin.json".equalsIgnoreCase(assetType)) {
            return 0;
        }
        if ("template.json".equalsIgnoreCase(assetType)) {
            return 1;
        }
        if ("plugin_job_template.json".equalsIgnoreCase(assetType)) {
            return 2;
        }
        if ("transformer.json".equalsIgnoreCase(assetType)) {
            return 3;
        }
        return 99;
    }

    private Map<String, PluginFamily> buildFamilies(List<CatalogPluginEntity> entries, String category) {
        Map<String, PluginFamily> families = new LinkedHashMap<String, PluginFamily>();
        for (CatalogPluginEntity entry : entries) {
            if (entry == null || entry.getPluginName() == null || entry.getPluginName().trim().isEmpty()) {
                continue;
            }
            String key = category + "::" + entry.getPluginName().trim().toLowerCase();
            PluginFamily family = families.get(key);
            if (family == null) {
                family = new PluginFamily(entry.getPluginName());
                families.put(key, family);
            }
            family.supportedTypes.addAll(extractSupportedTypes(entry));
        }
        return families;
    }

    private List<String> matchingPlugins(Map<String, PluginFamily> families, String sourceType) {
        List<String> matched = new ArrayList<String>();
        for (PluginFamily family : families.values()) {
            if (!family.supports(sourceType)) {
                continue;
            }
            if (!matched.contains(family.pluginName)) {
                matched.add(family.pluginName);
            }
        }
        Collections.sort(matched, String.CASE_INSENSITIVE_ORDER);
        return matched;
    }

    private Set<String> extractSupportedTypes(CatalogPluginEntity entry) {
        Set<String> supported = new LinkedHashSet<String>();
        String fromMetadata = extractTypeFromMetadata(entry);
        if (fromMetadata != null && !fromMetadata.isEmpty()) {
            supported.add(fromMetadata);
        }
        String inferred = inferTypeFromPluginName(entry.getPluginName());
        if (inferred != null && !inferred.isEmpty()) {
            supported.add(inferred);
        }
        return supported;
    }

    private String extractTypeFromMetadata(CatalogPluginEntity entry) {
        if (entry == null || entry.getMetadata() == null) {
            return null;
        }
        Object type = entry.getMetadata().get("type");
        if (type == null) {
            return null;
        }
        return normalizeCapabilityType(String.valueOf(type));
    }

    private String inferTypeFromPluginName(String pluginName) {
        return normalizeCapabilityType(pluginName);
    }

    private String normalizeCapabilityType(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return "";
        }
        if (normalized.endsWith("reader")) {
            normalized = normalized.substring(0, normalized.length() - "reader".length());
        } else if (normalized.endsWith("writer")) {
            normalized = normalized.substring(0, normalized.length() - "writer".length());
        }
        if ("postgresql".equals(normalized) || "postgresqlreader".equals(normalized) || "postgresqlwriter".equals(normalized)) {
            return "postgres";
        }
        return normalized;
    }

    private static final class PluginFamily {
        private final String pluginName;
        private final Set<String> supportedTypes = new LinkedHashSet<String>();

        private PluginFamily(String pluginName) {
            this.pluginName = pluginName;
        }

        private boolean supports(String sourceType) {
            if (sourceType == null || sourceType.trim().isEmpty()) {
                return false;
            }
            return supportedTypes.contains(sourceType.trim().toLowerCase());
        }
    }
}

