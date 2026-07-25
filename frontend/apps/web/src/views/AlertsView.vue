<template>
  <div class="studio-page alert-center-page">
    <section class="alert-header">
      <div>
        <h3>{{ t("web.alerts.heading") }}</h3>
        <p>{{ t("web.alerts.description") }}</p>
      </div>
      <div class="alert-header__actions">
        <el-button v-if="options.canViewTenantSummary" :icon="DataAnalysis" @click="openTenantSummary">{{ t("web.alerts.tenantSummary") }}</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="loadAll">{{ t("common.refresh") }}</el-button>
      </div>
    </section>

    <section v-loading="summaryLoading" class="alert-summary-band">
      <button v-for="item in summaryItems" :key="item.key" class="alert-summary-item" type="button" @click="applySummaryFilter(item.key)">
        <span>{{ item.label }}</span>
        <strong :class="`is-${item.tone}`">{{ item.value }}</strong>
      </button>
    </section>

    <el-tabs v-model="activeTab" class="alert-tabs" @tab-change="handleTabChange">
      <el-tab-pane :label="t('web.alerts.incidentTab')" name="incidents">
        <section class="alert-toolbar alert-toolbar--incidents">
          <el-input v-model="incidentFilters.keyword" clearable :placeholder="t('web.alerts.searchPlaceholder')" @keyup.enter="searchIncidents()" />
          <el-select v-model="incidentFilters.status" clearable :placeholder="t('common.status')">
            <el-option v-for="status in options.incidentStatuses" :key="status" :label="statusLabel(status)" :value="status" />
          </el-select>
          <el-select v-model="incidentFilters.severity" clearable :placeholder="t('web.alerts.severity')">
            <el-option v-for="severity in options.severities" :key="severity" :label="severityLabel(severity)" :value="severity" />
          </el-select>
          <el-select v-model="incidentFilters.ruleType" clearable :placeholder="t('web.alerts.ruleType')">
            <el-option v-for="rule in options.ruleTypes" :key="rule.code" :label="ruleTypeLabel(rule.code)" :value="rule.code" />
          </el-select>
          <el-select v-model="incidentFilters.requestedClusterId" clearable filterable :placeholder="t('web.runtimeClusterSelection.targetCluster')">
            <el-option v-for="item in runtimeClusters" :key="`target-${String(item.id)}`" :label="formatRuntimeClusterLabel(runtimeClusters, item.id, item.code)" :value="item.id" />
          </el-select>
          <el-select v-model="incidentFilters.actualClusterId" clearable filterable :placeholder="t('web.runtimeClusterSelection.actualCluster')">
            <el-option v-for="item in runtimeClusters" :key="`actual-${String(item.id)}`" :label="formatRuntimeClusterLabel(runtimeClusters, item.id, item.code)" :value="item.id" />
          </el-select>
          <el-button type="primary" :icon="Search" @click="searchIncidents()">{{ t("common.search") }}</el-button>
          <el-button @click="resetIncidentFilters">{{ t("common.reset") }}</el-button>
        </section>

        <el-table v-loading="incidentLoading" :data="incidents" border size="small" class="alert-table" @row-dblclick="openIncident">
          <el-table-column prop="severity" :label="t('web.alerts.severity')" width="105">
            <template #default="{ row }"><el-tag :type="severityTag(row.severity)">{{ severityLabel(row.severity) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="status" :label="t('common.status')" width="125">
            <template #default="{ row }"><el-tag :type="incidentStatusTag(row.status)">{{ statusLabel(row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="ruleName" :label="t('web.alerts.rule')" min-width="170" show-overflow-tooltip />
          <el-table-column prop="subjectName" :label="t('web.alerts.subject')" min-width="180" show-overflow-tooltip />
          <el-table-column :label="t('web.runtimeClusterSelection.runtimePlacement')" min-width="220">
            <template #default="{ row }">
              <div class="delivery-cell">
                <span class="delivery-cell__title">{{ t("web.runtimeClusterSelection.targetCluster") }}: {{ incidentClusterLabel(row, "target") }}</span>
                <span class="delivery-cell__meta">{{ t("web.runtimeClusterSelection.actualCluster") }}: {{ incidentClusterLabel(row, "actual") }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="summary" :label="t('web.alerts.summary')" min-width="260" show-overflow-tooltip />
          <el-table-column prop="occurrenceCount" :label="t('web.alerts.occurrences')" width="90" />
          <el-table-column prop="lastTriggeredAt" :label="t('web.alerts.lastTriggeredAt')" min-width="165" />
          <el-table-column :label="t('common.actions')" width="245" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" :icon="View" @click="openIncident(row)">{{ t("web.alerts.detail") }}</el-button>
              <el-button v-if="row.targetPath" link :icon="Promotion" @click="openTarget(row.targetPath)">{{ t("web.alerts.openTarget") }}</el-button>
              <el-button v-if="row.status === 'OPEN'" link type="warning" :icon="Check" @click="acknowledgeIncident(row)">{{ t("web.alerts.acknowledge") }}</el-button>
              <el-button v-if="row.status !== 'CLOSED'" link type="danger" :icon="Close" @click="closeIncident(row)">{{ t("web.alerts.closeIncident") }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="alert-pagination">
          <el-pagination v-model:current-page="incidentFilters.pageNo" v-model:page-size="incidentFilters.pageSize" layout="total, prev, pager, next" :total="incidentTotal" @current-change="loadIncidents" />
        </div>
      </el-tab-pane>

      <el-tab-pane :label="t('web.alerts.ruleTab')" name="rules">
        <section class="alert-toolbar alert-toolbar--rules">
          <el-input v-model="ruleFilters.keyword" clearable :placeholder="t('web.alerts.searchRulePlaceholder')" @keyup.enter="searchRules" />
          <el-select v-model="ruleFilters.ruleType" clearable :placeholder="t('web.alerts.ruleType')">
            <el-option v-for="rule in options.ruleTypes" :key="rule.code" :label="ruleTypeLabel(rule.code)" :value="rule.code" />
          </el-select>
          <el-select v-model="ruleFilters.enabled" clearable :placeholder="t('web.alerts.enabledState')">
            <el-option :label="t('web.alerts.enabled')" :value="true" />
            <el-option :label="t('web.alerts.disabled')" :value="false" />
          </el-select>
          <el-button type="primary" :icon="Search" @click="searchRules">{{ t("common.search") }}</el-button>
          <el-button @click="resetRuleFilters">{{ t("common.reset") }}</el-button>
          <el-button v-if="options.canManage" type="primary" :icon="Plus" @click="openRuleDialog()">{{ t("web.alerts.createRule") }}</el-button>
        </section>

        <el-table v-loading="ruleLoading" :data="rules" border size="small" class="alert-table">
          <el-table-column prop="name" :label="t('web.alerts.ruleName')" min-width="180" show-overflow-tooltip />
          <el-table-column prop="ruleType" :label="t('web.alerts.ruleType')" min-width="170">
            <template #default="{ row }">{{ ruleTypeLabel(row.ruleType) }}</template>
          </el-table-column>
          <el-table-column prop="subjectName" :label="t('web.alerts.scope')" min-width="180">
            <template #default="{ row }">{{ row.subjectName || t("web.alerts.allSubjects") }}</template>
          </el-table-column>
          <el-table-column prop="severity" :label="t('web.alerts.severity')" width="105">
            <template #default="{ row }"><el-tag :type="severityTag(row.severity)">{{ severityLabel(row.severity) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="enabled" :label="t('common.status')" width="100">
            <template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? t("web.alerts.enabled") : t("web.alerts.disabled") }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="lastEvaluatedAt" :label="t('web.alerts.lastEvaluatedAt')" min-width="165" />
          <el-table-column prop="lastEvaluationStatus" :label="t('web.alerts.evaluation')" width="110">
            <template #default="{ row }"><el-tag :type="row.lastEvaluationStatus === 'ERROR' ? 'danger' : 'success'">{{ row.lastEvaluationStatus || '-' }}</el-tag></template>
          </el-table-column>
          <el-table-column :label="t('common.actions')" width="315" fixed="right">
            <template #default="{ row }">
              <el-button v-if="options.canManage" link type="primary" :icon="Edit" @click="openRuleDialog(row)">{{ t("common.edit") }}</el-button>
              <el-button v-if="options.canManage" link :icon="Connection" @click="testRule(row)">{{ t("web.alerts.test") }}</el-button>
              <el-button v-if="options.canManage" link :type="row.enabled ? 'warning' : 'success'" @click="toggleRule(row)">{{ row.enabled ? t("web.alerts.disable") : t("web.alerts.enable") }}</el-button>
              <el-button v-if="options.canManage" link type="danger" :icon="Delete" @click="deleteRule(row)">{{ t("common.delete") }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="alert-pagination">
          <el-pagination v-model:current-page="ruleFilters.pageNo" v-model:page-size="ruleFilters.pageSize" layout="total, prev, pager, next" :total="ruleTotal" @current-change="loadRules" />
        </div>
      </el-tab-pane>

      <el-tab-pane :label="t('web.alerts.channelTab')" name="channels">
        <section class="alert-toolbar alert-toolbar--channels">
          <el-input v-model="channelFilters.keyword" clearable :placeholder="t('web.alerts.searchChannelPlaceholder')" @keyup.enter="searchChannels" />
          <el-select v-model="channelFilters.enabled" clearable :placeholder="t('web.alerts.enabledState')">
            <el-option :label="t('web.alerts.enabled')" :value="true" />
            <el-option :label="t('web.alerts.disabled')" :value="false" />
          </el-select>
          <el-button type="primary" :icon="Search" @click="searchChannels">{{ t("common.search") }}</el-button>
          <el-button @click="resetChannelFilters">{{ t("common.reset") }}</el-button>
          <el-button v-if="options.canManage" type="primary" :icon="Plus" @click="openChannelDialog()">{{ t("web.alerts.createChannel") }}</el-button>
        </section>

        <el-table v-loading="channelLoading" :data="channels" border size="small" class="alert-table">
          <el-table-column prop="name" :label="t('web.alerts.channelName')" min-width="180" />
          <el-table-column prop="channelType" :label="t('web.alerts.channelType')" width="110">
            <template #default="{ row }"><el-tag type="info">{{ channelTypeLabel(row.channelType) }}</el-tag></template>
          </el-table-column>
          <el-table-column :label="t('web.alerts.channelTarget')" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">{{ channelTargetSummary(row) }}</template>
          </el-table-column>
          <el-table-column prop="headerNames" :label="t('web.alerts.headers')" min-width="160">
            <template #default="{ row }">{{ row.channelType !== 'ELINK' ? row.headerNames?.join(', ') || '-' : '-' }}</template>
          </el-table-column>
          <el-table-column prop="enabled" :label="t('common.status')" width="100">
            <template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? t("web.alerts.enabled") : t("web.alerts.disabled") }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="lastTestStatus" :label="t('web.alerts.lastTest')" width="120">
            <template #default="{ row }"><el-tag v-if="row.lastTestStatus" :type="row.lastTestStatus === 'SUCCEEDED' ? 'success' : 'danger'">{{ deliveryStatusLabel(row.lastTestStatus) }}</el-tag><span v-else>-</span></template>
          </el-table-column>
          <el-table-column prop="lastTestedAt" :label="t('web.alerts.lastTestedAt')" min-width="165" />
          <el-table-column :label="t('common.actions')" width="300" fixed="right">
            <template #default="{ row }">
              <el-button v-if="options.canManage" link type="primary" :icon="Edit" @click="openChannelDialog(row)">{{ t("common.edit") }}</el-button>
              <el-button v-if="options.canManage" link :icon="Connection" @click="testChannel(row)">{{ t("web.alerts.test") }}</el-button>
              <el-button v-if="options.canManage" link :type="row.enabled ? 'warning' : 'success'" @click="toggleChannel(row)">{{ row.enabled ? t("web.alerts.disable") : t("web.alerts.enable") }}</el-button>
              <el-button v-if="options.canManage" link type="danger" :icon="Delete" @click="deleteChannel(row)">{{ t("common.delete") }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="alert-pagination">
          <el-pagination v-model:current-page="channelFilters.pageNo" v-model:page-size="channelFilters.pageSize" layout="total, prev, pager, next" :total="channelTotal" @current-change="loadChannels" />
        </div>

        <section class="delivery-section">
          <div class="delivery-section__heading">
            <div><h4>{{ t("web.alerts.deliveryTitle") }}</h4><p>{{ t("web.alerts.deliveryDescription") }}</p></div>
            <el-select v-model="deliveryFilters.status" clearable :placeholder="t('common.status')" @change="searchDeliveries">
              <el-option v-for="status in options.deliveryStatuses" :key="status" :label="deliveryStatusLabel(status)" :value="status" />
            </el-select>
          </div>
          <el-table v-loading="deliveryLoading" :data="deliveries" border size="small" class="delivery-table" @row-dblclick="openDeliveryDetail">
            <el-table-column :label="t('web.alerts.deliveryAlertSource')" min-width="220">
              <template #default="{ row }">
                <div class="delivery-cell">
                  <div class="delivery-cell__headline">
                    <el-tag v-if="row.severity" size="small" :type="severityTag(row.severity)">{{ severityLabel(row.severity) }}</el-tag>
                    <span class="delivery-cell__title">{{ row.ruleName || t("web.alerts.unknownRule") }}</span>
                  </div>
                  <span class="delivery-cell__meta">{{ row.ruleType ? ruleTypeLabel(row.ruleType) : '-' }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column :label="t('web.alerts.deliveryTrigger')" min-width="220">
              <template #default="{ row }">
                <div class="delivery-cell">
                  <div class="delivery-cell__headline">
                    <el-tag v-if="row.eventType" size="small" type="info">{{ eventTypeLabel(row.eventType) }}</el-tag>
                    <span class="delivery-cell__title">{{ row.subjectName || t("web.alerts.unknownSubject") }}</span>
                  </div>
                  <span class="delivery-cell__meta">{{ row.subjectType ? subjectTypeLabel(row.subjectType) : '-' }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column :label="t('web.alerts.deliveryMessage')" min-width="260">
              <template #default="{ row }">
                <div class="delivery-cell">
                  <span class="delivery-cell__title">{{ deliveryMessageTitle(row) }}</span>
                  <span class="delivery-cell__preview">{{ deliveryMessagePreview(row) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column :label="t('web.alerts.deliveryDestination')" min-width="190">
              <template #default="{ row }">
                <div class="delivery-cell">
                  <div class="delivery-cell__headline">
                    <el-tag size="small" type="info">{{ channelTypeLabel(row.channelType) }}</el-tag>
                    <span class="delivery-cell__title">{{ row.channelName || channelTypeLabel(row.channelType) }}</span>
                  </div>
                  <span class="delivery-cell__preview">{{ deliveryRecipientLabel(row) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="status" :label="t('common.status')" width="118">
              <template #default="{ row }">
                <div class="delivery-status-cell">
                  <el-tag :type="deliveryStatusTag(row.status)">{{ deliveryStatusLabel(row.status) }}</el-tag>
                  <span>{{ t("web.alerts.deliveryAttemptSummary", { count: row.attemptCount || 0 }) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column :label="t('web.alerts.deliveryTime')" min-width="165">
              <template #default="{ row }">{{ deliveryDisplayTime(row) }}</template>
            </el-table-column>
            <el-table-column :label="t('common.actions')" width="150" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :icon="View" @click="openDeliveryDetail(row)">{{ t("web.alerts.detail") }}</el-button>
                <el-button v-if="options.canManage && ['DEAD', 'RETRY', 'SKIPPED'].includes(row.status || '')" link type="primary" :icon="Refresh" @click="retryDelivery(row)">{{ t("web.alerts.retry") }}</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="alert-pagination">
            <el-pagination v-model:current-page="deliveryFilters.pageNo" v-model:page-size="deliveryFilters.pageSize" layout="total, prev, pager, next" :total="deliveryTotal" @current-change="loadDeliveries" />
          </div>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="incidentDrawerVisible" :title="t('web.alerts.incidentDetail')" size="min(760px, 96vw)">
      <div v-if="selectedIncident" class="incident-detail">
        <div class="incident-detail__status">
          <el-tag :type="severityTag(selectedIncident.severity)">{{ severityLabel(selectedIncident.severity) }}</el-tag>
          <el-tag :type="incidentStatusTag(selectedIncident.status)">{{ statusLabel(selectedIncident.status) }}</el-tag>
          <span>{{ selectedIncident.ruleName }}</span>
        </div>
        <h4>{{ selectedIncident.summary }}</h4>
        <dl class="incident-detail__grid">
          <dt>{{ t("web.alerts.subject") }}</dt><dd>{{ selectedIncident.subjectName }}</dd>
          <dt>{{ t("web.runtimeClusterSelection.targetCluster") }}</dt><dd>{{ incidentClusterLabel(selectedIncident, "target") }}</dd>
          <dt>{{ t("web.runtimeClusterSelection.actualCluster") }}</dt><dd>{{ incidentClusterLabel(selectedIncident, "actual") }}</dd>
          <dt>{{ t("web.alerts.firstTriggeredAt") }}</dt><dd>{{ selectedIncident.firstTriggeredAt || '-' }}</dd>
          <dt>{{ t("web.alerts.lastTriggeredAt") }}</dt><dd>{{ selectedIncident.lastTriggeredAt || '-' }}</dd>
          <dt>{{ t("web.alerts.occurrences") }}</dt><dd>{{ selectedIncident.occurrenceCount || 0 }}</dd>
        </dl>
        <div class="incident-detail__actions">
          <el-button v-if="selectedIncident.targetPath" :icon="Promotion" @click="openTarget(selectedIncident.targetPath)">{{ t("web.alerts.openTarget") }}</el-button>
          <el-button v-if="selectedIncident.status === 'OPEN'" type="warning" :icon="Check" @click="acknowledgeIncident(selectedIncident)">{{ t("web.alerts.acknowledge") }}</el-button>
          <el-button v-if="selectedIncident.status !== 'CLOSED'" type="danger" :icon="Close" @click="closeIncident(selectedIncident)">{{ t("web.alerts.closeIncident") }}</el-button>
        </div>
        <h5>{{ t("web.alerts.evidence") }}</h5>
        <pre class="alert-json">{{ prettyJson(selectedIncident.evidence) }}</pre>
        <h5>{{ t("web.alerts.timeline") }}</h5>
        <el-timeline>
          <el-timeline-item v-for="event in selectedIncident.recentEvents || []" :key="String(event.id)" :timestamp="event.observedAt" placement="top">
            <strong>{{ eventTypeLabel(event.eventType) }}</strong>
            <p>{{ event.summary }}</p>
          </el-timeline-item>
        </el-timeline>
        <h5>{{ t("web.alerts.deliveryTitle") }}</h5>
        <el-table :data="selectedIncident.recentDeliveries || []" border size="small" class="incident-delivery-table" @row-dblclick="openDeliveryDetail">
          <el-table-column :label="t('web.alerts.deliveryMessage')" min-width="180">
            <template #default="{ row }">
              <div class="delivery-cell">
                <span class="delivery-cell__title">{{ deliveryMessageTitle(row) }}</span>
                <span class="delivery-cell__preview">{{ deliveryMessagePreview(row) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.alerts.deliveryDestination')" min-width="150">
            <template #default="{ row }">
              <div class="delivery-cell">
                <span class="delivery-cell__title">{{ row.channelName || channelTypeLabel(row.channelType) }}</span>
                <span class="delivery-cell__preview">{{ deliveryRecipientLabel(row) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="status" :label="t('common.status')" width="105">
            <template #default="{ row }"><el-tag :type="deliveryStatusTag(row.status)">{{ deliveryStatusLabel(row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column :label="t('web.alerts.deliveryTime')" min-width="145">
            <template #default="{ row }">{{ deliveryDisplayTime(row) }}</template>
          </el-table-column>
          <el-table-column :label="t('common.actions')" width="140" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" :icon="View" @click="openDeliveryDetail(row)">{{ t("web.alerts.detail") }}</el-button>
              <el-button v-if="options.canManage && ['DEAD', 'RETRY', 'SKIPPED'].includes(row.status || '')" link type="primary" :icon="Refresh" @click="retryDelivery(row)">{{ t("web.alerts.retry") }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>

    <el-drawer v-model="deliveryDrawerVisible" :title="t('web.alerts.deliveryDetail')" size="min(720px, 96vw)" append-to-body @opened="resetDeliveryDrawerScroll">
      <div v-if="selectedDelivery" ref="deliveryDetailRef" class="delivery-detail">
        <section class="delivery-detail__section">
          <div class="delivery-detail__heading">
            <h4>{{ t("web.alerts.deliverySourceSection") }}</h4>
            <div class="delivery-detail__tags">
              <el-tag v-if="selectedDelivery.severity" :type="severityTag(selectedDelivery.severity)">{{ severityLabel(selectedDelivery.severity) }}</el-tag>
              <el-tag v-if="selectedDelivery.eventType" type="info">{{ eventTypeLabel(selectedDelivery.eventType) }}</el-tag>
            </div>
          </div>
          <dl class="delivery-detail__grid">
            <dt>{{ t("web.alerts.rule") }}</dt><dd>{{ selectedDelivery.ruleName || t("web.alerts.unknownRule") }}</dd>
            <dt>{{ t("web.alerts.ruleType") }}</dt><dd>{{ selectedDelivery.ruleType ? ruleTypeLabel(selectedDelivery.ruleType) : '-' }}</dd>
            <dt>{{ t("web.alerts.deliveryEvent") }}</dt><dd>{{ selectedDelivery.eventType ? eventTypeLabel(selectedDelivery.eventType) : '-' }}</dd>
            <dt>{{ t("web.alerts.deliveryOccurredAt") }}</dt><dd>{{ selectedDelivery.occurredAt || '-' }}</dd>
            <dt>{{ t("web.alerts.subject") }}</dt><dd>{{ selectedDelivery.subjectName || t("web.alerts.unknownSubject") }}</dd>
            <dt>{{ t("web.alerts.subjectType") }}</dt><dd>{{ selectedDelivery.subjectType ? subjectTypeLabel(selectedDelivery.subjectType) : '-' }}</dd>
            <dt>{{ t("web.alerts.summary") }}</dt><dd>{{ selectedDelivery.summary || '-' }}</dd>
          </dl>
          <el-button v-if="selectedDelivery.targetPath" class="delivery-detail__target" :icon="Promotion" @click="openTarget(selectedDelivery.targetPath)">{{ t("web.alerts.openTarget") }}</el-button>
        </section>

        <section class="delivery-detail__section">
          <div class="delivery-detail__heading">
            <h4>{{ t("web.alerts.deliveryContentSection") }}</h4>
            <el-tag v-if="selectedDelivery.messageFormat" type="info">{{ selectedDelivery.messageFormat }}</el-tag>
          </div>
          <dl class="delivery-detail__grid delivery-detail__grid--content">
            <dt>{{ t("web.alerts.deliveryMessageTitle") }}</dt><dd>{{ selectedDelivery.messageTitle || '-' }}</dd>
          </dl>
          <pre class="delivery-message-content">{{ formattedDeliveryContent(selectedDelivery) }}</pre>
        </section>

        <section class="delivery-detail__section">
          <div class="delivery-detail__heading">
            <h4>{{ t("web.alerts.deliveryResultSection") }}</h4>
            <el-tag :type="deliveryStatusTag(selectedDelivery.status)">{{ deliveryStatusLabel(selectedDelivery.status) }}</el-tag>
          </div>
          <dl class="delivery-detail__grid">
            <dt>{{ t("web.alerts.channelType") }}</dt><dd>{{ channelTypeLabel(selectedDelivery.channelType) }}</dd>
            <dt>{{ t("web.alerts.channelName") }}</dt><dd>{{ selectedDelivery.channelName || '-' }}</dd>
            <dt>{{ t("web.alerts.deliveryRecipient") }}</dt><dd>{{ deliveryRecipientLabel(selectedDelivery) }}</dd>
            <dt>{{ t("web.alerts.attempts") }}</dt><dd>{{ selectedDelivery.attemptCount || 0 }}</dd>
            <dt>HTTP</dt><dd>{{ selectedDelivery.httpStatus ?? '-' }}</dd>
            <dt>{{ t("web.alerts.createdAt") }}</dt><dd>{{ selectedDelivery.createdAt || '-' }}</dd>
            <dt>{{ t("web.alerts.deliveryLastAttemptAt") }}</dt><dd>{{ selectedDelivery.lastAttemptAt || '-' }}</dd>
            <dt>{{ t("web.alerts.deliveryNextAttemptAt") }}</dt><dd>{{ selectedDelivery.nextAttemptAt || '-' }}</dd>
          </dl>
          <div v-if="selectedDelivery.responseExcerpt" class="delivery-result-block">
            <span>{{ t("web.alerts.deliveryResponse") }}</span>
            <pre>{{ formatReadableText(selectedDelivery.responseExcerpt) }}</pre>
          </div>
          <div v-if="selectedDelivery.errorMessage" class="delivery-result-block is-error">
            <span>{{ t("web.alerts.error") }}</span>
            <pre>{{ selectedDelivery.errorMessage }}</pre>
          </div>
        </section>
      </div>
      <template #footer>
        <el-button @click="deliveryDrawerVisible = false">{{ t("web.alerts.closeDetail") }}</el-button>
        <el-button v-if="selectedDelivery && options.canManage && ['DEAD', 'RETRY', 'SKIPPED'].includes(selectedDelivery.status || '')" type="primary" :icon="Refresh" @click="retrySelectedDelivery">{{ t("web.alerts.retry") }}</el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="tenantSummaryVisible" :title="t('web.alerts.tenantSummary')" width="min(860px, 96vw)" top="7vh">
      <div class="tenant-summary-toolbar">
        <el-input v-model="tenantSummaryFilters.keyword" clearable :placeholder="t('web.alerts.searchProjectPlaceholder')" @keyup.enter="searchTenantSummary" />
        <el-button type="primary" :icon="Search" @click="searchTenantSummary">{{ t("common.search") }}</el-button>
      </div>
      <el-table v-loading="tenantSummaryLoading" :data="tenantSummaries" border size="small">
        <el-table-column prop="projectName" :label="t('web.alerts.projectName')" min-width="220" />
        <el-table-column prop="enabledRuleCount" :label="t('web.alerts.enabledRules')" width="125" />
        <el-table-column prop="openIncidentCount" :label="t('web.alerts.openIncidents')" width="130" />
        <el-table-column prop="criticalIncidentCount" :label="t('web.alerts.criticalIncidents')" width="125" />
        <el-table-column prop="failedDeliveryCount" :label="t('web.alerts.failedDeliveries')" width="130" />
      </el-table>
      <div class="alert-pagination">
        <el-pagination v-model:current-page="tenantSummaryFilters.pageNo" v-model:page-size="tenantSummaryFilters.pageSize" layout="total, prev, pager, next" :total="tenantSummaryTotal" @current-change="loadTenantSummary" />
      </div>
    </el-dialog>

    <el-dialog v-model="ruleDialogVisible" class="alert-dialog" :title="ruleForm.id ? t('web.alerts.editRule') : t('web.alerts.createRule')" width="min(780px, 96vw)" top="5vh" destroy-on-close>
      <el-form label-position="top" class="alert-form">
        <div class="alert-form__grid">
          <el-form-item :label="t('web.alerts.ruleName')" required><el-input v-model="ruleForm.name" maxlength="255" /></el-form-item>
          <el-form-item :label="t('web.alerts.severity')" required>
            <el-select v-model="ruleForm.severity"><el-option v-for="severity in options.severities" :key="severity" :label="severityLabel(severity)" :value="severity" /></el-select>
          </el-form-item>
          <el-form-item :label="t('web.alerts.ruleType')" required>
            <el-select v-model="ruleForm.ruleType" @change="handleRuleTypeChange"><el-option v-for="rule in options.ruleTypes" :key="rule.code" :label="ruleTypeLabel(rule.code)" :value="rule.code" /></el-select>
          </el-form-item>
          <el-form-item :label="t('web.alerts.subjectType')" required>
            <el-select v-model="ruleForm.subjectType" @change="handleSubjectTypeChange"><el-option v-for="subjectType in currentRuleDefinition?.subjectTypes || []" :key="subjectType" :label="subjectTypeLabel(subjectType)" :value="subjectType" /></el-select>
          </el-form-item>
          <el-form-item v-if="requiresSubjectSelection" :label="t('web.alerts.scope')" required class="alert-form__wide">
            <el-select v-model="ruleForm.subjectSelection" filterable remote reserve-keyword :remote-method="searchSubjects" :loading="subjectLoading" :placeholder="t('web.alerts.selectSubject')">
              <el-option :label="t('web.alerts.allSubjects')" :value="ALL_SUBJECTS" />
              <el-option v-for="subject in subjectOptions" :key="String(subject.id)" :label="subject.name" :value="subject.id" />
            </el-select>
          </el-form-item>
          <el-form-item v-for="field in conditionFields" :key="String(field.name)" :label="conditionFieldLabel(String(field.name), String(field.label || field.name))">
            <el-input-number v-if="field.type === 'integer'" v-model="ruleForm.condition[String(field.name)]" :min="Number(field.min || 0)" :max="Number(field.max || 1000000)" controls-position="right" />
            <el-select v-else-if="field.type === 'enum'" v-model="ruleForm.condition[String(field.name)]"><el-option v-for="item in field.options || []" :key="String(item)" :label="String(item)" :value="item" /></el-select>
            <el-select v-else-if="field.type === 'multi-enum'" v-model="ruleForm.condition[String(field.name)]" multiple><el-option v-for="item in field.options || []" :key="String(item)" :label="logDomainLabel(String(item))" :value="item" /></el-select>
          </el-form-item>
          <el-form-item :label="t('web.alerts.silenceMinutes')"><el-input-number v-model="ruleForm.silenceMinutes" :min="0" :max="10080" controls-position="right" /></el-form-item>
          <el-form-item :label="t('web.alerts.enabledState')"><el-switch v-model="ruleForm.enabled" /></el-form-item>
          <el-form-item :label="t('web.alerts.notificationChannels')" class="alert-form__wide">
            <el-select
              v-model="ruleForm.webhookChannelIds"
              multiple
              filterable
              remote
              reserve-keyword
              collapse-tags
              :max-collapse-tags="2"
              :remote-method="searchChannelOptions"
              :loading="channelOptionLoading"
            >
              <el-option v-for="channel in selectableChannels" :key="String(channel.id)" :label="ruleChannelOptionLabel(channel)" :value="channel.id">
                <div class="alert-channel-option">
                  <span class="alert-channel-option__name">{{ channel.name }}</span>
                  <span class="alert-channel-option__tags">
                    <el-tag size="small" :type="channelUsesRuleRecipients(channel) ? 'success' : 'warning'">{{ ruleChannelModeLabel(channel) }}</el-tag>
                    <el-tag v-if="channel.enabled === false" size="small" type="info">{{ t("web.alerts.channelModeDisabled") }}</el-tag>
                  </span>
                </div>
              </el-option>
            </el-select>
          </el-form-item>
          <div v-if="selectedDisabledRuleChannels.length" class="alert-form__wide alert-form__impact">
            <el-alert type="warning" :closable="false" show-icon :title="t('web.alerts.disabledSelectedChannelHint', { channels: selectedDisabledRuleChannelNames })" />
          </div>
          <div v-if="!ruleRecipientControlsEnabled" class="alert-form__wide alert-form__impact">
            <el-alert type="info" :closable="false" show-icon :title="t('web.alerts.recipientSourcesInactiveHint')" />
          </div>
          <div v-else-if="selectedIndependentRuleChannels.length" class="alert-form__wide alert-form__impact">
            <el-alert type="info" :closable="false" show-icon :title="t('web.alerts.fixedChannelRecipientImpactHint', { channels: selectedIndependentRuleChannelNames })" />
          </div>
          <el-form-item :label="t('web.alerts.recipients')" class="alert-form__wide">
            <el-select v-model="ruleForm.recipientUserIds" multiple filterable remote reserve-keyword :remote-method="searchRecipients" :loading="recipientLoading" :disabled="!ruleRecipientControlsEnabled"><el-option v-for="recipient in recipientOptions" :key="String(recipient.id)" :label="recipient.name" :value="recipient.id" /></el-select>
          </el-form-item>
        </div>
        <div class="alert-form__toggles">
          <el-checkbox v-model="ruleForm.inAppEnabled">{{ t("web.alerts.inApp") }}</el-checkbox>
          <el-checkbox v-model="ruleForm.notifyResourceOwner" :disabled="!resourceOwnerSupported || !ruleRecipientControlsEnabled">{{ t("web.alerts.resourceOwner") }}</el-checkbox>
          <el-checkbox v-model="ruleForm.notifyProjectAdmins" :disabled="!ruleRecipientControlsEnabled">{{ t("web.alerts.projectAdmins") }}</el-checkbox>
          <el-checkbox v-model="ruleForm.recoveryNotificationEnabled">{{ t("web.alerts.recoveryNotification") }}</el-checkbox>
        </div>
        <el-form-item :label="t('common.description')"><el-input v-model="ruleForm.description" type="textarea" :rows="3" maxlength="1000" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="ruleDialogVisible = false">{{ t("common.cancel") }}</el-button><el-button type="primary" :loading="ruleSaving" @click="saveRule">{{ t("common.save") }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="channelDialogVisible" class="alert-dialog" :title="channelForm.id ? t('web.alerts.editChannel') : t('web.alerts.createChannel')" width="min(680px, 96vw)" top="5vh" destroy-on-close>
      <el-form label-position="top">
        <el-form-item :label="t('web.alerts.channelName')" required><el-input v-model="channelForm.name" maxlength="255" /></el-form-item>
        <el-form-item :label="t('web.alerts.channelType')" required>
          <el-radio-group v-model="channelForm.channelType" :disabled="Boolean(channelForm.id)" @change="handleChannelTypeChange">
            <el-radio-button value="WEBHOOK">{{ t("web.alerts.channelTypeWebhook") }}</el-radio-button>
            <el-radio-button v-if="options.elinkChannelEnabled || channelForm.channelType === 'ELINK'" value="ELINK">{{ t("web.alerts.channelTypeElink") }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <template v-if="channelForm.channelType === 'WEBHOOK'">
          <el-form-item :label="t('web.alerts.endpoint')" :required="!channelForm.id"><el-input v-model="channelForm.endpointUrl" maxlength="2048" :placeholder="channelForm.id ? t('web.alerts.endpointRetainHint') : 'https://hooks.example.com/studio-alert'" /></el-form-item>
          <el-form-item :label="t('web.alerts.headersJson')"><el-input v-model="channelForm.headersJson" type="textarea" :rows="5" placeholder='{"Authorization":"Bearer ..."}' /></el-form-item>
          <el-form-item :label="t('web.alerts.signingSecret')"><el-input v-model="channelForm.signingSecret" type="password" maxlength="4096" show-password :placeholder="channelForm.id ? t('web.alerts.secretRetainHint') : ''" /></el-form-item>
          <el-form-item><el-checkbox v-if="channelForm.id && channelForm.hasSigningSecret" v-model="channelForm.clearSigningSecret">{{ t("web.alerts.clearSigningSecret") }}</el-checkbox></el-form-item>
        </template>
        <template v-else>
          <el-form-item :label="t('web.alerts.elinkRecipientMode')" required>
            <el-radio-group v-model="channelForm.elinkRecipientMode" @change="handleElinkRecipientModeChange">
              <el-radio-button value="FIXED">{{ t("web.alerts.elinkRecipientFixed") }}</el-radio-button>
              <el-radio-button value="RULE_RECIPIENTS">{{ t("web.alerts.elinkRecipientRuleRecipients") }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <template v-if="channelForm.elinkRecipientMode === 'FIXED'">
            <el-form-item :label="t('web.alerts.elinkTargetType')" required>
              <el-radio-group v-model="channelForm.elinkTargetType" @change="handleElinkTargetTypeChange">
                <el-radio-button value="PERSONAL">{{ t("web.alerts.elinkTargetPersonal") }}</el-radio-button>
                <el-radio-button value="GROUP">{{ t("web.alerts.elinkTargetGroup") }}</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item v-if="channelForm.elinkTargetType === 'PERSONAL'" :label="t('web.alerts.elinkUserIds')" required>
              <el-select
                v-model="channelForm.elinkUserIds"
                multiple
                filterable
                remote
                reserve-keyword
                collapse-tags
                :max-collapse-tags="3"
                :loading="elinkUserLoading"
                :remote-method="searchElinkUsers"
                :placeholder="t('web.alerts.elinkUserIdsPlaceholder')"
                @visible-change="handleElinkUserDropdownVisible"
              >
                <el-option
                  v-for="user in elinkUserOptions"
                  :key="user.userId"
                  :label="elinkUserLabel(user)"
                  :value="user.userId"
                  :disabled="user.enabled === false && !channelForm.elinkUserIds.includes(user.userId)"
                />
              </el-select>
            </el-form-item>
            <el-form-item v-else :label="t('web.alerts.elinkGroupId')" required>
              <el-select
                v-model="channelForm.elinkGroupId"
                filterable
                remote
                reserve-keyword
                :loading="elinkGroupLoading"
                :remote-method="searchElinkGroups"
                :placeholder="t('web.alerts.elinkGroupIdPlaceholder')"
                @visible-change="handleElinkGroupDropdownVisible"
              >
                <el-option v-for="group in elinkGroupOptions" :key="String(group.id)" :label="elinkGroupLabel(group)" :value="group.id" />
              </el-select>
            </el-form-item>
          </template>
          <el-alert v-else type="info" :closable="false" show-icon :title="t('web.alerts.elinkRuleRecipientsHint')" />
        </template>
        <el-form-item><el-switch v-model="channelForm.enabled" :active-text="t('web.alerts.enabled')" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="channelDialogVisible = false">{{ t("common.cancel") }}</el-button><el-button type="primary" :loading="channelSaving" @click="saveChannel">{{ t("common.save") }}</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { Check, Close, Connection, DataAnalysis, Delete, Edit, Plus, Promotion, Refresh, Search, View } from "@element-plus/icons-vue";
import type {
  AlertChannelType,
  AlertChannelView,
  AlertDeliveryView,
  AlertElinkRecipientMode,
  AlertElinkTargetType,
  AlertIncidentView,
  AlertOptionView,
  AlertOptionsView,
  AlertRuleView,
  AlertSelectOptionView,
  AlertSummaryView,
  AlertTenantProjectSummaryView,
  EntityId,
  RuntimeClusterView,
} from "@studio/api-sdk";
import { studioApi } from "@/api/studio";
import { useElinkDirectoryOptions } from "@/composables/useElinkDirectoryOptions";
import { formatRuntimeClusterLabel } from "@/utils/runtimeClusters";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const {
  userOptions: elinkUserOptions,
  groupOptions: elinkGroupOptions,
  userLoading: elinkUserLoading,
  groupLoading: elinkGroupLoading,
  seedUsers: seedElinkUsers,
  seedGroup: seedElinkGroup,
  queryUsers: queryElinkUsers,
  queryGroups: queryElinkGroups,
  ensureUsers: ensureElinkUsers,
  ensureGroups: ensureElinkGroups,
  cancelUserQuery: cancelElinkUserQuery,
  cancelGroupQuery: cancelElinkGroupQuery,
  cancelQueries: cancelElinkQueries,
  userLabel: elinkUserLabel,
  groupLabel: elinkGroupLabel,
} = useElinkDirectoryOptions();
const ALL_SUBJECTS = "__ALL_SUBJECTS__";
const OPTION_PAGE_SIZE = 100;
const ELINK_SEARCH_DEBOUNCE_MS = 250;
const ALERT_TABS = new Set(["incidents", "rules", "channels"]);

const activeTab = ref(normalizeAlertTab(route.query.tab));
const loading = ref(false);
const summaryLoading = ref(false);
const incidentLoading = ref(false);
const ruleLoading = ref(false);
const channelLoading = ref(false);
const deliveryLoading = ref(false);
const subjectLoading = ref(false);
const recipientLoading = ref(false);
const channelOptionLoading = ref(false);
const ruleSaving = ref(false);
const channelSaving = ref(false);
let subjectRequestSequence = 0;
let recipientRequestSequence = 0;
let channelOptionRequestSequence = 0;
let elinkUserSearchTimer: ReturnType<typeof setTimeout> | undefined;
let elinkGroupSearchTimer: ReturnType<typeof setTimeout> | undefined;
let elinkUserSearchKeyword = "";
let elinkGroupSearchKeyword = "";

const options = reactive<AlertOptionsView>({ ruleTypes: [], severities: [], incidentStatuses: [], deliveryStatuses: [], elinkChannelEnabled: false, canManage: false, canHandleIncidents: true, canViewTenantSummary: false });
const summary = reactive<AlertSummaryView>({ enabledRuleCount: 0, openIncidentCount: 0, acknowledgedIncidentCount: 0, criticalIncidentCount: 0, failedDeliveryCount: 0 });
const incidents = ref<AlertIncidentView[]>([]);
const rules = ref<AlertRuleView[]>([]);
const channels = ref<AlertChannelView[]>([]);
const channelOptions = ref<AlertChannelView[]>([]);
const deliveries = ref<AlertDeliveryView[]>([]);
const subjectOptions = ref<AlertSelectOptionView[]>([]);
const recipientOptions = ref<AlertSelectOptionView[]>([]);
const tenantSummaries = ref<AlertTenantProjectSummaryView[]>([]);
const runtimeClusters = ref<RuntimeClusterView[]>([]);
const incidentTotal = ref(0);
const ruleTotal = ref(0);
const channelTotal = ref(0);
const deliveryTotal = ref(0);
const tenantSummaryTotal = ref(0);

const incidentFilters = reactive({ keyword: "", status: "", severity: "", ruleType: "", requestedClusterId: "" as EntityId | "", actualClusterId: "" as EntityId | "", activeOnly: false, pageNo: 1, pageSize: 20 });
const ruleFilters = reactive<{ keyword: string; ruleType: string; enabled?: boolean; pageNo: number; pageSize: number }>({ keyword: "", ruleType: "", enabled: undefined, pageNo: 1, pageSize: 20 });
const channelFilters = reactive<{ keyword: string; enabled?: boolean; pageNo: number; pageSize: number }>({ keyword: "", enabled: undefined, pageNo: 1, pageSize: 20 });
const deliveryFilters = reactive({ status: "", failedOnly: false, pageNo: 1, pageSize: 20 });
const tenantSummaryFilters = reactive({ keyword: "", pageNo: 1, pageSize: 20 });

const incidentDrawerVisible = ref(false);
const selectedIncident = ref<AlertIncidentView>();
const deliveryDrawerVisible = ref(false);
const selectedDelivery = ref<AlertDeliveryView>();
const deliveryDetailRef = ref<HTMLElement>();
const ruleDialogVisible = ref(false);
const channelDialogVisible = ref(false);
const tenantSummaryVisible = ref(false);
const tenantSummaryLoading = ref(false);

const emptyRuleForm = () => ({
  id: undefined as EntityId | undefined,
  name: "",
  description: "",
  ruleType: "EXECUTION_FAILED",
  subjectType: "COLLECTION_TASK",
  subjectSelection: ALL_SUBJECTS as EntityId | string,
  severity: "WARNING",
  enabled: false,
  condition: {} as Record<string, any>,
  silenceMinutes: 30,
  recoveryNotificationEnabled: true,
  inAppEnabled: true,
  recipientUserIds: [] as EntityId[],
  notifyResourceOwner: true,
  notifyProjectAdmins: true,
  webhookChannelIds: [] as EntityId[],
});
const ruleForm = reactive(emptyRuleForm());
const emptyChannelForm = () => ({
  id: undefined as EntityId | undefined,
  name: "",
  channelType: "WEBHOOK" as AlertChannelType,
  originalChannelType: undefined as AlertChannelType | undefined,
  endpointUrl: "",
  headersJson: "",
  signingSecret: "",
  clearSigningSecret: false,
  hasSigningSecret: false,
  elinkRecipientMode: "FIXED" as AlertElinkRecipientMode,
  elinkTargetType: "PERSONAL" as AlertElinkTargetType,
  elinkUserIds: [] as string[],
  elinkGroupId: "" as EntityId,
  enabled: true,
});
const channelForm = reactive(emptyChannelForm());

const currentRuleDefinition = computed<AlertOptionView | undefined>(() => options.ruleTypes.find((item) => item.code === ruleForm.ruleType));
const conditionFields = computed<Array<Record<string, any>>>(() => currentRuleDefinition.value?.conditionSchema?.fields || []);
const requiresSubjectSelection = computed(() => !["PROJECT_QUEUE", "LOG_STORAGE"].includes(ruleForm.subjectType));
const resourceOwnerSupported = computed(() => ["COLLECTION_TASK", "QUALITY_TASK", "WORKFLOW", "DATA_SERVICE", "DATA_INGESTION_SERVICE", "PROTOCOL_CONVERSION_SERVICE"].includes(ruleForm.subjectType));
const selectableChannels = computed(() => {
  const selected = new Set(ruleForm.webhookChannelIds.map((item) => String(item)));
  return channelOptions.value.filter((item) => item.enabled || (item.id != null && selected.has(String(item.id))));
});
const selectedRuleChannels = computed(() => {
  const selected = new Set(ruleForm.webhookChannelIds.map((item) => String(item)));
  return channelOptions.value.filter((item) => item.id != null && selected.has(String(item.id)));
});
const selectedRuleRecipientChannels = computed(() => selectedRuleChannels.value
  .filter((item) => item.enabled !== false && channelUsesRuleRecipients(item)));
const selectedIndependentRuleChannels = computed(() => selectedRuleChannels.value
  .filter((item) => item.enabled !== false && !channelUsesRuleRecipients(item)));
const selectedDisabledRuleChannels = computed(() => selectedRuleChannels.value
  .filter((item) => item.enabled === false));
const ruleRecipientControlsEnabled = computed(() => ruleForm.inAppEnabled || selectedRuleRecipientChannels.value.length > 0);
const hasConfiguredRuleRecipientSource = computed(() => ruleForm.recipientUserIds.length > 0
  || (resourceOwnerSupported.value && ruleForm.notifyResourceOwner)
  || ruleForm.notifyProjectAdmins);
const selectedIndependentRuleChannelNames = computed(() => selectedIndependentRuleChannels.value.map((item) => item.name).join(", "));
const selectedDisabledRuleChannelNames = computed(() => selectedDisabledRuleChannels.value.map((item) => item.name).join(", "));
const summaryItems = computed(() => [
  { key: "open", label: t("web.alerts.openIncidents"), value: summary.openIncidentCount, tone: "danger" },
  { key: "ack", label: t("web.alerts.acknowledgedIncidents"), value: summary.acknowledgedIncidentCount, tone: "warning" },
  { key: "critical", label: t("web.alerts.criticalIncidents"), value: summary.criticalIncidentCount, tone: "critical" },
  { key: "rules", label: t("web.alerts.enabledRules"), value: summary.enabledRuleCount, tone: "normal" },
  { key: "deliveries", label: t("web.alerts.failedDeliveries"), value: summary.failedDeliveryCount, tone: "danger" },
]);

onMounted(loadAll);
onBeforeUnmount(cleanupElinkDirectoryRequests);
watch(() => route.query.tab, (value) => {
  const nextTab = normalizeAlertTab(value);
  if (activeTab.value !== nextTab) activeTab.value = nextTab;
});
watch(() => channelForm.signingSecret, (value) => {
  if (value && channelForm.clearSigningSecret) channelForm.clearSigningSecret = false;
});
watch(() => channelForm.clearSigningSecret, (value) => {
  if (value) channelForm.signingSecret = "";
});
watch(channelDialogVisible, (visible) => {
  if (!visible) cleanupElinkDirectoryRequests();
});

async function loadAll() {
  loading.value = true;
  try {
    const [optionResult] = await Promise.all([studioApi.alerts.options(), loadSummary(false), loadIncidents(false), loadRules(false), loadChannels(false), loadDeliveries(false), loadRecipients("", false), loadRuntimeClusters()]);
    Object.assign(options, optionResult);
  } catch (error) {
    ElMessage.error(errorMessage(error, t("web.alerts.loadFailed")));
  } finally {
    loading.value = false;
  }
}

async function loadSummary(reportError = true) { await runAlertLoad(summaryLoading, async () => { Object.assign(summary, await studioApi.alerts.summary()); }, reportError); }
async function loadIncidents(reportError = true) { await runAlertLoad(incidentLoading, async () => { const page = await studioApi.alerts.queryIncidents({ ...incidentFilters, status: incidentFilters.status || undefined, severity: incidentFilters.severity || undefined, ruleType: incidentFilters.ruleType || undefined, requestedClusterId: incidentFilters.requestedClusterId || undefined, actualClusterId: incidentFilters.actualClusterId || undefined, activeOnly: incidentFilters.activeOnly || undefined }); incidents.value = page.items; incidentTotal.value = page.total; }, reportError); }
async function loadRules(reportError = true) { await runAlertLoad(ruleLoading, async () => { const page = await studioApi.alerts.queryRules({ ...ruleFilters, keyword: ruleFilters.keyword || undefined, ruleType: ruleFilters.ruleType || undefined }); rules.value = page.items; ruleTotal.value = page.total; }, reportError); }
async function loadChannels(reportError = true) { await runAlertLoad(channelLoading, async () => { const page = await studioApi.alerts.queryChannels({ ...channelFilters, keyword: channelFilters.keyword || undefined }); channels.value = page.items; channelTotal.value = page.total; }, reportError); }
async function loadDeliveries(reportError = true) { await runAlertLoad(deliveryLoading, async () => { const page = await studioApi.alerts.queryDeliveries({ ...deliveryFilters, status: deliveryFilters.status || undefined, failedOnly: deliveryFilters.failedOnly || undefined }); deliveries.value = page.items; deliveryTotal.value = page.total; }, reportError); }
async function loadTenantSummary(reportError = true) { await runAlertLoad(tenantSummaryLoading, async () => { const page = await studioApi.alerts.tenantSummary({ ...tenantSummaryFilters, keyword: tenantSummaryFilters.keyword || undefined }); tenantSummaries.value = page.items; tenantSummaryTotal.value = page.total; }, reportError); }
async function loadRuntimeClusters() {
  try {
    runtimeClusters.value = await studioApi.runtimeClusters.options();
  } catch {
    runtimeClusters.value = [];
  }
}
async function loadRecipients(keyword = "", reportError = true) {
  const requestSequence = ++recipientRequestSequence;
  recipientLoading.value = true;
  try {
    const selected = new Set(ruleForm.recipientUserIds.map((item) => String(item)));
    const loaded: AlertSelectOptionView[] = [];
    let pageNo = 1;
    let total = 0;
    do {
      const page = await studioApi.alerts.recipientOptions({ keyword: keyword || undefined, pageNo, pageSize: OPTION_PAGE_SIZE });
      loaded.push(...page.items);
      total = page.total;
      pageNo += 1;
      const resolved = new Set(loaded.filter((item) => item.id != null).map((item) => String(item.id)));
      if (keyword || selected.size === 0 || Array.from(selected).every((id) => resolved.has(id))) break;
    } while ((pageNo - 1) * OPTION_PAGE_SIZE < total && requestSequence === recipientRequestSequence);
    if (requestSequence === recipientRequestSequence) recipientOptions.value = mergeOptions(recipientOptions.value, loaded, ruleForm.recipientUserIds);
  } catch (error) {
    if (reportError) ElMessage.error(errorMessage(error, t("web.alerts.loadFailed")));
    else throw error;
  } finally {
    if (requestSequence === recipientRequestSequence) recipientLoading.value = false;
  }
}
async function loadChannelOptions(keyword = "") {
  const requestSequence = ++channelOptionRequestSequence;
  channelOptionLoading.value = true;
  try {
    const page = await studioApi.alerts.queryChannels({ keyword: keyword || undefined, enabled: true, pageNo: 1, pageSize: OPTION_PAGE_SIZE });
    const loaded = [...page.items];
    const selected = new Set(ruleForm.webhookChannelIds.map((item) => String(item)));
    const resolved = new Set(loaded.filter((item) => item.id != null).map((item) => String(item.id)));
    if (!keyword && Array.from(selected).some((id) => !resolved.has(id))) {
      let pageNo = 1;
      let total = 0;
      do {
        const allPage = await studioApi.alerts.queryChannels({ pageNo, pageSize: OPTION_PAGE_SIZE });
        loaded.push(...allPage.items.filter((item) => item.id != null && selected.has(String(item.id))));
        total = allPage.total;
        pageNo += 1;
        const allResolved = new Set(loaded.filter((item) => item.id != null).map((item) => String(item.id)));
        if (Array.from(selected).every((id) => allResolved.has(id))) break;
      } while ((pageNo - 1) * OPTION_PAGE_SIZE < total && requestSequence === channelOptionRequestSequence);
    }
    if (requestSequence === channelOptionRequestSequence) channelOptions.value = mergeOptions(channelOptions.value, loaded, ruleForm.webhookChannelIds);
  } catch (error) {
    ElMessage.error(errorMessage(error, t("web.alerts.loadFailed")));
  } finally {
    if (requestSequence === channelOptionRequestSequence) channelOptionLoading.value = false;
  }
}

function searchIncidents(preserveSummaryMode = false) { if (!preserveSummaryMode) incidentFilters.activeOnly = false; incidentFilters.pageNo = 1; loadIncidents(); }
function searchRules() { ruleFilters.pageNo = 1; loadRules(); }
function searchChannels() { channelFilters.pageNo = 1; loadChannels(); }
function searchDeliveries() { deliveryFilters.failedOnly = false; deliveryFilters.pageNo = 1; loadDeliveries(); }
function searchTenantSummary() { tenantSummaryFilters.pageNo = 1; loadTenantSummary(); }
async function openTenantSummary() { tenantSummaryVisible.value = true; await loadTenantSummary(); }
function resetIncidentFilters() { Object.assign(incidentFilters, { keyword: "", status: "", severity: "", ruleType: "", requestedClusterId: "", actualClusterId: "", activeOnly: false, pageNo: 1 }); loadIncidents(); }
function resetRuleFilters() { Object.assign(ruleFilters, { keyword: "", ruleType: "", enabled: undefined, pageNo: 1 }); loadRules(); }
function resetChannelFilters() { Object.assign(channelFilters, { keyword: "", enabled: undefined, pageNo: 1 }); loadChannels(); }

function handleTabChange(name: string | number) { selectTab(String(name)); }
function applySummaryFilter(key: string) {
  if (key === "rules") { selectTab("rules"); Object.assign(ruleFilters, { keyword: "", ruleType: "", enabled: true, pageNo: 1 }); loadRules(); return; }
  if (key === "deliveries") { selectTab("channels"); deliveryFilters.status = ""; deliveryFilters.failedOnly = true; deliveryFilters.pageNo = 1; loadDeliveries(); return; }
  selectTab("incidents");
  incidentFilters.keyword = "";
  incidentFilters.ruleType = "";
  incidentFilters.requestedClusterId = "";
  incidentFilters.actualClusterId = "";
  incidentFilters.status = key === "open" ? "OPEN" : key === "ack" ? "ACKNOWLEDGED" : "";
  incidentFilters.severity = key === "critical" ? "CRITICAL" : "";
  incidentFilters.activeOnly = key === "critical";
  searchIncidents(true);
}

async function openIncident(row: AlertIncidentView) {
  await runAlertAction(async () => {
    selectedIncident.value = await studioApi.alerts.getIncident(row.id!);
    incidentDrawerVisible.value = true;
  });
}
function openTarget(path?: string) { if (path) router.push(path); }

async function acknowledgeIncident(row: AlertIncidentView) {
  await runAlertAction(async () => {
    const { value } = await ElMessageBox.prompt(t("web.alerts.ackCommentPrompt"), t("web.alerts.acknowledge"), { inputType: "textarea", inputValue: "" });
    await studioApi.alerts.acknowledgeIncident(row.id!, { comment: value || undefined });
    await refreshAfterIncidentAction(row.id!);
  }, "web.alerts.ackSuccess");
}

async function closeIncident(row: AlertIncidentView) {
  await runAlertAction(async () => {
    const { value } = await ElMessageBox.prompt(t("web.alerts.closeCommentPrompt"), t("web.alerts.closeIncident"), { inputType: "textarea", inputValue: "" });
    await studioApi.alerts.closeIncident(row.id!, { comment: value || undefined });
    await refreshAfterIncidentAction(row.id!);
  }, "web.alerts.closeSuccess");
}

async function refreshAfterIncidentAction(id: EntityId) { await Promise.all([loadSummary(), loadIncidents()]); if (incidentDrawerVisible.value) selectedIncident.value = await studioApi.alerts.getIncident(id); }

async function openRuleDialog(row?: AlertRuleView) {
  await runAlertAction(async () => {
    Object.assign(ruleForm, emptyRuleForm());
    const detail = row?.id ? await studioApi.alerts.getRule(row.id) : undefined;
    if (detail) Object.assign(ruleForm, { ...detail, subjectSelection: detail.subjectId ?? ALL_SUBJECTS, condition: { ...(detail.condition || {}) } });
    else handleRuleTypeChange(ruleForm.ruleType);
    await Promise.all([loadSubjects(detail?.subjectName || ""), loadRecipients(), loadChannelOptions()]);
    ruleDialogVisible.value = true;
  });
}

function handleRuleTypeChange(value: string) {
  const definition = options.ruleTypes.find((item) => item.code === value);
  ruleForm.severity = definition?.defaultSeverity || "WARNING";
  ruleForm.subjectType = definition?.subjectTypes?.[0] || "COLLECTION_TASK";
  ruleForm.condition = { ...(definition?.defaults || {}) };
  ruleForm.subjectSelection = ALL_SUBJECTS;
  ruleForm.notifyResourceOwner = resourceOwnerSupported.value;
  loadSubjects();
}

function handleSubjectTypeChange() { ruleForm.subjectSelection = ALL_SUBJECTS; ruleForm.notifyResourceOwner = resourceOwnerSupported.value; loadSubjects(); }
async function loadSubjects(keyword = "") {
  const requestSequence = ++subjectRequestSequence;
  const subjectType = ruleForm.subjectType;
  if (!requiresSubjectSelection.value) { subjectOptions.value = []; subjectLoading.value = false; return; }
  subjectLoading.value = true;
  try {
    const page = await studioApi.alerts.subjects({ subjectType, keyword: keyword || undefined, pageNo: 1, pageSize: 100 });
    if (requestSequence === subjectRequestSequence && subjectType === ruleForm.subjectType) {
      subjectOptions.value = mergeOptions(subjectOptions.value, page.items, ruleForm.subjectSelection === ALL_SUBJECTS ? [] : [ruleForm.subjectSelection]);
    }
  } catch (error) {
    ElMessage.error(errorMessage(error, t("web.alerts.loadFailed")));
  } finally {
    if (requestSequence === subjectRequestSequence) subjectLoading.value = false;
  }
}
function searchSubjects(keyword: string) { loadSubjects(keyword); }
function searchRecipients(keyword: string) { loadRecipients(keyword); }
function searchChannelOptions(keyword: string) { loadChannelOptions(keyword); }

async function saveRule() {
  if (!ruleForm.name.trim() || !ruleForm.ruleType || !ruleForm.subjectType) { ElMessage.warning(t("web.alerts.ruleRequired")); return; }
  if (ruleRecipientControlsEnabled.value && !hasConfiguredRuleRecipientSource.value) { ElMessage.warning(t("web.alerts.ruleRecipientSourceRequired")); return; }
  ruleSaving.value = true;
  try {
    const { subjectSelection, ...payload } = ruleForm;
    await studioApi.alerts.saveRule({
      ...payload,
      recipientUserIds: ruleRecipientControlsEnabled.value ? payload.recipientUserIds : [],
      notifyResourceOwner: ruleRecipientControlsEnabled.value ? payload.notifyResourceOwner : false,
      notifyProjectAdmins: ruleRecipientControlsEnabled.value ? payload.notifyProjectAdmins : false,
      subjectId: requiresSubjectSelection.value && subjectSelection !== ALL_SUBJECTS ? subjectSelection : undefined,
    });
    ElMessage.success(t("web.alerts.ruleSaved")); ruleDialogVisible.value = false; await Promise.all([loadRules(), loadSummary()]);
  } catch (error) { ElMessage.error(errorMessage(error, t("web.alerts.ruleSaveFailed"))); } finally { ruleSaving.value = false; }
}

async function toggleRule(row: AlertRuleView) { await runAlertAction(async () => { if (row.enabled) await studioApi.alerts.disableRule(row.id!); else await studioApi.alerts.enableRule(row.id!); await Promise.all([loadRules(), loadSummary()]); }, row.enabled ? "web.alerts.ruleDisabled" : "web.alerts.ruleEnabled"); }
async function testRule(row: AlertRuleView) { await runAlertAction(async () => { await studioApi.alerts.testRule(row.id!); await loadDeliveries(); }, "web.alerts.testQueued"); }
async function deleteRule(row: AlertRuleView) { await runAlertAction(async () => { await ElMessageBox.confirm(t("web.alerts.deleteRuleConfirm", { name: row.name }), t("common.delete"), { type: "warning" }); await studioApi.alerts.deleteRule(row.id!); await Promise.all([loadRules(), loadSummary()]); }, "web.alerts.ruleDeleted"); }

function openChannelDialog(row?: AlertChannelView) {
  const channelType = row?.channelType || "WEBHOOK";
  const elinkRecipientMode = row?.elinkRecipientMode || "FIXED";
  Object.assign(channelForm, emptyChannelForm(), row ? {
    id: row.id,
    name: row.name,
    channelType,
    originalChannelType: channelType,
    hasSigningSecret: row.hasSigningSecret,
    elinkRecipientMode,
    elinkTargetType: row.elinkTargetType || "PERSONAL",
    elinkUserIds: [...(row.elinkUserIds || [])],
    elinkGroupId: row.elinkGroupId ?? "",
    enabled: row.enabled,
  } : {});
  seedElinkUsers(row?.elinkUserIds || [], row?.elinkUserNames || []);
  seedElinkGroup(row?.elinkGroupId, row?.elinkGroupName);
  channelDialogVisible.value = true;
}
async function saveChannel() {
  if (!channelForm.name.trim()) { ElMessage.warning(t("web.alerts.channelNameRequired")); return; }
  if (channelForm.channelType === "ELINK") {
    const recipientMode = channelForm.elinkRecipientMode || "FIXED";
    const userIds = normalizeElinkUserIds(channelForm.elinkUserIds);
    const groupId = String(channelForm.elinkGroupId ?? "").trim();
    if (recipientMode === "FIXED" && channelForm.elinkTargetType === "PERSONAL" && userIds.length === 0) { ElMessage.warning(t("web.alerts.elinkUsersRequired")); return; }
    if (recipientMode === "FIXED" && channelForm.elinkTargetType === "GROUP" && !/^[1-9]\d*$/.test(groupId)) { ElMessage.warning(t("web.alerts.elinkGroupRequired")); return; }
    channelSaving.value = true;
    try {
      await studioApi.alerts.saveChannel({
        id: channelForm.id,
        name: channelForm.name.trim(),
        channelType: "ELINK",
        elinkRecipientMode: recipientMode,
        elinkTargetType: recipientMode === "FIXED" ? channelForm.elinkTargetType : undefined,
        elinkUserIds: recipientMode === "FIXED" && channelForm.elinkTargetType === "PERSONAL" ? userIds : undefined,
        elinkGroupId: recipientMode === "FIXED" && channelForm.elinkTargetType === "GROUP" ? groupId : undefined,
        enabled: channelForm.enabled,
      });
      ElMessage.success(t("web.alerts.channelSaved")); channelDialogVisible.value = false; await loadChannels();
    } catch (error) { ElMessage.error(errorMessage(error, t("web.alerts.channelSaveFailed"))); } finally { channelSaving.value = false; }
    return;
  }
  const endpointRequired = !channelForm.id || channelForm.originalChannelType !== "WEBHOOK";
  if (endpointRequired && !channelForm.endpointUrl.trim()) { ElMessage.warning(t("web.alerts.webhookEndpointRequired")); return; }
  let headers: Record<string, string> | undefined;
  if (channelForm.headersJson.trim()) {
    try {
      const parsed = JSON.parse(channelForm.headersJson) as unknown;
      if (parsed == null || Array.isArray(parsed) || typeof parsed !== "object"
        || Object.values(parsed as Record<string, unknown>).some((value) => typeof value !== "string")) throw new Error("invalid headers");
      headers = parsed as Record<string, string>;
    } catch { ElMessage.warning(t("web.alerts.invalidHeaders")); return; }
  }
  channelSaving.value = true;
  try {
    await studioApi.alerts.saveChannel({ id: channelForm.id, name: channelForm.name.trim(), channelType: "WEBHOOK", endpointUrl: channelForm.endpointUrl.trim() || undefined, headers, signingSecret: channelForm.signingSecret || undefined, clearSigningSecret: channelForm.clearSigningSecret, enabled: channelForm.enabled });
    ElMessage.success(t("web.alerts.channelSaved")); channelDialogVisible.value = false; await loadChannels();
  } catch (error) { ElMessage.error(errorMessage(error, t("web.alerts.channelSaveFailed"))); } finally { channelSaving.value = false; }
}
async function toggleChannel(row: AlertChannelView) { await runAlertAction(async () => { if (row.enabled) await studioApi.alerts.disableChannel(row.id!); else await studioApi.alerts.enableChannel(row.id!); await loadChannels(); }, row.enabled ? "web.alerts.channelDisabled" : "web.alerts.channelEnabled"); }
async function testChannel(row: AlertChannelView) {
  if (row.channelType === "ELINK" && (row.elinkRecipientMode || "FIXED") === "RULE_RECIPIENTS") {
    ElMessage.info(t("web.alerts.elinkRuleRecipientsTestHint"));
    return;
  }
  await runAlertAction(async () => { await studioApi.alerts.testChannel(row.id!); await loadDeliveries(); }, "web.alerts.testQueued");
}
async function deleteChannel(row: AlertChannelView) { await runAlertAction(async () => { await ElMessageBox.confirm(t("web.alerts.deleteChannelConfirm", { name: row.name }), t("common.delete"), { type: "warning" }); await studioApi.alerts.deleteChannel(row.id!); await loadChannels(); }, "web.alerts.channelDeleted"); }
function openDeliveryDetail(row: AlertDeliveryView) {
  selectedDelivery.value = row;
  deliveryDrawerVisible.value = true;
}
function resetDeliveryDrawerScroll() {
  nextTick(() => deliveryDetailRef.value?.closest(".el-drawer__body")?.scrollTo({ top: 0 }));
}
async function retryDelivery(row: AlertDeliveryView) {
  await runAlertAction(async () => {
    await studioApi.alerts.retryDelivery(row.id!);
    await loadDeliveries();
    if (selectedIncident.value?.id) selectedIncident.value = await studioApi.alerts.getIncident(selectedIncident.value.id);
    if (selectedDelivery.value?.id != null && String(selectedDelivery.value.id) === String(row.id)) {
      selectedDelivery.value = deliveries.value.find((item) => String(item.id) === String(row.id))
        || selectedIncident.value?.recentDeliveries?.find((item) => String(item.id) === String(row.id))
        || selectedDelivery.value;
    }
  }, "web.alerts.retryQueued");
}
async function retrySelectedDelivery() {
  if (selectedDelivery.value) await retryDelivery(selectedDelivery.value);
}

function ruleTypeLabel(value?: string) { return t(`web.alerts.ruleType${titleCase(value)}`, value || "-"); }
function conditionFieldLabel(value: string, fallback: string) { return t(`web.alerts.condition${titleCase(value)}`, fallback); }
function severityLabel(value?: string) { return t(`web.alerts.severity${titleCase(value)}`, value || "-"); }
function statusLabel(value?: string) { return t(`web.alerts.status${titleCase(value)}`, value || "-"); }
function subjectTypeLabel(value?: string) { return t(`web.alerts.subject${titleCase(value)}`, value || "-"); }
function logDomainLabel(value: string) { return t(`web.alerts.domain${titleCase(value)}`, value); }
function channelTypeLabel(value?: string) {
  if (value === "ELINK") return t("web.alerts.channelTypeElink");
  if (value === "WEBHOOK") return t("web.alerts.channelTypeWebhook");
  if (value === "IN_APP") return t("web.alerts.channelTypeInApp");
  return value || "-";
}
function channelUsesRuleRecipients(channel: AlertChannelView) {
  return channel.channelType === "ELINK" && (channel.elinkRecipientMode || "FIXED") === "RULE_RECIPIENTS";
}
function ruleChannelModeLabel(channel: AlertChannelView) {
  if (channel.channelType === "WEBHOOK") return t("web.alerts.channelModeWebhook");
  if (channelUsesRuleRecipients(channel)) return t("web.alerts.channelModeElinkRuleRecipients");
  if (channel.elinkTargetType === "GROUP") return t("web.alerts.channelModeElinkFixedGroup");
  return t("web.alerts.channelModeElinkFixedPersonal");
}
function ruleChannelOptionLabel(channel: AlertChannelView) {
  const disabled = channel.enabled === false ? ` · ${t("web.alerts.channelModeDisabled")}` : "";
  return `${channel.name} · ${ruleChannelModeLabel(channel)}${disabled}`;
}
function deliveryStatusLabel(value?: string) { return t(`web.alerts.deliveryStatus${titleCase(value)}`, value || "-"); }
function eventTypeLabel(value?: string) { return t(`web.alerts.eventType${titleCase(value)}`, value || "-"); }
function incidentClusterLabel(row: AlertIncidentView, kind: "target" | "actual") {
  const evidence = row.evidence || {};
  const scalar = kind === "target"
    ? row.requestedClusterId ?? evidence.targetClusterId ?? evidence.requestedClusterId
    : row.actualClusterId ?? evidence.actualClusterId;
  const list = kind === "target" ? evidence.targetClusterIds : evidence.actualClusterIds;
  const code = kind === "actual" ? evidence.actualClusterCode : undefined;
  const ids = Array.isArray(list) ? list : scalar == null ? [] : [scalar];
  if (ids.length === 0) return "-";
  return ids
    .map((id) => formatRuntimeClusterLabel(runtimeClusters.value, id as EntityId, typeof code === "string" ? code : undefined))
    .join(", ");
}
function deliveryMessageTitle(row: AlertDeliveryView) {
  return row.messageTitle || row.summary || t("web.alerts.untitledMessage");
}
function deliveryMessagePreview(row: AlertDeliveryView) {
  const content = formatReadableText(row.messageContent);
  const candidate = content !== "-" ? content : row.messageTitle ? row.summary : "";
  const normalized = String(candidate || "").replace(/\s+/g, " ").trim();
  if (!normalized) return t("web.alerts.noMessageContent");
  return normalized.length > 160 ? `${normalized.slice(0, 160)}...` : normalized;
}
function deliveryRecipientLabel(row: AlertDeliveryView) {
  if (row.recipientDisplay) return row.recipientDisplay;
  return row.recipientUserId != null ? String(row.recipientUserId) : t("web.alerts.unspecifiedRecipient");
}
function deliveryDisplayTime(row: AlertDeliveryView) {
  return row.lastAttemptAt || row.createdAt || row.occurredAt || "-";
}
function formattedDeliveryContent(row: AlertDeliveryView) {
  return formatReadableText(row.messageContent || row.summary);
}
function formatReadableText(value: unknown) {
  if (value == null || value === "") return "-";
  if (typeof value !== "string") return JSON.stringify(value, null, 2);
  const trimmed = value.trim();
  if (!trimmed) return "-";
  try {
    return JSON.stringify(JSON.parse(trimmed), null, 2);
  } catch {
    return value;
  }
}
function channelTargetSummary(row: AlertChannelView) {
  if (row.channelType !== "ELINK") return row.endpointMasked || "-";
  if ((row.elinkRecipientMode || "FIXED") === "RULE_RECIPIENTS") return t("web.alerts.elinkRuleRecipientsTarget");
  if (row.elinkTargetType === "GROUP") return t("web.alerts.elinkGroupTarget", { name: row.elinkGroupName || String(row.elinkGroupId ?? "-") });
  const names = row.elinkUserNames || [];
  const targets = (row.elinkUserIds || []).map((userId, index) => names[index] ? `${names[index]} (${userId})` : userId);
  return t("web.alerts.elinkPersonalTarget", { ids: targets.join(", ") || "-" });
}
function handleChannelTypeChange(value: string | number | boolean | undefined) {
  clearElinkSearchTimers();
  if (value !== "ELINK") {
    cancelElinkQueries();
  }
}
function handleElinkRecipientModeChange(value: string | number | boolean | undefined) {
  clearElinkSearchTimers();
  if (value !== "FIXED") {
    cancelElinkQueries();
  }
}
function handleElinkTargetTypeChange(value: string | number | boolean | undefined) {
  clearElinkSearchTimers();
  if (value === "GROUP") {
    cancelElinkUserQuery();
  } else {
    cancelElinkGroupQuery();
  }
}
function handleElinkUserDropdownVisible(visible: boolean) {
  if (visible) void loadElinkUsers("", true);
  else {
    clearElinkSearchTimers();
    cancelElinkUserQuery();
  }
}
function handleElinkGroupDropdownVisible(visible: boolean) {
  if (visible) void loadElinkGroups("", true);
  else {
    clearElinkSearchTimers();
    cancelElinkGroupQuery();
  }
}
function searchElinkUsers(keyword: string) {
  const normalizedKeyword = keyword.trim();
  if (!normalizedKeyword && !elinkUserSearchKeyword) return;
  elinkUserSearchKeyword = normalizedKeyword;
  if (elinkUserSearchTimer) clearTimeout(elinkUserSearchTimer);
  elinkUserSearchTimer = setTimeout(() => { void loadElinkUsers(normalizedKeyword, !normalizedKeyword); }, ELINK_SEARCH_DEBOUNCE_MS);
}
function searchElinkGroups(keyword: string) {
  const normalizedKeyword = keyword.trim();
  if (!normalizedKeyword && !elinkGroupSearchKeyword) return;
  elinkGroupSearchKeyword = normalizedKeyword;
  if (elinkGroupSearchTimer) clearTimeout(elinkGroupSearchTimer);
  elinkGroupSearchTimer = setTimeout(() => { void loadElinkGroups(normalizedKeyword, !normalizedKeyword); }, ELINK_SEARCH_DEBOUNCE_MS);
}
async function loadElinkUsers(keyword = "", preferCache = false) {
  try {
    const selectedNames = selectedElinkUserNames();
    if (preferCache && !keyword.trim()) await ensureElinkUsers(channelForm.elinkUserIds, selectedNames);
    else await queryElinkUsers(keyword, channelForm.elinkUserIds, selectedNames);
  } catch (error) {
    ElMessage.error(errorMessage(error, t("web.alerts.elinkDirectoryLoadFailed")));
  }
}
async function loadElinkGroups(keyword = "", preferCache = false) {
  try {
    const selectedName = selectedElinkGroupName();
    if (preferCache && !keyword.trim()) await ensureElinkGroups(channelForm.elinkGroupId, selectedName);
    else await queryElinkGroups(keyword, channelForm.elinkGroupId, selectedName);
  } catch (error) {
    ElMessage.error(errorMessage(error, t("web.alerts.elinkDirectoryLoadFailed")));
  }
}
function clearElinkSearchTimers() {
  if (elinkUserSearchTimer) clearTimeout(elinkUserSearchTimer);
  if (elinkGroupSearchTimer) clearTimeout(elinkGroupSearchTimer);
  elinkUserSearchTimer = undefined;
  elinkGroupSearchTimer = undefined;
  elinkUserSearchKeyword = "";
  elinkGroupSearchKeyword = "";
}
function cleanupElinkDirectoryRequests() {
  clearElinkSearchTimers();
  cancelElinkQueries();
}
function selectedElinkUserNames() {
  return channelForm.elinkUserIds.map((userId) => elinkUserOptions.value.find((item) => item.userId === userId)?.name || "");
}
function selectedElinkGroupName() {
  return elinkGroupOptions.value.find((item) => String(item.id) === String(channelForm.elinkGroupId ?? ""))?.name;
}
function normalizeElinkUserIds(values: string[]) { return Array.from(new Set(values.map((value) => value.trim()).filter(Boolean))); }
function titleCase(value?: string) { return String(value || "").replace(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase().split("_").map((item) => item.charAt(0).toUpperCase() + item.slice(1)).join(""); }
function severityTag(value?: string) { return value === "CRITICAL" ? "danger" : value === "WARNING" ? "warning" : "info"; }
function incidentStatusTag(value?: string) { return value === "OPEN" ? "danger" : value === "ACKNOWLEDGED" ? "warning" : value === "RECOVERED" ? "success" : "info"; }
function deliveryStatusTag(value?: string) { return value === "SUCCEEDED" ? "success" : value === "DEAD" ? "danger" : value === "RETRY" ? "warning" : "info"; }
function prettyJson(value?: Record<string, unknown>) { return JSON.stringify(value || {}, null, 2); }
function errorMessage(error: any, fallback: string) { return error?.response?.data?.message || error?.message || fallback; }
function normalizeAlertTab(value: unknown) { const tab = String(value || "incidents"); return ALERT_TABS.has(tab) ? tab : "incidents"; }
function selectTab(value: string) {
  const tab = normalizeAlertTab(value);
  activeTab.value = tab;
  if (String(route.query.tab || "incidents") !== tab) void router.replace({ query: { ...route.query, tab } });
}
function isDialogCancellation(error: unknown) { return error === "cancel" || error === "close"; }
async function runAlertAction(action: () => Promise<void>, successKey?: string) {
  try {
    await action();
    if (successKey) ElMessage.success(t(successKey));
  } catch (error) {
    if (!isDialogCancellation(error)) ElMessage.error(errorMessage(error, t("web.alerts.actionFailed")));
  }
}
async function runAlertLoad(loadingState: { value: boolean }, action: () => Promise<void>, reportError: boolean) {
  loadingState.value = true;
  try {
    await action();
  } catch (error) {
    if (reportError) ElMessage.error(errorMessage(error, t("web.alerts.loadFailed")));
    else throw error;
  } finally {
    loadingState.value = false;
  }
}
function mergeOptions<T extends { id?: EntityId }>(current: T[], incoming: T[], selected: Array<EntityId | string>) {
  const selectedIds = new Set(selected.map((item) => String(item)));
  const merged = new Map<string, T>();
  for (const item of current) if (item.id != null && selectedIds.has(String(item.id))) merged.set(String(item.id), item);
  for (const item of incoming) if (item.id != null) merged.set(String(item.id), item);
  return Array.from(merged.values());
}
</script>

<style scoped>
.alert-center-page { display: flex; flex-direction: column; gap: 16px; }
.alert-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.alert-header h3 { margin: 0; font-size: 22px; }
.alert-header p { margin: 6px 0 0; color: var(--el-text-color-secondary); }
.alert-header__actions { display: flex; gap: 8px; }
.tenant-summary-toolbar { display: grid; grid-template-columns: minmax(220px, 1fr) auto; gap: 10px; margin-bottom: 12px; }
.alert-summary-band { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); border: 1px solid var(--el-border-color-light); background: var(--el-bg-color); }
.alert-summary-item { min-width: 0; padding: 14px 16px; border: 0; border-right: 1px solid var(--el-border-color-light); background: transparent; text-align: left; cursor: pointer; }
.alert-summary-item:last-child { border-right: 0; }
.alert-summary-item span { display: block; color: var(--el-text-color-secondary); font-size: 13px; }
.alert-summary-item strong { display: block; margin-top: 6px; font-size: 24px; line-height: 1; }
.alert-summary-item strong.is-danger, .alert-summary-item strong.is-critical { color: var(--el-color-danger); }
.alert-summary-item strong.is-warning { color: var(--el-color-warning); }
.alert-tabs { min-height: 460px; }
.alert-toolbar { display: grid; gap: 10px; align-items: center; margin-bottom: 12px; }
.alert-toolbar--incidents { grid-template-columns: minmax(220px, 1fr) repeat(5, minmax(125px, 170px)) auto auto; }
.alert-toolbar--rules { grid-template-columns: minmax(240px, 1fr) repeat(2, minmax(150px, 190px)) auto auto auto; }
.alert-toolbar--channels { grid-template-columns: minmax(240px, 1fr) minmax(150px, 190px) auto auto auto; }
.alert-table { width: 100%; }
.alert-pagination { display: flex; justify-content: flex-end; margin-top: 12px; }
.delivery-section { margin-top: 24px; padding-top: 18px; border-top: 1px solid var(--el-border-color-light); }
.delivery-section__heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.delivery-section h4 { margin: 0; font-size: 16px; }
.delivery-section p { margin: 4px 0 0; color: var(--el-text-color-secondary); }
.delivery-table { width: 100%; }
.delivery-cell { display: flex; min-width: 0; flex-direction: column; gap: 4px; line-height: 1.35; }
.delivery-cell__headline { display: flex; min-width: 0; align-items: center; gap: 6px; }
.delivery-cell__title, .delivery-cell__preview, .delivery-cell__meta { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.delivery-cell__title { color: var(--el-text-color-primary); font-weight: 500; }
.delivery-cell__preview, .delivery-cell__meta { color: var(--el-text-color-secondary); font-size: 12px; }
.delivery-status-cell { display: flex; align-items: flex-start; flex-direction: column; gap: 5px; color: var(--el-text-color-secondary); font-size: 12px; }
.delivery-detail { padding: 0 4px 20px; }
.delivery-detail__section { padding: 0 0 22px; border-bottom: 1px solid var(--el-border-color-light); margin-bottom: 22px; }
.delivery-detail__section:last-child { margin-bottom: 0; border-bottom: 0; }
.delivery-detail__heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.delivery-detail__heading h4 { margin: 0; font-size: 16px; }
.delivery-detail__tags { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; }
.delivery-detail__grid { display: grid; grid-template-columns: 130px minmax(0, 1fr); gap: 9px 14px; margin: 0; }
.delivery-detail__grid dt { color: var(--el-text-color-secondary); }
.delivery-detail__grid dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.delivery-detail__grid--content { margin-bottom: 10px; }
.delivery-detail__target { margin-top: 16px; }
.delivery-message-content, .delivery-result-block pre { max-height: 360px; overflow: auto; margin: 0; padding: 12px; border: 1px solid var(--el-border-color-light); background: var(--el-fill-color-light); font: 12px/1.6 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; overflow-wrap: anywhere; white-space: pre-wrap; }
.delivery-result-block { margin-top: 16px; }
.delivery-result-block > span { display: block; margin-bottom: 7px; color: var(--el-text-color-secondary); font-size: 13px; }
.delivery-result-block.is-error pre { border-color: var(--el-color-danger-light-7); background: var(--el-color-danger-light-9); color: var(--el-color-danger); }
.incident-detail__status { display: flex; align-items: center; gap: 8px; }
.incident-detail h4 { margin: 16px 0; font-size: 18px; }
.incident-detail__grid { display: grid; grid-template-columns: 140px 1fr; gap: 8px 14px; margin: 0; }
.incident-detail__grid dt { color: var(--el-text-color-secondary); }
.incident-detail__grid dd { margin: 0; }
.incident-detail__actions { display: flex; gap: 8px; margin: 18px 0; }
.incident-detail h5 { margin: 20px 0 8px; font-size: 15px; }
.incident-delivery-table { width: 100%; }
.alert-json { max-height: 300px; overflow: auto; margin: 0; padding: 12px; background: var(--el-fill-color-light); border: 1px solid var(--el-border-color-light); font: 12px/1.55 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; white-space: pre-wrap; word-break: break-all; }
.alert-form__grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 18px; }
.alert-form__wide { grid-column: 1 / -1; }
.alert-form__impact { margin: -6px 0 14px; }
.alert-form__toggles { display: flex; flex-wrap: wrap; gap: 4px 20px; margin: 4px 0 18px; }
.alert-channel-option { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 12px; }
.alert-channel-option__name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.alert-channel-option__tags { display: inline-flex; flex: 0 0 auto; gap: 6px; }
.channel-enabled-switch { margin-left: 24px; }
:deep(.alert-dialog) { display: flex; max-height: 90vh; flex-direction: column; }
:deep(.alert-dialog .el-dialog__body) { min-height: 0; overflow-y: auto; padding-top: 12px; }
:deep(.alert-dialog .el-dialog__footer) { flex: 0 0 auto; }
@media (max-width: 900px) {
  .alert-summary-band { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .alert-summary-item { border-bottom: 1px solid var(--el-border-color-light); }
  .alert-toolbar--incidents, .alert-toolbar--rules, .alert-toolbar--channels { grid-template-columns: 1fr 1fr; }
}
@media (max-width: 640px) {
  .alert-header, .delivery-section__heading { flex-direction: column; align-items: stretch; }
  .alert-header__actions, .tenant-summary-toolbar { display: grid; grid-template-columns: 1fr; }
  .alert-summary-band, .alert-form__grid, .alert-toolbar { grid-template-columns: 1fr; }
  .alert-summary-item { border-right: 0; }
  .incident-detail__grid, .delivery-detail__grid { grid-template-columns: 1fr; }
  .incident-detail__grid dd, .delivery-detail__grid dd { margin-bottom: 8px; }
  .delivery-detail__heading { align-items: flex-start; flex-direction: column; }
  .delivery-detail__tags { justify-content: flex-start; }
}
</style>
