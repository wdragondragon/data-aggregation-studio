package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Encrypts short-lived execution inputs that must not be stored in the public dispatch JSON. */
@Service
public class DispatchProtectedPayloadService {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
            new TypeReference<LinkedHashMap<String, Object>>() { };

    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    public DispatchProtectedPayloadService(EncryptionService encryptionService, ObjectMapper objectMapper) {
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper;
    }

    public String protect(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return encryptionService.encrypt(objectMapper.writeValueAsString(payload));
        } catch (StudioException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to protect dispatch execution input", exception);
        }
    }

    public Map<String, Object> unprotect(String ciphertext) {
        if (ciphertext == null || ciphertext.trim().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            String json = encryptionService.decrypt(ciphertext);
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (StudioException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to read protected dispatch execution input", exception);
        }
    }
}
