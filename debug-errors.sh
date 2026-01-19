#!/bin/bash
# Build, deploy, and debug MediaDash Android app - crash and error logs only
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
MAGENTA='\033[0;35m'
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
ALL_APPS=false

while getopts "sla" opt; do
    case $opt in
        s)
            SKIP_BUILD=true
            ;;
        l)
            LOGS_ONLY=true
            ;;
        a)
            ALL_APPS=true
            ;;
        \?)
            echo "Usage: $0 [-s] [-l] [-a]"
            echo "  -s    Skip build, just install and run"
            echo "  -l    Logs only mode (don't build/install, just stream logs)"
            echo "  -a    Show errors from all apps, not just MediaDash"
            exit 1
            ;;
    esac
done

print_header "MediaDash Error & Crash Debug Session"

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
    print_header "Streaming Error & Crash Logs"
    echo -e "${YELLOW}Press Ctrl+C to stop${NC}"
    echo ""
    adb -s "$DEVICE_ID" logcat -c

    if [ "$ALL_APPS" = true ]; then
        # Show errors from all apps
        adb -s "$DEVICE_ID" logcat -v time *:E *:F AndroidRuntime:E DEBUG:* FATAL:*
    else
        # Show errors only from MediaDash
        adb -s "$DEVICE_ID" logcat -v time --pid=$(adb -s "$DEVICE_ID" shell pidof "$PACKAGE_NAME" 2>/dev/null || echo "0") *:E *:F 2>/dev/null || \
        adb -s "$DEVICE_ID" logcat -v time | grep -E "(${PACKAGE_NAME}|mediadash|MediaDash).*(E/|F/|FATAL|Exception|Error|Crash|ANR)"
    fi
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

# Get the app PID
APP_PID=$(adb -s "$DEVICE_ID" shell pidof "$PACKAGE_NAME" 2>/dev/null | tr -d '\r')

print_header "Streaming Error & Crash Logs"
echo ""
echo -e "${YELLOW}Watching for errors and crashes...${NC}"
echo -e "${YELLOW}Press Ctrl+C to stop${NC}"
echo ""
echo "Legend:"
echo -e "  ${RED}FATAL    = Fatal error / crash${NC}"
echo -e "  ${RED}ERROR    = Error log${NC}"
echo -e "  ${MAGENTA}EXCEPTION = Exception thrown${NC}"
echo -e "  ${YELLOW}ANR      = App Not Responding${NC}"
echo ""
echo "─────────────────────────────────────────────────────────────"

# Stream error logs with filtering and colorization
if [ -n "$APP_PID" ] && [ "$APP_PID" != "0" ]; then
    echo -e "${GREEN}Tracking PID: $APP_PID${NC}"
    echo ""

    adb -s "$DEVICE_ID" logcat -v time *:E *:F AndroidRuntime:E DEBUG:* | while read -r line; do
        # Filter to our app or system crash handlers
        if echo "$line" | grep -qE "(${PACKAGE_NAME}|mediadash|MediaDash|pid.*${APP_PID}|AndroidRuntime|DEBUG|FATAL)"; then
            if echo "$line" | grep -qE "(FATAL|fatal|Fatal)"; then
                echo -e "${RED}[FATAL] $line${NC}"
            elif echo "$line" | grep -qE "(Exception|exception|Throwable)"; then
                echo -e "${MAGENTA}[EXCEPTION] $line${NC}"
            elif echo "$line" | grep -qE "(ANR|anr)"; then
                echo -e "${YELLOW}[ANR] $line${NC}"
            elif echo "$line" | grep -qE "(E/|/E )"; then
                echo -e "${RED}$line${NC}"
            else
                echo "$line"
            fi
        fi
    done
else
    print_warning "Could not get app PID, showing all errors"
    echo ""

    adb -s "$DEVICE_ID" logcat -v time *:E *:F AndroidRuntime:E DEBUG:* | while read -r line; do
        if echo "$line" | grep -qE "(FATAL|fatal|Fatal)"; then
            echo -e "${RED}[FATAL] $line${NC}"
        elif echo "$line" | grep -qE "(Exception|exception|Throwable)"; then
            echo -e "${MAGENTA}[EXCEPTION] $line${NC}"
        elif echo "$line" | grep -qE "(ANR|anr)"; then
            echo -e "${YELLOW}[ANR] $line${NC}"
        else
            echo -e "${RED}$line${NC}"
        fi
    done
fi
