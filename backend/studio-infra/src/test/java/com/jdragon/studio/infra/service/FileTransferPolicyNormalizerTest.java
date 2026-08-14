package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileTransferPolicyNormalizerTest {

    @Test
    void historicalPolicyDefaultsToStrongVerification() {
        Map<String, Object> policy = FileTransferPolicyNormalizer.normalize(Map.of());

        assertThat(policy).containsEntry("verificationMode", "STRONG")
                .containsEntry("verificationFrameCount", 16)
                .containsEntry("verificationFrameSizeBytes", 1024L * 1024L)
                .containsEntry("sourceSuccessAction", "KEEP");
    }

    @Test
    void normalizesPartialVerificationPolicy() {
        Map<String, Object> policy = FileTransferPolicyNormalizer.normalize(Map.of(
                "verificationMode", "partial",
                "verificationFrameCount", "8",
                "verificationFrameSizeBytes", 262144));

        assertThat(policy).containsEntry("verificationMode", "PARTIAL")
                .containsEntry("verificationFrameCount", 8)
                .containsEntry("verificationFrameSizeBytes", 262144L);
    }

    @Test
    void rejectsInvalidFramesAndDestructiveNoneMode() {
        assertThatThrownBy(() -> FileTransferPolicyNormalizer.normalize(Map.of(
                "verificationMode", "PARTIAL", "verificationFrameCount", 0)))
                .isInstanceOf(StudioException.class);
        assertThatThrownBy(() -> FileTransferPolicyNormalizer.normalize(Map.of(
                "verificationMode", "PARTIAL", "verificationFrameSizeBytes", 96L * 1024L)))
                .isInstanceOf(StudioException.class);
        assertThatThrownBy(() -> FileTransferPolicyNormalizer.normalize(Map.of(
                "verificationMode", "NONE", "sourceSuccessAction", "DELETE")))
                .isInstanceOf(StudioException.class);
    }
}
