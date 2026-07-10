import type { ScriptType } from "@studio/api-sdk";

const INDENT = "  ";

export interface ScriptFormatResult {
  supported: boolean;
  value: string;
}

export function formatScriptContent(scriptType: ScriptType, source: string, selected = false): ScriptFormatResult {
  const formatter = resolveFormatter(scriptType);
  if (!formatter) {
    return { supported: false, value: source };
  }
  const normalized = normalizeLineEndings(source);
  const formatted = selected && normalized.includes("\n")
    ? formatWithCommonIndent(normalized, formatter)
    : formatter(normalized);
  return {
    supported: true,
    value: preserveTrailingNewline(normalized, formatted),
  };
}

function resolveFormatter(scriptType: ScriptType): ((source: string) => string) | null {
  switch (scriptType) {
    case "SQL":
    case "FLINK_QUESTION_SQL":
      return formatSql;
    case "JAVA":
      return formatBraceBasedScript;
    case "PYTHON":
      return formatPython;
    default:
      return null;
  }
}

function normalizeLineEndings(source: string) {
  return source.replace(/\r\n?/g, "\n");
}

function preserveTrailingNewline(source: string, formatted: string) {
  if (source.endsWith("\n") && formatted && !formatted.endsWith("\n")) {
    return `${formatted}\n`;
  }
  return formatted;
}

function formatWithCommonIndent(source: string, formatter: (source: string) => string) {
  const lines = source.split("\n");
  const commonIndent = resolveCommonIndent(lines);
  if (!commonIndent) {
    return formatter(source);
  }
  const stripped = lines
    .map((line) => line.trim() ? line.slice(commonIndent.length) : "")
    .join("\n");
  const formatted = formatter(stripped);
  return formatted
    .split("\n")
    .map((line) => line.trim() ? `${commonIndent}${line}` : line)
    .join("\n");
}

function resolveCommonIndent(lines: string[]) {
  const indents = lines
    .filter((line) => line.trim())
    .map((line) => line.match(/^\s*/)?.[0] ?? "");
  if (!indents.length) {
    return "";
  }
  return indents.reduce((common, indent) => {
    let index = 0;
    while (index < common.length && index < indent.length && common[index] === indent[index]) {
      index += 1;
    }
    return common.slice(0, index);
  });
}

interface SqlToken {
  text: string;
  kind: "word" | "quoted" | "comment" | "punct" | "operator" | "other";
}

const SQL_KEYWORD_SET = new Set([
  "ADD",
  "ALTER",
  "AND",
  "AS",
  "BETWEEN",
  "BY",
  "CASE",
  "CREATE",
  "DELETE",
  "DESC",
  "DISTINCT",
  "DROP",
  "ELSE",
  "END",
  "EXISTS",
  "FROM",
  "FULL",
  "GROUP",
  "HAVING",
  "IN",
  "INNER",
  "INSERT",
  "INTO",
  "IS",
  "JOIN",
  "LEFT",
  "LIKE",
  "LIMIT",
  "NOT",
  "NULL",
  "OFFSET",
  "ON",
  "OR",
  "ORDER",
  "OUTER",
  "OVER",
  "PARTITION",
  "RIGHT",
  "SELECT",
  "SET",
  "THEN",
  "UNION",
  "UPDATE",
  "VALUES",
  "WHEN",
  "WHERE",
  "WITH",
]);

const SQL_CLAUSE_KEYWORDS = new Set([
  "SELECT",
  "FROM",
  "WHERE",
  "GROUP BY",
  "ORDER BY",
  "HAVING",
  "LIMIT",
  "OFFSET",
  "UNION",
  "UNION ALL",
  "INSERT INTO",
  "VALUES",
  "UPDATE",
  "SET",
  "DELETE FROM",
  "CREATE TABLE",
  "ALTER TABLE",
  "DROP TABLE",
  "WITH",
]);

const SQL_JOIN_KEYWORDS = new Set([
  "JOIN",
  "LEFT JOIN",
  "RIGHT JOIN",
  "INNER JOIN",
  "FULL JOIN",
  "CROSS JOIN",
  "LEFT OUTER JOIN",
  "RIGHT OUTER JOIN",
  "FULL OUTER JOIN",
]);

function formatSql(source: string) {
  const statements = splitSqlStatements(source)
    .map((statement) => statement.trim())
    .filter(Boolean);
  if (!statements.length) {
    return "";
  }
  return statements.map(formatSqlStatement).join("\n\n");
}

function splitSqlStatements(source: string) {
  const tokens = tokenizeSql(source);
  const statements: string[] = [];
  let current = "";
  for (const token of tokens) {
    current = appendRawSqlToken(current, token.text);
    if (token.kind === "punct" && token.text === ";") {
      statements.push(current);
      current = "";
    }
  }
  if (current.trim()) {
    statements.push(current);
  }
  return statements;
}

function appendRawSqlToken(current: string, token: string) {
  if (!current) {
    return token;
  }
  if (/^[,.;)]$/.test(token)) {
    return `${current.trimEnd()}${token}`;
  }
  if (token === "(") {
    return `${current.trimEnd()} ${token}`;
  }
  return `${current.trimEnd()} ${token}`;
}

function formatSqlStatement(statement: string) {
  const tokens = tokenizeSql(statement);
  const lines: string[] = [];
  let current = "";
  let currentIndent = 0;
  let parenDepth = 0;
  let clause = "";
  let previousWord = "";

  const pushLine = () => {
    const text = current.trimEnd();
    if (text.trim()) {
      lines.push(text);
    }
    current = "";
  };

  const startLine = (indent: number) => {
    pushLine();
    currentIndent = Math.max(0, indent);
  };

  const appendToken = (text: string) => {
    if (!current.trim()) {
      current = `${INDENT.repeat(currentIndent)}${text}`;
      return;
    }
    if (/^[,.;)]$/.test(text)) {
      current = `${current.trimEnd()}${text}`;
      return;
    }
    if (text === "(") {
      const compactFunctionCall = previousWord && !SQL_KEYWORD_SET.has(previousWord);
      current = compactFunctionCall ? `${current.trimEnd()}(` : `${current.trimEnd()} (`;
      return;
    }
    if (current.endsWith("(")) {
      current += text;
      return;
    }
    if (/^(=|<>|!=|<=|>=|<|>|\+|-|\*|\/)$/.test(text)) {
      current = `${current.trimEnd()} ${text}`;
      return;
    }
    current = `${current.trimEnd()} ${text}`;
  };

  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    if (!token) {
      continue;
    }
    if (token.kind === "comment") {
      startLine(0);
      appendToken(token.text.trim());
      pushLine();
      previousWord = "";
      continue;
    }
    if (token.kind === "punct") {
      if (token.text === "(") {
        appendToken(token.text);
        parenDepth += 1;
      } else if (token.text === ")") {
        parenDepth = Math.max(0, parenDepth - 1);
        appendToken(token.text);
      } else if (token.text === ",") {
        appendToken(token.text);
        if (parenDepth === 0) {
          pushLine();
          currentIndent = clause === "SELECT" || clause === "WHERE" || clause === "SET" ? 1 : 0;
        }
      } else if (token.text === ";") {
        appendToken(token.text);
        pushLine();
      } else {
        appendToken(token.text);
      }
      previousWord = "";
      continue;
    }

    const normalized = normalizeSqlToken(token);
    const compound = resolveSqlCompoundKeyword(tokens, index);
    if (compound && parenDepth === 0) {
      if (SQL_JOIN_KEYWORDS.has(compound.text)) {
        startLine(0);
        clause = "JOIN";
      } else if (SQL_CLAUSE_KEYWORDS.has(compound.text)) {
        startLine(0);
        clause = compound.text;
      }
      appendToken(compound.text);
      const compoundParts = compound.text.split(" ");
      previousWord = compoundParts[compoundParts.length - 1] ?? "";
      index = compound.nextIndex;
      continue;
    }

    if (parenDepth === 0 && (normalized === "AND" || normalized === "OR") && (clause === "WHERE" || clause === "HAVING" || clause === "ON")) {
      startLine(1);
      appendToken(normalized);
      previousWord = normalized;
      continue;
    }
    if (parenDepth === 0 && normalized === "ON") {
      startLine(1);
      clause = "ON";
      appendToken(normalized);
      previousWord = normalized;
      continue;
    }
    appendToken(normalized);
    previousWord = token.kind === "word" ? normalized : "";
  }

  pushLine();
  return lines.join("\n");
}

function resolveSqlCompoundKeyword(tokens: SqlToken[], index: number) {
  const first = normalizeSqlToken(tokens[index]);
  if (tokens[index]?.kind !== "word") {
    return null;
  }
  const second = normalizeSqlToken(tokens[index + 1]);
  const third = normalizeSqlToken(tokens[index + 2]);
  const threeWord = `${first} ${second} ${third}`.trim();
  const twoWord = `${first} ${second}`.trim();
  if (SQL_JOIN_KEYWORDS.has(threeWord) || SQL_CLAUSE_KEYWORDS.has(threeWord)) {
    return { text: threeWord, nextIndex: index + 2 };
  }
  if (SQL_JOIN_KEYWORDS.has(twoWord) || SQL_CLAUSE_KEYWORDS.has(twoWord)) {
    return { text: twoWord, nextIndex: index + 1 };
  }
  if (SQL_JOIN_KEYWORDS.has(first) || SQL_CLAUSE_KEYWORDS.has(first)) {
    return { text: first, nextIndex: index };
  }
  return null;
}

function normalizeSqlToken(token?: SqlToken) {
  if (!token) {
    return "";
  }
  const value = token.text;
  if (token.kind === "word") {
    const upper = value.toUpperCase();
    return SQL_KEYWORD_SET.has(upper) ? upper : value;
  }
  return value;
}

function tokenizeSql(source: string): SqlToken[] {
  const tokens: SqlToken[] = [];
  let index = 0;
  while (index < source.length) {
    const char = source[index] ?? "";
    const next = source[index + 1] ?? "";
    if (/\s/.test(char)) {
      index += 1;
      continue;
    }
    if (char === "-" && next === "-") {
      const end = source.indexOf("\n", index);
      const stop = end < 0 ? source.length : end;
      tokens.push({ text: source.slice(index, stop), kind: "comment" });
      index = stop;
      continue;
    }
    if (char === "/" && next === "*") {
      const end = source.indexOf("*/", index + 2);
      const stop = end < 0 ? source.length : end + 2;
      tokens.push({ text: source.slice(index, stop), kind: "comment" });
      index = stop;
      continue;
    }
    if (char === "'" || char === "\"" || char === "`") {
      const quoted = readQuoted(source, index, char);
      tokens.push({ text: quoted.text, kind: "quoted" });
      index = quoted.nextIndex;
      continue;
    }
    if (char === "[") {
      const end = source.indexOf("]", index + 1);
      const stop = end < 0 ? source.length : end + 1;
      tokens.push({ text: source.slice(index, stop), kind: "quoted" });
      index = stop;
      continue;
    }
    if (/[,();]/.test(char)) {
      tokens.push({ text: char, kind: "punct" });
      index += 1;
      continue;
    }
    if (/[+\-*/%=<>!]/.test(char)) {
      const operator = /[=<>]/.test(next) ? source.slice(index, index + 2) : char;
      tokens.push({ text: operator, kind: "operator" });
      index += operator.length;
      continue;
    }
    if (/[A-Za-z0-9_$.]/.test(char)) {
      let end = index + 1;
      while (end < source.length && /[A-Za-z0-9_$.]/.test(source[end] ?? "")) {
        end += 1;
      }
      tokens.push({ text: source.slice(index, end), kind: "word" });
      index = end;
      continue;
    }
    tokens.push({ text: char, kind: "other" });
    index += 1;
  }
  return tokens;
}

function readQuoted(source: string, startIndex: number, quote: string) {
  let index = startIndex + 1;
  while (index < source.length) {
    const char = source[index] ?? "";
    const next = source[index + 1] ?? "";
    if (char === "\\" && index + 1 < source.length) {
      index += 2;
      continue;
    }
    if (char === quote) {
      if (next === quote) {
        index += 2;
        continue;
      }
      index += 1;
      break;
    }
    index += 1;
  }
  return {
    text: source.slice(startIndex, index),
    nextIndex: index,
  };
}

interface BraceScanState {
  inBlockComment: boolean;
}

function formatBraceBasedScript(source: string) {
  const lines = source.split("\n");
  const state: BraceScanState = { inBlockComment: false };
  const output: string[] = [];
  let indent = 0;
  let previousBlank = false;

  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) {
      if (!previousBlank) {
        output.push("");
      }
      previousBlank = true;
      continue;
    }
    previousBlank = false;
    const leadingClosers = trimmed.match(/^}+/)?.[0].length ?? 0;
    indent = Math.max(0, indent - leadingClosers);
    output.push(`${INDENT.repeat(indent)}${trimmed}`);
    const braces = scanBraceDelta(trimmed, state);
    indent = Math.max(0, indent + braces.opens - Math.max(0, braces.closes - leadingClosers));
  }

  return trimEdgeBlankLines(output).join("\n");
}

function scanBraceDelta(line: string, state: BraceScanState) {
  let opens = 0;
  let closes = 0;
  for (let index = 0; index < line.length; index += 1) {
    const char = line[index] ?? "";
    const next = line[index + 1] ?? "";
    if (state.inBlockComment) {
      if (char === "*" && next === "/") {
        state.inBlockComment = false;
        index += 1;
      }
      continue;
    }
    if (char === "/" && next === "*") {
      state.inBlockComment = true;
      index += 1;
      continue;
    }
    if (char === "/" && next === "/") {
      break;
    }
    if (char === "\"" || char === "'") {
      index = skipQuotedSegment(line, index, char);
      continue;
    }
    if (char === "{") {
      opens += 1;
    } else if (char === "}") {
      closes += 1;
    }
  }
  return { opens, closes };
}

function skipQuotedSegment(line: string, startIndex: number, quote: string) {
  let index = startIndex + 1;
  while (index < line.length) {
    const char = line[index] ?? "";
    if (char === "\\") {
      index += 2;
      continue;
    }
    if (char === quote) {
      return index;
    }
    index += 1;
  }
  return line.length - 1;
}

function formatPython(source: string) {
  const lines = source.split("\n");
  const output: string[] = [];
  let indent = 0;
  let bracketDepth = 0;
  let previousBlank = false;

  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) {
      if (!previousBlank) {
        output.push("");
      }
      previousBlank = true;
      continue;
    }
    previousBlank = false;
    if (isPythonDedentLine(trimmed)) {
      indent = Math.max(0, indent - 1);
    }
    output.push(`${INDENT.repeat(indent)}${trimmed}`);
    bracketDepth = Math.max(0, bracketDepth + scanBracketDelta(trimmed));
    if (bracketDepth === 0 && pythonLineOpensBlock(trimmed)) {
      indent += 1;
    }
  }

  return trimEdgeBlankLines(output).join("\n");
}

function isPythonDedentLine(line: string) {
  return /^(elif|else|except|finally)\b/.test(line) || /^[)\]}]/.test(line);
}

function pythonLineOpensBlock(line: string) {
  return stripPythonInlineComment(line).trimEnd().endsWith(":");
}

function stripPythonInlineComment(line: string) {
  let quote = "";
  for (let index = 0; index < line.length; index += 1) {
    const char = line[index] ?? "";
    if (quote) {
      if (char === "\\") {
        index += 1;
        continue;
      }
      if (char === quote) {
        quote = "";
      }
      continue;
    }
    if (char === "\"" || char === "'") {
      quote = char;
      continue;
    }
    if (char === "#") {
      return line.slice(0, index);
    }
  }
  return line;
}

function scanBracketDelta(line: string) {
  const clean = stripPythonInlineComment(line);
  let delta = 0;
  let quote = "";
  for (let index = 0; index < clean.length; index += 1) {
    const char = clean[index] ?? "";
    if (quote) {
      if (char === "\\") {
        index += 1;
        continue;
      }
      if (char === quote) {
        quote = "";
      }
      continue;
    }
    if (char === "\"" || char === "'") {
      quote = char;
      continue;
    }
    if (char === "(" || char === "[" || char === "{") {
      delta += 1;
    } else if (char === ")" || char === "]" || char === "}") {
      delta -= 1;
    }
  }
  return delta;
}

function trimEdgeBlankLines(lines: string[]) {
  let start = 0;
  let end = lines.length;
  while (start < end && !lines[start]?.trim()) {
    start += 1;
  }
  while (end > start && !lines[end - 1]?.trim()) {
    end -= 1;
  }
  return lines.slice(start, end);
}
