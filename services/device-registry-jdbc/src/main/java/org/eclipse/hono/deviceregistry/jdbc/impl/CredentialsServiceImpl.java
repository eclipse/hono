/*******************************************************************************
 * Copyright (c) 2020, 2021 Contributors to the Eclipse Foundation
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


package org.eclipse.hono.deviceregistry.jdbc.impl;

import java.net.HttpURLConnection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.hono.deviceregistry.jdbc.config.DeviceServiceProperties;
import org.eclipse.hono.deviceregistry.service.credentials.AbstractCredentialsService;
import org.eclipse.hono.deviceregistry.service.credentials.CredentialKey;
import org.eclipse.hono.deviceregistry.service.tenant.TenantKey;
import org.eclipse.hono.deviceregistry.util.DeviceRegistryUtils;
import org.eclipse.hono.service.base.jdbc.store.device.TableAdapterStore;
import org.eclipse.hono.util.Constants;
import org.eclipse.hono.util.CredentialsConstants;
import org.eclipse.hono.util.CredentialsResult;

import io.opentracing.Span;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Implementation of the <em>Credentials Service</em>.
 */
public class CredentialsServiceImpl extends AbstractCredentialsService {

    private final TableAdapterStore store;
    private final DeviceServiceProperties config;

    /**
     * Create a new instance.
     *
     * @param store The backing service to use.
     * @param properties The configuration properties to use.
     * @throws NullPointerException if any of the parameters are {@code null}.
     */
    public CredentialsServiceImpl(final TableAdapterStore store, final DeviceServiceProperties properties) {
        this.store = Objects.requireNonNull(store);
        this.config = Objects.requireNonNull(properties);
    }

    @Override
    protected Future<CredentialsResult<JsonObject>> processGet(
            final TenantKey tenant,
            final CredentialKey key,
            final JsonObject clientContext,
            final Span span) {

        return this.store.findCredentials(key, span.context())
                .map(r -> {

                    if (r.isEmpty()) {
                        return CredentialsResult.from(HttpURLConnection.HTTP_NOT_FOUND);
                    }

                    final var result = r.get();

                    final var secrets = result.getCredentials()
                            .stream()
                            .map(JsonObject::mapFrom)
                            .filter(filter(key.getType(), key.getAuthId()))
                            .filter(credential -> DeviceRegistryUtils.matchesWithClientContext(credential, clientContext))
                            .flatMap(c -> c.getJsonArray(CredentialsConstants.FIELD_SECRETS)
                                    .stream()
                                    .filter(JsonObject.class::isInstance)
                                    .map(JsonObject.class::cast))
                            .filter(CredentialsServiceImpl::filterSecrets)
                            .collect(Collectors.toList());

                    if (secrets.isEmpty()) {
                        // nothing was left after filtering ... not found
                        return CredentialsResult.from(HttpURLConnection.HTTP_NOT_FOUND);
                    }

                    final var payload = new JsonObject()
                            .put(Constants.JSON_FIELD_DEVICE_ID, result.getDeviceId())
                            .put(CredentialsConstants.FIELD_TYPE, key.getType())
                            .put(CredentialsConstants.FIELD_AUTH_ID, key.getAuthId())
                            .put(CredentialsConstants.FIELD_SECRETS, new JsonArray(secrets));

                    return CredentialsResult.from(
                            HttpURLConnection.HTTP_OK,
                            payload,
                            getCacheDirective(key.getType(), config.getCredentialsTtl().toSeconds()));

                });
    }

    private static boolean filterSecrets(final JsonObject secret) {
        if (secret == null) {
            return false;
        }

        if (!secret.getBoolean(CredentialsConstants.FIELD_ENABLED, true)) {
            return false;
        }

        if (!validTime(secret, CredentialsConstants.FIELD_SECRETS_NOT_BEFORE, Instant::isAfter)) {
            return false;
        }
        if (!validTime(secret, CredentialsConstants.FIELD_SECRETS_NOT_AFTER, Instant::isBefore)) {
            return false;
        }

        return true;
    }

    private static boolean validTime(final JsonObject obj, final String fieldName, final BiFunction<Instant, Instant, Boolean> comparator) {

        final var str = obj.getString(fieldName);
        if (str == null || str.isBlank()) {
            return true;
        }

        final OffsetDateTime dateTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(str, OffsetDateTime::from);

        return comparator.apply(Instant.now(), dateTime.toInstant());

    }

    private static Predicate<JsonObject> filter(final String type, final String authId) {

        Objects.requireNonNull(type);
        Objects.requireNonNull(authId);

        return c -> {

            if (!c.getBoolean(CredentialsConstants.FIELD_ENABLED, true)) {
                return false;
            }

            if (!type.equals(c.getString(CredentialsConstants.FIELD_TYPE))) {
                return false;
            }

            if (!authId.equals(c.getString(CredentialsConstants.FIELD_AUTH_ID))) {
                return false;
            }

            return true;
        };

    }

}
