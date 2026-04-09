import json
from datetime import datetime
from urllib import error, request


class ScriptLogger(object):
    def _log(self, level, message):
        timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        print("%s [%s] %s" % (timestamp, level, "" if message is None else str(message)), flush=True)

    def info(self, message):
        self._log("INFO", message)

    def warn(self, message):
        self._log("WARN", message)

    def error(self, message):
        self._log("ERROR", message)

    def debug(self, message):
        self._log("DEBUG", message)


class PythonDataScriptResult(object):
    def __init__(self, success=True, status="SUCCESS", message=None, result_json=None):
        self.success = bool(success)
        self.status = status or ("SUCCESS" if self.success else "FAILED")
        self.message = message
        self.result_json = result_json or {}

    def to_dict(self):
        return {
            "success": self.success,
            "status": self.status,
            "message": self.message,
            "resultJson": self.result_json,
        }


class ScriptServices(object):
    def __init__(self, base_url, token):
        self._base_url = base_url.rstrip("/")
        self._token = token

    def _invoke(self, action, payload=None):
        payload = payload or {}
        body = json.dumps({"action": action, "payload": payload}).encode("utf-8")
        req = request.Request(
            self._base_url + "/invoke",
            data=body,
            headers={
                "Content-Type": "application/json",
                "X-Studio-Python-Token": self._token,
            },
        )
        try:
            with request.urlopen(req) as response:
                parsed = json.loads(response.read().decode("utf-8"))
        except error.HTTPError as ex:
            message = ex.read().decode("utf-8")
            raise RuntimeError("Bridge request failed: %s" % message)
        except error.URLError as ex:
            raise RuntimeError("Bridge request failed: %s" % ex)
        if not parsed.get("success"):
            raise RuntimeError(parsed.get("error") or "Bridge request failed")
        return parsed.get("data")

    def list_datasources(self):
        return self._invoke("list_datasources", {})

    def get_datasource(self, datasource_id):
        return self._invoke("get_datasource", {"datasourceId": datasource_id})

    def list_models(self, datasource_id):
        return self._invoke("list_models", {"datasourceId": datasource_id})

    def execute_sql(self, datasource_id, sql, max_rows=None):
        payload = {"datasourceId": datasource_id, "sql": sql}
        if max_rows is not None:
            payload["maxRows"] = max_rows
        return self._invoke("execute_sql", payload)


class ScriptContext(object):
    def __init__(self, payload):
        bridge = payload.get("bridge") or {}
        self.tenant_id = payload.get("tenantId")
        self.username = payload.get("username")
        self.arguments = payload.get("arguments") or {}
        self.runtime_context = payload.get("runtimeContext") or {}
        self.logger = ScriptLogger()
        self.services = ScriptServices(bridge.get("baseUrl", ""), bridge.get("token", ""))
