# Lucifer Mobile - Rebranded Kai9000 with Omni-Bridge Integration

## Overview

This is the **Lucifer Mobile** app - a rebranded version of the Kai9000 app (`com.inspiredandroid.kai`) that serves as the mobile hub for the **Lucifer-Hermes Omni-Bridge Prime** architecture.

### Key Features

- **Four Horsemen AI**: Conquest (Planning), War (Red Teaming), Famine (Low-budget), Death (Assumption Invalidation)
- **Lucifer Wallet**: Arbitrum/Ethereum secp256k1 wallet with Kai sandbox integration
- **Lucifer Browser**: Playwright automation via Kai Linux sandbox
- **Lucifer Terminal**: Full Linux shell access to Kai sandbox (Python 3.12, Node.js 22)
- **Lucifer Skills**: 30+ embedded skills with Four Horsemen categorization
- **Omni-Bridge Control Boundary**: APPA, Tool ABI, Provenance, QSTAR Router, Sandbox tiers
- **Kai Sandbox Integration**: Full Linux rootfs (Python 3.12, Node.js 22, Playwright, Selenium)
- **SSH Server**: Port 8022 for host ↔ phone communication
- **NVIDIA LLM Integration**: Nemotron, Llama models with key rotation

### Architecture

```
Lucifer Mobile (Pixel 8a)
├── Compose UI (Material 3, Lucifer Red/Black theme)
├── Four Horsemen Navigation (Conquest/War/Famine/Death)
├── Kai Sandbox Bridge (Unix socket to Linux sandbox)
├── Omni-Bridge Control Boundary (APPA, Tool ABI, Provenance, QSTAR)
├── Lucifer Wallet (Arbitrum/Ethereum secp256k1)
├── Lucifer Browser (Playwright via Kai sandbox)
├── Lucifer LLM (NVIDIA Nemotron, key rotation)
├── Lucifer Terminal (Full Linux shell)
└── SSH Server (Port 8022, host ↔ phone tunnel)
```

### Build & Deploy

```bash
# Build native library
./scripts/build_lucifer_native.sh

# Build APK
./gradlew :composeApp:assembleDebug

# Deploy to Pixel 8a
./scripts/deploy_lucifer.sh
```

### Requirements

- Android NDK (for native library)
- Android SDK 34
- Kotlin 2.0.0
- Compose Compiler 1.5.14
- Pixel 8a (or compatible) with Termux + Kai9000 app installed
- NVIDIA API key for LLM features

### Project Structure

```
lucifer_mobile/
├── build.gradle.kts                 # Root build config
├── settings.gradle.kts              # Project includes
├── gradle.properties                # Version constants
├── gradlew                          # Gradle wrapper
├── gradle/wrapper/                  # Gradle wrapper
├── composeApp/                      # Main Compose Multiplatform app
│   ├── build.gradle.kts             # App build config
│   └── src/
│       ├── commonMain/              # Shared Kotlin/Compose code
│       │   └── kotlin/com/lucifer/mobile/
│       │       ├── data/            # Wallet, Browser, LLM, Sandbox
│       │       ├── ui/              # Compose UI (Chat, Wallet, Browser, Terminal, Skills, Settings)
│       │       ├── omni_bridge/     # Omni-Bridge control boundary
│       │       └── LuciferCore.kt   # Core initialization
│       ├── androidMain/             # Android-specific code
│       │   ├── kotlin/com/lucifer/mobile/
│       │   │   ├── MainActivity.kt
│       │   │   ├── LuciferApplication.kt
│       │   │   └── services/        # SSH, OmniBridge, etc.
│       │   └── res/                 # Resources (strings, colors, manifest)
│       └── build.gradle.kts
├── native/                          # C++ native library
│   ├── CMakeLists.txt
│   ├── src/lucifer_native.cpp       # JNI bridge
│   └── include/lucifer_native.h     # JNI headers
├── scripts/
│   ├── build_lucifer_native.sh      # Cross-compile native lib
│   └── deploy_lucifer.sh            # Deploy to Pixel 8a
├── gradle/                          # Gradle wrapper
├── gradlew                          # Gradle wrapper script
├── settings.gradle.kts              # Project settings
├── build.gradle.kts                 # Root build config
└── gradle.properties                # Version constants
```

### Key Integrations

1. **Kai Sandbox Bridge**: Unix socket communication with `com.inspiredandroid.kai` Linux sandbox
2. **Lucifer Wallet**: Arbitrum/Ethereum wallet via Kai sandbox `wallet_ops.py`
3. **Lucifer Browser**: Playwright automation via Kai sandbox `browser_ops.py`
4. **Lucifer LLM**: NVIDIA Nemotron with key rotation via `llm_ops.py`
5. **Lucifer Sandbox**: Full Linux execution via `sandbox_ops.py`
6. **Omni-Bridge**: APPA, Tool ABI, Provenance, QSTAR Router, Sandbox tiers

### Deployment

```bash
# On host machine
cd lucifer_mobile
./scripts/deploy_lucifer.sh

# Then on host:
adb forward tcp:8022 tcp:8022
ssh -p 8022 u0_a311@localhost
```

### Kai9000 App Prerequisites

The `com.inspiredandroid.kai` app must be installed with:
- Linux sandbox at `/data/data/com.inspiredandroid.kai/files/linux-sandbox/rootfs`
- Python 3.12, Node.js 22, Playwright, Selenium
- Wallet scripts at `/data/data/com.inspiredandroid.kai/files/linux-sandbox/tmp/omni_bridge/wallet/`
- Browser scripts at `/data/data/com.inspiredandroid.kai/files/linux-sandbox/tmp/omni_bridge/browser/`
- LLM scripts at `/data/data/com.inspiredandroid.kai/files/linux-sandbox/tmp/omni_bridge/llm/`
- Sandbox scripts at `/data/data/com.inspiredandroid.kai/files/linux-sandbox/tmp/omni_bridge/sandbox/`

### SSH Access

```bash
# On host
adb forward tcp:8022 tcp:8022
ssh -p 8022 u0_a311@localhost

# In Lucifer app SSH terminal
help          # Show commands
wallet gen    # Generate wallet
llm <prompt>  # Query LLM
exec <cmd>    - Execute in sandbox
```

---

**Lucifer Mobile** - The Four Horsemen in your pocket. 😈