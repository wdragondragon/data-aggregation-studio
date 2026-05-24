export type HttpDynamicFunctionKind = "literal" | "digest" | "token";

export interface HttpDynamicFunctionParamSchema {
  key: string;
  label: string;
  placeholder: string;
  description: string;
  required?: boolean;
  defaultValue?: string;
  useSelectedSnippet?: boolean;
}

export interface HttpDynamicFunctionSchema {
  name: string;
  label: string;
  summary: string;
  description: string;
  signature: string;
  returnDescription: string;
  example: string;
  kind: HttpDynamicFunctionKind;
  expression?: string;
  params: HttpDynamicFunctionParamSchema[];
}

export interface HttpDynamicTokenPair {
  name: string;
  value: string;
}

type Translate = (key: string, params?: Record<string, string>) => string;

export function buildHttpDynamicFunctionCatalog(t: Translate): HttpDynamicFunctionSchema[] {
  return [
    literalFunction(
      "dyn_page",
      "{dyn_page}",
      t("web.collectionTasks.httpDynPageSummary"),
      t("web.collectionTasks.httpDynPageDescription"),
      t("web.collectionTasks.httpDynPageReturn"),
    ),
    literalFunction(
      "dyn_pageSize",
      "{dyn_pageSize}",
      t("web.collectionTasks.httpDynPageSizeSummary"),
      t("web.collectionTasks.httpDynPageSizeDescription"),
      t("web.collectionTasks.httpDynPageSizeReturn"),
    ),
    literalFunction(
      "dyn_offset",
      "{dyn_offset}",
      t("web.collectionTasks.httpDynOffsetSummary"),
      t("web.collectionTasks.httpDynOffsetDescription"),
      t("web.collectionTasks.httpDynOffsetReturn"),
    ),
    {
      name: "dyn_from_http_token",
      label: "dyn_from_http_token",
      summary: t("web.collectionTasks.httpDynTokenSummary"),
      description: t("web.collectionTasks.httpDynTokenDescription"),
      signature: "{dyn_from_http_token(method,url,header,body,path)}",
      returnDescription: t("web.collectionTasks.httpDynTokenReturn"),
      example: "Bearer {dyn_from_http_token(POST,http://localhost/login,,username=admin&password=admin123,data.token)}",
      kind: "token",
      params: [],
    },
    literalFunction(
      "dyn_timestamp",
      "{dyn_timestamp}",
      t("web.collectionTasks.httpDynTimestampSummary"),
      t("web.collectionTasks.httpDynTimestampDescription"),
      t("web.collectionTasks.httpDynTimestampReturn"),
    ),
    literalFunction(
      "dyn_ten_timestamp",
      "{dyn_ten_timestamp}",
      t("web.collectionTasks.httpDynTenTimestampSummary"),
      t("web.collectionTasks.httpDynTenTimestampDescription"),
      t("web.collectionTasks.httpDynTenTimestampReturn"),
    ),
    digestFunction(t, "dyn_MD5", "MD5"),
    digestFunction(t, "dyn_Sha1", "SHA1"),
    digestFunction(t, "dyn_Sha256", "SHA256"),
    digestFunction(t, "dyn_Sha512", "SHA512"),
  ];
}

export function buildTokenPairParam(rows: HttpDynamicTokenPair[]) {
  return rows
    .map((row) => ({
      name: row.name.trim(),
      value: row.value.trim(),
    }))
    .filter((row) => row.name)
    .map((row) => `${row.name}=${row.value}`)
    .join("&");
}

function literalFunction(
  name: string,
  expression: string,
  summary: string,
  description: string,
  returnDescription: string,
): HttpDynamicFunctionSchema {
  return {
    name,
    label: name,
    summary,
    description,
    signature: expression,
    returnDescription,
    example: expression,
    kind: "literal",
    expression,
    params: [],
  };
}

function digestFunction(t: Translate, name: string, algorithm: string): HttpDynamicFunctionSchema {
  return {
    name,
    label: name,
    summary: t("web.collectionTasks.httpDynDigestSummary", { algorithm }),
    description: t("web.collectionTasks.httpDynDigestDescription", { algorithm }),
    signature: `{${name}(value)}`,
    returnDescription: t("web.collectionTasks.httpDynDigestReturn", { algorithm }),
    example: `{${name}(abc123)}`,
    kind: "digest",
    params: [
      {
        key: "value",
        label: t("web.collectionTasks.httpDynDigestValue"),
        placeholder: "abc123",
        description: t("web.collectionTasks.httpDynDigestValueDescription"),
        useSelectedSnippet: true,
      },
    ],
  };
}
