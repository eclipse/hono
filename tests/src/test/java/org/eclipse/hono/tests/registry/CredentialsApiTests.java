/**
 * Copyright (c) 2019, 2021 Contributors to the Eclipse Foundation
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


package org.eclipse.hono.tests.registry;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.net.HttpURLConnection;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.security.auth.x500.X500Principal;

import org.eclipse.hono.application.client.DownstreamMessage;
import org.eclipse.hono.client.registry.CredentialsClient;
import org.eclipse.hono.service.management.credentials.CommonCredential;
import org.eclipse.hono.service.management.credentials.Credentials;
import org.eclipse.hono.service.management.credentials.PasswordCredential;
import org.eclipse.hono.service.management.device.Device;
import org.eclipse.hono.service.management.device.DeviceStatus;
import org.eclipse.hono.service.management.tenant.Tenant;
import org.eclipse.hono.tests.IntegrationTestSupport;
import org.eclipse.hono.tests.Tenants;
import org.eclipse.hono.util.AuthenticationConstants;
import org.eclipse.hono.util.CredentialsConstants;
import org.eclipse.hono.util.CredentialsObject;
import org.eclipse.hono.util.EventConstants;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.RegistryManagementConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opentracing.SpanContext;
import io.opentracing.noop.NoopSpan;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SelfSignedCertificate;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxTestContext;

/**
 * Common test cases for the Credentials API.
 *
 */
abstract class CredentialsApiTests extends DeviceRegistryTestBase {

    protected static final Vertx VERTX = Vertx.vertx();

    private static final Logger LOG = LoggerFactory.getLogger(CredentialsApiTests.class);

    /**
     * The (random) tenant ID to use for the tests.
     */
    protected String tenantId;

    private final SpanContext spanContext = NoopSpan.INSTANCE.context();

    /**
     * Registers a random tenant to be used in the test case.
     *
     * @param ctx The vert.x test context.
     */
    @BeforeEach
    public void registerTemporaryTenant(final VertxTestContext ctx) {
        tenantId = getHelper().getRandomTenantId();
        getHelper().registry
                .addTenant(tenantId)
                .onComplete(ctx.completing());
    }

    /**
     * Gets a client for interacting with the Credentials service.
     *
     * @return The client.
     */
    protected abstract CredentialsClient getClient();

    /**
     * Verify that a request to retrieve credentials for a non-existing authentication ID fails with a 404.
     *
     * @param ctx The vert.x test context.
     */
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    @Test
    public void testGetCredentialsFailsForNonExistingAuthId(final VertxTestContext ctx) {

        getClient().get(tenantId, CredentialsConstants.SECRETS_TYPE_HASHED_PASSWORD, "nonExisting", spanContext)
                .onComplete(ctx.failing(t -> {
                    ctx.verify(() -> assertErrorCode(t, HttpURLConnection.HTTP_NOT_FOUND));
                    ctx.completeNow();
                }));
    }

    /**
     * Verifies that the service returns credentials for a given type and authentication ID including the default value
     * for the enabled property.
     *
     * @param ctx The vert.x test context.
     */
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    @Test
    public void testGetCredentialsByTypeAndAuthId(final VertxTestContext ctx) {

        final String deviceId = getHelper().getRandomDeviceId(tenantId);
        final String authId = UUID.randomUUID().toString();
        final String otherAuthId = UUID.randomUUID().toString();
        final List<CommonCredential> credentials = List.of(
                getRandomHashedPasswordCredential(authId),
                getRandomHashedPasswordCredential(otherAuthId));

        getHelper().registry
                .registerDevice(tenantId, deviceId)
                .compose(ok -> getHelper().registry.addCredentials(tenantId, deviceId, credentials))
                .compose(ok -> getClient().get(tenantId, CredentialsConstants.SECRETS_TYPE_HASHED_PASSWORD, otherAuthId, spanContext))
                .onComplete(ctx.succeeding(result -> {
                    ctx.verify(() -> assertStandardProperties(
                            result,
                            deviceId,
                            otherAuthId,
                            CredentialsConstants.SECRETS_TYPE_HASHED_PASSWORD,
                            2));
                    ctx.completeNow();
                }));
    }

    /**
     * Verifies that if no credentials are found and the client context in the Get request contains a serialized X.509
     * certificate, the credentials and device are created (i.e., automatic provisioning is performed).
     * <p>
     * It also verifies that an auto-provisioning event is successfully sent and the device registration
     * is updated accordingly.
     *
     * @param ctx The vert.x test context.
     * @throws CertificateException if the self signed certificate cannot be created.
     * @throws FileNotFoundException if the self signed certificate cannot be read.
     */
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    @Test
    public void testGetCredentialsWithAutoProvisioning(final VertxTestContext ctx)
            throws CertificateException, FileNotFoundException {

        // GIVEN a tenant with auto-provisioning enabled and a client context that contains a client certificate
        // while device has not been registered and no credentials are stored yet
        final X509Certificate cert = createCertificate();
        final var tenant = Tenants.createTenantForTrustAnchor(cert);
        tenant.getTrustedCertificateAuthorities().get(0).setAutoProvisioningEnabled(true);

        testAutoProvisioningSucceeds(ctx, tenant, cert, false, null);
    }

    /**
     * Verifies when no credentials are found and if the properties related to auto-provisioning of devices are 
     * enabled, the device id template is configured in the corresponding tenant's CA entry and the client context 
     * contains a serialized X.509 certificate then a device is auto-provisioned.
     * (i.e A device is registered and it's corresponding credentials are stored).
     * <p>
     * Also verify that the device is auto-provisioned with a device id generated in accordance
     * with the configured device id template.
     * <p>
     * It also verifies that an auto-provisioning event is successfully sent and the device registration
     * is updated accordingly.
     *
     * @param ctx The vert.x test context.
     * @throws CertificateException if the self signed certificate cannot be created.
     * @throws FileNotFoundException if the self signed certificate cannot be read.
     */
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    @Test
    public void testGetCredentialsWithDeviceAutoProvisioningUsingDeviceIdTemplate(final VertxTestContext ctx)
            throws CertificateException, FileNotFoundException {

        // GIVEN a tenant's trusted CA entry with auto-provisioning enabled
        // while device has not been registered and no credentials are stored yet
        final X509Certificate cert = createCertificate();
        final var tenant = Tenants.createTenantForTrustAnchor(cert);
        tenant.getTrustedCertificateAuthorities()
                .get(0)
                .setAutoProvisioningEnabled(true)
                .setAutoProvisioningDeviceIdTemplate("test-device-{{subject-dn}}");
        final String expectedDeviceId = "test-device-" + cert.getSubjectX500Principal().getName(X500Principal.RFC2253);

        testAutoProvisioningSucceeds(ctx, tenant, cert, false, expectedDeviceId);
    }

    /**
     * Verifies when no credentials are found, the properties related to auto-provisioning of gateways are enabled
     * in the corresponding tenant's CA entry and the client context contains a serialized X.509 certificate then 
     * a gateway is auto-provisioned. (i.e A gateway is registered and it's corresponding credentials are stored). 
     * <p>
     * It also verifies that an auto-provisioning event is successfully sent and the device registration
     * is updated accordingly.
     *
     * @param ctx The vert.x test context.
     * @throws CertificateException if the self signed certificate cannot be created.
     * @throws FileNotFoundException if the self signed certificate cannot be read.
     */
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    @Test
    public void testGetCredentialsWithGatewayAutoProvisioning(final VertxTestContext ctx)
            throws CertificateException, FileNotFoundException {

        // GIVEN a tenant's trusted CA entry with auto-provisioning and auto-provision as gateway enabled
        // while device has not been registered and no credentials are stored yet
        final X509Certificate cert = createCertificate();
        final var tenant = Tenants.createTenantForTrustAnchor(cert);
        tenant.getTrustedCertificateAuthorities()
                .get(0)
                .setAutoProvisioningEnabled(true)
                .setAutoProvisioningAsGatewayEnabled(true);

        testAutoProvisioningSucceeds(ctx, tenant, cert, true, null);
    }

    /**
     * Verifies when no credentials are found and if the properties related to auto-provisioning of gateways are 
     * enabled, device id template is configured in the corresponding tenant's CA entry and the client context 
     * contains a serialized X.509 certificate then a gateway is auto-provisioned.
     * (i.e A gateway is registered and it's corresponding credentials are stored).
     * <p>
     * Also verify that the gateway is auto-provisioned with a device id generated in accordance
     * with the configured device id template.
     * <p>
     * It also verifies that an auto-provisioning event is sent successfully sent and the device registration's
     * property {@value RegistryManagementConstants#FIELD_AUTO_PROVISIONING_NOTIFICATION_SENT} is updated to
     * {@code true}.
     *
     * @param ctx The vert.x test context.
     * @throws CertificateException if the self signed certificate cannot be created.
     * @throws FileNotFoundException if the self signed certificate cannot be read.
     */
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    @Test
    public void testGetCredentialsWithGatewayAutoProvisioningUsingDeviceIdTemplate(final VertxTestContext ctx)
            throws CertificateException, FileNotFoundException {

        // GIVEN a tenant's trusted CA entry with auto-provisioning and auto-provision as gateway enabled
        // while gateway has not been registered and no credentials are stored yet
        final X509Certificate cert = createCertificate();
        final var tenant = Tenants.createTenantForTrustAnchor(cert);
        tenant.getTrustedCertificateAuthorities()
                .get(0)
                .setAutoProvisioningEnabled(true)
                .setAutoProvisioningAsGatewayEnabled(true)
                .setAutoProvisioningDeviceIdTemplate("test-device-{{subject-cn}}");
        final String expectedDeviceId = "test-device-"
                + AuthenticationConstants.getCommonName(cert.getSubjectX500Principal().getName(X500Principal.RFC2253));

        testAutoProvisioningSucceeds(ctx, tenant, cert, true, expectedDeviceId);
    }

    private void testAutoProvisioningSucceeds(
            final VertxTestContext ctx,
            final Tenant tenant,
            final X509Certificate cert,
            final boolean isGateway,
            final String expectedDeviceId) throws CertificateEncodingException {

        final Checkpoint autoProvisioningEventReceived = ctx.checkpoint(1);
        final Checkpoint autoProvisioningCompleted = ctx.checkpoint(1);

        // GIVEN a client context that contains a client certificate
        final JsonObject clientCtx = new JsonObject().put(CredentialsConstants.FIELD_CLIENT_CERT, cert.getEncoded());
        final String authId = cert.getSubjectX500Principal().getName(X500Principal.RFC2253);

        tenantId = getHelper().getRandomTenantId();
        getHelper().applicationClient.createEventConsumer(
                tenantId,
                msg -> ctx.verify(() -> {
                    // VERIFY that the auto-provisioning event for the device has been received
                    verifyAutoProvisioningEventNotification(tenantId, expectedDeviceId, msg);
                    autoProvisioningEventReceived.flag();
                }),
                close -> {})
            .compose(ok -> getHelper().registry.addTenant(tenantId, tenant))
            .compose(ok -> getClient()
                    // WHEN getting credentials
                    .get(tenantId, CredentialsConstants.SECRETS_TYPE_X509_CERT, authId, clientCtx, spanContext))
            .compose(result -> {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("received get Credentials result from Credentials service:{}{}",
                            System.lineSeparator(), JsonObject.mapFrom(result).encodePrettily());
                }
                // VERIFY the newly created credentials
                ctx.verify(() -> {
                    assertThat(result).isNotNull();
                    assertThat(result.isEnabled()).isTrue();
                    assertThat(result.getDeviceId()).isNotNull();
                    assertThat(result.getAuthId()).isEqualTo(authId);
                    assertThat(result.getType()).isEqualTo(CredentialsConstants.SECRETS_TYPE_X509_CERT);
                    assertThat(result.getSecrets()).isNotNull();
                    assertThat(result.getSecrets()).hasSize(1);

                    if (expectedDeviceId != null) {
                        // VERIFY the generated device-id
                        assertThat(result.getDeviceId()).isEqualTo(expectedDeviceId);
                    }
                });
                // WHEN getting device registration information
                return getHelper().registry.getRegistrationInfo(tenantId, result.getDeviceId());
            })
            .onComplete(ctx.succeeding(result -> {
                ctx.verify(() -> {
                    final JsonObject resultBody = result.bodyAsJsonObject();
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("received get Device result from Registry Management API:{}{}",
                                System.lineSeparator(), resultBody.encodePrettily());
                    }
                    // VERIFY that the device/gateway has been registered as well
                    final Device device = resultBody.mapTo(Device.class);
                    assertThat(device.isEnabled()).isTrue();
                    if (isGateway) {
                        // VERIFY that the gateway related attributes are set
                        assertThat(device.getAuthorities())
                                .contains(RegistryManagementConstants.AUTHORITY_AUTO_PROVISIONING_ENABLED);
                    }
                    // VERIFY that the property "auto-provisioning-notification-sent" is updated to true.
                    final DeviceStatus deviceStatus = resultBody
                            .getJsonObject(RegistryManagementConstants.FIELD_STATUS)
                            .mapTo(DeviceStatus.class);
                    assertWithMessage("device auto-provisioned")
                            .that(deviceStatus.isAutoProvisioned())
                            .isTrue();
                    assertWithMessage("auto-provisioning notification for device sent")
                            .that(deviceStatus.isAutoProvisioningNotificationSent())
                            .isTrue();
                });
                autoProvisioningCompleted.flag();
            }));
    }

    private void verifyAutoProvisioningEventNotification(
            final String expectedTenantId,
            final String expectedDeviceId,
            final DownstreamMessage<?> msg) {

        assertThat(msg.getContentType())
                .isEqualTo(EventConstants.CONTENT_TYPE_DEVICE_PROVISIONING_NOTIFICATION);
        assertThat(msg.getProperties().getProperty(MessageHelper.APP_PROPERTY_REGISTRATION_STATUS, String.class))
                .isEqualTo(EventConstants.RegistrationStatus.NEW.name());
        assertThat(msg.getTenantId()).isEqualTo(expectedTenantId);
        assertThat(msg.getDeviceId()).isNotNull();
        if (expectedDeviceId != null) {
            assertThat(msg.getDeviceId()).isEqualTo(expectedDeviceId);
        }
    }

    private X509Certificate createCertificate() throws CertificateException, FileNotFoundException {
        final SelfSignedCertificate ssc = SelfSignedCertificate.create(UUID.randomUUID().toString());
        final CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(new FileInputStream(ssc.certificatePath()));
    }

    /**
     * Verifies that the service fails when the credentials set is disabled.
     *
     * @param ctx The vert.x test context.
     */
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    @Test
    public void testGetCredentialsFailsForDisabledCredentials(final VertxTestContext ctx) {

        final String deviceId = getHelper().getRandomDeviceId(tenantId);
        final String authId = UUID.randomUUID().toString();

        final CommonCredential credential = getRandomHashedPasswordCredential(authId);
        credential.setEnabled(false);

        getHelper().registry
                .registerDevice(tenantId, deviceId)
                .compose(ok -> getHelper().registry.addCredentials(tenantId, deviceId, Collections.singleton(credential)))
                .compose(ok -> getClient().get(tenantId, CredentialsConstants.SECRETS_TYPE_HASHED_PASSWORD, authId, spanContext))
                .onComplete(ctx.failing(t -> {
                    ctx.verify(() -> assertErrorCode(t, HttpURLConnection.HTTP_NOT_FOUND));
                    ctx.completeNow();
                }));
    }

    /**
     * Verifies that the service returns credentials for a given type, authentication ID and matching client context.
     *
     * @param ctx The vert.x test context.
     */
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    @Test
    public void testGetCredentialsByClientContext(final VertxTestContext ctx) {

        final String deviceId = getHelper().getRandomDeviceId(tenantId);
        final String authId = UUID.randomUUID().toString();
        final CommonCredential credentials = getRandomHashedPasswordCredential(authId)
                .putExtension("client-id", "gateway-one");

        final JsonObject clientContext = new JsonObject()
                .put("client-id", "gateway-one");

        getHelper().registry.registerDevice(tenantId, deviceId)
            .compose(httpResponse -> getHelper().registry.addCredentials(tenantId, deviceId, List.of(credentials)))
            .compose(ok -> getClient().get(tenantId, CredentialsConstants.SECRETS_TYPE_HASHED_PASSWORD, authId, clientContext, spanContext))
            .onComplete(ctx.succeeding(result -> {
                ctx.verify(() -> {
                    assertStandardProperties(
                            result,
                            deviceId,
                            authId,
                            CredentialsConstants.SECRETS_TYPE_HASHED_PASSWORD,
                            2);
                });
                ctx.completeNow();
        }));
    }

    /**
     * Verifies that a request for credentials using a client context that does not match
     * the credentials on record fails with a 404.
     *
     * @param ctx The vert.x test context.
     */
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    @Test
    public void testGetCredentialsFailsForNonMatchingClientContext(final VertxTestContext ctx) {

        final String deviceId = getHelper().getRandomDeviceId(tenantId);
        final String authId = UUID.randomUUID().toString();
        final CommonCredential credentials = getRandomHashedPasswordCredential(authId)
                .putExtension("client-id", UUID.randomUUID().toString());

        final JsonObject clientContext = new JsonObject()
                .put("client-id", "non-matching");

        getHelper().registry.registerDevice(tenantId, deviceId)
            .compose(httpResponse -> getHelper().registry.addCredentials(tenantId, deviceId, List.of(credentials)))
            .compose(ok -> getClient().get(tenantId, CredentialsConstants.SECRETS_TYPE_HASHED_PASSWORD, authId, clientContext, spanContext))
            .onComplete(ctx.failing(t -> {
                ctx.verify(() -> assertErrorCode(t, HttpURLConnection.HTTP_NOT_FOUND));
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that a request for credentials using a client context succeeds if the credentials on record
     * do not have any extension properties with keys matching the provided client context.
     *
     * @param ctx The vert.x test context.
     */
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    @Test
    public void testGetCredentialsSucceedsForNonExistingClientContext(final VertxTestContext ctx) {

        final String deviceId = getHelper().getRandomDeviceId(tenantId);
        final String authId = UUID.randomUUID().toString();
        final CommonCredential credentials = getRandomHashedPasswordCredential(authId)
                .putExtension("other", "property");

        final JsonObject clientContext = new JsonObject()
                .put("client-id", "gateway-one");

        getHelper().registry
            .registerDevice(tenantId, deviceId)
            .compose(httpResponse -> getHelper().registry.addCredentials(tenantId, deviceId, List.of(credentials)))
            .compose(httpResponse -> getClient().get(tenantId, CredentialsConstants.SECRETS_TYPE_HASHED_PASSWORD, authId, clientContext, spanContext))
            .onComplete(ctx.succeeding(credentialsObject -> {
                ctx.verify(() -> {
                    assertThat(credentialsObject.getSecrets()).isNotEmpty();
                });
                ctx.completeNow();
            }));
    }

    private static CommonCredential getRandomHashedPasswordCredential(final String authId) {

        final var secret1 = Credentials.createPasswordSecret("ClearTextPWD",
                OptionalInt.of(IntegrationTestSupport.MAX_BCRYPT_COST_FACTOR));
        secret1.setNotBefore(Instant.parse("2017-05-01T14:00:00Z"));
        secret1.setNotAfter(Instant.parse("2037-06-01T14:00:00Z"));

        final var secret2 = Credentials.createPasswordSecret("hono-password",
                OptionalInt.of(IntegrationTestSupport.MAX_BCRYPT_COST_FACTOR));

        return new PasswordCredential(authId, List.of(secret1, secret2));
    }

    private static void assertStandardProperties(
            final CredentialsObject credentials,
            final String expectedDeviceId,
            final String expectedAuthId,
            final String expectedType,
            final int expectedNumberOfSecrets) {

        assertThat(credentials).isNotNull();
        // only enabled credentials must be returned
        assertThat(credentials.isEnabled()).isTrue();
        assertThat(credentials.getDeviceId()).isEqualTo(expectedDeviceId);
        assertThat(credentials.getAuthId()).isEqualTo(expectedAuthId);
        assertThat(credentials.getType()).isEqualTo(expectedType);
        assertThat(credentials.getSecrets()).isNotNull();
        assertThat(credentials.getSecrets()).hasSize(expectedNumberOfSecrets);

    }

}
