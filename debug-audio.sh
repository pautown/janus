#!/bin/bash
# Build, deploy, and debug MediaDash Android app - podcast audio playback logs
# Filters for PodcastAudio tag and related audio/playback logs

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
VERBOSE=false

while getopts "slv" opt; do
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
        \?)
            echo "Usage: $0 [-s] [-l] [-v]"
            echo "  -s    Skip build, just install and run"
            echo "  -l    Logs only mode (don't build/install, just stream logs)"
            echo "  -v    Verbose mode (show all PodcastAudio logs, not just key events)"
            exit 1
            ;;
    esac
done

print_header "MediaDash Podcast Audio Debug Session"

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
    print_header "Streaming Audio Playback Logs"
    echo -e "${YELLOW}Press Ctrl+C to stop${NC}"
    echo ""
    adb -s "$DEVICE_ID" logcat -c

    # Stream logs with filtering for PodcastAudio tag
    adb -s "$DEVICE_ID" logcat -v time PodcastAudio:V ExoPlayer:W Media3:W *:S | while read -r line; do
        # Color code based on content
        if echo "$line" | grep -qE "(ERROR|onPlayerError)"; then
            echo -e "${RED}$line${NC}"
        elif echo "$line" | grep -qE "(WARNING|WARNING:)"; then
            echo -e "${YELLOW}$line${NC}"
        elif echo "$line" | grep -qE "(USING LOCAL FILE)"; then
            echo -e "${GREEN}$line${NC}"
        elif echo "$line" | grep -qE "(USING STREAMING URL)"; then
            echo -e "${MAGENTA}$line${NC}"
        elif echo "$line" | grep -qE "(playEpisode|playPlaylist|addToPlaylist)"; then
            echo -e "${WHITE}$line${NC}"
        elif echo "$line" | grep -qE "(Playback started|✓)"; then
            echo -e "${GREEN}$line${NC}"
        elif echo "$line" | grep -qE "(onMediaItemTransition|onPlaybackStateChanged)"; then
            echo -e "${CYAN}$line${NC}"
        else
            echo "$line"
        fi
    done
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

print_header "Streaming Audio Playback Logs"
echo ""
echo -e "${YELLOW}Watching for podcast audio events...${NC}"
echo -e "${YELLOW}Press Ctrl+C to stop${NC}"
echo ""
echo "Legend:"
echo -e "  ${WHITE}▶ PLAY     = Playback function called${NC}"
echo -e "  ${GREEN}LOCAL     = Using downloaded file${NC}"
echo -e "  ${MAGENTA}STREAM    = Using streaming URL${NC}"
echo -e "  ${CYAN}STATE     = Playback state change${NC}"
echo -e "  ${RED}ERROR     = Playback error${NC}"
echo ""
echo "─────────────────────────────────────────────────────────────"

# Stream audio-related logs with filtering and colorization
if [ -n "$APP_PID" ] && [ "$APP_PID" != "0" ]; then
    echo -e "${GREEN}Tracking PID: $APP_PID${NC}"
    echo ""

    if [ "$VERBOSE" = true ]; then
        # Verbose mode: show all PodcastAudio logs
        adb -s "$DEVICE_ID" logcat -v time PodcastAudio:V ExoPlayer:W Media3:W AudioTrack:W *:S | while read -r line; do
            if echo "$line" | grep -qE "(ERROR|onPlayerError)"; then
                echo -e "${RED}$line${NC}"
            elif echo "$line" | grep -qE "(WARNING|WARNING:)"; then
                echo -e "${YELLOW}$line${NC}"
            elif echo "$line" | grep -qE "(USING LOCAL FILE)"; then
                echo -e "${GREEN}$line${NC}"
            elif echo "$line" | grep -qE "(USING STREAMING URL)"; then
                echo -e "${MAGENTA}$line${NC}"
            elif echo "$line" | grep -qE "(playEpisode|playPlaylist|addToPlaylist)"; then
                echo -e "${WHITE}$line${NC}"
            elif echo "$line" | grep -qE "(Playback started|✓)"; then
                echo -e "${GREEN}$line${NC}"
            elif echo "$line" | grep -qE "(onMediaItemTransition|onPlaybackStateChanged)"; then
                echo -e "${CYAN}$line${NC}"
            else
                echo "$line"
            fi
        done
    else
        # Normal mode: filter to key events only
        adb -s "$DEVICE_ID" logcat -v time PodcastAudio:V ExoPlayer:E Media3:E *:S | while read -r line; do
            # Filter to important messages
            if echo "$line" | grep -qE "(PodcastAudio|ExoPlayer|Media3)"; then
                if echo "$line" | grep -qE "(ERROR|onPlayerError)"; then
                    echo -e "${RED}$line${NC}"
                elif echo "$line" | grep -qE "(WARNING|WARNING:)"; then
                    echo -e "${YELLOW}$line${NC}"
                elif echo "$line" | grep -qE "(USING LOCAL FILE)"; then
                    echo -e "${GREEN}$line${NC}"
                elif echo "$line" | grep -qE "(USING STREAMING URL)"; then
                    echo -e "${MAGENTA}$line${NC}"
                elif echo "$line" | grep -qE "(playEpisode|playPlaylist|addToPlaylist)"; then
                    echo -e "${WHITE}$line${NC}"
                elif echo "$line" | grep -qE "(Playback started|✓)"; then
                    echo -e "${GREEN}$line${NC}"
                elif echo "$line" | grep -qE "(onMediaItemTransition|onPlaybackStateChanged)"; then
                    echo -e "${CYAN}$line${NC}"
                else
                    echo "$line"
                fi
            fi
        done
    fi
else
    print_warning "Could not get app PID, showing all audio logs"
    echo ""

    adb -s "$DEVICE_ID" logcat -v time PodcastAudio:V ExoPlayer:W Media3:W *:S | while read -r line; do
        if echo "$line" | grep -qE "(ERROR|onPlayerError)"; then
            echo -e "${RED}$line${NC}"
        elif echo "$line" | grep -qE "(WARNING|WARNING:)"; then
            echo -e "${YELLOW}$line${NC}"
        elif echo "$line" | grep -qE "(USING LOCAL FILE)"; then
            echo -e "${GREEN}$line${NC}"
        elif echo "$line" | grep -qE "(USING STREAMING URL)"; then
            echo -e "${MAGENTA}$line${NC}"
        elif echo "$line" | grep -qE "(playEpisode|playPlaylist|addToPlaylist)"; then
            echo -e "${WHITE}$line${NC}"
        elif echo "$line" | grep -qE "(Playback started|✓)"; then
            echo -e "${GREEN}$line${NC}"
        else
            echo "$line"
        fi
    done
fi
