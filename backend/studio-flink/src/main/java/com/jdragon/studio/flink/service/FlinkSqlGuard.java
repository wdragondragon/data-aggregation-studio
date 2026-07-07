package com.jdragon.studio.flink.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Component
public class FlinkSqlGuard {
    private static final Set<String> BLOCKED_KEYWORDS = new HashSet<String>(Arrays.asList(
            "insert", "update", "delete", "merge", "create", "alter", "drop", "truncate", "call", "execute"
    ));

    public String guardSelectSql(String sql, int maxRows) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Flink SQL is empty");
        }
        String cleaned = stripTrailingSemicolons(sql.trim());
        String keyword = firstKeyword(cleaned);
        if (!"select".equals(keyword) && !"with".equals(keyword)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Only SELECT or WITH SELECT Flink SQL is allowed");
        }
        if (containsBlockedKeyword(cleaned)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Flink SQL contains a forbidden mutating keyword");
        }
        if (maxRows > 0 && !containsLimit(cleaned)) {
            return cleaned + " LIMIT " + maxRows;
        }
        return cleaned;
    }

    private boolean containsBlockedKeyword(String sql) {
        String normalized = sql.toLowerCase(Locale.ENGLISH).replaceAll("[^a-zA-Z_]+", " ");
        for (String keyword : BLOCKED_KEYWORDS) {
            if (normalized.contains(" " + keyword + " ") || normalized.startsWith(keyword + " ")) {
                return true;
            }
        }
        return false;
    }

    private boolean containsLimit(String sql) {
        return sql.toLowerCase(Locale.ENGLISH).matches("(?s).*\\blimit\\s+\\d+.*");
    }

    private String firstKeyword(String sql) {
        String withoutComments = sql.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)--.*?$", " ")
                .trim();
        if (withoutComments.isEmpty()) {
            return "";
        }
        int end = 0;
        while (end < withoutComments.length() && Character.isLetter(withoutComments.charAt(end))) {
            end++;
        }
        return withoutComments.substring(0, end).toLowerCase(Locale.ENGLISH);
    }

    private String stripTrailingSemicolons(String value) {
        String result = value;
        while (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }
}
