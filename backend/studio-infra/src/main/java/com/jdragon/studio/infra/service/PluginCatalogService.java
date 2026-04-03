package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.dto.model.PluginAssetDescriptor;
import com.jdragon.studio.infra.entity.CatalogPluginEntity;
import com.jdragon.studio.infra.mapper.CatalogPluginMapper;
import com.jdragon.studio.infra.service.plugin.PluginCatalogScanner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PluginCatalogService {

    private final CatalogPluginMapper catalogPluginMapper;
    private final PluginCatalogScanner scanner;

    public PluginCatalogService(CatalogPluginMapper catalogPluginMapper, PluginCatalogScanner scanner) {
        this.catalogPluginMapper = catalogPluginMapper;
        this.scanner = scanner;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapCatalog() {
        List<PluginAssetDescriptor> descriptors = scanner.scan();
        for (PluginAssetDescriptor descriptor : descriptors) {
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
        List<CatalogPluginEntity> sources = listByCategory("SOURCE");
        List<CatalogPluginEntity> readers = listByCategory("READER");
        List<String> supported = new ArrayList<String>();
        for (CatalogPluginEntity source : sources) {
            String readerCode = source.getPluginName() + "reader";
            for (CatalogPluginEntity reader : readers) {
                if (readerCode.equalsIgnoreCase(reader.getPluginName())) {
                    supported.add(source.getPluginName());
                    break;
                }
            }
        }
        return supported;
    }
}

