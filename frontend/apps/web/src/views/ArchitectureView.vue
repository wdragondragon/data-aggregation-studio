<template>
  <div :class="['studio-page', 'architecture-page', { 'is-roadmap-entry': isRoadmapPage }]">
    <section v-if="!isRoadmapPage" class="architecture-hero">
      <div class="architecture-hero__content">
        <div class="architecture-hero__eyebrow">
          <span class="architecture-hero__pulse"></span>
          {{ copy.eyebrow }}
        </div>
        <h2>{{ copy.heading }}</h2>
        <p>{{ copy.description }}</p>
        <div class="architecture-hero__badges">
          <span v-for="badge in copy.heroBadges" :key="badge">{{ badge }}</span>
        </div>
      </div>
      <div class="architecture-hero__metrics">
        <article v-for="metric in copy.metrics" :key="metric.label">
          <strong>{{ metric.value }}</strong>
          <span>{{ metric.label }}</span>
        </article>
      </div>
    </section>

    <section v-else class="guide-hero">
      <div class="guide-hero__content">
        <span class="guide-hero__eyebrow">{{ t("web.dashboard.heroEyebrow") }}</span>
        <h2>{{ guideHeroTitle }}</h2>
        <p>{{ t("web.dashboard.heroDescription") }}</p>
      </div>

      <nav class="guide-hero__actions" :aria-label="t('routes.web.guide.title')">
        <RouterLink v-for="(action, index) in guideQuickActions" :key="action.path" :to="action.path">
          <span class="guide-hero__action-index">{{ String(index + 1).padStart(2, "0") }}</span>
          <span class="guide-hero__action-copy">
            <strong>{{ action.label }}</strong>
            <small>{{ action.caption }}</small>
          </span>
          <el-icon><ArrowRight /></el-icon>
        </RouterLink>
      </nav>
    </section>

    <section class="architecture-workbench">
      <div v-if="!isRoadmapPage" class="architecture-toolbar">
        <div class="architecture-toolbar__scenarios">
          <span>{{ copy.viewLabel }}</span>
          <el-radio-group v-model="activeScenario" size="small">
            <el-radio-button v-for="scenario in scenarios" :key="scenario.id" :value="scenario.id">
              {{ scenario.label }}
            </el-radio-button>
          </el-radio-group>
        </div>
        <div class="architecture-toolbar__actions">
          <el-select
            v-model="selectedNodeId"
            class="architecture-node-select"
            filterable
            :placeholder="copy.locateNode"
            @change="focusSelectedNode"
          >
            <el-option
              v-for="option in visibleNodeOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-button v-if="activeScenario === 'overview'" @click="toggleAllGroups">
            <el-icon><component :is="allGroupsExpanded ? Fold : Expand" /></el-icon>
            {{ allGroupsExpanded ? copy.collapseAll : copy.expandAll }}
          </el-button>
        </div>
      </div>

      <div v-if="!isRoadmapPage" class="architecture-scenario-note">
        <el-icon><InfoFilled /></el-icon>
        <span>{{ activeScenarioCopy.description }}</span>
      </div>

      <div :class="['architecture-workbench__body', { 'is-roadmap': activeScenario === 'roadmap' }]">
        <div
          ref="architectureSurfaceRef"
          class="architecture-layout-shell"
          role="region"
          :aria-label="activeScenarioCopy.ariaLabel"
        >
          <div v-if="!isRoadmapPage" class="architecture-legend">
            <span v-for="item in copy.legend" :key="item.kind">
              <i :class="`is-${item.kind}`"></i>{{ item.label }}
            </span>
          </div>

          <div v-if="activeScenario === 'roadmap'" class="architecture-roadmap">
            <section :class="['architecture-roadmap-header', { 'has-architecture-link': isRoadmapPage }]">
              <div>
                <span>{{ copy.roadmap.eyebrow }}</span>
                <h3>{{ copy.roadmap.title }}</h3>
                <p>{{ copy.roadmap.description }}</p>
              </div>
              <div v-if="isRoadmapPage" class="architecture-roadmap-header__aside">
                <RouterLink class="architecture-roadmap-architecture-link" to="/architecture">
                  <span>{{ roadmapNavigation.architectureEyebrow }}</span>
                  <strong>{{ roadmapNavigation.architectureTitle }}</strong>
                  <small>{{ roadmapNavigation.architectureDescription }}</small>
                  <em>
                    {{ roadmapNavigation.architectureAction }}
                    <el-icon><ArrowRight /></el-icon>
                  </em>
                </RouterLink>
              </div>
            </section>

            <section class="architecture-roadmap-system">
              <button
                type="button"
                :data-node-id="copy.roadmap.lineage.id"
                :class="['architecture-roadmap-crosscut', 'is-lineage', { 'is-selected': selectedNodeId === copy.roadmap.lineage.id }]"
                @click="selectNode(copy.roadmap.lineage.id)"
              >
                <span class="architecture-roadmap-crosscut__identity">
                  <span class="architecture-roadmap-lineage-symbol" aria-hidden="true">
                    <i v-for="index in 4" :key="index"></i>
                  </span>
                  <span class="architecture-roadmap-crosscut__label">{{ copy.roadmap.lineage.label }}</span>
                </span>
                <span class="architecture-roadmap-crosscut__content">
                  <strong>{{ copy.roadmap.lineage.title }}</strong>
                  <small>{{ copy.roadmap.lineage.description }}</small>
                </span>
                <span class="architecture-roadmap-crosscut__items">
                  <i v-for="item in copy.roadmap.lineage.items" :key="item">{{ item }}</i>
                </span>
              </button>

              <ol class="architecture-roadmap-track">
                <li v-for="(stage, index) in copy.roadmap.stages" :key="stage.id" :class="`is-${stage.tone}`">
                  <button
                    type="button"
                    :data-node-id="stage.id"
                    :class="['architecture-roadmap-stage-button', { 'is-active': activeRoadmapStage.id === stage.id, 'is-selected': selectedNodeId === stage.id }]"
                    :aria-expanded="activeRoadmapStage.id === stage.id"
                    @click="selectRoadmapStage(stage.id)"
                  >
                    <span class="architecture-roadmap-stage-button__index">{{ String(index + 1).padStart(2, '0') }}</span>
                    <span class="architecture-roadmap-stage-button__kind">{{ stage.shortTitle }}</span>
                    <strong>{{ stage.title }}</strong>
                    <small>{{ stage.summary }}</small>
                  </button>
                  <RouterLink
                    v-if="roadmapStageRoutes[stage.id]"
                    :to="roadmapStageRoutes[stage.id]!.path"
                    class="architecture-roadmap-stage-route"
                    @click.stop
                  >
                    <strong>{{ roadmapStageRoutes[stage.id]!.label }}</strong>
                    <el-icon><ArrowRight /></el-icon>
                  </RouterLink>
                </li>
              </ol>

              <transition name="architecture-roadmap-focus" mode="out-in">
                <section :key="activeRoadmapStage.id" :class="['architecture-roadmap-focus', `is-${activeRoadmapStage.tone}`]">
                  <div class="architecture-roadmap-focus__summary">
                    <span>{{ copy.roadmap.stageLabel }} {{ String(activeRoadmapStageIndex + 1).padStart(2, '0') }}</span>
                    <h4>{{ activeRoadmapStage.title }}</h4>
                    <p>{{ activeRoadmapStage.description }}</p>
                    <div class="architecture-roadmap-outcome">
                      <strong>{{ copy.roadmap.outcomeLabel }}</strong>
                      <span>{{ activeRoadmapStage.outcome }}</span>
                    </div>
                  </div>

                  <div class="architecture-roadmap-focus__capabilities">
                    <div class="architecture-roadmap-focus__capabilities-title">
                      <strong>{{ copy.roadmap.capabilitiesLabel }}</strong>
                      <span>{{ activeRoadmapStage.capabilities.length }}</span>
                    </div>
                    <div class="architecture-roadmap-capability-grid">
                      <button
                        v-for="capability in activeRoadmapStage.capabilities"
                        :key="capability.id"
                        type="button"
                        :data-node-id="capability.id"
                        :class="['architecture-roadmap-capability', { 'is-selected': selectedNodeId === capability.id }]"
                        @click="selectNode(capability.id)"
                      >
                        <span>{{ kindLabel(capability.kind) }}</span>
                        <strong>{{ capability.title }}</strong>
                        <small>{{ capability.summary }}</small>
                        <em v-if="capability.tags.length">{{ capability.tags.join(' · ') }}</em>
                      </button>
                    </div>
                  </div>
                </section>
              </transition>

              <button
                type="button"
                :data-node-id="copy.roadmap.alerts.id"
                :class="['architecture-roadmap-crosscut', 'is-alert', { 'is-selected': selectedNodeId === copy.roadmap.alerts.id }]"
                @click="selectNode(copy.roadmap.alerts.id)"
              >
                <span class="architecture-roadmap-crosscut__label">{{ copy.roadmap.alerts.label }}</span>
                <span class="architecture-roadmap-crosscut__content">
                  <strong>{{ copy.roadmap.alerts.title }}</strong>
                  <small>{{ copy.roadmap.alerts.description }}</small>
                </span>
                <span class="architecture-roadmap-crosscut__items">
                  <i v-for="item in copy.roadmap.alerts.items" :key="item">{{ item }}</i>
                </span>
              </button>
            </section>
          </div>

          <div v-else-if="activeScenario === 'overview'" class="architecture-overview">
            <section class="architecture-entry-band">
              <div class="architecture-zone-heading">
                <span class="architecture-zone-heading__kind">{{ kindLabel('entry') }}</span>
                <strong>{{ copy.overviewEntryTitle }}</strong>
                <small>{{ copy.overviewEntryDescription }}</small>
              </div>
              <div class="architecture-entry-grid">
                <button
                  v-for="item in overviewEntryNodes"
                  :key="item.id"
                  type="button"
                  :data-node-id="item.id"
                  :class="nodeCardClasses(item.id)"
                  @click="selectNode(item.id)"
                >
                  <span class="architecture-node-card__kind">{{ kindLabel(item.kind) }}</span>
                  <strong>{{ item.title }}</strong>
                  <small>{{ item.summary }}</small>
                </button>
              </div>
            </section>

            <div class="architecture-route-rail is-primary" aria-hidden="true">
              <span>{{ copy.edgeLabels.identity }}</span>
              <span>{{ copy.edgeLabels.userApi }}</span>
              <span>{{ copy.edgeLabels.openApi }}</span>
            </div>

            <section class="architecture-tier architecture-application-tier">
              <div class="architecture-tier-heading">
                <span>Application Layer</span>
                <strong>{{ copy.applicationTierTitle }}</strong>
                <small>{{ copy.applicationTierDescription }}</small>
              </div>

              <div class="architecture-application-map">
                <section :class="['architecture-zone', `is-${overviewControlZone.kind}`]">
                  <div class="architecture-zone-heading">
                    <span class="architecture-zone-heading__kind">{{ kindLabel(overviewControlZone.kind) }}</span>
                    <strong>{{ overviewControlZone.title }}</strong>
                    <small>{{ overviewControlZone.description }}</small>
                  </div>

                  <div class="architecture-zone__nodes">
                    <template v-for="item in overviewControlZone.items" :key="item.id">
                      <div v-if="item.edgeLabel" :class="['architecture-node-connector', `is-${item.edgeKind ?? 'secondary'}`]">
                        <span>{{ item.edgeLabel }}</span>
                      </div>
                      <div class="architecture-node-stack">
                        <button
                          type="button"
                          :data-node-id="item.id"
                          :class="nodeCardClasses(item.id)"
                          :aria-expanded="item.collapsible ? expandedGroups.has(item.id) : undefined"
                          @click="selectNode(item.id, item.collapsible)"
                        >
                          <span class="architecture-node-card__kind">{{ kindLabel(item.kind) }}</span>
                          <strong>{{ item.title }}</strong>
                          <small>{{ item.summary }}</small>
                          <span v-if="item.collapsible" class="architecture-node-card__toggle">
                            <el-icon><component :is="expandedGroups.has(item.id) ? Fold : Expand" /></el-icon>
                            {{ expandedGroups.has(item.id) ? copy.collapseNode : copy.expandNode }}
                          </span>
                        </button>
                        <transition name="architecture-expand">
                          <div v-if="item.children?.length && expandedGroups.has(item.id)" class="architecture-node-children">
                            <button
                              v-for="child in item.children"
                              :key="child.id"
                              type="button"
                              :data-node-id="child.id"
                              :class="nodeCardClasses(child.id, true)"
                              @click.stop="selectNode(child.id)"
                            >
                              <span class="architecture-node-card__kind">{{ kindLabel(child.kind) }}</span>
                              <strong>{{ child.title }}</strong>
                              <small>{{ child.summary }}</small>
                            </button>
                          </div>
                        </transition>
                      </div>
                    </template>
                  </div>
                </section>

                <div class="architecture-server-worker-bridge">
                  <span>{{ copy.serverWorkerRelation }}</span>
                  <i aria-hidden="true"></i>
                  <div>
                    <code>runtimeClusterId</code>
                    <code>targetClusterId</code>
                  </div>
                </div>

                <div class="architecture-runtime-grid">
                  <section
                    v-for="zone in overviewRuntimeZones"
                    :key="zone.id"
                    :class="['architecture-zone', `is-${zone.kind}`]"
                  >
                    <div class="architecture-zone-heading">
                      <span class="architecture-zone-heading__kind">{{ kindLabel(zone.kind) }}</span>
                      <strong>{{ zone.title }}</strong>
                      <small>{{ zone.description }}</small>
                    </div>

                    <div class="architecture-zone__nodes">
                      <template v-for="item in zone.items" :key="item.id">
                        <div v-if="item.edgeLabel" :class="['architecture-node-connector', `is-${item.edgeKind ?? 'secondary'}`]">
                          <span>{{ item.edgeLabel }}</span>
                        </div>
                        <div class="architecture-node-stack">
                          <button
                            type="button"
                            :data-node-id="item.id"
                            :class="nodeCardClasses(item.id)"
                            :aria-expanded="item.collapsible ? isGroupExpanded(item.id) : undefined"
                            @click="selectNode(item.id, item.collapsible)"
                          >
                            <span class="architecture-node-card__kind">{{ kindLabel(item.kind) }}</span>
                            <strong>{{ item.title }}</strong>
                            <small>{{ item.summary }}</small>
                            <span v-if="item.collapsible" class="architecture-node-card__toggle">
                              <el-icon><component :is="isGroupExpanded(item.id) ? Fold : Expand" /></el-icon>
                              {{ isGroupExpanded(item.id) ? copy.collapseNode : copy.expandNode }}
                            </span>
                          </button>
                        </div>
                      </template>
                    </div>
                  </section>

                  <transition name="architecture-expand">
                    <section v-if="workerRuntimeExpanded" class="architecture-worker-shared-capabilities">
                      <div class="architecture-worker-shared-capabilities__heading">
                        <span>{{ copy.workerSharedCapabilitiesEyebrow }}</span>
                        <strong>{{ copy.workerSharedCapabilitiesTitle }}</strong>
                        <small>{{ copy.workerSharedCapabilitiesDescription }}</small>
                      </div>
                      <div class="architecture-worker-shared-capabilities__grid">
                        <button
                          v-for="child in overviewWorkerCapabilities"
                          :key="child.id"
                          type="button"
                          :data-node-id="child.id"
                          :class="nodeCardClasses(child.id, true)"
                          @click="selectNode(child.id)"
                        >
                          <span class="architecture-node-card__kind">{{ kindLabel(child.kind) }}</span>
                          <strong>{{ child.title }}</strong>
                          <small>{{ child.summary }}</small>
                        </button>
                      </div>
                    </section>
                  </transition>
                </div>
              </div>
            </section>

            <div class="architecture-tier-connector is-shared">
              <div class="architecture-shared-links">
                <span v-for="link in overviewSharedLinks" :key="link.source" :class="`is-${link.kind}`">
                  <strong>{{ link.source }}</strong>{{ link.label }}
                </span>
              </div>
              <i aria-hidden="true"></i>
              <strong>{{ copy.sharedDependencyRelation }}</strong>
            </div>

            <section class="architecture-tier architecture-shared-tier">
              <div class="architecture-tier-heading">
                <span>Shared Infrastructure</span>
                <strong>{{ copy.sharedTierTitle }}</strong>
                <small>{{ copy.sharedTierDescription }}</small>
              </div>

              <section :class="['architecture-zone', `is-${overviewSharedZone.kind}`]">
                <div class="architecture-zone-heading">
                  <span class="architecture-zone-heading__kind">{{ kindLabel(overviewSharedZone.kind) }}</span>
                  <strong>{{ overviewSharedZone.title }}</strong>
                  <small>{{ overviewSharedZone.description }}</small>
                </div>

                <div class="architecture-zone__nodes is-horizontal">
                  <template v-for="item in overviewSharedZone.items" :key="item.id">
                    <div class="architecture-node-stack">
                      <button
                        type="button"
                        :data-node-id="item.id"
                        :class="nodeCardClasses(item.id)"
                        :aria-expanded="item.collapsible ? expandedGroups.has(item.id) : undefined"
                        @click="selectNode(item.id, item.collapsible)"
                      >
                        <span class="architecture-node-card__kind">{{ kindLabel(item.kind) }}</span>
                        <strong>{{ item.title }}</strong>
                        <small>{{ item.summary }}</small>
                        <span v-if="item.collapsible" class="architecture-node-card__toggle">
                          <el-icon><component :is="expandedGroups.has(item.id) ? Fold : Expand" /></el-icon>
                          {{ expandedGroups.has(item.id) ? copy.collapseNode : copy.expandNode }}
                        </span>
                      </button>
                    </div>
                  </template>
                </div>

                <transition name="architecture-expand">
                  <div v-if="expandedGroups.has('object-storage')" class="architecture-shared-children">
                    <button
                      v-for="child in overviewStorageChildren"
                      :key="child.id"
                      type="button"
                      :data-node-id="child.id"
                      :class="nodeCardClasses(child.id, true)"
                      @click.stop="selectNode(child.id)"
                    >
                      <span class="architecture-node-card__kind">{{ kindLabel(child.kind) }}</span>
                      <strong>{{ child.title }}</strong>
                      <small>{{ child.summary }}</small>
                    </button>
                  </div>
                </transition>
              </section>
            </section>

            <div class="architecture-tier-connector is-downstream">
              <strong>{{ copy.workerDownstreamRelation }}</strong>
              <i aria-hidden="true"></i>
            </div>

            <section class="architecture-tier architecture-downstream-tier">
              <div class="architecture-tier-heading">
                <span>Execution Targets</span>
                <strong>{{ copy.downstreamTierTitle }}</strong>
                <small>{{ copy.downstreamTierDescription }}</small>
              </div>
              <div class="architecture-downstream-grid">
                <button
                  v-for="item in overviewDownstreamNodes"
                  :key="item.id"
                  type="button"
                  :data-node-id="item.id"
                  :class="nodeCardClasses(item.id)"
                  @click="selectNode(item.id)"
                >
                  <span class="architecture-node-card__kind">{{ kindLabel(item.kind) }}</span>
                  <strong>{{ item.title }}</strong>
                  <small>{{ item.summary }}</small>
                </button>
              </div>
            </section>
          </div>

          <div v-else class="architecture-flow-layout">
            <div class="architecture-flow-boundary">
              <span>{{ activeFlow.boundary }}</span>
            </div>

            <div class="architecture-flow-stage-list">
              <template v-for="(stage, stageIndex) in activeFlow.stages" :key="stage.id">
                <section :class="['architecture-flow-stage', `is-${stage.kind}`]">
                  <div class="architecture-flow-stage__heading">
                    <span>{{ String(stageIndex + 1).padStart(2, '0') }} · {{ kindLabel(stage.kind) }}</span>
                    <strong>{{ stage.title }}</strong>
                    <small>{{ stage.description }}</small>
                  </div>

                  <div :class="['architecture-flow-stage__nodes', { 'is-parallel': stage.layout === 'parallel' }]">
                    <template v-for="(item, itemIndex) in stage.items" :key="item.id">
                      <div
                        v-if="itemIndex > 0 && stage.layout !== 'parallel'"
                        :class="['architecture-flow-inline-connector', `is-${item.edgeKind ?? 'primary'}`]"
                      >
                        <span>{{ item.edgeLabel }}</span>
                        <i aria-hidden="true"></i>
                      </div>
                      <div class="architecture-node-stack">
                        <button
                          type="button"
                          :data-node-id="item.id"
                          :class="nodeCardClasses(item.id)"
                          :aria-expanded="item.collapsible ? expandedGroups.has(item.id) : undefined"
                          @click="selectNode(item.id, item.collapsible)"
                        >
                          <span class="architecture-node-card__kind">{{ kindLabel(item.kind) }}</span>
                          <strong>{{ item.title }}</strong>
                          <small>{{ item.summary }}</small>
                          <span v-if="item.collapsible" class="architecture-node-card__toggle">
                            <el-icon><component :is="expandedGroups.has(item.id) ? Fold : Expand" /></el-icon>
                            {{ expandedGroups.has(item.id) ? copy.collapseNode : copy.expandNode }}
                          </span>
                        </button>
                        <transition name="architecture-expand">
                          <div v-if="item.children?.length && expandedGroups.has(item.id)" class="architecture-node-children">
                            <button
                              v-for="child in item.children"
                              :key="child.id"
                              type="button"
                              :data-node-id="child.id"
                              :class="nodeCardClasses(child.id, true)"
                              @click.stop="selectNode(child.id)"
                            >
                              <span class="architecture-node-card__kind">{{ kindLabel(child.kind) }}</span>
                              <strong>{{ child.title }}</strong>
                              <small>{{ child.summary }}</small>
                            </button>
                          </div>
                        </transition>
                      </div>
                    </template>
                  </div>
                </section>

                <div
                  v-if="stage.outgoingLabel"
                  :class="['architecture-tier-connector', `is-${stage.outgoingKind ?? 'primary'}`]"
                >
                  <strong>{{ stage.outgoingLabel }}</strong>
                  <i aria-hidden="true"></i>
                </div>
              </template>
            </div>

            <div class="architecture-tier-connector is-shared">
              <strong>{{ activeFlow.sharedRelation }}</strong>
              <i aria-hidden="true"></i>
            </div>

            <section class="architecture-tier architecture-flow-shared-tier">
              <div class="architecture-tier-heading">
                <span>Shared Infrastructure</span>
                <strong>{{ activeFlow.sharedTitle }}</strong>
                <small>{{ activeFlow.sharedDescription }}</small>
              </div>

              <div class="architecture-flow-shared-grid">
                <div v-for="item in activeFlow.shared" :key="item.id" class="architecture-node-stack">
                  <button
                    type="button"
                    :data-node-id="item.id"
                    :class="nodeCardClasses(item.id)"
                    :aria-expanded="item.collapsible ? expandedGroups.has(item.id) : undefined"
                    @click="selectNode(item.id, item.collapsible)"
                  >
                    <span class="architecture-node-card__kind">{{ kindLabel(item.kind) }}</span>
                    <strong>{{ item.title }}</strong>
                    <small>{{ item.summary }}</small>
                    <span v-if="item.collapsible" class="architecture-node-card__toggle">
                      <el-icon><component :is="expandedGroups.has(item.id) ? Fold : Expand" /></el-icon>
                      {{ expandedGroups.has(item.id) ? copy.collapseNode : copy.expandNode }}
                    </span>
                  </button>
                </div>
              </div>

              <template v-for="item in activeFlow.shared" :key="`${item.id}-children`">
                <transition name="architecture-expand">
                  <div v-if="item.children?.length && expandedGroups.has(item.id)" class="architecture-shared-children">
                    <button
                      v-for="child in item.children"
                      :key="child.id"
                      type="button"
                      :data-node-id="child.id"
                      :class="nodeCardClasses(child.id, true)"
                      @click.stop="selectNode(child.id)"
                    >
                      <span class="architecture-node-card__kind">{{ kindLabel(child.kind) }}</span>
                      <strong>{{ child.title }}</strong>
                      <small>{{ child.summary }}</small>
                    </button>
                  </div>
                </transition>
              </template>
            </section>
          </div>
        </div>

        <aside v-if="activeScenario !== 'roadmap'" class="architecture-detail-panel">
          <div class="architecture-detail-panel__header">
            <span :class="['architecture-kind-badge', `is-${selectedDetail.kind}`]">
              {{ kindLabel(selectedDetail.kind) }}
            </span>
            <h3>{{ selectedDetail.title }}</h3>
            <p>{{ selectedDetail.summary }}</p>
          </div>

          <section>
            <h4>{{ copy.responsibilities }}</h4>
            <ul>
              <li v-for="item in selectedDetail.responsibilities" :key="item">{{ item }}</li>
            </ul>
          </section>

          <section v-if="selectedDetail.boundaries.length">
            <h4>{{ copy.boundaries }}</h4>
            <ul class="architecture-boundary-list">
              <li v-for="item in selectedDetail.boundaries" :key="item">{{ item }}</li>
            </ul>
          </section>

          <section v-if="selectedDetail.tags.length">
            <h4>{{ copy.keyConfig }}</h4>
            <div class="architecture-tag-list">
              <code v-for="tag in selectedDetail.tags" :key="tag">{{ tag }}</code>
            </div>
          </section>

          <button
            v-if="selectedDetail.collapsible"
            type="button"
            class="architecture-expand-button"
            @click="toggleGroup(selectedDetail.id)"
          >
            <el-icon><component :is="isGroupExpanded(selectedDetail.id) ? Fold : Expand" /></el-icon>
            {{ isGroupExpanded(selectedDetail.id) ? copy.collapseNode : copy.expandNode }}
          </button>
        </aside>
      </div>
    </section>

  </div>
</template>

<script setup lang="ts">
import { ArrowRight, Expand, Fold, InfoFilled } from "@element-plus/icons-vue";
import { computed, nextTick, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { RouterLink, useRoute } from "vue-router";
import { useAuthStore } from "@/stores/auth";

type ScenarioId = "roadmap" | "overview" | "sync" | "async" | "plugins";
type FlowScenarioId = Exclude<ScenarioId, "roadmap" | "overview">;
type NodeKind = "entry" | "control" | "runtime" | "state" | "middleware" | "external";
type EdgeKind = "primary" | "secondary" | "storage" | "plugin";
type RoadmapTone = "source" | "model" | "integration" | "development" | "quality" | "service";

interface NodeCopy {
  title: string;
  summary: string;
}

interface NodeDetail extends NodeCopy {
  id: string;
  kind: NodeKind;
  responsibilities: string[];
  boundaries: string[];
  tags: string[];
  collapsible?: boolean;
}

interface ArchitectureNode extends NodeCopy {
  id: string;
  kind: NodeKind;
  collapsible?: boolean;
  edgeLabel?: string;
  edgeKind?: EdgeKind;
  children?: ArchitectureNode[];
}

interface OverviewZone {
  id: string;
  title: string;
  description: string;
  kind: NodeKind;
  items: ArchitectureNode[];
}

interface FlowStage {
  id: string;
  title: string;
  description: string;
  kind: NodeKind;
  items: ArchitectureNode[];
  outgoingLabel?: string;
  outgoingKind?: EdgeKind;
  layout?: "sequence" | "parallel";
}

interface FlowScenario {
  boundary: string;
  stages: FlowStage[];
  sharedTitle: string;
  sharedDescription: string;
  sharedRelation: string;
  shared: ArchitectureNode[];
}

interface RoadmapCapability {
  id: string;
  title: string;
  summary: string;
  kind: NodeKind;
  tags: string[];
}

interface RoadmapStage {
  id: string;
  tone: RoadmapTone;
  kind: NodeKind;
  shortTitle: string;
  title: string;
  summary: string;
  description: string;
  outcome: string;
  tags: string[];
  capabilities: RoadmapCapability[];
}

interface RoadmapCrosscut {
  id: string;
  label: string;
  title: string;
  description: string;
  kind: NodeKind;
  items: string[];
  tags: string[];
}

interface RoadmapCopy {
  eyebrow: string;
  title: string;
  description: string;
  stageLabel: string;
  outcomeLabel: string;
  capabilitiesLabel: string;
  metrics: Array<{ value: string; label: string }>;
  stages: RoadmapStage[];
  lineage: RoadmapCrosscut;
  alerts: RoadmapCrosscut;
}

interface RoadmapStageRoute {
  path: string;
  label: string;
}

interface ArchitectureCopy {
  eyebrow: string;
  heading: string;
  description: string;
  heroBadges: string[];
  metrics: Array<{ value: string; label: string }>;
  viewLabel: string;
  locateNode: string;
  overviewEntryTitle: string;
  overviewEntryDescription: string;
  applicationTierTitle: string;
  applicationTierDescription: string;
  workerSharedCapabilitiesEyebrow: string;
  workerSharedCapabilitiesTitle: string;
  workerSharedCapabilitiesDescription: string;
  sharedTierTitle: string;
  sharedTierDescription: string;
  downstreamTierTitle: string;
  downstreamTierDescription: string;
  serverWorkerRelation: string;
  sharedDependencyRelation: string;
  workerDownstreamRelation: string;
  expandAll: string;
  collapseAll: string;
  expandNode: string;
  collapseNode: string;
  responsibilities: string;
  boundaries: string;
  keyConfig: string;
  kindLabels: Record<NodeKind, string>;
  legend: Array<{ kind: NodeKind; label: string }>;
  scenarios: Record<ScenarioId, { label: string; description: string; ariaLabel: string }>;
  roadmap: RoadmapCopy;
  flowSections: Record<FlowScenarioId, {
    stages: Array<{ title: string; description: string }>;
    sharedTitle: string;
    sharedDescription: string;
    sharedRelation: string;
  }>;
  groups: {
    control: string;
    shared: string;
    clusterA: string;
    clusterB: string;
    workerOnly: string;
  };
  nodes: Record<string, NodeCopy>;
  details: Record<string, Omit<NodeDetail, "id" | "title" | "summary">>;
  edgeLabels: Record<string, string>;
}

const { locale, t } = useI18n();
const route = useRoute();
const authStore = useAuthStore();
const isRoadmapPage = computed(() => route.name === "guide");
const guideHeroTitle = computed(() => authStore.username
  ? t("web.dashboard.heroGreetingUser", { username: authStore.username })
  : t("web.dashboard.heroGreeting"));
const guideQuickActions = computed(() => [
  {
    path: "/datasources",
    label: t("web.dashboard.heroActionDatasource"),
    caption: t("routes.web.datasources.menuCaption"),
  },
  {
    path: "/collection-tasks/new",
    label: t("web.dashboard.heroActionCollectionTask"),
    caption: t("routes.web.collectionTasks.menuCaption"),
  },
  {
    path: "/workflows/new",
    label: t("web.dashboard.heroActionWorkflow"),
    caption: t("routes.web.workflows.menuCaption"),
  },
  {
    path: "/data-development",
    label: t("web.dashboard.heroActionScript"),
    caption: t("routes.web.dataDevelopment.menuCaption"),
  },
]);
const activeScenario = ref<ScenarioId>(isRoadmapPage.value ? "roadmap" : "overview");
const selectedNodeId = ref(isRoadmapPage.value ? "roadmap-datasource" : "server");
const activeRoadmapStageId = ref("roadmap-datasource");
const expandedGroups = ref(new Set<string>());
const architectureSurfaceRef = ref<HTMLDivElement>();

const nodeKinds: Record<string, NodeKind> = {
  browser: "entry",
  "external-api": "entry",
  gateway: "middleware",
  server: "control",
  "server-access": "control",
  "server-scheduler": "control",
  "server-router": "control",
  "server-ops": "control",
  "flink-planner": "control",
  mysql: "state",
  redis: "middleware",
  nacos: "middleware",
  "object-storage": "state",
  "run-log": "state",
  "plugin-repo": "state",
  "script-artifact": "state",
  "cluster-endpoint-a": "middleware",
  "cluster-endpoint-b": "middleware",
  "worker-a": "runtime",
  "worker-b": "runtime",
  "worker-dispatch": "runtime",
  "worker-datasource": "runtime",
  "worker-task": "runtime",
  "worker-script": "runtime",
  "worker-service": "runtime",
  "plugin-loader": "runtime",
  "datasource-a": "external",
  "datasource-b": "external",
  "flink-gateway": "middleware",
  "dispatch-row": "state",
  "run-record": "state",
  "release-builder": "external",
  "plugin-manifest": "state",
  "local-cache": "runtime",
  "job-container": "runtime",
  "local-plugin-dir": "external",
};

const expandableNodeIds = new Set(["server", "worker-a", "worker-b", "object-storage"]);
const childToGroup: Record<string, string> = {
  "server-access": "server",
  "server-scheduler": "server",
  "server-router": "server",
  "server-ops": "server",
  "worker-dispatch": "worker-a",
  "worker-datasource": "worker-a",
  "worker-task": "worker-a",
  "worker-script": "worker-a",
  "worker-service": "worker-a",
  "plugin-loader": "worker-a",
  "run-log": "object-storage",
  "plugin-repo": "object-storage",
  "script-artifact": "object-storage",
};

const copy = computed<ArchitectureCopy>(() => (locale.value === "zh-CN" ? zhCopy : enCopy));
const roadmapNavigation = computed(() => (locale.value === "zh-CN"
  ? {
      architectureEyebrow: "深入了解系统",
      architectureTitle: "平台技术架构",
      architectureDescription: "了解 Server、Worker 集群、中间件、MySQL、OSS 以及同步与异步执行链。",
      architectureAction: "进入技术架构",
    }
  : {
      architectureEyebrow: "Explore the system",
      architectureTitle: "Platform technical architecture",
      architectureDescription: "Explore Server, Worker clusters, middleware, MySQL, OSS, and synchronous or asynchronous execution paths.",
      architectureAction: "Open architecture",
    }));
const roadmapStageRoutes = computed<Record<string, RoadmapStageRoute>>(() => (locale.value === "zh-CN"
  ? {
      "roadmap-datasource": { path: "/datasources", label: "进入数据源管理" },
      "roadmap-model": { path: "/models", label: "进入模型管理" },
      "roadmap-integration": { path: "/collection-tasks", label: "进入采集融合任务" },
      "roadmap-development": { path: "/data-development", label: "进入数据开发" },
      "roadmap-quality": { path: "/quality-tasks", label: "进入质量治理" },
      "roadmap-service": { path: "/data-services", label: "进入数据服务" },
    }
  : {
      "roadmap-datasource": { path: "/datasources", label: "Open datasources" },
      "roadmap-model": { path: "/models", label: "Open models" },
      "roadmap-integration": { path: "/collection-tasks", label: "Open ingestion and fusion" },
      "roadmap-development": { path: "/data-development", label: "Open data development" },
      "roadmap-quality": { path: "/quality-tasks", label: "Open quality governance" },
      "roadmap-service": { path: "/data-services", label: "Open data services" },
    }));
const activeRoadmapStage = computed(() => copy.value.roadmap.stages.find((stage) => stage.id === activeRoadmapStageId.value)
  ?? copy.value.roadmap.stages[0]!);
const activeRoadmapStageIndex = computed(() => Math.max(0, copy.value.roadmap.stages.findIndex((stage) => stage.id === activeRoadmapStage.value.id)));
const roadmapChildToStage = computed<Record<string, string>>(() => Object.fromEntries(
  copy.value.roadmap.stages.flatMap((stage) => stage.capabilities.map((capability) => [capability.id, stage.id])),
));
const roadmapNodeDetails = computed<Record<string, NodeDetail>>(() => {
  const details: Record<string, NodeDetail> = {};
  for (const stage of copy.value.roadmap.stages) {
    details[stage.id] = {
      id: stage.id,
      title: stage.title,
      summary: stage.summary,
      kind: stage.kind,
      responsibilities: [stage.description, ...stage.capabilities.map((capability) => capability.title)],
      boundaries: [stage.outcome],
      tags: stage.tags,
    };
    for (const capability of stage.capabilities) {
      details[capability.id] = {
        id: capability.id,
        title: capability.title,
        summary: capability.summary,
        kind: capability.kind,
        responsibilities: [capability.summary],
        boundaries: [],
        tags: capability.tags,
      };
    }
  }
  for (const crosscut of [copy.value.roadmap.lineage, copy.value.roadmap.alerts]) {
    details[crosscut.id] = {
      id: crosscut.id,
      title: crosscut.title,
      summary: crosscut.description,
      kind: crosscut.kind,
      responsibilities: crosscut.items,
      boundaries: [],
      tags: crosscut.tags,
    };
  }
  return details;
});
const scenarios = computed(() => (Object.keys(copy.value.scenarios) as ScenarioId[])
  .filter((id) => id !== "roadmap")
  .map((id) => ({
    id,
    label: copy.value.scenarios[id].label,
  })));
const activeScenarioCopy = computed(() => copy.value.scenarios[activeScenario.value]);
const allGroupsExpanded = computed(() => ["server", "worker-a", "object-storage"].every((id) => expandedGroups.value.has(id)));
const overviewEntryNodes = computed(() => ["browser", "external-api", "gateway"].map((id) => architectureNode(id)));
const overviewZones = computed<OverviewZone[]>(() => [
  {
    id: "control",
    title: copy.value.groups.control,
    description: copy.value.nodes.server.summary,
    kind: "control",
    items: [
      architectureNode("server", {
        collapsible: true,
        children: ["server-access", "server-scheduler", "server-router", "server-ops"].map((id) => architectureNode(id)),
      }),
      architectureNode("flink-planner", { edgeLabel: copy.value.edgeLabels.plan, edgeKind: "secondary" }),
    ],
  },
  {
    id: "shared",
    title: copy.value.groups.shared,
    description: copy.value.nodes["object-storage"].summary,
    kind: "state",
    items: [
      architectureNode("mysql"),
      architectureNode("redis", { edgeLabel: copy.value.edgeLabels.cache, edgeKind: "secondary" }),
      architectureNode("nacos", { edgeLabel: copy.value.edgeLabels.config, edgeKind: "secondary" }),
      architectureNode("object-storage", {
        collapsible: true,
        edgeLabel: copy.value.edgeLabels.pluginAndArtifact,
        edgeKind: "storage",
        children: ["run-log", "plugin-repo", "script-artifact"].map((id) => architectureNode(id)),
      }),
    ],
  },
  {
    id: "cluster-a",
    title: copy.value.groups.clusterA,
    description: copy.value.nodes["worker-a"].summary,
    kind: "runtime",
    items: [
      architectureNode("cluster-endpoint-a"),
      architectureNode("worker-a", {
        collapsible: true,
        edgeLabel: copy.value.edgeLabels.internalApi,
        edgeKind: "primary",
        children: ["worker-dispatch", "worker-datasource", "worker-task", "worker-script", "worker-service", "plugin-loader"].map((id) => architectureNode(id)),
      }),
    ],
  },
  {
    id: "cluster-b",
    title: copy.value.groups.clusterB,
    description: copy.value.nodes["worker-b"].summary,
    kind: "runtime",
    items: [
      architectureNode("cluster-endpoint-b"),
      architectureNode("worker-b", { collapsible: true, edgeLabel: copy.value.edgeLabels.internalApi, edgeKind: "primary" }),
    ],
  },
]);
const overviewControlZone = computed(() => overviewZones.value.find((zone) => zone.id === "control")!);
const overviewRuntimeZones = computed(() => overviewZones.value.filter((zone) => zone.id.startsWith("cluster-")));
const overviewWorkerCapabilities = computed(() => overviewRuntimeZones.value
  .flatMap((zone) => zone.items)
  .find((item) => item.id === "worker-a")?.children ?? []);
const workerRuntimeExpanded = computed(() => isGroupExpanded("worker-a"));
const overviewSharedZone = computed(() => overviewZones.value.find((zone) => zone.id === "shared")!);
const overviewStorageChildren = computed(() => overviewSharedZone.value.items.find((item) => item.id === "object-storage")?.children ?? []);
const overviewDownstreamNodes = computed(() => ["datasource-a", "datasource-b", "flink-gateway"].map((id) => architectureNode(id)));
const overviewSharedLinks = computed(() => [
  {
    source: "studio-server",
    label: `${copy.value.edgeLabels.metadata} / ${copy.value.edgeLabels.cache} / ${copy.value.edgeLabels.config}`,
    kind: "storage" as EdgeKind,
  },
  {
    source: "studio-worker",
    label: `${copy.value.edgeLabels.claimWriteback} / ${copy.value.edgeLabels.pluginAndArtifact}`,
    kind: "plugin" as EdgeKind,
  },
]);
const activeFlow = computed<FlowScenario>(() => isFlowScenario(activeScenario.value)
  ? buildFlowScenario(activeScenario.value)
  : emptyFlowScenario());
const visibleNodeIds = computed(() => {
  if (activeScenario.value === "roadmap") {
    return [
      copy.value.roadmap.lineage.id,
      ...copy.value.roadmap.stages.map((stage) => stage.id),
      ...activeRoadmapStage.value.capabilities.map((capability) => capability.id),
      copy.value.roadmap.alerts.id,
    ];
  }
  if (activeScenario.value !== "overview") {
    const ids: string[] = [];
    for (const stage of activeFlow.value.stages) ids.push(...collectVisibleNodeIds(stage.items));
    ids.push(...collectVisibleNodeIds(activeFlow.value.shared));
    return ids;
  }
  const ids = overviewEntryNodes.value.map((item) => item.id);
  for (const zone of overviewZones.value) {
    for (const item of zone.items) {
      ids.push(item.id);
      if (expandedGroups.value.has(item.id)) ids.push(...(item.children ?? []).map((child) => child.id));
    }
  }
  ids.push(...overviewDownstreamNodes.value.map((item) => item.id));
  return ids;
});
const visibleNodeOptions = computed(() => visibleNodeIds.value
  .map((id) => ({ value: id, label: roadmapNodeDetails.value[id]?.title ?? copy.value.nodes[id]?.title ?? id }))
  .filter((item, index, items) => items.findIndex((candidate) => candidate.value === item.value) === index));
const selectedDetail = computed<NodeDetail>(() => {
  const id = selectedNodeId.value;
  if (roadmapNodeDetails.value[id]) return roadmapNodeDetails.value[id]!;
  const nodeCopy = copy.value.nodes[id] ?? copy.value.nodes.server;
  const detail = copy.value.details[id] ?? (id === "worker-b" ? copy.value.details["worker-a"] : undefined);
  return {
    id,
    title: nodeCopy.title,
    summary: nodeCopy.summary,
    kind: detail?.kind ?? nodeKinds[id] ?? "external",
    responsibilities: detail?.responsibilities ?? [nodeCopy.summary],
    boundaries: detail?.boundaries ?? [],
    tags: detail?.tags ?? [],
    collapsible: detail?.collapsible ?? expandableNodeIds.has(id),
  };
});

function toggleGroup(id: string) {
  const groupId = id === "worker-b" ? "worker-a" : id;
  const next = new Set(expandedGroups.value);
  if (next.has(groupId)) next.delete(groupId);
  else next.add(groupId);
  expandedGroups.value = next;
}

function isGroupExpanded(id: string) {
  return expandedGroups.value.has(id === "worker-b" ? "worker-a" : id);
}

function toggleAllGroups() {
  expandedGroups.value = allGroupsExpanded.value
    ? new Set()
    : new Set(["server", "worker-a", "object-storage"]);
}

function kindLabel(kind: NodeKind) {
  return copy.value.kindLabels[kind];
}

function architectureNode(id: string, options: Partial<ArchitectureNode> = {}): ArchitectureNode {
  const content = copy.value.nodes[id] ?? { title: id, summary: "" };
  return {
    id,
    kind: nodeKinds[id] ?? "external",
    title: content.title,
    summary: content.summary,
    ...options,
  };
}

function collectVisibleNodeIds(nodes: ArchitectureNode[]) {
  const ids: string[] = [];
  for (const node of nodes) {
    ids.push(node.id);
    if (node.children?.length && expandedGroups.value.has(node.id)) {
      ids.push(...node.children.map((child) => child.id));
    }
  }
  return ids;
}

function isFlowScenario(scenario: ScenarioId): scenario is FlowScenarioId {
  return scenario === "sync" || scenario === "async" || scenario === "plugins";
}

function emptyFlowScenario(): FlowScenario {
  return {
    boundary: "",
    stages: [],
    sharedTitle: "",
    sharedDescription: "",
    sharedRelation: "",
    shared: [],
  };
}

function buildFlowScenario(scenario: FlowScenarioId): FlowScenario {
  if (scenario === "sync") {
    const flowCopy = copy.value.flowSections.sync;
    return {
      boundary: `${copy.value.groups.control} → ${copy.value.groups.clusterA}`,
      stages: [
        {
          id: "sync-entry",
          kind: "entry",
          ...flowCopy.stages[0]!,
          items: [
            architectureNode("external-api"),
            architectureNode("gateway", { edgeLabel: copy.value.edgeLabels.identity, edgeKind: "primary" }),
          ],
          outgoingLabel: copy.value.edgeLabels.resolveResource,
          outgoingKind: "primary",
        },
        {
          id: "sync-control",
          kind: "control",
          ...flowCopy.stages[1]!,
          items: [architectureNode("server", {
            collapsible: true,
            children: ["server-access", "server-router"].map((id) => architectureNode(id)),
          })],
          outgoingLabel: copy.value.edgeLabels.syncRoute,
          outgoingKind: "primary",
        },
        {
          id: "sync-runtime",
          kind: "runtime",
          ...flowCopy.stages[2]!,
          items: [
            architectureNode("cluster-endpoint-a"),
            architectureNode("worker-a", {
              collapsible: true,
              edgeLabel: copy.value.edgeLabels.internalAuth,
              edgeKind: "primary",
              children: ["worker-service", "worker-datasource", "worker-script"].map((id) => architectureNode(id)),
            }),
          ],
          outgoingLabel: copy.value.edgeLabels.execute,
          outgoingKind: "primary",
        },
        {
          id: "sync-targets",
          kind: "external",
          ...flowCopy.stages[3]!,
          layout: "parallel",
          items: [architectureNode("datasource-a"), architectureNode("flink-gateway")],
        },
      ],
      sharedTitle: flowCopy.sharedTitle,
      sharedDescription: flowCopy.sharedDescription,
      sharedRelation: flowCopy.sharedRelation,
      shared: [
        architectureNode("mysql"),
        architectureNode("redis"),
        architectureNode("nacos"),
        architectureNode("object-storage", {
          collapsible: true,
          children: [architectureNode("run-log")],
        }),
      ],
    };
  }
  if (scenario === "async") {
    const flowCopy = copy.value.flowSections.async;
    return {
      boundary: `${copy.value.groups.control} → Dispatch / Lease → ${copy.value.groups.clusterA}`,
      stages: [
        {
          id: "async-trigger",
          kind: "entry",
          ...flowCopy.stages[0]!,
          items: [architectureNode("browser")],
          outgoingLabel: copy.value.edgeLabels.trigger,
          outgoingKind: "primary",
        },
        {
          id: "async-control",
          kind: "control",
          ...flowCopy.stages[1]!,
          items: [architectureNode("server", {
            collapsible: true,
            children: ["server-scheduler", "server-ops"].map((id) => architectureNode(id)),
          })],
          outgoingLabel: copy.value.edgeLabels.enqueue,
          outgoingKind: "storage",
        },
        {
          id: "async-state",
          kind: "state",
          ...flowCopy.stages[2]!,
          items: [architectureNode("dispatch-row")],
          outgoingLabel: copy.value.edgeLabels.claim,
          outgoingKind: "primary",
        },
        {
          id: "async-runtime",
          kind: "runtime",
          ...flowCopy.stages[3]!,
          items: [architectureNode("worker-a", {
            collapsible: true,
            children: ["worker-dispatch", "worker-task", "worker-script"].map((id) => architectureNode(id)),
          })],
          outgoingLabel: copy.value.edgeLabels.execute,
          outgoingKind: "primary",
        },
        {
          id: "async-targets",
          kind: "external",
          ...flowCopy.stages[4]!,
          layout: "parallel",
          items: [architectureNode("datasource-a"), architectureNode("flink-gateway")],
        },
      ],
      sharedTitle: flowCopy.sharedTitle,
      sharedDescription: flowCopy.sharedDescription,
      sharedRelation: flowCopy.sharedRelation,
      shared: [
        architectureNode("mysql"),
        architectureNode("run-record"),
        architectureNode("nacos"),
        architectureNode("object-storage", {
          collapsible: true,
          children: [architectureNode("run-log")],
        }),
      ],
    };
  }
  const flowCopy = copy.value.flowSections.plugins;
  return {
    boundary: copy.value.groups.workerOnly,
    stages: [
      {
        id: "plugin-release",
        kind: "external",
        ...flowCopy.stages[0]!,
        items: [architectureNode("release-builder")],
        outgoingLabel: copy.value.edgeLabels.publishRelease,
        outgoingKind: "plugin",
      },
      {
        id: "plugin-storage",
        kind: "state",
        ...flowCopy.stages[1]!,
        items: [
          architectureNode("plugin-repo"),
          architectureNode("plugin-manifest", { edgeLabel: copy.value.edgeLabels.switchCurrent, edgeKind: "plugin" }),
        ],
        outgoingLabel: copy.value.edgeLabels.coldAndRefresh,
        outgoingKind: "plugin",
      },
      {
        id: "plugin-worker",
        kind: "runtime",
        ...flowCopy.stages[2]!,
        items: [
          architectureNode("plugin-loader"),
          architectureNode("local-cache", { edgeLabel: copy.value.edgeLabels.verifyAndExtract, edgeKind: "plugin" }),
        ],
        outgoingLabel: copy.value.edgeLabels.pinIdentity,
        outgoingKind: "plugin",
      },
      {
        id: "plugin-execution",
        kind: "runtime",
        ...flowCopy.stages[3]!,
        items: [architectureNode("job-container")],
      },
    ],
    sharedTitle: flowCopy.sharedTitle,
    sharedDescription: flowCopy.sharedDescription,
    sharedRelation: flowCopy.sharedRelation,
    shared: [
      architectureNode("object-storage", {
        collapsible: true,
        children: [architectureNode("run-log"), architectureNode("script-artifact")],
      }),
      architectureNode("local-plugin-dir"),
      architectureNode("mysql"),
    ],
  };
}

function selectNode(id: string, toggle = false) {
  selectedNodeId.value = id;
  if (toggle) toggleGroup(id);
}

function selectRoadmapStage(id: string) {
  activeRoadmapStageId.value = id;
  selectedNodeId.value = id;
}

function nodeCardClasses(id: string, compact = false) {
  return [
    "architecture-node-card",
    `is-${nodeKinds[id] ?? "external"}`,
    { "is-selected": selectedNodeId.value === id, "is-compact": compact },
  ];
}

async function focusSelectedNode() {
  const roadmapStageId = roadmapChildToStage.value[selectedNodeId.value];
  if (activeScenario.value === "roadmap" && roadmapStageId) {
    activeRoadmapStageId.value = roadmapStageId;
  }
  const group = childToGroup[selectedNodeId.value];
  if (group && !expandedGroups.value.has(group)) {
    const next = new Set(expandedGroups.value);
    next.add(group);
    expandedGroups.value = next;
  }
  await nextTick();
  const target = architectureSurfaceRef.value?.querySelector<HTMLElement>(`[data-node-id="${selectedNodeId.value}"]`);
  target?.scrollIntoView({ behavior: "smooth", block: "center", inline: "nearest" });
  target?.focus({ preventScroll: true });
}

watch(activeScenario, (scenario) => {
  const defaults: Record<ScenarioId, string> = {
    roadmap: activeRoadmapStageId.value,
    overview: "server",
    sync: "server-router",
    async: "dispatch-row",
    plugins: "plugin-repo",
  };
  selectedNodeId.value = defaults[scenario];
});

watch(isRoadmapPage, (roadmapPage) => {
  activeScenario.value = roadmapPage ? "roadmap" : "overview";
  selectedNodeId.value = roadmapPage ? activeRoadmapStageId.value : "server";
});

const zhCopy: ArchitectureCopy = {
  eyebrow: "P0-MC-02 · 当前统一运行模型",
  heading: "DataAggregation Studio 总体技术架构",
  description: "以 studio-server 作为纯控制面，以运行集群中的 studio-worker 作为唯一执行面；所有任务和同步调用都由 runtimeClusterId 定向，插件、日志与脚本制品通过共享对象存储跨集群协同。",
  heroBadges: ["控制面 / 执行面物理隔离", "多集群同构能力", "插件可按需热加载", "同步与异步统一定向"],
  metrics: [
    { value: "1", label: "统一控制面" },
    { value: "N", label: "全能力运行集群" },
    { value: "2", label: "同步 / 异步执行链" },
    { value: "0", label: "Server 本地执行回退" },
  ],
  viewLabel: "架构视图",
  locateNode: "定位架构节点",
  overviewEntryTitle: "统一接入与调用入口",
  overviewEntryDescription: "用户操作、公开 API 与可信身份入口在控制面汇合，再按运行集群显式定向。",
  applicationTierTitle: "应用进程与运行集群",
  applicationTierDescription: "studio-server 负责控制与定向，studio-worker 集群负责所有真实执行；两者通过 runtimeClusterId / targetClusterId 建立明确关系。",
  workerSharedCapabilitiesEyebrow: "集群 A / 集群 B 共同能力",
  workerSharedCapabilitiesTitle: "同构 Worker 全能力运行时",
  workerSharedCapabilitiesDescription: "两个运行集群共享同一套执行能力，仅 targetClusterId、内部端点和业务数据网络可达范围不同。",
  sharedTierTitle: "Server 与 Worker 共同依赖的共享状态",
  sharedTierDescription: "控制配置、Dispatch、租约、缓存、日志、插件和脚本制品由共享中间件承载，不属于任何单个应用实例。",
  downstreamTierTitle: "Worker 下游执行资源",
  downstreamTierDescription: "只有目标运行集群中的 Worker 可以继续访问业务数据源或发起真实 Flink SQL。",
  serverWorkerRelation: "Server 按 runtimeClusterId / targetClusterId 定向 Worker 集群",
  sharedDependencyRelation: "Server 与 Worker 共同连接共享中间件和 MySQL",
  workerDownstreamRelation: "Worker-only：继续向下访问真实数据资源",
  expandAll: "展开全部",
  collapseAll: "收起全部",
  expandNode: "展开内部能力",
  collapseNode: "收起内部能力",
  responsibilities: "承担职责",
  boundaries: "明确边界",
  keyConfig: "关键标识 / 配置",
  kindLabels: {
    entry: "接入层",
    control: "控制面",
    runtime: "执行面",
    state: "共享状态",
    middleware: "中间件",
    external: "外部资源",
  },
  legend: [
    { kind: "entry", label: "接入层" },
    { kind: "control", label: "控制面" },
    { kind: "runtime", label: "执行面" },
    { kind: "state", label: "共享状态" },
    { kind: "middleware", label: "中间件" },
    { kind: "external", label: "外部资源" },
  ],
  scenarios: {
    roadmap: {
      label: "平台路线图",
      description: "从数据源、模型、采集融合、数据开发和质量治理一路推进到在册模型服务发布；模型血缘和统一告警贯穿全部阶段。",
      ariaLabel: "DataAggregation Studio 平台建设路线图，展示六个数据阶段以及贯穿全程的模型血缘和告警体系。",
    },
    overview: {
      label: "总体拓扑",
      description: "采用响应式分层布局展示接入、控制、共享状态与运行集群；点击 Server、Worker 或对象存储可展开内部能力。",
      ariaLabel: "Studio 总体技术架构，展示接入层、控制面、共享状态和两个运行集群。",
    },
    sync: {
      label: "同步调用",
      description: "数据源探测、数据服务、数据接入、协议转换和 Flink SQL 通过受管 HTTP/SLB 端点进入目标 Worker，失败时不回退到 Server 或其它集群。",
      ariaLabel: "Studio 同步调用链，从外部调用方经过 Server 路由到目标 Worker 和业务数据源。",
    },
    async: {
      label: "异步调度",
      description: "Server 只创建带 targetClusterId 的 Dispatch；目标 Worker 使用租约和 CAS 领取、执行并回写运行状态。",
      ariaLabel: "Studio 异步调度链，从触发入队到目标 Worker 领取、执行和归档日志。",
    },
    plugins: {
      label: "OSS 插件加载",
      description: "LAZY_OBJECT_STORAGE 模式以不可变 release + current.json 发布插件；运行中任务固定旧 identity，新任务使用校验通过的新 identity。",
      ariaLabel: "Studio Worker 从对象存储按需加载 DataAggregation 插件的发布、校验、缓存和执行流程。",
    },
  },
  roadmap: {
    eyebrow: "Platform Delivery Roadmap",
    title: "从数据接入到模型服务化的六阶段路线",
    description: "路线图描述平台使用者真正完成一次数据建设的推荐顺序。点击任一阶段可展开当前阶段的交付物、扩展能力和下一阶段输入。",
    stageLabel: "建设阶段",
    outcomeLabel: "阶段交付物",
    capabilitiesLabel: "可展开能力",
    metrics: [
      { value: "06", label: "核心阶段" },
      { value: "02", label: "横向治理轨道" },
      { value: "04", label: "融合转换器类别" },
    ],
    stages: [
      {
        id: "roadmap-datasource",
        tone: "source",
        kind: "external",
        shortTitle: "数据源",
        title: "创建并纳管数据源",
        summary: "创建 · 测试 · 启用 · 纳管",
        description: "先建立真实数据连接，完成连通性验证、运行集群适用范围和业务元数据补充，再允许后续模型同步与任务配置使用。",
        outcome: "形成可被模型同步、任务配置和目标运行集群安全选择的可信数据连接。",
        tags: ["datasource", "runtimeClusterId", "connectionFingerprint"],
        capabilities: [
          { id: "roadmap-ds-create", title: "创建与连接测试", summary: "维护连接参数并由目标 Worker 完成真实连通性验证。", kind: "external", tags: ["JDBC", "HTTP", "MQ", "文件"] },
          { id: "roadmap-ds-govern", title: "启用与纳入管理", summary: "启用决定是否可用，纳管决定是否进入模型同步和任务候选。", kind: "control", tags: ["enabled", "managed"] },
          { id: "roadmap-ds-cluster", title: "绑定运行集群", summary: "声明数据源在哪些 Worker 集群网络中可达，不依据探测结果自动漂移。", kind: "runtime", tags: ["适用集群", "网络隔离"] },
          { id: "roadmap-ds-metadata", title: "补充业务元数据", summary: "维护负责人、描述、标签和管理属性，形成后续治理上下文。", kind: "state", tags: ["负责人", "标签", "描述"] },
        ],
      },
      {
        id: "roadmap-model",
        tone: "model",
        kind: "state",
        shortTitle: "模型",
        title: "模型同步、创建与登记",
        summary: "发现 · 同步 · 创建 · 血缘",
        description: "从已纳管数据源自动发现模型，也可以手工创建模型；同步字段、样例和统计信息，并建立可持续维护的模型资产。",
        outcome: "获得有字段定义、业务信息、上下游关系和运行集群上下文的在册模型。",
        tags: ["MODEL", "schema", "lineage"],
        capabilities: [
          { id: "roadmap-model-sync", title: "模型发现与同步", summary: "按数据源发现表、文件、主题或接口结构，并持续同步技术元数据。", kind: "runtime", tags: ["discover", "hydrate", "sync"] },
          { id: "roadmap-model-create", title: "手工创建模型", summary: "对无法自动发现或需要先行设计的对象维护模型和字段定义。", kind: "control", tags: ["模型定义", "字段维护"] },
          { id: "roadmap-model-profile", title: "字段、样例与统计", summary: "查看模型字段、数据样例和统计结果，确认后续任务输入质量。", kind: "state", tags: ["字段", "样例", "统计"] },
          { id: "roadmap-model-lineage", title: "多层级模型血缘", summary: "展示库级、表级和字段级上下游，并允许手工补充业务血缘。", kind: "state", tags: ["DATABASE", "TABLE", "FIELD"] },
        ],
      },
      {
        id: "roadmap-integration",
        tone: "integration",
        kind: "runtime",
        shortTitle: "采集融合",
        title: "创建贴源采集与融合任务",
        summary: "单源贴源 · 多源融合 · 字段转换",
        description: "根据在册模型创建单源贴源采集或多源融合任务，配置来源、目标、字段映射、调度和运行集群，由 Worker 执行真实数据搬运。",
        outcome: "形成可调度、可追踪且能够自动贡献模型血缘的数据同步与融合链路。",
        tags: ["collection-task", "fusion", "field-mapping"],
        capabilities: [
          { id: "roadmap-ingestion-source", title: "单源贴源采集", summary: "一个来源映射到目标模型，适合原始层落地和周期增量同步。", kind: "runtime", tags: ["SOURCE", "贴源层"] },
          { id: "roadmap-ingestion-fusion", title: "多源融合任务", summary: "多个来源通过别名、关联条件和目标字段映射汇聚到统一模型。", kind: "runtime", tags: ["FUSION", "多源"] },
          { id: "roadmap-ingestion-transformers", title: "四类转换器", summary: "字段链支持过滤、规整、脱敏和加密，并可组合多个规则及参数。", kind: "middleware", tags: ["过滤", "规整", "脱敏", "加密"] },
          { id: "roadmap-ingestion-runtime", title: "调度、指标与运行日志", summary: "固化运行集群和资源修订，记录读取、转换、写入指标及异常日志。", kind: "state", tags: ["Dispatch", "RunRecord", "Metrics"] },
        ],
      },
      {
        id: "roadmap-development",
        tone: "development",
        kind: "runtime",
        shortTitle: "数据开发",
        title: "脚本融合、清理与 DAG 编排",
        summary: "脚本 · 依赖环境 · 工作流 DAG",
        description: "对已采集数据继续进行 SQL、Java 或 Python 脚本融合与清理，并通过工作流把采集、质量、脚本、HTTP 和 Shell 节点编排为 DAG。",
        outcome: "形成可复用脚本资产、受管运行环境和支持手动或定时执行的数据加工 DAG。",
        tags: ["script", "environment", "workflow"],
        capabilities: [
          { id: "roadmap-dev-script", title: "多语言数据脚本", summary: "通过 SQL、Java、Python 等运行时完成数据融合、清理和派生计算。", kind: "runtime", tags: ["SQL", "Java", "Python"] },
          { id: "roadmap-dev-clean", title: "脚本融合与清理", summary: "围绕多个模型执行关联、标准化、去重、修复和目标写入。", kind: "runtime", tags: ["融合", "清理", "写入"] },
          { id: "roadmap-dev-environment", title: "额外依赖与脚本环境", summary: "维护可启停的依赖包和运行环境，按脚本类型组装受控执行类路径。", kind: "state", tags: ["dependency", "environment", "OSS 制品"] },
          { id: "roadmap-dev-dag", title: "DAG 工作流", summary: "组合采集、质量、脚本、HTTP 和 Shell 节点，支持发布、调度和运行跟踪。", kind: "control", tags: ["DAG", "Cron", "节点状态"] },
        ],
      },
      {
        id: "roadmap-quality",
        tone: "quality",
        kind: "control",
        shortTitle: "数据质量",
        title: "质量规则、评分与问题治理",
        summary: "规则 · 任务 · 评分 · 恢复重开",
        description: "为模型配置质量规则和质量任务，观察执行健康分、治理风险指数和问题趋势，并对异常事件进行确认、恢复和恢复后重开。",
        outcome: "形成有量化评分、有问题生命周期并能够持续验证治理效果的可信模型资产。",
        tags: ["quality-rule", "health-score", "REOPENED"],
        capabilities: [
          { id: "roadmap-quality-rule", title: "质量规则与任务", summary: "配置规则维度、作用域、粒度和适配数据源，并在目标 Worker 执行检查。", kind: "runtime", tags: ["规则", "任务", "运行集群"] },
          { id: "roadmap-quality-score", title: "执行健康分与治理风险", summary: "通过健康分、风险指数、覆盖矩阵和趋势识别高风险资产。", kind: "state", tags: ["健康分", "风险指数", "TopN"] },
          { id: "roadmap-quality-lifecycle", title: "问题开启、恢复与重开", summary: "事件经历 OPEN、ACKNOWLEDGED、RECOVERED、CLOSED；恢复后再次异常产生 REOPENED。", kind: "control", tags: ["OPEN", "RECOVERED", "REOPENED"] },
          { id: "roadmap-quality-noise", title: "高噪声任务与规则", summary: "按失败次数和 reopen 次数识别需要优化或降噪的任务与规则。", kind: "middleware", tags: ["失败次数", "Reopen", "降噪"] },
        ],
      },
      {
        id: "roadmap-service",
        tone: "service",
        kind: "entry",
        shortTitle: "服务发布",
        title: "将在册模型发布为数据服务",
        summary: "模型发布 · REST/SOAP · Token · 监控",
        description: "选择治理完成的在册模型或受控 SQL 创建数据服务，调试字段和参数后发布为 REST / SOAP 接口，并按订阅方管理访问 Token。",
        outcome: "形成具有稳定服务标识、开放地址、订阅权限、调用指标和访问日志的数据产品。",
        tags: ["MODEL_PUBLISH", "REST", "SOAP", "Token"],
        capabilities: [
          { id: "roadmap-service-model", title: "在册模型发布", summary: "选择模型字段或受控 SELECT SQL 解析服务输出，保留模型治理上下文。", kind: "control", tags: ["MODEL_PUBLISH", "SELECT"] },
          { id: "roadmap-service-protocol", title: "REST / SOAP 服务", summary: "发布查询和分页接口，也可启用 WebService、WSDL 与 SOAP 调试。", kind: "entry", tags: ["REST", "SOAP", "WSDL"] },
          { id: "roadmap-service-security", title: "订阅方与 Token", summary: "按订阅方签发、停用和重新启用访问 Token，隔离不同调用者。", kind: "middleware", tags: ["Subscriber", "Token", "RBAC"] },
          { id: "roadmap-service-observe", title: "调用监控与访问日志", summary: "查看调用次数、成功率、耗时、慢查询和异常请求证据。", kind: "state", tags: ["Metrics", "AccessLog", "SLA"] },
        ],
      },
    ],
    lineage: {
      id: "roadmap-lineage",
      label: "模型血缘",
      title: "贯穿数据源、模型、任务、脚本、质量与服务的影响链",
      description: "采集任务自动贡献上下游关系，管理员可补充手工血缘，并按库、表、字段层级查看影响范围。",
      kind: "state",
      items: ["自动采集", "手工维护", "库 / 表 / 字段级", "上下游影响分析"],
      tags: ["data_model_lineage_relation", "DATABASE", "TABLE", "FIELD"],
    },
    alerts: {
      id: "roadmap-alerts",
      label: "统一告警",
      title: "围绕全部建设与运行阶段的异常发现、通知和恢复闭环",
      description: "数据源健康、模型同步、任务与工作流、质量问题和服务调用均可进入统一告警中心，并通过站内信、Webhook 或 eLink 投递。",
      kind: "middleware",
      items: ["全阶段规则覆盖", "站内信", "Webhook", "eLink", "恢复 / 重开 / 重试"],
      tags: ["OPEN", "ACKNOWLEDGED", "RECOVERED", "REOPENED", "outbox"],
    },
  },
  flowSections: {
    sync: {
      stages: [
        { title: "调用入口与身份上下文", description: "外部调用经可信网关携带租户、项目和调用身份进入 Studio，同一入口同时覆盖数据服务、数据接入与协议转换。" },
        { title: "Server 解析资源与目标集群", description: "控制面读取资源配置，完成权限、目标端点与 SSRF 校验，只负责路由和响应转发，不执行真实数据操作。" },
        { title: "目标 Worker 集群受管入口", description: "HTTP / SLB 在目标集群内部均衡 Worker 副本，并使用内部 Token 和集群身份隔离控制面与执行面。" },
        { title: "Worker 访问真实执行资源", description: "目标 Worker 执行数据服务、探测、接入或 Flink SQL；其它集群不参与，也不会自动漂移。" },
      ],
      sharedTitle: "同步链共享状态与横向能力",
      sharedDescription: "Server 与目标 Worker 共同读取配置和运行状态；Redis、Nacos 与对象存储按启用能力提供缓存、发现和日志归档。",
      sharedRelation: "Server 与目标 Worker 共同连接共享中间件；只有 Worker 继续访问业务资源",
    },
    async: {
      stages: [
        { title: "用户操作与定时触发", description: "控制台、调度器或业务配置触发一次资源运行请求，触发阶段不直接选择空闲 Worker 实例。" },
        { title: "Server 创建定向 Dispatch", description: "控制面固化资源修订和 targetClusterId 后入队，不在 Server 进程内加载插件或执行任务。" },
        { title: "共享数据库持久化调度状态", description: "dispatch_task 保存目标集群、计划时间、Claim Token 与租约状态，是 Server 和 Worker 的协调边界。" },
        { title: "目标 Worker Claim 与执行", description: "只有匹配 targetClusterId 的 Worker 可以领取任务，并通过心跳、租约和 CAS 驱动完整执行生命周期。" },
        { title: "执行目标与结果落地", description: "Worker 访问集群内可达数据源或 Flink，并把状态、结果索引和运行日志写回共享设施。" },
      ],
      sharedTitle: "异步调度协调面与运行证据",
      sharedDescription: "MySQL 承载 Dispatch、租约和运行记录，对象存储归档日志，Nacos 只提供可选配置能力而不参与任务选址。",
      sharedRelation: "Server 写入 Dispatch，目标 Worker Claim / 心跳 / CAS 回写，运行日志归档到 OSS",
    },
    plugins: {
      stages: [
        { title: "构建不可变插件发布包", description: "发布端生成 plugin.zip、SHA-256、大小和 manifest；同一个 release 标识发布后不可覆盖。" },
        { title: "OSS 仓库完成原子发布", description: "先上传不可变 release，最后原子替换 current.json，使所有 Worker 观察到一致的活动版本指针。" },
        { title: "Worker 校验、下载与缓存", description: "插件加载中心执行单飞下载、哈希与运行版本校验、安全解压，并维护本地 staging、state 与缓存。" },
        { title: "任务固定插件 identity 执行", description: "新任务使用校验通过的新 identity；运行中任务继续固定旧 identity，避免热更新改变执行语义。" },
      ],
      sharedTitle: "插件加载依赖、制品与回退边界",
      sharedDescription: "OSS / MinIO 是 LAZY_OBJECT_STORAGE 的共享发布源，同时承载日志和脚本制品；EAGER_LOCAL 仅使用完整本地插件目录。",
      sharedRelation: "OSS 是 LAZY 模式共享发布源；Worker 本地缓存不等同于跨节点共享状态",
    },
  },
  groups: {
    control: "控制面 · OMS",
    shared: "共享状态与基础设施",
    clusterA: "运行集群 A · 全能力 Worker",
    clusterB: "运行集群 B · 同构部署",
    workerOnly: "仅存在于 Studio Worker 的执行边界",
  },
  nodes: {
    browser: { title: "Web 控制台", summary: "租户、项目、资产、任务与运维入口" },
    "external-api": { title: "外部 API / 调用方", summary: "数据服务、接入与协议转换消费者" },
    gateway: { title: "可信网关（可选）", summary: "身份签名兑换与统一入口" },
    server: { title: "studio-server", summary: "纯控制面：配置、调度、路由、统计和告警" },
    "server-access": { title: "权限与配置", summary: "租户 / 项目 / RBAC / 审计" },
    "server-scheduler": { title: "调度与 Dispatch", summary: "创建定向任务和资源修订快照" },
    "server-router": { title: "运行集群路由", summary: "目标解析、SSRF 校验与响应转发" },
    "server-ops": { title: "运维与告警", summary: "聚合运行、日志、指标和通知" },
    "flink-planner": { title: "studio-flink", summary: "智能问数 SQL 计划生成，不执行真实 SQL" },
    mysql: { title: "MySQL / Studio 元数据库", summary: "配置、Dispatch、Lease、运行记录与审计" },
    redis: { title: "Redis（按需）", summary: "多实例共享的数据服务响应缓存" },
    nacos: { title: "Nacos（按需）", summary: "配置分发与 studio-flink 服务发现" },
    "object-storage": { title: "共享对象存储", summary: "OSS / MinIO：运行日志、插件仓库、脚本制品" },
    "run-log": { title: "运行日志归档", summary: "Worker 上传，Server 与其它节点跨集群读取" },
    "plugin-repo": { title: "插件仓库", summary: "current.json + 不可变 releases/plugin.zip" },
    "script-artifact": { title: "脚本环境制品", summary: "受管 oss:// JAR / ZIP 与依赖包" },
    "cluster-endpoint-a": { title: "Worker HTTP / SLB 端点", summary: "同步数据面唯一受管入口" },
    "cluster-endpoint-b": { title: "Worker HTTP / SLB 端点", summary: "集群 B 的同步数据面受管入口" },
    "worker-a": { title: "studio-worker · 集群 A", summary: "唯一执行面：插件、数据源、任务、脚本和服务" },
    "worker-b": { title: "studio-worker · 集群 B", summary: "与集群 A 同构，差异仅在网络可达范围" },
    "worker-dispatch": { title: "Dispatch Consumer", summary: "只领取 targetClusterId 指向本集群的任务" },
    "worker-datasource": { title: "数据源与模型运行时", summary: "test / discover / hydrate / preview / query" },
    "worker-task": { title: "任务执行运行时", summary: "采集、质量、工作流节点执行" },
    "worker-script": { title: "脚本与 Flink 运行时", summary: "SQL / Flink SQL / Java / Python" },
    "worker-service": { title: "开放服务运行时", summary: "数据服务、数据接入、协议转换" },
    "plugin-loader": { title: "插件加载中心", summary: "本地完整加载或 OSS 按需加载" },
    "datasource-a": { title: "集群 A 可达数据源", summary: "数据库、消息队列、文件、对象存储和 HTTP" },
    "datasource-b": { title: "集群 B 可达数据源", summary: "由管理员维护适用集群，不自动漂移" },
    "flink-gateway": { title: "Flink Gateway / Cluster", summary: "由 Worker 发起真实 Flink SQL 执行" },
    "dispatch-row": { title: "dispatch_task", summary: "固化 targetClusterId、资源修订和租约状态" },
    "run-record": { title: "运行记录 / CAS", summary: "requested / actual 集群、状态与结果" },
    "release-builder": { title: "插件构建与发布", summary: "生成 ZIP、SHA-256、大小和 manifest" },
    "plugin-manifest": { title: "current.json", summary: "通道当前激活 release 的原子指针" },
    "local-cache": { title: "Worker 本地缓存", summary: ".staging / .state / cache 与版本保留" },
    "job-container": { title: "JobContainer / 执行实例", summary: "任务启动时固定插件 identity" },
    "local-plugin-dir": { title: "完整本地插件目录", summary: "EAGER_LOCAL 模式的 aggregation/plugin" },
  },
  details: {
    server: {
      kind: "control",
      responsibilities: ["用户 API、租户项目与权限管理", "资源配置、定时调度与定向 Dispatch 入队", "同步请求的集群解析、安全校验和响应转发", "运行统计、访问日志、告警与通知聚合"],
      boundaries: ["不加载 DataAggregation source/reader/writer 插件", "不连接业务数据源，不执行 SQL、脚本或用户任务", "Worker 不可用时不在 Server 本地回退"],
      tags: ["SERVER_PORT=18080", "STUDIO_INTERNAL_API_TOKEN", "STUDIO_ENCRYPTION_SECRET", "runtimeClusterId"],
      collapsible: true,
    },
    "worker-a": {
      kind: "runtime",
      responsibilities: ["数据源探测、模型访问和参数化查询", "采集、质量、工作流与数据开发执行", "SQL、Flink SQL、Java、Python 运行", "数据服务、接入与协议转换的真实调用", "插件加载、JobContainer 生命周期和日志归档"],
      boundaries: ["只领取目标为本集群的 Dispatch", "不承担用户控制面和跨集群自动故障转移", "内部 HTTP 接口不暴露到用户网络"],
      tags: ["SERVER_PORT=18081", "STUDIO_CLUSTER_CODE", "STUDIO_AGGREGATION_HOME", "STUDIO_PLUGIN_FINGERPRINT"],
      collapsible: true,
    },
    "object-storage": {
      kind: "state",
      responsibilities: ["保存多集群共享运行日志", "托管 DataAggregation 插件 release 与 current.json", "托管 Java / Python 等脚本环境制品"],
      boundaries: ["Server 使用 SDK 读取共享日志不代表可以执行插件", "生产 bucket 预创建，应用不自动创建", "当前认证模型使用静态 Access Key / Secret Key"],
      tags: ["STUDIO_OBJECT_PROVIDER=OSS|MINIO", "STUDIO_OBJECT_BUCKET", "STUDIO_PLUGIN_PREFIX", "STUDIO_PLUGIN_CHANNEL"],
      collapsible: true,
    },
    "flink-planner": {
      kind: "control",
      responsibilities: ["根据问题、模型上下文和 LLM 配置生成受约束 SQL 计划", "为智能问数提供 /question/plan"],
      boundaries: ["不执行真实 Flink SQL", "不携带 Worker 集群身份或插件目录", "不可作为运行集群端点"],
      tags: ["SERVER_PORT=18084", "STUDIO_FLINK_BASE_URL", "STUDIO_FLINK_SERVICE_NAME"],
    },
    mysql: {
      kind: "state",
      responsibilities: ["统一保存控制面配置和运行集群模型", "承载 Dispatch、Lease、运行记录、访问日志与审计", "为所有 Server 与 Worker 提供一致状态"],
      boundaries: ["异步调度依赖共享数据库定向 Claim", "所有进程必须使用一致加密密钥读取受保护字段"],
      tags: ["studio_runtime_cluster", "studio_runtime_endpoint", "dispatch_task", "studio_runtime_validation"],
    },
    redis: {
      kind: "middleware",
      responsibilities: ["启用数据服务响应缓存时提供跨实例共享缓存"],
      boundaries: ["不是基础运行必需项", "多集群不能用 Worker 本地缓存替代共享 Redis"],
      tags: ["REDIS_HOST", "REDIS_PORT", "REDIS_PASSWORD"],
    },
    nacos: {
      kind: "middleware",
      responsibilities: ["按部署需要分发配置", "为 studio-flink 提供可选服务发现"],
      boundaries: ["运行集群路由仍以数据库中的 HTTP/SLB 端点为准", "Desktop 不注册 Nacos"],
      tags: ["NACOS_SERVER", "NACOS_NAMESPACE", "NACOS_GROUP"],
    },
    "plugin-repo": {
      kind: "state",
      responsibilities: ["保存不可变 releases/{release}/plugin.zip", "通过 current.json 原子切换活动版本", "为 Worker 首次冷加载和后台刷新提供来源"],
      boundaries: ["不能覆盖已经发布的 release ZIP", "刷新失败保留最后有效版本并报告 DEGRADED", "运行中任务继续使用创建时固定的 identity"],
      tags: ["LAZY_OBJECT_STORAGE", "current.json", "SHA-256", "STUDIO_RUNTIME_VERSION"],
    },
    "plugin-loader": {
      kind: "runtime",
      responsibilities: ["验证 manifest、大小、哈希和运行版本", "单飞下载、安全解压并维护本地缓存", "向新执行实例提供活动插件 identity"],
      boundaries: ["只在 Worker 注册 OSS resolver", "普通插件升级无需重启 Worker", "应用代码升级仍需按发布流程重启"],
      tags: [".staging", ".state", "cache", "STUDIO_PLUGIN_COLD_LOAD_TIMEOUT_SECONDS"],
    },
    "cluster-endpoint-a": {
      kind: "middleware",
      responsibilities: ["承载 Server 到目标 Worker 的同步 HTTP 调用", "通过 Service / SLB 在同集群多 Worker 间负载均衡"],
      boundaries: ["每集群只选择一个启用端点", "不自动重试写入类请求，不跟随重定向", "内部 Token 与用户业务认证相互隔离"],
      tags: ["/internal/runtime/**", "STUDIO_RUNTIME_ENDPOINT_ALLOWED_HOSTS", "HTTP/SLB"],
    },
    "dispatch-row": {
      kind: "state",
      responsibilities: ["固化目标集群、资源修订和计划时间", "使用 claimToken + workerBootId + status 做 CAS", "目标集群离线时保持排队并等待恢复"],
      boundaries: ["不复制解密后的数据源秘密或完整业务请求体", "无 targetClusterId 的历史任务不会被 Worker 领取"],
      tags: ["target_cluster_id", "claim_token", "worker_boot_id", "protected_payload_ciphertext"],
    },
    "datasource-a": {
      kind: "external",
      responsibilities: ["提供业务数据库、消息队列、文件、对象和 HTTP 数据", "由对应运行集群的 Worker 直接访问"],
      boundaries: ["Server 网络可明确禁止访问业务数据源网段", "适用集群由管理员配置，不由健康探测自动增删"],
      tags: ["datasource_cluster_binding", "connectionFingerprint", "requestedClusterId", "actualClusterId"],
    },
    "flink-gateway": {
      kind: "middleware",
      responsibilities: ["承载 Worker 发起的真实 Flink SQL", "Remote Connector 通过任务级 capability 获取固定 source 插件"],
      boundaries: ["回调地址指向 Worker，不是 Server", "完全离线场景才使用包含全部插件的 bundle"],
      tags: ["STUDIO_FLINK_EXECUTION_MODE", "STUDIO_FLINK_GATEWAY_BASE_URL", "flink-connector-remote"],
    },
  },
  edgeLabels: {
    identity: "身份与项目上下文",
    userApi: "用户 API",
    openApi: "公开服务入口",
    metadata: "配置 / 状态",
    cache: "共享缓存",
    config: "配置 / 服务发现",
    readLog: "读取共享日志",
    plan: "生成 SQL 计划",
    syncRoute: "按 runtimeClusterId 路由",
    internalApi: "内部 Token + 集群身份",
    claimWriteback: "Claim / 心跳 / 回写",
    logArchive: "日志归档",
    execute: "真实数据访问与执行",
    flinkExecute: "Flink SQL",
    lazyLoad: "按需插件加载",
    pluginAndArtifact: "插件 / 日志 / 制品",
    resolveResource: "解析资源与目标集群",
    safeProxy: "SSRF 校验后代理",
    internalAuth: "内部认证",
    resourceConfig: "读取资源配置",
    accessLog: "访问日志与告警证据",
    trigger: "触发资源运行",
    enqueue: "创建定向 Dispatch",
    claim: "只领取本集群任务",
    instantiate: "加载不可变快照",
    casWriteback: "CAS 完成 / 恢复",
    publishRelease: "上传不可变 release",
    switchCurrent: "最后原子覆盖 current.json",
    coldAndRefresh: "首次冷加载 / 后台刷新",
    verifyAndExtract: "校验并安全解压",
    pinIdentity: "固定活动 identity",
    eagerLocal: "EAGER_LOCAL",
    scriptDependency: "受管脚本依赖",
  },
};

const enCopy: ArchitectureCopy = {
  eyebrow: "P0-MC-02 · Current runtime model",
  heading: "DataAggregation Studio technical architecture",
  description: "studio-server is the pure control plane and studio-worker in each runtime cluster is the only execution plane. Every task and synchronous call is placed by runtimeClusterId, while shared object storage carries plugins, run logs and script artifacts across clusters.",
  heroBadges: ["Physical control / execution isolation", "Homogeneous multi-cluster runtime", "On-demand plugin hot loading", "Explicit sync / async placement"],
  metrics: [
    { value: "1", label: "Unified control plane" },
    { value: "N", label: "Full-capability runtime clusters" },
    { value: "2", label: "Sync / async execution paths" },
    { value: "0", label: "Server-side execution fallback" },
  ],
  viewLabel: "Architecture view",
  locateNode: "Locate a component",
  overviewEntryTitle: "Unified entry and invocation layer",
  overviewEntryDescription: "User actions, public APIs and trusted identity entry converge on the control plane before explicit runtime-cluster placement.",
  applicationTierTitle: "Application processes and runtime clusters",
  applicationTierDescription: "studio-server controls and places work, while studio-worker clusters perform every real execution. runtimeClusterId / targetClusterId makes their relationship explicit.",
  workerSharedCapabilitiesEyebrow: "Shared by cluster A and cluster B",
  workerSharedCapabilitiesTitle: "Homogeneous full-capability Worker runtime",
  workerSharedCapabilitiesDescription: "Both runtime clusters expose the same execution capabilities; only targetClusterId, internal endpoints and business-network reachability differ.",
  sharedTierTitle: "Shared state used by both Server and Worker",
  sharedTierDescription: "Configuration, Dispatch, leases, caches, logs, plugins and script artifacts live in shared middleware rather than a single application instance.",
  downstreamTierTitle: "Worker downstream execution resources",
  downstreamTierDescription: "Only the Worker in the selected runtime cluster continues to business datasources or real Flink SQL execution.",
  serverWorkerRelation: "Server targets a Worker cluster by runtimeClusterId / targetClusterId",
  sharedDependencyRelation: "Server and Worker both connect to shared middleware and MySQL",
  workerDownstreamRelation: "Worker-only: continue to real data resources",
  expandAll: "Expand all",
  collapseAll: "Collapse all",
  expandNode: "Expand capabilities",
  collapseNode: "Collapse capabilities",
  responsibilities: "Responsibilities",
  boundaries: "Hard boundaries",
  keyConfig: "Key identities / configuration",
  kindLabels: {
    entry: "Entry",
    control: "Control plane",
    runtime: "Execution plane",
    state: "Shared state",
    middleware: "Middleware",
    external: "External resource",
  },
  legend: [
    { kind: "entry", label: "Entry" },
    { kind: "control", label: "Control plane" },
    { kind: "runtime", label: "Execution plane" },
    { kind: "state", label: "Shared state" },
    { kind: "middleware", label: "Middleware" },
    { kind: "external", label: "External resource" },
  ],
  scenarios: {
    roadmap: { label: "Platform roadmap", description: "Move from datasources and models through ingestion, fusion, development and quality governance to registered-model service publishing. Lineage and unified alerts span every stage.", ariaLabel: "DataAggregation Studio platform roadmap with six delivery stages plus cross-cutting lineage and alerting." },
    overview: { label: "Overview", description: "A responsive layered layout shows entry, control, shared state and runtime clusters. Select Server, Worker or object storage to expand its internal capabilities.", ariaLabel: "Studio architecture showing entry, control plane, shared state and two runtime clusters." },
    sync: { label: "Synchronous path", description: "Datasource probes, open services, protocol conversion and Flink SQL enter the target Worker through a managed HTTP/SLB endpoint. There is no fallback to Server or another cluster.", ariaLabel: "Studio synchronous path from an external caller through Server routing to the target Worker and business datasource." },
    async: { label: "Asynchronous dispatch", description: "Server only creates a Dispatch with targetClusterId. The target Worker claims, executes and completes it through leases and compare-and-set updates.", ariaLabel: "Studio asynchronous dispatch from trigger and enqueue to Worker claim, execution and log archival." },
    plugins: { label: "OSS plugin loading", description: "LAZY_OBJECT_STORAGE publishes immutable releases plus current.json. Running tasks pin their old identity while new tasks receive the newly verified identity.", ariaLabel: "Studio Worker object-storage plugin publishing, validation, caching and execution flow." },
  },
  roadmap: {
    eyebrow: "Platform Delivery Roadmap",
    title: "Six stages from data access to model-powered services",
    description: "This roadmap follows the recommended order for delivering governed data on the platform. Select a stage to reveal its deliverables, extensions and hand-off to the next stage.",
    stageLabel: "Delivery stage",
    outcomeLabel: "Stage deliverable",
    capabilitiesLabel: "Expandable capabilities",
    metrics: [
      { value: "06", label: "core stages" },
      { value: "02", label: "governance rails" },
      { value: "04", label: "transformer categories" },
    ],
    stages: [
      {
        id: "roadmap-datasource", tone: "source", kind: "external", shortTitle: "Datasource", title: "Create and govern datasources", summary: "Create · test · enable · manage",
        description: "Establish the real connection first, verify it through the target runtime cluster, define cluster reachability and add business metadata before models and tasks can use it.",
        outcome: "A trusted datasource connection that model synchronization, task configuration and the selected runtime cluster can use safely.",
        tags: ["datasource", "runtimeClusterId", "connectionFingerprint"],
        capabilities: [
          { id: "roadmap-ds-create", title: "Create and test connectivity", summary: "Maintain connection parameters and let the target Worker perform the real connectivity test.", kind: "external", tags: ["JDBC", "HTTP", "MQ", "files"] },
          { id: "roadmap-ds-govern", title: "Enable and bring under management", summary: "Enabled controls usability; managed controls eligibility for model synchronization and task selection.", kind: "control", tags: ["enabled", "managed"] },
          { id: "roadmap-ds-cluster", title: "Bind runtime clusters", summary: "Declare which Worker cluster networks can reach the source without automatic placement drift.", kind: "runtime", tags: ["applicable clusters", "network isolation"] },
          { id: "roadmap-ds-metadata", title: "Add business metadata", summary: "Maintain ownership, descriptions, tags and management attributes for downstream governance.", kind: "state", tags: ["owner", "tags", "description"] },
        ],
      },
      {
        id: "roadmap-model", tone: "model", kind: "state", shortTitle: "Model", title: "Synchronize, create and register models", summary: "Discover · synchronize · create · lineage",
        description: "Discover models from managed datasources or create them manually, synchronize fields, samples and statistics, and maintain them as durable platform assets.",
        outcome: "Registered models with field definitions, business context, upstream/downstream relationships and runtime-cluster context.",
        tags: ["MODEL", "schema", "lineage"],
        capabilities: [
          { id: "roadmap-model-sync", title: "Model discovery and synchronization", summary: "Discover tables, files, topics or API structures and keep their technical metadata synchronized.", kind: "runtime", tags: ["discover", "hydrate", "sync"] },
          { id: "roadmap-model-create", title: "Manual model creation", summary: "Maintain models and fields for objects that cannot be discovered or are designed before implementation.", kind: "control", tags: ["model definition", "field maintenance"] },
          { id: "roadmap-model-profile", title: "Fields, samples and statistics", summary: "Inspect fields, sample data and statistics before the model becomes an input to downstream work.", kind: "state", tags: ["fields", "samples", "statistics"] },
          { id: "roadmap-model-lineage", title: "Multi-level model lineage", summary: "Explore database-, table- and field-level relationships and supplement them with manual business lineage.", kind: "state", tags: ["DATABASE", "TABLE", "FIELD"] },
        ],
      },
      {
        id: "roadmap-integration", tone: "integration", kind: "runtime", shortTitle: "Ingestion & fusion", title: "Create source-aligned ingestion and fusion tasks", summary: "Single source · multi-source · field transforms",
        description: "Use registered models to configure single-source ingestion or multi-source fusion with sources, targets, mappings, schedules and an explicit runtime cluster.",
        outcome: "A schedulable, observable synchronization or fusion pipeline that can automatically contribute model lineage.",
        tags: ["collection-task", "fusion", "field-mapping"],
        capabilities: [
          { id: "roadmap-ingestion-source", title: "Source-aligned ingestion", summary: "Map one source into a target model for raw-layer landing and scheduled incremental synchronization.", kind: "runtime", tags: ["SOURCE", "raw layer"] },
          { id: "roadmap-ingestion-fusion", title: "Multi-source fusion", summary: "Join multiple aliased sources and map them into one governed target model.", kind: "runtime", tags: ["FUSION", "multi-source"] },
          { id: "roadmap-ingestion-transformers", title: "Four transformer categories", summary: "Compose filtering, normalization, masking and encryption rules with validated parameters.", kind: "middleware", tags: ["filter", "normalize", "mask", "encrypt"] },
          { id: "roadmap-ingestion-runtime", title: "Scheduling, metrics and run logs", summary: "Pin the runtime cluster and resource revision while tracking read, transform, write and failure metrics.", kind: "state", tags: ["Dispatch", "RunRecord", "Metrics"] },
        ],
      },
      {
        id: "roadmap-development", tone: "development", kind: "runtime", shortTitle: "Development", title: "Script fusion, cleanup and DAG orchestration", summary: "Scripts · dependency environments · workflow DAG",
        description: "Continue processing ingested data with SQL, Java or Python scripts, then orchestrate ingestion, quality, scripts, HTTP and Shell nodes as a workflow DAG.",
        outcome: "Reusable script assets, governed runtime environments and manually or periodically triggered processing DAGs.",
        tags: ["script", "environment", "workflow"],
        capabilities: [
          { id: "roadmap-dev-script", title: "Multi-language data scripts", summary: "Use SQL, Java and Python runtimes for fusion, cleanup and derived calculations.", kind: "runtime", tags: ["SQL", "Java", "Python"] },
          { id: "roadmap-dev-clean", title: "Scripted fusion and cleanup", summary: "Join, standardize, deduplicate, repair and write data across multiple models.", kind: "runtime", tags: ["fusion", "cleanup", "write"] },
          { id: "roadmap-dev-environment", title: "Extra dependencies and environments", summary: "Maintain enableable dependency packages and runtime environments with controlled classpaths per script type.", kind: "state", tags: ["dependency", "environment", "OSS artifacts"] },
          { id: "roadmap-dev-dag", title: "DAG workflows", summary: "Compose ingestion, quality, script, HTTP and Shell nodes with publishing, schedules and run tracking.", kind: "control", tags: ["DAG", "Cron", "node state"] },
        ],
      },
      {
        id: "roadmap-quality", tone: "quality", kind: "control", shortTitle: "Data quality", title: "Quality rules, scoring and issue governance", summary: "Rules · tasks · scores · recovery and reopen",
        description: "Apply quality rules and tasks to models, track execution health and governance risk, and manage issue acknowledgement, recovery and reopen behavior.",
        outcome: "Trusted model assets with measurable scores, explicit issue lifecycles and continuous evidence that governance is working.",
        tags: ["quality-rule", "health-score", "REOPENED"],
        capabilities: [
          { id: "roadmap-quality-rule", title: "Quality rules and tasks", summary: "Configure dimensions, scope, granularity and datasource compatibility, then execute checks on the target Worker.", kind: "runtime", tags: ["rule", "task", "runtime cluster"] },
          { id: "roadmap-quality-score", title: "Execution health and governance risk", summary: "Use health scores, risk indexes, coverage matrices and trends to identify high-risk assets.", kind: "state", tags: ["health score", "risk index", "TopN"] },
          { id: "roadmap-quality-lifecycle", title: "Open, recover and reopen issues", summary: "Issues move through OPEN, ACKNOWLEDGED, RECOVERED and CLOSED; a failure after recovery becomes REOPENED.", kind: "control", tags: ["OPEN", "RECOVERED", "REOPENED"] },
          { id: "roadmap-quality-noise", title: "Noisy tasks and rules", summary: "Rank repeated failures and reopen counts to find rules and tasks that need tuning.", kind: "middleware", tags: ["failure count", "Reopen", "noise reduction"] },
        ],
      },
      {
        id: "roadmap-service", tone: "service", kind: "entry", shortTitle: "Service publishing", title: "Publish registered models as data services", summary: "Model publish · REST/SOAP · tokens · monitoring",
        description: "Create a service from a governed registered model or controlled SQL, debug fields and parameters, publish REST / SOAP endpoints and manage subscriber tokens.",
        outcome: "A data product with a stable service identity, public endpoint, subscriber access, invocation metrics and audit logs.",
        tags: ["MODEL_PUBLISH", "REST", "SOAP", "Token"],
        capabilities: [
          { id: "roadmap-service-model", title: "Registered-model publishing", summary: "Resolve output from model fields or controlled SELECT SQL while retaining model governance context.", kind: "control", tags: ["MODEL_PUBLISH", "SELECT"] },
          { id: "roadmap-service-protocol", title: "REST / SOAP services", summary: "Publish query and pagination endpoints, with optional WebService, WSDL and SOAP debugging.", kind: "entry", tags: ["REST", "SOAP", "WSDL"] },
          { id: "roadmap-service-security", title: "Subscribers and tokens", summary: "Issue, disable and re-enable access tokens per subscriber to isolate callers.", kind: "middleware", tags: ["Subscriber", "Token", "RBAC"] },
          { id: "roadmap-service-observe", title: "Invocation monitoring and access logs", summary: "Track request count, success rate, latency, slow queries and failed-call evidence.", kind: "state", tags: ["Metrics", "AccessLog", "SLA"] },
        ],
      },
    ],
    lineage: {
      id: "roadmap-lineage", label: "Model lineage", title: "An impact chain across datasources, models, tasks, scripts, quality and services",
      description: "Ingestion tasks contribute relationships automatically, administrators can add manual lineage, and impact can be explored at database, table and field level.",
      kind: "state", items: ["automatic capture", "manual maintenance", "database / table / field", "upstream and downstream impact"],
      tags: ["data_model_lineage_relation", "DATABASE", "TABLE", "FIELD"],
    },
    alerts: {
      id: "roadmap-alerts", label: "Unified alerts", title: "Detection, notification and recovery around every delivery and runtime stage",
      description: "Datasource health, model synchronization, tasks, workflows, quality issues and service calls can all enter the unified alert center through in-app, Webhook or eLink delivery.",
      kind: "middleware", items: ["all-stage coverage", "in-app", "Webhook", "eLink", "recover / reopen / retry"],
      tags: ["OPEN", "ACKNOWLEDGED", "RECOVERED", "REOPENED", "outbox"],
    },
  },
  flowSections: {
    sync: {
      stages: [
        { title: "Invocation entry and identity context", description: "External calls enter Studio through the trusted gateway with tenant, project and caller identity for query, ingestion and conversion services." },
        { title: "Server resolves the resource and target cluster", description: "The control plane loads configuration, validates the target endpoint and SSRF policy, then only routes and relays the response." },
        { title: "Managed entry of the target Worker cluster", description: "HTTP / SLB balances Worker replicas inside the selected cluster while internal tokens and cluster identity isolate the two planes." },
        { title: "Worker reaches real execution resources", description: "The selected Worker performs service calls, probes, ingestion or Flink SQL. Other clusters do not participate or take over automatically." },
      ],
      sharedTitle: "Shared state and cross-cutting services for synchronous calls",
      sharedDescription: "Server and the selected Worker share configuration and run state. Redis, Nacos and object storage add caching, discovery and log archival when enabled.",
      sharedRelation: "Server and the selected Worker share middleware; only Worker continues to business resources",
    },
    async: {
      stages: [
        { title: "User action or scheduled trigger", description: "The console, scheduler or business configuration requests a run without directly selecting an idle Worker instance." },
        { title: "Server creates a targeted Dispatch", description: "The control plane pins the resource revision and targetClusterId, then enqueues work without loading plugins or executing it locally." },
        { title: "Shared database persists scheduling state", description: "dispatch_task stores the target cluster, schedule, claim token and lease state as the coordination boundary between Server and Worker." },
        { title: "Target Worker claims and executes", description: "Only Workers matching targetClusterId can claim the task and drive its lifecycle with heartbeats, leases and compare-and-set updates." },
        { title: "Execution targets and durable results", description: "Worker reaches cluster-local datasources or Flink, then writes status, result indexes and run logs back to shared infrastructure." },
      ],
      sharedTitle: "Asynchronous coordination state and run evidence",
      sharedDescription: "MySQL carries Dispatch, leases and run records, object storage archives logs, and Nacos remains optional configuration rather than a placement mechanism.",
      sharedRelation: "Server writes Dispatch; target Worker claims, heartbeats and completes by CAS; OSS archives logs",
    },
    plugins: {
      stages: [
        { title: "Build an immutable plugin release", description: "The publisher creates plugin.zip, SHA-256, size and manifest. A release identifier is never overwritten after publication." },
        { title: "Publish atomically through the OSS repository", description: "The immutable release uploads first and current.json is replaced last so every Worker observes one consistent active pointer." },
        { title: "Worker validates, downloads and caches", description: "The loader performs single-flight download, hash and runtime checks, secure extraction, and local staging, state and cache retention." },
        { title: "Execution pins a plugin identity", description: "New tasks use the newly verified identity while running tasks keep their previous identity so hot updates cannot change execution semantics." },
      ],
      sharedTitle: "Plugin dependencies, artifacts and fallback boundary",
      sharedDescription: "OSS / MinIO is the shared source for LAZY_OBJECT_STORAGE and also stores logs and script artifacts. EAGER_LOCAL only uses a complete local plugin directory.",
      sharedRelation: "OSS is the shared LAZY publication source; Worker local caches are not cross-node state",
    },
  },
  groups: { control: "Control plane · OMS", shared: "Shared state and infrastructure", clusterA: "Runtime cluster A · Full-capability Worker", clusterB: "Runtime cluster B · Homogeneous deployment", workerOnly: "Execution boundary available only inside Studio Worker" },
  nodes: {
    browser: { title: "Web console", summary: "Tenant, project, asset, task and operations entry" },
    "external-api": { title: "External API / caller", summary: "Consumer of query, ingestion and conversion services" },
    gateway: { title: "Trusted gateway (optional)", summary: "Signed identity exchange and unified entry" },
    server: { title: "studio-server", summary: "Pure control plane for configuration, scheduling, routing and operations" },
    "server-access": { title: "Access and configuration", summary: "Tenant / project / RBAC / audit" },
    "server-scheduler": { title: "Scheduler and Dispatch", summary: "Targeted queue records and resource revisions" },
    "server-router": { title: "Runtime cluster router", summary: "Target resolution, SSRF policy and response relay" },
    "server-ops": { title: "Operations and alerts", summary: "Runs, logs, metrics and notification aggregation" },
    "flink-planner": { title: "studio-flink", summary: "Generates question-to-SQL plans; does not execute SQL" },
    mysql: { title: "MySQL / Studio metadata DB", summary: "Configuration, Dispatch, leases, runs and audit" },
    redis: { title: "Redis (optional)", summary: "Shared response cache for multi-instance data services" },
    nacos: { title: "Nacos (optional)", summary: "Configuration delivery and studio-flink discovery" },
    "object-storage": { title: "Shared object storage", summary: "OSS / MinIO for logs, plugin repository and script artifacts" },
    "run-log": { title: "Run log archive", summary: "Written by Worker and read across Server / Worker nodes" },
    "plugin-repo": { title: "Plugin repository", summary: "current.json plus immutable releases/plugin.zip" },
    "script-artifact": { title: "Script environment artifacts", summary: "Managed oss:// JAR, ZIP and dependencies" },
    "cluster-endpoint-a": { title: "Worker HTTP / SLB endpoint", summary: "Only managed entry for synchronous execution" },
    "cluster-endpoint-b": { title: "Worker HTTP / SLB endpoint", summary: "Managed synchronous data-plane entry for cluster B" },
    "worker-a": { title: "studio-worker · Cluster A", summary: "Only execution plane for plugins, datasources, tasks and services" },
    "worker-b": { title: "studio-worker · Cluster B", summary: "Same capabilities as A; only network reachability differs" },
    "worker-dispatch": { title: "Dispatch Consumer", summary: "Claims only tasks targeting this runtime cluster" },
    "worker-datasource": { title: "Datasource and model runtime", summary: "test / discover / hydrate / preview / query" },
    "worker-task": { title: "Task runtime", summary: "Collection, quality and workflow node execution" },
    "worker-script": { title: "Script and Flink runtime", summary: "SQL / Flink SQL / Java / Python" },
    "worker-service": { title: "Open service runtime", summary: "Query service, ingestion and protocol conversion" },
    "plugin-loader": { title: "Plugin loader center", summary: "Complete local loading or object-storage lazy loading" },
    "datasource-a": { title: "Cluster A reachable sources", summary: "Databases, queues, files, object stores and HTTP" },
    "datasource-b": { title: "Cluster B reachable sources", summary: "Administrator-managed scope; never drifts automatically" },
    "flink-gateway": { title: "Flink Gateway / Cluster", summary: "Real Flink SQL execution is initiated by Worker" },
    "dispatch-row": { title: "dispatch_task", summary: "Persists targetClusterId, resource revision and lease state" },
    "run-record": { title: "Run record / CAS", summary: "Requested / actual clusters, status and result" },
    "release-builder": { title: "Plugin build and publish", summary: "Produces ZIP, SHA-256, size and manifest" },
    "plugin-manifest": { title: "current.json", summary: "Atomic pointer to the active release for a channel" },
    "local-cache": { title: "Worker local cache", summary: ".staging / .state / cache with retained releases" },
    "job-container": { title: "JobContainer / execution", summary: "Pins the plugin identity when a task starts" },
    "local-plugin-dir": { title: "Complete local plugin directory", summary: "aggregation/plugin used by EAGER_LOCAL" },
  },
  details: {
    server: { kind: "control", responsibilities: ["User API, tenant/project access and auditing", "Resource configuration, scheduling and targeted Dispatch creation", "Cluster resolution, endpoint security and response relay", "Operations, access logs, alerts and notifications"], boundaries: ["Does not load DataAggregation source/reader/writer plugins", "Does not connect to business datasources or execute SQL, scripts or tasks", "Never falls back to local execution when a Worker is unavailable"], tags: ["SERVER_PORT=18080", "STUDIO_INTERNAL_API_TOKEN", "STUDIO_ENCRYPTION_SECRET", "runtimeClusterId"], collapsible: true },
    "worker-a": { kind: "runtime", responsibilities: ["Datasource probes, model access and parameterized queries", "Collection, quality, workflow and data development execution", "SQL, Flink SQL, Java and Python runtimes", "Real query, ingestion and protocol-conversion calls", "Plugin loading, JobContainer lifecycle and log archival"], boundaries: ["Claims only Dispatch records for its own cluster", "Does not provide the user control plane or cross-cluster failover", "Internal HTTP endpoints must not be exposed to user networks"], tags: ["SERVER_PORT=18081", "STUDIO_CLUSTER_CODE", "STUDIO_AGGREGATION_HOME", "STUDIO_PLUGIN_FINGERPRINT"], collapsible: true },
    "object-storage": { kind: "state", responsibilities: ["Stores shared multi-cluster run logs", "Hosts plugin releases and current.json", "Hosts managed script-environment artifacts"], boundaries: ["Server SDK access for logs does not grant plugin execution", "Production buckets are pre-created", "Current authentication uses static access and secret keys"], tags: ["STUDIO_OBJECT_PROVIDER=OSS|MINIO", "STUDIO_OBJECT_BUCKET", "STUDIO_PLUGIN_PREFIX", "STUDIO_PLUGIN_CHANNEL"], collapsible: true },
    "flink-planner": { kind: "control", responsibilities: ["Builds constrained SQL plans from questions, model context and LLM configuration", "Provides /question/plan"], boundaries: ["Does not execute real Flink SQL", "Carries no Worker identity or plugin directory", "Cannot be registered as a runtime endpoint"], tags: ["SERVER_PORT=18084", "STUDIO_FLINK_BASE_URL", "STUDIO_FLINK_SERVICE_NAME"] },
    mysql: { kind: "state", responsibilities: ["Persists control-plane configuration and runtime-cluster models", "Coordinates Dispatch, leases, run records, access logs and audit", "Provides consistent state to every Server and Worker"], boundaries: ["Asynchronous execution depends on targeted claims in the shared DB", "Processes must share the same encryption secret for protected fields"], tags: ["studio_runtime_cluster", "studio_runtime_endpoint", "dispatch_task", "studio_runtime_validation"] },
    redis: { kind: "middleware", responsibilities: ["Provides shared response caching when data-service caching is enabled"], boundaries: ["Not required for the base runtime", "Local Worker caches cannot replace shared Redis in multi-cluster deployments"], tags: ["REDIS_HOST", "REDIS_PORT", "REDIS_PASSWORD"] },
    nacos: { kind: "middleware", responsibilities: ["Delivers configuration when required", "Optionally discovers studio-flink"], boundaries: ["Runtime placement still uses HTTP/SLB endpoints stored in the database", "Desktop does not register with Nacos"], tags: ["NACOS_SERVER", "NACOS_NAMESPACE", "NACOS_GROUP"] },
    "plugin-repo": { kind: "state", responsibilities: ["Stores immutable releases/{release}/plugin.zip", "Switches active versions atomically through current.json", "Feeds first cold loads and background refresh"], boundaries: ["Published release ZIPs are never overwritten", "A refresh failure keeps the last valid version and reports DEGRADED", "Running tasks keep the identity pinned at creation"], tags: ["LAZY_OBJECT_STORAGE", "current.json", "SHA-256", "STUDIO_RUNTIME_VERSION"] },
    "plugin-loader": { kind: "runtime", responsibilities: ["Validates manifest, size, hash and runtime version", "Single-flight downloads, secure extraction and cache retention", "Supplies the active identity to new executions"], boundaries: ["The OSS resolver exists only in Worker", "Normal plugin updates require no Worker restart", "Application-code updates still follow a controlled restart"], tags: [".staging", ".state", "cache", "STUDIO_PLUGIN_COLD_LOAD_TIMEOUT_SECONDS"] },
    "cluster-endpoint-a": { kind: "middleware", responsibilities: ["Carries Server-to-Worker synchronous calls", "Lets Service / SLB balance replicas inside one runtime cluster"], boundaries: ["One enabled managed endpoint is selected per cluster", "No redirects or automatic retries for write calls", "Internal endpoint authentication is isolated from business authentication"], tags: ["/internal/runtime/**", "STUDIO_RUNTIME_ENDPOINT_ALLOWED_HOSTS", "HTTP/SLB"] },
    "dispatch-row": { kind: "state", responsibilities: ["Persists target cluster, resource revision and schedule", "Uses claimToken + workerBootId + status for CAS", "Remains queued while the target cluster is offline"], boundaries: ["Does not copy decrypted datasource secrets or full request bodies", "Historical rows without targetClusterId are not claimed"], tags: ["target_cluster_id", "claim_token", "worker_boot_id", "protected_payload_ciphertext"] },
    "datasource-a": { kind: "external", responsibilities: ["Provides business databases, queues, files, objects and HTTP data", "Is accessed directly by the selected runtime cluster Worker"], boundaries: ["Server network access to business datasource ranges can be blocked", "Applicable clusters are configured by administrators, not probe results"], tags: ["datasource_cluster_binding", "connectionFingerprint", "requestedClusterId", "actualClusterId"] },
    "flink-gateway": { kind: "middleware", responsibilities: ["Runs real Flink SQL initiated by the target Worker", "Lets remote connectors obtain a task-pinned source plugin capability"], boundaries: ["Runtime callbacks point to Worker, never Server", "The all-plugin bundle is reserved for fully offline clusters"], tags: ["STUDIO_FLINK_EXECUTION_MODE", "STUDIO_FLINK_GATEWAY_BASE_URL", "flink-connector-remote"] },
  },
  edgeLabels: {
    identity: "Identity and project context", userApi: "User API", openApi: "Public service entry", metadata: "Configuration / state", cache: "Shared cache", config: "Configuration / discovery", readLog: "Read shared logs", plan: "Build SQL plan", syncRoute: "Route by runtimeClusterId", internalApi: "Internal token + cluster identity", claimWriteback: "Claim / heartbeat / writeback", logArchive: "Archive logs", execute: "Real data access and execution", flinkExecute: "Flink SQL", lazyLoad: "Lazy plugin loading", pluginAndArtifact: "Plugins / logs / artifacts", resolveResource: "Resolve resource and target", safeProxy: "SSRF-checked proxy", internalAuth: "Internal authentication", resourceConfig: "Load resource configuration", accessLog: "Access log and alert evidence", trigger: "Trigger resource", enqueue: "Create targeted Dispatch", claim: "Claim own-cluster work", instantiate: "Load immutable snapshot", casWriteback: "CAS complete / recovery", publishRelease: "Upload immutable release", switchCurrent: "Atomically replace current.json last", coldAndRefresh: "Cold load / background refresh", verifyAndExtract: "Verify and securely extract", pinIdentity: "Pin active identity", eagerLocal: "EAGER_LOCAL", scriptDependency: "Managed script dependency",
  },
};

watch(() => visibleNodeIds.value.join(","), () => {
  if (!visibleNodeIds.value.includes(selectedNodeId.value)) {
    if (activeScenario.value === "roadmap") selectedNodeId.value = activeRoadmapStageId.value;
    else if (activeScenario.value === "overview") selectedNodeId.value = "server";
    else selectedNodeId.value = activeFlow.value.stages[0]?.items[0]?.id ?? "server";
  }
});
</script>

<style scoped>
.architecture-page {
  gap: 18px;
}

.architecture-page.is-roadmap-entry {
  gap: 14px;
}

.guide-hero {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(420px, 0.9fr);
  gap: 24px;
  align-items: center;
  min-width: 0;
  padding: 22px 24px;
  overflow: hidden;
  border: 1px solid rgba(37, 99, 235, 0.14);
  border-radius: 22px;
  color: #f5f9ff;
  background:
    radial-gradient(circle at 90% 0%, rgba(56, 189, 248, 0.34), transparent 34%),
    linear-gradient(135deg, #173f7e 0%, #2758b2 58%, #2162c5 100%);
  box-shadow: 0 18px 42px rgba(24, 67, 142, 0.2);
}

.guide-hero::after {
  position: absolute;
  inset: auto -74px -94px auto;
  width: 280px;
  height: 280px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
  content: "";
  pointer-events: none;
}

.guide-hero__content,
.guide-hero__actions {
  position: relative;
  z-index: 1;
  min-width: 0;
}

.guide-hero__eyebrow {
  display: block;
  margin-bottom: 8px;
  color: rgba(224, 242, 254, 0.78);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.guide-hero h2 {
  margin: 0;
  font-size: clamp(26px, 3vw, 38px);
  line-height: 1.08;
}

.guide-hero p {
  max-width: 720px;
  margin: 12px 0 0;
  color: rgba(234, 244, 255, 0.82);
  font-size: 13px;
  line-height: 1.7;
}

.guide-hero__actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 9px;
}

.guide-hero__actions > a {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
  min-width: 0;
  padding: 11px 12px;
  border: 1px solid rgba(255, 255, 255, 0.17);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.09);
  transition: background 0.18s ease, border-color 0.18s ease, transform 0.18s ease;
}

.guide-hero__actions > a:hover,
.guide-hero__actions > a:focus-visible {
  border-color: rgba(255, 255, 255, 0.34);
  background: rgba(255, 255, 255, 0.15);
  transform: translateY(-1px);
}

.guide-hero__action-index {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 10px;
  color: #174c8f;
  background: rgba(255, 255, 255, 0.94);
  font-size: 10px;
  font-weight: 800;
}

.guide-hero__action-copy {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.guide-hero__action-copy strong {
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.guide-hero__action-copy small {
  overflow: hidden;
  color: rgba(234, 244, 255, 0.68);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.guide-hero__actions .el-icon {
  color: rgba(255, 255, 255, 0.76);
}

.architecture-page.is-roadmap-entry .architecture-workbench {
  padding: 12px;
}

.architecture-page.is-roadmap-entry .architecture-workbench__body {
  margin-top: 0;
}

.architecture-page.is-roadmap-entry .architecture-layout-shell {
  padding: 12px;
}

.architecture-hero {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(360px, 0.8fr);
  gap: 28px;
  align-items: end;
  padding: 28px;
  overflow: hidden;
  border: 1px solid rgba(68, 116, 188, 0.18);
  border-radius: 24px;
  color: #f8fbff;
  background:
    radial-gradient(circle at 86% 18%, rgba(56, 189, 248, 0.42), transparent 30%),
    radial-gradient(circle at 58% 110%, rgba(99, 102, 241, 0.32), transparent 38%),
    linear-gradient(135deg, #102d58 0%, #174c8f 52%, #12396d 100%);
  box-shadow: 0 20px 46px rgba(15, 54, 105, 0.2);
}

.architecture-hero::after {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.045) 1px, transparent 1px);
  background-size: 32px 32px;
  content: "";
  pointer-events: none;
}

.architecture-hero__content,
.architecture-hero__metrics {
  position: relative;
  z-index: 1;
}

.architecture-hero__eyebrow {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  color: rgba(224, 242, 254, 0.88);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.architecture-hero__pulse {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #67e8f9;
  box-shadow: 0 0 0 6px rgba(103, 232, 249, 0.13);
  animation: architecture-pulse 2.2s ease-in-out infinite;
}

.architecture-hero h2 {
  margin: 0;
  font-size: clamp(25px, 3vw, 40px);
  line-height: 1.15;
}

.architecture-hero p {
  max-width: 820px;
  margin: 14px 0 0;
  color: rgba(230, 241, 255, 0.84);
  font-size: 14px;
  line-height: 1.8;
}

.architecture-hero__badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 18px;
}

.architecture-hero__badges span {
  padding: 7px 11px;
  border: 1px solid rgba(191, 219, 254, 0.22);
  border-radius: 999px;
  background: rgba(15, 45, 86, 0.36);
  color: rgba(239, 246, 255, 0.92);
  font-size: 12px;
  backdrop-filter: blur(8px);
}

.architecture-hero__metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.architecture-hero__metrics article {
  display: grid;
  gap: 4px;
  min-height: 98px;
  padding: 16px;
  border: 1px solid rgba(191, 219, 254, 0.16);
  border-radius: 17px;
  background: rgba(7, 31, 66, 0.34);
  backdrop-filter: blur(10px);
}

.architecture-hero__metrics strong {
  font-size: 28px;
  line-height: 1;
}

.architecture-hero__metrics span {
  color: rgba(219, 234, 254, 0.78);
  font-size: 12px;
  line-height: 1.4;
}

.architecture-workbench {
  padding: 18px;
  border: 1px solid var(--studio-border);
  border-radius: 20px;
  background: var(--studio-surface);
  box-shadow: var(--studio-shadow);
}

.architecture-toolbar {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.architecture-toolbar__scenarios,
.architecture-toolbar__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 9px;
}

.architecture-toolbar__scenarios > span {
  color: var(--studio-text-soft);
  font-size: 12px;
  font-weight: 700;
}

.architecture-node-select {
  width: 220px;
}

.architecture-toolbar__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.architecture-scenario-note {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 13px;
  color: #355b88;
  background: rgba(37, 99, 235, 0.07);
  font-size: 12px;
  line-height: 1.55;
}

.architecture-scenario-note .el-icon {
  flex: 0 0 auto;
  margin-top: 2px;
  color: var(--studio-primary);
}

.architecture-workbench__body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 330px;
  gap: 14px;
  align-items: start;
  margin-top: 14px;
}

.architecture-workbench__body.is-roadmap {
  grid-template-columns: minmax(0, 1fr);
}

.architecture-layout-shell {
  position: relative;
  min-width: 0;
  padding: 14px;
  container-name: architecture-layout;
  container-type: inline-size;
  border: 1px solid rgba(69, 112, 176, 0.16);
  border-radius: 18px;
  background:
    radial-gradient(circle at 82% 12%, rgba(56, 189, 248, 0.11), transparent 28%),
    linear-gradient(rgba(51, 92, 150, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(51, 92, 150, 0.05) 1px, transparent 1px),
    rgba(248, 251, 255, 0.82);
  background-size: auto, 24px 24px, 24px 24px, auto;
}

.architecture-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  justify-content: flex-end;
  margin-bottom: 12px;
  padding: 8px 10px;
  border: 1px solid rgba(71, 113, 173, 0.16);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 8px 18px rgba(42, 71, 112, 0.08);
  backdrop-filter: blur(8px);
}

.architecture-legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: #587092;
  font-size: 10px;
  font-weight: 600;
}

.architecture-legend i,
.architecture-kind-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.architecture-legend .is-entry,
.architecture-kind-dot.is-entry { background: #2563eb; }
.architecture-legend .is-control,
.architecture-kind-dot.is-control { background: #1d4ed8; }
.architecture-legend .is-runtime,
.architecture-kind-dot.is-runtime { background: #0f766e; }
.architecture-legend .is-state,
.architecture-kind-dot.is-state { background: #b45309; }
.architecture-legend .is-middleware,
.architecture-kind-dot.is-middleware { background: #7c3aed; }
.architecture-legend .is-external,
.architecture-kind-dot.is-external { background: #52677f; }

.architecture-roadmap {
  display: grid;
  gap: 14px;
}

.architecture-roadmap-header {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
  align-items: center;
  padding: 17px;
  border: 1px solid rgba(71, 113, 173, 0.17);
  border-radius: 17px;
  background:
    radial-gradient(circle at 90% 0%, rgba(56, 189, 248, 0.12), transparent 34%),
    linear-gradient(135deg, rgba(255, 255, 255, 0.94), rgba(239, 246, 255, 0.78));
}

.architecture-roadmap-header.has-architecture-link {
  grid-template-columns: minmax(0, 1.25fr) minmax(360px, 0.75fr);
}

.architecture-roadmap-header__aside {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.architecture-roadmap-header > div:first-child > span,
.architecture-roadmap-focus__summary > span {
  color: var(--studio-primary);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.architecture-roadmap-header h3 {
  margin: 5px 0 0;
  color: #173a68;
  font-size: 20px;
}

.architecture-roadmap-header p {
  margin: 8px 0 0;
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.65;
}

.architecture-roadmap-architecture-link {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 3px 12px;
  align-items: center;
  min-width: 0;
  padding: 13px 14px;
  overflow: hidden;
  border: 1px solid rgba(37, 99, 235, 0.18);
  border-radius: 13px;
  color: #fff;
  text-decoration: none;
  background:
    radial-gradient(circle at 94% 0%, rgba(56, 189, 248, 0.38), transparent 42%),
    linear-gradient(135deg, #173a68, #1d4ed8 62%, #0f766e);
  box-shadow: 0 10px 24px rgba(29, 78, 216, 0.2);
  transition: box-shadow 0.18s ease, transform 0.18s ease;
}

.architecture-roadmap-architecture-link:hover,
.architecture-roadmap-architecture-link:focus-visible {
  color: #fff;
  box-shadow: 0 14px 30px rgba(29, 78, 216, 0.27);
  transform: translateY(-2px);
}

.architecture-roadmap-architecture-link > span,
.architecture-roadmap-architecture-link > strong,
.architecture-roadmap-architecture-link > small {
  grid-column: 1;
  min-width: 0;
}

.architecture-roadmap-architecture-link > span {
  color: rgba(255, 255, 255, 0.7);
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.architecture-roadmap-architecture-link > strong {
  font-size: 13px;
  line-height: 1.35;
}

.architecture-roadmap-architecture-link > small {
  color: rgba(255, 255, 255, 0.72);
  font-size: 9px;
  line-height: 1.45;
}

.architecture-roadmap-architecture-link > em {
  display: inline-flex;
  grid-row: 1 / 4;
  grid-column: 2;
  gap: 5px;
  align-items: center;
  align-self: stretch;
  padding-left: 12px;
  border-left: 1px solid rgba(255, 255, 255, 0.2);
  color: #fff;
  font-size: 9px;
  font-style: normal;
  font-weight: 700;
  white-space: nowrap;
}

.architecture-roadmap-system {
  position: relative;
  display: grid;
  gap: 14px;
  padding: 14px;
  overflow: hidden;
  border: 1px solid rgba(71, 113, 173, 0.17);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.5);
}

.architecture-roadmap-system::before,
.architecture-roadmap-system::after {
  position: absolute;
  top: 54px;
  bottom: 54px;
  width: 2px;
  background: linear-gradient(180deg, #0f766e, rgba(37, 99, 235, 0.4) 50%, #7c3aed);
  content: "";
  opacity: 0.42;
}

.architecture-roadmap-system::before { left: 7px; }
.architecture-roadmap-system::after { right: 7px; }

.architecture-roadmap-crosscut {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(105px, 0.18fr) minmax(250px, 0.68fr) minmax(0, 1fr);
  gap: 12px;
  align-items: center;
  width: 100%;
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid var(--roadmap-crosscut-border);
  border-radius: 14px;
  color: #173a68;
  text-align: left;
  background: var(--roadmap-crosscut-background);
  box-shadow: 0 8px 20px rgba(42, 71, 112, 0.07);
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}

.architecture-roadmap-crosscut.is-lineage {
  --roadmap-crosscut-border: rgba(15, 118, 110, 0.24);
  --roadmap-crosscut-background:
    radial-gradient(circle at 78% -35%, rgba(37, 99, 235, 0.16), transparent 46%),
    linear-gradient(112deg, rgba(236, 253, 245, 0.98), rgba(248, 251, 255, 0.97));
  grid-template-columns: minmax(145px, 0.23fr) minmax(300px, 0.72fr) minmax(0, 1fr);
  padding-block: 15px;
  overflow: hidden;
  box-shadow: 0 12px 28px rgba(15, 118, 110, 0.1);
}

.architecture-roadmap-crosscut.is-lineage::before {
  position: absolute;
  top: 0;
  right: 16px;
  left: 16px;
  height: 3px;
  border-radius: 0 0 999px 999px;
  background: linear-gradient(90deg, #0f766e, #14b8a6 42%, #2563eb);
  content: "";
  opacity: 0.82;
}

.architecture-roadmap-crosscut.is-alert {
  --roadmap-crosscut-border: rgba(124, 58, 237, 0.23);
  --roadmap-crosscut-background: linear-gradient(90deg, rgba(250, 247, 255, 0.96), rgba(255, 248, 232, 0.88));
}

.architecture-roadmap-crosscut:hover,
.architecture-roadmap-crosscut:focus-visible {
  transform: translateY(-1px);
  box-shadow: 0 11px 24px rgba(42, 71, 112, 0.12);
}

.architecture-roadmap-crosscut.is-selected {
  border-color: #2563eb;
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.13), 0 12px 26px rgba(42, 71, 112, 0.13);
}

.architecture-roadmap-crosscut__label {
  color: #1d4ed8;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.architecture-roadmap-crosscut.is-lineage .architecture-roadmap-crosscut__label { color: #0f766e; }
.architecture-roadmap-crosscut.is-alert .architecture-roadmap-crosscut__label { color: #7c3aed; }

.architecture-roadmap-crosscut__identity {
  display: flex;
  gap: 10px;
  align-items: center;
  min-width: 0;
}

.architecture-roadmap-lineage-symbol {
  position: relative;
  flex: 0 0 46px;
  width: 46px;
  height: 40px;
  border: 1px solid rgba(255, 255, 255, 0.72);
  border-radius: 13px;
  background: linear-gradient(135deg, #0f766e, #2563eb);
  box-shadow: 0 8px 18px rgba(15, 118, 110, 0.2), inset 0 1px 0 rgba(255, 255, 255, 0.28);
}

.architecture-roadmap-lineage-symbol::before,
.architecture-roadmap-lineage-symbol::after {
  position: absolute;
  background: rgba(255, 255, 255, 0.74);
  content: "";
}

.architecture-roadmap-lineage-symbol::before {
  top: 19px;
  left: 12px;
  width: 19px;
  height: 2px;
}

.architecture-roadmap-lineage-symbol::after {
  top: 8px;
  left: 29px;
  width: 2px;
  height: 24px;
}

.architecture-roadmap-lineage-symbol i {
  position: absolute;
  z-index: 1;
  width: 7px;
  height: 7px;
  border: 2px solid rgba(255, 255, 255, 0.72);
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 2px 7px rgba(16, 59, 96, 0.2);
}

.architecture-roadmap-lineage-symbol i:nth-child(1) { top: 16px; left: 7px; }
.architecture-roadmap-lineage-symbol i:nth-child(2) { top: 5px; left: 27px; }
.architecture-roadmap-lineage-symbol i:nth-child(3) { top: 16px; left: 27px; }
.architecture-roadmap-lineage-symbol i:nth-child(4) { top: 27px; left: 27px; }

.architecture-roadmap-crosscut__content {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.architecture-roadmap-crosscut__content strong {
  font-size: 13px;
  line-height: 1.35;
}

.architecture-roadmap-crosscut__content small {
  color: var(--studio-text-soft);
  font-size: 10px;
  line-height: 1.5;
}

.architecture-roadmap-crosscut__items {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  justify-content: flex-end;
}

.architecture-roadmap-crosscut__items i {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  padding: 4px 7px;
  border: 1px solid rgba(71, 113, 173, 0.13);
  border-radius: 999px;
  color: #536d90;
  background: rgba(255, 255, 255, 0.75);
  font-size: 8px;
  font-style: normal;
  font-weight: 700;
}

.architecture-roadmap-crosscut.is-lineage .architecture-roadmap-crosscut__items i {
  border-color: rgba(15, 118, 110, 0.15);
  color: #306b72;
  background: rgba(255, 255, 255, 0.84);
}

.architecture-roadmap-crosscut.is-lineage .architecture-roadmap-crosscut__items i::before {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: linear-gradient(135deg, #14b8a6, #2563eb);
  content: "";
}

.architecture-roadmap-track {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.architecture-roadmap-track li:not(:last-child)::after {
  position: absolute;
  z-index: 3;
  top: 79px;
  right: -9px;
  width: 8px;
  height: 8px;
  border-top: 2px solid rgba(84, 116, 153, 0.52);
  border-right: 2px solid rgba(84, 116, 153, 0.52);
  content: "";
  pointer-events: none;
  transform: translateY(-50%) rotate(45deg);
}

.architecture-roadmap-track li {
  --roadmap-stage-accent: #52677f;
  --roadmap-stage-soft: rgba(82, 103, 127, 0.08);
  position: relative;
  z-index: 1;
  min-width: 0;
}

.architecture-roadmap-track li.is-source { --roadmap-stage-accent: #0e7490; --roadmap-stage-soft: rgba(14, 116, 144, 0.09); }
.architecture-roadmap-track li.is-model { --roadmap-stage-accent: #2563eb; --roadmap-stage-soft: rgba(37, 99, 235, 0.09); }
.architecture-roadmap-track li.is-integration { --roadmap-stage-accent: #0f766e; --roadmap-stage-soft: rgba(15, 118, 110, 0.09); }
.architecture-roadmap-track li.is-development { --roadmap-stage-accent: #7c3aed; --roadmap-stage-soft: rgba(124, 58, 237, 0.09); }
.architecture-roadmap-track li.is-quality { --roadmap-stage-accent: #b45309; --roadmap-stage-soft: rgba(180, 83, 9, 0.09); }
.architecture-roadmap-track li.is-service { --roadmap-stage-accent: #be185d; --roadmap-stage-soft: rgba(190, 24, 93, 0.08); }

.architecture-roadmap-stage-button {
  position: relative;
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  gap: 5px 9px;
  align-content: start;
  width: 100%;
  min-width: 0;
  min-height: 158px;
  padding: 13px 12px 42px;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--roadmap-stage-accent) 28%, transparent);
  border-radius: 14px;
  color: #173a68;
  text-align: left;
  background: linear-gradient(155deg, rgba(255, 255, 255, 0.98), var(--roadmap-stage-soft));
  box-shadow: 0 7px 15px rgba(42, 71, 112, 0.07);
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}

.architecture-roadmap-stage-button::before {
  position: absolute;
  top: 0;
  right: 12px;
  left: 12px;
  height: 3px;
  border-radius: 0 0 999px 999px;
  background: var(--roadmap-stage-accent);
  content: "";
  opacity: 0.62;
}

.architecture-roadmap-stage-button:hover,
.architecture-roadmap-stage-button:focus-visible,
.architecture-roadmap-stage-button.is-active {
  border-color: var(--roadmap-stage-accent);
  transform: translateY(-2px);
  box-shadow: 0 12px 24px color-mix(in srgb, var(--roadmap-stage-accent) 15%, transparent);
}

.architecture-roadmap-stage-button.is-active,
.architecture-roadmap-stage-button.is-selected {
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--roadmap-stage-accent) 18%, transparent), 0 12px 24px rgba(42, 71, 112, 0.12);
}

.architecture-roadmap-stage-button__index {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 11px;
  color: #fff;
  background: var(--roadmap-stage-accent);
  box-shadow: 0 3px 10px color-mix(in srgb, var(--roadmap-stage-accent) 28%, transparent);
  font-size: 9px;
  font-weight: 700;
}

.architecture-roadmap-stage-button__kind {
  align-self: center;
  color: var(--roadmap-stage-accent);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.06em;
}

.architecture-roadmap-stage-button strong {
  grid-column: 1 / -1;
  min-width: 0;
  font-size: 12px;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.architecture-roadmap-stage-button small {
  grid-column: 1 / -1;
  color: #607998;
  font-size: 9px;
  line-height: 1.45;
}

.architecture-roadmap-stage-route {
  position: absolute;
  z-index: 5;
  right: 12px;
  bottom: 11px;
  display: inline-flex;
  gap: 4px;
  align-items: center;
  max-width: calc(100% - 24px);
  padding: 4px 0;
  color: var(--roadmap-stage-accent);
  text-decoration: none;
  transition: color 0.18s ease, transform 0.18s ease;
}

.architecture-roadmap-stage-route:hover,
.architecture-roadmap-stage-route:focus-visible {
  color: color-mix(in srgb, var(--roadmap-stage-accent) 76%, #102f57);
  transform: translateX(2px);
}

.architecture-roadmap-stage-route > strong {
  min-width: 0;
  overflow: hidden;
  color: inherit;
  font-size: 9px;
  font-weight: 700;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.architecture-roadmap-stage-route .el-icon {
  flex: 0 0 auto;
  color: inherit;
  font-size: 13px;
}

.architecture-roadmap-focus {
  --roadmap-focus-accent: #2563eb;
  --roadmap-focus-background: rgba(239, 246, 255, 0.78);
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(235px, 0.42fr) minmax(0, 1fr);
  gap: 16px;
  padding: 16px;
  border: 1px solid color-mix(in srgb, var(--roadmap-focus-accent) 28%, transparent);
  border-radius: 17px;
  background:
    radial-gradient(circle at 100% 0%, color-mix(in srgb, var(--roadmap-focus-accent) 13%, transparent), transparent 34%),
    var(--roadmap-focus-background);
  box-shadow: 0 12px 28px rgba(42, 71, 112, 0.08);
}

.architecture-roadmap-focus.is-source { --roadmap-focus-accent: #0e7490; --roadmap-focus-background: rgba(239, 250, 252, 0.88); }
.architecture-roadmap-focus.is-model { --roadmap-focus-accent: #2563eb; --roadmap-focus-background: rgba(239, 246, 255, 0.88); }
.architecture-roadmap-focus.is-integration { --roadmap-focus-accent: #0f766e; --roadmap-focus-background: rgba(236, 253, 245, 0.88); }
.architecture-roadmap-focus.is-development { --roadmap-focus-accent: #7c3aed; --roadmap-focus-background: rgba(250, 247, 255, 0.88); }
.architecture-roadmap-focus.is-quality { --roadmap-focus-accent: #b45309; --roadmap-focus-background: rgba(255, 248, 232, 0.9); }
.architecture-roadmap-focus.is-service { --roadmap-focus-accent: #be185d; --roadmap-focus-background: rgba(253, 242, 248, 0.86); }

.architecture-roadmap-focus__summary {
  display: grid;
  gap: 7px;
  align-content: start;
  min-width: 0;
}

.architecture-roadmap-focus__summary > span {
  color: var(--roadmap-focus-accent);
}

.architecture-roadmap-focus h4 {
  margin: 0;
  color: #173a68;
  font-size: 18px;
}

.architecture-roadmap-focus p {
  margin: 0;
  color: var(--studio-text-soft);
  font-size: 11px;
  line-height: 1.65;
}

.architecture-roadmap-outcome {
  display: grid;
  gap: 4px;
  margin-top: 4px;
  padding: 10px;
  border-left: 4px solid var(--roadmap-focus-accent);
  border-radius: 0 10px 10px 0;
  background: rgba(255, 255, 255, 0.7);
}

.architecture-roadmap-outcome strong {
  color: var(--roadmap-focus-accent);
  font-size: 9px;
}

.architecture-roadmap-outcome span {
  color: #496586;
  font-size: 10px;
  line-height: 1.5;
}

.architecture-roadmap-focus__capabilities {
  display: grid;
  gap: 9px;
  min-width: 0;
}

.architecture-roadmap-focus__capabilities-title {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  color: #24496f;
  font-size: 11px;
}

.architecture-roadmap-focus__capabilities-title span {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  color: var(--roadmap-focus-accent);
  background: rgba(255, 255, 255, 0.78);
  font-size: 10px;
  font-weight: 700;
}

.architecture-roadmap-capability-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.architecture-roadmap-capability {
  display: grid;
  gap: 4px;
  min-width: 0;
  min-height: 108px;
  padding: 11px;
  border: 1px solid rgba(71, 113, 173, 0.16);
  border-radius: 12px;
  color: #173a68;
  text-align: left;
  background: rgba(255, 255, 255, 0.8);
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}

.architecture-roadmap-capability:hover,
.architecture-roadmap-capability:focus-visible,
.architecture-roadmap-capability.is-selected {
  border-color: var(--roadmap-focus-accent);
  box-shadow: 0 8px 18px color-mix(in srgb, var(--roadmap-focus-accent) 13%, transparent);
  transform: translateY(-1px);
}

.architecture-roadmap-capability > span {
  color: var(--roadmap-focus-accent);
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 0.06em;
}

.architecture-roadmap-capability strong {
  font-size: 11px;
  line-height: 1.35;
}

.architecture-roadmap-capability small {
  color: #607998;
  font-size: 9px;
  line-height: 1.45;
}

.architecture-roadmap-capability em {
  align-self: end;
  color: #7085a1;
  font-size: 8px;
  font-style: normal;
  line-height: 1.4;
}

.architecture-roadmap-focus-enter-active,
.architecture-roadmap-focus-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.architecture-roadmap-focus-enter-from {
  opacity: 0;
  transform: translateX(10px);
}

.architecture-roadmap-focus-leave-to {
  opacity: 0;
  transform: translateX(-10px);
}

.architecture-overview,
.architecture-flow-layout {
  display: grid;
  gap: 14px;
}

.architecture-entry-band,
.architecture-zone {
  min-width: 0;
  padding: 14px;
  border: 1px solid rgba(71, 113, 173, 0.17);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.82);
}

.architecture-entry-band {
  display: grid;
  grid-template-columns: minmax(190px, 0.32fr) minmax(0, 1fr);
  gap: 14px;
  align-items: center;
}

.architecture-zone-heading {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.architecture-zone-heading__kind {
  color: var(--studio-primary);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.09em;
  text-transform: uppercase;
}

.architecture-zone-heading strong {
  color: #173a68;
  font-size: 14px;
  line-height: 1.35;
}

.architecture-zone-heading small {
  color: var(--studio-text-soft);
  font-size: 11px;
  line-height: 1.5;
}

.architecture-entry-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.architecture-route-rail {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  justify-content: center;
  align-items: center;
  min-height: 34px;
  padding: 5px 42px;
  color: #315f9c;
}

.architecture-route-rail::before,
.architecture-route-rail::after {
  position: absolute;
  top: 50%;
  width: 32px;
  height: 2px;
  background: currentColor;
  content: "";
}

.architecture-route-rail::before { left: 5px; }
.architecture-route-rail::after { right: 5px; }

.architecture-route-rail span {
  padding: 4px 8px;
  border: 1px solid rgba(37, 99, 235, 0.15);
  border-radius: 999px;
  background: rgba(239, 246, 255, 0.94);
  font-size: 10px;
  font-weight: 700;
}

.architecture-tier {
  display: grid;
  gap: 14px;
  padding: 14px;
  border: 1px solid rgba(71, 113, 173, 0.18);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.62);
}

.architecture-tier-heading {
  display: grid;
  grid-template-columns: minmax(210px, 0.38fr) minmax(0, 1fr);
  gap: 3px 18px;
  align-items: end;
}

.architecture-tier-heading > span {
  grid-row: 1;
  color: var(--studio-primary);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.architecture-tier-heading > strong {
  grid-row: 2;
  color: #173a68;
  font-size: 16px;
}

.architecture-tier-heading > small {
  grid-row: 1 / span 2;
  grid-column: 2;
  color: var(--studio-text-soft);
  font-size: 11px;
  line-height: 1.55;
}

.architecture-application-tier {
  background: linear-gradient(135deg, rgba(238, 244, 255, 0.7), rgba(236, 253, 245, 0.58));
}

.architecture-application-map {
  display: grid;
  grid-template-columns: minmax(0, 0.72fr) minmax(104px, 0.34fr) minmax(0, 1.5fr);
  gap: 12px;
  align-items: start;
}

.architecture-runtime-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  align-items: start;
  min-width: 0;
}

.architecture-worker-shared-capabilities {
  position: relative;
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: minmax(190px, 0.36fr) minmax(0, 1fr);
  gap: 12px;
  min-width: 0;
  padding: 12px;
  overflow: hidden;
  border: 1px solid rgba(15, 118, 110, 0.2);
  border-radius: 15px;
  background:
    radial-gradient(circle at 100% 0%, rgba(37, 99, 235, 0.1), transparent 36%),
    linear-gradient(135deg, rgba(236, 253, 245, 0.86), rgba(239, 246, 255, 0.74));
  box-shadow: 0 10px 22px rgba(42, 71, 112, 0.07);
}

.architecture-worker-shared-capabilities::before {
  position: absolute;
  top: 0;
  right: 14px;
  left: 14px;
  height: 3px;
  border-radius: 0 0 999px 999px;
  background: linear-gradient(90deg, #0f766e, #14b8a6 48%, #2563eb);
  content: "";
  opacity: 0.76;
}

.architecture-worker-shared-capabilities__heading {
  display: grid;
  gap: 6px;
  align-content: center;
  min-width: 0;
  padding: 9px 11px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.58);
}

.architecture-worker-shared-capabilities__heading > span {
  color: #0f766e;
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.architecture-worker-shared-capabilities__heading > strong {
  color: #173a68;
  font-size: 14px;
  line-height: 1.35;
}

.architecture-worker-shared-capabilities__heading > small {
  color: #607998;
  font-size: 9px;
  line-height: 1.55;
}

.architecture-worker-shared-capabilities__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  min-width: 0;
}

.architecture-worker-shared-capabilities__grid .architecture-node-card.is-compact {
  min-height: 82px;
}

.architecture-server-worker-bridge {
  display: grid;
  gap: 9px;
  place-items: center;
  align-content: center;
  min-width: 0;
  color: #2563eb;
  text-align: center;
}

.architecture-server-worker-bridge > span {
  color: #315f9c;
  font-size: 10px;
  font-weight: 700;
  line-height: 1.45;
}

.architecture-server-worker-bridge > i {
  position: relative;
  width: calc(100% - 18px);
  height: 3px;
  background: currentColor;
}

.architecture-server-worker-bridge > i::after {
  position: absolute;
  top: -4px;
  right: -1px;
  width: 0;
  height: 0;
  border-top: 5px solid transparent;
  border-bottom: 5px solid transparent;
  border-left: 8px solid currentColor;
  content: "";
}

.architecture-server-worker-bridge > div {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  justify-content: center;
}

.architecture-server-worker-bridge code {
  padding: 3px 6px;
  border: 1px solid rgba(37, 99, 235, 0.16);
  border-radius: 6px;
  color: #315f9c;
  background: rgba(239, 246, 255, 0.94);
  font-size: 8px;
}

.architecture-shared-tier {
  background: rgba(255, 248, 232, 0.62);
}

.architecture-shared-tier > .architecture-zone {
  padding: 12px;
}

.architecture-zone__nodes.is-horizontal {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 9px;
  align-items: start;
}

.architecture-downstream-tier {
  background: rgba(248, 250, 252, 0.72);
}

.architecture-downstream-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.architecture-tier-connector {
  display: grid;
  gap: 7px;
  place-items: center;
  color: #315f9c;
  text-align: center;
}

.architecture-tier-connector > strong {
  color: currentColor;
  font-size: 10px;
  line-height: 1.4;
}

.architecture-tier-connector > i {
  position: relative;
  width: 3px;
  height: 32px;
  background: currentColor;
}

.architecture-tier-connector > i::after {
  position: absolute;
  bottom: -1px;
  left: -4px;
  width: 0;
  height: 0;
  border-top: 8px solid currentColor;
  border-right: 5px solid transparent;
  border-left: 5px solid transparent;
  content: "";
}

.architecture-tier-connector.is-shared {
  color: #b45309;
}

.architecture-tier-connector.is-primary {
  color: #2563eb;
}

.architecture-tier-connector.is-storage {
  color: #b45309;
}

.architecture-tier-connector.is-plugin {
  color: #7c3aed;
}

.architecture-tier-connector.is-downstream {
  color: #0f766e;
}

.architecture-shared-links {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  width: min(760px, 100%);
}

.architecture-shared-links span {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  justify-content: center;
  padding: 6px 9px;
  border: 1px solid rgba(180, 83, 9, 0.18);
  border-radius: 999px;
  color: #7c5a39;
  background: rgba(255, 250, 240, 0.94);
  font-size: 9px;
  line-height: 1.4;
}

.architecture-shared-links span.is-plugin {
  border-color: rgba(124, 58, 237, 0.18);
  color: #6d48a3;
  background: rgba(250, 247, 255, 0.94);
}

.architecture-shared-links strong {
  color: #24496f;
}

.architecture-zone {
  display: grid;
  gap: 12px;
  height: auto;
  align-content: start;
}

.architecture-zone.is-control { background: rgba(238, 244, 255, 0.9); }
.architecture-zone.is-state { background: rgba(255, 248, 232, 0.86); }
.architecture-zone.is-runtime { background: rgba(236, 253, 245, 0.82); }

.architecture-zone__nodes,
.architecture-node-stack {
  display: grid;
  gap: 0;
  min-width: 0;
}

.architecture-node-card {
  position: relative;
  display: grid;
  gap: 5px;
  width: 100%;
  min-width: 0;
  min-height: 90px;
  padding: 13px 13px 13px 17px;
  overflow: hidden;
  border: 1px solid var(--node-border, rgba(71, 113, 173, 0.22));
  border-radius: 13px;
  color: #102a4e;
  text-align: left;
  background: var(--node-background, rgba(255, 255, 255, 0.94));
  box-shadow: 0 7px 14px rgba(42, 71, 112, 0.08);
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}

.architecture-node-card::before {
  position: absolute;
  inset: 0 auto 0 0;
  width: 5px;
  background: var(--node-accent, #52677f);
  content: "";
}

.architecture-node-card:hover,
.architecture-node-card:focus-visible {
  border-color: var(--node-accent, #52677f);
  box-shadow: 0 10px 20px rgba(37, 82, 143, 0.14);
  transform: translateY(-1px);
}

.architecture-node-card.is-selected {
  border-color: var(--node-accent, #2563eb);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--node-accent, #2563eb) 22%, transparent), 0 12px 24px rgba(37, 82, 143, 0.16);
}

.architecture-node-card.is-entry { --node-accent: #2563eb; --node-border: #93c5fd; --node-background: #eff6ff; }
.architecture-node-card.is-control { --node-accent: #1d4ed8; --node-border: #9abaf5; --node-background: #f3f7ff; }
.architecture-node-card.is-runtime { --node-accent: #0f766e; --node-border: #86cfc0; --node-background: #f0fdf9; }
.architecture-node-card.is-state { --node-accent: #b45309; --node-border: #e7bd72; --node-background: #fffaf0; }
.architecture-node-card.is-middleware { --node-accent: #7c3aed; --node-border: #c8b4ed; --node-background: #faf7ff; }
.architecture-node-card.is-external { --node-accent: #52677f; --node-border: #b6c3d1; --node-background: #f8fafc; }

.architecture-node-card__kind {
  color: var(--node-accent, #52677f);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.07em;
  text-transform: uppercase;
}

.architecture-node-card strong {
  min-width: 0;
  font-size: 13px;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.architecture-node-card small {
  color: #607998;
  font-size: 10px;
  line-height: 1.45;
}

.architecture-node-card.is-compact {
  min-height: 72px;
  padding: 10px 10px 10px 14px;
  box-shadow: none;
}

.architecture-node-card__toggle {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  margin-top: 3px;
  color: var(--node-accent, #2563eb);
  font-size: 9px;
  font-weight: 700;
}

.architecture-node-connector {
  position: relative;
  display: grid;
  place-items: center;
  min-height: 36px;
  color: #7186a5;
}

.architecture-node-connector::before {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 50%;
  width: 2px;
  background: currentColor;
  content: "";
}

.architecture-node-connector::after {
  position: absolute;
  bottom: 0;
  left: calc(50% - 4px);
  width: 0;
  height: 0;
  border-top: 6px solid currentColor;
  border-right: 4px solid transparent;
  border-left: 4px solid transparent;
  content: "";
}

.architecture-node-connector span {
  z-index: 1;
  max-width: calc(100% - 16px);
  padding: 2px 6px;
  border-radius: 999px;
  color: currentColor;
  background: #f8fbff;
  font-size: 9px;
  font-weight: 700;
  text-align: center;
}

.architecture-node-connector.is-primary { color: #2563eb; }
.architecture-node-connector.is-storage { color: #b45309; }
.architecture-node-connector.is-plugin { color: #7c3aed; }

.architecture-node-children {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 7px;
  margin: 8px 0 2px;
  padding: 8px;
  border: 1px dashed color-mix(in srgb, var(--node-accent, #2563eb) 25%, transparent);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.56);
}

.architecture-shared-children {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 9px;
  padding: 10px;
  border: 1px dashed rgba(180, 83, 9, 0.24);
  border-radius: 13px;
  background: rgba(255, 255, 255, 0.5);
}

.architecture-flow-boundary {
  display: flex;
  justify-content: center;
}

.architecture-flow-boundary span {
  padding: 6px 10px;
  border: 1px dashed rgba(71, 113, 173, 0.28);
  border-radius: 999px;
  color: #52709a;
  background: rgba(241, 247, 255, 0.88);
  font-size: 10px;
  font-weight: 700;
}

.architecture-flow-stage-list {
  display: grid;
  gap: 0;
}

.architecture-flow-stage {
  display: grid;
  grid-template-columns: minmax(205px, 0.4fr) minmax(0, 1fr);
  gap: 16px;
  align-items: stretch;
  min-width: 0;
  padding: 15px;
  border: 1px solid rgba(71, 113, 173, 0.18);
  border-radius: 17px;
  background: rgba(255, 255, 255, 0.82);
  box-shadow: 0 10px 24px rgba(42, 71, 112, 0.06);
}

.architecture-flow-stage.is-entry {
  background: linear-gradient(135deg, rgba(239, 246, 255, 0.94), rgba(248, 251, 255, 0.82));
}

.architecture-flow-stage.is-control {
  background: linear-gradient(135deg, rgba(238, 244, 255, 0.96), rgba(244, 247, 255, 0.84));
}

.architecture-flow-stage.is-runtime {
  background: linear-gradient(135deg, rgba(236, 253, 245, 0.92), rgba(245, 255, 251, 0.82));
}

.architecture-flow-stage.is-state {
  background: linear-gradient(135deg, rgba(255, 248, 232, 0.94), rgba(255, 252, 245, 0.84));
}

.architecture-flow-stage.is-external {
  background: linear-gradient(135deg, rgba(248, 250, 252, 0.96), rgba(255, 255, 255, 0.84));
}

.architecture-flow-stage__heading {
  display: grid;
  gap: 6px;
  align-content: center;
  min-width: 0;
}

.architecture-flow-stage__heading > span {
  color: var(--studio-primary);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.09em;
  text-transform: uppercase;
}

.architecture-flow-stage__heading > strong {
  color: #173a68;
  font-size: 15px;
  line-height: 1.35;
}

.architecture-flow-stage__heading > small {
  color: var(--studio-text-soft);
  font-size: 11px;
  line-height: 1.55;
}

.architecture-flow-stage__nodes {
  display: flex;
  gap: 10px;
  align-items: center;
  min-width: 0;
}

.architecture-flow-stage__nodes > .architecture-node-stack {
  flex: 1 1 0;
}

.architecture-flow-stage__nodes.is-parallel {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  align-items: stretch;
}

.architecture-flow-inline-connector {
  display: grid;
  flex: 0 0 86px;
  gap: 5px;
  place-items: center;
  min-width: 0;
  color: #2563eb;
}

.architecture-flow-inline-connector span {
  max-width: 84px;
  color: currentColor;
  font-size: 9px;
  font-weight: 700;
  line-height: 1.35;
  text-align: center;
}

.architecture-flow-inline-connector i {
  position: relative;
  width: 48px;
  height: 2px;
  background: currentColor;
}

.architecture-flow-inline-connector i::after {
  position: absolute;
  top: -3px;
  right: -1px;
  width: 0;
  height: 0;
  border-top: 4px solid transparent;
  border-bottom: 4px solid transparent;
  border-left: 7px solid currentColor;
  content: "";
}

.architecture-flow-inline-connector.is-storage { color: #b45309; }
.architecture-flow-inline-connector.is-plugin { color: #7c3aed; }
.architecture-flow-inline-connector.is-secondary { color: #7186a5; }

.architecture-flow-shared-tier {
  background: linear-gradient(135deg, rgba(255, 248, 232, 0.68), rgba(250, 247, 255, 0.68));
}

.architecture-flow-shared-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 9px;
  align-items: start;
}

.architecture-expand-enter-active,
.architecture-expand-leave-active {
  overflow: hidden;
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.architecture-expand-enter-from,
.architecture-expand-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

.architecture-detail-panel {
  position: sticky;
  top: 12px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
  padding: 18px;
  border: 1px solid rgba(66, 111, 180, 0.16);
  border-radius: 18px;
  background:
    radial-gradient(circle at 100% 0%, rgba(56, 189, 248, 0.11), transparent 34%),
    rgba(255, 255, 255, 0.86);
}

.architecture-detail-panel__header h3 {
  margin: 10px 0 0;
  font-size: 20px;
}

.architecture-detail-panel__header p {
  margin: 8px 0 0;
  color: var(--studio-text-soft);
  font-size: 13px;
  line-height: 1.65;
}

.architecture-kind-badge {
  display: inline-flex;
  padding: 5px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
}

.architecture-kind-badge.is-entry { color: #1d4ed8; background: #dbeafe; }
.architecture-kind-badge.is-control { color: #1e40af; background: #dbeafe; }
.architecture-kind-badge.is-runtime { color: #0f766e; background: #ccfbf1; }
.architecture-kind-badge.is-state { color: #a16207; background: #fef3c7; }
.architecture-kind-badge.is-middleware { color: #6d28d9; background: #ede9fe; }
.architecture-kind-badge.is-external { color: #475569; background: #e2e8f0; }

.architecture-detail-panel section {
  display: grid;
  gap: 8px;
}

.architecture-detail-panel h4 {
  margin: 0;
  color: #173a68;
  font-size: 12px;
}

.architecture-detail-panel ul {
  display: grid;
  gap: 7px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.architecture-detail-panel li {
  position: relative;
  padding-left: 15px;
  color: #496586;
  font-size: 12px;
  line-height: 1.55;
}

.architecture-detail-panel li::before {
  position: absolute;
  top: 0.62em;
  left: 0;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--studio-primary);
  content: "";
}

.architecture-boundary-list li::before {
  border-radius: 2px;
  background: var(--studio-warning);
  transform: rotate(45deg);
}

.architecture-tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.architecture-tag-list code {
  padding: 4px 7px;
  border: 1px solid rgba(50, 98, 165, 0.13);
  border-radius: 7px;
  color: #31577f;
  background: rgba(37, 99, 235, 0.06);
  font-size: 10px;
  overflow-wrap: anywhere;
}

.architecture-expand-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  width: 100%;
  margin-top: auto;
  padding: 10px 13px;
  border: 1px solid rgba(37, 99, 235, 0.22);
  border-radius: 12px;
  color: var(--studio-primary-deep);
  background: rgba(37, 99, 235, 0.06);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  font-weight: 700;
}

.architecture-expand-button:hover {
  background: rgba(37, 99, 235, 0.11);
}

@keyframes architecture-pulse {
  0%, 100% { transform: scale(1); opacity: 1; }
  50% { transform: scale(0.72); opacity: 0.66; }
}

@media (prefers-reduced-motion: reduce) {
  .architecture-hero__pulse {
    animation: none;
  }
}

@media (max-width: 1480px) {
  .architecture-workbench__body {
    grid-template-columns: minmax(0, 1fr);
  }

  .architecture-detail-panel {
    position: static;
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    align-items: start;
  }

  .architecture-detail-panel__header,
  .architecture-expand-button {
    grid-column: 1 / -1;
  }
}

@container architecture-layout (max-width: 900px) {
  .architecture-roadmap-header,
  .architecture-roadmap-focus {
    grid-template-columns: minmax(0, 1fr);
  }

  .architecture-roadmap-track {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .architecture-roadmap-track li:nth-child(3n)::after {
    display: none;
  }

  .architecture-roadmap-stage-button {
    min-height: 154px;
  }

  .architecture-roadmap-crosscut {
    grid-template-columns: minmax(95px, 0.2fr) minmax(0, 1fr);
  }

  .architecture-roadmap-crosscut.is-lineage {
    grid-template-columns: minmax(145px, 0.23fr) minmax(0, 1fr);
  }

  .architecture-roadmap-crosscut__items {
    grid-column: 1 / -1;
    justify-content: flex-start;
  }

  .architecture-flow-stage {
    grid-template-columns: minmax(0, 1fr);
  }

  .architecture-flow-shared-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .architecture-application-map {
    grid-template-columns: minmax(0, 1fr);
  }

  .architecture-server-worker-bridge {
    min-height: 110px;
  }

  .architecture-server-worker-bridge > i {
    width: 3px;
    height: 32px;
  }

  .architecture-server-worker-bridge > i::after {
    top: auto;
    right: auto;
    bottom: -1px;
    left: -4px;
    border-top: 8px solid currentColor;
    border-right: 5px solid transparent;
    border-bottom: 0;
    border-left: 5px solid transparent;
  }

  .architecture-tier-heading {
    grid-template-columns: minmax(0, 1fr);
  }

  .architecture-tier-heading > small {
    grid-row: auto;
    grid-column: 1;
  }

  .architecture-zone__nodes.is-horizontal,
  .architecture-downstream-grid,
  .architecture-shared-children {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@container architecture-layout (max-width: 620px) {
  .architecture-roadmap-architecture-link {
    grid-template-columns: minmax(0, 1fr);
  }

  .architecture-roadmap-architecture-link > em {
    grid-row: auto;
    grid-column: 1;
    align-self: auto;
    justify-content: space-between;
    margin-top: 5px;
    padding-top: 8px;
    padding-left: 0;
    border-top: 1px solid rgba(255, 255, 255, 0.2);
    border-left: 0;
  }

  .architecture-roadmap-capability-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .architecture-roadmap-track {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .architecture-roadmap-track li:nth-child(3n)::after {
    display: block;
  }

  .architecture-roadmap-track li:nth-child(2n)::after {
    display: none;
  }

  .architecture-roadmap-stage-button {
    min-height: 148px;
  }

  .architecture-roadmap-crosscut {
    grid-template-columns: minmax(0, 1fr);
  }

  .architecture-roadmap-crosscut.is-lineage {
    grid-template-columns: minmax(0, 1fr);
  }

  .architecture-roadmap-crosscut__items {
    grid-column: 1;
  }

  .architecture-worker-shared-capabilities {
    grid-template-columns: minmax(0, 1fr);
  }

  .architecture-worker-shared-capabilities__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .architecture-flow-stage__nodes {
    flex-direction: column;
    align-items: stretch;
  }

  .architecture-flow-inline-connector {
    flex: 0 0 54px;
  }

  .architecture-flow-inline-connector i {
    width: 2px;
    height: 30px;
  }

  .architecture-flow-inline-connector i::after {
    top: auto;
    right: auto;
    bottom: -1px;
    left: -3px;
    border-top: 7px solid currentColor;
    border-right: 4px solid transparent;
    border-bottom: 0;
    border-left: 4px solid transparent;
  }

  .architecture-entry-band,
  .architecture-entry-grid,
  .architecture-runtime-grid,
  .architecture-zone__nodes.is-horizontal,
  .architecture-downstream-grid,
  .architecture-shared-links,
  .architecture-flow-stage__nodes.is-parallel,
  .architecture-flow-shared-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .architecture-route-rail {
    padding-inline: 8px;
  }

  .architecture-route-rail::before,
  .architecture-route-rail::after {
    display: none;
  }
}

@media (max-width: 1160px) {
  .architecture-application-map {
    grid-template-columns: minmax(0, 1fr);
  }

  .architecture-server-worker-bridge {
    min-height: 110px;
  }

  .architecture-server-worker-bridge > i {
    width: 3px;
    height: 32px;
  }

  .architecture-server-worker-bridge > i::after {
    top: auto;
    right: auto;
    bottom: -1px;
    left: -4px;
    border-top: 8px solid currentColor;
    border-right: 5px solid transparent;
    border-bottom: 0;
    border-left: 5px solid transparent;
  }

}

@media (max-width: 980px) {
  .architecture-hero,
  .guide-hero {
    grid-template-columns: minmax(0, 1fr);
  }

  .architecture-hero__metrics {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .architecture-entry-band {
    grid-template-columns: minmax(0, 1fr);
  }

  .architecture-tier-heading {
    grid-template-columns: minmax(0, 1fr);
  }

  .architecture-tier-heading > small {
    grid-row: auto;
    grid-column: 1;
  }

  .architecture-zone__nodes.is-horizontal,
  .architecture-downstream-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

}

@media (max-width: 720px) {
  .architecture-hero,
  .guide-hero,
  .architecture-workbench {
    padding: 14px;
    border-radius: 17px;
  }

  .architecture-hero__metrics,
  .architecture-detail-panel {
    grid-template-columns: minmax(0, 1fr);
  }

  .architecture-hero__metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .architecture-toolbar,
  .architecture-toolbar__scenarios,
  .architecture-toolbar__actions {
    align-items: stretch;
  }

  .architecture-toolbar,
  .architecture-toolbar__scenarios,
  .architecture-toolbar__actions,
  .architecture-toolbar__scenarios :deep(.el-radio-group) {
    width: 100%;
  }

  .architecture-toolbar__scenarios :deep(.el-radio-button) {
    flex: 1 1 50%;
  }

  .architecture-toolbar__scenarios :deep(.el-radio-button__inner) {
    width: 100%;
  }

  .architecture-node-select {
    width: 100%;
  }

  .architecture-layout-shell {
    padding: 10px;
  }

  .guide-hero__actions {
    grid-template-columns: minmax(0, 1fr);
  }

  .architecture-roadmap-system,
  .architecture-roadmap-header,
  .architecture-roadmap-focus {
    padding: 12px;
  }

  .architecture-entry-grid,
  .architecture-runtime-grid,
  .architecture-zone__nodes.is-horizontal,
    .architecture-downstream-grid,
    .architecture-shared-links,
    .architecture-shared-children,
    .architecture-flow-shared-grid,
    .architecture-node-children {
    grid-template-columns: minmax(0, 1fr);
  }

  .architecture-legend {
    justify-content: flex-start;
  }
}
</style>
