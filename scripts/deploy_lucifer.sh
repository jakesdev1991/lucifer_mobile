#!/bin/bash
# deploy_lucifer.sh - Deploy Lucifer Mobile to Pixel 8a

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$PROJECT_ROOT/build_android"
TARGET="com.lucifer.mobile"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Deploying Lucifer Mobile to Pixel 8a ${NC}"
echo -e "${GREEN}========================================${NC}"

# 1. Check ADB
echo -e "\n${YELLOW}[1/7] Checking ADB connection...${NC}"
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}No device connected${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Device connected${NC}"

# 2. Check Kai9000 (Lucifer) app
echo -e "\n${YELLOW}[2/7] Checking Kai9000/Lucifer app...${NC}"
if ! adb shell pm list packages | grep -q "com.inspiredandroid.kai"; then
    echo -e "${RED}Kai9000 app not found${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Kai9000 app found${NC}"

# 3. Check Termux
echo -e "\n${YELLOW}[3/7] Checking Termux...${NC}"
if ! adb shell pm list packages | grep -q "com.termux"; then
    echo -e "${RED}Termux not installed${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Termux installed${NC}"

# 4. Build native library
echo -e "\n${YELLOW}[4/7] Building Lucifer native library...${NC}"
"$PROJECT_ROOT/scripts/build_lucifer_native.sh"
if [ $? -ne 0 ]; then
    echo -e "${RED}Native build failed${NC}"
    exit 1
fi

# 5. Build Android APK
echo -e "\n${YELLOW}[5/7] Building Android APK...${NC}"
cd "$PROJECT_ROOT"
./gradlew :composeApp:assembleDebug
if [ $? -ne 0 ]; then
    echo -e "${RED}APK build failed${NC}"
    exit 1
fi
echo -e "${GREEN}✓ APK built${NC}"

# 6. Install APK
echo -e "\n${YELLOW}[6/7] Installing Lucifer APK...${NC}"
adb install -r "$PROJECT_ROOT/composeApp/build/outputs/apk/debug/composeApp-debug.apk"
if [ $? -ne 0 ]; then
    echo -e "${RED}APK install failed${NC}"
    exit 1
fi
echo -e "${GREEN}✓ APK installed${NC}"

# 7. Start services
echo -e "\n${YELLOW}[7/7] Starting services...${NC}"

# Forward SSH port
adb forward tcp:8022 tcp:8022

# Start SSH in Termux
adb shell "run-as com.termux sshd -p 8022" &
sleep 2

# Start Lucifer app
adb shell am start -n com.lucifer.mobile/.MainActivity

sleep 3

# Verify
echo -e "\n${GREEN}========================================${NC}"
echo -e "${GREEN}  Deployment Complete!                  ${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "Lucifer Mobile is now running on your Pixel 8a"
echo ""
echo "Connect from host:"
echo "  ssh -p 8022 u0_a311@localhost"
echo ""
echo "In Lucifer app:"
echo "  - Chat with the Four Horsemen (Conquest/War/Famine/Death)"
echo "  - Manage Arbitrum/Ethereum wallets"
echo "  - Browser automation via Kai sandbox"
echo "  - Terminal access to Kai Linux sandbox"
echo "  - 30+ embedded skills"
echo ""
echo "SSH tunnel from host:"
echo "  adb forward tcp:8022 tcp:8022"
echo "  ssh -p 8022 u0_a311@localhost"
echo ""
echo -e "${GREEN}Lucifer is online. The Four Horsemen await your command. 😈${NC}"