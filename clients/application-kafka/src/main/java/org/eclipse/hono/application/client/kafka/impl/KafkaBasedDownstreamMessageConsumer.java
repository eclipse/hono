/*
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

import java.util.Objects;

import org.eclipse.hono.application.client.DownstreamMessage;
import org.eclipse.hono.application.client.MessageConsumer;
import org.eclipse.hono.application.client.kafka.KafkaMessageContext;
import org.eclipse.hono.client.kafka.HonoTopic;
import org.eclipse.hono.client.kafka.consumer.AbstractAtLeastOnceKafkaConsumer;
import org.eclipse.hono.client.kafka.consumer.KafkaConsumerConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;

/**
 * A consumer that consumes downstream messages from Kafka.
 * <p>
 * This consumer needs to be closed by invoking {@link #close()} when it is no longer needed.
 */
public class KafkaBasedDownstreamMessageConsumer
        extends AbstractAtLeastOnceKafkaConsumer<DownstreamMessage<KafkaMessageContext>> implements MessageConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaBasedDownstreamMessageConsumer.class);

    /**
     * Creates a new downstream consumer.
     *
     * @param kafkaConsumer The Kafka consumer to be exclusively used by this instance to consume records.
     * @param topic The Kafka topic to consume records from.
     * @param messageHandler The handler to be invoked for each message created from a record. If the handler throws a
     *            runtime exception, it will be invoked again with the message.
     * @param closeHandler The handler to be invoked when the Kafka consumer has been closed due to an error.
     * @param pollTimeout The maximal number of milliseconds to wait for messages during a poll operation.
     * @throws NullPointerException if any of the parameters is {@code null}.
     */
    private KafkaBasedDownstreamMessageConsumer(final KafkaConsumer<String, Buffer> kafkaConsumer, final String topic,
            final Handler<DownstreamMessage<KafkaMessageContext>> messageHandler, final Handler<Throwable> closeHandler,
            final long pollTimeout) {
        super(kafkaConsumer, topic, messageHandler, closeHandler, pollTimeout);
    }

    /**
     * Creates and starts a message consumer for downstream data.
     *
     * @param tenantId The tenant to consume downstream data for.
     * @param type type The type of downstream message.
     * @param kafkaConsumer The Kafka consumer to be exclusively used by this instance to consume records.
     * @param config The Kafka consumer configuration properties to use.
     * @param messageHandler The handler to be invoked for each message created from a record. If the handler throws a
     *            runtime exception, it will be invoked again with the message.
     * @param closeHandler The handler to be invoked when the Kafka consumer has been closed due to an error.
     * @return a future indicating the outcome. When {@link #start()} completes successfully, the future will be
     *         completed with the consumer. Otherwise the future will fail with the cause.
     * @throws NullPointerException if any of the parameters is {@code null}.
     */
    public static Future<MessageConsumer> create(
            final String tenantId,
            final HonoTopic.Type type,
            final KafkaConsumer<String, Buffer> kafkaConsumer,
            final KafkaConsumerConfigProperties config, 
            final Handler<DownstreamMessage<KafkaMessageContext>> messageHandler,
            final Handler<Throwable> closeHandler) {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(type);
        Objects.requireNonNull(kafkaConsumer);
        Objects.requireNonNull(config);
        Objects.requireNonNull(messageHandler);
        Objects.requireNonNull(closeHandler);

        LOG.debug("KafkaBasedDownstreamMessageConsumer.create");

        final String topic = new HonoTopic(type, tenantId).toString();
        final long pollTimeout = config.getPollTimeout();
        final KafkaBasedDownstreamMessageConsumer consumer = new KafkaBasedDownstreamMessageConsumer(kafkaConsumer, topic, messageHandler,
                closeHandler, pollTimeout);

        return consumer.start().map(consumer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected DownstreamMessage<KafkaMessageContext> createMessage(final KafkaConsumerRecord<String, Buffer> record) {
        Objects.requireNonNull(record);

        return new KafkaDownstreamMessage(record);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Void> close() {
        return stop();
    }
}
