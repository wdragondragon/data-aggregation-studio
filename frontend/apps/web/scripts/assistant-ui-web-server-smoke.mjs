import http from "node:http";
import net from "node:net";
import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const workspaceRoot = fileURLToPath(new URL("..", import.meta.url));
const studioRoot = path.resolve(workspaceRoot, "../../..");
const repoRoot = path.resolve(studioRoot, "..");
const targetDir = path.resolve(workspaceRoot, "target", "assistant-web-server-smoke");
const backendJar = path.resolve(studioRoot, "backend/studio-server/target/studio-server-0.1.0-SNAPSHOT-exec.jar");
const sqliteSchema = path.resolve(studioRoot, "backend/studio-desktop-runtime/src/main/resources/schema-sqlite.sql");
const realAggregationHome = path.resolve(repoRoot, "package_all/aggregation");

const appPort = Number(process.env.STUDIO_ASSISTANT_WEB_SMOKE_APP_PORT || 5177);
const backendPort = Number(process.env.STUDIO_ASSISTANT_WEB_SMOKE_BACKEND_PORT || 18181);
const llmPort = Number(process.env.STUDIO_ASSISTANT_WEB_SMOKE_LLM_PORT || 49155);
const cdpPort = Number(process.env.STUDIO_ASSISTANT_WEB_SMOKE_CDP_PORT || 9226);
const headed = process.env.STUDIO_ASSISTANT_UI_HEADED === "true";
const keepAlive = process.env.STUDIO_ASSISTANT_WEB_SMOKE_KEEP_ALIVE === "true";
const appBaseUrl = `http://127.0.0.1:${appPort}/dfs/data-aggregation-studio/`;
const backendBaseUrl = `http://127.0.0.1:${backendPort}`;
const llmBaseUrl = `http://127.0.0.1:${llmPort}/v1`;
const cdpBaseUrl = `http://127.0.0.1:${cdpPort}`;
const datasourceId = 880011;
const datasourceName = "assistant_web_smoke_ds";
const pagedDatasourceNames = [
  datasourceName,
  "assistant_web_smoke_ds_page_2",
  "assistant_web_smoke_ds_page_3",
];
const datasourceListPromptText = "把项目里所有数据源都列出来，别只给当前页。";
const datasourceListPageSize = 2;
const datasourceTypeCode = "assistant-web-smoke";
const discoveredTables = ["orders_all", "customers_all", "payments_all"];
const noProtocolPromptText = "触发缺失协议负例：模型只说准备查询，不输出协议。";
const searchSelectPromptText = "在当前页面上下文里搜索这个数据源，并把它设为助手记忆里的默认数据源，不要调用业务接口。";
const observePromptText = "先观察一下当前页面状态，告诉我在哪个功能页和当前选中的对象，不要调用业务接口。";
const contextPromptText = "先读取当前业务上下文，告诉我页面选中的对象是什么，不要调用业务接口。";
const discoveryPromptCases = [
  {
    id: "current-datasource-all-real-tables",
    text: "把当前这个数据源的所有真实表都列出来。",
  },
  {
    id: "selected-datasource-table-discovery",
    text: "我选中的这个数据源，表发现里到底有哪些物理表？全给我。",
  },
  {
    id: "avoid-models-real-table-list",
    text: "别看模型，直接查这个数据源里真实存在的表清单。",
  },
];

const children = [];
const servers = [];
const stoppingPids = new Set();
let runtimeDir = "";
let userDataDir = "";
let appDb = "";
let aggregationHome = "";
let resolvedJdkHome = "";
let sqliteJdbcJar = "";

const llmAudit = {
  requests: [],
};

main().catch((error) => {
  console.error(error instanceof Error ? error.stack || error.message : error);
  process.exitCode = 1;
}).finally(async () => {
  for (const child of children.reverse()) {
    stopProcess(child);
  }
  for (const server of servers.reverse()) {
    await closeServer(server);
  }
  if (userDataDir) {
    await fs.rm(userDataDir, { recursive: true, force: true }).catch(() => undefined);
  }
});

async function main() {
  assert(existsSync(backendJar), `Missing studio-server exec jar: ${backendJar}. Build backend/studio-server first.`);
  assert(existsSync(sqliteSchema), `Missing SQLite schema: ${sqliteSchema}`);
  assert(existsSync(realAggregationHome), `Missing aggregation home: ${realAggregationHome}`);
  await fs.mkdir(targetDir, { recursive: true });
  runtimeDir = await fs.mkdtemp(path.join(os.tmpdir(), "studio-assistant-web-server-smoke-"));
  appDb = path.join(runtimeDir, "studio-web-smoke.db");
  aggregationHome = path.join(runtimeDir, "aggregation");
  await prepareAggregationHome(aggregationHome);

  await initializeStudioDatabase();
  const projectId = await seedDatasource();

  const viteCli = path.resolve(workspaceRoot, "../../node_modules/vite/bin/vite.js");
  const vite = startProcess(process.execPath, [viteCli, "--host", "127.0.0.1", "--port", String(appPort)], {
    cwd: workspaceRoot,
    env: {
      ...process.env,
      VITE_API_BASE_URL: "/api/v1",
      STUDIO_PROXY_TARGET: backendBaseUrl,
    },
  }, "vite");
  children.push(vite);
  await waitForHttp(appBaseUrl, "Vite app", 60000);

  userDataDir = await fs.mkdtemp(path.join(os.tmpdir(), "studio-assistant-web-server-browser-"));
  const browser = startProcess(resolveBrowserPath(), [
    `--remote-debugging-port=${cdpPort}`,
    `--user-data-dir=${userDataDir}`,
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-background-networking",
    "--disable-gpu",
    headed ? "" : "--headless=new",
  ].filter(Boolean), { cwd: workspaceRoot, env: process.env }, "browser");
  children.push(browser);
  await waitForHttp(`${cdpBaseUrl}/json/version`, "Chrome DevTools", 60000);

  const target = await createTarget("about:blank");
  const page = await CdpSession.connect(target.webSocketDebuggerUrl);
  try {
    await page.send("Page.enable");
    await page.send("Runtime.enable");
    await page.send("Log.enable");
    await page.send("Network.enable");
    const network = installNetworkRecorder(page);
    const browserErrors = installBrowserErrorRecorder(page);

    const disabledBackend = startStudioServer("studio-server-disabled", {
      STUDIO_ASSISTANT_LLM_ENABLED: "false",
      STUDIO_ASSISTANT_LLM_BASE_URL: "",
      STUDIO_ASSISTANT_LLM_API_KEY: "",
      STUDIO_ASSISTANT_LLM_MODEL: "",
    });
    children.push(disabledBackend);
    await waitForBackendLogin(120000);
    const disabledBackendConfig = await fetchAssistantConfigWithFreshLogin();
    assert(disabledBackendConfig.status === 200, `Disabled assistant config should be readable over real backend auth: ${JSON.stringify(disabledBackendConfig)}`);
    assert(disabledBackendConfig.enabled === false, `Disabled assistant config should return enabled=false: ${JSON.stringify(disabledBackendConfig)}`);
    await page.send("Page.navigate", { url: appBaseUrl });
    await waitForPageLoad(page);
    await login(page);
    const disabledAssistantProbe = {
      backendConfig: disabledBackendConfig,
      ui: await waitForAssistantHiddenInUi(page, 60000),
    };
    stopProcess(disabledBackend);
    await waitForTcpPortClosed(backendPort, 60000);
    removeChild(disabledBackend);

    const llmServer = await startMockLlmServer(llmPort);
    servers.push(llmServer);
    const backend = startStudioServer("studio-server");
    children.push(backend);
    await waitForBackendLogin(120000);
    await page.send("Page.navigate", { url: appBaseUrl });
    await waitForPageLoad(page);
    await login(page);
    await waitForAssistantEnabledConfig(page, 60000);
    await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-open"]')`, 60000);

    await page.send("Page.navigate", { url: `${appBaseUrl}datasources` });
    await waitForPageLoad(page);
    await waitForText(page, datasourceName, 60000);
    await clickByText(page, "tr", datasourceName);

    await click(page, `[data-testid="studio-assistant-open"]`);
    await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-root"]')`, 30000);
    await click(page, `[data-testid="studio-assistant-mode-goal"]`);
    await click(page, `[data-testid="studio-assistant-language-zh"]`);
    await waitForText(page, "目标模式", 10000);

    const noProtocolRun = await runNoProtocolPromptCase(page, network);
    const searchSelectRun = await runSearchSelectPromptCase(page, network);
    const observeRun = await runObservePromptCase(page, network);

    const contextNetworkSince = network.entries.length;
    const contextLlmSince = llmAudit.requests.length;
    await setField(page, `[data-testid="studio-assistant-prompt"]`, contextPromptText);
    await waitForPromptReady(page, contextPromptText, 10000);
    await click(page, `[data-testid="studio-assistant-send"]`);
    await waitForText(page, "当前业务上下文读取结果", 60000);
    await waitForAssistantReady(page, 60000);
    const contextCalls = network.entries.slice(contextNetworkSince);
    assert(!contextCalls.some((entry) => entry.url.includes("/assistant/tools/execute")), "assistant.context.read should not call the backend tool gateway.");
    const contextLlmRequests = llmAudit.requests.slice(contextLlmSince);
    assert(contextLlmRequests.length >= 2, `Expected context read to call LLM twice, got ${contextLlmRequests.length}.`);
    assert(contextLlmRequests[0].prompt.includes(contextPromptText), "Context read prompt did not reach the LLM.");
    assert(contextLlmRequests.some((item) => item.hasContextToolResults), "LLM was not called again with assistant.context.read toolResults.");

    const discoveryRuns = [];
    for (const promptCase of discoveryPromptCases) {
      discoveryRuns.push(await runDiscoveryPromptCase(page, network, promptCase, {
        expectNoPriorToolResults: discoveryRuns.length === 0,
      }));
    }
    const primaryDiscoveryRun = discoveryRuns[0];
    const datasourcePaginationRun = await runDatasourcePaginationPromptCase(page, network);

    const report = {
      generatedAt: new Date().toISOString(),
      appBaseUrl,
      backendBaseUrl,
      projectId,
      datasourceId,
      datasourceName,
      prompt: primaryDiscoveryRun.prompt,
      disabledAssistant: disabledAssistantProbe,
      noProtocolRecovery: noProtocolRun,
      contextSearchSelect: searchSelectRun,
      contextObserve: observeRun,
      contextRead: {
        prompt: contextPromptText,
        llmRequests: contextLlmRequests.map((item) => ({
          prompt: item.prompt,
          activeObjectId: item.activeObjectId,
          hasContextToolResults: item.hasContextToolResults,
          branch: item.branch,
        })),
        backendToolGatewayCalls: contextCalls.filter((entry) => entry.url.includes("/assistant/tools/execute")).length,
      },
      llmRequests: primaryDiscoveryRun.llmRequests,
      toolCall: primaryDiscoveryRun.toolCall,
      discoveryRuns,
      fuzzyPromptCoverage: {
        expectedCount: discoveryPromptCases.length,
        passedCount: discoveryRuns.length,
        prompts: discoveryRuns.map((item) => item.prompt),
      },
      datasourcePaginationRun,
      tables: discoveredTables,
    };
    const reportPath = path.join(targetDir, "assistant-web-server-smoke-report.json");
    await fs.writeFile(reportPath, JSON.stringify(report, null, 2));

    const screenshot = await page.send("Page.captureScreenshot", { format: "png", captureBeyondViewport: true });
    const screenshotPath = path.join(targetDir, "assistant-web-server-smoke.png");
    await fs.writeFile(screenshotPath, Buffer.from(screenshot.data, "base64"));

    const meaningfulErrors = browserErrors.filter((message) => !isIgnorableBrowserError(message));
    if (meaningfulErrors.length) {
      throw new Error(`Browser console/runtime errors:\n${meaningfulErrors.join("\n")}`);
    }
    console.log(`Assistant Web server smoke passed. Report: ${reportPath}`);
    console.log(`Screenshot: ${screenshotPath}`);
    if (keepAlive) {
      console.log(`Assistant Web server smoke keep-alive is active. Open ${appBaseUrl} in an in-app browser and sign in with admin/admin123. Press Ctrl+C here to stop the temporary Web environment.`);
      await waitForShutdownSignal();
    }
  } finally {
    page.close();
  }
}

async function runNoProtocolPromptCase(page, network) {
  const networkSince = network.entries.length;
  const llmSince = llmAudit.requests.length;
  await setField(page, `[data-testid="studio-assistant-prompt"]`, noProtocolPromptText);
  await waitForPromptReady(page, noProtocolPromptText, 10000);
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "我会先查询数据源列表", 60000);
  await waitForAssistantReady(page, 60000);

  const relevantCalls = network.entries.slice(networkSince);
  assert(!relevantCalls.some((entry) => entry.url.includes("/assistant/tools/execute")), "Plain text without studio-assistant-protocol must not trigger backend tool gateway recovery.");

  const llmRequests = llmAudit.requests.slice(llmSince);
  assert(llmRequests.length === 1, `Plain text without protocol should not trigger local regex self-check, got ${llmRequests.length} LLM requests.`);
  assert(llmRequests[0].prompt.includes(noProtocolPromptText), "No-protocol prompt did not reach the first LLM request.");
  assert(llmRequests[0].branch === "plain-no-protocol-no-recovery", `Unexpected no-protocol mock branch: ${llmRequests[0].branch}`);

  const visibleText = await textContent(page);
  assert(!visibleText.includes("自检缺失的接口调用"), "Frontend should not show local missing-action self-check for plain text.");
  assert(!visibleText.includes("studio-assistant-protocol"), "No-protocol negative case should not leak protocol text.");

  return {
    prompt: noProtocolPromptText,
    llmRequests: llmRequests.map((item) => ({
      prompt: item.prompt,
      activeObjectId: item.activeObjectId,
      branch: item.branch,
    })),
    backendToolGatewayCalls: relevantCalls.filter((entry) => entry.url.includes("/assistant/tools/execute")).length,
  };
}

async function runSearchSelectPromptCase(page, network) {
  const networkSince = network.entries.length;
  const llmSince = llmAudit.requests.length;
  await setField(page, `[data-testid="studio-assistant-prompt"]`, searchSelectPromptText);
  await waitForPromptReady(page, searchSelectPromptText, 10000);
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "页面对象选择结果", 60000);
  await waitForAssistantReady(page, 60000);

  const relevantCalls = network.entries.slice(networkSince);
  assert(!relevantCalls.some((entry) => entry.url.includes("/assistant/tools/execute")), "assistant.context.search + assistant.memory.select should not call the backend tool gateway.");

  const llmRequests = llmAudit.requests.slice(llmSince);
  assert(llmRequests.length >= 3, `Expected search/select flow to call LLM for search, select, and final answer; got ${llmRequests.length}.`);
  assert(llmRequests[0].prompt.includes(searchSelectPromptText), "Search/select prompt did not reach the first LLM request.");
  assert(llmRequests[0].activeObjectId === String(datasourceId), `Search/select should receive active datasource id=${datasourceId}.`);
  assert(llmRequests.some((item) => item.hasSearchToolResults), "LLM was not called again with assistant.context.search toolResults.");
  assert(llmRequests.some((item) => item.hasMemorySelectToolResults), "LLM was not called again with assistant.memory.select toolResults.");

  const visibleText = await textContent(page);
  assert(visibleText.includes("页面对象选择结果"), "Search/select final answer should be visible.");
  assert(visibleText.includes(datasourceName), "Search/select final answer should mention selected datasource.");
  assert(!visibleText.includes("studio-assistant-protocol"), "Protocol block leaked into visible search/select answer.");

  return {
    prompt: searchSelectPromptText,
    llmRequests: llmRequests.map((item) => ({
      prompt: item.prompt,
      activeObjectId: item.activeObjectId,
      hasSearchToolResults: item.hasSearchToolResults,
      hasMemorySelectToolResults: item.hasMemorySelectToolResults,
      branch: item.branch,
    })),
    backendToolGatewayCalls: relevantCalls.filter((entry) => entry.url.includes("/assistant/tools/execute")).length,
  };
}

async function runObservePromptCase(page, network) {
  const networkSince = network.entries.length;
  const llmSince = llmAudit.requests.length;
  await setField(page, `[data-testid="studio-assistant-prompt"]`, observePromptText);
  await waitForPromptReady(page, observePromptText, 10000);
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "当前页面观察结果", 60000);
  await waitForAssistantReady(page, 60000);

  const relevantCalls = network.entries.slice(networkSince);
  assert(!relevantCalls.some((entry) => entry.url.includes("/assistant/tools/execute")), "assistant.context.observe should not call the backend tool gateway.");

  const llmRequests = llmAudit.requests.slice(llmSince);
  assert(llmRequests.length >= 2, `Expected context observe to call LLM twice, got ${llmRequests.length}.`);
  assert(llmRequests[0].prompt.includes(observePromptText), "Context observe prompt did not reach the first LLM request.");
  assert(llmRequests[0].activeObjectId === String(datasourceId), `Context observe should receive active datasource id=${datasourceId}.`);
  assert(!llmRequests[0].hasToolResults, "Initial observe prompt should not start with backend toolResults.");
  assert(llmRequests.some((item) => item.hasObserveToolResults), "LLM was not called again with assistant.context.observe toolResults.");

  const visibleText = await textContent(page);
  assert(visibleText.includes("数据源中心"), "Context observe final answer should mention current datasource page.");
  assert(visibleText.includes(datasourceName), "Context observe final answer should mention current selected datasource.");
  assert(!visibleText.includes("studio-assistant-protocol"), "Protocol block leaked into visible context observe answer.");

  return {
    prompt: observePromptText,
    llmRequests: llmRequests.map((item) => ({
      prompt: item.prompt,
      activeObjectId: item.activeObjectId,
      hasObserveToolResults: item.hasObserveToolResults,
      branch: item.branch,
    })),
    backendToolGatewayCalls: relevantCalls.filter((entry) => entry.url.includes("/assistant/tools/execute")).length,
  };
}

async function runDiscoveryPromptCase(page, network, promptCase, options = {}) {
  const networkSince = network.entries.length;
  const llmSince = llmAudit.requests.length;
  await setField(page, `[data-testid="studio-assistant-prompt"]`, promptCase.text);
  await waitForPromptReady(page, promptCase.text, 10000);
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForLlmRequestCount(llmSince + 1, 30000, `Discover prompt ${promptCase.id} did not reach the mock LLM.`);

  const toolEntry = await network.waitFor((entry) => {
    const request = entry.requestBodyJson || {};
    const response = entry.responseBodyJson || {};
    const data = response.data || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === "studio.feature.action"
      && request.params?.path === "/datasources"
      && request.params?.action === "discover"
      && String(request.params?.id) === String(datasourceId)
      && !Object.prototype.hasOwnProperty.call(request.params || {}, "pageNo")
      && !Object.prototype.hasOwnProperty.call(request.params || {}, "pageSize")
      && data.executedBy === "backend"
      && data.action === "discover"
      && Array.isArray(data.data?.models)
      && discoveredTables.every((table) => data.data.models.some((model) => model.name === table || model.physicalLocator === table));
  }, 60000, networkSince);

  for (const table of discoveredTables) {
    await waitForText(page, table, 60000);
  }
  await waitForAssistantReady(page, 60000);

  const visibleText = await textContent(page);
  assert(!visibleText.includes("studio-assistant-protocol"), `Protocol block leaked into visible chat text for ${promptCase.id}.`);
  assert(!visibleText.includes("pageNo"), `Visible answer should not expose pagination params for ${promptCase.id}.`);

  const relevantCalls = network.entries.slice(networkSince);
  assert(!relevantCalls.some((entry) => /\/api\/v1\/models(?:[/?]|$)/.test(entry.url)), `Assistant prompt ${promptCase.id} incorrectly called /models.`);
  assert(!relevantCalls.some((entry) => /\/api\/v1\/datasources\/page(?:[/?]|$)/.test(entry.url)), `Assistant prompt ${promptCase.id} re-read datasource page instead of using pageContext.activeObject.`);

  const llmRequests = llmAudit.requests.slice(llmSince);
  assert(llmRequests.length >= 2, `Expected at least two LLM requests for ${promptCase.id}, got ${llmRequests.length}.`);
  assert(llmRequests[0].activeObjectId === String(datasourceId), `First LLM request for ${promptCase.id} did not receive pageContext.activeObject.id=${datasourceId}.`);
  assert(llmRequests[0].prompt.includes(promptCase.text), `First LLM request did not include prompt ${promptCase.id}.`);
  if (options.expectNoPriorToolResults) {
    assert(!llmRequests[0].hasToolResults, `Initial discovery prompt ${promptCase.id} should not start with previous backend toolResults.`);
    assert(!llmRequests[0].hasContextToolResults, `Initial discovery prompt ${promptCase.id} should not carry previous assistant.context.read toolResults.`);
  }
  assert(llmRequests.some((item) => item.hasToolResults), `LLM was not called again with toolResults for ${promptCase.id}.`);

  const toolCall = summarizeToolEntry(toolEntry);
  assert(!Object.prototype.hasOwnProperty.call(toolCall.requestParams || {}, "pageNo"), `Tool call ${promptCase.id} should not contain pageNo.`);
  assert(!Object.prototype.hasOwnProperty.call(toolCall.requestParams || {}, "pageSize"), `Tool call ${promptCase.id} should not contain pageSize.`);

  return {
    id: promptCase.id,
    prompt: promptCase.text,
    initialRequestHadPriorToolResults: llmRequests[0].hasToolResults,
    initialRequestHadPriorContextToolResults: llmRequests[0].hasContextToolResults,
    llmRequests: llmRequests.map((item) => ({
      prompt: item.prompt,
      activeObjectId: item.activeObjectId,
      hasToolResults: item.hasToolResults,
      hasContextToolResults: item.hasContextToolResults,
      branch: item.branch,
    })),
    toolCall,
  };
}

async function runDatasourcePaginationPromptCase(page, network) {
  const networkSince = network.entries.length;
  const llmSince = llmAudit.requests.length;
  await setField(page, `[data-testid="studio-assistant-prompt"]`, datasourceListPromptText);
  await waitForPromptReady(page, datasourceListPromptText, 10000);
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForLlmRequestCount(llmSince + 1, 30000, "Datasource pagination prompt did not reach the mock LLM.");

  const firstPageEntry = await network.waitFor((entry) => {
    const request = entry.requestBodyJson || {};
    const response = entry.responseBodyJson || {};
    const data = response.data || {};
    const page = data.data || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === "studio.feature.list"
      && request.params?.path === "/datasources"
      && Number(request.params?.pageNo) === 1
      && Number(request.params?.pageSize) === datasourceListPageSize
      && data.executedBy === "backend"
      && Number(page.pageNo) === 1
      && Number(page.pageSize) === datasourceListPageSize
      && Number(page.total) >= pagedDatasourceNames.length
      && Array.isArray(page.items)
      && page.items.length === datasourceListPageSize;
  }, 60000, networkSince);

  const secondPageEntry = await network.waitFor((entry) => {
    const request = entry.requestBodyJson || {};
    const response = entry.responseBodyJson || {};
    const data = response.data || {};
    const page = data.data || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === "studio.feature.list"
      && request.params?.path === "/datasources"
      && Number(request.params?.pageNo) === 2
      && Number(request.params?.pageSize) === datasourceListPageSize
      && data.executedBy === "backend"
      && Number(page.pageNo) === 2
      && Number(page.pageSize) === datasourceListPageSize
      && Number(page.total) >= pagedDatasourceNames.length
      && Array.isArray(page.items)
      && page.items.some((item) => item.name === pagedDatasourceNames[2]);
  }, 60000, networkSince);

  for (const name of pagedDatasourceNames) {
    await waitForText(page, name, 60000);
  }
  await waitForAssistantReady(page, 60000);

  const visibleText = await textContent(page);
  assert(!visibleText.includes("studio-assistant-protocol"), "Protocol block leaked into visible datasource pagination answer.");

  const relevantCalls = network.entries.slice(networkSince);
  assert(!relevantCalls.some((entry) => /\/api\/v1\/datasources\/page(?:[/?]|$)/.test(entry.url)), "Datasource pagination prompt bypassed assistant gateway and called datasource page API directly.");

  const llmRequests = llmAudit.requests.slice(llmSince);
  assert(llmRequests.length >= 3, `Expected datasource pagination to call LLM for first page, second page, and final answer; got ${llmRequests.length}.`);
  assert(llmRequests[0].prompt.includes(datasourceListPromptText), "Datasource pagination prompt did not reach the first LLM request.");
  assert(llmRequests.some((item) => item.hasDatasourceListPage1ToolResults), "LLM was not called again with datasource page 1 toolResults.");
  assert(llmRequests.some((item) => item.hasDatasourceListPage2ToolResults), "LLM was not called again with datasource page 2 toolResults.");
  assert(llmRequests.some((item) => item.hasDatasourceListPage1Pagination), "Datasource page 1 toolResults did not expose computed pagination.hasMore/nextPage to the LLM.");
  assert(llmRequests.some((item) => item.hasDatasourceListPage2Pagination), "Datasource page 2 toolResults did not expose computed pagination completion to the LLM.");

  return {
    prompt: datasourceListPromptText,
    llmRequests: llmRequests.map((item) => ({
      prompt: item.prompt,
      activeObjectId: item.activeObjectId,
      hasDatasourceListPage1ToolResults: item.hasDatasourceListPage1ToolResults,
      hasDatasourceListPage2ToolResults: item.hasDatasourceListPage2ToolResults,
      hasDatasourceListPage1Pagination: item.hasDatasourceListPage1Pagination,
      hasDatasourceListPage2Pagination: item.hasDatasourceListPage2Pagination,
      branch: item.branch,
    })),
    toolCalls: [
      summarizeDatasourceListToolEntry(firstPageEntry),
      summarizeDatasourceListToolEntry(secondPageEntry),
    ],
  };
}

async function prepareAggregationHome(targetHome) {
  await fs.mkdir(path.join(targetHome, "conf"), { recursive: true });
  await fs.writeFile(path.join(targetHome, "conf", "core.json"), "{\n  \"core\": { \"plugin\": { \"reporter\": [] } }\n}\n");
  const pluginDir = path.join(targetHome, "plugin", "source", datasourceTypeCode);
  const sourceDir = path.join(pluginDir, "src", "com", "jdragon", "studio", "assistant", "smoke");
  const classesDir = path.join(pluginDir, "classes");
  await fs.mkdir(sourceDir, { recursive: true });
  await fs.mkdir(classesDir, { recursive: true });
  const javaFile = path.join(sourceDir, "AssistantWebSmokeSourcePlugin.java");
  await fs.writeFile(javaFile, assistantWebSmokeSourcePluginJava());
  const javac = resolveJdkTool("javac");
  const jar = resolveJdkTool("jar");
  const compile = spawnSync(javac, [
    "-encoding",
    "UTF-8",
    "-cp",
    path.join(realAggregationHome, "lib", "*"),
    "-d",
    classesDir,
    javaFile,
  ], { cwd: pluginDir, encoding: "utf8", windowsHide: true });
  assert(compile.status === 0, `Failed to compile smoke source plugin:\n${compile.stderr || compile.stdout}`);
  const jarPath = path.join(pluginDir, "assistant-web-smoke-source-plugin.jar");
  const jarResult = spawnSync(jar, ["cf", jarPath, "-C", classesDir, "."], {
    cwd: pluginDir,
    encoding: "utf8",
    windowsHide: true,
  });
  assert(jarResult.status === 0, `Failed to jar smoke source plugin:\n${jarResult.stderr || jarResult.stdout}`);
  await fs.writeFile(path.join(pluginDir, "plugin.json"), JSON.stringify({
    name: datasourceTypeCode,
    class: "com.jdragon.studio.assistant.smoke.AssistantWebSmokeSourcePlugin",
    description: "Assistant Web smoke source plugin",
    developer: "studio",
  }, null, 2));
}

function assistantWebSmokeSourcePluginJava() {
  return `
package com.jdragon.studio.assistant.smoke;

import com.jdragon.aggregation.commons.pagination.Table;
import com.jdragon.aggregation.datasource.AbstractDataSourcePlugin;
import com.jdragon.aggregation.datasource.BaseDataSourceDTO;
import com.jdragon.aggregation.datasource.ColumnInfo;
import com.jdragon.aggregation.datasource.InsertDataDTO;
import com.jdragon.aggregation.datasource.TableInfo;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AssistantWebSmokeSourcePlugin extends AbstractDataSourcePlugin {
    @Override
    public Connection getConnection(BaseDataSourceDTO datasource) {
        return null;
    }

    @Override
    public Table<Map<String, Object>> executeQuerySql(BaseDataSourceDTO datasource, String sql, boolean limit) {
        throw new UnsupportedOperationException("assistant web smoke plugin is discovery-only");
    }

    @Override
    public void scanQuery(BaseDataSourceDTO datasource, String sql, boolean limit, java.util.function.Consumer<Map<String, Object>> consumer) {
        throw new UnsupportedOperationException("assistant web smoke plugin is discovery-only");
    }

    @Override
    public void executeUpdate(BaseDataSourceDTO datasource, String sql) {
        throw new UnsupportedOperationException("assistant web smoke plugin is discovery-only");
    }

    @Override
    public void executeBatch(BaseDataSourceDTO datasource, List<String> sqlList) {
        throw new UnsupportedOperationException("assistant web smoke plugin is discovery-only");
    }

    @Override
    public Table<Map<String, Object>> dataModelPreview(BaseDataSourceDTO datasource, String tableName, String limit) {
        return null;
    }

    @Override
    public void insertData(InsertDataDTO insertDataDTO) {
        throw new UnsupportedOperationException("assistant web smoke plugin is discovery-only");
    }

    @Override
    public List<TableInfo> getTableInfos(BaseDataSourceDTO datasource, String keyword) {
        List<TableInfo> result = new ArrayList<TableInfo>();
        for (String tableName : getTableNames(datasource, keyword)) {
            TableInfo info = new TableInfo();
            info.setTableName(tableName);
            info.setTableType("TABLE");
            info.setRemarks("assistant web smoke physical table");
            result.add(info);
        }
        return result;
    }

    @Override
    public List<String> getTableNames(BaseDataSourceDTO datasource, String keyword) {
        String tables = "";
        if (datasource != null && datasource.getExtraParams() != null) {
            tables = datasource.getExtraParams().get("tables");
        }
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<String>();
        if (tables == null || tables.trim().isEmpty()) {
            return result;
        }
        for (String part : tables.split(",")) {
            String tableName = part.trim();
            if (tableName.isEmpty()) {
                continue;
            }
            if (normalizedKeyword.isEmpty() || tableName.toLowerCase(Locale.ROOT).contains(normalizedKeyword)) {
                result.add(tableName);
            }
        }
        return result;
    }

    @Override
    public List<ColumnInfo> getColumns(BaseDataSourceDTO datasource, String tableName) {
        return Collections.emptyList();
    }

    @Override
    public String getTableSize(BaseDataSourceDTO datasource, String tableName) {
        return "0";
    }

    @Override
    public Long getTableCount(BaseDataSourceDTO datasource, String tableName) {
        return 0L;
    }
}
`;
}

async function initializeStudioDatabase() {
  const init = startJavaProcess("studio-init", [
    "-Dloader.main=com.jdragon.studio.server.bootstrap.StudioDataInitializerApplication",
    "-cp",
    backendJar,
    "org.springframework.boot.loader.launch.PropertiesLauncher",
    "--studio.init.reset=true",
  ], backendEnv());
  const code = await waitForExit(init, 120000);
  assert(code === 0, `Studio data initialization failed with exit code ${code}.`);
}

function startStudioServer(label, envOverrides = {}) {
  return startJavaProcess(label, [
    "-Dloader.main=com.jdragon.studio.server.bootstrap.StudioServerApplication",
    "-cp",
    backendJar,
    "org.springframework.boot.loader.launch.PropertiesLauncher",
    `--server.port=${backendPort}`,
  ], backendEnv(envOverrides));
}

async function seedDatasource() {
  const projectRows = await sqliteQuery(appDb, "select cast(id as text) as id from studio_project order by id limit 1");
  assert(projectRows.length === 1, "Default project was not initialized.");
  const projectId = String(projectRows[0].id);
  const now = "2026-07-05 00:00:00";
  await sqliteExec(appDb, `
    insert into datasource_type_capability (
      id, tenant_id, deleted, created_at, updated_at, type_code, type_name, source_category,
      enabled, readable, writable, executable, sql_executable, source_plugin,
      reader_plugins_json, writer_plugins_json, sort_order, description
    ) values (?, 'default', 0, ?, ?, ?, ?, 'DATABASE', 1, 1, 0, 0, 0, ?, '[]', '[]', 5, ?)
  `, [
    "880001",
    now,
    now,
    datasourceTypeCode,
    "Assistant Web Smoke Source",
    datasourceTypeCode,
    "Discovery-only source plugin for Web assistant smoke test",
  ]);
  await sqliteExec(appDb, `
    insert into datasource_definition (
      id, tenant_id, project_id, deleted, created_at, updated_at, name, type_code,
      enabled, executable, connection_fingerprint, connection_status,
      technical_metadata, business_metadata
    ) values (?, 'default', ?, 0, ?, ?, ?, ?, 1, 0, ?, 'AVAILABLE', ?, '{}')
  `, [
    String(datasourceId),
    projectId,
    now,
    now,
    datasourceName,
    datasourceTypeCode,
    `assistant-web-smoke-${datasourceId}`,
    JSON.stringify({ extraParams: { tables: discoveredTables.join(",") } }),
  ]);
  for (let index = 1; index < pagedDatasourceNames.length; index += 1) {
    const id = datasourceId + index;
    await sqliteExec(appDb, `
      insert into datasource_definition (
        id, tenant_id, project_id, deleted, created_at, updated_at, name, type_code,
        enabled, executable, connection_fingerprint, connection_status,
        technical_metadata, business_metadata
      ) values (?, 'default', ?, 0, ?, ?, ?, ?, 1, 0, ?, 'AVAILABLE', ?, '{}')
    `, [
      String(id),
      projectId,
      now,
      now,
      pagedDatasourceNames[index],
      datasourceTypeCode,
      `assistant-web-smoke-${id}`,
      JSON.stringify({ extraParams: { tables: discoveredTables.join(",") } }),
    ]);
  }
  return projectId;
}

function backendEnv(overrides = {}) {
  return {
    ...process.env,
    JAVA_HOME: resolveJdkHome(),
    Path: `${path.join(resolveJdkHome(), "bin")};${process.env.Path || ""}`,
    APP_ACTIVE: "test",
    SPRING_DATASOURCE_URL: `jdbc:sqlite:${normalizeSqlitePath(appDb)}`,
    SPRING_DATASOURCE_DRIVER_CLASS_NAME: "org.sqlite.JDBC",
    SPRING_DATASOURCE_USERNAME: "",
    SPRING_DATASOURCE_PASSWORD: "",
    SPRING_SQL_INIT_MODE: "always",
    SPRING_SQL_INIT_SCHEMA_LOCATIONS: pathToFileUrl(sqliteSchema),
    SPRING_AUTOCONFIGURE_EXCLUDE: "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
    SPRING_CLOUD_NACOS_CONFIG_ENABLED: "false",
    SPRING_CLOUD_NACOS_DISCOVERY_ENABLED: "false",
    SPRING_QUARTZ_AUTO_STARTUP: "false",
    STUDIO_AGGREGATION_HOME: aggregationHome,
    STUDIO_SCAN_PLUGINS_ON_STARTUP: "false",
    STUDIO_ASSISTANT_LLM_ENABLED: "true",
    STUDIO_ASSISTANT_LLM_BASE_URL: llmBaseUrl,
    STUDIO_ASSISTANT_LLM_API_KEY: "test",
    STUDIO_ASSISTANT_LLM_MODEL: "assistant-web-smoke",
    STUDIO_ASSISTANT_LLM_TIMEOUT_SECONDS: "30",
    STUDIO_ASSISTANT_LLM_MAX_TOKENS: "1200",
    STUDIO_MVC_ASYNC_REQUEST_TIMEOUT: "120000",
    STUDIO_WEB_ASYNC_REQUEST_TIMEOUT: "120000",
    ...overrides,
  };
}

async function startMockLlmServer(port) {
  const server = http.createServer(async (request, response) => {
    if (request.method === "GET" && request.url === "/health") {
      response.writeHead(200, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ ok: true }));
      return;
    }
    if (request.method !== "POST" || request.url !== "/v1/chat/completions") {
      response.writeHead(404, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ error: "not found" }));
      return;
    }
    const body = await readJson(request);
    const summary = summarizeLlmRequest(body);
    llmAudit.requests.push(summary);
    const content = resolveMockLlmContent(summary);
    if (body.stream === true) {
      response.writeHead(200, {
        "Content-Type": "text/event-stream; charset=utf-8",
        "Cache-Control": "no-cache",
        Connection: "close",
      });
      writeLlmChunk(response, content);
      response.write("data: [DONE]\n\n");
      response.end();
      return;
    }
    response.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
    response.end(JSON.stringify({
      choices: [{ message: { role: "assistant", content } }],
    }));
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", () => {
      server.off("error", reject);
      resolve();
    });
  });
  return server;
}

function resolveMockLlmContent(summary) {
  if (summary.prompt.includes(noProtocolPromptText)) {
    return plainNoProtocolAnswer();
  }
  if (summary.hasMemorySelectToolResults) {
    return finalSearchSelectAnswer();
  }
  if (summary.hasSearchToolResults) {
    return memorySelectProtocolAnswer();
  }
  if (summary.prompt.includes(searchSelectPromptText)) {
    return contextSearchProtocolAnswer();
  }
  if (summary.hasDatasourceListPage2ToolResults) {
    return finalDatasourceListAnswer();
  }
  if (summary.hasDatasourceListPage1ToolResults) {
    return datasourceListPageProtocolAnswer(2);
  }
  if (summary.hasToolResults) {
    return finalLlmAnswer();
  }
  if (summary.hasObserveToolResults) {
    return finalContextObserveAnswer();
  }
  if (summary.hasContextToolResults) {
    return finalContextReadAnswer();
  }
  if (isDatasourceListPrompt(summary.prompt)) {
    return datasourceListPageProtocolAnswer(1);
  }
  if (summary.prompt.includes(observePromptText)) {
    return contextObserveProtocolAnswer();
  }
  if (isDiscoveryPrompt(summary.prompt)) {
    return discoveryProtocolAnswer(summary.activeObjectId || String(datasourceId));
  }
  if (summary.prompt.includes(contextPromptText)) {
    return contextReadProtocolAnswer();
  }
  return discoveryProtocolAnswer(summary.activeObjectId || String(datasourceId));
}

function summarizeLlmRequest(body) {
  const text = JSON.stringify(body || {});
  const messageText = textFromMessages(body?.messages);
  const activeObjectId = firstMatch(messageText, /"activeObject"\s*:\s*\{[\s\S]*?"id"\s*:\s*"?([^",}\s]+)"?/)
    || firstMatch(text, /"activeObject"\s*:\s*\{[\s\S]*?"id"\s*:\s*"?([^",}\s]+)"?/);
  const prompt = promptTextFromPayload(body) || promptTextFromMessages(body?.messages) || "";
  const toolResultsMatches = Array.from(messageText.matchAll(/"toolResults"\s*:\s*\[/g));
  const toolResultsIndex = toolResultsMatches.length
    ? toolResultsMatches[toolResultsMatches.length - 1].index ?? -1
    : -1;
  const toolResultsText = toolResultsIndex >= 0 ? messageText.slice(toolResultsIndex) : "";
  const firstToolResultSnippet = firstMatch(toolResultsText, /"toolResults"\s*:\s*\[\s*\{([\s\S]{0,2000})/) || "";
  const firstToolResultInterfaceCode = firstMatch(firstToolResultSnippet, /"interfaceCode"\s*:\s*"([^"]+)"/) || "";
  const hasToolResults = firstToolResultInterfaceCode === "studio.feature.action"
    && discoveredTables.some((table) => toolResultsText.includes(table));
  const hasObserveToolResults = firstToolResultInterfaceCode === "assistant.context.observe";
  const hasSearchToolResults = /"interfaceCode"\s*:\s*"assistant\.context\.search"/.test(toolResultsText)
    && toolResultsText.includes(datasourceName);
  const hasMemorySelectToolResults = /"interfaceCode"\s*:\s*"assistant\.memory\.select"/.test(toolResultsText)
    && toolResultsText.includes(datasourceName)
    && /"selected"\s*:\s*true/.test(toolResultsText);
  const hasDatasourceListToolResults = firstToolResultInterfaceCode === "studio.feature.list"
    && /"path"\s*:\s*"\/datasources"/.test(toolResultsText)
    && pagedDatasourceNames.some((name) => toolResultsText.includes(name));
  const hasDatasourceListPage1ToolResults = hasDatasourceListToolResults
    && /"pageNo"\s*:\s*1/.test(toolResultsText)
    && /"total"\s*:\s*3/.test(toolResultsText);
  const hasDatasourceListPage2ToolResults = hasDatasourceListToolResults
    && /"pageNo"\s*:\s*2/.test(toolResultsText)
    && toolResultsText.includes(pagedDatasourceNames[2]);
  const hasDatasourceListPage1Pagination = hasDatasourceListPage1ToolResults
    && /"pagination"\s*:\s*\{[\s\S]*?"pageNo"\s*:\s*1/.test(toolResultsText)
    && /"pagination"\s*:\s*\{[\s\S]*?"pages"\s*:\s*2/.test(toolResultsText)
    && /"pagination"\s*:\s*\{[\s\S]*?"hasMore"\s*:\s*true/.test(toolResultsText)
    && /"pagination"\s*:\s*\{[\s\S]*?"nextPage"\s*:\s*2/.test(toolResultsText);
  const hasDatasourceListPage2Pagination = hasDatasourceListPage2ToolResults
    && /"pagination"\s*:\s*\{[\s\S]*?"pageNo"\s*:\s*2/.test(toolResultsText)
    && /"pagination"\s*:\s*\{[\s\S]*?"pages"\s*:\s*2/.test(toolResultsText)
    && /"pagination"\s*:\s*\{[\s\S]*?"hasMore"\s*:\s*false/.test(toolResultsText);
  const hasContextToolResults = firstToolResultInterfaceCode === "assistant.context.read";
  const branch = resolveMockLlmBranch({
    prompt,
    hasToolResults,
    hasObserveToolResults,
    hasSearchToolResults,
    hasMemorySelectToolResults,
    hasContextToolResults,
    hasDatasourceListPage1ToolResults,
    hasDatasourceListPage2ToolResults,
  });
  return {
    prompt,
    activeObjectId,
    hasToolResults,
    hasObserveToolResults,
    hasSearchToolResults,
    hasMemorySelectToolResults,
    hasDatasourceListPage1ToolResults,
    hasDatasourceListPage2ToolResults,
    hasDatasourceListPage1Pagination,
    hasDatasourceListPage2Pagination,
    hasContextToolResults,
    branch,
  };
}

function resolveMockLlmBranch(summary) {
  if (summary.prompt.includes(noProtocolPromptText)) {
    return "plain-no-protocol-no-recovery";
  }
  if (summary.hasMemorySelectToolResults) {
    return "final-after-memory-select";
  }
  if (summary.hasSearchToolResults) {
    return "memory-select-from-search-results";
  }
  if (summary.prompt.includes(searchSelectPromptText)) {
    return "context-search-current-page";
  }
  if (summary.hasDatasourceListPage2ToolResults) {
    return "final-after-datasource-list-page-2";
  }
  if (summary.hasDatasourceListPage1ToolResults) {
    return "datasource-list-page-2";
  }
  if (summary.hasToolResults) {
    return "final-after-tool-result";
  }
  if (summary.hasObserveToolResults) {
    return "final-after-context-observe";
  }
  if (summary.hasContextToolResults) {
    return "final-after-context-read";
  }
  if (isDatasourceListPrompt(summary.prompt)) {
    return "datasource-list-page-1";
  }
  if (summary.prompt.includes(observePromptText)) {
    return "context-observe-from-page-state";
  }
  if (isDiscoveryPrompt(summary.prompt)) {
    return "discover-from-page-context";
  }
  if (summary.prompt.includes(contextPromptText)) {
    return "context-read-from-page-context";
  }
  return "discover-from-page-context";
}

function datasourceListPageProtocolAnswer(pageNo) {
  const protocol = {
    protocol: "studio-assistant.v1",
    plan: {
      intent: pageNo === 1 ? "分页读取所有数据源" : "继续读取下一页数据源",
      basis: pageNo === 1
        ? [
          "用户要求列出所有数据源且不要只看当前页",
          "operation catalog 暴露 /datasources 的 studio.feature.list 分页读取能力",
        ]
        : [
          "第一页 toolResults 显示 total 大于 pageSize",
          "需要继续读取 pageNo=2 才能覆盖所有数据源",
        ],
      requiredObjects: [{ type: "feature", path: "/datasources" }],
      nextActions: [
        { type: "tool", tool: "studio.feature.list", path: "/datasources", reason: `读取第 ${pageNo} 页数据源` },
      ],
    },
    loop: {
      mode: "goal",
      status: "tool_pending",
      autoContinue: true,
      questions: [
        { id: "q1", input: "所有数据源有哪些？", status: "needs_tool", output: `等待第 ${pageNo} 页工具结果` },
      ],
      next: [
        { id: `step-page-${pageNo}`, type: "tool", status: "pending", description: `读取 /datasources pageNo=${pageNo}, pageSize=${datasourceListPageSize}` },
      ],
      evidence: pageNo === 1 ? [] : [{ type: "toolResult", tool: "studio.feature.list", path: "/datasources", pageNo: 1 }],
      stopReason: "waiting_for_tool_result",
    },
    actions: [
      {
        type: "frontendTool",
        tool: "studio.feature.list",
        reason: `Read datasource page ${pageNo} through backend gateway.`,
        params: {
          path: "/datasources",
          pageNo,
          pageSize: datasourceListPageSize,
        },
      },
    ],
    controls: [],
  };
  return `${pageNo === 1 ? "我会分页读取数据源列表，先取第一页。" : "第一页结果显示还有后续页，我继续读取第二页。"}\n\n\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``;
}

function plainNoProtocolAnswer() {
  return "我会先查询数据源列表。";
}

function contextSearchProtocolAnswer() {
  const protocol = {
    protocol: "studio-assistant.v1",
    plan: {
      intent: "从当前页面上下文搜索数据源候选",
      basis: [
        "用户要求在当前页面上下文里搜索这个数据源",
        "assistant.context.search 是前端只读上下文工具，不调用 Studio 业务 API",
      ],
      requiredObjects: [
        { type: "pageContext", path: "/datasources", keyword: datasourceName },
      ],
      nextActions: [
        { type: "tool", tool: "assistant.context.search", path: "/datasources", reason: "搜索当前页面和助手记忆中的数据源候选" },
      ],
    },
    loop: {
      mode: "goal",
      status: "tool_pending",
      autoContinue: true,
      questions: [
        { id: "q1", input: "当前页面里能匹配哪个数据源对象？", status: "needs_tool", output: "等待 assistant.context.search 工具结果" },
      ],
      next: [
        { id: "step1", type: "tool", status: "pending", description: "搜索 Web 页面上下文，不调用业务 API" },
      ],
      evidence: [],
      stopReason: "waiting_for_tool_result",
    },
    actions: [
      {
        type: "frontendTool",
        tool: "assistant.context.search",
        reason: "Search current page candidates before selecting memory.",
        params: {
          path: "/datasources",
          keyword: datasourceName,
          kind: "datasource",
          limit: 5,
        },
      },
    ],
    controls: [],
  };
  return `我会先搜索当前页面上下文里的数据源候选，不调用业务接口。\n\n\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``;
}

function memorySelectProtocolAnswer() {
  const protocol = {
    protocol: "studio-assistant.v1",
    plan: {
      intent: "把搜索到的数据源候选设为助手记忆默认数据源",
      basis: [
        "assistant.context.search toolResults 已返回当前页面候选",
        `候选中包含 ${datasourceName}`,
      ],
      requiredObjects: [
        { type: "businessObject", path: "/datasources", id: String(datasourceId), name: datasourceName },
      ],
      nextActions: [
        { type: "tool", tool: "assistant.memory.select", path: "/datasources", reason: "选择现有候选写入助手工作记忆" },
      ],
    },
    loop: {
      mode: "goal",
      status: "tool_pending",
      autoContinue: true,
      questions: [
        { id: "q1", input: "应该选择哪个页面数据源对象？", status: "needs_tool", output: `选择 ${datasourceName}` },
      ],
      next: [
        { id: "step2", type: "tool", status: "pending", description: "从已返回候选中选择数据源到助手记忆" },
      ],
      evidence: [{ type: "toolResult", tool: "assistant.context.search", path: "/datasources" }],
      stopReason: "waiting_for_tool_result",
    },
    actions: [
      {
        type: "frontendTool",
        tool: "assistant.memory.select",
        reason: "Select the datasource candidate returned by assistant.context.search.",
        params: {
          path: "/datasources",
          id: String(datasourceId),
          name: datasourceName,
        },
      },
    ],
    controls: [],
  };
  return `搜索结果里有当前页面数据源，我会把它设为助手记忆里的默认数据源。\n\n\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``;
}

function finalSearchSelectAnswer() {
  const protocol = {
    protocol: "studio-assistant.v1",
    plan: {
      intent: "完成当前页面数据源选择",
      basis: ["toolResults 已返回 assistant.memory.select selected=true"],
      requiredObjects: [{ type: "businessObject", path: "/datasources", id: String(datasourceId), name: datasourceName }],
      nextActions: [{ type: "final", reason: "页面对象已选入助手记忆，直接回答" }],
    },
    loop: {
      mode: "goal",
      status: "completed",
      autoContinue: false,
      questions: [{ id: "q1", input: "是否已选中页面数据源？", status: "answered", output: datasourceName }],
      next: [],
      evidence: [{ type: "toolResult", tool: "assistant.memory.select", path: "/datasources" }],
      stopReason: "answer_complete",
    },
    actions: [],
    controls: [],
  };
  return `页面对象选择结果：已把当前页面数据源 ${datasourceName}（ID ${datasourceId}）设为助手记忆里的默认数据源。本轮只使用页面上下文搜索和记忆选择，没有调用后端业务工具网关。\n\n\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``;
}

function contextObserveProtocolAnswer() {
  const protocol = {
    protocol: "studio-assistant.v1",
    plan: {
      intent: "观察当前页面状态和选中对象",
      basis: [
        "用户要求观察当前页面状态",
        "assistant.context.observe 是只读上下文工具，不调用 Studio 业务 API",
      ],
      requiredObjects: [
        { type: "pageState", path: "/datasources" },
      ],
      nextActions: [
        { type: "tool", tool: "assistant.context.observe", path: "/datasources", reason: "读取当前路由、页面快照、选中对象和助手记忆" },
      ],
    },
    loop: {
      mode: "goal",
      status: "tool_pending",
      autoContinue: true,
      questions: [
        { id: "q1", input: "当前页面状态和选中对象是什么？", status: "needs_tool", output: "等待 assistant.context.observe 工具结果" },
      ],
      next: [
        { id: "step1", type: "tool", status: "pending", description: "观察 Web 页面状态，不调用业务 API" },
      ],
      evidence: [],
      stopReason: "waiting_for_tool_result",
    },
    actions: [
      {
        type: "frontendTool",
        tool: "assistant.context.observe",
        reason: "Observe route, active page object, controls, and assistant memory before deciding any business operation.",
        params: {
          path: "/datasources",
        },
      },
    ],
    controls: [],
  };
  return `我会先观察当前页面状态，不调用业务接口。\n\n\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``;
}

function contextReadProtocolAnswer() {
  const protocol = {
    protocol: "studio-assistant.v1",
    plan: {
      intent: "读取当前页面业务上下文",
      basis: [
        "用户要求先读取当前业务上下文",
        "这是开放上下文观察能力，不需要调用 Studio 业务 API",
      ],
      requiredObjects: [
        { type: "pageContext", path: "/datasources" },
      ],
      nextActions: [
        { type: "tool", tool: "assistant.context.read", path: "/datasources", reason: "读取当前页面选中对象和可见候选" },
      ],
    },
    loop: {
      mode: "goal",
      status: "tool_pending",
      autoContinue: true,
      questions: [
        { id: "q1", input: "当前页面业务上下文是什么？", status: "needs_tool", output: "等待 assistant.context.read 工具结果" },
      ],
      next: [
        { id: "step1", type: "tool", status: "pending", description: "读取 Web 上下文和助手记忆，不调用业务 API" },
      ],
      evidence: [],
      stopReason: "waiting_for_tool_result",
    },
    actions: [
      {
        type: "frontendTool",
        tool: "assistant.context.read",
        reason: "Read current business context without calling backend business APIs.",
        params: {
          path: "/datasources",
          limit: 5,
        },
      },
    ],
    controls: [],
  };
  return `我会先读取当前页面上下文，再判断是否需要业务接口。\n\n\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``;
}

function discoveryProtocolAnswer(activeObjectId) {
  const protocol = {
    protocol: "studio-assistant.v1",
    plan: {
      intent: "列出当前页面选中数据源的所有真实表",
      basis: [
        "用户说当前这个数据源",
        "currentContext.pageContext.activeObject 提供了当前选中数据源",
        "用户要求所有真实表，因此 discover 不传 pageNo/pageSize",
      ],
      requiredObjects: [
        { type: "businessObject", path: "/datasources", id: String(activeObjectId), name: datasourceName },
      ],
      nextActions: [
        { type: "tool", tool: "studio.feature.action", path: "/datasources", action: "discover", reason: "读取真实物理表列表" },
      ],
    },
    loop: {
      mode: "goal",
      status: "tool_pending",
      autoContinue: true,
      questions: [
        { id: "q1", input: "当前数据源有哪些真实表？", status: "needs_tool", output: "等待 discover 工具结果" },
      ],
      next: [
        { id: "step1", type: "tool", status: "pending", description: "调用 /datasources discover 且不传分页参数" },
      ],
      evidence: [],
      stopReason: "waiting_for_tool_result",
    },
    actions: [
      {
        type: "frontendTool",
        tool: "studio.feature.action",
        reason: "Use current page selected datasource and discover all physical tables without pagination.",
        params: {
          path: "/datasources",
          action: "discover",
          id: Number(activeObjectId),
        },
      },
    ],
    controls: [],
  };
  return `我会使用当前页面选中的数据源读取真实物理表。\n\n\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``;
}

function finalLlmAnswer() {
  const protocol = {
    protocol: "studio-assistant.v1",
    plan: {
      intent: "汇总当前数据源所有真实表",
      basis: ["toolResults 已返回 discover 的真实表列表"],
      requiredObjects: [{ type: "feature", path: "/datasources" }],
      nextActions: [{ type: "final", reason: "工具结果足够，直接回答" }],
    },
    loop: {
      mode: "goal",
      status: "completed",
      autoContinue: false,
      questions: [{ id: "q1", input: "当前数据源有哪些真实表？", status: "answered", output: discoveredTables.join(", ") }],
      next: [],
      evidence: [{ type: "toolResult", tool: "studio.feature.action", action: "discover" }],
      stopReason: "answer_complete",
    },
    actions: [],
    controls: [],
  };
  return `当前数据源的真实表有：\n\n${discoveredTables.map((table) => `- ${table}`).join("\n")}\n\n\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``;
}

function finalContextObserveAnswer() {
  const protocol = {
    protocol: "studio-assistant.v1",
    plan: {
      intent: "总结当前页面观察结果",
      basis: ["toolResults 已返回 assistant.context.observe 的页面状态"],
      requiredObjects: [{ type: "pageContext", path: "/datasources", id: String(datasourceId), name: datasourceName }],
      nextActions: [{ type: "final", reason: "观察结果足够，直接回答" }],
    },
    loop: {
      mode: "goal",
      status: "completed",
      autoContinue: false,
      questions: [{ id: "q1", input: "当前页面状态和选中对象是什么？", status: "answered", output: `数据源中心，当前选中 ${datasourceName}` }],
      next: [],
      evidence: [{ type: "toolResult", tool: "assistant.context.observe" }],
      stopReason: "answer_complete",
    },
    actions: [],
    controls: [],
  };
  return `当前页面观察结果：你在数据源中心页面，当前项目是 Default Project，页面当前选中的对象是数据源 ${datasourceName}（ID ${datasourceId}）。本轮只观察 Web 页面状态，没有调用后端业务工具网关。\n\n\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``;
}

function finalContextReadAnswer() {
  const protocol = {
    protocol: "studio-assistant.v1",
    plan: {
      intent: "总结当前页面业务上下文",
      basis: ["toolResults 已返回 assistant.context.read 的页面上下文"],
      requiredObjects: [{ type: "pageContext", path: "/datasources", name: datasourceName }],
      nextActions: [{ type: "final", reason: "上下文结果足够，直接回答" }],
    },
    loop: {
      mode: "goal",
      status: "completed",
      autoContinue: false,
      questions: [{ id: "q1", input: "当前页面业务上下文是什么？", status: "answered", output: datasourceName }],
      next: [],
      evidence: [{ type: "toolResult", tool: "assistant.context.read" }],
      stopReason: "answer_complete",
    },
    actions: [],
    controls: [],
  };
  return `当前业务上下文读取结果：页面当前选中对象是数据源 ${datasourceName}，ID 为 ${datasourceId}。本轮只读取 Web 上下文和助手记忆，没有调用后端业务工具网关。\n\n\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``;
}

function finalDatasourceListAnswer() {
  const protocol = {
    protocol: "studio-assistant.v1",
    plan: {
      intent: "汇总所有分页读取到的数据源",
      basis: ["toolResults 已返回 /datasources 第 1 页和第 2 页结果"],
      requiredObjects: [{ type: "feature", path: "/datasources" }],
      nextActions: [{ type: "final", reason: "分页结果已覆盖 total，直接回答" }],
    },
    loop: {
      mode: "goal",
      status: "completed",
      autoContinue: false,
      questions: [{ id: "q1", input: "所有数据源有哪些？", status: "answered", output: pagedDatasourceNames.join(", ") }],
      next: [],
      evidence: [
        { type: "toolResult", tool: "studio.feature.list", path: "/datasources", pageNo: 1 },
        { type: "toolResult", tool: "studio.feature.list", path: "/datasources", pageNo: 2 },
      ],
      stopReason: "answer_complete",
    },
    actions: [],
    controls: [],
  };
  return `当前项目的数据源有：\n\n${pagedDatasourceNames.map((name) => `- ${name}`).join("\n")}\n\n\`\`\`studio-assistant-protocol\n${JSON.stringify(protocol)}\n\`\`\``;
}

function writeLlmChunk(response, content) {
  const payload = {
    choices: [
      {
        delta: { content },
      },
    ],
  };
  response.write(`data: ${JSON.stringify(payload)}\n\n`);
}

function promptTextFromPayload(body) {
  const message = typeof body?.message === "string" ? body.message.trim() : "";
  if (!message) {
    return "";
  }
  return normalizeKnownPromptText(message);
}

function promptTextFromMessages(messages) {
  if (!Array.isArray(messages)) {
    return "";
  }
  for (let index = messages.length - 1; index >= 0; index--) {
    const item = messages[index];
    if (item?.role === "user" && typeof item.content === "string") {
      const trimmed = item.content.trim();
      if (trimmed && !trimmed.startsWith("{")) {
        return normalizeKnownPromptText(trimmed);
      }
    }
  }
  return "";
}

function normalizeKnownPromptText(text) {
  if (text.includes(noProtocolPromptText)) {
    return noProtocolPromptText;
  }
  if (text.includes(searchSelectPromptText)) {
    return searchSelectPromptText;
  }
  const discoveryPrompt = resolveDiscoveryPromptText(text);
  if (discoveryPrompt) {
    return discoveryPrompt;
  }
  if (isDatasourceListPrompt(text)) {
    return datasourceListPromptText;
  }
  if (text.includes(observePromptText)) {
    return observePromptText;
  }
  if (text.includes(contextPromptText)) {
    return contextPromptText;
  }
  return text;
}

function isDiscoveryPrompt(text) {
  return Boolean(resolveDiscoveryPromptText(text));
}

function isDatasourceListPrompt(text) {
  const normalized = String(text || "").replace(/\s+/g, "");
  if (!normalized.includes("数据源")) {
    return false;
  }
  const wantsAll = /(所有|全部|全量|完整|全(部)?列|都列|列全|全给|全查)/.test(normalized);
  const rejectsSinglePage = /(不只|不要只|别只|别停|不是只|当前页|第一页|分页|翻页|下一页)/.test(normalized);
  return wantsAll && rejectsSinglePage;
}

function resolveDiscoveryPromptText(text) {
  if (!text) {
    return "";
  }
  const match = discoveryPromptCases.find((item) => text.includes(item.text));
  return match?.text || "";
}

function textFromMessages(messages) {
  if (!Array.isArray(messages)) {
    return "";
  }
  return messages
    .map((item) => typeof item?.content === "string" ? item.content : "")
    .filter(Boolean)
    .join("\n");
}

async function login(page) {
  await waitForDom(page, `location.href.includes("/dashboard") || document.querySelector('input[autocomplete="username"]')`, 30000);
  const authState = await evaluate(page, `({
    onDashboard: location.href.includes("/dashboard"),
    tokenPresent: Boolean(window.localStorage.getItem("studio_token")),
    hasLoginForm: Boolean(document.querySelector('input[autocomplete="username"]'))
  })`).catch(() => ({ onDashboard: false, tokenPresent: false, hasLoginForm: false }));
  if (authState.onDashboard && authState.tokenPresent) {
    return;
  }
  if (!authState.hasLoginForm) {
    await page.send("Page.navigate", { url: `${appBaseUrl}login` });
    await waitForPageLoad(page);
    await waitForDom(page, `document.querySelector('input[autocomplete="username"]')`, 30000);
  }
  await setField(page, `input[autocomplete="username"]`, "admin");
  await setField(page, `input[autocomplete="current-password"]`, "admin123");
  await click(page, `.login__form .login__submit`);
  await waitForDom(page, `location.href.includes("/dashboard")`, 30000);
}

async function fetchAssistantConfigWithFreshLogin() {
  const loginResponse = await fetch(`${backendBaseUrl}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({ username: "admin", password: "admin123" }),
  });
  const loginPayload = await loginResponse.json().catch(() => ({}));
  const loginData = loginPayload?.data || loginPayload || {};
  const token = loginData.token || loginData.accessToken || "";
  assert(loginResponse.ok && token, `Backend login for assistant config failed: HTTP ${loginResponse.status} ${JSON.stringify(loginPayload)}`);
  const headers = {
    Authorization: `Bearer ${token}`,
    Accept: "application/json",
  };
  if (loginData.currentTenantId) {
    headers["X-Tenant-Id"] = String(loginData.currentTenantId);
  }
  if (loginData.currentProjectId) {
    headers["X-Project-Id"] = String(loginData.currentProjectId);
  }
  const response = await fetch(`${backendBaseUrl}/api/v1/assistant/config`, { headers });
  const payload = await response.json().catch(() => ({}));
  return {
    status: response.status,
    success: payload?.success === true,
    enabled: payload?.data?.enabled === true,
    reason: payload?.data?.reason || "",
  };
}

async function waitForAssistantHiddenInUi(page, timeoutMs) {
  const startedAt = Date.now();
  let latest = null;
  while (Date.now() - startedAt < timeoutMs) {
    latest = await evaluate(page, `
      ({
        onDashboard: location.href.includes("/dashboard"),
        tokenPresent: Boolean(window.localStorage.getItem("studio_token")),
        tenantPresent: Boolean(window.localStorage.getItem("studio_current_tenant")),
        projectPresent: Boolean(window.localStorage.getItem("studio_current_project")),
        assistantButtonCount: document.querySelectorAll('[data-testid="studio-assistant-open"]').length,
        assistantDrawerCount: document.querySelectorAll('[data-testid="studio-assistant-root"]').length
      })
    `).catch((error) => ({ error: String(error) }));
    if (latest.onDashboard === true
      && latest.tokenPresent === true
      && latest.assistantButtonCount === 0
      && latest.assistantDrawerCount === 0) {
      return latest;
    }
    await sleep(500);
  }
  throw new Error(`Assistant entry was not hidden when LLM was disabled: ${JSON.stringify(latest)}`);
}

async function waitForAssistantEnabledConfig(page, timeoutMs) {
  const startedAt = Date.now();
  let latest = null;
  while (Date.now() - startedAt < timeoutMs) {
    latest = await evaluate(page, `
      (async () => {
        const token = window.localStorage.getItem("studio_token");
        const response = await fetch("/api/v1/assistant/config", {
          headers: token ? { Authorization: "Bearer " + token } : {}
        });
        const payload = await response.json().catch(() => ({}));
        return { status: response.status, enabled: payload.data?.enabled === true, payload };
      })()
    `).catch((error) => ({ status: 0, enabled: false, error: String(error) }));
    if (latest.enabled === true) {
      return latest;
    }
    await sleep(500);
  }
  throw new Error(`Assistant config did not become enabled: ${JSON.stringify(latest)}`);
}

function installNetworkRecorder(page) {
  const pending = new Map();
  const entries = [];
  page.on("Network.requestWillBeSent", (event) => {
    const url = event.request?.url || "";
    if (!isInterestingRequest(url)) {
      return;
    }
    pending.set(event.requestId, {
      id: event.requestId,
      url,
      method: event.request?.method || "",
      requestPostData: event.request?.postData || "",
      requestBodyJson: parseJson(event.request?.postData),
      startedAt: Date.now(),
    });
  });
  page.on("Network.responseReceived", (event) => {
    const entry = pending.get(event.requestId);
    if (entry) {
      entry.status = event.response?.status;
      entry.mimeType = event.response?.mimeType || "";
    }
  });
  page.on("Network.loadingFinished", (event) => {
    const entry = pending.get(event.requestId);
    if (!entry) {
      return;
    }
    pending.delete(event.requestId);
    void page.send("Network.getResponseBody", { requestId: event.requestId })
      .then((body) => {
        entry.responseBodyText = body?.body || "";
        entry.responseBodyJson = parseJson(entry.responseBodyText);
        entry.finishedAt = Date.now();
        entries.push(entry);
      })
      .catch(() => {
        entry.finishedAt = Date.now();
        entries.push(entry);
      });
  });
  return {
    entries,
    async waitFor(predicate, timeoutMs, since = 0) {
      const startedAt = Date.now();
      while (Date.now() - startedAt < timeoutMs) {
        const matched = entries.slice(since).find(predicate);
        if (matched) {
          return matched;
        }
        await sleep(250);
      }
      const tail = entries.slice(Math.max(0, entries.length - 12)).map((entry) => ({
        method: entry.method,
        url: entry.url,
        status: entry.status,
        request: entry.requestBodyJson,
        response: entry.responseBodyJson,
      }));
      throw new Error(`Timed out waiting for expected network call. Recent calls:\n${JSON.stringify(tail, null, 2)}`);
    },
  };
}

function installBrowserErrorRecorder(page) {
  const errors = [];
  page.on("Runtime.exceptionThrown", (event) => {
    errors.push(event.exceptionDetails?.text || event.exceptionDetails?.exception?.description || "Runtime exception");
  });
  page.on("Runtime.consoleAPICalled", (event) => {
    if (event.type === "error") {
      errors.push(event.args?.map((arg) => arg.value || arg.description || "").join(" ") || "console.error");
    }
  });
  page.on("Log.entryAdded", (event) => {
    if (event.entry?.level === "error") {
      errors.push(event.entry.text);
    }
  });
  return errors;
}

function isInterestingRequest(url) {
  return /\/api\/v1\/assistant\//.test(url)
    || /\/api\/v1\/models(?:[/?]|$)/.test(url)
    || /\/api\/v1\/datasources\/page(?:[/?]|$)/.test(url);
}

function summarizeToolEntry(entry) {
  const request = entry.requestBodyJson || {};
  const response = entry.responseBodyJson || {};
  const data = response.data || {};
  return {
    requestUrl: trimLocalUrl(entry.url),
    httpStatus: entry.status,
    requestInterfaceCode: request.interfaceCode,
    requestParams: request.params,
    responseSuccess: response.success,
    executedBy: data.executedBy,
    responsePath: data.path,
    responseAction: data.action,
    responseModelNames: Array.isArray(data.data?.models)
      ? data.data.models.map((model) => model.name || model.physicalLocator)
      : [],
  };
}

function summarizeDatasourceListToolEntry(entry) {
  const request = entry.requestBodyJson || {};
  const response = entry.responseBodyJson || {};
  const envelope = response.data || {};
  const page = envelope.data || {};
  return {
    requestUrl: trimLocalUrl(entry.url),
    httpStatus: entry.status,
    requestInterfaceCode: request.interfaceCode,
    requestParams: request.params,
    responseSuccess: response.success,
    executedBy: envelope.executedBy,
    responsePath: envelope.path,
    pageNo: page.pageNo,
    pageSize: page.pageSize,
    total: page.total,
    itemNames: Array.isArray(page.items)
      ? page.items.map((item) => item.name)
      : [],
  };
}

async function sqliteExec(dbPath, sql, params = []) {
  await sqliteQuery(dbPath, sql, params);
}

async function sqliteQuery(dbPath, sql, params = []) {
  const javaFile = await ensureSqliteQueryJavaSource();
  const jdbcJar = await ensureSqliteJdbcJar();
  const result = spawnSync(resolveJdkTool("java"), [
    "-cp",
    jdbcJar,
    javaFile,
    normalizeSqlitePath(dbPath),
    sql,
    ...params.map((item) => item == null ? "" : String(item)),
  ], {
    cwd: workspaceRoot,
    encoding: "utf8",
    windowsHide: true,
    maxBuffer: 10 * 1024 * 1024,
  });
  assert(result.status === 0, `SQLite query failed:\n${result.stderr || result.stdout}`);
  return JSON.parse(result.stdout || "[]");
}

async function ensureSqliteJdbcJar() {
  if (sqliteJdbcJar && existsSync(sqliteJdbcJar)) {
    return sqliteJdbcJar;
  }
  const helperDir = path.join(targetDir, "java", "lib");
  await fs.mkdir(helperDir, { recursive: true });
  const jar = resolveJdkTool("jar");
  const list = spawnSync(jar, ["tf", backendJar], {
    encoding: "utf8",
    windowsHide: true,
    maxBuffer: 10 * 1024 * 1024,
  });
  assert(list.status === 0, `Failed to inspect studio-server jar:\n${list.stderr || list.stdout}`);
  const entry = String(list.stdout || "").split(/\r?\n/).find((line) => /^BOOT-INF\/lib\/sqlite-jdbc-.*\.jar$/.test(line));
  assert(entry, `Could not find sqlite-jdbc in ${backendJar}.`);
  sqliteJdbcJar = path.join(helperDir, ...entry.split("/"));
  if (!existsSync(sqliteJdbcJar)) {
    const extract = spawnSync(jar, ["xf", backendJar, entry], {
      cwd: helperDir,
      encoding: "utf8",
      windowsHide: true,
      maxBuffer: 10 * 1024 * 1024,
    });
    assert(extract.status === 0, `Failed to extract sqlite-jdbc from studio-server jar:\n${extract.stderr || extract.stdout}`);
  }
  assert(existsSync(sqliteJdbcJar), `Extracted sqlite-jdbc was not found: ${sqliteJdbcJar}`);
  return sqliteJdbcJar;
}

async function ensureSqliteQueryJavaSource() {
  const helperDir = path.join(targetDir, "java");
  await fs.mkdir(helperDir, { recursive: true });
  const javaFile = path.join(helperDir, "AssistantWebSmokeSqliteQuery.java");
  if (existsSync(javaFile)) {
    return javaFile;
  }
  await fs.writeFile(javaFile, `
import java.sql.*;

public class AssistantWebSmokeSqliteQuery {
  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      throw new IllegalArgumentException("Usage: <dbPath> <sql> [params...]");
    }
    Class.forName("org.sqlite.JDBC");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + args[0]);
         PreparedStatement statement = connection.prepareStatement(args[1])) {
      for (int index = 2; index < args.length; index++) {
        statement.setString(index - 1, args[index]);
      }
      boolean hasResult = statement.execute();
      if (!hasResult) {
        System.out.print("{\\"changes\\":" + statement.getUpdateCount() + "}");
        return;
      }
      try (ResultSet rs = statement.getResultSet()) {
        ResultSetMetaData meta = rs.getMetaData();
        StringBuilder out = new StringBuilder();
        out.append('[');
        boolean firstRow = true;
        while (rs.next()) {
          if (!firstRow) out.append(',');
          firstRow = false;
          out.append('{');
          for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (i > 1) out.append(',');
            out.append('"').append(json(meta.getColumnLabel(i))).append('"').append(':');
            Object value = rs.getObject(i);
            if (value == null) {
              out.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
              out.append(String.valueOf(value));
            } else {
              out.append('"').append(json(String.valueOf(value))).append('"');
            }
          }
          out.append('}');
        }
        out.append(']');
        System.out.print(out.toString());
      }
    }
  }

  private static String json(String value) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch == '"' || ch == '\\\\') {
        out.append('\\\\').append(ch);
      } else if (ch == '\\n') {
        out.append("\\\\n");
      } else if (ch == '\\r') {
        out.append("\\\\r");
      } else if (ch == '\\t') {
        out.append("\\\\t");
      } else {
        out.append(ch);
      }
    }
    return out.toString();
  }
}
`);
  return javaFile;
}

async function waitForBackendLogin(timeoutMs) {
  const startedAt = Date.now();
  let latest = "";
  while (Date.now() - startedAt < timeoutMs) {
    try {
      const response = await fetch(`${backendBaseUrl}/api/v1/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ username: "admin", password: "admin123" }),
      });
      latest = `${response.status} ${await response.text()}`;
      if (response.ok && latest.includes("token")) {
        return;
      }
    } catch (error) {
      latest = error instanceof Error ? error.message : String(error);
    }
    await sleep(500);
  }
  throw new Error(`Timed out waiting for backend login. Latest: ${latest}`);
}

function startJavaProcess(label, args, env) {
  return startProcess(resolveJdkTool("java"), args, {
    cwd: studioRoot,
    env,
  }, label);
}

function startProcess(command, args, options, label) {
  const child = spawn(command, args, {
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
    ...options,
  });
  child.stdout.on("data", (chunk) => process.stdout.write(prefixOutput(label, child.pid, chunk)));
  child.stderr.on("data", (chunk) => process.stderr.write(prefixOutput(label, child.pid, chunk)));
  child.on("exit", (code, signal) => {
    if (code && code !== 0 && !process.exitCode && !stoppingPids.has(child.pid)) {
      console.error(`[${label}:${child.pid}] exited with code ${code}${signal ? ` signal ${signal}` : ""}.`);
    }
  });
  return child;
}

function prefixOutput(label, pid, chunk) {
  return String(chunk).split(/\r?\n/).filter(Boolean).map((line) => `[${label}:${pid}] ${line}\n`).join("");
}

function waitForShutdownSignal() {
  return new Promise((resolve) => {
    const done = (signal) => {
      console.log(`Stopping temporary Web environment after ${signal}.`);
      process.off("SIGINT", done);
      process.off("SIGTERM", done);
      resolve();
    };
    process.once("SIGINT", done);
    process.once("SIGTERM", done);
  });
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

function removeChild(child) {
  const index = children.indexOf(child);
  if (index >= 0) {
    children.splice(index, 1);
  }
}

function waitForExit(child, timeoutMs) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`Timed out waiting for process ${child.pid}`)), timeoutMs);
    child.once("exit", (code) => {
      clearTimeout(timeout);
      resolve(code);
    });
  });
}

async function waitForTcpPortClosed(port, timeoutMs) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    const open = await isTcpPortOpen(port);
    if (!open) {
      return;
    }
    await sleep(250);
  }
  throw new Error(`Timed out waiting for TCP port ${port} to close.`);
}

function isTcpPortOpen(port) {
  return new Promise((resolve) => {
    const socket = net.createConnection({ host: "127.0.0.1", port }, () => {
      socket.destroy();
      resolve(true);
    });
    socket.once("error", () => resolve(false));
    socket.setTimeout(500, () => {
      socket.destroy();
      resolve(false);
    });
  });
}

async function waitForHttp(url, label, timeoutMs) {
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

async function createTarget(url) {
  const encoded = encodeURIComponent(url);
  let response = await fetch(`${cdpBaseUrl}/json/new?${encoded}`, { method: "PUT" });
  if (!response.ok) {
    response = await fetch(`${cdpBaseUrl}/json/new?${encoded}`);
  }
  assert(response.ok, `Failed to create browser target: HTTP ${response.status}`);
  return response.json();
}

async function waitForPageLoad(page, timeoutMs = 30000) {
  await page.waitForEvent("Page.loadEventFired", timeoutMs).catch(() => undefined);
}

async function waitForDom(page, expression, timeoutMs) {
  await waitForEval(page, `Boolean(${expression})`, timeoutMs);
}

async function waitForText(page, text, timeoutMs) {
  const quoted = JSON.stringify(text);
  try {
    await waitForEval(page, `document.body && document.body.innerText.includes(${quoted})`, timeoutMs);
  } catch (error) {
    const visibleText = await textContent(page).catch(() => "");
    throw new Error(`Timed out waiting for text ${quoted}. Visible text tail:\n${visibleText.slice(-4000)}`, { cause: error });
  }
}

async function waitForAssistantReady(page, timeoutMs) {
  await waitForEval(page, `
    (() => {
      const root = document.querySelector('[data-testid="studio-assistant-prompt"]');
      const prompt = root?.matches("input,textarea") ? root : root?.querySelector("input,textarea");
      const send = document.querySelector('[data-testid="studio-assistant-send"]');
      return Boolean(prompt && send && !prompt.disabled && !send.classList.contains("is-loading"));
    })()
  `, timeoutMs);
}

async function waitForPromptReady(page, value, timeoutMs) {
  const quoted = JSON.stringify(value);
  await waitForEval(page, `
    (() => {
      const root = document.querySelector('[data-testid="studio-assistant-prompt"]');
      const prompt = root?.matches("input,textarea") ? root : root?.querySelector("input,textarea");
      const send = document.querySelector('[data-testid="studio-assistant-send"]');
      return Boolean(prompt
        && send
        && prompt.value === ${quoted}
        && !prompt.disabled
        && !send.disabled
        && !send.matches(":disabled")
        && !send.classList.contains("is-disabled")
        && !send.classList.contains("is-loading"));
    })()
  `, timeoutMs);
}

async function waitForLlmRequestCount(expectedCount, timeoutMs, label) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    if (llmAudit.requests.length >= expectedCount) {
      return;
    }
    await sleep(250);
  }
  throw new Error(`${label} Recent LLM requests:\n${JSON.stringify(llmAudit.requests.slice(-6), null, 2)}`);
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

async function readJson(request) {
  const text = await readText(request);
  if (!text.trim()) {
    return {};
  }
  return JSON.parse(text);
}

function readText(request) {
  return new Promise((resolve, reject) => {
    let body = "";
    request.setEncoding("utf8");
    request.on("data", (chunk) => {
      body += chunk;
    });
    request.on("end", () => resolve(body));
    request.on("error", reject);
  });
}

function parseJson(text) {
  if (!text || !String(text).trim()) {
    return null;
  }
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function firstMatch(text, regex) {
  const match = regex.exec(String(text || ""));
  return match ? String(match[1]) : "";
}

function trimLocalUrl(url) {
  return String(url || "").replace(/^https?:\/\/127\.0\.0\.1:\d+/, "");
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
  throw new Error("Chrome or Edge executable was not found. Set STUDIO_ASSISTANT_SMOKE_BROWSER to run the Web server smoke test.");
}

function resolveJdkHome() {
  if (resolvedJdkHome) {
    return resolvedJdkHome;
  }
  const candidates = [
    process.env.JAVA_HOME,
    "C:\\dev\\Java\\jdk-17.0.12",
    "C:\\dev\\Java\\jdk-21.0.2",
    "C:\\dev\\Java\\jdk-23",
  ].filter(Boolean);
  for (const candidate of candidates) {
    if (readJdkMajorVersion(candidate) >= 17) {
      resolvedJdkHome = candidate;
      return resolvedJdkHome;
    }
  }
  throw new Error("JDK 17+ was not found. Set JAVA_HOME to a JDK that can run the Studio backend.");
}

function readJdkMajorVersion(jdkHome) {
  const java = path.join(jdkHome, "bin", process.platform === "win32" ? "java.exe" : "java");
  if (!existsSync(java)) {
    return 0;
  }
  const result = spawnSync(java, ["-version"], {
    encoding: "utf8",
    windowsHide: true,
  });
  const text = `${result.stdout || ""}\n${result.stderr || ""}`;
  const match = /version\s+"(\d+)(?:\.(\d+))?/.exec(text);
  if (!match) {
    return 0;
  }
  return match[1] === "1" ? Number(match[2] || 0) : Number(match[1] || 0);
}

function resolveJdkTool(name) {
  const executable = process.platform === "win32" ? `${name}.exe` : name;
  const candidate = path.join(resolveJdkHome(), "bin", executable);
  assert(existsSync(candidate), `Missing JDK tool: ${candidate}`);
  return candidate;
}

function normalizeSqlitePath(filePath) {
  return path.resolve(filePath).replace(/\\/g, "/");
}

function pathToFileUrl(filePath) {
  return `file:///${normalizeSqlitePath(filePath).replace(/^([A-Za-z]):/, "$1:")}`;
}

function isIgnorableBrowserError(message) {
  const text = String(message || "");
  return /Failed to load resource: the server responded with a status of 404 \(Not Found\)/i.test(text)
    || /Failed to load resource: the server responded with a status of 401 \(Unauthorized\)/i.test(text);
}

async function closeServer(server) {
  if (!server) {
    return;
  }
  await new Promise((resolve) => server.close(() => resolve()));
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
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

  waitForEvent(method, timeoutMs) {
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
