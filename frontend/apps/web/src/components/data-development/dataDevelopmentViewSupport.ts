import type { EntityId } from "@studio/api-sdk";

export function normalizeEntityId(value: unknown): EntityId | undefined {
  if (value == null || value === "") {
    return undefined;
  }
  return value as EntityId;
}

export function requireEntityId(value: unknown, fieldName: string): EntityId {
  const normalized = normalizeEntityId(value);
  if (normalized == null) {
    throw new Error(`${fieldName} is required`);
  }
  return normalized;
}

export function defaultJavaTemplate() {
  return [
    "import com.jdragon.studio.infra.script.java.JavaDataScript;",
    "import com.jdragon.studio.infra.script.java.JavaDataScriptContext;",
    "import com.jdragon.studio.infra.script.java.JavaDataScriptResult;",
    "",
    "public class DemoJavaDataScript implements JavaDataScript {",
    "    @Override",
    "    public JavaDataScriptResult execute(JavaDataScriptContext context) throws Exception {",
    "        context.getLogger().info(\"Java script started by \" + context.getUsername());",
    "        JavaDataScriptResult result = new JavaDataScriptResult();",
    "        result.setMessage(\"Java script executed successfully\");",
    "        result.getResultJson().put(\"tenantId\", context.getTenantId());",
    "        result.getResultJson().put(\"arguments\", context.getArguments());",
    "        return result;",
    "    }",
    "}",
  ].join("\n");
}

export function defaultPythonTemplate() {
  return [
    "def execute(context):",
    "    context.logger.info(\"Python script started by %s\" % context.username)",
    "    datasources = context.services.list_datasources()",
    "    return {",
    "        \"tenantId\": context.tenant_id,",
    "        \"arguments\": context.arguments,",
    "        \"datasourceCount\": len(datasources),",
    "    }",
  ].join("\n");
}
