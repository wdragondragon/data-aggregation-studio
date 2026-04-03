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
      sourceField: "Source Field",
      targetField: "Target Field",
      expression: "Expression",
      transformers: "Transformers",
      actions: "Actions",
      selectSourceField: "Select source field",
      selectTargetField: "Select target field",
      optionalExpression: "Optional expression",
      chooseTransformers: "Choose transformers",
    },
    workflowCanvas: {
      paletteTitle: "Node Palette",
      paletteHint: "Drag onto the canvas",
      nodeTypes: {
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
      sourceField: "源字段",
      targetField: "目标字段",
      expression: "表达式",
      transformers: "转换器",
      actions: "操作",
      selectSourceField: "选择源字段",
      selectTargetField: "选择目标字段",
      optionalExpression: "可选表达式",
      chooseTransformers: "选择转换器",
    },
    workflowCanvas: {
      paletteTitle: "节点调色板",
      paletteHint: "拖到画布上",
      nodeTypes: {
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
