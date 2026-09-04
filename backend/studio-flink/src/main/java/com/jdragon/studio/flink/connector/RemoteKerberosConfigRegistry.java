package com.jdragon.studio.flink.connector;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

final class RemoteKerberosConfigRegistry {
    private static final RemoteKerberosConfigRegistry GLOBAL = new RemoteKerberosConfigRegistry(
            RemoteManagedFileCache.cacheRoot().resolve("kerberos").resolve("krb5-merged.conf"));

    private final Object monitor = new Object();
    private final Path stablePath;
    private final Map<String, List<ParsedConfig>> active = new LinkedHashMap<String, List<ParsedConfig>>();

    RemoteKerberosConfigRegistry(Path stablePath) {
        this.stablePath = stablePath.toAbsolutePath().normalize();
    }

    static Activation activateManagedFiles(Collection<Path> sources) {
        return GLOBAL.activate(sources);
    }

    Activation activate(Collection<Path> sources) {
        if (sources == null || sources.isEmpty()) return Activation.empty();
        List<ParsedConfig> candidates = new ArrayList<ParsedConfig>();
        for (Path source : sources) {
            if (source == null || !Files.isRegularFile(source)) {
                throw new IllegalStateException("Kerberos configuration file is unavailable");
            }
            candidates.add(parse(source));
        }
        String token = UUID.randomUUID().toString();
        synchronized (monitor) {
            Map<String, List<ParsedConfig>> prospective =
                    new LinkedHashMap<String, List<ParsedConfig>>(active);
            prospective.put(token, candidates);
            MergedConfig merged = merge(prospective.values());
            writeMerged(merged);
            active.put(token, candidates);
        }
        return new Activation(token, stablePath, this);
    }

    private void deactivate(String token) {
        synchronized (monitor) {
            if (!active.containsKey(token)) return;
            Map<String, List<ParsedConfig>> prospective =
                    new LinkedHashMap<String, List<ParsedConfig>>(active);
            prospective.remove(token);
            if (!prospective.isEmpty()) writeMerged(merge(prospective.values()));
            active.remove(token);
        }
    }

    private ParsedConfig parse(Path path) {
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            ParsedConfig config = new ParsedConfig();
            String section = "";
            for (int index = 0; index < lines.size();) {
                String line = stripComment(lines.get(index)).trim();
                index++;
                if (line.isEmpty()) continue;
                if (line.toLowerCase(Locale.ROOT).startsWith("include")) {
                    throw new IllegalStateException("Kerberos include directives are not supported");
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT);
                    continue;
                }
                if ("libdefaults".equals(section)) {
                    KeyValue item = keyValue(line, "[libdefaults]");
                    if (!"default_realm".equalsIgnoreCase(item.key)) {
                        config.libdefaults.put(item.key.toLowerCase(Locale.ROOT), item.value);
                    }
                } else if ("domain_realm".equals(section)) {
                    KeyValue item = keyValue(line, "[domain_realm]");
                    config.domainRealm.put(item.key.toLowerCase(Locale.ROOT),
                            item.value.toUpperCase(Locale.ROOT));
                } else if ("realms".equals(section) || "capaths".equals(section)) {
                    KeyValue item = keyValue(line, "[" + section + "]");
                    List<String> block = new ArrayList<String>();
                    String initial = normalizeWhitespace(item.value);
                    int depth = braceDelta(initial);
                    block.add(initial);
                    while (depth > 0 && index < lines.size()) {
                        String nested = stripComment(lines.get(index)).trim();
                        index++;
                        if (nested.isEmpty()) continue;
                        block.add(normalizeWhitespace(nested));
                        depth += braceDelta(nested);
                    }
                    if (depth != 0) {
                        throw new IllegalStateException(
                                "Unbalanced Kerberos configuration block for " + item.key);
                    }
                    String canonical = canonicalBlock(block);
                    if ("realms".equals(section)) {
                        config.realms.put(item.key.toUpperCase(Locale.ROOT), canonical);
                    } else {
                        config.capaths.put(item.key.toUpperCase(Locale.ROOT), canonical);
                    }
                }
            }
            if (config.realms.isEmpty()) {
                throw new IllegalStateException(
                        "Kerberos configuration must define at least one realm in [realms]");
            }
            return config;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid Kerberos configuration", e);
        }
    }

    private MergedConfig merge(Collection<List<ParsedConfig>> groups) {
        TreeMap<String, String> libdefaults = new TreeMap<String, String>();
        TreeMap<String, String> realms = new TreeMap<String, String>();
        TreeMap<String, String> domainRealm = new TreeMap<String, String>();
        TreeMap<String, String> capaths = new TreeMap<String, String>();
        for (List<ParsedConfig> group : groups) {
            for (ParsedConfig config : group) {
                mergeStrict(libdefaults, config.libdefaults, "[libdefaults]");
                mergeStrict(realms, config.realms, "Kerberos realm");
                mergeStrict(domainRealm, config.domainRealm, "[domain_realm]");
                mergeStrict(capaths, config.capaths, "[capaths]");
            }
        }
        if (realms.isEmpty()) throw new IllegalStateException("No active Kerberos realm definition");
        return new MergedConfig(realms.firstKey(), libdefaults, realms, domainRealm, capaths);
    }

    private void mergeStrict(Map<String, String> target, Map<String, String> source, String section) {
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String existing = target.get(entry.getKey());
            if (existing != null && !existing.equals(entry.getValue())) {
                throw new IllegalStateException("Kerberos configuration conflict in " + section
                        + " for " + entry.getKey() + ": existing=" + existing
                        + ", candidate=" + entry.getValue());
            }
            target.put(entry.getKey(), entry.getValue());
        }
    }

    private void writeMerged(MergedConfig merged) {
        try {
            Files.createDirectories(stablePath.getParent());
            Path temporary = Files.createTempFile(stablePath.getParent(), "krb5-merged-", ".tmp");
            Files.writeString(temporary, render(merged), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, stablePath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, stablePath, StandardCopyOption.REPLACE_EXISTING);
            }
            System.setProperty("java.security.krb5.conf", stablePath.toString());
            javax.security.auth.login.Configuration.getConfiguration().refresh();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to activate merged Kerberos configuration", e);
        }
    }

    private String render(MergedConfig merged) {
        StringBuilder result = new StringBuilder();
        result.append("[libdefaults]\n");
        result.append("    default_realm = ").append(merged.defaultRealm).append('\n');
        for (Map.Entry<String, String> entry : merged.libdefaults.entrySet()) {
            result.append("    ").append(entry.getKey()).append(" = ")
                    .append(entry.getValue()).append('\n');
        }
        result.append("\n[realms]\n");
        renderBlocks(result, merged.realms);
        if (!merged.domainRealm.isEmpty()) {
            result.append("\n[domain_realm]\n");
            for (Map.Entry<String, String> entry : merged.domainRealm.entrySet()) {
                result.append("    ").append(entry.getKey()).append(" = ")
                        .append(entry.getValue()).append('\n');
            }
        }
        if (!merged.capaths.isEmpty()) {
            result.append("\n[capaths]\n");
            renderBlocks(result, merged.capaths);
        }
        return result.toString();
    }

    private void renderBlocks(StringBuilder result, Map<String, String> blocks) {
        for (Map.Entry<String, String> entry : blocks.entrySet()) {
            result.append("    ").append(entry.getKey()).append(" = ")
                    .append(entry.getValue()).append('\n');
        }
    }

    private KeyValue keyValue(String line, String section) {
        int equals = line.indexOf('=');
        if (equals <= 0) throw new IllegalStateException("Invalid Kerberos entry in " + section + ": " + line);
        String key = line.substring(0, equals).trim();
        String value = line.substring(equals + 1).trim();
        if (key.isEmpty() || value.isEmpty()) {
            throw new IllegalStateException("Invalid Kerberos entry in " + section + ": " + line);
        }
        return new KeyValue(key, value);
    }

    private String stripComment(String line) {
        if (line == null) return "";
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (value == '"') quoted = !quoted;
            if (!quoted && (value == '#' || value == ';')) return line.substring(0, index);
        }
        return line;
    }

    private String normalizeWhitespace(String value) {
        return value.trim().replaceAll("\\s+", " ")
                .replaceAll("\\s*\\{\\s*", "{")
                .replaceAll("\\s*}\\s*", "}")
                .replaceAll("\\s*=\\s*", "=");
    }

    private int braceDelta(String value) {
        int result = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '{') result++;
            else if (value.charAt(index) == '}') result--;
        }
        return result;
    }

    private String canonicalBlock(List<String> lines) {
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            if (result.length() > 0) result.append(' ');
            result.append(normalizeWhitespace(line));
        }
        return result.toString();
    }

    static final class Activation implements AutoCloseable {
        private final String token;
        private final Path mergedPath;
        private final RemoteKerberosConfigRegistry registry;
        private boolean closed;

        private Activation(String token, Path mergedPath, RemoteKerberosConfigRegistry registry) {
            this.token = token;
            this.mergedPath = mergedPath;
            this.registry = registry;
        }

        private static Activation empty() {
            return new Activation(null, null, null);
        }

        Path getMergedPath() {
            return mergedPath;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if (registry != null) registry.deactivate(token);
        }
    }

    private static final class ParsedConfig {
        private final Map<String, String> libdefaults = new LinkedHashMap<String, String>();
        private final Map<String, String> realms = new LinkedHashMap<String, String>();
        private final Map<String, String> domainRealm = new LinkedHashMap<String, String>();
        private final Map<String, String> capaths = new LinkedHashMap<String, String>();
    }

    private static final class MergedConfig {
        private final String defaultRealm;
        private final Map<String, String> libdefaults;
        private final Map<String, String> realms;
        private final Map<String, String> domainRealm;
        private final Map<String, String> capaths;

        private MergedConfig(String defaultRealm, Map<String, String> libdefaults,
                             Map<String, String> realms, Map<String, String> domainRealm,
                             Map<String, String> capaths) {
            this.defaultRealm = defaultRealm;
            this.libdefaults = libdefaults;
            this.realms = realms;
            this.domainRealm = domainRealm;
            this.capaths = capaths;
        }
    }

    private static final class KeyValue {
        private final String key;
        private final String value;

        private KeyValue(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }
}
