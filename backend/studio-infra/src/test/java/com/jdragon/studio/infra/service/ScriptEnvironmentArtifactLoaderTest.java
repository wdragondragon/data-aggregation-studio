package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScriptEnvironmentArtifactLoaderTest {

    @TempDir
    Path tempDir;

    private RuntimeEndpointHttpClient httpClient;

    @AfterEach
    void closeHttpClient() {
        if (httpClient != null) {
            httpClient.close();
        }
    }

    @Test
    void managedObjectStorageArtifactShouldRemainSupported() throws Exception {
        CloudObjectStorageService objectStorageService = mock(CloudObjectStorageService.class);
        when(objectStorageService.get("studio", "script-environments/a.jar"))
                .thenReturn("jar-content".getBytes(StandardCharsets.UTF_8));
        ScriptEnvironmentArtifactLoader loader = loader(new StudioPlatformProperties(), objectStorageService);

        try (InputStream input = loader.open("oss://studio/script-environments/a.jar")) {
            assertArrayEquals("jar-content".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
        }
    }

    @Test
    void managedObjectStorageArtifactShouldHonorConfiguredSizeLimit() {
        CloudObjectStorageService objectStorageService = mock(CloudObjectStorageService.class);
        when(objectStorageService.get("studio", "script-environments/large.jar"))
                .thenReturn(new byte[1025]);
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getScriptEnvironment().setMaxArtifactBytes(1024);
        ScriptEnvironmentArtifactLoader loader = loader(properties, objectStorageService);

        StudioException exception = assertThrows(StudioException.class,
                () -> loader.open("oss://studio/script-environments/large.jar"));

        assertTrue(exception.getMessage().contains("artifact size limit"));
    }

    @Test
    void localFilesShouldBeDeniedByDefault() throws Exception {
        Path artifact = Files.write(tempDir.resolve("dependency.jar"), new byte[]{1, 2, 3});
        ScriptEnvironmentArtifactLoader loader = loader(
                new StudioPlatformProperties(), mock(CloudObjectStorageService.class));

        StudioException exception = assertThrows(StudioException.class,
                () -> loader.open(artifact.toString()));

        assertTrue(exception.getMessage().contains("local files are disabled"));
    }

    @Test
    void localFilesShouldBeLimitedToExplicitRoots() throws Exception {
        Path allowedRoot = Files.createDirectory(tempDir.resolve("allowed"));
        Path allowedArtifact = Files.write(allowedRoot.resolve("dependency.jar"), new byte[]{1, 2, 3});
        Path deniedArtifact = Files.write(tempDir.resolve("outside.jar"), new byte[]{4, 5, 6});
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getScriptEnvironment().setAllowLocalFiles(true);
        properties.getScriptEnvironment().setAllowedLocalRoots(
                Collections.singletonList(allowedRoot.toString()));
        ScriptEnvironmentArtifactLoader loader = loader(
                properties, mock(CloudObjectStorageService.class));

        try (InputStream input = loader.open(allowedArtifact.toUri().toString())) {
            assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
        }
        StudioException exception = assertThrows(StudioException.class,
                () -> loader.open(deniedArtifact.toString()));
        assertTrue(exception.getMessage().contains("outside the configured roots"));
    }

    @Test
    void localFilesShouldHonorConfiguredSizeLimit() throws Exception {
        Path allowedRoot = Files.createDirectory(tempDir.resolve("bounded"));
        Path artifact = Files.write(allowedRoot.resolve("large.jar"), new byte[1025]);
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getScriptEnvironment().setAllowLocalFiles(true);
        properties.getScriptEnvironment().setAllowedLocalRoots(
                Collections.singletonList(allowedRoot.toString()));
        properties.getScriptEnvironment().setMaxArtifactBytes(1024);
        ScriptEnvironmentArtifactLoader loader = loader(
                properties, mock(CloudObjectStorageService.class));

        StudioException exception = assertThrows(StudioException.class,
                () -> loader.open(artifact.toString()));

        assertTrue(exception.getMessage().contains("artifact size limit"));
    }

    @Test
    void privateHttpArtifactShouldRequireAnExplicitAllowedHost() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        ScriptEnvironmentArtifactLoader loader = loader(
                properties, mock(CloudObjectStorageService.class));

        StudioException exception = assertThrows(StudioException.class,
                () -> loader.open("http://127.0.0.1:18081/dependency.jar"));

        assertTrue(exception.getMessage().contains("explicitly allowed"));
    }

    @Test
    void allowedHttpArtifactShouldUseBoundedNonRedirectingClient() throws Exception {
        byte[] body = "jar-content".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dependency.jar", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/redirect.jar", exchange -> {
            exchange.getResponseHeaders().add("Location", "/dependency.jar");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.getRuntimeEndpoint().setAllowedHosts(
                    Collections.singletonList("127.0.0.1"));
            ScriptEnvironmentArtifactLoader loader = loader(
                    properties, mock(CloudObjectStorageService.class));
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

            try (InputStream input = loader.open(baseUrl + "/dependency.jar")) {
                assertArrayEquals(body, input.readAllBytes());
            }
            StudioException redirect = assertThrows(StudioException.class,
                    () -> loader.open(baseUrl + "/redirect.jar"));
            assertTrue(redirect.getMessage().contains("HTTP 302"));
        } finally {
            server.stop(0);
        }
    }

    private ScriptEnvironmentArtifactLoader loader(StudioPlatformProperties properties,
                                                   CloudObjectStorageService objectStorageService) {
        httpClient = new RuntimeEndpointHttpClient();
        return new ScriptEnvironmentArtifactLoader(
                objectStorageService,
                new RuntimeEndpointSecurityService(properties),
                httpClient,
                properties);
    }
}
