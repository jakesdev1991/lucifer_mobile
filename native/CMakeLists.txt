cmake_minimum_required(VERSION 3.22)
project(LuciferNative CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Android NDK toolchain
if(ANDROID_NDK)
    set(CMAKE_ANDROID_NDK ${ANDROID_NDK})
elseif(DEFINED ENV{ANDROID_NDK_HOME})
    set(CMAKE_ANDROID_NDK $ENV{ANDROID_NDK_HOME})
elseif(DEFINED ENV{ANDROID_NDK_ROOT})
    set(CMAKE_ANDROID_NDK $ENV{ANDROID_NDK_ROOT})
else()
    message(WARNING "ANDROID_NDK not set. Set ANDROID_NDK_HOME or ANDROID_NDK_ROOT env var, or pass -DANDROID_NDK=/path/to/ndk")
endif()

if(CMAKE_ANDROID_NDK)
    set(CMAKE_TOOLCHAIN_FILE ${CMAKE_ANDROID_NDK}/build/cmake/android.toolchain.cmake)
    set(ANDROID_ABI arm64-v8a)
    set(ANDROID_PLATFORM android-34)
    set(ANDROID_STL c++_shared)
    set(ANDROID_TOOLCHAIN clang)
    message(STATUS "Cross-compiling for Android: ${ANDROID_ABI}, API ${ANDROID_PLATFORM}")
else()
    message(STATUS "Native build (not cross-compiling)")
endif()

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(CMAKE_ANDROID_NDK)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fPIE -fPIC")
    set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -pie")
    set(TERMUX_PREFIX "/data/data/com.termux/files/usr")
    set(CMAKE_INSTALL_PREFIX ${TERMUX_PREFIX})
endif()

# Build shared library
add_library(lucifer_native SHARED
    lucifer_native.cpp
)

target_include_directories(lucifer_native PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/../include
)

target_link_libraries(lucifer_native
    android
    log
)

# Install
install(TARGETS lucifer_native
    LIBRARY DESTINATION lib
)

# Custom targets
add_custom_target(cross_compile
    COMMAND ${CMAKE_COMMAND} -DCMAKE_TOOLCHAIN_FILE=${CMAKE_ANDROID_NDK}/build/cmake/android.toolchain.cmake
            -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-34
            -B ${CMAKE_BINARY_DIR}_android
            ${CMAKE_SOURCE_DIR}
    COMMAND ${CMAKE_COMMAND} --build ${CMAKE_BINARY_DIR}_android --parallel
    WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
    COMMENT "Cross-compiling for aarch64-linux-android"
)

add_custom_target(deploy_pixel
    COMMAND adb push ${CMAKE_BINARY_DIR}_android/liblucifer_native.so /data/data/com.termux/files/usr/lib/
    COMMAND adb shell chmod 755 /data/data/com.termux/files/usr/lib/liblucifer_native.so
    WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
    COMMENT "Deploying to Pixel 8a via ADB"
)