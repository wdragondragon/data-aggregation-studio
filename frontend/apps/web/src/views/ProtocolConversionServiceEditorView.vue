<template>
  <div class="studio-page protocol-conversion-editor">
    <div class="studio-toolbar">
      <div>
        <h3>{{ isCreateMode ? "新建协议转换服务" : "编辑协议转换服务" }}</h3>
        <p>按对外入口协议接收并返回，内部按下游目标协议完成转发调用。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button @click="router.push('/protocol-conversions')">返回列表</el-button>
        <template v-if="detailLoadError && serviceId">
          <el-button type="primary" plain @click="retryLoadService">刷新</el-button>
        </template>
        <template v-else>
          <el-button type="primary" :loading="saving" @click="saveService">保存</el-button>
          <el-button v-if="form.id" plain type="success" :disabled="form.runtimeValid === false" @click="publishService">发布</el-button>
        </template>
      </div>
    </div>

    <SectionCard v-if="detailLoadError && serviceId" title="协议转换服务不可用" description="该协议转换服务可能已被删除、取消共享，或当前项目无权访问。">
      <el-result
        icon="warning"
        title="协议转换服务不可用"
        :sub-title="detailLoadError"
      />
    </SectionCard>

    <template v-else>
    <el-alert
      v-if="form.runtimeValid === false"
      type="error"
      show-icon
      :closable="false"
      :title="t('web.runtimeClusterSelection.invalid')"
      :description="form.runtimeValidationMessage || t('web.runtimeClusterSelection.invalidFallback')"
    />
    <div class="service-wizard">
      <button
        v-for="(step, index) in wizardSteps"
        :key="step.title"
        class="service-wizard-step"
        :class="{ active: activeStep === index, done: activeStep > index }"
        type="button"
        @click="goStep(index)"
      >
        <span class="step-index">{{ index + 1 }}</span>
        <span>
          <strong>{{ step.title }}</strong>
          <small>{{ step.description }}</small>
        </span>
      </button>
    </div>

    <SectionCard v-if="activeStep === 0" title="一、基础与入口协议" description="入口协议决定开放服务的调用方式和最终响应格式；SOAP 入口必须走 WebService 地址。">
      <el-form label-width="120px" class="protocol-form-grid">
        <el-form-item :label="t('web.runtimeClusterSelection.runtimeCluster')" required>
          <el-select v-model="form.runtimeClusterId" :loading="runtimeClustersLoading" :disabled="singleRuntimeCluster" :placeholder="t('web.runtimeClusterSelection.placeholder')" @change="handleRuntimeClusterChange">
            <el-option v-for="cluster in runtimeClusters" :key="String(cluster.id)" :label="runtimeClusterOptionLabel(cluster)" :value="cluster.id" :disabled="runtimeClusterOptionDisabled(cluster)" />
          </el-select>
        </el-form-item>
        <el-form-item label="服务编码">
          <el-input v-model="form.serviceCode" :disabled="!isCreateMode" placeholder="customer_protocol_bridge" />
        </el-form-item>
        <el-form-item label="服务名称">
          <el-input v-model="form.serviceName" placeholder="客户协议转换服务" />
        </el-form-item>
        <el-form-item label="Token 校验">
          <el-switch v-model="form.tokenRequired" active-text="需要" inactive-text="不需要" />
        </el-form-item>
        <el-form-item label="入口协议">
          <el-select v-model="form.sourceProtocol">
            <el-option label="HTTP JSON" value="HTTP_JSON" />
            <el-option label="HTTP XML" value="HTTP_XML" />
            <el-option label="SOAP 1.1" value="SOAP_11" />
            <el-option label="SOAP 1.2" value="SOAP_12" />
          </el-select>
        </el-form-item>
        <el-form-item label="入口方法">
          <el-select v-model="form.sourceMethod" :disabled="isSoapSource">
            <el-option label="POST" value="POST" />
            <el-option label="GET" value="GET" />
            <el-option label="PUT" value="PUT" />
            <el-option label="PATCH" value="PATCH" />
          </el-select>
        </el-form-item>
        <el-form-item label="入口数据节点">
          <el-input v-model="form.sourceDataNodePath" placeholder="records.record，可选" />
        </el-form-item>
        <el-form-item label="转换模式">
          <el-select v-model="form.conversionMode">
            <el-option label="字段映射互转" value="FIELD_MAPPING" />
            <el-option label="整报文装载字段" value="RAW_MESSAGE_FIELD" />
            <el-option label="Body 直接桥接" value="BODY_BRIDGE" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <StatusPill :label="statusLabel(form.status)" :tone="statusTone(form.status)" />
        </el-form-item>
        <el-form-item label="开放地址">
          <el-input :model-value="openEndpoint" readonly />
        </el-form-item>
      </el-form>

      <div v-if="isSoapSource" class="sub-panel">
        <div class="sub-panel__header">
          <strong>WebService 配置</strong>
          <p>Namespace、Operation 和 SOAPAction 只描述 SOAP 合约，不要求 Namespace 是可访问 URL。</p>
        </div>
        <el-form label-width="120px" class="protocol-form-grid">
          <el-form-item label="Namespace">
            <el-input v-model="webserviceConfig.namespaceUri" placeholder="http://studio.jdragon.com/protocol-conversion/demo" />
          </el-form-item>
          <el-form-item label="Operation">
            <el-input v-model="webserviceConfig.operationName" placeholder="convertPayload" />
          </el-form-item>
          <el-form-item label="SOAPAction">
            <el-input v-model="webserviceConfig.soapAction" placeholder="可选" />
          </el-form-item>
          <el-form-item label="请求根节点">
            <el-input v-model="webserviceConfig.requestRootName" placeholder="convertPayload" />
          </el-form-item>
          <el-form-item label="响应根节点">
            <el-input v-model="webserviceConfig.responseRootName" placeholder="convertPayloadResponse" />
          </el-form-item>
        </el-form>
      </div>
    </SectionCard>

    <SectionCard v-if="activeStep === 1" title="二、下游目标协议" description="下游目标地址由 HTTP 数据源 URL 与目标路径拼接，不允许任意绝对 URL。">
      <el-form label-width="120px" class="protocol-form-grid">
        <el-form-item label="目标数据源">
          <el-select v-model="form.targetDatasourceId" filterable :disabled="!form.runtimeClusterId" placeholder="选择 HTTP 数据源">
            <el-option v-for="item in httpDatasources" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标路径">
          <el-input v-model="form.targetPath" placeholder="/openapi/data-ingestion-services/code/key" />
        </el-form-item>
        <el-form-item label="下游协议">
          <el-select v-model="form.targetProtocol">
            <el-option label="HTTP JSON" value="HTTP_JSON" />
            <el-option label="HTTP XML" value="HTTP_XML" />
            <el-option label="SOAP 1.1" value="SOAP_11" />
            <el-option label="SOAP 1.2" value="SOAP_12" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标方法">
          <el-select v-model="form.targetMethod">
            <el-option label="POST" value="POST" />
            <el-option label="GET" value="GET" />
            <el-option label="PUT" value="PUT" />
            <el-option label="PATCH" value="PATCH" />
          </el-select>
        </el-form-item>
        <el-form-item label="Payload">
          <el-select v-model="form.payloadMode">
            <el-option label="单对象" value="OBJECT" />
            <el-option label="数组" value="ARRAY" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标数据节点">
          <el-input v-model="form.targetDataNodePath" placeholder="records.record，数组 XML/SOAP 必填或可推导" />
        </el-form-item>
      </el-form>

      <div v-if="isSoapTarget" class="sub-panel">
        <div class="sub-panel__header">
          <strong>目标 WebService 配置</strong>
          <p>用于生成目标 SOAP Envelope 和 SOAPAction；不同于源 SOAP 开放入口配置。</p>
        </div>
        <el-form label-width="120px" class="protocol-form-grid">
          <el-form-item label="Namespace">
            <el-input v-model="targetWebserviceConfig.namespaceUri" placeholder="http://studio.jdragon.com/target-service/demo" />
          </el-form-item>
          <el-form-item label="Operation">
            <el-input v-model="targetWebserviceConfig.operationName" placeholder="submitPayload" />
          </el-form-item>
          <el-form-item label="SOAPAction">
            <el-input v-model="targetWebserviceConfig.soapAction" placeholder="SOAP 1.1 常用，可选" />
          </el-form-item>
          <el-form-item label="请求根节点">
            <el-input v-model="targetWebserviceConfig.requestRootName" placeholder="submitPayload" />
          </el-form-item>
          <el-form-item label="响应根节点">
            <el-input v-model="targetWebserviceConfig.responseRootName" placeholder="submitPayloadResponse" />
          </el-form-item>
        </el-form>
      </div>

      <div class="sub-panel">
        <div class="sub-panel__header">
          <strong>请求参数透传</strong>
          <p>启用后先透传调用方 Header、Query 和 Body，再叠加下方目标请求参数配置；重名时下方配置覆盖透传值。</p>
        </div>
        <el-checkbox-group v-model="requestPassthroughSelection" class="passthrough-options">
          <el-checkbox label="headers">透传 Header</el-checkbox>
          <el-checkbox label="query">透传 Query</el-checkbox>
          <el-checkbox label="body">透传 Body</el-checkbox>
        </el-checkbox-group>
        <p class="advanced-hint">
          可在目标参数值中引用原请求占位符，例如 <code v-pre>{{header.Authorization}}</code>、<code v-pre>{{query.pageNum}}</code>、<code v-pre>{{body.customerName}}</code>。
        </p>
      </div>

      <div class="protocol-json-grid">
        <ProtocolConversionJsonObjectEditor
          v-model="targetHeadersModel"
          index="1"
          title="HTTP Headers"
          description="HTTP 层请求头，Token 和 Authorization 会在调试目标请求中脱敏展示。"
          key-placeholder="Header 名称"
          value-placeholder="{{fieldName}}、{{header.Authorization}} 或固定值"
          :placeholder-options="requestPlaceholderOptions"
          @valid-change="targetHeadersValid = $event"
        />
        <ProtocolConversionJsonObjectEditor
          v-model="targetQueryModel"
          index="2"
          title="Query 参数"
          description="目标 URL 查询参数，可使用 {{fieldName}} 引用首条记录字段。"
          key-placeholder="Query 名称"
          value-placeholder="{{fieldName}}、{{query.pageNum}} 或固定值"
          :placeholder-options="requestPlaceholderOptions"
          @valid-change="targetQueryValid = $event"
        />
        <ProtocolConversionJsonObjectEditor
          v-model="responseStatusModel"
          index="3"
          title="响应校验"
          description="例如 path=result.status、code=SUCCESS；SOAP/XML 响应会先解析再取值。"
          key-placeholder="path / code"
          value-placeholder="校验值"
          @valid-change="responseStatusValid = $event"
        />
      </div>

      <el-collapse v-model="advancedPanels" class="advanced-collapse">
        <el-collapse-item title="高级：自定义目标 Body 模板" name="targetBodyTemplate">
          <p class="advanced-hint">默认由字段映射自动生成。只有目标系统要求特殊报文结构时才填写；数组模板可使用 <code v-pre>{{#records}}...{{/records}}</code>。</p>
          <el-input
            v-model="form.targetBodyTemplate"
            type="textarea"
            :rows="10"
            placeholder="JSON/XML/SOAP 模板，可使用 {{fieldName}}"
          />
        </el-collapse-item>
      </el-collapse>
    </SectionCard>

    <SectionCard v-if="activeStep === 2" title="三、字段与 Body" description="字段映射或 Body Bridge 决定下游目标 Body 的真实结构；预览会随配置即时刷新。">
      <template v-if="form.conversionMode === 'FIELD_MAPPING'">
        <div class="section-actions">
          <el-button type="primary" plain @click="addMapping">添加字段映射</el-button>
          <el-button plain @click="addFixedField">添加固定字段</el-button>
        </div>
        <StudioTableShell min-width="1180px">
          <el-table :data="form.fieldMappings" border>
            <el-table-column label="来源" width="140">
              <template #default="{ row }">
                <el-select v-model="row.sourcePosition">
                  <el-option label="Body" value="BODY" />
                  <el-option label="Header" value="HEADER" />
                  <el-option label="Query" value="QUERY" />
                  <el-option label="Form" value="FORM" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="源字段/路径" min-width="180">
              <template #default="{ row }"><el-input v-model="row.sourceField" placeholder="customer.name" /></template>
            </el-table-column>
            <el-table-column label="目标字段" min-width="180">
              <template #default="{ row }"><el-input v-model="row.targetField" placeholder="customerName" /></template>
            </el-table-column>
            <el-table-column label="目标路径" min-width="200">
              <template #default="{ row }"><el-input v-model="row.targetPath" placeholder="records.record.customerName，可选" /></template>
            </el-table-column>
            <el-table-column label="类型" width="140">
              <template #default="{ row }">
                <el-select v-model="row.valueType">
                  <el-option label="STRING" value="STRING" />
                  <el-option label="BOOLEAN" value="BOOLEAN" />
                  <el-option label="INTEGER" value="INTEGER" />
                  <el-option label="LONG" value="LONG" />
                  <el-option label="DECIMAL" value="DECIMAL" />
                  <el-option label="JSON" value="JSON" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="默认值" min-width="150">
              <template #default="{ row }"><el-input v-model="row.defaultValue" /></template>
            </el-table-column>
            <el-table-column label="必填" width="90" align="center" header-align="center">
              <template #default="{ row }"><el-switch v-model="row.required" /></template>
            </el-table-column>
            <el-table-column label="操作" width="86" align="center" header-align="center" fixed="right">
              <template #default="{ $index }">
                <el-button link type="danger" :icon="Delete" aria-label="删除" @click="form.fieldMappings.splice($index, 1)" />
              </template>
            </el-table-column>
          </el-table>
        </StudioTableShell>
      </template>

      <template v-else-if="form.conversionMode === 'RAW_MESSAGE_FIELD'">
        <div class="mode-panel">
          <el-form label-width="120px" class="protocol-form-grid">
            <el-form-item label="目标字段">
              <el-input v-model="rawMessageTargetField" placeholder="payload" />
            </el-form-item>
            <el-form-item label="请求 ID 字段">
              <el-input v-model="rawMessageRequestIdField" placeholder="requestId" />
            </el-form-item>
            <el-form-item label="接收时间字段">
              <el-input v-model="rawMessageReceivedAtField" placeholder="receivedAt" />
            </el-form-item>
          </el-form>
          <div class="sub-panel__header">
            <strong>整报文转换器</strong>
            <p>只开放安全转换器，参数通过表单配置，覆盖脱敏、摘要和编码场景。</p>
          </div>
          <div class="raw-transformers-panel">
            <div class="raw-transformers-panel__summary">
              <span>{{ rawTransformerSummary(form.rawTransformers) }}</span>
            </div>
            <el-button type="primary" plain @click="rawTransformerDialogVisible = true">配置转换器</el-button>
          </div>
        </div>
      </template>

      <template v-else>
        <div class="mode-panel">
          <el-form label-width="120px" class="protocol-form-grid">
            <el-form-item label="桥接方式">
              <el-select v-model="bodyBridgeMode">
                <el-option label="协议转换" value="CONVERT" />
                <el-option label="原样转发" value="RAW" />
              </el-select>
            </el-form-item>
          </el-form>
          <el-alert
            v-if="showRawBodyBridgeProtocolWarning"
            class="bridge-warning"
            type="warning"
            :closable="false"
            show-icon
            title="原样转发不会做协议转换，入口 Body 会按原始文本发送到下游。当前入口协议和下游协议不同，通常应选择“协议转换”。"
          />
        </div>
      </template>

      <h4 class="sub-title">固定字段</h4>
      <StudioTableShell min-width="820px">
        <el-table :data="form.fixedFields || []" border>
          <el-table-column label="目标字段" min-width="170">
            <template #default="{ row }"><el-input v-model="row.targetField" /></template>
          </el-table-column>
          <el-table-column label="目标路径" min-width="200">
            <template #default="{ row }"><el-input v-model="row.targetPath" placeholder="records.record.channel，可选" /></template>
          </el-table-column>
          <el-table-column label="值" min-width="220">
            <template #default="{ row }"><el-input v-model="row.value" /></template>
          </el-table-column>
          <el-table-column label="类型" width="140">
            <template #default="{ row }">
              <el-select v-model="row.valueType">
                <el-option label="STRING" value="STRING" />
                <el-option label="BOOLEAN" value="BOOLEAN" />
                <el-option label="LONG" value="LONG" />
                <el-option label="DECIMAL" value="DECIMAL" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="86" align="center" header-align="center">
            <template #default="{ $index }">
              <el-button link type="danger" :icon="Delete" aria-label="删除" @click="removeFixedField($index)" />
            </template>
          </el-table-column>
        </el-table>
      </StudioTableShell>

      <div class="body-preview">
        <div class="body-preview__header">
          <div>
            <strong>目标 Body 预览</strong>
            <p>{{ targetBodyPreviewDescription }}</p>
          </div>
          <el-tag v-if="targetBodyPreviewError" type="danger" effect="plain">配置需修正</el-tag>
          <el-tag v-else type="success" effect="plain">自动生成</el-tag>
        </div>
        <el-alert v-if="targetBodyPreviewError" type="error" show-icon :closable="false" :title="targetBodyPreviewError" />
        <el-input :model-value="targetBodyPreview" type="textarea" :rows="14" readonly />
      </div>
    </SectionCard>

    <SectionCard v-if="activeStep === 3" title="四、调试确认" description="调试接口会返回脱敏后的目标请求详情；开放接口不会暴露目标请求。">
      <template v-if="isSoapEntityDebugMode">
        <ProtocolConversionJsonObjectEditor
          v-model="debugSoapHeadersModel"
          title="入口 SOAP HTTP Header"
          description="模拟调用方传入的 SOAP HTTP Header。"
          compact
          @valid-change="debugHeadersValid = $event"
        />
        <div class="debug-body-editor">
          <div class="debug-body-editor__header">
            <div>
              <strong>入口 SOAP 实体内容</strong>
              <p>系统会按 SOAP 版本、Namespace 和 Operation 自动生成 Envelope，调试时只填写操作实体内部内容。</p>
            </div>
            <div class="debug-body-editor__actions">
              <el-button plain size="small" @click="generateDebugSoapEntitySample">生成实体样例</el-button>
              <el-button plain size="small" @click="formatDebugSoapEntityBody">格式化 XML</el-button>
            </div>
          </div>
          <el-input
            v-model="debugSoapEntityBody"
            type="textarea"
            :rows="12"
            :placeholder="debugSoapEntityBodyPlaceholder"
          />
        </div>
      </template>
      <template v-else-if="isSoapSource">
        <HttpWebServiceOptionsEditor
          :fields="debugSoapFields"
          :model-value="debugSoapOptions"
          :soap-contract="debugSoapContract"
          :soap-field-names="debugSoapFieldNames"
          @update:model-value="debugSoapOptions = $event"
        />
      </template>
      <template v-else>
        <div class="protocol-json-grid protocol-json-grid--debug">
          <ProtocolConversionJsonObjectEditor
            v-model="debugHeadersModel"
            title="入口请求 Header"
            description="模拟调用方传入的 HTTP Header。"
            compact
            @valid-change="debugHeadersValid = $event"
          />
          <ProtocolConversionJsonObjectEditor
            v-model="debugQueryModel"
            title="入口请求 Query"
            description="模拟调用方传入的 Query 参数。"
            compact
            @valid-change="debugQueryValid = $event"
          />
        </div>
        <div class="debug-body-editor">
          <div class="debug-body-editor__header">
            <strong>入口请求 Body</strong>
            <el-button v-if="isXmlSource" plain size="small" @click="formatDebugBody">格式化 XML</el-button>
          </div>
          <el-input v-model="debugRawBody" type="textarea" :rows="10" :placeholder="debugBodyPlaceholder" />
        </div>
      </template>
      <div class="section-actions">
        <el-button plain :disabled="!openEndpoint || openEndpoint === '-'" @click="generateCurlCommand('bash')">生成 cURL(bash)</el-button>
        <el-button plain :disabled="!openEndpoint || openEndpoint === '-'" @click="generateCurlCommand('cmd')">生成 cURL(cmd)</el-button>
        <el-button type="primary" :disabled="!form.id || form.runtimeValid === false" :loading="debugging" @click="debugService">发送调试</el-button>
      </div>
      <div class="curl-panel">
        <div class="curl-panel__header">
          <div>
            <strong>cURL 调用命令</strong>
            <p>根据当前开放地址、Header、Query 和 Body 生成调用命令，订阅 Token 使用占位符。</p>
          </div>
          <el-button plain :disabled="!curlCommand" @click="copyCurlCommand">复制 cURL</el-button>
        </div>
        <el-input
          :model-value="curlCommand"
          type="textarea"
          :rows="8"
          readonly
          placeholder="点击“生成 cURL(bash)”或“生成 cURL(cmd)”后展示命令"
        />
      </div>
      <el-alert v-if="debugError" type="error" show-icon :closable="false" :title="debugError" />
      <div v-if="debugTrace" class="trace-panel">
        <div class="debug-body-editor__header">
          <div>
            <strong>四阶段转换过程</strong>
            <p>按对外原请求、目标请求生成、目标原响应、对外响应生成展示本次调试的关键过程。</p>
          </div>
          <el-button v-if="rawDebugResultText" plain size="small" @click="rawDebugResultVisible = true">查看原始调试结果</el-button>
        </div>
        <div v-if="debugSummary" class="debug-summary">
          <span v-if="debugSummary.requestId">Request ID：{{ debugSummary.requestId }}</span>
          <el-tag :type="debugSummary.status === 'SUCCESS' ? 'success' : 'danger'" size="small">
            {{ debugSummary.status || "UNKNOWN" }}
          </el-tag>
          <span v-if="debugSummary.targetHttpStatus != null">目标 HTTP：{{ debugSummary.targetHttpStatus }}</span>
          <span v-if="debugSummary.receivedCount != null">接收：{{ debugSummary.receivedCount }}</span>
          <span v-if="debugSummary.successCount != null">成功：{{ debugSummary.successCount }}</span>
          <span v-if="debugSummary.failedCount != null">失败：{{ debugSummary.failedCount }}</span>
        </div>
        <ProtocolConversionTraceTimeline :trace="debugTrace" />
      </div>
      <div class="debug-result-panel">
        <div class="debug-body-editor__header">
          <div>
            <strong>业务响应</strong>
            <p>展示目标服务返回的真实业务 Body；请求和响应细节在四阶段转换过程中查看。</p>
          </div>
        </div>
        <el-input :model-value="debugResultText" type="textarea" :rows="14" readonly placeholder="业务响应" />
      </div>
      <el-dialog v-model="rawDebugResultVisible" title="原始调试结果" width="760px">
        <el-input :model-value="rawDebugResultText" type="textarea" :rows="18" readonly />
      </el-dialog>
    </SectionCard>

    <div class="wizard-footer">
      <el-button :disabled="activeStep === 0" @click="previousStep">上一步</el-button>
      <el-button v-if="activeStep < wizardSteps.length - 1" type="primary" @click="nextStep">下一步</el-button>
      <el-button v-else type="primary" :loading="saving" @click="saveService">保存服务</el-button>
    </div>
    </template>

    <TransformerBindingEditor
      v-model:visible="rawTransformerDialogVisible"
      :model-value="form.rawTransformers"
      :rule-options="onlineSafeFieldMappingRules"
      :load-rule-detail="loadFieldMappingRuleDetail"
      title="整报文转换器"
      description="按顺序对入口原始报文执行转换，参数由字段映射规则定义生成，不需要手写 JSON。"
      @save="saveRawTransformers"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { Delete } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import type {
  DataSourceOptionView,
  EntityId,
  FieldMappingRuleOptionView,
  FieldMappingRuleView,
  MetadataFieldDefinition,
  ProtocolConversionFieldMapping,
  ProtocolConversionFixedField,
  ProtocolConversionServiceSaveRequest,
  ProtocolConversionServiceView,
  ProtocolConversionTraceView,
  TransformerBinding,
  WebServiceConfig,
} from "@studio/api-sdk";
import { SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import HttpWebServiceOptionsEditor from "@/components/HttpWebServiceOptionsEditor.vue";
import TransformerBindingEditor from "@/components/TransformerBindingEditor.vue";
import {
  buildSoapEnvelope,
  buildSoapFieldsXmlByParentPath,
  buildSoapRecordsRepeatXmlByPath,
  formatXmlText,
  validateSoapArrayFieldParents,
  type SoapFieldSpec,
} from "@/components/open-service/openServiceDebugSupport";
import ProtocolConversionJsonObjectEditor from "@/components/protocol-conversion/ProtocolConversionJsonObjectEditor.vue";
import ProtocolConversionTraceTimeline from "@/components/protocol-conversion/ProtocolConversionTraceTimeline.vue";
import { resolveDataServiceOpenUrl, studioApi } from "@/api/studio";
import { copyTextFallback } from "@/components/data-service/dataServiceEditorSupport";
import { resolveErrorMessage } from "@/composables/useAsyncAction";
import { useRuntimeClusterOptions } from "@/composables/useRuntimeClusterOptions";
import { useRuntimeClusterChangeGuard } from "@/composables/useRuntimeClusterChangeGuard";

const route = useRoute();
const router = useRouter();
const { t } = useI18n();
const {
  runtimeClusters,
  runtimeClustersLoading,
  singleRuntimeCluster,
  loadRuntimeClusters,
  resolveInitialRuntimeClusterId,
  ensureRuntimeClusterOption,
  runtimeClusterOptionLabel,
  runtimeClusterOptionDisabled,
} = useRuntimeClusterOptions();
const { acceptedRuntimeClusterId, acceptRuntimeCluster, confirmRuntimeClusterChange } = useRuntimeClusterChangeGuard();
const serviceId = computed(() => route.params.serviceId as string | undefined);
const isCreateMode = computed(() => !serviceId.value || serviceId.value === "new");
const activeStep = ref(0);
const saving = ref(false);
const debugging = ref(false);
const datasources = ref<DataSourceOptionView[]>([]);
let datasourceLoadSequence = 0;
const debugResultText = ref("");
const debugError = ref("");
const debugTrace = ref<ProtocolConversionTraceView | null>(null);
const debugSummary = ref<ProtocolConversionDebugSummary | null>(null);
const fieldMappingRules = ref<FieldMappingRuleOptionView[]>([]);
const rawDebugResultText = ref("");
const rawDebugResultVisible = ref(false);
const rawTransformerDialogVisible = ref(false);
const curlCommand = ref("");
const advancedPanels = ref<string[]>([]);
const detailLoadError = ref("");

interface ProtocolConversionDebugSummary {
  requestId?: string;
  serviceCode?: string;
  status?: string;
  targetHttpStatus?: number;
  targetContentType?: string;
  receivedCount?: number | string;
  successCount?: number | string;
  failedCount?: number | string;
}

const wizardSteps = [
  { title: "基础与入口协议", description: "服务编码、开放方式和入口请求解析" },
  { title: "下游目标协议", description: "目标地址、协议和转发参数" },
  { title: "字段与 Body", description: "字段映射或 Body 桥接预览" },
  { title: "调试确认", description: "模拟调用并查看四阶段过程" },
];

const form = reactive<ProtocolConversionServiceView>({
  serviceCode: "",
  serviceName: "",
  runtimeClusterId: "",
  status: "DRAFT",
  tokenRequired: true,
  sourceProtocol: "HTTP_JSON",
  sourceMethod: "POST",
  sourceDataNodePath: "",
  conversionMode: "FIELD_MAPPING",
  fieldMappings: [],
  rawTransformers: [],
  fixedFields: [],
  bodyBridgeOptions: {},
  requestPassthrough: {},
  targetDatasourceId: undefined,
  targetPath: "",
  targetProtocol: "HTTP_JSON",
  targetMethod: "POST",
  targetHeaders: {},
  targetQuery: {},
  targetBodyTemplate: "",
  targetDataNodePath: "",
  payloadMode: "OBJECT",
  batchSize: 1,
  responseStatus: {},
});

const webserviceConfig = reactive<WebServiceConfig>({
  enabled: true,
  soapVersion: "SOAP_11",
  namespaceUri: "",
  operationName: "",
  soapAction: "",
  requestRootName: "",
  responseRootName: "",
});

const targetWebserviceConfig = reactive<WebServiceConfig>({
  enabled: true,
  soapVersion: "SOAP_11",
  namespaceUri: "",
  operationName: "",
  soapAction: "",
  requestRootName: "",
  responseRootName: "",
});

const targetHeadersValid = ref(true);
const targetQueryValid = ref(true);
const responseStatusValid = ref(true);
const debugHeadersValid = ref(true);
const debugQueryValid = ref(true);
const debugHeaders = ref<Record<string, unknown>>({});
const debugQuery = ref<Record<string, unknown>>({});
const debugRawBody = ref("");
const debugSoapOptions = ref<Record<string, unknown>>({ header: "{}", requestBody: "" });
const debugSoapHeaders = ref<Record<string, unknown>>({});
const debugSoapEntityBody = ref("");

const httpDatasources = computed(() => datasources.value.filter((item) => item.typeCode === "http"));
const isSoapSource = computed(() => form.sourceProtocol === "SOAP_11" || form.sourceProtocol === "SOAP_12");
const isSoapTarget = computed(() => form.targetProtocol === "SOAP_11" || form.targetProtocol === "SOAP_12");
const isBodyBridgeMode = computed(() => form.conversionMode === "BODY_BRIDGE");
const isRawMessageFieldMode = computed(() => form.conversionMode === "RAW_MESSAGE_FIELD");
const isSoapEntityDebugMode = computed(() => isSoapSource.value && (isBodyBridgeMode.value || isRawMessageFieldMode.value));
const isXmlSource = computed(() => form.sourceProtocol === "HTTP_XML");
const isArrayPayload = computed(() => String(form.payloadMode ?? "OBJECT").toUpperCase() === "ARRAY");
const openEndpoint = computed(() => {
  const path = isSoapSource.value ? form.webserviceEndpointPath : form.endpointPath;
  return path ? resolveDataServiceOpenUrl(path) : "-";
});
const targetHeadersModel = computed({
  get: () => form.targetHeaders ?? {},
  set: (value: Record<string, unknown>) => { form.targetHeaders = value; },
});
const targetQueryModel = computed({
  get: () => form.targetQuery ?? {},
  set: (value: Record<string, unknown>) => { form.targetQuery = value; },
});
const requestPlaceholderOptions = [
  { label: "Header: Authorization", value: "{{header.Authorization}}" },
  { label: "Header: X-Request-Id", value: "{{header.X-Request-Id}}" },
  { label: "Query: pageNum", value: "{{query.pageNum}}" },
  { label: "Query: pageSize", value: "{{query.pageSize}}" },
  { label: "Body: fieldName", value: "{{body.fieldName}}" },
  { label: "原始 Body", value: "{{rawBody}}" },
];
const requestPassthroughSelection = computed({
  get: () => {
    const config = form.requestPassthrough ?? {};
    return ["headers", "query", "body"].filter((key) => Boolean(config[key]));
  },
  set: (value: string[]) => {
    const selected = new Set(value);
    form.requestPassthrough = {
      headers: selected.has("headers"),
      query: selected.has("query"),
      body: selected.has("body"),
    };
  },
});
const responseStatusModel = computed({
  get: () => form.responseStatus ?? {},
  set: (value: Record<string, unknown>) => { form.responseStatus = value; },
});
const ONLINE_UNSAFE_TRANSFORMER_CODES = new Set([
  "dx_filter",
  "range_number_filter",
  "string_operation_filter",
  "number_operation_filter",
  "date_filter",
  "date_operation_filter",
  "null_value_filter",
  "dx_groovy",
  "dx_fackgroovy",
]);
const onlineSafeFieldMappingRules = computed(() =>
  fieldMappingRules.value.filter((rule) => {
    const code = rule.mappingCode?.trim().toLowerCase();
    return Boolean(code) && !ONLINE_UNSAFE_TRANSFORMER_CODES.has(code);
  }),
);
const debugHeadersModel = computed({
  get: () => debugHeaders.value,
  set: (value: Record<string, unknown>) => { debugHeaders.value = value; },
});
const debugQueryModel = computed({
  get: () => debugQuery.value,
  set: (value: Record<string, unknown>) => { debugQuery.value = value; },
});
const debugSoapHeadersModel = computed({
  get: () => debugSoapHeaders.value,
  set: (value: Record<string, unknown>) => {
    debugSoapHeaders.value = value;
    debugSoapOptions.value = { ...debugSoapOptions.value, header: stringifyJson(value) };
  },
});
const rawMessageTargetField = computed({
  get: () => stringOption("targetField", "payload"),
  set: (value: string) => setBodyBridgeOption("targetField", value),
});
const rawMessageRequestIdField = computed({
  get: () => stringOption("requestIdField", "requestId"),
  set: (value: string) => setBodyBridgeOption("requestIdField", value),
});
const rawMessageReceivedAtField = computed({
  get: () => stringOption("receivedAtField", "receivedAt"),
  set: (value: string) => setBodyBridgeOption("receivedAtField", value),
});
const bodyBridgeMode = computed({
  get: () => stringOption("mode", "CONVERT"),
  set: (value: string) => setBodyBridgeOption("mode", value),
});
const showRawBodyBridgeProtocolWarning = computed(() =>
  isBodyBridgeMode.value
  && bodyBridgeMode.value === "RAW"
  && form.sourceProtocol !== form.targetProtocol);
const debugSoapContract = computed(() => ({
  soapVersion: form.sourceProtocol === "SOAP_12" ? "SOAP_12" : "SOAP_11",
  namespaceUri: webserviceConfig.namespaceUri || "http://studio.jdragon.com/protocol-conversion",
  operationName: webserviceConfig.operationName || "convertPayload",
  requestRootName: webserviceConfig.requestRootName || webserviceConfig.operationName || "convertPayload",
}));
const debugSoapFields = computed<MetadataFieldDefinition[]>(() => [
  {
    fieldKey: "header",
    fieldName: "HTTP Headers",
    componentType: "CODE_EDITOR",
    valueType: "OBJECT",
    sortOrder: 10,
  },
  {
    fieldKey: "requestBody",
    fieldName: "源 SOAP Envelope",
    componentType: "CODE_EDITOR",
    valueType: "STRING",
    sortOrder: 20,
  },
]);
const debugSoapFieldNames = computed(() => {
  const names = form.fieldMappings
    .filter((item) => item.sourcePosition === "BODY" || !item.sourcePosition)
    .map((item) => item.sourceField || item.targetField)
    .filter((item): item is string => Boolean(item && item.trim()));
  return names.length ? names : ["payload"];
});
const debugBodyPlaceholder = computed(() =>
  form.sourceProtocol === "HTTP_XML"
    ? "<root>\n  <id>1</id>\n</root>"
    : "{\n  \"id\": 1\n}");
const debugSoapEntityBodyPlaceholder = computed(() => debugSoapSampleInnerXml());
const targetBodyPreviewDescription = computed(() =>
  form.targetBodyTemplate?.trim()
    ? "当前使用高级自定义模板；字段映射仍用于生成变量。"
    : form.conversionMode === "BODY_BRIDGE"
      ? "入口 Body 将按下游目标协议整体转换；不会额外包进某个目标字段。"
    : "根据字段映射、固定字段、Payload 和目标数据节点自动生成。");
const targetBodyPreviewError = computed(() => buildTargetBodyPreview().error);
const targetBodyPreview = computed(() => buildTargetBodyPreview().text);

watch(
  () => form.sourceProtocol,
  (value) => {
    webserviceConfig.soapVersion = value === "SOAP_12" ? "SOAP_12" : "SOAP_11";
    if (value === "SOAP_11" || value === "SOAP_12") {
      form.sourceMethod = "POST";
    }
  },
);

watch(
  () => form.targetProtocol,
  (value) => {
    targetWebserviceConfig.soapVersion = value === "SOAP_12" ? "SOAP_12" : "SOAP_11";
    if (value === "SOAP_11" || value === "SOAP_12") {
      form.targetMethod = "POST";
      if (!targetWebserviceConfig.operationName) {
        targetWebserviceConfig.operationName = "submitPayload";
        targetWebserviceConfig.requestRootName = "submitPayload";
        targetWebserviceConfig.responseRootName = "submitPayloadResponse";
      }
    }
  },
);

onMounted(async () => {
  const [, rules] = await Promise.all([
    loadRuntimeClusters(),
    studioApi.fieldMappingRules.optionSummaries(),
  ]);
  fieldMappingRules.value = rules;
  if (!isCreateMode.value && serviceId.value) {
    const loaded = await loadService(serviceId.value);
    if (!loaded) {
      return;
    }
  } else {
    addMapping();
  }
  await hydrateServiceDependencies();
  activeStep.value = route.query.debug === "1" ? 3 : 0;
});

function loadFieldMappingRuleDetail(id: EntityId) {
  return studioApi.fieldMappingRules.get(id);
}

async function loadService(id: EntityId) {
  try {
    detailLoadError.value = "";
    const detail = await studioApi.protocolConversions.get(id);
    ensureRuntimeClusterOption(detail.runtimeClusterId, detail.runtimeClusterName);
    Object.assign(form, detail);
    acceptRuntimeCluster(form.runtimeClusterId);
    Object.assign(webserviceConfig, detail.webserviceConfig ?? {});
    Object.assign(targetWebserviceConfig, detail.targetWebserviceConfig ?? detail.webserviceConfig ?? {});
    debugSoapOptions.value = { header: "{}", requestBody: "" };
    debugSoapHeaders.value = {};
    debugSoapEntityBody.value = "";
    return true;
  } catch (error) {
    const message = resolveErrorMessage(error, "加载协议转换服务失败");
    detailLoadError.value = message;
    ElMessage.error(message);
    return false;
  }
}

async function loadDatasourcesForRuntimeCluster() {
  const runtimeClusterId = form.runtimeClusterId;
  const sequence = ++datasourceLoadSequence;
  const options = runtimeClusterId == null || runtimeClusterId === ""
    ? []
    : await studioApi.datasources.optionsByRuntimeCluster(runtimeClusterId);
  if (sequence === datasourceLoadSequence && String(form.runtimeClusterId ?? "") === String(runtimeClusterId ?? "")) {
    datasources.value = options;
  }
}

async function handleRuntimeClusterChange() {
  const nextRuntimeClusterId = form.runtimeClusterId;
  if (!await confirmRuntimeClusterChange(nextRuntimeClusterId)) {
    form.runtimeClusterId = acceptedRuntimeClusterId.value ?? "";
    return;
  }
  form.runtimeClusterId = acceptedRuntimeClusterId.value ?? nextRuntimeClusterId ?? "";
  datasourceLoadSequence += 1;
  form.targetDatasourceId = undefined;
  datasources.value = [];
  form.targetPath = "";
  form.targetHeaders = {};
  form.targetQuery = {};
  form.targetBodyTemplate = "";
  form.fieldMappings = [];
  form.fixedFields = [];
  form.rawTransformers = [];
  debugSummary.value = null;
  debugTrace.value = null;
  debugError.value = "";
  debugResultText.value = "";
  rawDebugResultText.value = "";
  curlCommand.value = "";
  debugSoapOptions.value = { header: "{}", requestBody: "" };
  debugSoapHeaders.value = {};
  debugSoapEntityBody.value = "";
  await loadDatasourcesForRuntimeCluster();
}

async function retryLoadService() {
  if (!serviceId.value) {
    return;
  }
  if (await loadService(serviceId.value)) {
    await hydrateServiceDependencies();
  }
}

async function hydrateServiceDependencies() {
  if (!form.runtimeClusterId) {
    form.runtimeClusterId = resolveInitialRuntimeClusterId() ?? "";
  }
  acceptRuntimeCluster(form.runtimeClusterId);
  await loadDatasourcesForRuntimeCluster();
}

function addMapping() {
  form.fieldMappings.push({
    sourcePosition: "BODY",
    sourceField: "",
    targetField: "",
    valueType: "STRING",
    required: false,
    transformers: [],
  });
}

function addFixedField() {
  form.fixedFields = form.fixedFields ?? [];
  form.fixedFields.push({ targetField: "", value: "", valueType: "STRING" });
}

function removeFixedField(index: number) {
  form.fixedFields = form.fixedFields ?? [];
  form.fixedFields.splice(index, 1);
}

function rawTransformerSummary(transformers?: TransformerBinding[]) {
  if (!transformers?.length) {
    return "未配置";
  }
  return transformers
    .map((item) => item.mappingName || item.mappingCode || item.transformerCode)
    .filter(Boolean)
    .join(", ");
}

function saveRawTransformers(transformers: TransformerBinding[]) {
  form.rawTransformers = transformers;
  rawTransformerDialogVisible.value = false;
}

async function saveService() {
  if (detailLoadError.value && serviceId.value) {
    ElMessage.error(detailLoadError.value);
    return;
  }
  if (!form.runtimeClusterId) {
    ElMessage.warning(t("web.runtimeClusterSelection.selectFirst"));
    return;
  }
  if (!targetHeadersValid.value || !targetQueryValid.value || !responseStatusValid.value) {
    ElMessage.error("请先修正目标协议中的 JSON 配置");
    activeStep.value = 1;
    return;
  }
  if (targetBodyPreviewError.value) {
    ElMessage.error(targetBodyPreviewError.value);
    activeStep.value = 2;
    return;
  }
  saving.value = true;
  try {
    const payload = buildPayload();
    const saved = await studioApi.protocolConversions.save(payload);
    Object.assign(form, saved);
    ElMessage.success("协议转换服务已保存");
    if (isCreateMode.value) {
      await router.replace(`/protocol-conversions/${saved.id}/edit`);
    }
  } finally {
    saving.value = false;
  }
}

async function publishService() {
  if (detailLoadError.value && serviceId.value) {
    ElMessage.error(detailLoadError.value);
    return;
  }
  if (!form.id) {
    await saveService();
  }
  if (!form.id) {
    return;
  }
  const saved = await studioApi.protocolConversions.publish(form.id);
  Object.assign(form, saved);
  ElMessage.success("协议转换服务已发布");
}

async function debugService() {
  if (!form.id) {
    return;
  }
  if (!debugHeadersValid.value || !debugQueryValid.value) {
    ElMessage.error("请先修正调试请求中的 JSON 配置");
    return;
  }
  debugging.value = true;
  debugError.value = "";
  debugResultText.value = "";
  debugTrace.value = null;
  debugSummary.value = null;
  rawDebugResultText.value = "";
  rawDebugResultVisible.value = false;
  try {
    const soapDebug = isSoapSource.value;
    const result = await studioApi.protocolConversions.debug(form.id, {
      headers: soapDebug ? soapDebugHeadersForRequest() : debugHeaders.value,
      query: soapDebug ? {} : debugQuery.value,
      rawBody: soapDebug ? soapDebugBodyForRequest() : debugRawBody.value,
    });
    debugResultText.value = formatDebugResult(result);
    debugSummary.value = formatDebugSummary(result);
    rawDebugResultText.value = stringifyJson(result);
    debugTrace.value = result.conversionTrace || null;
  } catch (error) {
    debugError.value = error instanceof Error ? error.message : "调试失败";
  } finally {
    debugging.value = false;
  }
}

function generateCurlCommand(mode: "bash" | "cmd") {
  if (!openEndpoint.value || openEndpoint.value === "-") {
    ElMessage.warning("请先保存服务生成开放地址");
    return;
  }
  try {
    curlCommand.value = isSoapSource.value ? buildSoapCurlCommand(mode) : buildHttpCurlCommand(mode);
    ElMessage.success(`已生成 cURL(${mode === "bash" ? "bash" : "cmd"})`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "生成 cURL 失败");
  }
}

function buildHttpCurlCommand(mode: "bash" | "cmd") {
  const headers = ensureProtocolOpenHeaders({ ...debugHeaders.value }, resolveSourceContentType());
  const bodyText = debugRawBody.value || "";
  return buildRawCurl(mode, openEndpoint.value, form.sourceMethod || "POST", headers, debugQuery.value, bodyText, resolveSourceContentType());
}

function buildSoapCurlCommand(mode: "bash" | "cmd") {
  const headers = ensureProtocolOpenHeaders(soapDebugHeadersForRequest(), resolveSoapContentType(), "text/xml");
  const soapAction = String(webserviceConfig.soapAction ?? "").trim();
  if (soapAction && !hasHeader(headers, "SOAPAction")) {
    headers.SOAPAction = soapAction;
  }
  const bodyText = soapDebugBodyForRequest();
  return buildRawCurl(mode, openEndpoint.value, "POST", headers, {}, bodyText, resolveSoapContentType());
}

function soapDebugHeadersForRequest() {
  if (isSoapEntityDebugMode.value) {
    return debugSoapHeaders.value;
  }
  return parseMaybeJsonObject(debugSoapOptions.value.header, "源 SOAP HTTP Header");
}

function soapDebugBodyForRequest() {
  if (!isSoapEntityDebugMode.value) {
    return String(debugSoapOptions.value.requestBody ?? "");
  }
  const bodyInnerXml = debugSoapEntityBody.value.trim();
  validateXmlFragment(bodyInnerXml, "入口 SOAP 实体内容");
  return buildDebugSoapEnvelope(bodyInnerXml);
}

async function copyCurlCommand() {
  if (!curlCommand.value) {
    return;
  }
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(curlCommand.value);
    } else {
      copyTextFallback(curlCommand.value);
    }
    ElMessage.success("cURL 已复制");
  } catch {
    copyTextFallback(curlCommand.value);
    ElMessage.success("cURL 已复制");
  }
}

function ensureProtocolOpenHeaders(headers: Record<string, unknown>, contentType: string, accept = "application/json") {
  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(headers || {})) {
    if (!key || value == null || String(value).trim() === "") {
      continue;
    }
    result[key] = value;
  }
  if (!hasHeader(result, "Accept")) {
    result.Accept = accept;
  }
  if (contentType && !hasHeader(result, "Content-Type")) {
    result["Content-Type"] = contentType;
  }
  if (form.tokenRequired) {
    const existingTokenKey = Object.keys(result).find((key) => key.toLowerCase() === "x-protocol-conversion-token");
    result[existingTokenKey || "X-Protocol-Conversion-Token"] = "<订阅Token>";
  }
  return result;
}

function hasHeader(headers: Record<string, unknown>, name: string) {
  return Object.keys(headers).some((key) => key.toLowerCase() === name.toLowerCase());
}

function resolveSourceContentType() {
  if (form.sourceProtocol === "HTTP_XML") {
    return "application/xml;charset=UTF-8";
  }
  return "application/json;charset=UTF-8";
}

function resolveSoapContentType() {
  return form.sourceProtocol === "SOAP_12"
    ? "application/soap+xml;charset=UTF-8"
    : "text/xml;charset=UTF-8";
}

function buildRawCurl(
  mode: "bash" | "cmd",
  url: string,
  method: string,
  headers: Record<string, unknown>,
  query: Record<string, unknown>,
  bodyText: string,
  contentType: string,
) {
  const quote = mode === "bash" ? quoteBash : quoteCmd;
  const joiner = mode === "bash" ? " \\\n" : " ^\n";
  const normalizedMethod = String(method || "POST").toUpperCase();
  const lines = [`curl -X ${normalizedMethod} ${quote(appendQueryParamsForCurl(url, query))}`];
  for (const [key, value] of Object.entries(headers)) {
    if (!key || value == null || String(value).trim() === "") {
      continue;
    }
    lines.push(`  -H ${quote(`${key}: ${String(value)}`)}`);
  }
  if (bodyText && bodyText.trim()) {
    if (contentType && !hasHeader(headers, "Content-Type")) {
      lines.push(`  -H ${quote(`Content-Type: ${contentType}`)}`);
    }
    lines.push(`  --data-raw ${quote(bodyText)}`);
  }
  return lines.join(joiner);
}

function appendQueryParamsForCurl(url: string, query: Record<string, unknown>) {
  const parsed = new URL(url, window.location.origin);
  for (const [key, value] of Object.entries(query || {})) {
    if (!key || value == null || String(value).trim() === "") {
      continue;
    }
    if (Array.isArray(value)) {
      parsed.searchParams.set(key, value.map((item) => String(item)).join(","));
    } else {
      parsed.searchParams.set(key, String(value));
    }
  }
  return parsed.toString();
}

function quoteBash(value: string) {
  return `'${value.replace(/'/g, "'\\''")}'`;
}

function quoteCmd(value: string) {
  return `"${value.replace(/"/g, '\\"')}"`;
}

function buildPayload(): ProtocolConversionServiceSaveRequest {
  return {
    id: form.id,
    serviceCode: form.serviceCode,
    serviceName: form.serviceName,
    runtimeClusterId: form.runtimeClusterId ?? "",
    tokenRequired: form.tokenRequired,
    defaultSubscriptionName: form.defaultSubscriptionName,
    sourceProtocol: form.sourceProtocol,
    sourceMethod: form.sourceMethod,
    sourceDataNodePath: form.sourceDataNodePath,
    webserviceConfig: { ...webserviceConfig, enabled: true },
    conversionMode: form.conversionMode,
    fieldMappings: form.fieldMappings,
    rawTransformers: form.rawTransformers ?? [],
    fixedFields: form.fixedFields ?? [],
    bodyBridgeOptions: normalizedBodyBridgeOptions(),
    requestPassthrough: form.requestPassthrough ?? {},
    targetDatasourceId: form.targetDatasourceId,
    targetPath: form.targetPath,
    targetProtocol: form.targetProtocol,
    targetMethod: form.targetMethod,
    targetHeaders: form.targetHeaders ?? {},
    targetQuery: form.targetQuery ?? {},
    targetWebserviceConfig: { ...targetWebserviceConfig, enabled: true },
    targetBodyTemplate: form.targetBodyTemplate,
    targetDataNodePath: form.targetDataNodePath,
    payloadMode: form.payloadMode,
    batchSize: 1,
    responseStatus: form.responseStatus ?? {},
  };
}

function normalizedBodyBridgeOptions() {
  const options = { ...(form.bodyBridgeOptions ?? {}) };
  if (form.conversionMode === "BODY_BRIDGE") {
    delete options.targetField;
  }
  return options;
}

function buildTargetBodyPreview(): { text: string; error: string } {
  if (form.targetBodyTemplate?.trim()) {
    return { text: form.targetBodyTemplate, error: "" };
  }
  try {
    if (form.conversionMode === "BODY_BRIDGE") {
      return buildBodyBridgeTargetBodyPreview();
    }
    const payload = isArrayPayload.value ? buildArrayPreviewPayload() : buildObjectPreviewPayload();
    if (form.targetProtocol === "HTTP_JSON") {
      return { text: JSON.stringify(payload, null, 2), error: "" };
    }
    if (form.targetProtocol === "HTTP_XML") {
      return { text: formatXmlPreview(objectToXml("root", payload)), error: "" };
    }
    const fields = targetSoapFields();
    const dataNodePath = resolvePreviewDataNodePath(false);
    const arrayError = isArrayPayload.value ? validateSoapArrayFieldParents(fields, dataNodePath) : "";
    if (arrayError) {
      return { text: "", error: arrayError };
    }
    const bodyInnerXml = isArrayPayload.value
      ? buildSoapRecordsRepeatXmlByPath(fields, dataNodePath, 6)
      : buildSoapFieldsXmlByParentPath(fields, "", 6);
    return {
      text: formatXmlPreview(buildSoapEnvelope({
        soapVersion: form.targetProtocol === "SOAP_12" ? "SOAP_12" : "SOAP_11",
        namespaceUri: targetWebserviceConfig.namespaceUri || "http://studio.jdragon.com/protocol-conversion-target",
        requestRootName: targetWebserviceConfig.requestRootName || targetWebserviceConfig.operationName || "submitPayload",
        includeToken: false,
        bodyInnerXml,
      })),
      error: "",
    };
  } catch (error) {
    return { text: "", error: error instanceof Error ? error.message : "目标 Body 预览生成失败" };
  }
}

function buildBodyBridgeTargetBodyPreview(): { text: string; error: string } {
  const rawMode = bodyBridgeMode.value === "RAW";
  const note = rawMode ? "运行时原样转发入口 Body" : "运行时按入口 Body 结构转换";
  const payload = { bodyBridge: note };
  if (form.targetProtocol === "HTTP_JSON") {
    return { text: rawMode ? "{{rawBody}}" : JSON.stringify(payload, null, 2), error: "" };
  }
  if (form.targetProtocol === "HTTP_XML") {
    return { text: rawMode ? "{{rawBody}}" : formatXmlPreview(objectToXml("root", payload)), error: "" };
  }
  const bodyInnerXml = rawMode
    ? "{{rawBody}}"
    : "<bodyBridge>运行时按入口 Body 结构转换</bodyBridge>";
  return {
    text: formatXmlPreview(buildSoapEnvelope({
      soapVersion: form.targetProtocol === "SOAP_12" ? "SOAP_12" : "SOAP_11",
      namespaceUri: targetWebserviceConfig.namespaceUri || "http://studio.jdragon.com/protocol-conversion-target",
      requestRootName: targetWebserviceConfig.requestRootName || targetWebserviceConfig.operationName || "submitPayload",
      includeToken: false,
      bodyInnerXml,
    })),
    error: "",
  };
}

function buildObjectPreviewPayload() {
  const payload: Record<string, unknown> = {};
  for (const mapping of form.fieldMappings) {
    const path = targetFullPath(mapping.targetPath, mapping.targetField);
    if (!path) {
      continue;
    }
    assignPath(payload, path, `{{${mapping.targetField || lastPathSegment(path)}}}`);
  }
  for (const fixed of form.fixedFields ?? []) {
    const path = targetFullPath(fixed.targetPath, fixed.targetField);
    if (!path) {
      continue;
    }
    assignPath(payload, path, fixed.value ?? "");
  }
  return payload;
}

function buildArrayPreviewPayload() {
  const dataNodePath = resolvePreviewDataNodePath(form.targetProtocol !== "HTTP_JSON");
  const record = stripPreviewRecordNode(buildObjectPreviewPayload(), dataNodePath);
  if (!dataNodePath) {
    return [record];
  }
  const payload: Record<string, unknown> = {};
  assignPath(payload, dataNodePath, [record]);
  return payload;
}

function targetSoapFields(): SoapFieldSpec[] {
  const fields: SoapFieldSpec[] = [];
  for (const mapping of form.fieldMappings) {
    const path = targetFullPath(mapping.targetPath, mapping.targetField);
    if (!path) {
      continue;
    }
    fields.push({
      key: path,
      elementName: lastPathSegment(path),
      parentNode: parentPath(path),
      value: `{{${mapping.targetField || lastPathSegment(path)}}}`,
    });
  }
  for (const fixed of form.fixedFields ?? []) {
    const path = targetFullPath(fixed.targetPath, fixed.targetField);
    if (!path) {
      continue;
    }
    fields.push({
      key: path,
      elementName: lastPathSegment(path),
      parentNode: parentPath(path),
      value: fixed.value == null ? "" : String(fixed.value),
    });
  }
  return fields;
}

function resolvePreviewDataNodePath(required: boolean) {
  const configured = String(form.targetDataNodePath ?? "").trim();
  if (configured) {
    return configured;
  }
  const inferred = inferCommonParentPath();
  if (inferred) {
    return inferred;
  }
  if (required) {
    throw new Error("XML/SOAP 数组模式需要明确目标数据节点，或让所有字段拥有同一个非空父路径");
  }
  return "";
}

function inferCommonParentPath() {
  const parents = new Set<string>();
  for (const mapping of form.fieldMappings) {
    const path = targetFullPath(mapping.targetPath, mapping.targetField);
    if (!path) {
      continue;
    }
    const parent = parentPath(path);
    if (!parent) {
      return "";
    }
    parents.add(parent);
  }
  for (const fixed of form.fixedFields ?? []) {
    const path = targetFullPath(fixed.targetPath, fixed.targetField);
    if (!path) {
      continue;
    }
    const parent = parentPath(path);
    if (!parent) {
      return "";
    }
    parents.add(parent);
  }
  return parents.size === 1 ? Array.from(parents)[0] : "";
}

function stripPreviewRecordNode(payload: Record<string, unknown>, dataNodePath: string) {
  const nested = readPath(payload, dataNodePath);
  return nested && typeof nested === "object" && !Array.isArray(nested) ? nested as Record<string, unknown> : payload;
}

function assignPath(target: Record<string, unknown>, path: string, value: unknown) {
  const segments = path.split(".").map((item) => item.trim()).filter(Boolean);
  let current: Record<string, unknown> = target;
  segments.forEach((segment, index) => {
    if (index === segments.length - 1) {
      current[segment] = value;
      return;
    }
    if (!current[segment] || typeof current[segment] !== "object" || Array.isArray(current[segment])) {
      current[segment] = {};
    }
    current = current[segment] as Record<string, unknown>;
  });
}

function readPath(source: unknown, path: string) {
  if (!path) {
    return source;
  }
  let current = source;
  for (const segment of path.split(".").map((item) => item.trim()).filter(Boolean)) {
    if (!current || typeof current !== "object") {
      return undefined;
    }
    current = (current as Record<string, unknown>)[segment];
  }
  return current;
}

function objectToXml(rootName: string, value: unknown) {
  return `<${safeXmlName(rootName)}>${appendXmlValue(value, "item")}</${safeXmlName(rootName)}>`;
}

function appendXmlValue(value: unknown, fallbackName: string): string {
  if (Array.isArray(value)) {
    return value.map((item) => `<${safeXmlName(fallbackName)}>${appendXmlValue(item, "item")}</${safeXmlName(fallbackName)}>`).join("");
  }
  if (value && typeof value === "object") {
    return Object.entries(value as Record<string, unknown>).map(([key, item]) => {
      const name = safeXmlName(key);
      if (Array.isArray(item)) {
        return item.map((entry) => `<${name}>${appendXmlValue(entry, name)}</${name}>`).join("");
      }
      return `<${name}>${appendXmlValue(item, name)}</${name}>`;
    }).join("");
  }
  return escapeXml(value == null ? "" : String(value));
}

function targetFullPath(path?: string, field?: string) {
  return String(path || field || "").trim();
}

function parentPath(path: string) {
  const index = path.lastIndexOf(".");
  return index > 0 ? path.slice(0, index) : "";
}

function lastPathSegment(path: string) {
  const segments = path.split(".").map((item) => item.trim()).filter(Boolean);
  return segments[segments.length - 1] || path;
}

function safeXmlName(value: string) {
  const normalized = String(value || "node").replace(/[^A-Za-z0-9_.-]/g, "") || "node";
  return /^[A-Za-z_]/.test(normalized) ? normalized : `n${normalized}`;
}

function escapeXml(value: string) {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

function formatXmlPreview(value: string) {
  const formatted = formatXmlText(value);
  return formatted.ok ? formatted.value : value;
}

function generateDebugSoapEntitySample() {
  debugSoapEntityBody.value = formatXmlFragment(debugSoapSampleInnerXml(), "入口 SOAP 实体内容");
}

function debugSoapSampleInnerXml() {
  return "<customerId>1001</customerId><amount>12.30</amount><remark>Protocol conversion sample</remark>";
}

function buildDebugSoapEnvelope(bodyInnerXml: string) {
  return buildSoapEnvelope({
    soapVersion: form.sourceProtocol === "SOAP_12" ? "SOAP_12" : "SOAP_11",
    namespaceUri: webserviceConfig.namespaceUri || "http://studio.jdragon.com/protocol-conversion",
    requestRootName: webserviceConfig.requestRootName || webserviceConfig.operationName || "convertPayload",
    includeToken: false,
    bodyInnerXml,
  });
}

function formatDebugBody() {
  const formatted = formatXmlText(debugRawBody.value, "入口请求 XML");
  if (!formatted.ok) {
    ElMessage.error(formatted.error || "XML 格式化失败");
    return;
  }
  debugRawBody.value = formatted.value;
}

function formatDebugSoapEntityBody() {
  try {
    debugSoapEntityBody.value = formatXmlFragment(debugSoapEntityBody.value, "入口 SOAP 实体内容");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "SOAP 实体内容格式化失败");
  }
}

function validateXmlFragment(value: string, label: string) {
  if (!value.trim()) {
    return;
  }
  const formatted = formatXmlText(`<__body>${value}</__body>`, label);
  if (!formatted.ok) {
    throw new Error(formatted.error || `${label} XML 不合法`);
  }
}

function formatXmlFragment(value: string, label: string) {
  const text = value.trim();
  if (!text) {
    return "";
  }
  const formatted = formatXmlText(`<__body>${text}</__body>`, label);
  if (!formatted.ok) {
    throw new Error(formatted.error || `${label} XML 不合法`);
  }
  const lines = formatted.value.split("\n");
  return lines
    .slice(1, -1)
    .map((line) => line.startsWith("  ") ? line.slice(2) : line)
    .join("\n");
}

function parseMaybeJsonObject(value: unknown, label: string) {
  if (!value) {
    return {};
  }
  if (typeof value === "object" && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  const text = String(value ?? "").trim();
  if (!text) {
    return {};
  }
  const parsed = JSON.parse(text);
  if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
    throw new Error(`${label} 必须是 JSON 对象`);
  }
  return parsed as Record<string, unknown>;
}

function stringifyJson(value: unknown) {
  return JSON.stringify(value ?? {}, null, 2);
}

function formatDebugResult(value: unknown) {
  const result = value as { responseBody?: unknown; targetBody?: unknown };
  const body = result && typeof result === "object" && ("responseBody" in result || "targetBody" in result)
    ? result.responseBody ?? result.targetBody
    : value;
  if (typeof body === "string" && body.trim().startsWith("<")) {
    return formatXmlPreview(body);
  }
  return JSON.stringify(body ?? {}, null, 2);
}

function formatDebugSummary(value: unknown): ProtocolConversionDebugSummary | null {
  const result = value as {
    requestId?: string;
    serviceCode?: string;
    status?: string;
    targetHttpStatus?: number;
    targetContentType?: string;
    receivedCount?: number | string;
    successCount?: number | string;
    failedCount?: number | string;
  };
  if (!result || typeof result !== "object") {
    return null;
  }
  return {
    requestId: result.requestId,
    serviceCode: result.serviceCode,
    status: result.status,
    targetHttpStatus: result.targetHttpStatus,
    targetContentType: result.targetContentType,
    receivedCount: result.receivedCount,
    successCount: result.successCount,
    failedCount: result.failedCount,
  };
}

function stringOption(key: string, defaultValue: string) {
  const value = form.bodyBridgeOptions?.[key];
  return value == null || !String(value).trim() ? defaultValue : String(value);
}

function setBodyBridgeOption(key: string, value: string) {
  form.bodyBridgeOptions = {
    ...(form.bodyBridgeOptions ?? {}),
    [key]: value,
  };
}

function goStep(index: number) {
  const targetStep = Math.max(0, Math.min(wizardSteps.length - 1, index));
  if (targetStep > activeStep.value) {
    for (let step = activeStep.value; step < targetStep; step += 1) {
      if (!validateStep(step)) {
        return;
      }
    }
  }
  activeStep.value = targetStep;
}

function previousStep() {
  activeStep.value = Math.max(0, activeStep.value - 1);
}

function nextStep() {
  if (!validateStep(activeStep.value)) {
    return;
  }
  activeStep.value = Math.min(wizardSteps.length - 1, activeStep.value + 1);
}

function validateStep(step: number) {
  if (step === 0) {
    if (!String(form.serviceCode ?? "").trim()) {
      ElMessage.error("请填写服务编码");
      return false;
    }
    if (!String(form.serviceName ?? "").trim()) {
      ElMessage.error("请填写服务名称");
      return false;
    }
  }
  if (step === 1) {
    if (!targetHeadersValid.value || !targetQueryValid.value || !responseStatusValid.value) {
      ElMessage.error("请先修正目标协议中的 JSON 配置");
      return false;
    }
  }
  if (step === 2 && targetBodyPreviewError.value) {
    ElMessage.error(targetBodyPreviewError.value);
    return false;
  }
  return true;
}

function statusLabel(status?: string) {
  if (status === "ONLINE") {
    return "已发布";
  }
  if (status === "OFFLINE") {
    return "已下线";
  }
  return "草稿";
}

function statusTone(status?: string): "success" | "warning" | "neutral" | "primary" | "danger" {
  if (status === "ONLINE") {
    return "success";
  }
  if (status === "OFFLINE") {
    return "neutral";
  }
  return "warning";
}
</script>

<style scoped>
.protocol-conversion-editor {
  gap: 14px;
}

.service-wizard {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.service-wizard-step {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 76px;
  padding: 14px 16px;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  background: #fff;
  color: var(--studio-text-soft);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}

.service-wizard-step:hover,
.service-wizard-step.active {
  border-color: rgba(37, 99, 235, 0.42);
  box-shadow: 0 12px 28px rgba(37, 99, 235, 0.1);
  transform: translateY(-1px);
}

.service-wizard-step.done {
  border-color: rgba(22, 163, 74, 0.32);
}

.service-wizard-step strong {
  display: block;
  color: var(--studio-text);
  font-size: 14px;
}

.service-wizard-step small {
  display: block;
  margin-top: 4px;
  font-size: 12px;
}

.step-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  border-radius: 999px;
  background: rgba(37, 99, 235, 0.08);
  color: var(--studio-primary);
  font-weight: 700;
}

.service-wizard-step.done .step-index {
  background: rgba(22, 163, 74, 0.12);
  color: #15803d;
}

.protocol-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(260px, 1fr));
  gap: 2px 20px;
}

.protocol-conversion-editor :deep(.el-select),
.protocol-conversion-editor :deep(.el-input-number) {
  width: 100%;
}

.sub-panel,
.mode-panel,
.body-preview,
.curl-panel,
.trace-panel,
.debug-result-panel,
.debug-body-editor {
  display: grid;
  gap: 12px;
  margin-top: 14px;
  padding: 14px;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.72);
}

.bridge-warning {
  margin-top: 4px;
}

.sub-panel__header,
.body-preview__header,
.curl-panel__header,
.debug-body-editor__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.sub-panel__header p,
.body-preview__header p,
.curl-panel__header p,
.debug-body-editor__header p,
.advanced-hint {
  margin: 2px 0 0;
  color: var(--studio-text-soft);
  font-size: 12px;
}

.debug-body-editor__actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.debug-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 14px;
  padding: 10px 12px;
  border: 1px solid rgba(37, 99, 235, 0.14);
  border-radius: 8px;
  background: rgba(37, 99, 235, 0.04);
  color: var(--studio-text);
  font-size: 12px;
}

.protocol-json-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: 1fr;
  margin-top: 16px;
}

.passthrough-options {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  padding-top: 2px;
}

.protocol-json-grid--debug {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.section-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 0;
}

.raw-transformers-panel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  background: #fff;
}

.raw-transformers-panel__summary {
  min-width: 0;
  color: var(--studio-text);
  font-size: 13px;
  line-height: 1.5;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sub-title {
  margin: 18px 0 10px;
  font-size: 15px;
}

.wizard-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding-top: 14px;
}

.body-preview :deep(textarea),
.debug-result-panel :deep(textarea),
.debug-body-editor :deep(textarea),
:deep(.el-dialog textarea) {
  font-family: Consolas, "Courier New", monospace;
}

.advanced-collapse {
  margin-top: 14px;
}

@media (max-width: 1180px) {
  .service-wizard {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .protocol-form-grid {
    grid-template-columns: 1fr;
  }

  .protocol-json-grid,
  .protocol-json-grid--debug {
    grid-template-columns: 1fr;
  }

  .raw-transformers-panel {
    align-items: stretch;
    flex-direction: column;
  }

  .raw-transformers-panel__summary {
    white-space: normal;
  }
}

@media (max-width: 720px) {
  .service-wizard {
    grid-template-columns: 1fr;
  }
}
</style>
