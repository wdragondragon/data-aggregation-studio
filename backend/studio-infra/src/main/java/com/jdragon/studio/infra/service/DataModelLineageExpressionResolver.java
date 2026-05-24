package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.CollectionTaskSourceBinding;
import com.jdragon.studio.dto.model.FieldMappingDefinition;
import com.jdragon.studio.infra.entity.DataModelEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DataModelLineageExpressionResolver {

    private static final Pattern QUALIFIED_FIELD_PATTERN = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("(?<![A-Za-z0-9_\\.])([A-Za-z_][A-Za-z0-9_]*)(?![A-Za-z0-9_])");
    private static final Set<String> EXPRESSION_KEYWORDS = new LinkedHashSet<String>();

    static {
        Collections.addAll(EXPRESSION_KEYWORDS,
                "case", "when", "then", "else", "end", "null", "and", "or", "not", "as",
                "if", "cast", "concat", "sum", "max", "min", "avg", "count", "coalesce",
                "substr", "substring", "trim", "replace", "lower", "upper", "round", "floor",
                "ceil", "ceiling", "abs", "year", "month", "day", "hour", "minute", "second",
                "date", "now", "current_date", "current_timestamp", "true", "false", "distinct");
    }

    Map<String, Set<String>> buildAliasFields(List<CollectionTaskSourceBinding> sourceBindings,
                                              Map<Long, DataModelEntity> modelMap) {
        Map<String, Set<String>> result = new LinkedHashMap<String, Set<String>>();
        for (CollectionTaskSourceBinding binding : sourceBindings) {
            String alias = sourceAlias(binding);
            result.put(alias, DataModelLineageTextSupport.extractModelFields(modelMap.get(binding.getModelId())));
        }
        return result;
    }

    ExpressionResolution resolveExpressionMappings(FieldMappingDefinition mapping,
                                                   List<CollectionTaskSourceBinding> sourceBindings,
                                                   Map<String, Set<String>> aliasFields) {
        ExpressionResolution resolution = new ExpressionResolution();
        if (mapping == null || DataModelLineageTextSupport.isBlank(mapping.getExpression())) {
            return resolution;
        }
        Set<String> seen = new LinkedHashSet<String>();
        Matcher qualifiedMatcher = QUALIFIED_FIELD_PATTERN.matcher(mapping.getExpression());
        while (qualifiedMatcher.find()) {
            String alias = qualifiedMatcher.group(1);
            String field = qualifiedMatcher.group(2);
            Set<String> fields = aliasFields.get(alias);
            if (fields != null
                    && DataModelLineageTextSupport.containsIgnoreCase(fields, field)
                    && seen.add(alias + "." + field.toLowerCase(Locale.ENGLISH))) {
                resolution.references.add(new FieldReference(alias, DataModelLineageTextSupport.findOriginalField(fields, field)));
            }
        }
        if (!resolution.references.isEmpty()) {
            return resolution;
        }
        Matcher identifierMatcher = IDENTIFIER_PATTERN.matcher(mapping.getExpression());
        while (identifierMatcher.find()) {
            String identifier = identifierMatcher.group(1);
            if (EXPRESSION_KEYWORDS.contains(identifier.toLowerCase(Locale.ENGLISH))) {
                continue;
            }
            FieldReference uniqueMatch = resolveUniqueBareField(identifier, sourceBindings, aliasFields);
            if (uniqueMatch != null && seen.add(uniqueMatch.sourceAlias + "." + uniqueMatch.fieldName.toLowerCase(Locale.ENGLISH))) {
                resolution.references.add(uniqueMatch);
            }
        }
        return resolution;
    }

    CollectionTaskSourceBinding findSourceBinding(List<CollectionTaskSourceBinding> sourceBindings, String sourceAlias) {
        if (sourceBindings == null || sourceBindings.isEmpty()) {
            return null;
        }
        if (DataModelLineageTextSupport.isBlank(sourceAlias)) {
            return sourceBindings.get(0);
        }
        for (CollectionTaskSourceBinding binding : sourceBindings) {
            if (sourceAlias.equalsIgnoreCase(sourceAlias(binding))) {
                return binding;
            }
        }
        return sourceBindings.get(0);
    }

    private FieldReference resolveUniqueBareField(String identifier,
                                                  List<CollectionTaskSourceBinding> sourceBindings,
                                                  Map<String, Set<String>> aliasFields) {
        FieldReference result = null;
        for (CollectionTaskSourceBinding binding : sourceBindings) {
            String alias = sourceAlias(binding);
            Set<String> fields = aliasFields.get(alias);
            if (fields == null || !DataModelLineageTextSupport.containsIgnoreCase(fields, identifier)) {
                continue;
            }
            if (result != null) {
                return null;
            }
            result = new FieldReference(alias, DataModelLineageTextSupport.findOriginalField(fields, identifier));
        }
        return result;
    }

    private String sourceAlias(CollectionTaskSourceBinding binding) {
        return DataModelLineageTextSupport.firstNonBlank(binding.getSourceAlias(), binding.getDatasourceName(), String.valueOf(binding.getModelId()));
    }

    static final class FieldReference {
        final String sourceAlias;
        final String fieldName;

        FieldReference(String sourceAlias, String fieldName) {
            this.sourceAlias = sourceAlias;
            this.fieldName = fieldName;
        }
    }

    static final class ExpressionResolution {
        final List<FieldReference> references = new ArrayList<FieldReference>();
    }
}
