import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const appRoot = fileURLToPath(new URL("..", import.meta.url));
const studioRoot = path.resolve(appRoot, "../../..");

const routerPath = path.join(appRoot, "src/router/index.ts");
const registryPath = path.join(studioRoot, "backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/AssistantStudioOperationRegistry.java");
const gatewayPath = path.join(studioRoot, "backend/studio-server/src/main/java/com/jdragon/studio/server/web/service/AssistantStudioToolExecutionService.java");

const ignoredRoutes = new Set(["/", "/login", "/register"]);

function main() {
  const routePaths = routeTopLevelPaths(readFile(routerPath));
  const operationPaths = operationCatalogPaths(readFile(registryPath));
  const gatewayPaths = gatewaySupportedPaths(readFile(gatewayPath));

  const routesWithoutOperation = routePaths.filter((item) => !operationPaths.includes(item));
  const operationsWithoutGateway = operationPaths.filter((item) => !gatewayPaths.includes(item));
  const gatewayWithoutOperation = gatewayPaths.filter((item) => !operationPaths.includes(item));

  const failures = [];
  if (routesWithoutOperation.length > 0) {
    failures.push(["Frontend routes missing assistant operation catalog entries", routesWithoutOperation]);
  }
  if (operationsWithoutGateway.length > 0) {
    failures.push(["Assistant operation catalog paths missing backend gateway branches", operationsWithoutGateway]);
  }
  if (gatewayWithoutOperation.length > 0) {
    failures.push(["Backend gateway paths missing assistant operation catalog entries", gatewayWithoutOperation]);
  }

  if (failures.length > 0) {
    console.error("Assistant coverage audit failed.");
    for (const [title, items] of failures) {
      console.error(`\n${title}:`);
      for (const item of items) {
        console.error(`  - ${item}`);
      }
    }
    process.exitCode = 1;
    return;
  }

  console.log(`Assistant coverage audit passed: ${routePaths.length} routes, ${operationPaths.length} operations, ${gatewayPaths.length} gateway paths.`);
}

function routeTopLevelPaths(source) {
  const paths = [];
  for (const match of source.matchAll(/path:\s*"([^"\r\n]+)"/g)) {
    const normalized = topLevelPath(match[1]);
    if (normalized && !ignoredRoutes.has(normalized)) {
      paths.push(normalized);
    }
  }
  return uniqueSorted(paths);
}

function operationCatalogPaths(source) {
  const paths = [];
  for (const match of source.matchAll(/result\.add\(operation\(([\s\S]*?)\)\);/g)) {
    const quotedValues = Array.from(match[1].matchAll(/"([^"\\]*(?:\\.[^"\\]*)*)"/g), (item) => item[1]);
    const operationPath = quotedValues[3];
    if (operationPath?.startsWith("/")) {
      paths.push(operationPath);
    }
  }
  return uniqueSorted(paths);
}

function gatewaySupportedPaths(source) {
  return uniqueSorted(Array.from(source.matchAll(/"([^"\r\n]+)"\.equals\(path\)/g), (match) => match[1])
    .filter((item) => item.startsWith("/")));
}

function topLevelPath(rawPath) {
  if (!rawPath || rawPath.includes("*")) {
    return "";
  }
  const match = rawPath.match(/^\/[^/:]+/);
  if (!match) {
    return "";
  }
  return match[0];
}

function uniqueSorted(values) {
  return Array.from(new Set(values)).sort();
}

function readFile(filePath) {
  if (!fs.existsSync(filePath)) {
    throw new Error(`Required audit input is missing: ${filePath}`);
  }
  return fs.readFileSync(filePath, "utf8");
}

main();
