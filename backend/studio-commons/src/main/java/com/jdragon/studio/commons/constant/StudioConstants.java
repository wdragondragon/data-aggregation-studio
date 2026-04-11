package com.jdragon.studio.commons.constant;

public final class StudioConstants {
    public static final String DEFAULT_TENANT_ID = "default";
    public static final String DEFAULT_TENANT_NAME = "Default Tenant";
    public static final String DEFAULT_PROJECT_CODE = "default";
    public static final String DEFAULT_PROJECT_NAME = "Default Project";
    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    public static final String DEFAULT_ADMIN_USERNAME = "admin";
    public static final String DEFAULT_ADMIN_PASSWORD = "admin123";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ROLE_TENANT_ADMIN = "TENANT_ADMIN";
    public static final String ROLE_PROJECT_ADMIN = "PROJECT_ADMIN";
    public static final String ROLE_PROJECT_MEMBER = "PROJECT_MEMBER";
    public static final String RESOURCE_TYPE_DATASOURCE = "DATASOURCE";
    public static final String RESOURCE_TYPE_DATA_MODEL = "DATA_MODEL";
    public static final String RESOURCE_TYPE_COLLECTION_TASK = "COLLECTION_TASK";
    public static final String RESOURCE_TYPE_WORKFLOW = "WORKFLOW";
    public static final String RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT = "DATA_DEVELOPMENT_SCRIPT";
    public static final String FOLLOW_TARGET_MODEL_SYNC_TASK = "MODEL_SYNC_TASK";
    public static final String FOLLOW_TARGET_COLLECTION_TASK = "COLLECTION_TASK";
    public static final String FOLLOW_TARGET_COLLECTION_TASK_RUN = "COLLECTION_TASK_RUN";
    public static final String FOLLOW_TARGET_WORKFLOW = "WORKFLOW";
    public static final String FOLLOW_TARGET_WORKFLOW_RUN = "WORKFLOW_RUN";
    public static final String NOTIFICATION_CATEGORY_REGISTRATION_REQUEST = "REGISTRATION_REQUEST";
    public static final String NOTIFICATION_CATEGORY_REGISTRATION_REVIEW = "REGISTRATION_REVIEW";
    public static final String NOTIFICATION_CATEGORY_PROJECT_ACCESS_REQUEST = "PROJECT_ACCESS_REQUEST";
    public static final String NOTIFICATION_CATEGORY_PROJECT_ACCESS_REVIEW = "PROJECT_ACCESS_REVIEW";
    public static final String NOTIFICATION_CATEGORY_MODEL_SYNC_TASK = "MODEL_SYNC_TASK";
    public static final String NOTIFICATION_CATEGORY_COLLECTION_TASK_RUN = "COLLECTION_TASK_RUN";
    public static final String NOTIFICATION_CATEGORY_WORKFLOW_RUN = "WORKFLOW_RUN";
    public static final String NOTIFICATION_CATEGORY_RESOURCE_SHARE = "RESOURCE_SHARE";
    public static final String REGISTRATION_REQUEST_PENDING = "PENDING";
    public static final String REGISTRATION_REQUEST_APPROVED = "APPROVED";
    public static final String REGISTRATION_REQUEST_REJECTED = "REJECTED";
    public static final String REGISTRATION_REQUEST_CANCELLED = "CANCELLED";
    public static final String MEMBER_STATUS_ACTIVE = "ACTIVE";
    public static final String MEMBER_REQUEST_APPLY = "APPLY";
    public static final String MEMBER_REQUEST_INVITE = "INVITE";
    public static final String MEMBER_REQUEST_PENDING = "PENDING";
    public static final String MEMBER_REQUEST_APPROVED = "APPROVED";
    public static final String MEMBER_REQUEST_REJECTED = "REJECTED";
    public static final String MEMBER_REQUEST_ACCEPTED = "ACCEPTED";
    public static final String MEMBER_REQUEST_CANCELLED = "CANCELLED";
    public static final String REQUEST_TENANT_HEADER = "X-Tenant-Id";
    public static final String REQUEST_PROJECT_HEADER = "X-Project-Id";
    public static final String INTERNAL_API_TOKEN_HEADER = "X-Studio-Internal-Token";
    public static final String WORKER_STATUS_ONLINE = "ONLINE";
    public static final long WORKER_HEARTBEAT_TIMEOUT_SECONDS = 30L;
    public static final String MDC_RUN_LOG_ID = "runLogId";
    public static final String MDC_RUN_LOG_PATH = "runLogPath";

    private StudioConstants() {
    }
}
