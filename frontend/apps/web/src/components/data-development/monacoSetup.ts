import * as monaco from "monaco-editor";
import "monaco-editor/min/vs/editor/editor.main.css";
import EditorWorker from "monaco-editor/esm/vs/editor/editor.worker?worker";
import JsonWorker from "monaco-editor/esm/vs/language/json/json.worker?worker";
import CssWorker from "monaco-editor/esm/vs/language/css/css.worker?worker";
import HtmlWorker from "monaco-editor/esm/vs/language/html/html.worker?worker";
import TsWorker from "monaco-editor/esm/vs/language/typescript/ts.worker?worker";
import "monaco-editor/esm/vs/language/json/monaco.contribution";
import "monaco-editor/esm/vs/basic-languages/sql/sql.contribution";
import "monaco-editor/esm/vs/basic-languages/java/java.contribution";
import "monaco-editor/esm/vs/basic-languages/python/python.contribution";
import type { JavaImportHint, JavaMemberHint } from "@studio/api-sdk";
import type { JavaEditorHintSource, SqlEditorHintSource } from "./editorTypes";

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

const JAVA_KEYWORDS = [
  "abstract",
  "boolean",
  "break",
  "catch",
  "class",
  "continue",
  "else",
  "false",
  "final",
  "for",
  "if",
  "implements",
  "import",
  "new",
  "null",
  "private",
  "protected",
  "public",
  "return",
  "static",
  "throw",
  "throws",
  "true",
  "try",
  "void",
  "while",
];

const JAVA_SNIPPETS = [
  {
    label: "if",
    insertText: "if (${1:condition}) {\n  ${2}\n}",
    documentation: "if statement",
  },
  {
    label: "for",
    insertText: "for (${1:int i = 0}; ${2:i < size}; ${3:i++}) {\n  ${4}\n}",
    documentation: "for loop",
  },
  {
    label: "try",
    insertText: "try {\n  ${1}\n} catch (${2:Exception} ${3:ex}) {\n  ${4:throw ex;}\n}",
    documentation: "try/catch block",
  },
  {
    label: "JavaDataScriptResult",
    insertText: "JavaDataScriptResult ${1:result} = new JavaDataScriptResult();",
    documentation: "Create Java script result",
  },
];

const JAVA_LANG_CLASS_MAP = new Map<string, string>([
  ["Boolean", "java.lang.Boolean"],
  ["Byte", "java.lang.Byte"],
  ["Character", "java.lang.Character"],
  ["Class", "java.lang.Class"],
  ["Double", "java.lang.Double"],
  ["Float", "java.lang.Float"],
  ["Integer", "java.lang.Integer"],
  ["Long", "java.lang.Long"],
  ["Math", "java.lang.Math"],
  ["Object", "java.lang.Object"],
  ["Short", "java.lang.Short"],
  ["String", "java.lang.String"],
  ["StringBuilder", "java.lang.StringBuilder"],
  ["System", "java.lang.System"],
]);

const JAVA_PRIMITIVE_TYPES = new Set(["boolean", "byte", "char", "double", "float", "int", "long", "short", "void"]);
const JAVA_NON_TYPE_WORDS = new Set([...JAVA_KEYWORDS, "var"]);

type HintGetter = () => SqlEditorHintSource | undefined;
type JavaHintGetter = () => JavaEditorHintSource | undefined;
type SqlTableHint = NonNullable<SqlEditorHintSource["tables"]>[number];

interface JavaVariableHint {
  name: string;
  typeName: string;
  scope: "parameter" | "local" | "field";
}

interface JavaSourceAnalysis {
  imports: Map<string, string>;
  wildcardImports: string[];
  variables: Map<string, JavaVariableHint>;
}

interface JavaMemberContext {
  qualifier: string;
  prefix: string;
  range: monaco.Range;
}

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
let javaProviderRegistered = false;

const sqlHintSources = new Map<string, HintGetter>();
const javaHintSources = new Map<string, JavaHintGetter>();

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

function ensureJavaProvider() {
  if (javaProviderRegistered) {
    return;
  }
  monaco.languages.registerCompletionItemProvider("java", {
    triggerCharacters: [" ", ".", "_"],
    async provideCompletionItems(model, position) {
      const getter = javaHintSources.get(model.uri.toString());
      const source = getter?.();
      if (!source) {
        return { suggestions: [] };
      }
      try {
        return {
          suggestions: await buildJavaSuggestions(model, position, source),
        };
      } catch {
        return { suggestions: [] };
      }
    },
  });
  javaProviderRegistered = true;
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

async function buildJavaSuggestions(
  model: monaco.editor.ITextModel,
  position: monaco.Position,
  source: JavaEditorHintSource,
): Promise<monaco.languages.CompletionItem[]> {
  const importRange = resolveJavaImportRange(model, position);
  const wordInfo = model.getWordUntilPosition(position);
  const defaultRange = new monaco.Range(
    position.lineNumber,
    wordInfo.startColumn,
    position.lineNumber,
    wordInfo.endColumn,
  );
  if (importRange) {
    const prefix = model.getValueInRange(importRange).trim().toLowerCase();
    const importHints = await safeLoadJavaImports(source, prefix, 100);
    return buildJavaClassSuggestions(model, importRange, prefix, importHints, true);
  }

  const memberContext = resolveJavaMemberContext(model, position);
  const analysis = analyzeJavaSource(model.getValue());
  if (memberContext) {
    const importHints = /^[A-Z]/.test(memberContext.qualifier)
      ? await safeLoadJavaImports(source, memberContext.qualifier, 20)
      : [];
    const target = resolveJavaMemberTarget(memberContext.qualifier, analysis, importHints);
    if (!target?.className) {
      return [];
    }
    const members = await safeLoadJavaMembers(source, target.className, memberContext.prefix, target.staticOnly, 100);
    return dedupeSuggestions(buildJavaMemberSuggestions(memberContext.range, memberContext.prefix, members));
  }

  const prefix = wordInfo.word.trim().toLowerCase();
  const importHints = await safeLoadJavaImports(source, prefix, 100);
  const suggestions: monaco.languages.CompletionItem[] = [];
  pushJavaSnippetSuggestions(suggestions, defaultRange, prefix);
  pushJavaKeywordSuggestions(suggestions, defaultRange, prefix);
  pushJavaVariableSuggestions(suggestions, analysis, defaultRange, prefix);
  suggestions.push(...buildJavaClassSuggestions(model, defaultRange, prefix, importHints, false));
  return dedupeSuggestions(suggestions);
}

async function safeLoadJavaImports(source: JavaEditorHintSource, keyword: string, limit: number) {
  try {
    return await Promise.resolve(source.loadImports(keyword, limit));
  } catch {
    return [];
  }
}

async function safeLoadJavaMembers(
  source: JavaEditorHintSource,
  className: string,
  keyword: string,
  staticOnly: boolean,
  limit: number,
) {
  try {
    return await Promise.resolve(source.loadMembers(className, keyword, staticOnly, limit));
  } catch {
    return [];
  }
}

function buildJavaClassSuggestions(
  model: monaco.editor.ITextModel,
  range: monaco.Range,
  prefix: string,
  hints: JavaImportHint[],
  importSuggestion: boolean,
): monaco.languages.CompletionItem[] {
  const suggestions: monaco.languages.CompletionItem[] = [];
  const seen = new Set<string>();
  for (const hint of hints) {
    if (!hint.qualifiedName || seen.has(hint.qualifiedName)) {
      continue;
    }
    seen.add(hint.qualifiedName);
    if (!matchesPrefix(hint.qualifiedName, prefix) && !matchesPrefix(hint.simpleName, prefix)) {
      continue;
    }
    suggestions.push({
      label: importSuggestion ? hint.qualifiedName : hint.simpleName || hint.qualifiedName,
      kind: monaco.languages.CompletionItemKind.Class,
      insertText: importSuggestion ? hint.qualifiedName : hint.simpleName || hint.qualifiedName,
      detail: hint.qualifiedName,
      documentation: hint.source || hint.packageName,
      range,
      sortText: buildJavaClassSortText(prefix, importSuggestion ? 0 : 2, hint),
      additionalTextEdits: importSuggestion ? undefined : buildJavaImportTextEdits(model, hint),
    });
  }
  return suggestions;
}

function buildJavaClassSortText(prefix: string, groupRank: number, hint: JavaImportHint) {
  const simpleName = hint.simpleName || hint.qualifiedName || "";
  const qualifiedName = hint.qualifiedName || simpleName;
  const normalizedPrefix = prefix.toLowerCase();
  const normalizedSimpleName = simpleName.toLowerCase();
  const matchRank = !normalizedPrefix
    ? "0"
    : normalizedSimpleName === normalizedPrefix
      ? "0"
      : normalizedSimpleName.startsWith(normalizedPrefix)
        ? "1"
        : normalizedSimpleName.includes(normalizedPrefix)
          ? "2"
          : qualifiedName.toLowerCase().startsWith(normalizedPrefix)
            ? "3"
            : "4";
  return `${groupRank}-${javaClassSourceRank(hint)}-${matchRank}-${normalizedSimpleName}-${qualifiedName.toLowerCase()}`;
}

function javaClassSourceRank(hint: JavaImportHint) {
  const qualifiedName = hint.qualifiedName || "";
  if (qualifiedName.startsWith("com.jdragon.studio.infra.script.java.")) {
    return "0";
  }
  if (hint.source === "DEPENDENCY") {
    return "1";
  }
  if (qualifiedName.startsWith("com.jdragon.studio.")) {
    return "2";
  }
  if (qualifiedName.startsWith("com.jdragon.aggregation.")) {
    return "3";
  }
  if (hint.source === "JDK" || qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.")) {
    return "4";
  }
  return "5";
}

function buildJavaMemberSuggestions(
  range: monaco.Range,
  prefix: string,
  members: JavaMemberHint[],
): monaco.languages.CompletionItem[] {
  const suggestions: monaco.languages.CompletionItem[] = [];
  for (const member of members) {
    const label = member.kind === "METHOD"
      ? member.displaySignature || `${member.name}()`
      : member.name;
    if (!matchesPrefix(member.name, prefix) && !matchesPrefix(label, prefix)) {
      continue;
    }
    suggestions.push({
      label,
      kind: member.kind === "METHOD"
        ? monaco.languages.CompletionItemKind.Method
        : monaco.languages.CompletionItemKind.Field,
      insertText: member.insertText || member.name,
      insertTextRules: member.kind === "METHOD"
        ? monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet
        : undefined,
      detail: member.returnType,
      documentation: member.declaringClass,
      range,
      sortText: buildSortText(prefix, member.kind === "METHOD" ? 0 : 1, label),
    });
  }
  return suggestions;
}

function pushJavaSnippetSuggestions(
  suggestions: monaco.languages.CompletionItem[],
  range: monaco.Range,
  prefix: string,
) {
  for (const snippet of JAVA_SNIPPETS) {
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

function pushJavaKeywordSuggestions(
  suggestions: monaco.languages.CompletionItem[],
  range: monaco.Range,
  prefix: string,
) {
  for (const keyword of JAVA_KEYWORDS) {
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

function pushJavaVariableSuggestions(
  suggestions: monaco.languages.CompletionItem[],
  analysis: JavaSourceAnalysis,
  range: monaco.Range,
  prefix: string,
) {
  for (const variable of analysis.variables.values()) {
    if (!matchesPrefix(variable.name, prefix)) {
      continue;
    }
    suggestions.push({
      label: variable.name,
      kind: variable.scope === "field"
        ? monaco.languages.CompletionItemKind.Field
        : monaco.languages.CompletionItemKind.Variable,
      insertText: variable.name,
      detail: variable.typeName,
      range,
      sortText: buildSortText(prefix, variable.scope === "parameter" ? 1 : 2, variable.name),
    });
  }
}

function resolveJavaMemberContext(model: monaco.editor.ITextModel, position: monaco.Position): JavaMemberContext | null {
  const lineBeforeCursor = model.getLineContent(position.lineNumber).slice(0, Math.max(0, position.column - 1));
  const match = lineBeforeCursor.match(/([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)?$/);
  if (!match) {
    return null;
  }
  const qualifier = match[1] ?? "";
  const prefix = match[2] ?? "";
  return {
    qualifier,
    prefix: prefix.toLowerCase(),
    range: new monaco.Range(
      position.lineNumber,
      position.column - prefix.length,
      position.lineNumber,
      position.column,
    ),
  };
}

function resolveJavaMemberTarget(
  qualifier: string,
  analysis: JavaSourceAnalysis,
  importHints: JavaImportHint[],
) {
  const variable = analysis.variables.get(qualifier);
  if (variable) {
    return {
      className: resolveJavaTypeName(variable.typeName, analysis, importHints),
      staticOnly: false,
    };
  }
  if (/^[A-Z]/.test(qualifier)) {
    return {
      className: resolveJavaTypeName(qualifier, analysis, importHints),
      staticOnly: true,
    };
  }
  return null;
}

function analyzeJavaSource(source: string): JavaSourceAnalysis {
  const sanitized = stripJavaNoise(source);
  const imports = new Map<string, string>();
  const wildcardImports: string[] = [];
  const variables = new Map<string, JavaVariableHint>();

  const importRegex = /^\s*import\s+(?!static\s+)([\w.]+|\w[\w.]*\.\*)\s*;/gm;
  let importMatch: RegExpExecArray | null = importRegex.exec(sanitized);
  while (importMatch) {
    const imported = importMatch[1];
    if (imported.endsWith(".*")) {
      wildcardImports.push(imported.slice(0, -2));
    } else {
      const index = imported.lastIndexOf(".");
      imports.set(index < 0 ? imported : imported.slice(index + 1), imported);
    }
    importMatch = importRegex.exec(sanitized);
  }

  const parameterRegex = /\(([^()]*)\)\s*(?:throws\s+[^{]+)?\{/g;
  let parameterMatch: RegExpExecArray | null = parameterRegex.exec(sanitized);
  while (parameterMatch) {
    for (const parameter of splitJavaParameters(parameterMatch[1])) {
      const parsed = parseJavaTypedName(parameter);
      if (parsed) {
        variables.set(parsed.name, { name: parsed.name, typeName: parsed.typeName, scope: "parameter" });
      }
    }
    parameterMatch = parameterRegex.exec(sanitized);
  }

  const inferredVarRegex = /(?:^|[;{}\n]\s*)var\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)/g;
  let inferredMatch: RegExpExecArray | null = inferredVarRegex.exec(sanitized);
  while (inferredMatch) {
    variables.set(inferredMatch[1], { name: inferredMatch[1], typeName: inferredMatch[2], scope: "local" });
    inferredMatch = inferredVarRegex.exec(sanitized);
  }

  const variableRegex = /(?:^|[;{}\n]\s*)(?:(?:public|private|protected|static|final|transient|volatile)\s+)*([A-Za-z_$][\w$]*(?:\s*<[^;{}()=]+>)?(?:\s*\[\])?)\s+([A-Za-z_$][\w$]*)\s*(?==|;|,)/g;
  let variableMatch: RegExpExecArray | null = variableRegex.exec(sanitized);
  while (variableMatch) {
    const typeName = normalizeJavaTypeName(variableMatch[1]);
    const name = variableMatch[2];
    if (typeName && name && !JAVA_NON_TYPE_WORDS.has(typeName)) {
      const scope = isLikelyFieldDeclaration(sanitized, variableMatch.index) ? "field" : "local";
      variables.set(name, { name, typeName, scope });
    }
    variableMatch = variableRegex.exec(sanitized);
  }

  return { imports, wildcardImports, variables };
}

function stripJavaNoise(source: string) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, " ")
    .replace(/\/\/.*$/gm, " ")
    .replace(/"(?:\\.|[^"\\])*"/g, "\"\"")
    .replace(/'(?:\\.|[^'\\])*'/g, "''");
}

function splitJavaParameters(value: string) {
  return value.split(",").map((item) => item.trim()).filter(Boolean);
}

function parseJavaTypedName(value: string) {
  const cleaned = value
    .replace(/@\w+(?:\([^)]*\))?\s*/g, "")
    .replace(/\bfinal\s+/g, "")
    .trim();
  const match = cleaned.match(/^(.+?)\s+([A-Za-z_$][\w$]*)$/);
  if (!match) {
    return null;
  }
  const typeName = normalizeJavaTypeName(match[1]);
  if (!typeName || JAVA_NON_TYPE_WORDS.has(typeName)) {
    return null;
  }
  return { typeName, name: match[2] };
}

function normalizeJavaTypeName(value: string) {
  return value
    .replace(/@\w+(?:\([^)]*\))?\s*/g, "")
    .replace(/\b(?:final|public|private|protected|static|transient|volatile)\s+/g, "")
    .replace(/<[^<>]*>/g, "")
    .replace(/\s+/g, " ")
    .trim()
    .replace(/\s*\[\s*\]/g, "[]");
}

function resolveJavaTypeName(
  value: string,
  analysis: JavaSourceAnalysis,
  importHints: JavaImportHint[],
) {
  const typeName = normalizeJavaTypeName(value);
  if (!typeName || JAVA_PRIMITIVE_TYPES.has(typeName)) {
    return "";
  }
  const arraySuffix = typeName.endsWith("[]") ? "[]" : "";
  const baseTypeName = arraySuffix ? typeName.slice(0, -2) : typeName;
  if (baseTypeName.includes(".")) {
    return baseTypeName;
  }
  const imported = analysis.imports.get(baseTypeName);
  if (imported) {
    return imported;
  }
  const javaLangClass = JAVA_LANG_CLASS_MAP.get(baseTypeName);
  if (javaLangClass) {
    return javaLangClass;
  }
  const hinted = importHints.find((hint) => hint.simpleName === baseTypeName);
  if (hinted?.qualifiedName) {
    return hinted.qualifiedName;
  }
  if (analysis.wildcardImports.length > 0) {
    return `${analysis.wildcardImports[0]}.${baseTypeName}`;
  }
  return "";
}

function isLikelyFieldDeclaration(source: string, index: number) {
  const before = source.slice(0, index);
  const lastOpenBrace = before.lastIndexOf("{");
  const lastCloseBrace = before.lastIndexOf("}");
  if (lastOpenBrace < 0 || lastCloseBrace > lastOpenBrace) {
    return false;
  }
  const classBodyPrefix = before.slice(0, lastOpenBrace);
  return /\bclass\s+[A-Za-z_$][\w$]*/.test(classBodyPrefix)
    && !/\b(?:if|for|while|switch|catch|try|else)\s*\([^)]*$/.test(before.slice(lastOpenBrace));
}

function resolveJavaImportRange(model: monaco.editor.ITextModel, position: monaco.Position): monaco.Range | null {
  const lineBeforeCursor = model.getLineContent(position.lineNumber).slice(0, Math.max(0, position.column - 1));
  const match = lineBeforeCursor.match(/\bimport\s+(?:static\s+)?([\w.]*)$/);
  if (!match) {
    return null;
  }
  const typedImport = match[1] ?? "";
  return new monaco.Range(
    position.lineNumber,
    position.column - typedImport.length,
    position.lineNumber,
    position.column,
  );
}

function buildJavaImportTextEdits(model: monaco.editor.ITextModel, hint: JavaImportHint) {
  if (!hint.packageName || !hint.qualifiedName || hint.qualifiedName.startsWith("java.lang.")) {
    return undefined;
  }
  const source = model.getValue();
  const importLine = `import ${hint.qualifiedName};`;
  if (source.includes(importLine)) {
    return undefined;
  }
  const lines = source.split(/\r?\n/);
  let insertLine = 1;
  for (let index = 0; index < lines.length; index += 1) {
    if (/^\s*import\s+/.test(lines[index])) {
      insertLine = index + 2;
    }
  }
  if (insertLine === 1) {
    const packageIndex = lines.findIndex((line) => /^\s*package\s+/.test(line));
    if (packageIndex >= 0) {
      insertLine = packageIndex + 2;
    }
  }
  return [{
    range: new monaco.Range(insertLine, 1, insertLine, 1),
    text: `${importLine}\n`,
  }];
}

export function ensureMonacoSetup() {
  configureWorkers();
  ensureSqlProvider();
  ensureJavaProvider();
  return monaco;
}

export function bindSqlHintSource(uri: monaco.Uri, getter: HintGetter) {
  const key = uri.toString();
  sqlHintSources.set(key, getter);
  return () => {
    sqlHintSources.delete(key);
  };
}

export function bindJavaHintSource(uri: monaco.Uri, getter: JavaHintGetter) {
  const key = uri.toString();
  javaHintSources.set(key, getter);
  return () => {
    javaHintSources.delete(key);
  };
}
