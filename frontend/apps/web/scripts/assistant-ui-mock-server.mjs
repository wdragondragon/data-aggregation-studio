import http from "node:http";

const port = Number(process.env.STUDIO_ASSISTANT_UI_MOCK_PORT || 49152);

const profile = {
  token: "mock-studio-token",
  userId: 1,
  username: "admin",
  displayName: "Studio Admin",
  currentTenantId: "default",
  currentProjectId: 100,
  tenants: [{ tenantId: "default", tenantName: "Default Tenant" }],
  projects: [{ projectId: 100, projectName: "Assistant UI Smoke Project" }],
  systemRoleCodes: ["SUPER_ADMIN"],
  effectiveRoleCodes: ["PROJECT_ADMIN"],
};

const assistantPayloadAudit = {
  chatRequests: 0,
  toolExecuteRequests: 0,
  assistantCapabilityContexts: 0,
  leakedAccessibleCapabilityContexts: 0,
  backendCatalogContexts: 0,
  pageContextContexts: 0,
  legacyFrontendToolContexts: 0,
  lastChatMessage: "",
  lastChatBranch: "",
  lastPageContext: null,
  lastAssistantMemory: null,
  lastToolExecute: null,
  toolExecuteHistory: [],
  lastToolResult: null,
  lastToolResultExecution: null,
};

const legacyFrontendToolCodes = new Set([
  "catalog.capabilities",
  "datasources.options",
  "models.datasourceOptions",
  "models.get",
  "catalog.runtimeOptionSchema",
  "collectionTasks.preview",
]);

const datasourcePage = {
  pageNo: 1,
  pageSize: 20,
  total: 1,
  items: [
    {
      id: 11,
      name: "mock_mysql",
      typeCode: "MYSQL",
      enabled: true,
      connectionStatus: "AVAILABLE",
      description: "Datasource returned by the assistant UI smoke mock server.",
    },
  ],
};

const datasourceDetail = {
  id: 11,
  name: "mock_mysql",
  typeCode: "MYSQL",
  enabled: true,
  connectionStatus: "AVAILABLE",
  host: "127.0.0.1",
  port: 3306,
  databaseName: "studio_smoke",
  description: "Datasource detail returned by the assistant backend gateway.",
};

const runtimeClusterOptions = [
  {
    id: 7,
    code: "CLUSTER_A",
    name: "assistant-online",
    enabled: true,
    preferred: true,
    allowManualOverride: false,
    status: "ONLINE",
    onlineInstanceCount: 1,
  },
  {
    id: 8,
    code: "CLUSTER_B",
    name: "assistant-offline",
    enabled: true,
    preferred: false,
    allowManualOverride: true,
    status: "OFFLINE",
    onlineInstanceCount: 0,
  },
];

function runtimeClusterResult(params) {
  const datasourceIds = Array.isArray(params?.datasourceIds)
    ? params.datasourceIds.map((value) => Number(value))
    : [];
  const modelIds = Array.isArray(params?.modelIds)
    ? params.modelIds.map((value) => Number(value))
    : [];
  const applicableClusterIds = Array.isArray(params?.applicableClusterIds)
    ? params.applicableClusterIds.map((value) => Number(value))
    : [];
  let items = runtimeClusterOptions;
  if (datasourceIds.includes(11) || modelIds.includes(5)) {
    items = [runtimeClusterOptions[0]];
  } else if (applicableClusterIds.length) {
    items = runtimeClusterOptions.filter((item) => applicableClusterIds.includes(Number(item.id)));
  }
  return items;
}

const datasourceLoopPages = {
  1: {
    pageNo: 1,
    pageSize: 1,
    total: 2,
    pages: 2,
    hasMore: true,
    nextPage: 2,
    items: [
      {
        id: 11,
        name: "mock_mysql",
        typeCode: "MYSQL",
        enabled: true,
        connectionStatus: "AVAILABLE",
      },
    ],
  },
  2: {
    pageNo: 2,
    pageSize: 1,
    total: 2,
    pages: 2,
    hasMore: false,
    items: [
      {
        id: 12,
        name: "mock_postgres",
        typeCode: "POSTGRESQL",
        enabled: true,
        connectionStatus: "AVAILABLE",
      },
    ],
  },
};

const datasourceDiscoverResult = {
  models: [
    {
      tableName: "orders",
      physicalName: "orders",
      tableType: "TABLE",
      comment: "Mock order table discovered from the datasource.",
    },
    {
      tableName: "customers",
      physicalName: "customers",
      tableType: "TABLE",
      comment: "Mock customer table discovered from the datasource.",
    },
  ],
  total: 2,
};

const sqlExecutionResult = {
  columns: ["one"],
  rows: [{ one: 1 }],
  rowCount: 1,
};

const qualityIssuePage = {
  pageNo: 1,
  pageSize: 20,
  total: 1,
  items: [
    {
      id: 91,
      issueCode: "QI-91",
      title: "Order amount null rate exceeded threshold",
      status: "OPEN",
      severity: "HIGH",
      datasourceNameSnapshot: "mock_mysql",
      modelNameSnapshot: "ods_order",
    },
  ],
};

const metadataSchemas = [
  {
    id: 701,
    schemaCode: "datasource:mysql8",
    schemaName: "MySQL datasource metadata",
    objectType: "datasource",
    typeCode: "mysql8",
    status: "PUBLISHED",
    fields: [],
  },
];

const statisticsOptions = {
  datasourceType: "MYSQL",
  querySchemas: [
    {
      schemaCode: "business:order",
      schemaName: "Order business metadata",
      scope: "BUSINESS",
      fields: [
        { fieldKey: "status", fieldName: "Order Status", valueType: "STRING" },
      ],
    },
  ],
  targetSchemas: [
    {
      schemaCode: "business:order",
      schemaName: "Order business metadata",
      scope: "BUSINESS",
      fields: [
        { fieldKey: "amount", fieldName: "Order Amount", valueType: "DECIMAL", supportedChartTypes: ["TREND", "BAR"] },
      ],
    },
  ],
};

const systemUserPage = {
  pageNo: 1,
  pageSize: 20,
  total: 1,
  items: [
    {
      id: 1,
      username: "assistant_admin",
      displayName: "Assistant Admin",
      enabled: 1,
    },
  ],
};

const registrationApprovalResult = {
  id: 88,
  status: "APPROVED",
  username: "registered_user",
  displayName: "Registered User",
  reviewComment: "approved by assistant",
  approvedUserId: 188,
  approvedUsername: "registered_user",
};

const notificationPage = {
  pageNo: 1,
  pageSize: 20,
  total: 1,
  items: [
    {
      id: 501,
      category: "ASSISTANT",
      title: "ASSISTANT-NOTICE-501",
      content: "Assistant smoke notification is ready to mark as read.",
      targetType: "DASHBOARD",
      targetPath: "/dashboard",
      read: false,
      createdAt: "2026-07-04T08:00:00",
    },
  ],
};

const notificationMarkReadResult = {
  id: 501,
  title: "ASSISTANT-NOTICE-501",
  read: true,
  readAt: "2026-07-04T08:05:00",
};

const operationCatalog = [
  {
    capabilityCode: "studio.metadata.schemas",
    group: "Metadata",
    path: "/metadata",
    label: "Metadata schemas",
    description: "List and inspect metadata schema definitions.",
    supportsList: true,
    supportsGet: true,
    readTools: [
      {
        tool: "studio.feature.list",
        purpose: "List metadata schema definitions.",
        mutation: false,
        optionalValues: ["includeFields"],
        defaultParams: { path: "/metadata", includeFields: false },
      },
      {
        tool: "studio.feature.get",
        purpose: "Read metadata schema detail.",
        mutation: false,
        requiredValues: ["path"],
        optionalValues: ["id", "schemaId", "schemaCode"],
      },
    ],
    featureActions: [
      {
        tool: "studio.feature.action",
        action: "publish",
        purpose: "Publish a metadata schema after user confirmation.",
        mutation: true,
        requiredValues: ["path", "action", "id"],
      },
    ],
    writePolicy: "Metadata schema mutations require explicit frontend confirmation.",
  },
  {
    capabilityCode: "studio.modelStatistics.query",
    group: "Data assets",
    path: "/statistics",
    label: "Model statistics",
    description: "Read model statistics options and chart data.",
    supportsList: true,
    supportsGet: false,
    readTools: [
      {
        tool: "studio.feature.list",
        purpose: "Read model statistic options or metric charts.",
        mutation: false,
        optionalValues: ["view", "datasourceType", "targetScope", "targetMetaSchemaCode", "targetFieldKey", "chartType", "groups", "statType", "topN", "days", "bucketConfig", "payload"],
        defaultParams: { path: "/statistics", view: "options", targetScope: "BUSINESS" },
      },
    ],
    featureActions: [],
    writePolicy: "Read-only statistics.",
  },
  {
    capabilityCode: "studio.system.manage",
    group: "System",
    path: "/system",
    label: "System management",
    description: "Page system users, registration requests, tenants, projects, members, worker bindings, and resource shares.",
    supportsList: true,
    supportsGet: false,
    readTools: [
      {
        tool: "studio.feature.list",
        purpose: "Page system management resources by resource/view/tab selector.",
        mutation: false,
        optionalValues: ["resource", "view", "tab", "projectId", "resourceType", "pageNo", "pageSize"],
        defaultParams: { path: "/system", resource: "users", pageNo: 1, pageSize: 20 },
      },
    ],
    featureActions: [
      {
        tool: "studio.feature.action",
        path: "/system",
        action: "approve",
        resource: "userRegistrationRequests",
        purpose: "Approve a user registration request after confirmation.",
        mutation: true,
        requiredValues: ["id"],
        optionalValues: ["payload"],
      },
      {
        tool: "studio.feature.action",
        path: "/system",
        action: "reject",
        resource: "userRegistrationRequests",
        purpose: "Reject a user registration request after confirmation.",
        mutation: true,
        requiredValues: ["id"],
        optionalValues: ["payload"],
      },
    ],
    writePolicy: "System management mutations require explicit frontend confirmation.",
  },
  {
    capabilityCode: "studio.notifications.manage",
    group: "Operations",
    path: "/notifications",
    label: "Notifications",
    description: "List notifications, read snapshots, and mark notifications as read.",
    supportsList: true,
    supportsGet: false,
    readTools: [
      {
        tool: "studio.feature.list",
        purpose: "Read notifications, notification snapshot, or unread count.",
        mutation: false,
        optionalValues: ["view", "unreadOnly", "pageNo", "pageSize"],
        defaultParams: { path: "/notifications", view: "list", pageNo: 1, pageSize: 20 },
      },
    ],
    featureActions: [
      {
        tool: "studio.feature.action",
        path: "/notifications",
        action: "markRead",
        purpose: "Mark a notification as read after confirmation.",
        mutation: true,
        requiredValues: ["id"],
      },
      {
        tool: "studio.feature.action",
        path: "/notifications",
        action: "markAllRead",
        purpose: "Mark all notifications as read after confirmation.",
        mutation: true,
        requiredValues: [],
      },
    ],
    writePolicy: "Notification mark-read actions require explicit frontend confirmation.",
  },
  {
    capabilityCode: "studio.runtimeClusters.options",
    group: "Operations",
    path: "/runtime-clusters",
    label: "Runtime clusters",
    description: "List project-authorized runtime clusters and intersect them with datasource/model applicability before execution placement.",
    supportsList: true,
    supportsGet: false,
    readTools: [
      {
        tool: "studio.feature.list",
        purpose: "List authorized runtime clusters, optionally intersected by datasourceIds, modelIds, or applicableClusterIds.",
        mutation: false,
        optionalValues: ["datasourceIds", "modelIds", "applicableClusterIds"],
      },
    ],
    featureActions: [],
    writePolicy: "Read-only project runtime cluster options.",
  },
  {
    capabilityCode: "studio.datasources.manage",
    group: "Data assets",
    path: "/datasources",
    label: "Datasources",
    description: "List datasource connections, inspect details, and review connection test history.",
    supportsList: true,
    supportsGet: true,
    readTools: [
      {
        tool: "studio.feature.list",
        purpose: "Read datasource list.",
        mutation: false,
        optionalValues: ["pageNo", "pageSize", "keyword"],
        defaultParams: { path: "/datasources", pageNo: 1, pageSize: 20 },
      },
      {
        tool: "studio.feature.get",
        purpose: "Read datasource detail.",
        mutation: false,
        requiredValues: ["id"],
      },
    ],
    featureActions: [
      {
        tool: "studio.feature.action",
        path: "/datasources",
        action: "discover",
        purpose: "Discover real physical tables or views from a selected datasource.",
        mutation: false,
        requiredValues: ["id"],
        optionalValues: ["keyword", "pageNo", "pageSize"],
        aliases: ["discoverTables", "listPhysicalTables"],
      },
    ],
    writePolicy: "Datasource mutations require explicit frontend confirmation.",
  },
  {
    capabilityCode: "studio.models.manage",
    group: "Data assets",
    path: "/models",
    label: "Models",
    description: "List registered models, inspect metadata, and sync models from datasources.",
    supportsList: true,
    supportsGet: true,
    readTools: [
      {
        tool: "studio.feature.list",
        purpose: "Read registered model list or datasource model candidates.",
        mutation: false,
        optionalValues: ["keyword", "datasourceId", "datasourceType", "runtimeClusterId", "pageNo", "pageSize", "sortField", "sortOrder"],
        defaultParams: { path: "/models", pageNo: 1, pageSize: 20 },
      },
      {
        tool: "studio.feature.get",
        purpose: "Read model detail.",
        mutation: false,
        requiredValues: ["id"],
        optionalValues: ["view", "lineageLevel"],
      },
    ],
    featureActions: [
      {
        tool: "studio.feature.action",
        path: "/models",
        action: "preview",
        purpose: "Preview model sample rows in a selected runtime cluster.",
        mutation: false,
        requiredValues: ["id", "runtimeClusterId"],
        optionalValues: ["limit"],
      },
      {
        tool: "studio.feature.action",
        path: "/models",
        action: "save",
        purpose: "Save a model definition after confirmation.",
        mutation: true,
        requiredValues: ["payload"],
      },
      {
        tool: "studio.feature.action",
        path: "/models",
        action: "sync",
        purpose: "Sync models from a specified datasource after confirmation.",
        mutation: true,
        requiredValues: ["datasourceId"],
        aliases: ["syncDatasource"],
      },
      {
        tool: "studio.feature.action",
        path: "/models",
        action: "syncSelected",
        purpose: "Sync selected physical locators from a specified datasource after confirmation.",
        mutation: true,
        requiredValues: ["datasourceId", "physicalLocators"],
      },
      {
        tool: "studio.feature.action",
        path: "/models",
        action: "rebuildIndex",
        purpose: "Rebuild the model search index after confirmation.",
        mutation: true,
        requiredValues: [],
        optionalValues: ["datasourceId"],
      },
    ],
    writePolicy: "Model mutations and sync actions require explicit frontend confirmation.",
  },
  {
    capabilityCode: "studio.dataDevelopment.manage",
    group: "Development",
    path: "/data-development",
    label: "Data development",
    description: "List script directories, inspect scripts, execute SQL, run scripts, and save scripts.",
    supportsList: true,
    supportsGet: true,
    readTools: [
      {
        tool: "studio.feature.list",
        purpose: "Read script tree or datasource candidates.",
        mutation: false,
        optionalValues: ["view", "scriptType"],
        defaultParams: { path: "/data-development" },
      },
    ],
    featureActions: [
      {
        tool: "studio.feature.action",
        action: "executeSql",
        resource: "sql",
        purpose: "Execute SQL after user confirmation.",
        mutation: true,
        requiredValues: ["path", "action", "resource", "payload.datasourceId", "payload.content"],
      },
      {
        tool: "studio.feature.action",
        action: "saveScript",
        resource: "scripts",
        purpose: "Save a script after user confirmation.",
        mutation: true,
        requiredValues: ["path", "action", "resource", "payload.fileName", "payload.scriptType", "payload.content"],
      },
    ],
    writePolicy: "SQL execution and script saves require explicit frontend confirmation.",
  },
  {
    capabilityCode: "studio.qualityMetrics.query",
    group: "Quality",
    path: "/quality-metrics",
    label: "Quality metrics",
    description: "Read quality metrics, asset risks, and quality issues.",
    supportsList: true,
    supportsGet: true,
    readTools: [
      {
        tool: "studio.feature.list",
        purpose: "Read quality dashboard, asset risks, or quality issues.",
        mutation: false,
        optionalValues: ["view", "status", "severity", "pageNo", "pageSize"],
        defaultParams: { path: "/quality-metrics", pageNo: 1, pageSize: 20 },
      },
      {
        tool: "studio.feature.get",
        purpose: "Read quality issue or asset detail.",
        mutation: false,
        requiredValues: ["path", "id"],
      },
    ],
    featureActions: [
      {
        tool: "studio.feature.action",
        action: "updateIssueStatus",
        resource: "issues",
        purpose: "Update quality issue status after user confirmation.",
        mutation: true,
        requiredValues: ["path", "action", "resource", "id", "payload.status"],
      },
    ],
    writePolicy: "Quality issue mutations require explicit frontend confirmation.",
  },
];

const portableSkills = [
  {
    schema: "studio.skill.v1",
    portable: true,
    id: "assistant-protocol",
    title: "Studio assistant protocol",
    tags: ["protocol", "plan", "loop", "tool"],
    content: "Use fenced studio-assistant.v1 JSON for every internal tool call instead of provider function_call/tool_calls payloads. Each block includes plan, loop, actions, and controls so the LLM decision is visible before execution.",
    instruction: "Emit studio-assistant-protocol blocks with plan/loop/actions/controls and hide them from visible chat text.",
    agentUsage: "Can be copied into another agent platform as the Studio assistant protocol contract.",
    protocolVersion: "studio-assistant.v1",
    protocolFence: "studio-assistant-protocol",
    protocolSchema: {
      protocol: "studio-assistant.v1",
      required: ["protocol", "plan", "loop", "actions", "controls"],
      protocolRequiredRule: "Every fenced studio-assistant-protocol block must explicitly set protocol to studio-assistant.v1. Blocks without this exact protocol value are invalid and must not be executed or rendered.",
      plan: ["intent", "basis", "requiredObjects", "nextActions"],
      planRequired: ["intent", "basis", "requiredObjects", "nextActions"],
      planRequiredRule: "All four plan fields are required. intent must be non-empty text; basis and nextActions must be non-empty arrays; requiredObjects must be an explicit array and may be empty only when no object is needed.",
      loopRequired: ["status", "autoContinue", "questions", "next", "evidence", "stopReason"],
      loopRequiredRule: "Loop is required for every protocol block. status and stopReason must be non-empty text; autoContinue must be boolean; questions, next, and evidence must be explicit arrays.",
      forbiddenFields: ["toolCalls", "backendToolCalls", "uiControls"],
      forbiddenFieldsRule: "Provider-style or legacy execution fields are forbidden inside studio-assistant.v1. Use actions for executable calls and controls for conversational controls.",
      actionRequired: ["type", "tool", "params"],
      actionRequiredRule: "Every executable action must explicitly include type, tool, and params. Supported action types are frontendTool and backendTool; use params:{} when no parameters are needed.",
      controlRequired: ["type", "title", "paramKey"],
      controlRequiredRule: "Every conversational control must explicitly include type, title, and paramKey. select, choices, and confirm controls must include explicit options.",
      loop: ["status: thinking|tool_pending|user_confirmation_pending|waiting_for_user|completed", "stopReason: waiting_for_tool_result|waiting_for_user_confirmation|missing_user_input|answer_complete"],
      actions: ["frontendTool", "backendTool"],
      frontendTools: ["assistant.context.observe", "assistant.context.read", "assistant.context.search", "assistant.memory.select", "studio.feature.list", "studio.feature.get", "studio.feature.action", "studio.navigation.open", "assistant.script.execute"],
      controls: ["select", "choices", "text", "textarea", "confirm"],
      contextToolContract: "assistant.context.observe reads broad page state; assistant.context.read reads current business context including activeObject, selectedEntity, visible objects, recent candidates, filters, and pagination; assistant.context.search searches existing candidates. None of them call business APIs.",
      toolResultContract: "Feed toolResults into the next assistant request so the LLM decides whether to continue with another action/control or finish with loop.status=completed, autoContinue=false, actions=[], controls=[], stopReason=answer_complete. The gateway must not decide user intent or loop completion.",
      scriptToolContract: "assistant.script.execute only runs registered scriptEntrypoints by entrypointId with stdin-json input.",
    },
    examples: [
      {
        title: "read datasource list",
        fence: "studio-assistant-protocol",
        payload: "{\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"List visible datasources\",\"basis\":[\"User asked for datasource records\"],\"requiredObjects\":[{\"type\":\"feature\",\"path\":\"/datasources\"}],\"nextActions\":[{\"type\":\"tool\",\"tool\":\"studio.feature.list\",\"reason\":\"Read datasource list before summarizing\"}]},\"loop\":{\"mode\":\"goal\",\"status\":\"tool_pending\",\"autoContinue\":true,\"questions\":[{\"id\":\"q1\",\"input\":\"Which datasource records are visible?\",\"status\":\"needs_tool\",\"output\":\"pending tool result\"}],\"next\":[{\"id\":\"step1\",\"type\":\"tool\",\"status\":\"pending\",\"description\":\"Read datasource list.\"}],\"evidence\":[],\"stopReason\":\"waiting_for_tool_result\"},\"actions\":[{\"type\":\"frontendTool\",\"tool\":\"studio.feature.list\",\"params\":{\"path\":\"/datasources\"}}],\"controls\":[]}",
      },
      {
        title: "run field mapping script",
        fence: "studio-assistant-protocol",
        payload: "{\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"Suggest deterministic field mappings\",\"basis\":[\"Source and target fields are already available\"],\"requiredObjects\":[{\"type\":\"scriptEntrypoint\",\"id\":\"field-mapping-suggester\"}],\"nextActions\":[{\"type\":\"tool\",\"tool\":\"assistant.script.execute\",\"reason\":\"Run registered helper\"}]},\"loop\":{\"mode\":\"goal\",\"status\":\"tool_pending\",\"autoContinue\":true,\"questions\":[{\"id\":\"q1\",\"input\":\"Suggest field mappings from known fields.\",\"status\":\"needs_tool\",\"output\":\"pending tool result\"}],\"next\":[{\"id\":\"step1\",\"type\":\"tool\",\"status\":\"pending\",\"description\":\"Run registered helper.\"}],\"evidence\":[],\"stopReason\":\"waiting_for_tool_result\"},\"actions\":[{\"type\":\"frontendTool\",\"tool\":\"assistant.script.execute\",\"params\":{\"entrypointId\":\"field-mapping-suggester\",\"input\":{\"sourceFields\":[\"id\"],\"targetFields\":[\"ID\"]}}}],\"controls\":[]}",
      },
      {
        title: "finish after sufficient toolResults",
        fence: "studio-assistant-protocol",
        payload: "{\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"Finish answer after datasource tool result\",\"basis\":[\"toolResults already contain the requested datasource list\"],\"requiredObjects\":[{\"type\":\"feature\",\"path\":\"/datasources\"}],\"nextActions\":[{\"type\":\"final\",\"reason\":\"Return final answer without another tool call\"}]},\"loop\":{\"mode\":\"goal\",\"status\":\"completed\",\"autoContinue\":false,\"questions\":[{\"id\":\"q1\",\"input\":\"Are toolResults sufficient to answer?\",\"status\":\"answered\",\"output\":\"yes\"}],\"next\":[{\"id\":\"step1\",\"type\":\"final\",\"status\":\"done\",\"description\":\"Return final answer.\"}],\"evidence\":[{\"type\":\"toolResult\",\"interfaceCode\":\"studio.feature.list\",\"path\":\"/datasources\"}],\"stopReason\":\"answer_complete\"},\"actions\":[],\"controls\":[]}",
      },
    ],
  },
  {
    schema: "studio.skill.v1",
    portable: true,
    id: "assistant-loop",
    title: "Question decomposition loop",
    tags: ["question", "loop", "headless-engineer"],
    content: "Split every user input into actionable questions, continue with safe tool calls when context is enough, and avoid stopping after a vague status sentence.",
    instruction: "Use studio-assistant.v1 loop/actions/controls instead of provider-specific function calling.",
    agentUsage: "Can be copied into another agent platform as a Studio assistant skill card.",
  },
  {
    schema: "studio.skill.v1",
    portable: true,
    kind: "assistant.script.skill",
    id: "field-mapping-python-helper",
    title: "Field mapping Python helper",
    tags: ["script", "python", "field-mapping"],
    content: "Controlled offline Python helper for suggesting field mappings from sourceFields and targetFields.",
    instruction: "Use only through a registered script executor after field metadata is available.",
    agentUsage: "Portable script skill manifest for agent platforms that support registered Python helpers.",
    scriptEntrypoints: [
      {
        schema: "studio.script-entrypoint.v1",
        id: "field-mapping-suggester",
        language: "python",
        runtime: "python3",
        entrypoint: "classpath:assistant/python/field_mapping_suggester.py",
        inputMode: "stdin-json",
        outputMode: "stdout-json",
      },
    ],
    safetyPolicy: ["offline-only", "no-network", "no-file-write", "must-be-invoked-by-registered-executor"],
  },
  ...operationCatalog.map((operation) => ({
    schema: "studio.skill.v1",
    portable: true,
    kind: "studio.operation.catalog",
    id: `studio-operation-${operation.capabilityCode}`,
    title: `${operation.label} operation catalog`,
    tags: [operation.group, operation.path],
    path: operation.path,
    content: `Path: ${operation.path}. Description: ${operation.description}. Read tools: ${JSON.stringify(operation.readTools)}. Write policy: ${operation.writePolicy}`,
    instruction: "Use studio-assistant.v1 actions with studio.navigation.open, studio.feature.list, studio.feature.get, or studio.feature.action.",
    agentUsage: "Portable Studio operation card for custom-protocol agents.",
    operation: { schema: "studio.operation.v1", portable: true, ...operation },
    readTools: operation.readTools,
    writePolicy: operation.writePolicy,
  })),
];

const dashboardOverview = {
  datasourceCount: 1,
  publishedWorkflowCount: 0,
  scheduledWorkflowCount: 0,
  onlineCollectionTaskCount: 0,
  queuedTaskCount: 0,
  runningTaskCount: 0,
  failedTaskCount: 0,
  scriptTypeCounts: { SQL: 1, JAVA: 0, PYTHON: 0 },
  executableDatasourceTypes: ["MYSQL"],
  recentWorkflowDefinitions: [],
  recentWorkflowRuns: [],
  recentScripts: [{ id: 1, fileName: "assistant_smoke.sql", scriptType: "SQL" }],
};

const notificationSnapshot = {
  unreadCount: 1,
  recentNotifications: notificationPage.items,
};

const server = http.createServer(async (request, response) => {
  response.setHeader("Access-Control-Allow-Origin", "*");
  response.setHeader("Access-Control-Allow-Headers", "authorization,content-type,x-tenant-id,x-project-id");
  response.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  if (request.method === "OPTIONS") {
    response.writeHead(204);
    response.end();
    return;
  }

  const url = new URL(request.url || "/", "http://127.0.0.1");
  const pathname = url.pathname.replace(/^\/dfs\/data-aggregation-studio/, "");
  try {
    if (pathname === "/api/v1/auth/login" && request.method === "POST") {
      sendJson(response, profile);
      return;
    }
    if (pathname === "/api/v1/auth/me" && request.method === "GET") {
      sendJson(response, profile);
      return;
    }
    if (pathname === "/api/v1/dashboard/overview" && request.method === "GET") {
      sendJson(response, dashboardOverview);
      return;
    }
    if (pathname === "/api/v1/datasources/page" && request.method === "GET") {
      sendJson(response, datasourcePage);
      return;
    }
    if (pathname === "/api/v1/meta-schemas" && request.method === "GET") {
      sendJson(response, []);
      return;
    }
    if (pathname === "/api/v1/catalog/capabilities" && request.method === "GET") {
      sendJson(response, {
        executableSourceTypes: ["MYSQL"],
        executableTargetTypes: ["MYSQL"],
        executableDatasourceTypes: ["MYSQL"],
        sourceCapabilities: [{ typeCode: "MYSQL", readable: true, writable: true }],
      });
      return;
    }
    if (pathname === "/api/v1/notifications/snapshot" && request.method === "GET") {
      sendJson(response, notificationSnapshot);
      return;
    }
    if (pathname === "/api/v1/notifications/stream" && request.method === "GET") {
      sendSse(response, [
        ["message", notificationSnapshot],
        ["heartbeat", { at: new Date().toISOString() }],
      ]);
      return;
    }
    if (pathname === "/api/v1/assistant/operations" && request.method === "GET") {
      sendJson(response, operationCatalog);
      return;
    }
    if (pathname === "/api/v1/assistant/config" && request.method === "GET") {
      if (process.env.STUDIO_ASSISTANT_MOCK_CONFIG_UNAVAILABLE === "true") {
        sendJson(response, { message: "mock assistant config unavailable" }, 404, false);
        return;
      }
      const assistantEnabled = process.env.STUDIO_ASSISTANT_MOCK_LLM_ENABLED !== "false";
      sendJson(response, {
        enabled: assistantEnabled,
        reason: assistantEnabled ? "mock-llm-enabled" : "mock-llm-disabled",
      });
      return;
    }
    if (pathname === "/api/v1/assistant/skills" && request.method === "GET") {
      sendJson(response, portableSkills);
      return;
    }
    if (pathname === "/__assistant-ui-smoke/state" && request.method === "GET") {
      sendJson(response, assistantPayloadAudit);
      return;
    }
    if (pathname === "/api/v1/assistant/tools/execute" && request.method === "POST") {
      const body = await readJson(request);
      const params = isRecord(body.params) ? body.params : {};
      const interfaceCode = String(body.interfaceCode || body.tool || "");
      assistantPayloadAudit.toolExecuteRequests += 1;
      assistantPayloadAudit.lastToolExecute = {
        interfaceCode,
        params,
      };
      assistantPayloadAudit.toolExecuteHistory.push({
        interfaceCode,
        params,
      });
      if (interfaceCode === "studio.feature.list" && String(params.path || "") === "/runtime-clusters") {
        sendJson(response, {
          schema: "studio.tool-result.v1",
          interfaceCode,
          path: "/runtime-clusters",
          executedBy: "backend",
          mutation: false,
          requiresConfirmation: false,
          params,
          data: runtimeClusterResult(params),
        });
        return;
      }
      if (interfaceCode === "studio.feature.list" && String(params.path || "") === "/datasources") {
        if (String(params.keyword || "") === "loop-pages") {
          const pageNo = Number(params.pageNo || 1);
          sendJson(response, {
            schema: "studio.tool-result.v1",
            interfaceCode,
            path: "/datasources",
            executedBy: "backend",
            mutation: false,
            params,
            data: datasourceLoopPages[pageNo] || datasourceLoopPages[2],
          });
          return;
        }
        sendJson(response, {
          schema: "studio.tool-result.v1",
          interfaceCode,
          path: "/datasources",
          executedBy: "backend",
          mutation: false,
          params,
          data: datasourcePage,
        });
        return;
      }
      if (interfaceCode === "studio.feature.get" && String(params.path || "") === "/datasources") {
        sendJson(response, {
          schema: "studio.tool-result.v1",
          interfaceCode,
          path: "/datasources",
          executedBy: "backend",
          mutation: false,
          params,
          data: datasourceDetail,
        });
        return;
      }
      if (interfaceCode === "catalog.capabilities") {
        sendJson(response, {
          schema: "studio.tool-result.v1",
          interfaceCode,
          executedBy: "backend",
          mutation: false,
          requiresConfirmation: false,
          params,
          data: {
            executableSourceTypes: ["MYSQL"],
            executableTargetTypes: ["MYSQL"],
            executableDatasourceTypes: ["MYSQL"],
            sourceCapabilities: [{ typeCode: "MYSQL", readable: true, writable: true }],
          },
        });
        return;
      }
      if (interfaceCode === "datasources.options") {
        sendJson(response, {
          schema: "studio.tool-result.v1",
          interfaceCode,
          executedBy: "backend",
          mutation: false,
          requiresConfirmation: false,
          params,
          data: datasourcePage.items,
        });
        return;
      }
      if (interfaceCode === "studio.feature.list" && String(params.path || "") === "/metadata") {
        sendJson(response, {
          schema: "studio.tool-result.v1",
          interfaceCode,
          path: "/metadata",
          executedBy: "backend",
          mutation: false,
          params,
          data: metadataSchemas,
        });
        return;
      }
      if (interfaceCode === "studio.feature.list" && String(params.path || "") === "/statistics") {
        sendJson(response, {
          schema: "studio.tool-result.v1",
          interfaceCode,
          path: "/statistics",
          executedBy: "backend",
          mutation: false,
          params,
          data: statisticsOptions,
        });
        return;
      }
      if (interfaceCode === "studio.feature.list"
        && String(params.path || "") === "/system"
        && String(params.resource || params.view || params.tab || "") === "users") {
        sendJson(response, {
          schema: "studio.tool-result.v1",
          interfaceCode,
          path: "/system",
          executedBy: "backend",
          mutation: false,
          params,
          data: systemUserPage,
        });
        return;
      }
      if (interfaceCode === "studio.feature.list" && String(params.path || "") === "/notifications") {
        sendJson(response, {
          schema: "studio.tool-result.v1",
          interfaceCode,
          path: "/notifications",
          executedBy: "backend",
          mutation: false,
          params,
          data: notificationPage,
        });
        return;
      }
      if (interfaceCode === "studio.feature.list"
        && String(params.path || "") === "/quality-metrics"
        && String(params.view || "") === "issues") {
        sendJson(response, {
          schema: "studio.tool-result.v1",
          interfaceCode,
          path: "/quality-metrics",
          executedBy: "backend",
          mutation: false,
          params,
          data: qualityIssuePage,
        });
        return;
      }
      if (interfaceCode === "assistant.script.execute"
        && String(params.entrypointId || "") === "field-mapping-suggester") {
        sendJson(response, {
          schema: "studio.tool-result.v1",
          interfaceCode,
          entrypointId: "field-mapping-suggester",
          executedBy: "worker",
          runtimeClusterId: 7,
          mutation: false,
          requiresConfirmation: false,
          params,
          data: {
            schema: "studio.script-result.v1",
            success: true,
            entrypointId: "field-mapping-suggester",
            language: "python",
            data: {
              mappings: [
                { targetField: "ID", sourceField: "id", strategy: "normalized-name-match", confidence: 1 },
                { targetField: "orderNo", sourceField: "order_no", strategy: "normalized-name-match", confidence: 1 },
              ],
              unresolvedTargetFields: ["missing_col"],
              summary: {
                sourceFieldCount: 3,
                targetFieldCount: 3,
                mappedCount: 2,
                unresolvedCount: 1,
              },
            },
          },
        });
        return;
      }
      if (interfaceCode === "studio.feature.action"
        && String(params.path || "") === "/datasources"
        && String(params.action || "") === "discover") {
        sendJson(response, {
          schema: "studio.tool-result.v1",
          interfaceCode,
          path: "/datasources",
          action: "discover",
          executedBy: "backend",
          mutation: false,
          requiresConfirmation: false,
          params,
          data: datasourceDiscoverResult,
        });
        return;
      }
      if (interfaceCode === "studio.feature.action"
        && String(params.path || "") === "/models"
        && String(params.action || "") === "preview") {
        sendJson(response, {
          schema: "studio.tool-result.v1",
          interfaceCode,
          path: "/models",
          action: "preview",
          executedBy: "backend",
          mutation: false,
          requiresConfirmation: false,
          params,
          data: {
            detail: { id: params.id, name: "orders", physicalName: "orders" },
            previewRows: [{ id: 1, order_no: "A-001" }],
            sampleRows: [{ id: 1, order_no: "A-001" }],
            previewRowCount: 1,
          },
        });
        return;
      }
      if (interfaceCode === "studio.feature.action"
        && String(params.path || "") === "/notifications"
        && String(params.action || "") === "markRead") {
        if (params.confirmed !== true) {
          sendJson(response, {
            schema: "studio.tool-result.v1",
            interfaceCode,
            path: "/notifications",
            action: "markRead",
            executedBy: "backend",
            mutation: true,
            requiresConfirmation: true,
            params,
            data: {
              requiresConfirmation: true,
              path: "/notifications",
              action: "markRead",
              message: "assistant feature action requires confirmed=true before backend execution",
            },
          });
          return;
        }
        sendJson(response, {
          schema: "studio.tool-result.v1",
          interfaceCode,
          path: "/notifications",
          action: "markRead",
          executedBy: "backend",
          mutation: true,
          requiresConfirmation: false,
          params,
          data: notificationMarkReadResult,
        });
        return;
      }
      if (interfaceCode === "studio.feature.action"
        && String(params.path || "") === "/system"
        && String(params.resource || "") === "userRegistrationRequests"
        && String(params.action || "") === "approve") {
        if (params.confirmed !== true) {
          sendJson(response, {
            schema: "studio.tool-result.v1",
            interfaceCode,
            path: "/system",
            action: "approve",
            resource: "userRegistrationRequests",
            executedBy: "backend",
            mutation: true,
            requiresConfirmation: true,
            params,
            data: {
              requiresConfirmation: true,
              path: "/system",
              action: "approve",
              resource: "userRegistrationRequests",
              message: "assistant feature action requires confirmed=true before backend execution",
            },
          });
          return;
        }
        sendJson(response, {
          schema: "studio.tool-result.v1",
          interfaceCode,
          path: "/system",
          action: "approve",
          resource: "userRegistrationRequests",
          executedBy: "backend",
          mutation: true,
          requiresConfirmation: false,
          params,
          data: registrationApprovalResult,
        });
        return;
      }
      if (interfaceCode === "studio.feature.action"
        && String(params.path || "") === "/data-development"
        && String(params.resource || "") === "sql"
        && String(params.action || "") === "executeSql") {
        if (params.confirmed !== true) {
          sendJson(response, {
            schema: "studio.tool-result.v1",
            interfaceCode,
            path: "/data-development",
            action: "executeSql",
            resource: "sql",
            executedBy: "backend",
            mutation: true,
            requiresConfirmation: true,
            params,
            data: {
              requiresConfirmation: true,
              path: "/data-development",
              action: "executeSql",
              resource: "sql",
              message: "assistant feature action requires confirmed=true before backend execution",
            },
          });
          return;
        }
        sendJson(response, {
          schema: "studio.tool-result.v1",
          interfaceCode,
          path: "/data-development",
          action: "executeSql",
          resource: "sql",
          executedBy: "backend",
          mutation: true,
          requiresConfirmation: false,
          params,
          data: sqlExecutionResult,
        });
        return;
      }
      if (interfaceCode === "studio.feature.action"
        && String(params.path || "") === "/data-development"
        && String(params.resource || "") === "scripts"
        && String(params.action || "") === "saveScript") {
        sendJson(response, { message: "assistant backend action tool does not support mocked saveScript" }, 200, false);
        return;
      }
      sendJson(response, {
        schema: "studio.tool-result.v1",
        interfaceCode,
        path: String(params.path || ""),
        executedBy: "backend",
        mutation: false,
        params,
        data: { pageNo: 1, pageSize: 20, total: 0, items: [] },
      });
      return;
    }
    if (pathname === "/api/v1/data-development/scripts" && request.method === "POST") {
      sendJson(response, {
        marker: "FRONTEND_FALLBACK_SHOULD_NOT_RUN",
        message: "This endpoint is only here to prove the assistant did not bypass backend rejection.",
      });
      return;
    }
    if (pathname === "/api/v1/assistant/chat/stream" && request.method === "POST") {
      const body = await readJson(request);
      recordAssistantPayload(body);
      sendAssistantStream(response, body);
      return;
    }
    sendJson(response, emptyPayloadFor(pathname));
  } catch (error) {
    sendJson(response, { message: error instanceof Error ? error.message : "mock server error" }, 500, false);
  }
});

server.listen(port, "127.0.0.1", () => {
  console.log(`Assistant UI mock API listening on http://127.0.0.1:${port}`);
});

function sendAssistantStream(response, body) {
  const message = String(body?.message || "");
  const language = String(body?.responseLanguage || "zh");
  const context = isRecord(body?.context) ? body.context : {};
  const pageContext = isRecord(context.pageContext) ? context.pageContext : {};
  const activePageObject = isRecord(pageContext.activeObject) ? pageContext.activeObject : {};
  assistantPayloadAudit.lastChatMessage = message;
  assistantPayloadAudit.lastChatBranch = "unmatched";
  response.writeHead(200, {
    "Content-Type": "text/event-stream; charset=utf-8",
    "Cache-Control": "no-cache",
    Connection: "close",
  });
  writeSse(response, "progress", {
    stage: "assistant.mock.planning",
    title: "Mock assistant planner",
    detail: "Planning assistant UI smoke response.",
    status: "running",
  });
  const latestToolResult = latestToolResultFromBody(body);
  const isProtocolRepairRequest = /缺少必需的 plan|缺少必需的 loop|plan 或 loop 缺失|plan 缺失或不完整|missing required plan|missing required loop|incomplete plan|incomplete loop|plan\{intent,basis,requiredObjects,nextActions\}|loop\{mode,status,autoContinue,questions,next,evidence,stopReason\}|协议自检|protocol violation/i.test(message);
  const isToolFollowUp = !isProtocolRepairRequest && (message.includes("接口结果")
    || message.includes("最近一次接口")
    || message.includes("previous tool")
    || message.includes("latest tool"));
  if (isToolFollowUp
    && latestToolResult?.ok === true
    && latestToolResult?.interfaceCode === "studio.feature.list"
    && latestToolResult?.params?.path === "/runtime-clusters") {
    assistantPayloadAudit.lastChatBranch = "tool-follow-up-runtime-cluster-resolved";
    writeSse(response, "delta", {
      content: language === "en"
        ? "Runtime Cluster Resolution Result\nThe authorized runtime cluster candidates were resolved from the supplied resource evidence. The unique candidate is now available in assistant memory for the next cluster-scoped action."
        : "运行集群解析结果\n已根据提供的资源证据得到项目授权的运行集群候选。唯一候选已写入助手记忆，可用于下一步集群范围操作。",
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/用户已经选择当前运行集群|selected runtime cluster|runtime cluster selection/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "runtime-cluster-selection-complete";
    writeSse(response, "delta", {
      content: language === "en"
        ? "Runtime Cluster Selection Result\nThe explicitly selected runtime cluster remains in assistant memory. No preferred or online fallback was applied."
        : "运行集群选择结果\n用户明确选择的运行集群已保留在助手记忆中，没有按首选标记、在线状态或列表顺序改投其他集群。",
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (isToolFollowUp
    && latestToolResult?.ok === false
    && legacyFrontendToolCodes.has(String(latestToolResult.interfaceCode || ""))) {
    assistantPayloadAudit.lastChatBranch = "tool-follow-up-legacy-tool-repair";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["Which generic declarative Studio tool should replace the rejected legacy tool name?"],
        next: ["Repair the failed tool call by reading datasource candidates through studio.feature.list."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "Repair rejected legacy datasource options tool with the generic operation-catalog read tool.",
          params: { path: "/datasources" },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: language === "en"
        ? `Question Breakdown\n1. Why was the legacy tool rejected?\n2. Which operation-catalog tool should replace it?\n\nLegacy Tool Repair\nThe previous narrow tool name is not exposed to the Web LLM. I will use the generic Studio feature list tool instead.\n\n\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``
        : `问题拆解\n1. 旧工具名为什么被拒绝？\n2. 应该改用哪个 operation catalog 工具？\n\n旧工具修复\nWeb LLM 不再暴露这个窄工具名，我会改用通用 Studio 功能列表工具。\n\n\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (isToolFollowUp
    && latestToolResult?.ok === false
    && latestToolResult?.interfaceCode === "studio.feature.action"
    && /payload\.scriptType/.test(String(latestToolResult.error || ""))) {
    assistantPayloadAudit.lastChatBranch = "tool-follow-up-missing-script-type";
    writeSse(response, "delta", {
      content: language === "en"
        ? "Question Breakdown\n1. Did the browser infer scriptType from SQL text or file name?\n2. What should happen when an LLM action omits a required script parameter?\n\nScript Parameter Validation Result\nThe frontend did not infer scriptType locally. It returned the missing payload.scriptType error to the LLM so the LLM can repair the protocol or ask the user."
        : "问题拆解\n1. 浏览器是否根据 SQL 文本或文件名推断了 scriptType？\n2. LLM 动作缺少脚本必填参数时应该怎样处理？\n\n脚本参数校验结果\n前端没有本地推断 scriptType，而是把缺少 payload.scriptType 的错误回灌给 LLM，由 LLM 修复协议或询问用户。",
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (isToolFollowUp
    && latestToolResult?.ok === false
    && latestToolResult?.interfaceCode === "studio.feature.action"
    && /payload\.(?:datasourceId|content)/.test(String(latestToolResult.error || ""))) {
    assistantPayloadAudit.lastChatBranch = "tool-follow-up-missing-data-development-payload";
    writeSse(response, "delta", {
      content: language === "en"
        ? "Question Breakdown\n1. Did the browser move top-level datasourceId into payload.datasourceId?\n2. Did it reinterpret payload.sql as payload.content?\n\nData Development Payload Validation Result\nThe frontend preserved the LLM-provided payload shape. It did not move top-level datasourceId or rewrite payload.sql, and returned the missing payload.datasourceId / payload.content error to the LLM before confirmation or backend gateway execution."
        : "问题拆解\n1. 浏览器是否把顶层 datasourceId 移入 payload.datasourceId？\n2. 是否把 payload.sql 改写成 payload.content？\n\n数据开发 Payload 校验结果\n前端保留了 LLM 提供的 payload 形状，没有移动顶层 datasourceId，也没有把 payload.sql 改写成 payload.content，而是在确认或后端网关执行前把缺少 payload.datasourceId / payload.content 的错误回灌给 LLM。",
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (isToolFollowUp
    && latestToolResult?.ok === false
    && latestToolResult?.interfaceCode === "studio.feature.action"
    && /未声明参数|does not declare parameter/.test(String(latestToolResult.error || ""))
    && (Object.prototype.hasOwnProperty.call(latestToolResult.params || {}, "data")
      || Object.prototype.hasOwnProperty.call(latestToolResult.params || {}, "name")
      || Object.prototype.hasOwnProperty.call(latestToolResult.params || {}, "search"))) {
    assistantPayloadAudit.lastChatBranch = "tool-follow-up-action-undeclared-param";
    writeSse(response, "delta", {
      content: language === "en"
        ? "Question Breakdown\n1. Did the browser treat data as payload?\n2. Did it treat name/search as keyword for a feature action?\n\nAction Declared Parameter Validation Result\nThe frontend preserved the LLM-provided action params and returned the undeclared parameter error before confirmation or backend gateway execution. It did not inject payload or keyword."
        : "问题拆解\n1. 浏览器是否把 data 当成 payload？\n2. 是否把 name/search 当成动作 keyword？\n\n动作声明参数校验结果\n前端保留了 LLM 提供的 action 参数，并在确认或后端网关执行前返回未声明参数错误；没有注入 payload 或 keyword。",
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (isToolFollowUp
    && latestToolResult?.ok === false
    && latestToolResult?.interfaceCode === "studio.feature.action"
    && /action/.test(String(latestToolResult.error || ""))
    && Object.prototype.hasOwnProperty.call(latestToolResult.params || {}, "operation")) {
    assistantPayloadAudit.lastChatBranch = "tool-follow-up-missing-action";
    writeSse(response, "delta", {
      content: language === "en"
        ? "Question Breakdown\n1. Did the browser treat operation as action?\n2. What happens when the LLM omits the canonical action field?\n\nAction Parameter Validation Result\nThe frontend did not treat operation as action. It returned the missing action error to the LLM before confirmation or backend gateway execution."
        : "问题拆解\n1. 浏览器是否把 operation 当成 action？\n2. LLM 缺少规范 action 字段时应该怎样处理？\n\n动作参数校验结果\n前端没有把 operation 当成 action，而是在确认或后端网关执行前把缺少 action 的错误回灌给 LLM。",
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (isToolFollowUp
    && latestToolResult?.ok === false
    && latestToolResult?.interfaceCode === "studio.feature.action"
    && latestToolResult?.params?.path === "/models"
    && latestToolResult?.params?.action === "preview"
    && /runtimeClusterId/.test(String(latestToolResult.error || ""))) {
    assistantPayloadAudit.lastChatBranch = "tool-follow-up-missing-model-preview-cluster";
    writeSse(response, "delta", {
      content: language === "en"
        ? "Question Breakdown\n1. Did the browser send model preview without a runtime cluster?\n2. Was it blocked before backend execution?\n\nModel Preview Cluster Validation Result\nThe frontend rejected the preview before the backend gateway because runtimeClusterId is required by the operation catalog."
        : "问题拆解\n1. 浏览器是否发送了缺少运行集群的模型预览？\n2. 是否在后端执行前被拦截？\n\n模型预览集群校验结果\noperation catalog 将 runtimeClusterId 声明为必填，前端已在后端网关执行前拒绝本次预览。",
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (isToolFollowUp
    && latestToolResult?.ok === false
    && latestToolResult?.interfaceCode === "studio.feature.action"
    && /datasourceId/.test(String(latestToolResult.error || ""))) {
    assistantPayloadAudit.lastChatBranch = "tool-follow-up-missing-datasource-id";
    writeSse(response, "delta", {
      content: language === "en"
        ? "Question Breakdown\n1. Did the browser treat a generic id as datasourceId?\n2. What happens when the LLM omits a required datasourceId?\n\nDatasource Parameter Validation Result\nThe frontend did not treat id as datasourceId. It returned the missing datasourceId error to the LLM before confirmation or backend gateway execution."
        : "问题拆解\n1. 浏览器是否把通用 id 当成 datasourceId？\n2. LLM 缺少必填 datasourceId 时应该怎样处理？\n\n数据源参数校验结果\n前端没有把 id 当成 datasourceId，而是在确认或后端网关执行前把缺少 datasourceId 的错误回灌给 LLM。",
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (isToolFollowUp
    && latestToolResult?.ok === false
    && latestToolResult?.interfaceCode === "studio.feature.get"
    && /\bid\b/.test(String(latestToolResult.error || ""))) {
    assistantPayloadAudit.lastChatBranch = "tool-follow-up-missing-feature-get-id";
    writeSse(response, "delta", {
      content: language === "en"
        ? "Question Breakdown\n1. Did the browser treat recordId as the declarative get id?\n2. What happens when the LLM omits the canonical id field?\n\nFeature Get Parameter Validation Result\nThe frontend did not treat recordId as id. It returned the missing id error to the LLM before backend gateway execution."
        : "问题拆解\n1. 浏览器是否把 recordId 当成声明式详情读取的 id？\n2. LLM 缺少规范 id 字段时应该怎样处理？\n\n详情读取参数校验结果\n前端没有把 recordId 当成 id，而是在后端网关执行前把缺少 id 的错误回灌给 LLM。",
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (isToolFollowUp
    && latestToolResult?.ok === false
    && latestToolResult?.interfaceCode === "studio.feature.list"
    && /path/.test(String(latestToolResult.error || ""))
    && Object.prototype.hasOwnProperty.call(latestToolResult.params || {}, "featurePath")) {
    assistantPayloadAudit.lastChatBranch = "tool-follow-up-missing-feature-list-path";
    writeSse(response, "delta", {
      content: language === "en"
        ? "Question Breakdown\n1. Did the browser treat featurePath as the declarative list path?\n2. What happens when the LLM omits the canonical path field?\n\nFeature List Path Validation Result\nThe frontend did not treat featurePath as path. It returned the missing path error to the LLM before backend gateway execution."
        : "问题拆解\n1. 浏览器是否把 featurePath 当成声明式列表读取的 path？\n2. LLM 缺少规范 path 字段时应该怎样处理？\n\n列表读取路径校验结果\n前端没有把 featurePath 当成 path，而是在后端网关执行前把缺少 path 的错误回灌给 LLM。",
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (isToolFollowUp
    && latestToolResult?.ok === false
    && latestToolResult?.interfaceCode === "studio.feature.list"
    && /未声明参数|does not declare parameter/.test(String(latestToolResult.error || ""))
    && Object.prototype.hasOwnProperty.call(latestToolResult.params || {}, "tableName")) {
    assistantPayloadAudit.lastChatBranch = "tool-follow-up-model-list-undeclared-param";
    writeSse(response, "delta", {
      content: language === "en"
        ? "Question Breakdown\n1. Did the browser treat tableName as keyword for /models?\n2. What happens when the LLM emits an undeclared read parameter?\n\nFeature List Parameter Validation Result\nThe frontend did not treat tableName as keyword. It returned the undeclared parameter error to the LLM before backend gateway execution."
        : "问题拆解\n1. 浏览器是否把 /models 的 tableName 当成 keyword？\n2. LLM 输出未声明读取参数时应该怎样处理？\n\n列表读取参数校验结果\n前端没有把 tableName 当成 keyword，而是在后端网关执行前把未声明参数错误回灌给 LLM。",
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (isToolFollowUp
    && latestToolResult?.ok === false
    && latestToolResult?.interfaceCode === "assistant.context.search"
    && (Object.prototype.hasOwnProperty.call(latestToolResult.params || {}, "featurePath")
      || Object.prototype.hasOwnProperty.call(latestToolResult.params || {}, "query"))) {
    assistantPayloadAudit.lastChatBranch = "tool-follow-up-context-search-alias-param";
    writeSse(response, "delta", {
      content: language === "en"
        ? "Question Breakdown\n1. Did the browser treat featurePath as path for assistant.context.search?\n2. Did it treat query as keyword?\n\nContext Search Parameter Validation Result\nThe frontend did not treat featurePath as path or query as keyword. It returned the validation error to the LLM without calling the backend gateway."
        : "问题拆解\n1. 浏览器是否把 assistant.context.search 的 featurePath 当成 path？\n2. 是否把 query 当成 keyword？\n\n上下文搜索参数校验结果\n前端没有把 featurePath 当成 path，也没有把 query 当成 keyword，而是把校验错误回灌给 LLM，没有调用后端网关。",
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (isToolFollowUp
    && latestToolResult?.ok === false
    && latestToolResult?.interfaceCode === "assistant.memory.select"
    && Object.prototype.hasOwnProperty.call(latestToolResult.params || {}, "recordId")) {
    assistantPayloadAudit.lastChatBranch = "tool-follow-up-memory-select-record-id-param";
    writeSse(response, "delta", {
      content: language === "en"
        ? "Question Breakdown\n1. Did the browser treat recordId as id for assistant.memory.select?\n2. Who should repair the parameter name?\n\nMemory Select ID Validation Result\nThe frontend did not treat recordId as id. It returned the undeclared parameter error to the LLM so the LLM can re-plan with canonical params."
        : "问题拆解\n1. 浏览器是否把 assistant.memory.select 的 recordId 当成 id？\n2. 参数名应该由谁修正？\n\n记忆选择 ID 参数校验结果\n前端没有把 recordId 当成 id，而是把未声明参数错误回灌给 LLM，由 LLM 使用规范参数重新规划。",
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (isToolFollowUp
    && latestToolResult?.ok === false
    && latestToolResult?.interfaceCode === "assistant.memory.select"
    && Object.prototype.hasOwnProperty.call(latestToolResult.params || {}, "tableName")) {
    assistantPayloadAudit.lastChatBranch = "tool-follow-up-memory-select-table-name-param";
    writeSse(response, "delta", {
      content: language === "en"
        ? "Question Breakdown\n1. Did the browser treat tableName as physicalLocator for assistant.memory.select?\n2. Did it select a memory object anyway?\n\nMemory Select Locator Validation Result\nThe frontend did not treat tableName as physicalLocator and did not select a memory object. It returned the validation error to the LLM."
        : "问题拆解\n1. 浏览器是否把 assistant.memory.select 的 tableName 当成 physicalLocator？\n2. 是否仍然选择了记忆对象？\n\n记忆选择物理定位参数校验结果\n前端没有把 tableName 当成 physicalLocator，也没有选择记忆对象，而是把校验错误回灌给 LLM。",
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (isToolFollowUp
    && latestToolResult?.ok === false
    && latestToolResult?.interfaceCode === "assistant.script.execute"
    && /(?:entrypointId|input)/.test(String(latestToolResult.error || ""))
    && (Object.prototype.hasOwnProperty.call(latestToolResult.params || {}, "scriptId")
      || Object.prototype.hasOwnProperty.call(latestToolResult.params || {}, "payload"))) {
    assistantPayloadAudit.lastChatBranch = "tool-follow-up-missing-script-tool-canonical-fields";
    writeSse(response, "delta", {
      content: language === "en"
        ? "Question Breakdown\n1. Did the browser treat scriptId as entrypointId?\n2. Did it treat payload as input?\n\nScript Tool Parameter Validation Result\nThe frontend did not rewrite scriptId or payload for assistant.script.execute. It returned the missing entrypointId/input error to the LLM before backend script execution."
        : "问题拆解\n1. 浏览器是否把 scriptId 当成 entrypointId？\n2. 是否把 payload 当成 input？\n\n脚本工具参数校验结果\n前端没有为 assistant.script.execute 改写 scriptId 或 payload，而是在后端脚本执行前把缺少 entrypointId/input 的错误回灌给 LLM。",
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (isToolFollowUp && latestToolResult?.ok === false) {
    assistantPayloadAudit.lastChatBranch = "tool-follow-up-error";
    writeSse(response, "delta", {
      content: language === "en"
        ? "Question Breakdown\n1. Did Studio's backend gateway accept the action?\n2. Should the browser run a local fallback?\n\nBackend Gateway Rejection\nThe backend gateway rejected the action, so the assistant stopped without running a frontend fallback."
        : "问题拆解\n1. Studio 后端网关是否接受该动作？\n2. 浏览器是否应该本地降级执行？\n\n后端网关拒绝\n后端网关已拒绝该动作，助手已停止，没有执行前端降级调用。",
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (isToolFollowUp) {
    if (latestToolResult?.interfaceCode === "assistant.context.observe") {
      const observed = isRecord(latestToolResult.data) ? latestToolResult.data : {};
      const observedPageContext = isRecord(assistantPayloadAudit.lastPageContext)
        ? assistantPayloadAudit.lastPageContext
        : {};
      const activeObject = isRecord(observedPageContext.activeObject) ? observedPageContext.activeObject : {};
      const routePath = String(observed.routePath || observed.route || "/datasources");
      const pageSource = String(observedPageContext.source || "unknown-page-context");
      const activeName = String(activeObject.name || activeObject.label || "current object");
      assistantPayloadAudit.lastChatBranch = "tool-follow-up-context-observe";
      writeSse(response, "delta", {
        content: language === "en"
          ? `Question Breakdown\n1. What page state did the LLM request before choosing an operation?\n2. Which selected object is visible in pageContext?\n\nPage Context Observation Result\nThe assistant observed route=${routePath}, source=${pageSource}, active datasource ${activeName}, then stopped without calling a backend business tool.`
          : `问题拆解\n1. LLM 在选择操作前请求了哪些页面状态？\n2. pageContext 中当前可见的选中对象是谁？\n\n页面上下文观察结果\n助手观察到 route=${routePath}，source=${pageSource}，当前数据源 ${activeName}，并且没有调用后端业务工具。`,
      });
      writeSse(response, "done", {});
      response.end();
      return;
    }
    if (latestToolResult?.interfaceCode === "assistant.context.read"
      && latestToolResult?.params?.path === "/datasources") {
      const data = isRecord(latestToolResult.data) ? latestToolResult.data : {};
      const activeObject = isRecord(data.activeObject) ? data.activeObject : {};
      const selectedEntity = isRecord(data.selectedEntity) ? data.selectedEntity : {};
      const activeName = String(activeObject.name || selectedEntity.name || "current datasource");
      const candidateCount = Number(data.candidateCount || 0);
      assistantPayloadAudit.lastChatBranch = "tool-follow-up-context-read";
      writeSse(response, "delta", {
        content: language === "en"
          ? `Question Breakdown\n1. Which business object is active in current context?\n2. Did the assistant read context without a backend business API?\n\nBusiness Context Read Result\nThe LLM read the current /datasources business context, found active datasource ${activeName}, and saw ${candidateCount} available context candidate(s) without calling a backend business tool.`
          : `问题拆解\n1. 当前业务上下文里哪个对象处于激活状态？\n2. 助手是否没有调用后端业务 API 就读取了上下文？\n\n业务上下文读取结果\nLLM 读取了当前 /datasources 业务上下文，发现激活数据源 ${activeName}，并看到 ${candidateCount} 个上下文候选；没有调用后端业务工具。`,
      });
      writeSse(response, "done", {});
      response.end();
      return;
    }
    if (latestToolResult?.interfaceCode === "assistant.context.search"
      && latestToolResult?.params?.path === "/datasources") {
      const data = isRecord(latestToolResult.data) ? latestToolResult.data : {};
      const items = Array.isArray(data.items) ? data.items.filter(isRecord) : [];
      const selected = items[0] || {};
      const selectedId = selected.id;
      const selectedName = String(selected.name || "current datasource");
      assistantPayloadAudit.lastChatBranch = "tool-follow-up-context-search-select";
      const content = language === "en"
        ? `Context Search Result\nThe LLM searched current page context candidates and found ${selectedName}. I will select that exact candidate next.\n\n`
        : `上下文对象搜索结果\nLLM 已搜索当前页面上下文候选，并找到 ${selectedName}。下一步会选择这个确切候选。\n\n`;
      const protocol = {
        protocol: "studio-assistant.v1",
        loop: statefulLoop({
          questions: ["Which current context object matched the user's fuzzy object reference?"],
          next: ["Select the exact candidate returned by assistant.context.search."],
        }),
        actions: [
          {
            type: "frontendTool",
            tool: "assistant.memory.select",
            reason: "Select the exact datasource candidate found by context search.",
            params: { path: "/datasources", id: selectedId, name: selectedName },
          },
        ],
        controls: [],
      };
      writeSse(response, "delta", {
        content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
      });
      writeSse(response, "done", {});
      response.end();
      return;
    }
    if (latestToolResult?.interfaceCode === "assistant.memory.select"
      && latestToolResult?.params?.path === "/datasources") {
      const selected = isRecord(latestToolResult.data?.entity) ? latestToolResult.data.entity : {};
      const selectedName = String(selected.name || "current datasource");
      assistantPayloadAudit.lastChatBranch = "tool-follow-up-page-context-memory-select";
      writeSse(response, "delta", {
        content: language === "en"
          ? `Question Breakdown\n1. Which datasource did the LLM select from current page context?\n2. Did the browser avoid a business API fallback?\n\nPage Context Memory Selection Result\nThe assistant selected ${selectedName} from pageContext visible objects without calling a backend business tool.`
          : `问题拆解\n1. LLM 从当前页面上下文选择了哪个数据源？\n2. 浏览器是否避免了业务 API 兜底调用？\n\n页面上下文记忆选择结果\n助手已从 pageContext 可见对象中选择 ${selectedName}，没有调用后端业务工具。`,
      });
      writeSse(response, "done", {});
      response.end();
      return;
    }
    if (latestToolResult?.interfaceCode === "studio.feature.list"
      && latestToolResult?.params?.path === "/datasources"
      && latestToolResult?.params?.keyword === "loop-pages") {
      const data = isRecord(latestToolResult.data) ? latestToolResult.data : {};
      if (data.hasMore === true) {
        assistantPayloadAudit.lastChatBranch = "tool-follow-up-datasource-loop-next-page";
        const content = language === "en"
          ? "The first page says more data is available, so I will request the next page before summarizing.\n\n"
          : "第一页结果显示还有后续数据，我会继续请求下一页后再总结。\n\n";
        const protocol = {
          protocol: "studio-assistant.v1",
          loop: statefulLoop({
            questions: ["Are there more datasource pages after the first controlled tool result?"],
            next: ["Use toolResults.hasMore and nextPage to request the next datasource page."],
          }),
          actions: [
            {
              type: "frontendTool",
              tool: "studio.feature.list",
              reason: "Continue the LLM-controlled pagination loop because toolResults reported hasMore=true.",
              params: {
                path: "/datasources",
                keyword: "loop-pages",
                pageNo: Number(data.nextPage || 2),
                pageSize: 1,
              },
            },
          ],
          controls: [],
        };
        writeSse(response, "delta", {
          content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
        });
        writeSse(response, "done", {});
        response.end();
        return;
      }
      assistantPayloadAudit.lastChatBranch = "tool-follow-up-datasource-loop-complete";
      writeSse(response, "delta", {
        content: language === "en"
          ? "Question Breakdown\n1. Did the first datasource page report hasMore?\n2. Did the LLM request the second page before answering?\n\nPaginated Datasource Loop Result\nThe LLM-controlled loop collected two datasource pages and returned mock_mysql and mock_postgres."
          : "问题拆解\n1. 第一页数据源结果是否报告 hasMore？\n2. LLM 是否在回答前请求了第二页？\n\n分页数据源循环结果\nLLM 控制的循环读取了两页数据源，返回 mock_mysql 和 mock_postgres。",
      });
      writeSse(response, "done", {});
      response.end();
      return;
    }
    if (latestToolResult?.interfaceCode === "studio.feature.list"
      && latestToolResult?.params?.path === "/datasources"
      && latestToolResult?.params?.keyword === "stop-decision") {
      assistantPayloadAudit.lastChatBranch = "tool-follow-up-datasource-stop-complete";
      const protocol = {
        protocol: "studio-assistant.v1",
        plan: {
          intent: "Finish datasource list answer after inspecting toolResults",
          basis: [
            "toolResults contains the datasource list requested by the user",
            "No hasMore flag or follow-up operation is needed for this bounded request",
          ],
          requiredObjects: [{ type: "feature", path: "/datasources" }],
          nextActions: [{ type: "final", reason: "Return the final answer without another tool call" }],
        },
        loop: {
          mode: "goal",
          status: "completed",
          autoContinue: false,
          questions: [
            {
              id: "q1",
              input: "Are the returned datasource records enough to answer the user?",
              status: "answered",
              output: "toolResults returned mock_mysql and no further operation is needed",
            },
          ],
          next: [
            {
              id: "step1",
              type: "final",
              status: "done",
              description: "No further Studio tool call is needed; return the final answer to the user.",
            },
          ],
          evidence: [{ type: "toolResult", interfaceCode: "studio.feature.list", path: "/datasources" }],
          stopReason: "answer_complete",
        },
        actions: [],
        controls: [],
      };
      writeSse(response, "delta", {
        content: language === "en"
          ? `Question Breakdown\n1. Did the datasource list answer the user's request?\n2. Should another Studio operation run?\n\nLLM Stop Decision Result\nThe LLM inspected toolResults, found mock_mysql, and decided no further Studio tool call is needed.\n\n\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``
          : `问题拆解\n1. 数据源列表是否已经回答用户问题？\n2. 是否还需要继续调用 Studio 接口？\n\nLLM 结束决策结果\nLLM 检查 toolResults 后看到 mock_mysql，并判断不需要继续调用 Studio 接口。\n\n\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``,
      });
      writeSse(response, "done", {});
      response.end();
      return;
    }
    if (latestToolResult?.interfaceCode === "studio.feature.action"
      && latestToolResult?.params?.path === "/system"
      && latestToolResult?.params?.action === "approve") {
      assistantPayloadAudit.lastChatBranch = "tool-follow-up-system-approve";
      writeSse(response, "delta", {
        content: language === "en"
          ? "Question Breakdown\n1. Was the registration approval confirmed?\n2. What did the backend gateway return?\n\nRegistration Approval Result\nThe confirmed system action approved registration request 88 for registered_user."
          : "问题拆解\n1. 注册审批是否已确认？\n2. 后端网关返回了什么？\n\n注册审批结果\n已确认的系统动作通过了注册申请 88，用户 registered_user。",
      });
      writeSse(response, "done", {});
      response.end();
      return;
    }
    if (latestToolResult?.interfaceCode === "studio.feature.list"
      && latestToolResult?.params?.path === "/statistics") {
      assistantPayloadAudit.lastChatBranch = "tool-follow-up-statistics";
      writeSse(response, "delta", {
        content: language === "en"
          ? "Question Breakdown\n1. Which statistic option schemas are available?\n2. Did the assistant use the backend gateway instead of direct page APIs?\n\nStatistics Options Result\nThe backend gateway returned statistics options for MYSQL with target schema business:order and target field amount."
          : "问题拆解\n1. 当前可用哪些统计配置元模型？\n2. 助手是否通过后端网关读取而不是直接调用页面 API？\n\n统计配置结果\n后端网关返回 MYSQL 的统计配置，目标元模型 business:order，目标字段 amount。",
      });
      writeSse(response, "done", {});
      response.end();
      return;
    }
    if (latestToolResult?.interfaceCode === "studio.feature.list"
      && latestToolResult?.params?.path === "/notifications") {
      assistantPayloadAudit.lastChatBranch = "tool-follow-up-notifications-list";
      writeSse(response, "delta", {
        content: language === "en"
          ? "Question Breakdown\n1. Which unread notifications are visible?\n2. Did the assistant use the backend gateway for the inbox?\n\nNotification Inbox Result\nThe backend gateway returned 1 unread notification: ASSISTANT-NOTICE-501."
          : "问题拆解\n1. 当前有哪些未读通知？\n2. 助手是否通过后端网关读取通知中心？\n\n通知中心结果\n后端网关返回 1 条未读通知：ASSISTANT-NOTICE-501。",
      });
      writeSse(response, "done", {});
      response.end();
      return;
    }
    if (latestToolResult?.interfaceCode === "studio.feature.action"
      && latestToolResult?.params?.path === "/notifications"
      && latestToolResult?.params?.action === "markRead") {
      assistantPayloadAudit.lastChatBranch = "tool-follow-up-notifications-mark-read";
      writeSse(response, "delta", {
        content: language === "en"
          ? "Question Breakdown\n1. Was the notification mark-read action confirmed?\n2. What did the backend gateway return?\n\nNotification Read Result\nThe confirmed notification action marked ASSISTANT-NOTICE-501 as read."
          : "问题拆解\n1. 通知已读动作是否已确认？\n2. 后端网关返回了什么？\n\n通知已读结果\n已确认的通知动作将 ASSISTANT-NOTICE-501 标记为已读。",
      });
      writeSse(response, "done", {});
      response.end();
      return;
    }
    if (latestToolResult?.interfaceCode === "studio.feature.list"
      && latestToolResult?.params?.path === "/system") {
      assistantPayloadAudit.lastChatBranch = "tool-follow-up-system-list";
      writeSse(response, "delta", {
        content: language === "en"
          ? "Question Breakdown\n1. Which system users are visible?\n2. Did the assistant use the backend gateway instead of the frontend system page APIs?\n\nSystem Users Result\nThe backend gateway returned 1 system user: assistant_admin, Assistant Admin."
          : "问题拆解\n1. 当前可见哪些系统用户？\n2. 助手是否通过后端网关而不是前端系统页面 API 读取？\n\n系统用户结果\n后端网关返回 1 个系统用户：assistant_admin，Assistant Admin。",
      });
      writeSse(response, "done", {});
      response.end();
      return;
    }
    if (latestToolResult?.interfaceCode === "studio.feature.list"
      && latestToolResult?.params?.path === "/metadata") {
      assistantPayloadAudit.lastChatBranch = "tool-follow-up-metadata";
      writeSse(response, "delta", {
        content: language === "en"
          ? "Question Breakdown\n1. Which metadata schemas are available?\n2. Did the assistant read them through the backend gateway?\n\nMetadata Schemas Result\nThe backend gateway returned 1 metadata schema: datasource:mysql8, MySQL datasource metadata."
          : "问题拆解\n1. 当前可见哪些元模型？\n2. 助手是否通过后端网关读取？\n\n元模型结果\n后端网关返回 1 个元模型：datasource:mysql8，MySQL datasource metadata。",
      });
      writeSse(response, "done", {});
      response.end();
      return;
    }
    if (latestToolResult?.interfaceCode === "studio.feature.get"
      && latestToolResult?.params?.path === "/datasources") {
      assistantPayloadAudit.lastChatBranch = "tool-follow-up-datasource-get";
      writeSse(response, "delta", {
        content: language === "en"
          ? "Question Breakdown\n1. Which datasource detail did the LLM decide to read?\n2. Did the backend gateway execute the declarative read?\n\nDatasource Detail Result\nThe backend gateway returned datasource detail for mock_mysql, MYSQL, database studio_smoke. The browser did not call a direct datasource detail API."
          : "问题拆解\n1. LLM 决定读取哪个数据源详情？\n2. 是否由后端网关执行声明式读取？\n\n数据源详情结果\n后端网关返回 mock_mysql 的数据源详情，类型 MYSQL，数据库 studio_smoke。浏览器没有直接调用数据源详情 API。",
      });
      writeSse(response, "done", {});
      response.end();
      return;
    }
    if (latestToolResult?.interfaceCode === "studio.feature.list"
      && latestToolResult?.params?.path === "/quality-metrics") {
      assistantPayloadAudit.lastChatBranch = "tool-follow-up-quality-metrics";
      writeSse(response, "delta", {
        content: language === "en"
          ? "Question Breakdown\n1. Which quality issues are currently visible?\n2. Does the backend gateway handle quality metrics without frontend fallback?\n\nQuality Issues Result\nThe backend gateway returned 1 open quality issue: QI-91, HIGH, Order amount null rate exceeded threshold."
          : "问题拆解\n1. 当前可见哪些质量问题？\n2. 质量指标是否已由后端网关接管而非前端降级？\n\n质量问题结果\n后端网关返回 1 个打开的质量问题：QI-91，HIGH，Order amount null rate exceeded threshold。",
      });
      writeSse(response, "done", {});
      response.end();
      return;
    }
    if (latestToolResult?.interfaceCode === "assistant.script.execute") {
      assistantPayloadAudit.lastChatBranch = "tool-follow-up-script";
      writeSse(response, "delta", {
        content: language === "en"
          ? "Question Breakdown\n1. Can the registered script skill suggest deterministic field mappings?\n2. Which target fields are still unresolved?\n\nField Mapping Script Result\nThe registered Python helper mapped 2 fields and left 1 unresolved target field: missing_col."
          : "问题拆解\n1. 已登记脚本 skill 能否给出确定性的字段映射建议？\n2. 哪些目标字段仍未解析？\n\n字段映射脚本结果\n已登记 Python helper 映射 2 个字段，仍有 1 个目标字段未解析：missing_col。",
      });
      writeSse(response, "done", {});
      response.end();
      return;
    }
    if (latestToolResult?.interfaceCode === "studio.feature.action"
      && latestToolResult?.params?.path === "/datasources"
      && latestToolResult?.params?.action === "discover") {
      assistantPayloadAudit.lastChatBranch = "tool-follow-up-datasource-discover";
      writeSse(response, "delta", {
        content: language === "en"
          ? "Question Breakdown\n1. Which real tables were discovered from the selected datasource?\n2. Did the assistant request all tables without frontend pagination defaults?\n\nDatasource Table Discovery Result\nThe backend gateway returned 2 discovered tables: orders and customers. The discover action params did not include pageNo or pageSize."
          : "问题拆解\n1. 选中的数据源里发现了哪些真实表？\n2. 助手是否在不加前端分页默认值的情况下请求全部表？\n\n数据源表发现结果\n后端网关返回 2 张发现表：orders、customers。本次 discover 动作参数没有包含 pageNo 或 pageSize。",
      });
      writeSse(response, "done", {});
      response.end();
      return;
    }
    if (latestToolResult?.interfaceCode === "studio.feature.action"
      && latestToolResult?.params?.action === "executeSql") {
      assistantPayloadAudit.lastChatBranch = "tool-follow-up-execute-sql";
      writeSse(response, "delta", {
        content: language === "en"
          ? "Question Breakdown\n1. Should the SQL execution be confirmed?\n2. What did the confirmed Studio action return?\n\nSQL Execution Result\nThe confirmed SQL action completed through the backend gateway. rowCount=1, first row one=1."
          : "问题拆解\n1. 是否确认执行 SQL？\n2. Studio 确认动作返回了什么？\n\nSQL 执行结果\n已通过后端网关完成确认后的 SQL 动作。rowCount=1，首行 one=1。",
      });
      writeSse(response, "done", {});
      response.end();
      return;
    }
    assistantPayloadAudit.lastChatBranch = "tool-follow-up-default";
    writeSse(response, "delta", {
      content: language === "en"
        ? "Question Breakdown\n1. Which datasource records are visible?\n\nResult\nThe platform returned 1 datasource: mock_mysql. Next I would inspect the selected datasource only if the user asks for details."
        : "问题拆解\n1. 当前可见哪些数据源？\n\n结果\n平台返回 1 个数据源：mock_mysql。若用户需要详情，下一步再读取选中的数据源。",
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/progress-only loop guard probe/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "progress-only-loop-guard-probe";
    writeSse(response, "delta", {
      content: language === "en"
        ? "I will first read the datasources registered in the current project."
        : "正在读取当前项目已登记的数据源列表。",
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/stalled-loop protocol probe/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "stalled-loop-protocol-probe";
    const protocol = {
      protocol: "studio-assistant.v1",
      plan: {
        intent: "Read datasource records without silently stopping",
        basis: ["The user requested a stalled-loop protocol probe"],
        requiredObjects: [{ type: "feature", path: "/datasources" }],
        nextActions: [{ type: "tool", tool: "studio.feature.list", reason: "Read datasource records" }],
      },
      loop: {
        mode: "goal",
        status: "tool_pending",
        autoContinue: true,
        questions: [{ id: "q1", input: "Which datasource records are visible?", status: "needs_tool", output: "pending" }],
        next: [{ id: "step1", type: "tool", status: "pending", description: "Read datasource records" }],
        evidence: [],
        stopReason: "waiting_for_tool_result",
      },
      actions: [],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `I will continue reading datasource records.\n\n\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (isProtocolRepairRequest) {
    assistantPayloadAudit.lastChatBranch = "protocol-repair";
    if (/assistant-action|旧 assistant-action|旧格式动作|legacy assistant-action/i.test(message)) {
      assistantPayloadAudit.lastChatBranch = "protocol-repair-legacy-assistant-action";
      const content = language === "en"
        ? "I will repair the old action block by re-emitting it as a complete Studio assistant protocol.\n\n"
        : "我会把旧格式动作块修复为完整的 Studio 助手协议。\n\n";
      const protocol = {
        protocol: "studio-assistant.v1",
        loop: statefulLoop({
          questions: ["Which datasource records are visible after repairing the old action block?"],
          next: ["Run the datasource list tool only after the repaired studio-assistant.v1 protocol is present."],
        }),
        actions: [
          {
            type: "frontendTool",
            tool: "studio.feature.list",
            reason: "Repair the old action block by using the current operation-catalog tool contract.",
            params: { path: "/datasources", pageNo: 1, pageSize: 20 },
          },
        ],
        controls: [],
      };
      writeSse(response, "delta", {
        content: `${content}Legacy Assistant Action Repair Result\nThe old action block was rejected before execution; this repaired protocol uses the standard datasource list tool.\n\n\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
      });
      writeSse(response, "done", {});
      response.end();
      return;
    }
    if (/loop-only|actionless|completed loop|无动作/i.test(message)) {
      const content = language === "en"
        ? "I will repair the loop-only protocol by adding the required structured plan and no tool calls.\n\n"
        : "我会为无动作 loop 协议补齐必需的结构化计划，并且不调用任何工具。\n\n";
      const protocol = {
        protocol: "studio-assistant.v1",
        plan: {
          intent: "Finish the answer after validating an actionless loop protocol",
          basis: [
            "The previous studio-assistant.v1 block had loop but no plan",
            "No Studio operation is required for the repaired final state",
          ],
          requiredObjects: [],
          nextActions: [{ type: "final", reason: "Return final answer without any tool call" }],
        },
        loop: {
          mode: "goal",
          status: "completed",
          autoContinue: false,
          questions: [
            {
              id: "q1",
              input: "Can an actionless loop omit plan?",
              status: "answered",
              output: "No. Even final loop state needs plan.",
            },
          ],
          next: [],
          evidence: [],
          stopReason: "answer_complete",
        },
        actions: [],
        controls: [],
      };
      writeSse(response, "delta", {
        content: `${content}Actionless Missing Plan Repair Result\nThe LLM returned a completed loop with a structured plan and no Studio tool call.\n\n\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``,
      });
      writeSse(response, "done", {});
      response.end();
      return;
    }
    const content = language === "en"
      ? "I will re-emit the requested operation as a complete Studio assistant protocol with a structured plan.\n\n"
      : "我会按完整 Studio 助手协议重新输出，并补齐结构化计划。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["Which datasource records are visible after repairing the malformed protocol?"],
        next: ["Run the datasource list tool only after the structured plan is present."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "Repair the missing-plan probe by returning a complete protocol before execution.",
          params: { path: "/datasources", pageNo: 1, pageSize: 20 },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/assistantClarification\.datasourceScope/i.test(message) && /current-page/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "clarification-current-page-datasource";
    const datasourceId = activePageObject.id;
    const datasourceName = String(activePageObject.name || activePageObject.label || "current datasource");
    const content = language === "en"
      ? `I will use the datasource chosen in the clarification answer: ${datasourceName}.\n\n`
      : `我会使用你在澄清回答中选择的数据源：${datasourceName}。\n\n`;
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["Which datasource scope did the user choose from the clarification control?"],
        next: ["Use the current page datasource selected by the user and update assistant memory."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "assistant.memory.select",
          reason: "Use the datasource scope explicitly chosen by the user through the chat control.",
          params: { path: "/datasources", id: datasourceId, name: datasourceName },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/ambiguous datasource|not said which datasource|which datasource should|clarify datasource|没有说明.*数据源|不确定.*数据源|需要.*澄清.*数据源/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "clarification-datasource-scope";
    const content = language === "en"
      ? "I need one clarification before choosing an operation.\n\n"
      : "我需要先澄清一个选择，再决定下一步操作。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "waiting_for_user",
        autoContinue: false,
        stopReason: "missing_user_input",
        questions: ["Which datasource scope should the assistant use?"],
        next: ["Wait for the user to choose a datasource scope in the chat control."],
      }),
      actions: [],
      controls: [
        {
          type: "choices",
          title: "Which datasource should I use?",
          detail: "The request mentions a datasource but does not identify whether to use the current page selection or read the full datasource list first.",
          paramKey: "assistantClarification.datasourceScope",
          options: [
            { label: "Use current page datasource", value: "current-page", description: "Use pageContext.activeObject and do not call a backend business tool yet." },
            { label: "Read datasource list first", value: "read-list", description: "Ask Studio for datasource candidates before selecting." },
          ],
        },
      ],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/missing-plan protocol probe/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "missing-plan-probe";
    const content = language === "en"
      ? "I will emit a protocol probe that is missing plan so the frontend can reject it before execution.\n\n"
      : "我会输出一个缺少 plan 的协议探针，用来验证前端会在执行前拒绝它。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["Will the frontend execute a studio-assistant.v1 action without plan?"],
        next: ["The orchestrator should reject this block and ask the LLM to re-emit a complete plan."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "This should not run because the protocol has no plan.",
          params: { path: "/datasources", pageNo: 1, pageSize: 20 },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/missing-plan loop-only probe|missing-plan actionless probe|loop-only missing plan|actionless missing plan|无动作.*缺少.*plan|缺少.*plan.*无动作/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "missing-plan-loop-only-probe";
    const content = language === "en"
      ? "I will emit a loop-only protocol probe that is missing plan so the frontend must reject it too.\n\n"
      : "我会输出一个只有 loop 且缺少 plan 的协议探针，验证前端同样会拒绝它。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: {
        mode: "goal",
        status: "completed",
        autoContinue: false,
        questions: [
          {
            id: "q1",
            input: "Can a final loop omit plan?",
            status: "answered",
            output: "probe should be rejected",
          },
        ],
        next: [],
        evidence: [],
        stopReason: "answer_complete",
      },
      actions: [],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/incomplete-plan protocol probe/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "incomplete-plan-probe";
    const content = language === "en"
      ? "I will emit a protocol probe whose plan exists but is incomplete so the frontend can reject it before execution.\n\n"
      : "我会输出一个 plan 存在但字段不完整的协议探针，用来验证前端会在执行前拒绝它。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      plan: {
        intent: "Probe incomplete plan rejection before action execution",
        requiredObjects: [{ type: "feature", path: "/datasources" }],
        nextActions: [{ type: "tool", tool: "studio.feature.list", reason: "This should not run because plan.basis is missing." }],
      },
      loop: statefulLoop({
        questions: ["Will the frontend execute a studio-assistant.v1 action when plan.basis is missing?"],
        next: ["The orchestrator should reject this block and ask the LLM to re-emit a complete plan."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "This should not run because plan.basis is missing.",
          params: { path: "/datasources", keyword: "incomplete-plan-original", pageNo: 1, pageSize: 20 },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/missing-loop protocol probe/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "missing-loop-probe";
    const content = language === "en"
      ? "I will emit a protocol probe whose plan is complete but loop is missing so the frontend can reject it before execution.\n\n"
      : "我会输出一个 plan 完整但缺少 loop 的协议探针，用来验证前端会在执行前拒绝它。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      plan: {
        intent: "Probe missing loop rejection before action execution",
        basis: ["The user asked for a missing-loop protocol probe"],
        requiredObjects: [{ type: "feature", path: "/datasources" }],
        nextActions: [{ type: "tool", tool: "studio.feature.list", reason: "This should not run because loop is missing." }],
      },
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "This should not run because the protocol has no loop.",
          params: { path: "/datasources", keyword: "missing-loop-original", pageNo: 1, pageSize: 20 },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/loop stopReason alias probe|stopReason alias|loop reason alias|loop.*reason.*alias/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "loop-stop-reason-alias-probe";
    const content = language === "en"
      ? "I will emit a protocol probe whose loop uses reason instead of canonical stopReason so the frontend can reject it before execution.\n\n"
      : "我会输出一个 loop 使用 reason 而不是规范 stopReason 的协议探针，用来验证前端会在执行前拒绝它。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      plan: {
        intent: "Probe loop stopReason canonical field enforcement before action execution",
        basis: ["The user asked for a loop stopReason alias probe"],
        requiredObjects: [{ type: "feature", path: "/datasources" }],
        nextActions: [{ type: "tool", tool: "studio.feature.list", reason: "This should not run because loop.stopReason is missing." }],
      },
      loop: {
        mode: "goal",
        status: "tool_pending",
        autoContinue: true,
        questions: [{ id: "q1", input: "Will the frontend accept loop.reason as stopReason?", status: "needs_tool", output: "probe should be rejected" }],
        next: [{ id: "step1", type: "tool", status: "pending", description: "read datasource list" }],
        evidence: [],
        reason: "waiting_for_tool_result",
      },
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "This should not run because loop.stopReason is missing.",
          params: { path: "/datasources", keyword: "loop-stop-reason-alias-original", pageNo: 1, pageSize: 20 },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/legacy-in-protocol probe/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "legacy-in-protocol-probe";
    const content = language === "en"
      ? "I will emit a complete protocol probe that incorrectly uses legacy toolCalls so the frontend can reject it before execution.\n\n"
      : "我会输出一个完整但错误使用旧 toolCalls 字段的协议探针，用来验证前端会在执行前拒绝它。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      plan: {
        intent: "Probe legacy field rejection inside studio-assistant protocol",
        basis: ["The user asked for a legacy-in-protocol probe"],
        requiredObjects: [{ type: "feature", path: "/datasources" }],
        nextActions: [{ type: "tool", tool: "studio.feature.list", reason: "This should not run because protocol uses legacy toolCalls." }],
      },
      loop: statefulLoop({
        questions: ["Will the frontend execute legacy toolCalls inside a complete protocol?"],
        next: ["The orchestrator should reject this block and ask the LLM to re-emit actions/controls."],
      }),
      toolCalls: [
        {
          interfaceCode: "studio.feature.list",
          reason: "This should not run because protocol must use actions.",
          params: { path: "/datasources", keyword: "legacy-in-protocol-original", pageNo: 1, pageSize: 20 },
        },
      ],
      actions: [],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/missing-protocol protocol probe/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "missing-protocol-probe";
    const content = language === "en"
      ? "I will emit a fenced protocol-shaped JSON object without protocol=studio-assistant.v1 so the frontend can reject it before execution.\n\n"
      : "我会输出一个 fenced 但缺少 protocol=studio-assistant.v1 的协议形状 JSON，用来验证前端会在执行前拒绝它。\n\n";
    const protocol = {
      plan: {
        intent: "Probe missing protocol version rejection before action execution",
        basis: ["The user asked for a missing-protocol protocol probe"],
        requiredObjects: [{ type: "feature", path: "/datasources" }],
        nextActions: [{ type: "tool", tool: "studio.feature.list", reason: "This should not run because protocol version is missing." }],
      },
      loop: statefulLoop({
        questions: ["Will the frontend execute a fenced JSON object that omits protocol=studio-assistant.v1?"],
        next: ["The orchestrator should reject this block and ask the LLM to re-emit the standard protocol object."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "This should not run because protocol=studio-assistant.v1 is missing.",
          params: { path: "/datasources", keyword: "missing-protocol-original", pageNo: 1, pageSize: 20 },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/loose-protocol-json probe/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "loose-protocol-json-probe";
    const content = language === "en"
      ? "I will emit a complete protocol JSON object without the required fence so the frontend can reject it before execution.\n\n"
      : "我会输出一个完整但没有 fenced 协议块包裹的 JSON，用来验证前端会在执行前拒绝它。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      plan: {
        intent: "Probe loose protocol JSON rejection before execution",
        basis: ["The user asked for a loose-protocol-json probe"],
        requiredObjects: [{ type: "feature", path: "/datasources" }],
        nextActions: [{ type: "tool", tool: "studio.feature.list", reason: "This should not run because the JSON is not fenced." }],
      },
      loop: statefulLoop({
        questions: ["Will the frontend execute a complete protocol JSON object without the required fence?"],
        next: ["The orchestrator should reject loose JSON and ask the LLM to re-emit a fenced protocol block."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "This should not run because the protocol JSON is not fenced.",
          params: { path: "/datasources", keyword: "loose-protocol-original", pageNo: 1, pageSize: 20 },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}${JSON.stringify(protocol)}`,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/malformed-protocol-json probe/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "malformed-protocol-json-probe";
    const content = language === "en"
      ? "I will emit a malformed fenced protocol JSON object so the frontend can reject it before execution.\n\n"
      : "我会输出一个 fenced 协议块，但其中 JSON 损坏，用来验证前端会在执行前拒绝并要求自修复。\n\n";
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n{"protocol":"studio-assistant.v1","plan":{"intent":"Probe malformed JSON rejection","basis":["The user asked for a malformed-protocol-json probe"],"requiredObjects":[{"type":"feature","path":"/datasources"}],"nextActions":[{"type":"tool","tool":"studio.feature.list"}]},"loop":{"mode":"goal","status":"tool_pending","autoContinue":true,"questions":[],"next":[],"evidence":[],"stopReason":"waiting_for_tool_result"},"actions":[{"type":"frontendTool","tool":"studio.feature.list","reason":"This should not run because the JSON is malformed.","params":{"path":"/datasources","keyword":"malformed-protocol-original","pageNo":1,"pageSize":20}}],"controls":[]\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/missing-action-type protocol probe/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "missing-action-type-probe";
    const content = language === "en"
      ? "I will emit a complete protocol probe whose action is missing type so the frontend can reject it before execution.\n\n"
      : "我会输出一个协议探针，其中 action 缺少 type，用来验证前端会在执行前拒绝它。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      plan: {
        intent: "Probe missing action type rejection before execution",
        basis: ["The user asked for a missing-action-type protocol probe"],
        requiredObjects: [{ type: "feature", path: "/datasources" }],
        nextActions: [{ type: "tool", tool: "studio.feature.list", reason: "This should not run because action.type is missing." }],
      },
      loop: statefulLoop({
        questions: ["Will the frontend execute an action without explicit type?"],
        next: ["The orchestrator should reject this block and ask the LLM to re-emit explicit action fields."],
      }),
      actions: [
        {
          tool: "studio.feature.list",
          reason: "This should not run because action.type is missing.",
          params: { path: "/datasources", keyword: "missing-action-type-original", pageNo: 1, pageSize: 20 },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/missing-control-type protocol probe/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "missing-control-type-probe";
    const content = language === "en"
      ? "I will emit a complete protocol probe whose control is missing type so the frontend can reject it before rendering.\n\n"
      : "我会输出一个协议探针，其中 control 缺少 type，用来验证前端会在渲染前拒绝它。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      plan: {
        intent: "Probe missing control type rejection before rendering",
        basis: ["The user asked for a missing-control-type protocol probe"],
        requiredObjects: [{ type: "datasource", status: "ambiguous" }],
        nextActions: [{ type: "control", reason: "This should not render because control.type is missing." }],
      },
      loop: statefulLoop({
        status: "waiting_for_user",
        autoContinue: false,
        stopReason: "missing_user_input",
        questions: ["Will the frontend render a control without explicit type?"],
        next: ["The orchestrator should reject this block and ask the LLM to re-emit explicit control fields."],
      }),
      actions: [],
      controls: [
        {
          title: "Invalid Missing Type Control",
          paramKey: "datasourceId",
          options: [
            { label: "mock_mysql", value: "11" },
            { label: "mock_postgres", value: "12" },
          ],
        },
      ],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/missing-plan control probe/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "missing-plan-control-probe";
    const content = language === "en"
      ? "I will emit a control probe that is missing plan so the frontend can reject it before rendering controls.\n\n"
      : "我会输出一个缺少 plan 的控件探针，用来验证前端会在渲染控件前拒绝它。\n\n";
    const control = {
      type: "select",
      title: "Invalid Missing Plan Control",
      paramKey: "datasourceId",
      options: [{ label: "mock_mysql", value: "11" }],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(control)}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/legacy action block probe/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "legacy-assistant-action-probe";
    const content = language === "en"
      ? "Legacy Assistant Action Probe\nThis old action block must be ignored by the web orchestrator.\n\n"
      : "旧格式动作探针\nWeb 编排器必须忽略这个旧格式动作块。\n\n";
    writeSse(response, "delta", {
      content: `${content}\`\`\`assistant-action\n{"toolCalls":[{"interfaceCode":"studio.feature.list","reason":"This legacy block must not execute.","params":{"path":"/datasources","pageNo":1,"pageSize":20}}]}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/legacy capability probe|legacy catalog capability|历史能力矩阵|能力矩阵探针/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "legacy-datasource-options-probe";
    const content = language === "en"
      ? "I will intentionally emit a legacy datasource options tool name. The Web orchestrator should reject it and ask the LLM to repair the plan with a generic operation-catalog tool.\n\n"
      : "我会故意输出一个历史数据源候选工具名。Web 编排器应拒绝它，并要求 LLM 改用通用 operation catalog 工具修复计划。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["Does the Web assistant still expose legacy narrow read-only tool names to the LLM?"],
        next: ["Emit a legacy tool name once, then verify the next LLM turn repairs it with studio.feature.list."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "datasources.options",
          reason: "Verify legacy read-only tool names are rejected before backend execution.",
          params: {},
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/defaulted params|pagination defaults|default pagination|默认补参|缺省分页/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "datasource-defaulted-params";
    const content = language === "en"
      ? "I will ask Studio for datasources without pagination so the backend execution metadata can report applied defaults.\n\n"
      : "我会请求数据源列表但不传分页参数，用后端执行元数据回报实际采用的默认值。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["Which datasource records are visible when the LLM omits pagination?"],
        next: ["Run the datasource list tool without pageNo/pageSize and let backend execution metadata report effective params."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "Verify backend defaulted params are visible to the next LLM turn.",
          params: { path: "/datasources" },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/paginated datasource loop|pagination loop|hasMore loop|分页拉全|分页循环|拉全分页/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "datasource-pagination-loop-start";
    const content = language === "en"
      ? "I will start with the first datasource page, inspect toolResults.hasMore, and only answer after the loop is complete.\n\n"
      : "我会先读取第一页数据源，根据 toolResults.hasMore 判断是否继续，循环完成后再回答。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["How can the assistant collect every datasource page without a local gateway deciding the loop?"],
        next: ["Request page 1, feed the result back to the LLM, and let the LLM decide whether page 2 is needed."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "Start an LLM-controlled pagination loop for a broad list request.",
          params: { path: "/datasources", keyword: "loop-pages", pageNo: 1, pageSize: 1 },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/stop decision|completion decision|complete after datasource list|no more tools after datasource list|结束判断|完成判断|停止判断/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "datasource-stop-decision-start";
    const content = language === "en"
      ? "I will read the datasource list first, then decide from toolResults whether any more operation is needed.\n\n"
      : "我会先读取数据源列表，再根据 toolResults 判断是否还需要继续调用接口。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["Can the datasource list answer the user without a second tool call?"],
        next: ["Read the datasource list, feed toolResults back to the LLM, then let the LLM decide whether the loop is complete."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "Read datasource records before the LLM decides whether to stop or continue.",
          params: { path: "/datasources", keyword: "stop-decision", pageNo: 1, pageSize: 20 },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/read.*(?:business|current).*context|current.*business.*context|business context|读取.*(?:业务|当前).*上下文|当前业务上下文|业务上下文/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "context-read-start";
    const content = language === "en"
      ? "I will read the current business context before deciding whether a Studio API is needed.\n\n"
      : "我会先读取当前业务上下文，再判断是否需要调用 Studio 接口。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["Which business object is active in the current feature context?"],
        next: ["Use assistant.context.read to inspect current business context without calling a business API."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "assistant.context.read",
          reason: "Read active object, selected entity, visible objects, and recent candidates from current Web context.",
          params: { path: "/datasources", kind: "datasource", limit: 5 },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (!/(?:search|find|搜索|查找)/i.test(message)
    && /observe.*(?:current|page|context)|current.*page.*(?:state|context)|page.*state|inspect.*current.*page|观察.*(?:当前|页面|上下文)|看看.*(?:当前|页面).*状态|读取当前.*上下文/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "context-observe-start";
    const content = language === "en"
      ? "I will first observe the current Studio page state and selected object, then decide whether any business tool is needed.\n\n"
      : "我会先观察当前 Studio 页面状态和选中对象，再判断是否需要调用业务工具。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["What route, feature, pageContext, and selected object are visible right now?"],
        next: ["Use assistant.context.observe before choosing any business API."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "assistant.context.observe",
          reason: "Observe the current page state as context before deciding the next operation.",
          params: { path: "/datasources" },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/search.*(?:current|page|context).*mock_mysql|find.*mock_mysql.*(?:current|page|context)|search.*page.*datasource|搜索.*mock_mysql|查找.*(?:当前|页面|上下文).*数据源/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "context-search-start";
    const content = language === "en"
      ? "I will search the current assistant context for the datasource name before selecting anything.\n\n"
      : "我会先在当前助手上下文中搜索数据源名称，再决定是否选择。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["Which datasource candidate in current context matches mock_mysql?"],
        next: ["Use assistant.context.search, then feed the candidates back to the LLM before selecting."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "assistant.context.search",
          reason: "Search current page context and assistant memory for the named datasource candidate.",
          params: { path: "/datasources", keyword: "mock_mysql", kind: "datasource", limit: 5 },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/studio\.feature\.get|declarative.*(?:datasource|data source).*detail|(?:datasource|data source).*detail.*declarative|read.*detail.*current.*(?:datasource|data source)|detail.*current.*(?:datasource|data source).*declarative|read.*current.*(?:datasource|data source).*detail|current.*(?:datasource|data source).*detail|声明式.*数据源.*详情|数据源.*详情.*声明式|读取.*详情.*当前.*数据源|读取.*当前.*数据源.*详情/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "datasource-detail-declarative-get";
    const datasourceId = activePageObject.id || 11;
    const datasourceName = String(activePageObject.name || activePageObject.label || "mock_mysql");
    const content = language === "en"
      ? `I will use the operation catalog to read the current datasource detail through studio.feature.get: ${datasourceName}.\n\n`
      : `我会根据能力目录通过 studio.feature.get 读取当前数据源详情：${datasourceName}。\n\n`;
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["Which declarative read tool should inspect the current datasource detail?"],
        next: ["Use the current page datasource id and execute studio.feature.get through the backend gateway."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.get",
          reason: "Read the current datasource detail through the declarative backend gateway.",
          params: { path: "/datasources", id: datasourceId },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/current page.*selected datasource|selected datasource.*current page|当前页面.*(?:选中|当前|这个).*数据源|用页面.*数据源|页面上下文.*数据源/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "datasource-discover-from-page-context";
    const datasourceId = activePageObject.id;
    const datasourceName = String(activePageObject.name || activePageObject.label || "current datasource");
    const content = language === "en"
      ? `I will use the datasource already exposed by the current page context: ${datasourceName}.\n\n`
      : `我会使用当前页面上下文里已经选中的数据源：${datasourceName}。\n\n`;
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["Which datasource is active in currentContext.pageContext?"],
        next: ["Use pageContext.activeObject.id to discover real tables without re-listing datasources."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.action",
          reason: "Use the datasource from current page context instead of guessing or re-listing.",
          params: { path: "/datasources", action: "discover", id: datasourceId },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/(?:all|every|complete|full).{0,30}(?:table|tables)|real.{0,20}(?:table|tables)|physical.{0,20}(?:table|tables)|表发现|真实表|物理表|所有表|全部表/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "datasource-discover-all";
    const datasourceId = activePageObject.id || 11;
    const datasourceName = String(activePageObject.name || activePageObject.label || "selected datasource");
    const content = language === "en"
      ? `I will ask Studio to discover all real tables from the selected datasource ${datasourceName} without adding pagination defaults.\n\n`
      : `我会让 Studio 从选中的数据源 ${datasourceName} 发现全部真实表，不附加前端分页默认值。\n\n`;
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["Which real tables are available in the selected datasource?"],
        next: ["Run datasource table discovery without pageNo/pageSize and summarize the discovered tables."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.action",
          reason: "Discover all real tables from the selected datasource through the controlled backend gateway.",
          params: { path: "/datasources", action: "discover", id: datasourceId },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/select current page datasource|remember current page datasource|default current page datasource|current page datasource.*default|default assistant datasource|页面.*(?:设为|选择|默认).*数据源|把当前页面.*数据源.*(?:设为|作为|默认)/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "page-context-memory-select-start";
    const datasourceId = activePageObject.id;
    const datasourceName = String(activePageObject.name || activePageObject.label || "current datasource");
    const content = language === "en"
      ? `I will select the datasource already visible in the current page context: ${datasourceName}.\n\n`
      : `我会选择当前页面上下文里可见的数据源：${datasourceName}。\n\n`;
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["Which datasource object is visible on the current page?"],
        next: ["Use assistant.memory.select to set the selected object from pageContext without a business API call."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "assistant.memory.select",
          reason: "Select the datasource from current pageContext visibleObjects.",
          params: { path: "/datasources", id: datasourceId, name: datasourceName },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/missing script type inference|script type inference|missing-script-type|缺少.*scriptType|脚本类型.*推断/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "missing-script-type-probe";
    const content = language === "en"
      ? "I will intentionally omit scriptType to verify the browser does not infer it from the file name or SQL text.\n\n"
      : "我会故意不提供 scriptType，用来验证浏览器不会根据文件名或 SQL 文本推断脚本类型。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "tool_pending",
        autoContinue: true,
        stopReason: "waiting_for_tool_result",
        questions: ["Does the frontend infer missing scriptType locally?"],
        next: ["Try the saveScript action without scriptType and feed the validation result back to the LLM."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.action",
          reason: "Probe that required script parameters must be supplied by the LLM, not inferred by the frontend.",
          params: {
            path: "/data-development",
            resource: "scripts",
            action: "saveScript",
            payload: {
              fileName: "assistant_missing_type.sql",
              content: "select 1",
            },
          },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/missing action alias inference|action alias inference|operation.*action.*inference|动作.*别名.*推断/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "missing-action-alias-probe";
    const content = language === "en"
      ? "I will intentionally provide operation without action for a model sync action to verify the browser does not reinterpret it as action.\n\n"
      : "我会故意只给模型同步动作提供 operation，不提供 action，用来验证浏览器不会把它重新解释为 action。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "tool_pending",
        autoContinue: true,
        stopReason: "waiting_for_tool_result",
        questions: ["Does the frontend infer action from operation for studio.feature.action?"],
        next: ["Try the model sync action without canonical action and feed the validation result back to the LLM."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.action",
          reason: "Probe that operation-catalog actions must be supplied by the LLM with the canonical action parameter.",
          params: {
            path: "/models",
            operation: "sync",
            datasourceId: 11,
          },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/missing datasourceId inference|datasourceId inference|missing-datasource-id|缺少.*datasourceId|数据源.*参数.*推断/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "missing-datasource-id-probe";
    const content = language === "en"
      ? "I will intentionally provide only id for a model sync action to verify the browser does not reinterpret it as datasourceId.\n\n"
      : "我会故意只给模型同步动作提供 id，用来验证浏览器不会把它重新解释为 datasourceId。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "tool_pending",
        autoContinue: true,
        stopReason: "waiting_for_tool_result",
        questions: ["Does the frontend infer datasourceId from a generic id?"],
        next: ["Try the model sync action without datasourceId and feed the validation result back to the LLM."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.action",
          reason: "Probe that operation-catalog required values must be supplied by the LLM with the declared parameter name.",
          params: {
            path: "/models",
            action: "sync",
            id: 11,
          },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/resolve model preview runtime cluster|model preview cluster resolution|解析.*模型预览.*集群|模型预览.*集群.*唯一/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "model-preview-runtime-cluster-resolution-probe";
    const content = language === "en"
      ? "I will resolve the runtime cluster from the selected model evidence before previewing it.\n\n"
      : "我会先依据模型证据解析模型预览使用的运行集群，再执行预览。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "tool_pending",
        autoContinue: true,
        stopReason: "waiting_for_tool_result",
        questions: ["Which project-authorized runtime cluster applies to model 5?"],
        next: ["Resolve the runtime cluster candidates for model 5."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "Intersect project authorization with model applicability before cluster-scoped preview.",
          params: { path: "/runtime-clusters", modelIds: [5] },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/list runtime cluster candidates|runtime cluster choice|multiple runtime clusters|多候选.*集群|选择.*运行集群/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "runtime-cluster-multiple-candidates-probe";
    const content = language === "en"
      ? "I found multiple authorized runtime cluster candidates. I will wait for an explicit selection instead of choosing by preferred flag, online status, or list order.\n\n"
      : "当前有多个项目授权的运行集群候选。我会等待你明确选择，不会按首选标记、在线状态或列表顺序自动选择。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "waiting_for_user",
        autoContinue: false,
        stopReason: "missing_user_input",
        questions: ["Which runtime cluster should be used?"],
        next: ["Wait for an explicit runtime cluster selection."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "Read all project-authorized runtime cluster candidates before asking the user.",
          params: { path: "/runtime-clusters" },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/missing model preview cluster|model preview missing cluster|missing-preview-cluster|模型预览.*缺少.*集群/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "missing-model-preview-cluster-probe";
    const content = language === "en"
      ? "I will intentionally omit runtimeClusterId from a model preview action to verify catalog validation.\n\n"
      : "我会故意在模型预览动作中省略 runtimeClusterId，用来验证目录参数校验。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "tool_pending",
        autoContinue: true,
        stopReason: "waiting_for_tool_result",
        questions: ["Does model preview require runtimeClusterId before backend execution?"],
        next: ["Try the read-only model preview action without runtimeClusterId and feed validation back to the LLM."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.action",
          reason: "Verify cluster-scoped model preview validation.",
          params: { path: "/models", action: "preview", id: 5, limit: 20 },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/model preview with runtime cluster|cluster-scoped model preview|preview model in cluster|携带.*集群.*模型预览/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "model-preview-with-cluster-probe";
    const content = language === "en"
      ? "I will preview the selected model through the read-only cluster-scoped action.\n\n"
      : "我会通过只读的集群范围模型预览动作读取样例数据。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "tool_pending",
        autoContinue: true,
        stopReason: "waiting_for_tool_result",
        questions: ["Can model preview reach the backend with an explicit runtimeClusterId?"],
        next: ["Preview model 5 in runtime cluster 7."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.action",
          reason: "Preview the selected model in an authorized runtime cluster.",
          params: { path: "/models", action: "preview", id: 5, runtimeClusterId: 7, limit: 20 },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/missing feature get id inference|feature get id inference|missing-feature-get-id|recordId.*id.*inference|详情.*id.*推断/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "missing-feature-get-id-probe";
    const content = language === "en"
      ? "I will intentionally provide recordId without id for a declarative get action to verify the browser does not reinterpret it as id.\n\n"
      : "我会故意只给声明式详情读取提供 recordId，用来验证浏览器不会把它重新解释为 id。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "tool_pending",
        autoContinue: true,
        stopReason: "waiting_for_tool_result",
        questions: ["Does the frontend infer id from recordId for studio.feature.get?"],
        next: ["Try the declarative get action without canonical id and feed the validation result back to the LLM."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.get",
          reason: "Probe that declarative read tools require the LLM to provide the canonical id parameter.",
          params: {
            path: "/datasources",
            recordId: 11,
          },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/missing feature list path inference|feature list path inference|missing-feature-list-path|featurePath.*path.*inference|列表.*path.*推断/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "missing-feature-list-path-probe";
    const content = language === "en"
      ? "I will intentionally provide featurePath without path for a declarative list action to verify the browser does not reinterpret it as path.\n\n"
      : "我会故意只给声明式列表读取提供 featurePath，不提供 path，用来验证浏览器不会把它重新解释为 path。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "tool_pending",
        autoContinue: true,
        stopReason: "waiting_for_tool_result",
        questions: ["Does the frontend infer path from featurePath for studio.feature.list?"],
        next: ["Try the declarative list action without canonical path and feed the validation result back to the LLM."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "Probe that declarative read tools require the LLM to provide the canonical path parameter.",
          params: {
            featurePath: "/datasources",
          },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/model list undeclared parameter|models.*tableName.*keyword|tableName.*keyword.*models|模型列表.*tableName.*keyword|未声明.*tableName/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "model-list-undeclared-param-probe";
    const content = language === "en"
      ? "I will intentionally provide tableName for a /models list action to verify the browser does not reinterpret it as keyword.\n\n"
      : "我会故意给 /models 列表读取提供 tableName，用来验证浏览器不会把它重新解释为 keyword。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "tool_pending",
        autoContinue: true,
        stopReason: "waiting_for_tool_result",
        questions: ["Does the frontend infer keyword from tableName for studio.feature.list /models?"],
        next: ["Try the /models list action with undeclared tableName and feed the validation result back to the LLM."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "Probe that declarative list tools require catalog-declared parameter names.",
          params: {
            path: "/models",
            tableName: "orders",
            pageNo: 1,
            pageSize: 20,
          },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/context search alias parameter|context search.*featurePath.*query|featurePath.*context search|上下文搜索.*featurePath/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "context-search-alias-param-probe";
    const content = language === "en"
      ? "I will intentionally provide featurePath and query for assistant.context.search to verify the browser does not reinterpret them as path and keyword.\n\n"
      : "我会故意给 assistant.context.search 提供 featurePath 和 query，用来验证浏览器不会把它们重新解释为 path 和 keyword。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "tool_pending",
        autoContinue: true,
        stopReason: "waiting_for_tool_result",
        questions: ["Does the frontend infer path/keyword aliases for assistant.context.search?"],
        next: ["Try the context search tool without canonical path/keyword and feed the validation result back to the LLM."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "assistant.context.search",
          reason: "Probe that local context tools require canonical declared parameter names.",
          params: {
            featurePath: "/datasources",
            query: "mock_mysql",
            kind: "datasource",
          },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/memory select recordId alias|memory select.*recordId|recordId.*memory select|记忆选择.*recordId/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "memory-select-record-id-param-probe";
    const content = language === "en"
      ? "I will intentionally provide recordId for assistant.memory.select to verify the browser does not reinterpret it as id.\n\n"
      : "我会故意给 assistant.memory.select 提供 recordId，用来验证浏览器不会把它重新解释为 id。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "tool_pending",
        autoContinue: true,
        stopReason: "waiting_for_tool_result",
        questions: ["Does the frontend infer id from recordId for assistant.memory.select?"],
        next: ["Try memory selection with undeclared recordId and feed the validation result back to the LLM."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "assistant.memory.select",
          reason: "Probe that local memory selection requires canonical id/name/physicalLocator/ordinal.",
          params: {
            path: "/datasources",
            recordId: 11,
          },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/memory select tableName alias|memory select.*tableName|tableName.*memory select|记忆选择.*tableName/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "memory-select-table-name-param-probe";
    const content = language === "en"
      ? "I will intentionally provide tableName for assistant.memory.select to verify the browser does not reinterpret it as physicalLocator.\n\n"
      : "我会故意给 assistant.memory.select 提供 tableName，用来验证浏览器不会把它重新解释为 physicalLocator。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "tool_pending",
        autoContinue: true,
        stopReason: "waiting_for_tool_result",
        questions: ["Does the frontend infer physicalLocator from tableName for assistant.memory.select?"],
        next: ["Try memory selection with undeclared tableName and feed the validation result back to the LLM."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "assistant.memory.select",
          reason: "Probe that local memory selection requires canonical physicalLocator.",
          params: {
            path: "/datasources",
            tableName: "mock_mysql",
          },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/missing script tool canonical fields|script tool alias inference|scriptId.*entrypointId|payload.*input.*script|脚本工具.*参数.*推断/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "missing-script-tool-canonical-fields-probe";
    const content = language === "en"
      ? "I will intentionally provide scriptId and payload for assistant.script.execute to verify the browser does not reinterpret them as entrypointId and input.\n\n"
      : "我会故意给 assistant.script.execute 提供 scriptId 和 payload，用来验证浏览器不会把它们重新解释为 entrypointId 和 input。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "tool_pending",
        autoContinue: true,
        stopReason: "waiting_for_tool_result",
        questions: ["Does the frontend infer entrypointId/input from scriptId/payload for assistant.script.execute?"],
        next: ["Try the script tool without canonical entrypointId/input and feed the validation result back to the LLM."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "assistant.script.execute",
          reason: "Probe that registered script tools require canonical entrypointId and input fields.",
          params: {
            scriptId: "field-mapping-suggester",
            payload: {
              sourceFields: ["id"],
              targetFields: ["ID"],
            },
          },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/unsupported|save script|fallback/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "unsupported-save-script";
    const content = language === "en"
      ? "I will request a script save action to verify backend rejection handling.\n\n"
      : "我会请求保存脚本动作，用来验证后端拒绝时不会前端降级。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "user_confirmation_pending",
        autoContinue: false,
        stopReason: "waiting_for_user_confirmation",
        questions: ["Will an unsupported backend action fall back to browser execution?"],
        next: ["Confirm the Studio action, then stop after backend rejection and report the failure context."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.action",
          reason: "Verify backend rejection is not bypassed by frontend fallback.",
          params: {
            path: "/data-development",
            resource: "scripts",
            action: "saveScript",
            payload: {
              fileName: "assistant_rejection_probe.sql",
              scriptType: "SQL",
              content: "select 1",
            },
          },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/field mapping|mapping script|suggest mappings|字段映射|映射建议/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "field-mapping-script";
    const content = language === "en"
      ? "I will use the registered field mapping helper instead of asking the model to invent mappings.\n\n"
      : "我会使用已登记字段映射 helper，而不是让模型自由编造映射。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["Which source fields can map to the target fields?"],
        next: ["Run the registered field mapping script and summarize mapped and unresolved fields."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "assistant.script.execute",
          reason: "Run registered field mapping helper.",
          params: {
            entrypointId: "field-mapping-suggester",
            runtimeClusterId: 7,
            input: {
              sourceFields: ["id", "order_no", "amount"],
              targetFields: ["ID", "orderNo", "missing_col"],
            },
          },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/quality issue|quality metrics|质量问题|质量指标/i.test(message)) {
    const content = language === "en"
      ? "I will read quality issues through the backend assistant gateway.\n\n"
      : "我会通过后端助手网关读取质量问题。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["Which quality issues are currently visible?"],
        next: ["Read quality metric issues and summarize the issue status for the user."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "Read quality issues through the controlled backend gateway.",
          params: { path: "/quality-metrics", view: "issues", status: "OPEN", pageNo: 1, pageSize: 20 },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/mark.*notification|notification.*read|通知.*已读|标记.*通知/i.test(message)) {
    const content = language === "en"
      ? "I will ask for confirmation before marking the notification as read.\n\n"
      : "我会先请求确认，然后再标记通知为已读。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "user_confirmation_pending",
        autoContinue: false,
        stopReason: "waiting_for_user_confirmation",
        questions: ["Should Studio mark notification 501 as read?"],
        next: ["Confirm the notification mark-read action, then summarize the backend gateway result."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.action",
          reason: "Mark a notification as read through the confirmed backend gateway.",
          params: { path: "/notifications", action: "markRead", id: 501 },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/notification|notifications|unread|inbox|通知中心|通知|未读/i.test(message)) {
    const content = language === "en"
      ? "I will read unread notifications through the backend assistant gateway.\n\n"
      : "我会通过后端助手网关读取未读通知。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["Which unread notifications are currently visible?"],
        next: ["Read notifications and summarize the unread inbox records returned by the backend gateway."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "Read notifications through the controlled backend gateway.",
          params: { path: "/notifications", view: "list", unreadOnly: true, pageNo: 1, pageSize: 20 },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/statistics|statistic options|统计中心|统计配置|统计选项/i.test(message)) {
    const content = language === "en"
      ? "I will read statistics options through the backend assistant gateway.\n\n"
      : "我会通过后端助手网关读取统计配置。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["Which statistics option schemas are currently available?"],
        next: ["Read statistics options and summarize target schema and field choices returned by the backend gateway."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "Read statistics options through the controlled backend gateway.",
          params: { path: "/statistics", view: "options", datasourceType: "MYSQL", targetScope: "BUSINESS" },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/metadata|meta model|schema|元模型/i.test(message)) {
    const content = language === "en"
      ? "I will read metadata schemas through the backend assistant gateway.\n\n"
      : "我会通过后端助手网关读取元模型。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["Which metadata schemas are currently visible?"],
        next: ["Read metadata schemas and summarize the schema codes returned by the backend gateway."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "Read metadata schemas through the controlled backend gateway.",
          params: { path: "/metadata", includeFields: false },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/approve.*registration|registration request|注册申请|注册审批/i.test(message)) {
    const content = language === "en"
      ? "I will ask for confirmation before approving the registration request.\n\n"
      : "我会先请求确认，然后再审批注册申请。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "user_confirmation_pending",
        autoContinue: false,
        stopReason: "waiting_for_user_confirmation",
        questions: ["Should Studio approve registration request 88?"],
        next: ["Confirm the system approval action, then summarize the returned registration review result."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.action",
          reason: "Approve a user registration request through the confirmed system gateway.",
          params: {
            path: "/system",
            resource: "userRegistrationRequests",
            action: "approve",
            id: 88,
            payload: {
              reviewComment: "approved by assistant",
            },
          },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/system user|system users|系统用户|用户列表/i.test(message)) {
    const content = language === "en"
      ? "I will read system users through the backend assistant gateway.\n\n"
      : "我会通过后端助手网关读取系统用户。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        questions: ["Which system users are currently visible?"],
        next: ["Read the system users page through the backend gateway and summarize the visible user records."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.list",
          reason: "Read system users through the controlled backend gateway.",
          params: { path: "/system", resource: "users", pageNo: 1, pageSize: 20 },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/data development payload alias|payload alias inference|missing data development payload|payload.*(?:datasourceId|content).*inference|数据开发.*payload.*推断/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "missing-data-development-payload-probe";
    const content = language === "en"
      ? "I will intentionally put datasourceId outside payload and use payload.sql to verify the browser does not rewrite the payload for the LLM.\n\n"
      : "我会故意把 datasourceId 放在 payload 外，并使用 payload.sql，用来验证浏览器不会替 LLM 改写数据开发 payload。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "tool_pending",
        autoContinue: true,
        stopReason: "waiting_for_tool_result",
        questions: ["Does the frontend rewrite data-development payload aliases locally?"],
        next: ["Try executeSql with non-canonical payload fields and feed the validation result back to the LLM."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.action",
          reason: "Probe that data-development payload fields must be supplied by the LLM with the declared parameter names.",
          params: {
            path: "/data-development",
            resource: "sql",
            action: "executeSql",
            datasourceId: 11,
            payload: {
              scriptType: "SQL",
              sql: "select 1",
            },
          },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/action undeclared parameter|action param alias|action payload alias|feature action.*(?:data|name|search)|动作.*(?:data|name|search).*参数/i.test(message)) {
    assistantPayloadAudit.lastChatBranch = "action-undeclared-param-probe";
    const content = language === "en"
      ? "I will intentionally provide data/name/search on a feature action to verify the browser does not rewrite them into payload or keyword.\n\n"
      : "我会故意给功能动作提供 data/name/search，用来验证浏览器不会把它们改写成 payload 或 keyword。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "tool_pending",
        autoContinue: true,
        stopReason: "waiting_for_tool_result",
        questions: ["Does the frontend rewrite undeclared feature action params locally?"],
        next: ["Try a datasource discover action with data/name/search and feed the validation result back to the LLM."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.action",
          reason: "Probe that action params must use operation-catalog declared names.",
          params: {
            path: "/datasources",
            action: "discover",
            id: 11,
            data: {
              name: "assistant_mysql",
              typeCode: "mysql8",
            },
            name: "assistant_mysql",
            search: "assistant_mysql",
          },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  if (/\b(sql|execute|run|select\s+1)\b/i.test(message)) {
    const content = language === "en"
      ? "I will execute the SQL only after Studio asks for confirmation.\n\n"
      : "我会在 Studio 要求确认后执行这段 SQL。\n\n";
    const protocol = {
      protocol: "studio-assistant.v1",
      loop: statefulLoop({
        status: "user_confirmation_pending",
        autoContinue: false,
        stopReason: "waiting_for_user_confirmation",
        questions: ["Should Studio execute select 1 on the selected datasource?"],
        next: ["Confirm the SQL execution, then use the confirmed tool result to summarize the SQL output."],
      }),
      actions: [
        {
          type: "frontendTool",
          tool: "studio.feature.action",
          reason: "Execute SQL through confirmed backend gateway.",
          params: {
            path: "/data-development",
            resource: "sql",
            action: "executeSql",
            payload: {
              datasourceId: 11,
              scriptType: "SQL",
              content: "select 1",
              maxRows: 10,
            },
          },
        },
      ],
      controls: [],
    };
    writeSse(response, "delta", {
      content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
    });
    writeSse(response, "done", {});
    response.end();
    return;
  }
  const content = language === "en"
    ? "I will read the datasource list and continue with a short summary.\n\n"
    : "我会先读取数据源列表，然后继续给出简短总结。\n\n";
  const protocol = {
    protocol: "studio-assistant.v1",
    loop: statefulLoop({
      questions: ["Which datasource records are visible?"],
      next: ["Read controlled Studio datasource data and summarize the datasource list for the user."],
    }),
    actions: [
      {
        type: "frontendTool",
        tool: "studio.feature.list",
        reason: "Read datasource list through the controlled assistant tool gateway.",
        params: { path: "/datasources", pageNo: 1, pageSize: 20 },
      },
    ],
    controls: [],
  };
  writeSse(response, "delta", {
    content: `${content}\`\`\`studio-assistant-protocol\n${JSON.stringify(withProtocolPlan(protocol))}\n\`\`\``,
  });
  writeSse(response, "done", {});
  response.end();
}

function withProtocolPlan(protocol) {
  if (!isRecord(protocol) || isRecord(protocol.plan)) {
    return protocol;
  }
  return {
    ...protocol,
    plan: inferProtocolPlan(protocol),
  };
}

function inferProtocolPlan(protocol) {
  const actions = Array.isArray(protocol.actions) ? protocol.actions.filter(isRecord) : [];
  const loop = isRecord(protocol.loop) ? protocol.loop : {};
  const questions = Array.isArray(loop.questions) ? loop.questions : [];
  const next = Array.isArray(loop.next) ? loop.next : [];
  const firstAction = actions[0] || {};
  const params = isRecord(firstAction.params) ? firstAction.params : {};
  const requiredObjects = [];
  if (params.path) {
    requiredObjects.push({ type: "feature", path: String(params.path) });
  }
  if (params.id != null) {
    requiredObjects.push({ type: "businessObject", id: String(params.id), path: String(params.path || "") });
  }
  if (params.entrypointId) {
    requiredObjects.push({ type: "scriptEntrypoint", id: String(params.entrypointId) });
  }
  const nextActions = actions.length
    ? actions.map((action) => ({
        type: "tool",
        tool: String(action.tool || action.interfaceCode || ""),
        path: isRecord(action.params) && action.params.path ? String(action.params.path) : undefined,
        action: isRecord(action.params) && action.params.action ? String(action.params.action) : undefined,
        reason: String(action.reason || ""),
      }))
    : next.map((item) => ({
        type: protocolItemText(item, ["type"]) || "step",
        reason: protocolItemText(item, ["description", "reason", "input"]),
      }));
  return {
    intent: protocolItemText(questions[0], ["input", "question", "title"]) || protocolItemText(next[0], ["description", "reason"]) || "Continue Studio assistant task",
    basis: [
      "Mock LLM interpreted the user prompt",
      actions.length ? "Selected an allow-listed Studio assistant tool" : "No tool action needed",
    ],
    requiredObjects,
    nextActions,
  };
}

function protocolItemText(item, keys) {
  if (isRecord(item)) {
    for (const key of keys) {
      const value = item[key];
      if (value != null && String(value).trim()) {
        return String(value).trim();
      }
    }
    return "";
  }
  return item == null ? "" : String(item).trim();
}

function statefulLoop({
  mode = "goal",
  status = "tool_pending",
  autoContinue = true,
  stopReason = "waiting_for_tool_result",
  questions = [],
  next = [],
} = {}) {
  return {
    mode,
    status,
    autoContinue,
    questions: questions.map((question, index) => ({
      id: `q${index + 1}`,
      input: question,
      status: autoContinue ? "needs_tool" : "needs_user",
      output: autoContinue ? "pending controlled Studio tool result" : "pending user confirmation",
    })),
    next: next.map((description, index) => ({
      id: `step${index + 1}`,
      type: autoContinue ? "tool" : "control",
      status: "pending",
      description,
    })),
    evidence: [],
    stopReason,
  };
}

function recordAssistantPayload(body) {
  assistantPayloadAudit.chatRequests += 1;
  const context = isRecord(body?.context) ? body.context : {};
  if (Array.isArray(context.assistantFeatures) && Array.isArray(context.assistantCapabilities)) {
    assistantPayloadAudit.assistantCapabilityContexts += 1;
  }
  if (Object.prototype.hasOwnProperty.call(context, "accessibleFeatures")
      || Object.prototype.hasOwnProperty.call(context, "accessibleCapabilities")) {
    assistantPayloadAudit.leakedAccessibleCapabilityContexts += 1;
  }
  if (context.operationCatalogSource === "backend" && Number(context.operationCatalogSize || 0) > 0) {
    assistantPayloadAudit.backendCatalogContexts += 1;
  }
  if (Array.isArray(context.frontendTools)
      && context.frontendTools.some((tool) => legacyFrontendToolCodes.has(String(tool?.interfaceCode || tool)))) {
    assistantPayloadAudit.legacyFrontendToolContexts += 1;
  }
  if (isRecord(context.pageContext)) {
    assistantPayloadAudit.pageContextContexts += 1;
    assistantPayloadAudit.lastPageContext = context.pageContext;
  }
  if (isRecord(context.assistantMemory)) {
    assistantPayloadAudit.lastAssistantMemory = context.assistantMemory;
  }
  const latestToolResult = latestToolResultFromBody(body);
  assistantPayloadAudit.lastToolResult = latestToolResult;
  if (latestToolResult?.execution && isRecord(latestToolResult.execution)) {
    assistantPayloadAudit.lastToolResultExecution = latestToolResult.execution;
  }
}

function latestToolResultFromBody(body) {
  if (!Array.isArray(body?.toolResults) || body.toolResults.length === 0) {
    return null;
  }
  const latest = body.toolResults[body.toolResults.length - 1];
  return isRecord(latest) ? latest : null;
}

function sendSse(response, events) {
  response.writeHead(200, {
    "Content-Type": "text/event-stream; charset=utf-8",
    "Cache-Control": "no-cache",
    Connection: "close",
  });
  for (const [event, data] of events) {
    writeSse(response, event, data);
  }
  response.end();
}

function writeSse(response, event, data) {
  response.write(`event: ${event}\n`);
  response.write(`data: ${JSON.stringify(data)}\n\n`);
}

function sendJson(response, data, status = 200, success = true) {
  const normalizedData = normalizeToolExecutionResult(data);
  response.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  response.end(JSON.stringify({
    success,
    code: success ? "0" : "ERROR",
    message: success ? "OK" : String(data?.message || "ERROR"),
    data: normalizedData,
  }));
}

function normalizeToolExecutionResult(data) {
  if (!isRecord(data) || data.schema !== "studio.tool-result.v1") {
    return data;
  }
  const params = isRecord(data.params) ? data.params : {};
  const executionParams = toolExecutionParams(String(data.interfaceCode || ""), params);
  return {
    ...data,
    effectiveParams: isRecord(data.effectiveParams) ? data.effectiveParams : executionParams.effectiveParams,
    defaultedParams: Array.isArray(data.defaultedParams) ? data.defaultedParams : executionParams.defaultedParams,
  };
}

function toolExecutionParams(interfaceCode, params) {
  const effectiveParams = { ...params };
  const defaultedParams = [];
  if (interfaceCode === "studio.feature.list" && String(params.path || "") === "/datasources") {
    if (!Object.prototype.hasOwnProperty.call(effectiveParams, "pageNo")) {
      effectiveParams.pageNo = 1;
      defaultedParams.push("pageNo");
    }
    if (!Object.prototype.hasOwnProperty.call(effectiveParams, "pageSize")) {
      effectiveParams.pageSize = 20;
      defaultedParams.push("pageSize");
    }
  }
  return { effectiveParams, defaultedParams };
}

function emptyPayloadFor(pathname) {
  if (pathname.includes("options")) {
    return [];
  }
  if (pathname.includes("page") || pathname.includes("list") || pathname.includes("notifications")) {
    return { pageNo: 1, pageSize: 20, total: 0, items: [] };
  }
  return {};
}

function readJson(request) {
  return new Promise((resolve, reject) => {
    let body = "";
    request.setEncoding("utf8");
    request.on("data", (chunk) => {
      body += chunk;
    });
    request.on("end", () => {
      if (!body.trim()) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(body));
      } catch (error) {
        reject(error);
      }
    });
    request.on("error", reject);
  });
}

function isRecord(value) {
  return value != null && typeof value === "object" && !Array.isArray(value);
}
