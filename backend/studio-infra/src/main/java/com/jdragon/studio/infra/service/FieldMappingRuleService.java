package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.FieldMappingRuleParamView;
import com.jdragon.studio.dto.model.FieldMappingRuleView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.FieldMappingRuleParamSaveRequest;
import com.jdragon.studio.dto.model.request.FieldMappingRuleSaveRequest;
import com.jdragon.studio.infra.entity.FieldMappingRuleEntity;
import com.jdragon.studio.infra.entity.FieldMappingRuleParamEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.mapper.FieldMappingRuleMapper;
import com.jdragon.studio.infra.mapper.FieldMappingRuleParamMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class FieldMappingRuleService {

    private static final Set<String> ALLOWED_COMPONENT_TYPES = new LinkedHashSet<String>();
    private static final Set<String> ALLOWED_MAPPING_TYPES = new LinkedHashSet<String>();

    static {
        Collections.addAll(ALLOWED_COMPONENT_TYPES,
                "input",
                "numberPicker",
                "textArea",
                "datePicker",
                "dateTimePicker",
                "rangePicker",
                "select",
                "checkbox",
                "radioGroup");
        Collections.addAll(ALLOWED_MAPPING_TYPES,
                "过滤",
                "规整",
                "脱敏",
                "加密");
    }

    private final FieldMappingRuleMapper fieldMappingRuleMapper;
    private final FieldMappingRuleParamMapper fieldMappingRuleParamMapper;
    private final StudioUserMapper studioUserMapper;
    private final StudioSecurityService securityService;
    private final ObjectMapper objectMapper;

    public FieldMappingRuleService(FieldMappingRuleMapper fieldMappingRuleMapper,
                                   FieldMappingRuleParamMapper fieldMappingRuleParamMapper,
                                   StudioUserMapper studioUserMapper,
                                   StudioSecurityService securityService,
                                   ObjectMapper objectMapper) {
        this.fieldMappingRuleMapper = fieldMappingRuleMapper;
        this.fieldMappingRuleParamMapper = fieldMappingRuleParamMapper;
        this.studioUserMapper = studioUserMapper;
        this.securityService = securityService;
        this.objectMapper = objectMapper;
    }

    public PageView<FieldMappingRuleView> list(Integer pageNo,
                                               Integer pageSize,
                                               String keyword,
                                               String mappingType,
                                               Boolean enabled) {
        requireSuperAdmin();
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        String normalizedKeyword = normalizeText(keyword);
        String normalizedType = normalizeText(mappingType);
        Page<FieldMappingRuleEntity> page = new Page<FieldMappingRuleEntity>(safePageNo, safePageSize);
        LambdaQueryWrapper<FieldMappingRuleEntity> queryWrapper = new LambdaQueryWrapper<FieldMappingRuleEntity>()
                .and(hasText(normalizedKeyword), wrapper -> wrapper.like(FieldMappingRuleEntity::getMappingName, normalizedKeyword)
                        .or()
                        .like(FieldMappingRuleEntity::getMappingCode, normalizedKeyword)
                        .or()
                        .like(FieldMappingRuleEntity::getMappingType, normalizedKeyword))
                .eq(hasText(normalizedType), FieldMappingRuleEntity::getMappingType, normalizedType)
                .eq(enabled != null, FieldMappingRuleEntity::getEnabled, enabled == null ? null : (enabled.booleanValue() ? Integer.valueOf(1) : Integer.valueOf(0)))
                .orderByDesc(FieldMappingRuleEntity::getCreatedAt)
                .orderByDesc(FieldMappingRuleEntity::getId);
        Page<FieldMappingRuleEntity> entityPage = fieldMappingRuleMapper.selectPage(page, queryWrapper);
        Map<Long, String> creatorNames = resolveCreatorNames(entityPage.getRecords());
        List<FieldMappingRuleView> items = new ArrayList<FieldMappingRuleView>();
        for (FieldMappingRuleEntity entity : entityPage.getRecords()) {
            items.add(toView(entity, creatorNames.get(entity.getCreatedBy()), false));
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), items);
    }

    public FieldMappingRuleView get(Long id) {
        requireSuperAdmin();
        FieldMappingRuleEntity entity = requireRule(id);
        return toView(entity, resolveCreatorName(entity.getCreatedBy()), true);
    }

    @Transactional
    public FieldMappingRuleView save(FieldMappingRuleSaveRequest request) {
        requireSuperAdmin();
        validateRequest(request);
        FieldMappingRuleEntity entity = request.getId() == null
                ? new FieldMappingRuleEntity()
                : requireRule(request.getId());
        ensureUniqueMappingCode(normalizeText(request.getMappingCode()), entity.getId());

        entity.setMappingName(normalizeText(request.getMappingName()));
        entity.setMappingType(normalizeMappingType(request.getMappingType()));
        entity.setMappingCode(normalizeText(request.getMappingCode()));
        entity.setEnabled(Boolean.FALSE.equals(request.getEnabled()) ? Integer.valueOf(0) : Integer.valueOf(1));
        entity.setDescription(normalizeNullableText(request.getDescription()));
        if (entity.getId() == null) {
            entity.setCreatedBy(securityService.currentUserId());
            fieldMappingRuleMapper.insert(entity);
        } else {
            fieldMappingRuleMapper.updateById(entity);
            fieldMappingRuleParamMapper.delete(new LambdaQueryWrapper<FieldMappingRuleParamEntity>()
                    .eq(FieldMappingRuleParamEntity::getRuleId, entity.getId()));
        }

        List<FieldMappingRuleParamSaveRequest> params = request.getParams() == null
                ? new ArrayList<FieldMappingRuleParamSaveRequest>()
                : new ArrayList<FieldMappingRuleParamSaveRequest>(request.getParams());
        params.sort((left, right) -> Integer.compare(left.getParamOrder() == null ? Integer.MAX_VALUE : left.getParamOrder().intValue(),
                right.getParamOrder() == null ? Integer.MAX_VALUE : right.getParamOrder().intValue()));
        for (FieldMappingRuleParamSaveRequest param : params) {
            FieldMappingRuleParamEntity item = new FieldMappingRuleParamEntity();
            String componentType = normalizeComponentType(param.getComponentType());
            item.setRuleId(entity.getId());
            item.setParamName(normalizeText(param.getParamName()));
            item.setParamOrder(param.getParamOrder());
            item.setComponentType(componentType);
            item.setParamValueJson(normalizeNullableJson(param.getParamValueJson(), componentType));
            item.setDescription(normalizeNullableText(param.getDescription()));
            fieldMappingRuleParamMapper.insert(item);
        }
        return toView(fieldMappingRuleMapper.selectById(entity.getId()), resolveCreatorName(entity.getCreatedBy()), true);
    }

    @Transactional
    public void delete(Long id) {
        requireSuperAdmin();
        requireRule(id);
        fieldMappingRuleParamMapper.delete(new LambdaQueryWrapper<FieldMappingRuleParamEntity>()
                .eq(FieldMappingRuleParamEntity::getRuleId, id));
        fieldMappingRuleMapper.deleteById(id);
    }

    public List<FieldMappingRuleView> options(String mappingType) {
        String normalizedType = normalizeText(mappingType);
        List<FieldMappingRuleEntity> entities = fieldMappingRuleMapper.selectList(new LambdaQueryWrapper<FieldMappingRuleEntity>()
                .eq(FieldMappingRuleEntity::getEnabled, Integer.valueOf(1))
                .eq(hasText(normalizedType), FieldMappingRuleEntity::getMappingType, normalizedType)
                .orderByAsc(FieldMappingRuleEntity::getMappingType)
                .orderByAsc(FieldMappingRuleEntity::getMappingName)
                .orderByAsc(FieldMappingRuleEntity::getId));
        Map<Long, String> creatorNames = resolveCreatorNames(entities);
        List<FieldMappingRuleView> result = new ArrayList<FieldMappingRuleView>();
        for (FieldMappingRuleEntity entity : entities) {
            result.add(toView(entity, creatorNames.get(entity.getCreatedBy()), true));
        }
        return result;
    }

    private FieldMappingRuleView toView(FieldMappingRuleEntity entity, String createdByName, boolean includeParams) {
        FieldMappingRuleView view = new FieldMappingRuleView();
        view.setId(entity.getId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setMappingName(entity.getMappingName());
        view.setMappingType(entity.getMappingType());
        view.setMappingCode(entity.getMappingCode());
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled().intValue() == 1);
        view.setDescription(entity.getDescription());
        view.setCreatedBy(entity.getCreatedBy());
        view.setCreatedByName(createdByName);
        if (includeParams) {
            view.setParams(loadParams(entity.getId()));
        }
        return view;
    }

    private List<FieldMappingRuleParamView> loadParams(Long ruleId) {
        if (ruleId == null) {
            return new ArrayList<FieldMappingRuleParamView>();
        }
        List<FieldMappingRuleParamEntity> entities = fieldMappingRuleParamMapper.selectList(new LambdaQueryWrapper<FieldMappingRuleParamEntity>()
                .eq(FieldMappingRuleParamEntity::getRuleId, ruleId)
                .orderByAsc(FieldMappingRuleParamEntity::getParamOrder)
                .orderByAsc(FieldMappingRuleParamEntity::getId));
        List<FieldMappingRuleParamView> items = new ArrayList<FieldMappingRuleParamView>();
        for (FieldMappingRuleParamEntity entity : entities) {
            FieldMappingRuleParamView item = new FieldMappingRuleParamView();
            item.setId(entity.getId());
            item.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
            item.setCreatedAt(entity.getCreatedAt());
            item.setUpdatedAt(entity.getUpdatedAt());
            item.setRuleId(entity.getRuleId());
            item.setParamName(entity.getParamName());
            item.setParamOrder(entity.getParamOrder());
            item.setComponentType(entity.getComponentType());
            item.setParamValueJson(entity.getParamValueJson());
            item.setDescription(entity.getDescription());
            items.add(item);
        }
        return items;
    }

    private void validateRequest(FieldMappingRuleSaveRequest request) {
        if (request == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Field mapping rule request is required");
        }
        if (!hasText(request.getMappingName())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Mapping name is required");
        }
        if (!hasText(request.getMappingType())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Mapping type is required");
        }
        normalizeMappingType(request.getMappingType());
        if (!hasText(request.getMappingCode())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Mapping code is required");
        }
        Set<Integer> orders = new LinkedHashSet<Integer>();
        Set<String> names = new LinkedHashSet<String>();
        List<FieldMappingRuleParamSaveRequest> params = request.getParams() == null
                ? Collections.<FieldMappingRuleParamSaveRequest>emptyList()
                : request.getParams();
        for (FieldMappingRuleParamSaveRequest param : params) {
            if (param == null) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Rule parameter is required");
            }
            if (!hasText(param.getParamName())) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Parameter name is required");
            }
            if (param.getParamOrder() == null) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Parameter order is required");
            }
            if (!hasText(param.getComponentType())) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Parameter component type is required");
            }
            String normalizedName = normalizeText(param.getParamName()).toLowerCase(Locale.ROOT);
            if (!names.add(normalizedName)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Parameter name must be unique within the same rule");
            }
            if (!orders.add(param.getParamOrder())) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Parameter order must be unique within the same rule");
            }
            String componentType = normalizeComponentType(param.getComponentType());
            normalizeNullableJson(param.getParamValueJson(), componentType);
        }
    }

    private String normalizeComponentType(String componentType) {
        String normalized = normalizeText(componentType);
        if (!ALLOWED_COMPONENT_TYPES.contains(normalized)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported parameter component type: " + componentType);
        }
        return normalized;
    }

    private String normalizeMappingType(String mappingType) {
        String normalized = normalizeText(mappingType);
        if (!ALLOWED_MAPPING_TYPES.contains(normalized)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported mapping type: " + mappingType);
        }
        return normalized;
    }

    private String normalizeNullableJson(String value, String componentType) {
        String normalized = normalizeNullableText(value);
        if (!hasText(normalized)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(normalized);
            if (!isOptionValueComponent(componentType)) {
                return normalized;
            }
            if (!node.isArray()) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Option parameter values must be a JSON string array");
            }
            List<String> values = new ArrayList<String>();
            for (JsonNode item : node) {
                if (item == null || item.isNull()) {
                    continue;
                }
                if (!item.isTextual()) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST, "Option parameter values must be a JSON string array");
                }
                String text = normalizeText(item.asText());
                if (!hasText(text)) {
                    continue;
                }
                if (!values.contains(text)) {
                    values.add(text);
                }
            }
            return values.isEmpty() ? null : objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Parameter value JSON is invalid: " + ex.getOriginalMessage());
        }
    }

    private boolean isOptionValueComponent(String componentType) {
        return "select".equals(componentType)
                || "checkbox".equals(componentType)
                || "radioGroup".equals(componentType);
    }

    private void ensureUniqueMappingCode(String mappingCode, Long selfId) {
        FieldMappingRuleEntity duplicate = fieldMappingRuleMapper.selectOne(new LambdaQueryWrapper<FieldMappingRuleEntity>()
                .eq(FieldMappingRuleEntity::getMappingCode, mappingCode)
                .last("limit 1"));
        if (duplicate == null) {
            return;
        }
        if (selfId != null && selfId.equals(duplicate.getId())) {
            return;
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "Mapping code already exists");
    }

    private FieldMappingRuleEntity requireRule(Long id) {
        if (id == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Rule id is required");
        }
        FieldMappingRuleEntity entity = fieldMappingRuleMapper.selectById(id);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Field mapping rule not found: " + id);
        }
        return entity;
    }

    private Map<Long, String> resolveCreatorNames(List<FieldMappingRuleEntity> entities) {
        Set<Long> userIds = new LinkedHashSet<Long>();
        for (FieldMappingRuleEntity entity : entities) {
            if (entity != null && entity.getCreatedBy() != null) {
                userIds.add(entity.getCreatedBy());
            }
        }
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> result = new LinkedHashMap<Long, String>();
        for (StudioUserEntity user : studioUserMapper.selectBatchIds(userIds)) {
            result.put(user.getId(), hasText(user.getDisplayName()) ? user.getDisplayName() : user.getUsername());
        }
        return result;
    }

    private String resolveCreatorName(Long userId) {
        if (userId == null) {
            return null;
        }
        StudioUserEntity user = studioUserMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        return hasText(user.getDisplayName()) ? user.getDisplayName() : user.getUsername();
    }

    private void requireSuperAdmin() {
        if (!securityService.currentRoleCodes().stream().anyMatch(role -> StudioConstants.ROLE_SUPER_ADMIN.equalsIgnoreCase(role))) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Operation is not allowed in the current context");
        }
    }

    private int normalizePageNo(Integer pageNo) {
        return pageNo == null || pageNo.intValue() <= 0 ? 1 : pageNo.intValue();
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize.intValue() <= 0) {
            return 20;
        }
        return Math.min(pageSize.intValue(), 200);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String normalizeText(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeNullableText(String value) {
        String normalized = normalizeText(value);
        return hasText(normalized) ? normalized : null;
    }
}
