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

package org.eclipse.hono.client.kafka.consumer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.kafka.common.errors.TimeoutException;
import org.eclipse.hono.client.kafka.KafkaRecordHelper;
import org.eclipse.hono.util.Lifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.kafka.client.common.TopicPartition;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.consumer.KafkaConsumerRecords;
import io.vertx.kafka.client.consumer.OffsetAndMetadata;

/**
 * A client to consume data from a Kafka cluster.
 * <p>
 * This consumer continuously polls for batches of messages from Kafka. Each message is passed to a message handler for
 * processing. When all messages of a batch are processed, this consumer commits the offset and polls for a new batch.
 * <p>
 * This consumer provides AT LEAST ONCE message processing. When the message handler completes without throwing an
 * exception, the message is considered to be processed and will be "acknowledged" with the following commit.
 * <p>
 * If a fatal error occurs, the underlying Kafka consumer will be closed. In this case, other consumer instances in the
 * same consumer group will consume the messages from the last committed offset. Already processed messages might be
 * polled again with the next batch and then passed to the message handler. If de-duplication of messages is required,
 * it must be handled by the message handler.
 * <p>
 * The consumer starts consuming when {@link #start()} is invoked. It needs to be closed by invoking {@link #stop()} to
 * release the resources. A stopped instance cannot be started again.
 * <p>
 * </p>
 * ERROR CASES:
 * <p>
 * Errors can happen when polling, in message processing, and when committing the offset to Kafka.
 * If a fatal error occurs, the underlying Kafka consumer will be closed and the close-handler invoked with an exception
 * indicating the cause. Therefore, the provided Kafka consumer must not be used anywhere else.
 * <p>
 * If {@link KafkaConsumer#poll(Duration, Handler)} fails during the start, {@link #start()} will return a failed
 * future. For subsequent {@code poll} operations, the Kafka consumer will be closed and the close handler will be
 * passed a {@link KafkaConsumerPollException}.
 * <p>
 * If the provided message handler throws a runtime exception, the current offsets are committed and the failed message
 * will be polled again with the next batch of records.
 * <p>
 * If {@link KafkaConsumer#commit(Handler)} times out, the commit will be retried once. If the retry fails or the commit
 * fails with another exception, the Kafka consumer will be closed and the close handler will be passed a
 * {@link KafkaConsumerCommitException}. For example, commits could regularly fail during consumer rebalance.
 *
 * @param <T> The type of message to be created from the Kafka records.
 */
public abstract class AbstractAtLeastOnceKafkaConsumer<T> implements Lifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractAtLeastOnceKafkaConsumer.class);

    boolean stopped = false; // visible for testing

    private final KafkaConsumer<String, Buffer> kafkaConsumer;
    private final Set<String> topics;
    private final Pattern topicPattern;
    private final String topicsLogString;
    private final Handler<T> messageHandler;
    private final Handler<Throwable> closeHandler;
    private final Duration pollTimeout;
    private final Map<TopicPartition, OffsetAndMetadata> offsetsToBeCommitted = new HashMap<>();
    private boolean respectTtl = true;

    /**
     * Creates a new consumer.
     *
     * @param kafkaConsumer The Kafka consumer to be exclusively used by this instance to consume records.
     * @param topic The Kafka topic to consume records from.
     * @param messageHandler The handler to be invoked for each message created from a record. If the handler throws a
     *            runtime exception, the record will be polled again.
     * @param closeHandler The handler to be invoked when the Kafka consumer has been closed due to an error.
     * @param pollTimeout The maximal number of milliseconds to wait for messages during a poll operation.
     * @throws NullPointerException if any of the parameters is {@code null}.
     * @see #start()
     * @see #stop()
     */
    public AbstractAtLeastOnceKafkaConsumer(final KafkaConsumer<String, Buffer> kafkaConsumer, final String topic,
            final Handler<T> messageHandler, final Handler<Throwable> closeHandler, final long pollTimeout) {

        this(kafkaConsumer, Set.of(Objects.requireNonNull(topic)), messageHandler, closeHandler, pollTimeout);
    }

    /**
     * Creates a new consumer.
     * <p>
     * It must be ensured that all given topics contain compatible data to be able to create the expected message type
     * in {@link #createMessage(KafkaConsumerRecord)}.
     *
     * @param kafkaConsumer The Kafka consumer to be exclusively used by this instance to consume records.
     * @param topics The Kafka topics to consume records from.
     * @param messageHandler The handler to be invoked for each message created from a record. If the handler throws a
     *            runtime exception, the record will be polled again.
     * @param closeHandler The handler to be invoked when the Kafka consumer has been closed due to an error.
     * @param pollTimeout The maximal number of milliseconds to wait for messages during a poll operation.
     * @throws NullPointerException if any of the parameters is {@code null}.
     * @see #start()
     * @see #stop()
     */
    public AbstractAtLeastOnceKafkaConsumer(final KafkaConsumer<String, Buffer> kafkaConsumer, final Set<String> topics,
            final Handler<T> messageHandler, final Handler<Throwable> closeHandler, final long pollTimeout) {

        this(kafkaConsumer, Objects.requireNonNull(topics), null, messageHandler, closeHandler, pollTimeout);
    }

    /**
     * Creates a new consumer.
     * <p>
     * It must be ensured that all Topics matching the specified Topic pattern contain compatible data to be able to
     * create the expected message type in {@link #createMessage(KafkaConsumerRecord)}.
     *
     * @param kafkaConsumer The Kafka consumer to be exclusively used by this instance to consume records.
     * @param topicPattern The pattern of Kafka topic names to consume records from.
     * @param messageHandler The handler to be invoked for each message created from a record. If the handler throws a
     *            runtime exception, the record will be polled again.
     * @param closeHandler The handler to be invoked when the Kafka consumer has been closed due to an error.
     * @param pollTimeout The maximal number of milliseconds to wait for messages during a poll operation.
     * @throws NullPointerException if any of the parameters is {@code null}.
     * @see #start()
     * @see #stop()
     */
    public AbstractAtLeastOnceKafkaConsumer(final KafkaConsumer<String, Buffer> kafkaConsumer,
            final Pattern topicPattern, final Handler<T> messageHandler, final Handler<Throwable> closeHandler,
            final long pollTimeout) {

        this(kafkaConsumer, null, Objects.requireNonNull(topicPattern), messageHandler, closeHandler, pollTimeout);
    }

    private AbstractAtLeastOnceKafkaConsumer(final KafkaConsumer<String, Buffer> kafkaConsumer,
            final Set<String> topics, final Pattern topicPattern, final Handler<T> messageHandler,
            final Handler<Throwable> closeHandler, final long pollTimeout) {

        Objects.requireNonNull(kafkaConsumer);
        Objects.requireNonNull(messageHandler);
        Objects.requireNonNull(closeHandler);

        this.kafkaConsumer = kafkaConsumer;
        this.messageHandler = messageHandler;
        this.closeHandler = closeHandler;

        this.topics = topics;
        this.topicPattern = topicPattern;
        topicsLogString = topics != null
                ? "[" + topics.stream().limit(3).collect(Collectors.joining(", "))
                        + (topics.size() > 3 ? ", ...]" : "]")
                : topicPattern.toString();

        this.pollTimeout = Duration.ofMillis(pollTimeout);
    }

    /**
     * Creates a message from the given Kafka consumer record. The message will be passed to the message handler
     * afterward.
     * <p>
     * If possible, implementations should not throw exceptions. Any exception thrown here, will stop message
     * consumption permanently, because the consumer will not skip the message and new consumer instances will start
     * consuming from the failed message again.
     *
     * @param record The record.
     * @return The message.
     * @throws NullPointerException if the record is {@code null}.
     */
    protected abstract T createMessage(KafkaConsumerRecord<String, Buffer> record);

    /**
     * Starts the Kafka consumer.
     * <p>
     * The consumer subscribes and {@link KafkaConsumer#poll(Duration, Handler) polls} for the first batch of messages.
     * This method waits for the results of both operations but not for the processing of the messages), so that error
     * cases like invalid config properties or failing authentication are detected early.
     *
     * @return a future indicating the outcome. If the <em>subscribe</em> or the first <em>poll</em> operation fails,
     *         the future will be failed with the cause.
     */
    @Override
    public Future<Void> start() {

        if (stopped) {
            return Future.failedFuture("consumer already stopped"); // the underlying Kafka consumer cannot be reopened
        }

        kafkaConsumer.partitionsAssignedHandler(this::onPartitionsAssigned);
        kafkaConsumer.partitionsRevokedHandler(this::onPartitionsRevoked);

        final Promise<Void> promise = Promise.promise();
        if (topics != null) {
            kafkaConsumer.subscribe(topics, promise);
        } else {
            kafkaConsumer.subscribe(topicPattern, promise);
        }

        return promise.future()
                .compose(v -> {
                    LOG.debug("subscribe done, now poll...");
                    final Promise<KafkaConsumerRecords<String, Buffer>> pollPromise = Promise.promise();
                    kafkaConsumer.poll(pollTimeout, pollPromise);
                    return pollPromise.future()
                            .onSuccess(this::handleBatch) // do not wait for the processing to finish
                            .recover(cause -> Future.failedFuture(new KafkaConsumerPollException(cause)))
                            .mapEmpty();
                });
    }

    private void onPartitionsAssigned(final Set<TopicPartition> partitionsSet) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("partitions assigned: [{}]", getPartitionsDebugString(partitionsSet));
        }
    }

    private void onPartitionsRevoked(final Set<TopicPartition> partitionsSet) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("partitions revoked: [{}]", getPartitionsDebugString(partitionsSet));
        }
    }

    private String getPartitionsDebugString(final Set<TopicPartition> partitionsSet) {
        return partitionsSet.size() <= 20 // skip details for larger set
                ? partitionsSet.stream()
                        .collect(Collectors.groupingBy(TopicPartition::getTopic,
                                Collectors.mapping(TopicPartition::getPartition, Collectors.toCollection(TreeSet::new))))
                        .toString()
                : partitionsSet.size() + " topic partitions";
    }

    /**
     * Closes the underlying Kafka consumer and tries to commit the current offsets before.
     * <p>
     * This does not invoke the close handler.
     *
     * @return A future that will complete when the Kafka consumer is closed. If the offsets have been committed
     *         successfully, the future will succeed, otherwise, it will fail.
     */
    @Override
    public Future<Void> stop() {
        return tryCommitAndClose();
    }

    /**
     * Sets if messages that contain a <em>ttl</em> header where the time-to-live elapsed should be ignored.
     * <p>
     * The default is true.
     *
     * @param respectTtl The intended behaviour: if true, messages with elapsed ttl are silently dropped and the message
     *            handler not invoked.
     */
    public final void setRespectTtl(final boolean respectTtl) {
        this.respectTtl = respectTtl;
    }

    private void handleBatch(final KafkaConsumerRecords<String, Buffer> records) {
        try {
            LOG.debug("polled {} records on {}", records.size(), topicsLogString);

            for (int i = 0; i < records.size(); i++) {
                if (stopped) {
                    return;
                }

                final KafkaConsumerRecord<String, Buffer> record = records.recordAt(i);

                if (respectTtl && KafkaRecordHelper.isTtlElapsed(record.headers())) {
                    addToCurrentOffsets(record); // ttl elapsed -> add offset and resume with the next record
                } else {
                    final T message = createMessage(record);
                    try {
                        messageHandler.handle(message);
                        addToCurrentOffsets(record);
                    } catch (final RuntimeException messageHandlingError) {
                        LOG.debug("Message handler failed", messageHandlingError);
                        // resume with committing the current offsets and then polling (the failed record will be polled
                        // again)
                        break;
                    }
                }
            }
            commit(true).compose(ok -> poll()).onSuccess(this::handleBatch);
        } catch (final Exception ex) {
            LOG.error("Consumer failed, closing", ex);
            // indicates an unexpected programming error and ensures that the consumer does not silently stop consuming
            tryCommitAndClose().onComplete(v -> closeHandler.handle(ex));
        }
    }

    private Future<KafkaConsumerRecords<String, Buffer>> poll() {
        if (stopped) {
            return Future.failedFuture("consumer already stopped"); // stop consuming
        }

        final Promise<KafkaConsumerRecords<String, Buffer>> pollPromise = Promise.promise();
        kafkaConsumer.poll(pollTimeout, pollPromise);
        return pollPromise.future()
                .recover(cause -> {
                    LOG.error("Error polling messages: " + cause);
                    final KafkaConsumerPollException exception = new KafkaConsumerPollException(cause);
                    closeAndCallHandler(exception);
                    return Future.failedFuture(exception);
                });
    }

    private void addToCurrentOffsets(final KafkaConsumerRecord<String, Buffer> record) {
        final TopicPartition topicPartition = new TopicPartition(record.topic(), record.partition());
        // Kafka expects the offset to be committed that should be read next, therefore it is the current + 1
        final OffsetAndMetadata offsetAndMetadata = new OffsetAndMetadata(record.offset() + 1, "");
        offsetsToBeCommitted.put(topicPartition, offsetAndMetadata);
    }

    private Future<Void> commit(final boolean retry) {
        if (offsetsToBeCommitted.isEmpty()) {
            return Future.succeededFuture(); // poll next batch
        } else if (stopped) {
            return Future.failedFuture("consumer already stopped"); // stop consuming
        }

        return commitCurrentOffsets()
                .recover(cause -> {
                    LOG.error("Error committing offsets: " + cause);
                    if ((cause instanceof TimeoutException) && retry) {
                        LOG.debug("Committing offsets timed out. Maybe increase 'default.api.timeout.ms'?");
                        return commit(false); // retry once
                    } else {
                        final KafkaConsumerCommitException exception = new KafkaConsumerCommitException(cause);
                        closeAndCallHandler(exception);
                        return Future.failedFuture(exception);
                    }
                });
    }

    private Future<Void> commitCurrentOffsets() {
        if (offsetsToBeCommitted.isEmpty()) {
            LOG.debug("no offsets to commit");
            return Future.succeededFuture();
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace("committing offsets: {}", offsetsToBeCommitted);
        } else {
            LOG.debug("committing current offsets");
        }

        final Promise<Map<TopicPartition, OffsetAndMetadata>> completionHandler = Promise.promise();
        kafkaConsumer.commit(offsetsToBeCommitted, completionHandler);
        return completionHandler.future()
                .map(committedOffsets -> {
                    LOG.trace("successfully committed offsets");
                    offsetsToBeCommitted.clear();
                    return null;
                });
    }

    private void closeAndCallHandler(final Throwable exception) {
        LOG.error("Closing consumer with cause", exception);
        closeConsumer().onComplete(v -> closeHandler.handle(exception));
    }

    private Future<Void> tryCommitAndClose() {
        stopped = true;
        final Promise<Void> returnFuture = Promise.promise();

        commitCurrentOffsets()
                .onComplete(commitResult -> {
                    // always close consumer
                    closeConsumer()
                            .onComplete(v -> {
                                // return outcome of commit
                                if (commitResult.succeeded()) {
                                    returnFuture.complete();
                                } else {
                                    returnFuture.fail(commitResult.cause());
                                }
                            });
                });

        return returnFuture.future();
    }

    private Future<Void> closeConsumer() {
        stopped = true;
        final Promise<Void> promise = Promise.promise();
        kafkaConsumer.close(promise);
        offsetsToBeCommitted.clear();
        return promise.future();
    }

}
