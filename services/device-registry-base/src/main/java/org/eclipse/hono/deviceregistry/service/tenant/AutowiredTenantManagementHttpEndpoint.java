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
package org.eclipse.hono.deviceregistry.service.tenant;

import org.eclipse.hono.service.Lifecycle;
import org.eclipse.hono.service.management.tenant.AbstractTenantManagementHttpEndpoint;
import org.eclipse.hono.service.management.tenant.TenantManagementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import io.vertx.core.Promise;
import io.vertx.core.Vertx;

/**
 * An {@code HttpEndpoint} for managing tenant information.
 * <p>
 * This endpoint implements the <em>tenants</em> resources of Hono's
 * <a href="https://www.eclipse.org/hono/docs/api/management/">Device Registry Management API</a>.
 * It receives HTTP requests representing operation invocations and forwards them to the
 * configured {@code TenantManagementService} implementation.
 * The outcome is then returned to the peer in the HTTP response.
 */
public class AutowiredTenantManagementHttpEndpoint extends AbstractTenantManagementHttpEndpoint {

    private TenantManagementService service;

    /**
     * Creates an endpoint for a Vertx instance.
     *
     * @param vertx The Vertx instance to use.
     * @throws NullPointerException if vertx is {@code null};
     */
    @Autowired
    public AutowiredTenantManagementHttpEndpoint(final Vertx vertx) {
        super(vertx);
    }

    @Autowired
    @Qualifier("backend")
    public void setService(final TenantManagementService service) {
        this.service = service;
    }

    @Override
    protected TenantManagementService getService() {
        return this.service;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStart(final Promise<Void> startPromise) {
        if (service instanceof Lifecycle) {
            ((Lifecycle) service).start().onComplete(startPromise);
        } else {
            startPromise.complete();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStop(final Promise<Void> stopPromise) {
        if (service instanceof Lifecycle) {
            ((Lifecycle) service).stop().onComplete(stopPromise);
        } else {
            stopPromise.complete();
        }
    }
}
