/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.engine.processing.processinstance;

import static io.camunda.zeebe.protocol.record.intent.ProcessInstanceIntent.SUSPENDED;
import static io.camunda.zeebe.protocol.record.intent.ProcessInstanceIntent.SUSPENDING;
import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.zeebe.engine.perf.TestEngine;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.protocol.record.intent.JobIntent;
import io.camunda.zeebe.protocol.record.intent.ProcessMessageSubscriptionIntent;
import io.camunda.zeebe.test.util.Strings;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import org.junit.jupiter.api.Test;

final class ProcessInstanceSuspendingTest {

  @Test
  void shouldCloseSubscriptionsAndSuspendJobsWhileSuspending() throws Exception {
    final var context = TestEngine.createTestContext();
    try {
      final var engine = TestEngine.createSinglePartitionEngine(context);
      final var processId = Strings.newRandomValidBpmnId();
      final var jobType = Strings.newRandomValidBpmnId();
      final var messageName = Strings.newRandomValidBpmnId();
      final var correlationKey = Strings.newRandomValidBpmnId();

      // given
      engine
          .createDeploymentClient()
          .withXmlResource(
              Bpmn.createExecutableProcess(processId)
                  .startEvent()
                  .parallelGateway("fork")
                  .serviceTask("task", task -> task.zeebeJobType(jobType))
                  .parallelGateway("join")
                  .moveToNode("fork")
                  .receiveTask("receive")
                  .message(m -> m.name(messageName).zeebeCorrelationKeyExpression("key"))
                  .connectTo("join")
                  .endEvent()
                  .done())
          .deploy();
      final var processInstanceClient = engine.createProcessInstanceClient();
      final long processInstanceKey =
          processInstanceClient
              .ofBpmnProcessId(processId)
              .withVariable("key", correlationKey)
              .create();
      RecordingExporter.jobRecords(JobIntent.CREATED)
          .withProcessInstanceKey(processInstanceKey)
          .getFirst();
      RecordingExporter.processMessageSubscriptionRecords(ProcessMessageSubscriptionIntent.CREATED)
          .withProcessInstanceKey(processInstanceKey)
          .getFirst();

      // when
      final var response = processInstanceClient.withInstanceKey(processInstanceKey).suspend();

      // then
      assertThat(response.getIntent()).isEqualTo(SUSPENDED);
      final var suspending =
          RecordingExporter.processInstanceRecords(SUSPENDING)
              .withRecordKey(processInstanceKey)
              .getFirst();
      final var subscriptionClosing =
          RecordingExporter.processMessageSubscriptionRecords(
                  ProcessMessageSubscriptionIntent.DELETING)
              .withProcessInstanceKey(processInstanceKey)
              .getFirst();
      final var jobSuspended =
          RecordingExporter.jobRecords(JobIntent.SUSPENDED)
              .withProcessInstanceKey(processInstanceKey)
              .getFirst();
      final var suspended =
          RecordingExporter.processInstanceRecords(SUSPENDED)
              .withRecordKey(processInstanceKey)
              .getFirst();
      assertThat(suspending.getPosition()).isLessThan(subscriptionClosing.getPosition());
      assertThat(subscriptionClosing.getPosition()).isLessThan(jobSuspended.getPosition());
      assertThat(jobSuspended.getPosition()).isLessThan(suspended.getPosition());
      assertThat(suspended.getSourceRecordPosition())
          .isEqualTo(suspending.getSourceRecordPosition());
    } finally {
      context.close();
      RecordingExporter.reset();
    }
  }
}
