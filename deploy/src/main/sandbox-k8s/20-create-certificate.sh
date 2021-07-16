#!/bin/bash
#*******************************************************************************
# Copyright (c) 2021 Contributors to the Eclipse Foundation
#
# See the NOTICE file(s) distributed with this work for additional
# information regarding copyright ownership.
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0
#
# SPDX-License-Identifier: EPL-2.0
#*******************************************************************************

# This script takes care to provide a public certificate. It  adds an issuer definition
# for Let's Encrypt and requests a certificate.
# The script expects an email address to be used by Let's Encrypt, as the first argument.
# To create a valid certificate, the second argument must be "production",  otherwise the
# certificate will be requested from the Let's Encrypt Staging API. This is to avoid
# hitting a limit on LE's productive API.

LE_API=${2:-"staging"}

set -ue
EMAIL=$1
SCRIPTPATH="$(cd "$(dirname "$0")" && pwd -P)"

retry() {
  "$@"
  while [ $? -ne 0 ]; do
    sleep 2s
    echo "retrying" "$@"
    "$@"
  done
}

echo ""
echo "Creating configuration..."
LE_CONFIG=$(helm template $SCRIPTPATH/letsencrypt-chart -f $SCRIPTPATH/letsencrypt-chart/values-$LE_API.yaml --set spec.acme.email=$EMAIL)

# checking when cert-manager is ready is tricky: https://cert-manager.io/docs/installation/kubernetes/#verifying-the-installation
# we just retry the following command
echo ""
echo "Adding issuer and certificate for Let's Encrypt API \"$LE_API\"..."
retry echo "$LE_CONFIG" | kubectl apply -f - || true # do not abort although '-e' is set
