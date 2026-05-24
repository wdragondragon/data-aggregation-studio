export interface DynamicFunctionParamSchema {
  key: string;
  label: string;
  placeholder: string;
  description: string;
  required?: boolean;
  autoQuote?: boolean;
  defaultValue?: string;
  useSelectedSnippet?: boolean;
}

export interface DynamicFunctionSchema {
  name: string;
  label: string;
  summary: string;
  description: string;
  signature: string;
  returnDescription: string;
  example: string;
  params: DynamicFunctionParamSchema[];
}

export type DynamicFunctionInsertTarget =
  | { type: "whereClause" }
  | { type: "binding"; key: string };

export const dynamicFunctionCatalog: DynamicFunctionSchema[] = [
  {
    name: "getCurrentTime",
    label: "getCurrentTime",
    summary: "按格式输出当前时间，并支持偏移量。",
    description: "常用于生成按天、按小时、按批次的运行条件值。",
    signature: "$getCurrentTime(pattern, offset)",
    returnDescription: "格式化后的时间字符串",
    example: "$getCurrentTime('yyyy-MM-dd', '-1d')",
    params: [
      {
        key: "pattern",
        label: "时间格式",
        placeholder: "例如：yyyy-MM-dd HH:mm:ss",
        description: "Java 时间格式模板。",
        defaultValue: "yyyy-MM-dd",
      },
      {
        key: "offset",
        label: "偏移量",
        placeholder: "例如：-1d、+2h、-30mi",
        description: "支持 y、m、w、d、h、mi、s，也支持秒数表达式。",
        defaultValue: "",
        required: false,
      },
    ],
  },
  {
    name: "getTheMonthLastDay",
    label: "getTheMonthLastDay",
    summary: "计算指定时间所在月份的最后一天。",
    description: "可用于月末分区、账期或对账周期类校验逻辑。",
    signature: "$getTheMonthLastDay(dateTime?)",
    returnDescription: "月份最后一天的数字字符串",
    example: "$getTheMonthLastDay('2026-04-15 00:00:00')",
    params: [
      {
        key: "dateTime",
        label: "时间值",
        placeholder: "例如：2026-04-15 00:00:00",
        description: "可填写日期时间字符串、时间戳或其他动态函数表达式。",
        required: false,
      },
    ],
  },
  {
    name: "getTimeUnitValue",
    label: "getTimeUnitValue",
    summary: "提取时间中的年、月、日、时、分、秒或星期。",
    description: "适合把时间字段拆成多个维度参与参数赋值或 where 条件。",
    signature: "$getTimeUnitValue(dateTime, unit)",
    returnDescription: "指定时间单位的字符串值",
    example: "$getTimeUnitValue('2026-04-15 13:30:00', 'd')",
    params: [
      {
        key: "dateTime",
        label: "时间值",
        placeholder: "例如：2026-04-15 13:30:00",
        description: "支持常见日期时间字符串、时间戳或其他动态函数表达式。",
      },
      {
        key: "unit",
        label: "时间单位",
        placeholder: "y / m / d / h / mi / s / day",
        description: "分别表示年、月、日、时、分、秒、星期。",
        defaultValue: "d",
      },
    ],
  },
  {
    name: "subStr",
    label: "subStr",
    summary: "按起始位置和长度截取字符串。",
    description: "适合处理编码、日期串、业务前缀等固定长度片段。",
    signature: "$subStr(source, start, length)",
    returnDescription: "截取后的字符串",
    example: "$subStr('ABC123', 0, 3)",
    params: [
      {
        key: "source",
        label: "原始字符串",
        placeholder: "例如：order_20260415",
        description: "可直接输入文本，也可以用当前选中内容自动带入。",
        useSelectedSnippet: true,
      },
      {
        key: "start",
        label: "起始位置",
        placeholder: "例如：0",
        description: "从 0 开始计数。",
        autoQuote: false,
        defaultValue: "0",
      },
      {
        key: "length",
        label: "截取长度",
        placeholder: "例如：8",
        description: "需要截取的字符数量。",
        autoQuote: false,
        defaultValue: "1",
      },
    ],
  },
  {
    name: "subString",
    label: "subString",
    summary: "按起始和结束位置截取字符串。",
    description: "适合明确知道开始、结束下标的截取场景。",
    signature: "$subString(source, start, end)",
    returnDescription: "截取后的字符串",
    example: "$subString('ABC123', 1, 4)",
    params: [
      {
        key: "source",
        label: "原始字符串",
        placeholder: "例如：order_20260415",
        description: "可直接输入文本，也可以用当前选中内容自动带入。",
        useSelectedSnippet: true,
      },
      {
        key: "start",
        label: "起始位置",
        placeholder: "例如：0",
        description: "从 0 开始计数。",
        autoQuote: false,
        defaultValue: "0",
      },
      {
        key: "end",
        label: "结束位置",
        placeholder: "例如：8",
        description: "结束位置本身不包含在结果中。",
        autoQuote: false,
        defaultValue: "1",
      },
    ],
  },
  {
    name: "toLower",
    label: "toLower",
    summary: "把字符串转成小写。",
    description: "适合做大小写归一化条件。",
    signature: "$toLower(source)",
    returnDescription: "小写字符串",
    example: "$toLower('ABC123')",
    params: [
      {
        key: "source",
        label: "原始字符串",
        placeholder: "例如：USER_NAME",
        description: "可直接输入文本，也可以用当前选中内容自动带入。",
        useSelectedSnippet: true,
      },
    ],
  },
  {
    name: "toUpper",
    label: "toUpper",
    summary: "把字符串转成大写。",
    description: "适合做大小写归一化条件。",
    signature: "$toUpper(source)",
    returnDescription: "大写字符串",
    example: "$toUpper('abc123')",
    params: [
      {
        key: "source",
        label: "原始字符串",
        placeholder: "例如：user_name",
        description: "可直接输入文本，也可以用当前选中内容自动带入。",
        useSelectedSnippet: true,
      },
    ],
  },
];
