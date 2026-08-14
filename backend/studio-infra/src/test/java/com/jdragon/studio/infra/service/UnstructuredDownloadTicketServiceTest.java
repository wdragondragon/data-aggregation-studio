package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.UnstructuredDownloadTicketView;
import com.jdragon.studio.dto.model.request.UnstructuredDownloadTicketRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnstructuredDownloadTicketServiceTest {

    @Test
    void shouldStoreOnlyHashedRedisKeyAndReturnRawTicketOnce() throws Exception {
        Fixture fixture = fixture();
        UnstructuredManagementService.PreparedNativeDownload prepared = preparedFile();
        when(fixture.managementService.prepareNativeDownload(50L, 100L,
                List.of("/native.txt"))).thenReturn(prepared);
        when(fixture.valueOperations.setIfAbsent(anyString(), anyString(),
                eq(Duration.ofSeconds(120L)))).thenReturn(true);
        UnstructuredDownloadTicketRequest request = request();

        UnstructuredDownloadTicketView result = fixture.service.create(request);

        assertThat(result.getTicket()).matches("[A-Za-z0-9_-]{43}");
        assertThat(result.getFileName()).isEqualTo("native.txt");
        assertThat(result.getArchive()).isFalse();
        assertThat(result.getContentLength()).isEqualTo(6L);
        org.mockito.ArgumentCaptor<String> keyCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(fixture.valueOperations).setIfAbsent(keyCaptor.capture(), anyString(),
                eq(Duration.ofSeconds(120L)));
        assertThat(keyCaptor.getValue())
                .startsWith("studio:unstructured-download-ticket:")
                .doesNotContain(result.getTicket());
        assertThat(keyCaptor.getValue().substring(
                "studio:unstructured-download-ticket:".length())).hasSize(64);
    }

    @Test
    void shouldAtomicallyConsumeTicketAndRebuildExecutionContext() throws Exception {
        Fixture fixture = fixture();
        String ticket = "A".repeat(43);
        UnstructuredDownloadTicketService.TicketPayload payload = validPayload();
        when(fixture.valueOperations.getAndDelete(anyString()))
                .thenReturn(fixture.objectMapper.writeValueAsString(payload));
        UnstructuredManagementService.PreparedNativeDownload prepared = preparedFile();
        when(fixture.managementService.prepareNativeDownload(50L, 100L,
                List.of("/native.txt"))).thenReturn(prepared);
        when(fixture.executionContextService.callAs(eq(7L), eq("tenant-a"), eq(9L),
                any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(3)).get());

        UnstructuredManagementService.PreparedNativeDownload result =
                fixture.service.consume(ticket);

        assertThat(result).isSameAs(prepared);
        verify(fixture.valueOperations).getAndDelete(anyString());
        verify(fixture.executionContextService).callAs(eq(7L), eq("tenant-a"),
                eq(9L), any());
    }

    @Test
    void shouldRejectMissingExpiredAndReplayedTickets() throws Exception {
        Fixture fixture = fixture();
        when(fixture.valueOperations.getAndDelete(anyString())).thenReturn(null);

        assertThatThrownBy(() -> fixture.service.consume("A".repeat(43)))
                .isInstanceOfSatisfying(StudioException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(StudioErrorCode.UNAUTHORIZED));

        UnstructuredDownloadTicketService.TicketPayload expired = validPayload();
        expired.expiresAtEpochMillis = 1L;
        when(fixture.valueOperations.getAndDelete(anyString()))
                .thenReturn(fixture.objectMapper.writeValueAsString(expired));
        assertThatThrownBy(() -> fixture.service.consume("B".repeat(43)))
                .isInstanceOfSatisfying(StudioException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(StudioErrorCode.UNAUTHORIZED));
    }

    @Test
    void shouldFailClosedWhenRedisIsUnavailable() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        UnstructuredDownloadTicketService service = new UnstructuredDownloadTicketService(
                provider, new ObjectMapper(), mock(UnstructuredManagementService.class),
                mock(StudioSecurityService.class), mock(StudioExecutionContextService.class),
                new StudioPlatformProperties());

        assertThatThrownBy(() -> service.consume("A".repeat(43)))
                .isInstanceOfSatisfying(StudioException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(
                                StudioErrorCode.SERVICE_UNAVAILABLE));
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        UnstructuredManagementService managementService = mock(UnstructuredManagementService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(securityService.currentUserId()).thenReturn(7L);
        when(securityService.currentUsername()).thenReturn("admin");
        when(securityService.currentTenantId()).thenReturn("tenant-a");
        when(securityService.currentProjectId()).thenReturn(9L);
        StudioExecutionContextService executionContextService =
                mock(StudioExecutionContextService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        UnstructuredDownloadTicketService service = new UnstructuredDownloadTicketService(
                provider, objectMapper, managementService, securityService,
                executionContextService, new StudioPlatformProperties());
        return new Fixture(service, managementService, executionContextService,
                objectMapper, valueOperations);
    }

    private UnstructuredDownloadTicketRequest request() {
        UnstructuredDownloadTicketRequest request = new UnstructuredDownloadTicketRequest();
        request.setRuntimeClusterId(50L);
        request.setDatasourceId(100L);
        request.setPaths(List.of("/native.txt"));
        return request;
    }

    private UnstructuredDownloadTicketService.TicketPayload validPayload() {
        UnstructuredDownloadTicketService.TicketPayload payload =
                new UnstructuredDownloadTicketService.TicketPayload();
        payload.userId = 7L;
        payload.username = "admin";
        payload.tenantId = "tenant-a";
        payload.projectId = 9L;
        payload.runtimeClusterId = 50L;
        payload.datasourceId = 100L;
        payload.paths = List.of("/native.txt");
        payload.mode = "FILE";
        payload.fileName = "native.txt";
        payload.expiresAtEpochMillis = System.currentTimeMillis() + 60_000L;
        return payload;
    }

    private UnstructuredManagementService.PreparedNativeDownload preparedFile() {
        return new UnstructuredManagementService.PreparedNativeDownload(
                false, null, null, "native.txt", 6L, List.of("/native.txt"));
    }

    private record Fixture(UnstructuredDownloadTicketService service,
                           UnstructuredManagementService managementService,
                           StudioExecutionContextService executionContextService,
                           ObjectMapper objectMapper,
                           ValueOperations<String, String> valueOperations) {
    }
}
