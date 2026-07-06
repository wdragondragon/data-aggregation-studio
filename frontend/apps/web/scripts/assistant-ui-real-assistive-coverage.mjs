import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const appPort = Number(process.env.STUDIO_ASSISTANT_COVERAGE_APP_PORT || 5176);
const cdpPort = Number(process.env.STUDIO_ASSISTANT_COVERAGE_CDP_PORT || 9225);
const headed = process.env.STUDIO_ASSISTANT_UI_HEADED === "true";
const workspaceRoot = fileURLToPath(new URL("..", import.meta.url));
const targetDir = path.resolve(workspaceRoot, "target");
const reportDir = path.join(targetDir, "assistant-assistive-coverage");
const backendBaseUrl = normalizeBaseUrl(process.env.STUDIO_ASSISTANT_BACKEND_BASE_URL || "http://127.0.0.1:18080");
const appBaseUrl = normalizeAppBaseUrl(process.env.STUDIO_ASSISTANT_WEB_BASE_URL || `http://127.0.0.1:${appPort}/dfs/data-aggregation-studio/`);
const startVite = process.env.STUDIO_ASSISTANT_COVERAGE_START_VITE !== "false" && !process.env.STUDIO_ASSISTANT_WEB_BASE_URL;
const onlyMultiTurn = process.env.STUDIO_ASSISTANT_COVERAGE_ONLY_MULTI_TURN === "true";
const cdpBaseUrl = `http://127.0.0.1:${cdpPort}`;
const loginUsername = process.env.STUDIO_ASSISTANT_USERNAME || "admin";
const loginPassword = process.env.STUDIO_ASSISTANT_PASSWORD || "admin123";
const caseTimeoutMs = Number(process.env.STUDIO_ASSISTANT_CASE_TIMEOUT_MS || 30000);
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
  await fs.mkdir(reportDir, { recursive: true });

  await waitForBackendLogin(60000);

  if (startVite) {
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
  }
  await waitForHttp(appBaseUrl, "Vite app");

  userDataDir = await fs.mkdtemp(path.join(os.tmpdir(), "studio-assistant-coverage-"));
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
  await page.send("Network.enable");
  const network = installNetworkRecorder(page);

  await page.send("Page.navigate", { url: appBaseUrl });
  await waitForPageLoad(page);

  await waitForDom(page, `document.querySelector('input[autocomplete="username"]')`, 30000);
  await setField(page, `input[autocomplete="username"]`, loginUsername);
  await setField(page, `input[autocomplete="current-password"]`, loginPassword);
  await click(page, `.login__form .login__submit`);
  await waitForAssistantEnabledConfig(page, 60000);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-open"]')`, 60000);

  const operationsProbe = await fetchAssistantOperations(page);
  assert(Array.isArray(operationsProbe) && operationsProbe.length >= 27, "Assistant operations endpoint did not return the expected catalog.");
  const readCases = operationsProbe
    .filter((operation) => operation && operation.supportsList !== false && operation.path)
    .map((operation) => ({
      id: `read:${operation.path}`,
      kind: "read",
      label: String(operation.label || operation.path),
      path: normalizePath(String(operation.path)),
      prompt: `请读取 ${operation.label || operation.path} ${operation.path} 的列表或概览，必须通过 Studio 助手受控读取接口返回。`,
      expectedInterfaceCode: "studio.feature.list",
    }));

  await click(page, `[data-testid="studio-assistant-open"]`);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-root"]')`);
  await click(page, `[data-testid="studio-assistant-mode-goal"]`);
  await click(page, `[data-testid="studio-assistant-language-zh"]`);
  await waitForText(page, "目标模式", 10000);

  const evidence = {
    generatedAt: new Date().toISOString(),
    appBaseUrl,
    backendBaseUrl,
    database: databaseEvidenceConfig(),
    operationCatalogSize: operationsProbe.length,
    actionCatalog: buildActionCatalog(operationsProbe),
    readCases: [],
    multiTurnCases: [],
    actionCases: [],
    mutationCases: [],
    gaps: [],
  };

  if (!onlyMultiTurn) {
    for (let index = 0; index < readCases.length; index++) {
      const testCase = readCases[index];
      console.log(`[assistant-coverage] read ${index + 1}/${readCases.length}: ${testCase.path} ${testCase.label}`);
      const result = await runAssistantReadCase(page, network, testCase).catch((error) => ({
        ...baseCaseEvidence(testCase),
        status: "failed",
        error: error instanceof Error ? error.message : String(error),
      }));
      evidence.readCases.push(result);
      console.log(`[assistant-coverage] read ${index + 1}/${readCases.length}: ${result.status}${result.error ? ` - ${result.error.split(/\r?\n/)[0]}` : ""}`);
    }
  } else {
    console.log("[assistant-coverage] read cases skipped by STUDIO_ASSISTANT_COVERAGE_ONLY_MULTI_TURN=true");
  }

  if (onlyMultiTurn && process.env.STUDIO_ASSISTANT_COVERAGE_PRELOAD_DATASOURCES === "true") {
    console.log("[assistant-coverage] debug preload: /datasources read before seeding");
    await runAssistantReadCase(page, network, {
      id: "debug-read:/datasources",
      kind: "read",
      label: "Datasources",
      path: "/datasources",
      prompt: "请读取 Datasources /datasources 的列表或概览，必须通过 Studio 助手受控读取接口返回。",
      expectedInterfaceCode: "studio.feature.list",
    });
  }

  const seededMetadata = await seedAssistantCoverageMetadata(page);
  evidence.seededMetadata = seededMetadata;
  const genericMultiTurnCases = buildGenericEntityReferenceCases(seededMetadata);
  const ambiguityMultiTurnCase = buildGenericAmbiguityReferenceCase();
  const ordinalMultiTurnCase = buildGenericOrdinalReferenceCase();
  const totalMultiTurnCases = 4 + genericMultiTurnCases.length;
  console.log(`[assistant-coverage] multi-turn 1/${totalMultiTurnCases}: datasource memory -> physical table discovery`);
  const datasourceMemoryResult = await runDatasourceMemoryDiscoveryCase(page, network, seededMetadata).catch((error) => ({
    id: "multi-turn:/datasources:selected-discover",
    kind: "multi-turn",
    label: "Datasource selected memory discovers physical tables",
    path: "/datasources",
    status: "failed",
    error: error instanceof Error ? error.message : String(error),
  }));
  evidence.multiTurnCases.push(datasourceMemoryResult);
  console.log(`[assistant-coverage] multi-turn 1/${totalMultiTurnCases}: ${datasourceMemoryResult.status}${datasourceMemoryResult.error ? ` - ${datasourceMemoryResult.error.split(/\r?\n/)[0]}` : ""}`);

  console.log(`[assistant-coverage] multi-turn 2/${totalMultiTurnCases}: datasource page context -> physical table discovery`);
  const datasourcePageContextResult = await runDatasourcePageContextDiscoveryCase(page, network, seededMetadata).catch((error) => ({
    id: "multi-turn:/datasources:page-context-discover",
    kind: "multi-turn",
    label: "Datasource page context active object discovers physical tables",
    path: "/datasources",
    status: "failed",
    error: error instanceof Error ? error.message : String(error),
  }));
  evidence.multiTurnCases.push(datasourcePageContextResult);
  console.log(`[assistant-coverage] multi-turn 2/${totalMultiTurnCases}: ${datasourcePageContextResult.status}${datasourcePageContextResult.error ? ` - ${datasourcePageContextResult.error.split(/\r?\n/)[0]}` : ""}`);

  console.log(`[assistant-coverage] multi-turn 3/${totalMultiTurnCases}: ${ambiguityMultiTurnCase.path} ${ambiguityMultiTurnCase.label}`);
  const ambiguityResult = await runGenericAmbiguityReferenceCase(page, network, ambiguityMultiTurnCase).catch((error) => ({
    ...baseCaseEvidence(ambiguityMultiTurnCase),
    status: "failed",
    error: error instanceof Error ? error.message : String(error),
  }));
  evidence.multiTurnCases.push(ambiguityResult);
  console.log(`[assistant-coverage] multi-turn 3/${totalMultiTurnCases}: ${ambiguityResult.status}${ambiguityResult.error ? ` - ${ambiguityResult.error.split(/\r?\n/)[0]}` : ""}`);

  console.log(`[assistant-coverage] multi-turn 4/${totalMultiTurnCases}: ${ordinalMultiTurnCase.path} ${ordinalMultiTurnCase.label}`);
  const ordinalResult = await runGenericOrdinalReferenceCase(page, network, ordinalMultiTurnCase).catch((error) => ({
    ...baseCaseEvidence(ordinalMultiTurnCase),
    status: "failed",
    error: error instanceof Error ? error.message : String(error),
  }));
  evidence.multiTurnCases.push(ordinalResult);
  console.log(`[assistant-coverage] multi-turn 4/${totalMultiTurnCases}: ${ordinalResult.status}${ordinalResult.error ? ` - ${ordinalResult.error.split(/\r?\n/)[0]}` : ""}`);

  for (let index = 0; index < genericMultiTurnCases.length; index++) {
    const testCase = genericMultiTurnCases[index];
    console.log(`[assistant-coverage] multi-turn ${index + 5}/${totalMultiTurnCases}: ${testCase.path} ${testCase.label}`);
    const result = await runGenericEntityReferenceCase(page, network, testCase).catch((error) => ({
      ...baseCaseEvidence(testCase),
      status: "failed",
      error: error instanceof Error ? error.message : String(error),
    }));
    evidence.multiTurnCases.push(result);
    console.log(`[assistant-coverage] multi-turn ${index + 5}/${totalMultiTurnCases}: ${result.status}${result.error ? ` - ${result.error.split(/\r?\n/)[0]}` : ""}`);
  }

  if (!onlyMultiTurn) {
    const actionCases = buildSafeActionCases(seededMetadata);
    for (let index = 0; index < actionCases.length; index++) {
      const testCase = actionCases[index];
      console.log(`[assistant-coverage] action ${index + 1}/${actionCases.length}: ${testCase.path} ${testCase.action}`);
      const result = await runAssistantActionCase(page, network, testCase).catch((error) => ({
        ...baseCaseEvidence(testCase),
        action: testCase.action,
        resource: testCase.resource,
        status: "failed",
        error: error instanceof Error ? error.message : String(error),
      }));
      evidence.actionCases.push(result);
      console.log(`[assistant-coverage] action ${index + 1}/${actionCases.length}: ${result.status}${result.error ? ` - ${result.error.split(/\r?\n/)[0]}` : ""}`);
    }

    let mutationIndex = 0;
    const expectedMutationCount = 16;
    const mutationGroups = [
    {
      label: "quality rules save/enable/disable",
      run: () => runQualityRuleMutationCases(page, network),
      fallback: {
        id: "mutation:/quality-rules:save-enable-disable",
        kind: "mutation",
        label: "Quality rule save/enable/disable",
        path: "/quality-rules",
        action: "save-enable-disable",
      },
    },
    {
      label: "datasources save/testCurrent/test",
      run: () => runDatasourceMutationCases(page, network, seededMetadata),
      fallback: {
        id: "mutation:/datasources:save-test",
        kind: "mutation",
        label: "Datasource save/testCurrent/test",
        path: "/datasources",
        action: "save-test",
      },
    },
    {
      label: "models save/syncSelected/rebuildIndex",
      run: () => runModelMutationCases(page, network, seededMetadata),
      fallback: {
        id: "mutation:/models:save-syncSelected-rebuildIndex",
        kind: "mutation",
        label: "Model save/syncSelected/rebuildIndex",
        path: "/models",
        action: "save-syncSelected-rebuildIndex",
      },
    },
    {
      label: "field mapping rule save",
      run: () => runFieldMappingRuleMutationCases(page, network),
      fallback: {
        id: "mutation:/field-mapping-rules:save",
        kind: "mutation",
        label: "Field mapping rule save",
        path: "/field-mapping-rules",
        action: "save",
      },
    },
    {
      label: "script environments save/enable/disable/refresh",
      run: () => runScriptEnvironmentMutationCases(page, network),
      fallback: {
        id: "mutation:/script-environments:save-enable-disable-refresh",
        kind: "mutation",
        label: "Script environment save/enable/disable/refresh",
        path: "/script-environments",
        action: "save-enable-disable-refresh",
      },
    },
    ];
    for (const group of mutationGroups) {
      const results = await group.run().catch((error) => ([{
        ...group.fallback,
        status: "failed",
        error: error instanceof Error ? error.message : String(error),
      }]));
      for (const result of results) {
        mutationIndex += 1;
        evidence.mutationCases.push(result);
        console.log(`[assistant-coverage] mutation ${mutationIndex}/${expectedMutationCount}: ${result.path} ${result.action} ${result.status}${result.error ? ` - ${result.error.split(/\r?\n/)[0]}` : ""}`);
      }
    }

    mutationIndex += 1;
    console.log(`[assistant-coverage] mutation ${mutationIndex}/${expectedMutationCount}: /notifications markRead`);
    const notificationMarkReadResult = await runNotificationMarkReadMutationCase(page, network).catch((error) => ({
      id: "mutation:/notifications:markRead",
      kind: "mutation",
      label: "Notifications mark read",
      path: "/notifications",
      action: "markRead",
      status: "failed",
      error: error instanceof Error ? error.message : String(error),
    }));
    evidence.mutationCases.push(notificationMarkReadResult);
    console.log(`[assistant-coverage] mutation ${mutationIndex}/${expectedMutationCount}: ${notificationMarkReadResult.status}${notificationMarkReadResult.error ? ` - ${notificationMarkReadResult.error.split(/\r?\n/)[0]}` : ""}`);

    mutationIndex += 1;
    console.log(`[assistant-coverage] mutation ${mutationIndex}/${expectedMutationCount}: /notifications markAllRead`);
    const notificationResult = await runNotificationMutationCase(page, network).catch((error) => ({
      id: "mutation:/notifications:markAllRead",
      kind: "mutation",
      label: "Notifications mark all read",
      path: "/notifications",
      action: "markAllRead",
      status: "failed",
      error: error instanceof Error ? error.message : String(error),
    }));
    evidence.mutationCases.push(notificationResult);
    console.log(`[assistant-coverage] mutation ${mutationIndex}/${expectedMutationCount}: ${notificationResult.status}${notificationResult.error ? ` - ${notificationResult.error.split(/\r?\n/)[0]}` : ""}`);
  } else {
    console.log("[assistant-coverage] action and mutation cases skipped by STUDIO_ASSISTANT_COVERAGE_ONLY_MULTI_TURN=true");
  }

  evidence.gaps.push(
    "发布、触发、调度、订阅轮换等高风险写动作未在本批次强行执行；这些动作需要业务对象 ID 或完整 payload，后续应按模块构造专用种子数据后再覆盖。",
    "本批次对 27 个 UI 顶层功能全部覆盖助手读取链路，对数据源列表->默认选择->当前数据源真实表发现覆盖多轮记忆和误路由断言，对数据源页面选中对象->pageContext.activeObject->真实表发现覆盖真实页面上下文链路，对模型歧义引用覆盖选择控件澄清，并对模型、质量规则、采集任务、数据服务、运行记录覆盖列表记忆->默认选择->这个/当前详情读取链路。",
    "本批次对 8 个非变更动作全部覆盖后端动作网关链路，对质量规则 save/enable/disable、通知单条已读和全部已读覆盖确认控件、后端动作网关和配置的 web 数据库落库。",
  );
  evidence.actionCatalogCoverage = summarizeActionCatalogCoverage(evidence);

  const screenshot = await page.send("Page.captureScreenshot", { format: "png", captureBeyondViewport: true });
  const screenshotPath = path.join(reportDir, "assistant-ui-real-assistive-coverage.png");
  await fs.writeFile(screenshotPath, Buffer.from(screenshot.data, "base64"));
  evidence.screenshotPath = screenshotPath;

  const reportJsonPath = path.join(reportDir, "assistant-assistive-coverage-report.json");
  const reportMdPath = path.join(reportDir, "assistant-assistive-coverage-report.md");
  await fs.writeFile(reportJsonPath, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
  await fs.writeFile(reportMdPath, renderMarkdownReport(evidence), "utf8");

  const meaningfulBrowserErrors = browserErrors.filter((item) => !isIgnorableBrowserError(item));
  if (meaningfulBrowserErrors.length) {
    throw new Error(`Browser console/runtime errors:\n${meaningfulBrowserErrors.join("\n")}`);
  }
  const failed = [...evidence.readCases, ...evidence.multiTurnCases, ...evidence.actionCases, ...evidence.mutationCases].filter((item) => item.status !== "passed");
  if (failed.length) {
    throw new Error(`Assistant assistive coverage failed ${failed.length} case(s). Report: ${reportJsonPath}`);
  }
  console.log(`Assistant assistive coverage passed: ${evidence.readCases.length} read cases, ${evidence.multiTurnCases.length} multi-turn cases, ${evidence.actionCases.length} action cases, ${evidence.mutationCases.length} mutation cases. Report: ${reportMdPath}`);
  page.close();
}

async function runAssistantReadCase(page, network, testCase) {
  const since = network.entries.length;
  await askAssistant(page, testCase.prompt);
  const toolEntry = await network.waitFor((entry) => {
    const request = entry.requestBodyJson || {};
    const response = entry.responseBodyJson || {};
    const data = response.data || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === testCase.expectedInterfaceCode
      && normalizePath(request.params?.path || data.path) === testCase.path
      && data.executedBy === "backend"
      && data.interfaceCode === testCase.expectedInterfaceCode
      && normalizePath(data.path) === testCase.path;
  }, caseTimeoutMs, since);
  await waitForAssistantReady(page, caseTimeoutMs);
  await expandProcessPanel(page).catch(() => undefined);
  const visibleText = await textContent(page);
  assert(!visibleText.includes("studio-assistant-protocol"), `${testCase.path} leaked protocol block in UI.`);
  assert(!visibleText.includes("后端工具网关降级"), `${testCase.path} fell back to frontend compatibility execution.`);
  assert(visibleText.includes("后端工具网关"), `${testCase.path} did not show backend tool gateway evidence.`);
  assert(visibleText.includes("后台结果摘要"), `${testCase.path} did not show backend result summary.`);

  return {
    ...baseCaseEvidence(testCase),
    status: "passed",
    uiEvidence: pickUiEvidence(visibleText, ["后端工具网关", "后台结果摘要", testCase.label]),
    chainEvidence: summarizeToolEntry(toolEntry),
    dbEvidence: {
      checked: false,
      reason: "read-only case; database state was not expected to change",
    },
  };
}

async function runDatasourceMemoryDiscoveryCase(page, network, seed) {
  const testCase = {
    id: "multi-turn:/datasources:selected-discover",
    kind: "multi-turn",
    label: "Datasource selected memory discovers physical tables",
    path: "/datasources",
    expectedInterfaceCode: "studio.feature.action",
    prompts: [
      "帮我查看一下当前可用的数据源。请读取 Datasources /datasources 的列表或概览，必须通过 Studio 助手受控读取接口返回。",
      `现在默认使用 ${seed.datasourceName}`,
      `帮我查看一下当前数据源里名为 ${seed.physicalLocator} 的真实表。`,
    ],
  };
  const listSince = network.entries.length;
  console.log("[assistant-coverage] multi-turn: ask datasource list");
  await askAssistant(page, testCase.prompts[0]);
  const listEntry = await network.waitFor((entry) => {
    const request = entry.requestBodyJson || {};
    const response = entry.responseBodyJson || {};
    const data = response.data || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === "studio.feature.list"
      && normalizePath(request.params?.path || data.path) === "/datasources"
      && data.executedBy === "backend"
      && data.interfaceCode === "studio.feature.list";
  }, caseTimeoutMs, listSince);
  await waitForAssistantReady(page, caseTimeoutMs);
  const listData = listEntry.responseBodyJson?.data?.data;
  const listedItems = extractResponseItems(listData);
  console.log(`[assistant-coverage] multi-turn: datasource list matched ${listedItems.length} item(s)`);
  assert(listedItems.some((item) => String(item?.id) === String(seed.datasourceId) || String(item?.name) === String(seed.datasourceName)),
    `Seeded datasource was not present in assistant datasource list memory: ${JSON.stringify(listData)}`);

  const selectionSince = network.entries.length;
  console.log("[assistant-coverage] multi-turn: select datasource memory");
  await askAssistant(page, testCase.prompts[1]);
  await waitForText(page, "默认使用", caseTimeoutMs);
  await waitForText(page, seed.datasourceName, caseTimeoutMs);
  const selectionCalls = network.entries.slice(selectionSince);
  assert(!selectionCalls.some(isModelAssistantToolCall), "Datasource selection unexpectedly called /models.");

  const discoverSince = network.entries.length;
  console.log("[assistant-coverage] multi-turn: ask selected datasource physical tables");
  await askAssistant(page, testCase.prompts[2]);
  const discoverEntry = await network.waitFor((entry) => {
    const request = entry.requestBodyJson || {};
    const response = entry.responseBodyJson || {};
    const data = response.data || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === "studio.feature.action"
      && normalizePath(request.params?.path || data.path) === "/datasources"
      && request.params?.action === "discover"
      && String(request.params?.id || request.params?.datasourceId || "") === String(seed.datasourceId)
      && String(request.params?.keyword || "") === String(seed.physicalLocator)
      && data.executedBy === "backend"
      && data.interfaceCode === "studio.feature.action"
      && normalizePath(data.path) === "/datasources"
      && data.action === "discover";
  }, caseTimeoutMs, discoverSince);
  console.log("[assistant-coverage] multi-turn: physical discovery matched");
  await waitForAssistantReady(page, caseTimeoutMs);
  await expandProcessPanel(page).catch(() => undefined);
  const discoverCalls = network.entries.slice(discoverSince);
  assert(!discoverCalls.some(isModelAssistantToolCall), "Physical table discovery misrouted to /models.");
  assert(!discoverCalls.some((entry) => {
    const request = entry.requestBodyJson || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === "studio.feature.list"
      && normalizePath(request.params?.path) === "/datasources";
  }), "Physical table discovery re-read /datasources list instead of using selected datasource memory.");

  const resultData = discoverEntry.responseBodyJson?.data?.data;
  assertCleanActionResultData(resultData, "/datasources:discover multi-turn");
  const discoveredItems = extractResponseItems(resultData);
  assert(discoveredItems.some((item) => item?.name === seed.physicalLocator || item?.physicalLocator === seed.physicalLocator),
    `/datasources:discover did not return seeded physical table ${seed.physicalLocator}: ${JSON.stringify(resultData)}`);
  const visibleText = await textContent(page);
  assert(visibleText.includes("真实表发现") || visibleText.includes("物理表"), "UI did not render physical table discovery wording.");
  assert(visibleText.includes(seed.physicalLocator), "UI did not render the seeded physical table name.");

  return {
    ...baseCaseEvidence({
      ...testCase,
      prompt: testCase.prompts.join(" -> "),
    }),
    status: "passed",
    prompts: testCase.prompts,
    uiEvidence: pickUiEvidence(visibleText, ["默认使用", "真实表发现", seed.physicalLocator]),
    chainEvidence: {
      datasourceList: summarizeToolEntry(listEntry),
      datasourceSelection: {
        checked: true,
        assertion: "selection updated frontend assistant memory without calling /models",
      },
      physicalDiscovery: summarizeToolEntry(discoverEntry),
      negativeAssertions: {
        noModelsCallAfterDiscoveryPrompt: true,
        noDatasourceListRereadAfterDiscoveryPrompt: true,
      },
    },
    dbEvidence: {
      checked: true,
      table: "datasource_definition,data_model,physical mysql table",
      seededDatasourceId: seed.datasourceId,
      seededDatasourceName: seed.datasourceName,
      seededPhysicalLocator: seed.physicalLocator,
      physicalTableRows: seed.physicalTableRows,
      assertion: "seeded datasource and physical table were visible through the Web assistant controlled discovery flow",
    },
  };
}

async function runDatasourcePageContextDiscoveryCase(page, network, seed) {
  const testCase = {
    id: "multi-turn:/datasources:page-context-discover",
    kind: "multi-turn",
    label: "Datasource page context active object discovers physical tables",
    path: "/datasources",
    expectedInterfaceCode: "studio.feature.action",
    prompt: `帮我查看当前页面选中的数据源里有哪些真实表，优先使用页面上下文，不要重新读取数据源列表，也不要给发现动作附加分页参数。`,
  };

  await page.send("Page.navigate", { url: `${appBaseUrl}datasources` });
  await waitForPageLoad(page);
  await waitForText(page, seed.datasourceName, caseTimeoutMs);
  await clickElementByText(page, "tr", seed.datasourceName);
  await ensureAssistantOpen(page);

  const since = network.entries.length;
  await askAssistant(page, testCase.prompt);
  const chatEntry = await network.waitFor((entry) => {
    const request = entry.requestBodyJson || {};
    const pageContext = request.context?.pageContext || {};
    const activeObject = pageContext.activeObject || {};
    return entry.url.includes("/assistant/chat/stream")
      && pageContext.source === "datasources-view"
      && normalizePath(pageContext.path) === "/datasources"
      && String(activeObject.id || "") === String(seed.datasourceId);
  }, caseTimeoutMs, since);
  const discoverEntry = await network.waitFor((entry) => {
    const request = entry.requestBodyJson || {};
    const response = entry.responseBodyJson || {};
    const data = response.data || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === "studio.feature.action"
      && normalizePath(request.params?.path || data.path) === "/datasources"
      && request.params?.action === "discover"
      && String(request.params?.id || request.params?.datasourceId || "") === String(seed.datasourceId)
      && data.executedBy === "backend"
      && data.interfaceCode === "studio.feature.action"
      && normalizePath(data.path) === "/datasources"
      && data.action === "discover";
  }, caseTimeoutMs, since);
  await waitForAssistantReady(page, caseTimeoutMs);
  await expandProcessPanel(page).catch(() => undefined);

  const promptCalls = network.entries.slice(since);
  assert(!promptCalls.some(isModelAssistantToolCall), "Page-context datasource discovery misrouted to /models.");
  assert(!promptCalls.some((entry) => {
    const request = entry.requestBodyJson || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === "studio.feature.list"
      && normalizePath(request.params?.path) === "/datasources";
  }), "Page-context datasource discovery re-read /datasources instead of using pageContext.activeObject.");

  const requestParams = discoverEntry.requestBodyJson?.params || {};
  assert(!Object.prototype.hasOwnProperty.call(requestParams, "pageNo"), "Page-context discovery should not add pageNo when the LLM asks for all current datasource tables.");
  assert(!Object.prototype.hasOwnProperty.call(requestParams, "pageSize"), "Page-context discovery should not add pageSize when the LLM asks for all current datasource tables.");

  const resultData = discoverEntry.responseBodyJson?.data?.data;
  assertCleanActionResultData(resultData, "/datasources:discover page-context");
  const discoveredItems = extractResponseItems(resultData);
  assert(discoveredItems.some((item) => item?.name === seed.physicalLocator || item?.physicalLocator === seed.physicalLocator),
    `/datasources:discover page-context did not return seeded physical table ${seed.physicalLocator}: ${JSON.stringify(resultData)}`);
  const visibleText = await textContent(page);
  assert(!visibleText.includes("studio-assistant-protocol"), "Page-context datasource discovery leaked protocol block in UI.");
  assert(visibleText.includes(seed.physicalLocator), "Page-context discovery UI did not render the seeded physical table name.");

  const requestContext = chatEntry.requestBodyJson?.context || {};
  const pageContext = requestContext.pageContext || {};
  return {
    ...baseCaseEvidence(testCase),
    status: "passed",
    uiEvidence: pickUiEvidence(visibleText, ["当前页面", "真实表", seed.physicalLocator]),
    chainEvidence: {
      chatContext: {
        requestUrl: trimLocalUrl(chatEntry.url),
        pageContextSource: pageContext.source,
        pageContextPath: pageContext.path,
        activeObject: redactSensitiveResultData(pageContext.activeObject),
        visibleObjectCount: Array.isArray(pageContext.visibleObjects) ? pageContext.visibleObjects.length : 0,
      },
      physicalDiscovery: summarizeToolEntry(discoverEntry),
      negativeAssertions: {
        noModelsCallAfterPageContextPrompt: true,
        noDatasourceListRereadAfterPageContextPrompt: true,
        noPaginationParamsOnDiscover: true,
      },
    },
    dbEvidence: {
      checked: true,
      table: "datasource_definition,data_model,physical mysql table",
      seededDatasourceId: seed.datasourceId,
      seededDatasourceName: seed.datasourceName,
      seededPhysicalLocator: seed.physicalLocator,
      assertion: "real datasource page row selection was passed to the LLM as pageContext.activeObject and used by backend controlled discovery",
    },
  };
}

function buildGenericEntityReferenceCases(seed) {
  return [
    {
      id: "multi-turn:/models:selected-detail",
      kind: "multi-turn",
      label: "Model selected memory resolves this model detail",
      path: "/models",
      listLabel: "Models",
      memoryLabel: "模型",
      expectedId: seed.modelId,
      expectedName: seed.modelName,
      listPrompt: "请读取 Models /models 的列表或概览，必须通过 Studio 助手受控读取接口返回。",
      selectPrompt: `现在默认使用模型 ${seed.modelName}`,
      detailPrompt: "帮我查看这个模型的详情。",
      forbiddenPathsAfterDetail: ["/datasources"],
      dbEvidence: seedActionDbEvidence(seed, "seeded model was selected from assistant list memory and read by id through studio.feature.get"),
    },
    {
      id: "multi-turn:/quality-rules:selected-detail",
      kind: "multi-turn",
      label: "Quality rule selected memory resolves this rule detail",
      path: "/quality-rules",
      listLabel: "Quality rules",
      memoryLabel: "质量规则",
      expectedId: seed.qualityRuleId,
      expectedName: seed.qualityRuleName,
      listPrompt: "请读取 Quality rules /quality-rules 的列表或概览，必须通过 Studio 助手受控读取接口返回。",
      selectPrompt: `现在默认使用质量规则 ${seed.qualityRuleName}`,
      detailPrompt: "帮我查看这个质量规则的详情。",
      forbiddenPathsAfterDetail: ["/datasources", "/models"],
      dbEvidence: seedActionDbEvidence(seed, "seeded quality rule was selected from assistant list memory and read by id through studio.feature.get"),
    },
    {
      id: "multi-turn:/collection-tasks:selected-detail",
      kind: "multi-turn",
      label: "Collection task selected memory resolves this task detail",
      path: "/collection-tasks",
      listLabel: "Collection tasks",
      memoryLabel: "采集任务",
      expectedId: seed.collectionTaskId,
      expectedName: seed.collectionTaskName,
      listPrompt: "请读取 Collection tasks /collection-tasks 的列表或概览，必须通过 Studio 助手受控读取接口返回。",
      selectPrompt: `现在默认使用采集任务 ${seed.collectionTaskName}`,
      detailPrompt: "帮我查看这个采集任务的详情。",
      forbiddenPathsAfterDetail: ["/datasources", "/models"],
      dbEvidence: seedActionDbEvidence(seed, "seeded collection task was selected from assistant list memory and read by id through studio.feature.get"),
    },
    {
      id: "multi-turn:/data-services:selected-detail",
      kind: "multi-turn",
      label: "Data service selected memory resolves this service detail",
      path: "/data-services",
      listLabel: "Data services",
      memoryLabel: "数据服务",
      expectedId: seed.dataServiceId,
      expectedName: seed.dataServiceName,
      listPrompt: "请读取 Data services /data-services 的列表或概览，必须通过 Studio 助手受控读取接口返回。",
      selectPrompt: `现在默认使用数据服务 ${seed.dataServiceName}`,
      detailPrompt: "帮我查看这个数据服务的详情。",
      forbiddenPathsAfterDetail: ["/datasources", "/models"],
      dbEvidence: seedActionDbEvidence(seed, "seeded data service was selected from assistant list memory and read by id through studio.feature.get"),
    },
    {
      id: "multi-turn:/runs:selected-detail",
      kind: "multi-turn",
      label: "Run record selected memory resolves this run detail",
      path: "/runs",
      listLabel: "Runs",
      memoryLabel: "运行记录",
      expectedId: seed.runRecordId,
      expectedName: seed.runRecordId,
      listPrompt: "请读取 Runs /runs 的列表或概览，必须通过 Studio 助手受控读取接口返回。",
      selectPrompt: `现在默认使用运行记录 ${seed.runRecordId}`,
      detailPrompt: "帮我查看这个运行记录的详情。",
      forbiddenPathsAfterDetail: ["/datasources", "/models"],
      dbEvidence: seedActionDbEvidence(seed, "seeded run record was selected from assistant list memory and read by id through studio.feature.get"),
    },
  ];
}

function buildGenericAmbiguityReferenceCase() {
  return {
    id: "multi-turn:/models:ambiguous-selection-control",
    kind: "multi-turn",
    label: "Ambiguous model reference asks for selection",
    path: "/models",
    listPrompt: "请读取 Models /models 的列表或概览，必须通过 Studio 助手受控读取接口返回。",
    ambiguousPrompt: "现在默认使用这个模型",
    expectedInterfaceCode: "studio.feature.list",
  };
}

function buildGenericOrdinalReferenceCase() {
  return {
    id: "multi-turn:/models:ordinal-detail",
    kind: "multi-turn",
    label: "Model ordinal reference resolves first model detail",
    path: "/models",
    memoryLabel: "模型",
    listPrompt: "请读取 Models /models 的列表或概览，必须通过 Studio 助手受控读取接口返回。",
    detailPrompt: "帮我查看第 1 个模型的详情。",
    expectedInterfaceCode: "studio.feature.get",
    forbiddenPathsAfterDetail: ["/datasources"],
  };
}

async function runGenericAmbiguityReferenceCase(page, network, testCase) {
  const prompts = [testCase.listPrompt, testCase.ambiguousPrompt];
  const listSince = network.entries.length;
  await askAssistant(page, testCase.listPrompt);
  const listEntry = await network.waitFor((entry) => {
    const request = entry.requestBodyJson || {};
    const response = entry.responseBodyJson || {};
    const data = response.data || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === "studio.feature.list"
      && normalizePath(request.params?.path || data.path) === testCase.path
      && data.executedBy === "backend"
      && data.interfaceCode === "studio.feature.list"
      && normalizePath(data.path) === testCase.path;
  }, caseTimeoutMs, listSince);
  await waitForAssistantReady(page, caseTimeoutMs);
  const listedItems = extractResponseItems(listEntry.responseBodyJson?.data?.data);
  assert(listedItems.length > 1, `${testCase.path} ambiguity case requires multiple candidates, got ${listedItems.length}.`);

  const ambiguitySince = network.entries.length;
  await askAssistant(page, testCase.ambiguousPrompt);
  await waitForText(page, "我能定位到最近的模型列表", caseTimeoutMs);
  await waitForText(page, "不能唯一确定", caseTimeoutMs);
  await waitForText(page, "请选择本轮默认使用的模型", caseTimeoutMs);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-root"] select, [data-testid="studio-assistant-root"] .el-select')`, caseTimeoutMs);
  await waitForAssistantReady(page, caseTimeoutMs);
  const ambiguityCalls = network.entries.slice(ambiguitySince);
  assert(!ambiguityCalls.some(isAssistantToolExecuteCall), `${testCase.path} ambiguous reference should ask for a selection without backend tool execution.`);
  const visibleText = await textContent(page);

  return {
    ...baseCaseEvidence({
      ...testCase,
      prompt: prompts.join(" -> "),
    }),
    status: "passed",
    prompts,
    uiEvidence: pickUiEvidence(visibleText, ["我能定位到最近的模型列表", "请选择本轮默认使用的模型"]),
    chainEvidence: {
      list: summarizeToolEntry(listEntry),
      clarification: {
        checked: true,
        action: "selection-control",
        assertion: "ambiguous model reference rendered a selection control without guessing or executing a backend tool",
      },
      negativeAssertions: {
        noBackendToolExecutionAfterAmbiguousPrompt: true,
      },
    },
    dbEvidence: {
      checked: true,
      table: "data_model",
      candidateCount: listedItems.length,
      assertion: "multiple model candidates existed and the assistant asked the user to choose one",
    },
  };
}

async function runGenericOrdinalReferenceCase(page, network, testCase) {
  const prompts = [testCase.listPrompt, testCase.detailPrompt];
  const listSince = network.entries.length;
  await askAssistant(page, testCase.listPrompt);
  const listEntry = await network.waitFor((entry) => {
    const request = entry.requestBodyJson || {};
    const response = entry.responseBodyJson || {};
    const data = response.data || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === "studio.feature.list"
      && normalizePath(request.params?.path || data.path) === testCase.path
      && data.executedBy === "backend"
      && data.interfaceCode === "studio.feature.list"
      && normalizePath(data.path) === testCase.path;
  }, caseTimeoutMs, listSince);
  await waitForAssistantReady(page, caseTimeoutMs);
  const listedItems = extractResponseItems(listEntry.responseBodyJson?.data?.data);
  assert(listedItems.length > 0, `${testCase.path} ordinal case requires at least one list item.`);
  const target = listedItems.find((item) => entityItemId(item));
  assert(target, `${testCase.path} ordinal case could not find an item with id: ${JSON.stringify(listEntry.responseBodyJson?.data?.data)}`);
  const targetId = entityItemId(target);
  const targetName = entityItemName(target);

  const detailSince = network.entries.length;
  await askAssistant(page, testCase.detailPrompt);
  const detailEntry = await network.waitFor((entry) => {
    const request = entry.requestBodyJson || {};
    const response = entry.responseBodyJson || {};
    const data = response.data || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === "studio.feature.get"
      && normalizePath(request.params?.path || data.path) === testCase.path
      && String(request.params?.id || request.params?.recordId || request.params?.entityId || "") === String(targetId)
      && data.executedBy === "backend"
      && data.interfaceCode === "studio.feature.get"
      && normalizePath(data.path) === testCase.path;
  }, caseTimeoutMs, detailSince);
  await waitForAssistantReady(page, caseTimeoutMs);
  await expandProcessPanel(page).catch(() => undefined);

  const detailCalls = network.entries.slice(detailSince);
  assert(!detailCalls.some((entry) => {
    const request = entry.requestBodyJson || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === "studio.feature.list"
      && normalizePath(request.params?.path) === testCase.path;
  }), `${testCase.path} ordinal detail prompt re-read the list instead of using recent entity memory.`);
  for (const forbiddenPath of testCase.forbiddenPathsAfterDetail || []) {
    assert(!detailCalls.some((entry) => isAssistantToolCallForPath(entry, forbiddenPath)),
      `${testCase.path} ordinal detail prompt unexpectedly called ${forbiddenPath}.`);
  }
  const visibleText = await textContent(page);
  assert(!visibleText.includes("studio-assistant-protocol"), `${testCase.path} leaked protocol block in UI.`);
  assert(!visibleText.includes("后端工具网关降级"), `${testCase.path} fell back to frontend compatibility execution.`);
  assert(visibleText.includes("后端工具网关"), `${testCase.path} did not show backend tool gateway evidence.`);
  assert(visibleText.includes(targetName) || visibleText.includes(String(targetId)),
    `${testCase.path} UI did not render ordinal target evidence ${targetName || targetId}.`);

  return {
    ...baseCaseEvidence({
      ...testCase,
      prompt: prompts.join(" -> "),
    }),
    status: "passed",
    prompts,
    selectedEntity: {
      ordinal: 1,
      id: String(targetId),
      name: targetName,
    },
    uiEvidence: pickUiEvidence(visibleText, [targetName, String(targetId), "后端工具网关"]),
    chainEvidence: {
      list: summarizeToolEntry(listEntry),
      detail: summarizeToolEntry(detailEntry),
      ordinalResolution: {
        checked: true,
        ordinal: 1,
        resolvedId: String(targetId),
        assertion: "第 1 个 resolved to the first recent list entity without an explicit selection step",
      },
      negativeAssertions: {
        noSamePathListRereadAfterDetailPrompt: true,
        noForbiddenPathCallsAfterDetailPrompt: true,
      },
    },
    dbEvidence: {
      checked: true,
      table: "data_model",
      selectedModelId: String(targetId),
      selectedModelName: targetName,
      assertion: "model ordinal reference was resolved from the Web assistant recent list memory and read by id",
    },
  };
}

async function runGenericEntityReferenceCase(page, network, testCase) {
  const prompts = [testCase.listPrompt, testCase.selectPrompt, testCase.detailPrompt];
  const listSince = network.entries.length;
  await askAssistant(page, testCase.listPrompt);
  const listEntry = await network.waitFor((entry) => {
    const request = entry.requestBodyJson || {};
    const response = entry.responseBodyJson || {};
    const data = response.data || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === "studio.feature.list"
      && normalizePath(request.params?.path || data.path) === testCase.path
      && data.executedBy === "backend"
      && data.interfaceCode === "studio.feature.list"
      && normalizePath(data.path) === testCase.path;
  }, caseTimeoutMs, listSince);
  await waitForAssistantReady(page, caseTimeoutMs);
  const listedItems = extractResponseItems(listEntry.responseBodyJson?.data?.data);
  const target = findListedEntity(listedItems, testCase);
  assert(target, `${testCase.path} list did not contain expected entity ${testCase.expectedId || testCase.expectedName}: ${JSON.stringify(listEntry.responseBodyJson?.data?.data)}`);
  const targetId = entityItemId(target);
  const targetName = entityItemName(target);
  assert(String(targetId) === String(testCase.expectedId), `${testCase.path} expected selected id ${testCase.expectedId}, got ${targetId}`);

  const selectionSince = network.entries.length;
  await askAssistant(page, testCase.selectPrompt);
  await waitForText(page, `已设置当前${testCase.memoryLabel}`, caseTimeoutMs);
  await waitForText(page, targetName, caseTimeoutMs);
  const selectionCalls = network.entries.slice(selectionSince);
  assert(!selectionCalls.some(isAssistantToolExecuteCall), `${testCase.path} selection should update frontend memory without backend tool execution.`);

  const detailSince = network.entries.length;
  await askAssistant(page, testCase.detailPrompt);
  const detailEntry = await network.waitFor((entry) => {
    const request = entry.requestBodyJson || {};
    const response = entry.responseBodyJson || {};
    const data = response.data || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === "studio.feature.get"
      && normalizePath(request.params?.path || data.path) === testCase.path
      && String(request.params?.id || request.params?.recordId || request.params?.entityId || "") === String(testCase.expectedId)
      && data.executedBy === "backend"
      && data.interfaceCode === "studio.feature.get"
      && normalizePath(data.path) === testCase.path;
  }, caseTimeoutMs, detailSince);
  await waitForAssistantReady(page, caseTimeoutMs);
  await expandProcessPanel(page).catch(() => undefined);

  const detailCalls = network.entries.slice(detailSince);
  assert(!detailCalls.some((entry) => {
    const request = entry.requestBodyJson || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === "studio.feature.list"
      && normalizePath(request.params?.path) === testCase.path;
  }), `${testCase.path} detail prompt re-read the list instead of using selected entity memory.`);
  for (const forbiddenPath of testCase.forbiddenPathsAfterDetail || []) {
    assert(!detailCalls.some((entry) => isAssistantToolCallForPath(entry, forbiddenPath)),
      `${testCase.path} detail prompt unexpectedly called ${forbiddenPath}.`);
  }

  const visibleText = await textContent(page);
  assert(!visibleText.includes("studio-assistant-protocol"), `${testCase.path} leaked protocol block in UI.`);
  assert(!visibleText.includes("后端工具网关降级"), `${testCase.path} fell back to frontend compatibility execution.`);
  assert(visibleText.includes("后端工具网关"), `${testCase.path} did not show backend tool gateway evidence.`);
  assert(visibleText.includes(targetName) || visibleText.includes(String(testCase.expectedId)),
    `${testCase.path} UI did not render selected entity evidence ${targetName || testCase.expectedId}.`);

  return {
    ...baseCaseEvidence({
      ...testCase,
      prompt: prompts.join(" -> "),
    }),
    status: "passed",
    prompts,
    selectedEntity: {
      id: String(targetId),
      name: targetName,
    },
    uiEvidence: pickUiEvidence(visibleText, [`已设置当前${testCase.memoryLabel}`, targetName, "后端工具网关"]),
    chainEvidence: {
      list: summarizeToolEntry(listEntry),
      selection: {
        checked: true,
        assertion: "selection updated frontend assistant memory without backend tool execution",
      },
      detail: summarizeToolEntry(detailEntry),
      negativeAssertions: {
        noSamePathListRereadAfterDetailPrompt: true,
        noForbiddenPathCallsAfterDetailPrompt: true,
      },
    },
    dbEvidence: testCase.dbEvidence || {
      checked: true,
      assertion: "selected entity was visible through the Web assistant controlled list/detail flow",
    },
  };
}

function buildSafeActionCases(seed) {
  const seededDatasourceId = seed?.datasourceId;
  const seededModelId = seed?.modelId;
  const seededRuleId = seed?.qualityRuleId;
  const collectionPreviewPayload = {
    name: `AI助手覆盖采集预览 ${seed?.suffix || Date.now()}`,
    sourceBindings: [
      {
        sourceAlias: "src",
        datasourceId: seededDatasourceId,
        modelId: seededModelId,
      },
    ],
    targetBinding: {
      datasourceId: seededDatasourceId,
      modelId: seededModelId,
    },
    fieldMappings: [
      {
        sourceAlias: "src",
        sourceField: "id",
        targetField: "id",
      },
      {
        sourceAlias: "src",
        sourceField: "name",
        targetField: "name",
      },
    ],
    executionOptions: {},
  };
  const qualityTaskPreviewPayload = {
    taskName: `AI助手覆盖质量预览 ${seed?.suffix || Date.now()}`,
    taskCode: `assistant_coverage_quality_task_${seed?.suffix || Date.now()}`,
    ruleId: seededRuleId,
    granularity: "TABLE",
    datasourceId: seededDatasourceId,
    modelId: seededModelId,
    parameterBindings: [
      {
        paramOrder: 1,
        paramName: "Schema_Table",
        paramType: "TABLE",
        paramValue: seed?.physicalLocator,
        editable: false,
      },
    ],
    alertConfigs: [],
  };
  return [
    {
      id: "action:/quality-rules:parse",
      kind: "action",
      label: "Quality rule parse",
      path: "/quality-rules",
      action: "parse",
      expectedInterfaceCode: "studio.feature.action",
      expectedMutation: false,
      expectedPayload: {
        granularity: "TABLE",
        logicSql: "select count(*) as total_count from ${Schema_Table}",
      },
      assertResult: (result) => {
        assertArrayContainsObjectValue(result?.inputParams, "paramName", "Schema_Table", "/quality-rules:parse inputParams");
        assertArrayContainsObjectValue(result?.outputParams, "resultField", "total_count", "/quality-rules:parse outputParams");
      },
      prompt: "请通过 Studio 助手受控动作接口执行 /quality-rules 的 parse 动作，payload 为 {\"granularity\":\"TABLE\",\"logicSql\":\"select count(*) as total_count from ${Schema_Table}\"}。必须走后端动作网关。",
    },
    {
      id: "action:/quality-rules:validate",
      kind: "action",
      label: "Quality rule validate",
      path: "/quality-rules",
      action: "validate",
      expectedInterfaceCode: "studio.feature.action",
      expectedMutation: false,
      expectedPayload: {
        granularity: "COLUMN",
        logicSql: "select count(*) as null_count from ${Schema_Table} where ${Column} is null",
      },
      assertResult: (result) => {
        assert(result?.valid === true, `/quality-rules:validate expected valid=true, got ${JSON.stringify(result)}`);
      },
      prompt: "请通过 Studio 助手受控动作接口执行 /quality-rules 的 validate 动作，payload 为 {\"granularity\":\"COLUMN\",\"logicSql\":\"select count(*) as null_count from ${Schema_Table} where ${Column} is null\"}。必须走后端动作网关。",
    },
    {
      id: "action:/datasources:discover",
      kind: "action",
      label: "Datasource discover physical tables",
      path: "/datasources",
      action: "discover",
      expectedInterfaceCode: "studio.feature.action",
      expectedMutation: false,
      expectedParams: {
        id: seededDatasourceId,
        keyword: seed?.physicalLocator,
        pageNo: 1,
        pageSize: 5,
      },
      prompt: `请通过 Studio 助手受控动作接口执行 /datasources 的 discover 动作，参数为 ${JSON.stringify({
        id: seededDatasourceId,
        keyword: seed?.physicalLocator,
        pageNo: 1,
        pageSize: 5,
      })}。必须走后端动作网关。`,
      assertResult: (result) => {
        assert(Array.isArray(result?.models), `/datasources:discover expected models array, got ${JSON.stringify(result)}`);
        assert(result.models.some((model) => model?.name === seed?.physicalLocator || model?.physicalLocator === seed?.physicalLocator),
          `/datasources:discover did not return seeded physical table ${seed?.physicalLocator}: ${JSON.stringify(result)}`);
      },
      dbEvidence: seedActionDbEvidence(seed, "seeded datasource metadata isolates datasource discovery without saving datasource changes"),
    },
    {
      id: "action:/data-services:resolveFields",
      kind: "action",
      label: "Data service resolve fields",
      path: "/data-services",
      action: "resolveFields",
      expectedInterfaceCode: "studio.feature.action",
      expectedMutation: false,
      expectedPayload: {
        sourceType: "TABLE",
        datasourceId: seededDatasourceId,
        modelId: seededModelId,
      },
      assertResult: (result) => {
        assertFieldNames(result?.fields, seed?.columns, "/data-services:resolveFields fields");
      },
      prompt: `请通过 Studio 助手受控动作接口执行 /data-services 的 resolveFields 动作，payload 为 ${JSON.stringify({
        sourceType: "TABLE",
        datasourceId: seededDatasourceId,
        modelId: seededModelId,
      })}。必须走后端动作网关。`,
      dbEvidence: seedActionDbEvidence(seed, "seeded datasource/model metadata supplies fields without changing business state"),
    },
    {
      id: "action:/data-ingestion-services:resolveFields",
      kind: "action",
      label: "Data ingestion service resolve fields",
      path: "/data-ingestion-services",
      action: "resolveFields",
      expectedInterfaceCode: "studio.feature.action",
      expectedMutation: false,
      expectedPayload: {
        datasourceId: seededDatasourceId,
        modelId: seededModelId,
      },
      assertResult: (result) => {
        assertFieldNames(result?.fields, seed?.columns, "/data-ingestion-services:resolveFields fields");
      },
      prompt: `请通过 Studio 助手受控动作接口执行 /data-ingestion-services 的 resolveFields 动作，payload 为 ${JSON.stringify({
        datasourceId: seededDatasourceId,
        modelId: seededModelId,
      })}。必须走后端动作网关。`,
      dbEvidence: seedActionDbEvidence(seed, "seeded datasource/model metadata supplies ingestion field mappings without changing business state"),
    },
    {
      id: "action:/collection-tasks:preview",
      kind: "action",
      label: "Collection task preview",
      path: "/collection-tasks",
      action: "preview",
      expectedInterfaceCode: "studio.feature.action",
      expectedMutation: false,
      expectedPayload: {
        name: collectionPreviewPayload.name,
      },
      assertResult: (result) => {
        assert(result?.reader && result?.writer, `/collection-tasks:preview expected reader/writer config, got ${JSON.stringify(result)}`);
        assertResultContains(result, seed?.physicalLocator, "/collection-tasks:preview physical table");
      },
      prompt: `请通过 Studio 助手受控动作接口执行 /collection-tasks 的 preview 动作，payload 为 ${JSON.stringify(collectionPreviewPayload)}。必须走后端动作网关。`,
      dbEvidence: seedActionDbEvidence(seed, "seeded datasource/model metadata lets preview assemble reader and writer config without saving a task"),
    },
    {
      id: "action:/quality-tasks:preview",
      kind: "action",
      label: "Quality task preview",
      path: "/quality-tasks",
      action: "preview",
      expectedInterfaceCode: "studio.feature.action",
      expectedMutation: false,
      expectedPayload: {
        taskCode: qualityTaskPreviewPayload.taskCode,
        ruleId: seededRuleId,
        datasourceId: seededDatasourceId,
        modelId: seededModelId,
      },
      assertResult: (result) => {
        assert(String(result?.resolvedSql || "").includes(seed?.physicalLocator),
          `/quality-tasks:preview resolvedSql did not include ${seed?.physicalLocator}: ${JSON.stringify(result)}`);
        assert(!String(result?.resolvedSql || "").includes("${"),
          `/quality-tasks:preview resolvedSql still contains placeholder: ${JSON.stringify(result)}`);
      },
      prompt: `请通过 Studio 助手受控动作接口执行 /quality-tasks 的 preview 动作，payload 为 ${JSON.stringify(qualityTaskPreviewPayload)}。必须走后端动作网关。`,
      dbEvidence: seedActionDbEvidence(seed, "seeded quality rule and model resolve SQL preview without saving a quality task"),
    },
    {
      id: "action:/quality-tasks:validate",
      kind: "action",
      label: "Quality task validate",
      path: "/quality-tasks",
      action: "validate",
      expectedInterfaceCode: "studio.feature.action",
      expectedMutation: false,
      expectedPayload: {
        taskCode: qualityTaskPreviewPayload.taskCode,
        ruleId: seededRuleId,
        datasourceId: seededDatasourceId,
        modelId: seededModelId,
      },
      assertResult: (result) => {
        assert(result?.valid === true, `/quality-tasks:validate expected valid=true, got ${JSON.stringify(result)}`);
        assert(String(result?.resolvedSql || "").includes(seed?.physicalLocator),
          `/quality-tasks:validate resolvedSql did not include ${seed?.physicalLocator}: ${JSON.stringify(result)}`);
        assert(Array.isArray(result?.rows) && String(result.rows[0]?.total_count) === String(seed?.physicalTableRows || 1),
          `/quality-tasks:validate did not query seeded table row count: ${JSON.stringify(result)}`);
      },
      prompt: `请通过 Studio 助手受控动作接口执行 /quality-tasks 的 validate 动作，payload 为 ${JSON.stringify(qualityTaskPreviewPayload)}。必须走后端动作网关。`,
      dbEvidence: seedActionDbEvidence(seed, "seeded quality rule and model validate SQL configuration without saving a quality task"),
    },
  ];
}

async function seedAssistantCoverageMetadata(page) {
  const localContext = await evaluate(page, `
    ({
      tenantId: window.localStorage.getItem("studio_current_tenant") || "default",
      projectId: window.localStorage.getItem("studio_current_project") || ""
    })
  `);
  assert(localContext.projectId, "Current Web project id was not found in localStorage.");
  const suffix = `${Date.now()}_${Math.floor(Math.random() * 1000)}`;
  const now = formatMysqlLocalDateTime(new Date());
  const datasourceListTime = "2026-01-01 00:00:00";
  const snowflakeEpoch = 1288834974657n;
  const baseId = ((BigInt(Date.now()) - snowflakeEpoch) << 22n) + BigInt(Math.floor(Math.random() * 1000) * 16);
  const datasourceBaseId = 1700000000000000n
    + BigInt(Date.now() % 1000000000) * 1000n
    + BigInt(Math.floor(Math.random() * 100));
  const datasourceId = String(datasourceBaseId + 1n);
  const modelId = String(baseId + 2n);
  const qualityRuleId = String(baseId + 3n);
  const inputParamId = String(baseId + 4n);
  const outputParamId = String(baseId + 5n);
  const collectionTaskId = String(baseId + 6n);
  const dataServiceId = String(baseId + 7n);
  const runRecordId = String(baseId + 8n);
  const datasourceName = `assistant_coverage_ds_0000_${suffix}`;
  const modelName = `assistant_coverage_model_${suffix}`;
  const physicalLocator = `assistant_coverage_model_${suffix}`;
  const qualityRuleName = `AI助手覆盖预览规则 ${suffix}`;
  const ruleCode = `assistant_coverage_preview_rule_${suffix}`;
  const collectionTaskName = `AI助手覆盖采集任务 ${suffix}`;
  const dataServiceCode = `assistant_coverage_service_${suffix}`;
  const dataServiceName = `AI助手覆盖数据服务 ${suffix}`;
  const columns = [
    { name: "id", type: "bigint", comment: "coverage id" },
    { name: "name", type: "varchar", comment: "coverage name" },
    { name: "amount", type: "decimal", comment: "coverage amount" },
  ];
  const datasourceTechnicalMetadata = {
    ...mysqlConnectionMetadata(),
    columns,
    coverageTable: physicalLocator,
  };
  const quotedPhysicalLocator = quoteMysqlIdentifier(physicalLocator);
  querySql(`
    create table if not exists ${quotedPhysicalLocator} (
      id bigint not null primary key,
      name varchar(128) not null,
      amount decimal(18, 2) not null
    )
  `);
  querySql(`insert into ${quotedPhysicalLocator} (id, name, amount) values (?, ?, ?)`, [
    1,
    `assistant coverage ${suffix}`,
    12.34,
  ]);
  querySql(`
    insert into datasource_definition (
      id, tenant_id, project_id, deleted, created_at, updated_at, name, type_code,
      enabled, executable, connection_fingerprint, connection_status, technical_metadata, business_metadata
    ) values (?, ?, ?, 0, ?, ?, ?, 'mysql8', 1, 1, ?, 'UNKNOWN', ?, ?)
  `, [
    datasourceId,
    localContext.tenantId || "default",
    localContext.projectId,
    datasourceListTime,
    datasourceListTime,
    datasourceName,
    `assistant-coverage-${suffix}`,
    JSON.stringify(datasourceTechnicalMetadata),
    JSON.stringify({ seededBy: "assistant-assistive-coverage" }),
  ]);
  querySql(`
    insert into data_model (
      id, tenant_id, project_id, deleted, created_at, updated_at, datasource_id,
      name, model_kind, physical_locator, technical_metadata, business_metadata
    ) values (?, ?, ?, 0, ?, ?, ?, ?, 'TABLE', ?, ?, ?)
  `, [
    modelId,
    localContext.tenantId || "default",
    localContext.projectId,
    now,
    now,
    datasourceId,
    modelName,
    physicalLocator,
    JSON.stringify({ columns }),
    JSON.stringify({ seededBy: "assistant-assistive-coverage" }),
  ]);
  querySql(`
    insert into quality_rule (
      id, tenant_id, project_id, deleted, created_at, updated_at, created_by,
      rule_name, rule_code, scope_type, rule_dimension, description,
      supported_datasource_types_json, granularity, logic_sql, enabled
    ) values (?, ?, ?, 0, ?, ?, null, ?, ?, 'PROJECT', 'COMPLETENESS', ?, ?, 'TABLE', ?, 1)
  `, [
    qualityRuleId,
    localContext.tenantId || "default",
    localContext.projectId,
    now,
    now,
    qualityRuleName,
    ruleCode,
    "Seeded for assistant quality task preview coverage.",
    JSON.stringify(["mysql8"]),
    "select count(*) as total_count from ${Schema_Table}",
  ]);
  querySql(`
    insert into quality_rule_input_param (
      id, deleted, created_at, updated_at, rule_id, param_order, param_name, param_type, param_meaning
    ) values (?, 0, ?, ?, ?, 1, 'Schema_Table', 'TABLE', '覆盖测试模型表名')
  `, [inputParamId, now, now, qualityRuleId]);
  querySql(`
    insert into quality_rule_output_param (
      id, deleted, created_at, updated_at, rule_id, output_order, result_field, output_type, output_description
    ) values (?, 0, ?, ?, ?, 1, 'total_count', 'NUMBER', '覆盖测试总数')
  `, [outputParamId, now, now, qualityRuleId]);
  const sourceBindings = [
    {
      sourceAlias: "src",
      datasourceId,
      datasourceName,
      datasourceTypeCode: "mysql8",
      modelId,
      modelName,
      modelPhysicalLocator: physicalLocator,
      readerOptions: {},
    },
  ];
  const targetBinding = {
    datasourceId,
    datasourceName,
    datasourceTypeCode: "mysql8",
    modelId,
    modelName,
    modelPhysicalLocator: physicalLocator,
    writerOptions: {},
  };
  const fieldMappings = [
    { sourceAlias: "src", sourceField: "id", targetField: "id" },
    { sourceAlias: "src", sourceField: "name", targetField: "name" },
    { sourceAlias: "src", sourceField: "amount", targetField: "amount" },
  ];
  querySql(`
    insert into collection_task_definition (
      id, tenant_id, project_id, deleted, created_at, updated_at, name, task_type, status,
      source_count, target_datasource_name_snapshot, target_datasource_type_code_snapshot,
      target_model_name_snapshot, target_model_physical_locator_snapshot,
      source_bindings_json, target_binding_json, field_mappings_json, execution_options_json
    ) values (?, ?, ?, 0, ?, ?, ?, 'SINGLE_TABLE', 'DRAFT', 1, ?, 'mysql8', ?, ?, ?, ?, ?, ?)
  `, [
    collectionTaskId,
    localContext.tenantId || "default",
    localContext.projectId,
    now,
    now,
    collectionTaskName,
    datasourceName,
    modelName,
    physicalLocator,
    JSON.stringify(sourceBindings),
    JSON.stringify(targetBinding),
    JSON.stringify(fieldMappings),
    JSON.stringify({ seededBy: "assistant-assistive-coverage" }),
  ]);
  querySql(`
    insert into data_service_definition (
      id, tenant_id, project_id, deleted, created_at, updated_at, service_code, service_name,
      service_type, status, source_type, datasource_id, datasource_name_snapshot, datasource_type_code,
      model_id, model_name_snapshot, model_physical_locator, request_method, response_type,
      endpoint_path, service_key, cache_enabled, token_required, webservice_enabled, webservice_config_json
    ) values (?, ?, ?, 0, ?, ?, ?, ?, 'MODEL_PUBLISH', 'DRAFT', 'TABLE', ?, ?, 'mysql8', ?, ?, ?, 'GET', 'JSON', ?, ?, 0, 1, 0, ?)
  `, [
    dataServiceId,
    localContext.tenantId || "default",
    localContext.projectId,
    now,
    now,
    dataServiceCode,
    dataServiceName,
    datasourceId,
    datasourceName,
    modelId,
    modelName,
    physicalLocator,
    `/openapi/data-services/${dataServiceCode}/assistantCoverageKey${suffix}`,
    `assistantCoverageKey${suffix}`,
    JSON.stringify({ enabled: false }),
  ]);
  querySql(`
    insert into run_record (
      id, tenant_id, project_id, deleted, created_at, updated_at, execution_type,
      collection_task_id, node_code, status, worker_code, message, started_at, ended_at,
      success_records, failed_records, payload_json, result_json
    ) values (?, ?, ?, 0, ?, ?, 'COLLECTION_TASK', ?, ?, 'SUCCESS', 'assistant-coverage-worker',
      ?, ?, ?, 1, 0, ?, ?)
  `, [
    runRecordId,
    localContext.tenantId || "default",
    localContext.projectId,
    now,
    now,
    collectionTaskId,
    `assistant_coverage_node_${suffix}`,
    `AI assistant coverage run for ${collectionTaskName}`,
    now,
    now,
    JSON.stringify({ seededBy: "assistant-assistive-coverage", collectionTaskId }),
    JSON.stringify({ successRecords: 1, failedRecords: 0 }),
  ]);
  const datasourceRows = querySql("select id from datasource_definition where id = ?", [datasourceId]);
  const modelRows = querySql("select id from data_model where id = ?", [modelId]);
  const ruleRows = querySql("select id from quality_rule where id = ?", [qualityRuleId]);
  const collectionTaskRows = querySql("select id from collection_task_definition where id = ?", [collectionTaskId]);
  const dataServiceRows = querySql("select id from data_service_definition where id = ?", [dataServiceId]);
  const runRecordRows = querySql("select id from run_record where id = ?", [runRecordId]);
  const physicalRows = querySql(`select count(*) as row_count from ${quotedPhysicalLocator}`);
  assert(datasourceRows.length === 1
    && modelRows.length === 1
    && ruleRows.length === 1
    && collectionTaskRows.length === 1
    && dataServiceRows.length === 1
    && runRecordRows.length === 1, "Assistant coverage metadata seed was not visible in MySQL.");
  assert(Number(physicalRows[0]?.row_count || 0) >= 1, "Assistant coverage physical MySQL table seed was not visible.");
  return {
    suffix,
    tenantId: localContext.tenantId || "default",
    projectId: localContext.projectId,
    datasourceId,
    datasourceName,
    modelId,
    modelName,
    physicalLocator,
    physicalTableRows: Number(physicalRows[0]?.row_count || 0),
    datasourceHost: datasourceTechnicalMetadata.host,
    datasourcePort: datasourceTechnicalMetadata.port,
    datasourceDatabase: datasourceTechnicalMetadata.database,
    qualityRuleId,
    qualityRuleName,
    qualityRuleCode: ruleCode,
    collectionTaskId,
    collectionTaskName,
    dataServiceId,
    dataServiceCode,
    dataServiceName,
    runRecordId,
    columns: columns.map((item) => item.name),
  };
}

function seedActionDbEvidence(seed, assertion) {
  return {
    checked: true,
    table: "datasource_definition,data_model,quality_rule,collection_task_definition,data_service_definition,run_record",
    seededDatasourceId: seed?.datasourceId,
    seededModelId: seed?.modelId,
    seededQualityRuleId: seed?.qualityRuleId,
    seededCollectionTaskId: seed?.collectionTaskId,
    seededDataServiceId: seed?.dataServiceId,
    seededRunRecordId: seed?.runRecordId,
    seededProjectId: seed?.projectId,
    assertion,
  };
}

function buildActionCatalog(operations) {
  const actions = [];
  for (const operation of operations || []) {
    for (const action of operation?.featureActions || []) {
      if (!action?.path || !action?.action) {
        continue;
      }
      actions.push({
        key: actionKey(action.path, action.action, action.resource),
        path: normalizePath(action.path),
        action: String(action.action),
        resource: action.resource ? String(action.resource) : "",
        mutation: action.mutation === true,
        requiredValues: Array.isArray(action.requiredValues) ? action.requiredValues : [],
        optionalValues: Array.isArray(action.optionalValues) ? action.optionalValues : [],
      });
    }
  }
  return actions;
}

function summarizeActionCatalogCoverage(evidence) {
  const executed = new Set([
    ...(evidence.actionCases || []).filter((item) => item.status === "passed").map((item) => actionKey(item.path, item.action, item.resource)),
    ...(evidence.mutationCases || []).filter((item) => item.status === "passed").map((item) => actionKey(item.path, item.action, item.resource)),
  ]);
  const catalog = evidence.actionCatalog || [];
  const tested = catalog.filter((item) => executed.has(item.key));
  const untested = catalog.filter((item) => !executed.has(item.key));
  const untestedByPath = {};
  for (const item of untested) {
    if (!untestedByPath[item.path]) {
      untestedByPath[item.path] = [];
    }
    untestedByPath[item.path].push(`${item.resource ? `${item.resource}:` : ""}${item.action}${item.mutation ? "*" : ""}`);
  }
  return {
    total: catalog.length,
    mutationTotal: catalog.filter((item) => item.mutation).length,
    nonMutationTotal: catalog.filter((item) => !item.mutation).length,
    tested: tested.length,
    untested: untested.length,
    untestedByPath,
    note: "* marks mutating actions that require confirmation and dedicated seeded business objects before real execution.",
  };
}

function actionKey(pathValue, actionValue, resourceValue = "") {
  return `${normalizePath(pathValue)}|${String(actionValue || "")}|${String(resourceValue || "")}`;
}

async function runAssistantActionCase(page, network, testCase) {
  const since = network.entries.length;
  await askAssistant(page, testCase.prompt);
  const toolEntry = await network.waitFor((entry) => {
    const request = entry.requestBodyJson || {};
    const response = entry.responseBodyJson || {};
    const data = response.data || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === testCase.expectedInterfaceCode
      && normalizePath(request.params?.path || data.path) === testCase.path
      && request.params?.action === testCase.action
      && data.executedBy === "backend"
      && data.interfaceCode === testCase.expectedInterfaceCode
      && normalizePath(data.path) === testCase.path
      && data.action === testCase.action;
  }, caseTimeoutMs, since);
  await waitForAssistantReady(page, caseTimeoutMs);
  await expandProcessPanel(page).catch(() => undefined);
  const visibleText = await textContent(page);
  assert(!visibleText.includes("studio-assistant-protocol"), `${testCase.path}:${testCase.action} leaked protocol block in UI.`);
  assert(!visibleText.includes("确认执行 Studio 操作"), `${testCase.path}:${testCase.action} unexpectedly required mutation confirmation.`);
  assert(!visibleText.includes("后端动作网关降级"), `${testCase.path}:${testCase.action} fell back to frontend compatibility execution.`);
  assert(visibleText.includes("后端动作网关"), `${testCase.path}:${testCase.action} did not show backend action gateway evidence.`);
  assert(visibleText.includes("动作接口已返回结果"), `${testCase.path}:${testCase.action} did not show action result summary.`);

  const chainEvidence = summarizeToolEntry(toolEntry);
  assert(chainEvidence.mutation === testCase.expectedMutation, `${testCase.path}:${testCase.action} mutation flag mismatch.`);
  assert(chainEvidence.requiresConfirmation === false, `${testCase.path}:${testCase.action} should not require confirmation.`);
  assertParamsContain(chainEvidence.requestParams, testCase.expectedParams, `${testCase.path}:${testCase.action}`);
  assertPayloadContains(chainEvidence.requestParams?.payload, testCase.expectedPayload, `${testCase.path}:${testCase.action}`);
  const resultData = assertActionResultData(toolEntry, testCase);

  return {
    ...baseCaseEvidence(testCase),
    action: testCase.action,
    resource: testCase.resource,
    status: "passed",
    uiEvidence: pickUiEvidence(visibleText, ["后端动作网关", "动作接口已返回结果", testCase.label]),
    chainEvidence,
    resultEvidence: summarizeResultData(resultData),
    dbEvidence: testCase.dbEvidence || {
      checked: false,
      reason: "non-mutating action; database state was not expected to change",
    },
  };
}

async function runQualityRuleMutationCases(page, network) {
  const suffix = `${Date.now()}_${Math.floor(Math.random() * 1000)}`;
  const ruleCode = `assistant_coverage_rule_${suffix}`;
  const rulePayload = {
    ruleName: `AI助手覆盖测试规则 ${suffix}`,
    ruleCode,
    scopeType: "PROJECT",
    ruleDimension: "COMPLETENESS",
    description: "Studio AI assistant Web coverage seeded rule.",
    supportedDatasourceTypes: ["mysql8"],
    granularity: "TABLE",
    logicSql: "select count(*) as total_count from ${Schema_Table}",
    enabled: false,
    inputParams: [
      {
        paramOrder: 1,
        paramName: "Schema_Table",
        paramType: "TABLE",
        paramMeaning: "覆盖测试表名",
      },
    ],
    outputParams: [
      {
        outputOrder: 1,
        resultField: "total_count",
        outputType: "NUMBER",
        outputDescription: "覆盖测试总数",
      },
    ],
  };
  const savePrompt = `请通过 Studio 助手受控动作接口执行 /quality-rules 的 save 动作，payload 为 ${JSON.stringify(rulePayload)}。必须先确认再走后端动作网关。`;
  const saveAction = await runConfirmedAssistantActionCase(page, network, {
    id: "mutation:/quality-rules:save",
    kind: "mutation",
    label: "Quality rule save",
    path: "/quality-rules",
    action: "save",
    prompt: savePrompt,
    expectedPayload: {
      ruleCode,
      scopeType: "PROJECT",
      ruleDimension: "COMPLETENESS",
      granularity: "TABLE",
      logicSql: "select count(*) as total_count from ${Schema_Table}",
      enabled: false,
    },
  });
  const savedRows = queryQualityRuleRows(ruleCode);
  assert(savedRows.length === 1, "Saved quality rule row was not found in the configured web database.");
  const ruleId = String(savedRows[0].id || "");
  assert(/^\d+$/.test(ruleId), `Saved quality rule id is invalid: ${savedRows[0].id}`);
  assert(Number(savedRows[0].enabled) === 0, "Saved quality rule should be disabled before enable action.");
  assert(savedRows[0].scope_type === "PROJECT", "Saved quality rule should use PROJECT scope.");
  const savedChildCounts = queryQualityRuleChildCounts(ruleId);
  assert(Number(savedChildCounts.input_params) >= 1, "Saved quality rule input params were not persisted.");
  assert(Number(savedChildCounts.output_params) >= 1, "Saved quality rule output params were not persisted.");

  const saveResult = {
    id: "mutation:/quality-rules:save",
    kind: "mutation",
    label: "Quality rule save",
    path: "/quality-rules",
    action: "save",
    prompt: savePrompt,
    status: "passed",
    uiEvidence: pickUiEvidence(saveAction.visibleText, ["确认执行 Studio 操作", "后端动作网关", "动作接口已返回结果"]),
    chainEvidence: saveAction.chainEvidence,
    dbEvidence: {
      checked: true,
      table: "quality_rule",
      setupQuery: "assistant sent confirmed /quality-rules save action with unique ruleCode",
      ruleId,
      ruleCode,
      scopeType: savedRows[0].scope_type,
      projectId: savedRows[0].project_id,
      enabledAfterSave: Number(savedRows[0].enabled),
      inputParams: Number(savedChildCounts.input_params),
      outputParams: Number(savedChildCounts.output_params),
      assertion: "quality_rule row and parsed child params exist in the configured web database",
    },
  };

  const enablePrompt = `请通过 Studio 助手受控动作接口执行 /quality-rules 的 enable 动作，参数为 ${JSON.stringify({ id: ruleId })}。必须先确认再走后端动作网关。`;
  const enableAction = await runConfirmedAssistantActionCase(page, network, {
    id: "mutation:/quality-rules:enable",
    kind: "mutation",
    label: "Quality rule enable",
    path: "/quality-rules",
    action: "enable",
    prompt: enablePrompt,
    expectedId: ruleId,
  });
  const enabledRows = queryQualityRuleRows(ruleCode);
  assert(enabledRows.length === 1 && Number(enabledRows[0].enabled) === 1, "Quality rule enable action did not set enabled=1 in the configured web database.");

  const enableResult = {
    id: "mutation:/quality-rules:enable",
    kind: "mutation",
    label: "Quality rule enable",
    path: "/quality-rules",
    action: "enable",
    prompt: enablePrompt,
    status: "passed",
    uiEvidence: pickUiEvidence(enableAction.visibleText, ["确认执行 Studio 操作", "后端动作网关", "动作接口已返回结果"]),
    chainEvidence: enableAction.chainEvidence,
    dbEvidence: {
      checked: true,
      table: "quality_rule",
      setupQuery: "reuse the saved assistant coverage quality rule",
      ruleId,
      ruleCode,
      enabledBeforeAction: Number(savedRows[0].enabled),
      enabledAfterAction: Number(enabledRows[0].enabled),
      assertion: "quality_rule.enabled changed from 0 to 1 through backend action gateway",
    },
  };

  const disablePrompt = `请通过 Studio 助手受控动作接口执行 /quality-rules 的 disable 动作，参数为 ${JSON.stringify({ id: ruleId })}。必须先确认再走后端动作网关。`;
  const disableAction = await runConfirmedAssistantActionCase(page, network, {
    id: "mutation:/quality-rules:disable",
    kind: "mutation",
    label: "Quality rule disable",
    path: "/quality-rules",
    action: "disable",
    prompt: disablePrompt,
    expectedId: ruleId,
  });
  const disabledRows = queryQualityRuleRows(ruleCode);
  assert(disabledRows.length === 1 && Number(disabledRows[0].enabled) === 0, "Quality rule disable action did not set enabled=0 in the configured web database.");

  const disableResult = {
    id: "mutation:/quality-rules:disable",
    kind: "mutation",
    label: "Quality rule disable",
    path: "/quality-rules",
    action: "disable",
    prompt: disablePrompt,
    status: "passed",
    uiEvidence: pickUiEvidence(disableAction.visibleText, ["确认执行 Studio 操作", "后端动作网关", "动作接口已返回结果"]),
    chainEvidence: disableAction.chainEvidence,
    dbEvidence: {
      checked: true,
      table: "quality_rule",
      setupQuery: "reuse the saved assistant coverage quality rule",
      ruleId,
      ruleCode,
      enabledBeforeAction: Number(enabledRows[0].enabled),
      enabledAfterAction: Number(disabledRows[0].enabled),
      assertion: "quality_rule.enabled changed from 1 to 0 through backend action gateway",
    },
  };

  return [saveResult, enableResult, disableResult];
}

async function runDatasourceMutationCases(page, network, seed) {
  const suffix = `${Date.now()}_${Math.floor(Math.random() * 1000)}`;
  const datasourceId = seed?.datasourceId;
  assert(datasourceId, "Datasource mutation cases require seeded datasource id.");
  const updatedName = `assistant_coverage_ds_saved_${suffix}`;
  const savePayload = {
    id: datasourceId,
    name: updatedName,
    typeCode: "mysql8",
    enabled: true,
    executable: true,
    manualConnectionTestTimeoutSeconds: 15,
    technicalMetadata: {
      ...mysqlConnectionMetadataForPrompt(),
      columns: seed?.columns?.map((name) => ({ name })),
      coverageTable: seed?.physicalLocator,
    },
    businessMetadata: {
      seededBy: "assistant-assistive-coverage",
      coverageAction: "datasource-save",
    },
  };
  const saveCase = {
    id: "mutation:/datasources:save",
    kind: "mutation",
    label: "Datasource save",
    path: "/datasources",
    action: "save",
    prompt: `请通过 Studio 助手受控动作接口执行 /datasources 的 save 动作，payload 为 ${JSON.stringify(savePayload)}。必须先确认再走后端动作网关。`,
    expectedPayload: {
      id: datasourceId,
      name: updatedName,
      typeCode: "mysql8",
      enabled: true,
      executable: true,
    },
    assertResult: (result) => {
      assert(String(result?.id) === String(datasourceId), `/datasources:save returned unexpected id: ${JSON.stringify(result)}`);
      assert(result?.name === updatedName, `/datasources:save returned unexpected name: ${JSON.stringify(result)}`);
    },
  };
  const saveAction = await runConfirmedAssistantActionCase(page, network, saveCase);
  const savedRows = queryDatasourceRows(datasourceId);
  assert(savedRows.length === 1, "Datasource save action did not leave one datasource row.");
  assert(savedRows[0].name === updatedName, `Datasource save action did not update name to ${updatedName}.`);

  const testCurrentCase = {
    id: "mutation:/datasources:testCurrent",
    kind: "mutation",
    label: "Datasource test current",
    path: "/datasources",
    action: "testCurrent",
    prompt: `请通过 Studio 助手受控动作接口执行 /datasources 的 testCurrent 动作，payload 为 ${JSON.stringify(savePayload)}。必须先确认再走后端动作网关。`,
    expectedPayload: {
      id: datasourceId,
      name: updatedName,
      typeCode: "mysql8",
    },
    assertResult: (result) => {
      assert(result?.success === true, `/datasources:testCurrent expected success=true, got ${JSON.stringify(result)}`);
      assert(String(result?.status || "").toUpperCase() === "AVAILABLE", `/datasources:testCurrent expected AVAILABLE, got ${JSON.stringify(result)}`);
    },
  };
  const testCurrentAction = await runConfirmedAssistantActionCase(page, network, testCurrentCase);

  const testCase = {
    id: "mutation:/datasources:test",
    kind: "mutation",
    label: "Datasource test",
    path: "/datasources",
    action: "test",
    prompt: `请通过 Studio 助手受控动作接口执行 /datasources 的 test 动作，参数为 ${JSON.stringify({ id: datasourceId })}。必须先确认再走后端动作网关。`,
    expectedId: datasourceId,
    assertResult: (result) => {
      assert(result?.success === true, `/datasources:test expected success=true, got ${JSON.stringify(result)}`);
      assert(String(result?.status || "").toUpperCase() === "AVAILABLE", `/datasources:test expected AVAILABLE, got ${JSON.stringify(result)}`);
    },
  };
  const testAction = await runConfirmedAssistantActionCase(page, network, testCase);
  const testedRows = queryDatasourceRows(datasourceId);
  assert(testedRows.length === 1 && testedRows[0].connection_status === "AVAILABLE",
    `Datasource test action did not persist AVAILABLE status: ${JSON.stringify(testedRows)}`);

  return [
    passedMutationResult(saveCase, saveAction, {
      checked: true,
      table: "datasource_definition",
      datasourceId,
      name: savedRows[0].name,
      typeCode: savedRows[0].type_code,
      enabled: Number(savedRows[0].enabled),
      executable: Number(savedRows[0].executable),
      assertion: "assistant /datasources save updated the seeded datasource row without exposing credentials",
    }),
    passedMutationResult(testCurrentCase, testCurrentAction, {
      checked: true,
      table: "datasource_definition",
      datasourceId,
      assertion: "assistant /datasources testCurrent returned AVAILABLE using preserved datasource password",
    }),
    passedMutationResult(testCase, testAction, {
      checked: true,
      table: "datasource_definition",
      datasourceId,
      connectionStatus: testedRows[0].connection_status,
      lastConnectionTestAt: testedRows[0].last_connection_test_at,
      assertion: "assistant /datasources test persisted AVAILABLE connection status",
    }),
  ];
}

async function runModelMutationCases(page, network, seed) {
  const suffix = `${Date.now()}_${Math.floor(Math.random() * 1000)}`;
  const datasourceId = seed?.datasourceId;
  assert(datasourceId, "Model mutation cases require seeded datasource id.");
  const physicalLocator = `assistant_coverage_saved_model_${suffix}`;
  const modelName = `assistant_coverage_saved_model_${suffix}`;
  querySql(`
    create table if not exists ${quoteMysqlIdentifier(physicalLocator)} (
      id bigint not null primary key,
      name varchar(128) not null,
      amount decimal(18, 2) not null
    )
  `);
  querySql(`insert into ${quoteMysqlIdentifier(physicalLocator)} (id, name, amount) values (?, ?, ?)`, [
    1,
    `assistant model coverage ${suffix}`,
    42.00,
  ]);
  const modelPayload = {
    datasourceId,
    name: modelName,
    physicalLocator,
    modelKind: "TABLE",
    technicalMetadata: {
      columns: [
        { name: "id", type: "bigint", comment: "coverage id" },
        { name: "name", type: "varchar", comment: "coverage name" },
        { name: "amount", type: "decimal", comment: "coverage amount" },
      ],
    },
    businessMetadata: {
      seededBy: "assistant-assistive-coverage",
    },
  };
  const saveCase = {
    id: "mutation:/models:save",
    kind: "mutation",
    label: "Model save",
    path: "/models",
    action: "save",
    prompt: `请通过 Studio 助手受控动作接口执行 /models 的 save 动作，payload 为 ${JSON.stringify(modelPayload)}。必须先确认再走后端动作网关。`,
    expectedPayload: {
      datasourceId,
      name: modelName,
      physicalLocator,
      modelKind: "TABLE",
    },
    assertResult: (result) => {
      assert(result?.name === modelName, `/models:save returned unexpected name: ${JSON.stringify(result)}`);
      assert(result?.physicalLocator === physicalLocator, `/models:save returned unexpected physicalLocator: ${JSON.stringify(result)}`);
    },
  };
  const saveAction = await runConfirmedAssistantActionCase(page, network, saveCase);
  const savedRows = queryModelRows(datasourceId, physicalLocator);
  assert(savedRows.length === 1, "Model save action did not persist the expected data_model row.");

  const syncSelectedCase = {
    id: "mutation:/models:syncSelected",
    kind: "mutation",
    label: "Model sync selected",
    path: "/models",
    action: "syncSelected",
    prompt: `请通过 Studio 助手受控动作接口执行 /models 的 syncSelected 动作，参数为 ${JSON.stringify({
      datasourceId,
      physicalLocators: [physicalLocator],
    })}。必须先确认再走后端动作网关。`,
    assertResult: (result) => {
      assertResultContains(result, physicalLocator, "/models:syncSelected physical locator");
    },
  };
  const syncSelectedAction = await runConfirmedAssistantActionCase(page, network, syncSelectedCase);
  const syncedRows = queryModelRows(datasourceId, physicalLocator);
  assert(syncedRows.length === 1, "Model syncSelected action did not leave the selected data_model row.");

  const rebuildCase = {
    id: "mutation:/models:rebuildIndex",
    kind: "mutation",
    label: "Model rebuild index",
    path: "/models",
    action: "rebuildIndex",
    prompt: `请通过 Studio 助手受控动作接口执行 /models 的 rebuildIndex 动作，参数为 ${JSON.stringify({ datasourceId })}。必须先确认再走后端动作网关。`,
    assertResult: (result) => {
      assert(Number(result) >= 1, `/models:rebuildIndex expected affected model count >= 1, got ${JSON.stringify(result)}`);
    },
  };
  const rebuildAction = await runConfirmedAssistantActionCase(page, network, rebuildCase);

  return [
    passedMutationResult(saveCase, saveAction, {
      checked: true,
      table: "data_model",
      datasourceId,
      modelId: savedRows[0].id,
      physicalLocator,
      assertion: "assistant /models save persisted a dedicated model row",
    }),
    passedMutationResult(syncSelectedCase, syncSelectedAction, {
      checked: true,
      table: "data_model",
      datasourceId,
      physicalLocator,
      modelCount: syncedRows.length,
      assertion: "assistant /models syncSelected completed against the seeded physical table",
    }),
    passedMutationResult(rebuildCase, rebuildAction, {
      checked: true,
      table: "data_model_search_index",
      datasourceId,
      assertion: "assistant /models rebuildIndex returned a positive accessible model count",
    }),
  ];
}

async function runFieldMappingRuleMutationCases(page, network) {
  const suffix = `${Date.now()}_${Math.floor(Math.random() * 1000)}`;
  const mappingCode = `assistant_coverage_mapping_${suffix}`;
  const mappingPayload = {
    mappingName: `AI助手覆盖字段映射 ${suffix}`,
    mappingType: "规整",
    mappingCode,
    enabled: true,
    description: "Studio AI assistant Web coverage seeded field mapping rule.",
    params: [
      {
        paramName: "prefix",
        paramOrder: 1,
        componentType: "input",
        paramValueJson: "{\"defaultValue\":\"AI\"}",
        description: "覆盖测试参数",
      },
    ],
  };
  const saveCase = {
    id: "mutation:/field-mapping-rules:save",
    kind: "mutation",
    label: "Field mapping rule save",
    path: "/field-mapping-rules",
    action: "save",
    prompt: `请通过 Studio 助手受控动作接口执行 /field-mapping-rules 的 save 动作，payload 为 ${JSON.stringify(mappingPayload)}。必须先确认再走后端动作网关。`,
    expectedPayload: {
      mappingCode,
      mappingType: "规整",
      enabled: true,
    },
    assertResult: (result) => {
      assert(result?.mappingCode === mappingCode, `/field-mapping-rules:save returned unexpected mappingCode: ${JSON.stringify(result)}`);
      assert(Array.isArray(result?.params) && result.params.length === 1, `/field-mapping-rules:save did not return saved param: ${JSON.stringify(result)}`);
    },
  };
  const saveAction = await runConfirmedAssistantActionCase(page, network, saveCase);
  const rows = queryFieldMappingRuleRows(mappingCode);
  assert(rows.length === 1, "Field mapping rule save action did not persist one rule row.");
  const childCounts = queryFieldMappingRuleChildCounts(rows[0].id);
  assert(Number(childCounts.params) === 1, "Field mapping rule save action did not persist the parameter row.");
  return [passedMutationResult(saveCase, saveAction, {
    checked: true,
    table: "field_mapping_rule",
    ruleId: rows[0].id,
    mappingCode,
    mappingType: rows[0].mapping_type,
    enabled: Number(rows[0].enabled),
    params: Number(childCounts.params),
    assertion: "assistant /field-mapping-rules save persisted rule and parameter rows",
  })];
}

async function runScriptEnvironmentMutationCases(page, network) {
  const suffix = `${Date.now()}_${Math.floor(Math.random() * 1000)}`;
  const environmentCode = `assistant_coverage_env_${suffix}`;
  const environmentPayload = {
    environmentName: `AI助手覆盖脚本环境 ${suffix}`,
    environmentCode,
    enabled: false,
    useApplicationParent: true,
    description: "Studio AI assistant Web coverage seeded script environment.",
    dependencyIds: [],
  };
  const saveCase = {
    id: "mutation:/script-environments:save",
    kind: "mutation",
    label: "Script environment save",
    path: "/script-environments",
    action: "save",
    prompt: `请通过 Studio 助手受控动作接口执行 /script-environments 的 save 动作，payload 为 ${JSON.stringify(environmentPayload)}。必须先确认再走后端动作网关。`,
    expectedPayload: {
      environmentCode,
      enabled: false,
      useApplicationParent: true,
    },
    assertResult: (result) => {
      assert(result?.environmentCode === environmentCode, `/script-environments:save returned unexpected code: ${JSON.stringify(result)}`);
    },
  };
  const saveAction = await runConfirmedAssistantActionCase(page, network, saveCase);
  const savedRows = queryScriptEnvironmentRows(environmentCode);
  assert(savedRows.length === 1, "Script environment save action did not persist one environment row.");
  const environmentId = String(savedRows[0].id);
  const initialVersion = Number(savedRows[0].environment_version || 0);

  const enableCase = {
    id: "mutation:/script-environments:enable",
    kind: "mutation",
    label: "Script environment enable",
    path: "/script-environments",
    action: "enable",
    prompt: `请通过 Studio 助手受控动作接口执行 /script-environments 的 enable 动作，参数为 ${JSON.stringify({ id: environmentId })}。必须先确认再走后端动作网关。`,
    expectedId: environmentId,
    assertResult: (result) => {
      assert(result?.enabled === true, `/script-environments:enable expected enabled=true, got ${JSON.stringify(result)}`);
    },
  };
  const enableAction = await runConfirmedAssistantActionCase(page, network, enableCase);
  const enabledRows = queryScriptEnvironmentRows(environmentCode);
  assert(enabledRows.length === 1 && Number(enabledRows[0].enabled) === 1, "Script environment enable action did not set enabled=1.");

  const disableCase = {
    id: "mutation:/script-environments:disable",
    kind: "mutation",
    label: "Script environment disable",
    path: "/script-environments",
    action: "disable",
    prompt: `请通过 Studio 助手受控动作接口执行 /script-environments 的 disable 动作，参数为 ${JSON.stringify({ id: environmentId })}。必须先确认再走后端动作网关。`,
    expectedId: environmentId,
    assertResult: (result) => {
      assert(result?.enabled === false, `/script-environments:disable expected enabled=false, got ${JSON.stringify(result)}`);
    },
  };
  const disableAction = await runConfirmedAssistantActionCase(page, network, disableCase);
  const disabledRows = queryScriptEnvironmentRows(environmentCode);
  assert(disabledRows.length === 1 && Number(disabledRows[0].enabled) === 0, "Script environment disable action did not set enabled=0.");

  const refreshCase = {
    id: "mutation:/script-environments:refresh",
    kind: "mutation",
    label: "Script environment refresh",
    path: "/script-environments",
    action: "refresh",
    prompt: `请通过 Studio 助手受控动作接口执行 /script-environments 的 refresh 动作，参数为 ${JSON.stringify({ id: environmentId })}。必须先确认再走后端动作网关。`,
    expectedId: environmentId,
    assertResult: (result) => {
      assert(Number(result?.environmentVersion || 0) > initialVersion,
        `/script-environments:refresh expected incremented version, got ${JSON.stringify(result)}`);
    },
  };
  const refreshAction = await runConfirmedAssistantActionCase(page, network, refreshCase);
  const refreshedRows = queryScriptEnvironmentRows(environmentCode);
  assert(refreshedRows.length === 1 && Number(refreshedRows[0].environment_version) > Number(disabledRows[0].environment_version),
    "Script environment refresh action did not increment environment_version.");

  return [
    passedMutationResult(saveCase, saveAction, {
      checked: true,
      table: "so_pf_script_env",
      environmentId,
      environmentCode,
      enabled: Number(savedRows[0].enabled),
      environmentVersion: initialVersion,
      assertion: "assistant /script-environments save persisted one environment row",
    }),
    passedMutationResult(enableCase, enableAction, {
      checked: true,
      table: "so_pf_script_env",
      environmentId,
      environmentCode,
      enabledAfterAction: Number(enabledRows[0].enabled),
      environmentVersion: Number(enabledRows[0].environment_version),
      assertion: "assistant /script-environments enable set enabled=1",
    }),
    passedMutationResult(disableCase, disableAction, {
      checked: true,
      table: "so_pf_script_env",
      environmentId,
      environmentCode,
      enabledAfterAction: Number(disabledRows[0].enabled),
      environmentVersion: Number(disabledRows[0].environment_version),
      assertion: "assistant /script-environments disable set enabled=0",
    }),
    passedMutationResult(refreshCase, refreshAction, {
      checked: true,
      table: "so_pf_script_env",
      environmentId,
      environmentCode,
      environmentVersionBefore: Number(disabledRows[0].environment_version),
      environmentVersionAfter: Number(refreshedRows[0].environment_version),
      assertion: "assistant /script-environments refresh incremented environment_version",
    }),
  ];
}

function passedMutationResult(testCase, actionEvidence, dbEvidence) {
  return {
    ...baseCaseEvidence(testCase),
    action: testCase.action,
    resource: testCase.resource,
    status: "passed",
    uiEvidence: pickUiEvidence(actionEvidence.visibleText, ["确认执行 Studio 操作", "后端动作网关", "动作接口已返回结果"]),
    chainEvidence: actionEvidence.chainEvidence,
    resultEvidence: actionEvidence.resultEvidence,
    dbEvidence,
  };
}

async function runConfirmedAssistantActionCase(page, network, testCase) {
  const since = network.entries.length;
  await askAssistant(page, testCase.prompt);
  await waitForText(page, "确认执行 Studio 操作", caseTimeoutMs);
  await waitForEnabledButtonByText(page, "确认执行", caseTimeoutMs);
  await clickButtonByText(page, "确认执行");
  await waitForText(page, "已确认，开始执行", 30000).catch(() => undefined);

  const toolEntry = await network.waitFor((entry) => {
    const request = entry.requestBodyJson || {};
    const response = entry.responseBodyJson || {};
    const data = response.data || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === "studio.feature.action"
      && normalizePath(request.params?.path || data.path) === testCase.path
      && request.params?.action === testCase.action
      && request.params?.confirmed === true
      && data.executedBy === "backend"
      && data.interfaceCode === "studio.feature.action"
      && normalizePath(data.path) === testCase.path
      && data.action === testCase.action;
  }, caseTimeoutMs, since);
  await waitForAssistantReady(page, caseTimeoutMs);
  await expandProcessPanel(page).catch(() => undefined);
  const visibleText = await textContent(page);
  assert(!visibleText.includes("studio-assistant-protocol"), `${testCase.path}:${testCase.action} leaked protocol block in UI.`);
  assert(!visibleText.includes("后端动作网关降级"), `${testCase.path}:${testCase.action} fell back to frontend compatibility execution.`);
  assert(visibleText.includes("后端动作网关"), `${testCase.path}:${testCase.action} did not show backend action gateway evidence.`);
  assert(visibleText.includes("动作接口已返回结果"), `${testCase.path}:${testCase.action} did not show action result summary.`);

  const chainEvidence = summarizeToolEntry(toolEntry);
  assert(chainEvidence.mutation === true, `${testCase.path}:${testCase.action} mutation flag mismatch.`);
  assert(chainEvidence.requiresConfirmation === false, `${testCase.path}:${testCase.action} should execute after confirmation.`);
  if (testCase.expectedPayload) {
    assertPayloadContains(chainEvidence.requestParams?.payload, testCase.expectedPayload, `${testCase.path}:${testCase.action}`);
  }
  if (testCase.expectedId != null) {
    assert(String(chainEvidence.requestParams?.id) === String(testCase.expectedId), `${testCase.path}:${testCase.action} id mismatch.`);
  }
  const resultData = toolEntry.responseBodyJson?.data?.data;
  if (resultData != null) {
    assertCleanActionResultData(resultData, `${testCase.path}:${testCase.action}`);
  }
  if (typeof testCase.assertResult === "function") {
    assert(resultData != null, `${testCase.path}:${testCase.action} backend mutation returned empty data.`);
    testCase.assertResult(resultData);
  }
  return { toolEntry, visibleText, chainEvidence, resultData, resultEvidence: summarizeResultData(resultData) };
}

async function runNotificationMutationCase(page, network) {
  const localContext = await evaluate(page, `
    ({
      tenantId: window.localStorage.getItem("studio_current_tenant") || "default",
      projectId: window.localStorage.getItem("studio_current_project") || ""
    })
  `);
  const adminRows = querySql("select id, tenant_id, username from sys_user where username = ? limit 1", [loginUsername]);
  assert(adminRows.length === 1, "login user row was not found in the configured web database.");
  const adminUserId = adminRows[0].id;
  const beforeUnread = queryUnreadNotifications(adminUserId);
  const notificationId = Date.now() * 1000 + Math.floor(Math.random() * 1000);
  const dedupeKey = `assistant-coverage-${Date.now()}`;
  const now = new Date().toISOString().replace("T", " ").slice(0, 19);
  querySql(`
    insert into studio_notification (
      id, deleted, created_at, updated_at, recipient_user_id, tenant_id, project_id,
      category, title, content, target_type, target_path, dedupe_key, read_at, archived_at
    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null, null)
  `, [
    notificationId,
    0,
    now,
    now,
    adminUserId,
    localContext.tenantId || "default",
    localContext.projectId ? Number(localContext.projectId) : null,
    "ASSISTANT_COVERAGE",
    "AI助手覆盖测试通知",
    "用于验证助手 markAllRead 是否真实落库。",
    "assistant-coverage",
    "/notifications",
    dedupeKey,
  ]);
  const afterSeedUnread = queryUnreadNotifications(adminUserId);
  assert(afterSeedUnread > beforeUnread, "Unread notification seed was not visible in the configured web database.");

  const prompt = "请把所有通知全部标记为已读，必须通过 Studio 助手确认后执行。";
  const since = network.entries.length;
  await askAssistant(page, prompt);
  await waitForText(page, "确认执行 Studio 操作", caseTimeoutMs);
  await waitForEnabledButtonByText(page, "确认执行", caseTimeoutMs);
  await clickButtonByText(page, "确认执行");
  await waitForText(page, "已确认，开始执行", 30000);

  const toolEntry = await network.waitFor((entry) => {
    const request = entry.requestBodyJson || {};
    const response = entry.responseBodyJson || {};
    const data = response.data || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === "studio.feature.action"
      && normalizePath(request.params?.path || data.path) === "/notifications"
      && request.params?.action === "markAllRead"
      && request.params?.confirmed === true
      && data.executedBy === "backend"
      && data.interfaceCode === "studio.feature.action"
      && normalizePath(data.path) === "/notifications"
      && data.action === "markAllRead";
  }, caseTimeoutMs, since);
  await waitForAssistantReady(page, caseTimeoutMs);
  await expandProcessPanel(page).catch(() => undefined);
  const visibleText = await textContent(page);
  assert(!visibleText.includes("studio-assistant-protocol"), "Notification mutation leaked protocol block in UI.");
  assert(!visibleText.includes("后端动作网关降级"), "Notification mutation fell back to frontend compatibility execution.");
  assert(visibleText.includes("后端动作网关"), "Notification mutation did not show backend action gateway evidence.");
  assert(visibleText.includes("动作接口已返回结果"), "Notification mutation did not show action result summary.");

  const afterMutationUnread = queryUnreadNotifications(adminUserId);
  const seededRows = querySql("select id, read_at from studio_notification where dedupe_key = ?", [dedupeKey]);
  assert(seededRows.length === 1 && seededRows[0].read_at, "Seeded notification was not marked read in the configured web database.");

  const directReadAllCalls = network.entries.slice(since).filter((entry) => /\/notifications\/read-all(?:\?|$)/.test(entry.url));
  assert(directReadAllCalls.length === 0, "Notification mutation used direct frontend /notifications/read-all fallback.");

  return {
    id: "mutation:/notifications:markAllRead",
    kind: "mutation",
    label: "Notifications mark all read",
    path: "/notifications",
    action: "markAllRead",
    prompt,
    status: "passed",
    uiEvidence: pickUiEvidence(visibleText, ["确认执行 Studio 操作", "后端动作网关", "动作接口已返回结果"]),
    chainEvidence: summarizeToolEntry(toolEntry),
    dbEvidence: {
      checked: true,
      table: "studio_notification",
      setupQuery: "insert one unread assistant coverage notification for the login user",
      beforeUnread,
      afterSeedUnread,
      afterMutationUnread,
      seededId: notificationId,
      seededDedupeKey: dedupeKey,
      seededReadAt: seededRows[0].read_at,
      assertion: "seeded row read_at is not null and direct /notifications/read-all fallback was not called",
    },
  };
}

async function runNotificationMarkReadMutationCase(page, network) {
  const localContext = await evaluate(page, `
    ({
      tenantId: window.localStorage.getItem("studio_current_tenant") || "default",
      projectId: window.localStorage.getItem("studio_current_project") || ""
    })
  `);
  const adminRows = querySql("select id, tenant_id, username from sys_user where username = ? limit 1", [loginUsername]);
  assert(adminRows.length === 1, "login user row was not found in the configured web database.");
  const adminUserId = adminRows[0].id;
  const beforeUnread = queryUnreadNotifications(adminUserId);
  const notificationId = Date.now() * 1000 + Math.floor(Math.random() * 1000);
  const dedupeKey = `assistant-coverage-mark-read-${Date.now()}`;
  const now = new Date().toISOString().replace("T", " ").slice(0, 19);
  querySql(`
    insert into studio_notification (
      id, deleted, created_at, updated_at, recipient_user_id, tenant_id, project_id,
      category, title, content, target_type, target_path, dedupe_key, read_at, archived_at
    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null, null)
  `, [
    notificationId,
    0,
    now,
    now,
    adminUserId,
    localContext.tenantId || "default",
    localContext.projectId ? Number(localContext.projectId) : null,
    "ASSISTANT_COVERAGE",
    "AI助手覆盖测试单条通知",
    "用于验证助手 markRead 是否真实落库。",
    "assistant-coverage",
    "/notifications",
    dedupeKey,
  ]);
  const afterSeedUnread = queryUnreadNotifications(adminUserId);
  assert(afterSeedUnread > beforeUnread, "Unread notification seed was not visible in the configured web database.");

  const prompt = `请通过 Studio 助手受控动作接口执行 /notifications 的 markRead 动作，参数为 {"id":${notificationId}}。必须先确认再走后端动作网关。`;
  const since = network.entries.length;
  await askAssistant(page, prompt);
  await waitForText(page, "确认执行 Studio 操作", caseTimeoutMs);
  await waitForEnabledButtonByText(page, "确认执行", caseTimeoutMs);
  await clickButtonByText(page, "确认执行");
  await waitForText(page, "已确认，开始执行", 30000);

  const toolEntry = await network.waitFor((entry) => {
    const request = entry.requestBodyJson || {};
    const response = entry.responseBodyJson || {};
    const data = response.data || {};
    return entry.url.includes("/assistant/tools/execute")
      && request.interfaceCode === "studio.feature.action"
      && normalizePath(request.params?.path || data.path) === "/notifications"
      && request.params?.action === "markRead"
      && Number(request.params?.id) === Number(notificationId)
      && request.params?.confirmed === true
      && data.executedBy === "backend"
      && data.interfaceCode === "studio.feature.action"
      && normalizePath(data.path) === "/notifications"
      && data.action === "markRead";
  }, caseTimeoutMs, since);
  await waitForAssistantReady(page, caseTimeoutMs);
  await expandProcessPanel(page).catch(() => undefined);
  const visibleText = await textContent(page);
  assert(!visibleText.includes("studio-assistant-protocol"), "Notification markRead leaked protocol block in UI.");
  assert(!visibleText.includes("后端动作网关降级"), "Notification markRead fell back to frontend compatibility execution.");
  assert(visibleText.includes("后端动作网关"), "Notification markRead did not show backend action gateway evidence.");
  assert(visibleText.includes("动作接口已返回结果"), "Notification markRead did not show action result summary.");

  const afterMutationUnread = queryUnreadNotifications(adminUserId);
  const seededRows = querySql("select id, read_at from studio_notification where dedupe_key = ?", [dedupeKey]);
  assert(seededRows.length === 1 && seededRows[0].read_at, "Seeded notification was not marked read in the configured web database.");

  return {
    id: "mutation:/notifications:markRead",
    kind: "mutation",
    label: "Notifications mark read",
    path: "/notifications",
    action: "markRead",
    prompt,
    status: "passed",
    uiEvidence: pickUiEvidence(visibleText, ["确认执行 Studio 操作", "后端动作网关", "动作接口已返回结果"]),
    chainEvidence: summarizeToolEntry(toolEntry),
    dbEvidence: {
      checked: true,
      table: "studio_notification",
      setupQuery: "insert one unread assistant coverage notification for the login user",
      beforeUnread,
      afterSeedUnread,
      afterMutationUnread,
      seededId: notificationId,
      seededDedupeKey: dedupeKey,
      seededReadAt: seededRows[0].read_at,
      assertion: "seeded row read_at is not null and backend /notifications markRead action was used",
    },
  };
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
    if (!entry) {
      return;
    }
    entry.status = event.response?.status;
    entry.mimeType = event.response?.mimeType || "";
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
    async waitFor(predicate, timeoutMs = 30000, since = 0) {
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

function isInterestingRequest(url) {
  return /\/api\/v1\/assistant\//.test(url)
    || /\/api\/v1\/notifications\/read-all(?:\?|$)/.test(url);
}

async function askAssistant(page, prompt) {
  await waitForAssistantReady(page, 60000);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, prompt);
  await click(page, `[data-testid="studio-assistant-send"]`);
}

async function waitForAssistantEnabledConfig(page, timeoutMs = 60000) {
  const startedAt = Date.now();
  let lastProbe = null;
  while (Date.now() - startedAt < timeoutMs) {
    lastProbe = await fetchAssistantConfigProbe(page).catch((error) => ({
      ok: false,
      status: 0,
      payload: { message: error instanceof Error ? error.message : String(error) },
    }));
    const config = extractAssistantConfig(lastProbe?.payload);
    if (lastProbe?.ok && config.enabled === true) {
      return config;
    }
    const message = String(lastProbe?.payload?.message || config.reason || lastProbe?.payload?.raw || "");
    if (lastProbe?.status === 200 && config.enabled === false) {
      break;
    }
    if (lastProbe?.status >= 400 && /No static resource|not found|404/i.test(message)) {
      break;
    }
    await sleep(500);
  }
  throw new Error(`Assistant assistive coverage requires /assistant/config enabled=true. Last response: ${JSON.stringify(lastProbe)}`);
}

async function fetchAssistantConfigProbe(page) {
  return evaluate(page, `
    (async () => {
      const token = window.localStorage.getItem("studio_token") || "";
      const projectId = window.localStorage.getItem("studio_current_project") || "";
      const tenantId = window.localStorage.getItem("studio_current_tenant") || "";
      const headers = { Accept: "application/json" };
      if (token) headers.Authorization = "Bearer " + token;
      if (projectId) headers["X-Project-Id"] = projectId;
      if (tenantId) headers["X-Tenant-Id"] = tenantId;
      const response = await fetch("/api/v1/assistant/config", { headers });
      const text = await response.text();
      let payload;
      try {
        payload = text ? JSON.parse(text) : null;
      } catch {
        payload = { raw: text };
      }
      return { ok: response.ok, status: response.status, payload };
    })()
  `);
}

function extractAssistantConfig(payload) {
  if (!payload || typeof payload !== "object") {
    return {};
  }
  if (payload.data && typeof payload.data === "object" && !Array.isArray(payload.data)) {
    return payload.data;
  }
  return payload;
}

async function ensureAssistantOpen(page) {
  const alreadyOpen = await evaluate(page, `Boolean(document.querySelector('[data-testid="studio-assistant-root"]'))`);
  if (alreadyOpen) {
    await waitForAssistantReady(page, caseTimeoutMs);
    return;
  }
  await click(page, `[data-testid="studio-assistant-open"]`);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-root"]')`, caseTimeoutMs);
  await waitForAssistantReady(page, caseTimeoutMs);
}

async function fetchAssistantOperations(page) {
  const authContext = await evaluate(page, `
    (() => {
      return {
        token: window.localStorage.getItem("studio_token") || "",
        projectId: window.localStorage.getItem("studio_current_project") || "",
        tenantId: window.localStorage.getItem("studio_current_tenant") || ""
      };
    })()
  `);
  const response = await fetch(`${backendBaseUrl}/api/v1/assistant/operations`, {
    headers: {
      Authorization: authContext?.token ? `Bearer ${authContext.token}` : "",
      "X-Project-Id": authContext?.projectId || "",
      "X-Tenant-Id": authContext?.tenantId || "",
    },
  });
  if (!response.ok) {
    throw new Error(`Assistant operations endpoint failed: HTTP ${response.status}`);
  }
  const payload = await response.json();
  return payload?.data || [];
}

function baseCaseEvidence(testCase) {
  return {
    id: testCase.id,
    kind: testCase.kind,
    label: testCase.label,
    path: testCase.path,
    prompt: testCase.prompt,
    expectedInterfaceCode: testCase.expectedInterfaceCode,
  };
}

function summarizeToolEntry(entry) {
  const request = entry.requestBodyJson || {};
  const response = entry.responseBodyJson || {};
  const data = response.data || {};
  return {
    requestUrl: trimLocalUrl(entry.url),
    httpStatus: entry.status,
    requestInterfaceCode: request.interfaceCode,
    requestParams: redactSensitiveResultData(request.params),
    responseSuccess: response.success,
    responseInterfaceCode: data.interfaceCode,
    responsePath: data.path,
    responseAction: data.action,
    responseResource: data.resource,
    executedBy: data.executedBy,
    mutation: data.mutation,
    requiresConfirmation: data.requiresConfirmation,
    responseDataSummary: summarizeResultData(data.data),
  };
}

function extractResponseItems(data) {
  if (Array.isArray(data)) {
    return data;
  }
  if (!data || typeof data !== "object") {
    return [];
  }
  for (const key of ["items", "records", "list", "models", "rows", "data"]) {
    if (Array.isArray(data[key])) {
      return data[key];
    }
  }
  return [];
}

function findListedEntity(items, testCase) {
  const expectedId = String(testCase.expectedId ?? "");
  const expectedName = String(testCase.expectedName ?? "");
  return (items || []).find((item) => {
    const id = String(entityItemId(item) ?? "");
    const name = String(entityItemName(item) ?? "");
    return (expectedId && id === expectedId) || (expectedName && name === expectedName);
  });
}

function entityItemId(item) {
  if (!item || typeof item !== "object") {
    return "";
  }
  return item.id ?? item.entityId ?? item.recordId ?? item.taskId ?? item.serviceId ?? item.ruleId ?? item.runId ?? item.workflowId ?? item.scriptId ?? "";
}

function entityItemName(item) {
  if (!item || typeof item !== "object") {
    return "";
  }
  return String(item.name
    ?? item.label
    ?? item.title
    ?? item.taskName
    ?? item.serviceName
    ?? item.ruleName
    ?? item.workflowName
    ?? item.scriptName
    ?? item.modelName
    ?? item.runName
    ?? item.serviceCode
    ?? item.ruleCode
    ?? item.taskCode
    ?? item.workflowCode
    ?? item.executionId
    ?? item.traceId
    ?? item.id
    ?? "");
}

function isAssistantToolExecuteCall(entry) {
  return entry.url.includes("/assistant/tools/execute");
}

function isAssistantToolCallForPath(entry, pathValue) {
  const request = entry.requestBodyJson || {};
  const response = entry.responseBodyJson || {};
  const data = response.data || {};
  return entry.url.includes("/assistant/tools/execute")
    && normalizePath(request.params?.path || data.path) === normalizePath(pathValue);
}

function isModelAssistantToolCall(entry) {
  const request = entry.requestBodyJson || {};
  const response = entry.responseBodyJson || {};
  const data = response.data || {};
  return entry.url.includes("/assistant/tools/execute")
    && normalizePath(request.params?.path || data.path) === "/models";
}

function assertActionResultData(entry, testCase) {
  const label = `${testCase.path}:${testCase.action}`;
  const response = entry.responseBodyJson || {};
  const resultData = response.data?.data;
  assert(entry.status === 200, `${label} expected HTTP 200, got ${entry.status}`);
  assert(response.success === true, `${label} expected response.success=true, got ${JSON.stringify(response)}`);
  assert(resultData !== undefined && resultData !== null, `${label} backend action returned empty data.`);
  assertCleanActionResultData(resultData, label);
  if (typeof testCase.assertResult === "function") {
    testCase.assertResult(resultData);
  }
  return resultData;
}

function assertCleanActionResultData(resultData, label) {
  const serialized = JSON.stringify(resultData);
  assert(!/jdbc:mysql:\/\/null:null\/null/i.test(serialized), `${label} used an incomplete datasource connection: ${serialized}`);
  assert(!/(Communications link failure|SQLSyntaxErrorException|SQLException|Access denied|Unknown database|UnknownHostException|Table .* doesn't exist)/i.test(serialized),
    `${label} returned SQL/connection failure data: ${serialized}`);
}

function summarizeResultData(value) {
  if (value === undefined) {
    return { type: "undefined" };
  }
  if (value === null) {
    return { type: "null" };
  }
  const safeValue = redactSensitiveResultData(value);
  if (Array.isArray(safeValue)) {
    return {
      type: "array",
      length: safeValue.length,
      sample: safeValue.slice(0, 2).map((item) => summarizeResultData(item)),
    };
  }
  if (typeof safeValue === "object") {
    const keys = Object.keys(safeValue);
    return {
      type: "object",
      keys: keys.slice(0, 20),
      message: typeof safeValue.message === "string" ? safeValue.message : undefined,
      valid: typeof safeValue.valid === "boolean" ? safeValue.valid : undefined,
      models: Array.isArray(safeValue.models) ? safeValue.models.length : undefined,
      fields: Array.isArray(safeValue.fields) ? safeValue.fields.length : undefined,
      rows: Array.isArray(safeValue.rows) ? safeValue.rows.length : undefined,
      snippet: truncateText(JSON.stringify(safeValue), 900),
    };
  }
  return {
    type: typeof safeValue,
    value: truncateText(String(safeValue), 900),
  };
}

function redactSensitiveResultData(value) {
  if (value === null || value === undefined) {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map((item) => redactSensitiveResultData(item));
  }
  if (typeof value !== "object") {
    return value;
  }
  const result = {};
  for (const [key, item] of Object.entries(value)) {
    result[key] = isSensitiveKey(key) ? "***" : redactSensitiveResultData(item);
  }
  return result;
}

function isSensitiveKey(key) {
  return /(password|secret|token|accesskey|access_key|credential)/i.test(String(key || ""));
}

function assertPayloadContains(actual, expected, label) {
  for (const [key, value] of Object.entries(expected || {})) {
    assert(actual && actual[key] === value, `${label} payload.${key} mismatch: expected ${JSON.stringify(value)}, got ${JSON.stringify(actual && actual[key])}`);
  }
}

function assertParamsContain(actual, expected, label) {
  for (const [key, value] of Object.entries(expected || {})) {
    assert(actual && actual[key] === value, `${label} param.${key} mismatch: expected ${JSON.stringify(value)}, got ${JSON.stringify(actual && actual[key])}`);
  }
}

function assertArrayContainsObjectValue(items, key, expected, label) {
  assert(Array.isArray(items), `${label} expected array, got ${JSON.stringify(items)}`);
  assert(items.some((item) => item && item[key] === expected),
    `${label} expected ${key}=${JSON.stringify(expected)}, got ${JSON.stringify(items)}`);
}

function assertFieldNames(fields, expectedNames, label) {
  assert(Array.isArray(fields), `${label} expected fields array, got ${JSON.stringify(fields)}`);
  const actual = new Set(fields
    .map((field) => field?.fieldName || field?.name || field?.columnName)
    .filter(Boolean)
    .map(String));
  for (const expectedName of expectedNames || []) {
    assert(actual.has(expectedName), `${label} missing field ${expectedName}; got ${JSON.stringify(Array.from(actual))}`);
  }
}

function assertResultContains(result, expectedText, label) {
  const serialized = JSON.stringify(result);
  assert(serialized.includes(String(expectedText)), `${label} missing ${expectedText}: ${serialized}`);
}

function truncateText(value, maxLength) {
  const text = String(value || "");
  if (text.length <= maxLength) {
    return text;
  }
  return `${text.slice(0, maxLength)}...`;
}

function pickUiEvidence(text, markers) {
  const lines = String(text || "").split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  const picked = [];
  for (const marker of markers) {
    const index = lines.findIndex((line) => line.includes(marker));
    if (index >= 0) {
      picked.push(...lines.slice(Math.max(0, index - 1), Math.min(lines.length, index + 3)));
    }
  }
  return Array.from(new Set(picked)).slice(0, 16);
}

function queryUnreadNotifications(adminUserId) {
  const rows = querySql(`
    select count(*) as count
    from studio_notification
    where recipient_user_id = ?
      and read_at is null
      and archived_at is null
      and coalesce(deleted, 0) = 0
  `, [adminUserId]);
  return Number(rows[0]?.count || 0);
}

function queryQualityRuleRows(ruleCode) {
  return querySql(`
    select id, tenant_id, project_id, rule_code, scope_type, enabled
    from quality_rule
    where rule_code = ?
      and coalesce(deleted, 0) = 0
  `, [ruleCode]);
}

function queryQualityRuleChildCounts(ruleId) {
  const rows = querySql(`
    select
      (select count(*) from quality_rule_input_param where rule_id = ? and coalesce(deleted, 0) = 0) as input_params,
      (select count(*) from quality_rule_output_param where rule_id = ? and coalesce(deleted, 0) = 0) as output_params
  `, [ruleId, ruleId]);
  return rows[0] || { input_params: 0, output_params: 0 };
}

function queryDatasourceRows(datasourceId) {
  return querySql(`
    select id, name, type_code, enabled, executable, connection_status,
           last_connection_test_at, last_connection_test_message
    from datasource_definition
    where id = ?
      and coalesce(deleted, 0) = 0
  `, [datasourceId]);
}

function queryModelRows(datasourceId, physicalLocator) {
  return querySql(`
    select id, tenant_id, project_id, datasource_id, name, model_kind, physical_locator
    from data_model
    where datasource_id = ?
      and physical_locator = ?
      and coalesce(deleted, 0) = 0
  `, [datasourceId, physicalLocator]);
}

function queryFieldMappingRuleRows(mappingCode) {
  return querySql(`
    select id, mapping_name, mapping_type, mapping_code, enabled
    from field_mapping_rule
    where mapping_code = ?
      and coalesce(deleted, 0) = 0
  `, [mappingCode]);
}

function queryFieldMappingRuleChildCounts(ruleId) {
  const rows = querySql(`
    select count(*) as params
    from field_mapping_rule_param
    where rule_id = ?
      and coalesce(deleted, 0) = 0
  `, [ruleId]);
  return rows[0] || { params: 0 };
}

function queryScriptEnvironmentRows(environmentCode) {
  return querySql(`
    select id, tenant_id, environment_name, environment_code, enabled,
           use_application_parent, environment_version
    from so_pf_script_env
    where environment_code = ?
      and coalesce(deleted, 0) = 0
  `, [environmentCode]);
}

function querySql(sql, params = []) {
  return queryMysql(sql, params);
}

function queryMysql(sql, params = []) {
  const javaFile = ensureJdbcQueryJavaSource();
  const maxAttempts = Number(process.env.STUDIO_ASSISTANT_DB_QUERY_ATTEMPTS || 3);
  let lastError = "";
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    const result = spawnSync(resolveJavaExecutable(), [
      "-cp",
      resolveMysqlJdbcJar(),
      javaFile,
      mysqlJdbcUrl(),
      mysqlUsername(),
      mysqlPassword(),
      sql,
      ...params.map(encodeSqlParam),
    ], {
      cwd: workspaceRoot,
      encoding: "utf8",
      windowsHide: true,
      maxBuffer: 10 * 1024 * 1024,
    });
    if (result.status === 0) {
      return JSON.parse(result.stdout || "[]");
    }
    lastError = result.stderr || result.stdout || `exit ${result.status}`;
    if (attempt >= maxAttempts || !isTransientMysqlError(lastError)) {
      break;
    }
    console.warn(`[assistant-coverage] MySQL query failed transiently, retrying ${attempt + 1}/${maxAttempts}: ${firstLine(lastError)}`);
    sleepSync(750 * attempt);
  }
  throw new Error(`MySQL JDBC query failed after ${maxAttempts} attempt(s): ${lastError}`);
}

function isTransientMysqlError(message) {
  return /CommunicationsException|CJCommunicationsException|Communications link failure|Connection reset|Connection reset by peer|SocketTimeoutException|Read timed out|Connection timed out|No operations allowed after connection closed/i
    .test(String(message || ""));
}

function firstLine(value) {
  return String(value || "").split(/\r?\n/).find((line) => line.trim()) || "";
}

function sleepSync(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function ensureJdbcQueryJavaSource() {
  const helperDir = path.join(targetDir, "assistant-assistive-coverage");
  mkdirSync(helperDir, { recursive: true });
  const javaFile = path.join(helperDir, "AssistantCoverageJdbcQuery.java");
  writeFileSync(javaFile, `
import java.sql.*;
import java.util.*;

public class AssistantCoverageJdbcQuery {
  public static void main(String[] args) throws Exception {
    if (args.length < 4) {
      throw new IllegalArgumentException("Usage: <jdbcUrl> <username> <password> <sql> [typedParams...]");
    }
    Class.forName("com.mysql.cj.jdbc.Driver");
    try (Connection connection = DriverManager.getConnection(args[0], args[1], args[2]);
         PreparedStatement statement = connection.prepareStatement(args[3])) {
      for (int index = 4; index < args.length; index++) {
        setParam(statement, index - 3, args[index]);
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
          for (int col = 1; col <= meta.getColumnCount(); col++) {
            if (col > 1) out.append(',');
            out.append(json(meta.getColumnLabel(col))).append(':');
            Object value = rs.getObject(col);
            if (rs.wasNull()) {
              out.append("null");
            } else {
              out.append(json(String.valueOf(value)));
            }
          }
          out.append('}');
        }
        out.append(']');
        System.out.print(out.toString());
      }
    }
  }

  private static void setParam(PreparedStatement statement, int index, String value) throws Exception {
    if ("N:".equals(value)) {
      statement.setObject(index, null);
      return;
    }
    if (value.startsWith("L:")) {
      statement.setLong(index, Long.parseLong(value.substring(2)));
      return;
    }
    if (value.startsWith("D:")) {
      statement.setDouble(index, Double.parseDouble(value.substring(2)));
      return;
    }
    if (value.startsWith("B:")) {
      statement.setBoolean(index, Boolean.parseBoolean(value.substring(2)));
      return;
    }
    statement.setString(index, value.startsWith("S:") ? value.substring(2) : value);
  }

  private static String json(String value) {
    if (value == null) return "null";
    StringBuilder out = new StringBuilder();
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"': out.append("\\\\\\""); break;
        case '\\\\': out.append("\\\\\\\\"); break;
        case '\\b': out.append("\\\\b"); break;
        case '\\f': out.append("\\\\f"); break;
        case '\\n': out.append("\\\\n"); break;
        case '\\r': out.append("\\\\r"); break;
        case '\\t': out.append("\\\\t"); break;
        default:
          if (c < 32) out.append(String.format("\\\\u%04x", (int)c));
          else out.append(c);
      }
    }
    out.append('"');
    return out.toString();
  }
}
`, "utf8");
  return javaFile;
}

function encodeSqlParam(value) {
  if (value == null) {
    return "N:";
  }
  if (typeof value === "boolean") {
    return `B:${value}`;
  }
  if (typeof value === "number" && Number.isInteger(value)) {
    return `L:${value}`;
  }
  if (typeof value === "number") {
    return `D:${value}`;
  }
  return `S:${String(value)}`;
}

function mysqlJdbcUrl() {
  return process.env.STUDIO_ASSISTANT_DB_URL
    || process.env.SPRING_DATASOURCE_URL
    || "jdbc:mysql://192.168.188.129:3306/data_aggregation_studio?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true";
}

function mysqlUsername() {
  return process.env.STUDIO_ASSISTANT_DB_USERNAME
    || process.env.SPRING_DATASOURCE_USERNAME
    || "root";
}

function mysqlPassword() {
  return process.env.STUDIO_ASSISTANT_DB_PASSWORD
    || process.env.SPRING_DATASOURCE_PASSWORD
    || "951753";
}

function mysqlConnectionMetadata() {
  const parsed = parseMysqlJdbcUrl(mysqlJdbcUrl());
  return {
    host: parsed.host,
    port: parsed.port,
    database: parsed.database,
    userName: mysqlUsername(),
    username: mysqlUsername(),
    password: mysqlPassword(),
    jdbcUrl: mysqlJdbcUrl(),
    driverClassName: "com.mysql.cj.jdbc.Driver",
    usePool: false,
  };
}

function mysqlConnectionMetadataForPrompt() {
  const metadata = { ...mysqlConnectionMetadata() };
  delete metadata.password;
  return metadata;
}

function parseMysqlJdbcUrl(jdbcUrl) {
  const match = String(jdbcUrl || "").match(/^jdbc:mysql:\/\/([^/:?]+)(?::(\d+))?\/([^?]+)/i);
  assert(match, `Unsupported MySQL JDBC URL for assistant coverage: ${jdbcUrl}`);
  return {
    host: match[1],
    port: match[2] || "3306",
    database: decodeURIComponent(match[3]),
  };
}

function quoteMysqlIdentifier(identifier) {
  assert(/^[A-Za-z0-9_]+$/.test(String(identifier || "")), `Unsafe MySQL identifier: ${identifier}`);
  return `\`${identifier}\``;
}

function formatMysqlLocalDateTime(date) {
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
  return local.toISOString().replace("T", " ").slice(0, 19);
}

function resolveMysqlJdbcJar() {
  const candidates = [
    process.env.STUDIO_ASSISTANT_MYSQL_JDBC_JAR,
    path.join(os.homedir(), ".m2", "repository", "com", "mysql", "mysql-connector-j", "8.0.33", "mysql-connector-j-8.0.33.jar"),
    path.join(os.homedir(), ".m2", "repository", "com", "mysql", "mysql-connector-j", "8.3.0", "mysql-connector-j-8.3.0.jar"),
    path.join(os.homedir(), ".m2", "repository", "com", "mysql", "mysql-connector-j", "8.2.0", "mysql-connector-j-8.2.0.jar"),
  ].filter(Boolean);
  for (const candidate of candidates) {
    if (existsSync(candidate)) {
      return candidate;
    }
  }
  throw new Error("MySQL JDBC driver was not found. Set STUDIO_ASSISTANT_MYSQL_JDBC_JAR.");
}

function databaseEvidenceConfig() {
  return {
    type: "mysql",
    url: mysqlJdbcUrl().replace(/password=[^&]*/i, "password=***"),
    username: mysqlUsername(),
  };
}

function renderMarkdownReport(evidence) {
  const passedReads = evidence.readCases.filter((item) => item.status === "passed").length;
  const failedReads = evidence.readCases.length - passedReads;
  const multiTurnCases = evidence.multiTurnCases ?? [];
  const passedMultiTurn = multiTurnCases.filter((item) => item.status === "passed").length;
  const failedMultiTurn = multiTurnCases.length - passedMultiTurn;
  const actionCases = evidence.actionCases ?? [];
  const actionCatalogCoverage = evidence.actionCatalogCoverage || summarizeActionCatalogCoverage(evidence);
  const passedActions = actionCases.filter((item) => item.status === "passed").length;
  const failedActions = actionCases.length - passedActions;
  const passedMutations = evidence.mutationCases.filter((item) => item.status === "passed").length;
  const failedMutations = evidence.mutationCases.length - passedMutations;
  const rows = evidence.readCases.map((item, index) => [
    String(index + 1),
    item.status,
    item.path,
    item.chainEvidence?.requestInterfaceCode || item.expectedInterfaceCode || "",
    item.chainEvidence?.executedBy || "",
    item.error || "通过",
  ]);
  const mutationRows = evidence.mutationCases.map((item, index) => [
    String(index + 1),
    item.status,
    `${item.path}:${item.action || ""}`,
    item.chainEvidence?.requestInterfaceCode || "",
    item.chainEvidence?.executedBy || "",
    formatMutationDbEvidence(item),
  ]);
  const actionRows = actionCases.map((item, index) => [
    String(index + 1),
    item.status,
    `${item.path}:${item.action || ""}`,
    item.chainEvidence?.requestInterfaceCode || item.expectedInterfaceCode || "",
    item.chainEvidence?.executedBy || "",
    item.chainEvidence ? formatActionResultEvidence(item) : item.error || "",
  ]);
  const multiTurnRows = multiTurnCases.map((item, index) => [
    String(index + 1),
    item.status,
    item.path,
    item.chainEvidence?.datasourceList?.requestInterfaceCode
      || item.chainEvidence?.list?.requestInterfaceCode
      || "",
    item.chainEvidence?.physicalDiscovery?.requestParams?.action
      || item.chainEvidence?.detail?.requestInterfaceCode
      || item.chainEvidence?.clarification?.action
      || "",
    item.error || item.chainEvidence?.dbEvidence?.assertion || item.dbEvidence?.assertion || "通过",
  ]);
  return [
    "# Studio AI 助手协助覆盖测试结果",
    "",
    `- 生成时间：${evidence.generatedAt}`,
    `- UI 地址：${evidence.appBaseUrl}`,
    `- 后端地址：${evidence.backendBaseUrl}`,
    `- 操作目录数量：${evidence.operationCatalogSize}`,
    `- 动作目录覆盖：${actionCatalogCoverage.tested}/${actionCatalogCoverage.total} 已真实执行`,
    `- 读取用例：${passedReads} 通过，${failedReads} 失败`,
    `- 多轮记忆用例：${passedMultiTurn} 通过，${failedMultiTurn} 失败`,
    `- 非变更动作用例：${passedActions} 通过，${failedActions} 失败`,
    `- 变更用例：${passedMutations} 通过，${failedMutations} 失败`,
    `- 截图：${evidence.screenshotPath}`,
    "",
    "## 读取链路覆盖",
    "",
    markdownTable(["#", "状态", "路径", "接口", "执行方", "结果"], rows),
    "",
    "## 多轮记忆与引用解析覆盖",
    "",
    markdownTable(["#", "状态", "路径", "列表接口", "后续动作", "结果"], multiTurnRows),
    "",
    "## 非变更动作覆盖",
    "",
    markdownTable(["#", "状态", "动作", "接口", "执行方", "链路证据"], actionRows),
    "",
    "## 变更链路覆盖",
    "",
    markdownTable(["#", "状态", "动作", "接口", "执行方", "数据库证据"], mutationRows),
    "",
    "## 覆盖说明",
    "",
    ...evidence.gaps.map((item) => `- ${item}`),
    "",
    "## 动作目录缺口",
    "",
    `- 动作目录总数：${actionCatalogCoverage.total}`,
    `- 变更动作：${actionCatalogCoverage.mutationTotal}`,
    `- 非变更动作：${actionCatalogCoverage.nonMutationTotal}`,
    `- 已通过真实助手输入执行：${actionCatalogCoverage.tested}`,
    `- 未执行动作：${actionCatalogCoverage.untested}`,
    `- 说明：${actionCatalogCoverage.note}`,
    "",
    markdownTable(["路径", "未执行动作"], Object.entries(actionCatalogCoverage.untestedByPath || {}).map(([pathValue, actions]) => [
      pathValue,
      actions.join(", "),
    ])),
    "",
  ].join("\n");
}

function formatMutationDbEvidence(item) {
  if (!item.dbEvidence?.checked) {
    return item.error || "";
  }
  if (item.dbEvidence.table === "quality_rule") {
    const enabled = item.dbEvidence.enabledAfterAction ?? item.dbEvidence.enabledAfterSave;
    const childCounts = item.dbEvidence.inputParams == null
      ? ""
      : `, input_params=${item.dbEvidence.inputParams}, output_params=${item.dbEvidence.outputParams}`;
    return `rule_id=${item.dbEvidence.ruleId}, code=${item.dbEvidence.ruleCode}, enabled=${enabled}${childCounts}`;
  }
  if (item.dbEvidence.table === "studio_notification") {
    return `before=${item.dbEvidence.beforeUnread}, seeded=${item.dbEvidence.afterSeedUnread}, after=${item.dbEvidence.afterMutationUnread}, read_at=${item.dbEvidence.seededReadAt}`;
  }
  return item.dbEvidence.assertion || "checked";
}

function formatActionResultEvidence(item) {
  const result = item.resultEvidence || item.chainEvidence?.responseDataSummary || {};
  const parts = [
    `mutation=${item.chainEvidence?.mutation}`,
    `confirmation=${item.chainEvidence?.requiresConfirmation}`,
  ];
  if (result.valid !== undefined) {
    parts.push(`valid=${result.valid}`);
  }
  if (result.models !== undefined) {
    parts.push(`models=${result.models}`);
  }
  if (result.fields !== undefined) {
    parts.push(`fields=${result.fields}`);
  }
  if (result.rows !== undefined) {
    parts.push(`rows=${result.rows}`);
  }
  if (result.message) {
    parts.push(`message=${result.message}`);
  }
  if (result.snippet) {
    parts.push(`data=${truncateText(result.snippet, 180)}`);
  }
  return parts.join(", ");
}

function markdownTable(headers, rows) {
  const safeRows = rows.length ? rows : [headers.map(() => "-")];
  return [
    `| ${headers.join(" | ")} |`,
    `| ${headers.map(() => "---").join(" | ")} |`,
    ...safeRows.map((row) => `| ${row.map(markdownCell).join(" | ")} |`),
  ].join("\n");
}

function markdownCell(value) {
  return String(value ?? "").replace(/\|/g, "\\|").replace(/\r?\n/g, " ");
}

function normalizeBaseUrl(value) {
  return String(value || "").trim().replace(/\/$/, "");
}

function normalizeAppBaseUrl(value) {
  const normalized = String(value || "").trim();
  if (!normalized) {
    return normalized;
  }
  return normalized.endsWith("/") ? normalized : `${normalized}/`;
}

function startProcess(command, args, options, label) {
  const launch = wrapWindowsBatchCommand(command, args);
  const child = spawn(launch.command, launch.args, {
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
    ...options,
  });
  child.stdout.on("data", (chunk) => process.stdout.write(prefixOutput(label, child.pid, chunk)));
  child.stderr.on("data", (chunk) => process.stderr.write(prefixOutput(label, child.pid, chunk)));
  child.on("exit", (code, signal) => {
    if (code && code !== 0 && !process.exitCode && !stoppingPids.has(child.pid)) {
      console.error(`${label} process ${child.pid} exited with code ${code}${signal ? ` signal ${signal}` : ""}.`);
    }
  });
  return child;
}

function wrapWindowsBatchCommand(command, args) {
  if (process.platform === "win32" && /\.(cmd|bat)$/i.test(command)) {
    return {
      command: "cmd.exe",
      args: ["/d", "/s", "/c", command, ...args],
    };
  }
  return { command, args };
}

function prefixOutput(label, pid, chunk) {
  return String(chunk).split(/\r?\n/).filter(Boolean).map((line) => `[${label}:${pid}] ${line}\n`).join("");
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

function resolveJavaHome() {
  const candidates = [
    "C:\\dev\\Java\\jdk-17.0.12",
    process.env.JAVA_HOME,
  ].filter(Boolean);
  return candidates.find((candidate) => existsSync(path.join(candidate, "bin", process.platform === "win32" ? "java.exe" : "java"))) || "";
}

function resolveJavaExecutable() {
  const javaHome = resolveJavaHome();
  if (javaHome) {
    return path.join(javaHome, "bin", process.platform === "win32" ? "java.exe" : "java");
  }
  return process.platform === "win32" ? "java.exe" : "java";
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
  throw new Error("Chrome or Edge executable was not found. Set STUDIO_ASSISTANT_SMOKE_BROWSER to run the UI coverage test.");
}

async function waitForHttp(url, label, timeoutMs = 30000, watchedProcess) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    if (watchedProcess && watchedProcess.exitCode != null) {
      throw new Error(`${label} process exited before it became healthy: exitCode=${watchedProcess.exitCode}`);
    }
    try {
      const response = await fetch(url);
      if (response.ok) {
        return;
      }
    } catch {
      // Keep waiting.
    }
    await sleep(500);
  }
  throw new Error(`Timed out waiting for ${label}: ${url}`);
}

async function waitForBackendLogin(timeoutMs = 240000, watchedProcess) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    if (watchedProcess && watchedProcess.exitCode != null) {
      throw new Error(`web backend process exited before login became ready: exitCode=${watchedProcess.exitCode}`);
    }
    try {
      const response = await fetch(`${backendBaseUrl}/api/v1/auth/login`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json",
        },
        body: JSON.stringify({ username: loginUsername, password: loginPassword }),
      });
      if (response.ok) {
        const payload = await response.json();
        if (payload?.success === true && payload?.data?.token) {
          return;
        }
      }
    } catch {
      // Keep waiting until the web server and bootstrap data are both ready.
    }
    await sleep(1000);
  }
  throw new Error(`Timed out waiting for web backend login readiness: ${backendBaseUrl}`);
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

async function clickElementByText(page, selector, text) {
  const ok = await evaluate(page, `
    (() => {
      const selector = ${JSON.stringify(selector)};
      const expected = ${JSON.stringify(text)};
      const element = Array.from(document.querySelectorAll(selector))
        .find((candidate) => candidate.innerText && candidate.innerText.includes(expected));
      if (!element) return false;
      element.scrollIntoView({ block: "center", inline: "center" });
      element.click();
      return true;
    })()
  `);
  assert(ok, `Unable to click ${selector} containing ${text}`);
}

async function expandProcessPanel(page) {
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-process"]')`, 10000);
  const expanded = await evaluate(page, `
    Boolean(document.querySelector('[data-testid="studio-assistant-process"].assistant-process--expanded'))
  `);
  if (!expanded) {
    await click(page, `[data-testid="studio-assistant-process"] .assistant-process__summary`);
  }
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-process"].assistant-process--expanded')`, 10000);
}

async function clickButtonByText(page, text) {
  const ok = await evaluate(page, `
    (() => {
      const expected = ${JSON.stringify(text)};
      const matches = Array.from(document.querySelectorAll("button"))
        .filter((button) => !button.disabled && button.innerText.trim().includes(expected));
      const element = matches[matches.length - 1];
      if (!element) return false;
      element.scrollIntoView({ block: "center", inline: "center" });
      element.click();
      return true;
    })()
  `);
  assert(ok, `Unable to click button text ${text}`);
}

async function waitForEnabledButtonByText(page, text, timeoutMs = 15000) {
  const quoted = JSON.stringify(text);
  await waitForEval(page, `
    Array.from(document.querySelectorAll("button"))
      .some((button) => !button.disabled && button.innerText.trim().includes(${quoted}))
  `, timeoutMs);
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

function normalizePath(value) {
  const text = String(value || "").trim();
  if (!text) {
    return "";
  }
  return text.startsWith("/") ? text : `/${text}`;
}

function parseJson(text) {
  if (!text) {
    return undefined;
  }
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

function trimLocalUrl(url) {
  try {
    const parsed = new URL(url);
    return parsed.pathname + parsed.search;
  } catch {
    return url;
  }
}

function isIgnorableBrowserError(message) {
  const text = String(message || "");
  return /Failed to load resource: the server responded with a status of 404 \(Not Found\)/i.test(text)
    || /Failed to load resource: the server responded with a status of 401 \(\)/i.test(text)
    || /favicon\.ico/i.test(text);
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
