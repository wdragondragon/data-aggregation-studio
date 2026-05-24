package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.enums.LineageLevel;
import com.jdragon.studio.dto.model.DataModelLineageContributorView;
import com.jdragon.studio.dto.model.DataModelLineageEdgeDetailView;
import com.jdragon.studio.dto.model.DataModelLineageEdgeView;
import com.jdragon.studio.dto.model.DataModelLineageNodeFieldView;
import com.jdragon.studio.dto.model.DataModelLineageNodeView;
import com.jdragon.studio.dto.model.DataModelLineageSummaryView;
import com.jdragon.studio.dto.model.DataModelLineageUnresolvedExpressionView;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.entity.DataModelLineageRelationEntity;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.mapper.DatasourceMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class DataModelLineageGraphAssembler {

    private static final String SOURCE_TYPE_COLLECTION_TASK = "COLLECTION_TASK";
    private static final String SOURCE_TYPE_MANUAL = "MANUAL";
    private static final String MAPPING_MODE_UNRESOLVED_EXPRESSION = "UNRESOLVED_EXPRESSION";
    private static final String SOURCE_TYPE_LABEL_AUTOMATIC = "AUTOMATIC";
    private static final String SOURCE_TYPE_LABEL_MANUAL = "MANUAL";
    private static final String SOURCE_TYPE_LABEL_MIXED = "MIXED";
    private static final String VISUAL_PLATFORM_MODEL = "PLATFORM_MODEL";
    private static final String VISUAL_EXTERNAL_ACCESS = "EXTERNAL_ACCESS";

    private final DatasourceMapper datasourceMapper;

    DataModelLineageGraphAssembler(DatasourceMapper datasourceMapper) {
        this.datasourceMapper = datasourceMapper;
    }

    LineageQueryContext buildContext(DataModelEntity focusModel,
                                     List<DataModelLineageRelationEntity> relations,
                                     LineageLevel level) {
        LineageQueryContext context = new LineageQueryContext();
        DatasourceEntity focusDatasource = focusModel.getDatasourceId() == null ? null : datasourceMapper.selectById(focusModel.getDatasourceId());
        String focusNodeId = resolveFocusNodeId(level, focusModel, focusDatasource);
        Set<String> reachableNodeIds = traverseReachableNodeIds(relations, focusNodeId, level);
        reachableNodeIds.add(focusNodeId);
        Map<String, List<DataModelLineageRelationEntity>> groupedEdges = new LinkedHashMap<String, List<DataModelLineageRelationEntity>>();
        for (DataModelLineageRelationEntity relation : relations) {
            String sourceNodeId = relationSourceNodeId(relation, level);
            String targetNodeId = relationTargetNodeId(relation, level);
            if (!reachableNodeIds.contains(sourceNodeId) || !reachableNodeIds.contains(targetNodeId)) {
                continue;
            }
            String edgeId = encodeEdgeKey(buildEdgeKey(level, relation));
            groupedEdges.computeIfAbsent(edgeId, key -> new ArrayList<DataModelLineageRelationEntity>()).add(relation);
        }

        Map<String, DataModelLineageNodeView> nodes = new LinkedHashMap<String, DataModelLineageNodeView>();
        ensureFocusNode(nodes, focusModel, focusDatasource, focusNodeId, level);
        Map<String, NodeRelationRole> roles = classifyNodeRoles(groupedEdges, focusNodeId, level);

        Set<String> unresolvedKeys = new LinkedHashSet<String>();
        for (Map.Entry<String, List<DataModelLineageRelationEntity>> entry : groupedEdges.entrySet()) {
            List<DataModelLineageRelationEntity> contributors = entry.getValue();
            contributors.sort(DataModelLineageRunStatusSupport.runStatusComparator());
            DataModelLineageRelationEntity latest = contributors.get(0);
            DataModelLineageRelationEntity preferred = resolvePreferredEdgeRelation(contributors);
            String sourceNodeId = relationSourceNodeId(latest, level);
            String targetNodeId = relationTargetNodeId(latest, level);
            nodes.putIfAbsent(sourceNodeId, buildNodeView(latest, true, level, roles.get(sourceNodeId), sourceNodeId.equals(focusNodeId)));
            nodes.putIfAbsent(targetNodeId, buildNodeView(latest, false, level, roles.get(targetNodeId), targetNodeId.equals(focusNodeId)));
            if (level == LineageLevel.FIELD) {
                appendFieldToNode(nodes.get(sourceNodeId), latest.getSourceFieldKey());
                appendFieldToNode(nodes.get(targetNodeId), latest.getTargetFieldKey());
            }

            DataModelLineageEdgeView edge = new DataModelLineageEdgeView();
            edge.setEdgeId(entry.getKey());
            edge.setSourceNodeId(sourceNodeId);
            edge.setTargetNodeId(targetNodeId);
            edge.setSelfLoop(Boolean.valueOf(Objects.equals(sourceNodeId, targetNodeId)));
            edge.setSourceField(level == LineageLevel.FIELD ? latest.getSourceFieldKey() : null);
            edge.setTargetField(level == LineageLevel.FIELD ? latest.getTargetFieldKey() : null);
            edge.setLabel(level == LineageLevel.FIELD
                    ? DataModelLineageTextSupport.safeText(latest.getSourceFieldKey()) + " -> " + DataModelLineageTextSupport.safeText(latest.getTargetFieldKey())
                    : null);
            edge.setSourceType(preferred == null ? null : preferred.getSourceType());
            edge.setSourceTypeLabel(resolveAggregatedSourceTypeLabel(contributors));
            edge.setLatestRunId(preferred == null ? null : preferred.getLatestRunId());
            edge.setLatestRunStatus(preferred == null ? DataModelLineageRunStatusSupport.RUN_STATUS_NOT_RUN : DataModelLineageRunStatusSupport.defaultRunStatus(preferred.getLatestRunStatus()));
            edge.setDisplayStatus(DataModelLineageRunStatusSupport.resolveDisplayStatus(preferred));
            edge.setLatestRunAt(preferred == null ? null : preferred.getLatestRunAt());
            edge.setContributorCount(Integer.valueOf(contributors.size()));
            context.edges.add(edge);

            if (level == LineageLevel.FIELD) {
                appendUnresolvedExpressions(context, unresolvedKeys, contributors);
            }
        }

        context.level = level == null ? null : level.name();
        context.focusNodeId = focusNodeId;
        context.nodes.addAll(nodes.values());
        return context;
    }

    DataModelLineageEdgeDetailView buildEdgeDetail(String edgeId,
                                                   LineageLevel level,
                                                   List<DataModelLineageRelationEntity> contributors) {
        contributors.sort(DataModelLineageRunStatusSupport.runStatusComparator());
        DataModelLineageRelationEntity latest = contributors.get(0);
        DataModelLineageRelationEntity preferred = resolvePreferredEdgeRelation(contributors);
        DataModelLineageEdgeDetailView detail = new DataModelLineageEdgeDetailView();
        detail.setEdgeId(edgeId);
        detail.setSourceNodeTitle(resolveNodeTitle(latest, true, level));
        detail.setTargetNodeTitle(resolveNodeTitle(latest, false, level));
        detail.setSourceField(level == LineageLevel.FIELD ? latest.getSourceFieldKey() : null);
        detail.setTargetField(level == LineageLevel.FIELD ? latest.getTargetFieldKey() : null);
        detail.setSourceType(preferred == null ? null : preferred.getSourceType());
        detail.setSourceTypeLabel(resolveAggregatedSourceTypeLabel(contributors));
        detail.setLatestRunId(preferred == null ? null : preferred.getLatestRunId());
        detail.setLatestRunStatus(preferred == null ? DataModelLineageRunStatusSupport.RUN_STATUS_NOT_RUN : DataModelLineageRunStatusSupport.defaultRunStatus(preferred.getLatestRunStatus()));
        detail.setDisplayStatus(DataModelLineageRunStatusSupport.resolveDisplayStatus(preferred));
        detail.setLatestRunAt(preferred == null ? null : preferred.getLatestRunAt());
        for (DataModelLineageRelationEntity relation : contributors) {
            DataModelLineageContributorView contributor = new DataModelLineageContributorView();
            contributor.setRelationId(relation.getId());
            contributor.setSourceType(relation.getSourceType());
            contributor.setSourceTypeLabel(resolveSourceTypeLabel(relation.getSourceType()));
            contributor.setDisplayStatus(DataModelLineageRunStatusSupport.resolveDisplayStatus(relation));
            contributor.setSourceModelId(relation.getSourceModelId());
            contributor.setTargetModelId(relation.getTargetModelId());
            contributor.setSourceField(relation.getSourceFieldKey());
            contributor.setTargetField(relation.getTargetFieldKey());
            contributor.setCollectionTaskId(relation.getCollectionTaskId());
            contributor.setCollectionTaskName(relation.getCollectionTaskNameSnapshot());
            contributor.setLatestRunId(relation.getLatestRunId());
            contributor.setLatestRunStatus(DataModelLineageRunStatusSupport.defaultRunStatus(relation.getLatestRunStatus()));
            contributor.setLatestRunAt(relation.getLatestRunAt());
            contributor.setTaskPath(relation.getCollectionTaskId() == null ? null : "/collection-tasks/" + relation.getCollectionTaskId() + "/edit");
            contributor.setRunPath(relation.getCollectionTaskId() == null || relation.getLatestRunId() == null
                    ? null
                    : "/collection-task-runs?collectionTaskId=" + relation.getCollectionTaskId() + "&runRecordId=" + relation.getLatestRunId());
            contributor.setMappingMode(relation.getMappingMode());
            contributor.setExpression(relation.getExpressionSnapshot());
            contributor.setMaintainerUserId(relation.getManualMaintainerUserId());
            contributor.setMaintainer(relation.getManualMaintainerNameSnapshot());
            contributor.setUpdatedAt(relation.getUpdatedAt());
            contributor.setEditable(Boolean.valueOf(SOURCE_TYPE_MANUAL.equalsIgnoreCase(relation.getSourceType())));
            detail.getContributors().add(contributor);
        }
        return detail;
    }

    DataModelLineageSummaryView buildSummary(LineageQueryContext context) {
        DataModelLineageSummaryView summary = new DataModelLineageSummaryView();
        if (context == null) {
            return summary;
        }
        if (LineageLevel.FIELD.name().equalsIgnoreCase(context.level)) {
            buildFieldLevelSummary(summary, context);
            return summary;
        }
        Map<String, Set<String>> incoming = new LinkedHashMap<String, Set<String>>();
        Map<String, Set<String>> outgoing = new LinkedHashMap<String, Set<String>>();
        for (DataModelLineageEdgeView edge : context.edges) {
            incoming.computeIfAbsent(edge.getTargetNodeId(), key -> new LinkedHashSet<String>()).add(edge.getSourceNodeId());
            outgoing.computeIfAbsent(edge.getSourceNodeId(), key -> new LinkedHashSet<String>()).add(edge.getTargetNodeId());
        }
        Set<String> directUpstream = new LinkedHashSet<String>(incoming.getOrDefault(context.focusNodeId, Collections.<String>emptySet()));
        directUpstream.remove(context.focusNodeId);
        summary.setDirectUpstreamCount(Integer.valueOf(directUpstream.size()));
        Set<String> allUpstream = traverse(incoming, context.focusNodeId);
        summary.setTotalUpstreamCount(Integer.valueOf(allUpstream.size()));
        summary.setUpstreamDepth(Integer.valueOf(maxDepth(incoming, context.focusNodeId)));
        Set<String> directDownstream = new LinkedHashSet<String>(outgoing.getOrDefault(context.focusNodeId, Collections.<String>emptySet()));
        directDownstream.remove(context.focusNodeId);
        summary.setDirectDownstreamCount(Integer.valueOf(directDownstream.size()));
        Set<String> allDownstream = traverse(outgoing, context.focusNodeId);
        summary.setTotalDownstreamCount(Integer.valueOf(allDownstream.size()));
        summary.setDownstreamDepth(Integer.valueOf(maxDepth(outgoing, context.focusNodeId)));
        return summary;
    }

    Set<String> traverseReachableNodeIds(List<DataModelLineageRelationEntity> relations,
                                         String focusNodeId,
                                         LineageLevel level) {
        Map<String, Set<String>> adjacency = new LinkedHashMap<String, Set<String>>();
        for (DataModelLineageRelationEntity relation : relations) {
            String sourceNodeId = relationSourceNodeId(relation, level);
            String targetNodeId = relationTargetNodeId(relation, level);
            adjacency.computeIfAbsent(sourceNodeId, key -> new LinkedHashSet<String>()).add(targetNodeId);
            adjacency.computeIfAbsent(targetNodeId, key -> new LinkedHashSet<String>()).add(sourceNodeId);
        }
        Set<String> visited = new LinkedHashSet<String>();
        Deque<String> queue = new ArrayDeque<String>();
        queue.add(focusNodeId);
        visited.add(focusNodeId);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : adjacency.getOrDefault(current, Collections.<String>emptySet())) {
                if (visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return visited;
    }

    String relationSourceNodeId(DataModelLineageRelationEntity relation, LineageLevel level) {
        if (level == LineageLevel.DATABASE) {
            return datasourceNodeId(relation.getSourceDatasourceId(), relation.getSourceDatasourceNameSnapshot(), relation.getSourceDatabaseNameSnapshot());
        }
        return modelNodeId(relation.getSourceModelId(), relation.getSourceModelLocatorSnapshot(), relation.getSourceModelNameSnapshot());
    }

    String relationTargetNodeId(DataModelLineageRelationEntity relation, LineageLevel level) {
        if (level == LineageLevel.DATABASE) {
            return datasourceNodeId(relation.getTargetDatasourceId(), relation.getTargetDatasourceNameSnapshot(), relation.getTargetDatabaseNameSnapshot());
        }
        return modelNodeId(relation.getTargetModelId(), relation.getTargetModelLocatorSnapshot(), relation.getTargetModelNameSnapshot());
    }

    String resolveFocusNodeId(LineageLevel level, DataModelEntity focusModel, DatasourceEntity focusDatasource) {
        if (level == LineageLevel.DATABASE) {
            return datasourceNodeId(focusDatasource == null ? null : focusDatasource.getId(),
                    focusDatasource == null ? null : focusDatasource.getName(),
                    DataModelLineageTextSupport.resolveDatabaseName(focusDatasource));
        }
        return modelNodeId(focusModel.getId(), focusModel.getPhysicalLocator(), focusModel.getName());
    }

    EdgeKey decodeEdgeKey(String edgeId) {
        if (DataModelLineageTextSupport.isBlank(edgeId)) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(edgeId), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length < 5) {
                return null;
            }
            EdgeKey edgeKey = new EdgeKey();
            edgeKey.level = parts[0];
            edgeKey.sourceNodeId = parts[1];
            edgeKey.targetNodeId = parts[2];
            edgeKey.sourceField = DataModelLineageTextSupport.blankToNull(parts[3]);
            edgeKey.targetField = DataModelLineageTextSupport.blankToNull(parts[4]);
            return edgeKey;
        } catch (IllegalArgumentException decodeError) {
            return null;
        }
    }

    boolean matchesEdgeKey(EdgeKey edgeKey, DataModelLineageRelationEntity relation, LineageLevel level) {
        EdgeKey candidate = buildEdgeKey(level, relation);
        return Objects.equals(edgeKey.level, candidate.level)
                && Objects.equals(edgeKey.sourceNodeId, candidate.sourceNodeId)
                && Objects.equals(edgeKey.targetNodeId, candidate.targetNodeId)
                && Objects.equals(DataModelLineageTextSupport.normalizeText(edgeKey.sourceField), DataModelLineageTextSupport.normalizeText(candidate.sourceField))
                && Objects.equals(DataModelLineageTextSupport.normalizeText(edgeKey.targetField), DataModelLineageTextSupport.normalizeText(candidate.targetField));
    }

    private void appendUnresolvedExpressions(LineageQueryContext context,
                                             Set<String> unresolvedKeys,
                                             List<DataModelLineageRelationEntity> contributors) {
        for (DataModelLineageRelationEntity relation : contributors) {
            if (!MAPPING_MODE_UNRESOLVED_EXPRESSION.equalsIgnoreCase(relation.getMappingMode())) {
                continue;
            }
            String unresolvedKey = relation.getCollectionTaskId()
                    + "|" + DataModelLineageTextSupport.safeText(relation.getTargetFieldKey())
                    + "|" + DataModelLineageTextSupport.safeText(relation.getExpressionSnapshot());
            if (!unresolvedKeys.add(unresolvedKey)) {
                continue;
            }
            DataModelLineageUnresolvedExpressionView unresolved = new DataModelLineageUnresolvedExpressionView();
            unresolved.setCollectionTaskId(relation.getCollectionTaskId());
            unresolved.setCollectionTaskName(relation.getCollectionTaskNameSnapshot());
            unresolved.setSourceAlias(null);
            unresolved.setTargetField(relation.getTargetFieldKey());
            unresolved.setExpression(relation.getExpressionSnapshot());
            unresolved.setLatestRunAt(relation.getLatestRunAt());
            unresolved.setLatestRunStatus(DataModelLineageRunStatusSupport.defaultRunStatus(relation.getLatestRunStatus()));
            context.unresolvedExpressions.add(unresolved);
        }
    }

    private Map<String, NodeRelationRole> classifyNodeRoles(Map<String, List<DataModelLineageRelationEntity>> groupedEdges,
                                                            String focusNodeId,
                                                            LineageLevel level) {
        Map<String, Set<String>> incoming = new LinkedHashMap<String, Set<String>>();
        Map<String, Set<String>> outgoing = new LinkedHashMap<String, Set<String>>();
        for (List<DataModelLineageRelationEntity> contributors : groupedEdges.values()) {
            if (contributors.isEmpty()) {
                continue;
            }
            DataModelLineageRelationEntity latest = contributors.get(0);
            String sourceNodeId = relationSourceNodeId(latest, level);
            String targetNodeId = relationTargetNodeId(latest, level);
            incoming.computeIfAbsent(targetNodeId, key -> new LinkedHashSet<String>()).add(sourceNodeId);
            outgoing.computeIfAbsent(sourceNodeId, key -> new LinkedHashSet<String>()).add(targetNodeId);
        }
        Set<String> upstream = traverse(incoming, focusNodeId);
        Set<String> downstream = traverse(outgoing, focusNodeId);
        Set<String> allNodes = new LinkedHashSet<String>();
        allNodes.addAll(incoming.keySet());
        allNodes.addAll(outgoing.keySet());
        Map<String, NodeRelationRole> result = new LinkedHashMap<String, NodeRelationRole>();
        for (String nodeId : allNodes) {
            if (Objects.equals(nodeId, focusNodeId)) {
                result.put(nodeId, NodeRelationRole.FOCUS);
            } else if (upstream.contains(nodeId)) {
                result.put(nodeId, NodeRelationRole.UPSTREAM);
            } else if (downstream.contains(nodeId)) {
                result.put(nodeId, NodeRelationRole.DOWNSTREAM);
            } else {
                result.put(nodeId, NodeRelationRole.UNRELATED);
            }
        }
        return result;
    }

    private void buildFieldLevelSummary(DataModelLineageSummaryView summary, LineageQueryContext context) {
        Map<String, Set<String>> incoming = new LinkedHashMap<String, Set<String>>();
        Map<String, Set<String>> outgoing = new LinkedHashMap<String, Set<String>>();
        Set<String> focusFieldNodeIds = new LinkedHashSet<String>();
        for (DataModelLineageEdgeView edge : context.edges) {
            if (DataModelLineageTextSupport.isBlank(edge.getSourceField()) || DataModelLineageTextSupport.isBlank(edge.getTargetField())) {
                continue;
            }
            String sourceFieldNodeId = fieldVertexId(edge.getSourceNodeId(), edge.getSourceField());
            String targetFieldNodeId = fieldVertexId(edge.getTargetNodeId(), edge.getTargetField());
            incoming.computeIfAbsent(targetFieldNodeId, key -> new LinkedHashSet<String>()).add(sourceFieldNodeId);
            outgoing.computeIfAbsent(sourceFieldNodeId, key -> new LinkedHashSet<String>()).add(targetFieldNodeId);
            if (Objects.equals(edge.getSourceNodeId(), context.focusNodeId)) {
                focusFieldNodeIds.add(sourceFieldNodeId);
            }
            if (Objects.equals(edge.getTargetNodeId(), context.focusNodeId)) {
                focusFieldNodeIds.add(targetFieldNodeId);
            }
        }
        if (focusFieldNodeIds.isEmpty()) {
            summary.setUpstreamDepth(Integer.valueOf(0));
            summary.setTotalUpstreamCount(Integer.valueOf(0));
            summary.setDirectUpstreamCount(Integer.valueOf(0));
            summary.setDownstreamDepth(Integer.valueOf(0));
            summary.setTotalDownstreamCount(Integer.valueOf(0));
            summary.setDirectDownstreamCount(Integer.valueOf(0));
            return;
        }
        Set<String> directUpstream = new LinkedHashSet<String>();
        Set<String> directDownstream = new LinkedHashSet<String>();
        for (String focusFieldNodeId : focusFieldNodeIds) {
            directUpstream.addAll(incoming.getOrDefault(focusFieldNodeId, Collections.<String>emptySet()));
            directDownstream.addAll(outgoing.getOrDefault(focusFieldNodeId, Collections.<String>emptySet()));
        }
        directUpstream.removeAll(focusFieldNodeIds);
        directDownstream.removeAll(focusFieldNodeIds);
        summary.setDirectUpstreamCount(Integer.valueOf(directUpstream.size()));
        summary.setDirectDownstreamCount(Integer.valueOf(directDownstream.size()));
        summary.setTotalUpstreamCount(Integer.valueOf(traverseAll(incoming, focusFieldNodeIds).size()));
        summary.setTotalDownstreamCount(Integer.valueOf(traverseAll(outgoing, focusFieldNodeIds).size()));
        summary.setUpstreamDepth(Integer.valueOf(maxDepth(incoming, focusFieldNodeIds)));
        summary.setDownstreamDepth(Integer.valueOf(maxDepth(outgoing, focusFieldNodeIds)));
    }

    private void ensureFocusNode(Map<String, DataModelLineageNodeView> nodes,
                                 DataModelEntity focusModel,
                                 DatasourceEntity focusDatasource,
                                 String focusNodeId,
                                 LineageLevel level) {
        if (nodes.containsKey(focusNodeId)) {
            return;
        }
        DataModelLineageNodeView node = new DataModelLineageNodeView();
        node.setNodeId(focusNodeId);
        node.setFocus(Boolean.TRUE);
        node.setVisualType(VISUAL_PLATFORM_MODEL);
        node.setDatasourceId(focusDatasource == null ? null : focusDatasource.getId());
        node.setModelId(focusModel.getId());
        node.setDatasourceName(focusDatasource == null ? null : focusDatasource.getName());
        node.setDatasourceType(focusDatasource == null ? null : focusDatasource.getTypeCode());
        node.setDatabaseName(DataModelLineageTextSupport.resolveDatabaseName(focusDatasource));
        node.setPhysicalLocator(focusModel.getPhysicalLocator());
        node.setHost(DataModelLineageTextSupport.resolveStringMetadata(focusDatasource, "host", "endpoint"));
        node.setPort(DataModelLineageTextSupport.resolveStringMetadata(focusDatasource, "port"));
        node.setDailyIncrement("--");
        node.setTotalCount("--");
        node.setTitle(level == LineageLevel.DATABASE
                ? DataModelLineageTextSupport.firstNonBlank(node.getDatasourceName(), node.getDatabaseName(), focusModel.getName())
                : focusModel.getName());
        node.setSubtitle(level == LineageLevel.DATABASE ? node.getDatabaseName() : node.getPhysicalLocator());
        nodes.put(focusNodeId, node);
    }

    private DataModelLineageNodeView buildNodeView(DataModelLineageRelationEntity relation,
                                                   boolean sourceSide,
                                                   LineageLevel level,
                                                   NodeRelationRole role,
                                                   boolean focus) {
        DataModelLineageNodeView node = new DataModelLineageNodeView();
        node.setNodeId(sourceSide ? relationSourceNodeId(relation, level) : relationTargetNodeId(relation, level));
        node.setFocus(Boolean.valueOf(focus));
        node.setVisualType(resolveVisualType(role, focus));
        node.setDatasourceId(sourceSide ? relation.getSourceDatasourceId() : relation.getTargetDatasourceId());
        node.setModelId(sourceSide ? relation.getSourceModelId() : relation.getTargetModelId());
        node.setDatasourceName(sourceSide ? relation.getSourceDatasourceNameSnapshot() : relation.getTargetDatasourceNameSnapshot());
        node.setDatasourceType(sourceSide ? relation.getSourceDatasourceTypeSnapshot() : relation.getTargetDatasourceTypeSnapshot());
        node.setDatabaseName(sourceSide ? relation.getSourceDatabaseNameSnapshot() : relation.getTargetDatabaseNameSnapshot());
        node.setPhysicalLocator(sourceSide ? relation.getSourceModelLocatorSnapshot() : relation.getTargetModelLocatorSnapshot());
        node.setHost(sourceSide ? relation.getSourceHostSnapshot() : relation.getTargetHostSnapshot());
        node.setPort(sourceSide ? relation.getSourcePortSnapshot() : relation.getTargetPortSnapshot());
        node.setDailyIncrement("--");
        node.setTotalCount("--");
        node.setTitle(level == LineageLevel.DATABASE
                ? DataModelLineageTextSupport.firstNonBlank(node.getDatasourceName(), node.getDatabaseName(), sourceSide ? relation.getSourceModelNameSnapshot() : relation.getTargetModelNameSnapshot())
                : sourceSide ? relation.getSourceModelNameSnapshot() : relation.getTargetModelNameSnapshot());
        node.setSubtitle(level == LineageLevel.DATABASE ? node.getDatabaseName() : node.getPhysicalLocator());
        return node;
    }

    private void appendFieldToNode(DataModelLineageNodeView node, String fieldName) {
        if (node == null || DataModelLineageTextSupport.isBlank(fieldName)) {
            return;
        }
        for (DataModelLineageNodeFieldView field : node.getFields()) {
            if (fieldName.equalsIgnoreCase(field.getFieldKey())) {
                return;
            }
        }
        DataModelLineageNodeFieldView field = new DataModelLineageNodeFieldView();
        field.setFieldKey(fieldName);
        field.setFieldName(fieldName);
        node.getFields().add(field);
    }

    private Set<String> traverse(Map<String, Set<String>> adjacency, String startNodeId) {
        Set<String> visited = new LinkedHashSet<String>();
        Deque<String> queue = new ArrayDeque<String>();
        queue.add(startNodeId);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : adjacency.getOrDefault(current, Collections.<String>emptySet())) {
                if (visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        visited.remove(startNodeId);
        return visited;
    }

    private Set<String> traverseAll(Map<String, Set<String>> adjacency, Set<String> startNodeIds) {
        Set<String> visited = new LinkedHashSet<String>();
        if (startNodeIds == null || startNodeIds.isEmpty()) {
            return visited;
        }
        Deque<String> queue = new ArrayDeque<String>(startNodeIds);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : adjacency.getOrDefault(current, Collections.<String>emptySet())) {
                if (startNodeIds.contains(next)) {
                    continue;
                }
                if (visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return visited;
    }

    private int maxDepth(Map<String, Set<String>> incoming, String nodeId) {
        return maxDepth(incoming, nodeId, new LinkedHashSet<String>());
    }

    private int maxDepth(Map<String, Set<String>> incoming, Set<String> nodeIds) {
        int maxDepth = 0;
        if (nodeIds == null) {
            return maxDepth;
        }
        for (String nodeId : nodeIds) {
            if (nodeId == null) {
                continue;
            }
            maxDepth = Math.max(maxDepth, maxDepth(incoming, nodeId, new LinkedHashSet<String>()));
        }
        return maxDepth;
    }

    private int maxDepth(Map<String, Set<String>> incoming, String nodeId, Set<String> path) {
        if (!path.add(nodeId)) {
            return 0;
        }
        Set<String> direct = incoming.getOrDefault(nodeId, Collections.<String>emptySet());
        if (direct.isEmpty()) {
            path.remove(nodeId);
            return 0;
        }
        int maxDepth = 0;
        for (String parentNodeId : direct) {
            if (Objects.equals(parentNodeId, nodeId)) {
                continue;
            }
            maxDepth = Math.max(maxDepth, 1 + maxDepth(incoming, parentNodeId, path));
        }
        path.remove(nodeId);
        return maxDepth;
    }

    private String datasourceNodeId(Long datasourceId, String datasourceName, String databaseName) {
        if (datasourceId != null) {
            return "datasource:" + datasourceId + ":" + DataModelLineageTextSupport.safeText(DataModelLineageTextSupport.normalizeText(DataModelLineageTextSupport.firstNonBlank(databaseName, "_")));
        }
        return "datasource-snapshot:" + DataModelLineageTextSupport.safeText(DataModelLineageTextSupport.normalizeText(DataModelLineageTextSupport.firstNonBlank(databaseName, datasourceName, "unknown")));
    }

    private String modelNodeId(Long modelId, String physicalLocator, String modelName) {
        if (modelId != null) {
            return "model:" + modelId;
        }
        return "model-snapshot:" + DataModelLineageTextSupport.firstNonBlank(physicalLocator, modelName, "unknown");
    }

    private String fieldVertexId(String nodeId, String fieldKey) {
        return DataModelLineageTextSupport.safeText(nodeId) + "#" + DataModelLineageTextSupport.normalizeText(fieldKey);
    }

    private EdgeKey buildEdgeKey(LineageLevel level, DataModelLineageRelationEntity relation) {
        EdgeKey edgeKey = new EdgeKey();
        edgeKey.level = level.name();
        edgeKey.sourceNodeId = relationSourceNodeId(relation, level);
        edgeKey.targetNodeId = relationTargetNodeId(relation, level);
        edgeKey.sourceField = level == LineageLevel.FIELD ? DataModelLineageTextSupport.normalizeText(relation.getSourceFieldKey()) : null;
        edgeKey.targetField = level == LineageLevel.FIELD ? DataModelLineageTextSupport.normalizeText(relation.getTargetFieldKey()) : null;
        return edgeKey;
    }

    private String encodeEdgeKey(EdgeKey edgeKey) {
        String raw = edgeKey.level
                + "|" + edgeKey.sourceNodeId
                + "|" + edgeKey.targetNodeId
                + "|" + DataModelLineageTextSupport.safeText(edgeKey.sourceField)
                + "|" + DataModelLineageTextSupport.safeText(edgeKey.targetField);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String resolveNodeTitle(DataModelLineageRelationEntity relation, boolean sourceSide, LineageLevel level) {
        if (level == LineageLevel.DATABASE) {
            return sourceSide
                    ? DataModelLineageTextSupport.firstNonBlank(relation.getSourceDatasourceNameSnapshot(), relation.getSourceDatabaseNameSnapshot(), relation.getSourceModelNameSnapshot())
                    : DataModelLineageTextSupport.firstNonBlank(relation.getTargetDatasourceNameSnapshot(), relation.getTargetDatabaseNameSnapshot(), relation.getTargetModelNameSnapshot());
        }
        return sourceSide ? relation.getSourceModelNameSnapshot() : relation.getTargetModelNameSnapshot();
    }

    private String resolveVisualType(NodeRelationRole role, boolean focus) {
        if (focus) {
            return VISUAL_PLATFORM_MODEL;
        }
        if (role == NodeRelationRole.UPSTREAM) {
            return VISUAL_EXTERNAL_ACCESS;
        }
        return VISUAL_PLATFORM_MODEL;
    }

    private DataModelLineageRelationEntity resolvePreferredEdgeRelation(List<DataModelLineageRelationEntity> contributors) {
        if (contributors == null || contributors.isEmpty()) {
            return null;
        }
        List<DataModelLineageRelationEntity> automatic = new ArrayList<DataModelLineageRelationEntity>();
        for (DataModelLineageRelationEntity contributor : contributors) {
            if (contributor != null && SOURCE_TYPE_COLLECTION_TASK.equalsIgnoreCase(contributor.getSourceType())) {
                automatic.add(contributor);
            }
        }
        if (!automatic.isEmpty()) {
            automatic.sort(DataModelLineageRunStatusSupport.runStatusComparator());
            return automatic.get(0);
        }
        return contributors.get(0);
    }

    private String resolveSourceTypeLabel(String sourceType) {
        if (SOURCE_TYPE_MANUAL.equalsIgnoreCase(sourceType)) {
            return SOURCE_TYPE_LABEL_MANUAL;
        }
        return SOURCE_TYPE_LABEL_AUTOMATIC;
    }

    private String resolveAggregatedSourceTypeLabel(List<DataModelLineageRelationEntity> contributors) {
        if (contributors == null || contributors.isEmpty()) {
            return SOURCE_TYPE_LABEL_AUTOMATIC;
        }
        boolean hasAutomatic = false;
        boolean hasManual = false;
        for (DataModelLineageRelationEntity contributor : contributors) {
            if (contributor == null) {
                continue;
            }
            if (SOURCE_TYPE_MANUAL.equalsIgnoreCase(contributor.getSourceType())) {
                hasManual = true;
            } else {
                hasAutomatic = true;
            }
        }
        if (hasAutomatic && hasManual) {
            return SOURCE_TYPE_LABEL_MIXED;
        }
        if (hasManual) {
            return SOURCE_TYPE_LABEL_MANUAL;
        }
        return SOURCE_TYPE_LABEL_AUTOMATIC;
    }

    private enum NodeRelationRole {
        FOCUS,
        UPSTREAM,
        DOWNSTREAM,
        UNRELATED
    }

    static final class EdgeKey {
        String level;
        String sourceNodeId;
        String targetNodeId;
        String sourceField;
        String targetField;
    }

    static final class LineageQueryContext {
        String level;
        String focusNodeId;
        List<DataModelLineageNodeView> nodes = new ArrayList<DataModelLineageNodeView>();
        List<DataModelLineageEdgeView> edges = new ArrayList<DataModelLineageEdgeView>();
        List<DataModelLineageUnresolvedExpressionView> unresolvedExpressions = new ArrayList<DataModelLineageUnresolvedExpressionView>();
    }
}
