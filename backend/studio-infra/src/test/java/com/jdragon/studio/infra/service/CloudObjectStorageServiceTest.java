package com.jdragon.studio.infra.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudObjectStorageServiceTest {

    @Test
    void immutableCreateTreatsProviderConflictCodesAsAlreadyPresent() {
        assertTrue(CloudObjectStorageService.isImmutableCreateConflict("FileAlreadyExists"));
        assertTrue(CloudObjectStorageService.isImmutableCreateConflict("ObjectAlreadyExists"));
        assertTrue(CloudObjectStorageService.isImmutableCreateConflict("PreconditionFailed"));
        assertTrue(CloudObjectStorageService.isImmutableCreateConflict("ConditionalRequestConflict"));
        assertTrue(CloudObjectStorageService.isImmutableCreateConflict("objectalreadyexists"));
    }

    @Test
    void immutableCreateDoesNotHideUnrelatedStorageErrors() {
        assertFalse(CloudObjectStorageService.isImmutableCreateConflict(null));
        assertFalse(CloudObjectStorageService.isImmutableCreateConflict("AccessDenied"));
        assertFalse(CloudObjectStorageService.isImmutableCreateConflict("NoSuchBucket"));
    }

    @Test
    void immutableCreateRecognizesAliyunIndeterminateResponseForExistenceCheck() {
        assertTrue(CloudObjectStorageService.isIndeterminateImmutableCreateResponse("InvalidResponse"));
        assertTrue(CloudObjectStorageService.isIndeterminateImmutableCreateResponse("invalidresponse"));
        assertFalse(CloudObjectStorageService.isIndeterminateImmutableCreateResponse(null));
        assertFalse(CloudObjectStorageService.isIndeterminateImmutableCreateResponse("AccessDenied"));
    }
}
