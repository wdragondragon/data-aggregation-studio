package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class KerberosConfigRegistry {

    private final Object monitor = new Object();
    private final Path stablePath;
    private final Map<String, ParsedConfig> active = new LinkedHashMap<String, ParsedConfig>();
    private long generation;

    public KerberosConfigRegistry(StudioPlatformProperties properties) {
        String cacheDir = properties.getManagedFile() != null
                && StringUtils.hasText(properties.getManagedFile().getCacheDir())
                ? properties.getManagedFile().getCacheDir().trim() : "./runtime/managed-files";
        this.stablePath = Path.of(cacheDir).toAbsolutePath().normalize()
                .resolve("kerberos").resolve("krb5-merged.conf");
    }

    public Activation activate(Path source, Long fileId) {
        return activate(source, fileId, null, null);
    }

    public Activation activate(Path source, Long fileId, String principal, String serviceHost) {
        if (source == null || !Files.isRegularFile(source)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Kerberos configuration file is unavailable");
        }
        ParsedConfig candidate = parse(source);
        String token = UUID.randomUUID().toString();
        synchronized (monitor) {
            Map<String, ParsedConfig> prospective = new LinkedHashMap<String, ParsedConfig>(active);
            prospective.put(token, candidate);
            MergedConfig merged = merge(prospective.values());
            validateIdentity(merged, principal, serviceHost);
            writeMerged(merged);
            active.put(token, candidate);
            generation++;
            return new Activation(token, fileId, stablePath, generation, merged.defaultRealm, this);
        }
    }

    private void validateIdentity(MergedConfig merged, String principal, String serviceHost) {
        if (!StringUtils.hasText(principal)) return;
        String normalizedPrincipal = principal.trim();
        int separator = normalizedPrincipal.lastIndexOf('@');
        if (separator <= 0 || separator == normalizedPrincipal.length() - 1) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Kerberos Principal must include an explicit @REALM");
        }
        String realm = normalizedPrincipal.substring(separator + 1).toUpperCase(Locale.ROOT);
        if (!merged.realms.containsKey(realm)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Kerberos Principal realm is not defined by the active krb5.conf files: " + realm);
        }
        if (merged.realms.size() <= 1 || merged.defaultRealm.equals(realm)) return;
        String host = normalizeHost(StringUtils.hasText(serviceHost)
                ? serviceHost : principalHost(normalizedPrincipal));
        String mappedRealm = mappedRealm(merged.domainRealm, host);
        if (!realm.equals(mappedRealm)) {
            throw new StudioException(StudioErrorCode.CONFLICT,
                    "Kerberos service host " + (host.isEmpty() ? "<missing>" : host)
                            + " must have an explicit [domain_realm] mapping to " + realm
                            + " when multiple realms are active");
        }
    }

    private String mappedRealm(Map<String, String> mappings, String host) {
        if (host.isEmpty()) return null;
        String exact = mappings.get(host);
        if (exact != null) return exact;
        String matchedKey = null;
        String matchedRealm = null;
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(".") || !host.endsWith(key)) continue;
            if (matchedKey == null || key.length() > matchedKey.length()) {
                matchedKey = key;
                matchedRealm = entry.getValue();
            }
        }
        return matchedRealm;
    }

    private String principalHost(String principal) {
        int at = principal.lastIndexOf('@');
        String identity = at < 0 ? principal : principal.substring(0, at);
        int slash = identity.indexOf('/');
        return slash < 0 || slash == identity.length() - 1 ? "" : identity.substring(slash + 1);
    }

    private String normalizeHost(String value) {
        if (!StringUtils.hasText(value)) return "";
        String host = value.trim();
        int comma = host.indexOf(',');
        if (comma >= 0) host = host.substring(0, comma).trim();
        int scheme = host.indexOf("://");
        if (scheme >= 0) {
            try {
                host = java.net.URI.create(host).getHost();
            } catch (Exception ignored) {
                return "";
            }
        }
        if (host == null) return "";
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            host = end > 0 ? host.substring(1, end) : host;
        } else {
            int firstColon = host.indexOf(':');
            int lastColon = host.lastIndexOf(':');
            if (firstColon > 0 && firstColon == lastColon) host = host.substring(0, firstColon);
        }
        host = host.trim().toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host;
    }

    public long generation() {
        synchronized (monitor) {
            return generation;
        }
    }

    private void deactivate(String token) {
        synchronized (monitor) {
            if (!active.containsKey(token)) return;
            Map<String, ParsedConfig> prospective = new LinkedHashMap<String, ParsedConfig>(active);
            prospective.remove(token);
            if (!prospective.isEmpty()) {
                MergedConfig merged = merge(prospective.values());
                writeMerged(merged);
            }
            active.remove(token);
            generation++;
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
                    config.domainRealm.put(item.key.toLowerCase(Locale.ROOT), item.value.toUpperCase(Locale.ROOT));
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
                        throw new StudioException(StudioErrorCode.BAD_REQUEST,
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
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "Kerberos configuration must define at least one realm in [realms]");
            }
            return config;
        } catch (StudioException e) {
            throw e;
        } catch (Exception e) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Invalid Kerberos configuration", e);
        }
    }

    private MergedConfig merge(Iterable<ParsedConfig> configs) {
        TreeMap<String, String> libdefaults = new TreeMap<String, String>();
        TreeMap<String, String> realms = new TreeMap<String, String>();
        TreeMap<String, String> domainRealm = new TreeMap<String, String>();
        TreeMap<String, String> capaths = new TreeMap<String, String>();
        for (ParsedConfig config : configs) {
            mergeStrict(libdefaults, config.libdefaults, "[libdefaults]");
            mergeStrict(realms, config.realms, "Kerberos realm");
            mergeStrict(domainRealm, config.domainRealm, "[domain_realm]");
            mergeStrict(capaths, config.capaths, "[capaths]");
        }
        if (realms.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "No active Kerberos realm definition");
        }
        return new MergedConfig(realms.firstKey(), libdefaults, realms, domainRealm, capaths);
    }

    private void mergeStrict(Map<String, String> target, Map<String, String> source, String section) {
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String existing = target.get(entry.getKey());
            if (existing != null && !existing.equals(entry.getValue())) {
                throw new StudioException(StudioErrorCode.CONFLICT,
                        "Kerberos configuration conflict in " + section + " for " + entry.getKey()
                                + ": existing=" + existing + ", candidate=" + entry.getValue());
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
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to activate merged Kerberos configuration", e);
        }
    }

    private String render(MergedConfig merged) {
        StringBuilder result = new StringBuilder();
        result.append("[libdefaults]\n");
        result.append("    default_realm = ").append(merged.defaultRealm).append('\n');
        for (Map.Entry<String, String> entry : merged.libdefaults.entrySet()) {
            result.append("    ").append(entry.getKey()).append(" = ").append(entry.getValue()).append('\n');
        }
        result.append("\n[realms]\n");
        renderBlocks(result, merged.realms);
        if (!merged.domainRealm.isEmpty()) {
            result.append("\n[domain_realm]\n");
            for (Map.Entry<String, String> entry : merged.domainRealm.entrySet()) {
                result.append("    ").append(entry.getKey()).append(" = ").append(entry.getValue()).append('\n');
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
            result.append("    ").append(entry.getKey()).append(" = ").append(entry.getValue()).append('\n');
        }
    }

    private KeyValue keyValue(String line, String section) {
        int equals = line.indexOf('=');
        if (equals <= 0) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Invalid Kerberos entry in " + section + ": " + line);
        }
        String key = line.substring(0, equals).trim();
        String value = line.substring(equals + 1).trim();
        if (key.isEmpty() || value.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Invalid Kerberos entry in " + section + ": " + line);
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

    public static final class Activation implements AutoCloseable {
        private final String token;
        private final Long fileId;
        private final Path mergedPath;
        private final long generation;
        private final String defaultRealm;
        private final KerberosConfigRegistry registry;
        private boolean closed;

        private Activation(String token, Long fileId, Path mergedPath, long generation,
                           String defaultRealm, KerberosConfigRegistry registry) {
            this.token = token;
            this.fileId = fileId;
            this.mergedPath = mergedPath;
            this.generation = generation;
            this.defaultRealm = defaultRealm;
            this.registry = registry;
        }

        public Long getFileId() { return fileId; }
        public Path getMergedPath() { return mergedPath; }
        public long getGeneration() { return generation; }
        public String getDefaultRealm() { return defaultRealm; }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            registry.deactivate(token);
        }
    }
}
