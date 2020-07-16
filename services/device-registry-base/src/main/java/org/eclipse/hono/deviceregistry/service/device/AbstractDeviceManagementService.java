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
package org.eclipse.hono.deviceregistry.service.device;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.hono.deviceregistry.service.tenant.NoopTenantInformationService;
import org.eclipse.hono.deviceregistry.service.tenant.TenantInformationService;
import org.eclipse.hono.service.management.Id;
import org.eclipse.hono.service.management.OperationResult;
import org.eclipse.hono.service.management.Result;
import org.eclipse.hono.service.management.device.Device;
import org.eclipse.hono.service.management.device.DeviceManagementService;
import org.springframework.beans.factory.annotation.Autowired;

import io.opentracing.Span;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;

/**
 * An abstract base class implementation for {@link DeviceManagementService}.
 * <p>
 * It checks the parameters, validate tenant using {@link TenantInformationService} and creates {@link DeviceKey} for device operations.
 */
public abstract class AbstractDeviceManagementService implements DeviceManagementService {


    protected TenantInformationService tenantInformationService = new NoopTenantInformationService();

    /**
     * Sets the service to use for checking existence of tenants.
     * <p>
     * If not set, tenant existence will not be verified.
     *
     * @param tenantInformationService The tenant information service.
     */
    @Autowired(required = false)
    public void setTenantInformationService(final TenantInformationService tenantInformationService) {
        this.tenantInformationService = Objects.requireNonNull(tenantInformationService);
    }

    /**
     * Create a device with a specified key and value object.
     *
     * @param key The device key object.
     * @param device The device value object.
     * @param span The active OpenTracing span for this operation.
     * @return A future indicating the outcome of the operation.
     */
    protected abstract Future<OperationResult<Id>> processCreateDevice(DeviceKey key, Device device, Span span);

    /**
     * Read a device with a specified key.
     *
     * @param key The device key object.
     * @param span The active OpenTracing span for this operation.
     * @return A future indicating the outcome of the operation.
     */
    protected abstract Future<OperationResult<Device>> processReadDevice(DeviceKey key, Span span);

    /**
     * Update a device with a specified key and value object.
     *
     * @param key The device key object.
     * @param device The device value object.
     * @param resourceVersion The identifier of the resource version to update.
     * @param span The active OpenTracing span for this operation.
     * @return A future indicating the outcome of the operation.
     */
    protected abstract Future<OperationResult<Id>> processUpdateDevice(DeviceKey key, Device device, Optional<String> resourceVersion, Span span);

    /**
     * Delete a device with a specified key.
     *
     * @param key The device key object.
     * @param resourceVersion The identifier of the resource version to update.
     * @param span The active OpenTracing span for this operation.
     * @return A future indicating the outcome of the operation.
     */
    protected abstract Future<Result<Void>> processDeleteDevice(DeviceKey key, Optional<String> resourceVersion, Span span);

    /**
     * Apply a patch to a list of devices.
     *
     * @param tenantId The tenant the device belongs to.
     * @param deviceIds A list of devices ID to apply the patch to.
     * @param patchData The actual data to patch.
     * @param span The active OpenTracing span for this operation.
     * @return  A future indicating the outcome of the operation.
     */
     protected abstract Future<Result<Void>> processPatchDevice(String tenantId, List deviceIds,
                                                                JsonArray patchData, Span span);

    /**
     * Generates a unique device identifier for a given tenant. A default implementation generates a random UUID value.
     *
     * @param tenantId The tenant identifier.
     * @return The device identifier.
     */
    protected String generateDeviceId(final String tenantId) {
        return UUID.randomUUID().toString();
    }

    @Override
    public Future<OperationResult<Id>> createDevice(final String tenantId, final Optional<String> deviceId, final Device device, final Span span) {

        Objects.requireNonNull(tenantId);
        final String deviceIdValue = deviceId.orElseGet(() -> generateDeviceId(tenantId));

        return this.tenantInformationService
                .tenantExists(tenantId, span)
                .compose(result -> result.isError()
                        ? Future.succeededFuture(OperationResult.empty(result.getStatus()))
                        : processCreateDevice(DeviceKey.from(result.getPayload(), deviceIdValue), device, span));

    }

    @Override
    public Future<OperationResult<Device>> readDevice(final String tenantId, final String deviceId, final Span span) {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);

        return this.tenantInformationService
                .tenantExists(tenantId, span)
                .compose(result -> result.isError()
                        ? Future.succeededFuture(OperationResult.empty(result.getStatus()))
                        : processReadDevice(DeviceKey.from(result.getPayload(), deviceId), span));

    }

    @Override
    public Future<OperationResult<Id>> updateDevice(final String tenantId, final String deviceId, final Device device, final Optional<String> resourceVersion, final Span span) {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(resourceVersion);

        return this.tenantInformationService
                .tenantExists(tenantId, span)
                .compose(result -> result.isError()
                        ? Future.succeededFuture(OperationResult.empty(result.getStatus()))
                        : processUpdateDevice(DeviceKey.from(result.getPayload(), deviceId), device, resourceVersion, span));

    }

    @Override
    public Future<Result<Void>> deleteDevice(final String tenantId, final String deviceId, final Optional<String> resourceVersion, final Span span) {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(resourceVersion);

        return this.tenantInformationService
                .tenantExists(tenantId, span)
                .compose(result -> result.isError()
                        ? Future.succeededFuture(Result.from(result.getStatus()))
                        : processDeleteDevice(DeviceKey.from(result.getPayload(), deviceId), resourceVersion, span));

    }

    @Override
    public Future<Result<Void>> patchDevice(final String tenantId, final List deviceIds, final JsonArray patch, final Span span) {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceIds);
        Objects.requireNonNull(patch);

        return this.tenantInformationService
                .tenantExists(tenantId, span)
                .compose(result -> result.isError()
                        ? Future.succeededFuture(Result.from(result.getStatus()))
                        : processPatchDevice(tenantId, deviceIds, patch, span));

    }
}
