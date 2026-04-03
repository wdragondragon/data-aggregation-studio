package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.dto.model.PluginAssetDescriptor;
import com.jdragon.studio.infra.entity.CatalogPluginEntity;
import com.jdragon.studio.infra.mapper.CatalogPluginMapper;
import com.jdragon.studio.infra.service.plugin.PluginCatalogScanner;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
        List<CatalogPluginEntity> sources = deduplicateByPlugin(listByCategory("SOURCE"));
        List<CatalogPluginEntity> readers = deduplicateByPlugin(listByCategory("READER"));
        List<String> supported = new ArrayList<String>();
        for (CatalogPluginEntity source : sources) {
            String readerCode = source.getPluginName() + "reader";
            for (CatalogPluginEntity reader : readers) {
                if (readerCode.equalsIgnoreCase(reader.getPluginName())) {
                    if (!supported.contains(source.getPluginName())) {
                        supported.add(source.getPluginName());
                    }
                    break;
                }
            }
        }
        return supported;
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
}

