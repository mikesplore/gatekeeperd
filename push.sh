#!/bin/bash
set -e

echo "Pushing image to Docker Hub..."
docker push mikesplore/gatekeeperd:1.0.0-SNAPSHOT

echo ""
echo "Push complete!"
echo "Image: mikesplore/gatekeeperd:1.0.0-SNAPSHOT"