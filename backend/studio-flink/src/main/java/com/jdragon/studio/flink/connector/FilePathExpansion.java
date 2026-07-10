package com.jdragon.studio.flink.connector;

import com.jdragon.aggregation.datasource.file.FileHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

final class FilePathExpansion {
    private FilePathExpansion() {
    }

    static List<ResolvedFilePath> expand(FileHelper fileHelper,
                                         AggregationFlinkTableRuntime runtime,
                                         ResolvedFilePath resolvedPath) throws IOException {
        if (resolvedPath == null || !hasText(resolvedPath.getPath())) {
            return Collections.emptyList();
        }
        String path = normalizePath(resolvedPath.getPath());
        String partitionType = partitionType(runtime.getModelMetadata());
        if (requiresListing(path, partitionType)) {
            return expandPattern(fileHelper, resolvedPath, path, partitionType);
        }
        if (!exists(fileHelper, path)) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new ResolvedFilePath(path, resolvedPath.getContextValues()));
    }

    static boolean isMissingFile(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ENGLISH);
                if (normalized.contains("no such file")
                        || normalized.contains("not found")
                        || normalized.contains("not exist")
                        || normalized.contains("does not exist")
                        || normalized.contains("nosuchkey")
                        || normalized.contains("status code: 404")
                        || normalized.contains("http status: 404")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static List<ResolvedFilePath> expandPattern(FileHelper fileHelper,
                                                       ResolvedFilePath resolvedPath,
                                                       String path,
                                                       String partitionType) throws IOException {
        PathPattern pattern = PathPattern.from(path, partitionType);
        Set<String> files;
        try {
            files = fileHelper.listFile(pattern.directory, pattern.regex);
        } catch (RuntimeException ex) {
            if (isMissingFile(ex)) {
                return Collections.emptyList();
            }
            throw ex;
        } catch (IOException ex) {
            if (isMissingFile(ex)) {
                return Collections.emptyList();
            }
            throw ex;
        }
        List<String> names = new ArrayList<String>(files == null ? Collections.<String>emptySet() : files);
        Collections.sort(names);
        List<ResolvedFilePath> result = new ArrayList<ResolvedFilePath>();
        for (String name : names) {
            if (!hasText(name) || ".".equals(name) || "..".equals(name)) {
                continue;
            }
            result.add(new ResolvedFilePath(qualify(pattern.directory, name), resolvedPath.getContextValues()));
        }
        return result;
    }

    private static boolean exists(FileHelper fileHelper, String path) throws IOException {
        PathName pathName = PathName.from(path);
        try {
            return fileHelper.exists(pathName.directory, pathName.name);
        } catch (RuntimeException ex) {
            if (isMissingFile(ex)) {
                return false;
            }
            return true;
        } catch (IOException ex) {
            if (isMissingFile(ex)) {
                return false;
            }
            throw ex;
        }
    }

    private static boolean requiresListing(String path, String partitionType) {
        if ("regex".equals(partitionType)) {
            return containsRegexMeta(PathName.from(path).name);
        }
        return containsGlobMeta(path);
    }

    private static String partitionType(Map<String, Object> metadata) {
        Object configured = metadata == null ? null : metadata.get("partitionType");
        String type = configured == null ? "glob" : String.valueOf(configured).trim().toLowerCase(Locale.ENGLISH);
        return "regex".equals(type) ? "regex" : "glob";
    }

    private static boolean containsGlobMeta(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '*' || c == '?' || c == '{' || c == '[') {
                return true;
            }
        }
        return false;
    }

    private static boolean containsRegexMeta(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ("*+?[](){}|^$".indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static String qualify(String directory, String name) {
        String normalizedName = normalizePath(name);
        if (normalizedName.startsWith("/") || normalizedName.contains("://")) {
            return normalizedName;
        }
        String normalizedDir = normalizePath(directory);
        if (hasText(normalizedDir) && !"/".equals(normalizedDir)
                && (normalizedName.equals(normalizedDir) || normalizedName.startsWith(normalizedDir + "/"))) {
            return normalizedName;
        }
        return joinPath(normalizedDir, normalizedName);
    }

    private static String joinPath(String directory, String name) {
        String dir = directory == null ? "" : directory;
        String fileName = name == null ? "" : name;
        while (dir.endsWith("/") && dir.length() > 1) {
            dir = dir.substring(0, dir.length() - 1);
        }
        while (fileName.startsWith("/")) {
            fileName = fileName.substring(1);
        }
        if (!hasText(dir) || "/".equals(dir)) {
            return "/" + fileName;
        }
        return dir + "/" + fileName;
    }

    private static String normalizePath(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        while (normalized.contains("//") && !normalized.contains("://")) {
            normalized = normalized.replace("//", "/");
        }
        return normalized;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static final class PathName {
        private final String directory;
        private final String name;

        private PathName(String directory, String name) {
            this.directory = directory;
            this.name = name;
        }

        private static PathName from(String path) {
            String normalized = normalizePath(path);
            int slash = normalized.lastIndexOf('/');
            if (slash < 0) {
                return new PathName("/", normalized);
            }
            if (slash == 0) {
                return new PathName("/", normalized.substring(1));
            }
            return new PathName(normalized.substring(0, slash), normalized.substring(slash + 1));
        }
    }

    private static final class PathPattern {
        private final String directory;
        private final String regex;

        private PathPattern(String directory, String regex) {
            this.directory = directory;
            this.regex = regex;
        }

        private static PathPattern from(String path, String partitionType) {
            PathName pathName = PathName.from(path);
            String body = "regex".equals(partitionType)
                    ? stripRegexAnchors(pathName.name)
                    : globToRegexBody(pathName.name);
            try {
                String regex = "^(?:.*/)?" + body + "$";
                "".matches(regex);
                return new PathPattern(pathName.directory, regex);
            } catch (PatternSyntaxException ex) {
                throw new IllegalArgumentException("Invalid file path " + partitionType + " pattern: " + pathName.name, ex);
            }
        }
    }

    private static String stripRegexAnchors(String regex) {
        String result = regex == null ? "" : regex.trim();
        if (result.startsWith("^")) {
            result = result.substring(1);
        }
        if (result.endsWith("$")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String globToRegexBody(String glob) {
        String normalized = glob == null || glob.trim().isEmpty() ? "*" : glob.trim().replace('\\', '/');
        StringBuilder regex = new StringBuilder();
        boolean inGroup = false;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < normalized.length() && normalized.charAt(i + 1) == '*') {
                        regex.append(".*");
                        i++;
                    } else {
                        regex.append("[^/]*");
                    }
                    break;
                case '?':
                    regex.append("[^/]");
                    break;
                case '{':
                    regex.append('(');
                    inGroup = true;
                    break;
                case '}':
                    regex.append(')');
                    inGroup = false;
                    break;
                case ',':
                    regex.append(inGroup ? '|' : ',');
                    break;
                case '.':
                case '(':
                case ')':
                case '+':
                case '|':
                case '^':
                case '$':
                case '@':
                case '%':
                    regex.append('\\').append(c);
                    break;
                default:
                    regex.append(c);
                    break;
            }
        }
        return regex.toString();
    }
}
