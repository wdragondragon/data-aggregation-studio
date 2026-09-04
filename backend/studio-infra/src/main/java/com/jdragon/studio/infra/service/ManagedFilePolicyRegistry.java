package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.ManagedFilePolicyCode;
import org.springframework.stereotype.Service;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ManagedFilePolicyRegistry {

    private static final Pattern KRB5_INCLUDE = Pattern.compile("(?im)^\\s*(include|includedir)\\s+.+$");
    private final Map<ManagedFilePolicyCode, Policy> policies;

    public ManagedFilePolicyRegistry() {
        Map<ManagedFilePolicyCode, Policy> values = new EnumMap<ManagedFilePolicyCode, Policy>(ManagedFilePolicyCode.class);
        values.put(ManagedFilePolicyCode.KERBEROS_KEYTAB,
                new Policy(1024L * 1024L, set("keytab"), true));
        values.put(ManagedFilePolicyCode.KERBEROS_KRB5_CONF,
                new Policy(1024L * 1024L, set("conf"), true));
        values.put(ManagedFilePolicyCode.HADOOP_SITE_XML,
                new Policy(2L * 1024L * 1024L, set("xml"), false));
        values.put(ManagedFilePolicyCode.GENERAL_CONFIG,
                new Policy(5L * 1024L * 1024L,
                        set("conf", "config", "properties", "yaml", "yml", "json", "xml", "txt", "ini"), false));
        values.put(ManagedFilePolicyCode.GENERAL_ATTACHMENT,
                new Policy(10L * 1024L * 1024L,
                        set("txt", "md", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx"), false));
        this.policies = Collections.unmodifiableMap(values);
    }

    public Policy require(String policyCode) {
        ManagedFilePolicyCode code;
        try {
            code = ManagedFilePolicyCode.valueOf(policyCode == null ? "" : policyCode.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unknown managed file policy: " + policyCode);
        }
        Policy policy = policies.get(code);
        if (policy == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unknown managed file policy: " + policyCode);
        }
        return policy;
    }

    public void validate(String policyCode, String fileName, Path file) {
        Policy policy = require(policyCode);
        String safeName = requireSafeFileName(fileName);
        String extension = extension(safeName);
        if (!policy.getExtensions().contains(extension)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "File extension is not allowed for policy " + policyCode + ": " + safeName);
        }
        try {
            long size = Files.size(file);
            if (size <= 0L) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Managed file must not be empty");
            }
            if (size > policy.getMaxBytes()) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "Managed file exceeds policy limit of " + policy.getMaxBytes() + " bytes");
            }
            ManagedFilePolicyCode code = ManagedFilePolicyCode.valueOf(policyCode.trim().toUpperCase(Locale.ROOT));
            if (code == ManagedFilePolicyCode.KERBEROS_KEYTAB) {
                validateKeytab(file);
            } else if (code == ManagedFilePolicyCode.KERBEROS_KRB5_CONF) {
                validateKrb5(file);
            } else if (code == ManagedFilePolicyCode.HADOOP_SITE_XML) {
                validateHadoopXml(file);
            }
        } catch (StudioException e) {
            throw e;
        } catch (Exception e) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Managed file validation failed", e);
        }
    }

    public String requireSafeFileName(String fileName) {
        String value = fileName == null ? "" : fileName.trim();
        if (value.isEmpty() || value.length() > 255 || value.indexOf('\0') >= 0
                || value.contains("/") || value.contains("\\") || ".".equals(value) || "..".equals(value)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Invalid managed file name");
        }
        return value;
    }

    private void validateKeytab(Path file) throws Exception {
        byte[] header = new byte[2];
        try (java.io.InputStream input = Files.newInputStream(file)) {
            if (input.read(header) != 2 || header[0] != 0x05 || (header[1] != 0x01 && header[1] != 0x02)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Invalid Kerberos keytab version header");
            }
        }
    }

    private void validateKrb5(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        String content = decodeUtf8(bytes);
        if (KRB5_INCLUDE.matcher(content).find()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Kerberos configuration must be self-contained; include and includedir are not allowed");
        }
        if (!content.matches("(?s).*\\[[^]]+].*")) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Invalid krb5.conf: no section found");
        }
    }

    private void validateHadoopXml(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        String root = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement().getTagName();
        if (!"configuration".equals(root)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Hadoop site XML root element must be configuration");
        }
    }

    private String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
        return decoded.toString();
    }

    private String extension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index < 0 ? "" : fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private static Set<String> set(String... values) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(values)));
    }

    public static final class Policy {
        private final long maxBytes;
        private final Set<String> extensions;
        private final boolean sensitive;

        private Policy(long maxBytes, Set<String> extensions, boolean sensitive) {
            this.maxBytes = maxBytes;
            this.extensions = extensions;
            this.sensitive = sensitive;
        }

        public long getMaxBytes() { return maxBytes; }
        public Set<String> getExtensions() { return extensions; }
        public boolean isSensitive() { return sensitive; }
    }
}
