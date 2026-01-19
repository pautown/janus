#!/bin/bash
# Build, deploy, and debug MediaDash Android app with album art logging
# Targets Motorola Edge 2024 specifically

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Motorola Edge 2024 device ID
DEVICE_ID="ZY22K93WMH"
PACKAGE_NAME="com.mediadash.android"
MAIN_ACTIVITY="com.mediadash.android.ui.MainActivity"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

print_header() {
    echo ""
    echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
    echo -e "${CYAN}  $1${NC}"
    echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
}

print_step() {
    echo -e "${BLUE}▶ $1${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

# Parse flags
SKIP_BUILD=false
LOGS_ONLY=false

while getopts "sl" opt; do
    case $opt in
        s)
            SKIP_BUILD=true
            ;;
        l)
            LOGS_ONLY=true
            ;;
        \?)
            echo "Usage: $0 [-s] [-l]"
            echo "  -s    Skip build, just install and run"
            echo "  -l    Logs only mode (don't build/install, just stream logs)"
            exit 1
            ;;
    esac
done

print_header "MediaDash Album Art Debug Session"

# Check device connection
print_step "Checking for Motorola Edge 2024..."
if ! adb devices | grep -q "$DEVICE_ID"; then
    print_error "Motorola Edge 2024 ($DEVICE_ID) not found!"
    echo ""
    echo "Connected devices:"
    adb devices -l
    exit 1
fi
print_success "Found Motorola Edge 2024 ($DEVICE_ID)"

# Get device info
DEVICE_MODEL=$(adb -s "$DEVICE_ID" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
ANDROID_VERSION=$(adb -s "$DEVICE_ID" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
echo "   Model: $DEVICE_MODEL"
echo "   Android: $ANDROID_VERSION"

if [ "$LOGS_ONLY" = true ]; then
    print_header "Streaming Album Art Logs"
    echo -e "${YELLOW}Press Ctrl+C to stop${NC}"
    echo ""
    adb -s "$DEVICE_ID" logcat -c
    adb -s "$DEVICE_ID" logcat -v time -s ALBUMART:* GattServerManager:I GattServerService:I AlbumArtTransmitter:I
    exit 0
fi

if [ "$SKIP_BUILD" = false ]; then
    # Build the app
    print_header "Building Debug APK"
    print_step "Running Gradle build..."
    ./gradlew assembleDebug --quiet

    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

    if [ ! -f "$APK_PATH" ]; then
        print_error "APK not found at $APK_PATH"
        exit 1
    fi

    APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
    print_success "APK built: $APK_SIZE"
fi

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

# Stop the app if running
print_header "Installing & Launching"
print_step "Stopping existing app instance..."
adb -s "$DEVICE_ID" shell am force-stop "$PACKAGE_NAME" 2>/dev/null || true

# Install the app
print_step "Installing APK..."
adb -s "$DEVICE_ID" install -r "$APK_PATH"
print_success "App installed"

# Clear old logs
print_step "Clearing logcat buffer..."
adb -s "$DEVICE_ID" logcat -c

# Launch the app
print_step "Launching MediaDash..."
adb -s "$DEVICE_ID" shell am start -n "$PACKAGE_NAME/$MAIN_ACTIVITY"
print_success "App launched"

# Wait for app to start
sleep 2

print_header "Streaming Album Art Logs"
echo ""
echo -e "${YELLOW}Watching for album art activity...${NC}"
echo -e "${YELLOW}Press Ctrl+C to stop${NC}"
echo ""
echo "Legend:"
echo -e "  ${GREEN}📥 = Request received from BLE${NC}"
echo -e "  ${BLUE}🔄 = Processing request${NC}"
echo -e "  ${CYAN}📤 = Transmitting album art${NC}"
echo -e "  ${GREEN}✅ = Success${NC}"
echo -e "  ${RED}❌ = Error${NC}"
echo ""
echo "─────────────────────────────────────────────────────────────"

# Stream logs with filtering and colorization
adb -s "$DEVICE_ID" logcat -v time ALBUMART:* GattServerManager:I GattServerService:I AlbumArtTransmitter:I *:S | while read -r line; do
    if echo "$line" | grep -q "📥"; then
        echo -e "${GREEN}$line${NC}"
    elif echo "$line" | grep -q "📤"; then
        echo -e "${CYAN}$line${NC}"
    elif echo "$line" | grep -q "🔄"; then
        echo -e "${BLUE}$line${NC}"
    elif echo "$line" | grep -q "✅"; then
        echo -e "${GREEN}$line${NC}"
    elif echo "$line" | grep -q "❌"; then
        echo -e "${RED}$line${NC}"
    elif echo "$line" | grep -q "⚠️"; then
        echo -e "${YELLOW}$line${NC}"
    else
        echo "$line"
    fi
done
