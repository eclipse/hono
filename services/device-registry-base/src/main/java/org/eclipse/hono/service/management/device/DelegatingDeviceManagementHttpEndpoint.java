/*******************************************************************************
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.hono.service.management.device;

import java.net.HttpURLConnection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.config.ServiceConfigProperties;
import org.eclipse.hono.service.http.TracingHandler;
import org.eclipse.hono.service.management.AbstractDelegatingRegistryHttpEndpoint;
import org.eclipse.hono.service.management.Id;
import org.eclipse.hono.tracing.TracingHelper;
import org.eclipse.hono.util.RegistryManagementConstants;

import io.opentracing.Span;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * An {@code HttpEndpoint} for managing device registration information.
 * <p>
 * This endpoint implements the <em>device</em> resources of Hono's
 * <a href="https://www.eclipse.org/hono/docs/api/management/">Device Registry Management API</a>.
 * It receives HTTP requests representing operation invocations and executes the matching service
 * implementation methods. The outcome is then returned to the peer in the HTTP response.
 *
 * @param <S> The type of service this endpoint delegates to.
 */
public class DelegatingDeviceManagementHttpEndpoint<S extends DeviceManagementService> extends AbstractDelegatingRegistryHttpEndpoint<S, ServiceConfigProperties> {

    private static final String SPAN_NAME_CREATE_DEVICE = "create Device from management API";
    private static final String SPAN_NAME_GET_DEVICE = "get Device from management API";
    private static final String SPAN_NAME_SEARCH_DEVICES = "search Devices from management API";
    private static final String SPAN_NAME_UPDATE_DEVICE = "update Device from management API";
    private static final String SPAN_NAME_REMOVE_DEVICE = "remove Device from management API";

    private static final String DEVICE_MANAGEMENT_ENDPOINT_NAME = String.format("%s/%s",
                    RegistryManagementConstants.API_VERSION,
                    RegistryManagementConstants.DEVICES_HTTP_ENDPOINT);

    /**
     * Creates an endpoint for a service instance.
     *
     * @param vertx The vert.x instance to use.
     * @param service The service to delegate to.
     * @throws NullPointerException if any of the parameters are {@code null};
     */
    public DelegatingDeviceManagementHttpEndpoint(final Vertx vertx, final S service) {
        super(vertx, service);
    }

    @Override
    public String getName() {
        return DEVICE_MANAGEMENT_ENDPOINT_NAME;
    }

    @Override
    public void addRoutes(final Router router) {

        final String pathWithTenant = String.format("/%s/:%s", getName(), PARAM_TENANT_ID);
        final String pathWithTenantAndDeviceId = String.format("/%s/:%s/:%s", getName(), PARAM_TENANT_ID,
                PARAM_DEVICE_ID);

        // Add CORS handler
        router.route(pathWithTenant).handler(createCorsHandler(config.getCorsAllowedOrigin(), EnumSet.of(HttpMethod.POST)));
        router.route(pathWithTenantAndDeviceId).handler(createDefaultCorsHandler(config.getCorsAllowedOrigin()));


        // CREATE device with auto-generated deviceID
        router.post(pathWithTenant)
                .handler(this::extractOptionalJsonPayload)
                .handler(this::doCreateDevice);

        // CREATE device
        router.post(pathWithTenantAndDeviceId)
                .handler(this::extractOptionalJsonPayload)
                .handler(this::doCreateDevice);

        // GET device
        router.get(pathWithTenantAndDeviceId)
                .handler(this::doGetDevice);

        // SEARCH devices
        router.get(pathWithTenant)
                .handler(this::doSearchDevices);

        // UPDATE existing device
        router.put(pathWithTenantAndDeviceId)
                .handler(this::extractRequiredJsonPayload)
                .handler(this::extractIfMatchVersionParam)
                .handler(this::doUpdateDevice);

        // DELETE device
        router.delete(pathWithTenantAndDeviceId)
                .handler(this::extractIfMatchVersionParam)
                .handler(this::doDeleteDevice);
    }

    private void doGetDevice(final RoutingContext ctx) {

        final Span span = TracingHelper.buildServerChildSpan(
                tracer,
                TracingHandler.serverSpanContext(ctx),
                SPAN_NAME_GET_DEVICE,
                getClass().getSimpleName()
        ).start();

        final Future<String> tenantId = getRequestParameter(ctx, PARAM_TENANT_ID, getPredicate(config.getTenantIdPattern(), false));
        final Future<String> deviceId = getRequestParameter(ctx, PARAM_DEVICE_ID, getPredicate(config.getDeviceIdPattern(), false));

        CompositeFuture.all(tenantId, deviceId)
            .compose(ok -> {
                logger.debug("retrieving device [tenant: {}, device-id: {}]", tenantId.result(), deviceId.result());
                return getService().readDevice(tenantId.result(), deviceId.result(), span);
            })
            .onSuccess(operationResult -> writeResponse(ctx, operationResult, span))
            .onFailure(t -> failRequest(ctx, t, span))
            .onComplete(s -> span.finish());
    }

    private void doSearchDevices(final RoutingContext ctx) {
        final Span span = TracingHelper.buildServerChildSpan(
                tracer,
                TracingHandler.serverSpanContext(ctx),
                SPAN_NAME_SEARCH_DEVICES,
                getClass().getSimpleName()).start();

        final Future<String> tenantId = getRequestParameter(ctx, PARAM_TENANT_ID,
                getPredicate(config.getTenantIdPattern(), false));
        final Optional<Integer> pageSize = Optional
                .ofNullable(ctx.request().getParam(RegistryManagementConstants.FIELD_PAGE_SIZE))
                .map(Integer::parseInt);
        final Optional<Integer> pageOffset = Optional
                .ofNullable(ctx.request().getParam(RegistryManagementConstants.FIELD_PAGE_OFFSET))
                .map(Integer::parseInt);
        final Future<Optional<List<Filter>>> filters = decodeJsonFromRequestParameter(ctx,
                RegistryManagementConstants.FIELD_FILTER_JSON, Filter.class);
        final Future<Optional<List<Sort>>> sortOptions = decodeJsonFromRequestParameter(ctx,
                RegistryManagementConstants.FIELD_SORT_JSON, Sort.class);

        CompositeFuture.all(tenantId, filters, sortOptions)
                .compose(ok -> getService().searchDevices(
                        tenantId.result(),
                        pageSize,
                        pageOffset,
                        filters.result(),
                        sortOptions.result(),
                        span))
                .onSuccess(operationResult -> writeResponse(ctx, operationResult, span))
                .onFailure(t -> failRequest(ctx, t, span))
                .onComplete(s -> span.finish());
    }

    private void doCreateDevice(final RoutingContext ctx) {

        final Span span = TracingHelper.buildServerChildSpan(
                tracer,
                TracingHandler.serverSpanContext(ctx),
                SPAN_NAME_CREATE_DEVICE,
                getClass().getSimpleName()
        ).start();

        final Future<String> tenantId = getRequestParameter(ctx, PARAM_TENANT_ID, getPredicate(config.getTenantIdPattern(), false));
        final Future<String> deviceId = getRequestParameter(ctx, PARAM_DEVICE_ID, getPredicate(config.getDeviceIdPattern(), true));
        final Future<Device> device = fromPayload(ctx);

        CompositeFuture.all(tenantId, deviceId, device)
            .compose(ok -> {
                final Optional<String> did = Optional.ofNullable(deviceId.result());
                logger.debug("creating device [tenant: {}, device-id: {}]", tenantId.result(), did.orElse("<auto>"));
                return getService().createDevice(tenantId.result(), did, device.result(), span);
            })
            .onSuccess(operationResult -> writeResponse(ctx, operationResult, (responseHeaders, status) -> {
                    Optional.ofNullable(operationResult.getPayload())
                        .map(Id::getId)
                        .ifPresent(id -> responseHeaders.set(
                                HttpHeaders.LOCATION,
                                String.format("/%s/%s/%s", getName(), tenantId.result(), id)));
                }, span))
            .onFailure(t -> failRequest(ctx, t, span))
            .onComplete(s -> span.finish());
    }

    private void doUpdateDevice(final RoutingContext ctx) {

        final Span span = TracingHelper.buildServerChildSpan(
                tracer,
                TracingHandler.serverSpanContext(ctx),
                SPAN_NAME_UPDATE_DEVICE,
                getClass().getSimpleName()
        ).start();

        final Future<String> tenantId = getRequestParameter(ctx, PARAM_TENANT_ID, getPredicate(config.getTenantIdPattern(), false));
        final Future<String> deviceId = getRequestParameter(ctx, PARAM_DEVICE_ID, getPredicate(config.getDeviceIdPattern(), false));
        final Future<Device> device = fromPayload(ctx);

        CompositeFuture.all(tenantId, deviceId, device)
            .compose(ok -> {
                logger.debug("updating device [tenant: {}, device-id: {}]", tenantId.result(), deviceId.result());
                final Optional<String> resourceVersion = Optional.ofNullable(ctx.get(KEY_RESOURCE_VERSION));
                return getService().updateDevice(tenantId.result(), deviceId.result(), device.result(), resourceVersion, span);
            })
            .onSuccess(operationResult -> writeResponse(ctx, operationResult, span))
            .onFailure(t -> failRequest(ctx, t, span))
            .onComplete(s -> span.finish());
    }

    private void doDeleteDevice(final RoutingContext ctx) {

        final Span span = TracingHelper.buildServerChildSpan(
                tracer,
                TracingHandler.serverSpanContext(ctx),
                SPAN_NAME_REMOVE_DEVICE,
                getClass().getSimpleName()
        ).start();

        final Future<String> tenantId = getRequestParameter(ctx, PARAM_TENANT_ID, getPredicate(config.getTenantIdPattern(), false));
        final Future<String> deviceId = getRequestParameter(ctx, PARAM_DEVICE_ID, getPredicate(config.getDeviceIdPattern(), false));

        CompositeFuture.all(tenantId, deviceId)
            .compose(ok -> {
                logger.debug("removing device [tenant: {}, device-id: {}]", tenantId.result(), deviceId.result());
                final Optional<String> resourceVersion = Optional.ofNullable(ctx.get(KEY_RESOURCE_VERSION));
                return getService().deleteDevice(tenantId.result(), deviceId.result(), resourceVersion, span);
            })
            .onSuccess(result -> writeResponse(ctx, result, span))
            .onFailure(t -> failRequest(ctx, t, span))
            .onComplete(s -> span.finish());
    }

    /**
     * Gets the device from the request body.
     *
     * @param ctx The context to retrieve the request body from.
     * @return A future indicating the outcome of the operation.
     *         The future will be succeeded if the request body is either empty or contains a JSON
     *         object that complies with the Device Registry Management API's Device object definition.
     *         Otherwise, the future will be failed with a {@link org.eclipse.hono.client.ClientErrorException}
     *         containing a corresponding status code.
     * @throws NullPointerException If the context is {@code null}.
     */
    private static Future<Device> fromPayload(final RoutingContext ctx) {

        Objects.requireNonNull(ctx);

        final Promise<Device> result = Promise.promise();
        Optional.ofNullable(ctx.get(KEY_REQUEST_BODY))
            .map(JsonObject.class::cast)
            .ifPresentOrElse(
                    // validate payload
                    json -> {
                        try {
                            result.complete(json.mapTo(Device.class));
                        } catch (final DecodeException | IllegalArgumentException e) {
                            result.fail(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST,
                                    "request does not contain a valid Device object", e));
                        }
                    },
                    // payload was empty
                    () -> result.complete(new Device()));
        return result.future();
    }

    private <T> Future<Optional<List<T>>> decodeJsonFromRequestParameter(final RoutingContext ctx,
            final String paramKey, final Class<T> clazz) {

        Objects.requireNonNull(ctx);
        Objects.requireNonNull(paramKey);
        Objects.requireNonNull(clazz);

        final Promise<Optional<List<T>>> result = Promise.promise();
        final Optional<List<T>> values = Optional.ofNullable(ctx.request().params()
                .getAll(paramKey))
                .map(jsons -> jsons
                        .stream()
                        .map(json -> Json.decodeValue(json, clazz))
                        .collect(Collectors.toList()));
        result.complete(values);

        return result.future();
    }
}
