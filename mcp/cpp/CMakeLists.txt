cmake_minimum_required(VERSION 3.22)
project(lucifer_memory_mcp LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(HTTPLIB_USE_OPENSSL_IF_AVAILABLE OFF CACHE BOOL "Local loopback HTTP only" FORCE)
set(HTTPLIB_COMPILE OFF CACHE BOOL "Use cpp-httplib header-only" FORCE)

include(FetchContent)
FetchContent_Declare(httplib
    URL https://codeload.github.com/yhirose/cpp-httplib/tar.gz/refs/tags/v0.18.3
    URL_HASH SHA256=a0567bcd6c3fe5cef1b329b96245119047f876b49e06cc129a36a7a8dffe173e
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_Declare(nlohmann_json
    URL https://codeload.github.com/nlohmann/json/tar.gz/refs/tags/v3.11.3
    URL_HASH SHA256=0d8ef5af7f9794e3263480193c491549b2ba6cc74bb018906202ada498a79406
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(httplib nlohmann_json)
find_package(Threads REQUIRED)

add_executable(lucifer_memory_mcp drive_memory_mcp.cpp)
target_link_libraries(lucifer_memory_mcp PRIVATE httplib::httplib nlohmann_json::nlohmann_json Threads::Threads)
target_compile_options(lucifer_memory_mcp PRIVATE -O2 -Wall -Wextra -Wpedantic)
install(TARGETS lucifer_memory_mcp RUNTIME DESTINATION bin)
