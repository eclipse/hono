/*******************************************************************************
 * Copyright (c) 2016, 2019 Contributors to the Eclipse Foundation
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

package org.eclipse.hono.adapter.sigfox.impl;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.adapter.http.AbstractVertxBasedHttpProtocolAdapter;
import org.eclipse.hono.adapter.http.HttpProtocolAdapterProperties;
import org.eclipse.hono.auth.Device;
import org.eclipse.hono.service.auth.device.HonoClientBasedAuthProvider;
import org.eclipse.hono.service.auth.device.UsernamePasswordAuthProvider;
import org.eclipse.hono.service.auth.device.UsernamePasswordCredentials;
import org.eclipse.hono.service.http.HonoBasicAuthHandler;
import org.eclipse.hono.service.http.HonoChainAuthHandler;
import org.eclipse.hono.util.EventConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.BaseEncoding;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.ChainAuthHandler;

/**
 * A Vert.x based Hono protocol adapter for receiving HTTP push messages from and sending commands to the Sigfox
 * backend.
 */
public final class SigfoxProtocolAdapter extends AbstractVertxBasedHttpProtocolAdapter<HttpProtocolAdapterProperties> {

    private static final String SIGFOX_HEADER_PREFIX = "sigfox-";

    private static final String SIGFOX_PROPERTY_PREFIX = "sigfox.";

    private static final Logger LOG = LoggerFactory.getLogger(SigfoxProtocolAdapter.class);

    private HonoClientBasedAuthProvider<UsernamePasswordCredentials> usernamePasswordAuthProvider;

    /**
     * Handle message upload.
     */
    @FunctionalInterface
    private interface UploadHandler {
        void upload(RoutingContext ctx, String tenant, String deviceId, Buffer payload, String contentType);
    }

    /**
     * Sets the provider to use for authenticating devices based on a username and password.
     * <p>
     * If not set explicitly using this method, a {@code UsernamePasswordAuthProvider} will be created during startup.
     *
     * @param provider The provider to use.
     * @throws NullPointerException if provider is {@code null}.
     */
    public void setUsernamePasswordAuthProvider(
            final HonoClientBasedAuthProvider<UsernamePasswordCredentials> provider) {
        this.usernamePasswordAuthProvider = Objects.requireNonNull(provider);
    }


    @Override
    protected String getTypeName() {
        return "hono-sigfox";
    }

    private void setupAuthorization(final Router router) {
        final ChainAuthHandler authHandler = new HonoChainAuthHandler();

        authHandler.append(new HonoBasicAuthHandler(
                Optional.ofNullable(usernamePasswordAuthProvider).orElse(
                        new UsernamePasswordAuthProvider(getCredentialsClientFactory(), getConfig(), tracer)),
                getConfig().getRealm(), tracer));

        router.route().handler(authHandler);
    }

    @Override
    protected void addRoutes(final Router router) {

        setupAuthorization(router);

        router.route("/data/telemetry")
                .method(HttpMethod.GET)
                .method(HttpMethod.POST)
                .method(HttpMethod.PUT)
                .handler(ctx -> dataHandler(ctx, this::uploadTelemetryMessage));

        router.route("/data/event")
                .method(HttpMethod.GET)
                .method(HttpMethod.POST)
                .method(HttpMethod.PUT)
                .handler(ctx -> dataHandler(ctx, this::uploadEventMessage));

        router.errorHandler(500, t -> {
            LOG.warn("Unhandled exception", t);
        });
    }

    protected void dataHandler(final RoutingContext ctx, final UploadHandler uploadHandler) {

        if (!(ctx.user() instanceof Device)) {
            LOG.warn("Not a device");
            return;
        }

        final Device gatewayDevice = (Device) ctx.user();

        final String tenant = gatewayDevice.getTenantId();

        final String deviceId = ctx.queryParams().get("device");
        final Buffer data = decode(ctx.queryParams().get("data"));

        LOG.debug("{} handler - deviceId: {}, data: {}", ctx.request().method(), deviceId, data);

        final String contentType = (data != null) ? CONTENT_TYPE_OCTET_STREAM
                : EventConstants.CONTENT_TYPE_EMPTY_NOTIFICATION;

        uploadHandler.upload(ctx, tenant, deviceId, data, contentType);
    }

    @Override
    protected void customizeDownstreamMessage(final Message downstreamMessage, final RoutingContext ctx) {
        super.customizeDownstreamMessage(downstreamMessage, ctx);

        // pass along all query parameters that start with 'sigfox.'
        // If a key has multiple values, then only one of them will be mapped.

        for (final Map.Entry<String, String> entry : ctx.queryParams()) {
            if (entry.getKey() == null || !entry.getKey().startsWith(SIGFOX_PROPERTY_PREFIX)) {
                continue;
            }
            downstreamMessage.getApplicationProperties().getValue().put(entry.getKey(), entry.getValue());
        }

        // pass along all headers that start with 'sigfox-' and map the prefix to "sigfox."
        // If a key has multiple values, then only one of them will be mapped.

        for (final Map.Entry<String, String> entry : ctx.request().headers()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            key = key.toLowerCase();
            if (!key.startsWith(SIGFOX_HEADER_PREFIX)) {
                continue;
            }
            key = key.substring(SIGFOX_HEADER_PREFIX.length());
            downstreamMessage.getApplicationProperties().getValue().put(SIGFOX_PROPERTY_PREFIX + key, entry.getValue());
        }

    }

    private static Buffer decode(final String data) {
        if (data == null) {
            return Buffer.buffer();
        }
        return Buffer.buffer(BaseEncoding.base16().decode(data.toUpperCase()));
    }

}
