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

# This script deploys the Cert-Manager.

set -ue


echo ""
echo "Adding the Helm repository for cert-manager..."
helm repo add jetstack https://charts.jetstack.io
helm repo update

echo ""
echo "Deploying cert-manager..."
helm install cert-manager jetstack/cert-manager --namespace cert-manager --create-namespace --set installCRDs=true --wait --timeout 5m0s
