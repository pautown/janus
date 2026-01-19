#!/bin/bash
# Build, deploy, and debug MediaDash Android app - lyrics data flow logs
# Tracks BLE command flow between golang_ble_client and MediaDash Android
#
# Shows: Lyrics requests, LRCLIB API calls, cache operations, and BLE transmission

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
WHITE='\033[1;37m'
GRAY='\033[0;90m'
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

show_usage() {
    echo "Usage: $0 [-s] [-l] [-v] [-g] [-h]"
    echo ""
    echo "Options:"
    echo "  -s    Skip build, just install and run"
    echo "  -l    Logs only mode (don't build/install, just stream logs)"
    echo "  -v    Verbose mode (show all lyrics logs, not just key events)"
    echo "  -g    Also show Go client logs (requires SSH to CarThing)"
    echo "  -h    Show this help message"
    echo ""
    echo "Log Tags Monitored:"
    echo "  LYRICS        - High-level lyrics operations (requests, responses)"
    echo "  GattServer*   - BLE command reception and transmission"
    echo "  LyricsManager - Lyrics fetching and caching"
    echo "  LyricsApi*    - LRCLIB API calls"
    echo ""
    echo "Examples:"
    echo "  $0           # Full build, deploy, and debug"
    echo "  $0 -l        # Just stream lyrics logs"
    echo "  $0 -l -v     # Verbose lyrics logs"
    echo "  $0 -l -g     # Stream logs with Go client output"
}

# Parse flags
SKIP_BUILD=false
LOGS_ONLY=false
VERBOSE=false
SHOW_GO=false

while getopts "slvgh" opt; do
    case $opt in
        s)
            SKIP_BUILD=true
            ;;
        l)
            LOGS_ONLY=true
            ;;
        v)
            VERBOSE=true
            ;;
        g)
            SHOW_GO=true
            ;;
        h)
            show_usage
            exit 0
            ;;
        \?)
            show_usage
            exit 1
            ;;
    esac
done

print_header "MediaDash Lyrics Debug Session"

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

# Function to colorize lyrics logs
colorize_lyrics_logs() {
    while read -r line; do
        # Request events (incoming from BLE or internal triggers)
        if echo "$line" | grep -qiE "(lyrics.*request.*received|request.*lyrics|fetch.*lyrics.*request|getLyrics)"; then
            echo -e "${MAGENTA}$line${NC}"
        # API fetch events (LRCLIB calls)
        elif echo "$line" | grep -qiE "(lrclib|api.*call|fetching.*lyrics|http.*lyrics|network.*lyrics)"; then
            echo -e "${BLUE}$line${NC}"
        # Cache events
        elif echo "$line" | grep -qiE "(cache.*hit|cache.*miss|cached.*lyrics|lyrics.*cache|from.*cache|storing.*cache)"; then
            echo -e "${CYAN}$line${NC}"
        # BLE transmission events
        elif echo "$line" | grep -qiE "(sending.*lyrics|transmit.*lyrics|notify.*lyrics|chunk|ble.*lyrics|lyrics.*ble)"; then
            echo -e "${GREEN}$line${NC}"
        # Success events
        elif echo "$line" | grep -qiE "(success|complete|found.*lyrics|lyrics.*found)"; then
            echo -e "${GREEN}$line${NC}"
        # Warning events
        elif echo "$line" | grep -qiE "(warning|not found|no.*lyrics|lyrics.*not.*found|unavailable)"; then
            echo -e "${YELLOW}$line${NC}"
        # Error events
        elif echo "$line" | grep -qiE "(error|failed|exception|timeout)"; then
            echo -e "${RED}$line${NC}"
        # BLE command events
        elif echo "$line" | grep -qiE "(command.*received|processing.*command|characteristic.*write)"; then
            echo -e "${WHITE}$line${NC}"
        # Data/timing info
        elif echo "$line" | grep -qiE "([0-9]+\s*(ms|bytes|lines|chars)|duration|timestamp)"; then
            echo -e "${GRAY}$line${NC}"
        # Track info being searched
        elif echo "$line" | grep -qiE "(track|artist|album|title|song)"; then
            echo -e "${WHITE}$line${NC}"
        else
            echo "$line"
        fi
    done
}

# Function to stream logs
stream_logs() {
    local filter_level="$1"

    if [ "$VERBOSE" = true ]; then
        # Verbose: All lyrics-related tags at verbose level
        adb -s "$DEVICE_ID" logcat -v time \
            LYRICS:V \
            GattServerService:V \
            GattServerManager:V \
            LyricsManager:V \
            LyricsApiService:V \
            LyricsRepository:V \
            *:S | colorize_lyrics_logs
    else
        # Normal: Key lyrics events only
        adb -s "$DEVICE_ID" logcat -v time \
            LYRICS:I \
            GattServerService:D \
            GattServerManager:D \
            LyricsManager:D \
            LyricsApiService:D \
            *:S | colorize_lyrics_logs
    fi
}

# Function to also show Go client logs (via SSH)
stream_with_go_logs() {
    echo -e "${YELLOW}Starting dual log stream (Android + Go client)...${NC}"
    echo -e "${YELLOW}Note: Go client logs require SSH access to CarThing (172.16.42.2)${NC}"
    echo ""

    # Start Android logs in background
    stream_logs &
    ANDROID_PID=$!

    # Try to get Go client logs via SSH
    if command -v sshpass &> /dev/null; then
        echo -e "${CYAN}[GO CLIENT]${NC} Attempting SSH connection to CarThing..."
        sshpass -p nocturne ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 \
            root@172.16.42.2 "tail -f /tmp/llizard_ble_status.json 2>/dev/null || echo 'No status file'" &
        GO_PID=$!

        # Cleanup on exit
        trap "kill $ANDROID_PID $GO_PID 2>/dev/null" EXIT
        wait
    else
        echo -e "${YELLOW}sshpass not installed - showing Android logs only${NC}"
        echo -e "${GRAY}Install with: sudo apt install sshpass${NC}"
        wait $ANDROID_PID
    fi
}

if [ "$LOGS_ONLY" = true ]; then
    print_header "Streaming Lyrics Data Flow Logs"
    echo -e "${YELLOW}Press Ctrl+C to stop${NC}"
    echo ""
    adb -s "$DEVICE_ID" logcat -c

    echo "Legend:"
    echo -e "  ${MAGENTA}REQUEST   = Incoming lyrics request${NC}"
    echo -e "  ${BLUE}API       = LRCLIB API fetch operations${NC}"
    echo -e "  ${CYAN}CACHE     = Cache hit/miss events${NC}"
    echo -e "  ${GREEN}TRANSMIT  = BLE lyrics transmission${NC}"
    echo -e "  ${WHITE}TRACK     = Track/artist info being searched${NC}"
    echo -e "  ${YELLOW}WARNING   = Lyrics not found or unavailable${NC}"
    echo -e "  ${RED}ERROR     = Errors and failures${NC}"
    echo ""
    echo "─────────────────────────────────────────────────────────────"

    if [ "$SHOW_GO" = true ]; then
        stream_with_go_logs
    else
        stream_logs
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

print_header "Streaming Lyrics Data Flow Logs"
echo ""
echo -e "${YELLOW}Watching for lyrics data flow events...${NC}"
echo -e "${YELLOW}Press Ctrl+C to stop${NC}"
echo ""
echo "Legend:"
echo -e "  ${MAGENTA}REQUEST   = Incoming lyrics request${NC}"
echo -e "  ${BLUE}API       = LRCLIB API fetch operations${NC}"
echo -e "  ${CYAN}CACHE     = Cache hit/miss events${NC}"
echo -e "  ${GREEN}TRANSMIT  = BLE lyrics transmission${NC}"
echo -e "  ${WHITE}TRACK     = Track/artist info being searched${NC}"
echo -e "  ${YELLOW}WARNING   = Lyrics not found or unavailable${NC}"
echo -e "  ${RED}ERROR     = Errors and failures${NC}"
echo ""
echo "─────────────────────────────────────────────────────────────"

if [ -n "$APP_PID" ] && [ "$APP_PID" != "0" ]; then
    echo -e "${GREEN}Tracking PID: $APP_PID${NC}"
    echo ""
fi

if [ "$SHOW_GO" = true ]; then
    stream_with_go_logs
else
    stream_logs
fi
