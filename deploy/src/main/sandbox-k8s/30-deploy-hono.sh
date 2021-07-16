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

# This script deploys Hono with the configuration intended for the Eclipse Foundation's sandbox.
# It requires that the namespace "hono" already exists and that the secret for the certificate is present.

set -ue

SCRIPTPATH="$(cd "$(dirname "$0")" && pwd -P)"

echo ""
echo "Adding the Helm repository for Hono..."
helm repo add eclipse-iot https://eclipse.org/packages/charts
helm repo update

echo ""
echo "Deploying Hono..."
helm install eclipse-hono --dependency-update -f $SCRIPTPATH/hono-values.yml -n hono eclipse-iot/hono --wait --timeout 5m0s
