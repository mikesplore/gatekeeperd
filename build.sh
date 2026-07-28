#!/bin/bash
set -e

echo "Building Docker image..."
docker build -t gatekeeperd:1.0.0-SNAPSHOT .

echo "Tagging image for Docker Hub..."
docker tag gatekeeperd:1.0.0-SNAPSHOT mikesplore/gatekeeperd:1.0.0-SNAPSHOT

echo "Build complete!"
echo "Image: mikesplore/gatekeeperd:1.0.0-SNAPSHOT"
echo ""
echo "To push, run: ./push.sh"