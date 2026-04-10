package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.DataModelIndexQueueStatusView;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DataModelIndexRebuildQueueService {

    private static final Logger log = LoggerFactory.getLogger(DataModelIndexRebuildQueueService.class);
    private static final int MAX_DRAIN_SIZE = 256;

    private final DataModelMapper dataModelMapper;
    private final DatasourceMapper datasourceMapper;
    private final DataModelSearchIndexService dataModelSearchIndexService;
    private final Executor indexRebuildQueueExecutor;
    private final BlockingQueue<IndexCommand> commandQueue = new LinkedBlockingQueue<IndexCommand>();
    private final Set<String> queuedKeys = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private final Set<Long> queuedModelRebuildIds = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
    private final Set<Long> queuedModelDeleteIds = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
    private final Set<Long> queuedDatasourceDeleteIds = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
    private final AtomicBoolean workerStarted = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger activeCommandCount = new AtomicInteger(0);
    private final AtomicInteger activeModelRebuildCount = new AtomicInteger(0);
    private final AtomicInteger activeModelDeleteCount = new AtomicInteger(0);
    private final AtomicInteger activeDatasourceDeleteCount = new AtomicInteger(0);

    public DataModelIndexRebuildQueueService(DataModelMapper dataModelMapper,
                                            DatasourceMapper datasourceMapper,
                                            DataModelSearchIndexService dataModelSearchIndexService,
                                            @Qualifier("indexRebuildQueueExecutor") Executor indexRebuildQueueExecutor) {
        this.dataModelMapper = dataModelMapper;
        this.datasourceMapper = datasourceMapper;
        this.dataModelSearchIndexService = dataModelSearchIndexService;
        this.indexRebuildQueueExecutor = indexRebuildQueueExecutor;
    }

    @PostConstruct
    public void startWorker() {
        if (!workerStarted.compareAndSet(false, true)) {
            return;
        }
        indexRebuildQueueExecutor.execute(new Runnable() {
            @Override
            public void run() {
                runWorkerLoop();
            }
        });
    }

    @PreDestroy
    public void stopWorker() {
        running.set(false);
    }

    public int enqueueModelRebuild(Long modelId) {
        return enqueueModelRebuilds(Collections.singletonList(modelId));
    }

    public int enqueueModelRebuilds(Collection<Long> modelIds) {
        return scheduleAfterCommit(buildCommands(IndexCommandType.REBUILD_MODEL, modelIds));
    }

    public int enqueueModelDelete(Long modelId) {
        return enqueueModelDeletes(Collections.singletonList(modelId));
    }

    public int enqueueModelDeletes(Collection<Long> modelIds) {
        return scheduleAfterCommit(buildCommands(IndexCommandType.DELETE_MODEL, modelIds));
    }

    public int enqueueDatasourceDelete(Long datasourceId) {
        return enqueueDatasourceDeletes(Collections.singletonList(datasourceId));
    }

    public int enqueueDatasourceDeletes(Collection<Long> datasourceIds) {
        return scheduleAfterCommit(buildCommands(IndexCommandType.DELETE_DATASOURCE, datasourceIds));
    }

    public boolean awaitIdle(Duration timeout) {
        long timeoutMillis = timeout == null ? 0L : timeout.toMillis();
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        while (System.currentTimeMillis() <= deadline) {
            if (commandQueue.isEmpty() && activeCommandCount.get() == 0) {
                return true;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return commandQueue.isEmpty() && activeCommandCount.get() == 0;
    }

    public DataModelIndexQueueStatusView currentStatus() {
        DataModelIndexQueueStatusView view = new DataModelIndexQueueStatusView();
        int queuedRebuildCount = queuedModelRebuildIds.size();
        int activeRebuildCount = activeModelRebuildCount.get();
        int queuedCommandCount = commandQueue.size();
        int activeCount = activeCommandCount.get();
        view.setQueuedRebuildCount(queuedRebuildCount);
        view.setActiveRebuildCount(activeRebuildCount);
        view.setPendingRebuildCount(queuedRebuildCount + activeRebuildCount);
        view.setQueuedCommandCount(queuedCommandCount);
        view.setActiveCommandCount(activeCount);
        view.setBusy(queuedCommandCount > 0
                || activeCount > 0
                || activeModelDeleteCount.get() > 0
                || activeDatasourceDeleteCount.get() > 0);
        return view;
    }

    private int scheduleAfterCommit(final List<IndexCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return 0;
        }
        Runnable enqueueTask = new Runnable() {
            @Override
            public void run() {
                enqueueNow(commands);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            enqueueTask.run();
            return commands.size();
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                enqueueTask.run();
            }
        });
        return commands.size();
    }

    private void enqueueNow(List<IndexCommand> commands) {
        int queuedCount = 0;
        for (IndexCommand command : commands) {
            if (command == null) {
                continue;
            }
            if (!registerQueuedCommand(command)) {
                continue;
            }
            commandQueue.offer(command);
            queuedCount++;
        }
        if (queuedCount > 0) {
            log.info("Queued {} index maintenance commands. queueSize={}", queuedCount, commandQueue.size());
        }
    }

    private List<IndexCommand> buildCommands(IndexCommandType type, Collection<Long> ids) {
        List<IndexCommand> commands = new ArrayList<IndexCommand>();
        if (ids == null) {
            return commands;
        }
        Set<Long> normalizedIds = new LinkedHashSet<Long>();
        for (Long id : ids) {
            if (id != null && id.longValue() > 0L) {
                normalizedIds.add(id);
            }
        }
        for (Long id : normalizedIds) {
            commands.add(new IndexCommand(type, id.longValue()));
        }
        return commands;
    }

    private void runWorkerLoop() {
        while (running.get()) {
            try {
                IndexCommand first = commandQueue.poll(1L, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                List<IndexCommand> commands = new ArrayList<IndexCommand>();
                commands.add(first);
                commandQueue.drainTo(commands, MAX_DRAIN_SIZE - 1);
                activeCommandCount.addAndGet(commands.size());
                try {
                    processCommands(commands);
                } finally {
                    activeCommandCount.addAndGet(-commands.size());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable ex) {
                log.error("Index rebuild queue worker failed unexpectedly", ex);
            }
        }
    }

    private void processCommands(List<IndexCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        for (IndexCommand command : commands) {
            if (command != null) {
                unregisterQueuedCommand(command);
            }
        }

        NormalizedCommandBatch normalizedBatch = normalize(commands);
        if (normalizedBatch.isEmpty()) {
            return;
        }
        setActiveBatchCounts(normalizedBatch);
        try {
            log.info("Processing index maintenance batch. datasourceDeletes={}, modelDeletes={}, modelRebuilds={}",
                    normalizedBatch.datasourceDeletes.size(),
                    normalizedBatch.modelDeletes.size(),
                    normalizedBatch.modelRebuilds.size());

            processDatasourceDeletes(normalizedBatch.datasourceDeletes);
            processModelDeletes(normalizedBatch.modelDeletes);
            processModelRebuilds(normalizedBatch.modelRebuilds);
        } finally {
            clearActiveBatchCounts();
        }
    }

    private boolean registerQueuedCommand(IndexCommand command) {
        if (command == null) {
            return false;
        }
        if (!queuedKeys.add(command.uniqueKey())) {
            return false;
        }
        if (shouldMergeIntoQueuedCommand(command)) {
            queuedKeys.remove(command.uniqueKey());
            return false;
        }
        if (IndexCommandType.REBUILD_MODEL.equals(command.type)) {
            queuedModelRebuildIds.add(command.targetId);
            return true;
        }
        if (IndexCommandType.DELETE_MODEL.equals(command.type)) {
            queuedModelDeleteIds.add(command.targetId);
            return true;
        }
        if (IndexCommandType.DELETE_DATASOURCE.equals(command.type)) {
            queuedDatasourceDeleteIds.add(command.targetId);
            return true;
        }
        queuedKeys.remove(command.uniqueKey());
        return false;
    }

    private void unregisterQueuedCommand(IndexCommand command) {
        if (command == null) {
            return;
        }
        queuedKeys.remove(command.uniqueKey());
        if (IndexCommandType.REBUILD_MODEL.equals(command.type)) {
            queuedModelRebuildIds.remove(command.targetId);
            return;
        }
        if (IndexCommandType.DELETE_MODEL.equals(command.type)) {
            queuedModelDeleteIds.remove(command.targetId);
            return;
        }
        if (IndexCommandType.DELETE_DATASOURCE.equals(command.type)) {
            queuedDatasourceDeleteIds.remove(command.targetId);
        }
    }

    private boolean shouldMergeIntoQueuedCommand(IndexCommand command) {
        if (command == null) {
            return false;
        }
        if (!IndexCommandType.REBUILD_MODEL.equals(command.type)) {
            return false;
        }
        Long modelId = command.targetId;
        if (modelId == null) {
            return false;
        }
        if (queuedModelRebuildIds.contains(modelId) || queuedModelDeleteIds.contains(modelId)) {
            return true;
        }
        if (queuedDatasourceDeleteIds.isEmpty()) {
            return false;
        }
        Long datasourceId = resolveDatasourceIdByModelId(modelId);
        return datasourceId != null && queuedDatasourceDeleteIds.contains(datasourceId);
    }

    private Long resolveDatasourceIdByModelId(Long modelId) {
        if (modelId == null) {
            return null;
        }
        DataModelEntity model = dataModelMapper.selectById(modelId);
        if (model == null) {
            return null;
        }
        return model.getDatasourceId();
    }

    private NormalizedCommandBatch normalize(List<IndexCommand> commands) {
        NormalizedCommandBatch batch = new NormalizedCommandBatch();
        for (IndexCommand command : commands) {
            if (command == null) {
                continue;
            }
            if (IndexCommandType.DELETE_DATASOURCE.equals(command.type)) {
                batch.datasourceDeletes.add(command.targetId);
                continue;
            }
            if (IndexCommandType.DELETE_MODEL.equals(command.type)) {
                batch.modelRebuilds.remove(command.targetId);
                batch.modelDeletes.add(command.targetId);
                continue;
            }
            if (!batch.modelDeletes.contains(command.targetId)) {
                batch.modelRebuilds.add(command.targetId);
            }
        }
        return batch;
    }

    private void setActiveBatchCounts(NormalizedCommandBatch batch) {
        if (batch == null) {
            clearActiveBatchCounts();
            return;
        }
        activeDatasourceDeleteCount.set(batch.datasourceDeletes.size());
        activeModelDeleteCount.set(batch.modelDeletes.size());
        activeModelRebuildCount.set(batch.modelRebuilds.size());
    }

    private void clearActiveBatchCounts() {
        activeDatasourceDeleteCount.set(0);
        activeModelDeleteCount.set(0);
        activeModelRebuildCount.set(0);
    }

    private void processDatasourceDeletes(Set<Long> datasourceIds) {
        List<Long> ordered = orderIds(datasourceIds);
        for (Long datasourceId : ordered) {
            try {
                dataModelSearchIndexService.deleteByDatasourceId(datasourceId);
            } catch (Exception ex) {
                log.error("Failed to delete model indexes by datasource. datasourceId={}", datasourceId, ex);
            }
        }
    }

    private void processModelDeletes(Set<Long> modelIds) {
        List<Long> ordered = orderIds(modelIds);
        for (Long modelId : ordered) {
            try {
                dataModelSearchIndexService.deleteByModelId(modelId);
            } catch (Exception ex) {
                log.error("Failed to delete model index. modelId={}", modelId, ex);
            }
        }
    }

    private void processModelRebuilds(Set<Long> modelIds) {
        List<Long> orderedIds = orderIds(modelIds);
        if (orderedIds.isEmpty()) {
            return;
        }
        List<DataModelEntity> foundModels = dataModelMapper.selectBatchIds(orderedIds);
        Map<Long, DataModelEntity> modelMap = new LinkedHashMap<Long, DataModelEntity>();
        if (foundModels != null) {
            for (DataModelEntity model : foundModels) {
                if (model != null && model.getId() != null) {
                    modelMap.put(model.getId(), model);
                }
            }
        }

        List<DataModelEntity> orderedModels = new ArrayList<DataModelEntity>();
        List<Long> missingModelIds = new ArrayList<Long>();
        for (Long modelId : orderedIds) {
            DataModelEntity model = modelMap.get(modelId);
            if (model == null) {
                missingModelIds.add(modelId);
                continue;
            }
            orderedModels.add(model);
        }
        processModelDeletes(new LinkedHashSet<Long>(missingModelIds));
        if (orderedModels.isEmpty()) {
            return;
        }

        Map<Long, DataSourceDefinition> datasourceMap = loadDatasourceDefinitions(orderedModels);
        try {
            dataModelSearchIndexService.rebuildModelIndexes(orderedModels, datasourceMap);
        } catch (Exception ex) {
            log.warn("Batch index rebuild failed, retrying one by one. modelCount={}", orderedModels.size(), ex);
            for (DataModelEntity model : orderedModels) {
                try {
                    dataModelSearchIndexService.rebuildModelIndex(model, datasourceMap.get(model.getDatasourceId()));
                } catch (Exception itemEx) {
                    log.error("Failed to rebuild model index. modelId={}, datasourceId={}",
                            model.getId(), model.getDatasourceId(), itemEx);
                }
            }
        }
    }

    private Map<Long, DataSourceDefinition> loadDatasourceDefinitions(List<DataModelEntity> models) {
        Map<Long, DataSourceDefinition> result = new LinkedHashMap<Long, DataSourceDefinition>();
        Set<Long> datasourceIds = new LinkedHashSet<Long>();
        for (DataModelEntity model : models) {
            if (model != null && model.getDatasourceId() != null) {
                datasourceIds.add(model.getDatasourceId());
            }
        }
        if (datasourceIds.isEmpty()) {
            return result;
        }
        List<DatasourceEntity> datasources = datasourceMapper.selectBatchIds(new ArrayList<Long>(datasourceIds));
        if (datasources == null) {
            return result;
        }
        for (DatasourceEntity datasource : datasources) {
            if (datasource != null && datasource.getId() != null) {
                result.put(datasource.getId(), toDatasourceDefinition(datasource));
            }
        }
        return result;
    }

    private DataSourceDefinition toDatasourceDefinition(DatasourceEntity datasource) {
        if (datasource == null) {
            return null;
        }
        DataSourceDefinition definition = new DataSourceDefinition();
        definition.setId(datasource.getId());
        definition.setTenantId(datasource.getTenantId());
        definition.setProjectId(datasource.getProjectId());
        definition.setDeleted(datasource.getDeleted() != null && datasource.getDeleted().intValue() == 1);
        definition.setCreatedAt(datasource.getCreatedAt());
        definition.setUpdatedAt(datasource.getUpdatedAt());
        definition.setName(datasource.getName());
        definition.setTypeCode(datasource.getTypeCode());
        definition.setSchemaVersionId(datasource.getSchemaVersionId());
        definition.setEnabled(datasource.getEnabled() != null && datasource.getEnabled().intValue() == 1);
        definition.setExecutable(datasource.getExecutable() != null && datasource.getExecutable().intValue() == 1);
        definition.setTechnicalMetadata(datasource.getTechnicalMetadata());
        definition.setBusinessMetadata(datasource.getBusinessMetadata());
        return definition;
    }

    private List<Long> orderIds(Set<Long> ids) {
        List<Long> ordered = new ArrayList<Long>();
        if (ids != null) {
            ordered.addAll(ids);
        }
        Collections.sort(ordered, new Comparator<Long>() {
            @Override
            public int compare(Long left, Long right) {
                return Long.compare(left.longValue(), right.longValue());
            }
        });
        return ordered;
    }

    private enum IndexCommandType {
        REBUILD_MODEL,
        DELETE_MODEL,
        DELETE_DATASOURCE
    }

    private static final class IndexCommand {
        private final IndexCommandType type;
        private final Long targetId;

        private IndexCommand(IndexCommandType type, Long targetId) {
            this.type = type;
            this.targetId = targetId;
        }

        private String uniqueKey() {
            return type.name() + ":" + targetId;
        }
    }

    private static final class NormalizedCommandBatch {
        private final Set<Long> datasourceDeletes = new LinkedHashSet<Long>();
        private final Set<Long> modelDeletes = new LinkedHashSet<Long>();
        private final Set<Long> modelRebuilds = new LinkedHashSet<Long>();

        private boolean isEmpty() {
            return datasourceDeletes.isEmpty() && modelDeletes.isEmpty() && modelRebuilds.isEmpty();
        }
    }
}
