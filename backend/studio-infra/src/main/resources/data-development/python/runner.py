import importlib.util
import json
import sys
import traceback

from studio_runtime import PythonDataScriptResult, ScriptContext


def load_json(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path, payload):
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)


def normalize_result(value):
    if value is None:
        return PythonDataScriptResult(message="Python script executed successfully").to_dict()
    if isinstance(value, PythonDataScriptResult):
        return value.to_dict()
    if isinstance(value, dict):
        return PythonDataScriptResult(message="Python script executed successfully", result_json=value).to_dict()
    return PythonDataScriptResult(
        message="Python script executed successfully",
        result_json={"value": value},
    ).to_dict()


def main():
    if len(sys.argv) < 4:
        raise RuntimeError("Runner arguments are invalid")
    script_path = sys.argv[1]
    context_path = sys.argv[2]
    result_path = sys.argv[3]
    context_payload = load_json(context_path)
    context = ScriptContext(context_payload)

    try:
        spec = importlib.util.spec_from_file_location("studio_user_script", script_path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        execute = getattr(module, "execute", None)
        if not callable(execute):
            raise RuntimeError("Python script must define callable execute(context)")
        result_payload = normalize_result(execute(context))
        write_json(result_path, result_payload)
        return 0
    except Exception as ex:
        context.logger.error("Python script execution failed: %s" % ex)
        write_json(result_path, {
            "success": False,
            "status": "FAILED",
            "message": str(ex),
            "resultJson": {
                "exceptionType": ex.__class__.__name__,
                "stackTrace": traceback.format_exc(),
            },
        })
        return 1


if __name__ == "__main__":
    sys.exit(main())
