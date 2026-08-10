package com.jdragon.studio.infra.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.OSSObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.infra.entity.ArtifactStoreEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads and writes Python and Java dependencies directly in the selected physical repository. */
@Service
public class ArtifactRepositoryPublisher {
    private static final int MAX_INDEX_BYTES = 1024 * 1024;
    private static final int MAX_PACKAGE_BYTES = 64 * 1024 * 1024;
    private static final Pattern GITLAB_PYPI_URL = Pattern.compile(
            "^(.*?/api/v4)/(projects|groups)/(.+?)(?:/-)?/packages/pypi/?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SIMPLE_LINK = Pattern.compile(
            "<a\\b[^>]*\\bhref\\s*=\\s*([\"'])(.*?)\\1[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final ArtifactStoreService stores;
    private final RuntimeEndpointSecurityService endpointSecurity;
    private final RuntimeEndpointHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ArtifactRepositoryPublisher(ArtifactStoreService stores,
                                       RuntimeEndpointSecurityService endpointSecurity,
                                       RuntimeEndpointHttpClient httpClient,
                                       ObjectMapper objectMapper) {
        this.stores = stores;
        this.endpointSecurity = endpointSecurity;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public void publish(Long storeId, String scriptType, String packageName, String version, String fileName, byte[] bytes) {
        if (storeId == null) {
            throw new IllegalStateException("Dependency is not bound to an artifact repository");
        }
        ArtifactStoreEntity store = stores.requireEnabled(storeId);
        String provider = safe(store.getProvider()).toUpperCase(Locale.ROOT);
        if ("OSS".equals(provider)) {
            publishOss(store, packageName, version, fileName, bytes);
            return;
        }
        if ("JAVA".equalsIgnoreCase(scriptType)) {
            if (!"GITLAB".equals(provider)) {
                throw new IllegalStateException("Java repositories only support OSS or GitLab");
            }
            publishGitLabGeneric(store, packageName, version, fileName, bytes);
            return;
        }
        publishPypi(store, packageName, version, fileName, bytes);
    }

    /** Reads a package file from the selected physical repository, which is the only persisted source. */
    public byte[] downloadPackageFile(Long storeId, String scriptType, String packageName,
                                      String version, String fileName) {
        if (storeId == null) {
            throw new IllegalStateException("Dependency is not bound to an artifact repository");
        }
        ArtifactStoreEntity store = stores.requireAccessible(storeId);
        String provider = safe(store.getProvider()).toUpperCase(Locale.ROOT);
        if ("OSS".equals(provider)) {
            return downloadOssFile(store, packageName, version, fileName);
        }
        if ("JAVA".equalsIgnoreCase(scriptType)) {
            if (!"GITLAB".equals(provider)) {
                throw new IllegalStateException("Java repositories only support OSS or GitLab");
            }
            return downloadGitLabGenericFile(store, packageName, version, fileName);
        }
        return downloadPythonSimpleFile(store, packageName, fileName);
    }

    /** Deletes one logical dependency version from its selected physical repository. */
    public void deleteArtifactVersion(Long storeId, String scriptType, String packageName,
                                      String version, List<String> fileNames) {
        if (storeId == null) {
            throw new IllegalStateException("Dependency is not bound to an artifact repository");
        }
        ArtifactStoreEntity store = stores.requireAccessible(storeId);
        String provider = safe(store.getProvider()).toUpperCase(Locale.ROOT);
        if ("OSS".equals(provider)) {
            deleteOssFiles(store, packageName, version, fileNames);
            return;
        }
        if ("JAVA".equalsIgnoreCase(scriptType) && "GITLAB".equals(provider)) {
            deleteGitLabPackageVersion(store, packageName, version, "generic");
            return;
        }
        if (!"JAVA".equalsIgnoreCase(scriptType) && "GITLAB".equals(provider)) {
            deleteGitLabPackageVersion(store, packageName, version);
            return;
        }
        throw new IllegalStateException(
                store.getProvider() + " deletion is not configured through a standard package API; remove the "
                        + "distribution in the repository first, then clear the repository binding or use GitLab/OSS");
    }

    public void validateArtifactVersionDeletion(Long storeId, String scriptType) {
        if (storeId == null) {
            throw new IllegalStateException("Dependency is not bound to an artifact repository");
        }
        ArtifactStoreEntity store = stores.requireAccessible(storeId);
        String provider = safe(store.getProvider()).toUpperCase(Locale.ROOT);
        if ("OSS".equals(provider) || "GITLAB".equals(provider)) return;
        throw new IllegalStateException(
                store.getProvider() + " deletion is not configured through a standard package API; remove the "
                        + "distribution in the repository first, then clear the repository binding or use GitLab/OSS");
    }

    /**
     * File-level deletion is safe for the OSS layout only. Deleting a GitLab PyPI package file can corrupt
     * the package version, while pypiserver has no standard delete endpoint.
     */
    public void deleteArtifactFile(Long storeId, String scriptType, String packageName,
                                   String version, String fileName) {
        if (storeId == null) {
            throw new IllegalStateException("Dependency is not bound to an artifact repository");
        }
        ArtifactStoreEntity store = stores.requireAccessible(storeId);
        if ("OSS".equalsIgnoreCase(store.getProvider())) {
            deleteOssFiles(store, packageName, version, Collections.singletonList(fileName));
            return;
        }
        throw new IllegalStateException(
                "File-level deletion is not supported for " + store.getProvider()
                        + " Python repositories; delete the complete package version instead");
    }

    private void publishOss(ArtifactStoreEntity store, String packageName, String version, String fileName, byte[] bytes) {
        String path = join(store.getRootPrefix(), normalize(packageName), safe(version));
        OSS client = new OSSClientBuilder().build(store.getEndpoint(), stores.username(store), stores.secret(store));
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            client.putObject(store.getBucket(), join(path, fileName), input);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to publish package to OSS repository", ex);
        } finally {
            client.shutdown();
        }
    }

    private byte[] downloadOssFile(ArtifactStoreEntity store, String packageName, String version, String fileName) {
        String path = join(store.getRootPrefix(), normalize(packageName), safe(version), fileName);
        OSS client = new OSSClientBuilder().build(store.getEndpoint(), stores.username(store), stores.secret(store));
        try (OSSObject object = client.getObject(store.getBucket(), path);
             InputStream input = object.getObjectContent()) {
            long contentLength = object.getObjectMetadata().getContentLength();
            if (contentLength > MAX_PACKAGE_BYTES) {
                throw new IllegalStateException("Package file exceeds the 64 MiB download limit");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    contentLength > 0 ? (int) Math.min(contentLength, 64 * 1024L) : 8192);
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_PACKAGE_BYTES) {
                    throw new IllegalStateException("Package file exceeds the 64 MiB download limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException) throw (IllegalStateException) ex;
            throw new IllegalStateException("Failed to download package from OSS repository", ex);
        } finally {
            client.shutdown();
        }
    }

    private byte[] downloadGitLabGenericFile(ArtifactStoreEntity store, String packageName, String version,
                                              String fileName) {
        GitLabLocation location = gitLabLocation(store);
        if (!"projects".equals(location.scope)) {
            throw new IllegalStateException(
                    "Java GitLab downloads require a project package URL, not a group package URL");
        }
        String downloadUrl = location.apiBase + "/projects/" + location.resourceId + "/packages/generic/"
                + url(packageName) + "/" + url(StringUtils.hasText(version) ? version : "unspecified")
                + "/" + url(fileName);
        return downloadBinary(downloadUrl, gitLabHeaders(store), "GitLab generic package");
    }

    private byte[] downloadPythonSimpleFile(ArtifactStoreEntity store, String packageName, String fileName) {
        URI projectUri = simpleProjectUri(store, packageName);
        Map<String, List<String>> indexHeaders = new LinkedHashMap<String, List<String>>();
        indexHeaders.put("Accept", Collections.singletonList("text/html"));
        addBasicAuthorization(indexHeaders, store);
        RuntimeEndpointHttpClient.Response index = executeDownload(
                projectUri.toString(), indexHeaders, MAX_INDEX_BYTES, "Python Simple Index");
        String href = findSimpleFileHref(
                new String(index.getBody(), StandardCharsets.UTF_8), projectUri, fileName);
        if (!StringUtils.hasText(href)) {
            throw new IllegalStateException(
                    "Package file is not present in the repository Simple Index: " + fileName);
        }
        URI fileUri;
        try {
            fileUri = projectUri.resolve(href);
            String withoutFragment = fileUri.toString();
            int fragmentIndex = withoutFragment.indexOf('#');
            if (fragmentIndex >= 0) withoutFragment = withoutFragment.substring(0, fragmentIndex);
            fileUri = URI.create(withoutFragment);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Repository Simple Index returned an invalid package file URL", ex);
        }
        Map<String, List<String>> fileHeaders = new LinkedHashMap<String, List<String>>();
        if (sameOrigin(projectUri, fileUri)) {
            addBasicAuthorization(fileHeaders, store);
        }
        return downloadBinary(fileUri.toString(), fileHeaders, "Python package");
    }

    private URI simpleProjectUri(ArtifactStoreEntity store, String packageName) {
        boolean configuredSimple = StringUtils.hasText(store.getSimpleIndexUrl());
        String configured = configuredSimple ? store.getSimpleIndexUrl() : store.getEndpoint();
        if (!StringUtils.hasText(configured)) {
            throw new IllegalStateException("Repository Simple Index URL is not configured");
        }
        String base = configured.trim().replaceAll("/+$", "");
        URI baseUri;
        try {
            baseUri = URI.create(base);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Repository Simple Index URL is invalid", ex);
        }
        String path = baseUri.getPath() == null ? "" : baseUri.getPath().toLowerCase(Locale.ROOT);
        if (!configuredSimple && "PYPISERVER".equalsIgnoreCase(store.getProvider())
                && !path.endsWith("/simple")) {
            base += "/simple";
        }
        return URI.create(base + "/" + url(normalize(packageName)) + "/");
    }

    private String findSimpleFileHref(String html, URI projectUri, String requestedFileName) {
        Matcher matcher = SIMPLE_LINK.matcher(html == null ? "" : html);
        while (matcher.find()) {
            String href = htmlDecode(matcher.group(2)).trim();
            if (!StringUtils.hasText(href)) continue;
            try {
                URI candidate = projectUri.resolve(href);
                if (requestedFileName.equals(decodedFileName(candidate))) {
                    return href;
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed unrelated links and continue looking for the exact distribution filename.
            }
            String linkText = htmlDecode(matcher.group(3).replaceAll("<[^>]+>", "")).trim();
            if (requestedFileName.equals(linkText)) {
                return href;
            }
        }
        return null;
    }

    private String decodedFileName(URI uri) {
        String path = uri == null ? null : uri.getRawPath();
        if (!StringUtils.hasText(path)) return "";
        int slash = path.lastIndexOf('/');
        String value = slash >= 0 ? path.substring(slash + 1) : path;
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    private String htmlDecode(String value) {
        return value == null ? "" : value
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private boolean sameOrigin(URI left, URI right) {
        if (left == null || right == null) return false;
        int leftPort = left.getPort() >= 0 ? left.getPort() : defaultPort(left.getScheme());
        int rightPort = right.getPort() >= 0 ? right.getPort() : defaultPort(right.getScheme());
        return safeText(left.getScheme()).equalsIgnoreCase(safeText(right.getScheme()))
                && safeText(left.getHost()).equalsIgnoreCase(safeText(right.getHost()))
                && leftPort == rightPort;
    }

    private int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private byte[] downloadBinary(String url, Map<String, List<String>> headers, String repositoryName) {
        return executeDownload(url, headers, MAX_PACKAGE_BYTES, repositoryName).getBody();
    }

    private RuntimeEndpointHttpClient.Response executeDownload(String url, Map<String, List<String>> headers,
                                                               int maxBytes, String repositoryName) {
        try {
            RuntimeEndpointHttpClient.Response response = httpClient.execute(
                    endpointSecurity.validateRequestTarget(url), "GET", headers, null,
                    10000, 60000, maxBytes);
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                throw new IllegalStateException(
                        repositoryName + " download returned HTTP " + response.getStatusCode()
                                + repositoryDetail(response));
            }
            return response;
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException) throw (IllegalStateException) ex;
            throw new IllegalStateException("Failed to download file from " + repositoryName, ex);
        }
    }

    /**
     * Extracts a concise human-readable detail from an external repository error body so the
     * user sees the underlying cause (for example pypiserver's "Bad filename: ...") instead of
     * only a bare HTTP status. Returns an empty string when no useful detail is present.
     */
    private String repositoryDetail(RuntimeEndpointHttpClient.Response response) {
        byte[] body = response == null ? null : response.getBody();
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return "";
        }
        String detail = extractHtmlDetail(text);
        if (detail == null) {
            detail = text;
        }
        int maxLength = 400;
        String normalized = detail.replaceAll("\\s+", " ").trim();
        if (normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength) + "...";
        }
        return normalized.isEmpty() ? "" : ": " + normalized;
    }

    private String extractHtmlDetail(String html) {
        int preStart = html.indexOf("<pre>");
        int preEnd = html.indexOf("</pre>");
        if (preStart >= 0 && preEnd > preStart) {
            String detail = html.substring(preStart + "<pre>".length(), preEnd).trim();
            if (!detail.isEmpty()) {
                return detail;
            }
        }
        int titleStart = html.indexOf("<title>");
        int titleEnd = html.indexOf("</title>");
        if (titleStart >= 0 && titleEnd > titleStart) {
            String title = html.substring(titleStart + "<title>".length(), titleEnd).replace("Error: ", "").trim();
            if (!title.isEmpty()) {
                return title;
            }
        }
        return null;
    }

    private void publishPypi(ArtifactStoreEntity store, String packageName, String version, String fileName, byte[] bytes) {
        String uploadUrl = StringUtils.hasText(store.getUploadUrl()) ? store.getUploadUrl() : store.getEndpoint();
        String boundary = "----StudioPyPi" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, packageName, version, fileName, bytes);
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        headers.put("Content-Type", java.util.Collections.singletonList("multipart/form-data; boundary=" + boundary));
        addBasicAuthorization(headers, store);
        try {
            RuntimeEndpointHttpClient.Response response = httpClient.execute(
                    endpointSecurity.validateRequestTarget(uploadUrl), "POST", headers, body,
                    10000, 60000, 1024 * 1024);
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                throw new IllegalStateException("Repository returned HTTP " + response.getStatusCode()
                        + repositoryDetail(response));
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to publish package to " + store.getProvider(), ex);
        }
    }

    private void publishGitLabGeneric(ArtifactStoreEntity store, String packageName, String version,
                                      String fileName, byte[] bytes) {
        GitLabLocation location = gitLabLocation(store);
        if (!"projects".equals(location.scope)) {
            throw new IllegalStateException(
                    "Java GitLab publishing requires a project package URL, not a group package URL");
        }
        String uploadUrl = location.apiBase + "/projects/" + location.resourceId + "/packages/generic/"
                + url(packageName) + "/" + url(StringUtils.hasText(version) ? version : "unspecified")
                + "/" + url(fileName);
        Map<String, List<String>> headers = gitLabHeaders(store);
        headers.put("Content-Type", Collections.singletonList("application/octet-stream"));
        try {
            RuntimeEndpointHttpClient.Response response = httpClient.execute(
                    endpointSecurity.validateRequestTarget(uploadUrl), "PUT", headers, bytes,
                    10000, 60000, 1024 * 1024);
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                throw new IllegalStateException("GitLab generic package upload returned HTTP "
                        + response.getStatusCode());
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to publish Java package to GitLab", ex);
        }
    }

    private void deleteOssFiles(ArtifactStoreEntity store, String packageName, String version, List<String> fileNames) {
        if (fileNames == null || fileNames.isEmpty()) return;
        String path = join(store.getRootPrefix(), normalize(packageName), safe(version));
        OSS client = new OSSClientBuilder().build(store.getEndpoint(), stores.username(store), stores.secret(store));
        try {
            for (String fileName : fileNames) {
                if (StringUtils.hasText(fileName)) client.deleteObject(store.getBucket(), join(path, fileName));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to delete package from OSS repository", ex);
        } finally {
            client.shutdown();
        }
    }

    private void deleteGitLabPackageVersion(ArtifactStoreEntity store, String packageName, String version) {
        deleteGitLabPackageVersion(store, packageName, version, "pypi");
    }

    private void deleteGitLabPackageVersion(ArtifactStoreEntity store, String packageName,
                                            String version, String packageType) {
        GitLabLocation location = gitLabLocation(store);
        Map<String, List<String>> headers = gitLabHeaders(store);
        String listUrl = location.apiBase + "/" + location.scope + "/" + location.resourceId + "/packages"
                + "?package_type=" + url(packageType) + "&package_name=" + url(packageName)
                + "&package_version=" + url(version) + "&per_page=100";
        try {
            RuntimeEndpointHttpClient.Response response = execute(listUrl, "GET", headers);
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                throw new IllegalStateException("GitLab package query returned HTTP " + response.getStatusCode());
            }
            JsonNode packages = objectMapper.readTree(response.getBody());
            if (!packages.isArray()) throw new IllegalStateException("GitLab package query returned an invalid response");
            List<String> deleteUrls = new ArrayList<String>();
            for (JsonNode item : packages) {
                if (!packageType.equalsIgnoreCase(item.path("package_type").asText())
                        || !normalize(packageName).equals(normalize(item.path("name").asText()))
                        || !trim(version).equals(trim(item.path("version").asText()))) continue;
                String deleteUrl = gitLabDeleteUrl(location, item);
                if (StringUtils.hasText(deleteUrl)) deleteUrls.add(deleteUrl);
            }
            for (String deleteUrl : deleteUrls) {
                RuntimeEndpointHttpClient.Response deletion = execute(deleteUrl, "DELETE", headers);
                if (deletion.getStatusCode() != 204 && deletion.getStatusCode() != 404) {
                    throw new IllegalStateException("GitLab package deletion returned HTTP " + deletion.getStatusCode());
                }
            }
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException) throw (IllegalStateException) ex;
            throw new IllegalStateException("Failed to delete package version from GitLab", ex);
        }
    }

    private RuntimeEndpointHttpClient.Response execute(String url, String method,
                                                       Map<String, List<String>> headers) throws Exception {
        return httpClient.execute(endpointSecurity.validateRequestTarget(url), method, headers,
                null, 10000, 30000, 4 * 1024 * 1024);
    }

    private GitLabLocation gitLabLocation(ArtifactStoreEntity store) {
        String repositoryUrl = StringUtils.hasText(store.getUploadUrl()) ? store.getUploadUrl() : store.getEndpoint();
        URI uri;
        try {
            uri = URI.create(repositoryUrl);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("GitLab upload URL is invalid", ex);
        }
        Matcher matcher = GITLAB_PYPI_URL.matcher(uri.getRawPath());
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "GitLab upload URL must end with /api/v4/projects/{id}/packages/pypi "
                            + "or /api/v4/groups/{id}/-/packages/pypi");
        }
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        return new GitLabLocation(origin, origin + matcher.group(1).replaceAll("/+$", ""),
                matcher.group(2).toLowerCase(Locale.ROOT), matcher.group(3));
    }

    private String gitLabDeleteUrl(GitLabLocation location, JsonNode item) {
        if ("projects".equals(location.scope)) {
            return location.apiBase + "/projects/" + location.resourceId + "/packages/" + item.path("id").asLong();
        }
        String deletePath = item.path("_links").path("delete_api_path").asText();
        if (StringUtils.hasText(deletePath) && deletePath.startsWith("/api/")) return location.origin + deletePath;
        if (item.hasNonNull("project_id")) {
            return location.apiBase + "/projects/" + item.path("project_id").asLong()
                    + "/packages/" + item.path("id").asLong();
        }
        throw new IllegalStateException(
                "GitLab group package response does not expose a deletable project package path");
    }

    private Map<String, List<String>> gitLabHeaders(ArtifactStoreEntity store) {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        headers.put("Accept", Collections.singletonList("application/json"));
        addBasicAuthorization(headers, store);
        String username = stores.username(store);
        String secret = stores.secret(store);
        if (StringUtils.hasText(secret)) {
            String tokenHeader = "gitlab-ci-token".equalsIgnoreCase(username) ? "JOB-TOKEN" : "PRIVATE-TOKEN";
            headers.put(tokenHeader, Collections.singletonList(secret));
        }
        return headers;
    }

    private void addBasicAuthorization(Map<String, List<String>> headers, ArtifactStoreEntity store) {
        String username = stores.username(store);
        String secret = stores.secret(store);
        if (StringUtils.hasText(username) || StringUtils.hasText(secret)) {
            String token = Base64.getEncoder().encodeToString(
                    ((username == null ? "" : username) + ":" + (secret == null ? "" : secret))
                            .getBytes(StandardCharsets.UTF_8));
            headers.put("Authorization", Collections.singletonList("Basic " + token));
        }
    }

    private byte[] multipart(String boundary, String name, String version, String fileName, byte[] content) {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try {
            field(output, boundary, ":action", "file_upload");
            field(output, boundary, "protocol_version", "1");
            field(output, boundary, "name", name);
            field(output, boundary, "version", version);
            field(output, boundary, "filetype", fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".whl") ? "bdist_wheel" : "sdist");
            field(output, boundary, "pyversion", fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".whl") ? "py3" : "source");
            field(output, boundary, "sha256_digest", sha256(content));
            output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"content\"; filename=\""
                    + fileName.replace("\"", "") + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.write(content);
            output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return output.toByteArray();
        } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
    }
    private void field(java.io.ByteArrayOutputStream output, String boundary, String name, String value) throws java.io.IOException {
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\n\r\n" + (value == null ? "" : value) + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
    private String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }
    private String url(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }
    private String sha256(byte[] content) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder value = new StringBuilder();
            for (byte item : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", item));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private String safe(String value) { return StringUtils.hasText(value) ? value.replaceAll("[^A-Za-z0-9._-]", "_") : "unknown"; }
    private String trim(String value) { return value == null ? "" : value.trim(); }
    private String join(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) if (StringUtils.hasText(value)) {
            if (result.length() > 0) result.append('/');
            result.append(value.replace('\\', '/').replaceAll("^/+|/+$", ""));
        }
        return result.toString();
    }

    private static final class GitLabLocation {
        private final String origin;
        private final String apiBase;
        private final String scope;
        private final String resourceId;

        private GitLabLocation(String origin, String apiBase, String scope, String resourceId) {
            this.origin = origin;
            this.apiBase = apiBase;
            this.scope = scope;
            this.resourceId = resourceId;
        }
    }
}
