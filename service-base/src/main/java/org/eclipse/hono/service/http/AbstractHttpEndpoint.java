/*******************************************************************************
 * Copyright (c) 2016, 2020 Contributors to the Eclipse Foundation
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

package org.eclipse.hono.service.http;

import java.net.HttpURLConnection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.client.ServiceInvocationException;
import org.eclipse.hono.config.ServiceConfigProperties;
import org.eclipse.hono.service.AbstractEndpoint;
import org.eclipse.hono.tracing.TracingHelper;
import org.eclipse.hono.util.Constants;
import org.eclipse.hono.util.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import io.opentracing.Span;
import io.opentracing.tag.Tags;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.DecodeException;
import io.vertx.ext.web.MIMEHeader;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.CorsHandler;


/**
 * Base class for HTTP based Hono endpoints.
 *
 * @param <T> The type of configuration properties this endpoint understands.
 */
public abstract class AbstractHttpEndpoint<T extends ServiceConfigProperties> extends AbstractEndpoint
        implements HttpEndpoint {

    /**
     * The key that is used to put a valid JSON payload to the RoutingContext.
     */
    protected static final String KEY_REQUEST_BODY = "KEY_REQUEST_BODY";
    /**
     * The name of the URI path parameter for the tenant ID.
     */
    protected static final String PARAM_TENANT_ID = "tenant_id";
    /**
     * The name of the URI path parameter for the device ID.
     */
    protected static final String PARAM_DEVICE_ID = "device_id";
    /**
     * The key that is used to put the if-Match ETags values to the RoutingContext.
     */
    protected static final String KEY_RESOURCE_VERSION = "KEY_RESOURCE_VERSION";

    /**
     * The configuration properties for this endpoint.
     */
    protected T config;

    /**
     * Creates an endpoint for a Vertx instance.
     *
     * @param vertx The Vertx instance to use.
     * @throws NullPointerException if vertx is {@code null};
     */
    public AbstractHttpEndpoint(final Vertx vertx) {
        super(vertx);
    }

    /**
     * Sets configuration properties.
     *
     * @param props The properties.
     * @throws NullPointerException if props is {@code null}.
     */
    @Qualifier(Constants.QUALIFIER_REST)
    @Autowired(required = false)
    public final void setConfiguration(final T props) {
        this.config = Objects.requireNonNull(props);
    }

    /**
     * Check the Content-Type of the request to be 'application/json' and extract the payload if this check was
     * successful.
     * <p>
     * The payload is parsed to ensure it is valid JSON and is put to the RoutingContext ctx with the key
     * {@link #KEY_REQUEST_BODY}.
     *
     * @param ctx The routing context to retrieve the JSON request body from.
     * @param payloadExtractor The extractor of the payload from the context.
     */
    protected final void extractRequiredJson(final RoutingContext ctx, final Function<RoutingContext, Object> payloadExtractor) {

        Objects.requireNonNull(payloadExtractor);

        final MIMEHeader contentType = ctx.parsedHeaders().contentType();
        if (contentType == null) {
            ctx.fail(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST, "Missing Content-Type header"));
        } else if ( !(HttpUtils.CONTENT_TYPE_JSON.equalsIgnoreCase(contentType.value()) ||
                    HttpUtils.CONTENT_TYPE_JSON_PATCH.equalsIgnoreCase(contentType.value()))) {
            ctx.fail(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST, "Unsupported Content-Type"));
        } else {
            try {
                if (ctx.getBody() != null) {
                    final var payload = payloadExtractor.apply(ctx);
                    if (payload != null) {
                        ctx.put(KEY_REQUEST_BODY, payload);
                        ctx.next();
                    } else {
                        ctx.fail(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST, "Null body"));
                    }
                } else {
                    ctx.fail(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST, "Empty body"));
                }
            } catch (final DecodeException e) {
                ctx.fail(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST, "Invalid JSON", e));
            }
        }

    }

    /**
     * Check the Content-Type of the request to be 'application/json' and extract the payload if this check was
     * successful.
     * <p>
     * The payload is parsed to ensure it is valid JSON and is put to the RoutingContext ctx with the key
     * {@link #KEY_REQUEST_BODY}.
     *
     * @param ctx The routing context to retrieve the JSON request body from.
     */
    protected final void extractRequiredJsonPayload(final RoutingContext ctx) {
        extractRequiredJson(ctx, RoutingContext::getBodyAsJson);
    }

    /**
     * Extracts JSON payload from a request body, if not empty.
     * <p>
     * This method tries to de-serialize the content of the request body
     * into a {@code JsonObject} and put it to the request context using key
     * {@value #KEY_REQUEST_BODY}.
     * <p>
     * The request is failed with a 400 status code if de-serialization fails, for example
     * because the content is not a valid JSON string.
     *
     * @param ctx The routing context to retrieve the JSON request body from.
     */
    protected final void extractOptionalJsonPayload(final RoutingContext ctx) {

        if (ctx.getBody().length() != 0) {
            extractRequiredJson(ctx, RoutingContext::getBodyAsJson);
        } else {
            ctx.next();
        }
    }

    /**
     * Check the Content-Type of the request to be 'application/json' and extract the payload if this check was
     * successful.
     * <p>
     * The payload is parsed to ensure it is valid JSON and is put to the RoutingContext ctx with the
     * key {@link #KEY_REQUEST_BODY}.
     *
     * @param ctx The routing context to retrieve the JSON request body from.
     */
    protected final void extractRequiredJsonArrayPayload(final RoutingContext ctx) {
        extractRequiredJson(ctx, body -> {
            return body.getBodyAsJsonArray();
        });
    }

    /**
     * Get the tenantId from the standard parameter name {@link #PARAM_TENANT_ID}.
     *
     * @param ctx The routing context of the request.
     * @return The tenantId retrieved from the request.
     * @throws NullPointerException If ctx is null.
     */
    protected final String getTenantParam(final RoutingContext ctx) {
        return ctx.request().getParam(PARAM_TENANT_ID);
    }

    /**
     * Get the deviceId from the standard parameter name {@link #PARAM_DEVICE_ID}.
     *
     * @param ctx The routing context of the request.
     * @return The deviceId retrieved from the request.
     * @throws NullPointerException If ctx is null.
     */
    protected final String getDeviceIdParam(final RoutingContext ctx) {
        return ctx.request().getParam(PARAM_DEVICE_ID);
    }

    /**
     * Gets the value of a request parameter.
     *
     * @param ctx The routing context to get the parameter from.
     * @param paramName The name of the parameter.
     * @param validator A predicate to use for validating the parameter value.
     *                  The predicate may throw an {@code IllegalArgumentException}
     *                  instead of returning {@code false} in order to convey additional
     *                  information about why the test failed.
     * @return A future indicating the outcome of the operation.
     *         If the request does not contain a parameter with the given name, the future will be
     *         <ul>
     *         <li>completed with an empty optional if the <em>optional</em> flag is {@code true}, or</li>
     *         <li>failed with a {@link ClientErrorException} with status 400 if the flag is {@code false}.</li>
     *         </ul>
     *         If the request does contain a parameter with the given name, the future will be
     *         <ul>
     *         <li>failed with a {@link ClientErrorException} with status 400 if a predicate has been
     *         given and the predicate evaluates to {@code false}, or</li>
     *         <li>otherwise be completed with the parameter value.</li>
     *         </ul>
     * @throws NullPointerException If ctx, paramName or validator are {@code null}.
     */
    protected final Future<String> getRequestParameter(
            final RoutingContext ctx,
            final String paramName,
            final Predicate<String> validator) {

        Objects.requireNonNull(ctx);
        Objects.requireNonNull(paramName);
        Objects.requireNonNull(validator);

        final Promise<String> result = Promise.promise();
        final String value = ctx.request().getParam(paramName);

        try {
            if (validator.test(value)) {
                result.complete(value);
            } else {
                result.fail(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST,
                        String.format("request parameter [name: %s, value: %s] failed validation", paramName, value)));
            }
        } catch (final IllegalArgumentException e) {
            result.fail(new ClientErrorException(
                    HttpURLConnection.HTTP_BAD_REQUEST,
                    String.format("request parameter [name: %s, value: %s] failed validation: %s", paramName, value, e.getMessage()),
                    e));
        }
        return result.future();
    }

    /**
     * Fails a request with a given error.
     * <p>
     * This method
     * <ul>
     * <li>logs the error to the given span,</li>
     * <li>sets the span's <em>http.status</em> tag to the HTTP status code corresponding to the error and</li>
     * <li>fails the context with the error.</li>
     * </ul>
     *
     * @param ctx The context to fail the request for.
     * @param error The cause for the failed request.
     * @param span The OpenTracing span to log the error to. The span will be finished after
     *             the request has been failed.
     */
    protected final void failRequest(final RoutingContext ctx, final Throwable error, final Span span) {

        Objects.requireNonNull(ctx);
        Objects.requireNonNull(error);
        Objects.requireNonNull(span);

        final String msg = "error processing request";
        logger.debug(msg, error);
        TracingHelper.logError(span, msg, error);
        Tags.HTTP_STATUS.set(span, ServiceInvocationException.extractStatusCode(error));
        ctx.fail(error);
    }

    /**
     * Check the ETags values from the HTTP if-Match header if they exist,
     * and extract the values if this check was successful
     * <p>
     * The HTTP header "If-Match"is parsed and the values are put to the routingContext ctx with the
     * key {@link #KEY_RESOURCE_VERSION}.
     *
     * @param ctx The routing context of the request.
     */
    protected void extractIfMatchVersionParam(final RoutingContext ctx) {
        final String ifMatchHeader = ctx.request().getHeader(HttpHeaders.IF_MATCH);
        if (! Strings.isNullOrEmpty(ifMatchHeader)) {
                ctx.put(KEY_RESOURCE_VERSION, ifMatchHeader);
        }
        ctx.next();
    }

    /**
     * Creates default CORS handler that allows 'POST', 'GET', 'PUT' and 'DELETE' methods for the specified origin.
     *
     * @param allowedOrigin The allowed origin pattern.
     * @return The handler.
     */
    protected final CorsHandler createDefaultCorsHandler(final String allowedOrigin) {
        return createCorsHandler(allowedOrigin, EnumSet.of(
                HttpMethod.POST,
                HttpMethod.GET,
                HttpMethod.PUT,
                HttpMethod.DELETE)
        );
    }

    /**
     * Creates CORS Handler that allows HTTP methods for the specified origin.
     *
     * @param allowedOrigin The allowed origin pattern.
     * @param methods Set of allowed HTTP methods
     * @return The handler.
     */
    protected final CorsHandler createCorsHandler(final String allowedOrigin, final Set<HttpMethod> methods) {
        return CorsHandler.create(allowedOrigin)
                .allowedMethods(methods)
                .allowedHeader(HttpHeaders.CONTENT_TYPE.toString())
                .allowedHeader(HttpHeaders.AUTHORIZATION.toString())
                .allowedHeader(HttpHeaders.IF_MATCH.toString())
                .exposedHeader(HttpHeaders.ETAG.toString());
    }
}
