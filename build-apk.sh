#!/bin/bash
echo "============================================"
echo " CastBridge - Building APK via Docker"
echo "============================================"
echo ""

# Read .env file
if [ ! -f ".env" ]; then
    echo "ERROR: .env file not found."
    echo ""
    echo "Create it by copying the example:"
    echo "  cp .env.example .env"
    echo ""
    echo "Then edit .env and set your passwords."
    exit 1
fi
source .env

# Create output directory
mkdir -p app-output

# Write secrets to temporary files for Docker BuildKit
SECRETS_DIR=$(mktemp -d)
echo -n "$KEYSTORE_PASSWORD" > "$SECRETS_DIR/KEYSTORE_PASSWORD"
echo -n "$KEY_ALIAS" > "$SECRETS_DIR/KEY_ALIAS"
echo -n "$KEY_PASSWORD" > "$SECRETS_DIR/KEY_PASSWORD"

echo "[1/2] Building Docker image (this may take a few minutes the first time)..."
DOCKER_BUILDKIT=1 docker build \
    --secret id=KEYSTORE_PASSWORD,src="$SECRETS_DIR/KEYSTORE_PASSWORD" \
    --secret id=KEY_ALIAS,src="$SECRETS_DIR/KEY_ALIAS" \
    --secret id=KEY_PASSWORD,src="$SECRETS_DIR/KEY_PASSWORD" \
    -t castbridge-builder .
BUILD_RESULT=$?

# Clean up secret files
rm -rf "$SECRETS_DIR"

if [ $BUILD_RESULT -ne 0 ]; then
    echo ""
    echo "ERROR: Docker build failed. Make sure Docker is running."
    exit 1
fi

echo ""
echo "[2/2] Extracting APK..."
docker run --rm -v "$(pwd)/app-output:/output" castbridge-builder
if [ $? -ne 0 ]; then
    echo ""
    echo "ERROR: Failed to extract APK."
    exit 1
fi

echo ""
echo "============================================"
echo " SUCCESS! APK generated at:"
echo " app-output/CastBridge.apk"
echo ""
echo " To install on your phone:"
echo " 1. Connect phone via USB (enable USB debugging)"
echo " 2. Run: adb install app-output/CastBridge.apk"
echo " OR copy the APK to your phone and install manually"
echo "============================================"
