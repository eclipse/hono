/**
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.hono.application.client.kafka.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.hono.client.kafka.CachingKafkaProducerFactory;
import org.eclipse.hono.client.kafka.HonoTopic;
import org.eclipse.hono.client.kafka.KafkaProducerConfigProperties;
import org.eclipse.hono.client.kafka.consumer.KafkaConsumerConfigProperties;
import org.eclipse.hono.kafka.test.KafkaClientUnitTestHelper;
import org.eclipse.hono.util.MessageHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.opentracing.noop.NoopSpan;
import io.opentracing.noop.NoopTracerFactory;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.serialization.BufferSerializer;

/**
 * Tests verifying behavior of {@link KafkaBasedCommandSender}.
 *
 */
@ExtendWith(VertxExtension.class)
// Sending command (request/response pattern) requires slightly higher timeout in slower environments.
@Timeout(value = 40, timeUnit = TimeUnit.SECONDS)
public class KafkaBasedCommandSenderTest {
    private KafkaBasedCommandSender commandSender;
    private KafkaConsumerConfigProperties consumerConfig;
    private MockProducer<String, Buffer> mockProducer;
    private MockConsumer<String, Buffer> mockConsumer;
    private String tenantId;
    private String deviceId;
    private Vertx vertx;

    /**
     *
     * Sets up fixture.
     *
     * @param vertx The vert.x instance to use.
     */
    @BeforeEach
    void setUp(final Vertx vertx) {
        final KafkaProducerConfigProperties producerConfig;
        final CachingKafkaProducerFactory<String, Buffer> producerFactory;

        this.vertx = vertx;
        consumerConfig = new KafkaConsumerConfigProperties();
        mockConsumer = new MockConsumer<>(OffsetResetStrategy.LATEST);
        producerConfig = new KafkaProducerConfigProperties();
        producerConfig.setProducerConfig(Map.of("client.id", "application-test-sender"));
        mockProducer = new MockProducer<>(true, new StringSerializer(), new BufferSerializer());
        producerFactory = KafkaClientUnitTestHelper.newProducerFactory(mockProducer);

        commandSender = new KafkaBasedCommandSender(vertx, consumerConfig, producerFactory, producerConfig,
                NoopTracerFactory.create());
        tenantId = UUID.randomUUID().toString();
        deviceId = UUID.randomUUID().toString();
    }

    /**
     * Verifies that a command sent asynchronously to a device succeeds.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testSendAsyncCommandSucceeds(final VertxTestContext ctx) {
        final Map<String, Object> headerProperties = new HashMap<>();
        final String correlationId = UUID.randomUUID().toString();
        final String subject = "setVolume";
        headerProperties.put("appKey", "appValue");

        commandSender
                .sendAsyncCommand(tenantId, deviceId, subject, null, Buffer.buffer("{\"value\": 20}"), correlationId,
                null, headerProperties, NoopSpan.INSTANCE.context())
                .onComplete(ctx.succeeding(ok -> {
                    ctx.verify(() -> {
                        final ProducerRecord<String, Buffer> commandRecord = mockProducer.history().get(0);
                        assertThat(commandRecord.key()).isEqualTo(deviceId);
                        assertThat(commandRecord.headers()).containsOnlyOnce(
                                new RecordHeader(MessageHelper.SYS_PROPERTY_SUBJECT, subject.getBytes()));
                        assertThat(commandRecord.headers()).containsOnlyOnce(
                                new RecordHeader(MessageHelper.SYS_PROPERTY_CORRELATION_ID, correlationId.getBytes()));
                        assertThat(commandRecord.headers()).containsOnlyOnce(
                                new RecordHeader("appKey", "appValue".getBytes()));
                    });
                    ctx.completeNow();
                }));
    }

    /**
     * Verifies that a one-way command sent to a device succeeds.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testSendOneWayCommandSucceeds(final VertxTestContext ctx) {
        final Map<String, Object> headerProperties = new HashMap<>();
        final String subject = "setVolume";
        headerProperties.put("appKey", "appValue");

        commandSender
                .sendOneWayCommand(tenantId, deviceId, subject, null, Buffer.buffer("{\"value\": 20}"), headerProperties,
                NoopSpan.INSTANCE.context())
                .onComplete(ctx.succeeding(ok -> {
                    ctx.verify(() -> {
                        final ProducerRecord<String, Buffer> commandRecord = mockProducer.history().get(0);
                        assertThat(commandRecord.key()).isEqualTo(deviceId);
                        assertThat(commandRecord.headers()).containsOnlyOnce(
                                new RecordHeader(MessageHelper.SYS_PROPERTY_SUBJECT, subject.getBytes()));
                        assertThat(commandRecord.headers()).containsOnlyOnce(
                                new RecordHeader("appKey", "appValue".getBytes()));
                    });
                    ctx.completeNow();
                }));
    }

    /**
     * Verifies that a command sent to a device succeeds and also a response is received from the device.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testSendCommandAndReceiveResponse(final VertxTestContext ctx) { // FIXME HERE
        final Map<String, Object> headerProperties = new HashMap<>();
        final String command = "setVolume";
        final String correlationId = UUID.randomUUID().toString();
        headerProperties.put("appKey", "appValue");
        final String responsePayload = "success";
        final int responseStatus = HttpURLConnection.HTTP_OK;

        // This correlation id is used for both command and its response.
        commandSender.setCorrelationIdSupplier(() -> correlationId);

        //start a command response consumer
        commandSender.setCommandResponseConsumerFactory((msgHandler) -> KafkaBasedDownstreamMessageConsumer
                        .create(tenantId, HonoTopic.Type.COMMAND_RESPONSE, KafkaConsumer.create(vertx, mockConsumer),
                                consumerConfig, msgHandler, t -> {})
                        .onSuccess(ok -> {
                            // Send a command response with the same correlation id as that of the command
                            final ConsumerRecord<String, Buffer> commandResponseRecord = commandResponseRecord(tenantId,
                                    deviceId, correlationId, responseStatus, Buffer.buffer(responsePayload));
                            final String responseTopic = new HonoTopic(HonoTopic.Type.COMMAND_RESPONSE, tenantId).toString();
                            final TopicPartition responseTopicPartition = new TopicPartition(responseTopic, 0);
                            mockConsumer.updateBeginningOffsets(Map.of(responseTopicPartition, 0L));
                            mockConsumer.updateEndOffsets(Map.of(responseTopicPartition, 0L));
                            mockConsumer.rebalance(Collections.singletonList(responseTopicPartition));
                            mockConsumer.addRecord(commandResponseRecord);
                        }));
        // Send a command to the device
        commandSender.sendCommand(tenantId, deviceId, command, null, Buffer.buffer("test"), headerProperties)
                .onComplete(ctx.succeeding(response -> {
                    ctx.verify(() -> {
                        // Verify the command response that has been received
                        assertThat(response.getDeviceId()).isEqualTo(deviceId);
                        assertThat(response.getStatus()).isEqualTo(responseStatus);
                        assertThat(response.getPayload().toString()).isEqualTo(responsePayload);
                    });
                    ctx.completeNow();
                    mockConsumer.close();
                    commandSender.stop();
                }));
    }

    private ConsumerRecord<String, Buffer> commandResponseRecord(final String tenantId, final String deviceId,
            final String correlationId, final int status, final Buffer payload) {
        final List<Header> headers = new ArrayList<>();

        headers.add(new RecordHeader(MessageHelper.APP_PROPERTY_TENANT_ID, tenantId.getBytes()));
        headers.add(new RecordHeader(MessageHelper.APP_PROPERTY_DEVICE_ID, deviceId.getBytes()));
        headers.add(new RecordHeader(MessageHelper.SYS_PROPERTY_CORRELATION_ID, correlationId.getBytes()));
        headers.add(new RecordHeader(MessageHelper.APP_PROPERTY_STATUS, String.valueOf(status).getBytes()));
        return new ConsumerRecord<>(new HonoTopic(HonoTopic.Type.COMMAND_RESPONSE, tenantId).toString(), 0, 0, -1L,
                TimestampType.NO_TIMESTAMP_TYPE, -1L, -1, -1, deviceId,
                payload, new RecordHeaders(headers.toArray(Header[]::new)));
    }

}
