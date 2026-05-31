package com.jdragon.studio.infra.service;

public interface RunLogObjectStore {

    void put(String bucket, String objectKey, byte[] bytes, String contentType);

    byte[] get(String bucket, String objectKey);

    boolean available();
}
