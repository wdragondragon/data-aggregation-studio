package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.QualityRuleGranularity;
import com.jdragon.studio.dto.enums.QualityRuleOutputType;
import com.jdragon.studio.dto.enums.QualityRuleParamType;
import com.jdragon.studio.dto.enums.QualityRuleScopeType;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.QualityRuleInputParamView;
import com.jdragon.studio.dto.model.QualityRuleListView;
import com.jdragon.studio.dto.model.QualityRuleOptionView;
import com.jdragon.studio.dto.model.QualityRuleOutputParamView;
import com.jdragon.studio.dto.model.QualityRuleParseResultView;
import com.jdragon.studio.dto.model.QualityRuleValidationResultView;
import com.jdragon.studio.dto.model.QualityRuleView;
import com.jdragon.studio.dto.model.request.QualityRuleBatchDeleteRequest;
import com.jdragon.studio.dto.model.request.QualityRuleInputParamSaveRequest;
import com.jdragon.studio.dto.model.request.QualityRuleOutputParamSaveRequest;
import com.jdragon.studio.dto.model.request.QualityRuleParseRequest;
import com.jdragon.studio.dto.model.request.QualityRuleSaveRequest;
import com.jdragon.studio.dto.model.request.QualityRuleValidateRequest;
import com.jdragon.studio.infra.entity.QualityRuleEntity;
import com.jdragon.studio.infra.entity.QualityRuleInputParamEntity;
import com.jdragon.studio.infra.entity.QualityRuleOutputParamEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.mapper.QualityRuleInputParamMapper;
import com.jdragon.studio.infra.mapper.QualityRuleMapper;
import com.jdragon.studio.infra.mapper.QualityRuleOutputParamMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class QualityRuleService {

    private final QualityRuleMapper qualityRuleMapper;
    private final QualityRuleInputParamMapper qualityRuleInputParamMapper;
    private final QualityRuleOutputParamMapper qualityRuleOutputParamMapper;
    private final StudioUserMapper studioUserMapper;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final QualitySqlTemplateService qualitySqlTemplateService;

    public QualityRuleService(QualityRuleMapper qualityRuleMapper,
                              QualityRuleInputParamMapper qualityRuleInputParamMapper,
                              QualityRuleOutputParamMapper qualityRuleOutputParamMapper,
                              StudioUserMapper studioUserMapper,
                              StudioSecurityService securityService,
                              ProjectResourceAccessService projectResourceAccessService,
                              QualitySqlTemplateService qualitySqlTemplateService) {
        this.qualityRuleMapper = qualityRuleMapper;
        this.qualityRuleInputParamMapper = qualityRuleInputParamMapper;
        this.qualityRuleOutputParamMapper = qualityRuleOutputParamMapper;
        this.studioUserMapper = studioUserMapper;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.qualitySqlTemplateService = qualitySqlTemplateService;
    }

    public PageView<QualityRuleListView> list(Integer pageNo,
                                              Integer pageSize,
                                              String keyword,
                                              String ruleDimension,
                                              String scopeType,
                                              Boolean enabled) {
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        String normalizedKeyword = normalizeText(keyword);
        String normalizedDimension = normalizeText(ruleDimension);
        String normalizedScopeType = normalizeText(scopeType);
        if (QualityRuleScopeType.PROJECT.name().equalsIgnoreCase(normalizedScopeType)
                && projectResourceAccessService.currentProjectId() == null) {
            return PageView.of(safePageNo, safePageSize, 0L, new ArrayList<QualityRuleListView>());
        }
        Page<QualityRuleEntity> page = new Page<QualityRuleEntity>(safePageNo, safePageSize);
        LambdaQueryWrapper<QualityRuleEntity> queryWrapper = selectRuleListColumns(buildAccessibleQuery(normalizedScopeType))
                .and(hasText(normalizedKeyword), wrapper -> wrapper.like(QualityRuleEntity::getRuleName, normalizedKeyword)
                        .or()
                        .like(QualityRuleEntity::getRuleCode, normalizedKeyword)
                        .or()
                        .like(QualityRuleEntity::getDescription, normalizedKeyword))
                .eq(hasText(normalizedDimension), QualityRuleEntity::getRuleDimension,
                        normalizedDimension == null ? null : normalizedDimension.toUpperCase(Locale.ROOT))
                .eq(enabled != null, QualityRuleEntity::getEnabled, Boolean.TRUE.equals(enabled) ? Integer.valueOf(1) : Integer.valueOf(0))
                .orderByDesc(QualityRuleEntity::getUpdatedAt)
                .orderByDesc(QualityRuleEntity::getId);
        Page<QualityRuleEntity> entityPage = qualityRuleMapper.selectPage(page, queryWrapper);
        Map<Long, String> creatorNames = resolveCreatorNames(entityPage.getRecords());
        List<QualityRuleListView> items = new ArrayList<QualityRuleListView>();
        for (QualityRuleEntity entity : entityPage.getRecords()) {
            items.add(toListView(entity, creatorNames.get(entity.getCreatedBy())));
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), items);
    }

    public QualityRuleView get(Long id) {
        QualityRuleEntity entity = requireAccessibleEntity(id);
        return toView(entity, resolveCreatorName(entity.getCreatedBy()), true);
    }

    public QualityRuleView requireAccessibleRule(Long id) {
        return get(id);
    }

    public QualityRuleView requireEnabledRule(Long id) {
        QualityRuleView view = get(id);
        if (!Boolean.TRUE.equals(view.getEnabled())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Quality rule is disabled");
        }
        return view;
    }

    @Transactional
    public QualityRuleView save(QualityRuleSaveRequest request) {
        validateSaveRequest(request);
        QualityRuleValidationResultView validation = qualitySqlTemplateService.validateRule(request.getGranularity(), request.getLogicSql());
        if (!Boolean.TRUE.equals(validation.getValid())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, validation.getMessage());
        }

        QualityRuleEntity entity = request.getId() == null
                ? new QualityRuleEntity()
                : requireWritableEntity(request.getId());
        ensureUniqueRuleCode(normalizeText(request.getRuleCode()), entity.getId());

        entity.setRuleName(normalizeText(request.getRuleName()));
        entity.setRuleCode(normalizeText(request.getRuleCode()));
        entity.setScopeType(request.getScopeType().name());
        entity.setRuleDimension(request.getRuleDimension().name());
        entity.setDescription(normalizeNullableText(request.getDescription()));
        entity.setSupportedDatasourceTypesJson(normalizeSupportedDatasourceTypes(request.getSupportedDatasourceTypes()));
        entity.setGranularity(request.getGranularity().name());
        entity.setLogicSql(normalizeText(request.getLogicSql()));
        entity.setEnabled(Boolean.FALSE.equals(request.getEnabled()) ? Integer.valueOf(0) : Integer.valueOf(1));
        if (entity.getId() == null) {
            entity.setCreatedBy(securityService.currentUserId());
        }

        if (request.getScopeType() == QualityRuleScopeType.SYSTEM) {
            requireSuperAdmin();
            entity.setProjectId(null);
        } else {
            requireProjectRulePermission();
            entity.setProjectId(projectResourceAccessService.requireCurrentProjectId());
        }

        if (entity.getId() == null) {
            qualityRuleMapper.insert(entity);
        } else {
            qualityRuleMapper.updateById(entity);
            deleteRuleChildren(entity.getId());
        }
        if (request.getScopeType() == QualityRuleScopeType.SYSTEM) {
            clearProjectId(entity.getId());
        }

        saveRuleChildren(entity.getId(), request);
        return get(entity.getId());
    }

    @Transactional
    public void delete(Long id) {
        QualityRuleEntity entity = requireWritableEntity(id);
        deleteRuleChildren(entity.getId());
        qualityRuleMapper.deleteById(id);
    }

    @Transactional
    public void batchDelete(QualityRuleBatchDeleteRequest request) {
        if (request == null || request.getIds() == null) {
            return;
        }
        for (Long id : request.getIds()) {
            if (id != null) {
                delete(id);
            }
        }
    }

    @Transactional
    public QualityRuleView enable(Long id) {
        return updateEnabled(id, true);
    }

    @Transactional
    public QualityRuleListView enableSummary(Long id) {
        updateEnabledStatus(id, true);
        return getListView(id);
    }

    @Transactional
    public QualityRuleView disable(Long id) {
        return updateEnabled(id, false);
    }

    @Transactional
    public QualityRuleListView disableSummary(Long id) {
        updateEnabledStatus(id, false);
        return getListView(id);
    }

    public QualityRuleParseResultView parse(QualityRuleParseRequest request) {
        if (request == null || request.getGranularity() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Rule granularity is required");
        }
        return qualitySqlTemplateService.parseRule(request.getGranularity(), request.getLogicSql());
    }

    public QualityRuleValidationResultView validate(QualityRuleValidateRequest request) {
        if (request == null || request.getGranularity() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Rule granularity is required");
        }
        return qualitySqlTemplateService.validateRule(request.getGranularity(), request.getLogicSql());
    }

    public List<QualityRuleView> options(String ruleDimension,
                                         String granularity,
                                         String datasourceType,
                                         Boolean enabledOnly) {
        String normalizedDimension = normalizeText(ruleDimension);
        String normalizedGranularity = normalizeText(granularity);
        String normalizedDatasourceType = normalizeNullableText(datasourceType);
        List<QualityRuleEntity> entities = qualityRuleMapper.selectList(buildAccessibleQuery(null)
                .eq(hasText(normalizedDimension), QualityRuleEntity::getRuleDimension,
                        normalizedDimension == null ? null : normalizedDimension.toUpperCase(Locale.ROOT))
                .eq(hasText(normalizedGranularity), QualityRuleEntity::getGranularity,
                        normalizedGranularity == null ? null : normalizedGranularity.toUpperCase(Locale.ROOT))
                .eq(Boolean.TRUE.equals(enabledOnly), QualityRuleEntity::getEnabled, Integer.valueOf(1))
                .orderByAsc(QualityRuleEntity::getScopeType)
                .orderByAsc(QualityRuleEntity::getRuleName)
                .orderByAsc(QualityRuleEntity::getId));
        Map<Long, String> creatorNames = resolveCreatorNames(entities);
        List<QualityRuleView> result = new ArrayList<QualityRuleView>();
        for (QualityRuleEntity entity : entities) {
            if (hasText(normalizedDatasourceType)
                    && !matchesDatasourceType(entity.getSupportedDatasourceTypesJson(), normalizedDatasourceType)) {
                continue;
            }
            result.add(toView(entity, creatorNames.get(entity.getCreatedBy()), true));
        }
        return result;
    }

    public List<QualityRuleOptionView> optionSummaries(String ruleDimension,
                                                       String granularity,
                                                       String datasourceType,
                                                       Boolean enabledOnly) {
        String normalizedDimension = normalizeText(ruleDimension);
        String normalizedGranularity = normalizeText(granularity);
        String normalizedDatasourceType = normalizeNullableText(datasourceType);
        List<QualityRuleEntity> entities = qualityRuleMapper.selectList(selectRuleOptionColumns(buildAccessibleQuery(null))
                .eq(hasText(normalizedDimension), QualityRuleEntity::getRuleDimension,
                        normalizedDimension == null ? null : normalizedDimension.toUpperCase(Locale.ROOT))
                .eq(hasText(normalizedGranularity), QualityRuleEntity::getGranularity,
                        normalizedGranularity == null ? null : normalizedGranularity.toUpperCase(Locale.ROOT))
                .eq(Boolean.TRUE.equals(enabledOnly), QualityRuleEntity::getEnabled, Integer.valueOf(1))
                .orderByAsc(QualityRuleEntity::getScopeType)
                .orderByAsc(QualityRuleEntity::getRuleName)
                .orderByAsc(QualityRuleEntity::getId));
        List<QualityRuleOptionView> result = new ArrayList<QualityRuleOptionView>();
        for (QualityRuleEntity entity : entities) {
            if (hasText(normalizedDatasourceType)
                    && !matchesDatasourceType(entity.getSupportedDatasourceTypesJson(), normalizedDatasourceType)) {
                continue;
            }
            result.add(toOptionView(entity));
        }
        return result;
    }

    private QualityRuleView updateEnabled(Long id, boolean enabled) {
        updateEnabledStatus(id, enabled);
        return get(id);
    }

    private void updateEnabledStatus(Long id, boolean enabled) {
        QualityRuleEntity entity = requireWritableReference(id);
        qualityRuleMapper.update(null, new LambdaUpdateWrapper<QualityRuleEntity>()
                .set(QualityRuleEntity::getEnabled, enabled ? Integer.valueOf(1) : Integer.valueOf(0))
                .set(QualityRuleEntity::getUpdatedAt, LocalDateTime.now())
                .eq(QualityRuleEntity::getId, entity.getId()));
    }

    private QualityRuleListView getListView(Long id) {
        QualityRuleEntity entity = qualityRuleMapper.selectOne(selectRuleListColumns(new LambdaQueryWrapper<QualityRuleEntity>())
                .eq(QualityRuleEntity::getTenantId, securityService.currentTenantId())
                .eq(QualityRuleEntity::getId, id)
                .last("limit 1"));
        if (entity == null || !canRead(entity)) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Quality rule not found: " + id);
        }
        return toListView(entity, resolveCreatorName(entity.getCreatedBy()));
    }

    private void saveRuleChildren(Long ruleId, QualityRuleSaveRequest request) {
        QualityRuleParseResultView parsed = qualitySqlTemplateService.parseRule(request.getGranularity(), request.getLogicSql());
        saveInputParams(ruleId, parsed.getInputParams(), request.getInputParams());
        saveOutputParams(ruleId, parsed.getOutputParams(), request.getOutputParams());
    }

    private void saveInputParams(Long ruleId,
                                 List<QualityRuleInputParamView> parsedParams,
                                 List<QualityRuleInputParamSaveRequest> requestParams) {
        Map<String, QualityRuleInputParamSaveRequest> requestParamMap = new LinkedHashMap<String, QualityRuleInputParamSaveRequest>();
        if (requestParams != null) {
            for (QualityRuleInputParamSaveRequest requestParam : requestParams) {
                if (requestParam == null || !hasText(requestParam.getParamName())) {
                    continue;
                }
                requestParamMap.put(normalizeText(requestParam.getParamName()), requestParam);
            }
        }
        Set<String> parsedNames = new LinkedHashSet<String>();
        for (QualityRuleInputParamView parsedParam : parsedParams) {
            parsedNames.add(parsedParam.getParamName());
            QualityRuleInputParamSaveRequest requestParam = requestParamMap.get(parsedParam.getParamName());
            QualityRuleInputParamEntity entity = new QualityRuleInputParamEntity();
            entity.setRuleId(ruleId);
            entity.setParamOrder(parsedParam.getParamOrder());
            entity.setParamName(parsedParam.getParamName());
            entity.setParamType(parsedParam.getParamType() == null ? QualityRuleParamType.CUSTOM.name() : parsedParam.getParamType().name());
            entity.setParamMeaning(normalizeNullableText(requestParam == null ? parsedParam.getParamMeaning() : requestParam.getParamMeaning()));
            qualityRuleInputParamMapper.insert(entity);
        }
        for (String requestName : requestParamMap.keySet()) {
            if (!parsedNames.contains(requestName)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Input parameter " + requestName + " is not referenced in logic SQL");
            }
        }
    }

    private void saveOutputParams(Long ruleId,
                                  List<QualityRuleOutputParamView> parsedParams,
                                  List<QualityRuleOutputParamSaveRequest> requestParams) {
        List<QualityRuleOutputParamSaveRequest> candidates = requestParams == null
                ? new ArrayList<QualityRuleOutputParamSaveRequest>()
                : new ArrayList<QualityRuleOutputParamSaveRequest>(requestParams);
        if (candidates.isEmpty()) {
            for (QualityRuleOutputParamView parsed : parsedParams) {
                QualityRuleOutputParamSaveRequest item = new QualityRuleOutputParamSaveRequest();
                item.setOutputOrder(parsed.getOutputOrder());
                item.setResultField(parsed.getResultField());
                item.setOutputType(parsed.getOutputType());
                item.setOutputDescription(parsed.getOutputDescription());
                candidates.add(item);
            }
        }
        Set<Integer> orders = new LinkedHashSet<Integer>();
        Set<String> names = new LinkedHashSet<String>();
        int orderIndex = 1;
        for (QualityRuleOutputParamSaveRequest candidate : candidates) {
            if (candidate == null || !hasText(candidate.getResultField())) {
                continue;
            }
            Integer outputOrder = candidate.getOutputOrder() == null ? Integer.valueOf(orderIndex) : candidate.getOutputOrder();
            if (!orders.add(outputOrder)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Output parameter order must be unique");
            }
            String normalizedField = normalizeText(candidate.getResultField());
            if (!names.add(normalizedField.toLowerCase(Locale.ROOT))) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Output parameter name must be unique");
            }
            QualityRuleOutputParamEntity entity = new QualityRuleOutputParamEntity();
            entity.setRuleId(ruleId);
            entity.setOutputOrder(outputOrder);
            entity.setResultField(normalizedField);
            entity.setOutputType(candidate.getOutputType() == null ? QualityRuleOutputType.STRING.name() : candidate.getOutputType().name());
            entity.setOutputDescription(normalizeNullableText(candidate.getOutputDescription()));
            qualityRuleOutputParamMapper.insert(entity);
            orderIndex++;
        }
    }

    private void deleteRuleChildren(Long ruleId) {
        qualityRuleInputParamMapper.delete(new LambdaQueryWrapper<QualityRuleInputParamEntity>()
                .eq(QualityRuleInputParamEntity::getRuleId, ruleId));
        qualityRuleOutputParamMapper.delete(new LambdaQueryWrapper<QualityRuleOutputParamEntity>()
                .eq(QualityRuleOutputParamEntity::getRuleId, ruleId));
    }

    private void clearProjectId(Long ruleId) {
        qualityRuleMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<QualityRuleEntity>()
                .eq(QualityRuleEntity::getId, ruleId)
                .set(QualityRuleEntity::getProjectId, null));
    }

    private QualityRuleView toView(QualityRuleEntity entity, String createdByName, boolean includeParams) {
        QualityRuleView view = new QualityRuleView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setRuleName(entity.getRuleName());
        view.setRuleCode(entity.getRuleCode());
        view.setScopeType(entity.getScopeType() == null ? null : QualityRuleScopeType.valueOf(entity.getScopeType()));
        view.setRuleDimension(entity.getRuleDimension() == null ? null : com.jdragon.studio.dto.enums.QualityRuleDimension.valueOf(entity.getRuleDimension()));
        view.setDescription(entity.getDescription());
        view.setSupportedDatasourceTypes(entity.getSupportedDatasourceTypesJson() == null
                ? new ArrayList<String>()
                : new ArrayList<String>(entity.getSupportedDatasourceTypesJson()));
        view.setGranularity(entity.getGranularity() == null ? null : QualityRuleGranularity.valueOf(entity.getGranularity()));
        view.setLogicSql(entity.getLogicSql());
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled().intValue() == 1);
        view.setCreatedBy(entity.getCreatedBy());
        view.setCreatedByName(createdByName);
        view.setEditable(canManage(entity));
        view.setDeletable(canManage(entity));
        if (includeParams) {
            view.setInputParams(loadInputParams(entity.getId()));
            view.setOutputParams(loadOutputParams(entity.getId()));
        }
        return view;
    }

    private QualityRuleListView toListView(QualityRuleEntity entity, String createdByName) {
        QualityRuleListView view = new QualityRuleListView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setRuleName(entity.getRuleName());
        view.setRuleCode(entity.getRuleCode());
        view.setScopeType(entity.getScopeType() == null ? null : QualityRuleScopeType.valueOf(entity.getScopeType()));
        view.setRuleDimension(entity.getRuleDimension() == null ? null : com.jdragon.studio.dto.enums.QualityRuleDimension.valueOf(entity.getRuleDimension()));
        view.setGranularity(entity.getGranularity() == null ? null : QualityRuleGranularity.valueOf(entity.getGranularity()));
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled().intValue() == 1);
        view.setCreatedBy(entity.getCreatedBy());
        view.setCreatedByName(createdByName);
        view.setEditable(canManage(entity));
        view.setDeletable(canManage(entity));
        return view;
    }

    private QualityRuleOptionView toOptionView(QualityRuleEntity entity) {
        QualityRuleOptionView view = new QualityRuleOptionView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setRuleName(entity.getRuleName());
        view.setRuleCode(entity.getRuleCode());
        view.setScopeType(entity.getScopeType() == null ? null : QualityRuleScopeType.valueOf(entity.getScopeType()));
        view.setRuleDimension(entity.getRuleDimension() == null ? null : com.jdragon.studio.dto.enums.QualityRuleDimension.valueOf(entity.getRuleDimension()));
        view.setSupportedDatasourceTypes(entity.getSupportedDatasourceTypesJson() == null
                ? new ArrayList<String>()
                : new ArrayList<String>(entity.getSupportedDatasourceTypesJson()));
        view.setGranularity(entity.getGranularity() == null ? null : QualityRuleGranularity.valueOf(entity.getGranularity()));
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled().intValue() == 1);
        return view;
    }

    private LambdaQueryWrapper<QualityRuleEntity> selectRuleListColumns(LambdaQueryWrapper<QualityRuleEntity> queryWrapper) {
        return queryWrapper.select(QualityRuleEntity::getId,
                QualityRuleEntity::getTenantId,
                QualityRuleEntity::getProjectId,
                QualityRuleEntity::getDeleted,
                QualityRuleEntity::getCreatedAt,
                QualityRuleEntity::getUpdatedAt,
                QualityRuleEntity::getCreatedBy,
                QualityRuleEntity::getRuleName,
                QualityRuleEntity::getRuleCode,
                QualityRuleEntity::getScopeType,
                QualityRuleEntity::getRuleDimension,
                QualityRuleEntity::getGranularity,
                QualityRuleEntity::getEnabled);
    }

    private LambdaQueryWrapper<QualityRuleEntity> selectRuleOptionColumns(LambdaQueryWrapper<QualityRuleEntity> queryWrapper) {
        return queryWrapper.select(QualityRuleEntity::getId,
                QualityRuleEntity::getTenantId,
                QualityRuleEntity::getProjectId,
                QualityRuleEntity::getDeleted,
                QualityRuleEntity::getRuleName,
                QualityRuleEntity::getRuleCode,
                QualityRuleEntity::getScopeType,
                QualityRuleEntity::getRuleDimension,
                QualityRuleEntity::getSupportedDatasourceTypesJson,
                QualityRuleEntity::getGranularity,
                QualityRuleEntity::getEnabled);
    }

    private List<QualityRuleInputParamView> loadInputParams(Long ruleId) {
        List<QualityRuleInputParamEntity> entities = qualityRuleInputParamMapper.selectList(new LambdaQueryWrapper<QualityRuleInputParamEntity>()
                .eq(QualityRuleInputParamEntity::getRuleId, ruleId)
                .orderByAsc(QualityRuleInputParamEntity::getParamOrder)
                .orderByAsc(QualityRuleInputParamEntity::getId));
        List<QualityRuleInputParamView> result = new ArrayList<QualityRuleInputParamView>();
        for (QualityRuleInputParamEntity entity : entities) {
            QualityRuleInputParamView item = new QualityRuleInputParamView();
            item.setId(entity.getId());
            item.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
            item.setCreatedAt(entity.getCreatedAt());
            item.setUpdatedAt(entity.getUpdatedAt());
            item.setRuleId(entity.getRuleId());
            item.setParamOrder(entity.getParamOrder());
            item.setParamName(entity.getParamName());
            item.setParamType(entity.getParamType() == null ? null : QualityRuleParamType.valueOf(entity.getParamType()));
            item.setParamMeaning(entity.getParamMeaning());
            result.add(item);
        }
        return result;
    }

    private List<QualityRuleOutputParamView> loadOutputParams(Long ruleId) {
        List<QualityRuleOutputParamEntity> entities = qualityRuleOutputParamMapper.selectList(new LambdaQueryWrapper<QualityRuleOutputParamEntity>()
                .eq(QualityRuleOutputParamEntity::getRuleId, ruleId)
                .orderByAsc(QualityRuleOutputParamEntity::getOutputOrder)
                .orderByAsc(QualityRuleOutputParamEntity::getId));
        List<QualityRuleOutputParamView> result = new ArrayList<QualityRuleOutputParamView>();
        for (QualityRuleOutputParamEntity entity : entities) {
            QualityRuleOutputParamView item = new QualityRuleOutputParamView();
            item.setId(entity.getId());
            item.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
            item.setCreatedAt(entity.getCreatedAt());
            item.setUpdatedAt(entity.getUpdatedAt());
            item.setRuleId(entity.getRuleId());
            item.setOutputOrder(entity.getOutputOrder());
            item.setResultField(entity.getResultField());
            item.setOutputType(entity.getOutputType() == null ? null : QualityRuleOutputType.valueOf(entity.getOutputType()));
            item.setOutputDescription(entity.getOutputDescription());
            result.add(item);
        }
        return result;
    }

    private void validateSaveRequest(QualityRuleSaveRequest request) {
        if (request == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Quality rule request is required");
        }
        if (!hasText(request.getRuleName())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Rule name is required");
        }
        if (!hasText(request.getRuleCode())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Rule code is required");
        }
        if (request.getScopeType() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Scope type is required");
        }
        if (request.getGranularity() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Rule granularity is required");
        }
        if (request.getRuleDimension() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Rule dimension is required");
        }
        if (!hasText(request.getLogicSql())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Logic SQL is required");
        }
        normalizeSupportedDatasourceTypes(request.getSupportedDatasourceTypes());
    }

    private List<String> normalizeSupportedDatasourceTypes(List<String> datasourceTypes) {
        Set<String> supportedTypes = qualitySqlTemplateService.supportedDatasourceTypes();
        List<String> result = new ArrayList<String>();
        if (datasourceTypes == null) {
            return result;
        }
        for (String datasourceType : datasourceTypes) {
            String normalized = normalizeNullableText(datasourceType);
            if (!hasText(normalized)) {
                continue;
            }
            normalized = normalized.toLowerCase(Locale.ROOT);
            if (!supportedTypes.contains(normalized)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported datasource type: " + datasourceType);
            }
            if (!result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private boolean matchesDatasourceType(List<String> supportedDatasourceTypes, String datasourceType) {
        if (!hasText(datasourceType)) {
            return true;
        }
        if (supportedDatasourceTypes == null || supportedDatasourceTypes.isEmpty()) {
            return true;
        }
        String normalizedType = datasourceType.toLowerCase(Locale.ROOT);
        for (String supportedDatasourceType : supportedDatasourceTypes) {
            if (hasText(supportedDatasourceType)
                    && normalizedType.equalsIgnoreCase(supportedDatasourceType.trim())) {
                return true;
            }
        }
        return false;
    }

    private QualityRuleEntity requireAccessibleEntity(Long id) {
        if (id == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Rule id is required");
        }
        QualityRuleEntity entity = qualityRuleMapper.selectById(id);
        if (entity == null || !matchesTenant(entity.getTenantId()) || !canRead(entity)) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Quality rule not found: " + id);
        }
        return entity;
    }

    private QualityRuleEntity requireWritableEntity(Long id) {
        QualityRuleEntity entity = requireAccessibleEntity(id);
        if (!canManage(entity)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Operation is not allowed in the current context");
        }
        return entity;
    }

    private QualityRuleEntity requireWritableReference(Long id) {
        if (id == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Rule id is required");
        }
        QualityRuleEntity entity = qualityRuleMapper.selectOne(new LambdaQueryWrapper<QualityRuleEntity>()
                .select(QualityRuleEntity::getId,
                        QualityRuleEntity::getTenantId,
                        QualityRuleEntity::getProjectId,
                        QualityRuleEntity::getScopeType)
                .eq(QualityRuleEntity::getTenantId, securityService.currentTenantId())
                .eq(QualityRuleEntity::getId, id)
                .last("limit 1"));
        if (entity == null || !canRead(entity)) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Quality rule not found: " + id);
        }
        if (!canManage(entity)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Operation is not allowed in the current context");
        }
        return entity;
    }

    private LambdaQueryWrapper<QualityRuleEntity> buildAccessibleQuery(String scopeType) {
        LambdaQueryWrapper<QualityRuleEntity> queryWrapper = new LambdaQueryWrapper<QualityRuleEntity>()
                .eq(QualityRuleEntity::getTenantId, securityService.currentTenantId());
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (QualityRuleScopeType.SYSTEM.name().equalsIgnoreCase(scopeType)) {
            queryWrapper.eq(QualityRuleEntity::getScopeType, QualityRuleScopeType.SYSTEM.name());
            return queryWrapper;
        }
        if (QualityRuleScopeType.PROJECT.name().equalsIgnoreCase(scopeType)) {
            if (currentProjectId == null) {
                queryWrapper.eq(QualityRuleEntity::getId, Long.valueOf(-1L));
                return queryWrapper;
            }
            queryWrapper.eq(QualityRuleEntity::getScopeType, QualityRuleScopeType.PROJECT.name())
                    .eq(QualityRuleEntity::getProjectId, currentProjectId);
            return queryWrapper;
        }
        if (currentProjectId == null) {
            queryWrapper.eq(QualityRuleEntity::getScopeType, QualityRuleScopeType.SYSTEM.name());
            return queryWrapper;
        }
        queryWrapper.and(wrapper -> wrapper.eq(QualityRuleEntity::getScopeType, QualityRuleScopeType.SYSTEM.name())
                .or(projectWrapper -> projectWrapper.eq(QualityRuleEntity::getScopeType, QualityRuleScopeType.PROJECT.name())
                        .eq(QualityRuleEntity::getProjectId, currentProjectId)));
        return queryWrapper;
    }

    private boolean canRead(QualityRuleEntity entity) {
        if (entity == null) {
            return false;
        }
        if (QualityRuleScopeType.SYSTEM.name().equalsIgnoreCase(entity.getScopeType())) {
            return true;
        }
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        return currentProjectId != null
                && entity.getProjectId() != null
                && currentProjectId.longValue() == entity.getProjectId().longValue();
    }

    private boolean canManage(QualityRuleEntity entity) {
        if (entity == null) {
            return false;
        }
        if (QualityRuleScopeType.SYSTEM.name().equalsIgnoreCase(entity.getScopeType())) {
            return securityService.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN);
        }
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null || entity.getProjectId() == null || currentProjectId.longValue() != entity.getProjectId().longValue()) {
            return false;
        }
        return securityService.hasAnyRole(
                StudioConstants.ROLE_SUPER_ADMIN,
                StudioConstants.ROLE_TENANT_ADMIN,
                StudioConstants.ROLE_ADMIN,
                StudioConstants.ROLE_PROJECT_ADMIN);
    }

    private boolean matchesTenant(String tenantId) {
        return tenantId != null && tenantId.equals(securityService.currentTenantId());
    }

    private void requireSuperAdmin() {
        if (!securityService.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Operation is not allowed in the current context");
        }
    }

    private void requireProjectRulePermission() {
        if (projectResourceAccessService.currentProjectId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Project context is required");
        }
        if (!securityService.hasAnyRole(
                StudioConstants.ROLE_SUPER_ADMIN,
                StudioConstants.ROLE_TENANT_ADMIN,
                StudioConstants.ROLE_ADMIN,
                StudioConstants.ROLE_PROJECT_ADMIN)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Operation is not allowed in the current context");
        }
    }

    private void ensureUniqueRuleCode(String ruleCode, Long selfId) {
        QualityRuleEntity duplicate = qualityRuleMapper.selectOne(new LambdaQueryWrapper<QualityRuleEntity>()
                .eq(QualityRuleEntity::getTenantId, securityService.currentTenantId())
                .eq(QualityRuleEntity::getRuleCode, ruleCode)
                .last("limit 1"));
        if (duplicate == null) {
            return;
        }
        if (selfId != null && selfId.equals(duplicate.getId())) {
            return;
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "Rule code already exists");
    }

    private Map<Long, String> resolveCreatorNames(List<QualityRuleEntity> entities) {
        Set<Long> userIds = new LinkedHashSet<Long>();
        for (QualityRuleEntity entity : entities) {
            if (entity != null && entity.getCreatedBy() != null) {
                userIds.add(entity.getCreatedBy());
            }
        }
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> result = new LinkedHashMap<Long, String>();
        for (StudioUserEntity user : studioUserMapper.selectByIds(userIds)) {
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
