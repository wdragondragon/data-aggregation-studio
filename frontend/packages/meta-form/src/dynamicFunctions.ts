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

const textParam = (key: string, label: string, placeholder: string, description: string,
                   optional = false): DynamicFunctionParamSchema => ({
  key, label, placeholder, description, required: optional ? false : undefined,
  useSelectedSnippet: key === "source",
});

const numberParam = (key: string, label: string, placeholder: string, description: string,
                     defaultValue: string): DynamicFunctionParamSchema => ({
  key, label, placeholder, description, autoQuote: false, defaultValue,
});

export const dynamicFunctionCatalog: DynamicFunctionSchema[] = [
  {
    name: "getCurrentTime", label: "getCurrentTime", summary: "按格式输出当前时间，并支持偏移量。",
    description: "常用于生成按天、按小时、按批次的运行条件值。",
    signature: "$getCurrentTime(pattern, offset)", returnDescription: "格式化后的时间字符串",
    example: "$getCurrentTime('yyyy-MM-dd', '-1d')",
    params: [
      textParam("pattern", "时间格式", "例如：yyyy-MM-dd HH:mm:ss", "Java 时间格式模板。"),
      { ...textParam("offset", "偏移量", "例如：-1d、+2h、-30mi", "支持 y、m、w、d、h、mi、s，也支持秒数表达式。", true), defaultValue: "" },
    ],
  },
  {
    name: "getTheMonthLastDay", label: "getTheMonthLastDay", summary: "计算指定时间所在月份的最后一天。",
    description: "可用于月末分区、账期或对账周期类校验逻辑。",
    signature: "$getTheMonthLastDay(dateTime?)", returnDescription: "月份最后一天的数字字符串",
    example: "$getTheMonthLastDay('2026-04-15 00:00:00')",
    params: [textParam("dateTime", "时间值", "例如：2026-04-15 00:00:00", "支持日期时间字符串、时间戳或动态函数表达式。", true)],
  },
  {
    name: "getTimeUnitValue", label: "getTimeUnitValue", summary: "提取时间中的年、月、日、时、分、秒或星期。",
    description: "适合把时间字段拆成多个维度参与参数赋值或路径拼接。",
    signature: "$getTimeUnitValue(dateTime, unit)", returnDescription: "指定时间单位的字符串值",
    example: "$getTimeUnitValue('2026-04-15 13:30:00', 'd')",
    params: [
      textParam("dateTime", "时间值", "例如：2026-04-15 13:30:00", "支持日期时间字符串、时间戳或动态函数表达式。"),
      { ...textParam("unit", "时间单位", "y / m / d / h / mi / s / day", "分别表示年、月、日、时、分、秒、星期。"), defaultValue: "d" },
    ],
  },
  {
    name: "subStr", label: "subStr", summary: "按起始位置和长度截取字符串。",
    description: "适合处理编码、日期串、业务前缀等固定长度片段。",
    signature: "$subStr(source, start, length)", returnDescription: "截取后的字符串",
    example: "$subStr('ABC123', 0, 3)",
    params: [
      textParam("source", "原始字符串", "例如：order_20260415", "可直接输入文本，也可以用当前选中内容自动带入。"),
      numberParam("start", "起始位置", "例如：0", "从 0 开始计数。", "0"),
      numberParam("length", "截取长度", "例如：8", "需要截取的字符数量。", "1"),
    ],
  },
  {
    name: "subString", label: "subString", summary: "按起始和结束位置截取字符串。",
    description: "适合明确知道开始、结束下标的截取场景。",
    signature: "$subString(source, start, end)", returnDescription: "截取后的字符串",
    example: "$subString('ABC123', 1, 4)",
    params: [
      textParam("source", "原始字符串", "例如：order_20260415", "可直接输入文本，也可以用当前选中内容自动带入。"),
      numberParam("start", "起始位置", "例如：0", "从 0 开始计数。", "0"),
      numberParam("end", "结束位置", "例如：8", "结束位置本身不包含在结果中。", "1"),
    ],
  },
  {
    name: "toLower", label: "toLower", summary: "把字符串转成小写。", description: "适合做大小写归一化条件。",
    signature: "$toLower(source)", returnDescription: "小写字符串", example: "$toLower('ABC123')",
    params: [textParam("source", "原始字符串", "例如：USER_NAME", "可直接输入文本，也可以用当前选中内容自动带入。")],
  },
  {
    name: "toUpper", label: "toUpper", summary: "把字符串转成大写。", description: "适合做大小写归一化条件。",
    signature: "$toUpper(source)", returnDescription: "大写字符串", example: "$toUpper('abc123')",
    params: [textParam("source", "原始字符串", "例如：user_name", "可直接输入文本，也可以用当前选中内容自动带入。")],
  },
];
