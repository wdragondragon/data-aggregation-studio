import { spawn, spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const appPort = Number(process.env.STUDIO_ASSISTANT_UI_APP_PORT || 5174);
const mockPort = Number(process.env.STUDIO_ASSISTANT_UI_MOCK_PORT || 49152);
const cdpPort = Number(process.env.STUDIO_ASSISTANT_UI_CDP_PORT || 9223);
const headed = process.env.STUDIO_ASSISTANT_UI_HEADED === "true";
const workspaceRoot = fileURLToPath(new URL("..", import.meta.url));
const targetDir = path.resolve(workspaceRoot, "target");
const appBaseUrl = `http://127.0.0.1:${appPort}/dfs/data-aggregation-studio/`;
const mockBaseUrl = `http://127.0.0.1:${mockPort}`;
const cdpBaseUrl = `http://127.0.0.1:${cdpPort}`;
const stoppingPids = new Set();

const children = [];
let userDataDir = "";

main().catch(async (error) => {
  console.error(error instanceof Error ? error.stack || error.message : error);
  process.exitCode = 1;
}).finally(async () => {
  for (const child of children.reverse()) {
    stopProcess(child);
  }
  if (userDataDir) {
    await fs.rm(userDataDir, { recursive: true, force: true }).catch(() => undefined);
  }
});

async function main() {
  await fs.mkdir(targetDir, { recursive: true });
  let mock = startAssistantMock(false, { configUnavailable: true });
  children.push(mock);
  await waitForHttp(`${mockBaseUrl}/api/v1/auth/me`, "mock API");

  const viteCli = path.resolve(workspaceRoot, "../../node_modules/vite/bin/vite.js");
  const vite = startProcess(process.execPath, [viteCli, "--host", "127.0.0.1", "--port", String(appPort)], {
    env: {
      ...process.env,
      VITE_API_BASE_URL: "/api/v1",
      STUDIO_PROXY_TARGET: mockBaseUrl,
    },
  });
  children.push(vite);
  await waitForHttp(appBaseUrl, "Vite app");

  userDataDir = await fs.mkdtemp(path.join(os.tmpdir(), "studio-assistant-smoke-"));
  const browserPath = resolveBrowserPath();
  const browser = startProcess(browserPath, [
    `--remote-debugging-port=${cdpPort}`,
    `--user-data-dir=${userDataDir}`,
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-background-networking",
    "--disable-gpu",
    headed ? "" : "--headless=new",
  ].filter(Boolean), { env: process.env });
  children.push(browser);
  await waitForHttp(`${cdpBaseUrl}/json/version`, "Chrome DevTools");

  const target = await createTarget("about:blank");
  const page = await CdpSession.connect(target.webSocketDebuggerUrl);
  const browserErrors = [];
  page.on("Runtime.exceptionThrown", (event) => {
    browserErrors.push(event.exceptionDetails?.text || event.exceptionDetails?.exception?.description || "Runtime exception");
  });
  page.on("Runtime.consoleAPICalled", (event) => {
    if (event.type === "error") {
      browserErrors.push(event.args?.map((arg) => arg.value || arg.description || "").join(" ") || "console.error");
    }
  });
  page.on("Log.entryAdded", (event) => {
    if (event.entry?.level === "error") {
      browserErrors.push(event.entry.text);
    }
  });
  await page.send("Page.enable");
  await page.send("Runtime.enable");
  await page.send("Log.enable");
  await page.send("Page.navigate", { url: appBaseUrl });
  await waitForPageLoad(page);

  await waitForDom(page, `document.querySelector('input[autocomplete="username"]')`);
  await setField(page, `input[autocomplete="username"]`, "admin");
  await setField(page, `input[autocomplete="current-password"]`, "admin");
  await click(page, `.login__form .login__submit`);
  await waitForDom(page, `location.href.includes("/dashboard")`);
  const unavailableAssistantProbe = await evaluate(page, `
    fetch("/api/v1/assistant/config")
      .then(async (response) => {
        const payload = await response.json().catch(() => ({}));
        return {
          status: response.status,
          ok: payload.success === true,
          enabled: payload.data?.enabled === true,
          assistantButtonCount: document.querySelectorAll('[data-testid="studio-assistant-open"]').length,
          assistantDrawerCount: document.querySelectorAll('[data-testid="studio-assistant-root"]').length
        };
      })
  `);
  assert(unavailableAssistantProbe?.status === 404, "Assistant config endpoint should be unavailable in config-unavailable mock mode.");
  assert(unavailableAssistantProbe.enabled === false, "Unavailable assistant config must not be treated as enabled.");
  assert(unavailableAssistantProbe.assistantButtonCount === 0, "Assistant entry should be hidden when assistant config is unavailable.");
  assert(unavailableAssistantProbe.assistantDrawerCount === 0, "Assistant drawer should not be mounted when assistant config is unavailable.");

  await page.send("Page.navigate", { url: "about:blank" });
  await waitForPageLoad(page);
  stopProcess(mock);
  await waitForHttpUnavailable(`${mockBaseUrl}/api/v1/auth/me`, "config-unavailable mock API");
  mock = startAssistantMock(false);
  children.push(mock);
  await waitForHttp(`${mockBaseUrl}/api/v1/auth/me`, "disabled mock API");
  await page.send("Page.navigate", { url: appBaseUrl });
  await waitForPageLoad(page);
  await waitForDom(page, `location.href.includes("/dashboard")`);
  const disabledAssistantProbe = await evaluate(page, `
    fetch("/api/v1/assistant/config")
      .then((response) => response.json())
      .then((payload) => ({
        ok: payload.success === true,
        enabled: payload.data?.enabled === true,
        assistantButtonCount: document.querySelectorAll('[data-testid="studio-assistant-open"]').length,
        assistantDrawerCount: document.querySelectorAll('[data-testid="studio-assistant-root"]').length
      }))
  `);
  assert(disabledAssistantProbe?.ok, "Assistant config endpoint did not respond in disabled mode.");
  assert(disabledAssistantProbe.enabled === false, "Assistant config should report disabled when mock LLM is disabled.");
  assert(disabledAssistantProbe.assistantButtonCount === 0, "Assistant entry should be hidden when LLM is disabled.");
  assert(disabledAssistantProbe.assistantDrawerCount === 0, "Assistant drawer should not be mounted when LLM is disabled.");

  await page.send("Page.navigate", { url: "about:blank" });
  await waitForPageLoad(page);
  stopProcess(mock);
  await waitForHttpUnavailable(`${mockBaseUrl}/api/v1/auth/me`, "disabled mock API");
  mock = startAssistantMock(true);
  children.push(mock);
  await waitForHttp(`${mockBaseUrl}/api/v1/auth/me`, "enabled mock API");
  await page.send("Page.navigate", { url: appBaseUrl });
  await waitForPageLoad(page);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-open"]')`);
  browserErrors.length = 0;
  const skillsProbe = await evaluate(page, `
    fetch("/api/v1/assistant/skills")
      .then((response) => response.json())
      .then((payload) => ({
        ok: payload.success === true,
        hasLoopSkill: Array.isArray(payload.data) && payload.data.some((item) => item.id === "assistant-loop" && item.portable === true),
        hasProtocolContract: Array.isArray(payload.data) && payload.data.some((item) =>
          item.id === "assistant-protocol"
            && item.protocolVersion === "studio-assistant.v1"
            && item.protocolSchema
            && Array.isArray(item.examples)
        ),
        hasProtocolPlanContract: Array.isArray(payload.data) && payload.data.some((item) =>
          item.id === "assistant-protocol"
            && Array.isArray(item.protocolSchema?.required)
            && item.protocolSchema.required.includes("protocol")
            && item.protocolSchema.required.includes("plan")
            && item.protocolSchema.required.includes("loop")
            && item.protocolSchema.required.includes("actions")
            && item.protocolSchema.required.includes("controls")
            && String(item.protocolSchema?.protocolRequiredRule || "").includes("studio-assistant.v1")
            && Array.isArray(item.protocolSchema?.planRequired)
            && ["intent", "basis", "requiredObjects", "nextActions"].every((field) => item.protocolSchema.planRequired.includes(field))
            && String(item.protocolSchema?.planRequiredRule || "").includes("All four plan fields are required")
            && Array.isArray(item.protocolSchema?.loopRequired)
            && ["status", "autoContinue", "questions", "next", "evidence", "stopReason"].every((field) => item.protocolSchema.loopRequired.includes(field))
            && String(item.protocolSchema?.loopRequiredRule || "").includes("Loop is required")
            && Array.isArray(item.protocolSchema?.forbiddenFields)
            && ["toolCalls", "backendToolCalls", "uiControls"].every((field) => item.protocolSchema.forbiddenFields.includes(field))
            && String(item.protocolSchema?.forbiddenFieldsRule || "").includes("Use actions")
            && Array.isArray(item.protocolSchema?.actionRequired)
            && ["type", "tool", "params"].every((field) => item.protocolSchema.actionRequired.includes(field))
            && String(item.protocolSchema?.actionRequiredRule || "").includes("type, tool, and params")
            && Array.isArray(item.protocolSchema?.controlRequired)
            && ["type", "title", "paramKey"].every((field) => item.protocolSchema.controlRequired.includes(field))
            && String(item.protocolSchema?.controlRequiredRule || "").includes("type, title, and paramKey")
            && Array.isArray(item.examples)
            && item.examples.some((example) => String(example.payload || "").includes('"plan"'))
        ),
        hasProtocolCompletionContract: Array.isArray(payload.data) && payload.data.some((item) =>
          item.id === "assistant-protocol"
            && String(item.protocolSchema?.toolResultContract || "").includes("loop.status=completed")
            && String(item.protocolSchema?.toolResultContract || "").includes("gateway must not decide")
            && Array.isArray(item.examples)
            && item.examples.some((example) =>
              String(example.payload || "").includes('"status":"completed"')
                && String(example.payload || "").includes('"actions":[]')
                && String(example.payload || "").includes('"stopReason":"answer_complete"')
            )
        ),
        hasContextReadContract: Array.isArray(payload.data) && payload.data.some((item) =>
          item.id === "assistant-protocol"
            && String(item.protocolSchema?.contextToolContract || "").includes("assistant.context.read")
            && String(item.protocolSchema?.contextToolContract || "").includes("activeObject")
        ),
        hasScriptSkill: Array.isArray(payload.data) && payload.data.some((item) =>
          item.id === "field-mapping-python-helper"
            && item.kind === "assistant.script.skill"
            && Array.isArray(item.scriptEntrypoints)
            && String(item.safetyPolicy || "").includes("no-network")
        ),
        hasOperationCard: Array.isArray(payload.data) && payload.data.some((item) => item.kind === "studio.operation.catalog" && item.path === "/data-development"),
      }))
  `);
  assert(skillsProbe?.ok, "Portable skills endpoint did not return a successful response.");
  assert(skillsProbe?.hasLoopSkill, "Portable assistant loop skill was not exported.");
  assert(skillsProbe?.hasProtocolContract, "Portable assistant protocol contract was not exported.");
  assert(skillsProbe?.hasProtocolPlanContract, "Portable assistant protocol contract did not require structured plan.");
  assert(skillsProbe?.hasProtocolCompletionContract, "Portable assistant protocol contract did not expose completed-loop toolResult semantics.");
  assert(skillsProbe?.hasContextReadContract, "Portable assistant protocol contract did not expose context-read semantics.");
  assert(skillsProbe?.hasScriptSkill, "Portable controlled script skill was not exported.");
  assert(skillsProbe?.hasOperationCard, "Portable operation skill card was not exported.");

  await page.send("Page.navigate", { url: `${appBaseUrl}datasources` });
  await waitForPageLoad(page);
  await waitForText(page, "mock_mysql", 25000);
  await clickByText(page, "tr", "mock_mysql");

  await click(page, `[data-testid="studio-assistant-open"]`);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-root"]')`);
  await click(page, `[data-testid="studio-assistant-mode-goal"]`);
  await click(page, `[data-testid="studio-assistant-language-en"]`);
  await waitForText(page, "Goal mode");

  const pageContextBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const pageContextBefore = pageContextBeforePayload.data ?? pageContextBeforePayload;
  const pageContextToolExecuteBefore = Number(pageContextBefore.toolExecuteRequests || 0);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Use the current page selected datasource to discover real tables.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Datasource Table Discovery Result", 25000);
  await waitForText(page, "orders and customers", 25000);
  const pageContextAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const pageContextAfter = pageContextAfterPayload.data ?? pageContextAfterPayload;
  assert(Number(pageContextAfter.toolExecuteRequests || 0) === pageContextToolExecuteBefore + 1, "Page-context prompt should execute exactly one datasource discovery tool call.");
  assert(pageContextAfter.pageContextContexts > pageContextBefore.pageContextContexts, "Assistant chat request should include current pageContext.");
  assert(pageContextAfter.lastPageContext?.source === "datasources-view", "Assistant pageContext should come from the datasources view.");
  assert(String(pageContextAfter.lastPageContext?.activeObject?.id) === "11", "Assistant pageContext should expose the clicked datasource as activeObject.");
  assert(pageContextAfter.lastToolExecute?.interfaceCode === "studio.feature.action", "Page-context prompt should execute a datasource feature action.");
  assert(pageContextAfter.lastToolExecute?.params?.path === "/datasources", "Page-context prompt should target /datasources.");
  assert(pageContextAfter.lastToolExecute?.params?.action === "discover", "Page-context prompt should use datasource discovery.");
  assert(String(pageContextAfter.lastToolExecute?.params?.id) === "11", "Datasource discovery should use pageContext.activeObject.id.");

  const pageContextMemoryBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const pageContextMemoryBefore = pageContextMemoryBeforePayload.data ?? pageContextMemoryBeforePayload;
  const pageContextMemoryToolExecuteBefore = Number(pageContextMemoryBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Set the current page datasource as the default assistant datasource.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Page Context Memory Selection Result", 25000);
  await waitForText(page, "without calling a backend business tool", 25000);
  const pageContextMemoryAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const pageContextMemoryAfter = pageContextMemoryAfterPayload.data ?? pageContextMemoryAfterPayload;
  assert(Number(pageContextMemoryAfter.toolExecuteRequests || 0) === pageContextMemoryToolExecuteBefore, "Page-context memory selection should not call a backend business tool.");
  assert(pageContextMemoryAfter.pageContextContexts > pageContextMemoryBefore.pageContextContexts, "Page-context memory selection request should include pageContext.");
  assert(pageContextMemoryAfter.lastChatBranch === "tool-follow-up-page-context-memory-select", "Page-context memory selection should complete through LLM tool-result follow-up.");
  assert(pageContextMemoryAfter.lastPageContext?.source === "datasources-view", "Page-context memory selection should use datasources view context.");
  assert(String(pageContextMemoryAfter.lastPageContext?.activeObject?.id) === "11", "Page-context memory selection should expose the clicked datasource as activeObject.");

  const contextObserveBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const contextObserveBefore = contextObserveBeforePayload.data ?? contextObserveBeforePayload;
  const contextObserveToolExecuteBefore = Number(contextObserveBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Observe the current Studio page state before choosing any operation.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Page Context Observation Result", 25000);
  await waitForText(page, "route=/datasources", 25000);
  await waitForText(page, "source=datasources-view", 25000);
  await waitForText(page, "active datasource mock_mysql", 25000);
  const contextObserveAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const contextObserveAfter = contextObserveAfterPayload.data ?? contextObserveAfterPayload;
  assert(Number(contextObserveAfter.toolExecuteRequests || 0) === contextObserveToolExecuteBefore, "Context observation should not call a backend business tool.");
  assert(contextObserveAfter.pageContextContexts > contextObserveBefore.pageContextContexts, "Context observation request should include pageContext.");
  assert(contextObserveAfter.lastChatBranch === "tool-follow-up-context-observe", "Context observation should complete through LLM tool-result follow-up.");
  assert(contextObserveAfter.lastPageContext?.source === "datasources-view", "Context observation should use datasources view context.");
  assert(String(contextObserveAfter.lastPageContext?.activeObject?.id) === "11", "Context observation should expose the clicked datasource as activeObject.");

  const contextReadBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const contextReadBefore = contextReadBeforePayload.data ?? contextReadBeforePayload;
  const contextReadToolExecuteBefore = Number(contextReadBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Read the current business context for this datasource page before choosing any API.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Business Context Read Result", 25000);
  await waitForText(page, "active datasource mock_mysql", 25000);
  await waitForText(page, "without calling a backend business tool", 25000);
  const contextReadAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const contextReadAfter = contextReadAfterPayload.data ?? contextReadAfterPayload;
  assert(Number(contextReadAfter.toolExecuteRequests || 0) === contextReadToolExecuteBefore, "Context read should not call a backend business tool.");
  assert(contextReadAfter.pageContextContexts > contextReadBefore.pageContextContexts, "Context read request should include pageContext.");
  assert(contextReadAfter.lastChatBranch === "tool-follow-up-context-read", "Context read should complete through LLM tool-result follow-up.");
  assert(contextReadAfter.lastPageContext?.source === "datasources-view", "Context read should use datasources view context.");
  assert(String(contextReadAfter.lastPageContext?.activeObject?.id) === "11", "Context read should expose the clicked datasource as activeObject.");

  const contextSearchBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const contextSearchBefore = contextSearchBeforePayload.data ?? contextSearchBeforePayload;
  const contextSearchToolExecuteBefore = Number(contextSearchBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Search the current page context for mock_mysql and then select the matching datasource.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Context Search Result", 25000);
  await waitForText(page, "found mock_mysql", 25000);
  await waitForText(page, "Page Context Memory Selection Result", 25000);
  const contextSearchAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const contextSearchAfter = contextSearchAfterPayload.data ?? contextSearchAfterPayload;
  assert(Number(contextSearchAfter.toolExecuteRequests || 0) === contextSearchToolExecuteBefore, "Context object search and selection should not call a backend business tool.");
  assert(contextSearchAfter.pageContextContexts > contextSearchBefore.pageContextContexts, "Context object search request should include pageContext.");
  assert(contextSearchAfter.lastChatBranch === "tool-follow-up-page-context-memory-select", "Context object search should finish through the LLM-selected memory update follow-up.");
  assert(contextSearchAfter.lastPageContext?.source === "datasources-view", "Context object search should use datasources view context.");
  assert(String(contextSearchAfter.lastPageContext?.activeObject?.id) === "11", "Context object search should expose the clicked datasource as activeObject.");

  const clarificationBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const clarificationBefore = clarificationBeforePayload.data ?? clarificationBeforePayload;
  const clarificationToolExecuteBefore = Number(clarificationBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "This is an ambiguous datasource request; I have not said which datasource should be used.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Which datasource should I use?", 25000);
  await waitForText(page, "Use current page datasource", 25000);
  await clickByText(page, "button", "Use current page datasource");
  await waitForText(page, "I will use the datasource chosen in the clarification answer", 25000);
  const clarificationAfter = await waitForAudit(
    (audit) => audit.lastChatBranch === "tool-follow-up-page-context-memory-select"
      && Number(audit.toolExecuteRequests || 0) === clarificationToolExecuteBefore,
    "clarification control answer LLM follow-up",
  );
  await waitForText(page, "Page Context Memory Selection Result", 25000);
  assert(Number(clarificationAfter.toolExecuteRequests || 0) === clarificationToolExecuteBefore, "Clarification and current-page selection should not call a backend business tool.");
  assert(clarificationAfter.pageContextContexts > clarificationBefore.pageContextContexts, "Clarification follow-up should include pageContext.");
  assert(clarificationAfter.lastChatBranch === "tool-follow-up-page-context-memory-select", "Clarification answer should be fed back to the LLM before memory selection completes.");
  assert(clarificationAfter.lastPageContext?.source === "datasources-view", "Clarification should use datasources view context.");
  assert(String(clarificationAfter.lastPageContext?.activeObject?.id) === "11", "Clarification should preserve the clicked datasource as activeObject.");

  const datasourceGetBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const datasourceGetBefore = datasourceGetBeforePayload.data ?? datasourceGetBeforePayload;
  const datasourceGetToolExecuteBefore = Number(datasourceGetBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Use the assistant operation catalog to read details for the current datasource through declarative API.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Datasource Detail Result", 25000);
  await waitForText(page, "mock_mysql, MYSQL, database studio_smoke", 25000);
  const datasourceGetAfter = await waitForAudit(
    (audit) => audit.lastChatBranch === "tool-follow-up-datasource-get"
      && Number(audit.toolExecuteRequests || 0) === datasourceGetToolExecuteBefore + 1
      && audit.lastToolResultExecution?.interfaceCode === "studio.feature.get",
    "declarative datasource get through backend gateway",
  );
  assert(datasourceGetAfter.lastToolExecute?.interfaceCode === "studio.feature.get", "Declarative datasource detail should use studio.feature.get.");
  assert(datasourceGetAfter.lastToolExecute?.params?.path === "/datasources", "Declarative datasource detail should target /datasources.");
  assert(String(datasourceGetAfter.lastToolExecute?.params?.id) === "11", "Declarative datasource detail should use pageContext.activeObject.id.");
  assert(datasourceGetAfter.lastToolResultExecution?.executedBy === "backend", "Declarative datasource get should execute through the backend gateway.");
  assert(datasourceGetAfter.lastToolResultExecution?.path === "/datasources", "Tool result execution metadata should preserve the feature path.");
  assert(datasourceGetAfter.lastToolResultExecution?.params?.path === "/datasources", "Tool result execution metadata should preserve gateway params.");
  assert(String(datasourceGetAfter.lastToolResultExecution?.params?.id) === "11", "Tool result execution metadata should preserve the datasource id.");

  const missingFeatureGetIdBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const missingFeatureGetIdBefore = missingFeatureGetIdBeforePayload.data ?? missingFeatureGetIdBeforePayload;
  const missingFeatureGetIdToolExecuteBefore = Number(missingFeatureGetIdBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run missing feature get id inference probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  const missingFeatureGetIdAfter = await waitForAudit(
    (audit) => audit.lastChatBranch === "tool-follow-up-missing-feature-get-id"
      && Number(audit.toolExecuteRequests || 0) === missingFeatureGetIdToolExecuteBefore,
    "missing feature get id should be returned to LLM without recordId alias inference",
  );
  assert(Number(missingFeatureGetIdAfter.toolExecuteRequests || 0) === missingFeatureGetIdToolExecuteBefore, "Missing feature get id should fail before backend tool gateway execution.");
  assert(missingFeatureGetIdAfter.lastToolResult?.interfaceCode === "studio.feature.get", "Missing feature get id should be recorded as a studio.feature.get tool result.");
  assert(missingFeatureGetIdAfter.lastToolResult?.params?.path === "/datasources", "Missing feature get id probe should target /datasources.");
  assert(String(missingFeatureGetIdAfter.lastToolResult?.params?.recordId) === "11", "Missing feature get id probe should preserve the LLM-provided recordId.");
  assert(!Object.prototype.hasOwnProperty.call(missingFeatureGetIdAfter.lastToolResult?.params || {}, "id"), "Missing feature get id probe should not inject id from recordId.");

  const missingFeatureListPathBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const missingFeatureListPathBefore = missingFeatureListPathBeforePayload.data ?? missingFeatureListPathBeforePayload;
  const missingFeatureListPathToolExecuteBefore = Number(missingFeatureListPathBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run missing feature list path inference probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Feature List Path Validation Result", 25000);
  await waitForText(page, "did not treat featurePath as path", 25000);
  const missingFeatureListPathAfter = await waitForAudit(
    (audit) => audit.lastChatBranch === "tool-follow-up-missing-feature-list-path"
      && Number(audit.toolExecuteRequests || 0) === missingFeatureListPathToolExecuteBefore
      && audit.lastToolResult?.ok === false
      && String(audit.lastToolResult?.error || "").includes("path"),
    "missing feature list path should be returned to LLM without featurePath alias inference",
  );
  assert(Number(missingFeatureListPathAfter.toolExecuteRequests || 0) === missingFeatureListPathToolExecuteBefore, "Missing feature list path should fail before backend tool gateway execution.");
  assert(missingFeatureListPathAfter.lastToolResult?.interfaceCode === "studio.feature.list", "Missing feature list path should be recorded as a studio.feature.list tool result.");
  assert(String(missingFeatureListPathAfter.lastToolResult?.params?.featurePath) === "/datasources", "Missing feature list path probe should preserve the LLM-provided featurePath.");
  assert(!Object.prototype.hasOwnProperty.call(missingFeatureListPathAfter.lastToolResult?.params || {}, "path"), "Missing feature list path probe should not inject path from featurePath.");

  const modelListUndeclaredParamBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const modelListUndeclaredParamBefore = modelListUndeclaredParamBeforePayload.data ?? modelListUndeclaredParamBeforePayload;
  const modelListUndeclaredParamToolExecuteBefore = Number(modelListUndeclaredParamBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run model list undeclared parameter probe: do not treat tableName as keyword for /models.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Feature List Parameter Validation Result", 25000);
  await waitForText(page, "did not treat tableName as keyword", 25000);
  const modelListUndeclaredParamAfter = await waitForAudit(
    (audit) => audit.lastChatBranch === "tool-follow-up-model-list-undeclared-param"
      && Number(audit.toolExecuteRequests || 0) === modelListUndeclaredParamToolExecuteBefore
      && audit.lastToolResult?.ok === false
      && String(audit.lastToolResult?.error || "").includes("tableName"),
    "undeclared model list parameter should be returned to LLM without tableName keyword inference",
  );
  assert(Number(modelListUndeclaredParamAfter.toolExecuteRequests || 0) === modelListUndeclaredParamToolExecuteBefore, "Undeclared model list parameter should fail before backend tool gateway execution.");
  assert(modelListUndeclaredParamAfter.lastToolResult?.interfaceCode === "studio.feature.list", "Undeclared model list parameter should be recorded as a studio.feature.list tool result.");
  assert(modelListUndeclaredParamAfter.lastToolResult?.params?.path === "/models", "Undeclared model list parameter probe should target /models.");
  assert(modelListUndeclaredParamAfter.lastToolResult?.params?.tableName === "orders", "Undeclared model list parameter probe should preserve the LLM-provided tableName.");
  assert(!Object.prototype.hasOwnProperty.call(modelListUndeclaredParamAfter.lastToolResult?.params || {}, "keyword"), "Undeclared model list parameter probe should not inject keyword from tableName.");

  const contextSearchAliasParamBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const contextSearchAliasParamBefore = contextSearchAliasParamBeforePayload.data ?? contextSearchAliasParamBeforePayload;
  const contextSearchAliasParamToolExecuteBefore = Number(contextSearchAliasParamBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run context search alias parameter probe: do not treat featurePath/query as path/keyword.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Context Search Parameter Validation Result", 25000);
  await waitForText(page, "did not treat featurePath as path or query as keyword", 25000);
  const contextSearchAliasParamAfter = await waitForAudit(
    (audit) => audit.lastChatBranch === "tool-follow-up-context-search-alias-param"
      && Number(audit.toolExecuteRequests || 0) === contextSearchAliasParamToolExecuteBefore
      && audit.lastToolResult?.ok === false
      && String(audit.lastToolResult?.error || "").includes("featurePath")
      && String(audit.lastToolResult?.error || "").includes("query"),
    "context search aliases should be returned to LLM without path/keyword inference",
  );
  assert(Number(contextSearchAliasParamAfter.toolExecuteRequests || 0) === contextSearchAliasParamToolExecuteBefore, "Context search alias parameter should fail without backend gateway execution.");
  assert(contextSearchAliasParamAfter.lastToolResult?.interfaceCode === "assistant.context.search", "Context search alias probe should be recorded as assistant.context.search.");
  assert(contextSearchAliasParamAfter.lastToolResult?.params?.featurePath === "/datasources", "Context search alias probe should preserve the LLM-provided featurePath.");
  assert(contextSearchAliasParamAfter.lastToolResult?.params?.query === "mock_mysql", "Context search alias probe should preserve the LLM-provided query.");
  assert(!Object.prototype.hasOwnProperty.call(contextSearchAliasParamAfter.lastToolResult?.params || {}, "path"), "Context search alias probe should not inject path from featurePath.");
  assert(!Object.prototype.hasOwnProperty.call(contextSearchAliasParamAfter.lastToolResult?.params || {}, "keyword"), "Context search alias probe should not inject keyword from query.");

  const memorySelectRecordIdBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const memorySelectRecordIdBefore = memorySelectRecordIdBeforePayload.data ?? memorySelectRecordIdBeforePayload;
  const memorySelectRecordIdToolExecuteBefore = Number(memorySelectRecordIdBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run memory select recordId alias probe: do not treat recordId as id.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Memory Select ID Validation Result", 25000);
  await waitForText(page, "did not treat recordId as id", 25000);
  const memorySelectRecordIdAfter = await waitForAudit(
    (audit) => audit.lastChatBranch === "tool-follow-up-memory-select-record-id-param"
      && Number(audit.toolExecuteRequests || 0) === memorySelectRecordIdToolExecuteBefore
      && audit.lastToolResult?.ok === false
      && String(audit.lastToolResult?.error || "").includes("recordId"),
    "memory select recordId should be returned to LLM without id inference",
  );
  assert(Number(memorySelectRecordIdAfter.toolExecuteRequests || 0) === memorySelectRecordIdToolExecuteBefore, "Memory select recordId alias should fail without backend gateway execution.");
  assert(memorySelectRecordIdAfter.lastToolResult?.interfaceCode === "assistant.memory.select", "Memory select recordId alias should be recorded as assistant.memory.select.");
  assert(memorySelectRecordIdAfter.lastToolResult?.params?.path === "/datasources", "Memory select recordId alias probe should target /datasources.");
  assert(String(memorySelectRecordIdAfter.lastToolResult?.params?.recordId) === "11", "Memory select recordId alias probe should preserve recordId.");
  assert(!Object.prototype.hasOwnProperty.call(memorySelectRecordIdAfter.lastToolResult?.params || {}, "id"), "Memory select recordId alias probe should not inject id from recordId.");

  const memorySelectTableNameBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const memorySelectTableNameBefore = memorySelectTableNameBeforePayload.data ?? memorySelectTableNameBeforePayload;
  const memorySelectTableNameToolExecuteBefore = Number(memorySelectTableNameBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run memory select tableName alias probe: do not treat tableName as physicalLocator.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Memory Select Locator Validation Result", 25000);
  await waitForText(page, "did not treat tableName as physicalLocator", 25000);
  const memorySelectTableNameAfter = await waitForAudit(
    (audit) => audit.lastChatBranch === "tool-follow-up-memory-select-table-name-param"
      && Number(audit.toolExecuteRequests || 0) === memorySelectTableNameToolExecuteBefore
      && audit.lastToolResult?.ok === false
      && String(audit.lastToolResult?.error || "").includes("tableName"),
    "memory select tableName should be returned to LLM without physicalLocator inference",
  );
  assert(Number(memorySelectTableNameAfter.toolExecuteRequests || 0) === memorySelectTableNameToolExecuteBefore, "Memory select tableName alias should fail without backend gateway execution.");
  assert(memorySelectTableNameAfter.lastToolResult?.interfaceCode === "assistant.memory.select", "Memory select tableName alias should be recorded as assistant.memory.select.");
  assert(memorySelectTableNameAfter.lastToolResult?.params?.path === "/datasources", "Memory select tableName alias probe should target /datasources.");
  assert(memorySelectTableNameAfter.lastToolResult?.params?.tableName === "mock_mysql", "Memory select tableName alias probe should preserve tableName.");
  assert(!Object.prototype.hasOwnProperty.call(memorySelectTableNameAfter.lastToolResult?.params || {}, "physicalLocator"), "Memory select tableName alias probe should not inject physicalLocator from tableName.");

  const missingScriptToolCanonicalBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const missingScriptToolCanonicalBefore = missingScriptToolCanonicalBeforePayload.data ?? missingScriptToolCanonicalBeforePayload;
  const missingScriptToolCanonicalToolExecuteBefore = Number(missingScriptToolCanonicalBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run missing script tool canonical fields probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Script Tool Parameter Validation Result", 25000);
  await waitForText(page, "did not rewrite scriptId or payload", 25000);
  const missingScriptToolCanonicalAfter = await waitForAudit(
    (audit) => audit.lastChatBranch === "tool-follow-up-missing-script-tool-canonical-fields"
      && Number(audit.toolExecuteRequests || 0) === missingScriptToolCanonicalToolExecuteBefore
      && audit.lastToolResult?.ok === false
      && String(audit.lastToolResult?.error || "").includes("entrypointId"),
    "missing script tool canonical fields should be returned to LLM without scriptId/payload alias inference",
  );
  assert(Number(missingScriptToolCanonicalAfter.toolExecuteRequests || 0) === missingScriptToolCanonicalToolExecuteBefore, "Missing script tool canonical fields should fail before backend script execution.");
  assert(missingScriptToolCanonicalAfter.lastToolResult?.interfaceCode === "assistant.script.execute", "Missing script tool canonical fields should be recorded as assistant.script.execute.");
  assert(missingScriptToolCanonicalAfter.lastToolResult?.params?.scriptId === "field-mapping-suggester", "Missing script tool canonical probe should preserve the LLM-provided scriptId.");
  assert(missingScriptToolCanonicalAfter.lastToolResult?.params?.payload?.sourceFields?.[0] === "id", "Missing script tool canonical probe should preserve the LLM-provided payload.");
  assert(!Object.prototype.hasOwnProperty.call(missingScriptToolCanonicalAfter.lastToolResult?.params || {}, "entrypointId"), "Missing script tool canonical probe should not inject entrypointId from scriptId.");
  assert(!Object.prototype.hasOwnProperty.call(missingScriptToolCanonicalAfter.lastToolResult?.params || {}, "input"), "Missing script tool canonical probe should not inject input from payload.");

  const missingActionAliasBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const missingActionAliasBefore = missingActionAliasBeforePayload.data ?? missingActionAliasBeforePayload;
  const missingActionAliasToolExecuteBefore = Number(missingActionAliasBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run missing action alias inference probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Action Parameter Validation Result", 25000);
  await waitForText(page, "did not treat operation as action", 25000);
  const missingActionAliasAfter = await waitForAudit(
    (audit) => audit.lastChatBranch === "tool-follow-up-missing-action"
      && Number(audit.toolExecuteRequests || 0) === missingActionAliasToolExecuteBefore
      && audit.lastToolResult?.ok === false
      && String(audit.lastToolResult?.error || "").includes("action"),
    "missing action should be returned to LLM without operation alias inference",
  );
  assert(Number(missingActionAliasAfter.toolExecuteRequests || 0) === missingActionAliasToolExecuteBefore, "Missing action alias probe should fail before backend tool gateway execution.");
  assert(missingActionAliasAfter.lastToolResult?.interfaceCode === "studio.feature.action", "Missing action alias probe should be recorded as a studio.feature.action tool result.");
  assert(missingActionAliasAfter.lastToolResult?.params?.path === "/models", "Missing action alias probe should target models.");
  assert(missingActionAliasAfter.lastToolResult?.params?.operation === "sync", "Missing action alias probe should preserve the LLM-provided operation.");
  assert(String(missingActionAliasAfter.lastToolResult?.params?.datasourceId) === "11", "Missing action alias probe should preserve the LLM-provided datasourceId.");
  assert(!Object.prototype.hasOwnProperty.call(missingActionAliasAfter.lastToolResult?.params || {}, "action"), "Missing action alias probe should not inject action from operation.");

  const actionUndeclaredParamBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const actionUndeclaredParamBefore = actionUndeclaredParamBeforePayload.data ?? actionUndeclaredParamBeforePayload;
  const actionUndeclaredParamToolExecuteBefore = Number(actionUndeclaredParamBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run action undeclared parameter probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Action Declared Parameter Validation Result", 25000);
  await waitForText(page, "did not inject payload or keyword", 25000);
  const actionUndeclaredParamAfter = await waitForAudit(
    (audit) => audit.lastChatBranch === "tool-follow-up-action-undeclared-param"
      && Number(audit.toolExecuteRequests || 0) === actionUndeclaredParamToolExecuteBefore
      && audit.lastToolResult?.ok === false
      && String(audit.lastToolResult?.error || "").includes("未声明参数"),
    "undeclared action params should be returned to LLM without frontend payload/keyword inference",
  );
  assert(Number(actionUndeclaredParamAfter.toolExecuteRequests || 0) === actionUndeclaredParamToolExecuteBefore, "Undeclared action param probe should fail before backend tool gateway execution.");
  assert(actionUndeclaredParamAfter.lastToolResult?.interfaceCode === "studio.feature.action", "Undeclared action param probe should be recorded as a studio.feature.action tool result.");
  assert(actionUndeclaredParamAfter.lastToolResult?.params?.path === "/datasources", "Undeclared action param probe should target datasources.");
  assert(actionUndeclaredParamAfter.lastToolResult?.params?.action === "discover", "Undeclared action param probe should preserve the LLM-provided discover action.");
  assert(String(actionUndeclaredParamAfter.lastToolResult?.params?.id) === "11", "Undeclared action param probe should preserve the LLM-provided id.");
  assert(actionUndeclaredParamAfter.lastToolResult?.params?.data?.name === "assistant_mysql", "Undeclared action param probe should preserve the LLM-provided data payload alias.");
  assert(actionUndeclaredParamAfter.lastToolResult?.params?.name === "assistant_mysql", "Undeclared action param probe should preserve the LLM-provided name.");
  assert(actionUndeclaredParamAfter.lastToolResult?.params?.search === "assistant_mysql", "Undeclared action param probe should preserve the LLM-provided search.");
  assert(!Object.prototype.hasOwnProperty.call(actionUndeclaredParamAfter.lastToolResult?.params || {}, "payload"), "Undeclared action param probe should not inject payload from data.");
  assert(!Object.prototype.hasOwnProperty.call(actionUndeclaredParamAfter.lastToolResult?.params || {}, "keyword"), "Undeclared action param probe should not inject keyword from name/search.");

  const stopDecisionBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const stopDecisionBefore = stopDecisionBeforePayload.data ?? stopDecisionBeforePayload;
  const stopDecisionToolExecuteBefore = Number(stopDecisionBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run completion decision after datasource list; stop only if the returned list is enough.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "LLM Stop Decision Result", 25000);
  await waitForText(page, "no further Studio tool call is needed", 25000);
  await waitForText(page, "Loop 已由 LLM 结束", 25000);
  const stopDecisionAfter = await waitForAudit(
    (audit) => audit.lastChatBranch === "tool-follow-up-datasource-stop-complete"
      && Number(audit.toolExecuteRequests || 0) === stopDecisionToolExecuteBefore + 1,
    "LLM completed-loop decision after toolResults",
  );
  assert(stopDecisionAfter.lastToolExecute?.interfaceCode === "studio.feature.list", "Stop decision probe should read datasource list first.");
  assert(stopDecisionAfter.lastToolExecute?.params?.path === "/datasources", "Stop decision probe should target /datasources.");
  assert(stopDecisionAfter.lastToolExecute?.params?.keyword === "stop-decision", "Stop decision probe should use the stop-decision marker.");
  assert(stopDecisionAfter.lastToolResultExecution?.executedBy === "backend", "Stop decision probe should feed backend gateway metadata back to the LLM.");
  assert(stopDecisionAfter.lastToolResultExecution?.interfaceCode === "studio.feature.list", "Stop decision metadata should identify the list tool.");
  const visibleTextAfterStopDecision = await textContent(page);
  assert(!visibleTextAfterStopDecision.includes("studio-assistant-protocol"), "Completed-loop protocol block leaked into visible assistant text.");
  assert(visibleTextAfterStopDecision.includes("LLM Stop Decision Result"), "LLM completed-loop final answer was not rendered.");
  assert(visibleTextAfterStopDecision.includes("Loop 已由 LLM 结束"), "Completed-loop process event was not rendered.");

  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "List the available datasources and continue with a summary.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-process"]')`, 10000);
  await waitForText(page, "mock_mysql", 25000);
  await waitForText(page, "Question Breakdown", 25000);
  await click(page, `[data-testid="studio-assistant-process"] .assistant-process__summary`);
  await waitForText(page, "LLM 结构化计划", 10000);
  await waitForText(page, "Loop 已回灌工具结果", 10000);

  const visibleText = await textContent(page);
  assert(!visibleText.includes("studio-assistant-protocol"), "Protocol block leaked into visible assistant text.");
  assert(!visibleText.includes("tool_pending"), "Loop status leaked into visible assistant text.");
  assert(!visibleText.includes("waiting_for_tool_result"), "Loop stop reason leaked into visible assistant text.");
  assert(visibleText.includes("mock_mysql"), "Datasource result was not rendered.");
  assert(visibleText.includes("Question Breakdown"), "Question decomposition result was not rendered.");
  assert(visibleText.includes("LLM 结构化计划"), "Structured LLM plan event was not rendered in the process panel.");
  assert(visibleText.includes("Loop 已回灌工具结果"), "Stateful loop event was not rendered in the process panel.");

  const protocolAuditBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const protocolAuditBefore = protocolAuditBeforePayload.data ?? protocolAuditBeforePayload;
  const protocolToolExecuteBefore = Number(protocolAuditBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run missing-plan protocol probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-process"] .assistant-process__summary')`, 10000);
  await click(page, `[data-testid="studio-assistant-process"] .assistant-process__summary`);
  await waitForText(page, "协议自检完成", 25000);
  await waitForAssistantReady(page, 25000);
  await waitForAudit(
    (audit) => Number(audit.toolExecuteRequests || 0) >= protocolToolExecuteBefore + 1,
    "missing-plan protocol repair tool execution",
  );
  await sleep(500);
  const protocolAuditAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const protocolAuditAfter = protocolAuditAfterPayload.data ?? protocolAuditAfterPayload;
  assert(
    Number(protocolAuditAfter.toolExecuteRequests || 0) === protocolToolExecuteBefore + 1,
    `Missing-plan protocol should be rejected before execution and repaired with exactly one later tool call. before=${protocolToolExecuteBefore}, after=${protocolAuditAfter.toolExecuteRequests}`,
  );
  assert(protocolAuditAfter.lastToolExecute?.interfaceCode === "studio.feature.list", "Repaired protocol should execute the datasource list tool.");
  assert(protocolAuditAfter.lastToolExecute?.params?.path === "/datasources", "Repaired protocol should read /datasources.");

  const incompletePlanBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const incompletePlanBefore = incompletePlanBeforePayload.data ?? incompletePlanBeforePayload;
  const incompletePlanToolExecuteBefore = Number(incompletePlanBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run incomplete-plan protocol probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-process"] .assistant-process__summary')`, 10000);
  await click(page, `[data-testid="studio-assistant-process"] .assistant-process__summary`);
  await waitForText(page, "协议自检完成", 25000);
  await waitForAssistantReady(page, 25000);
  await waitForAudit(
    (audit) => Number(audit.toolExecuteRequests || 0) >= incompletePlanToolExecuteBefore + 1,
    "incomplete-plan protocol repair tool execution",
  );
  await sleep(500);
  const incompletePlanAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const incompletePlanAfter = incompletePlanAfterPayload.data ?? incompletePlanAfterPayload;
  const visibleTextAfterIncompletePlan = await textContent(page);
  assert(!visibleTextAfterIncompletePlan.includes("incomplete-plan-original"), "Incomplete-plan protocol action params should not leak or execute.");
  assert(
    Number(incompletePlanAfter.toolExecuteRequests || 0) === incompletePlanToolExecuteBefore + 1,
    `Incomplete-plan protocol should be rejected before execution and repaired with exactly one later tool call. before=${incompletePlanToolExecuteBefore}, after=${incompletePlanAfter.toolExecuteRequests}`,
  );
  assert(incompletePlanAfter.lastToolExecute?.interfaceCode === "studio.feature.list", "Repaired incomplete-plan protocol should execute the datasource list tool.");
  assert(incompletePlanAfter.lastToolExecute?.params?.path === "/datasources", "Repaired incomplete-plan protocol should read /datasources.");
  assert(incompletePlanAfter.lastToolExecute?.params?.keyword !== "incomplete-plan-original", "Original incomplete-plan tool call should not execute.");

  const missingLoopBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const missingLoopBefore = missingLoopBeforePayload.data ?? missingLoopBeforePayload;
  const missingLoopToolExecuteBefore = Number(missingLoopBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run missing-loop protocol probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-process"] .assistant-process__summary')`, 10000);
  await click(page, `[data-testid="studio-assistant-process"] .assistant-process__summary`);
  await waitForText(page, "协议自检完成", 25000);
  await waitForAssistantReady(page, 25000);
  await waitForAudit(
    (audit) => Number(audit.toolExecuteRequests || 0) >= missingLoopToolExecuteBefore + 1,
    "missing-loop protocol repair tool execution",
  );
  await sleep(500);
  const missingLoopAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const missingLoopAfter = missingLoopAfterPayload.data ?? missingLoopAfterPayload;
  const visibleTextAfterMissingLoop = await textContent(page);
  assert(!visibleTextAfterMissingLoop.includes("missing-loop-original"), "Missing-loop protocol action params should not leak or execute.");
  assert(
    Number(missingLoopAfter.toolExecuteRequests || 0) === missingLoopToolExecuteBefore + 1,
    `Missing-loop protocol should be rejected before execution and repaired with exactly one later tool call. before=${missingLoopToolExecuteBefore}, after=${missingLoopAfter.toolExecuteRequests}`,
  );
  assert(missingLoopAfter.lastToolExecute?.interfaceCode === "studio.feature.list", "Repaired missing-loop protocol should execute the datasource list tool.");
  assert(missingLoopAfter.lastToolExecute?.params?.path === "/datasources", "Repaired missing-loop protocol should read /datasources.");
  assert(missingLoopAfter.lastToolExecute?.params?.keyword !== "missing-loop-original", "Original missing-loop tool call should not execute.");

  const legacyInProtocolBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const legacyInProtocolBefore = legacyInProtocolBeforePayload.data ?? legacyInProtocolBeforePayload;
  const legacyInProtocolToolExecuteBefore = Number(legacyInProtocolBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run legacy-in-protocol probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-process"] .assistant-process__summary')`, 10000);
  await click(page, `[data-testid="studio-assistant-process"] .assistant-process__summary`);
  await waitForText(page, "协议自检完成", 25000);
  await waitForAssistantReady(page, 25000);
  await waitForAudit(
    (audit) => Number(audit.toolExecuteRequests || 0) >= legacyInProtocolToolExecuteBefore + 1,
    "legacy-in-protocol repair tool execution",
  );
  await sleep(500);
  const legacyInProtocolAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const legacyInProtocolAfter = legacyInProtocolAfterPayload.data ?? legacyInProtocolAfterPayload;
  const visibleTextAfterLegacyInProtocol = await textContent(page);
  assert(!visibleTextAfterLegacyInProtocol.includes("legacy-in-protocol-original"), "Legacy toolCalls inside protocol should not leak or execute.");
  assert(
    Number(legacyInProtocolAfter.toolExecuteRequests || 0) === legacyInProtocolToolExecuteBefore + 1,
    `Legacy toolCalls inside protocol should be rejected before execution and repaired with exactly one later tool call. before=${legacyInProtocolToolExecuteBefore}, after=${legacyInProtocolAfter.toolExecuteRequests}`,
  );
  assert(legacyInProtocolAfter.lastToolExecute?.interfaceCode === "studio.feature.list", "Repaired legacy-in-protocol should execute the datasource list tool.");
  assert(legacyInProtocolAfter.lastToolExecute?.params?.path === "/datasources", "Repaired legacy-in-protocol should read /datasources.");
  assert(legacyInProtocolAfter.lastToolExecute?.params?.keyword !== "legacy-in-protocol-original", "Original legacy toolCalls inside protocol should not execute.");

  const missingProtocolBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const missingProtocolBefore = missingProtocolBeforePayload.data ?? missingProtocolBeforePayload;
  const missingProtocolToolExecuteBefore = Number(missingProtocolBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run missing-protocol protocol probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-process"] .assistant-process__summary')`, 10000);
  await click(page, `[data-testid="studio-assistant-process"] .assistant-process__summary`);
  await waitForText(page, "协议自检完成", 25000);
  await waitForAssistantReady(page, 25000);
  await waitForAudit(
    (audit) => Number(audit.toolExecuteRequests || 0) >= missingProtocolToolExecuteBefore + 1,
    "missing-protocol protocol repair tool execution",
  );
  await sleep(500);
  const missingProtocolAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const missingProtocolAfter = missingProtocolAfterPayload.data ?? missingProtocolAfterPayload;
  const visibleTextAfterMissingProtocol = await textContent(page);
  assert(!visibleTextAfterMissingProtocol.includes("missing-protocol-original"), "Missing-protocol action params should not leak or execute.");
  assert(
    Number(missingProtocolAfter.toolExecuteRequests || 0) === missingProtocolToolExecuteBefore + 1,
    `Missing-protocol fenced JSON should be rejected before execution and repaired with exactly one later tool call. before=${missingProtocolToolExecuteBefore}, after=${missingProtocolAfter.toolExecuteRequests}`,
  );
  assert(missingProtocolAfter.lastToolExecute?.interfaceCode === "studio.feature.list", "Repaired missing-protocol should execute the datasource list tool.");
  assert(missingProtocolAfter.lastToolExecute?.params?.path === "/datasources", "Repaired missing-protocol should read /datasources.");
  assert(missingProtocolAfter.lastToolExecute?.params?.keyword !== "missing-protocol-original", "Original missing-protocol tool call should not execute.");

  const progressOnlyBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const progressOnlyBefore = progressOnlyBeforePayload.data ?? progressOnlyBeforePayload;
  const progressOnlyToolExecuteBefore = Number(progressOnlyBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run progress-only loop guard probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForAudit(
    (audit) => Number(audit.toolExecuteRequests || 0) >= progressOnlyToolExecuteBefore + 1,
    "progress-only response automatic protocol repair",
  );
  await waitForAssistantReady(page, 25000);
  await sleep(500);
  const progressOnlyAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const progressOnlyAfter = progressOnlyAfterPayload.data ?? progressOnlyAfterPayload;
  const visibleTextAfterProgressOnly = await textContent(page);
  assert(
    Number(progressOnlyAfter.toolExecuteRequests || 0) === progressOnlyToolExecuteBefore + 1,
    `Progress-only reply should be repaired with exactly one datasource tool call. before=${progressOnlyToolExecuteBefore}, after=${progressOnlyAfter.toolExecuteRequests}`,
  );
  assert(progressOnlyAfter.lastChatBranch === "tool-follow-up-default", "Progress-only reply repair should continue through tool result feedback to the final answer.");
  assert(progressOnlyAfter.lastToolExecute?.interfaceCode === "studio.feature.list", "Progress-only reply repair should execute the datasource list tool.");
  assert(progressOnlyAfter.lastToolExecute?.params?.path === "/datasources", "Progress-only reply repair should read /datasources.");
  assert(!visibleTextAfterProgressOnly.includes("正在读取当前项目已登记的数据源列表。"), "Incomplete progress-only narration should be removed after automatic repair.");

  const stalledLoopBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const stalledLoopBefore = stalledLoopBeforePayload.data ?? stalledLoopBeforePayload;
  const stalledLoopToolExecuteBefore = Number(stalledLoopBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run stalled-loop protocol probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForAudit(
    (audit) => Number(audit.toolExecuteRequests || 0) >= stalledLoopToolExecuteBefore + 1,
    "autoContinue protocol without actions repair",
  );
  await waitForAssistantReady(page, 25000);
  await sleep(500);
  const stalledLoopAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const stalledLoopAfter = stalledLoopAfterPayload.data ?? stalledLoopAfterPayload;
  assert(
    Number(stalledLoopAfter.toolExecuteRequests || 0) === stalledLoopToolExecuteBefore + 1,
    `autoContinue protocol without actions should be repaired with exactly one datasource tool call. before=${stalledLoopToolExecuteBefore}, after=${stalledLoopAfter.toolExecuteRequests}`,
  );
  assert(stalledLoopAfter.lastChatBranch === "tool-follow-up-default", "Stalled loop repair should continue through tool result feedback to the final answer.");
  assert(stalledLoopAfter.lastToolExecute?.interfaceCode === "studio.feature.list", "Stalled loop repair should execute the datasource list tool.");
  assert(stalledLoopAfter.lastToolExecute?.params?.path === "/datasources", "Stalled loop repair should read /datasources.");

  const looseProtocolBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const looseProtocolBefore = looseProtocolBeforePayload.data ?? looseProtocolBeforePayload;
  const looseProtocolToolExecuteBefore = Number(looseProtocolBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run loose-protocol-json probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-process"] .assistant-process__summary')`, 10000);
  await click(page, `[data-testid="studio-assistant-process"] .assistant-process__summary`);
  await waitForText(page, "协议自检完成", 25000);
  await waitForAssistantReady(page, 25000);
  await waitForAudit(
    (audit) => Number(audit.toolExecuteRequests || 0) >= looseProtocolToolExecuteBefore + 1,
    "loose-protocol-json repair tool execution",
  );
  await sleep(500);
  const looseProtocolAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const looseProtocolAfter = looseProtocolAfterPayload.data ?? looseProtocolAfterPayload;
  const visibleTextAfterLooseProtocol = await textContent(page);
  assert(!visibleTextAfterLooseProtocol.includes("loose-protocol-original"), "Loose protocol JSON action params should not leak or execute.");
  assert(!visibleTextAfterLooseProtocol.includes('"protocol":"studio-assistant.v1"'), "Loose protocol JSON should not leak into visible assistant text.");
  assert(
    Number(looseProtocolAfter.toolExecuteRequests || 0) === looseProtocolToolExecuteBefore + 1,
    `Loose protocol JSON should be rejected before execution and repaired with exactly one later tool call. before=${looseProtocolToolExecuteBefore}, after=${looseProtocolAfter.toolExecuteRequests}`,
  );
  assert(looseProtocolAfter.lastToolExecute?.interfaceCode === "studio.feature.list", "Repaired loose-protocol-json should execute the datasource list tool.");
  assert(looseProtocolAfter.lastToolExecute?.params?.path === "/datasources", "Repaired loose-protocol-json should read /datasources.");
  assert(looseProtocolAfter.lastToolExecute?.params?.keyword !== "loose-protocol-original", "Original loose-protocol-json tool call should not execute.");

  const malformedProtocolBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const malformedProtocolBefore = malformedProtocolBeforePayload.data ?? malformedProtocolBeforePayload;
  const malformedProtocolToolExecuteBefore = Number(malformedProtocolBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run malformed-protocol-json probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-process"] .assistant-process__summary')`, 10000);
  await click(page, `[data-testid="studio-assistant-process"] .assistant-process__summary`);
  await waitForText(page, "协议自检完成", 25000);
  await waitForAssistantReady(page, 25000);
  await waitForAudit(
    (audit) => Number(audit.toolExecuteRequests || 0) >= malformedProtocolToolExecuteBefore + 1,
    "malformed-protocol-json repair tool execution",
  );
  await sleep(500);
  const malformedProtocolAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const malformedProtocolAfter = malformedProtocolAfterPayload.data ?? malformedProtocolAfterPayload;
  const visibleTextAfterMalformedProtocol = await textContent(page);
  assert(!visibleTextAfterMalformedProtocol.includes("malformed-protocol-original"), "Malformed protocol JSON action params should not leak or execute.");
  assert(
    Number(malformedProtocolAfter.toolExecuteRequests || 0) === malformedProtocolToolExecuteBefore + 1,
    `Malformed protocol JSON should be rejected before execution and repaired with exactly one later tool call. before=${malformedProtocolToolExecuteBefore}, after=${malformedProtocolAfter.toolExecuteRequests}`,
  );
  assert(malformedProtocolAfter.lastToolExecute?.interfaceCode === "studio.feature.list", "Repaired malformed-protocol-json should execute the datasource list tool.");
  assert(malformedProtocolAfter.lastToolExecute?.params?.path === "/datasources", "Repaired malformed-protocol-json should read /datasources.");
  assert(malformedProtocolAfter.lastToolExecute?.params?.keyword !== "malformed-protocol-original", "Original malformed-protocol-json tool call should not execute.");

  const missingActionTypeBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const missingActionTypeBefore = missingActionTypeBeforePayload.data ?? missingActionTypeBeforePayload;
  const missingActionTypeToolExecuteBefore = Number(missingActionTypeBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run missing-action-type protocol probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-process"] .assistant-process__summary')`, 10000);
  await click(page, `[data-testid="studio-assistant-process"] .assistant-process__summary`);
  await waitForText(page, "协议自检完成", 25000);
  await waitForAssistantReady(page, 25000);
  await waitForAudit(
    (audit) => Number(audit.toolExecuteRequests || 0) >= missingActionTypeToolExecuteBefore + 1,
    "missing-action-type protocol repair tool execution",
  );
  await sleep(500);
  const missingActionTypeAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const missingActionTypeAfter = missingActionTypeAfterPayload.data ?? missingActionTypeAfterPayload;
  const visibleTextAfterMissingActionType = await textContent(page);
  assert(!visibleTextAfterMissingActionType.includes("missing-action-type-original"), "Missing-action-type protocol action params should not leak or execute.");
  assert(
    Number(missingActionTypeAfter.toolExecuteRequests || 0) === missingActionTypeToolExecuteBefore + 1,
    `Missing-action-type protocol should be rejected before execution and repaired with exactly one later tool call. before=${missingActionTypeToolExecuteBefore}, after=${missingActionTypeAfter.toolExecuteRequests}`,
  );
  assert(missingActionTypeAfter.lastToolExecute?.interfaceCode === "studio.feature.list", "Repaired missing-action-type protocol should execute the datasource list tool.");
  assert(missingActionTypeAfter.lastToolExecute?.params?.path === "/datasources", "Repaired missing-action-type protocol should read /datasources.");
  assert(missingActionTypeAfter.lastToolExecute?.params?.keyword !== "missing-action-type-original", "Original missing-action-type tool call should not execute.");

  const loopStopReasonAliasBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const loopStopReasonAliasBefore = loopStopReasonAliasBeforePayload.data ?? loopStopReasonAliasBeforePayload;
  const loopStopReasonAliasToolExecuteBefore = Number(loopStopReasonAliasBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run loop stopReason alias probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-process"] .assistant-process__summary')`, 10000);
  await click(page, `[data-testid="studio-assistant-process"] .assistant-process__summary`);
  await waitForText(page, "协议自检完成", 25000);
  await waitForAssistantReady(page, 25000);
  await waitForAudit(
    (audit) => Number(audit.toolExecuteRequests || 0) >= loopStopReasonAliasToolExecuteBefore + 1,
    "loop stopReason alias protocol repair tool execution",
  );
  await sleep(500);
  const loopStopReasonAliasAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const loopStopReasonAliasAfter = loopStopReasonAliasAfterPayload.data ?? loopStopReasonAliasAfterPayload;
  const visibleTextAfterLoopStopReasonAlias = await textContent(page);
  assert(!visibleTextAfterLoopStopReasonAlias.includes("loop-stop-reason-alias-original"), "Loop stopReason alias protocol action params should not leak or execute.");
  assert(
    Number(loopStopReasonAliasAfter.toolExecuteRequests || 0) === loopStopReasonAliasToolExecuteBefore + 1,
    `Loop stopReason alias protocol should be rejected before execution and repaired with exactly one later tool call. before=${loopStopReasonAliasToolExecuteBefore}, after=${loopStopReasonAliasAfter.toolExecuteRequests}`,
  );
  assert(loopStopReasonAliasAfter.lastToolExecute?.interfaceCode === "studio.feature.list", "Repaired loop stopReason alias protocol should execute the datasource list tool.");
  assert(loopStopReasonAliasAfter.lastToolExecute?.params?.path === "/datasources", "Repaired loop stopReason alias protocol should read /datasources.");
  assert(loopStopReasonAliasAfter.lastToolExecute?.params?.keyword !== "loop-stop-reason-alias-original", "Original loop stopReason alias tool call should not execute.");

  const missingControlTypeBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const missingControlTypeBefore = missingControlTypeBeforePayload.data ?? missingControlTypeBeforePayload;
  const missingControlTypeToolExecuteBefore = Number(missingControlTypeBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run missing-control-type protocol probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-process"] .assistant-process__summary')`, 10000);
  await click(page, `[data-testid="studio-assistant-process"] .assistant-process__summary`);
  await waitForText(page, "协议自检完成", 25000);
  await waitForAssistantReady(page, 25000);
  await waitForAudit(
    (audit) => Number(audit.toolExecuteRequests || 0) >= missingControlTypeToolExecuteBefore + 1,
    "missing-control-type protocol repair tool execution",
  );
  await sleep(500);
  const missingControlTypeAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const missingControlTypeAfter = missingControlTypeAfterPayload.data ?? missingControlTypeAfterPayload;
  const visibleTextAfterMissingControlType = await textContent(page);
  assert(!visibleTextAfterMissingControlType.includes("Invalid Missing Type Control"), "Missing-control-type protocol control should be rejected before rendering.");
  assert(
    Number(missingControlTypeAfter.toolExecuteRequests || 0) === missingControlTypeToolExecuteBefore + 1,
    `Missing-control-type protocol should be rejected before rendering and repaired with exactly one later tool call. before=${missingControlTypeToolExecuteBefore}, after=${missingControlTypeAfter.toolExecuteRequests}`,
  );
  assert(missingControlTypeAfter.lastToolExecute?.interfaceCode === "studio.feature.list", "Repaired missing-control-type protocol should execute the datasource list tool.");
  assert(missingControlTypeAfter.lastToolExecute?.params?.path === "/datasources", "Repaired missing-control-type protocol should read /datasources.");

  const controlProtocolBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const controlProtocolBefore = controlProtocolBeforePayload.data ?? controlProtocolBeforePayload;
  const controlProtocolToolExecuteBefore = Number(controlProtocolBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run missing-plan control probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-process"] .assistant-process__summary')`, 10000);
  await click(page, `[data-testid="studio-assistant-process"] .assistant-process__summary`);
  await waitForText(page, "协议自检完成", 25000);
  await waitForAssistantReady(page, 25000);
  await waitForAudit(
    (audit) => Number(audit.toolExecuteRequests || 0) >= controlProtocolToolExecuteBefore + 1,
    "missing-plan control protocol repair tool execution",
  );
  await sleep(500);
  const controlProtocolAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const controlProtocolAfter = controlProtocolAfterPayload.data ?? controlProtocolAfterPayload;
  const visibleTextAfterMissingPlanControl = await textContent(page);
  assert(!visibleTextAfterMissingPlanControl.includes("Invalid Missing Plan Control"), "Missing-plan protocol control should be rejected before rendering.");
  assert(
    Number(controlProtocolAfter.toolExecuteRequests || 0) === controlProtocolToolExecuteBefore + 1,
    `Missing-plan control protocol should be rejected and repaired with exactly one later tool call. before=${controlProtocolToolExecuteBefore}, after=${controlProtocolAfter.toolExecuteRequests}`,
  );
  assert(controlProtocolAfter.lastToolExecute?.interfaceCode === "studio.feature.list", "Repaired control protocol should execute the datasource list tool.");
  assert(controlProtocolAfter.lastToolExecute?.params?.path === "/datasources", "Repaired control protocol should read /datasources.");

  const loopOnlyProtocolBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const loopOnlyProtocolBefore = loopOnlyProtocolBeforePayload.data ?? loopOnlyProtocolBeforePayload;
  const loopOnlyProtocolToolExecuteBefore = Number(loopOnlyProtocolBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run missing-plan loop-only probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Actionless Missing Plan Repair Result", 25000);
  await waitForText(page, "completed loop with a structured plan", 25000);
  await waitForText(page, "Loop 已由 LLM 结束", 25000);
  await waitForAssistantReady(page, 25000);
  await sleep(500);
  await ensureProcessPanelExpanded(page);
  await waitForText(page, "LLM 结构化计划", 10000);
  const loopOnlyProtocolAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const loopOnlyProtocolAfter = loopOnlyProtocolAfterPayload.data ?? loopOnlyProtocolAfterPayload;
  assert(
    Number(loopOnlyProtocolAfter.toolExecuteRequests || 0) === loopOnlyProtocolToolExecuteBefore,
    `Missing-plan loop-only protocol should be rejected and repaired without executing tools. before=${loopOnlyProtocolToolExecuteBefore}, after=${loopOnlyProtocolAfter.toolExecuteRequests}`,
  );
  const visibleTextAfterLoopOnlyMissingPlan = await textContent(page);
  assert(!visibleTextAfterLoopOnlyMissingPlan.includes("studio-assistant-protocol"), "Loop-only missing-plan repair protocol block leaked into visible text.");
  assert(visibleTextAfterLoopOnlyMissingPlan.includes("LLM 结构化计划"), "Loop-only missing-plan repair should render a structured plan event.");

  const legacyActionBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const legacyActionBefore = legacyActionBeforePayload.data ?? legacyActionBeforePayload;
  const legacyActionToolExecuteBefore = Number(legacyActionBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run legacy action block probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Legacy Assistant Action Probe", 25000);
  await ensureProcessPanelExpanded(page);
  await waitForText(page, "协议自检完成", 25000);
  await waitForText(page, "Legacy Assistant Action Repair Result", 25000);
  await waitForAudit(
    (audit) => Number(audit.toolExecuteRequests || 0) >= legacyActionToolExecuteBefore + 1,
    "legacy assistant-action repaired through standard protocol execution",
  );
  await waitForAssistantReady(page, 25000);
  await sleep(500);
  const legacyActionAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const legacyActionAfter = legacyActionAfterPayload.data ?? legacyActionAfterPayload;
  const visibleTextAfterLegacyAction = await textContent(page);
  assert(!visibleTextAfterLegacyAction.includes("assistant-action"), "Legacy assistant-action block leaked into visible assistant text.");
  assert(!visibleTextAfterLegacyAction.includes('"toolCalls"'), "Legacy action JSON leaked into visible assistant text.");
  assert(!visibleTextAfterLegacyAction.includes("This legacy block must not execute."), "Legacy action reason leaked into visible assistant text.");
  assert(
    Number(legacyActionAfter.toolExecuteRequests || 0) === legacyActionToolExecuteBefore + 1,
    `Legacy assistant-action block should be rejected before execution and repaired with exactly one later tool call. before=${legacyActionToolExecuteBefore}, after=${legacyActionAfter.toolExecuteRequests}`,
  );
  assert(legacyActionAfter.lastChatBranch === "tool-follow-up-default", "Legacy assistant-action repair should complete after feeding the repaired tool result back to the LLM.");
  assert(legacyActionAfter.lastToolExecute?.interfaceCode === "studio.feature.list", "Repaired legacy assistant-action should execute the datasource list tool.");
  assert(legacyActionAfter.lastToolExecute?.params?.path === "/datasources", "Repaired legacy assistant-action should read /datasources.");

  const legacyGatewayBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const legacyGatewayBefore = legacyGatewayBeforePayload.data ?? legacyGatewayBeforePayload;
  const legacyGatewayToolExecuteBefore = Number(legacyGatewayBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run legacy capability probe through the assistant gateway.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Legacy Tool Repair", 25000);
  await waitForText(page, "Result", 25000);
  await waitForText(page, "mock_mysql", 25000);
  await waitForAudit(
    (audit) => Number(audit.toolExecuteRequests || 0) >= legacyGatewayToolExecuteBefore + 1,
    "legacy read-only tool repaired through generic backend gateway execution",
  );
  const legacyGatewayAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const legacyGatewayAfter = legacyGatewayAfterPayload.data ?? legacyGatewayAfterPayload;
  assert(legacyGatewayAfter.lastToolExecute?.interfaceCode === "studio.feature.list", "Legacy datasource options tool should be repaired to the generic datasource list tool.");
  assert(legacyGatewayAfter.lastToolExecute?.params?.path === "/datasources", "Repaired legacy datasource options should read /datasources.");
  assert(legacyGatewayAfter.lastToolResultExecution?.executedBy === "backend", "Backend gateway execution metadata should be fed back to the LLM in toolResults.");
  assert(legacyGatewayAfter.lastToolResultExecution?.interfaceCode === "studio.feature.list", "Tool result execution metadata should identify the repaired generic backend tool interface.");
  assert(legacyGatewayAfter.lastToolResultExecution?.params?.path === "/datasources", "Tool result execution metadata should preserve repaired backend gateway params.");

  const defaultedParamsBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const defaultedParamsBefore = defaultedParamsBeforePayload.data ?? defaultedParamsBeforePayload;
  const defaultedParamsToolExecuteBefore = Number(defaultedParamsBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Probe defaulted params without pagination defaults.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  const defaultedParamsAfter = await waitForAudit(
    (audit) => Number(audit.toolExecuteRequests || 0) >= defaultedParamsToolExecuteBefore + 1
      && audit.lastToolExecute?.interfaceCode === "studio.feature.list"
      && audit.lastToolExecute?.params?.path === "/datasources"
      && Array.isArray(audit.lastToolResultExecution?.defaultedParams)
      && audit.lastToolResultExecution.defaultedParams.includes("pageNo")
      && audit.lastToolResultExecution.defaultedParams.includes("pageSize"),
    "backend defaulted params feedback",
  );
  assert(!Object.prototype.hasOwnProperty.call(defaultedParamsAfter.lastToolExecute.params, "pageNo"), "LLM protocol should omit pageNo in the defaulted params probe.");
  assert(!Object.prototype.hasOwnProperty.call(defaultedParamsAfter.lastToolExecute.params, "pageSize"), "LLM protocol should omit pageSize in the defaulted params probe.");
  assert(defaultedParamsAfter.lastToolResultExecution?.effectiveParams?.pageNo === 1, "Backend execution metadata should expose effective pageNo=1.");
  assert(defaultedParamsAfter.lastToolResultExecution?.effectiveParams?.pageSize === 20, "Backend execution metadata should expose effective pageSize=20.");

  const paginationLoopBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const paginationLoopBefore = paginationLoopBeforePayload.data ?? paginationLoopBeforePayload;
  const paginationLoopHistoryBefore = Array.isArray(paginationLoopBefore.toolExecuteHistory)
    ? paginationLoopBefore.toolExecuteHistory.length
    : 0;
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run paginated datasource loop using hasMore loop.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Paginated Datasource Loop Result", 25000);
  await waitForText(page, "mock_mysql and mock_postgres", 25000);

  const paginationLoopAfterPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const paginationLoopAfter = paginationLoopAfterPayload.data ?? paginationLoopAfterPayload;
  const paginationLoopHistory = Array.isArray(paginationLoopAfter.toolExecuteHistory)
    ? paginationLoopAfter.toolExecuteHistory.slice(paginationLoopHistoryBefore)
    : [];
  const paginationLoopCalls = paginationLoopHistory.filter((item) =>
    item?.interfaceCode === "studio.feature.list"
      && item?.params?.path === "/datasources"
      && item?.params?.keyword === "loop-pages"
  );
  assert(paginationLoopAfter.lastChatBranch === "tool-follow-up-datasource-loop-complete", "LLM pagination loop should finish only after the follow-up page result.");
  assert(paginationLoopCalls.length === 2, `LLM pagination loop should execute exactly two datasource page reads. calls=${paginationLoopCalls.length}`);
  assert(paginationLoopCalls[0]?.params?.pageNo === 1, "LLM pagination loop should start at pageNo=1.");
  assert(paginationLoopCalls[1]?.params?.pageNo === 2, "LLM pagination loop should request pageNo=2 after toolResults.hasMore.");
  assert(paginationLoopAfter.lastToolResultExecution?.effectiveParams?.pageNo === 2, "Final pagination loop execution metadata should expose effective pageNo=2.");

  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Return every real physical table in mock_mysql; do not use pagination.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Datasource Table Discovery Result", 25000);
  await waitForText(page, "orders and customers", 25000);
  await waitForText(page, "did not include pageNo or pageSize", 25000);

  const visibleTextAfterAllTables = await textContent(page);
  assert(!visibleTextAfterAllTables.includes("studio-assistant-protocol"), "Protocol block leaked after datasource table discovery.");
  assert(visibleTextAfterAllTables.includes("Datasource Table Discovery Result"), "Datasource discovery result was not rendered.");
  assert(visibleTextAfterAllTables.includes("orders and customers"), "Datasource discovery table names were not rendered.");

  const allTablesAuditPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const allTablesAudit = allTablesAuditPayload.data ?? allTablesAuditPayload;
  const allTablesParams = allTablesAudit.lastToolExecute?.params ?? {};
  assert(allTablesAudit.lastToolExecute?.interfaceCode === "studio.feature.action", `Datasource discovery should execute through studio.feature.action. actual=${JSON.stringify(allTablesAudit.lastToolExecute)}`);
  assert(allTablesParams.path === "/datasources", "Datasource discovery should target /datasources.");
  assert(allTablesParams.action === "discover", "Datasource discovery should use the discover action.");
  assert(String(allTablesParams.id) === "11", "Datasource discovery should use the selected mock datasource id.");
  assert(!Object.prototype.hasOwnProperty.call(allTablesParams, "pageNo"), "All-table discovery should not inject pageNo.");
  assert(!Object.prototype.hasOwnProperty.call(allTablesParams, "pageSize"), "All-table discovery should not inject pageSize.");
  assert(Array.isArray(allTablesAudit.lastToolResultExecution?.defaultedParams) && allTablesAudit.lastToolResultExecution.defaultedParams.length === 0, "All-table discovery should not report defaulted pagination.");
  assert(!Object.prototype.hasOwnProperty.call(allTablesAudit.lastToolResultExecution?.effectiveParams || {}, "pageNo"), "All-table discovery effective params should not contain pageNo.");
  assert(!Object.prototype.hasOwnProperty.call(allTablesAudit.lastToolResultExecution?.effectiveParams || {}, "pageSize"), "All-table discovery effective params should not contain pageSize.");

  await click(page, `[data-testid="studio-assistant-language-zh"]`);
  await waitForText(page, "目标模式", 10000);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "把当前这个数据源的所有真实表都列出来。");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "数据源表发现结果", 25000);
  await waitForText(page, "orders、customers", 25000);
  await waitForText(page, "没有包含 pageNo 或 pageSize", 25000);

  const fuzzyAllTablesAuditPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const fuzzyAllTablesAudit = fuzzyAllTablesAuditPayload.data ?? fuzzyAllTablesAuditPayload;
  const fuzzyAllTablesParams = fuzzyAllTablesAudit.lastToolExecute?.params ?? {};
  assert(fuzzyAllTablesAudit.lastChatBranch === "tool-follow-up-datasource-discover", "Chinese fuzzy all-table prompt should finish through datasource discover follow-up.");
  assert(fuzzyAllTablesAudit.lastToolExecute?.interfaceCode === "studio.feature.action", "Chinese fuzzy all-table prompt should execute through studio.feature.action.");
  assert(fuzzyAllTablesParams.path === "/datasources", "Chinese fuzzy all-table prompt should target /datasources.");
  assert(fuzzyAllTablesParams.action === "discover", "Chinese fuzzy all-table prompt should use the discover action.");
  assert(String(fuzzyAllTablesParams.id) === "11", "Chinese fuzzy all-table prompt should use the current page datasource id.");
  assert(!Object.prototype.hasOwnProperty.call(fuzzyAllTablesParams, "pageNo"), "Chinese fuzzy all-table prompt should not inject pageNo.");
  assert(!Object.prototype.hasOwnProperty.call(fuzzyAllTablesParams, "pageSize"), "Chinese fuzzy all-table prompt should not inject pageSize.");
  assert(Array.isArray(fuzzyAllTablesAudit.lastToolResultExecution?.defaultedParams) && fuzzyAllTablesAudit.lastToolResultExecution.defaultedParams.length === 0, "Chinese fuzzy all-table prompt should not report defaulted pagination.");
  assert(!Object.prototype.hasOwnProperty.call(fuzzyAllTablesAudit.lastToolResultExecution?.effectiveParams || {}, "pageNo"), "Chinese fuzzy all-table effective params should not contain pageNo.");
  assert(!Object.prototype.hasOwnProperty.call(fuzzyAllTablesAudit.lastToolResultExecution?.effectiveParams || {}, "pageSize"), "Chinese fuzzy all-table effective params should not contain pageSize.");

  await click(page, `[data-testid="studio-assistant-language-en"]`);
  await waitForText(page, "Goal mode", 10000);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "List metadata schemas through the metadata center.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Metadata Schemas Result", 25000);
  await waitForText(page, "datasource:mysql8", 25000);

  const visibleTextAfterMetadata = await textContent(page);
  assert(!visibleTextAfterMetadata.includes("studio-assistant-protocol"), "Protocol block leaked after metadata query.");
  assert(visibleTextAfterMetadata.includes("Metadata Schemas Result"), "Metadata backend result was not rendered.");
  assert(visibleTextAfterMetadata.includes("datasource:mysql8"), "Metadata schema result was not rendered.");

  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "List statistics options through the statistics center.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Statistics Options Result", 25000);
  await waitForText(page, "business:order", 25000);

  const visibleTextAfterStatistics = await textContent(page);
  assert(!visibleTextAfterStatistics.includes("studio-assistant-protocol"), "Protocol block leaked after statistics query.");
  assert(visibleTextAfterStatistics.includes("Statistics Options Result"), "Statistics backend result was not rendered.");
  assert(visibleTextAfterStatistics.includes("business:order"), "Statistics option schema result was not rendered.");

  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "List system users through system management.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "System Users Result", 25000);
  await waitForText(page, "assistant_admin", 25000);

  const visibleTextAfterSystemUsers = await textContent(page);
  assert(!visibleTextAfterSystemUsers.includes("studio-assistant-protocol"), "Protocol block leaked after system users query.");
  assert(visibleTextAfterSystemUsers.includes("System Users Result"), "System users backend result was not rendered.");
  assert(visibleTextAfterSystemUsers.includes("assistant_admin"), "System user result was not rendered.");

  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Approve registration request 88 through system management.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "确认执行 Studio 操作", 25000);
  await clickByText(page, "button", "确认执行");
  await waitForText(page, "Registration Approval Result", 25000);
  await waitForText(page, "registered_user", 25000);

  const visibleTextAfterRegistrationApproval = await textContent(page);
  assert(!visibleTextAfterRegistrationApproval.includes("studio-assistant-protocol"), "Protocol block leaked after system registration approval.");
  assert(!visibleTextAfterRegistrationApproval.includes("user_confirmation_pending"), "Loop confirmation status leaked after system registration approval.");
  assert(visibleTextAfterRegistrationApproval.includes("Registration Approval Result"), "System registration approval result was not rendered.");
  assert(visibleTextAfterRegistrationApproval.includes("registered_user"), "Approved registration user was not rendered.");

  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "List unread notifications through notification center.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Notification Inbox Result", 25000);
  await waitForText(page, "ASSISTANT-NOTICE-501", 25000);

  const visibleTextAfterNotifications = await textContent(page);
  assert(!visibleTextAfterNotifications.includes("studio-assistant-protocol"), "Protocol block leaked after notification query.");
  assert(visibleTextAfterNotifications.includes("Notification Inbox Result"), "Notification backend result was not rendered.");
  assert(visibleTextAfterNotifications.includes("ASSISTANT-NOTICE-501"), "Notification result was not rendered.");

  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Mark notification 501 as read through notification center.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "确认执行 Studio 操作", 25000);
  await clickByText(page, "button", "确认执行");
  await waitForText(page, "Notification Read Result", 25000);
  await waitForText(page, "ASSISTANT-NOTICE-501", 25000);

  const visibleTextAfterNotificationRead = await textContent(page);
  assert(!visibleTextAfterNotificationRead.includes("studio-assistant-protocol"), "Protocol block leaked after notification mark-read.");
  assert(!visibleTextAfterNotificationRead.includes("user_confirmation_pending"), "Loop confirmation status leaked after notification mark-read.");
  assert(visibleTextAfterNotificationRead.includes("Notification Read Result"), "Notification mark-read result was not rendered.");
  assert(visibleTextAfterNotificationRead.includes("ASSISTANT-NOTICE-501"), "Notification mark-read target was not rendered.");

  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "List open quality issues through quality metrics.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Quality Issues Result", 25000);
  await waitForText(page, "QI-91", 25000);

  const visibleTextAfterQualityMetrics = await textContent(page);
  assert(!visibleTextAfterQualityMetrics.includes("studio-assistant-protocol"), "Protocol block leaked after quality metrics query.");
  assert(visibleTextAfterQualityMetrics.includes("Quality Issues Result"), "Quality metrics backend result was not rendered.");
  assert(visibleTextAfterQualityMetrics.includes("QI-91"), "Quality issue result was not rendered.");

  const runtimeClusterResolutionBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const runtimeClusterResolutionBefore = runtimeClusterResolutionBeforePayload.data ?? runtimeClusterResolutionBeforePayload;
  const runtimeClusterResolutionToolExecuteBefore = Number(runtimeClusterResolutionBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Resolve model preview runtime cluster probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Runtime Cluster Resolution Result", 25000);
  const runtimeClusterResolutionAfter = await waitForAudit(
    (audit) => audit.lastChatBranch === "tool-follow-up-runtime-cluster-resolved"
      && Number(audit.toolExecuteRequests || 0) === runtimeClusterResolutionToolExecuteBefore + 1
      && audit.lastToolExecute?.interfaceCode === "studio.feature.list"
      && audit.lastToolExecute?.params?.path === "/runtime-clusters",
    "unique runtime cluster resolution should complete before model preview",
  );
  assert(runtimeClusterResolutionAfter.lastToolExecute?.params?.modelIds?.[0] === 5, "Runtime cluster resolution should use model applicability evidence.");

  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Suggest field mappings from id, order_no, amount to ID, orderNo, missing_col.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Field Mapping Script Result", 25000);
  await waitForText(page, "mapped 2 fields", 25000);
  await waitForText(page, "missing_col", 25000);

  const visibleTextAfterScript = await textContent(page);
  assert(!visibleTextAfterScript.includes("studio-assistant-protocol"), "Protocol block leaked after script tool execution.");
  assert(visibleTextAfterScript.includes("Field Mapping Script Result"), "Script tool result was not rendered.");
  assert(visibleTextAfterScript.includes("missing_col"), "Script tool unresolved field was not rendered.");

  const missingScriptTypeBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const missingScriptTypeBefore = missingScriptTypeBeforePayload.data ?? missingScriptTypeBeforePayload;
  const missingScriptTypeToolExecuteBefore = Number(missingScriptTypeBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run missing script type inference probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Script Parameter Validation Result", 25000);
  await waitForText(page, "did not infer scriptType locally", 25000);
  const missingScriptTypeAfter = await waitForAudit(
    (audit) => audit.lastChatBranch === "tool-follow-up-missing-script-type"
      && Number(audit.toolExecuteRequests || 0) === missingScriptTypeToolExecuteBefore
      && audit.lastToolResult?.ok === false
      && String(audit.lastToolResult?.error || "").includes("payload.scriptType"),
    "missing scriptType should be returned to LLM without frontend inference",
  );
  assert(Number(missingScriptTypeAfter.toolExecuteRequests || 0) === missingScriptTypeToolExecuteBefore, "Missing scriptType should fail before backend tool gateway execution.");
  assert(missingScriptTypeAfter.lastToolResult?.interfaceCode === "studio.feature.action", "Missing scriptType should be recorded as a studio.feature.action tool result.");
  assert(missingScriptTypeAfter.lastToolResult?.params?.path === "/data-development", "Missing scriptType probe should target data-development.");
  assert(missingScriptTypeAfter.lastToolResult?.params?.payload?.fileName === "assistant_missing_type.sql", "Missing scriptType probe should preserve the LLM-provided fileName without using it to infer scriptType.");
  assert(!Object.prototype.hasOwnProperty.call(missingScriptTypeAfter.lastToolResult?.params?.payload || {}, "scriptType"), "Missing scriptType probe should not inject payload.scriptType.");

  const missingDatasourceIdBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const missingDatasourceIdBefore = missingDatasourceIdBeforePayload.data ?? missingDatasourceIdBeforePayload;
  const missingDatasourceIdToolExecuteBefore = Number(missingDatasourceIdBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run missing datasourceId inference probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Datasource Parameter Validation Result", 25000);
  await waitForText(page, "did not treat id as datasourceId", 25000);
  const missingDatasourceIdAfter = await waitForAudit(
    (audit) => audit.lastChatBranch === "tool-follow-up-missing-datasource-id"
      && Number(audit.toolExecuteRequests || 0) === missingDatasourceIdToolExecuteBefore
      && audit.lastToolResult?.ok === false
      && String(audit.lastToolResult?.error || "").includes("datasourceId"),
    "missing datasourceId should be returned to LLM without id alias inference",
  );
  assert(Number(missingDatasourceIdAfter.toolExecuteRequests || 0) === missingDatasourceIdToolExecuteBefore, "Missing datasourceId should fail before backend tool gateway execution.");
  assert(missingDatasourceIdAfter.lastToolResult?.interfaceCode === "studio.feature.action", "Missing datasourceId should be recorded as a studio.feature.action tool result.");
  assert(missingDatasourceIdAfter.lastToolResult?.params?.path === "/models", "Missing datasourceId probe should target models.");
  assert(missingDatasourceIdAfter.lastToolResult?.params?.action === "sync", "Missing datasourceId probe should use the model sync action.");
  assert(String(missingDatasourceIdAfter.lastToolResult?.params?.id) === "11", "Missing datasourceId probe should preserve the LLM-provided generic id.");
  assert(!Object.prototype.hasOwnProperty.call(missingDatasourceIdAfter.lastToolResult?.params || {}, "datasourceId"), "Missing datasourceId probe should not inject datasourceId from id.");

  const missingPreviewClusterBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const missingPreviewClusterBefore = missingPreviewClusterBeforePayload.data ?? missingPreviewClusterBeforePayload;
  const missingPreviewClusterToolExecuteBefore = Number(missingPreviewClusterBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run missing model preview cluster probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Model Preview Cluster Validation Result", 25000);
  const missingPreviewClusterAfter = await waitForAudit(
    (audit) => audit.lastChatBranch === "tool-follow-up-missing-model-preview-cluster"
      && Number(audit.toolExecuteRequests || 0) === missingPreviewClusterToolExecuteBefore
      && audit.lastToolResult?.ok === false
      && String(audit.lastToolResult?.error || "").includes("runtimeClusterId"),
    "missing model preview runtimeClusterId should be rejected before backend gateway execution",
  );
  assert(missingPreviewClusterAfter.lastToolResult?.params?.path === "/models", "Missing preview cluster probe should target models.");
  assert(missingPreviewClusterAfter.lastToolResult?.params?.action === "preview", "Missing preview cluster probe should use preview action.");
  assert(String(missingPreviewClusterAfter.lastToolResult?.params?.id) === "5", "Missing preview cluster probe should preserve model id.");

  const validPreviewBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const validPreviewBefore = validPreviewBeforePayload.data ?? validPreviewBeforePayload;
  const validPreviewToolExecuteBefore = Number(validPreviewBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run model preview with runtime cluster probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  const validPreviewAfter = await waitForAudit(
    (audit) => Number(audit.toolExecuteRequests || 0) === validPreviewToolExecuteBefore + 1
      && audit.lastToolExecute?.interfaceCode === "studio.feature.action"
      && audit.lastToolExecute?.params?.path === "/models"
      && audit.lastToolExecute?.params?.action === "preview",
    "cluster-scoped model preview should reach backend gateway",
  );
  assert(String(validPreviewAfter.lastToolExecute?.params?.runtimeClusterId) === "7", "Model preview should preserve runtimeClusterId.");
  assert(validPreviewAfter.lastToolExecute?.params?.limit === 20, "Model preview should preserve limit.");

  const missingDataDevelopmentPayloadBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const missingDataDevelopmentPayloadBefore = missingDataDevelopmentPayloadBeforePayload.data ?? missingDataDevelopmentPayloadBeforePayload;
  const missingDataDevelopmentPayloadToolExecuteBefore = Number(missingDataDevelopmentPayloadBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run data development payload alias inference probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Data Development Payload Validation Result", 25000);
  await waitForText(page, "did not move top-level datasourceId", 25000);
  const missingDataDevelopmentPayloadAfter = await waitForAudit(
    (audit) => audit.lastChatBranch === "tool-follow-up-missing-data-development-payload"
      && Number(audit.toolExecuteRequests || 0) === missingDataDevelopmentPayloadToolExecuteBefore
      && audit.lastToolResult?.ok === false
      && String(audit.lastToolResult?.error || "").includes("payload.datasourceId")
      && String(audit.lastToolResult?.error || "").includes("payload.content"),
    "data-development payload aliases should be returned to LLM without frontend rewriting",
  );
  assert(Number(missingDataDevelopmentPayloadAfter.toolExecuteRequests || 0) === missingDataDevelopmentPayloadToolExecuteBefore, "Data-development payload alias probe should fail before backend tool gateway execution.");
  assert(missingDataDevelopmentPayloadAfter.lastToolResult?.interfaceCode === "studio.feature.action", "Data-development payload alias probe should be recorded as a studio.feature.action tool result.");
  assert(missingDataDevelopmentPayloadAfter.lastToolResult?.params?.path === "/data-development", "Data-development payload alias probe should target data-development.");
  assert(missingDataDevelopmentPayloadAfter.lastToolResult?.params?.resource === "sql", "Data-development payload alias probe should target sql resource.");
  assert(missingDataDevelopmentPayloadAfter.lastToolResult?.params?.action === "executeSql", "Data-development payload alias probe should use executeSql action.");
  assert(String(missingDataDevelopmentPayloadAfter.lastToolResult?.params?.datasourceId) === "11", "Data-development payload alias probe should preserve the LLM-provided top-level datasourceId.");
  assert(missingDataDevelopmentPayloadAfter.lastToolResult?.params?.payload?.sql === "select 1", "Data-development payload alias probe should preserve the LLM-provided payload.sql.");
  assert(!Object.prototype.hasOwnProperty.call(missingDataDevelopmentPayloadAfter.lastToolResult?.params?.payload || {}, "datasourceId"), "Data-development payload alias probe should not inject payload.datasourceId from top-level datasourceId.");
  assert(!Object.prototype.hasOwnProperty.call(missingDataDevelopmentPayloadAfter.lastToolResult?.params?.payload || {}, "content"), "Data-development payload alias probe should not rewrite payload.sql to payload.content.");

  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Execute select 1 on mock_mysql and continue with the result.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "确认执行 Studio 操作", 25000);
  await waitForText(page, "这个操作会改变系统状态，需要你确认后执行。", 25000);
  await clickByText(page, "button", "确认执行");
  await waitForText(page, "已确认，开始执行", 25000);
  await waitForText(page, "SQL Execution Result", 25000);
  await waitForText(page, "rowCount=1", 25000);

  const visibleTextAfterAction = await textContent(page);
  assert(!visibleTextAfterAction.includes("studio-assistant-protocol"), "Protocol block leaked after confirmed action.");
  assert(!visibleTextAfterAction.includes("user_confirmation_pending"), "Loop confirmation status leaked after confirmed action.");
  assert(visibleTextAfterAction.includes("SQL Execution Result"), "Confirmed SQL result was not rendered.");
  assert(visibleTextAfterAction.includes("rowCount=1"), "Confirmed SQL row count was not rendered.");

  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Try an unsupported save script action to verify frontend fallback is not used.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "确认执行 Studio 操作", 25000);
  await clickByText(page, "button", "确认执行");
  await waitForText(page, "Backend Gateway Rejection", 25000);

  const visibleTextAfterRejection = await textContent(page);
  assert(!visibleTextAfterRejection.includes("studio-assistant-protocol"), "Protocol block leaked after backend rejection.");
  assert(!visibleTextAfterRejection.includes("user_confirmation_pending"), "Loop confirmation status leaked after backend rejection.");
  assert(visibleTextAfterRejection.includes("Backend Gateway Rejection"), "Backend rejection recovery was not rendered.");
  assert(!visibleTextAfterRejection.includes("FRONTEND_FALLBACK_SHOULD_NOT_RUN"), "Frontend fallback ran after backend rejection.");

  const multiClusterBeforePayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const multiClusterBefore = multiClusterBeforePayload.data ?? multiClusterBeforePayload;
  const multiClusterToolExecuteBefore = Number(multiClusterBefore.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "List runtime cluster candidates for user selection.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "选择运行集群", 25000);
  await click(page, ".assistant-chat-control__select .el-select__wrapper");
  await waitForText(page, "assistant-online | code=CLUSTER_A | id=7 | status=ONLINE", 25000);
  await waitForText(page, "assistant-offline | code=CLUSTER_B | id=8 | status=OFFLINE", 25000);
  const multiClusterAfter = await waitForAudit(
    (audit) => Number(audit.toolExecuteRequests || 0) === multiClusterToolExecuteBefore + 1
      && audit.lastToolExecute?.interfaceCode === "studio.feature.list"
      && audit.lastToolExecute?.params?.path === "/runtime-clusters",
    "multiple runtime cluster candidates should be returned before user selection",
  );
  assert(!multiClusterAfter.lastToolExecute?.params?.datasourceIds, "Multiple-candidate probe should not inject datasource evidence.");
  await clickByText(page, ".el-select-dropdown__item", "assistant-offline");
  await waitForText(page, "Runtime Cluster Selection Result", 25000);
  const offlineSelectionAfter = await waitForAudit(
    (audit) => audit.lastChatBranch === "runtime-cluster-selection-complete"
      && String(audit.lastAssistantMemory?.selectedRuntimeCluster?.id || "") === "8",
    "explicit offline runtime cluster selection should remain in assistant memory",
  );
  assert(offlineSelectionAfter.lastAssistantMemory?.selectedRuntimeCluster?.name === "assistant-offline", "The explicitly selected offline cluster should not be replaced by the online candidate.");

  const clusterMismatchToolExecuteBefore = Number(offlineSelectionAfter.toolExecuteRequests || 0);
  await waitForAssistantReady(page);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "Run model preview with runtime cluster probe.");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Model Preview Cluster Validation Result", 25000);
  const clusterMismatchAfter = await waitForAudit(
    (audit) => Number(audit.toolExecuteRequests || 0) === clusterMismatchToolExecuteBefore
      && audit.lastToolResult?.ok === false
      && String(audit.lastToolResult?.params?.runtimeClusterId || "") === "7"
      && String(audit.lastToolResult?.error || "").includes("用户已选择的集群 8")
      && String(audit.lastAssistantMemory?.selectedRuntimeCluster?.id || "") === "8",
    "explicit offline selection should block an unconfirmed cluster switch",
  );
  assert(Number(clusterMismatchAfter.toolExecuteRequests || 0) === clusterMismatchToolExecuteBefore, "Cluster mismatch must fail before backend gateway execution.");

  await waitForAssistantReady(page);
  await click(page, `[data-testid="studio-assistant-mode-plan"]`);
  await click(page, `[data-testid="studio-assistant-language-zh"]`);
  await waitForText(page, "计划模式", 10000);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "列出数据源并继续总结。");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "问题拆解", 25000);
  await waitForText(page, "平台返回 1 个数据源：mock_mysql", 25000);

  const visibleTextAfterPlanZh = await textContent(page);
  assert(!visibleTextAfterPlanZh.includes("studio-assistant-protocol"), "Protocol block leaked after plan/zh query.");
  assert(visibleTextAfterPlanZh.includes("计划模式"), "Plan mode label was not visible.");
  assert(visibleTextAfterPlanZh.includes("问题拆解"), "Chinese question breakdown was not rendered.");
  assert(visibleTextAfterPlanZh.includes("平台返回 1 个数据源：mock_mysql"), "Chinese datasource summary was not rendered.");

  const auditPayload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
  const audit = auditPayload.data ?? auditPayload;
  assert(audit.chatRequests >= 8, "Assistant stream requests were not observed by the mock server.");
  assert(audit.assistantCapabilityContexts === audit.chatRequests, "Assistant context did not consistently use assistantFeatures/assistantCapabilities.");
  assert(audit.backendCatalogContexts === audit.chatRequests, "Assistant context did not consistently use the backend operation catalog.");
  assert(audit.leakedAccessibleCapabilityContexts === 0, "Legacy accessibleFeatures/accessibleCapabilities leaked into assistant context.");
  assert(audit.legacyFrontendToolContexts === 0, "Legacy narrow read-only tool names leaked into frontendTools; Web LLM should use generic studio.feature.* tools.");

  const screenshot = await page.send("Page.captureScreenshot", { format: "png", captureBeyondViewport: true });
  const screenshotPath = path.join(targetDir, "assistant-ui-smoke.png");
  await fs.writeFile(screenshotPath, Buffer.from(screenshot.data, "base64"));

  const meaningfulBrowserErrors = browserErrors.filter((item) => !isIgnorableBrowserError(item));
  if (meaningfulBrowserErrors.length) {
    throw new Error(`Browser console/runtime errors:\n${meaningfulBrowserErrors.join("\n")}`);
  }
  console.log(`Assistant UI smoke passed. Screenshot: ${screenshotPath}`);
  page.close();
}

function startProcess(command, args, options) {
  const child = spawn(command, args, {
    cwd: workspaceRoot,
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
    ...options,
  });
  child.stdout.on("data", (chunk) => process.stdout.write(prefixOutput(child.pid, chunk)));
  child.stderr.on("data", (chunk) => process.stderr.write(prefixOutput(child.pid, chunk)));
  child.on("exit", (code, signal) => {
    if (code && code !== 0 && !process.exitCode && !stoppingPids.has(child.pid)) {
      console.error(`Process ${child.pid} exited with code ${code}${signal ? ` signal ${signal}` : ""}.`);
    }
  });
  return child;
}

function prefixOutput(pid, chunk) {
  return String(chunk).split(/\r?\n/).filter(Boolean).map((line) => `[${pid}] ${line}\n`).join("");
}

function stopProcess(child) {
  if (!child || child.killed) {
    return;
  }
  stoppingPids.add(child.pid);
  if (process.platform === "win32") {
    spawnSync("taskkill", ["/pid", String(child.pid), "/t", "/f"], { stdio: "ignore" });
    return;
  }
  child.kill("SIGTERM");
}

function startAssistantMock(enabled, options = {}) {
  return startProcess(process.execPath, ["./scripts/assistant-ui-mock-server.mjs"], {
    env: {
      ...process.env,
      STUDIO_ASSISTANT_UI_MOCK_PORT: String(mockPort),
      STUDIO_ASSISTANT_MOCK_LLM_ENABLED: enabled ? "true" : "false",
      STUDIO_ASSISTANT_MOCK_CONFIG_UNAVAILABLE: options.configUnavailable ? "true" : "false",
    },
  });
}

function resolveBrowserPath() {
  const candidates = [
    process.env.STUDIO_ASSISTANT_SMOKE_BROWSER,
    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
    "/usr/bin/chromium-browser",
    "/usr/bin/microsoft-edge",
  ].filter(Boolean);
  for (const candidate of candidates) {
    if (existsSync(candidate)) {
      return candidate;
    }
  }
  throw new Error("Chrome or Edge executable was not found. Set STUDIO_ASSISTANT_SMOKE_BROWSER to run the UI smoke test.");
}

async function waitForHttp(url, label, timeoutMs = 30000) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    try {
      const response = await fetch(url);
      if (response.ok) {
        return;
      }
    } catch {
      // Keep waiting.
    }
    await sleep(250);
  }
  throw new Error(`Timed out waiting for ${label}: ${url}`);
}

async function waitForHttpUnavailable(url, label, timeoutMs = 10000) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    try {
      const response = await fetch(url);
      if (!response.ok) {
        return;
      }
    } catch {
      return;
    }
    await sleep(250);
  }
  throw new Error(`Timed out waiting for ${label} to stop: ${url}`);
}

async function waitForAudit(predicate, label, timeoutMs = 30000) {
  const startedAt = Date.now();
  let latest = null;
  while (Date.now() - startedAt < timeoutMs) {
    const payload = await fetch(`${mockBaseUrl}/__assistant-ui-smoke/state`).then((response) => response.json());
    latest = payload.data ?? payload;
    if (predicate(latest)) {
      return latest;
    }
    await sleep(250);
  }
  throw new Error(`Timed out waiting for audit condition: ${label}. Latest audit: ${JSON.stringify(latest)}`);
}

async function createTarget(url) {
  const encoded = encodeURIComponent(url);
  let response = await fetch(`${cdpBaseUrl}/json/new?${encoded}`, { method: "PUT" });
  if (!response.ok) {
    response = await fetch(`${cdpBaseUrl}/json/new?${encoded}`);
  }
  if (!response.ok) {
    throw new Error(`Failed to create browser target: HTTP ${response.status}`);
  }
  return response.json();
}

async function waitForPageLoad(page, timeoutMs = 30000) {
  await page.waitForEvent("Page.loadEventFired", timeoutMs).catch(() => undefined);
}

async function waitForDom(page, expression, timeoutMs = 15000) {
  await waitForEval(page, `Boolean(${expression})`, timeoutMs);
}

async function waitForText(page, text, timeoutMs = 15000) {
  const quoted = JSON.stringify(text);
  try {
    await waitForEval(page, `document.body && document.body.innerText.includes(${quoted})`, timeoutMs);
  } catch (error) {
    const visibleText = await textContent(page).catch(() => "");
    const tail = visibleText.slice(-4000);
    throw new Error(`Timed out waiting for text ${quoted}. Visible text tail:\n${tail}`, { cause: error });
  }
}

async function waitForAssistantReady(page, timeoutMs = 15000) {
  await waitForEval(page, `
    (() => {
      const root = document.querySelector('[data-testid="studio-assistant-prompt"]');
      const prompt = root?.matches("input,textarea") ? root : root?.querySelector("input,textarea");
      const send = document.querySelector('[data-testid="studio-assistant-send"]');
      return Boolean(prompt && send && !prompt.disabled && !send.classList.contains("is-loading"));
    })()
  `, timeoutMs);
}

async function waitForEval(page, expression, timeoutMs) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    const result = await evaluate(page, expression).catch(() => undefined);
    if (result) {
      return;
    }
    await sleep(250);
  }
  throw new Error(`Timed out waiting for expression: ${expression}`);
}

async function click(page, selector) {
  const ok = await evaluate(page, `
    (() => {
      const element = document.querySelector(${JSON.stringify(selector)});
      if (!element) return false;
      element.scrollIntoView({ block: "center", inline: "center" });
      element.click();
      return true;
    })()
  `);
  assert(ok, `Unable to click ${selector}`);
}

async function clickByText(page, selector, text) {
  const ok = await evaluate(page, `
    (() => {
      const expected = ${JSON.stringify(text)};
      const elements = Array.from(document.querySelectorAll(${JSON.stringify(selector)}));
      const element = elements.find((item) => item.innerText && item.innerText.includes(expected) && !item.disabled);
      if (!element) return false;
      element.scrollIntoView({ block: "center", inline: "center" });
      element.click();
      return true;
    })()
  `);
  assert(ok, `Unable to click ${selector} containing ${text}`);
}

async function ensureProcessPanelExpanded(page) {
  const ok = await evaluate(page, `
    (() => {
      const processPanel = document.querySelector('[data-testid="studio-assistant-process"]');
      if (!processPanel) return false;
      if (processPanel.classList.contains("assistant-process--expanded")) return true;
      const summary = processPanel.querySelector(".assistant-process__summary");
      if (!summary) return false;
      summary.click();
      return true;
    })()
  `);
  assert(ok, "Unable to expand assistant process panel");
}

async function setField(page, selector, value) {
  const ok = await evaluate(page, `
    (() => {
      const root = document.querySelector(${JSON.stringify(selector)});
      if (!root) return false;
      const field = root.matches("input,textarea") ? root : root.querySelector("input,textarea");
      if (!field) return false;
      const proto = field.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      const descriptor = Object.getOwnPropertyDescriptor(proto, "value");
      descriptor.set.call(field, ${JSON.stringify(value)});
      field.dispatchEvent(new Event("input", { bubbles: true }));
      field.dispatchEvent(new Event("change", { bubbles: true }));
      field.focus();
      return true;
    })()
  `);
  assert(ok, `Unable to set field ${selector}`);
}

async function textContent(page) {
  return evaluate(page, `document.body ? document.body.innerText : ""`);
}

async function evaluate(page, expression) {
  const result = await page.send("Runtime.evaluate", {
    expression,
    awaitPromise: true,
    returnByValue: true,
  });
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.text || result.exceptionDetails.exception?.description || "Runtime.evaluate failed");
  }
  return result.result?.value;
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function isIgnorableBrowserError(message) {
  return /Failed to load resource: the server responded with a status of 404 \(Not Found\)/i.test(String(message || ""));
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

class CdpSession {
  static connect(url) {
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(url);
      const session = new CdpSession(socket);
      socket.addEventListener("open", () => resolve(session), { once: true });
      socket.addEventListener("error", reject, { once: true });
    });
  }

  constructor(socket) {
    this.socket = socket;
    this.nextId = 1;
    this.pending = new Map();
    this.listeners = new Map();
    socket.addEventListener("message", (event) => this.handleMessage(event));
    socket.addEventListener("close", () => {
      for (const { reject } of this.pending.values()) {
        reject(new Error("CDP socket closed"));
      }
      this.pending.clear();
    });
  }

  send(method, params = {}) {
    const id = this.nextId++;
    const payload = JSON.stringify({ id, method, params });
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.socket.send(payload);
    });
  }

  on(method, listener) {
    if (!this.listeners.has(method)) {
      this.listeners.set(method, []);
    }
    this.listeners.get(method).push(listener);
  }

  waitForEvent(method, timeoutMs = 30000) {
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error(`Timed out waiting for CDP event ${method}`)), timeoutMs);
      const listener = (params) => {
        clearTimeout(timeout);
        resolve(params);
      };
      this.on(method, listener);
    });
  }

  close() {
    this.socket.close();
  }

  handleMessage(event) {
    const message = JSON.parse(String(event.data));
    if (message.id) {
      const pending = this.pending.get(message.id);
      if (!pending) {
        return;
      }
      this.pending.delete(message.id);
      if (message.error) {
        pending.reject(new Error(message.error.message || "CDP command failed"));
      } else {
        pending.resolve(message.result || {});
      }
      return;
    }
    const listeners = this.listeners.get(message.method) || [];
    for (const listener of listeners) {
      listener(message.params || {});
    }
  }
}
