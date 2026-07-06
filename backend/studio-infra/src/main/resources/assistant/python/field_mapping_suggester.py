import json
import re
import sys


def normalize(value):
    return re.sub(r"[^a-z0-9]+", "", str(value or "").strip().lower())


def field_name(field):
    if isinstance(field, dict):
        return str(field.get("name") or field.get("fieldName") or field.get("columnName") or "").strip()
    return str(field or "").strip()


def suggest_mappings(source_fields, target_fields):
    source_names = [field_name(item) for item in source_fields or []]
    target_names = [field_name(item) for item in target_fields or []]
    source_by_normalized = {}
    for source in source_names:
        key = normalize(source)
        if key and key not in source_by_normalized:
            source_by_normalized[key] = source

    mappings = []
    unresolved = []
    for target in target_names:
        key = normalize(target)
        source = source_by_normalized.get(key, "")
        if source:
            mappings.append({
                "targetField": target,
                "sourceField": source,
                "strategy": "normalized-name-match",
                "confidence": 1.0,
            })
        else:
            unresolved.append(target)
    return {
        "mappings": mappings,
        "unresolvedTargetFields": unresolved,
        "summary": {
            "sourceFieldCount": len(source_names),
            "targetFieldCount": len(target_names),
            "mappedCount": len(mappings),
            "unresolvedCount": len(unresolved),
        },
    }


def main():
    payload = json.load(sys.stdin)
    result = suggest_mappings(payload.get("sourceFields"), payload.get("targetFields"))
    json.dump({
        "schema": "studio.script-result.v1",
        "success": True,
        "data": result,
    }, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
