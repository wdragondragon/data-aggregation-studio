const { app, BrowserWindow } = require("electron");
const fs = require("fs");
const path = require("path");

function shouldUseDevServer() {
  return process.argv.includes("--dev") || Boolean(process.env.ELECTRON_RENDERER_URL);
}

function buildErrorPage(title, message, detail) {
  return `data:text/html;charset=UTF-8,${encodeURIComponent(`
    <!doctype html>
    <html lang="zh-CN">
      <head>
        <meta charset="UTF-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
        <title>${title}</title>
        <style>
          body {
            margin: 0;
            font-family: "Segoe UI", "PingFang SC", sans-serif;
            color: #0f2342;
            background: linear-gradient(180deg, #f7fbff 0%, #e9f2ff 100%);
          }
          .page {
            min-height: 100vh;
            display: grid;
            place-items: center;
            padding: 24px;
          }
          .panel {
            width: min(720px, 100%);
            padding: 28px;
            border: 1px solid rgba(64, 113, 187, 0.16);
            border-radius: 24px;
            background: rgba(255, 255, 255, 0.92);
            box-shadow: 0 22px 60px rgba(37, 99, 235, 0.12);
          }
          h1 {
            margin: 0 0 12px;
          }
          p {
            margin: 0 0 12px;
            line-height: 1.6;
          }
          pre {
            overflow: auto;
            padding: 14px;
            border-radius: 16px;
            color: #eaf3ff;
            background: linear-gradient(180deg, #17376a 0%, #0d2344 100%);
            white-space: pre-wrap;
            word-break: break-word;
          }
        </style>
      </head>
      <body>
        <div class="page">
          <div class="panel">
            <h1>${title}</h1>
            <p>${message}</p>
            <pre>${detail}</pre>
          </div>
        </div>
      </body>
    </html>
  `)}`;
}

function createWindow() {
  const window = new BrowserWindow({
    width: 1440,
    height: 940,
    minWidth: 1100,
    minHeight: 760,
    show: false,
    backgroundColor: "#f3efe6",
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  const devUrl = process.env.ELECTRON_RENDERER_URL || "http://localhost:5174";
  const filePath = path.join(__dirname, "..", "dist", "renderer", "index.html");
  const useDevServer = shouldUseDevServer();
  let loadAttempts = 0;
  let showingErrorPage = false;

  function showLoadError(detail) {
    if (showingErrorPage) {
      return;
    }
    showingErrorPage = true;
    const hint = useDevServer
      ? "Desktop renderer could not connect to the Vite dev server. Make sure `npm run dev:desktop` is fully ready and port 5174 is reachable."
      : "Desktop renderer failed to load local build assets. Rebuild the desktop renderer with `npm run build:desktop` and try again.";
    window.loadURL(buildErrorPage("Desktop renderer failed to load", hint, detail)).catch(() => {});
  }

  function loadRenderer() {
    loadAttempts += 1;
    if (useDevServer || !fs.existsSync(filePath)) {
      window.loadURL(devUrl).catch((error) => {
        const detail = `Target: ${devUrl}\nAttempt: ${loadAttempts}\nReason: ${error && error.message ? error.message : String(error)}`;
        if (useDevServer && loadAttempts < 8) {
          setTimeout(loadRenderer, 700);
          return;
        }
        showLoadError(detail);
      });
      return;
    }
    window.loadFile(filePath).catch((error) => {
      const detail = `Target: ${filePath}\nReason: ${error && error.message ? error.message : String(error)}`;
      showLoadError(detail);
    });
  }

  window.once("ready-to-show", () => {
    window.show();
  });

  window.webContents.on("did-fail-load", (_event, code, description, validatedURL, isMainFrame) => {
    if (!isMainFrame || showingErrorPage) {
      return;
    }
    const detail = `Target: ${validatedURL}\nCode: ${code}\nReason: ${description}\nAttempt: ${loadAttempts}`;
    if (useDevServer && loadAttempts < 8) {
      setTimeout(loadRenderer, 700);
      return;
    }
    showLoadError(detail);
  });

  window.webContents.on("render-process-gone", (_event, details) => {
    showLoadError(`Renderer process exited.\nReason: ${details.reason}\nExit code: ${details.exitCode}`);
  });

  window.webContents.on("console-message", (_event, level, message, line, sourceId) => {
    console.log(`[desktop-renderer:${level}] ${message} (${sourceId}:${line})`);
  });

  loadRenderer();
}

app.whenReady().then(() => {
  createWindow();
  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") {
    app.quit();
  }
});
