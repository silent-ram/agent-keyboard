cmake_minimum_required(VERSION 3.22.1)

project(agentkeyboard-ime LANGUAGES CXX)

set(REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../..")
set(FCITX_ROOT "${REPO_ROOT}/third_party/fcitx5-android")
set(LIBIME_HEADERS "${FCITX_ROOT}/lib/libime/build/headers/usr/include/LibIME")
set(FCITX_UTIL_HEADERS "${FCITX_ROOT}/lib/fcitx5/build/headers/usr/include/Fcitx5/Utils")
set(BOOST_HEADERS "${FCITX_ROOT}/lib/fcitx5/src/main/cpp/prebuilt/boost/include")
set(PREBUILT_LIB_DIR "${CMAKE_CURRENT_LIST_DIR}/../jniLibs/${ANDROID_ABI}")

add_library(Fcitx5Utils SHARED IMPORTED)
set_target_properties(Fcitx5Utils PROPERTIES
    IMPORTED_LOCATION "${PREBUILT_LIB_DIR}/libFcitx5Utils.so"
    INTERFACE_INCLUDE_DIRECTORIES "${FCITX_UTIL_HEADERS}"
)

add_library(IMECore SHARED IMPORTED)
set_target_properties(IMECore PROPERTIES
    IMPORTED_LOCATION "${PREBUILT_LIB_DIR}/libIMECore.so"
    INTERFACE_INCLUDE_DIRECTORIES "${LIBIME_HEADERS}"
)

add_library(IMEPinyin SHARED IMPORTED)
set_target_properties(IMEPinyin PROPERTIES
    IMPORTED_LOCATION "${PREBUILT_LIB_DIR}/libIMEPinyin.so"
    INTERFACE_INCLUDE_DIRECTORIES "${LIBIME_HEADERS}"
)

add_library(agentime SHARED libime_jni.cpp)

target_compile_features(agentime PRIVATE cxx_std_20)
target_compile_options(agentime PRIVATE
    -fvisibility=hidden
    -ffile-prefix-map=${FCITX_ROOT}=third_party/fcitx5-android
)
target_include_directories(agentime PRIVATE
    "${LIBIME_HEADERS}"
    "${FCITX_UTIL_HEADERS}"
    "${BOOST_HEADERS}"
)
target_link_libraries(agentime PRIVATE
    android
    log
    IMEPinyin
    IMECore
    Fcitx5Utils
)
