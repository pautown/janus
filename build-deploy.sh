#!/bin/bash
# Build and deploy MediaDash Android app to connected device

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

USE_EMULATOR=false

# Parse flags
while getopts "e" opt; do
    case $opt in
        e)
            USE_EMULATOR=true
            ;;
        \?)
            echo "Usage: $0 [-e] [device_id]"
            echo "  -e    Deploy to emulator instead of physical device"
            exit 1
            ;;
    esac
done
shift $((OPTIND-1))

echo "=== MediaDash Android Build & Deploy ==="

# Check for connected devices
echo ""
echo "Checking for connected devices..."

if [ "$USE_EMULATOR" = true ]; then
    # Find emulator
    DEVICE_ID=$(adb devices | grep "emulator" | head -1 | awk '{print $1}')
    if [ -z "$DEVICE_ID" ]; then
        echo "ERROR: No emulator running. Start an emulator first."
        exit 1
    fi
    echo "Using emulator: $DEVICE_ID"
else
    # Find physical device (exclude emulators)
    if [ -n "$1" ]; then
        DEVICE_ID="$1"
    else
        DEVICE_ID=$(adb devices | grep -v "List of devices" | grep -v "^$" | grep -v "emulator" | head -1 | awk '{print $1}')
    fi

    if [ -z "$DEVICE_ID" ]; then
        echo "ERROR: No physical device connected. Please connect your phone and enable USB debugging."
        echo "       Or use -e flag to deploy to emulator."
        exit 1
    fi
    echo "Using physical device: $DEVICE_ID"
fi

# Build the app
echo ""
echo "Building debug APK..."
./gradlew assembleDebug

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK_PATH" ]; then
    echo "ERROR: APK not found at $APK_PATH"
    exit 1
fi

echo ""
echo "APK built successfully: $APK_PATH"
echo "Size: $(du -h "$APK_PATH" | cut -f1)"

# Install to device
echo ""
echo "Installing to device $DEVICE_ID..."
adb -s "$DEVICE_ID" install -r "$APK_PATH"

echo ""
echo "=== Done! ==="
echo "MediaDash has been installed. Open the app on your phone."
echo ""
echo "Note: You'll need to grant Notification Listener permission in:"
echo "  Settings > Apps > Special app access > Notification access > MediaDash"
