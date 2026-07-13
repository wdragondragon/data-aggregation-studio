package com.jdragon.studio.test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.HttpReaderOptionSecurityService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpReaderOptionSecurityServiceTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldRemoveOrphanedInheritanceMarkersWhenModelDefaultsAreUnavailable() throws Exception {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> overrides = new LinkedHashMap<String, Object>();
        overrides.put("header", "{\"Authorization\":\"__STUDIO_HTTP_READER_INHERITED_VALUE__\",\"X-Trace\":\"trace-1\"}");
        overrides.put("requestBody", "<Envelope><Header><token>__STUDIO_HTTP_READER_INHERITED_VALUE__</token></Header>"
                + "<Body><password>__STUDIO_HTTP_READER_REMOVED_VALUE__</password></Body></Envelope>");

        Map<String, Object> masked = service.maskReaderOptionOverridesForView(
                overrides, new LinkedHashMap<String, Object>());

        assertThat(jsonObject(masked.get("header")))
                .containsEntry("X-Trace", "trace-1")
                .doesNotContainKey("Authorization");
        assertThat(String.valueOf(masked.get("requestBody")))
                .contains("Envelope")
                .doesNotContain("token")
                .doesNotContain("password");
        assertThat(String.valueOf(masked)).doesNotContain("__STUDIO_HTTP_READER_");
    }

    @Test
    void shouldEncryptMaskDecryptAndPreserveMaskedHttpReaderSecrets() throws Exception {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> metadata = metadata(
                "{\"Authorization\":\"Bearer top-secret\",\"X-Trace\":\"trace-1\"}",
                "{\"customer_id\":\"C001\",\"api_token\":\"query-secret\"}",
                "{\"filter\":{\"password\":\"body-secret\",\"status\":\"ACTIVE\"}}");

        Map<String, Object> encrypted = service.encryptTechnicalMetadata(metadata, null);
        Map<String, Object> encryptedOptions = readerOptions(encrypted);
        assertThat(String.valueOf(encryptedOptions.get("header")))
                .contains("ENC(")
                .doesNotContain("top-secret");
        assertThat(String.valueOf(encryptedOptions.get("params")))
                .contains("ENC(")
                .doesNotContain("query-secret");
        assertThat(String.valueOf(encryptedOptions.get("requestBody")))
                .contains("ENC(")
                .doesNotContain("body-secret");

        Map<String, Object> masked = service.maskTechnicalMetadata(encrypted);
        assertThat(String.valueOf(readerOptions(masked).get("header")))
                .contains("Be****et")
                .doesNotContain("top-secret");

        Map<String, Object> savedAgain = service.encryptTechnicalMetadata(masked, encrypted);
        assertThat(savedAgain).isEqualTo(encrypted);

        Map<String, Object> decrypted = service.decryptTechnicalMetadata(encrypted);
        assertThat(jsonObject(readerOptions(decrypted).get("header")))
                .containsEntry("Authorization", "Bearer top-secret")
                .containsEntry("X-Trace", "trace-1");
        assertThat(jsonObject(readerOptions(decrypted).get("params")))
                .containsEntry("api_token", "query-secret")
                .containsEntry("customer_id", "C001");
        assertThat(String.valueOf(readerOptions(decrypted).get("requestBody")))
                .contains("body-secret")
                .contains("ACTIVE");
    }

    @Test
    void shouldProtectSensitiveSoapElementsWithoutChangingDynamicValues() {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> metadata = metadata("{}", "{}",
                "<Envelope><Header><token>soap-secret</token></Header>"
                        + "<Body><password>{dyn_md5(value)}</password></Body></Envelope>");

        Map<String, Object> encrypted = service.encryptTechnicalMetadata(metadata, null);
        String encryptedBody = String.valueOf(readerOptions(encrypted).get("requestBody"));
        assertThat(encryptedBody).contains("ENC(").doesNotContain("soap-secret");
        assertThat(encryptedBody).doesNotContain("{dyn_md5(value)}");

        Map<String, Object> masked = service.maskTechnicalMetadata(encrypted);
        assertThat(String.valueOf(readerOptions(masked).get("requestBody")))
                .contains("{dyn_md5(****)}")
                .doesNotContain("value");

        Map<String, Object> decrypted = service.decryptTechnicalMetadata(encrypted);
        assertThat(String.valueOf(readerOptions(decrypted).get("requestBody")))
                .contains("soap-secret")
                .contains("{dyn_md5(value)}");
    }

    @Test
    void shouldEncryptStaticSegmentsAroundDynamicSensitiveValuesAndRestoreThemAfterMaskedSave() throws Exception {
        HttpReaderOptionSecurityService service = service();
        String dynamicToken = "{dyn_from_http_token(GET,http://auth.example.com,,,data.token)}";
        Map<String, Object> metadata = metadata(
                "{\"Authorization\":\"Bearer " + dynamicToken + "\",\"api_token\":\"{dyn_page}\"}",
                "{}",
                "{\"password\":\"prefix {{runtime_secret}} suffix\"}");

        Map<String, Object> encrypted = service.encryptTechnicalMetadata(metadata, null);
        String encryptedHeader = String.valueOf(readerOptions(encrypted).get("header"));
        String encryptedBody = String.valueOf(readerOptions(encrypted).get("requestBody"));
        assertThat(encryptedHeader)
                .contains("ENC(")
                .doesNotContain(dynamicToken)
                .doesNotContain("{dyn_page}")
                .doesNotContain("Bearer ");
        assertThat(encryptedBody)
                .contains("ENC(")
                .doesNotContain("{{runtime_secret}}")
                .doesNotContain("prefix ")
                .doesNotContain(" suffix");

        Map<String, Object> masked = service.maskTechnicalMetadata(encrypted);
        assertThat(String.valueOf(readerOptions(masked).get("header")))
                .contains("{dyn_from_http_token(****)}")
                .contains("{dyn_page}")
                .doesNotContain("auth.example.com")
                .doesNotContain("Bearer ");
        assertThat(String.valueOf(readerOptions(masked).get("requestBody")))
                .contains("{{****}}")
                .doesNotContain("runtime_secret");
        assertThat(service.encryptTechnicalMetadata(masked, encrypted)).isEqualTo(encrypted);

        Map<String, Object> decrypted = service.decryptTechnicalMetadata(encrypted);
        assertThat(jsonObject(readerOptions(decrypted).get("header")))
                .containsEntry("Authorization", "Bearer " + dynamicToken)
                .containsEntry("api_token", "{dyn_page}");
        assertThat(jsonObject(readerOptions(decrypted).get("requestBody")))
                .containsEntry("password", "prefix {{runtime_secret}} suffix");
    }

    @Test
    void shouldMaskDynamicArgumentsContainingNestedBraces() {
        HttpReaderOptionSecurityService service = service();
        String dynamicToken = "{dyn_from_http_token(POST,http://auth.example.com,Authorization={%header%},payload={\"secret\":\"raw-secret\"},data.token)}";
        Map<String, Object> metadata = metadata(
                "{\"Authorization\":\"Bearer " + dynamicToken.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}",
                "{}",
                "");

        Map<String, Object> masked = service.maskTechnicalMetadata(
                service.encryptTechnicalMetadata(metadata, null));
        String maskedHeader = String.valueOf(readerOptions(masked).get("header"));

        assertThat(maskedHeader)
                .contains("{dyn_from_http_token(****)}")
                .doesNotContain("auth.example.com")
                .doesNotContain("{%header%}")
                .doesNotContain("raw-secret");
    }

    @Test
    void shouldEncryptAndMaskOpaqueRequestBody() {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> metadata = metadata("{}", "{}",
                "password=form-secret&token=opaque-token&query=query%20Payload");

        Map<String, Object> encrypted = service.encryptTechnicalMetadata(metadata, null);
        String encryptedBody = String.valueOf(readerOptions(encrypted).get("requestBody"));
        assertThat(encryptedBody)
                .startsWith("ENC(")
                .doesNotContain("form-secret")
                .doesNotContain("opaque-token");

        Map<String, Object> masked = service.maskTechnicalMetadata(encrypted);
        assertThat(String.valueOf(readerOptions(masked).get("requestBody")))
                .doesNotContain("form-secret")
                .doesNotContain("opaque-token");
        assertThat(String.valueOf(readerOptions(service.decryptTechnicalMetadata(encrypted)).get("requestBody")))
                .isEqualTo("password=form-secret&token=opaque-token&query=query%20Payload");
    }

    @Test
    void shouldRejectInvalidJsonInsteadOfSavingSensitivePlaintext() {
        HttpReaderOptionSecurityService service = service();

        assertThatThrownBy(() -> service.encryptTechnicalMetadata(
                metadata("{\"Authorization\":\"plain-secret\"", "{}", "{}"), null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid JSON")
                .hasMessageContaining("header")
                .hasMessageNotContaining("plain-secret");
    }

    @Test
    void shouldRejectNonObjectHeaderAndParamsInsteadOfPersistingPlaintext() {
        HttpReaderOptionSecurityService service = service();

        assertThatThrownBy(() -> service.encryptTechnicalMetadata(
                metadata("Authorization: Bearer plain-secret", "{}", "{}"), null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid JSON object")
                .hasMessageContaining("header")
                .hasMessageNotContaining("plain-secret");
        assertThatThrownBy(() -> service.prepareReaderOptionOverrides(
                readerOptions(metadata("{}", "[\"plain-secret\"]", "{}")),
                new LinkedHashMap<String, Object>()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid JSON object")
                .hasMessageContaining("params")
                .hasMessageNotContaining("plain-secret");
    }

    @Test
    void shouldMaskLegacyNonObjectHeaderBeforeReturningIt() {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> masked = service.maskTechnicalMetadata(
                metadata("Authorization: Bearer legacy-secret", "{}", "{}"));

        assertThat(String.valueOf(readerOptions(masked).get("header")))
                .doesNotContain("legacy-secret")
                .contains("****");
    }

    @Test
    void shouldRejectInvalidXmlInsteadOfSavingSensitivePlaintext() {
        HttpReaderOptionSecurityService service = service();

        assertThatThrownBy(() -> service.encryptTechnicalMetadata(
                metadata("{}", "{}", "<Envelope><token>plain-secret</Envelope>"), null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid XML")
                .hasMessageContaining("requestBody")
                .hasMessageNotContaining("plain-secret");
    }

    @Test
    void shouldPruneMaskedInheritedLeavesWhenOnlySiblingFieldsChange() throws Exception {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> inherited = metadata(
                "{\"Authorization\":\"Bearer header-secret\",\"X-Trace\":\"trace-1\"}",
                "{\"api_token\":\"params-secret\",\"page\":1}",
                "{\"credentials\":{\"password\":\"body-secret\"},\"status\":\"ACTIVE\"}");
        Map<String, Object> masked = service.maskTechnicalMetadata(inherited);
        Map<String, Object> submittedOptions = readerOptions(masked);
        submittedOptions.put("header", replaceJsonValue(submittedOptions.get("header"), "X-Trace", "trace-2"));
        submittedOptions.put("params", replaceJsonValue(submittedOptions.get("params"), "page", Integer.valueOf(2)));
        submittedOptions.put("requestBody", replaceJsonValue(submittedOptions.get("requestBody"), "status", "PAUSED"));

        Map<String, Object> prepared = service.prepareReaderOptionOverrides(submittedOptions, inherited);
        Map<String, Object> preparedHeader = jsonObject(prepared.get("header"));
        Map<String, Object> preparedParams = jsonObject(prepared.get("params"));
        Map<String, Object> preparedBody = jsonObject(prepared.get("requestBody"));
        assertThat(preparedHeader)
                .containsEntry("X-Trace", "trace-2")
                .doesNotContainKey("Authorization");
        assertThat(preparedParams)
                .containsEntry("page", Integer.valueOf(2))
                .doesNotContainKey("api_token");
        assertThat(castMap(preparedBody.get("credentials"))).doesNotContainKey("password");
        assertThat(preparedBody).containsEntry("status", "PAUSED");
        assertThat(String.valueOf(prepared))
                .doesNotContain("header-secret")
                .doesNotContain("params-secret")
                .doesNotContain("body-secret")
                .doesNotContain("****");

        Map<String, Object> resolved = service.resolveReaderOptionOverrides(prepared, inherited);
        assertThat(jsonObject(resolved.get("header")))
                .containsEntry("Authorization", "Bearer header-secret")
                .containsEntry("X-Trace", "trace-2");
        assertThat(jsonObject(resolved.get("params")))
                .containsEntry("api_token", "params-secret")
                .containsEntry("page", Integer.valueOf(2));
        Map<String, Object> resolvedBody = jsonObject(resolved.get("requestBody"));
        assertThat(castMap(resolvedBody.get("credentials"))).containsEntry("password", "body-secret");
        assertThat(resolvedBody).containsEntry("status", "PAUSED");

        Map<String, Object> view = service.maskReaderOptionOverridesForView(prepared, inherited);
        assertThat(String.valueOf(view))
                .contains("Be****et")
                .contains("pa****et")
                .contains("bo****et")
                .doesNotContain("header-secret")
                .doesNotContain("params-secret")
                .doesNotContain("body-secret");
    }

    @Test
    void shouldMergeNewModelDefaultsIntoExistingStructuredOverrides() throws Exception {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> initialModel = metadata(
                "{\"X-Base\":\"base-1\"}",
                "{\"region\":\"east\"}",
                "{\"filter\":{\"status\":\"ACTIVE\"}}");
        Map<String, Object> submitted = readerOptions(service.maskTechnicalMetadata(initialModel));
        submitted.put("header", "{\"X-Base\":\"base-1\",\"X-Task\":\"task-1\"}");
        submitted.put("params", "{\"region\":\"west\"}");
        submitted.put("requestBody", "{\"filter\":{\"status\":\"PAUSED\"}}");
        Map<String, Object> prepared = service.prepareReaderOptionOverrides(submitted, initialModel);

        Map<String, Object> updatedModel = metadata(
                "{\"X-Base\":\"base-1\",\"X-Tenant\":\"tenant-1\"}",
                "{\"region\":\"east\",\"language\":\"zh-CN\"}",
                "{\"filter\":{\"status\":\"ACTIVE\",\"channel\":\"WEB\"}}");
        Map<String, Object> resolved = service.resolveReaderOptionOverrides(prepared, updatedModel);

        assertThat(jsonObject(resolved.get("header")))
                .containsEntry("X-Base", "base-1")
                .containsEntry("X-Task", "task-1")
                .containsEntry("X-Tenant", "tenant-1");
        assertThat(jsonObject(resolved.get("params")))
                .containsEntry("region", "west")
                .containsEntry("language", "zh-CN");
        assertThat(castMap(jsonObject(resolved.get("requestBody")).get("filter")))
                .containsEntry("status", "PAUSED")
                .containsEntry("channel", "WEB");
    }

    @Test
    void shouldPruneAndRestoreMaskedInheritedXmlLeafWhenSiblingChanges() {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> inherited = metadata("{}", "{}",
                "<Envelope><Header><token>soap-secret</token><trace>trace-1</trace></Header></Envelope>");
        Map<String, Object> masked = service.maskTechnicalMetadata(inherited);
        Map<String, Object> submittedOptions = readerOptions(masked);
        submittedOptions.put("requestBody", String.valueOf(submittedOptions.get("requestBody"))
                .replace("trace-1", "trace-2"));

        Map<String, Object> prepared = service.prepareReaderOptionOverrides(submittedOptions, inherited);
        assertThat(String.valueOf(prepared.get("requestBody")))
                .contains("trace-2")
                .doesNotContain("soap-secret")
                .doesNotContain("****")
                .contains("<token>__STUDIO_HTTP_READER_INHERITED_VALUE__</token>");

        Map<String, Object> resolved = service.resolveReaderOptionOverrides(prepared, inherited);
        String resolvedBody = String.valueOf(resolved.get("requestBody"));
        assertThat(resolvedBody)
                .contains("<token>soap-secret</token>")
                .contains("trace-2");
        assertThat(resolvedBody.indexOf("<token>"))
                .isLessThan(resolvedBody.indexOf("<trace>"));
    }

    @Test
    void shouldRestoreMaskedItemsInsidePartiallyChangedSensitiveList() throws Exception {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> inherited = metadata("{}", "{}",
                "{\"accessTokens\":[\"token-one\",\"token-two\"]}");
        Map<String, Object> masked = service.maskTechnicalMetadata(inherited);
        Map<String, Object> submittedOptions = readerOptions(masked);
        Map<String, Object> submittedBody = jsonObject(submittedOptions.get("requestBody"));
        @SuppressWarnings("unchecked")
        java.util.List<Object> tokens = (java.util.List<Object>) submittedBody.get("accessTokens");
        tokens.set(1, "task-token");
        submittedOptions.put("requestBody", new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(submittedBody));

        Map<String, Object> prepared = service.prepareReaderOptionOverrides(submittedOptions, inherited);
        assertThat(String.valueOf(prepared.get("requestBody")))
                .contains("__STUDIO_HTTP_READER_INHERITED_VALUE__")
                .doesNotContain("token-one")
                .doesNotContain("token-two")
                .doesNotContain("task-token");

        Map<String, Object> resolved = service.resolveReaderOptionOverrides(prepared, inherited);
        @SuppressWarnings("unchecked")
        java.util.List<Object> resolvedTokens = (java.util.List<Object>) jsonObject(
                resolved.get("requestBody")).get("accessTokens");
        assertThat(resolvedTokens).containsExactly("token-one", "task-token");
    }

    @Test
    void shouldKeepExplicitlyRemovedInheritedSensitiveKeysDeleted() throws Exception {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> inherited = metadata(
                "{\"Authorization\":\"Bearer header-secret\",\"X-Trace\":\"trace-1\"}",
                "{\"api_token\":\"params-secret\",\"region\":\"east\"}",
                "{}");
        Map<String, Object> overrides = new LinkedHashMap<String, Object>();
        overrides.put("header", "{\"Authorization\":\""
                + HttpReaderOptionSecurityService.REMOVED_VALUE_MARKER + "\",\"X-Trace\":\"trace-1\"}");
        overrides.put("params", "{\"api_token\":\""
                + HttpReaderOptionSecurityService.REMOVED_VALUE_MARKER + "\",\"region\":\"east\"}");

        Map<String, Object> prepared = service.prepareReaderOptionOverrides(overrides, inherited);
        Map<String, Object> resolved = service.resolveReaderOptionOverrides(prepared, inherited);

        assertThat(jsonObject(resolved.get("header")))
                .doesNotContainKey("Authorization")
                .containsEntry("X-Trace", "trace-1");
        assertThat(jsonObject(resolved.get("params")))
                .doesNotContainKey("api_token")
                .containsEntry("region", "east");
        assertThat(String.valueOf(prepared)).doesNotContain("header-secret").doesNotContain("params-secret");
    }

    @Test
    void shouldMatchInheritedSoapSecretsByNamespaceUri() {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> inherited = metadata("{}", "{}",
                "<Envelope xmlns:a=\"urn:first\" xmlns:b=\"urn:second\">"
                        + "<a:token>first-secret</a:token><b:token>second-secret</b:token></Envelope>");
        Map<String, Object> masked = service.maskTechnicalMetadata(inherited);
        String maskedBody = String.valueOf(readerOptions(masked).get("requestBody"));
        int firstStart = maskedBody.indexOf("<a:token>");
        int firstEnd = maskedBody.indexOf("</a:token>") + "</a:token>".length();
        int secondStart = maskedBody.indexOf("<b:token>");
        int secondEnd = maskedBody.indexOf("</b:token>") + "</b:token>".length();
        String submittedBody = maskedBody.substring(0, firstStart)
                + maskedBody.substring(secondStart, secondEnd)
                + "<a:token>task-secret</a:token>"
                + maskedBody.substring(secondEnd);
        Map<String, Object> overrides = new LinkedHashMap<String, Object>();
        overrides.put("requestBody", submittedBody);

        Map<String, Object> prepared = service.prepareReaderOptionOverrides(overrides, inherited);
        String resolved = String.valueOf(service.resolveReaderOptionOverrides(prepared, inherited).get("requestBody"));

        assertThat(resolved)
                .contains("<a:token>task-secret</a:token>")
                .contains("<b:token>second-secret</b:token>")
                .doesNotContain("<b:token>first-secret</b:token>");
    }

    @Test
    void shouldKeepRemovedInheritedSoapSecretsDeleted() {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> inherited = metadata("{}", "{}",
                "<Envelope apiToken=\"attribute-secret\"><Auth><token>node-secret</token><trace>trace-1</trace></Auth></Envelope>");
        String maskedBody = String.valueOf(readerOptions(service.maskTechnicalMetadata(inherited)).get("requestBody"));
        String submittedBody = maskedBody
                .replaceFirst("\\s+apiToken=\"[^\"]*\"", "")
                .replaceFirst("<token>.*?</token>", "");
        Map<String, Object> overrides = new LinkedHashMap<String, Object>();
        overrides.put("requestBody", submittedBody);

        Map<String, Object> prepared = service.prepareReaderOptionOverrides(overrides, inherited);
        String resolved = String.valueOf(service.resolveReaderOptionOverrides(prepared, inherited).get("requestBody"));

        assertThat(resolved)
                .doesNotContain("apiToken")
                .doesNotContain("node-secret")
                .doesNotContain("<token>")
                .contains("<trace>trace-1</trace>");
        assertThat(String.valueOf(prepared))
                .doesNotContain("attribute-secret")
                .doesNotContain("node-secret");
    }

    @Test
    void shouldTreatCommonAuthAndSessionHeadersAsSensitive() throws Exception {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> encrypted = service.encryptTechnicalMetadata(metadata(
                "{\"X-Auth\":\"auth-secret\",\"Authentication\":\"authentication-secret\",\"Session-Id\":\"session-secret\"}",
                "{}", "{}"), null);

        String stored = String.valueOf(readerOptions(encrypted).get("header"));
        assertThat(stored)
                .contains("ENC(")
                .doesNotContain("auth-secret")
                .doesNotContain("authentication-secret")
                .doesNotContain("session-secret");
        assertThat(jsonObject(readerOptions(service.decryptTechnicalMetadata(encrypted)).get("header")))
                .containsEntry("X-Auth", "auth-secret")
                .containsEntry("Authentication", "authentication-secret")
                .containsEntry("Session-Id", "session-secret");
    }

    @Test
    void shouldPreserveCorrectSensitiveListValuesAfterDeleteAndReorder() throws Exception {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> inherited = metadata("{}", "{}",
                "{\"accounts\":[{\"id\":\"A\",\"token\":\"token-a\"},{\"id\":\"B\",\"token\":\"token-b\"},{\"id\":\"C\",\"token\":\"token-c\"}]}");
        Map<String, Object> submitted = readerOptions(service.maskTechnicalMetadata(inherited));
        Map<String, Object> body = jsonObject(submitted.get("requestBody"));
        @SuppressWarnings("unchecked")
        java.util.List<Object> accounts = (java.util.List<Object>) body.get("accounts");
        Object accountB = accounts.remove(1);
        accounts.add(0, accountB);
        submitted.put("requestBody", OBJECT_MAPPER.writeValueAsString(body));

        Map<String, Object> prepared = service.prepareReaderOptionOverrides(submitted, inherited);
        Map<String, Object> resolved = jsonObject(service.resolveReaderOptionOverrides(prepared, inherited).get("requestBody"));
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> resolvedAccounts = (java.util.List<Map<String, Object>>) resolved.get("accounts");

        assertThat(resolvedAccounts).extracting(item -> item.get("id"))
                .containsExactly("B", "A", "C");
        assertThat(resolvedAccounts).extracting(item -> item.get("token"))
                .containsExactly("token-b", "token-a", "token-c");
        assertThat(String.valueOf(prepared))
                .doesNotContain("token-a")
                .doesNotContain("token-b")
                .doesNotContain("token-c");
    }

    @Test
    void shouldKeepCorrectDuplicateXmlSecretAfterDeletingFirstSibling() {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> inherited = metadata("{}", "{}",
                "<Envelope><token>first-secret</token><token>second-secret</token></Envelope>");
        String maskedBody = String.valueOf(readerOptions(service.maskTechnicalMetadata(inherited)).get("requestBody"));
        String submittedBody = maskedBody.replaceFirst("<token>.*?</token>", "");
        Map<String, Object> overrides = new LinkedHashMap<String, Object>();
        overrides.put("requestBody", submittedBody);

        Map<String, Object> prepared = service.prepareReaderOptionOverrides(overrides, inherited);
        String resolved = String.valueOf(service.resolveReaderOptionOverrides(prepared, inherited).get("requestBody"));

        assertThat(resolved)
                .containsOnlyOnce("<token>second-secret</token>")
                .doesNotContain("first-secret");
        assertThat(String.valueOf(prepared))
                .doesNotContain("first-secret")
                .doesNotContain("second-secret");
    }

    @Test
    void shouldPreserveExistingTaskSecretWhenMaskedTaskIsSavedWithoutChanges() throws Exception {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> inherited = metadata(
                "{\"Authorization\":\"Bearer model-secret\",\"X-Trace\":\"model\"}",
                "{}", "{}");
        Map<String, Object> originalTaskOptions = new LinkedHashMap<String, Object>();
        originalTaskOptions.put("header",
                "{\"Authorization\":\"Bearer task-secret\",\"X-Trace\":\"task\"}");
        Map<String, Object> storedTaskOptions = service.prepareReaderOptionOverrides(
                originalTaskOptions, inherited);
        Map<String, Object> maskedView = service.maskReaderOptionOverridesForView(
                storedTaskOptions, inherited);

        Map<String, Object> savedAgain = service.prepareReaderOptionOverrides(
                new LinkedHashMap<String, Object>(), inherited, storedTaskOptions);
        Map<String, Object> savedFromMaskedView = service.prepareReaderOptionOverrides(
                maskedView, inherited, storedTaskOptions);

        assertThat(savedAgain).isEqualTo(storedTaskOptions);
        assertThat(savedFromMaskedView).isEqualTo(storedTaskOptions);
        assertThat(jsonObject(service.resolveReaderOptionOverrides(savedAgain, inherited).get("header")))
                .containsEntry("Authorization", "Bearer task-secret")
                .containsEntry("X-Trace", "task");
    }

    @Test
    void shouldUpgradeHistoricalPlainTaskSecretWhenMaskedTaskIsSavedWithoutChanges() throws Exception {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> inherited = metadata("{}", "{}", "{}");
        Map<String, Object> historicalPlainOptions = new LinkedHashMap<String, Object>();
        historicalPlainOptions.put("header", "{\"Authorization\":\"Bearer historical-secret\"}");
        Map<String, Object> maskedView = service.maskReaderOptionOverridesForView(
                historicalPlainOptions, inherited);

        Map<String, Object> saved = service.prepareReaderOptionOverrides(
                maskedView, inherited, historicalPlainOptions);

        assertThat(String.valueOf(saved))
                .contains("ENC(")
                .doesNotContain("historical-secret")
                .doesNotContain("****");
        assertThat(jsonObject(service.resolveReaderOptionOverrides(saved, inherited).get("header")))
                .containsEntry("Authorization", "Bearer historical-secret");
    }

    @Test
    void shouldProtectPwdAliasesInAllStructuredHttpOptions() throws Exception {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> original = metadata(
                "{\"pwd\":\"header-secret\",\"proxy_passwd\":\"proxy-secret\"}",
                "{\"db_pwd\":\"param-secret\"}",
                "{\"credentials\":{\"pwd\":\"body-secret\"}}");

        Map<String, Object> encrypted = service.encryptTechnicalMetadata(original, null);
        Map<String, Object> masked = service.maskTechnicalMetadata(encrypted);

        assertThat(String.valueOf(encrypted))
                .contains("ENC(")
                .doesNotContain("header-secret")
                .doesNotContain("proxy-secret")
                .doesNotContain("param-secret")
                .doesNotContain("body-secret");
        assertThat(String.valueOf(masked))
                .doesNotContain("header-secret")
                .doesNotContain("proxy-secret")
                .doesNotContain("param-secret")
                .doesNotContain("body-secret");
        assertThat(service.decryptTechnicalMetadata(encrypted)).isEqualTo(original);
    }

    @Test
    void shouldPreserveExistingTaskSecretWhenNonSensitiveSiblingChanges() throws Exception {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> inherited = metadata(
                "{\"Authorization\":\"Bearer model-secret\",\"X-Trace\":\"model\"}",
                "{}", "{}");
        Map<String, Object> originalTaskOptions = new LinkedHashMap<String, Object>();
        originalTaskOptions.put("header",
                "{\"Authorization\":\"Bearer task-secret\",\"X-Trace\":\"task-1\"}");
        Map<String, Object> storedTaskOptions = service.prepareReaderOptionOverrides(
                originalTaskOptions, inherited);
        Map<String, Object> submitted = service.maskReaderOptionOverridesForView(
                storedTaskOptions, inherited);
        submitted.put("header", replaceJsonValue(submitted.get("header"), "X-Trace", "task-2"));

        Map<String, Object> saved = service.prepareReaderOptionOverrides(
                submitted, inherited, storedTaskOptions);
        Map<String, Object> resolvedHeader = jsonObject(
                service.resolveReaderOptionOverrides(saved, inherited).get("header"));

        assertThat(resolvedHeader)
                .containsEntry("Authorization", "Bearer task-secret")
                .containsEntry("X-Trace", "task-2");
        assertThat(String.valueOf(saved))
                .doesNotContain("task-secret")
                .doesNotContain("****");
    }

    @Test
    void shouldKeepExplicitTaskOptionWhenItInitiallyEqualsModelDefault() {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> originalModel = metadata("{}", "{}", "{}");
        readerOptions(originalModel).put("pageSize", Integer.valueOf(100));
        Map<String, Object> submittedTaskOptions = new LinkedHashMap<String, Object>();
        submittedTaskOptions.put("pageSize", Integer.valueOf(100));

        Map<String, Object> storedTaskOptions = service.prepareReaderOptionOverrides(
                submittedTaskOptions, originalModel);
        Map<String, Object> changedModel = metadata("{}", "{}", "{}");
        readerOptions(changedModel).put("pageSize", Integer.valueOf(200));
        Map<String, Object> savedAgain = service.prepareReaderOptionOverrides(
                new LinkedHashMap<String, Object>(), changedModel, storedTaskOptions);

        assertThat(storedTaskOptions).containsEntry("pageSize", Integer.valueOf(100));
        assertThat(savedAgain).containsEntry("pageSize", Integer.valueOf(100));
    }

    @Test
    void shouldPreserveModelArraySecretsAfterMaskedDeleteAndReorder() throws Exception {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> original = metadata("{}", "{}",
                "{\"accounts\":[{\"id\":\"A\",\"token\":\"token-a\"},"
                        + "{\"id\":\"B\",\"token\":\"token-b\"},"
                        + "{\"id\":\"C\",\"token\":\"token-c\"}]}");
        Map<String, Object> encrypted = service.encryptTechnicalMetadata(original, null);
        Map<String, Object> submitted = service.maskTechnicalMetadata(encrypted);
        Map<String, Object> body = jsonObject(readerOptions(submitted).get("requestBody"));
        @SuppressWarnings("unchecked")
        java.util.List<Object> accounts = (java.util.List<Object>) body.get("accounts");
        Object accountC = accounts.remove(2);
        accounts.remove(0);
        accounts.add(0, accountC);
        readerOptions(submitted).put("requestBody", OBJECT_MAPPER.writeValueAsString(body));

        Map<String, Object> saved = service.encryptTechnicalMetadata(submitted, encrypted);
        Map<String, Object> resolved = jsonObject(
                readerOptions(service.decryptTechnicalMetadata(saved)).get("requestBody"));
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> resolvedAccounts =
                (java.util.List<Map<String, Object>>) resolved.get("accounts");

        assertThat(resolvedAccounts).extracting(item -> item.get("id"))
                .containsExactly("C", "B");
        assertThat(resolvedAccounts).extracting(item -> item.get("token"))
                .containsExactly("token-c", "token-b");
    }

    @Test
    void shouldPreserveModelDuplicateXmlSecretsAfterMaskedReorder() {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> original = metadata("{}", "{}",
                "<Envelope><account id=\"A\"><token>token-a</token></account>"
                        + "<account id=\"B\"><token>token-b</token></account></Envelope>");
        Map<String, Object> encrypted = service.encryptTechnicalMetadata(original, null);
        Map<String, Object> submitted = service.maskTechnicalMetadata(encrypted);
        String body = String.valueOf(readerOptions(submitted).get("requestBody"));
        int firstStart = body.indexOf("<account");
        int firstEnd = body.indexOf("</account>", firstStart) + "</account>".length();
        int secondStart = body.indexOf("<account", firstEnd);
        int secondEnd = body.indexOf("</account>", secondStart) + "</account>".length();
        readerOptions(submitted).put("requestBody",
                body.substring(0, firstStart)
                        + body.substring(secondStart, secondEnd)
                        + body.substring(firstStart, firstEnd)
                        + body.substring(secondEnd));

        Map<String, Object> saved = service.encryptTechnicalMetadata(submitted, encrypted);
        String resolved = String.valueOf(
                readerOptions(service.decryptTechnicalMetadata(saved)).get("requestBody"));

        assertThat(resolved.indexOf("id=\"B\"")).isLessThan(resolved.indexOf("id=\"A\""));
        assertThat(resolved)
                .contains("id=\"B\"><token>token-b</token>")
                .contains("id=\"A\"><token>token-a</token>");
    }

    @Test
    void shouldUpgradeHistoricalPlaintextModelSecretsWhenMaskedValuesAreResubmitted() {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> historical = metadata(
                "{\"Authorization\":\"Bearer historical-secret\"}",
                "{\"passwd\":\"legacy-password\"}",
                "{\"credentials\":{\"pwd\":\"legacy-body-password\"}}");
        Map<String, Object> submitted = service.maskTechnicalMetadata(historical);

        Map<String, Object> saved = service.encryptTechnicalMetadata(submitted, historical);
        Map<String, Object> resolved = service.decryptTechnicalMetadata(saved);

        assertThat(String.valueOf(readerOptions(saved).get("header")))
                .contains("ENC(")
                .doesNotContain("historical-secret");
        assertThat(readerOptions(resolved))
                .containsEntry("header", "{\"Authorization\":\"Bearer historical-secret\"}")
                .containsEntry("params", "{\"passwd\":\"legacy-password\"}")
                .containsEntry("requestBody", "{\"credentials\":{\"pwd\":\"legacy-body-password\"}}");
    }

    @Test
    void shouldKeepNonStringJsonValuesTypedEvenWhenTheirKeysLookSensitive() throws Exception {
        HttpReaderOptionSecurityService service = service();
        Map<String, Object> original = metadata("{}", "{}",
                "{\"tokenCount\":3,\"passwordExpired\":false,\"token\":\"secret-token\"}");

        Map<String, Object> encrypted = service.encryptTechnicalMetadata(original, null);
        Map<String, Object> resolved = jsonObject(
                readerOptions(service.decryptTechnicalMetadata(encrypted)).get("requestBody"));

        assertThat(resolved.get("tokenCount")).isEqualTo(3);
        assertThat(resolved.get("passwordExpired")).isEqualTo(false);
        assertThat(resolved.get("token")).isEqualTo("secret-token");
    }

    @Test
    void shouldMaskAndRejectCredentialsEmbeddedInHttpPhysicalLocator() {
        HttpReaderOptionSecurityService service = service();
        String locator = "https://user:password@example.test/customers?region=east&access_token=secret#debug";

        assertThat(service.maskSensitiveUrl(locator))
                .isEqualTo("https://****@example.test/customers?region=east&access_token=****#****");
        assertThatThrownBy(() -> service.validatePhysicalLocator(locator))
                .hasMessageContaining("Reader default parameters");
        service.validatePhysicalLocator("/customers?region=east");
    }

    private HttpReaderOptionSecurityService service() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setEncryptionSecret("http-reader-option-security-test");
        return new HttpReaderOptionSecurityService(new EncryptionService(properties));
    }

    private Map<String, Object> metadata(String header, String params, String requestBody) {
        Map<String, Object> readerOptions = new LinkedHashMap<String, Object>();
        readerOptions.put("header", header);
        readerOptions.put("params", params);
        readerOptions.put("requestBody", requestBody);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("readerOptions", readerOptions);
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readerOptions(Map<String, Object> metadata) {
        return (Map<String, Object>) metadata.get("readerOptions");
    }

    private Map<String, Object> jsonObject(Object value) throws Exception {
        return OBJECT_MAPPER.readValue(String.valueOf(value), new TypeReference<Map<String, Object>>() {
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?>
                ? (Map<String, Object>) value
                : new LinkedHashMap<String, Object>();
    }

    private String replaceJsonValue(Object value, String key, Object replacement) throws Exception {
        Map<String, Object> object = jsonObject(value);
        object.put(key, replacement);
        return OBJECT_MAPPER.writeValueAsString(object);
    }
}
