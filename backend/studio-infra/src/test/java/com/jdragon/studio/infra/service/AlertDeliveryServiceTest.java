package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.AlertChannelEntity;
import com.jdragon.studio.infra.entity.AlertDeliveryEntity;
import com.jdragon.studio.infra.mapper.AlertDeliveryMapper;
import com.jdragon.studio.infra.mapper.AlertEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertDeliveryServiceTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(AlertDeliveryEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), AlertDeliveryEntity.class);
        }
    }

    @Test
    void shouldPersistNullFieldsWhenRetryIsRequested() {
        Fixture fixture = fixture();
        AlertDeliveryEntity delivery = delivery("RETRY");
        delivery.setHttpStatus(500);
        delivery.setResponseExcerpt("retry");
        delivery.setErrorMessage("Webhook returned HTTP 500");
        when(fixture.deliveryMapper.selectOne(any())).thenReturn(delivery);

        fixture.service.retry(delivery.getId());

        LambdaUpdateWrapper<AlertDeliveryEntity> update = captureUpdate(fixture.deliveryMapper);
        assertTrue(update.getSqlSet().contains("next_attempt_at"));
        assertTrue(update.getSqlSet().contains("http_status"));
        assertTrue(update.getSqlSet().contains("response_excerpt"));
        assertTrue(update.getSqlSet().contains("error_message"));
        assertNull(delivery.getHttpStatus());
        assertNull(delivery.getResponseExcerpt());
        assertNull(delivery.getErrorMessage());
        verify(fixture.deliveryMapper, never()).updateById(any(AlertDeliveryEntity.class));
    }

    @Test
    void shouldRejectRetryWhenDeliveryWasClaimedConcurrently() {
        Fixture fixture = fixture();
        AlertDeliveryEntity delivery = delivery("RETRY");
        when(fixture.deliveryMapper.selectOne(any())).thenReturn(delivery);
        when(fixture.deliveryMapper.update(isNull(), any())).thenReturn(0);

        assertThrows(StudioException.class, () -> fixture.service.retry(delivery.getId()));
    }

    @Test
    void shouldRejectRetryWhenWebhookChannelNoLongerExists() {
        Fixture fixture = fixture();
        AlertDeliveryEntity delivery = delivery("DEAD");
        delivery.setChannelType("WEBHOOK");
        delivery.setChannelId(50L);
        when(fixture.deliveryMapper.selectOne(any())).thenReturn(delivery);

        StudioException error = assertThrows(StudioException.class, () -> fixture.service.retry(delivery.getId()));

        assertTrue(error.getMessage().contains("Alert channel is missing"));
        verify(fixture.deliveryMapper, never()).update(isNull(), any());
    }

    @Test
    void shouldPausePendingWebhooksWhenServerDeliveryIsDisabled() {
        Fixture fixture = fixture();
        fixture.properties.getAlert().getWebhook().setEnabled(false);
        AlertDeliveryEntity delivery = delivery("PENDING");
        delivery.setChannelType("WEBHOOK");
        delivery.setChannelId(50L);
        delivery.setAttemptCount(0);
        delivery.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        when(fixture.deliveryMapper.selectList(any()))
                .thenReturn(Collections.emptyList(), Collections.singletonList(delivery));
        when(fixture.deliveryMapper.selectById(delivery.getId())).thenReturn(delivery);

        fixture.service.dispatchDue();

        assertEquals("PENDING", delivery.getStatus());
        assertEquals(0, delivery.getAttemptCount());
        verify(fixture.deliveryMapper, never()).update(isNull(), any());
        verify(fixture.channelService, never()).findById(any(), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<AlertDeliveryEntity>> queryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fixture.deliveryMapper, times(2)).selectList(queryCaptor.capture());
        assertTrue(queryCaptor.getAllValues().get(1).getSqlSegment().contains("channel_type"));
    }

    @Test
    void shouldApplyElinkManagerResponseToDeliveryStatus() {
        AlertDeliveryMapper deliveryMapper = mock(AlertDeliveryMapper.class);
        AlertEventMapper eventMapper = mock(AlertEventMapper.class);
        AlertChannelService channelService = mock(AlertChannelService.class);
        ElinkAlertSender elinkSender = mock(ElinkAlertSender.class);
        AlertDeliveryEntity delivery = delivery("PENDING");
        delivery.setChannelType("ELINK");
        delivery.setChannelId(50L);
        delivery.setAttemptCount(0);
        delivery.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        delivery.setPayloadJson(new LinkedHashMap<String, Object>(Map.of("summary", "worker offline")));
        when(deliveryMapper.selectList(any())).thenReturn(Collections.emptyList(), Collections.singletonList(delivery));
        when(deliveryMapper.selectById(delivery.getId())).thenReturn(delivery);
        when(deliveryMapper.update(isNull(), any())).thenReturn(1);
        AlertChannelEntity channel = new AlertChannelEntity();
        channel.setId(50L);
        channel.setEnabled(1);
        when(channelService.findById(50L, "default", 200L)).thenReturn(channel);
        when(elinkSender.send(same(channel), same(delivery.getPayloadJson())))
                .thenReturn(ElinkAlertSender.SendResult.success(200,
                        "{\"errcode\":0,\"errmsg\":\"ok\",\"jobId\":\"job-1\"}"));
        StudioPlatformProperties properties = new StudioPlatformProperties();
        AlertDeliveryService service = new AlertDeliveryService(deliveryMapper, eventMapper,
                mock(AlertIncidentService.class), channelService, mock(AlertRuleService.class),
                mock(NotificationService.class), mock(AlertWebhookSecurityService.class),
                mock(AlertWebhookHttpClient.class), elinkSender, properties, mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class), new ObjectMapper());

        service.dispatchDue();

        assertEquals("SUCCEEDED", delivery.getStatus());
        assertEquals(200, delivery.getHttpStatus());
        assertTrue(delivery.getResponseExcerpt().contains("\"errcode\":0"));
        verify(elinkSender).send(same(channel), same(delivery.getPayloadJson()));
    }

    @Test
    void shouldContinueTheBatchWhenOneDeliveryFailsUnexpectedly() {
        Fixture fixture = fixture();
        AlertDeliveryEntity broken = delivery("PENDING");
        broken.setAttemptCount(0);
        broken.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        AlertDeliveryEntity healthy = delivery("PENDING");
        healthy.setId(101L);
        healthy.setAttemptCount(0);
        healthy.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        when(fixture.deliveryMapper.selectList(any()))
                .thenReturn(Collections.emptyList(), List.of(broken, healthy));
        when(fixture.deliveryMapper.selectById(broken.getId()))
                .thenThrow(new IllegalStateException("simulated mapper failure"));
        when(fixture.deliveryMapper.selectById(healthy.getId())).thenReturn(healthy);

        fixture.service.dispatchDue();

        assertEquals("SUCCEEDED", healthy.getStatus());
        verify(fixture.notificationService).notifyUsers(any(), any());
    }

    @Test
    void shouldNotStartAWebHookAttemptBeyondTheConfiguredLimit() throws Exception {
        Fixture fixture = fixture();
        AlertDeliveryEntity delivery = delivery("RETRY");
        delivery.setChannelType("WEBHOOK");
        delivery.setChannelId(50L);
        delivery.setAttemptCount(5);
        delivery.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        when(fixture.deliveryMapper.selectList(any()))
                .thenReturn(Collections.emptyList(), Collections.singletonList(delivery));
        when(fixture.deliveryMapper.selectById(delivery.getId())).thenReturn(delivery);

        fixture.service.dispatchDue();

        assertEquals("DEAD", delivery.getStatus());
        assertEquals(5, delivery.getAttemptCount());
        assertNull(delivery.getNextAttemptAt());
        verify(fixture.channelService, never()).findById(any(), any(), any());
        verify(fixture.webhookHttpClient, never()).post(any(), any(), any(), any(Integer.class), any(Integer.class), any(Integer.class));
    }

    @Test
    void shouldMarkExhaustedStaleProcessingAsDeadInsteadOfRetrying() {
        Fixture fixture = fixture();
        AlertDeliveryEntity delivery = delivery("PROCESSING");
        delivery.setAttemptCount(5);
        delivery.setLastAttemptAt(LocalDateTime.now().minusMinutes(10));
        when(fixture.deliveryMapper.selectList(any()))
                .thenReturn(Collections.singletonList(delivery), Collections.emptyList());

        fixture.service.dispatchDue();

        assertEquals("DEAD", delivery.getStatus());
        assertEquals(5, delivery.getAttemptCount());
        assertNull(delivery.getNextAttemptAt());
    }

    @Test
    void shouldNotRetryWebhookTargetsRejectedBySecurityValidation() throws Exception {
        Fixture fixture = fixture();
        AlertDeliveryEntity delivery = delivery("PENDING");
        delivery.setChannelType("WEBHOOK");
        delivery.setChannelId(50L);
        delivery.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        when(fixture.deliveryMapper.selectList(any()))
                .thenReturn(Collections.emptyList(), Collections.singletonList(delivery));
        when(fixture.deliveryMapper.selectById(delivery.getId())).thenReturn(delivery);
        AlertChannelEntity channel = new AlertChannelEntity();
        channel.setId(50L);
        channel.setEnabled(1);
        when(fixture.channelService.findById(50L, "default", 200L)).thenReturn(channel);
        when(fixture.channelService.endpoint(channel)).thenReturn("https://127.0.0.1/private");
        when(fixture.webhookSecurityService.validateAndResolve(any()))
                .thenThrow(new StudioException(StudioErrorCode.BAD_REQUEST,
                        "Webhook endpoint resolves to a private or local network address"));

        fixture.service.dispatchDue();

        assertEquals("DEAD", delivery.getStatus());
        assertTrue(delivery.getErrorMessage().contains("private or local network"));
        verify(fixture.webhookHttpClient, never()).post(any(), any(), any(), any(Integer.class), any(Integer.class), any(Integer.class));
    }

    @Test
    void shouldRetryWebhookWhenDnsResolutionFailsTemporarily() throws Exception {
        Fixture fixture = fixture();
        AlertDeliveryEntity delivery = delivery("PENDING");
        delivery.setChannelType("WEBHOOK");
        delivery.setChannelId(50L);
        delivery.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        when(fixture.deliveryMapper.selectList(any()))
                .thenReturn(Collections.emptyList(), Collections.singletonList(delivery));
        when(fixture.deliveryMapper.selectById(delivery.getId())).thenReturn(delivery);
        AlertChannelEntity channel = new AlertChannelEntity();
        channel.setId(50L);
        channel.setEnabled(1);
        when(fixture.channelService.findById(50L, "default", 200L)).thenReturn(channel);
        when(fixture.channelService.endpoint(channel)).thenReturn("https://hooks.example.com/alert");
        when(fixture.webhookSecurityService.validateAndResolve(any()))
                .thenThrow(new AlertWebhookSecurityService.WebhookDnsResolutionException());

        fixture.service.dispatchDue();

        assertEquals("RETRY", delivery.getStatus());
        assertTrue(delivery.getErrorMessage().contains("Webhook request failed"));
        verify(fixture.webhookHttpClient, never()).post(any(), any(), any(), any(Integer.class), any(Integer.class), any(Integer.class));
    }

    @Test
    void shouldClearPreviousFailureAfterSuccessfulRedelivery() {
        Fixture fixture = fixture();
        AlertDeliveryEntity delivery = delivery("RETRY");
        delivery.setNextAttemptAt(LocalDateTime.now().minusMinutes(1));
        delivery.setHttpStatus(500);
        delivery.setResponseExcerpt("retry");
        delivery.setErrorMessage("Webhook returned HTTP 500");
        when(fixture.deliveryMapper.selectList(any()))
                .thenReturn(Collections.emptyList(), Collections.singletonList(delivery));
        when(fixture.deliveryMapper.selectById(delivery.getId())).thenReturn(delivery);
        when(fixture.deliveryMapper.update(isNull(), any())).thenReturn(1);

        fixture.service.dispatchDue();

        LambdaUpdateWrapper<AlertDeliveryEntity> update = captureLastUpdate(fixture.deliveryMapper, 2);
        assertTrue(update.getSqlSet().contains("next_attempt_at"));
        assertTrue(update.getSqlSet().contains("error_message"));
        assertNull(delivery.getNextAttemptAt());
        assertNull(delivery.getHttpStatus());
        assertNull(delivery.getResponseExcerpt());
        assertNull(delivery.getErrorMessage());
    }

    @Test
    void shouldSkipInAppDeliveryWhenRecipientWasDisabledAfterOutboxCreation() {
        Fixture fixture = fixture();
        when(fixture.notificationService.notifyUsers(any(), any())).thenReturn(Collections.emptyList());
        AlertDeliveryEntity delivery = delivery("PENDING");
        delivery.setAttemptCount(0);
        delivery.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        when(fixture.deliveryMapper.selectList(any()))
                .thenReturn(Collections.emptyList(), Collections.singletonList(delivery));
        when(fixture.deliveryMapper.selectById(delivery.getId())).thenReturn(delivery);

        fixture.service.dispatchDue();

        assertEquals("SKIPPED", delivery.getStatus());
        assertEquals("In-app recipient is disabled or missing", delivery.getErrorMessage());
        assertNull(delivery.getNextAttemptAt());
    }

    @Test
    void shouldSkipPendingWebhookWhenItsChannelWasDisabledAfterOutboxCreation() throws Exception {
        Fixture fixture = fixture();
        AlertDeliveryEntity delivery = delivery("PENDING");
        delivery.setChannelType("WEBHOOK");
        delivery.setChannelId(50L);
        delivery.setAttemptCount(0);
        delivery.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        when(fixture.deliveryMapper.selectList(any()))
                .thenReturn(Collections.emptyList(), Collections.singletonList(delivery));
        when(fixture.deliveryMapper.selectById(delivery.getId())).thenReturn(delivery);
        AlertChannelEntity channel = new AlertChannelEntity();
        channel.setId(50L);
        channel.setEnabled(0);
        when(fixture.channelService.findById(50L, "default", 200L)).thenReturn(channel);

        fixture.service.dispatchDue();

        assertEquals("SKIPPED", delivery.getStatus());
        assertEquals("Webhook channel is disabled", delivery.getErrorMessage());
        assertNull(delivery.getNextAttemptAt());
        verify(fixture.webhookHttpClient, never()).post(any(), any(), any(), any(Integer.class), any(Integer.class), any(Integer.class));
    }

    @Test
    void shouldUseUtcEpochSecondsForWebhookSignatureTimestamp() {
        long timestamp = AlertDeliveryService.currentEpochSecond();
        assertTrue(Math.abs(timestamp - Instant.now().getEpochSecond()) <= 1L);
    }

    @Test
    void shouldRedactStructuredSecretsAndAuthorizationHeaders() {
        String sanitized = AlertSensitiveTextSanitizer.sanitize(
                "{\"token\":\"json-secret\"}\nAuthorization: Bearer bearer-secret\nCookie: sid=cookie-secret");
        assertFalse(sanitized.contains("json-secret"));
        assertFalse(sanitized.contains("bearer-secret"));
        assertFalse(sanitized.contains("cookie-secret"));
        assertTrue(sanitized.contains("******"));
    }

    @Test
    void shouldSendValidHmacHeadersThroughRealHttpClient() throws Exception {
        AtomicReference<String> timestampHeader = new AtomicReference<String>();
        AtomicReference<String> signatureHeader = new AtomicReference<String>();
        AtomicReference<String> requestBody = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/alert", exchange -> {
            timestampHeader.set(exchange.getRequestHeaders().getFirst("X-Studio-Timestamp"));
            signatureHeader.set(exchange.getRequestHeaders().getFirst("X-Studio-Signature-SHA256"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();
        try {
            AlertDeliveryMapper deliveryMapper = mock(AlertDeliveryMapper.class);
            AlertChannelService channelService = mock(AlertChannelService.class);
            AlertDeliveryEntity delivery = delivery("PENDING");
            delivery.setChannelType("WEBHOOK");
            delivery.setChannelId(50L);
            delivery.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
            delivery.setPayloadJson(new LinkedHashMap<String, Object>(Map.of("schemaVersion", "studio.alert.webhook.v1")));
            when(deliveryMapper.selectList(any())).thenReturn(Collections.emptyList(), Collections.singletonList(delivery));
            when(deliveryMapper.selectById(delivery.getId())).thenReturn(delivery);
            when(deliveryMapper.update(isNull(), any())).thenReturn(1);
            AlertChannelEntity channel = new AlertChannelEntity();
            channel.setId(50L);
            channel.setEnabled(1);
            when(channelService.findById(50L, "default", 200L)).thenReturn(channel);
            when(channelService.endpoint(channel)).thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/alert");
            when(channelService.headers(channel)).thenReturn(Collections.emptyMap());
            when(channelService.signingSecret(channel)).thenReturn("test-secret");

            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.getAlert().getWebhook().setAllowHttp(true);
            properties.getAlert().getWebhook().setAllowedHosts(Collections.singletonList("127.0.0.1"));
            properties.getAlert().getWebhook().setRequestTimeoutSeconds(2);
            AlertWebhookSecurityService security = new AlertWebhookSecurityService(properties);
            AlertDeliveryService service = new AlertDeliveryService(deliveryMapper, mock(AlertEventMapper.class),
                    mock(AlertIncidentService.class), channelService, mock(AlertRuleService.class),
                    mock(NotificationService.class), security, new AlertWebhookHttpClient(), properties,
                    mock(StudioSecurityService.class), mock(ProjectResourceAccessService.class), new ObjectMapper());

            long beforeDispatch = Instant.now().getEpochSecond();
            service.dispatchDue();
            long afterDispatch = Instant.now().getEpochSecond();

            long timestamp = Long.parseLong(timestampHeader.get());
            assertTrue(timestamp >= beforeDispatch && timestamp <= afterDispatch);
            assertEquals(hmacHex("test-secret", timestampHeader.get() + "." + requestBody.get()), signatureHeader.get());
            assertEquals("SUCCEEDED", delivery.getStatus());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRetryHttp500AndSucceedOnTheNextAttempt() throws Exception {
        WebhookDispatchResult result = dispatchWebhookStatuses(500, 200);
        AlertDeliveryEntity delivery = result.delivery();

        assertEquals("SUCCEEDED", delivery.getStatus());
        assertEquals(2, delivery.getAttemptCount());
        assertEquals(200, delivery.getHttpStatus());
        assertEquals(2, result.requestCount());
        assertRetryDelay(result, 0, 60L);
    }

    @Test
    void shouldRetryHttp429ButTerminateHttp400() throws Exception {
        WebhookDispatchResult throttledResult = dispatchWebhookStatuses(429);
        WebhookDispatchResult rejectedResult = dispatchWebhookStatuses(400);
        AlertDeliveryEntity throttled = throttledResult.delivery();
        AlertDeliveryEntity rejected = rejectedResult.delivery();

        assertEquals("RETRY", throttled.getStatus());
        assertEquals(429, throttled.getHttpStatus());
        assertEquals(1, throttledResult.requestCount());
        assertRetryDelay(throttledResult, 0, 60L);
        assertEquals("DEAD", rejected.getStatus());
        assertEquals(400, rejected.getHttpStatus());
        assertEquals(1, rejectedResult.requestCount());
    }

    @Test
    void shouldUseConfiguredBackoffAndStopAfterFiveAttempts() throws Exception {
        WebhookDispatchResult result = dispatchWebhookStatuses(500, 500, 500, 500, 500);

        assertEquals("DEAD", result.delivery().getStatus());
        assertEquals(5, result.delivery().getAttemptCount());
        assertEquals(5, result.requestCount());
        assertRetryDelay(result, 0, 60L);
        assertRetryDelay(result, 1, 300L);
        assertRetryDelay(result, 2, 900L);
        assertRetryDelay(result, 3, 3600L);
    }

    @Test
    void shouldRedactWebhookPathAndQueryFragmentsFromResponseExcerpt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/alert/super-secret-path", exchange -> {
            byte[] response = "super-secret-path token=query-secret query-secret".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AlertDeliveryMapper deliveryMapper = mock(AlertDeliveryMapper.class);
            AlertChannelService channelService = mock(AlertChannelService.class);
            AlertDeliveryEntity delivery = delivery("PENDING");
            delivery.setChannelType("WEBHOOK");
            delivery.setChannelId(50L);
            delivery.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
            when(deliveryMapper.selectList(any())).thenReturn(Collections.emptyList(), Collections.singletonList(delivery));
            when(deliveryMapper.selectById(delivery.getId())).thenReturn(delivery);
            when(deliveryMapper.update(isNull(), any())).thenReturn(1);
            AlertChannelEntity channel = new AlertChannelEntity();
            channel.setId(50L);
            channel.setEnabled(1);
            when(channelService.findById(50L, "default", 200L)).thenReturn(channel);
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/alert/super-secret-path?token=query-secret";
            when(channelService.endpoint(channel)).thenReturn(endpoint);
            when(channelService.headers(channel)).thenReturn(Collections.emptyMap());

            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.getAlert().getWebhook().setAllowHttp(true);
            properties.getAlert().getWebhook().setAllowedHosts(Collections.singletonList("127.0.0.1"));
            AlertWebhookSecurityService security = new AlertWebhookSecurityService(properties);
            AlertDeliveryService service = new AlertDeliveryService(deliveryMapper, mock(AlertEventMapper.class),
                    mock(AlertIncidentService.class), channelService, mock(AlertRuleService.class),
                    mock(NotificationService.class), security, new AlertWebhookHttpClient(), properties,
                    mock(StudioSecurityService.class), mock(ProjectResourceAccessService.class), new ObjectMapper());

            service.dispatchDue();

            assertEquals("SUCCEEDED", delivery.getStatus());
            assertFalse(delivery.getResponseExcerpt().contains("super-secret-path"));
            assertFalse(delivery.getResponseExcerpt().contains("query-secret"));
            assertTrue(delivery.getResponseExcerpt().contains("******"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRedactShortWebhookPathAndQueryFragmentsFromResponseExcerpt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            byte[] response = "path=/ok query=q=z value=z".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AlertDeliveryMapper deliveryMapper = mock(AlertDeliveryMapper.class);
            AlertChannelService channelService = mock(AlertChannelService.class);
            AlertDeliveryEntity delivery = delivery("PENDING");
            delivery.setChannelType("WEBHOOK");
            delivery.setChannelId(50L);
            delivery.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
            when(deliveryMapper.selectList(any())).thenReturn(Collections.emptyList(), Collections.singletonList(delivery));
            when(deliveryMapper.selectById(delivery.getId())).thenReturn(delivery);
            when(deliveryMapper.update(isNull(), any())).thenReturn(1);
            AlertChannelEntity channel = new AlertChannelEntity();
            channel.setId(50L);
            channel.setEnabled(1);
            when(channelService.findById(50L, "default", 200L)).thenReturn(channel);
            when(channelService.endpoint(channel)).thenReturn(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/ok?q=z");
            when(channelService.headers(channel)).thenReturn(Collections.emptyMap());

            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.getAlert().getWebhook().setAllowHttp(true);
            properties.getAlert().getWebhook().setAllowedHosts(Collections.singletonList("127.0.0.1"));
            AlertDeliveryService service = new AlertDeliveryService(deliveryMapper, mock(AlertEventMapper.class),
                    mock(AlertIncidentService.class), channelService, mock(AlertRuleService.class),
                    mock(NotificationService.class), new AlertWebhookSecurityService(properties),
                    new AlertWebhookHttpClient(), properties, mock(StudioSecurityService.class),
                    mock(ProjectResourceAccessService.class), new ObjectMapper());

            service.dispatchDue();

            assertEquals("SUCCEEDED", delivery.getStatus());
            assertFalse(delivery.getResponseExcerpt().contains("/ok"));
            assertFalse(delivery.getResponseExcerpt().contains("q=z"));
            assertFalse(delivery.getResponseExcerpt().contains("value=z"));
            assertTrue(delivery.getResponseExcerpt().contains("******"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRedactConfiguredHeaderValuesEchoedByWebhook() throws Exception {
        String configuredHeaderValue = "custom-sensitive-header-value";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/alert", exchange -> {
            byte[] response = ("echo=" + exchange.getRequestHeaders().getFirst("X-Custom-Credential"))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AlertDeliveryMapper deliveryMapper = mock(AlertDeliveryMapper.class);
            AlertChannelService channelService = mock(AlertChannelService.class);
            AlertDeliveryEntity delivery = delivery("PENDING");
            delivery.setChannelType("WEBHOOK");
            delivery.setChannelId(50L);
            delivery.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
            when(deliveryMapper.selectList(any())).thenReturn(Collections.emptyList(), Collections.singletonList(delivery));
            when(deliveryMapper.selectById(delivery.getId())).thenReturn(delivery);
            when(deliveryMapper.update(isNull(), any())).thenReturn(1);
            AlertChannelEntity channel = new AlertChannelEntity();
            channel.setId(50L);
            channel.setEnabled(1);
            when(channelService.findById(50L, "default", 200L)).thenReturn(channel);
            when(channelService.endpoint(channel)).thenReturn(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/alert");
            when(channelService.headers(channel)).thenReturn(
                    Collections.singletonMap("X-Custom-Credential", configuredHeaderValue));

            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.getAlert().getWebhook().setAllowHttp(true);
            properties.getAlert().getWebhook().setAllowedHosts(Collections.singletonList("127.0.0.1"));
            AlertWebhookSecurityService security = new AlertWebhookSecurityService(properties);
            AlertDeliveryService service = new AlertDeliveryService(deliveryMapper, mock(AlertEventMapper.class),
                    mock(AlertIncidentService.class), channelService, mock(AlertRuleService.class),
                    mock(NotificationService.class), security, new AlertWebhookHttpClient(), properties,
                    mock(StudioSecurityService.class), mock(ProjectResourceAccessService.class), new ObjectMapper());

            service.dispatchDue();

            assertEquals("SUCCEEDED", delivery.getStatus());
            assertFalse(delivery.getResponseExcerpt().contains(configuredHeaderValue));
            assertTrue(delivery.getResponseExcerpt().contains("******"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRedactGeneratedSignatureEchoedByWebhook() throws Exception {
        AtomicReference<String> signature = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/alert", exchange -> {
            signature.set(exchange.getRequestHeaders().getFirst("X-Studio-Signature-SHA256"));
            byte[] response = ("signature=" + signature.get()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AlertDeliveryMapper deliveryMapper = mock(AlertDeliveryMapper.class);
            AlertChannelService channelService = mock(AlertChannelService.class);
            AlertDeliveryEntity delivery = delivery("PENDING");
            delivery.setChannelType("WEBHOOK");
            delivery.setChannelId(50L);
            delivery.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
            delivery.setPayloadJson(new LinkedHashMap<String, Object>(Map.of("schemaVersion", "studio.alert.webhook.v1")));
            when(deliveryMapper.selectList(any())).thenReturn(Collections.emptyList(), Collections.singletonList(delivery));
            when(deliveryMapper.selectById(delivery.getId())).thenReturn(delivery);
            when(deliveryMapper.update(isNull(), any())).thenReturn(1);
            AlertChannelEntity channel = new AlertChannelEntity();
            channel.setId(50L);
            channel.setEnabled(1);
            when(channelService.findById(50L, "default", 200L)).thenReturn(channel);
            when(channelService.endpoint(channel)).thenReturn(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/alert");
            when(channelService.headers(channel)).thenReturn(Collections.emptyMap());
            when(channelService.signingSecret(channel)).thenReturn("test-secret");

            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.getAlert().getWebhook().setAllowHttp(true);
            properties.getAlert().getWebhook().setAllowedHosts(Collections.singletonList("127.0.0.1"));
            AlertDeliveryService service = new AlertDeliveryService(deliveryMapper, mock(AlertEventMapper.class),
                    mock(AlertIncidentService.class), channelService, mock(AlertRuleService.class),
                    mock(NotificationService.class), new AlertWebhookSecurityService(properties),
                    new AlertWebhookHttpClient(), properties, mock(StudioSecurityService.class),
                    mock(ProjectResourceAccessService.class), new ObjectMapper());

            service.dispatchDue();

            assertEquals("SUCCEEDED", delivery.getStatus());
            assertTrue(signature.get() != null && !signature.get().isBlank());
            assertFalse(delivery.getResponseExcerpt().contains(signature.get()));
            assertTrue(delivery.getResponseExcerpt().contains("******"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRedactSigningSecretEchoedByWebhook() throws Exception {
        String signingSecret = "shared-signing-secret";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/alert", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = ("secret=" + signingSecret).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AlertDeliveryMapper deliveryMapper = mock(AlertDeliveryMapper.class);
            AlertChannelService channelService = mock(AlertChannelService.class);
            AlertDeliveryEntity delivery = delivery("PENDING");
            delivery.setChannelType("WEBHOOK");
            delivery.setChannelId(50L);
            delivery.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
            when(deliveryMapper.selectList(any())).thenReturn(Collections.emptyList(), Collections.singletonList(delivery));
            when(deliveryMapper.selectById(delivery.getId())).thenReturn(delivery);
            when(deliveryMapper.update(isNull(), any())).thenReturn(1);
            AlertChannelEntity channel = new AlertChannelEntity();
            channel.setId(50L);
            channel.setEnabled(1);
            when(channelService.findById(50L, "default", 200L)).thenReturn(channel);
            when(channelService.endpoint(channel)).thenReturn(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/alert");
            when(channelService.headers(channel)).thenReturn(Collections.emptyMap());
            when(channelService.signingSecret(channel)).thenReturn(signingSecret);

            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.getAlert().getWebhook().setAllowHttp(true);
            properties.getAlert().getWebhook().setAllowedHosts(Collections.singletonList("127.0.0.1"));
            AlertDeliveryService service = new AlertDeliveryService(deliveryMapper, mock(AlertEventMapper.class),
                    mock(AlertIncidentService.class), channelService, mock(AlertRuleService.class),
                    mock(NotificationService.class), new AlertWebhookSecurityService(properties),
                    new AlertWebhookHttpClient(), properties, mock(StudioSecurityService.class),
                    mock(ProjectResourceAccessService.class), new ObjectMapper());

            service.dispatchDue();

            assertEquals("SUCCEEDED", delivery.getStatus());
            assertFalse(delivery.getResponseExcerpt().contains(signingSecret));
            assertTrue(delivery.getResponseExcerpt().contains("******"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldPhysicallyDeleteExpiredDeliveriesAndEvents() {
        Fixture fixture = fixture();

        fixture.service.cleanup();

        verify(fixture.deliveryMapper).hardDeleteTerminalBefore(any(LocalDateTime.class));
        verify(fixture.eventMapper).hardDeleteBefore(any(LocalDateTime.class));
        verify(fixture.deliveryMapper, never()).delete(any());
        verify(fixture.eventMapper, never()).delete(any());
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<AlertDeliveryEntity> captureUpdate(AlertDeliveryMapper mapper) {
        ArgumentCaptor<LambdaUpdateWrapper<AlertDeliveryEntity>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<AlertDeliveryEntity> captureLastUpdate(AlertDeliveryMapper mapper, int count) {
        ArgumentCaptor<LambdaUpdateWrapper<AlertDeliveryEntity>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(count)).update(isNull(), captor.capture());
        return captor.getAllValues().get(count - 1);
    }

    private AlertDeliveryEntity delivery(String status) {
        AlertDeliveryEntity delivery = new AlertDeliveryEntity();
        delivery.setId(100L);
        delivery.setTenantId("default");
        delivery.setProjectId(200L);
        delivery.setEventId(300L);
        delivery.setChannelType("IN_APP");
        delivery.setRecipientUserId(400L);
        delivery.setStatus(status);
        delivery.setAttemptCount(1);
        delivery.setPayloadJson(new LinkedHashMap<String, Object>());
        return delivery;
    }

    private String hmacHex(String secret, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder result = new StringBuilder();
        for (byte item : mac.doFinal(value.getBytes(StandardCharsets.UTF_8))) {
            result.append(String.format("%02x", item));
        }
        return result.toString();
    }

    private WebhookDispatchResult dispatchWebhookStatuses(int... statuses) throws Exception {
        AtomicInteger responseIndex = new AtomicInteger();
        List<Long> retryDelaySeconds = new ArrayList<Long>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/alert", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int index = Math.min(responseIndex.getAndIncrement(), statuses.length - 1);
            exchange.sendResponseHeaders(statuses[index], -1);
            exchange.close();
        });
        server.start();
        try {
            AlertDeliveryMapper deliveryMapper = mock(AlertDeliveryMapper.class);
            AlertChannelService channelService = mock(AlertChannelService.class);
            AlertDeliveryEntity delivery = delivery("PENDING");
            delivery.setChannelType("WEBHOOK");
            delivery.setChannelId(50L);
            delivery.setAttemptCount(0);
            delivery.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
            delivery.setPayloadJson(new LinkedHashMap<String, Object>(Map.of("schemaVersion", "studio.alert.webhook.v1")));
            java.util.List<AlertDeliveryEntity> due = Collections.singletonList(delivery);
            AtomicInteger selectListCalls = new AtomicInteger();
            when(deliveryMapper.selectList(any())).thenAnswer(invocation ->
                    selectListCalls.incrementAndGet() % 2 == 1 ? Collections.emptyList() : due);
            when(deliveryMapper.selectById(delivery.getId())).thenReturn(delivery);
            when(deliveryMapper.update(isNull(), any())).thenReturn(1);
            AlertChannelEntity channel = new AlertChannelEntity();
            channel.setId(50L);
            channel.setEnabled(1);
            when(channelService.findById(50L, "default", 200L)).thenReturn(channel);
            when(channelService.endpoint(channel)).thenReturn(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/alert");
            when(channelService.headers(channel)).thenReturn(Collections.emptyMap());

            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.getAlert().getWebhook().setAllowHttp(true);
            properties.getAlert().getWebhook().setAllowedHosts(Collections.singletonList("127.0.0.1"));
            AlertDeliveryService service = new AlertDeliveryService(deliveryMapper, mock(AlertEventMapper.class),
                    mock(AlertIncidentService.class), channelService, mock(AlertRuleService.class),
                    mock(NotificationService.class), new AlertWebhookSecurityService(properties),
                    new AlertWebhookHttpClient(), properties, mock(StudioSecurityService.class),
                    mock(ProjectResourceAccessService.class), new ObjectMapper());

            for (int ignored : statuses) {
                delivery.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
                service.dispatchDue();
                if ("RETRY".equals(delivery.getStatus()) && delivery.getNextAttemptAt() != null) {
                    retryDelaySeconds.add(Duration.between(LocalDateTime.now(), delivery.getNextAttemptAt()).getSeconds());
                }
            }
            return new WebhookDispatchResult(delivery, responseIndex.get(), retryDelaySeconds);
        } finally {
            server.stop(0);
        }
    }

    private Fixture fixture() {
        AlertDeliveryMapper deliveryMapper = mock(AlertDeliveryMapper.class);
        AlertEventMapper eventMapper = mock(AlertEventMapper.class);
        AlertIncidentService incidentService = mock(AlertIncidentService.class);
        AlertChannelService channelService = mock(AlertChannelService.class);
        AlertRuleService ruleService = mock(AlertRuleService.class);
        NotificationService notificationService = mock(NotificationService.class);
        when(notificationService.notifyUsers(any(), any())).thenReturn(Collections.singletonList(
                mock(com.jdragon.studio.dto.model.NotificationView.class)));
        AlertWebhookSecurityService webhookSecurityService = mock(AlertWebhookSecurityService.class);
        AlertWebhookHttpClient webhookHttpClient = mock(AlertWebhookHttpClient.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        StudioPlatformProperties properties = new StudioPlatformProperties();
        when(accessService.requireCurrentProjectId()).thenReturn(200L);
        when(securityService.currentTenantId()).thenReturn("default");
        when(deliveryMapper.updateById(any(AlertDeliveryEntity.class))).thenReturn(1);
        when(deliveryMapper.update(isNull(), any())).thenReturn(1);
        AlertDeliveryService service = new AlertDeliveryService(
                deliveryMapper, eventMapper, incidentService, channelService, ruleService,
                notificationService, webhookSecurityService, webhookHttpClient, properties, securityService,
                accessService, new ObjectMapper());
        return new Fixture(service, deliveryMapper, eventMapper, channelService, notificationService,
                webhookSecurityService, webhookHttpClient, properties);
    }

    private void assertRetryDelay(WebhookDispatchResult result, int index, long expectedSeconds) {
        long actual = result.retryDelaySeconds().get(index);
        assertTrue(actual >= expectedSeconds - 2L && actual <= expectedSeconds + 1L,
                "Expected retry delay near " + expectedSeconds + " seconds but was " + actual);
    }

    private record Fixture(AlertDeliveryService service, AlertDeliveryMapper deliveryMapper,
                           AlertEventMapper eventMapper, AlertChannelService channelService,
                           NotificationService notificationService,
                           AlertWebhookSecurityService webhookSecurityService,
                           AlertWebhookHttpClient webhookHttpClient, StudioPlatformProperties properties) {
    }

    private record WebhookDispatchResult(AlertDeliveryEntity delivery, int requestCount,
                                         List<Long> retryDelaySeconds) {
    }
}
