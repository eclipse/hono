This folder contains scripts and configuration files for setting up a Hono *sandbox* that runs in a Kubernetes cluster with the following properties:

* Clients can register devices for arbitrary tenants using Device Registry's REST interface (requires registering the device itself as well as corresponding credentials).
* Devices can publish telemetry data and events for the tenant they have been registered with using the deployed protocol adapters.
* Devices can subscribe for receiving commands. 
* An AMQP 1.0 based messaging network is provided as well as a Kafka cluster. The messaging type can be configured per tenant. Northbound clients need to use the corresponding API.
* Consumers can receive telemetry data and events for arbitrary tenants using the Telemetry and Event APIs.
* Consumers and devices are required to authenticate with the Dispatch Router and the adapters respectively.

# Deployment

The scripts need to be executed in the order of the numbers in the file names.

* Install Kubernetes with: `./00-install-k3s.sh`. Export the Kubernetes config as an environment variable, 
   e.g., like: `export KUBECONFIG=/etc/rancher/k3s/k3s.yaml`. 
* Deploy the [cert-manager](https://cert-manager.io/): `./10-deploy-cert-manager.sh`.
* Create the namespace "hono": `kubectl create namespace hono`.
* Requesting a public certificate from Let's Encrypt requires an email address that Let's Encrypt uses to contact you
  Please make sure to replace "<your-email-address>" in the following commands by an email address that is actually used.
  Request the certificate with: `./20-create-certificate.sh <your-email-address>`. Verify that the certificate
  has successfully been issued with `kubectl describe certificates hono-eclipseprojects-io -n hono`. When this looks good,
  invoke the script again against the productive Let's Encrypt API: `./20-create-certificate.sh <your-email-address> production`
* Deploy Hono: `./30-deploy-hono.sh`.

**NB:** Let's Encrypt has quite strict rate limits. Therefore the script `./20-create-certificate.sh` does not use
the productive Let's Encrypt API by default. 

# Updating the Let's Encrypt certificate

Cert-Manager automatically renews the public-facing Let's Encrypt certificate.

Once the certificate has been renewed, the Hono installation need to be upgraded in order to pick up the updated certificate:

`sudo helm upgrade eclipse-hono -f hono-values.yml -n hono eclipse-iot/hono`
