package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QualityDynamicFunctionService {

    private static final Pattern FUNCTION_PATTERN = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)\\(([^()]*)\\)");

    public String replaceAll(String content) {
        if (content == null || content.trim().isEmpty()) {
            return content;
        }
        Matcher matcher = FUNCTION_PATTERN.matcher(content);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = execute(matcher.group(1), splitArgs(matcher.group(2)));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    public List<String> validate(String content) {
        List<String> errors = new ArrayList<String>();
        if (content == null || content.trim().isEmpty()) {
            return errors;
        }
        Matcher matcher = FUNCTION_PATTERN.matcher(content);
        Set<String> matchedSegments = new LinkedHashSet<String>();
        while (matcher.find()) {
            matchedSegments.add(matcher.group());
            String functionName = matcher.group(1);
            try {
                execute(functionName, splitArgs(matcher.group(2)));
            } catch (Exception ex) {
                errors.add("动态函数 " + functionName + " 校验失败: " + ex.getMessage());
            }
        }
        if (content.contains("$")) {
            Matcher rawMatcher = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*").matcher(content);
            while (rawMatcher.find()) {
                boolean covered = false;
                for (String segment : matchedSegments) {
                    if (segment.indexOf(rawMatcher.group()) == 0) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) {
                    errors.add("检测到不支持或格式非法的动态函数: " + rawMatcher.group());
                }
            }
        }
        return errors;
    }

    private String execute(String functionName, String[] args) {
        if ("getCurrentTime".equals(functionName)) {
            return getCurrentTime(args);
        }
        if ("getTheMonthLastDay".equals(functionName)) {
            return getTheMonthLastDay(args);
        }
        if ("getTimeUnitValue".equals(functionName)) {
            return getTimeUnitValue(args);
        }
        if ("subStr".equals(functionName)) {
            return subStr(args);
        }
        if ("subString".equals(functionName)) {
            return subString(args);
        }
        if ("toLower".equals(functionName)) {
            return toLower(args);
        }
        if ("toUpper".equals(functionName)) {
            return toUpper(args);
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported dynamic function: " + functionName);
    }

    private String getCurrentTime(String[] args) {
        requireArgCount("getCurrentTime", args, 1);
        String pattern = stripQuotes(args[0]);
        String offset = args.length > 1 ? stripQuotes(args[1]) : "";
        LocalDateTime value = LocalDateTime.now();
        if (offset != null && !offset.trim().isEmpty()) {
            value = applyOffset(value, offset.trim());
        }
        return DateTimeFormatter.ofPattern(pattern).format(value);
    }

    private String getTheMonthLastDay(String[] args) {
        LocalDateTime value = args.length > 0 && args[0] != null && !args[0].trim().isEmpty()
                ? parseDateTime(stripQuotes(args[0]))
                : LocalDateTime.now();
        return String.valueOf(YearMonth.from(value).lengthOfMonth());
    }

    private String getTimeUnitValue(String[] args) {
        requireArgCount("getTimeUnitValue", args, 2);
        LocalDateTime value = parseDateTime(stripQuotes(args[0]));
        String unit = stripQuotes(args[1]).toLowerCase(Locale.ENGLISH);
        if ("y".equals(unit)) {
            return String.valueOf(value.getYear());
        }
        if ("m".equals(unit)) {
            return String.valueOf(value.getMonthValue());
        }
        if ("d".equals(unit)) {
            return String.valueOf(value.getDayOfMonth());
        }
        if ("h".equals(unit)) {
            return String.valueOf(value.getHour());
        }
        if ("mi".equals(unit)) {
            return String.valueOf(value.getMinute());
        }
        if ("s".equals(unit)) {
            return String.valueOf(value.getSecond());
        }
        if ("day".equals(unit)) {
            DayOfWeek dayOfWeek = value.getDayOfWeek();
            return String.valueOf(dayOfWeek.getValue());
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported unit for getTimeUnitValue: " + unit);
    }

    private String subStr(String[] args) {
        requireArgCount("subStr", args, 3);
        String source = stripQuotes(args[0]);
        int start = parseInt(stripQuotes(args[1]));
        int length = parseInt(stripQuotes(args[2]));
        if (source == null || source.isEmpty()) {
            return "";
        }
        int from = Math.max(0, start);
        int to = Math.min(source.length(), from + Math.max(0, length));
        return from >= to ? "" : source.substring(from, to);
    }

    private String subString(String[] args) {
        requireArgCount("subString", args, 3);
        String source = stripQuotes(args[0]);
        int start = parseInt(stripQuotes(args[1]));
        int end = parseInt(stripQuotes(args[2]));
        if (source == null || source.isEmpty()) {
            return "";
        }
        int from = Math.max(0, start);
        int to = Math.min(source.length(), Math.max(from, end));
        return from >= to ? "" : source.substring(from, to);
    }

    private String toLower(String[] args) {
        requireArgCount("toLower", args, 1);
        return stripQuotes(args[0]).toLowerCase(Locale.ENGLISH);
    }

    private String toUpper(String[] args) {
        requireArgCount("toUpper", args, 1);
        return stripQuotes(args[0]).toUpperCase(Locale.ENGLISH);
    }

    private void requireArgCount(String functionName, String[] args, int minCount) {
        if (args == null || args.length < minCount) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, functionName + " requires at least " + minCount + " parameters");
        }
    }

    private String[] splitArgs(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new String[0];
        }
        List<String> parts = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int index = 0; index < value.length(); index++) {
            char currentChar = value.charAt(index);
            if ((currentChar == '\'' || currentChar == '"')) {
                if (!inQuotes) {
                    inQuotes = true;
                    quoteChar = currentChar;
                } else if (quoteChar == currentChar) {
                    inQuotes = false;
                    quoteChar = 0;
                }
            }
            if (currentChar == ',' && !inQuotes) {
                parts.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(currentChar);
        }
        parts.add(current.toString().trim());
        return parts.toArray(new String[0]);
    }

    private String stripQuotes(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.trim().isEmpty()) {
            return LocalDateTime.now();
        }
        String trimmed = value.trim();
        if (trimmed.matches("^-?\\d+$")) {
            long numeric = Long.parseLong(trimmed);
            if (trimmed.length() >= 13) {
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(numeric), ZoneId.systemDefault());
            }
            if (trimmed.length() == 8) {
                return LocalDateTime.parse(trimmed + "000000", DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            }
            if (trimmed.length() == 14) {
                return LocalDateTime.parse(trimmed, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            }
        }
        try {
            return LocalDateTime.parse(trimmed, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(trimmed, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(trimmed + " 00:00:00", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (DateTimeParseException ignored) {
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unable to parse datetime value: " + value);
    }

    private LocalDateTime applyOffset(LocalDateTime base, String offset) {
        if (offset.indexOf('y') >= 0 || offset.indexOf('m') >= 0 || offset.indexOf('d') >= 0
                || offset.indexOf('h') >= 0 || offset.indexOf('w') >= 0 || offset.indexOf('s') >= 0) {
            Matcher matcher = Pattern.compile("([+-]?)(\\d+)(mi|[ymdhws])").matcher(offset);
            LocalDateTime current = base;
            int consumed = 0;
            while (matcher.find()) {
                String sign = matcher.group(1);
                long value = Long.parseLong(matcher.group(2));
                if ("-".equals(sign)) {
                    value = -value;
                }
                String unit = matcher.group(3);
                current = applyTemporalUnit(current, value, unit);
                consumed += matcher.group().length();
            }
            if (consumed != offset.replace(" ", "").length()) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported offset expression: " + offset);
            }
            return current;
        }
        long seconds = evaluateSecondExpression(offset);
        return base.plusSeconds(seconds);
    }

    private LocalDateTime applyTemporalUnit(LocalDateTime value, long amount, String unit) {
        if ("y".equals(unit)) {
            return value.plusYears(amount);
        }
        if ("m".equals(unit)) {
            return value.plusMonths(amount);
        }
        if ("w".equals(unit)) {
            return value.plusWeeks(amount);
        }
        if ("d".equals(unit)) {
            return value.plusDays(amount);
        }
        if ("h".equals(unit)) {
            return value.plusHours(amount);
        }
        if ("mi".equals(unit)) {
            return value.plusMinutes(amount);
        }
        if ("s".equals(unit)) {
            return value.plus(amount, ChronoUnit.SECONDS);
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported offset unit: " + unit);
    }

    private long evaluateSecondExpression(String expression) {
        String normalized = expression.replace(" ", "");
        if (normalized.isEmpty()) {
            return 0L;
        }
        List<String> terms = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < normalized.length(); index++) {
            char currentChar = normalized.charAt(index);
            if ((currentChar == '+' || currentChar == '-') && current.length() > 0) {
                terms.add(current.toString());
                current.setLength(0);
            }
            current.append(currentChar);
        }
        terms.add(current.toString());
        long result = 0L;
        for (String term : terms) {
            if (term == null || term.isEmpty()) {
                continue;
            }
            result += multiplyFactors(term);
        }
        return result;
    }

    private long multiplyFactors(String term) {
        boolean negative = term.startsWith("-");
        String normalized = negative || term.startsWith("+") ? term.substring(1) : term;
        long result = 1L;
        String[] factors = normalized.split("\\*");
        for (String factor : factors) {
            if (factor == null || factor.trim().isEmpty()) {
                continue;
            }
            result *= Long.parseLong(factor.trim());
        }
        return negative ? -result : result;
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Invalid integer value: " + value);
        }
    }
}
