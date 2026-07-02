package com.jdragon.studio.infra.service;

import org.springframework.stereotype.Service;

@Service
public class MinioRunLogObjectStore implements RunLogObjectStore {

    private final CloudObjectStorageService cloudObjectStorageService;

    public MinioRunLogObjectStore(CloudObjectStorageService cloudObjectStorageService) {
        this.cloudObjectStorageService = cloudObjectStorageService;
    }

    @Override
    public void put(String bucket, String objectKey, byte[] bytes, String contentType) {
        cloudObjectStorageService.put(bucket, objectKey, bytes, contentType);
    }

    @Override
    public byte[] get(String bucket, String objectKey) {
        return cloudObjectStorageService.get(bucket, objectKey);
    }

    @Override
    public void delete(String bucket, String objectKey) {
        cloudObjectStorageService.delete(bucket, objectKey);
    }

    @Override
    public boolean available() {
        return cloudObjectStorageService.available();
    }
}
