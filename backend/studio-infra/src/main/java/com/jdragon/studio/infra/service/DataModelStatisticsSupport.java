package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.request.DataModelQueryCondition;
import com.jdragon.studio.dto.model.request.DataModelQueryGroup;
import com.jdragon.studio.dto.model.request.DataModelQueryRequest;
import com.jdragon.studio.dto.model.request.DataModelStatisticsRequest;
import com.jdragon.studio.infra.entity.DataModelAttrIndexEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

final class DataModelStatisticsSupport {

    DataModelQueryRequest normalizeRequest(DataModelStatisticsRequest request) {
        DataModelQueryRequest normalized = new DataModelQueryRequest();
        normalized.setDatasourceId(request.getDatasourceId());
        normalized.setModelKind(request.getModelKind());
        normalized.setGroups(normalizeQueryGroups(request.getGroups()));
        return normalized;
    }

    boolean hasTargetSchemaGroup(List<DataModelQueryGroup> groups, String targetMetaSchemaCode) {
        if (groups == null || groups.isEmpty() || targetMetaSchemaCode == null || targetMetaSchemaCode.trim().isEmpty()) {
            return false;
        }
        for (DataModelQueryGroup group : groups) {
            if (group != null && targetMetaSchemaCode.equalsIgnoreCase(group.getMetaSchemaCode())) {
                return true;
            }
        }
        return false;
    }

    String resolveBucketValue(DataModelAttrIndexEntity row) {
        if (row == null) {
            return "";
        }
        if (row.getNumberValue() != null) {
            return normalizeNumber(row.getNumberValue());
        }
        if (row.getBoolValue() != null) {
            return row.getBoolValue().intValue() == 0 ? "false" : "true";
        }
        if (row.getKeywordValue() != null && !row.getKeywordValue().trim().isEmpty()) {
            return row.getKeywordValue().trim();
        }
        if (row.getRawValue() != null && !row.getRawValue().trim().isEmpty()) {
            return row.getRawValue().trim();
        }
        return "";
    }

    String normalizeNumber(BigDecimal value) {
        if (value == null) {
            return "";
        }
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) {
            normalized = normalized.setScale(0, RoundingMode.UNNECESSARY);
        }
        return normalized.toPlainString();
    }

    private List<DataModelQueryGroup> normalizeQueryGroups(List<DataModelQueryGroup> groups) {
        List<DataModelQueryGroup> normalized = new ArrayList<DataModelQueryGroup>();
        if (groups == null) {
            return normalized;
        }
        for (DataModelQueryGroup group : groups) {
            if (group == null || group.getMetaSchemaCode() == null || group.getMetaSchemaCode().trim().isEmpty()) {
                continue;
            }
            DataModelQueryGroup copied = new DataModelQueryGroup();
            copied.setScope(group.getScope());
            copied.setMetaSchemaCode(group.getMetaSchemaCode().trim());
            copied.setRowMatchMode(group.getRowMatchMode());
            List<DataModelQueryCondition> conditions = new ArrayList<DataModelQueryCondition>();
            if (group.getConditions() != null) {
                for (DataModelQueryCondition condition : group.getConditions()) {
                    if (condition == null || condition.getFieldKey() == null || condition.getFieldKey().trim().isEmpty()) {
                        continue;
                    }
                    if ((condition.getValue() == null || String.valueOf(condition.getValue()).trim().isEmpty())
                            && (condition.getValues() == null || condition.getValues().isEmpty())) {
                        continue;
                    }
                    DataModelQueryCondition copiedCondition = new DataModelQueryCondition();
                    copiedCondition.setFieldKey(condition.getFieldKey().trim());
                    copiedCondition.setOperator(condition.getOperator());
                    copiedCondition.setValue(condition.getValue());
                    copiedCondition.setValues(condition.getValues());
                    conditions.add(copiedCondition);
                }
            }
            if (!conditions.isEmpty()) {
                copied.setConditions(conditions);
                normalized.add(copied);
            }
        }
        return normalized;
    }
}
