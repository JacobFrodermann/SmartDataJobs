#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

echo "Building SmartUser artifact image..."
docker build -t smartuser-war:local ../SmartUser

echo "Building SmartDataJobs artifact image..."
docker build -t smartdatajobs-war:local .

echo "Building master Payara image and starting services..."
docker compose up --build -d

echo
echo "SmartDataJobs: http://localhost:${SMARTDATAJOBS_HTTP_PORT:-8080}/SmartDataJobs/"
echo "SmartUser:     http://localhost:${SMARTDATAJOBS_HTTP_PORT:-8080}/SmartUser/"
echo "Payara admin:  http://localhost:${SMARTDATAJOBS_ADMIN_PORT:-4848} (admin/admin)"
