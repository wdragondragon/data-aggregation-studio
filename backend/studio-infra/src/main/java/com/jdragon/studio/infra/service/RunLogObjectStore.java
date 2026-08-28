package com.jdragon.studio.infra.service;

import java.nio.file.Path;
import java.io.OutputStream;

public interface RunLogObjectStore {

    void put(String bucket, String objectKey, byte[] bytes, String contentType);

    /** Streams a local file without materialising the complete object in the JVM heap. */
    default void putFile(String bucket, String objectKey, Path source, String contentType) {
        throw new UnsupportedOperationException("File upload is not supported by this object store");
    }

    /** Downloads an object to a local file without materialising the complete object in the JVM heap. */
    default void downloadTo(String bucket, String objectKey, Path target) {
        throw new UnsupportedOperationException("File download is not supported by this object store");
    }

    /** Streams an object directly to the caller. */
    default void downloadTo(String bucket, String objectKey, OutputStream output) {
        throw new UnsupportedOperationException("Streaming download is not supported by this object store");
    }

    byte[] get(String bucket, String objectKey);

    void delete(String bucket, String objectKey);

    boolean available();
}
