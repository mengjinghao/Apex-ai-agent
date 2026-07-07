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

# 添加编译选项
target_compile_options(
        ai_terminal_jni
        PRIVATE
        -Wall
        -Wextra
        -Wno-unused-parameter
)
