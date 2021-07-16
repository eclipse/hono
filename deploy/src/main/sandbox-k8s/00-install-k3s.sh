#!/bin/sh
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

# This script installs the current version of k3s. It is intended to be only used
# on the Eclipse Foundation's Hono sandbox VM.

set -ue

curl -sfL https://get.k3s.io | sh -s - server --node-name hono.eclipseprojects.io
