package com.jdragon.studio.infra.service;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.io.OutputStream;

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
    public void putFile(String bucket, String objectKey, Path source, String contentType) {
        cloudObjectStorageService.putFile(bucket, objectKey, source, contentType);
    }

    @Override
    public void downloadTo(String bucket, String objectKey, Path target) {
        cloudObjectStorageService.downloadTo(bucket, objectKey, target);
    }

    @Override
    public void downloadTo(String bucket, String objectKey, OutputStream output) {
        cloudObjectStorageService.downloadTo(bucket, objectKey, output);
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
