export const componentMessages = {
  "en-US": {
    metaForm: {
      technicalTitle: "Technical Metadata",
      businessTitle: "Business Metadata",
      enterField: "Enter {fieldName}",
      selectField: "Select {fieldName}",
    },
    fieldMapping: {
      title: "Field mappings",
      sourceAlias: "Source Alias",
      sourceField: "Source Field",
      targetField: "Target Field",
      expression: "Expression",
      transformers: "Transformers",
      actions: "Actions",
      selectSourceAlias: "Select source alias",
      selectSourceField: "Select source field",
      selectTargetField: "Select target field",
      optionalExpression: "Optional expression",
      chooseTransformers: "Choose transformers",
      configureTransformers: "Configure",
      transformerDialogTitle: "Configure Transformers",
      transformerDialogDescription: "Each field mapping row can carry multiple transformers and parameter JSON.",
      addTransformer: "Add Transformer",
      transformerCode: "Transformer Code",
      transformerParameters: "Parameters JSON",
      noTransformers: "No transformers",
      invalidTransformerParameters: "Invalid transformer parameter JSON",
    },
    workflowCanvas: {
      paletteTitle: "Node Palette",
      paletteHint: "Drag onto the canvas",
      nodeTypes: {
        COLLECTION_TASK: {
          label: "Collection Task",
          caption: "Bind an online collection job",
        },
        DATA_SCRIPT: {
          label: "Data Script",
          caption: "Run a saved SQL script",
        },
        ETL_SINGLE: {
          label: "Single ETL",
          caption: "Reader -> Transformer -> Writer",
        },
        FUSION: {
          label: "Fusion",
          caption: "Multi-model join and merge",
        },
        CONSISTENCY: {
          label: "Consistency",
          caption: "Cross-source compare",
        },
        HTTP: {
          label: "HTTP",
          caption: "External callback or webhook",
        },
        SHELL: {
          label: "Shell",
          caption: "Local runtime command",
        },
      },
    },
  },
  "zh-CN": {
    metaForm: {
      technicalTitle: "技术元数据",
      businessTitle: "业务元数据",
      enterField: "请输入{fieldName}",
      selectField: "请选择{fieldName}",
    },
    fieldMapping: {
      title: "字段映射",
      sourceAlias: "源别名",
      sourceField: "源字段",
      targetField: "目标字段",
      expression: "表达式",
      transformers: "转换器",
      actions: "操作",
      selectSourceAlias: "选择源别名",
      selectSourceField: "选择源字段",
      selectTargetField: "选择目标字段",
      optionalExpression: "可选表达式",
      chooseTransformers: "选择转换器",
      configureTransformers: "配置",
      transformerDialogTitle: "配置转换器",
      transformerDialogDescription: "每条字段映射都可以维护多个转换器及其参数 JSON。",
      addTransformer: "新增转换器",
      transformerCode: "转换器编码",
      transformerParameters: "参数 JSON",
      noTransformers: "暂无转换器",
      invalidTransformerParameters: "转换器参数 JSON 不合法",
    },
    workflowCanvas: {
      paletteTitle: "节点调色板",
      paletteHint: "拖到画布上",
      nodeTypes: {
        COLLECTION_TASK: {
          label: "采集任务",
          caption: "绑定已上线采集任务",
        },
        DATA_SCRIPT: {
          label: "数据脚本",
          caption: "执行已保存的 SQL 脚本",
        },
        ETL_SINGLE: {
          label: "单表 ETL",
          caption: "Reader -> Transformer -> Writer",
        },
        FUSION: {
          label: "融合",
          caption: "多模型关联与合并",
        },
        CONSISTENCY: {
          label: "一致性",
          caption: "跨源对比校验",
        },
        HTTP: {
          label: "HTTP",
          caption: "外部回调或 Webhook",
        },
        SHELL: {
          label: "Shell",
          caption: "本地运行时命令",
        },
      },
    },
  },
} as const;
