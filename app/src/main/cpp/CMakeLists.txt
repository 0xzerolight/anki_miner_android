cmake_minimum_required(VERSION 3.22.1)
project(anki_miner_tokenizer LANGUAGES CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "S1b Android library must be configured by the Android NDK")
endif()

get_filename_component(ANKI_MINER_NDK_REVISION "${CMAKE_ANDROID_NDK}" NAME)
if(NOT ANKI_MINER_NDK_REVISION STREQUAL "28.2.13676358")
    message(FATAL_ERROR "S1b requires the locked Android NDK 28.2.13676358")
endif()
if(NOT CMAKE_ANDROID_ARCH_ABI MATCHES "^(arm64-v8a|x86_64)$")
    message(FATAL_ERROR "S1b supports only arm64-v8a and x86_64")
endif()

set(MECAB_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/mecab/src")
set(MECAB_RUNTIME_SOURCES
    "${MECAB_SOURCE_DIR}/char_property.cpp"
    "${MECAB_SOURCE_DIR}/connector.cpp"
    "${MECAB_SOURCE_DIR}/context_id.cpp"
    "${MECAB_SOURCE_DIR}/dictionary.cpp"
    "${MECAB_SOURCE_DIR}/eval.cpp"
    "${MECAB_SOURCE_DIR}/iconv_utils.cpp"
    "${MECAB_SOURCE_DIR}/libmecab.cpp"
    "${MECAB_SOURCE_DIR}/nbest_generator.cpp"
    "${MECAB_SOURCE_DIR}/param.cpp"
    "${MECAB_SOURCE_DIR}/string_buffer.cpp"
    "${MECAB_SOURCE_DIR}/tagger.cpp"
    "${MECAB_SOURCE_DIR}/tokenizer.cpp"
    "${MECAB_SOURCE_DIR}/utils.cpp"
    "${MECAB_SOURCE_DIR}/viterbi.cpp"
    "${MECAB_SOURCE_DIR}/writer.cpp"
)

add_library(mecab_runtime STATIC ${MECAB_RUNTIME_SOURCES})
set_target_properties(
    mecab_runtime
    PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        POSITION_INDEPENDENT_CODE ON
        VISIBILITY_INLINES_HIDDEN ON
)
target_compile_features(mecab_runtime PRIVATE cxx_std_11)
target_compile_options(
    mecab_runtime
    PRIVATE
        # NDK Clang defaults to C++17, which removed constructs used by the
        # pinned upstream MeCab sources. Keep this target on the same exact
        # C++11 dialect exercised by the host parity build.
        -std=c++11
        -Wno-deprecated-declarations
        -Wno-register
)
target_compile_definitions(
    mecab_runtime
    PRIVATE
        HAVE_MMAP=1
        HAVE_SYS_MMAN_H=1
        HAVE_SYS_TYPES_H=1
)
target_include_directories(mecab_runtime PUBLIC "${MECAB_SOURCE_DIR}")

add_library(
    anki_miner_mecab
    SHARED
    tokenizer_jni.cpp
    tokenizer_wire.cpp
)
set_target_properties(
    anki_miner_mecab
    PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON
)
target_compile_features(anki_miner_mecab PRIVATE cxx_std_17)
target_compile_options(
    anki_miner_mecab
    PRIVATE
        -Wall
        -Wextra
        -Werror
)
target_link_libraries(anki_miner_mecab PRIVATE mecab_runtime)
target_link_options(
    anki_miner_mecab
    PRIVATE
        "-Wl,--no-undefined"
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
)
