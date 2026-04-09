import * as monaco from "monaco-editor";
import "monaco-editor/min/vs/editor/editor.main.css";
import EditorWorker from "monaco-editor/esm/vs/editor/editor.worker?worker";
import JsonWorker from "monaco-editor/esm/vs/language/json/json.worker?worker";
import CssWorker from "monaco-editor/esm/vs/language/css/css.worker?worker";
import HtmlWorker from "monaco-editor/esm/vs/language/html/html.worker?worker";
import TsWorker from "monaco-editor/esm/vs/language/typescript/ts.worker?worker";
import "monaco-editor/esm/vs/basic-languages/sql/sql.contribution";
import "monaco-editor/esm/vs/basic-languages/java/java.contribution";
import "monaco-editor/esm/vs/basic-languages/python/python.contribution";
import type { SqlEditorHintSource } from "./editorTypes";

const SQL_KEYWORDS = [
  "SELECT",
  "FROM",
  "WHERE",
  "GROUP BY",
  "ORDER BY",
  "HAVING",
  "LIMIT",
  "INSERT INTO",
  "UPDATE",
  "DELETE FROM",
  "JOIN",
  "LEFT JOIN",
  "RIGHT JOIN",
  "INNER JOIN",
  "ON",
  "AND",
  "OR",
  "NOT",
  "IN",
  "BETWEEN",
  "AS",
  "DISTINCT",
  "CASE",
  "WHEN",
  "THEN",
  "ELSE",
  "END",
  "COUNT(*)",
  "SUM()",
  "MAX()",
  "MIN()",
  "AVG()",
];

const SQL_SNIPPETS = [
  {
    label: "SELECT *",
    insertText: "SELECT *\nFROM ${1:table};",
    documentation: "Simple select statement",
  },
  {
    label: "SELECT COUNT(*)",
    insertText: "SELECT COUNT(*) AS total\nFROM ${1:table};",
    documentation: "Aggregate count query",
  },
  {
    label: "INSERT INTO",
    insertText: "INSERT INTO ${1:table} (${2:columns})\nVALUES (${3:values});",
    documentation: "Insert statement template",
  },
  {
    label: "UPDATE",
    insertText: "UPDATE ${1:table}\nSET ${2:column} = ${3:value}\nWHERE ${4:condition};",
    documentation: "Update statement template",
  },
  {
    label: "DELETE FROM",
    insertText: "DELETE FROM ${1:table}\nWHERE ${2:condition};",
    documentation: "Delete statement template",
  },
];

type HintGetter = () => SqlEditorHintSource | undefined;
type SqlTableHint = NonNullable<SqlEditorHintSource["tables"]>[number];

const RESERVED_ALIAS_WORDS = new Set([
  "select",
  "from",
  "where",
  "join",
  "left",
  "right",
  "inner",
  "outer",
  "full",
  "cross",
  "on",
  "group",
  "order",
  "having",
  "limit",
  "offset",
  "union",
  "as",
  "and",
  "or",
  "set",
  "values",
]);

let configured = false;
let sqlProviderRegistered = false;

const sqlHintSources = new Map<string, HintGetter>();

function configureWorkers() {
  if (configured) {
    return;
  }
  const globalScope = globalThis as typeof globalThis & {
    MonacoEnvironment?: {
      getWorker: (_workerId: string, label: string) => Worker;
    };
  };
  globalScope.MonacoEnvironment = {
    getWorker(_workerId: string, label: string) {
      switch (label) {
        case "json":
          return new JsonWorker();
        case "css":
        case "scss":
        case "less":
          return new CssWorker();
        case "html":
        case "handlebars":
        case "razor":
          return new HtmlWorker();
        case "typescript":
        case "javascript":
          return new TsWorker();
        default:
          return new EditorWorker();
      }
    },
  };
  configured = true;
}

function ensureSqlProvider() {
  if (sqlProviderRegistered) {
    return;
  }
  monaco.languages.registerCompletionItemProvider("sql", {
    triggerCharacters: [" ", ".", "_"],
    provideCompletionItems(model, position) {
      const getter = sqlHintSources.get(model.uri.toString());
      return {
        suggestions: buildSqlSuggestions(model, position, getter?.()),
      };
    },
  });
  sqlProviderRegistered = true;
}

function buildSqlSuggestions(model: monaco.editor.ITextModel,
                             position: monaco.Position,
                             hints?: SqlEditorHintSource): monaco.languages.CompletionItem[] {
  const wordInfo = model.getWordUntilPosition(position);
  const range = new monaco.Range(
    position.lineNumber,
    wordInfo.startColumn,
    position.lineNumber,
    wordInfo.endColumn,
  );
  const prefix = wordInfo.word.trim().toLowerCase();
  const contentBeforeCursor = model.getValueInRange(new monaco.Range(
    1,
    1,
    position.lineNumber,
    position.column,
  ));
  const qualifierMatch = contentBeforeCursor.match(/([A-Za-z_][\w$]*)\.[\w$]*$/);
  const qualifier = qualifierMatch?.[1]?.toLowerCase();
  const tableHints = hints?.tables ?? [];
  const tableMap = buildTableMap(tableHints);
  const aliasMap = extractAliasMap(contentBeforeCursor, tableMap);
  const isTableContext = isTableCompletionContext(contentBeforeCursor);
  const resolvedQualifierTable = qualifier
    ? resolveQualifiedTable(qualifier, aliasMap, tableMap)
    : null;
  const suggestions: monaco.languages.CompletionItem[] = [];

  if (!isTableContext) {
    pushSnippetSuggestions(suggestions, range, prefix);
    pushKeywordSuggestions(suggestions, range, prefix);
  } else {
    pushTableContextKeywordSuggestions(suggestions, range, prefix);
  }

  pushTableSuggestions(suggestions, tableHints, hints, range, prefix);

  if (!isTableContext) {
    pushAliasSuggestions(suggestions, aliasMap, tableMap, range, prefix);
    if (resolvedQualifierTable) {
      pushColumnSuggestions(suggestions, [resolvedQualifierTable], hints, range, prefix, qualifier);
    } else {
      pushColumnSuggestions(suggestions, tableHints, hints, range, prefix);
    }
  }

  return dedupeSuggestions(suggestions);
}

function pushSnippetSuggestions(
  suggestions: monaco.languages.CompletionItem[],
  range: monaco.Range,
  prefix: string,
) {
  for (const snippet of SQL_SNIPPETS) {
    if (!matchesPrefix(snippet.label, prefix)) {
      continue;
    }
    suggestions.push({
      label: snippet.label,
      kind: monaco.languages.CompletionItemKind.Snippet,
      insertText: snippet.insertText,
      insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
      documentation: snippet.documentation,
      range,
      sortText: buildSortText(prefix, 0, snippet.label),
    });
  }
}

function pushKeywordSuggestions(
  suggestions: monaco.languages.CompletionItem[],
  range: monaco.Range,
  prefix: string,
) {
  for (const keyword of SQL_KEYWORDS) {
    if (!matchesPrefix(keyword, prefix)) {
      continue;
    }
    suggestions.push({
      label: keyword,
      kind: monaco.languages.CompletionItemKind.Keyword,
      insertText: keyword,
      range,
      sortText: buildSortText(prefix, 1, keyword),
    });
  }
}

function pushTableContextKeywordSuggestions(
  suggestions: monaco.languages.CompletionItem[],
  range: monaco.Range,
  prefix: string,
) {
  const contextualKeywords = ["JOIN", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "ON", "AS"];
  for (const keyword of contextualKeywords) {
    if (!matchesPrefix(keyword, prefix)) {
      continue;
    }
    suggestions.push({
      label: keyword,
      kind: monaco.languages.CompletionItemKind.Keyword,
      insertText: keyword,
      range,
      sortText: buildSortText(prefix, 1, keyword),
    });
  }
}

function pushTableSuggestions(
  suggestions: monaco.languages.CompletionItem[],
  tables: SqlTableHint[],
  hints: SqlEditorHintSource | undefined,
  range: monaco.Range,
  prefix: string,
) {
  for (const table of tables) {
    if (!matchesPrefix(table.name, prefix) && !matchesPrefix(table.modelName, prefix)) {
      continue;
    }
    suggestions.push({
      label: table.name,
      kind: monaco.languages.CompletionItemKind.Struct,
      insertText: table.name,
      detail: table.modelName || hints?.datasourceName,
      documentation: hints?.datasourceName
        ? `${hints.datasourceName}${table.modelName ? ` · ${table.modelName}` : ""}`
        : table.modelName,
      range,
      sortText: buildSortText(prefix, 2, table.name),
    });
  }
}

function pushAliasSuggestions(
  suggestions: monaco.languages.CompletionItem[],
  aliasMap: Map<string, string>,
  tableMap: Map<string, SqlTableHint>,
  range: monaco.Range,
  prefix: string,
) {
  for (const [alias, tableName] of aliasMap.entries()) {
    if (!matchesPrefix(alias, prefix)) {
      continue;
    }
    const table = tableMap.get(tableName);
    suggestions.push({
      label: alias,
      kind: monaco.languages.CompletionItemKind.Variable,
      insertText: alias,
      detail: table?.name || tableName,
      documentation: table?.modelName ? `${table.name} · ${table.modelName}` : table?.name || tableName,
      range,
      sortText: buildSortText(prefix, 3, alias),
    });
  }
}

function pushColumnSuggestions(
  suggestions: monaco.languages.CompletionItem[],
  tables: SqlTableHint[],
  hints: SqlEditorHintSource | undefined,
  range: monaco.Range,
  prefix: string,
  qualifier?: string,
) {
  for (const table of tables) {
    for (const column of table.columns) {
      const qualifiedLabel = qualifier ? `${qualifier}.${column}` : `${table.name}.${column}`;
      if (!qualifier && !matchesPrefix(column, prefix) && !matchesPrefix(qualifiedLabel, prefix)) {
        continue;
      }
      if (qualifier && !matchesPrefix(column, prefix) && !matchesPrefix(qualifiedLabel, prefix)) {
        continue;
      }
      suggestions.push({
        label: column,
        kind: monaco.languages.CompletionItemKind.Field,
        insertText: column,
        detail: qualifier ? `${qualifier} → ${table.name}` : table.name,
        documentation: qualifiedLabel,
        range,
        sortText: buildSortText(prefix, qualifier ? 4 : 5, qualifiedLabel),
      });
      if (qualifier) {
        continue;
      }
      suggestions.push({
        label: qualifiedLabel,
        kind: monaco.languages.CompletionItemKind.Field,
        insertText: qualifiedLabel,
        detail: table.name,
        documentation: hints?.datasourceName ? `${hints.datasourceName} · ${table.name}` : table.name,
        range,
        sortText: buildSortText(prefix, qualifier ? 5 : 6, qualifiedLabel),
      });
    }
  }
}

function buildTableMap(tables: SqlTableHint[]) {
  const tableMap = new Map<string, SqlTableHint>();
  for (const table of tables) {
    tableMap.set(table.name.toLowerCase(), table);
  }
  return tableMap;
}

function extractAliasMap(source: string, tableMap: Map<string, SqlTableHint>) {
  const aliasMap = new Map<string, string>();
  const aliasRegex = /\b(?:from|join|update|into)\s+([A-Za-z_][\w$]*)(?:\s+(?:as\s+)?([A-Za-z_][\w$]*))?/gi;
  let match: RegExpExecArray | null = aliasRegex.exec(source);
  while (match) {
    const tableName = match[1]?.trim()?.toLowerCase();
    const alias = match[2]?.trim()?.toLowerCase();
    if (tableName && alias && tableMap.has(tableName) && !RESERVED_ALIAS_WORDS.has(alias)) {
      aliasMap.set(alias, tableName);
    }
    match = aliasRegex.exec(source);
  }
  return aliasMap;
}

function resolveQualifiedTable(
  qualifier: string,
  aliasMap: Map<string, string>,
  tableMap: Map<string, SqlTableHint>,
) {
  const tableName = aliasMap.get(qualifier) ?? qualifier;
  return tableMap.get(tableName) ?? null;
}

function isTableCompletionContext(source: string) {
  const normalized = source.replace(/\s+/g, " ").toLowerCase();
  return /(?:\bfrom|\bjoin|\bupdate|\binto)\s+[a-z_0-9$]*$/i.test(normalized);
}

function dedupeSuggestions(items: monaco.languages.CompletionItem[]): monaco.languages.CompletionItem[] {
  const seen = new Map<string, monaco.languages.CompletionItem>();
  for (const item of items) {
    const key = `${item.kind}:${String(item.label)}`;
    if (!seen.has(key)) {
      seen.set(key, item);
    }
  }
  return Array.from(seen.values());
}

function matchesPrefix(candidate?: string, prefix?: string) {
  if (!candidate) {
    return false;
  }
  if (!prefix) {
    return true;
  }
  const normalizedCandidate = candidate.toLowerCase();
  return normalizedCandidate.startsWith(prefix) || normalizedCandidate.includes(prefix);
}

function buildSortText(prefix: string, groupRank: number, value: string) {
  const directHit = prefix && value.toLowerCase().startsWith(prefix) ? "0" : "1";
  return `${groupRank}-${directHit}-${value.toLowerCase()}`;
}

export function ensureMonacoSetup() {
  configureWorkers();
  ensureSqlProvider();
  return monaco;
}

export function bindSqlHintSource(uri: monaco.Uri, getter: HintGetter) {
  const key = uri.toString();
  sqlHintSources.set(key, getter);
  return () => {
    sqlHintSources.delete(key);
  };
}
