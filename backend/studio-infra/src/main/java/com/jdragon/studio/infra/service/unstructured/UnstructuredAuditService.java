package com.jdragon.studio.infra.service.unstructured;

import com.jdragon.studio.commons.logging.StudioSensitiveLogSanitizer;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.request.UnstructuredOperationRequest;
import com.jdragon.studio.infra.entity.UnstructuredOpAuditEntity;
import com.jdragon.studio.infra.mapper.UnstructuredOpAuditMapper;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;

@Service
public class UnstructuredAuditService {
    private static final Logger log = LoggerFactory.getLogger(UnstructuredAuditService.class);
    private static final int MAX_AUDIT_MESSAGE_LENGTH = 1800;
    private static final String AUDIT_MESSAGE_TRUNCATED_SUFFIX = " ...[truncated]";
    private static final int MAX_SANITIZED_STACK_TRACE_LENGTH = 12 * 1024;
    private static final int MAX_SANITIZED_ERROR_MESSAGE_LENGTH = 2 * 1024;
    private static final String STACK_TRACE_TRUNCATED_SUFFIX = "\n...[truncated]";

    private final UnstructuredOpAuditMapper auditMapper;
    private final StudioSecurityService securityService;

    public UnstructuredAuditService(UnstructuredOpAuditMapper auditMapper,
                                    StudioSecurityService securityService) {
        this.auditMapper = auditMapper;
        this.securityService = securityService;
    }

    public void recordSafely(DataSourceDefinition datasource,
                             UnstructuredOperationRequest request,
                             String operation,
                             String sourcePath,
                             String targetPath,
                             String status,
                             String message) {
        try {
            UnstructuredOpAuditEntity audit = baseAudit(datasource, request.getRuntimeClusterId(),
                    securityService.currentUserId(), securityService.currentUsername(), operation,
                    sourcePath, targetPath, Boolean.TRUE.equals(request.getRecursiveConfirmed()),
                    status, message);
            auditMapper.insert(audit);
        } catch (RuntimeException auditException) {
            logFailure(datasource, operation, status, auditException);
        }
    }

    public void recordSafely(DataSourceDefinition datasource,
                             Long runtimeClusterId,
                             Long userId,
                             String username,
                             String operation,
                             String sourcePath,
                             String targetPath,
                             boolean recursive,
                             String status,
                             String message) {
        try {
            auditMapper.insert(baseAudit(datasource, runtimeClusterId, userId, username,
                    operation, sourcePath, targetPath, recursive, status, message));
        } catch (RuntimeException auditException) {
            logFailure(datasource, operation, status, auditException);
        }
    }

    private UnstructuredOpAuditEntity baseAudit(DataSourceDefinition datasource,
                                                 Long runtimeClusterId,
                                                 Long userId,
                                                 String username,
                                                 String operation,
                                                 String sourcePath,
                                                 String targetPath,
                                                 boolean recursive,
                                                 String status,
                                                 String message) {
        UnstructuredOpAuditEntity audit = new UnstructuredOpAuditEntity();
        audit.setTenantId(datasource.getTenantId());
        audit.setProjectId(datasource.getProjectId());
        audit.setDatasourceId(datasource.getId());
        audit.setRuntimeClusterId(runtimeClusterId);
        audit.setUserId(userId);
        audit.setUsername(username);
        audit.setOperation(operation);
        audit.setSourcePath(sourcePath);
        audit.setTargetPath(targetPath);
        audit.setRecursive(recursive ? 1 : 0);
        audit.setStatus(status);
        audit.setMessage(auditMessage(message));
        return audit;
    }

    private void logFailure(DataSourceDefinition datasource,
                            String operation,
                            String status,
                            RuntimeException auditException) {
        log.warn("[UF_AUDIT_FAILED] Non-structured operation audit persistence failed operation={} "
                        + "datasourceId={} status={} exceptionType={} message={} stackTrace={}",
                operation, datasource == null ? null : datasource.getId(), status,
                auditException.getClass().getName(),
                sanitizedErrorMessage(auditException.getMessage()),
                sanitizedStackTrace(auditException));
    }

    private String auditMessage(String message) {
        if (message == null) {
            return null;
        }
        String normalized = message.trim();
        if (normalized.length() <= MAX_AUDIT_MESSAGE_LENGTH) {
            return normalized;
        }
        return normalized.substring(0,
                MAX_AUDIT_MESSAGE_LENGTH - AUDIT_MESSAGE_TRUNCATED_SUFFIX.length())
                + AUDIT_MESSAGE_TRUNCATED_SUFFIX;
    }

    public static String sanitizedStackTrace(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        StringWriter buffer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(buffer));
        String sanitized = StudioSensitiveLogSanitizer.sanitize(buffer.toString());
        if (sanitized == null || sanitized.length() <= MAX_SANITIZED_STACK_TRACE_LENGTH) {
            return sanitized;
        }
        int prefixLength = Math.max(0,
                MAX_SANITIZED_STACK_TRACE_LENGTH - STACK_TRACE_TRUNCATED_SUFFIX.length());
        return sanitized.substring(0, prefixLength) + STACK_TRACE_TRUNCATED_SUFFIX;
    }

    public static String sanitizedErrorMessage(String message) {
        return StudioSensitiveLogSanitizer.sanitizeSingleLine(
                message, MAX_SANITIZED_ERROR_MESSAGE_LENGTH);
    }
}
