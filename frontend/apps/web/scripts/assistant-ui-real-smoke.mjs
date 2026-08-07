import { spawn, spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const appPort = Number(process.env.STUDIO_ASSISTANT_REAL_UI_APP_PORT || 5175);
const backendPort = Number(process.env.STUDIO_ASSISTANT_REAL_UI_BACKEND_PORT || 18181);
const cdpPort = Number(process.env.STUDIO_ASSISTANT_REAL_UI_CDP_PORT || 9224);
const headed = process.env.STUDIO_ASSISTANT_UI_HEADED === "true";
const workspaceRoot = fileURLToPath(new URL("..", import.meta.url));
const studioRoot = path.resolve(workspaceRoot, "../../..");
const backendRoot = path.join(studioRoot, "backend");
const targetDir = path.resolve(workspaceRoot, "target");
const runtimeDir = path.join(targetDir, "assistant-real-ui-runtime", `run-${Date.now()}`);
const sqlitePath = path.join(runtimeDir, "studio-real-ui.db");
const appBaseUrl = `http://127.0.0.1:${appPort}/dfs/data-aggregation-studio/`;
const backendBaseUrl = `http://127.0.0.1:${backendPort}`;
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
  await fs.rm(runtimeDir, { recursive: true, force: true }).catch(() => undefined);
});

async function main() {
  await fs.mkdir(runtimeDir, { recursive: true });

  runProcess(resolveMavenCommand(), [
    "-pl",
    "studio-desktop-runtime",
    "-am",
    "-Dmaven.test.skip=true",
    "package",
  ], {
    cwd: backendRoot,
    env: backendEnv(),
  }, "maven");

  const backend = startProcess(resolveJavaExecutable(), [
    "-jar",
    path.join(backendRoot, "studio-desktop-runtime", "target", "studio-desktop-runtime-0.1.0-SNAPSHOT-exec.jar"),
  ], {
    cwd: backendRoot,
    env: backendEnv(),
  }, "backend");
  children.push(backend);
  await waitForBackendLogin(240000, backend);

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
  await waitForHttp(appBaseUrl, "Vite app");

  userDataDir = await fs.mkdtemp(path.join(os.tmpdir(), "studio-assistant-real-smoke-"));
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
  await page.send("Page.navigate", { url: appBaseUrl });
  await waitForPageLoad(page);

  await waitForDom(page, `document.querySelector('input[autocomplete="username"]')`, 30000);
  await setField(page, `input[autocomplete="username"]`, "admin");
  await setField(page, `input[autocomplete="current-password"]`, "admin123");
  await click(page, `.login__form .login__submit`);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-open"]')`, 60000);

  const skillsProbe = await evaluate(page, `
    (() => {
      const token = window.localStorage.getItem("studio-token") || window.localStorage.getItem("studio_token");
      const projectId = window.localStorage.getItem("studio_current_project");
      const tenantId = window.localStorage.getItem("studio_current_tenant");
      return fetch("/api/v1/assistant/skills", {
        headers: {
          Authorization: token ? "Bearer " + token : "",
          "X-Project-Id": projectId || "",
          "X-Tenant-Id": tenantId || ""
        }
      })
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
              && item.protocolSchema.required.includes("plan")
              && Array.isArray(item.examples)
              && item.examples.some((example) => String(example.payload || "").includes('"plan"'))
          ),
          hasOperationCard: Array.isArray(payload.data) && payload.data.some((item) => item.kind === "studio.operation.catalog" && item.path === "/dashboard"),
        }));
    })()
  `);
  assert(skillsProbe?.ok, "Real backend portable skills endpoint did not return success.");
  assert(skillsProbe?.hasLoopSkill, "Real backend did not export the assistant loop skill.");
  assert(skillsProbe?.hasProtocolContract, "Real backend did not export the custom protocol skill.");
  assert(skillsProbe?.hasProtocolPlanContract, "Real backend custom protocol skill did not require structured plan.");
  assert(skillsProbe?.hasOperationCard, "Real backend did not export operation catalog skill cards.");

  await click(page, `[data-testid="studio-assistant-open"]`);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-root"]')`);
  await click(page, `[data-testid="studio-assistant-mode-goal"]`);
  await click(page, `[data-testid="studio-assistant-language-en"]`);
  await waitForText(page, "Goal mode");

  await setField(page, `[data-testid="studio-assistant-prompt"]`, "what can Studio assistant do?");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "Question Breakdown", 30000);
  await waitForText(page, "Injected Skills", 30000);
  await waitForAssistantReady(page, 30000);

  let visibleText = await textContent(page);
  assert(!visibleText.includes("studio-assistant-protocol"), "Protocol block leaked in the real backend basic answer.");
  assert(visibleText.includes("Goal mode"), "Goal mode was not visible in the real UI smoke.");
  assert(visibleText.includes("Question Breakdown"), "Question decomposition was not rendered in goal/en mode.");

  await setField(page, `[data-testid="studio-assistant-prompt"]`, "read dashboard overview");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForDom(page, `document.querySelector('[data-testid="studio-assistant-process"]')`, 10000);
  await click(page, `[data-testid="studio-assistant-process"] .assistant-process__summary`);
  await waitForText(page, "后台结果摘要", 45000);
  await waitForText(page, "Project dashboard", 45000);
  await waitForAssistantReady(page, 45000);

  visibleText = await textContent(page);
  assert(!visibleText.includes("studio-assistant-protocol"), "Protocol block leaked after real backend tool execution.");
  assert(!visibleText.includes("tool_pending"), "Loop tool status leaked after real backend tool execution.");
  assert(!visibleText.includes("waiting_for_tool_result"), "Loop stop reason leaked after real backend tool execution.");
  assert(visibleText.includes("后台结果摘要"), "Real backend tool result process summary was not rendered.");
  assert(visibleText.includes("Project dashboard"), "Real backend operation catalog label was not rendered.");

  await click(page, `[data-testid="studio-assistant-mode-plan"]`);
  await click(page, `[data-testid="studio-assistant-language-zh"]`);
  await waitForText(page, "计划模式", 10000);
  await setField(page, `[data-testid="studio-assistant-prompt"]`, "帮我规划一下数据开发 SQL 执行和结果验证");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "问题拆解", 30000);
  await waitForText(page, "当前回答", 30000);
  await waitForAssistantReady(page, 30000);

  visibleText = await textContent(page);
  assert(!visibleText.includes("studio-assistant-protocol"), "Protocol block leaked after plan/zh answer.");
  assert(visibleText.includes("计划模式"), "Plan mode label was not visible.");
  assert(visibleText.includes("问题拆解"), "Chinese question breakdown was not rendered.");
  assert(visibleText.includes("当前回答"), "Chinese answer section was not rendered.");

  await setField(page, `[data-testid="studio-assistant-prompt"]`, "mark all notifications read");
  await click(page, `[data-testid="studio-assistant-send"]`);
  await waitForText(page, "确认执行 Studio 操作", 45000);
  visibleText = await textContent(page);
  assert(!visibleText.includes("studio-assistant-protocol"), "Protocol block leaked before real backend mutation confirmation.");
  assert(visibleText.includes("确认执行"), "Mutation confirmation button was not rendered.");

  await clickButtonByText(page, "确认执行");
  await waitForText(page, "已确认，开始执行", 30000);
  await expandProcessPanel(page);
  await waitForText(page, "后端动作网关", 45000);
  await waitForText(page, "动作接口已返回结果", 45000);
  await waitForAssistantReady(page, 45000);

  visibleText = await textContent(page);
  assert(!visibleText.includes("studio-assistant-protocol"), "Protocol block leaked after confirmed real backend mutation.");
  assert(visibleText.includes("后端动作网关"), "Confirmed mutation did not use the backend action gateway.");
  assert(visibleText.includes("动作接口已返回结果"), "Confirmed mutation result summary was not rendered.");

  const screenshot = await page.send("Page.captureScreenshot", { format: "png", captureBeyondViewport: true });
  const screenshotPath = path.join(targetDir, "assistant-ui-real-smoke.png");
  await fs.writeFile(screenshotPath, Buffer.from(screenshot.data, "base64"));

  const meaningfulBrowserErrors = browserErrors.filter((item) => !isIgnorableBrowserError(item));
  if (meaningfulBrowserErrors.length) {
    throw new Error(`Browser console/runtime errors:\n${meaningfulBrowserErrors.join("\n")}`);
  }
  console.log(`Assistant real UI smoke passed. Screenshot: ${screenshotPath}`);
  page.close();
}

function backendEnv() {
  const env = { ...process.env };
  const javaHome = resolveJavaHome();
  if (javaHome) {
    env.JAVA_HOME = javaHome;
    const pathKey = Object.keys(env).find((key) => key.toLowerCase() === "path") || "Path";
    env[pathKey] = `${path.join(javaHome, "bin")};${env[pathKey] || ""}`;
  }
  env.SERVER_PORT = String(backendPort);
  env.STUDIO_DESKTOP_DB_PATH = sqlitePath;
  env.STUDIO_AGGREGATION_HOME = path.resolve(studioRoot, "../package_all/aggregation");
  env.STUDIO_ASSISTANT_LLM_ENABLED = "false";
  env.STUDIO_SCHEMA_AUTO_UPGRADE_ON_STARTUP = "false";
  env.STUDIO_SCAN_PLUGINS_ON_STARTUP = "false";
  env.STUDIO_WORKER_LIFECYCLE_ENABLED = "false";
  env.SPRING_QUARTZ_AUTO_STARTUP = "false";
  env.SPRING_CLOUD_DISCOVERY_ENABLED = "false";
  env.SPRING_CLOUD_SERVICE_REGISTRY_AUTO_REGISTRATION_ENABLED = "false";
  env.SPRING_CLOUD_NACOS_CONFIG_ENABLED = "false";
  env.SPRING_CLOUD_NACOS_DISCOVERY_ENABLED = "false";
  env.SPRING_AUTOCONFIGURE_EXCLUDE = [
    "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
    "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
  ].join(",");
  return env;
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

function runProcess(command, args, options, label) {
  const launch = wrapWindowsBatchCommand(command, args);
  const result = spawnSync(launch.command, launch.args, {
    stdio: "inherit",
    windowsHide: true,
    ...options,
  });
  if (result.status !== 0) {
    throw new Error(`${label} command failed with exitCode=${result.status}`);
  }
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

function resolveMavenCommand() {
  const candidates = [
    "C:\\dev\\apache-maven-3.8.8\\bin\\mvn.cmd",
    process.env.MAVEN_CMD,
    "mvn.cmd",
    "mvn",
  ].filter(Boolean);
  for (const candidate of candidates) {
    if (candidate.includes("\\") || candidate.includes("/")) {
      if (existsSync(candidate)) {
        return candidate;
      }
      continue;
    }
    return candidate;
  }
  throw new Error("Maven was not found. Set MAVEN_CMD to run the real UI smoke test.");
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
  throw new Error("Chrome or Edge executable was not found. Set STUDIO_ASSISTANT_SMOKE_BROWSER to run the UI smoke test.");
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
      throw new Error(`desktop runtime backend process exited before login became ready: exitCode=${watchedProcess.exitCode}`);
    }
    try {
      const response = await fetch(`${backendBaseUrl}/api/v1/auth/login`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json",
        },
        body: JSON.stringify({ username: "admin", password: "admin123" }),
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
  throw new Error("Timed out waiting for desktop runtime login readiness.");
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
      const element = Array.from(document.querySelectorAll("button"))
        .find((button) => !button.disabled && button.innerText.trim().includes(expected));
      if (!element) return false;
      element.scrollIntoView({ block: "center", inline: "center" });
      element.click();
      return true;
    })()
  `);
  assert(ok, `Unable to click button text ${text}`);
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
  const text = String(message || "");
  return /Failed to load resource: the server responded with a status of 404 \(Not Found\)/i.test(text)
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
