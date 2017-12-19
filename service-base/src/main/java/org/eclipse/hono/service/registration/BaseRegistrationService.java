/**
 * Copyright (c) 2016, 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial creation
 */
package org.eclipse.hono.service.registration;

import java.net.HttpURLConnection;
import java.util.Objects;

import org.eclipse.hono.util.ConfigurationSupportingVerticle;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.RegistrationConstants;
import org.eclipse.hono.util.RegistrationResult;
import org.eclipse.hono.util.RequestResponseApiConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;

/**
 * Base class for implementing {@code RegistrationService}s.
 * <p>
 * In particular, this base class provides support for parsing registration request messages
 * received via the event bus and route them to specific methods corresponding to the <em>action</em>
 * indicated in the message.
 * 
 * @param <T> The type of configuration properties this service requires.
 */
public abstract class BaseRegistrationService<T> extends ConfigurationSupportingVerticle<T> implements RegistrationService {

    /**
     * A logger to be shared by subclasses.
     */
    protected final Logger log = LoggerFactory.getLogger(getClass());
    private MessageConsumer<JsonObject> registrationConsumer;
    private RegistrationAssertionHelper assertionFactory;

    /**
     * Sets the factory to use for creating tokens asserting a device's registration status.
     * 
     * @param assertionFactory The factory.
     * @throws NullPointerException if factory is {@code null}.
     */
    @Autowired
    @Qualifier("signing")
    public final void setRegistrationAssertionFactory(final RegistrationAssertionHelper assertionFactory) {
        this.assertionFactory = Objects.requireNonNull(assertionFactory);
    }

    /**
     * Starts up this service.
     * <ol>
     * <li>Checks if <em>registrationAssertionFactory</em>is set. If not, startup fails.</li>
     * <li>Registers an event bus consumer for address {@link RegistrationConstants#EVENT_BUS_ADDRESS_REGISTRATION_IN}
     * listening for registration requests.</li>
     * <li>Invokes {@link #doStart(Future)}.</li>
     * </ol>
     *
     * @param startFuture The future to complete on successful startup.
     */
    @Override
    public final void start(final Future<Void> startFuture) {

        if (assertionFactory == null) {
            startFuture.fail("registration assertion factory must be set");
        } else {
            registerConsumer();
            doStart(startFuture);
        }
    }

    /**
     * Subclasses should override this method to perform any work required on start-up of this verticle.
     * <p>
     * This method is invoked by {@link #start()} as part of the verticle deployment process.
     * </p>
     *
     * @param startFuture future to invoke once start up is complete.
     */
    protected void doStart(final Future<Void> startFuture) {
        // should be overridden by subclasses
        startFuture.complete();
    }

    private void registerConsumer() {
        registrationConsumer = vertx.eventBus().consumer(RegistrationConstants.EVENT_BUS_ADDRESS_REGISTRATION_IN);
        registrationConsumer.handler(this::processRegistrationMessage);
        log.info("listening on event bus [address: {}] for incoming registration messages",
                RegistrationConstants.EVENT_BUS_ADDRESS_REGISTRATION_IN);
    }

    /**
     * Unregisters the registration message consumer from the Vert.x event bus and then invokes {@link #doStop(Future)}.
     *
     * @param stopFuture the future to invoke once shutdown is complete.
     */
    @Override
    public final void stop(final Future<Void> stopFuture) {
        registrationConsumer.unregister();
        log.info("unregistered registration data consumer from event bus");
        doStop(stopFuture);
    }

    /**
     * Subclasses should override this method to perform any work required before shutting down this verticle.
     * <p>
     * This method is invoked by {@link #stop()} as part of the verticle deployment process.
     * </p>
     *
     * @param stopFuture the future to invoke once shutdown is complete.
     */
    protected void doStop(final Future<Void> stopFuture) {
        // to be overridden by subclasses
        stopFuture.complete();
    }

    /**
     * Processes a registration request message received via the Vertx event bus.
     * 
     * @param regMsg The message.
     */
    public final void processRegistrationMessage(final Message<JsonObject> regMsg) {

        try {
            final JsonObject body = regMsg.body();
            final String tenantId = body.getString(RequestResponseApiConstants.FIELD_TENANT_ID);
            final String deviceId = body.getString(RequestResponseApiConstants.FIELD_DEVICE_ID);
            final String operation = body.getString(MessageHelper.SYS_PROPERTY_SUBJECT);


            switch (operation) {
            case RegistrationConstants.ACTION_ASSERT:
                log.debug("asserting registration of device [{}] with tenant [{}]", deviceId, tenantId);
                assertRegistration(tenantId, deviceId, result -> reply(regMsg, result));
                break;
            case RegistrationConstants.ACTION_GET:
                log.debug("retrieving device [{}] of tenant [{}]", deviceId, tenantId);
                getDevice(tenantId, deviceId, result -> reply(regMsg, result));
                break;
            case RegistrationConstants.ACTION_REGISTER:
                JsonObject payload = getRequestPayload(body);
                log.debug("registering device [{}] of tenant [{}] with data {}", deviceId, tenantId,
                        payload != null ? payload.encode() : null);
                addDevice(tenantId, deviceId, payload, result -> reply(regMsg, result));
                break;
            case RegistrationConstants.ACTION_UPDATE:
                payload = getRequestPayload(body);
                log.debug("updating registration for device [{}] of tenant [{}] with data {}", deviceId, tenantId,
                        payload != null ? payload.encode() : null);
                updateDevice(tenantId, deviceId, payload, result -> reply(regMsg, result));
                break;
            case RegistrationConstants.ACTION_DEREGISTER:
                log.debug("deregistering device [{}] of tenant [{}]", deviceId, tenantId);
                removeDevice(tenantId, deviceId, result -> reply(regMsg, result));
                break;
            default:
                log.info("operation [{}] not supported", operation);
                reply(regMsg, RegistrationResult.from(HttpURLConnection.HTTP_BAD_REQUEST));
            }
        } catch (ClassCastException e) {
            log.debug("malformed request message");
            reply(regMsg, RegistrationResult.from(HttpURLConnection.HTTP_BAD_REQUEST));
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Subclasses may override this method in order to implement a more sophisticated approach for asserting registration status, e.g.
     * using cached information etc.
     */
    @Override
    public void assertRegistration(final String tenantId, final String deviceId, final Handler<AsyncResult<RegistrationResult>> resultHandler) {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(resultHandler);

        getDevice(tenantId, deviceId, getAttempt -> {
            if (getAttempt.failed()) {
                resultHandler.handle(getAttempt);
            } else {
                final RegistrationResult result = getAttempt.result();
                if (result.getStatus() == HttpURLConnection.HTTP_NOT_FOUND) {
                    // device is not registered with tenant
                    resultHandler.handle(getAttempt);
                } else {
                    final JsonObject registrationInfo = result.getPayload().getJsonObject(RegistrationConstants.FIELD_DATA);
                    if (isDeviceEnabled(registrationInfo)) {
                        // device is registered with tenant and is enabled
                        resultHandler.handle(Future.succeededFuture(RegistrationResult.from(
                                HttpURLConnection.HTTP_OK,
                                getAssertionPayload(tenantId, deviceId, registrationInfo))));
                    } else {
                        resultHandler.handle(Future.succeededFuture(RegistrationResult.from(HttpURLConnection.HTTP_NOT_FOUND)));
                    }
                }
            }
        });
    }

    private boolean isDeviceEnabled(final JsonObject registrationData) {
        return registrationData.getBoolean(RegistrationConstants.FIELD_ENABLED, Boolean.TRUE);
    }

    /**
     * Creates a registration assertion token for a device and wraps it in a JSON object.
     * <p>
     * The returned JSON object may also contain <em>default</em> values registered for the
     * device under key {@link RegistrationConstants#FIELD_DEFAULTS}.
     * 
     * @param tenantId The tenant the device belongs to.
     * @param deviceId The device to create the assertion token for.
     * @param registrationInfo The device's registration information.
     * @return The payload.
     */
    protected final JsonObject getAssertionPayload(final String tenantId, final String deviceId, final JsonObject registrationInfo) {

        final JsonObject result = new JsonObject()
                .put(RegistrationConstants.FIELD_DEVICE_ID, deviceId)
                .put(RegistrationConstants.FIELD_ASSERTION, assertionFactory.getAssertion(tenantId, deviceId));
        final JsonObject defaults = registrationInfo.getJsonObject(RegistrationConstants.FIELD_DEFAULTS);
        if (defaults != null) {
            result.put(RegistrationConstants.FIELD_DEFAULTS, defaults);
        }
        return result;
    }

    private void reply(final Message<JsonObject> request, final AsyncResult<RegistrationResult> result) {

        if (result.succeeded()) {
            reply(request, result.result());
        } else {
            request.fail(HttpURLConnection.HTTP_INTERNAL_ERROR, "cannot process registration request");
        }
    }

    /**
     * Sends a response to a registration request over the Vertx event bus.
     * 
     * @param request The message to respond to.
     * @param result The registration result that should be conveyed in the response.
     */
    protected final void reply(final Message<JsonObject> request, final RegistrationResult result) {

        final JsonObject body = request.body();
        final String tenantId = body.getString(RequestResponseApiConstants.FIELD_TENANT_ID);
        final String deviceId = body.getString(RequestResponseApiConstants.FIELD_DEVICE_ID);

        request.reply(RegistrationConstants.getServiceReplyAsJson(tenantId, deviceId, result));
    }

    private JsonObject getRequestPayload(final JsonObject request) {

        final JsonObject payload = request.getJsonObject(RegistrationConstants.FIELD_PAYLOAD, new JsonObject());
        Boolean enabled = payload.getBoolean(RegistrationConstants.FIELD_ENABLED);
        if (enabled == null) {
            log.debug("adding 'enabled' key to payload");
            payload.put(RegistrationConstants.FIELD_ENABLED, Boolean.TRUE);
        }
        return payload;
    }

    /**
     * Wraps a given device ID and registration data into a JSON structure suitable
     * to be returned to clients as the result of a registration operation.
     * 
     * @param deviceId The device ID.
     * @param data The registration data.
     * @return The JSON structure.
     */
    protected final static JsonObject getResultPayload(final String deviceId, final JsonObject data) {

        return new JsonObject()
                .put(RegistrationConstants.FIELD_DEVICE_ID, deviceId)
                .put(RegistrationConstants.FIELD_DATA, data);
    }

}
