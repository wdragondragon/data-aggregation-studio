package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.dto.enums.DataServiceParamPosition;
import com.jdragon.studio.dto.enums.DataServiceQueryOperator;
import com.jdragon.studio.dto.enums.DataServiceRequestMethod;
import com.jdragon.studio.dto.enums.DataServiceValueType;
import com.jdragon.studio.dto.model.DataServiceFieldView;
import com.jdragon.studio.dto.model.DataServicePublishParamView;
import com.jdragon.studio.dto.model.DataServiceRequestParamView;
import com.jdragon.studio.dto.model.DataServiceResponseParamView;
import com.jdragon.studio.infra.entity.DataServicePublishParamEntity;
import com.jdragon.studio.infra.entity.DataServiceRequestParamEntity;
import com.jdragon.studio.infra.entity.DataServiceResponseParamEntity;
import com.jdragon.studio.infra.mapper.DataServicePublishParamMapper;
import com.jdragon.studio.infra.mapper.DataServiceRequestParamMapper;
import com.jdragon.studio.infra.mapper.DataServiceResponseParamMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DataServiceParamSupport {

    private final DataServiceRequestParamMapper requestParamMapper;
    private final DataServiceResponseParamMapper responseParamMapper;
    private final DataServicePublishParamMapper publishParamMapper;
    private final DataServiceInvocationSupport invocationSupport;
    private final StudioTransformerSupport transformerSupport;

    DataServiceParamSupport(DataServiceRequestParamMapper requestParamMapper,
                            DataServiceResponseParamMapper responseParamMapper,
                            DataServicePublishParamMapper publishParamMapper,
                            DataServiceInvocationSupport invocationSupport,
                            StudioTransformerSupport transformerSupport) {
        this.requestParamMapper = requestParamMapper;
        this.responseParamMapper = responseParamMapper;
        this.publishParamMapper = publishParamMapper;
        this.invocationSupport = invocationSupport;
        this.transformerSupport = transformerSupport;
    }

    List<DataServiceRequestParamView> defaultRequestParams() {
        List<DataServiceRequestParamView> result = new ArrayList<DataServiceRequestParamView>();
        result.add(fixedPageParam("pageNum", "页码", 1));
        result.add(fixedPageParam("pageSize", "每页条数", 2));
        return result;
    }

    Map<String, String> requestParamAliases(List<DataServiceRequestParamView> input) {
        Map<String, String> aliases = new LinkedHashMap<String, String>();
        if (input == null) {
            return aliases;
        }
        for (DataServiceRequestParamView item : input) {
            if (item == null || invocationSupport.isPageParam(item.getParamName())) {
                continue;
            }
            if (!invocationSupport.hasText(item.getParamName()) || !invocationSupport.hasText(item.getFieldName())) {
                continue;
            }
            String paramName = item.getParamName().trim();
            String fieldName = item.getFieldName().trim();
            if (!paramName.equals(fieldName)) {
                aliases.put(paramName, fieldName);
            }
        }
        return aliases;
    }

    List<DataServiceResponseParamView> defaultResponseParams(List<DataServiceFieldView> fields) {
        List<DataServiceResponseParamView> result = new ArrayList<DataServiceResponseParamView>();
        int order = 1;
        for (DataServiceFieldView field : fields) {
            DataServiceResponseParamView responseParam = new DataServiceResponseParamView();
            responseParam.setSortOrder(Integer.valueOf(order++));
            responseParam.setEnabled(Boolean.TRUE);
            responseParam.setParamName(field.getFieldName());
            responseParam.setFieldName(field.getFieldName());
            responseParam.setExampleValue(field.getExampleValue());
            responseParam.setDescription(field.getDescription());
            result.add(responseParam);
        }
        return result;
    }

    List<DataServiceRequestParamView> normalizeRequestParams(List<DataServiceRequestParamView> input) {
        List<DataServiceRequestParamView> result = new ArrayList<DataServiceRequestParamView>();
        result.add(fixedPageParam("pageNum", "页码", 1));
        result.add(fixedPageParam("pageSize", "每页条数", 2));
        int order = 3;
        if (input != null) {
            for (DataServiceRequestParamView item : input) {
                if (item == null || invocationSupport.isPageParam(item.getParamName())) {
                    continue;
                }
                String paramName = invocationSupport.normalizeRequiredText(item.getParamName(), "Request parameter name is required");
                String fieldName = invocationSupport.hasText(item.getFieldName()) ? item.getFieldName().trim() : paramName;
                DataServiceRequestParamView view = new DataServiceRequestParamView();
                view.setSortOrder(item.getSortOrder() == null ? Integer.valueOf(order) : item.getSortOrder());
                view.setParamName(fieldName);
                view.setFieldName(fieldName);
                view.setValueType(item.getValueType() == null ? DataServiceValueType.STRING : item.getValueType());
                view.setQueryOperator(item.getQueryOperator() == null ? DataServiceQueryOperator.EQ : item.getQueryOperator());
                view.setRequired(Boolean.TRUE.equals(item.getRequired()));
                view.setDescription(invocationSupport.normalizeNullableText(item.getDescription()));
                view.setFixedParam(Boolean.FALSE);
                invocationSupport.validateSimpleIdentifier(view.getParamName(), "Request parameter name is invalid: " + view.getParamName());
                invocationSupport.validateSimpleIdentifier(view.getFieldName(), "Request field is invalid: " + view.getFieldName());
                result.add(view);
                order++;
            }
        }
        return result;
    }

    List<DataServiceResponseParamView> normalizeResponseParams(List<DataServiceResponseParamView> input,
                                                               List<DataServiceResponseParamView> defaults) {
        List<DataServiceResponseParamView> source = input == null || input.isEmpty() ? defaults : input;
        List<DataServiceResponseParamView> result = new ArrayList<DataServiceResponseParamView>();
        int order = 1;
        for (DataServiceResponseParamView item : source) {
            if (item == null) {
                continue;
            }
            String fieldName = invocationSupport.normalizeRequiredText(item.getFieldName(), "Response field is required");
            String paramName = invocationSupport.hasText(item.getParamName()) ? item.getParamName().trim() : fieldName;
            invocationSupport.validateSimpleIdentifier(fieldName, "Response field is invalid: " + fieldName);
            invocationSupport.validateSimpleIdentifier(paramName, "Response param name is invalid: " + paramName);
            DataServiceResponseParamView view = new DataServiceResponseParamView();
            view.setSortOrder(item.getSortOrder() == null ? Integer.valueOf(order) : item.getSortOrder());
            view.setEnabled(!Boolean.FALSE.equals(item.getEnabled()));
            view.setParamName(paramName);
            view.setFieldName(fieldName);
            view.setExampleValue(invocationSupport.normalizeNullableText(item.getExampleValue()));
            view.setDescription(invocationSupport.normalizeNullableText(item.getDescription()));
            view.setTransformers(item.getTransformers() == null ? new ArrayList<>() : item.getTransformers());
            result.add(view);
            order++;
        }
        return result;
    }

    List<DataServicePublishParamView> normalizePublishParams(List<DataServicePublishParamView> input,
                                                              List<DataServiceRequestParamView> requestParams,
                                                              DataServiceRequestMethod requestMethod,
                                                              Map<String, String> requestParamAliases) {
        List<DataServicePublishParamView> result = new ArrayList<DataServicePublishParamView>();
        Map<String, DataServicePublishParamView> existing = new LinkedHashMap<String, DataServicePublishParamView>();
        if (input != null) {
            for (DataServicePublishParamView item : input) {
                if (item != null && invocationSupport.hasText(item.getBackendParamName())) {
                    String backendParamName = item.getBackendParamName().trim();
                    existing.put(backendParamName, item);
                    String canonicalParamName = requestParamAliases == null ? null : requestParamAliases.get(backendParamName);
                    if (invocationSupport.hasText(canonicalParamName)) {
                        existing.putIfAbsent(canonicalParamName, item);
                    }
                }
            }
        }
        int order = 1;
        for (DataServiceRequestParamView requestParam : requestParams) {
            DataServicePublishParamView source = existing.get(requestParam.getParamName());
            DataServicePublishParamView view = new DataServicePublishParamView();
            view.setSortOrder(source == null || source.getSortOrder() == null ? Integer.valueOf(order) : source.getSortOrder());
            view.setFrontendParamName(source != null && invocationSupport.hasText(source.getFrontendParamName())
                    ? source.getFrontendParamName().trim()
                    : requestParam.getParamName());
            view.setBackendParamName(requestParam.getParamName());
            view.setPosition(source == null || source.getPosition() == null
                    ? (requestMethod == DataServiceRequestMethod.POST ? DataServiceParamPosition.BODY : DataServiceParamPosition.QUERY)
                    : source.getPosition());
            view.setValueType(requestParam.getValueType());
            view.setExampleValue(source != null ? invocationSupport.normalizeNullableText(source.getExampleValue()) : invocationSupport.defaultExampleValue(requestParam.getValueType()));
            view.setDefaultValue(source != null ? invocationSupport.normalizeNullableText(source.getDefaultValue()) : invocationSupport.defaultValueFor(requestParam.getParamName()));
            view.setRequired(source == null ? requestParam.getRequired() : Boolean.TRUE.equals(source.getRequired()));
            view.setDescription(source != null && invocationSupport.hasText(source.getDescription())
                    ? source.getDescription().trim()
                    : requestParam.getDescription());
            invocationSupport.validateFrontendParamName(view);
            result.add(view);
            order++;
        }
        return result;
    }

    void saveChildren(Long serviceId,
                      List<DataServiceRequestParamView> requestParams,
                      List<DataServiceResponseParamView> responseParams,
                      List<DataServicePublishParamView> publishParams) {
        for (DataServiceRequestParamView view : requestParams) {
            DataServiceRequestParamEntity entity = new DataServiceRequestParamEntity();
            entity.setServiceId(serviceId);
            entity.setSortOrder(view.getSortOrder());
            entity.setParamName(view.getParamName());
            entity.setFieldName(view.getFieldName());
            entity.setValueType(view.getValueType() == null ? null : view.getValueType().name());
            entity.setQueryOperator(view.getQueryOperator() == null ? null : view.getQueryOperator().name());
            entity.setRequired(Boolean.TRUE.equals(view.getRequired()) ? Integer.valueOf(1) : Integer.valueOf(0));
            entity.setDescription(view.getDescription());
            entity.setFixedParam(Boolean.TRUE.equals(view.getFixedParam()) ? Integer.valueOf(1) : Integer.valueOf(0));
            requestParamMapper.insert(entity);
        }
        for (DataServiceResponseParamView view : responseParams) {
            DataServiceResponseParamEntity entity = new DataServiceResponseParamEntity();
            entity.setServiceId(serviceId);
            entity.setSortOrder(view.getSortOrder());
            entity.setEnabled(Boolean.TRUE.equals(view.getEnabled()) ? Integer.valueOf(1) : Integer.valueOf(0));
            entity.setParamName(view.getParamName());
            entity.setFieldName(view.getFieldName());
            entity.setExampleValue(view.getExampleValue());
            entity.setDescription(view.getDescription());
            entity.setTransformersJson(transformerSupport.toBindingMaps(view.getTransformers()));
            responseParamMapper.insert(entity);
        }
        for (DataServicePublishParamView view : publishParams) {
            DataServicePublishParamEntity entity = new DataServicePublishParamEntity();
            entity.setServiceId(serviceId);
            entity.setSortOrder(view.getSortOrder());
            entity.setFrontendParamName(view.getFrontendParamName());
            entity.setBackendParamName(view.getBackendParamName());
            entity.setPosition(view.getPosition() == null ? null : view.getPosition().name());
            entity.setValueType(view.getValueType() == null ? null : view.getValueType().name());
            entity.setExampleValue(view.getExampleValue());
            entity.setDefaultValue(view.getDefaultValue());
            entity.setRequired(Boolean.TRUE.equals(view.getRequired()) ? Integer.valueOf(1) : Integer.valueOf(0));
            entity.setDescription(view.getDescription());
            publishParamMapper.insert(entity);
        }
    }

    void deleteChildren(Long serviceId) {
        requestParamMapper.delete(new LambdaQueryWrapper<DataServiceRequestParamEntity>()
                .eq(DataServiceRequestParamEntity::getServiceId, serviceId));
        responseParamMapper.delete(new LambdaQueryWrapper<DataServiceResponseParamEntity>()
                .eq(DataServiceResponseParamEntity::getServiceId, serviceId));
        publishParamMapper.delete(new LambdaQueryWrapper<DataServicePublishParamEntity>()
                .eq(DataServicePublishParamEntity::getServiceId, serviceId));
    }

    List<DataServiceRequestParamView> loadRequestParams(Long serviceId) {
        List<DataServiceRequestParamEntity> entities = requestParamMapper.selectList(new LambdaQueryWrapper<DataServiceRequestParamEntity>()
                .eq(DataServiceRequestParamEntity::getServiceId, serviceId)
                .orderByAsc(DataServiceRequestParamEntity::getSortOrder)
                .orderByAsc(DataServiceRequestParamEntity::getId));
        List<DataServiceRequestParamView> result = new ArrayList<DataServiceRequestParamView>();
        for (DataServiceRequestParamEntity entity : entities) {
            DataServiceRequestParamView view = new DataServiceRequestParamView();
            view.setId(entity.getId());
            view.setServiceId(entity.getServiceId());
            view.setSortOrder(entity.getSortOrder());
            view.setParamName(entity.getParamName());
            view.setFieldName(entity.getFieldName());
            view.setValueType(invocationSupport.enumValue(DataServiceValueType.class, entity.getValueType(), DataServiceValueType.STRING));
            view.setQueryOperator(invocationSupport.enumValue(DataServiceQueryOperator.class, entity.getQueryOperator(), DataServiceQueryOperator.EQ));
            view.setRequired(entity.getRequired() != null && entity.getRequired() == 1);
            view.setDescription(entity.getDescription());
            view.setFixedParam(entity.getFixedParam() != null && entity.getFixedParam() == 1);
            result.add(view);
        }
        return result;
    }

    List<DataServiceResponseParamView> loadResponseParams(Long serviceId) {
        List<DataServiceResponseParamEntity> entities = responseParamMapper.selectList(new LambdaQueryWrapper<DataServiceResponseParamEntity>()
                .eq(DataServiceResponseParamEntity::getServiceId, serviceId)
                .orderByAsc(DataServiceResponseParamEntity::getSortOrder)
                .orderByAsc(DataServiceResponseParamEntity::getId));
        List<DataServiceResponseParamView> result = new ArrayList<DataServiceResponseParamView>();
        for (DataServiceResponseParamEntity entity : entities) {
            DataServiceResponseParamView view = new DataServiceResponseParamView();
            view.setId(entity.getId());
            view.setServiceId(entity.getServiceId());
            view.setSortOrder(entity.getSortOrder());
            view.setEnabled(entity.getEnabled() != null && entity.getEnabled() == 1);
            view.setParamName(entity.getParamName());
            view.setFieldName(entity.getFieldName());
            view.setExampleValue(entity.getExampleValue());
            view.setDescription(entity.getDescription());
            view.setTransformers(transformerSupport.toBindings(entity.getTransformersJson()));
            result.add(view);
        }
        return result;
    }

    List<DataServicePublishParamView> loadPublishParams(Long serviceId) {
        List<DataServicePublishParamEntity> entities = publishParamMapper.selectList(new LambdaQueryWrapper<DataServicePublishParamEntity>()
                .eq(DataServicePublishParamEntity::getServiceId, serviceId)
                .orderByAsc(DataServicePublishParamEntity::getSortOrder)
                .orderByAsc(DataServicePublishParamEntity::getId));
        List<DataServicePublishParamView> result = new ArrayList<DataServicePublishParamView>();
        for (DataServicePublishParamEntity entity : entities) {
            DataServicePublishParamView view = new DataServicePublishParamView();
            view.setId(entity.getId());
            view.setServiceId(entity.getServiceId());
            view.setSortOrder(entity.getSortOrder());
            view.setFrontendParamName(entity.getFrontendParamName());
            view.setBackendParamName(entity.getBackendParamName());
            view.setPosition(invocationSupport.enumValue(DataServiceParamPosition.class, entity.getPosition(), DataServiceParamPosition.QUERY));
            view.setValueType(invocationSupport.enumValue(DataServiceValueType.class, entity.getValueType(), DataServiceValueType.STRING));
            view.setExampleValue(entity.getExampleValue());
            view.setDefaultValue(entity.getDefaultValue());
            view.setRequired(entity.getRequired() != null && entity.getRequired() == 1);
            view.setDescription(entity.getDescription());
            result.add(view);
        }
        return result;
    }

    private DataServiceRequestParamView fixedPageParam(String name, String description, int sortOrder) {
        DataServiceRequestParamView view = new DataServiceRequestParamView();
        view.setSortOrder(Integer.valueOf(sortOrder));
        view.setParamName(name);
        view.setFieldName(name);
        view.setValueType(DataServiceValueType.INT);
        view.setQueryOperator(DataServiceQueryOperator.EQ);
        view.setRequired(Boolean.FALSE);
        view.setDescription(description);
        view.setFixedParam(Boolean.TRUE);
        return view;
    }
}
