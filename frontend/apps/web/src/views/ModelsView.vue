<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.models.heading") }}</h3>
        <p>{{ t("web.models.description") }}</p>
      </div>
      <div v-if="!isDetailPage" class="soft-panel index-queue-card">
        <div class="index-queue-card__header">
          <div>
            <strong>{{ t("web.models.indexQueueTitle") }}</strong>
            <p>{{ t("web.models.indexQueueDescription") }}</p>
          </div>
          <StatusPill
            :label="indexQueueBusy ? t('web.models.indexQueueBusy') : t('web.models.indexQueueIdle')"
            :tone="indexQueueBusy ? 'warning' : 'success'"
          />
        </div>
        <div class="index-queue-card__metrics">
          <div class="index-queue-card__metric">
            <span class="index-queue-card__metric-label">{{ t("web.models.indexQueuePending") }}</span>
            <strong class="index-queue-card__metric-value">{{ indexQueuePendingRebuildCount }}</strong>
          </div>
          <div class="index-queue-card__metric">
            <span class="index-queue-card__metric-label">{{ t("web.models.indexQueueQueuedCommands") }}</span>
            <strong class="index-queue-card__metric-value">{{ indexQueueQueuedCommandCount }}</strong>
          </div>
        </div>
      </div>
    </div>

    <template v-if="!isDetailPage">
      <el-tabs v-model="activeListTab" class="models-tabs">
        <el-tab-pane label="模型列表" name="models">
          <SectionCard :title="t('web.models.tableTitle')" :description="t('web.models.tableDescription')">
        <div class="models-toolbar">
          <el-select
            v-model="selectedDatasourceType"
            clearable
            :placeholder="t('web.models.datasourceTypePlaceholder')"
            class="models-toolbar__filter"
            @change="handleDatasourceTypeChange"
          >
            <el-option
              v-for="typeCode in queryDatasourceTypes"
              :key="typeCode"
              :label="typeCode"
              :value="typeCode"
            />
          </el-select>
          <el-select
            v-model="selectedDatasourceId"
            clearable
            :placeholder="t('web.models.datasourcePlaceholder')"
            class="models-toolbar__filter"
            @change="handleDatasourceChange"
          >
            <el-option
              v-for="item in filteredDatasourceOptions"
              :key="item.id"
              :label="`${item.name} (${item.typeCode})`"
              :value="item.id"
            />
          </el-select>
          <el-button type="primary" :disabled="!authStore.currentProjectId" @click="openCreateDialog">{{ t("common.newModel") }}</el-button>
          <el-button plain :disabled="!authStore.currentProjectId" @click="openSyncDialog">{{ t("common.sync") }}</el-button>
          <el-button plain @click="rebuildQueryIndex">{{ t("common.rebuild") }}</el-button>
          <el-button plain @click="openStatisticsWorkspace()">{{ t("common.statistics") }}</el-button>
          <el-button plain @click="refreshModels">{{ t("common.refresh") }}</el-button>
        </div>

        <div class="soft-panel model-query-panel">
          <div class="model-query-panel__header">
            <div>
              <strong>{{ t("web.models.dynamicFiltersTitle") }}</strong>
              <p>{{ t("web.models.dynamicFiltersDescription") }}</p>
            </div>
            <div class="model-query-panel__actions">
              <el-button plain :disabled="!activeQueryDatasourceType" @click="appendQueryGroup">{{ t("common.addFilter") }}</el-button>
              <el-button type="primary" @click="searchModels">{{ t("common.search") }}</el-button>
              <el-button plain @click="resetQueryFilters">{{ t("common.reset") }}</el-button>
            </div>
          </div>

          <div v-if="queryGroups.length === 0" class="soft-panel empty-hint section-empty">
            {{ t("web.models.dynamicFiltersEmpty") }}
          </div>

          <div
            v-for="group in queryGroups"
            :key="group.key"
            class="soft-panel model-query-group"
          >
            <div class="model-query-group__header">
              <div class="model-query-group__meta">
                <span class="model-query-group__label">{{ t("web.models.filterMetaModel") }}</span>
                <el-select
                  v-model="group.metaSchemaCode"
                  clearable
                  :placeholder="t('web.models.filterMetaModelPlaceholder')"
                  @change="handleQuerySchemaChange(group)"
                >
                  <el-option
                    v-for="schema in querySchemaOptions"
                    :key="schema.id ?? schema.schemaCode"
                    :label="querySchemaLabel(schema)"
                    :value="schema.schemaCode"
                  />
                </el-select>
              </div>
              <div v-if="isMultipleQuerySchema(group)" class="model-query-group__meta model-query-group__meta--compact">
                <span class="model-query-group__label">{{ t("web.models.filterRowMatchMode") }}</span>
                <el-select v-model="group.rowMatchMode">
                  <el-option :label="t('web.models.filterRowMatchSameItem')" value="SAME_ITEM" />
                  <el-option :label="t('web.models.filterRowMatchAnyItem')" value="ANY_ITEM" />
                </el-select>
              </div>
              <div class="model-query-group__actions">
                <el-button type="primary" plain @click="appendQueryCondition(group)">{{ t("common.addCondition") }}</el-button>
                <el-button link type="danger" @click="removeQueryGroup(group.key)">{{ t("common.remove") }}</el-button>
              </div>
            </div>

            <div v-if="group.conditions.length === 0" class="soft-panel empty-hint section-empty">
              {{ t("web.models.dynamicFiltersEmpty") }}
            </div>

            <div v-else class="model-query-group__conditions">
              <div
                v-for="(row, index) in group.conditions"
                :key="`${group.key}-${index}`"
                class="soft-panel model-query-condition"
              >
                <div class="model-query-condition__line">
                  <div class="model-query-condition__segment">
                    <span class="model-query-condition__label">{{ t("web.models.filterField") }}</span>
                    <el-select
                      v-model="row.fieldKey"
                      clearable
                      :placeholder="t('web.models.filterFieldPlaceholder')"
                      @change="handleQueryFieldChange(group, row)"
                    >
                      <el-option
                        v-for="field in querySchemaFields(group)"
                        :key="field.fieldKey"
                        :label="field.fieldName"
                        :value="field.fieldKey"
                      />
                    </el-select>
                  </div>
                  <div class="model-query-condition__segment model-query-condition__segment--operator">
                    <span class="model-query-condition__label">{{ t("web.models.filterOperator") }}</span>
                    <el-select
                      v-model="row.operator"
                      clearable
                      :placeholder="t('web.models.filterOperatorPlaceholder')"
                    >
                      <el-option
                        v-for="operator in queryConditionOperators(group, row)"
                        :key="operator"
                        :label="operator"
                        :value="operator"
                      />
                    </el-select>
                  </div>
                  <div class="model-query-condition__segment model-query-condition__segment--value">
                    <span class="model-query-condition__label">{{ t("web.models.filterValue") }}</span>
                    <div class="query-condition-value">
                      <el-input
                        v-if="row.operator === 'IN'"
                        v-model="row.multiValueText"
                        :placeholder="t('web.models.filterValuesPlaceholder')"
                      />
                      <template v-else-if="row.operator === 'BETWEEN'">
                        <component
                          :is="isNumericQueryField(queryConditionField(group, row)) ? ElInputNumber : ElInput"
                          :model-value="queryConditionInputValue(row.value)"
                          class="query-condition-value__input"
                          :placeholder="t('web.models.filterValuePlaceholder')"
                          @update:model-value="setQueryConditionValue(row, $event)"
                        />
                        <span class="query-condition-value__divider">-</span>
                        <component
                          :is="isNumericQueryField(queryConditionField(group, row)) ? ElInputNumber : ElInput"
                          :model-value="queryConditionInputValue(row.valueTo)"
                          class="query-condition-value__input"
                          :placeholder="t('web.models.filterValueToPlaceholder')"
                          @update:model-value="setQueryConditionValueTo(row, $event)"
                        />
                      </template>
                      <el-select
                        v-else-if="queryConditionField(group, row)?.valueType === 'BOOLEAN'"
                        :model-value="queryConditionBooleanValue(row.value)"
                        clearable
                        :placeholder="t('web.models.filterValuePlaceholder')"
                        @update:model-value="setQueryConditionValue(row, $event)"
                      >
                        <el-option :label="t('common.yes')" :value="true" />
                        <el-option :label="t('common.no')" :value="false" />
                      </el-select>
                      <component
                        :is="isNumericQueryField(queryConditionField(group, row)) ? ElInputNumber : ElInput"
                        v-else
                        :model-value="queryConditionInputValue(row.value)"
                        class="query-condition-value__input"
                        :placeholder="t('web.models.filterValuePlaceholder')"
                        @update:model-value="setQueryConditionValue(row, $event)"
                      />
                    </div>
                  </div>
                  <el-button link type="danger" class="model-query-condition__remove" @click="removeQueryCondition(group, index)">
                    {{ t("common.remove") }}
                  </el-button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <el-table :data="pagedModels" border :empty-text="modelTableEmptyText">
          <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
            <template #default="{ $index }">
              {{ getPaginatedRowNumber(modelPagination, $index) }}
            </template>
          </el-table-column>
          <el-table-column :label="t('web.models.modelName')" min-width="180">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="openModelDetail(row)">{{ row.name }}</el-button>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.models.datasourceLabel')" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              {{ resolveDatasourceLabel(row.datasourceId) }}
            </template>
          </el-table-column>
          <el-table-column label="所属项目" min-width="170">
            <template #default="{ row }">
              <div class="stack-cell">
                <span>{{ resolveProjectLabel(row.projectId) }}</span>
                <span class="cell-subtle">{{ isSharedModel(row) ? "共享来源" : "当前项目" }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.models.kind')" width="120" align="center" header-align="center">
            <template #default="{ row }">
              {{ formatModelKind(t, row.modelKind) }}
            </template>
          </el-table-column>
          <el-table-column prop="physicalLocator" :label="t('web.models.physicalLocator')" min-width="220" show-overflow-tooltip />
          <el-table-column :label="t('web.metadata.actions')" width="140" align="center" header-align="center">
            <template #default="{ row }">
              <OverflowActionGroup :items="buildModelActions(row)" />
            </template>
          </el-table-column>
        </el-table>
        <div class="table-pagination">
          <el-pagination
            v-model:current-page="modelPagination.page"
            v-model:page-size="modelPagination.pageSize"
            background
            layout="total, sizes, prev, pager, next"
            :page-sizes="[10, 20, 50, 100]"
            :total="models.length"
          />
        </div>
          </SectionCard>
        </el-tab-pane>

        <el-tab-pane label="模型同步任务" name="sync-tasks">
          <SectionCard title="模型同步任务" description="大批量表同步会自动转入后台任务执行，避免页面长时间等待。">
            <div class="models-toolbar">
              <el-select
                v-model="syncTaskFilters.datasourceType"
                clearable
                placeholder="按数据源类型筛选"
                class="models-toolbar__filter"
                @change="handleSyncTaskDatasourceTypeChange"
              >
                <el-option
                  v-for="typeCode in queryDatasourceTypes"
                  :key="typeCode"
                  :label="typeCode"
                  :value="typeCode"
                />
              </el-select>
              <el-select
                v-model="syncTaskFilters.datasourceId"
                clearable
                placeholder="按数据源筛选"
                class="models-toolbar__filter"
                @change="loadSyncTasks"
              >
                <el-option
                  v-for="item in syncTaskFilterDatasourceOptions"
                  :key="item.id"
                  :label="`${item.name} (${item.typeCode})`"
                  :value="item.id"
                />
              </el-select>
              <el-select
                v-model="syncTaskFilters.status"
                clearable
                placeholder="按状态筛选"
                class="models-toolbar__filter"
                @change="loadSyncTasks"
              >
                <el-option v-for="status in syncTaskStatusOptions" :key="status" :label="formatStatusLabel(t, status)" :value="status" />
              </el-select>
              <el-button type="primary" :disabled="!authStore.currentProjectId" @click="openCreateSyncTaskDialog">新建同步任务</el-button>
              <el-button plain @click="loadSyncTasks">刷新任务</el-button>
            </div>

            <el-table :data="syncTasks" border v-loading="loadingSyncTasksPage">
              <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
                <template #default="{ $index }">
                  {{ (syncTaskPagination.page - 1) * syncTaskPagination.pageSize + $index + 1 }}
                </template>
              </el-table-column>
              <el-table-column label="同步名称" min-width="220">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openSyncTaskDetail(row)">{{ row.name }}</el-button>
                </template>
              </el-table-column>
              <el-table-column label="同步进度" min-width="220">
                <template #default="{ row }">
                  <div class="sync-task-progress">
                    <el-progress :percentage="Number(row.progressPercent || 0)" :stroke-width="10" />
                    <span class="cell-subtle">
                      {{ Number(row.successCount || 0) }}/{{ Number(row.totalCount || 0) }} 成功，失败 {{ Number(row.failedCount || 0) }}，停止 {{ Number(row.stoppedCount || 0) }}
                    </span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="创建时间" min-width="180">
                <template #default="{ row }">
                  {{ row.createdAt || t("common.none") }}
                </template>
              </el-table-column>
              <el-table-column label="持续时间" min-width="140">
                <template #default="{ row }">
                  {{ formatDurationMs(row.durationMs) }}
                </template>
              </el-table-column>
              <el-table-column label="状态" width="120" align="center" header-align="center">
                <template #default="{ row }">
                  <StatusPill :label="formatStatusLabel(t, row.status)" :tone="toneFromStatus(row.status)" />
                </template>
              </el-table-column>
              <el-table-column :label="t('web.metadata.actions')" width="150" align="center" header-align="center">
                <template #default="{ row }">
                  <div class="sync-task-actions">
                    <el-button link type="primary" @click="openSyncTaskDetail(row)">查看</el-button>
                    <el-button link type="warning" :disabled="!canStopSyncTask(row)" @click="stopSyncTask(row)">停止</el-button>
                    <el-button link type="danger" :disabled="!canDeleteSyncTask(row)" @click="deleteSyncTask(row)">删除</el-button>
                  </div>
                </template>
              </el-table-column>
            </el-table>

            <div class="table-pagination">
              <el-pagination
                v-model:current-page="syncTaskPagination.page"
                v-model:page-size="syncTaskPagination.pageSize"
                background
                layout="total, sizes, prev, pager, next"
                :page-sizes="[10, 20, 50, 100]"
                :total="syncTaskTotal"
                @current-change="loadSyncTasks"
                @size-change="handleSyncTaskPageSizeChange"
              />
            </div>
          </SectionCard>
        </el-tab-pane>
      </el-tabs>
    </template>

    <template v-else>
      <div class="detail-toolbar">
        <el-button plain @click="goBackToList">{{ t("common.backToList") }}</el-button>
        <div class="detail-toolbar__actions">
          <el-button plain :disabled="!selectedModel?.datasourceId" @click="openStatisticsWorkspace(selectedModel?.datasourceId)">{{ t("common.statistics") }}</el-button>
          <el-button plain @click="refreshDetail">{{ t("common.refresh") }}</el-button>
          <el-button type="primary" :disabled="!selectedModel || isSharedSelectedModel" @click="openDetailEdit">{{ t("common.edit") }}</el-button>
        </div>
      </div>

      <SectionCard :title="t('web.models.previewTitle')" :description="t('web.models.previewDescription')">
        <div v-if="selectedModel" class="soft-panel preview-head">
          <div>
            <strong>{{ selectedModel.name }}</strong>
            <p>{{ selectedModel.physicalLocator }}</p>
            <p>所属项目：{{ resolveProjectLabel(selectedModel.projectId) }}</p>
          </div>
          <div class="preview-head__tags">
            <StatusPill :label="formatModelKind(t, selectedModel.modelKind)" tone="primary" />
            <StatusPill
              :label="isSharedSelectedModel ? '共享来源' : '当前项目'"
              :tone="isSharedSelectedModel ? 'warning' : 'success'"
            />
          </div>
        </div>
        <div v-else class="soft-panel empty-hint">
          {{ t("web.models.previewEmpty") }}
        </div>

        <template v-if="selectedModel">
          <div v-if="isSharedSelectedModel" class="soft-panel warning-hint">
            当前模型来自其他项目共享，支持查看与引用，但不能在此项目中编辑或删除。
          </div>
          <div class="model-section-stack">
            <div
              v-for="section in previewSections"
              :key="section.key"
              class="soft-panel model-meta-section"
            >
              <div class="model-meta-section__header">
                <div>
                  <strong>{{ section.title }}</strong>
                  <p>{{ section.description }}</p>
                </div>
                <div class="model-meta-section__tags">
                  <StatusPill
                    :label="section.binding === 'TECHNICAL' ? t('metaForm.technicalTitle') : t('metaForm.businessTitle')"
                    :tone="section.binding === 'TECHNICAL' ? 'primary' : 'success'"
                  />
                  <StatusPill
                    :label="section.displayMode === 'MULTIPLE' ? t('web.metadata.displayMultiple') : t('web.metadata.displaySingle')"
                    tone="neutral"
                  />
                </div>
              </div>

              <div v-if="section.displayMode === 'MULTIPLE'">
                <div v-if="previewSectionRows(section).length === 0" class="soft-panel empty-hint section-empty">
                  {{ t("web.models.metaSectionEmpty") }}
                </div>
                <el-table v-else :data="previewSectionRows(section)" border>
                  <el-table-column
                    v-for="field in section.fields"
                    :key="field.fieldKey"
                    :prop="field.fieldKey"
                    :label="field.fieldName"
                    min-width="140"
                    show-overflow-tooltip
                  >
                    <template #default="{ row }">
                      {{ formatDisplayValue(row[field.fieldKey]) }}
                    </template>
                  </el-table-column>
                </el-table>
              </div>

              <div v-else class="model-field-grid">
                <div
                  v-for="field in section.fields"
                  :key="field.fieldKey"
                  class="model-field-grid__item"
                >
                  <span class="model-field-grid__label">{{ field.fieldName }}</span>
                  <div class="model-field-grid__value">
                    {{ formatDisplayValue(sectionValue(section, field.fieldKey, false)) }}
                  </div>
                </div>
              </div>
            </div>

            <div v-if="previewSections.length === 0" class="soft-panel empty-hint">
              {{ t("web.models.metaSectionEmpty") }}
            </div>
          </div>

          <div class="soft-panel sample-panel">
            <div class="model-meta-section__header">
              <div>
                <strong>{{ t("web.models.sampleRowsTitle") }}</strong>
                <p>{{ t("web.models.sampleRowsDescription") }}</p>
              </div>
            </div>

            <div v-if="previewRows.length === 0" class="soft-panel empty-hint section-empty">
              {{ t("web.models.sampleRowsEmpty") }}
            </div>
            <el-table v-else :data="previewRows" border>
              <el-table-column
                v-for="column in previewColumns"
                :key="column"
                :prop="column"
                :label="column"
                min-width="140"
                show-overflow-tooltip
              />
            </el-table>
          </div>
        </template>
      </SectionCard>
    </template>

    <el-dialog v-model="syncDialogOpen" :title="t('web.models.syncDialogTitle')" width="72%">
      <p class="dialog-description">{{ t("web.models.syncDialogDescription") }}</p>
      <div class="studio-form-grid">
        <el-form-item :label="t('web.models.syncDatasourceType')">
          <el-select
            v-model="syncForm.datasourceType"
            clearable
            :placeholder="t('web.models.syncTypePlaceholder')"
            @change="handleSyncDatasourceTypeChange"
          >
            <el-option
              v-for="typeCode in databaseDatasourceTypes"
              :key="typeCode"
              :label="typeCode"
              :value="typeCode"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('web.models.syncDatasource')">
          <el-select
            v-model="syncForm.datasourceId"
            clearable
            :placeholder="t('web.models.syncDatasourcePlaceholder')"
            @change="handleSyncDatasourceChange"
          >
            <el-option
              v-for="item in syncDatasourceOptions"
              :key="item.id"
              :label="`${item.name} (${item.typeCode})`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
      </div>

      <ModelSyncTableSelector
        v-model="syncSelectedLocators"
        :datasource-id="syncForm.datasourceId"
        :disabled="syncing"
      />

      <template #footer>
        <el-button @click="syncDialogOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="syncing" @click="submitSync">{{ t("common.sync") }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="syncTaskDialogOpen" title="新建模型同步任务" width="76%">
      <p class="dialog-description">选择数据源与需要同步的表，提交后会立即在后台创建并执行同步任务。</p>
      <div class="studio-form-grid">
        <el-form-item label="数据源类型">
          <el-select
            v-model="syncTaskForm.datasourceType"
            clearable
            placeholder="选择数据源类型"
            @change="handleSyncTaskFormDatasourceTypeChange"
          >
            <el-option
              v-for="typeCode in databaseDatasourceTypes"
              :key="typeCode"
              :label="typeCode"
              :value="typeCode"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="数据源">
          <el-select
            v-model="syncTaskForm.datasourceId"
            clearable
            placeholder="选择数据源"
            @change="handleSyncTaskFormDatasourceChange"
          >
            <el-option
              v-for="item in syncTaskDatasourceOptions"
              :key="item.id"
              :label="`${item.name} (${item.typeCode})`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
      </div>

      <ModelSyncTableSelector
        v-model="syncTaskForm.selectedLocators"
        :datasource-id="syncTaskForm.datasourceId"
        :disabled="creatingSyncTask"
      />

      <template #footer>
        <el-button @click="syncTaskDialogOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="creatingSyncTask" @click="submitSyncTaskCreate">创建并执行</el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="editorOpen"
      size="72%"
      :title="isEditingModel ? t('web.models.editDialogTitle') : t('web.models.addDialogTitle')"
    >
      <div class="editor-description">
        {{ isEditingModel ? t("web.models.editDialogDescription") : t("web.models.addDialogDescription") }}
      </div>

      <SectionCard :title="editorPanelTitle" :description="editorPanelDescription">
        <div class="studio-form-grid">
          <el-form-item :label="t('web.models.datasourceLabel')">
            <el-select
              v-model="modelForm.datasourceId"
              :disabled="isEditingModel"
              clearable
              :placeholder="t('web.models.manualDatasourcePlaceholder')"
              @change="handleModelDatasourceChange"
            >
              <el-option
                v-for="item in manualDatasourceOptions"
                :key="item.id"
                :label="`${item.name} (${item.typeCode})`"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('web.models.schemaBinding')">
            <el-select
              v-model="modelForm.schemaVersionId"
              clearable
              :placeholder="t('web.models.schemaBindingPlaceholder')"
              @change="handleModelSchemaChange"
            >
              <el-option
                v-for="schema in availableModelSchemas"
                :key="schema.id"
                :label="`${schema.schemaName} v${schema.versionNumber ?? 1}`"
                :value="schema.currentVersionId ?? schema.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('web.models.modelKindLabel')">
            <el-input :model-value="modelForm.modelKind" readonly />
          </el-form-item>
          <el-form-item :label="t('web.models.modelNameLabel')">
            <el-input v-model="modelForm.name" :placeholder="t('web.models.modelNamePlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('web.models.physicalLocatorLabel')">
            <el-input v-model="modelForm.physicalLocator" :placeholder="t('web.models.physicalLocatorPlaceholder')" />
          </el-form-item>
        </div>

        <div v-if="showManualDatasourceHint" class="soft-panel warning-hint">
          {{ t("web.models.manualDatasourceOnly") }}
        </div>
        <div v-if="!selectedModelSchema" class="soft-panel empty-hint">
          {{ t("web.models.noModelSchema") }}
        </div>
      </SectionCard>

      <template v-for="section in editorSections" :key="section.key">
        <SectionCard :title="section.title" :description="section.description">
          <template #actions>
            <StatusPill
              :label="section.binding === 'TECHNICAL' ? t('metaForm.technicalTitle') : t('metaForm.businessTitle')"
              :tone="section.binding === 'TECHNICAL' ? 'primary' : 'success'"
            />
          </template>

          <template v-if="section.displayMode === 'MULTIPLE'">
            <div class="multiple-section-actions">
              <el-button type="primary" plain @click="appendSectionRow(section)">{{ t("common.addRow") }}</el-button>
            </div>
            <el-table :data="editorSectionRows(section)" border>
              <el-table-column
                v-for="field in section.fields"
                :key="field.fieldKey"
                :label="field.fieldName"
                min-width="150"
              >
                <template #default="{ row, $index }">
                  <component
                    :is="resolveRowEditorComponent(field)"
                    v-bind="resolveRowEditorProps(field)"
                    :model-value="row[field.fieldKey]"
                    @update:model-value="updateSectionRowField(section, $index, field.fieldKey, $event)"
                  >
                    <template v-if="field.componentType === 'SELECT'">
                      <el-option
                        v-for="option in field.options ?? []"
                        :key="option"
                        :label="option"
                        :value="option"
                      />
                    </template>
                  </component>
                </template>
              </el-table-column>
              <el-table-column :label="t('web.metadata.actions')" width="100">
                <template #default="{ $index }">
                  <el-button link type="danger" @click="removeSectionRow(section, $index)">{{ t("common.remove") }}</el-button>
                </template>
              </el-table-column>
            </el-table>
          </template>

          <MetaFormRenderer
            v-else
            :fields="section.fields"
            :model-value="sectionModelValue(section, true)"
            @update:model-value="updateSectionModelValue(section, $event)"
          />
        </SectionCard>
      </template>

      <div class="drawer-actions">
        <el-button @click="editorOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="saving" @click="saveModel">{{ t("common.save") }}</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { ElInput, ElInputNumber, ElMessage, ElMessageBox, ElSelect, ElSwitch } from "element-plus";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import type {
  DataModelDefinition,
  DataModelIndexQueueStatusView,
  DataModelQueryCondition,
  DataModelQueryGroup,
  DataModelQueryRequest,
  DataModelSaveRequest,
  DataSourceDefinition,
  EntityId,
  MetadataFieldDefinition,
  MetadataSchemaDefinition,
  ModelKind,
  ModelSyncTaskView,
} from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import { OverflowActionGroup, SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";
import ModelSyncTableSelector from "@/components/ModelSyncTableSelector.vue";
import { getPaginatedRowNumber, useClientPagination } from "@/composables/useClientPagination";
import {
  ensureBusinessMetaModelEntries,
  getBusinessMetaModelRows,
  getBusinessMetaModelValues,
  parseMetaModelSchema,
  setBusinessMetaModelRows,
  setBusinessMetaModelValues,
} from "@/utils/metaModel";
import { cloneDeep, formatModelKind, formatStatusLabel, isSharedFromAnotherProject, resolveProjectName, toneFromStatus } from "@/utils/studio";

type MetaSectionBinding = "TECHNICAL" | "BUSINESS";

interface ModelFormState {
  id?: EntityId;
  datasourceId?: EntityId;
  name: string;
  physicalLocator: string;
  modelKind?: ModelKind;
  schemaVersionId?: EntityId;
  technicalMetadata: Record<string, unknown>;
  businessMetadata: Record<string, unknown>;
}

interface SyncFormState {
  datasourceType: string;
  datasourceId?: EntityId;
}

interface SyncTaskFormState {
  datasourceType: string;
  datasourceId?: EntityId;
  selectedLocators: string[];
}

interface ModelMetaSection {
  key: string;
  schema: MetadataSchemaDefinition;
  title: string;
  description: string;
  binding: MetaSectionBinding;
  displayMode: "SINGLE" | "MULTIPLE";
  metaModelCode: string;
  fields: MetadataFieldDefinition[];
  collectionKey?: string;
}

interface ModelQueryConditionState {
  fieldKey: string;
  operator: string;
  value?: unknown;
  valueTo?: unknown;
  multiValueText: string;
}

interface ModelQueryGroupState {
  key: string;
  metaSchemaCode: string;
  rowMatchMode: "SAME_ITEM" | "ANY_ITEM";
  conditions: ModelQueryConditionState[];
}

const DATABASE_TYPE_HINTS = [
  "mysql",
  "oracle",
  "postgres",
  "postgresql",
  "sqlserver",
  "clickhouse",
  "kingbase",
  "dm",
  "db2",
  "hive",
  "gauss",
  "tidb",
  "phoenix",
  "greenplum",
  "starrocks",
  "doris",
  "sqlite",
];

const route = useRoute();
const router = useRouter();
const { t } = useI18n();
const authStore = useAuthStore();
const datasources = ref<DataSourceDefinition[]>([]);
const schemas = ref<MetadataSchemaDefinition[]>([]);
const models = ref<DataModelDefinition[]>([]);
const {
  pagination: modelPagination,
  pagedItems: pagedModels,
  resetPagination: resetModelPagination,
} = useClientPagination(models);
const previewRows = ref<Record<string, unknown>[]>([]);
const queryGroups = ref<ModelQueryGroupState[]>([]);
const selectedDatasourceType = ref("");
const selectedDatasourceId = ref<EntityId>();
const selectedModel = ref<DataModelDefinition>();
const activeListTab = ref("models");
const editorOpen = ref(false);
const saving = ref(false);
const syncDialogOpen = ref(false);
const syncing = ref(false);
const syncSelectedLocators = ref<string[]>([]);
const syncTaskDialogOpen = ref(false);
const creatingSyncTask = ref(false);
const syncTasks = ref<ModelSyncTaskView[]>([]);
const loadingSyncTasksPage = ref(false);
const syncTaskTotal = ref(0);
const indexQueueStatus = ref<DataModelIndexQueueStatusView>();
const syncTaskPagination = reactive({
  page: 1,
  pageSize: 20,
});
const syncTaskFilters = reactive({
  datasourceType: "",
  datasourceId: undefined as EntityId | undefined,
  status: "",
});

const modelForm = reactive<ModelFormState>({
  name: "",
  physicalLocator: "",
  technicalMetadata: {},
  businessMetadata: {},
});

const syncForm = reactive<SyncFormState>({
  datasourceType: "",
});

const syncTaskForm = reactive<SyncTaskFormState>({
  datasourceType: "",
  selectedLocators: [],
});

const detailModelId = computed(() => {
  const value = route.params.modelId;
  if (typeof value === "string" || typeof value === "number") {
    return value;
  }
  return undefined;
});
const isDetailPage = computed(() => detailModelId.value != null);
const previewColumns = computed(() => Object.keys(previewRows.value[0] ?? {}));
const modelTableEmptyText = computed(() =>
  selectedDatasourceId.value ? undefined : t("web.models.allModelsEmpty"),
);
const isEditingModel = computed(() => Boolean(modelForm.id));
const editorPanelTitle = computed(() => (isEditingModel.value ? t("web.models.editDialogTitle") : t("web.models.addDialogTitle")));
const editorPanelDescription = computed(() =>
  isEditingModel.value ? t("web.models.editDialogDescription") : t("web.models.addDialogDescription"),
);
const queryDatasourceTypes = computed(() =>
  Array.from(new Set(datasources.value.map((item) => item.typeCode).filter(Boolean))).sort(),
);
const filteredDatasourceOptions = computed(() =>
  datasources.value.filter((item) => !selectedDatasourceType.value || normalizeTypeCode(item.typeCode) === normalizeTypeCode(selectedDatasourceType.value)),
);
const selectedDatasource = computed(() => findDatasourceById(selectedDatasourceId.value));
const activeQueryDatasourceType = computed(() => selectedDatasource.value?.typeCode ?? selectedDatasourceType.value);
const editorDatasource = computed(() => findDatasourceById(modelForm.datasourceId));
const databaseDatasourceTypes = computed(() =>
  Array.from(new Set(datasources.value.filter((item) => isDatabaseDatasourceType(item.typeCode)).map((item) => item.typeCode))).sort(),
);
const syncDatasourceOptions = computed(() =>
  datasources.value.filter(
    (item) => isDatabaseDatasourceType(item.typeCode) && (!syncForm.datasourceType || item.typeCode === syncForm.datasourceType),
  ),
);
const syncTaskDatasourceOptions = computed(() =>
  datasources.value.filter(
    (item) => isDatabaseDatasourceType(item.typeCode)
      && (!syncTaskForm.datasourceType || item.typeCode === syncTaskForm.datasourceType),
  ),
);
const syncTaskFilterDatasourceOptions = computed(() =>
  datasources.value.filter(
    (item) => !syncTaskFilters.datasourceType || item.typeCode === syncTaskFilters.datasourceType,
  ),
);
const syncTaskStatusOptions = computed(() => ["PENDING", "RUNNING", "STOPPING", "SUCCESS", "FAILED", "STOPPED"]);
const indexQueueBusy = computed(() => Boolean(indexQueueStatus.value?.busy));
const indexQueuePendingRebuildCount = computed(() => Number(indexQueueStatus.value?.pendingRebuildCount ?? 0));
const indexQueueQueuedCommandCount = computed(() => Number(indexQueueStatus.value?.queuedCommandCount ?? 0));
const manualDatasourceOptions = computed(() =>
  datasources.value.filter((item) => {
    if (isEditingModel.value && sameId(item.id, modelForm.datasourceId)) {
      return true;
    }
    return !isDatabaseDatasourceType(item.typeCode);
  }),
);
const availableModelSchemas = computed(() =>
  filterModelSchemas(editorDatasource.value?.typeCode).filter((schema) => parseMetaModelSchema(schema).config.metaModelCode !== "field"),
);
const querySchemaOptions = computed(() =>
  buildQuerySchemaOptions(),
);
const selectedModelSchema = computed(() => findSelectedModelSchema(availableModelSchemas.value, modelForm.schemaVersionId));
const editorTechnicalSections = computed(() => buildTechnicalSections(editorDatasource.value?.typeCode, modelForm.schemaVersionId));
const previewTechnicalSections = computed(() => buildTechnicalSections(selectedDatasource.value?.typeCode, selectedModel.value?.schemaVersionId));
const editorBusinessSections = computed(() => buildBusinessSections(resolveBusinessSchemas(editorTechnicalSections.value.map((section) => section.metaModelCode))));
const previewBusinessSections = computed(() => buildBusinessSections(resolveBusinessSchemas(previewTechnicalSections.value.map((section) => section.metaModelCode))));
const editorSections = computed(() => buildOrderedSections(editorTechnicalSections.value, editorBusinessSections.value));
const previewSections = computed(() => buildOrderedSections(previewTechnicalSections.value, previewBusinessSections.value));
const showManualDatasourceHint = computed(
  () => Boolean(editorDatasource.value && isDatabaseDatasourceType(editorDatasource.value.typeCode) && !isEditingModel.value),
);
const isSharedSelectedModel = computed(() =>
  isSharedFromAnotherProject(authStore.currentProjectId, selectedModel.value?.projectId),
);

function sameId(left?: EntityId, right?: EntityId) {
  if (left == null || right == null) {
    return false;
  }
  return String(left) === String(right);
}

function normalizeTypeCode(value?: string) {
  return value?.trim().toLowerCase() ?? "";
}

function isDatabaseDatasourceType(typeCode?: string) {
  const normalized = normalizeTypeCode(typeCode);
  if (!normalized) {
    return false;
  }
  if (["ftp", "sftp", "minio", "oss", "file", "kafka", "rabbitmq", "rocketmq"].some((item) => normalized.includes(item))) {
    return false;
  }
  return DATABASE_TYPE_HINTS.some((item) => normalized.includes(item));
}

function findDatasourceById(datasourceId?: EntityId) {
  return datasources.value.find((item) => sameId(item.id, datasourceId));
}

function resolveProjectLabel(projectId?: EntityId | null) {
  return resolveProjectName(authStore.projects, projectId);
}

function isSharedModel(model: DataModelDefinition) {
  return isSharedFromAnotherProject(authStore.currentProjectId, model.projectId);
}

function filterModelSchemas(datasourceTypeCode?: string) {
  const normalizedType = normalizeTypeCode(datasourceTypeCode);
  if (!normalizedType) {
    return [];
  }
  return schemas.value.filter((schema) => {
    if (normalizeTypeCode(schema.objectType) !== "model") {
      return false;
    }
    const schemaType = normalizeTypeCode(schema.typeCode);
    return schemaType === normalizedType || schemaType.startsWith(`${normalizedType}.`);
  });
}

function querySchemaOperatorOptions(field?: MetadataFieldDefinition) {
  const configured = (field?.queryOperators ?? []).map((item) => String(item)).filter(Boolean);
  if (configured.length > 0) {
    return configured;
  }
  if (field?.valueType === "BOOLEAN") {
    return ["EQ", "IN"];
  }
  if (isNumericQueryField(field)) {
    return ["EQ", "IN", "GT", "GE", "LT", "LE", "BETWEEN"];
  }
  return ["EQ", "LIKE", "IN"];
}

function defaultQueryOperator(field?: MetadataFieldDefinition) {
  return String(field?.queryDefaultOperator ?? querySchemaOperatorOptions(field)[0] ?? "EQ");
}

function activeBusinessMetaModelCodesForQuery() {
  const typeCode = activeQueryDatasourceType.value;
  if (!typeCode) {
    return undefined;
  }
  return new Set(
    filterModelSchemas(typeCode)
      .map((schema) => normalizeTypeCode(parseMetaModelSchema(schema).config.metaModelCode))
      .filter((metaModelCode) => Boolean(metaModelCode) && metaModelCode !== "source"),
  );
}

function buildQuerySchemaOptions() {
  const allowedBusinessMetaModels = activeBusinessMetaModelCodesForQuery();
  return schemas.value
    .filter((schema) => normalizeTypeCode(schema.objectType) === "model" || parseMetaModelSchema(schema).config.domain === "BUSINESS")
    .filter((schema) => parseMetaModelSchema(schema).config.metaModelCode !== "source")
    .filter((schema) => searchableFields(schema).length > 0)
    .filter((schema) => {
      const parsed = parseMetaModelSchema(schema).config;
      if (parsed.domain === "TECHNICAL") {
        if (!activeQueryDatasourceType.value) {
          return false;
        }
        return normalizeTypeCode(parsed.datasourceType) === normalizeTypeCode(activeQueryDatasourceType.value);
      }
      if (!allowedBusinessMetaModels) {
        return false;
      }
      return allowedBusinessMetaModels.has(normalizeTypeCode(parsed.metaModelCode));
    })
    .sort((left, right) => querySchemaLabel(left).localeCompare(querySchemaLabel(right)));
}

function findSelectedModelSchema(options: MetadataSchemaDefinition[], schemaVersionId?: EntityId) {
  if (schemaVersionId != null) {
    const matched = options.find((schema) => sameId(schema.id, schemaVersionId) || sameId(schema.currentVersionId, schemaVersionId));
    if (matched) {
      return matched;
    }
  }
  return options[0];
}

function resolveBusinessSchemas(metaModelCodes: string[]) {
  const allowedCodes = new Set(metaModelCodes.map((code) => normalizeTypeCode(code)).filter(Boolean));
  return schemas.value
    .filter((schema) => {
      const parsed = parseMetaModelSchema(schema).config;
      return parsed.domain === "BUSINESS" && allowedCodes.has(normalizeTypeCode(parsed.metaModelCode));
    })
    .sort((left, right) => {
      const leftConfig = parseMetaModelSchema(left).config;
      const rightConfig = parseMetaModelSchema(right).config;
      const directoryCompare = `${leftConfig.directoryName || leftConfig.directoryCode || ""}`
        .localeCompare(`${rightConfig.directoryName || rightConfig.directoryCode || ""}`);
      if (directoryCompare !== 0) {
        return directoryCompare;
      }
      return left.schemaName.localeCompare(right.schemaName);
    });
}

function resolveSectionCollectionKey(metaModelCode?: string) {
  if (metaModelCode === "field") {
    return "columns";
  }
  if (!metaModelCode) {
    return "items";
  }
  return metaModelCode.endsWith("s") ? metaModelCode : `${metaModelCode}s`;
}

function metaModelRank(metaModelCode?: string) {
  switch (metaModelCode) {
    case "table":
      return 0;
    case "field":
      return 1;
    default:
      return 10;
  }
}

function isRelevantTechnicalSchema(schema: MetadataSchemaDefinition, activeSchemaVersionId?: EntityId) {
  const metaModelCode = parseMetaModelSchema(schema).config.metaModelCode;
  return metaModelCode === "field"
    || sameId(schema.id, activeSchemaVersionId)
    || sameId(schema.currentVersionId, activeSchemaVersionId)
    || (!activeSchemaVersionId && metaModelCode === "table");
}

function buildTechnicalSections(datasourceTypeCode?: string, activeSchemaVersionId?: EntityId) {
  const candidates = filterModelSchemas(datasourceTypeCode)
    .filter((schema) => parseMetaModelSchema(schema).config.metaModelCode !== "source")
    .filter((schema) => isRelevantTechnicalSchema(schema, activeSchemaVersionId))
    .sort((left, right) => {
      const leftActive = sameId(left.id, activeSchemaVersionId) || sameId(left.currentVersionId, activeSchemaVersionId);
      const rightActive = sameId(right.id, activeSchemaVersionId) || sameId(right.currentVersionId, activeSchemaVersionId);
      if (leftActive !== rightActive) {
        return leftActive ? -1 : 1;
      }
      const leftCode = parseMetaModelSchema(left).config.metaModelCode;
      const rightCode = parseMetaModelSchema(right).config.metaModelCode;
      const rankCompare = metaModelRank(leftCode) - metaModelRank(rightCode);
      if (rankCompare !== 0) {
        return rankCompare;
      }
      return left.schemaName.localeCompare(right.schemaName);
    });

  const multipleFieldKeys = new Set<string>();
  for (const schema of candidates) {
    const parsed = parseMetaModelSchema(schema);
    if (parsed.config.displayMode === "MULTIPLE") {
      multipleFieldKeys.add(resolveSectionCollectionKey(parsed.config.metaModelCode));
    }
  }

  return candidates.map((schema) => {
    const parsed = parseMetaModelSchema(schema);
    const displayMode = parsed.config.displayMode ?? (parsed.config.metaModelCode === "field" ? "MULTIPLE" : "SINGLE");
    const fields = (schema.fields ?? [])
      .filter((field) => field.scope !== "BUSINESS")
      .filter((field) => !(displayMode !== "MULTIPLE" && multipleFieldKeys.has(field.fieldKey)));
    return {
      key: `technical:${schema.id ?? schema.schemaCode}`,
      schema,
      title: parsed.config.metaModelName || schema.schemaName,
      description: parsed.plainDescription || schema.schemaCode,
      binding: "TECHNICAL" as MetaSectionBinding,
      displayMode,
      metaModelCode: parsed.config.metaModelCode,
      fields,
      collectionKey: displayMode === "MULTIPLE" ? resolveSectionCollectionKey(parsed.config.metaModelCode) : undefined,
    };
  });
}

function buildBusinessSections(schemasForRender: MetadataSchemaDefinition[]) {
  if (!schemasForRender.length) {
    return [] as ModelMetaSection[];
  }
  return schemasForRender.map((schema) => {
    const parsed = parseMetaModelSchema(schema);
    const displayMode = parsed.config.displayMode ?? "SINGLE";
    return {
      key: `business:${schema.id ?? schema.schemaCode}`,
      schema,
      title: `${parsed.config.directoryName || parsed.config.directoryCode || t("web.models.businessMetaModelTitle")} / ${schema.schemaName}`,
      description: parsed.plainDescription || schema.schemaCode,
      binding: "BUSINESS" as MetaSectionBinding,
      displayMode,
      metaModelCode: parsed.config.metaModelCode,
      fields: (schema.fields ?? []).filter((field) => field.scope === "BUSINESS"),
      collectionKey: displayMode === "MULTIPLE" ? resolveSectionCollectionKey(parsed.config.metaModelCode) : undefined,
    };
  });
}

function buildOrderedSections(technicalSections: ModelMetaSection[], businessSections: ModelMetaSection[]) {
  const ordered = [] as ModelMetaSection[];
  const businessByCode = new Map<string, ModelMetaSection[]>();
  for (const section of businessSections) {
    const code = normalizeTypeCode(section.metaModelCode);
    const items = businessByCode.get(code) ?? [];
    items.push(section);
    businessByCode.set(code, items);
  }
  for (const technicalSection of technicalSections) {
    ordered.push(technicalSection);
    const code = normalizeTypeCode(technicalSection.metaModelCode);
    const matchedBusinessSections = businessByCode.get(code) ?? [];
    ordered.push(...matchedBusinessSections);
    businessByCode.delete(code);
  }
  for (const sectionsForCode of businessByCode.values()) {
    ordered.push(...sectionsForCode);
  }
  return ordered;
}

function deriveModelKindFromDatasource(datasource?: DataSourceDefinition): ModelKind | undefined {
  const typeCode = normalizeTypeCode(datasource?.typeCode);
  if (!typeCode) {
    return undefined;
  }
  if (["kafka", "rocketmq", "rabbitmq"].some((item) => typeCode.includes(item))) {
    return "TOPIC";
  }
  if (["ftp", "sftp", "minio", "oss", "file"].some((item) => typeCode.includes(item))) {
    return "FILE";
  }
  if (isDatabaseDatasourceType(typeCode)) {
    return "TABLE";
  }
  return "DATASET";
}

function deriveModelKindFromSchema(schema?: MetadataSchemaDefinition, datasource?: DataSourceDefinition): ModelKind | undefined {
  const suffix = normalizeTypeCode(schema?.typeCode).split(".").pop();
  switch (suffix) {
    case "table":
      return "TABLE";
    case "view":
      return "VIEW";
    case "file":
      return "FILE";
    case "topic":
      return "TOPIC";
    case "measurement":
      return "MEASUREMENT";
    case "dataset":
      return "DATASET";
    default:
      return deriveModelKindFromDatasource(datasource);
  }
}

function buildDefaultMetadata(fields: MetadataFieldDefinition[]) {
  const defaults: Record<string, unknown> = {};
  for (const field of fields) {
    if (!field.fieldKey || field.defaultValue === undefined || field.defaultValue === null || field.defaultValue === "") {
      continue;
    }
    defaults[field.fieldKey] = parseDefaultValue(field);
  }
  return defaults;
}

function searchableFields(schema?: MetadataSchemaDefinition) {
  return (schema?.fields ?? []).filter((field) => field.searchable);
}

function querySchemaLabel(schema: MetadataSchemaDefinition) {
  const parsed = parseMetaModelSchema(schema);
  if (parsed.config.domain === "BUSINESS") {
    return `${parsed.config.directoryName || parsed.config.directoryCode || "business"} / ${schema.schemaName}`;
  }
  return `${parsed.config.datasourceType || schema.typeCode} / ${schema.schemaName}`;
}

function findQuerySchema(metaSchemaCode?: string) {
  if (!metaSchemaCode) {
    return undefined;
  }
  return querySchemaOptions.value.find((schema) => schema.schemaCode === metaSchemaCode);
}

function querySchemaFields(group: ModelQueryGroupState) {
  return searchableFields(findQuerySchema(group.metaSchemaCode));
}

function queryConditionField(group: ModelQueryGroupState, condition: ModelQueryConditionState) {
  return querySchemaFields(group).find((field) => field.fieldKey === condition.fieldKey);
}

function queryConditionOperators(group: ModelQueryGroupState, condition: ModelQueryConditionState) {
  return querySchemaOperatorOptions(queryConditionField(group, condition));
}

function queryConditionInputValue(value: unknown): string | number | undefined {
  return typeof value === "string" || typeof value === "number" ? value : undefined;
}

function queryConditionBooleanValue(value: unknown): boolean | undefined {
  return typeof value === "boolean" ? value : undefined;
}

function setQueryConditionValue(condition: ModelQueryConditionState, value: string | number | boolean | null | undefined) {
  condition.value = value == null ? undefined : value;
}

function setQueryConditionValueTo(condition: ModelQueryConditionState, value: string | number | null | undefined) {
  condition.valueTo = value == null ? undefined : value;
}

function isMultipleQuerySchema(group: ModelQueryGroupState) {
  const schema = findQuerySchema(group.metaSchemaCode);
  return schema ? parseMetaModelSchema(schema).config.displayMode === "MULTIPLE" : false;
}

function isNumericQueryField(field?: MetadataFieldDefinition) {
  return field?.valueType === "INTEGER" || field?.valueType === "LONG" || field?.valueType === "DECIMAL";
}

let queryGroupSeed = 0;

function nextQueryGroupKey() {
  queryGroupSeed += 1;
  return `query-group-${queryGroupSeed}`;
}

function createDefaultQueryCondition(schema?: MetadataSchemaDefinition): ModelQueryConditionState {
  const field = searchableFields(schema)[0];
  return {
    fieldKey: field?.fieldKey ?? "",
    operator: defaultQueryOperator(field),
    value: undefined,
    valueTo: undefined,
    multiValueText: "",
  };
}

function createQueryGroup(schemaCode?: string): ModelQueryGroupState {
  const schema = schemaCode ? findQuerySchema(schemaCode) : querySchemaOptions.value[0];
  return {
    key: nextQueryGroupKey(),
    metaSchemaCode: schema?.schemaCode ?? schemaCode ?? "",
    rowMatchMode: schema && parseMetaModelSchema(schema).config.displayMode === "MULTIPLE" ? "SAME_ITEM" : "ANY_ITEM",
    conditions: [createDefaultQueryCondition(schema)],
  };
}

function parseDefaultValue(field: MetadataFieldDefinition) {
  const rawValue = field.defaultValue;
  if (rawValue === undefined || rawValue === null) {
    return undefined;
  }
  if (field.valueType === "BOOLEAN") {
    return rawValue === "true";
  }
  if (field.valueType === "INTEGER" || field.valueType === "LONG" || field.valueType === "DECIMAL") {
    const numberValue = Number(rawValue);
    return Number.isNaN(numberValue) ? rawValue : numberValue;
  }
  if (field.valueType === "JSON" || field.valueType === "OBJECT" || field.valueType === "ARRAY") {
    try {
      return JSON.parse(rawValue);
    } catch (error) {
      return rawValue;
    }
  }
  return rawValue;
}

function mergeMetadataDefaults(current: Record<string, unknown> | undefined, fields: MetadataFieldDefinition[]) {
  return {
    ...buildDefaultMetadata(fields),
    ...(current ?? {}),
  };
}

function parseFieldRows(value: unknown) {
  if (!Array.isArray(value)) {
    return [] as Record<string, unknown>[];
  }
  return value
    .filter((item) => item && typeof item === "object" && !Array.isArray(item))
    .map((item) => ({ ...(item as Record<string, unknown>) }));
}

function sectionValue(section: ModelMetaSection, fieldKey?: string, editor = false) {
  if (!fieldKey) {
    return undefined;
  }
  if (section.binding === "TECHNICAL") {
    const metadata = editor ? modelForm.technicalMetadata : selectedModel.value?.technicalMetadata;
    return metadata?.[fieldKey];
  }
  return sectionModelValue(section, editor)?.[fieldKey];
}

function sectionModelValue(section: ModelMetaSection, editor = false) {
  if (section.binding === "TECHNICAL") {
    return (editor ? modelForm.technicalMetadata : selectedModel.value?.technicalMetadata) ?? {};
  }
  const metadata = editor ? modelForm.businessMetadata : selectedModel.value?.businessMetadata;
  return getBusinessMetaModelValues(metadata, section.schema);
}

function previewSectionRows(section: ModelMetaSection) {
  if (section.binding === "BUSINESS") {
    return getBusinessMetaModelRows(selectedModel.value?.businessMetadata, section.schema);
  }
  return parseFieldRows(sectionValue(section, section.collectionKey, false));
}

function editorSectionRows(section: ModelMetaSection) {
  if (section.binding === "BUSINESS") {
    return getBusinessMetaModelRows(modelForm.businessMetadata, section.schema);
  }
  return parseFieldRows(sectionValue(section, section.collectionKey, true));
}

function updateSectionModelValue(section: ModelMetaSection, value: Record<string, unknown>) {
  if (section.binding === "TECHNICAL") {
    modelForm.technicalMetadata = value;
    return;
  }
  modelForm.businessMetadata = setBusinessMetaModelValues(modelForm.businessMetadata, section.schema, value);
}

function setSectionRows(section: ModelMetaSection, rows: Record<string, unknown>[]) {
  if (!section.collectionKey) {
    return;
  }
  if (section.binding === "TECHNICAL") {
    const container = { ...(modelForm.technicalMetadata ?? {}) };
    container[section.collectionKey] = rows;
    modelForm.technicalMetadata = container;
    return;
  }
  modelForm.businessMetadata = setBusinessMetaModelRows(modelForm.businessMetadata, section.schema, rows);
}

function appendSectionRow(section: ModelMetaSection) {
  const rows = editorSectionRows(section);
  rows.push(buildDefaultMetadata(section.fields));
  setSectionRows(section, rows);
}

function removeSectionRow(section: ModelMetaSection, index: number) {
  const rows = editorSectionRows(section);
  rows.splice(index, 1);
  setSectionRows(section, rows);
}

function updateSectionRowField(section: ModelMetaSection, index: number, fieldKey: string, value: unknown) {
  const rows = editorSectionRows(section);
  rows[index] = {
    ...(rows[index] ?? {}),
    [fieldKey]: value,
  };
  setSectionRows(section, rows);
}

function resolveRowEditorComponent(field: MetadataFieldDefinition) {
  switch (field.componentType) {
    case "SWITCH":
      return ElSwitch;
    case "SELECT":
      return ElSelect;
    case "NUMBER":
      return ElInputNumber;
    default:
      return ElInput;
  }
}

function resolveRowEditorProps(field: MetadataFieldDefinition) {
  switch (field.componentType) {
    case "SWITCH":
      return {
        inlinePrompt: true,
        activeText: t("common.on"),
        inactiveText: t("common.off"),
      };
    case "SELECT":
      return {
        clearable: true,
        filterable: true,
        placeholder: field.placeholder ?? field.fieldName,
      };
    case "NUMBER":
      return {
        controlsPosition: "right",
      };
    case "TEXTAREA":
    case "JSON_EDITOR":
    case "SQL_EDITOR":
    case "CODE_EDITOR":
      return {
        type: "textarea",
        rows: 2,
        placeholder: field.placeholder ?? field.fieldName,
      };
    default:
      return {
        placeholder: field.placeholder ?? field.fieldName,
      };
  }
}

function formatDisplayValue(value: unknown) {
  if (value === null || value === undefined || value === "") {
    return "-";
  }
  if (typeof value === "boolean") {
    return value ? t("common.yes") : t("common.no");
  }
  if (Array.isArray(value)) {
    return value.length === 0 ? "-" : JSON.stringify(value);
  }
  if (typeof value === "object") {
    try {
      return JSON.stringify(value);
    } catch (error) {
      return String(value);
    }
  }
  return String(value);
}

function applyModelSchemaContext(options: { resetMetadata?: boolean; forceKind?: boolean } = {}) {
  const schema = selectedModelSchema.value;
  if (schema) {
    modelForm.schemaVersionId = schema.currentVersionId ?? schema.id;
  }
  const technicalDefaultFields = (schema?.fields ?? []).filter((field) => field.scope !== "BUSINESS");
  if (options.resetMetadata) {
    modelForm.technicalMetadata = buildDefaultMetadata(technicalDefaultFields);
  } else {
    modelForm.technicalMetadata = mergeMetadataDefaults(modelForm.technicalMetadata, technicalDefaultFields);
  }
  for (const section of buildTechnicalSections(editorDatasource.value?.typeCode, modelForm.schemaVersionId)) {
    if (section.displayMode === "MULTIPLE") {
      setSectionRows(section, editorSectionRows(section));
    }
  }
  if ((options.forceKind || !modelForm.modelKind) && editorDatasource.value) {
    modelForm.modelKind = deriveModelKindFromSchema(schema, editorDatasource.value);
  }
  applyBusinessMetadataDefaults();
}

function applyBusinessMetadataDefaults() {
  modelForm.businessMetadata = ensureBusinessMetaModelEntries(
    modelForm.businessMetadata,
    editorBusinessSections.value.map((section) => section.schema),
  );
  for (const section of editorBusinessSections.value) {
    if (section.displayMode === "MULTIPLE") {
      setSectionRows(section, editorSectionRows(section));
      continue;
    }
    updateSectionModelValue(section, {
      ...buildDefaultMetadata(section.fields),
      ...sectionModelValue(section, true),
    });
  }
}

function resetModelForm(prefillDatasourceId?: EntityId) {
  modelForm.id = undefined;
  modelForm.datasourceId = prefillDatasourceId;
  modelForm.name = "";
  modelForm.physicalLocator = "";
  modelForm.modelKind = undefined;
  modelForm.schemaVersionId = undefined;
  modelForm.technicalMetadata = {};
  modelForm.businessMetadata = {};
  if (prefillDatasourceId) {
    applyModelSchemaContext({ resetMetadata: true, forceKind: true });
  }
}

function normalizeQueryGroupsForDatasource() {
  const availableCodes = new Set(querySchemaOptions.value.map((schema) => schema.schemaCode));
  queryGroups.value = queryGroups.value.filter((group) => !group.metaSchemaCode || availableCodes.has(group.metaSchemaCode));
}

function appendQueryGroup() {
  queryGroups.value.push(createQueryGroup());
}

function removeQueryGroup(groupKey: string) {
  queryGroups.value = queryGroups.value.filter((group) => group.key !== groupKey);
}

function handleQuerySchemaChange(group: ModelQueryGroupState) {
  const schema = findQuerySchema(group.metaSchemaCode);
  group.rowMatchMode = schema && parseMetaModelSchema(schema).config.displayMode === "MULTIPLE" ? "SAME_ITEM" : "ANY_ITEM";
  group.conditions = [createDefaultQueryCondition(schema)];
}

function appendQueryCondition(group: ModelQueryGroupState) {
  group.conditions.push(createDefaultQueryCondition(findQuerySchema(group.metaSchemaCode)));
}

function removeQueryCondition(group: ModelQueryGroupState, index: number) {
  group.conditions.splice(index, 1);
  if (group.conditions.length === 0) {
    group.conditions.push(createDefaultQueryCondition(findQuerySchema(group.metaSchemaCode)));
  }
}

function handleQueryFieldChange(group: ModelQueryGroupState, condition: ModelQueryConditionState) {
  const field = queryConditionField(group, condition);
  condition.operator = defaultQueryOperator(field);
  condition.value = undefined;
  condition.valueTo = undefined;
  condition.multiValueText = "";
}

function parseQueryValue(value: unknown, field?: MetadataFieldDefinition) {
  if (value === undefined || value === null || (typeof value === "string" && value.trim() === "")) {
    return undefined;
  }
  if (field?.valueType === "BOOLEAN") {
    if (typeof value === "boolean") {
      return value;
    }
    return String(value).trim().toLowerCase() === "true";
  }
  if (isNumericQueryField(field)) {
    const numberValue = Number(value);
    return Number.isNaN(numberValue) ? undefined : numberValue;
  }
  return value;
}

function filterModelsBySelectedDatasourceType(items: DataModelDefinition[]) {
  if (!selectedDatasourceType.value) {
    return items;
  }
  const expectedType = normalizeTypeCode(selectedDatasourceType.value);
  return items.filter((model) => normalizeTypeCode(findDatasourceById(model.datasourceId)?.typeCode) === expectedType);
}

function buildModelQueryRequest(): DataModelQueryRequest {
  const groups: DataModelQueryGroup[] = [];
  for (const group of queryGroups.value) {
    const schema = findQuerySchema(group.metaSchemaCode);
    if (!schema) {
      continue;
    }
    const conditions: DataModelQueryCondition[] = [];
    for (const condition of group.conditions) {
      const field = queryConditionField(group, condition);
      if (!field || !condition.fieldKey || !condition.operator) {
        continue;
      }
      if (condition.operator === "IN") {
        const values = condition.multiValueText
          .split(",")
          .map((item) => parseQueryValue(item.trim(), field))
          .filter((item) => item !== undefined);
        if (values.length === 0) {
          continue;
        }
        conditions.push({
          fieldKey: condition.fieldKey,
          operator: condition.operator,
          values,
        });
        continue;
      }
      if (condition.operator === "BETWEEN") {
        const lower = parseQueryValue(condition.value, field);
        const upper = parseQueryValue(condition.valueTo, field);
        if (lower === undefined || upper === undefined) {
          continue;
        }
        conditions.push({
          fieldKey: condition.fieldKey,
          operator: condition.operator,
          values: [lower, upper],
        });
        continue;
      }
      const value = parseQueryValue(condition.value, field);
      if (value === undefined) {
        continue;
      }
      conditions.push({
        fieldKey: condition.fieldKey,
        operator: condition.operator,
        value,
      });
    }
    if (conditions.length === 0) {
      continue;
    }
    groups.push({
      scope: parseMetaModelSchema(schema).config.domain as DataModelQueryGroup["scope"],
      metaSchemaCode: schema.schemaCode,
      rowMatchMode: group.rowMatchMode,
      conditions,
    });
  }
  return {
    datasourceId: selectedDatasourceId.value,
    groups,
  };
}

async function searchModels() {
  await handleDatasourceChange();
}

async function resetQueryFilters() {
  queryGroups.value = [];
  await handleDatasourceChange();
}

function openCreateDialog() {
  const prefillDatasourceId =
    selectedDatasource.value && !isDatabaseDatasourceType(selectedDatasource.value.typeCode) ? selectedDatasource.value.id : undefined;
  resetModelForm(prefillDatasourceId);
  editorOpen.value = true;
}

function editModel(model: DataModelDefinition) {
  modelForm.id = model.id;
  modelForm.datasourceId = model.datasourceId;
  modelForm.name = model.name;
  modelForm.physicalLocator = model.physicalLocator;
  modelForm.modelKind = model.modelKind;
  modelForm.schemaVersionId = model.schemaVersionId;
  modelForm.technicalMetadata = cloneDeep(model.technicalMetadata ?? {});
  modelForm.businessMetadata = cloneDeep(model.businessMetadata ?? {});
  applyModelSchemaContext({ resetMetadata: false, forceKind: false });
  editorOpen.value = true;
}

function handleModelDatasourceChange() {
  modelForm.schemaVersionId = undefined;
  modelForm.modelKind = undefined;
  applyModelSchemaContext({ resetMetadata: true, forceKind: true });
}

function handleModelSchemaChange() {
  applyModelSchemaContext({ resetMetadata: false, forceKind: true });
}

function openSyncDialog() {
  syncForm.datasourceType = "";
  syncForm.datasourceId = undefined;
  syncSelectedLocators.value = [];
  syncDialogOpen.value = true;
}

function handleSyncDatasourceTypeChange() {
  syncForm.datasourceId = undefined;
  syncSelectedLocators.value = [];
}

function handleSyncDatasourceChange() {
  syncSelectedLocators.value = [];
}

function openCreateSyncTaskDialog() {
  syncTaskForm.datasourceType = "";
  syncTaskForm.datasourceId = undefined;
  syncTaskForm.selectedLocators = [];
  syncTaskDialogOpen.value = true;
}

function handleSyncTaskFormDatasourceTypeChange() {
  syncTaskForm.datasourceId = undefined;
  syncTaskForm.selectedLocators = [];
}

function handleSyncTaskFormDatasourceChange() {
  syncTaskForm.selectedLocators = [];
}

function handleSyncTaskDatasourceTypeChange() {
  syncTaskFilters.datasourceId = undefined;
  syncTaskPagination.page = 1;
  void loadSyncTasks();
}

function handleSyncTaskPageSizeChange(pageSize: number) {
  syncTaskPagination.pageSize = pageSize;
  syncTaskPagination.page = 1;
  void loadSyncTasks();
}

async function loadSyncTasks() {
  if (!authStore.currentProjectId) {
    syncTasks.value = [];
    syncTaskTotal.value = 0;
    return;
  }
  loadingSyncTasksPage.value = true;
  try {
    const result = await studioApi.modelSyncTasks.list({
      pageNo: syncTaskPagination.page,
      pageSize: syncTaskPagination.pageSize,
      datasourceType: syncTaskFilters.datasourceType || undefined,
      datasourceId: syncTaskFilters.datasourceId,
      status: syncTaskFilters.status || undefined,
    });
    syncTasks.value = result.items;
    syncTaskTotal.value = result.total;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载同步任务失败");
  } finally {
    loadingSyncTasksPage.value = false;
  }
}

async function loadIndexQueueStatus(showError = false) {
  try {
    indexQueueStatus.value = await studioApi.models.indexQueueStatus();
  } catch (error) {
    if (showError) {
      ElMessage.error(error instanceof Error ? error.message : "加载索引队列状态失败");
    }
  }
}

function canStopSyncTask(task: ModelSyncTaskView) {
  return ["PENDING", "RUNNING", "STOPPING"].includes(String(task.status || "").toUpperCase());
}

function canDeleteSyncTask(task: ModelSyncTaskView) {
  return ["SUCCESS", "FAILED", "STOPPED"].includes(String(task.status || "").toUpperCase());
}

function openSyncTaskDetail(task: ModelSyncTaskView) {
  if (!task.id) {
    return;
  }
  router.push({
    name: "model-sync-task-detail",
    params: { taskId: String(task.id) },
  });
}

async function stopSyncTask(task: ModelSyncTaskView) {
  if (!task.id || !canStopSyncTask(task)) {
    return;
  }
  try {
    await studioApi.modelSyncTasks.stop(task.id);
    ElMessage.success("已请求停止同步任务");
    await loadSyncTasks();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "停止同步任务失败");
  }
}

async function deleteSyncTask(task: ModelSyncTaskView) {
  if (!task.id || !canDeleteSyncTask(task)) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      `删除同步任务“${task.name}”吗？已同步的模型不会回滚。`,
      t("common.confirm"),
      { type: "warning" },
    );
    await studioApi.modelSyncTasks.delete(task.id);
    ElMessage.success("同步任务已删除");
    await loadSyncTasks();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : "删除同步任务失败");
    }
  }
}

function formatDurationMs(durationMs?: number) {
  if (durationMs == null || Number.isNaN(Number(durationMs))) {
    return t("common.none");
  }
  if (durationMs < 1000) {
    return `${durationMs} ms`;
  }
  const seconds = durationMs / 1000;
  if (seconds < 60) {
    return `${seconds.toFixed(seconds >= 10 ? 1 : 2)} s`;
  }
  const minutes = Math.floor(seconds / 60);
  const remainderSeconds = Math.round(seconds % 60);
  return `${minutes}m ${remainderSeconds}s`;
}

async function loadPage() {
  try {
    const [datasourceData, schemaData] = await Promise.all([
      studioApi.datasources.list(),
      studioApi.metaSchemas.list(),
    ]);
    datasources.value = datasourceData;
    schemas.value = schemaData;
    if (selectedDatasourceType.value && !queryDatasourceTypes.value.some((item) => normalizeTypeCode(item) === normalizeTypeCode(selectedDatasourceType.value))) {
      selectedDatasourceType.value = "";
    }
    if (selectedDatasourceId.value && !datasourceData.some((item) => sameId(item.id, selectedDatasourceId.value))) {
      selectedDatasourceId.value = undefined;
      models.value = [];
      selectedModel.value = undefined;
      previewRows.value = [];
    }
    if (selectedModel.value?.datasourceId && !sameId(selectedDatasourceId.value, selectedModel.value.datasourceId)) {
      selectedDatasourceId.value = selectedModel.value.datasourceId;
    }
    const activeDatasource = findDatasourceById(selectedDatasourceId.value);
    if (activeDatasource?.typeCode) {
      selectedDatasourceType.value = activeDatasource.typeCode;
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.models.loadDatasourcesFailed"));
  }
}

async function loadModelsForSelectedDatasource() {
  selectedModel.value = undefined;
  previewRows.value = [];
  const queryRequest = buildModelQueryRequest();
  const loadedModels = queryRequest.groups.length > 0
    ? await studioApi.models.query(queryRequest)
    : (selectedDatasourceId.value
      ? await studioApi.models.listByDatasource(selectedDatasourceId.value)
      : await studioApi.models.list());
  models.value = filterModelsBySelectedDatasourceType(loadedModels);
  resetModelPagination();
}

async function handleDatasourceTypeChange() {
  if (selectedDatasourceId.value) {
    const datasource = findDatasourceById(selectedDatasourceId.value);
    if (datasource && selectedDatasourceType.value && normalizeTypeCode(datasource.typeCode) !== normalizeTypeCode(selectedDatasourceType.value)) {
      selectedDatasourceId.value = undefined;
    }
  }
  await handleDatasourceChange();
}

async function handleDatasourceChange() {
  try {
    const datasource = findDatasourceById(selectedDatasourceId.value);
    if (datasource?.typeCode) {
      selectedDatasourceType.value = datasource.typeCode;
    }
    normalizeQueryGroupsForDatasource();
    await loadModelsForSelectedDatasource();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.models.loadModelsFailed"));
  }
}

async function refreshModels() {
  await loadPage();
  await loadIndexQueueStatus();
  await handleDatasourceChange();
}

async function rebuildQueryIndex() {
  try {
    await studioApi.models.rebuildIndex(selectedDatasourceId.value);
    ElMessage.success(t("web.models.rebuildIndexSuccess"));
    await handleDatasourceChange();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.models.rebuildIndexFailed"));
  }
}

function resolveDatasourceLabel(datasourceId?: EntityId) {
  const datasource = findDatasourceById(datasourceId);
  if (!datasource) {
    return "-";
  }
  return `${datasource.name} (${datasource.typeCode})`;
}

async function selectModel(model: DataModelDefinition) {
  selectedModel.value = model;
  if (model.datasourceId) {
    selectedDatasourceId.value = model.datasourceId;
  }
  const datasource = findDatasourceById(model.datasourceId);
  if (datasource?.typeCode) {
    selectedDatasourceType.value = datasource.typeCode;
  }
  if (!model.id) {
    previewRows.value = [];
    return;
  }
  try {
    previewRows.value = await studioApi.models.preview(model.id);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.models.previewFailed"));
  }
}

function openModelDetail(model: DataModelDefinition, edit = false) {
  if (!model.id) {
    return;
  }
  router.push({
    name: "model-detail",
    params: { modelId: String(model.id) },
    query: edit ? { edit: "1" } : undefined,
  });
}

function openStatisticsWorkspace(datasourceId?: EntityId) {
  const activeDatasource = datasourceId == null ? selectedDatasource.value : findDatasourceById(datasourceId);
  router.push({
    name: "statistics",
    query: {
      ...(datasourceId == null ? {} : { datasourceId: String(datasourceId) }),
      ...(activeDatasource?.typeCode ? { datasourceType: activeDatasource.typeCode } : {}),
    },
  });
}

function goBackToList() {
  editorOpen.value = false;
  router.push({ name: "models" });
}

function openDetailEdit() {
  if (!selectedModel.value) {
    return;
  }
  editModel(selectedModel.value);
}

async function loadModelDetail() {
  if (!detailModelId.value) {
    selectedModel.value = undefined;
    previewRows.value = [];
    return;
  }
  try {
    const model = await studioApi.models.get(detailModelId.value);
    await selectModel(model);
    if (route.query.edit === "1") {
      editModel(model);
      const nextQuery = { ...route.query };
      delete nextQuery.edit;
      router.replace({ name: "model-detail", params: { modelId: String(detailModelId.value) }, query: nextQuery });
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.models.loadModelDetailFailed"));
  }
}

async function refreshDetail() {
  await loadPage();
  await loadModelDetail();
}

async function saveModel() {
  if (!modelForm.datasourceId) {
    ElMessage.warning(t("web.models.saveSelectDatasourceFirst"));
    return;
  }
  if (!isEditingModel.value && editorDatasource.value && isDatabaseDatasourceType(editorDatasource.value.typeCode)) {
    ElMessage.warning(t("web.models.manualDatasourceOnly"));
    return;
  }
  if (!isEditingModel.value && !selectedModelSchema.value) {
    ElMessage.warning(t("web.models.noModelSchema"));
    return;
  }
  saving.value = true;
  try {
    const payload: DataModelSaveRequest = {
      id: modelForm.id,
      datasourceId: modelForm.datasourceId,
      name: modelForm.name,
      physicalLocator: modelForm.physicalLocator,
      modelKind: modelForm.modelKind,
      schemaVersionId: modelForm.schemaVersionId,
      technicalMetadata: cloneDeep(modelForm.technicalMetadata),
      businessMetadata: cloneDeep(modelForm.businessMetadata),
    };
    const saved = await studioApi.models.save(payload);
    editorOpen.value = false;
    selectedDatasourceId.value = saved.datasourceId;
    const datasource = findDatasourceById(saved.datasourceId);
    if (datasource?.typeCode) {
      selectedDatasourceType.value = datasource.typeCode;
    }
    ElMessage.success(t("web.models.saveSuccess"));
    if (isDetailPage.value) {
      goBackToList();
    } else {
      await handleDatasourceChange();
      const current = models.value.find((item) => sameId(item.id, saved.id));
      if (current) {
        await selectModel(current);
      }
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.models.saveFailed"));
  } finally {
    saving.value = false;
  }
}

async function submitSync() {
  if (!syncForm.datasourceId) {
    ElMessage.warning(t("web.models.syncSelectDatasourceFirst"));
    return;
  }
  if (syncSelectedLocators.value.length === 0) {
    ElMessage.warning(t("web.models.syncNoSelection"));
    return;
  }
  if (syncSelectedLocators.value.length > 20) {
    try {
      await ElMessageBox.confirm(
        "当前选择的表超过 20 张，页面同步耗时较长，将自动创建模型同步任务并立即执行。",
        t("common.confirm"),
        { type: "warning" },
      );
      const created = await studioApi.modelSyncTasks.create({
        datasourceId: syncForm.datasourceId,
        physicalLocators: cloneDeep(syncSelectedLocators.value),
        source: "AUTO_PAGE",
      });
      syncDialogOpen.value = false;
      activeListTab.value = "sync-tasks";
      await loadSyncTasks();
      if (created.id) {
        openSyncTaskDetail(created);
      }
      return;
    } catch (error) {
      if (error !== "cancel") {
        ElMessage.error(error instanceof Error ? error.message : "创建同步任务失败");
      }
      return;
    }
  }
  syncing.value = true;
  try {
    selectedDatasourceId.value = syncForm.datasourceId;
    const datasource = findDatasourceById(syncForm.datasourceId);
    if (datasource?.typeCode) {
      selectedDatasourceType.value = datasource.typeCode;
    }
    models.value = await studioApi.models.syncSelected(syncForm.datasourceId, {
      physicalLocators: cloneDeep(syncSelectedLocators.value),
    });
    selectedModel.value = undefined;
    previewRows.value = [];
    syncDialogOpen.value = false;
    ElMessage.success(t("web.models.syncSuccess"));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.models.syncFailed"));
  } finally {
    syncing.value = false;
  }
}

async function submitSyncTaskCreate() {
  if (!syncTaskForm.datasourceId) {
    ElMessage.warning("请先选择数据源");
    return;
  }
  if (syncTaskForm.selectedLocators.length === 0) {
    ElMessage.warning(t("web.models.syncNoSelection"));
    return;
  }
  creatingSyncTask.value = true;
  try {
    const created = await studioApi.modelSyncTasks.create({
      datasourceId: syncTaskForm.datasourceId,
      physicalLocators: cloneDeep(syncTaskForm.selectedLocators),
      source: "MANUAL",
    });
    syncTaskDialogOpen.value = false;
    activeListTab.value = "sync-tasks";
    await loadSyncTasks();
    if (created.id) {
      openSyncTaskDetail(created);
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "创建同步任务失败");
  } finally {
    creatingSyncTask.value = false;
  }
}

async function deleteModel(model: DataModelDefinition) {
  if (!model.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("web.models.deleteConfirmMessage", { name: model.name }),
      t("common.confirm"),
      { type: "warning" },
    );
    await studioApi.models.delete(model.id);
    if (selectedModel.value?.id === model.id) {
      selectedModel.value = undefined;
      previewRows.value = [];
    }
    ElMessage.success(t("web.models.deleteSuccess"));
    if (isDetailPage.value && sameId(detailModelId.value, model.id)) {
      goBackToList();
      return;
    }
    await handleDatasourceChange();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : t("web.models.deleteFailed"));
    }
  }
}

function buildModelActions(model: DataModelDefinition) {
  const shared = isSharedModel(model);
  return [
    {
      key: "statistics",
      label: t("common.statistics"),
      onClick: () => openStatisticsWorkspace(model.datasourceId),
    },
    {
      key: "edit",
      label: t("common.edit"),
      type: "primary",
      disabled: shared,
      onClick: () => openModelDetail(model, true),
    },
    {
      key: "delete",
      label: t("common.delete"),
      type: "danger",
      disabled: shared,
      onClick: () => deleteModel(model),
    },
  ];
}

function normalizeListTab(value: unknown) {
  return value === "sync-tasks" ? "sync-tasks" : "models";
}

watch(
  () => route.query.tab,
  (value) => {
    if (!isDetailPage.value) {
      activeListTab.value = normalizeListTab(value);
    }
  },
  { immediate: true },
);

watch(
  activeListTab,
  async (value) => {
    if (isDetailPage.value) {
      return;
    }
    const normalized = normalizeListTab(value);
    if (route.query.tab !== normalized) {
      await router.replace({
        name: "models",
        query: normalized === "sync-tasks" ? { tab: normalized } : {},
      });
    }
    if (normalized === "sync-tasks") {
      await loadSyncTasks();
      return;
    }
  },
  { immediate: true },
);

watch(
  () => [route.params.modelId, route.fullPath, authStore.currentTenantId, authStore.currentProjectId],
  async () => {
    await loadPage();
    if (isDetailPage.value) {
      await loadModelDetail();
      return;
    }
    await loadIndexQueueStatus();
    if (activeListTab.value === "sync-tasks") {
      await loadSyncTasks();
      return;
    }
    await handleDatasourceChange();
  },
  { immediate: true },
);
</script>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}

.models-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.index-queue-card {
  min-width: 280px;
  max-width: 360px;
  display: grid;
  gap: 12px;
}

.index-queue-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.index-queue-card__metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.index-queue-card__metric {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(248, 250, 252, 0.92);
  border: 1px solid rgba(148, 163, 184, 0.18);
}

.index-queue-card__metric-label {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.index-queue-card__metric-value {
  color: var(--studio-text);
  font-size: 20px;
  line-height: 1.1;
}

.models-tabs {
  display: grid;
  gap: 12px;
}

.models-toolbar__filter {
  min-width: 280px;
}

.sync-task-progress {
  display: grid;
  gap: 6px;
}

.sync-task-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.model-query-panel {
  display: grid;
  gap: 10px;
  margin-bottom: 12px;
}

.model-query-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.model-query-panel__header p,
.model-meta-section__header p {
  display: none;
}

.model-query-panel__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.model-query-group {
  display: grid;
  gap: 12px;
}

.model-query-group__header {
  display: grid;
  grid-template-columns: minmax(280px, 1.4fr) minmax(220px, 0.9fr) auto;
  align-items: end;
  gap: 10px;
}

.model-query-group__meta {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.model-query-group__meta--compact {
  max-width: 320px;
}

.model-query-group__label,
.model-query-condition__label {
  color: var(--studio-text-soft);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.model-query-group__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.model-query-group__conditions {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(560px, 1fr));
  gap: 10px;
}

.model-query-condition {
  min-width: 0;
}

.model-query-condition__line {
  display: grid;
  grid-template-columns: minmax(180px, 1.2fr) minmax(130px, 0.8fr) minmax(220px, 1.5fr) auto;
  align-items: end;
  gap: 10px;
}

.model-query-condition__segment {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.model-query-condition__segment--operator {
  max-width: 180px;
}

.model-query-condition__segment--value {
  min-width: 0;
}

.model-query-condition__remove {
  align-self: end;
  white-space: nowrap;
}

.query-condition-value {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.query-condition-value__input {
  flex: 1 1 0;
  min-width: 0;
}

.query-condition-value__divider {
  flex: 0 0 auto;
  color: var(--studio-text-soft);
}

.model-query-condition :deep(.el-select),
.model-query-condition :deep(.el-input),
.model-query-condition :deep(.el-input-number) {
  width: 100%;
}

.detail-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}

.detail-toolbar__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}

.preview-head p {
  margin: 4px 0 0;
  display: block;
}

.preview-head__tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.stack-cell {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.cell-subtle {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.model-section-stack {
  display: grid;
  gap: 10px;
}

.model-meta-section,
.sample-panel {
  display: grid;
  gap: 10px;
}

.model-meta-section__header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.model-meta-section__tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.model-field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.model-field-grid__item {
  border: 1px solid var(--studio-border);
  border-radius: 14px;
  padding: 10px 12px;
  background: rgba(255, 255, 255, 0.8);
}

.model-field-grid__label {
  display: block;
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--studio-text-soft);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.model-field-grid__value {
  color: var(--studio-text);
  word-break: break-word;
}

.empty-hint,
.warning-hint,
.editor-description,
.dialog-description {
  margin-bottom: 12px;
}

.section-empty {
  margin-bottom: 0;
}

.warning-hint {
  border-color: rgba(36, 99, 235, 0.2);
  background: rgba(219, 234, 254, 0.7);
}

.sync-table-panel {
  display: grid;
  gap: 10px;
}

.sync-table-panel__header {
  display: grid;
  gap: 8px;
}

.sync-table-panel__title {
  color: var(--studio-text);
}

.sync-table-panel__title strong {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  font-size: 14px;
  font-weight: 700;
}

.sync-table-panel__search {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--studio-border);
  border-radius: 14px;
  background: rgba(248, 250, 252, 0.92);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.6);
}

.sync-table-panel__search-input {
  width: 100%;
}

.sync-table-panel__search-action {
  min-width: 96px;
}

.multiple-section-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 10px;
}

.drawer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 14px;
}

.business-schema-selector {
  margin-bottom: 0;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

@media (max-width: 980px) {
  .model-field-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .models-toolbar__filter {
    min-width: 100%;
  }

  .index-queue-card {
    min-width: 100%;
    max-width: none;
  }

  .index-queue-card__header {
    flex-direction: column;
  }

  .model-query-panel__header {
    flex-direction: column;
  }

  .model-query-group__header {
    grid-template-columns: minmax(0, 1fr);
  }

  .model-query-group__actions {
    justify-content: flex-start;
  }

  .model-query-group__conditions {
    grid-template-columns: minmax(0, 1fr);
  }

  .model-query-condition__line {
    grid-template-columns: minmax(0, 1fr);
  }

  .model-query-condition__segment--operator {
    max-width: none;
  }

  .detail-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .detail-toolbar__actions {
    justify-content: flex-start;
  }

  .model-meta-section__header {
    flex-direction: column;
  }

  .model-meta-section__tags {
    justify-content: flex-start;
  }

  .table-pagination {
    justify-content: flex-start;
  }

  .sync-table-panel__search {
    grid-template-columns: 1fr;
  }

  .sync-table-panel__search-input {
    width: 100%;
  }

  .sync-table-panel__search-action {
    width: 100%;
  }
}
</style>
