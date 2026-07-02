cmake_minimum_required(VERSION 3.22.1)

project(streamnative)

if(MSVC)
    add_compile_options(/wd4996 /wd4244 /wd4267 /wd4305)
else()
    add_compile_options(
        -Wno-deprecated-declarations
        -Wno-unused-value
        -Wno-switch
        -Wno-implicit-const-int-float-conversion
        -Wno-single-bit-bitfield-constant-conversion
    )
endif()

set(OPENGL_opengl_LIBRARY "")
set(OPENGL_glx_LIBRARY "")
set(OPENGL_INCLUDE_DIR "")

function(force_release_flags_for_debug target_name)
    if(TARGET ${target_name})
        target_compile_definitions(${target_name} PRIVATE $<$<CONFIG:Debug>:NDEBUG>)
        target_compile_options(${target_name} PRIVATE $<$<CONFIG:Debug>:-O3 -g0>)
    endif()
endfunction()

add_library(
        streamnative
        SHARED
        streamnative/native_xml_splitter.cpp
        streamnative/native_markdown_splitter.cpp
        streamnative/StreamOperators.cpp
        streamnative/plugins/StreamXmlPlugin.cpp
        streamnative/plugins/BaseJsonPlugin.cpp
        streamnative/plugins/StreamJsonPlugin.cpp
        streamnative/plugins/StreamPureJsonPlugin.cpp
        streamnative/plugins/StreamMarkdownPlugin.cpp
        streamnative/StreamBuilders.cpp
        streamnative/HotStream.cpp
        streamnative/StringExtensions.cpp
)

target_include_directories(
        streamnative
        PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(
        log-lib
        log
)

target_link_libraries(
        streamnative
        ${log-lib}
)