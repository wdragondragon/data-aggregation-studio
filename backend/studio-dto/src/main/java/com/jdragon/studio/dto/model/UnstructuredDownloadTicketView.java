package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.Instant;

@Data
public class UnstructuredDownloadTicketView {
    private String ticket;
    private String fileName;
    private Boolean archive;
    private Long contentLength;
    private Instant expiresAt;
}
