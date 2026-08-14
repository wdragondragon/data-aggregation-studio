package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.FileTransferBrowserPageView;
import com.jdragon.studio.dto.model.StudioUserOptionView;
import com.jdragon.studio.dto.model.UnstructuredAclEntryView;
import com.jdragon.studio.dto.model.UnstructuredDownloadTicketView;
import com.jdragon.studio.dto.model.UnstructuredOperationResultView;
import com.jdragon.studio.dto.model.UnstructuredPermissionView;
import com.jdragon.studio.dto.model.UnstructuredSourceView;
import com.jdragon.studio.dto.model.UnstructuredUploadResultView;
import com.jdragon.studio.dto.model.request.FileTransferBrowserRequest;
import com.jdragon.studio.dto.model.request.UnstructuredOperationRequest;
import com.jdragon.studio.dto.model.request.UnstructuredArchiveDownloadRequest;
import com.jdragon.studio.dto.model.request.UnstructuredDownloadTicketRequest;
import com.jdragon.studio.dto.model.request.UnstructuredPathAclRequest;
import com.jdragon.studio.dto.model.request.UnstructuredSourceAclRequest;
import com.jdragon.studio.infra.service.UnstructuredManagementService;
import com.jdragon.studio.infra.service.UnstructuredDownloadTicketService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/unstructured-management")
public class UnstructuredManagementController {
    private final UnstructuredManagementService service;
    private final UnstructuredDownloadTicketService downloadTicketService;

    public UnstructuredManagementController(UnstructuredManagementService service,
                                            UnstructuredDownloadTicketService downloadTicketService) {
        this.service = service;
        this.downloadTicketService = downloadTicketService;
    }

    @GetMapping("/sources")
    public Result<List<UnstructuredSourceView>> sources(@RequestParam("runtimeClusterId") Long runtimeClusterId) {
        return Result.success(service.sources(runtimeClusterId));
    }

    @PostMapping("/browser/list")
    public Result<FileTransferBrowserPageView> list(@Valid @RequestBody FileTransferBrowserRequest request) {
        return Result.success(service.browse(request.getRuntimeClusterId(), request.getDatasourceId(),
                request.getPath(), request.getCursor(), request.getPageSize()));
    }

    @GetMapping("/stat")
    public Result<com.jdragon.studio.dto.model.FileTransferFileEntryView> stat(
            @RequestParam("runtimeClusterId") Long runtimeClusterId,
            @RequestParam("datasourceId") Long datasourceId,
            @RequestParam("path") String path) {
        return Result.success(service.statForUpload(runtimeClusterId, datasourceId, path));
    }

    @PostMapping(value = "/upload", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Result<UnstructuredUploadResultView>> upload(
            @RequestParam("runtimeClusterId") Long runtimeClusterId,
            @RequestParam("datasourceId") Long datasourceId,
            @RequestParam("targetPath") String targetPath,
            @RequestParam(value = "overwrite", defaultValue = "false") boolean overwrite,
            HttpServletRequest request) throws java.io.IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength < 0L) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.LENGTH_REQUIRED)
                    .body(Result.error("BAD_REQUEST", "Content-Length header is required"));
        }
        return ResponseEntity.ok(Result.success(service.upload(runtimeClusterId, datasourceId,
                targetPath, overwrite, contentLength, request.getInputStream())));
    }

    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(
            @RequestParam("runtimeClusterId") Long runtimeClusterId,
            @RequestParam("datasourceId") Long datasourceId,
            @RequestParam("path") String path) {
        UnstructuredManagementService.PreparedDownload prepared =
                service.prepareDownload(runtimeClusterId, datasourceId, path);
        var entry = prepared.entry();
        String name = entry.getName() == null ? "download" : entry.getName();
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        StreamingResponseBody body = output -> service.download(prepared, output);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(entry.getSize() == null ? 0L : entry.getSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .body(body);
    }

    @PostMapping("/download/archive")
    public ResponseEntity<StreamingResponseBody> downloadArchive(
            @Valid @RequestBody UnstructuredArchiveDownloadRequest request) {
        UnstructuredManagementService.PreparedArchive prepared = service.prepareArchive(
                request.getRuntimeClusterId(), request.getDatasourceId(), request.getPaths());
        String encoded = URLEncoder.encode(prepared.fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        StreamingResponseBody body = output -> service.downloadArchive(prepared, output);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .body(body);
    }

    @PostMapping("/download-tickets")
    public Result<UnstructuredDownloadTicketView> createDownloadTicket(
            @Valid @RequestBody UnstructuredDownloadTicketRequest request) {
        return Result.success(downloadTicketService.create(request));
    }

    @GetMapping("/download/native")
    public ResponseEntity<StreamingResponseBody> downloadNative(
            @RequestParam("ticket") String ticket) {
        UnstructuredManagementService.PreparedNativeDownload prepared =
                downloadTicketService.consume(ticket);
        String encoded = URLEncoder.encode(prepared.fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        StreamingResponseBody body;
        MediaType contentType;
        if (prepared.archive()) {
            body = output -> service.downloadArchive(prepared.archiveDownload(), output);
            contentType = MediaType.parseMediaType("application/zip");
        } else {
            body = output -> service.download(prepared.download(), output);
            contentType = MediaType.APPLICATION_OCTET_STREAM;
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Accel-Buffering", "no");
        if (!prepared.archive() && prepared.contentLength() != null
                && prepared.contentLength() >= 0L) {
            response.contentLength(prepared.contentLength());
        }
        return response.body(body);
    }

    @PostMapping("/operations")
    public Result<UnstructuredOperationResultView> operation(@Valid @RequestBody UnstructuredOperationRequest request) {
        return Result.success(service.operate(request));
    }

    @GetMapping("/acl/source/{datasourceId}")
    public Result<List<UnstructuredAclEntryView>> sourceAcl(
            @PathVariable("datasourceId") Long datasourceId) {
        return Result.success(service.sourceAcl(datasourceId));
    }

    @PutMapping("/acl/source/{datasourceId}")
    public Result<List<UnstructuredAclEntryView>> replaceSourceAcl(
            @PathVariable("datasourceId") Long datasourceId,
            @RequestBody UnstructuredSourceAclRequest request) {
        return Result.success(service.replaceSourceAcl(datasourceId, request));
    }

    @GetMapping("/acl/path")
    public Result<List<UnstructuredAclEntryView>> pathAcl(@RequestParam("datasourceId") Long datasourceId,
                                                          @RequestParam("path") String path) {
        return Result.success(service.pathAcl(datasourceId, path));
    }

    @PutMapping("/acl/path")
    public Result<List<UnstructuredAclEntryView>> replacePathAcl(@Valid @RequestBody UnstructuredPathAclRequest request) {
        return Result.success(service.replacePathAcl(request.getDatasourceId(), request));
    }

    @DeleteMapping("/acl/{id}")
    public Result<Void> deleteAcl(@PathVariable("id") Long id) {
        service.deleteAcl(id);
        return Result.success(null);
    }

    @GetMapping("/users/options")
    public Result<List<StudioUserOptionView>> userOptions() {
        return Result.success(service.userOptions());
    }

    @GetMapping("/permissions")
    public Result<UnstructuredPermissionView> permissions(@RequestParam("datasourceId") Long datasourceId,
                                                          @RequestParam(value = "path", required = false, defaultValue = "/") String path) {
        return Result.success(service.permissions(datasourceId, path));
    }
}
