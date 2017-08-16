/**
 * Copyright (c) 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial creation
 */

package org.eclipse.hono.adapter.http;

import org.eclipse.hono.service.metric.Metrics;
import org.springframework.stereotype.Component;

/**
 * Metrics for the HTTP based adapters
 */
@Component
public class HttpAdapterMetrics extends Metrics {

    private static final String SERVICE_PREFIX = "hono.http";
    private static final String MESSAGES       = ".messages";

    @Override
    protected String getPrefix() {
        return SERVICE_PREFIX;
    }

    void incrementProcessedHttpMessages(final String clientId) {
        counterService.increment(METER_PREFIX + SERVICE_PREFIX + MESSAGES + normalizeAddress(clientId) + PROCESSED);
    }

    void incrementUndeliverableHttpMessages(final String clientId) {
        counterService.increment(SERVICE_PREFIX + MESSAGES + normalizeAddress(clientId) + UNDELIVERABLE);
    }

}
