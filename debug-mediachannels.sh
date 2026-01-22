#!/bin/bash
# Build, deploy, and debug MediaDash Android app - media channels data flow logs
# Tracks BLE command flow for media channel list requests between golang_ble_client and MediaDash Android
#
# Shows: Media channel requests, channel list responses, BLE transmission

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
    echo "Usage: $0 [-s] [-l] [-v] [-g] [-r]"
    echo ""
    echo "Options:"
    echo "  -s    Skip build, just install and run"
    echo "  -l    Logs only mode (don't build/install, just stream logs)"
    echo "  -v    Verbose mode (show all media channel logs, not just key events)"
    echo "  -g    Also show Go client logs (requires SSH to CarThing)"
    echo "  -r    Send test request via Redis (requires SSH to CarThing)"
    echo ""
    echo "Log Tags Monitored:"
    echo "  MEDIA_CHANNELS  - Media channel operations"
    echo "  GattServer*     - BLE command reception and transmission"
    echo "  MediaRepo*      - Media data access"
    echo ""
    echo "Examples:"
    echo "  $0           # Full build, deploy, and debug"
    echo "  $0 -l        # Just stream media channel logs"
    echo "  $0 -l -v     # Verbose media channel logs"
    echo "  $0 -l -g     # Stream logs with Go client output"
    echo "  $0 -r        # Send test request from CarThing"
}

# Parse flags
SKIP_BUILD=false
LOGS_ONLY=false
VERBOSE=false
SHOW_GO=false
SEND_REQUEST=false

while getopts "slvghr" opt; do
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
        r)
            SEND_REQUEST=true
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

print_header "MediaDash Media Channels Debug Session"

# Check device connection
print_step "Checking for Motorola Edge 2024..."
if ! adb devices | grep -q "$DEVICE_ID"; then
    print_warning "Motorola Edge 2024 ($DEVICE_ID) not found, trying any device..."
    DEVICE_ID=$(adb devices | grep -v "List of devices" | grep -v "^$" | grep -v "emulator" | head -1 | awk '{print $1}')
    if [ -z "$DEVICE_ID" ]; then
        print_error "No Android device found!"
        echo ""
        echo "Connected devices:"
        adb devices -l
        exit 1
    fi
fi
print_success "Using device: $DEVICE_ID"

# Get device info
DEVICE_MODEL=$(adb -s "$DEVICE_ID" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
ANDROID_VERSION=$(adb -s "$DEVICE_ID" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
echo "   Model: $DEVICE_MODEL"
echo "   Android: $ANDROID_VERSION"

# Function to colorize media channel logs
colorize_channel_logs() {
    while read -r line; do
        # Request events (incoming from BLE)
        if echo "$line" | grep -qiE "(request_media_channels|media.*channel.*request|getMediaChannels)"; then
            echo -e "${MAGENTA}[REQUEST] $line${NC}"
        # Response/transmission events
        elif echo "$line" | grep -qiE "(sending.*channel|transmit.*channel|notify.*channel|channel.*response|returning.*channel|writeCharacteristic.*channel)"; then
            echo -e "${GREEN}[RESPONSE] $line${NC}"
        # Channel list data
        elif echo "$line" | grep -qiE "(channel.*list|channels.*found|channel.*count|audio.*app|media.*session)"; then
            echo -e "${WHITE}[DATA] $line${NC}"
        # Binary encoding
        elif echo "$line" | grep -qiE "(binary|encode|byte|chunk|packet)"; then
            echo -e "${BLUE}[BINARY] $line${NC}"
        # Success events
        elif echo "$line" | grep -qiE "(success|complete|sent)"; then
            echo -e "${GREEN}$line${NC}"
        # Warning events
        elif echo "$line" | grep -qiE "(warning|no.*channel|empty|not found)"; then
            echo -e "${YELLOW}$line${NC}"
        # Error events
        elif echo "$line" | grep -qiE "(error|failed|exception|null)"; then
            echo -e "${RED}$line${NC}"
        # BLE command events
        elif echo "$line" | grep -qiE "(command.*received|processing.*command|characteristic.*write|onCharacteristic)"; then
            echo -e "${CYAN}[BLE] $line${NC}"
        # Spotify/app detection
        elif echo "$line" | grep -qiE "(spotify|youtube|music|player|session)"; then
            echo -e "${BLUE}$line${NC}"
        else
            echo "$line"
        fi
    done
}

# Function to stream logs
stream_logs() {
    if [ "$VERBOSE" = true ]; then
        # Verbose: All related tags at verbose level
        adb -s "$DEVICE_ID" logcat -v time \
            MEDIA_CHANNELS:V \
            MediaChannels:V \
            GattServerService:V \
            GattServerManager:V \
            MediaRepositoryImpl:V \
            MediaSession:V \
            AudioApps:V \
            *:S | colorize_channel_logs
    else
        # Normal: Key events only
        adb -s "$DEVICE_ID" logcat -v time \
            MEDIA_CHANNELS:I \
            MediaChannels:D \
            GattServerService:D \
            GattServerManager:D \
            MediaRepositoryImpl:D \
            *:S | colorize_channel_logs
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
        sshpass -p llizardos ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 \
            root@172.16.42.2 "journalctl -f -u mediadash-client 2>/dev/null | grep -iE '(channel|media_channels)'" &
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

# Function to send test request via Redis
send_test_request() {
    print_header "Sending Test Media Channels Request"

    if ! command -v sshpass &> /dev/null; then
        print_error "sshpass not installed. Install with: sudo apt install sshpass"
        exit 1
    fi

    print_step "Connecting to CarThing..."

    # Send request_media_channels command via Redis
    TIMESTAMP=$(date +%s)
    CMD="{\"action\":\"request_media_channels\",\"value\":0,\"timestamp\":$TIMESTAMP}"

    print_step "Sending command: $CMD"

    sshpass -p llizardos ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 \
        root@172.16.42.2 "redis-cli RPUSH system:playback_cmd_q '$CMD'" && \
        print_success "Command sent to Redis queue" || \
        print_error "Failed to send command"

    echo ""
    print_step "Checking Redis for response (media:channels key)..."
    sleep 2

    sshpass -p llizardos ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 \
        root@172.16.42.2 "redis-cli GET media:channels" | python3 -m json.tool 2>/dev/null || \
        echo "(No data yet or invalid JSON)"

    echo ""
    print_success "Test request sent. Watch logs for response."
}

# Handle test request mode
if [ "$SEND_REQUEST" = true ]; then
    send_test_request

    if [ "$LOGS_ONLY" = false ]; then
        echo ""
        print_step "Now streaming logs to see the response..."
        echo ""
        adb -s "$DEVICE_ID" logcat -c
        stream_logs
    fi
    exit 0
fi

if [ "$LOGS_ONLY" = true ]; then
    print_header "Streaming Media Channels Logs"
    echo -e "${YELLOW}Press Ctrl+C to stop${NC}"
    echo ""
    adb -s "$DEVICE_ID" logcat -c

    echo "Legend:"
    echo -e "  ${MAGENTA}REQUEST   = Incoming channel list request from BLE${NC}"
    echo -e "  ${GREEN}RESPONSE  = Outgoing channel list via BLE${NC}"
    echo -e "  ${WHITE}DATA      = Channel list data${NC}"
    echo -e "  ${BLUE}BINARY    = Binary encoding operations${NC}"
    echo -e "  ${CYAN}BLE       = BLE characteristic operations${NC}"
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

print_header "Streaming Media Channels Logs"
echo ""
echo -e "${YELLOW}Watching for media channel events...${NC}"
echo -e "${YELLOW}Press Ctrl+C to stop${NC}"
echo ""
echo "Legend:"
echo -e "  ${MAGENTA}REQUEST   = Incoming channel list request from BLE${NC}"
echo -e "  ${GREEN}RESPONSE  = Outgoing channel list via BLE${NC}"
echo -e "  ${WHITE}DATA      = Channel list data${NC}"
echo -e "  ${BLUE}BINARY    = Binary encoding operations${NC}"
echo -e "  ${CYAN}BLE       = BLE characteristic operations${NC}"
echo -e "  ${RED}ERROR     = Errors and failures${NC}"
echo ""
echo -e "${GRAY}Tip: Use -r flag to send a test request from CarThing${NC}"
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
