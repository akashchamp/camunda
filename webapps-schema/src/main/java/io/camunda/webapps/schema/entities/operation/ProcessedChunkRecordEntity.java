/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.webapps.schema.entities.operation;

import io.camunda.webapps.schema.entities.PartitionedEntity;
import io.camunda.webapps.schema.entities.SinceVersion;

/**
 * High-water-mark of the BATCH_OPERATION_CHUNK CREATED records of one partition that have been
 * applied to {@link BatchOperationEntity#getOperationsTotalCount()}.
 *
 * <p>An exporter replays its own partition's log in ascending position, and the chunk records are
 * written by that same partition, so their keys arrive in ascending order. A single key per
 * partition is therefore enough to recognise an already applied record: anything with a key less
 * than or equal to {@link #getRecordKey()} has been counted before.
 *
 * @since 8.11.0
 */
public class ProcessedChunkRecordEntity implements PartitionedEntity<ProcessedChunkRecordEntity> {

  @SinceVersion("8.11.0")
  private int partitionId;

  @SinceVersion("8.11.0")
  private long recordKey;

  @Override
  public int getPartitionId() {
    return partitionId;
  }

  @Override
  public ProcessedChunkRecordEntity setPartitionId(final int partitionId) {
    this.partitionId = partitionId;
    return this;
  }

  public long getRecordKey() {
    return recordKey;
  }

  public ProcessedChunkRecordEntity setRecordKey(final long recordKey) {
    this.recordKey = recordKey;
    return this;
  }

  @Override
  public int hashCode() {
    return 31 * Integer.hashCode(partitionId) + Long.hashCode(recordKey);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final ProcessedChunkRecordEntity that = (ProcessedChunkRecordEntity) o;
    return partitionId == that.partitionId && recordKey == that.recordKey;
  }

  @Override
  public String toString() {
    return "ProcessedChunkRecordEntity{"
        + "partitionId="
        + partitionId
        + ", recordKey="
        + recordKey
        + '}';
  }

  public static ProcessedChunkRecordEntity of(final int partitionId, final long recordKey) {
    return new ProcessedChunkRecordEntity().setPartitionId(partitionId).setRecordKey(recordKey);
  }
}
