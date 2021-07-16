/*******************************************************************************
 * Copyright (c) 2019, 2020 Contributors to the Eclipse Foundation
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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.google.common.base.MoreObjects;

/**
 * Provides a unique key for a <em>Tenant</em> resource of Hono's
 * <a href="https://www.eclipse.org/hono/docs/api/management/">Device Registry Management API</a>.
 * It is used for storing and retrieving values from the backend storage and external systems.
 */
public class TenantKey {

    private final String tenantId;
    private final String name;

    private Map<String, Object> properties = new HashMap<>();


    /**
     * Creates a tenant key.
     *
     * @param tenantId The tenant identifier.
     * @param name The tenant name in an external system.
     */
    public TenantKey(final String tenantId, final String name) {
        this.tenantId = tenantId;
        this.name = name;
    }

    /**
     * Gets the tenant identifier.
     *
     * @return The identifier or {@code null} if not set.
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Gets the tenant name in external system.
     * It is used for mapping of the tenant id to the logical name in the external system (if applicable).
     *
     * @return The name or {@code null} if not set.
     */
    public String getName() {
        return name;
    }


    /**
     * Gets the properties of this tenant key.
     *
     * @return An unmodifiable view on the extension properties.
     */
    public Map<String, Object> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    /**
     * Sets the properties for this tenant key.
     * <p>
     * Existing properties are completely replaced by the new properties.
     *
     * @param properties The properties.
     * @return This instance, to allow chained invocations.
     */
    public TenantKey setProperties(final Map<String, Object> properties) {
        this.properties.clear();
        if (properties != null) {
            this.properties.putAll(properties);
        }
        return this;
    }

    /**
     * Get a property from this tenant key.
     *
     * @param key The key of the entry.
     * @return The property if found or {@code null} otherwise.
     * @throws NullPointerException if the key argument is {@code null}.
     */
    public Object getProperty(final String key) {
        Objects.requireNonNull(key);

        return properties.get(key);
    }

    /**
     * Adds a property to this tenant key.
     * <p>
     * If a property already exist for the specified key, the old value is replaced by the specified value.
     *
     * @param key The key of the entry.
     * @param value The value of the entry.
     * @return This instance, to allow chained invocations.
     * @throws NullPointerException if any of the arguments are {@code null}.
     */
    public TenantKey putProperty(final String key, final Object value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        this.properties.put(key, value);
        return this;
    }

    /**
     * Creates a tenant key from tenant identifier.
     *
     * @param tenantId The tenant identifier.
     * @throws NullPointerException if provided tenant identifier is {@code null}.
     * @return The tenant key.
     */
    public static TenantKey from(final String tenantId) {
        Objects.requireNonNull(tenantId);
        return new TenantKey(tenantId, null);
    }

    /**
     * Creates a tenant key from tenant identifier and ad tenant name in external system.
     *
     * @param tenantId The tenant identifier.
     * @param name The tenant name in an external system.
     * @throws NullPointerException if any of the parameters is {@code null}.
     * @return The tenant key.
     */
    public static TenantKey from(final String tenantId, final String name) {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(name);
        return new TenantKey(tenantId, name);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TenantKey)) {
            return false;
        }
        final TenantKey tenantKey = (TenantKey) o;
        return Objects.equals(tenantId, tenantKey.tenantId) &&
                Objects.equals(name, tenantKey.name);
    }


    @Override
    public int hashCode() {
        return Objects.hash(name, tenantId);
    }

    @Override
    public String toString() {
        return toStringHelper().toString();
    }

    private MoreObjects.ToStringHelper toStringHelper() {
        return MoreObjects.toStringHelper(this)
                .add("name", this.name)
                .add("id", this.tenantId);
    }
}
