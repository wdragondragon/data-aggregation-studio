package com.jdragon.studio.flink.connector;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FilePathPushdownFilter implements Serializable {
    private final String field;
    private final String displayName;
    private final String operator;
    private final List<String> values;
    private final String expression;

    public FilePathPushdownFilter(String field, String displayName, String operator, List<String> values, String expression) {
        this.field = field;
        this.displayName = displayName;
        this.operator = operator;
        this.values = values == null ? new ArrayList<String>() : new ArrayList<String>(values);
        this.expression = expression;
    }

    public String getField() {
        return field;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getOperator() {
        return operator;
    }

    public List<String> getValues() {
        return values;
    }

    public String getExpression() {
        return expression;
    }

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("field", field);
        map.put("displayName", displayName);
        map.put("operator", operator);
        map.put("values", new ArrayList<String>(values));
        map.put("expression", expression);
        return map;
    }
}
