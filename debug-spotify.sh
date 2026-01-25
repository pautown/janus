#!/bin/bash
# Build, deploy, and debug MediaDash Android app - Spotify integration logs
# Tracks Spotify OAuth, API calls, and data fetching
#
# Shows: Auth flow, API requests, library stats, activity data

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
    echo -e "${GREEN}═══════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}  $1${NC}"
    echo -e "${GREEN}═══════════════════════════════════════════════════════════${NC}"
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
    echo "Usage: $0 [-s] [-l] [-v] [-h]"
    echo ""
    echo "Options:"
    echo "  -s    Skip build, just install and run"
    echo "  -l    Logs only mode (don't build/install, just stream logs)"
    echo "  -v    Verbose mode (show all Spotify logs including HTTP traffic)"
    echo "  -h    Show this help message"
    echo ""
    echo "Log Tags Monitored:"
    echo "  SPOTIFY       - Main Spotify auth and data fetching"
    echo "  SpotifyHttp   - HTTP request/response details"
    echo "  SpotifyAuth*  - OAuth flow details"
    echo "  SpotSDK*      - SpotSDK library internals"
    echo ""
    echo "Examples:"
    echo "  $0           # Full build, deploy, and debug"
    echo "  $0 -l        # Just stream Spotify logs"
    echo "  $0 -l -v     # Verbose Spotify logs with HTTP traffic"
    echo "  $0 -s        # Skip build, just install and run"
}

# Parse flags
SKIP_BUILD=false
LOGS_ONLY=false
VERBOSE=false

while getopts "slvh" opt; do
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

print_header "MediaDash Spotify Debug Session"

# Check device connection
print_step "Checking for device..."
if ! adb devices | grep -q "$DEVICE_ID"; then
    # Try to find any device
    FOUND_DEVICE=$(adb devices | grep -v "List" | grep "device$" | head -n1 | cut -f1)
    if [ -n "$FOUND_DEVICE" ]; then
        DEVICE_ID="$FOUND_DEVICE"
        print_warning "Using device: $DEVICE_ID"
    else
        print_error "No device found!"
        echo ""
        echo "Connected devices:"
        adb devices -l
        exit 1
    fi
fi
print_success "Found device ($DEVICE_ID)"

# Get device info
DEVICE_MODEL=$(adb -s "$DEVICE_ID" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
ANDROID_VERSION=$(adb -s "$DEVICE_ID" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
echo "   Model: $DEVICE_MODEL"
echo "   Android: $ANDROID_VERSION"

# Function to colorize Spotify logs
colorize_spotify_logs() {
    while read -r line; do
        # Section markers
        if echo "$line" | grep -qE "===.*==="; then
            echo -e "${CYAN}$line${NC}"
        # Success events
        elif echo "$line" | grep -qiE "(success|fetched|saved|logged in|✓)"; then
            echo -e "${GREEN}$line${NC}"
        # API responses with data
        elif echo "$line" | grep -qiE "(total:|count:|response code: 200)"; then
            echo -e "${GREEN}$line${NC}"
        # Auth events
        elif echo "$line" | grep -qiE "(access token|refresh token|login|logout|auth)"; then
            echo -e "${MAGENTA}$line${NC}"
        # API requests
        elif echo "$line" | grep -qiE "(fetching|creating|request)"; then
            echo -e "${BLUE}$line${NC}"
        # HTTP traffic
        elif echo "$line" | grep -qiE "(SpotifyHttp|https://api.spotify)"; then
            echo -e "${GRAY}$line${NC}"
        # Data values
        elif echo "$line" | grep -qiE "(user id:|displayname:|email:|country:|product:|follower)"; then
            echo -e "${WHITE}$line${NC}"
        # Track info
        elif echo "$line" | grep -qiE "(track:|playing:|recent track)"; then
            echo -e "${CYAN}$line${NC}"
        # Warning events
        elif echo "$line" | grep -qiE "(warning|⚠️|not found|null|empty)"; then
            echo -e "${YELLOW}$line${NC}"
        # Error events
        elif echo "$line" | grep -qiE "(error|failed|exception|response code: [45])"; then
            echo -e "${RED}$line${NC}"
        # Error body details
        elif echo "$line" | grep -qiE "(error body:|error_description)"; then
            echo -e "${RED}$line${NC}"
        else
            echo "$line"
        fi
    done
}

# Function to stream logs
stream_logs() {
    if [ "$VERBOSE" = true ]; then
        # Verbose: All Spotify-related tags at verbose level including HTTP
        adb -s "$DEVICE_ID" logcat -v time \
            SPOTIFY:V \
            SpotifyHttp:V \
            SpotifyAuth:V \
            SpotifyAuthManager:V \
            SpotSDK:V \
            SpotifyApiClient:V \
            OkHttp:V \
            *:S | colorize_spotify_logs
    else
        # Normal: Key Spotify events only
        adb -s "$DEVICE_ID" logcat -v time \
            SPOTIFY:V \
            SpotifyAuth:D \
            SpotifyAuthManager:D \
            SpotSDK:D \
            *:S | colorize_spotify_logs
    fi
}

if [ "$LOGS_ONLY" = true ]; then
    print_header "Streaming Spotify Logs"
    echo -e "${YELLOW}Press Ctrl+C to stop${NC}"
    echo ""
    adb -s "$DEVICE_ID" logcat -c

    echo "Legend:"
    echo -e "  ${CYAN}SECTION   = Start/end markers${NC}"
    echo -e "  ${MAGENTA}AUTH      = Authentication events${NC}"
    echo -e "  ${BLUE}REQUEST   = API requests${NC}"
    echo -e "  ${GREEN}SUCCESS   = Successful responses with data${NC}"
    echo -e "  ${WHITE}DATA      = User/track info${NC}"
    echo -e "  ${YELLOW}WARNING   = Warnings and null values${NC}"
    echo -e "  ${RED}ERROR     = Errors and failures${NC}"
    if [ "$VERBOSE" = true ]; then
        echo -e "  ${GRAY}HTTP      = Raw HTTP traffic${NC}"
    fi
    echo ""
    echo "─────────────────────────────────────────────────────────────"

    stream_logs
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

print_header "Streaming Spotify Logs"
echo ""
echo -e "${YELLOW}Watching for Spotify events...${NC}"
echo -e "${YELLOW}Swipe to Spotify page (page 4) to trigger API calls${NC}"
echo -e "${YELLOW}Press Ctrl+C to stop${NC}"
echo ""
echo "Legend:"
echo -e "  ${CYAN}SECTION   = Start/end markers${NC}"
echo -e "  ${MAGENTA}AUTH      = Authentication events${NC}"
echo -e "  ${BLUE}REQUEST   = API requests${NC}"
echo -e "  ${GREEN}SUCCESS   = Successful responses with data${NC}"
echo -e "  ${WHITE}DATA      = User/track info${NC}"
echo -e "  ${YELLOW}WARNING   = Warnings and null values${NC}"
echo -e "  ${RED}ERROR     = Errors and failures${NC}"
if [ "$VERBOSE" = true ]; then
    echo -e "  ${GRAY}HTTP      = Raw HTTP traffic${NC}"
fi
echo ""
echo "─────────────────────────────────────────────────────────────"

if [ -n "$APP_PID" ] && [ "$APP_PID" != "0" ]; then
    echo -e "${GREEN}Tracking PID: $APP_PID${NC}"
    echo ""
fi

stream_logs
