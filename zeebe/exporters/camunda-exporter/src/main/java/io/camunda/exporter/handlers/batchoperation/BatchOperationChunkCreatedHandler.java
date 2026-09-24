/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.exporter.handlers.batchoperation;

import io.camunda.exporter.exceptions.PersistenceException;
import io.camunda.exporter.handlers.ExportHandler;
import io.camunda.exporter.index.TargetIndex;
import io.camunda.exporter.store.BatchRequest;
import io.camunda.exporter.tasks.batchoperations.BatchOperationUpdateTask;
import io.camunda.webapps.schema.entities.operation.BatchOperationEntity;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.protocol.record.ValueType;
import io.camunda.zeebe.protocol.record.intent.BatchOperationChunkIntent;
import io.camunda.zeebe.protocol.record.value.BatchOperationChunkRecordValue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This handler updates the {@link BatchOperationEntity} and increases the total number of items of
 * a batch operation. This is not done in the {@link BatchOperationUpdateTask} because - depending
 * on the configuration <code>exportItemsOnCreation</code> - the operation items are not exported
 * and therefore cannot be counted properly. <br>
 * <br>
 * Additionally to the {@link BatchOperationChunkCreatedItemHandler}, this handler removes an
 * existing endDate of the batch operation from the document. This way the {@link
 * BatchOperationUpdateTask} will process this batch operation again to update all counts. <br>
 * <br>
 * This process is necessary because sometimes the <code>COMPLETED</code> event from one partition
 * is exported before all <code>CHUNK_CREATED</code> events are exported from another partition. In
 * that case the numbers would forever be wrong.
 */
public class BatchOperationChunkCreatedHandler
    implements ExportHandler<BatchOperationEntity, BatchOperationChunkRecordValue> {

  private static final String PARTITION_ID_PARAM = "partitionId";
  private static final String CHUNK_RECORD_KEYS_PARAM = "chunkRecordKeys";
  private static final String CHUNK_RECORD_ITEM_COUNTS_PARAM = "chunkRecordItemCounts";
  private static final String MAX_CHUNK_RECORD_KEY_PARAM = "maxChunkRecordKey";

  // Guards operationsTotalCount against duplicate export of the same CHUNK_CREATED record (e.g. an
  // exporter restart before position acknowledgment causes a resend). An exporter replays its own
  // partition's log in ascending position, and the chunk records are written by that partition
  // itself, so their keys arrive in ascending order: one high-water-mark key per partition is
  // enough to recognise an already applied record, and keeps the tracking state bounded by the
  // partition count instead of growing with every chunk.
  // Elasticsearch/OpenSearch deserialize a persisted "long" back into a Painless Integer when its
  // value happens to fit in an int, so stored values are compared via longValue() rather than
  // directly - the latter would silently mismatch.
  private static final String SCRIPT =
      """
          if (ctx._source.lastProcessedChunkRecords == null) {
            ctx._source.lastProcessedChunkRecords = [];
          }
          long lastKey = -1;
          def marker = null;
          for (def candidate : ctx._source.lastProcessedChunkRecords) {
            if (((Number) candidate.partitionId).intValue() == params.partitionId) {
              marker = candidate;
              lastKey = ((Number) candidate.recordKey).longValue();
              break;
            }
          }
          int delta = 0;
          for (int i = 0; i < params.chunkRecordKeys.size(); i++) {
            if (((Number) params.chunkRecordKeys[i]).longValue() > lastKey) {
              delta += (int) params.chunkRecordItemCounts[i];
            }
          }
          long maxKey = ((Number) params.maxChunkRecordKey).longValue();
          if (maxKey > lastKey) {
            if (marker == null) {
              ctx._source.lastProcessedChunkRecords.add(
                  ["partitionId": params.partitionId, "recordKey": maxKey]);
            } else {
              marker.recordKey = maxKey;
            }
          }
          ctx._source.operationsTotalCount = ctx._source.operationsTotalCount + delta;
          ctx._source.endDate = null;
      """;

  private final String indexName;

  public BatchOperationChunkCreatedHandler(final String indexName) {
    this.indexName = indexName;
  }

  @Override
  public ValueType getHandledValueType() {
    return ValueType.BATCH_OPERATION_CHUNK;
  }

  @Override
  public Class<BatchOperationEntity> getEntityType() {
    return BatchOperationEntity.class;
  }

  @Override
  public boolean handlesRecord(final Record<BatchOperationChunkRecordValue> record) {
    return record.getIntent().equals(BatchOperationChunkIntent.CREATED);
  }

  @Override
  public List<String> generateIds(final Record<BatchOperationChunkRecordValue> record) {
    // Use a composite ID with a static suffix (batchOperationKey:chunk) so that all chunks for the
    // same batch operation share a single cached entity in the ExporterBatchWriter, while still
    // being separate from BatchOperationCreatedHandler (which uses just the batchOperationKey).
    // This avoids double-counting of operationsTotalCount without inflating the batch size.
    return List.of(record.getValue().getBatchOperationKey() + ":chunk");
  }

  @Override
  public BatchOperationEntity createNewEntity(final String id) {
    return new BatchOperationEntity().setId(id);
  }

  @Override
  public void updateEntity(
      final Record<BatchOperationChunkRecordValue> record, final BatchOperationEntity entity) {
    // keyed by the record's own (replay-stable) key so a duplicate updateEntity() call for the
    // same record - e.g. the batch writer retrying this entity after a failed flush - overwrites
    // rather than doubles its contribution
    entity
        .getPendingChunkRecordItemCounts()
        .put(record.getKey(), record.getValue().getItems().size());
    entity.setPendingChunkRecordPartitionId(record.getPartitionId());
    entity.setOperationsTotalCount(
        entity.getPendingChunkRecordItemCounts().values().stream()
            .mapToInt(Integer::intValue)
            .sum());
  }

  @Override
  public void flush(
      final TargetIndex index, final BatchOperationEntity entity, final BatchRequest batchRequest)
      throws PersistenceException {
    // Atomically increment the total and reset endDate so the BatchOperationUpdateTask
    // re-processes the counts. Extract the batchKey from the composite cache ID (batchKey:chunk).
    final String batchOperationKey = entity.getId().split(":")[0];

    final var recordKeys = new ArrayList<>(entity.getPendingChunkRecordItemCounts().keySet());
    final var itemCounts = new ArrayList<>(entity.getPendingChunkRecordItemCounts().values());

    final Map<String, Object> params = new HashMap<>();
    params.put(PARTITION_ID_PARAM, entity.getPendingChunkRecordPartitionId());
    params.put(CHUNK_RECORD_KEYS_PARAM, recordKeys);
    params.put(CHUNK_RECORD_ITEM_COUNTS_PARAM, itemCounts);
    params.put(
        MAX_CHUNK_RECORD_KEY_PARAM,
        recordKeys.stream().mapToLong(Long::longValue).max().orElse(-1));

    batchRequest.updateWithScript(index, batchOperationKey, SCRIPT, params);
  }

  @Override
  public String getIndexName() {
    return indexName;
  }
}
