package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

final class DataServiceTokenSupport {

    private final SecureRandom secureRandom = new SecureRandom();

    String generateServiceKey() {
        byte[] bytes = new byte[12];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String generateSubscriptionToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return "dsvc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : hash) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Failed to hash token");
        }
    }

    String maskToken(String token) {
        if (token == null || token.length() <= 12) {
            return "******";
        }
        return token.substring(0, 8) + "..." + token.substring(token.length() - 4);
    }
}
