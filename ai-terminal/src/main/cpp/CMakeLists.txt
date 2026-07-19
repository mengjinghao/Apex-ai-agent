cmake_minimum_required(VERSION 3.22.1)

project("ai_terminal")

add_library(
        ai_terminal_jni
        SHARED
        terminal_jni.cpp
        terminal_session.cpp
        shell_adapter.cpp
        root_terminal_core.cpp
        process_manager.cpp
        performance_monitor.cpp
        shell_extension.cpp
        flashing_helper.cpp
)

# 添加头文件目录
target_include_directories(
        ai_terminal_jni
        PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(
        log-lib
        log
)

target_link_libraries(
        ai_terminal_jni
        ${log-lib}
        android
)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Security (A-19): compiler/linker hardening flags.
#
# -fstack-protector-strong: stack canaries on functions with char arrays
#   or address-taken locals — catches stack buffer overflows at runtime
#   (abort instead of exploit).
# -D_FORTIFY_SOURCE=2: compile-time + runtime checks on common libc string
#   / memory functions (memcpy, strcpy, sprintf, etc.). Requires -O1 or
#   higher (Android NDK release builds use -O2 by default; debug uses -O0
#   which silently disables FORTIFY — acceptable trade-off).
# -fvisibility=hidden: default symbol visibility to hidden; only symbols
#   marked JNI_EXPORT (or explicitly exported) become public, shrinking
#   the attack surface and the .dynsym table.
#
# Linker:
# -Wl,-z,relro: read-only relocations (GOT becomes read-only after load).
# -Wl,-z,now: full RELRO + immediate symbol binding at startup (no lazy
#   binding, which closes a classic GOT-overwrite exploit vector).
target_compile_options(
        ai_terminal_jni
        PRIVATE
        -Wall
        -Wextra
        -Wno-unused-parameter
        -fstack-protector-strong
        -D_FORTIFY_SOURCE=2
        -fvisibility=hidden
)

target_link_options(
        ai_terminal_jni
        PRIVATE
        -Wl,-z,relro
        -Wl,-z,now
)
