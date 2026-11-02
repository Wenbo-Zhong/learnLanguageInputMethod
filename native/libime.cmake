# Portable static build of the pinned upstream decoder. Desktop Fcitx services,
# D-Bus, input-method frontends and platform event loops are not required by libime.
include(GenerateExportHeader)
include(CheckIncludeFile)
set(LI_VENDOR "${CMAKE_CURRENT_LIST_DIR}/../third_party")
set(LI_GENERATED "${CMAKE_CURRENT_BINARY_DIR}/libime-include")
set(LI_FCITX "${LI_VENDOR}/fcitx5/src/lib/fcitx-utils")
set(LI_IME "${LI_VENDOR}/libime/src/libime")
set(LI_KENLM "${LI_IME}/core/kenlm")
set(LI_BOOST "${LI_VENDOR}/boost")
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
file(MAKE_DIRECTORY "${LI_GENERATED}/fcitx-utils" "${LI_GENERATED}/libime/core" "${LI_GENERATED}/libime/pinyin")

set(ZSTD_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_STATIC ON CACHE BOOL "" FORCE)
add_subdirectory("${LI_VENDOR}/zstd/build/cmake" "${CMAKE_CURRENT_BINARY_DIR}/zstd" EXCLUDE_FROM_ALL)

add_library(learnime-boost-iostreams STATIC
    "${LI_BOOST}/libs/iostreams/src/file_descriptor.cpp"
    "${LI_BOOST}/libs/iostreams/src/mapped_file.cpp")
target_include_directories(learnime-boost-iostreams PUBLIC "${LI_BOOST}")
target_compile_definitions(learnime-boost-iostreams PUBLIC BOOST_IOSTREAMS_NO_LIB BOOST_ALL_NO_LIB)

set(LI_UTIL_SOURCES stringutils.cpp key.cpp cutf8.cpp fs.cpp unixfd.cpp utf8.cpp
    connectableobject.cpp element.cpp inputbuffer.cpp log.cpp misc.cpp keydata.cpp
    environ.cpp fdstreambuf.cpp standardpaths.cpp standardpaths_p_unix.cpp semver.cpp)
list(TRANSFORM LI_UTIL_SOURCES PREPEND "${LI_FCITX}/")
add_library(learnime-fcitx-utils STATIC ${LI_UTIL_SOURCES})
generate_export_header(learnime-fcitx-utils BASE_NAME FcitxUtils
    EXPORT_FILE_NAME "${LI_GENERATED}/fcitx-utils/fcitxutils_export.h")
check_include_file("sys/wait.h" HAVE_SYS_WAIT_H)
check_include_file("sys/mman.h" HAVE_SYS_MMAN_H)
check_include_file("sys/uio.h" HAVE_SYS_UIO_H)
set(FCITX_VERSION "5.1.20")
set(FCITX_INSTALL_PREFIX "")
set(FCITX_INSTALL_SYSCONFDIR "/etc")
set(FCITX_INSTALL_LIBDIR "/lib")
set(FCITX_INSTALL_LIBDATADIR "/lib")
set(FCITX_INSTALL_DATADIR "/share")
set(FCITX_INSTALL_PKGDATADIR "/share/fcitx5")
set(FCITX_INSTALL_ADDONDIR "/lib/fcitx5")
set(FCITX_INSTALL_BINDIR "/bin")
configure_file("${LI_VENDOR}/fcitx5/config.h.in" "${LI_GENERATED}/fcitx-config/config.h")
target_include_directories(learnime-fcitx-utils PRIVATE "${LI_GENERATED}/fcitx-config"
    PUBLIC "${LI_VENDOR}/fcitx5/src/lib" "${LI_GENERATED}")
target_compile_definitions(learnime-fcitx-utils PUBLIC FCITXUTILS_STATIC_DEFINE)
target_link_libraries(learnime-fcitx-utils PRIVATE "${CMAKE_DL_LIBS}")

file(GLOB LI_KENLM_SOURCES CONFIGURE_DEPENDS "${LI_KENLM}/lm/*.cc"
    "${LI_KENLM}/util/*.cc" "${LI_KENLM}/util/double-conversion/*.cc")
list(FILTER LI_KENLM_SOURCES EXCLUDE REGEX "(test|main)\\.cc$")
add_library(learnime-kenlm STATIC ${LI_KENLM_SOURCES})
target_include_directories(learnime-kenlm PUBLIC "${LI_KENLM}" "${LI_BOOST}" "${LI_VENDOR}/zstd/lib")
target_compile_definitions(learnime-kenlm PUBLIC KENLM_MAX_ORDER=3
    _LIBCPP_ENABLE_CXX17_REMOVED_UNARY_BINARY_FUNCTION PRIVATE NDEBUG HAVE_CLOCKGETTIME)
target_link_libraries(learnime-kenlm PUBLIC libzstd_static)

set(LI_CORE_SOURCES datrie.cpp dictionary.cpp decoder.cpp languagemodel.cpp inputbuffer.cpp
    lattice.cpp userlanguagemodel.cpp historybigram.cpp segmentgraph.cpp utils.cpp prediction.cpp triedictionary.cpp)
list(TRANSFORM LI_CORE_SOURCES PREPEND "${LI_IME}/core/")
add_library(learnime-libime-core STATIC ${LI_CORE_SOURCES})
generate_export_header(learnime-libime-core BASE_NAME LibIMECore
    EXPORT_FILE_NAME "${LI_GENERATED}/libime/core/libimecore_export.h")
configure_file("${LI_VENDOR}/libime/config.h.in" "${LI_GENERATED}/config.h")
target_include_directories(learnime-libime-core PUBLIC "${LI_VENDOR}/libime/src" "${LI_GENERATED}" "${LI_BOOST}"
    PRIVATE "${LI_VENDOR}/zstd/lib")
target_compile_definitions(learnime-libime-core PUBLIC LIBIMECORE_STATIC_DEFINE)
target_link_libraries(learnime-libime-core PUBLIC learnime-fcitx-utils
    PRIVATE learnime-kenlm learnime-boost-iostreams libzstd_static)

set(LI_PINYIN_SOURCES pinyincontext.cpp pinyindata.cpp pinyindecoder.cpp pinyindictionary.cpp
    pinyinencoder.cpp pinyinime.cpp pinyinmatchstate.cpp shuangpinprofile.cpp pinyinprediction.cpp pinyincorrectionprofile.cpp
    ninekeyencoder.cpp)
list(TRANSFORM LI_PINYIN_SOURCES PREPEND "${LI_IME}/pinyin/")
add_library(learnime-libime-pinyin STATIC ${LI_PINYIN_SOURCES})
generate_export_header(learnime-libime-pinyin BASE_NAME LibIMEPinyin
    EXPORT_FILE_NAME "${LI_GENERATED}/libime/pinyin/libimepinyin_export.h")
target_include_directories(learnime-libime-pinyin PUBLIC "${LI_VENDOR}/libime/src" "${LI_GENERATED}" "${LI_BOOST}"
    PRIVATE "${LI_VENDOR}/zstd/lib")
target_compile_definitions(learnime-libime-pinyin PUBLIC LIBIMEPINYIN_STATIC_DEFINE)
target_link_libraries(learnime-libime-pinyin PUBLIC learnime-libime-core)

# Keyboard latency depends on trie traversal and language-model scoring even
# in development APKs. Keep symbols, but optimize the portable engine itself.
foreach(LI_TARGET learnime-boost-iostreams learnime-fcitx-utils learnime-kenlm
        learnime-libime-core learnime-libime-pinyin libzstd_static)
    target_compile_options(${LI_TARGET} PRIVATE -O2)
endforeach()

if(NOT ANDROID AND NOT CMAKE_SYSTEM_NAME STREQUAL "iOS")
    add_executable(learnime-pinyindict EXCLUDE_FROM_ALL "${LI_VENDOR}/libime/tools/libime_pinyindict.cpp")
    target_link_libraries(learnime-pinyindict PRIVATE learnime-libime-pinyin)
    add_executable(learnime-lm-build EXCLUDE_FROM_ALL "${LI_KENLM}/lm/build_binary_main.cc")
    target_link_libraries(learnime-lm-build PRIVATE learnime-kenlm)
    add_executable(learnime-lm-predict EXCLUDE_FROM_ALL "${LI_VENDOR}/libime/tools/libime_prediction.cpp")
    target_link_libraries(learnime-lm-predict PRIVATE learnime-libime-core)
endif()
