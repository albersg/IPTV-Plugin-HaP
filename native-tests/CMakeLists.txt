cmake_minimum_required(VERSION 3.22.1)
project(httpaceproxy_native_tests LANGUAGES CXX)

# Host-side unit tests for the pure parts of the HaP native core.
#
# These deliberately do NOT define HTTPACEPROXYCPP_ANDROID: util.cpp then falls
# back to std::cerr for logging instead of <android/log.h>, so the same source
# that ships in the APK is compiled and executed on the build host. Nothing here
# is packaged into the app.
#
# Usage:
#   cmake -S native-tests -B build/native-tests
#   cmake --build build/native-tests -j
#   ctest --test-dir build/native-tests --output-on-failure

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

enable_testing()

set(HTTPACE_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp/httpaceproxycpp)

include(FetchContent)
FetchContent_Declare(
    googletest
    URL https://github.com/google/googletest/archive/refs/tags/v1.15.2.tar.gz
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

add_executable(httpace_util_tests
    test_util.cpp
    ${HTTPACE_CPP_DIR}/src/util.cpp
)

target_include_directories(httpace_util_tests PRIVATE ${HTTPACE_CPP_DIR}/include)

target_link_libraries(httpace_util_tests PRIVATE GTest::gtest_main)

add_executable(httpace_chunk_parser_tests
    test_chunk_parser.cpp
    ${HTTPACE_CPP_DIR}/src/chunk_parser.cpp
    ${HTTPACE_CPP_DIR}/src/util.cpp
)

target_include_directories(httpace_chunk_parser_tests PRIVATE ${HTTPACE_CPP_DIR}/include)

target_link_libraries(httpace_chunk_parser_tests PRIVATE GTest::gtest_main)

add_executable(httpace_ts_probe_tests
    test_ts_probe.cpp
    ${HTTPACE_CPP_DIR}/src/ts_probe.cpp
)

target_include_directories(httpace_ts_probe_tests PRIVATE ${HTTPACE_CPP_DIR}/include)

target_link_libraries(httpace_ts_probe_tests PRIVATE GTest::gtest_main)

add_executable(httpace_recovery_policy_tests
    test_recovery_policy.cpp
    ${HTTPACE_CPP_DIR}/src/recovery_policy.cpp
)

target_include_directories(httpace_recovery_policy_tests PRIVATE ${HTTPACE_CPP_DIR}/include)

target_link_libraries(httpace_recovery_policy_tests PRIVATE GTest::gtest_main)

add_executable(httpace_local_source_tests
    test_local_source.cpp
    ${HTTPACE_CPP_DIR}/src/local_source.cpp
)

target_include_directories(httpace_local_source_tests PRIVATE ${HTTPACE_CPP_DIR}/include)

target_link_libraries(httpace_local_source_tests PRIVATE GTest::gtest_main)

include(GoogleTest)
# PRE_TEST defers enumeration to `ctest` time instead of running the freshly
# linked binary during the build, which fails on toolchains where the build
# tree binary cannot be executed at that point.
gtest_discover_tests(httpace_util_tests DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(httpace_chunk_parser_tests DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(httpace_local_source_tests DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(httpace_recovery_policy_tests DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(httpace_ts_probe_tests DISCOVERY_MODE PRE_TEST)
