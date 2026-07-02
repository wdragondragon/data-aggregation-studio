<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ serviceId ? "编辑数据接入服务" : "新建数据接入服务" }}</h3>
        <p>配置开放请求的字段来源、目标模型和写入映射。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button @click="router.push('/data-ingestion-services')">返回列表</el-button>
        <template v-if="detailLoadError && serviceId">
          <el-button type="primary" plain @click="retryLoadService">刷新</el-button>
        </template>
        <template v-else>
          <el-button type="primary" :loading="saving" @click="saveService">保存</el-button>
          <el-button v-if="form.id" type="success" :loading="publishing" @click="publishService">发布</el-button>
        </template>
      </div>
    </div>

    <SectionCard v-if="detailLoadError && serviceId" title="数据接入服务不可用" description="该数据接入服务可能已被删除、取消共享，或当前项目无权访问。">
      <el-result
        icon="warning"
        title="数据接入服务不可用"
        :sub-title="detailLoadError"
      />
    </SectionCard>

    <template v-else>
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

    <SectionCard v-if="activeStep === 0" title="一、基础信息" description="配置开放 API 基础信息。每个目标的来源路径在目标配置中单独维护。">
      <div class="section-toolbar">
        <div>
          <strong>基础信息</strong>
          <p>服务编码会组成开放 API 地址，保存后自动生成 serviceKey。</p>
        </div>
      </div>
      <el-form label-width="120px" class="ingestion-form-grid">
        <el-form-item label="服务名称" required>
          <el-input v-model="form.serviceName" placeholder="例如：客户资料接入" />
        </el-form-item>
        <el-form-item label="服务编码" required>
          <el-input v-model="form.serviceCode" placeholder="customer_ingest" :disabled="Boolean(form.id)" />
        </el-form-item>
        <el-form-item label="访问 Token">
          <el-switch v-model="form.tokenRequired" active-text="需要 Token" inactive-text="免 Token" />
        </el-form-item>
        <el-form-item v-if="!form.tokenRequired" label="默认订阅方">
          <el-input v-model="form.defaultSubscriptionName" placeholder="免 Token 调用" />
        </el-form-item>
      </el-form>
      <el-input :model-value="resolveEndpoint()" readonly placeholder="保存后生成接入地址" />

      <el-collapse v-model="webserviceCollapseNames" class="webservice-collapse">
        <el-collapse-item name="webservice">
          <template #title>
            <span class="webservice-title">WebService 设置</span>
          </template>
          <el-form label-width="120px" class="ingestion-form-grid">
            <el-form-item label="启用 WebService">
              <el-switch v-model="form.webserviceEnabled" active-text="启用 SOAP" inactive-text="关闭" @change="handleWebServiceToggle" />
            </el-form-item>
            <el-form-item label="SOAP 版本">
              <el-select v-model="form.webserviceConfig.soapVersion" :disabled="!form.webserviceEnabled">
                <el-option label="SOAP 1.1" value="SOAP_11" />
                <el-option label="SOAP 1.2" value="SOAP_12" />
              </el-select>
            </el-form-item>
            <el-form-item label="SOAP 请求类型">
              <el-input model-value="XML" disabled />
            </el-form-item>
            <el-form-item label="SOAP 响应类型">
              <el-input model-value="XML" disabled />
            </el-form-item>
            <el-form-item label="Namespace">
              <el-input v-model="form.webserviceConfig.namespaceUri" :disabled="!form.webserviceEnabled" placeholder="默认按服务编码生成" />
            </el-form-item>
            <el-form-item label="Operation">
              <el-input v-model="form.webserviceConfig.operationName" :disabled="!form.webserviceEnabled" placeholder="默认使用服务编码" />
            </el-form-item>
            <el-form-item label="SOAP Action">
              <el-input v-model="form.webserviceConfig.soapAction" :disabled="!form.webserviceEnabled" placeholder="默认 namespace/operation" />
            </el-form-item>
            <el-form-item label="请求根节点">
              <el-input v-model="form.webserviceConfig.requestRootName" :disabled="!form.webserviceEnabled" placeholder="默认使用 Operation" />
            </el-form-item>
            <el-form-item label="响应根节点">
              <el-input v-model="form.webserviceConfig.responseRootName" :disabled="!form.webserviceEnabled" placeholder="默认 OperationResponse" />
            </el-form-item>
          </el-form>
          <el-form-item label="WSDL 地址">
            <el-input :model-value="webserviceWsdlUrl" readonly placeholder="保存并启用后可预览 WSDL">
              <template #append>
                <el-button :disabled="!form.id || !form.webserviceEnabled" :loading="webservicePreviewLoading" @click="previewWebService()">预览</el-button>
              </template>
            </el-input>
          </el-form-item>
          <el-input
            v-if="webservicePreview?.wsdl"
            :model-value="webservicePreview.wsdl"
            class="xml-textarea"
            type="textarea"
            :rows="10"
            readonly
            placeholder="点击预览后展示 WSDL"
          />
        </el-collapse-item>
      </el-collapse>

    </SectionCard>

    <SectionCard v-if="activeStep === 1" title="二、目标配置" description="维护每个目标的来源截取、写入参数和字段映射。">
      <div v-if="targetConfigMode === 'overview'" class="target-card-grid">
        <button
          v-for="(source, index) in form.sourceBindings"
          :key="source.sourceCode || index"
          class="target-card"
          :class="{ active: activeSourceIndex === index, disabled: source.enabled === false }"
          type="button"
          @click="openTargetBinding(index)"
        >
          <span
            class="target-card-delete"
            :class="{ disabled: (form.sourceBindings?.length ?? 0) <= 1 }"
            role="button"
            tabindex="0"
            aria-label="删除目标配置"
            @click.stop="removeSourceBinding(index)"
            @keydown.enter.stop.prevent="removeSourceBinding(index)"
          >
            ×
          </span>
          <div class="target-card-header">
            <div class="target-card-title">
              <strong>{{ source.sourceName || source.sourceCode || `目标 ${index + 1}` }}</strong>
              <small>{{ source.sourceCode || `source_${index + 1}` }}</small>
            </div>
            <div class="target-card-status" :class="{ disabled: source.enabled === false }">
              {{ source.enabled === false ? "停用" : "启用" }}
            </div>
          </div>
          <div class="target-card-path">
            <div>
              <span>截取位置</span>
              <strong>{{ sourcePositionLabel(source.sourcePosition) }}</strong>
            </div>
            <div>
              <span>完整来源路径</span>
              <strong>{{ fullSourcePathForBinding(source) || "整个请求节点" }}</strong>
            </div>
          </div>
          <div class="target-card-facts">
            <div>
              <span>来源形态</span>
              <strong>{{ payloadModeLabel(source.payloadMode) }}</strong>
            </div>
            <div>
              <span>数据源类型</span>
              <strong>{{ sourceBindingDatasourceTypeLabel(source) }}</strong>
            </div>
            <div>
              <span>数据源</span>
              <strong>{{ sourceBindingDatasourceLabel(source) }}</strong>
            </div>
            <div>
              <span>模型表名</span>
              <strong>{{ sourceBindingModelLabel(source) }}</strong>
            </div>
            <div>
              <span>单次最大行数</span>
              <strong>{{ sourceMaxBatchSizeLabel(source) }}</strong>
            </div>
          </div>
          <div class="target-card-meta">
            <el-tag size="small" effect="plain">{{ source.targetType === "FILE" ? "文件模型" : "数据库表" }}</el-tag>
            <el-tag size="small" effect="plain">{{ (source.fieldMappings ?? []).length }} 个映射</el-tag>
          </div>
        </button>
        <button class="target-card target-card--add" type="button" @click="addSourceBinding">
          <span>+</span>
        </button>
      </div>

      <template v-else>
        <div class="target-detail-header">
          <div>
            <strong>{{ activeSourceBinding.sourceName || activeSourceBinding.sourceCode || `目标 ${activeSourceIndex + 1}` }}</strong>
            <p>{{ activeTargetFullPath || "读取整个请求节点" }}</p>
          </div>
          <div class="target-detail-actions">
            <el-button plain @click="targetConfigMode = 'overview'">返回目标列表</el-button>
            <el-button plain @click="copySourceBinding(activeSourceIndex)">复制当前</el-button>
            <el-button type="danger" plain :disabled="(form.sourceBindings?.length ?? 0) <= 1" @click="removeSourceBinding(activeSourceIndex)">删除当前</el-button>
          </div>
        </div>

        <div class="target-secondary-tabs">
          <button type="button" :class="{ active: targetConfigMode === 'params' }" @click="targetConfigMode = 'params'">目标参数</button>
          <button type="button" :class="{ active: targetConfigMode === 'mapping' }" @click="targetConfigMode = 'mapping'">字段映射</button>
        </div>

        <template v-if="targetConfigMode === 'params'">
          <div class="section-toolbar section-toolbar--spaced">
            <div>
              <strong>目标参数</strong>
              <p>每个目标独立配置从请求中截取数据的完整路径。</p>
            </div>
          </div>
          <el-form label-width="120px" class="ingestion-form-grid">
            <el-form-item label="目标编码" required>
              <el-input v-model="activeSourceBinding.sourceCode" placeholder="source_1" @input="handleActiveSourceMetaChange" />
            </el-form-item>
            <el-form-item label="目标名称">
              <el-input v-model="activeSourceBinding.sourceName" placeholder="订单明细" @input="handleActiveSourceMetaChange" />
            </el-form-item>
            <el-form-item label="截取位置">
              <el-select v-model="activeSourceBinding.sourcePosition" @change="handleActiveSourceMetaChange">
                <el-option v-for="item in sourcePositionOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="完整来源路径">
              <el-input v-model="activeSourceBinding.sourcePath" placeholder="例如：data.order、data.items；空值表示整个请求节点" @input="handleActiveSourcePathChange" />
            </el-form-item>
            <el-form-item label="来源形态">
              <el-radio-group v-model="activeSourceBinding.payloadMode" @change="handleActiveSourceMetaChange">
                <el-radio-button value="OBJECT">对象</el-radio-button>
                <el-radio-button value="ARRAY">数组</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="单次最大行数">
              <el-input-number
                v-model="activeSourceBinding.maxBatchSize"
                class="full-width target-max-batch-input"
                :min="1"
                :controls="false"
                placeholder="不限制"
                @change="handleActiveSourceMetaChange"
              />
            </el-form-item>
            <el-form-item label="启用目标">
              <el-switch v-model="activeSourceBinding.enabled" @change="handleActiveSourceMetaChange" />
            </el-form-item>
            <el-form-item label="目标类型">
              <el-radio-group v-model="form.targetType" @change="handleTargetTypeChange">
                <el-radio-button value="DATABASE">数据库表</el-radio-button>
                <el-radio-button value="FILE">文件模型</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="数据源类型" required>
              <el-select v-model="targetDatasourceType" filterable clearable placeholder="选择数据源类型" @change="handleDatasourceTypeChange">
                <el-option
                  v-for="item in datasourceTypeOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="数据源" required>
              <el-select v-model="form.datasourceId" filterable clearable :disabled="!targetDatasourceType" placeholder="选择数据源" @change="handleDatasourceChange">
                <el-option v-for="item in filteredDatasources" :key="item.id" :label="`${item.name} / ${item.typeCode}`" :value="item.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="模型" required>
              <el-select
                v-model="form.modelId"
                filterable
                remote
                clearable
                :disabled="!form.datasourceId"
                placeholder="选择目标模型"
                :remote-method="searchModels"
                @visible-change="handleModelDropdownVisible"
                @change="handleModelChange"
              >
                <el-option v-for="item in models" :key="item.id" :label="`${item.name} / ${item.physicalLocator}`" :value="item.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="字段">
              <el-button plain :disabled="!form.datasourceId || !form.modelId || modelDetailLoading" :loading="resolving || modelDetailLoading" @click="resolveFields">解析目标字段</el-button>
            </el-form-item>
          </el-form>
          <div class="writer-options-panel">
            <div class="writer-options-header">
              <div>
                <strong>高级写入参数</strong>
                <p>{{ runtimeSchemaTitle("writer", form.datasourceId) || "选择数据源后加载 Writer 参数表单" }}</p>
              </div>
              <el-tag
                v-if="runtimeSchemaFor('writer', form.datasourceId)"
                :type="runtimeStatusType('writer', form.datasourceId)"
              >
                {{ runtimeStatusLabel("writer", form.datasourceId) }}
              </el-tag>
            </div>

            <div v-if="writerRuntimeLoading" class="writer-options-loading">正在加载 Writer 参数...</div>
            <HttpRequestOptionsEditor
              v-else-if="writerAdvancedFields.length && isHttpWriterTarget()"
              :fields="writerAdvancedFields"
              :model-value="form.writerOptions ?? {}"
              :dynamic-function-fields="writerDynamicFunctionFields()"
              @update:model-value="updateWriterOptions($event)"
            />
            <MetaFormRenderer
              v-else-if="writerAdvancedFields.length"
              :fields="writerAdvancedFields"
              :model-value="form.writerOptions ?? {}"
              :dynamic-function-fields="writerDynamicFunctionFields()"
              @update:model-value="updateWriterOptions($event)"
            />
            <el-alert
              v-else-if="runtimeSchemaFor('writer', form.datasourceId) && !runtimeSchemaFor('writer', form.datasourceId)?.runtimeSupported"
              type="warning"
              :closable="false"
              show-icon
              title="当前 Writer 插件暂不支持运行参数表单"
            />
            <el-alert
              v-else-if="runtimeSchemaFor('writer', form.datasourceId)"
              type="info"
              :closable="false"
              show-icon
              title="当前 Writer 插件没有额外运行参数"
            />
            <el-alert
              v-else-if="!form.datasourceId"
              type="info"
              :closable="false"
              show-icon
              title="选择数据源后会按 Writer 插件自动渲染运行参数"
            />
            <div v-else class="writer-options-fallback">
              <el-alert
                type="warning"
                :closable="false"
                show-icon
                title="未获取到 Writer 参数 schema，可临时使用 JSON 参数覆盖"
              />
              <JsonEditor v-model="writerOptionsText" title="Writer 运行参数" description="支持 writeMode、fileName 等 writer 参数覆盖。" height="180px" />
            </div>
          </div>
        </template>

        <template v-else>
          <StudioTableShell min-width="1040px">
            <el-table :data="form.fieldMappings" border>
              <el-table-column label="序号" width="70" align="center">
                <template #default="{ $index }">{{ $index + 1 }}</template>
              </el-table-column>
              <el-table-column label="取值位置" width="150">
                <template #default="{ row }">
                  <el-select v-model="row.sourcePosition">
                    <el-option v-for="item in sourcePositionOptions" :key="item.value" :label="item.label" :value="item.value" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="请求字段" min-width="180">
                <template #default="{ row }">
                  <el-input v-model="row.sourceField" placeholder="支持 a.b 路径" />
                </template>
              </el-table-column>
              <el-table-column label="目标字段" min-width="180">
                <template #default="{ row }">
                  <el-input v-model="row.targetField" />
                </template>
              </el-table-column>
              <el-table-column label="类型" width="130">
                <template #default="{ row }">
                  <el-select v-model="row.valueType">
                    <el-option v-for="item in valueTypes" :key="item" :label="item" :value="item" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="必填" width="90" align="center">
                <template #default="{ row }">
                  <el-switch v-model="row.required" />
                </template>
              </el-table-column>
              <el-table-column label="默认值" min-width="150">
                <template #default="{ row }">
                  <el-input v-model="row.defaultValue" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="90" align="center">
                <template #default="{ $index }">
                  <el-button link type="danger" @click="removeMapping($index)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </StudioTableShell>
          <div class="mapping-actions">
            <el-button plain @click="addMapping">新增映射</el-button>
          </div>
        </template>
      </template>
    </SectionCard>

    <SectionCard v-if="activeStep === 2" title="三、发布调试" description="显式编辑开放请求内容；HTTP 使用 JSON Body，WebService 使用业务实体 XML 并自动包装 SOAP。">
      <el-alert
        v-if="!form.id"
        class="service-debug-alert"
        type="info"
        show-icon
        :closable="false"
        title="请先保存服务，保存后会生成接入地址并允许发送调试请求。"
      />

      <div class="ingestion-debug-toolbar">
        <el-radio-group v-model="debugMode" size="small">
          <el-radio-button value="http">HTTP JSON</el-radio-button>
          <el-radio-button v-if="form.webserviceEnabled" value="soap">WebService SOAP</el-radio-button>
        </el-radio-group>
        <div class="ingestion-debug-actions">
          <el-button v-if="debugMode === 'soap'" plain :disabled="!form.id || !form.webserviceEnabled" :loading="webservicePreviewLoading" @click="previewWebService()">预览 WSDL</el-button>
          <el-button v-if="debugMode === 'soap'" plain :disabled="!form.id || !form.webserviceEnabled" @click="generateSoapEnvelopeFromEntity">生成 SOAP 包装</el-button>
          <el-button plain :disabled="debugCurlDisabled" @click="generateCurlCommand('bash')">生成 cURL(bash)</el-button>
          <el-button plain :disabled="debugCurlDisabled" @click="generateCurlCommand('cmd')">生成 cURL(cmd)</el-button>
          <el-button type="primary" :disabled="!form.id" :loading="debugging || webserviceDebugging" @click="debugCurrentMode">发送调试</el-button>
        </div>
      </div>

      <template v-if="debugMode === 'http'">
        <el-form-item v-if="endpointUrl" label="接入地址">
          <el-input :model-value="endpointUrl" readonly />
        </el-form-item>
        <div class="ingestion-debug-layout">
          <section class="ingestion-debug-panel">
            <div class="ingestion-debug-panel__header">
              <div>
                <strong>Header 参数</strong>
                <p>HTTP 请求头；服务需要 Token 时，生成 cURL 会自动补占位值。</p>
              </div>
              <el-button plain size="small" @click="appendHttpHeaderRow">添加参数</el-button>
            </div>
            <StudioTableShell min-width="640px">
              <el-table :data="httpHeaderRows" border size="small" table-layout="fixed">
                <el-table-column label="参数名" min-width="220">
                  <template #default="{ row }">
                    <span v-if="row.readonly">{{ row.name }}</span>
                    <el-input v-else :model-value="row.name" placeholder="Header 名称" @update:model-value="renameHttpHeader(row.key, String($event ?? ''))" />
                  </template>
                </el-table-column>
                <el-table-column label="值" min-width="260">
                  <template #default="{ row }">
                    <el-input :model-value="stringValue(row.value)" :placeholder="row.placeholder" @update:model-value="updateHttpHeaderValue(row.key, String($event ?? ''))" />
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="86" align="center" header-align="center">
                  <template #default="{ row }">
                    <el-button link type="danger" :disabled="row.readonly" @click="removeHttpHeader(row.key)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </StudioTableShell>
          </section>

          <section class="ingestion-debug-panel">
            <div class="ingestion-debug-panel__header">
              <div>
                <strong>Query 参数</strong>
                <p>拼接在接入地址后的查询参数。</p>
              </div>
              <el-button plain size="small" @click="appendHttpQueryRow">添加参数</el-button>
            </div>
            <StudioTableShell min-width="640px">
              <el-table :data="httpQueryRows" border size="small" table-layout="fixed">
                <el-table-column label="参数名" min-width="220">
                  <template #default="{ row }">
                    <el-input :model-value="row.name" placeholder="Query 名称" @update:model-value="renameHttpQuery(row.key, String($event ?? ''))" />
                  </template>
                </el-table-column>
                <el-table-column label="值" min-width="260">
                  <template #default="{ row }">
                    <el-input :model-value="stringValue(row.value)" placeholder="参数值" @update:model-value="updateHttpQueryValue(row.key, String($event ?? ''))" />
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="86" align="center" header-align="center">
                  <template #default="{ row }">
                    <el-button link type="danger" @click="removeHttpQuery(row.key)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </StudioTableShell>
          </section>

          <JsonEditor
            v-model="debugBody"
            title="JSON Body"
            description="填写完整请求 Body；支持对象、数组或各目标完整来源路径对应的数据结构。"
            placeholder="{&#10;  &quot;data&quot;: {&#10;    &quot;items&quot;: []&#10;  }&#10;}"
            height="320px"
          />
        </div>
        <JsonEditor :model-value="debugResult" title="调试结果" readonly height="180px" />
      </template>

      <template v-else>
        <el-form-item label="WSDL 地址">
          <el-input :model-value="webserviceWsdlUrl" readonly placeholder="保存并启用 WebService 后生成" />
        </el-form-item>
        <div class="ingestion-debug-layout">
          <section class="ingestion-debug-panel">
            <div class="ingestion-debug-panel__header">
              <div>
                <strong>HTTP Headers</strong>
                <p>HTTP 层请求头；服务需要 Token 时，生成 cURL 会自动补占位值。</p>
              </div>
              <el-button plain size="small" @click="appendSoapHeaderRow">添加参数</el-button>
            </div>
            <StudioTableShell min-width="640px">
              <el-table :data="soapHeaderRows" border size="small" table-layout="fixed">
                <el-table-column label="参数名" min-width="220">
                  <template #default="{ row }">
                    <span v-if="row.readonly">{{ row.name }}</span>
                    <el-input v-else :model-value="row.name" placeholder="Header 名称" @update:model-value="renameSoapHeader(row.key, String($event ?? ''))" />
                  </template>
                </el-table-column>
                <el-table-column label="值" min-width="260">
                  <template #default="{ row }">
                    <el-input :model-value="stringValue(row.value)" :placeholder="row.placeholder" @update:model-value="updateSoapHeaderValue(row.key, String($event ?? ''))" />
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="86" align="center" header-align="center">
                  <template #default="{ row }">
                    <el-button link type="danger" :disabled="row.readonly" @click="removeSoapHeader(row.key)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </StudioTableShell>
          </section>

          <XmlEditor
            v-model="soapEntityXml"
            title="业务实体 XML"
            description="只填写 SOAP Body 操作节点内部的业务实体，系统会自动包装 Envelope 和操作节点。"
            placeholder="&lt;data&gt;&#10;  &lt;items&gt;&#10;    &lt;item&gt;&lt;/item&gt;&#10;  &lt;/items&gt;&#10;&lt;/data&gt;"
            height="260px"
            allow-fragment
          />

          <XmlEditor
            v-model="soapEnvelope"
            title="SOAP 请求 Body（Envelope）"
            description="点击“生成 SOAP 包装”后展示完整请求 XML；生成 cURL 和发送调试会使用此结构。"
            placeholder="点击生成 SOAP 包装后展示完整 XML 请求 Body"
            height="300px"
            readonly
          />

          <XmlEditor
            :model-value="debugResult === '{}' ? '' : debugResult"
            title="SOAP 响应 Body（Envelope）"
            description="发送 SOAP 调试后展示响应 XML Envelope。"
            placeholder="发送 SOAP 调试后展示响应 XML"
            height="220px"
            readonly
          />
        </div>
      </template>

      <div class="service-debug-curl-panel">
        <div class="service-debug-curl-panel__header">
          <div>
            <strong>cURL 调用命令</strong>
            <p>{{ debugMode === "soap" ? "根据 HTTP Header 和生成后的 SOAP Envelope 生成调用命令。" : "根据 Header、Query 和 JSON Body 生成开放接入调用命令。" }}</p>
          </div>
          <el-button plain :disabled="!curlCommand" @click="copyCurlCommand">复制 cURL</el-button>
        </div>
        <el-input
          :model-value="curlCommand"
          type="textarea"
          :rows="8"
          readonly
          :placeholder="debugMode === 'soap' ? '点击生成 cURL 后展示 SOAP 调用命令' : '点击“生成 cURL(bash)”或“生成 cURL(cmd)”后展示命令'"
        />
      </div>
    </SectionCard>

    <SectionCard v-if="activeStep === 2 && debugSourceResults.length" title="来源写入结果" description="按每个来源展示本次接入写入情况。">
      <StudioTableShell min-width="900px">
        <el-table :data="debugSourceResults" border>
          <el-table-column label="来源" min-width="160">
            <template #default="{ row }">{{ row.sourceName || row.sourceCode }}</template>
          </el-table-column>
          <el-table-column prop="targetDatasourceName" label="目标数据源" min-width="160" />
          <el-table-column prop="targetModelName" label="目标模型" min-width="160" />
          <el-table-column prop="receivedCount" label="接收数" width="100" align="right" />
          <el-table-column prop="successCount" label="成功数" width="100" align="right" />
          <el-table-column prop="failedCount" label="失败数" width="100" align="right" />
          <el-table-column prop="status" label="状态" width="130" />
          <el-table-column prop="message" label="信息" min-width="220" show-overflow-tooltip />
        </el-table>
      </StudioTableShell>
    </SectionCard>

    <div class="wizard-footer">
      <el-button :disabled="activeStep === 0" @click="previousStep">上一步</el-button>
      <el-button v-if="activeStep < wizardSteps.length - 1" type="primary" @click="nextStep">下一步</el-button>
      <el-button v-else type="primary" :loading="saving" @click="saveService">保存服务</el-button>
    </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import type {
  DataIngestionFieldMapping,
  DataIngestionPayloadMode,
  DataIngestionRequestFormat,
  DataIngestionServiceSaveRequest,
  DataIngestionServiceView,
  DataIngestionSourceBinding,
  DataIngestionSourcePosition,
  DataModelDefinition,
  DataModelDatasourceOptionView,
  DataSourceOptionView,
  DatasourceTypeCapabilityView,
  EntityId,
  FieldValueType,
  MetadataFieldDefinition,
  PluginRuntimeOptionSchemaView,
  WebServiceConfig,
  WebServicePreviewView,
} from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import { SectionCard, StudioTableShell } from "@studio/ui";
import { resolveDataServiceOpenUrl, studioApi } from "@/api/studio";
import JsonEditor from "@/components/JsonEditor.vue";
import XmlEditor from "@/components/XmlEditor.vue";
import HttpRequestOptionsEditor from "@/components/HttpRequestOptionsEditor.vue";
import {
  buildSoapEnvelope,
  formatXmlText,
  firstArrayItemOrSelf,
  parseJsonObjectText,
  parseJsonValueText,
  parseSoapEnvelope,
  prettyJsonValue,
  assignPath as assignDebugPath,
  readPath as readDebugPath,
  stringifyDebugValue,
  type DebugObject,
  type SoapFieldSpec,
} from "@/components/open-service/openServiceDebugSupport";
import {
  buildBashCurl,
  buildBashRawBodyCurl,
  buildCmdCurl,
  buildCmdRawBodyCurl,
  copyTextFallback,
} from "@/components/data-service/dataServiceEditorSupport";
import {
  fileWriterDatasourceTypes,
  fileWriterDynamicFunctionFields,
  hasConfiguredArrayValue,
  httpWriterDynamicFunctionFields,
  mergeRuntimeDefaults,
  resolveFieldsByModel,
  resolvePrimaryKeyFieldsByModel,
  type RuntimeOptionRole,
} from "@/components/collection-task/collectionTaskEditorSupport";
import { resolveErrorMessage } from "@/composables/useAsyncAction";

type DebugMode = "http" | "soap";
type DebugControlType = "text" | "number" | "boolean" | "textarea";
type DebugFieldValue = string | number | boolean | null | undefined;
type TargetConfigMode = "overview" | "params" | "mapping";

interface OpenServiceDebugField {
  key: string;
  label: string;
  meta: string;
  required?: boolean;
  controlType?: DebugControlType;
  value?: DebugFieldValue;
}

interface OpenServiceDebugGroup {
  key: string;
  title: string;
  description: string;
  rows: OpenServiceDebugField[];
}

interface OpenServiceRawSection {
  key: string;
  title: string;
  description: string;
  value: string;
  placeholder?: string;
  rows?: number;
  parseError?: string;
}

interface SourcePositionOption {
  label: string;
  value: DataIngestionSourcePosition;
}

interface WizardStep {
  title: string;
  description: string;
}

interface DatasourceTypeOption {
  label: string;
  value: string;
}

interface DebugFieldItem {
  key: string;
  label: string;
  targetField: string;
  required: boolean;
  valueType?: FieldValueType;
  mapping: DataIngestionFieldMapping;
}

interface DebugMappingEntry {
  binding: DataIngestionSourceBinding;
  bindingIndex: number;
  mapping: DataIngestionFieldMapping;
  mappingIndex: number;
}

interface DebugFieldGroup {
  position: DataIngestionSourcePosition;
  title: string;
  description: string;
  rows: DebugFieldItem[];
}

interface DebugKeyValueRow {
  key: string;
  name: string;
  value?: unknown;
  readonly?: boolean;
  required?: boolean;
  placeholder?: string;
}

const route = useRoute();
const router = useRouter();
const valueTypes: FieldValueType[] = ["STRING", "BOOLEAN", "INTEGER", "LONG", "DECIMAL", "ARRAY", "OBJECT", "JSON"];
const sourcePositionOptions: SourcePositionOption[] = [
  { label: "Body 路径", value: "BODY" },
  { label: "Form Body", value: "FORM" },
  { label: "Query", value: "QUERY" },
  { label: "Header", value: "HEADER" },
];
const wizardSteps: WizardStep[] = [
  { title: "基础信息", description: "服务身份和访问控制" },
  { title: "目标配置", description: "目标参数和字段映射" },
  { title: "发布调试", description: "样例请求和调试结果" },
];
const sourcePositionOrder: DataIngestionSourcePosition[] = ["BODY", "FORM", "QUERY", "HEADER"];
const INGESTION_TOKEN_HEADER = "X-Data-Ingestion-Token";
const serviceId = computed(() => route.params.serviceId as EntityId | undefined);
const activeStep = ref(0);
const datasources = ref<DataSourceOptionView[]>([]);
const datasourceTypes = ref<DatasourceTypeCapabilityView[]>([]);
const MODEL_OPTION_PAGE_SIZE = 100;
const models = ref<DataModelDatasourceOptionView[]>([]);
const modelDetailCache = ref<Record<string, DataModelDefinition>>({});
const modelDetailLoading = ref(false);
const runtimeSchemaCache = ref<Record<string, PluginRuntimeOptionSchemaView>>({});
const runtimeSchemaLoading = ref<Record<string, boolean>>({});
const saving = ref(false);
const publishing = ref(false);
const resolving = ref(false);
const debugging = ref(false);
const webservicePreviewLoading = ref(false);
const webserviceDebugging = ref(false);
const detailLoadError = ref("");
const writerOptionsText = ref("{}");
const endpointPath = ref("");
const activeSourceIndex = ref(0);
const targetConfigMode = ref<TargetConfigMode>("overview");
const debugMode = ref<DebugMode>("http");
const debugHeaders = ref("{}");
const debugQuery = ref("{}");
const debugForm = ref("{}");
const debugBody = ref("{\n  \"id\": 1,\n  \"name\": \"demo\"\n}");
const debugResult = ref("{}");
const lastInvokeResult = ref<unknown>(null);
const debugSourceResults = computed<Array<Record<string, unknown>>>(() => {
  const payload = lastInvokeResult.value;
  const sourceResults = isPlainDebugObject(payload) && Array.isArray(payload.sourceResults)
    ? payload.sourceResults
    : isPlainDebugObject(payload) && isPlainDebugObject(payload.result) && Array.isArray(payload.result.sourceResults)
      ? payload.result.sourceResults
      : [];
  return sourceResults as Array<Record<string, unknown>>;
});
const curlCommand = ref("");
const debugValues = reactive<Record<string, DebugFieldValue>>({});
const restDebugPayload = reactive<{
  headers: DebugObject;
  query: DebugObject;
  form: DebugObject;
  body: unknown;
}>({
  headers: {},
  query: {},
  form: {},
  body: {},
});
const debugRawErrors = reactive<Record<"headers" | "query" | "form" | "body", string>>({
  headers: "",
  query: "",
  form: "",
  body: "",
});
const targetDatasourceType = ref("");
const webserviceCollapseNames = ref<string[]>([]);
const webservicePreview = ref<WebServicePreviewView | null>(null);
const soapEntityXml = ref("");
const soapEnvelope = ref("");
const soapDebugHeaders = ref("{}");
const soapEnvelopeError = ref("");
const soapHeadersError = ref("");
const soapHttpHeaders = reactive<DebugObject>({});
const soapFieldValues = reactive<Record<string, DebugFieldValue>>({});
const SOAP_TOKEN_FIELD_KEY = "__soap_header_token";

const form = reactive<DataIngestionServiceSaveRequest & {
  webserviceEnabled: boolean;
  webserviceConfig: WebServiceConfig;
}>({
  serviceCode: "",
  serviceName: "",
  requestFormat: "JSON",
  payloadMode: "OBJECT",
  dataNodePath: "",
  targetType: "DATABASE",
  datasourceId: undefined,
  modelId: undefined,
  maxBatchSize: 1000,
  tokenRequired: true,
  defaultSubscriptionName: "",
  webserviceEnabled: false,
  webserviceConfig: defaultWebServiceConfig(),
  writerOptions: {},
  fieldMappings: [],
  sourceBindings: [],
});

const activeSourceBinding = computed<DataIngestionSourceBinding>(() => {
  ensureSourceBindings();
  return form.sourceBindings?.[activeSourceIndex.value] as DataIngestionSourceBinding;
});

const activeTargetFullPath = computed(() => fullSourcePathForBinding(activeSourceBinding.value));

const webserviceEndpointPath = computed(() => {
  if (webservicePreview.value?.endpointPath) {
    return webservicePreview.value.endpointPath;
  }
  if (!form.serviceCode || !endpointPath.value) {
    return "";
  }
  const serviceKey = endpointPath.value.split("/").filter(Boolean).pop();
  if (!serviceKey) {
    return "";
  }
  return `/openapi/ws/data-ingestion-services/${form.serviceCode}/${serviceKey}`;
});

const webserviceWsdlUrl = computed(() => {
  const path = webservicePreview.value?.wsdlPath
    || (webserviceEndpointPath.value ? `${webserviceEndpointPath.value}?wsdl` : "");
  return resolveDataServiceOpenUrl(path);
});
const webserviceInvokeUrl = computed(() => {
  if (!webserviceEndpointPath.value) {
    return "";
  }
  return resolveDataServiceOpenUrl(webserviceEndpointPath.value);
});
const endpointUrl = computed(() => resolveEndpoint());

const tokenHeaderRow = computed<DebugKeyValueRow[]>(() => form.tokenRequired
  ? [{
      key: INGESTION_TOKEN_HEADER,
      name: INGESTION_TOKEN_HEADER,
      value: "",
      readonly: true,
      required: true,
      placeholder: "<订阅Token>",
    }]
  : []);
const httpHeaderRows = computed(() => debugObjectRows(restDebugPayload.headers, tokenHeaderRow.value, true));
const httpQueryRows = computed(() => debugObjectRows(restDebugPayload.query));
const soapHeaderRows = computed(() => debugObjectRows(soapHttpHeaders, tokenHeaderRow.value, true));
const debugCurlDisabled = computed(() => {
  if (!form.id) {
    return true;
  }
  return debugMode.value === "soap"
    ? !form.webserviceEnabled || !webserviceInvokeUrl.value
    : !endpointUrl.value;
});

const targetModel = computed(() =>
  modelDetailCache.value[String(form.modelId ?? "")],
);
const datasourceTypeOptions = computed<DatasourceTypeOption[]>(() => {
  const configuredTypes = new Set(datasources.value.map((item) => item.typeCode).filter(Boolean));
  const options = datasourceTypes.value
    .filter((item) => configuredTypes.has(item.typeCode) && isDatasourceTypeAllowedForTarget(item.typeCode))
    .map((item) => ({
      value: item.typeCode,
      label: item.typeName ? `${item.typeName} / ${item.typeCode}` : item.typeCode,
    }));
  if (options.length) {
    return options;
  }
  return Array.from(configuredTypes)
    .filter((typeCode) => isDatasourceTypeAllowedForTarget(typeCode))
    .sort()
    .map((typeCode) => ({ value: typeCode, label: typeCode }));
});
const filteredDatasources = computed(() => {
  if (!targetDatasourceType.value) {
    return [];
  }
  if (!isDatasourceTypeAllowedForTarget(targetDatasourceType.value)) {
    return [];
  }
  return datasources.value.filter((item) => item.typeCode === targetDatasourceType.value);
});
const targetFieldOptions = computed(() => resolveFieldsByModel(targetModel.value));
const targetPrimaryKeyFields = computed(() => resolvePrimaryKeyFieldsByModel(targetModel.value));
const writerAdvancedFields = computed<MetadataFieldDefinition[]>(() =>
  (runtimeSchemaFor("writer", form.datasourceId)?.fields ?? []).map(enhanceWriterAdvancedField),
);
const writerRuntimeLoading = computed(() => {
  const datasourceType = resolveDatasourceTypeCode(form.datasourceId);
  return Boolean(datasourceType && runtimeSchemaLoading.value[runtimeSchemaKey("writer", datasourceType)]);
});

const debugFieldGroups = computed<DebugFieldGroup[]>(() => {
  const groups = new Map<DataIngestionSourcePosition, DebugFieldItem[]>();
  debugMappingEntries().forEach(({ binding, bindingIndex, mapping, mappingIndex }) => {
    if (!mapping.targetField) {
      return;
    }
    const position = normalizeSourcePosition(mapping.sourcePosition);
    const rows = groups.get(position) ?? [];
    const targetName = binding.sourceName || binding.sourceCode || `目标 ${bindingIndex + 1}`;
    rows.push({
      key: debugFieldKey(mapping, mappingIndex, bindingIndex, binding.sourceCode),
      label: `${targetName} · ${mapping.sourceField?.trim() || mapping.targetField}`,
      targetField: mapping.targetField,
      required: Boolean(mapping.required),
      valueType: mapping.valueType ?? "STRING",
      mapping,
    });
    groups.set(position, rows);
  });
  return sourcePositionOrder
    .filter((position) => groups.has(position))
    .map((position) => ({
      position,
      title: sourcePositionTitle(position),
      description: sourcePositionDescription(position),
      rows: groups.get(position) ?? [],
    }));
});

const debugPanelGroups = computed<OpenServiceDebugGroup[]>(() =>
  debugFieldGroups.value.map((group) => ({
    key: group.position,
    title: group.title,
    description: group.description,
    rows: group.rows.map((row) => ({
      key: row.key,
      label: row.label,
      meta: `写入 ${row.targetField}`,
      required: row.required,
      controlType: debugControlType(row.valueType),
      value: debugValues[row.key],
    })),
  })),
);

const soapFieldGroups = computed<OpenServiceDebugGroup[]>(() => {
  const headerRows = debugFieldGroups.value
    .filter((group) => group.position === "HEADER")
    .flatMap((group) => group.rows.map((row) => ({
      key: row.key,
      label: row.label,
      meta: `SOAP Header / 写入 ${row.targetField}`,
      required: row.required,
      controlType: debugControlType(row.valueType),
      value: soapFieldValues[row.key],
    })));
  const bodyRows = debugFieldGroups.value
    .filter((group) => group.position !== "HEADER")
    .flatMap((group) => group.rows.map((row) => ({
      key: row.key,
      label: row.label,
      meta: `${sourcePositionTitle(group.position)} / 写入 ${row.targetField}`,
      required: row.required,
      controlType: debugControlType(row.valueType),
      value: soapFieldValues[row.key],
    })));
  const groups: OpenServiceDebugGroup[] = [
    {
      key: "soap-header",
      title: "SOAP Header",
      description: "会写入 Envelope Header；Token 可使用 token / dataIngestionToken。",
      rows: [
        {
          key: SOAP_TOKEN_FIELD_KEY,
          label: "token",
          meta: "SOAP Header token",
          required: Boolean(form.tokenRequired),
          controlType: "text",
          value: soapFieldValues[SOAP_TOKEN_FIELD_KEY],
        },
        ...headerRows,
      ],
    },
  ];
  if (bodyRows.length) {
    groups.push({
      key: "soap-body",
      title: "SOAP Body 参数",
      description: "会写入当前 WebService 操作节点；按各目标完整来源路径自动包装节点。",
      rows: bodyRows,
    });
  }
  return groups;
});

const debugRawSections = computed<OpenServiceRawSection[]>(() => [
  {
    key: "headers",
    title: "Header 参数",
    description: "HTTP 请求头 JSON 对象。",
    value: debugHeaders.value,
    placeholder: "{\n  \"X-Trace-Id\": \"debug-001\"\n}",
    rows: 5,
    parseError: debugRawErrors.headers,
  },
  {
    key: "query",
    title: "Query 参数",
    description: "拼接在接入地址后的查询参数 JSON 对象。",
    value: debugQuery.value,
    placeholder: "{\n  \"source\": \"demo\"\n}",
    rows: 5,
    parseError: debugRawErrors.query,
  },
  {
    key: "form",
    title: "Form Body",
    description: "以 x-www-form-urlencoded 提交的表单字段 JSON 对象。",
    value: debugForm.value,
    placeholder: "{\n  \"name\": \"demo\"\n}",
    rows: 5,
    parseError: debugRawErrors.form,
  },
  {
    key: "body",
    title: "Body 参数",
    description: "JSON Body 原文；可填写对象、数组或各目标完整来源路径对应的数据结构。",
    value: debugBody.value,
    placeholder: "{\n  \"id\": 1,\n  \"name\": \"demo\"\n}",
    rows: 8,
    parseError: debugRawErrors.body,
  },
]);

const usesBothJsonBodyAndForm = computed(() => {
  const positions = new Set(
    allMappingsFromBindings(form.sourceBindings ?? []).map((mapping) => normalizeSourcePosition(mapping.sourcePosition)),
  );
  return positions.has("BODY") && positions.has("FORM");
});

function defaultWebServiceConfig(config?: WebServiceConfig, enabled = false): WebServiceConfig {
  return {
    enabled,
    soapVersion: config?.soapVersion || "SOAP_11",
    namespaceUri: config?.namespaceUri || "",
    operationName: config?.operationName || "",
    soapAction: config?.soapAction || "",
    requestRootName: config?.requestRootName || "",
    responseRootName: config?.responseRootName || "",
  };
}

function ensureSourceBindings() {
  if (!form.sourceBindings?.length) {
    form.sourceBindings = [createSourceBindingFromForm(0)];
    activeSourceIndex.value = 0;
  }
  if (activeSourceIndex.value >= form.sourceBindings.length) {
    activeSourceIndex.value = Math.max(0, form.sourceBindings.length - 1);
  }
}

function createSourceBindingFromForm(index: number, seed?: DataIngestionSourceBinding): DataIngestionSourceBinding {
  return {
    sourceCode: seed?.sourceCode || uniqueSourceCode(index),
    sourceName: seed?.sourceName || seed?.sourceCode || `来源 ${index + 1}`,
    sourcePosition: seed?.sourcePosition ?? "BODY",
    sourcePath: seed?.sourcePath ?? "",
    payloadMode: seed?.payloadMode ?? form.payloadMode ?? "OBJECT",
    targetType: seed?.targetType ?? form.targetType ?? "DATABASE",
    datasourceId: seed?.datasourceId ?? form.datasourceId,
    datasourceName: seed?.datasourceName,
    datasourceTypeCode: seed?.datasourceTypeCode ?? resolveDatasourceTypeCode(seed?.datasourceId ?? form.datasourceId),
    modelId: seed?.modelId ?? form.modelId,
    modelName: seed?.modelName,
    modelPhysicalLocator: seed?.modelPhysicalLocator,
    maxBatchSize: normalizeTargetMaxBatchSize(seed?.maxBatchSize),
    writerOptions: cloneWriterOptions(seed?.writerOptions ?? form.writerOptions ?? {}),
    fieldMappings: cloneMappings(seed?.fieldMappings ?? form.fieldMappings ?? []),
    sortOrder: seed?.sortOrder ?? index,
    enabled: seed?.enabled !== false,
  };
}

function createSourceBindingFromDetail(detail: DataIngestionServiceView, index: number): DataIngestionSourceBinding {
  return createSourceBindingFromForm(index, {
    sourceCode: "source_1",
    sourceName: "默认来源",
    sourcePosition: "BODY",
    sourcePath: detail.dataNodePath ?? "",
    payloadMode: detail.payloadMode ?? "OBJECT",
    targetType: detail.targetType ?? "DATABASE",
    datasourceId: detail.datasourceId,
    datasourceName: detail.datasourceName,
    datasourceTypeCode: detail.datasourceTypeCode,
    modelId: detail.modelId,
    modelName: detail.modelName,
    modelPhysicalLocator: detail.modelPhysicalLocator,
    maxBatchSize: detail.maxBatchSize,
    writerOptions: detail.writerOptions ?? {},
    fieldMappings: detail.fieldMappings ?? [],
    sortOrder: index,
    enabled: true,
  });
}

function persistActiveSourceFromForm(
  writerOptions: Record<string, unknown> = form.writerOptions ?? {},
  mappings: DataIngestionFieldMapping[] = form.fieldMappings,
) {
  ensureSourceBindings();
  const binding = form.sourceBindings?.[activeSourceIndex.value];
  if (!binding) {
    return;
  }
  const datasource = datasources.value.find((item) => String(item.id) === String(form.datasourceId ?? ""));
  const model = modelDetailCache.value[String(form.modelId ?? "")];
  binding.sourceCode = binding.sourceCode?.trim() || uniqueSourceCode(activeSourceIndex.value);
  binding.sourceName = binding.sourceName?.trim() || binding.sourceCode;
  binding.sourcePosition = binding.sourcePosition ?? "BODY";
  binding.sourcePath = binding.sourcePath ?? "";
  binding.payloadMode = binding.payloadMode ?? "OBJECT";
  binding.targetType = form.targetType ?? "DATABASE";
  binding.datasourceId = form.datasourceId;
  binding.datasourceName = datasource?.name ?? binding.datasourceName;
  binding.datasourceTypeCode = datasource?.typeCode ?? binding.datasourceTypeCode ?? resolveDatasourceTypeCode(form.datasourceId);
  binding.modelId = form.modelId;
  binding.modelName = model?.name ?? binding.modelName;
  binding.modelPhysicalLocator = model?.physicalLocator ?? binding.modelPhysicalLocator;
  binding.maxBatchSize = normalizeTargetMaxBatchSize(binding.maxBatchSize);
  binding.writerOptions = cloneWriterOptions(writerOptions);
  binding.fieldMappings = cloneMappings(mappings);
  binding.sortOrder = activeSourceIndex.value;
  binding.enabled = binding.enabled !== false;
}

function applySourceBindingToForm(binding: DataIngestionSourceBinding) {
  form.payloadMode = binding.payloadMode ?? "OBJECT";
  form.targetType = binding.targetType ?? "DATABASE";
  form.datasourceId = binding.datasourceId;
  form.modelId = binding.modelId;
  form.writerOptions = cloneWriterOptions(binding.writerOptions ?? {});
  form.fieldMappings = cloneMappings(binding.fieldMappings ?? []);
  targetDatasourceType.value = binding.datasourceTypeCode ?? resolveDatasourceTypeCode(binding.datasourceId);
  syncWriterOptionsText();
  syncDebugValues(false);
  syncRestPayloadFromFields();
}

async function selectSourceBinding(index: number) {
  ensureSourceBindings();
  if (index === activeSourceIndex.value || !form.sourceBindings?.[index]) {
    return;
  }
  persistActiveSourceFromForm();
  activeSourceIndex.value = index;
  applySourceBindingToForm(form.sourceBindings[index]);
  await prepareActiveSourceTarget();
}

async function openTargetBinding(index: number) {
  await selectSourceBinding(index);
  targetConfigMode.value = "params";
}

function handleActiveSourcePathChange() {
  clearDebugCommand();
}

function handleActiveSourceMetaChange() {
  persistActiveSourceFromForm();
  syncRestPayloadFromFields();
}

async function addSourceBinding() {
  persistActiveSourceFromForm();
  const nextIndex = form.sourceBindings?.length ?? 0;
  form.sourceBindings = [
    ...(form.sourceBindings ?? []),
    createSourceBindingFromForm(nextIndex, {
      sourceCode: uniqueSourceCode(nextIndex),
      sourceName: `来源 ${nextIndex + 1}`,
      sourcePosition: "BODY",
      sourcePath: "",
      payloadMode: "OBJECT",
      targetType: "DATABASE",
      maxBatchSize: undefined,
      writerOptions: {},
      fieldMappings: [],
      sortOrder: nextIndex,
      enabled: true,
    }),
  ];
  await selectSourceBinding(nextIndex);
  targetConfigMode.value = "params";
}

async function copySourceBinding(index: number) {
  ensureSourceBindings();
  const source = form.sourceBindings?.[index];
  if (!source) {
    return;
  }
  persistActiveSourceFromForm();
  const nextIndex = form.sourceBindings?.length ?? 0;
  const copy = createSourceBindingFromForm(nextIndex, {
    ...source,
    sourceCode: uniqueSourceCode(nextIndex),
    sourceName: `${source.sourceName || source.sourceCode || "来源"}副本`,
    sortOrder: nextIndex,
  });
  form.sourceBindings = [...(form.sourceBindings ?? []), copy];
  await selectSourceBinding(nextIndex);
  targetConfigMode.value = "params";
}

async function removeSourceBinding(index: number) {
  ensureSourceBindings();
  if ((form.sourceBindings?.length ?? 0) <= 1) {
    ElMessage.warning("至少保留一个来源");
    return;
  }
  form.sourceBindings = (form.sourceBindings ?? [])
    .filter((_, itemIndex) => itemIndex !== index)
    .map((item, itemIndex) => ({ ...item, sortOrder: itemIndex }));
  activeSourceIndex.value = Math.min(activeSourceIndex.value, (form.sourceBindings?.length ?? 1) - 1);
  applySourceBindingToForm(activeSourceBinding.value);
  targetConfigMode.value = "overview";
  await prepareActiveSourceTarget();
}

async function prepareActiveSourceTarget() {
  if (!form.datasourceId) {
    models.value = [];
    return;
  }
  await Promise.all([
    loadModels(form.datasourceId),
    form.modelId ? loadModelDetail(form.modelId) : Promise.resolve(),
    ensureRuntimeSchemaForDatasource("writer", form.datasourceId),
  ]);
  ensureSelectedModelOption();
  applyRuntimeDefaultsForWriter();
}

function normalizedSourceBindingsForSave(
  mappings: DataIngestionFieldMapping[],
  writerOptions: Record<string, unknown>,
) {
  persistActiveSourceFromForm(writerOptions, mappings);
  ensureSourceBindings();
  return (form.sourceBindings ?? []).map((binding, index): DataIngestionSourceBinding => {
    const sourcePath = fullSourcePathForBinding(binding) || undefined;
    const normalizedMappingsForBinding = normalizedMappingsFrom(binding.fieldMappings ?? []);
    return {
      sourceCode: binding.sourceCode?.trim() || uniqueSourceCode(index),
      sourceName: binding.sourceName?.trim() || binding.sourceCode?.trim() || `来源 ${index + 1}`,
      sourcePosition: normalizeSourcePosition(binding.sourcePosition),
      sourcePath,
      payloadMode: binding.payloadMode ?? "OBJECT",
      targetType: binding.targetType ?? "DATABASE",
      datasourceId: binding.datasourceId,
      modelId: binding.modelId,
      maxBatchSize: normalizeTargetMaxBatchSize(binding.maxBatchSize),
      writerOptions: cloneWriterOptions(binding.writerOptions ?? {}),
      fieldMappings: normalizedMappingsForBinding,
      sortOrder: index,
      enabled: binding.enabled !== false,
    };
  });
}

function validateSourceBindingsForSave(bindings: DataIngestionSourceBinding[]) {
  const enabledBindings = bindings.filter((binding) => binding.enabled !== false);
  if (!enabledBindings.length) {
    ElMessage.warning("至少启用一个来源");
    return false;
  }
  const invalid = enabledBindings.find((binding) =>
    !binding.datasourceId || !binding.modelId || !(binding.fieldMappings ?? []).length,
  );
  if (invalid) {
    ElMessage.warning(`${invalid.sourceName || invalid.sourceCode || "来源"}未完成目标或字段映射配置`);
    return false;
  }
  return true;
}

function allMappingsFromBindings(bindings: DataIngestionSourceBinding[]) {
  return bindings.flatMap((binding) => binding.fieldMappings ?? []);
}

function fullSourcePathForBinding(binding?: DataIngestionSourceBinding) {
  return normalizeSourcePath(binding?.sourcePath);
}

function sourcePositionLabel(position?: DataIngestionSourcePosition) {
  const value = position ?? "BODY";
  return sourcePositionOptions.find((item) => item.value === value)?.label ?? value;
}

function payloadModeLabel(mode?: DataIngestionPayloadMode) {
  return mode === "ARRAY" ? "数组" : "对象";
}

function sourceBindingDatasourceTypeLabel(binding: DataIngestionSourceBinding) {
  const typeCode = binding.datasourceTypeCode || resolveDatasourceTypeCode(binding.datasourceId);
  if (!typeCode) {
    return "未选择";
  }
  const type = datasourceTypes.value.find((item) => item.typeCode === typeCode);
  return type?.typeName ? `${type.typeName} / ${typeCode}` : typeCode;
}

function sourceBindingDatasourceLabel(binding: DataIngestionSourceBinding) {
  const datasource = datasources.value.find((item) => String(item.id) === String(binding.datasourceId ?? ""));
  return datasource?.name || binding.datasourceName || "未选择";
}

function sourceBindingModelLabel(binding: DataIngestionSourceBinding) {
  const model = modelDetailCache.value[String(binding.modelId ?? "")];
  const name = model?.name || binding.modelName;
  const locator = model?.physicalLocator || binding.modelPhysicalLocator;
  if (name && locator && name !== locator) {
    return `${name} / ${locator}`;
  }
  return name || locator || "未选择";
}

function normalizeTargetMaxBatchSize(value: unknown) {
  const numberValue = Number(value);
  if (!Number.isFinite(numberValue) || numberValue <= 0) {
    return undefined;
  }
  return Math.floor(numberValue);
}

function sourceMaxBatchSizeLabel(binding: DataIngestionSourceBinding) {
  const maxBatchSize = normalizeTargetMaxBatchSize(binding.maxBatchSize);
  return maxBatchSize ? `${maxBatchSize} 行` : "不限制";
}

function normalizeSourcePath(path?: string) {
  return path?.split(".").map((segment) => segment.trim()).filter(Boolean).join(".") ?? "";
}

function uniqueSourceCode(index: number) {
  const existing = new Set((form.sourceBindings ?? []).map((item) => item.sourceCode).filter(Boolean));
  let candidate = `source_${index + 1}`;
  let next = index + 1;
  while (existing.has(candidate)) {
    next += 1;
    candidate = `source_${next}`;
  }
  return candidate;
}

function cloneWriterOptions(value: Record<string, unknown>) {
  return JSON.parse(JSON.stringify(value ?? {})) as Record<string, unknown>;
}

function cloneMappings(mappings: DataIngestionFieldMapping[]) {
  return mappings.map((item) => ({
    ...item,
    sourcePosition: normalizeSourcePosition(item.sourcePosition),
    required: Boolean(item.required),
  }));
}

onMounted(async () => {
  const [datasourceData, datasourceTypeData] = await Promise.all([
    studioApi.datasources.options(),
    studioApi.catalog.datasourceTypes(),
  ]);
  datasources.value = datasourceData;
  datasourceTypes.value = datasourceTypeData;
  if (serviceId.value) {
    const loaded = await loadService(serviceId.value);
    if (!loaded) {
      return;
    }
  }
  if (route.query.debug) {
    activeStep.value = 2;
  }
});

watch(
  () => form.fieldMappings,
  () => {
    syncDebugValues(false);
  },
  { deep: true, immediate: true },
);

watch(
  () => form.webserviceEnabled,
  (enabled) => {
    if (enabled) {
      debugMode.value = "soap";
      return;
    }
    if (debugMode.value === "soap") {
      debugMode.value = "http";
    }
    soapEntityXml.value = "";
    soapEnvelope.value = "";
    soapEnvelopeError.value = "";
  },
);

watch(
  () => debugMode.value,
  () => {
    clearDebugCommand();
  },
);

watch(
  () => debugBody.value,
  () => {
    debugRawErrors.body = "";
    clearDebugCommand();
  },
);

watch(
  () => soapEntityXml.value,
  () => {
    soapEnvelope.value = "";
    soapEnvelopeError.value = "";
    clearDebugCommand();
  },
);

async function loadService(id: EntityId) {
  try {
    detailLoadError.value = "";
    const detail = await studioApi.dataIngestionServices.get(id);
    applyDetail(detail);
    if (detail.datasourceId) {
      await Promise.all([
        loadModels(detail.datasourceId),
        detail.modelId ? loadModelDetail(detail.modelId) : Promise.resolve(),
        ensureRuntimeSchemaForDatasource("writer", detail.datasourceId),
      ]);
      ensureSelectedModelOption();
      applyRuntimeDefaultsForWriter();
    }
    return true;
  } catch (error) {
    const message = resolveErrorMessage(error, "加载数据接入服务失败");
    detailLoadError.value = message;
    ElMessage.error(message);
    return false;
  }
}

async function retryLoadService() {
  if (!serviceId.value) {
    return;
  }
  await loadService(serviceId.value);
}

function applyDetail(detail: DataIngestionServiceView) {
  form.id = detail.id;
  form.serviceCode = detail.serviceCode;
  form.serviceName = detail.serviceName;
  form.maxBatchSize = detail.maxBatchSize ?? 1000;
  form.tokenRequired = detail.tokenRequired !== false;
  form.defaultSubscriptionName = detail.defaultSubscriptionName ?? "";
  form.webserviceEnabled = Boolean(detail.webserviceEnabled);
  form.requestFormat = form.webserviceEnabled ? "SOAP" : detail.requestFormat ?? "JSON";
  form.webserviceConfig = defaultWebServiceConfig(detail.webserviceConfig, form.webserviceEnabled);
  const detailBindings = (detail.sourceBindings?.length
    ? detail.sourceBindings
    : [createSourceBindingFromDetail(detail, 0)]);
  form.dataNodePath = detail.dataNodePath ?? detailBindings[0]?.sourcePath ?? "";
  form.sourceBindings = detailBindings
    .map((binding, index) => createSourceBindingFromForm(index, {
      ...binding,
      sourcePath: normalizeSourcePath(binding.sourcePath ?? ""),
      sortOrder: binding.sortOrder ?? index,
      enabled: binding.enabled !== false,
    }));
  activeSourceIndex.value = 0;
  targetConfigMode.value = "overview";
  applySourceBindingToForm(form.sourceBindings[0]);
  endpointPath.value = detail.endpointPath ?? "";
  webservicePreview.value = null;
  if (!form.webserviceEnabled) {
    soapEntityXml.value = "";
    soapEnvelope.value = "";
  }
}

function handleTargetTypeChange() {
  if (!targetDatasourceType.value || isDatasourceTypeAllowedForTarget(targetDatasourceType.value)) {
    persistActiveSourceFromForm();
    return;
  }
  handleDatasourceTypeChange("");
}

function handleDatasourceTypeChange(value?: string) {
  targetDatasourceType.value = value ?? "";
  form.datasourceId = undefined;
  form.modelId = undefined;
  form.fieldMappings = [];
  models.value = [];
  form.writerOptions = {};
  syncWriterOptionsText();
  persistActiveSourceFromForm();
}

async function handleDatasourceChange(value?: EntityId) {
  form.datasourceId = value;
  form.modelId = undefined;
  form.fieldMappings = [];
  form.writerOptions = {};
  syncWriterOptionsText();
  persistActiveSourceFromForm();
  const datasourceType = resolveDatasourceTypeCode(value);
  if (datasourceType) {
    targetDatasourceType.value = datasourceType;
  }
  if (!value) {
    models.value = [];
    return;
  }
  await Promise.all([
    loadModels(value),
    ensureRuntimeSchemaForDatasource("writer", value),
  ]);
  applyRuntimeDefaultsForWriter();
}

async function handleModelChange(value?: EntityId) {
  form.modelId = value;
  await loadModelDetail(value);
  applyRuntimeDefaultsForWriter();
  persistActiveSourceFromForm();
}

async function loadModels(datasourceId: EntityId, keyword = "") {
  const page = await studioApi.models.listDatasourceOptions(datasourceId, {
    keyword: keyword.trim() || undefined,
    pageNo: 1,
    pageSize: MODEL_OPTION_PAGE_SIZE,
  });
  models.value = page.items;
  ensureSelectedModelOption();
}

async function searchModels(keyword: string) {
  if (!form.datasourceId) {
    models.value = [];
    return;
  }
  await loadModels(form.datasourceId, keyword);
}

function handleModelDropdownVisible(visible: boolean) {
  if (visible && form.datasourceId) {
    void loadModels(form.datasourceId);
  }
}

async function loadModelDetail(modelId?: EntityId) {
  const key = String(modelId ?? "");
  if (!key || modelDetailCache.value[key]) {
    return;
  }
  modelDetailLoading.value = true;
  try {
    const detail = await studioApi.models.get(modelId as EntityId);
    modelDetailCache.value = {
      ...modelDetailCache.value,
      [key]: detail,
    };
    ensureSelectedModelOption();
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, "加载目标模型详情失败"));
  } finally {
    modelDetailLoading.value = false;
  }
}

function ensureSelectedModelOption() {
  const detail = modelDetailCache.value[String(form.modelId ?? "")];
  if (!detail || !form.datasourceId || String(detail.datasourceId) !== String(form.datasourceId)) {
    return;
  }
  if (models.value.some((item) => String(item.id) === String(detail.id))) {
    return;
  }
  models.value = [
    ...models.value,
    toModelListView(detail),
  ];
}

function toModelListView(model: DataModelDefinition): DataModelDatasourceOptionView {
  return {
    id: model.id,
    datasourceId: model.datasourceId,
    name: model.name,
    modelKind: model.modelKind,
    physicalLocator: model.physicalLocator,
  };
}

async function ensureRuntimeSchemaForDatasource(role: RuntimeOptionRole, datasourceId: unknown) {
  const datasourceType = resolveDatasourceTypeCode(datasourceId);
  if (!datasourceType) {
    return;
  }
  await ensureRuntimeSchemaForType(role, datasourceType);
}

async function ensureRuntimeSchemaForType(role: RuntimeOptionRole, datasourceType: string) {
  const key = runtimeSchemaKey(role, datasourceType);
  if (runtimeSchemaCache.value[key] || runtimeSchemaLoading.value[key]) {
    return;
  }
  runtimeSchemaLoading.value[key] = true;
  try {
    runtimeSchemaCache.value[key] = await studioApi.catalog.runtimeOptionSchema({ role, datasourceType });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Writer 参数加载失败");
  } finally {
    runtimeSchemaLoading.value[key] = false;
  }
}

function runtimeSchemaFor(role: RuntimeOptionRole, datasourceId: unknown) {
  const datasourceType = resolveDatasourceTypeCode(datasourceId);
  if (!datasourceType) {
    return undefined;
  }
  return runtimeSchemaCache.value[runtimeSchemaKey(role, datasourceType)];
}

function runtimeSchemaTitle(role: RuntimeOptionRole, datasourceId: unknown) {
  const datasourceType = resolveDatasourceTypeCode(datasourceId);
  if (!datasourceType) {
    return "";
  }
  const schema = runtimeSchemaFor(role, datasourceId);
  return schema?.pluginType ? `${schema.pluginType}${role}` : datasourceType;
}

function runtimeStatusType(role: RuntimeOptionRole, datasourceId: unknown) {
  const schema = runtimeSchemaFor(role, datasourceId);
  if (!schema?.runtimeSupported) {
    return "warning";
  }
  return schema.fields?.length ? "success" : "info";
}

function runtimeStatusLabel(role: RuntimeOptionRole, datasourceId: unknown) {
  const schema = runtimeSchemaFor(role, datasourceId);
  if (!schema?.runtimeSupported) {
    return "未支持";
  }
  return schema.fields?.length ? "已加载" : "无额外参数";
}

function runtimeSchemaKey(role: RuntimeOptionRole, datasourceType: string) {
  return `${role}:${datasourceType}`;
}

function resolveDatasourceTypeCode(datasourceId: unknown) {
  const datasource = datasources.value.find((item) => String(item.id) === String(datasourceId ?? ""));
  return datasource?.typeCode ?? "";
}

function isDatasourceTypeAllowedForTarget(typeCode: string) {
  const category = datasourceTypes.value.find((item) => item.typeCode === typeCode)?.sourceCategory ?? "";
  const fileDatasource = category.toUpperCase() === "FILE_SYSTEM" || fileWriterDatasourceTypes.has(typeCode);
  if (form.targetType === "FILE") {
    return fileDatasource;
  }
  return !fileDatasource;
}

function writerDynamicFunctionFields() {
  const datasourceType = resolveDatasourceTypeCode(form.datasourceId);
  if (fileWriterDatasourceTypes.has(datasourceType)) {
    return fileWriterDynamicFunctionFields;
  }
  if (datasourceType === "http") {
    return httpWriterDynamicFunctionFields;
  }
  return [];
}

function isHttpWriterTarget() {
  return resolveDatasourceTypeCode(form.datasourceId) === "http";
}

function updateWriterOptions(value: Record<string, unknown>) {
  form.writerOptions = value ?? {};
  syncWriterOptionsText();
  persistActiveSourceFromForm();
}

function applyRuntimeDefaultsForWriter() {
  if (!writerAdvancedFields.value.length) {
    return;
  }
  form.writerOptions = mergeTargetPrimaryKeyDefault(
    mergeRuntimeDefaults(form.writerOptions, writerAdvancedFields.value),
  );
  syncWriterOptionsText();
  persistActiveSourceFromForm();
}

function mergeTargetPrimaryKeyDefault(writerOptions: Record<string, unknown>) {
  if (hasConfiguredArrayValue(writerOptions.pkColumn) || !targetPrimaryKeyFields.value.length) {
    return writerOptions;
  }
  return {
    ...writerOptions,
    pkColumn: [...targetPrimaryKeyFields.value],
  };
}

function enhanceWriterAdvancedField(field: MetadataFieldDefinition): MetadataFieldDefinition {
  if (field.fieldKey !== "pkColumn") {
    return field;
  }
  return {
    ...field,
    valueType: "ARRAY",
    componentType: "SELECT",
    options: [...targetFieldOptions.value],
  };
}

function syncWriterOptionsText() {
  writerOptionsText.value = JSON.stringify(form.writerOptions ?? {}, null, 2);
}

async function resolveFields() {
  resolving.value = true;
  try {
    const resolved = await studioApi.dataIngestionServices.resolveFields({
      datasourceId: form.datasourceId,
      modelId: form.modelId,
    });
    form.fieldMappings = resolved.fieldMappings.map((item) => ({
      ...item,
      sourcePosition: normalizeSourcePosition(item.sourcePosition),
      required: Boolean(item.required),
    }));
    persistActiveSourceFromForm();
    ElMessage.success(`已解析 ${resolved.fields.length} 个目标字段`);
  } finally {
    resolving.value = false;
  }
}

function addMapping() {
  form.fieldMappings.push({
    sortOrder: form.fieldMappings.length,
    sourcePosition: "BODY",
    sourceField: "",
    targetField: "",
    valueType: "STRING",
    required: false,
    defaultValue: "",
  });
  persistActiveSourceFromForm();
}

function removeMapping(index: number) {
  form.fieldMappings.splice(index, 1);
  persistActiveSourceFromForm();
}

function nextStep() {
  if (!validateStep(activeStep.value)) {
    return;
  }
  activeStep.value = Math.min(wizardSteps.length - 1, activeStep.value + 1);
  if (activeStep.value === 2) {
    syncDebugValues(false);
    syncRestPayloadFromFields();
  }
}

function previousStep() {
  activeStep.value = Math.max(0, activeStep.value - 1);
}

function goStep(index: number) {
  if (index > activeStep.value) {
    for (let step = activeStep.value; step < index; step += 1) {
      if (!validateStep(step)) {
        return;
      }
    }
  }
  activeStep.value = index;
  if (activeStep.value === 2) {
    syncDebugValues(false);
    syncRestPayloadFromFields();
  }
}

function validateStep(index: number) {
  if (index === 0) {
    if (!form.serviceName.trim() || !form.serviceCode.trim()) {
      ElMessage.warning("请先填写服务名称和服务编码");
      return false;
    }
  }
  if (index === 1) {
    persistActiveSourceFromForm();
    if (!validateSourceBindingsForSave(form.sourceBindings ?? [])) {
      return false;
    }
  }
  return true;
}

async function saveService() {
  if (detailLoadError.value && serviceId.value) {
    ElMessage.error(detailLoadError.value);
    return;
  }
  saving.value = true;
  try {
    const fieldMappings = normalizedMappings();
    const writerOptions = resolveWriterOptionsForSave();
    const sourceBindings = normalizedSourceBindingsForSave(fieldMappings, writerOptions);
    if (!validateSourceBindingsForSave(sourceBindings)) {
      return;
    }
    const primaryBinding = sourceBindings[0] ?? createSourceBindingFromForm(0);
    const requestMappings = primaryBinding.fieldMappings ?? [];
    const saved = await studioApi.dataIngestionServices.save({
      ...form,
      requestFormat: deriveRequestFormat(allMappingsFromBindings(sourceBindings)),
      payloadMode: primaryBinding.payloadMode,
      dataNodePath: primaryBinding.sourcePath,
      targetType: primaryBinding.targetType,
      datasourceId: primaryBinding.datasourceId,
      modelId: primaryBinding.modelId,
      serviceCode: form.serviceCode.trim(),
      serviceName: form.serviceName.trim(),
      defaultSubscriptionName: form.defaultSubscriptionName?.trim() || undefined,
      webserviceEnabled: Boolean(form.webserviceEnabled),
      webserviceConfig: normalizeWebServiceConfigForSave(),
      writerOptions: primaryBinding.writerOptions ?? {},
      fieldMappings: requestMappings,
      sourceBindings,
    });
    applyDetail(saved);
    ElMessage.success("数据接入服务已保存");
    if (!serviceId.value) {
      await router.replace(`/data-ingestion-services/${saved.id}/edit`);
    }
  } finally {
    saving.value = false;
  }
}

function normalizeWebServiceConfigForSave(): WebServiceConfig {
  const config = defaultWebServiceConfig(form.webserviceConfig, form.webserviceEnabled);
  return {
    enabled: Boolean(form.webserviceEnabled),
    soapVersion: config.soapVersion || "SOAP_11",
    namespaceUri: config.namespaceUri?.trim() || undefined,
    operationName: config.operationName?.trim() || undefined,
    soapAction: config.soapAction?.trim() || undefined,
    requestRootName: config.requestRootName?.trim() || undefined,
    responseRootName: config.responseRootName?.trim() || undefined,
  };
}

function handleWebServiceToggle() {
  form.webserviceConfig = defaultWebServiceConfig(form.webserviceConfig, form.webserviceEnabled);
  form.requestFormat = form.webserviceEnabled ? "SOAP" : deriveRequestFormat(normalizedMappings());
  webservicePreview.value = null;
}

function resolveWriterOptionsForSave() {
  if (writerAdvancedFields.value.length) {
    return normalizeWriterOptions(form.writerOptions ?? {});
  }
  return normalizeWriterOptions(parseJsonObject(writerOptionsText.value, "Writer 运行参数"));
}

function normalizeWriterOptions(writerOptions: Record<string, unknown>) {
  const next = { ...writerOptions };
  if (Array.isArray(next.pkColumn)) {
    const pkColumn = next.pkColumn.map((item) => String(item).trim()).filter(Boolean);
    if (pkColumn.length) {
      next.pkColumn = pkColumn;
    } else {
      delete next.pkColumn;
    }
  } else if (next.pkColumn !== undefined && next.pkColumn !== null) {
    delete next.pkColumn;
  }
  return next;
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
  publishing.value = true;
  try {
    const published = await studioApi.dataIngestionServices.publish(form.id);
    applyDetail(published);
    ElMessage.success("数据接入服务已发布");
  } finally {
    publishing.value = false;
  }
}

function debugObjectRows(
  source: DebugObject,
  fixedRows: DebugKeyValueRow[] = [],
  caseInsensitive = false,
) {
  const rows: DebugKeyValueRow[] = fixedRows.map((row) => {
    const actualName = findDebugObjectKey(source, row.name, caseInsensitive) || row.name;
    return {
      ...row,
      key: row.name,
      value: source[actualName],
    };
  });
  const fixedNames = new Set(fixedRows.map((row) => normalizeDebugObjectKey(row.name, caseInsensitive)));
  Object.entries(source).forEach(([name, value]) => {
    if (fixedNames.has(normalizeDebugObjectKey(name, caseInsensitive))) {
      return;
    }
    rows.push({
      key: name,
      name,
      value,
    });
  });
  return rows;
}

function appendHttpHeaderRow() {
  appendDebugObjectRow(restDebugPayload.headers, "X-Header", true);
}

function renameHttpHeader(key: string, nextName: string) {
  renameDebugObjectRow(restDebugPayload.headers, key, nextName, true);
}

function updateHttpHeaderValue(key: string, value: string) {
  updateDebugObjectValue(restDebugPayload.headers, key, value, true);
}

function removeHttpHeader(key: string) {
  removeDebugObjectRow(restDebugPayload.headers, key, true);
}

function appendHttpQueryRow() {
  appendDebugObjectRow(restDebugPayload.query, "param");
}

function renameHttpQuery(key: string, nextName: string) {
  renameDebugObjectRow(restDebugPayload.query, key, nextName);
}

function updateHttpQueryValue(key: string, value: string) {
  updateDebugObjectValue(restDebugPayload.query, key, value);
}

function removeHttpQuery(key: string) {
  removeDebugObjectRow(restDebugPayload.query, key);
}

function appendSoapHeaderRow() {
  appendDebugObjectRow(soapHttpHeaders, "X-Header", true);
}

function renameSoapHeader(key: string, nextName: string) {
  renameDebugObjectRow(soapHttpHeaders, key, nextName, true);
}

function updateSoapHeaderValue(key: string, value: string) {
  updateDebugObjectValue(soapHttpHeaders, key, value, true);
}

function removeSoapHeader(key: string) {
  removeDebugObjectRow(soapHttpHeaders, key, true);
}

function appendDebugObjectRow(source: DebugObject, prefix: string, caseInsensitive = false) {
  const nextName = nextDebugObjectName(source, prefix, caseInsensitive);
  source[nextName] = "";
  clearDebugCommand();
}

function renameDebugObjectRow(source: DebugObject, oldName: string, nextName: string, caseInsensitive = false) {
  const trimmedName = nextName.trim();
  if (!trimmedName) {
    return;
  }
  const actualOldName = findDebugObjectKey(source, oldName, caseInsensitive) || oldName;
  if (normalizeDebugObjectKey(actualOldName, caseInsensitive) === normalizeDebugObjectKey(trimmedName, caseInsensitive)) {
    return;
  }
  if (findDebugObjectKey(source, trimmedName, caseInsensitive)) {
    ElMessage.warning("参数名已存在");
    return;
  }
  const nextSource: DebugObject = {};
  Object.entries(source).forEach(([name, value]) => {
    nextSource[name === actualOldName ? trimmedName : name] = value;
  });
  replaceDebugObject(source, nextSource);
  clearDebugCommand();
}

function updateDebugObjectValue(source: DebugObject, name: string, value: unknown, caseInsensitive = false) {
  const actualName = findDebugObjectKey(source, name, caseInsensitive) || name;
  source[actualName] = value;
  clearDebugCommand();
}

function removeDebugObjectRow(source: DebugObject, name: string, caseInsensitive = false) {
  const actualName = findDebugObjectKey(source, name, caseInsensitive) || name;
  delete source[actualName];
  clearDebugCommand();
}

function nextDebugObjectName(source: DebugObject, prefix: string, caseInsensitive = false) {
  let index = Object.keys(source).length + 1;
  let candidate = `${prefix}-${index}`;
  while (findDebugObjectKey(source, candidate, caseInsensitive)) {
    index += 1;
    candidate = `${prefix}-${index}`;
  }
  return candidate;
}

function findDebugObjectKey(source: Record<string, unknown>, name: string, caseInsensitive = false) {
  const normalizedName = normalizeDebugObjectKey(name, caseInsensitive);
  return Object.keys(source).find((key) => normalizeDebugObjectKey(key, caseInsensitive) === normalizedName);
}

function normalizeDebugObjectKey(name: string, caseInsensitive = false) {
  const value = String(name || "").trim();
  return caseInsensitive ? value.toLowerCase() : value;
}

function compactDebugObject(source: DebugObject, caseInsensitive = false) {
  const result: Record<string, unknown> = {};
  Object.entries(source).forEach(([name, value]) => {
    const trimmedName = name.trim();
    if (!trimmedName || isBlankHeaderValue(value)) {
      return;
    }
    const existingKey = findDebugObjectKey(result, trimmedName, caseInsensitive);
    result[existingKey || trimmedName] = value;
  });
  return result;
}

function currentHttpHeaders() {
  return compactDebugObject(restDebugPayload.headers, true);
}

function currentHttpQuery() {
  return compactDebugObject(restDebugPayload.query);
}

function parseCurrentHttpBody() {
  const parsed = parseJsonValueText(debugBody.value, "Body");
  if (!parsed.ok) {
    debugRawErrors.body = parsed.error;
    throw new Error(parsed.error);
  }
  debugRawErrors.body = "";
  return parsed.value;
}

function stringValue(value: unknown) {
  return value == null ? "" : String(value);
}

function isPlainDebugObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === "object" && !Array.isArray(value));
}

function clearDebugCommand() {
  curlCommand.value = "";
}

function generateSoapEnvelopeFromEntity() {
  try {
    soapEnvelope.value = buildCurrentSoapEnvelope();
    clearDebugCommand();
    ElMessage.success("SOAP 包装已生成");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "SOAP 包装生成失败");
  }
}

function buildCurrentSoapEnvelope() {
  const innerXml = normalizedSoapEntityXml();
  const envelope = buildSoapEnvelope({
    soapVersion: form.webserviceConfig.soapVersion || "SOAP_11",
    namespaceUri: resolveSoapNamespaceUri(),
    requestRootName: resolveSoapRequestRootName(),
    includeToken: false,
    bodyInnerXml: indentSoapInnerXml(innerXml),
  });
  const formatted = formatXmlText(envelope, "SOAP Envelope");
  if (!formatted.ok) {
    soapEnvelopeError.value = formatted.error;
    throw new Error(formatted.error);
  }
  soapEnvelopeError.value = "";
  return formatted.value;
}

function normalizedSoapEntityXml() {
  const text = soapEntityXml.value.trim();
  if (!text) {
    return "";
  }
  const parsed = formatXmlText(`<__fragment>${text}</__fragment>`, "业务实体 XML");
  if (!parsed.ok) {
    soapEnvelopeError.value = parsed.error;
    throw new Error(parsed.error);
  }
  return text;
}

function indentSoapInnerXml(value: string) {
  return value
    .split(/\r?\n/u)
    .map((line) => line.trim() ? `      ${line}` : line)
    .join("\n");
}

function resolveSoapNamespaceUri() {
  return form.webserviceConfig.namespaceUri?.trim()
    || webservicePreview.value?.namespaceUri
    || "http://studio.jdragon.com/open-service";
}

function resolveSoapRequestRootName() {
  return form.webserviceConfig.requestRootName?.trim()
    || webservicePreview.value?.operationName
    || form.webserviceConfig.operationName?.trim()
    || form.serviceCode
    || "request";
}

async function debugService() {
  if (!form.id) {
    ElMessage.warning("请先保存服务");
    return;
  }
  debugging.value = true;
  try {
    const payload = buildCurrentRestDebugPayload();
    const result = await studioApi.dataIngestionServices.debug(form.id, payload);
    lastInvokeResult.value = result;
    debugResult.value = JSON.stringify(result, null, 2);
  } catch (error) {
    if (error instanceof Error) {
      ElMessage.error(error.message);
    }
  } finally {
    debugging.value = false;
  }
}

async function debugCurrentMode() {
  if (debugMode.value === "soap") {
    await debugWebService();
    return;
  }
  await debugService();
}

function buildCurrentRestDebugPayload() {
  const body = parseCurrentHttpBody();
  restDebugPayload.body = body;
  syncRestRawTextFromPayload();
  return {
    headers: currentHttpHeaders(),
    query: currentHttpQuery(),
    form: {},
    body,
  };
}

function updateDebugRawSection(key: string, value: string) {
  if (key === "headers") {
    debugHeaders.value = value;
    updateRawObjectSection("headers", value, "Headers");
  } else if (key === "query") {
    debugQuery.value = value;
    updateRawObjectSection("query", value, "Query");
  } else if (key === "form") {
    debugForm.value = value;
    updateRawObjectSection("form", value, "Form");
  } else if (key === "body") {
    debugBody.value = value;
    updateRawBodySection(value);
  }
  curlCommand.value = "";
}

async function generateCurlCommand(mode: "bash" | "cmd") {
  if (debugMode.value === "soap") {
    await generateSoapCurlCommand(mode);
    return;
  }
  if (!endpointUrl.value) {
    ElMessage.warning("请先保存服务生成接入地址");
    return;
  }
  try {
    const payload = buildCurrentRestDebugPayload();
    const headers = buildHttpCurlHeaders(payload.headers);
    curlCommand.value = mode === "bash"
      ? buildBashCurl(endpointUrl.value, "POST", headers, payload.query, payload.body)
      : buildCmdCurl(endpointUrl.value, "POST", headers, payload.query, payload.body);
    ElMessage.success(`已生成 cURL(${mode === "bash" ? "bash" : "cmd"})`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "生成 cURL 失败");
  }
}

async function generateSoapCurlCommand(mode: "bash" | "cmd") {
  if (!form.webserviceEnabled || !webserviceInvokeUrl.value) {
    ElMessage.warning("请先启用 WebService 并保存服务");
    return;
  }
  try {
    const envelope = buildCurrentSoapEnvelope();
    if (soapEnvelopeError.value) {
      throw new Error(soapEnvelopeError.value);
    }
    if (!envelope.trim()) {
      throw new Error("SOAP Envelope 不能为空");
    }
    soapEnvelope.value = envelope;
    const headers = buildSoapCurlHeaders();
    const contentType = resolveSoapContentType();
    curlCommand.value = mode === "bash"
      ? buildBashRawBodyCurl(webserviceInvokeUrl.value, "POST", headers, envelope, contentType)
      : buildCmdRawBodyCurl(webserviceInvokeUrl.value, "POST", headers, envelope, contentType);
    ElMessage.success(`已生成 SOAP cURL(${mode === "bash" ? "bash" : "cmd"})`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "生成 SOAP cURL 失败");
  }
}

function buildHttpCurlHeaders(source: Record<string, unknown>) {
  const headers: Record<string, unknown> = { ...source };
  setHeaderIfMissing(headers, "Accept", "application/json");
  if (form.tokenRequired) {
    setHeaderIfMissing(headers, INGESTION_TOKEN_HEADER, "<订阅Token>");
  }
  return compactCurlHeaders(headers);
}

function buildSoapCurlHeaders() {
  const headers: Record<string, unknown> = { ...currentSoapHttpHeaders() };
  setHeaderIfMissing(headers, "Accept", "text/xml");
  if (form.tokenRequired) {
    setHeaderIfMissing(headers, INGESTION_TOKEN_HEADER, "<订阅Token>");
  }
  const soapAction = resolveSoapAction();
  if (soapAction) {
    setHeaderIfMissing(headers, "SOAPAction", soapAction);
  }
  return compactCurlHeaders(headers);
}

function resolveSoapContentType() {
  return form.webserviceConfig.soapVersion === "SOAP_12"
    ? "application/soap+xml;charset=UTF-8"
    : "text/xml;charset=UTF-8";
}

function resolveSoapAction() {
  const configured = form.webserviceConfig.soapAction?.trim();
  if (configured) {
    return configured;
  }
  const namespaceUri = form.webserviceConfig.namespaceUri?.trim() || webservicePreview.value?.namespaceUri || "";
  const operationName = form.webserviceConfig.operationName?.trim() || webservicePreview.value?.operationName || form.serviceCode || "";
  return namespaceUri && operationName ? `${namespaceUri}/${operationName}` : "";
}

function setHeaderIfMissing(headers: Record<string, unknown>, name: string, value: string) {
  const existingKey = Object.keys(headers).find((key) => key.toLowerCase() === name.toLowerCase());
  if (!existingKey || isBlankHeaderValue(headers[existingKey])) {
    headers[existingKey || name] = value;
  }
}

function isBlankHeaderValue(value: unknown) {
  return value == null || (typeof value === "string" && !value.trim());
}

function compactCurlHeaders(headers: Record<string, unknown>) {
  const compacted: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(headers)) {
    if (!key || isBlankHeaderValue(value)) {
      continue;
    }
    compacted[key] = value;
  }
  return compacted;
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

async function previewWebService(fillSample = false) {
  if (!form.id) {
    ElMessage.warning("请先保存服务");
    return;
  }
  if (!form.webserviceEnabled) {
    ElMessage.warning("请先启用 WebService 并保存");
    return;
  }
  webservicePreviewLoading.value = true;
  try {
    webservicePreview.value = await studioApi.dataIngestionServices.previewWebService(form.id);
    void fillSample;
    ElMessage.success("WebService 预览已生成");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "WebService 预览失败");
  } finally {
    webservicePreviewLoading.value = false;
  }
}

async function debugWebService() {
  if (!form.id) {
    ElMessage.warning("请先保存服务");
    return;
  }
  if (!form.webserviceEnabled) {
    ElMessage.warning("请先启用 WebService 并保存");
    return;
  }
  let envelope = "";
  try {
    envelope = buildCurrentSoapEnvelope();
    soapEnvelope.value = envelope;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "SOAP Envelope 生成失败");
    return;
  }
  if (!envelope.trim()) {
    return;
  }
  webserviceDebugging.value = true;
  try {
    const headers = currentSoapHttpHeaders();
    const result = await studioApi.dataIngestionServices.debugWebService(form.id, {
      soapEnvelope: envelope,
      soapVersion: form.webserviceConfig.soapVersion || "SOAP_11",
      headers,
    });
    lastInvokeResult.value = result.result ?? null;
    debugResult.value = result.responseEnvelope || JSON.stringify(result, null, 2);
  } catch (error) {
    if (error instanceof Error) {
      ElMessage.error(error.message);
    }
  } finally {
    webserviceDebugging.value = false;
  }
}

function normalizedMappings() {
  return normalizedMappingsFrom(form.fieldMappings);
}

function normalizedMappingsFrom(mappings: DataIngestionFieldMapping[]) {
  return mappings
    .map((item, index): DataIngestionFieldMapping => ({
      ...item,
      sortOrder: index,
      sourcePosition: normalizeSourcePosition(item.sourcePosition),
      sourceField: item.sourceField?.trim() || item.targetField?.trim(),
      targetField: item.targetField?.trim() || "",
      valueType: item.valueType ?? "STRING",
      required: Boolean(item.required),
      defaultValue: item.defaultValue?.trim() || undefined,
    }))
    .filter((item) => item.targetField);
}

function deriveRequestFormat(mappings: DataIngestionFieldMapping[]): DataIngestionRequestFormat {
  if (form.webserviceEnabled) {
    return "SOAP";
  }
  if (mappings.some((mapping) => normalizeSourcePosition(mapping.sourcePosition) === "BODY")) {
    return "JSON";
  }
  if (mappings.some((mapping) => normalizeSourcePosition(mapping.sourcePosition) === "FORM")) {
    return "FORM";
  }
  return form.requestFormat ?? "JSON";
}

function normalizeSourcePosition(position?: DataIngestionSourcePosition): DataIngestionSourcePosition {
  return position ?? "BODY";
}

function debugMappingEntries(): DebugMappingEntry[] {
  ensureSourceBindings();
  const entries: DebugMappingEntry[] = [];
  (form.sourceBindings ?? []).forEach((binding, bindingIndex) => {
    if (binding.enabled === false) {
      return;
    }
    (binding.fieldMappings ?? []).forEach((mapping, mappingIndex) => {
      entries.push({
        binding,
        bindingIndex,
        mapping,
        mappingIndex,
      });
    });
  });
  return entries;
}

function syncDebugValues(force: boolean) {
  debugMappingEntries().forEach(({ binding, bindingIndex, mapping, mappingIndex }) => {
    const key = debugFieldKey(mapping, mappingIndex, bindingIndex, binding.sourceCode);
    if (force || !(key in debugValues)) {
      debugValues[key] = initialDebugValue(mapping);
    }
  });
}

function resetDebugValues() {
  syncDebugValues(true);
  syncRestPayloadFromFields();
  curlCommand.value = "";
}

function debugFieldKey(
  mapping: DataIngestionFieldMapping,
  index: number,
  bindingIndex = activeSourceIndex.value,
  sourceCode = activeSourceBinding.value?.sourceCode,
) {
  return `${bindingIndex}:${sourceCode || ""}:${index}:${normalizeSourcePosition(mapping.sourcePosition)}:${mapping.targetField || ""}:${mapping.sourceField || ""}`;
}

function initialDebugValue(mapping: DataIngestionFieldMapping): DebugFieldValue {
  if (mapping.defaultValue != null && String(mapping.defaultValue).trim()) {
    return defaultDebugValue(mapping.defaultValue, mapping.valueType);
  }
  if (mapping.valueType === "BOOLEAN") {
    return false;
  }
  if (isNumericType(mapping.valueType)) {
    return null;
  }
  if (mapping.valueType === "ARRAY") {
    return "[]";
  }
  if (mapping.valueType === "OBJECT" || mapping.valueType === "JSON") {
    return "{}";
  }
  return "";
}

function defaultDebugValue(value: string, valueType?: FieldValueType): DebugFieldValue {
  if (valueType === "BOOLEAN") {
    return value === "true";
  }
  if (isNumericType(valueType)) {
    const numberValue = Number(value);
    return Number.isFinite(numberValue) ? numberValue : null;
  }
  return value;
}

function buildDebugPayloadFromForm() {
  persistActiveSourceFromForm();
  const headers: Record<string, unknown> = {};
  const query: Record<string, unknown> = {};
  const formValues: Record<string, unknown> = {};
  const bodyRows = new Map<number, Record<string, unknown>>();

  debugMappingEntries().forEach(({ binding, bindingIndex, mapping: rawMapping, mappingIndex }) => {
    if (!rawMapping.targetField?.trim()) {
      return;
    }
    const mapping: DataIngestionFieldMapping = {
      ...rawMapping,
      sourcePosition: normalizeSourcePosition(rawMapping.sourcePosition),
      sourceField: rawMapping.sourceField?.trim() || rawMapping.targetField.trim(),
      targetField: rawMapping.targetField.trim(),
      valueType: rawMapping.valueType ?? "STRING",
      required: Boolean(rawMapping.required),
    };
    const sourceField = mapping.sourceField?.trim() || mapping.targetField;
    const key = debugFieldKey(mapping, mappingIndex, bindingIndex, binding.sourceCode);
    const value = parseDebugFieldValue(debugValues[key], mapping);
    if (isBlankDebugValue(value) && !mapping.required) {
      return;
    }
    const position = normalizeSourcePosition(mapping.sourcePosition);
    if (position === "HEADER") {
      headers[sourceField] = value;
      return;
    }
    if (position === "QUERY") {
      query[sourceField] = value;
      return;
    }
    if (position === "FORM") {
      formValues[sourceField] = value;
      return;
    }
    const bodyRow = bodyRows.get(bindingIndex) ?? {};
    assignPath(bodyRow, sourceField, value);
    bodyRows.set(bindingIndex, bodyRow);
  });

  return {
    headers,
    query,
    form: formValues,
    body: bodyRows.size ? wrapBodyPayload(bodyRows) : {},
  };
}

function wrapBodyPayload(rowsByBinding: Map<number, Record<string, unknown>>) {
  if (rowsByBinding.size === 1) {
    const [[bindingIndex, row]] = Array.from(rowsByBinding.entries());
    const binding = form.sourceBindings?.[bindingIndex];
    const path = fullSourcePathForBinding(binding);
    const payload = binding?.payloadMode === "ARRAY" ? [row] : row;
    if (!path) {
      return payload;
    }
    const root: Record<string, unknown> = {};
    assignPath(root, path, payload);
    return root;
  }
  const root: Record<string, unknown> = {};
  rowsByBinding.forEach((row, bindingIndex) => {
    const binding = form.sourceBindings?.[bindingIndex];
    const path = fullSourcePathForBinding(binding);
    const payload = binding?.payloadMode === "ARRAY" ? [row] : row;
    if (path) {
      assignPath(root, path, payload);
      return;
    }
    Object.assign(root, row);
  });
  return root;
}

function syncRestPayloadFromFields() {
  syncRestRawTextFromPayload();
  clearRestRawErrors();
}

function syncRestRawTextFromPayload() {
  debugHeaders.value = prettyJsonValue(restDebugPayload.headers);
  debugQuery.value = prettyJsonValue(restDebugPayload.query);
  debugForm.value = prettyJsonValue(restDebugPayload.form);
}

function updateRawObjectSection(key: "headers" | "query" | "form", value: string, label: string) {
  const parsed = parseJsonObjectText(value, label);
  if (!parsed.ok) {
    debugRawErrors[key] = parsed.error;
    return;
  }
  debugRawErrors[key] = "";
  replaceDebugObject(restDebugPayload[key], parsed.value);
  syncDebugValuesFromRestPayload();
}

function updateRawBodySection(value: string) {
  const parsed = parseJsonValueText(value, "Body");
  if (!parsed.ok) {
    debugRawErrors.body = parsed.error;
    return;
  }
  debugRawErrors.body = "";
  restDebugPayload.body = parsed.value;
  syncDebugValuesFromRestPayload();
}

function syncDebugValuesFromRestPayload() {
  debugMappingEntries().forEach(({ binding, bindingIndex, mapping, mappingIndex }) => {
    if (!mapping.targetField?.trim()) {
      return;
    }
    const bodySource = resolveBodySourceRow(restDebugPayload.body, binding);
    const normalized: DataIngestionFieldMapping = {
      ...mapping,
      sourcePosition: normalizeSourcePosition(mapping.sourcePosition),
      sourceField: mapping.sourceField?.trim() || mapping.targetField.trim(),
      targetField: mapping.targetField.trim(),
      valueType: mapping.valueType ?? "STRING",
    };
    const sourceField = normalized.sourceField?.trim() || normalized.targetField;
    const position = normalizeSourcePosition(normalized.sourcePosition);
    const source = position === "HEADER"
      ? restDebugPayload.headers
      : position === "QUERY"
        ? restDebugPayload.query
        : position === "FORM"
          ? restDebugPayload.form
          : bodySource;
    const value = position === "BODY" ? readDebugPath(source, sourceField) : source[sourceField];
    if (value !== undefined) {
      debugValues[debugFieldKey(mapping, mappingIndex, bindingIndex, binding.sourceCode)] = toDebugFieldValue(value);
    }
  });
}

function resolveBodySourceRow(body: unknown, binding: DataIngestionSourceBinding = activeSourceBinding.value) {
  const path = fullSourcePathForBinding(binding);
  const payload = path ? readDebugPath(body, path) : body;
  return firstArrayItemOrSelf(payload) ?? {};
}

function clearRestRawErrors() {
  debugRawErrors.headers = "";
  debugRawErrors.query = "";
  debugRawErrors.form = "";
  debugRawErrors.body = "";
}

function assignPath(target: Record<string, unknown>, path: string, value: unknown) {
  assignDebugPath(target, path, value);
}

function parseDebugFieldValue(value: DebugFieldValue, mapping: DataIngestionFieldMapping) {
  if (isBlankDebugValue(value)) {
    return "";
  }
  if (mapping.valueType === "BOOLEAN") {
    return Boolean(value);
  }
  if (isNumericType(mapping.valueType)) {
    const numberValue = Number(value);
    if (!Number.isFinite(numberValue)) {
      throw new Error(`字段 ${mapping.targetField} 必须是数字`);
    }
    return numberValue;
  }
  if (isJsonLikeType(mapping.valueType)) {
    try {
      return JSON.parse(String(value));
    } catch {
      throw new Error(`字段 ${mapping.targetField} 不是合法 JSON`);
    }
  }
  return value;
}

function isBlankDebugValue(value: DebugFieldValue) {
  return value == null || (typeof value === "string" && !value.trim());
}

function setDebugValue(key: string, value: DebugFieldValue) {
  debugValues[key] = value;
  syncRestPayloadFromFields();
  curlCommand.value = "";
}

function setSoapFieldValue(key: string, value: DebugFieldValue) {
  soapFieldValues[key] = value;
  rebuildSoapEnvelopeFromFields();
}

function setSoapHeaderValue(key: string, value: DebugFieldValue) {
  soapHttpHeaders[key] = value ?? "";
  syncSoapHeadersRawFromPayload();
  soapHeadersError.value = "";
}

function updateSoapHeaders(value: string) {
  soapDebugHeaders.value = value;
  const parsed = parseJsonObjectText(value, "HTTP Headers");
  if (!parsed.ok) {
    soapHeadersError.value = parsed.error;
    return;
  }
  soapHeadersError.value = "";
  replaceDebugObject(soapHttpHeaders, parsed.value);
}

function formatSoapResponse() {
  const formatted = formatXmlText(debugResult.value === "{}" ? "" : debugResult.value, "SOAP 响应");
  if (!formatted.ok) {
    ElMessage.error(formatted.error);
    return;
  }
  debugResult.value = formatted.value || "{}";
}

function updateSoapEnvelope(value: string) {
  soapEnvelope.value = value;
  const parsed = parseSoapEnvelope(value);
  if (!parsed.ok) {
    soapEnvelopeError.value = parsed.error;
    return;
  }
  soapEnvelopeError.value = "";
  if (parsed.tokenValue != null) {
    soapFieldValues[SOAP_TOKEN_FIELD_KEY] = parsed.tokenValue;
  }
  syncSoapValuesFromEnvelope(parsed.values, parsed.headerValues);
}

function syncSoapValues(force: boolean) {
  if (force || !(SOAP_TOKEN_FIELD_KEY in soapFieldValues)) {
    soapFieldValues[SOAP_TOKEN_FIELD_KEY] = "your-token";
  }
  debugMappingEntries().forEach(({ binding, bindingIndex, mapping, mappingIndex }) => {
    const key = debugFieldKey(mapping, mappingIndex, bindingIndex, binding.sourceCode);
    if (force || !(key in soapFieldValues)) {
      soapFieldValues[key] = initialDebugValue(mapping);
    }
  });
  if (!Object.keys(soapHttpHeaders).length) {
    soapHttpHeaders["X-Data-Ingestion-Token"] = "";
    syncSoapHeadersRawFromPayload();
  }
}

function syncSoapValuesFromEnvelope(bodyValues: DebugObject, headerValues: DebugObject) {
  debugMappingEntries().forEach(({ binding, bindingIndex, mapping, mappingIndex }) => {
    if (!mapping.targetField?.trim()) {
      return;
    }
    const bodySource = resolveBodySourceRow(bodyValues, binding);
    const sourceField = mapping.sourceField?.trim() || mapping.targetField.trim();
    const position = normalizeSourcePosition(mapping.sourcePosition);
    const source = position === "HEADER" ? headerValues : bodySource;
    const value = position === "BODY" ? readDebugPath(source, sourceField) : source[sourceField];
    if (value !== undefined) {
      soapFieldValues[debugFieldKey(mapping, mappingIndex, bindingIndex, binding.sourceCode)] = toDebugFieldValue(value);
    }
  });
}

function rebuildSoapEnvelopeFromFields() {
  syncSoapValues(false);
  soapEnvelope.value = buildSoapEnvelope({
    soapVersion: form.webserviceConfig.soapVersion || "SOAP_11",
    namespaceUri: form.webserviceConfig.namespaceUri || webservicePreview.value?.namespaceUri || undefined,
    requestRootName: form.webserviceConfig.requestRootName
      || form.webserviceConfig.operationName
      || webservicePreview.value?.operationName
      || form.serviceCode
      || "request",
    tokenElementName: "token",
    tokenValue: stringifyDebugValue(soapFieldValues[SOAP_TOKEN_FIELD_KEY] || "your-token"),
    headerFields: buildSoapFieldSpecs("HEADER"),
    bodyPayload: buildSoapBodyPayload(),
  });
  soapEnvelopeError.value = "";
}

function buildSoapFieldSpecs(target: "HEADER" | "BODY"): SoapFieldSpec[] {
  return debugMappingEntries()
    .filter(({ mapping }) => {
      if (!mapping.targetField?.trim()) {
        return false;
      }
      const position = normalizeSourcePosition(mapping.sourcePosition);
      return target === "HEADER" ? position === "HEADER" : position !== "HEADER";
    })
    .map(({ binding, bindingIndex, mapping, mappingIndex }) => {
      const sourceField = mapping.sourceField?.trim() || mapping.targetField.trim();
      const key = debugFieldKey(mapping, mappingIndex, bindingIndex, binding.sourceCode);
      return {
        key,
        elementName: sourceField,
        value: soapFieldValues[key] ?? initialDebugValue(mapping),
      };
    });
}

function buildSoapBodyPayload() {
  const bodyRows = new Map<number, Record<string, unknown>>();
  debugMappingEntries().forEach(({ binding, bindingIndex, mapping, mappingIndex }) => {
    if (!mapping.targetField?.trim() || normalizeSourcePosition(mapping.sourcePosition) === "HEADER") {
      return;
    }
    const row = bodyRows.get(bindingIndex) ?? {};
    const sourceField = mapping.sourceField?.trim() || mapping.targetField.trim();
    const key = debugFieldKey(mapping, mappingIndex, bindingIndex, binding.sourceCode);
    assignPath(row, sourceField, soapFieldValues[key] ?? initialDebugValue(mapping));
    bodyRows.set(bindingIndex, row);
  });
  return bodyRows.size ? wrapBodyPayload(bodyRows) : {};
}

function syncSoapHeadersRawFromPayload() {
  soapDebugHeaders.value = prettyJsonValue(soapHttpHeaders);
}

function currentSoapHttpHeaders() {
  soapHeadersError.value = "";
  return compactDebugObject(soapHttpHeaders, true);
}

function toDebugFieldValue(value: unknown): DebugFieldValue {
  if (value == null || typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return value as DebugFieldValue;
  }
  return stringifyDebugValue(value);
}

function replaceDebugObject(target: DebugObject, source?: Record<string, unknown>) {
  Object.keys(target).forEach((key) => {
    delete target[key];
  });
  Object.assign(target, source ?? {});
}

function isNumericType(valueType?: FieldValueType) {
  return valueType === "INTEGER" || valueType === "LONG" || valueType === "DECIMAL";
}

function isJsonLikeType(valueType?: FieldValueType) {
  return valueType === "ARRAY" || valueType === "OBJECT" || valueType === "JSON";
}

function debugControlType(valueType?: FieldValueType): DebugControlType {
  if (valueType === "BOOLEAN") {
    return "boolean";
  }
  if (isNumericType(valueType)) {
    return "number";
  }
  if (isJsonLikeType(valueType)) {
    return "textarea";
  }
  return "text";
}

function sourcePositionTitle(position: DataIngestionSourcePosition) {
  if (position === "FORM") {
    return "Form Body";
  }
  if (position === "QUERY") {
    return "Query 参数";
  }
  if (position === "HEADER") {
    return "Header 参数";
  }
  return "Body 路径";
}

function sourcePositionDescription(position: DataIngestionSourcePosition) {
  if (position === "FORM") {
    return "以表单字段提交，适合 x-www-form-urlencoded。";
  }
  if (position === "QUERY") {
    return "拼接在接入地址后的查询参数。";
  }
  if (position === "HEADER") {
    return "随请求头提交的元数据。";
  }
  return "写入各目标完整来源路径下的请求 Body，SOAP 模式下按 XML 节点路径读取。";
}

function parseJsonObject(value: string, label: string) {
  const parsed = parseJsonObjectText(value, label);
  if (!parsed.ok) {
    throw new Error(parsed.error);
  }
  return parsed.value;
}

function parseJsonValue(value: string, label: string) {
  const parsed = parseJsonValueText(value, label);
  if (!parsed.ok) {
    throw new Error(parsed.error);
  }
  return parsed.value;
}

function resolveEndpoint() {
  return resolveDataServiceOpenUrl(endpointPath.value);
}
</script>

<style scoped>
.service-wizard {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
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

.section-toolbar {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  margin: 0 0 12px;
}

.section-toolbar--spaced {
  margin-top: 20px;
}

.section-toolbar p {
  margin: 4px 0 0;
  color: var(--studio-text-soft);
  font-size: 12px;
}

.ingestion-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(260px, 1fr));
  gap: 2px 20px;
}

.target-max-batch-input {
  width: 100%;
}

.studio-toolbar-actions,
.mapping-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.mapping-actions {
  padding-top: 14px;
}

.target-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 18px;
  padding: 4px 0;
}

.target-card {
  position: relative;
  display: grid;
  align-content: start;
  gap: 14px;
  min-height: 248px;
  padding: 22px 20px 18px;
  border: 1px solid rgba(148, 163, 184, 0.32);
  border-radius: 8px;
  background:
    linear-gradient(180deg, rgba(248, 250, 252, 0.96), rgba(255, 255, 255, 1) 42%),
    #fff;
  color: var(--studio-text);
  text-align: left;
  cursor: pointer;
  box-shadow: 0 16px 36px rgba(15, 23, 42, 0.07);
  overflow: visible;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease, background 0.18s ease;
}

.target-card:hover,
.target-card.active {
  border-color: var(--studio-primary);
  background:
    linear-gradient(180deg, rgba(239, 246, 255, 0.96), rgba(255, 255, 255, 1) 46%),
    #fff;
  box-shadow: 0 22px 46px rgba(37, 99, 235, 0.14);
  transform: translateY(-2px);
}

.target-card.disabled {
  opacity: 0.62;
}

.target-card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.target-card-title {
  min-width: 0;
}

.target-card-title strong {
  display: block;
  padding-right: 8px;
  font-size: 14px;
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.target-card-title small {
  display: block;
  margin-top: 4px;
  color: var(--studio-text-soft);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.target-card-status {
  flex: 0 0 auto;
  min-width: 48px;
  height: 24px;
  padding: 0 10px;
  border: 1px solid rgba(34, 197, 94, 0.25);
  border-radius: 999px;
  background: rgba(34, 197, 94, 0.1);
  color: #15803d;
  font-size: 12px;
  line-height: 22px;
  text-align: center;
}

.target-card-status.disabled {
  border-color: rgba(100, 116, 139, 0.24);
  background: rgba(100, 116, 139, 0.08);
  color: var(--studio-text-soft);
}

.target-card-path {
  display: grid;
  grid-template-columns: minmax(84px, max-content) minmax(0, 1fr);
  gap: 6px;
  padding: 12px 14px;
  border: 1px solid rgba(37, 99, 235, 0.12);
  border-radius: 8px;
  background: rgba(37, 99, 235, 0.04);
}

.target-card-path div {
  display: grid;
  gap: 5px;
  min-width: 0;
}

.target-card-path span,
.target-card-facts span {
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.2;
}

.target-card-path strong {
  color: var(--studio-text);
  font-size: 15px;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.target-card-path div:first-child strong {
  font-size: 13px;
  white-space: nowrap;
}

.target-card-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.target-card-facts div {
  display: grid;
  gap: 5px;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.82);
}

.target-card-facts strong {
  min-width: 0;
  color: var(--studio-text);
  font-size: 13px;
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.target-card-delete {
  position: absolute;
  top: -10px;
  left: -10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 1px solid rgba(239, 68, 68, 0.2);
  border-radius: 50%;
  background: #fff;
  color: #dc2626;
  font-size: 18px;
  font-weight: 600;
  line-height: 1;
  box-shadow: 0 10px 22px rgba(239, 68, 68, 0.18), 0 2px 6px rgba(15, 23, 42, 0.08);
  z-index: 2;
  transition: background 0.18s ease, color 0.18s ease, transform 0.18s ease, box-shadow 0.18s ease;
}

.target-card-delete:hover {
  background: #ef4444;
  color: #fff;
  box-shadow: 0 14px 28px rgba(239, 68, 68, 0.25), 0 3px 8px rgba(15, 23, 42, 0.1);
  transform: scale(1.04);
}

.target-card-delete.disabled {
  border-color: var(--el-border-color-lighter);
  background: var(--el-fill-color-light);
  color: var(--studio-text-soft);
  box-shadow: none;
  cursor: not-allowed;
}

.target-card-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-self: end;
}

.target-card--add {
  place-items: center;
  align-content: center;
  min-height: 248px;
  color: var(--studio-primary);
  border-style: dashed;
  background: var(--el-fill-color-extra-light);
  box-shadow: none;
}

.target-card--add span {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  border-radius: 50%;
  border: 1px solid rgba(37, 99, 235, 0.26);
  background: #fff;
  font-size: 30px;
  line-height: 1;
}

.target-detail-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 14px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.target-detail-header p {
  margin: 4px 0 0;
  color: var(--studio-text-soft);
  font-size: 12px;
}

.target-detail-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.target-secondary-tabs {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
}

.target-secondary-tabs button {
  min-width: 96px;
  height: 32px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--studio-text-soft);
  cursor: pointer;
}

.target-secondary-tabs button.active {
  background: #fff;
  color: var(--studio-primary);
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.08);
}

.service-debug-alert {
  margin-bottom: 14px;
}

.ingestion-debug-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.ingestion-debug-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.ingestion-debug-layout {
  display: grid;
  gap: 18px;
  margin-bottom: 14px;
}

.ingestion-debug-panel {
  overflow: hidden;
  border: 1px solid var(--studio-border);
  border-left: 4px solid var(--studio-primary);
  border-radius: 8px;
  background: var(--studio-surface-strong, #fff);
}

.ingestion-debug-panel:nth-of-type(2) {
  border-left-color: var(--studio-accent);
}

.ingestion-debug-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px 12px;
  border-bottom: 1px solid rgba(64, 113, 187, 0.12);
  background: linear-gradient(180deg, rgba(248, 250, 252, 0.9), rgba(255, 255, 255, 0.96));
}

.ingestion-debug-panel__header strong {
  color: var(--studio-text);
  font-size: 13px;
}

.ingestion-debug-panel__header p {
  margin: 4px 0 0;
  color: var(--studio-text-soft);
  font-size: 12px;
}

.service-debug-curl-panel {
  display: grid;
  gap: 10px;
  margin-top: 16px;
  padding: 14px;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
}

.service-debug-curl-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.service-debug-curl-panel__header strong {
  color: var(--studio-text);
  font-size: 13px;
}

.service-debug-curl-panel__header p {
  margin: 4px 0 0;
  color: var(--studio-text-soft);
  font-size: 12px;
}

.service-debug-curl-panel :deep(.el-textarea__inner) {
  font-family: "Cascadia Code", "Consolas", monospace;
  font-size: 12px;
  line-height: 1.6;
}

.wizard-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 16px;
}

.writer-options-panel {
  display: grid;
  gap: 14px;
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-extra-light);
}

.writer-options-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.writer-options-header p {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.writer-options-loading {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.writer-options-fallback {
  display: grid;
  gap: 12px;
}

.webservice-collapse {
  margin: 14px 0;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  padding: 0 12px;
  background: #fff;
}

.webservice-title {
  font-weight: 700;
  color: var(--studio-text);
}

.xml-textarea :deep(.el-textarea__inner) {
  font-family: "Cascadia Code", "Consolas", monospace;
  font-size: 13px;
  line-height: 1.6;
}

@media (max-width: 960px) {
  .ingestion-form-grid {
    grid-template-columns: 1fr;
  }

  .service-wizard {
    grid-template-columns: 1fr;
  }

  .target-detail-header {
    display: grid;
  }

  .target-detail-actions {
    justify-content: flex-start;
  }

  .ingestion-debug-toolbar,
  .ingestion-debug-panel__header,
  .service-debug-curl-panel__header {
    display: grid;
  }

  .ingestion-debug-actions {
    justify-content: flex-start;
  }
}
</style>
