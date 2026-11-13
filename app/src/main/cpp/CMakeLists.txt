cmake_minimum_required(VERSION 3.22.1)
project(gremlinamp)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
function(extract_decoder archive name output)
    set(input "${REPO_ROOT}/decoder-sources/archives/${archive}")
    set(destination "${CMAKE_CURRENT_BINARY_DIR}/${name}-source")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${input}")
    file(SHA256 "${input}" hash)
    if(NOT EXISTS "${destination}/${hash}")
        file(REMOVE_RECURSE "${destination}")
        file(MAKE_DIRECTORY "${destination}")
        file(ARCHIVE_EXTRACT INPUT "${input}" DESTINATION "${destination}")
        file(TOUCH "${destination}/${hash}")
    endif()
    set(${output} "${destination}" PARENT_SCOPE)
endfunction()

extract_decoder(LIBVGM_0_9_SOURCE.zip libvgm LIBVGM_SOURCE)
set(LIBVGM_ROOT "${LIBVGM_SOURCE}/third_party/libvgm")
set(BUILD_LIBAUDIO OFF CACHE BOOL "" FORCE)
set(BUILD_PLAYER OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_VGM2WAV OFF CACHE BOOL "" FORCE)
set(UTIL_CHARSET_CONV OFF CACHE BOOL "" FORCE)
set(CMAKE_EXPORT_NO_PACKAGE_REGISTRY ON)
set(SNDEMU__ALL ON CACHE BOOL "" FORCE)
add_subdirectory("${LIBVGM_ROOT}" libvgm EXCLUDE_FROM_ALL)
target_compile_definitions(vgm-player PRIVATE LIBVGM_FLOAT32_OUTPUT=1)

add_subdirectory(game-music-emu)
add_library(gremlinamp SHARED bridge.cpp vgm_bridge.cpp board_bridge.cpp machine_bridge.cpp)
target_include_directories(gremlinamp PRIVATE "${LIBVGM_ROOT}")
target_link_libraries(gremlinamp PRIVATE gme_static vgm-player vgm-emu vgm-utils log z dl)

# AGP collects the linked core libraries. Open them only for the active track
# so closing the decoder also discards the core's process-global state.
target_link_options(gremlinamp PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--as-needed")

# The site GME+ build is kept isolated from the original Game Music Emu symbols.
extract_decoder(GME_PLUS_0_10_SOURCE.zip gme-plus GME_PLUS_SOURCE)
set(GME_PLUS_ROOT "${GME_PLUS_SOURCE}/third_party/gme/gme")
set(GME_PLUS_FILES
    gme.cpp blargg_common.cpp blargg_errors.cpp Music_Emu.cpp Gme_File.cpp
    Gme_Loader.cpp M3u_Playlist.cpp Track_Filter.cpp Classic_Emu.cpp
    Blip_Buffer.cpp Multi_Buffer.cpp Rom_Data.cpp Nsf_Emu.cpp Nsfe_Emu.cpp
    Nsf_Core.cpp Nsf_Impl.cpp Nsf_Cpu.cpp Nes_Cpu.cpp Z80_Cpu.cpp Nes_Apu.cpp
    Nes_Oscs.cpp Nes_Fds_Apu.cpp Nes_Namco_Apu.cpp Nes_Vrc6_Apu.cpp
    Nes_Fme7_Apu.cpp Nes_Vrc7_Apu.cpp Gbs_Emu.cpp Gbs_Core.cpp Gbs_Cpu.cpp
    Gb_Cpu.cpp Gb_Apu.cpp Gb_Oscs.cpp Sgc_Emu.cpp Sgc_Core.cpp Sgc_Impl.cpp
    Sgc_Cpu.cpp Sms_Apu.cpp Sms_Fm_Apu.cpp Ym2413_Emu.cpp Dual_Resampler.cpp
    Fir_Resampler.cpp Resampler.cpp ym2413.c)
list(TRANSFORM GME_PLUS_FILES PREPEND "${GME_PLUS_ROOT}/")
add_library(gremlinamp-gme-plus SHARED gme_plus_bridge.cpp ${GME_PLUS_FILES}
    "${GME_PLUS_SOURCE}/src/data_reader.cpp")
target_include_directories(gremlinamp-gme-plus PRIVATE "${GME_PLUS_ROOT}"
    "${GME_PLUS_SOURCE}/third_party/file-extractor/fex")
target_compile_definitions(gremlinamp-gme-plus PRIVATE HAVE_STDINT_H=1 GME_DISABLE_EFFECTS=1
    GME_TYPE_LIST=gme_nsf_type,gme_nsfe_type,gme_sgc_type,gme_gbs_type)
set_target_properties(gremlinamp-gme-plus PROPERTIES C_VISIBILITY_PRESET hidden CXX_VISIBILITY_PRESET hidden)
target_link_options(gremlinamp-gme-plus PRIVATE "-Wl,-z,max-page-size=16384")

extract_decoder(gsf-source.zip gsf GSF_SOURCE)
if(NOT EXISTS "${GSF_SOURCE}/lazygsf/mgba/include/mgba")
    file(CREATE_LINK "${GSF_SOURCE}/mgba" "${GSF_SOURCE}/lazygsf/mgba" SYMBOLIC COPY_ON_ERROR)
endif()
# Use the shared-library target to avoid lazygsf's desktop-only static post-link step.
function(add_lazygsf)
    set(BUILD_SHARED_LIBS ON)
    add_subdirectory("${GSF_SOURCE}/lazygsf" lazygsf EXCLUDE_FROM_ALL)
endfunction()
add_lazygsf()
target_link_options(lazygsf PRIVATE "-Wl,-z,max-page-size=16384")
add_library(gremlinamp-gsf SHARED gsf_bridge.cpp "${GSF_SOURCE}/psflib/psflib.c")
target_include_directories(gremlinamp-gsf PRIVATE "${GSF_SOURCE}/psflib")
target_link_libraries(gremlinamp-gsf PRIVATE lazygsf z)
target_link_options(gremlinamp-gsf PRIVATE "-Wl,-z,max-page-size=16384")

include(native-boards.cmake)
include(native-machines.cmake)

extract_decoder(openmpt-source.zip openmpt OPENMPT_SOURCE)
set(OPENMPT_ROOT "${OPENMPT_SOURCE}/openmpt")
# Use upstream's Android NDK source list.
file(READ "${OPENMPT_ROOT}/build/android_ndk/Android.mk" OPENMPT_NDK)
string(REGEX MATCHALL "[A-Za-z0-9_./-]+[.]cpp" OPENMPT_FILES "${OPENMPT_NDK}")
list(TRANSFORM OPENMPT_FILES PREPEND "${OPENMPT_ROOT}/")
add_library(gremlinamp-openmpt SHARED openmpt_bridge.cpp ${OPENMPT_FILES})
target_compile_features(gremlinamp-openmpt PRIVATE cxx_std_23)
target_compile_definitions(gremlinamp-openmpt PRIVATE LIBOPENMPT_BUILD MPT_WITH_ZLIB)
target_include_directories(gremlinamp-openmpt PRIVATE "${OPENMPT_ROOT}" "${OPENMPT_ROOT}/src" "${OPENMPT_ROOT}/common")
set_target_properties(gremlinamp-openmpt PROPERTIES CXX_VISIBILITY_PRESET hidden)
target_link_libraries(gremlinamp-openmpt PRIVATE z)
target_link_options(gremlinamp-openmpt PRIVATE "-Wl,-z,max-page-size=16384")

extract_decoder(vgmplay-js-2-source.zip broad BROAD)
set(SEXYPSF "${BROAD}/sexypsf")
set(VGMPLAY2 "${BROAD}/vgmplay-js-2")
if(NOT EXISTS "${SEXYPSF}/.gremlin-patched")
    execute_process(COMMAND patch -p1 -i "${VGMPLAY2}/patches/sexypsf.patch"
        WORKING_DIRECTORY "${SEXYPSF}" COMMAND_ERROR_IS_FATAL ANY)
    file(TOUCH "${SEXYPSF}/.gremlin-patched")
endif()
add_subdirectory("${SEXYPSF}" sexypsf EXCLUDE_FROM_ALL)
target_compile_definitions(sexypsf PRIVATE fopen=gremlin_psf_fopen)
target_compile_options(sexypsf PRIVATE -fcommon -fwrapv -ffp-contract=off)
set(DC "${VGMPLAY2}/modules/highly_theoretical/Core")
add_library(dreamcast STATIC "${DC}/arm.c" "${DC}/dcsound.c" "${DC}/sega.c" "${DC}/yam.c")
target_include_directories(dreamcast PUBLIC "${DC}")
target_compile_definitions(dreamcast PUBLIC EMU_COMPILE EMU_LITTLE_ENDIAN HAVE_STDINT_H DISABLE_SSF)
target_compile_options(dreamcast PRIVATE -fwrapv -ffp-contract=off)
set(LAZYUSF "${BROAD}/lazyusf")
if(NOT EXISTS "${LAZYUSF}/.gremlin-patched")
    execute_process(COMMAND patch -p1 -i "${VGMPLAY2}/patches/lazyusf.patch"
        WORKING_DIRECTORY "${LAZYUSF}" COMMAND_ERROR_IS_FATAL ANY)
    file(TOUCH "${LAZYUSF}/.gremlin-patched")
endif()
add_subdirectory("${LAZYUSF}" lazyusf EXCLUDE_FROM_ALL)
target_compile_options(lazyusf PRIVATE -fcommon -fwrapv)
# Model 2 and Saturn share Mednafen's build configuration. The original
# Saturn frontend comes from the broad source archive.
set(SSF "${BROAD}/ssfplay_tools")
if(NOT EXISTS "${SSF}/mednafen/include/mednafen/types.h")
    file(MAKE_DIRECTORY "${SSF}/mednafen/include")
    file(CREATE_LINK "${SSF}/mednafen/src" "${SSF}/mednafen/include/mednafen" SYMBOLIC COPY_ON_ERROR)
endif()
get_target_property(SSF_FILES ssfplay SOURCES)
list(FILTER SSF_FILES EXCLUDE REGEX "^mednafen/src/(endian|hash/)")
list(TRANSFORM SSF_FILES REPLACE "^${M2S}/sas-source/" "${SSF}/")
list(TRANSFORM SSF_FILES REPLACE "^src/" "${SSF}/src/")
add_library(gremlinamp-ssf-core STATIC ${SSF_FILES})
target_include_directories(gremlinamp-ssf-core PUBLIC "${SSF}/include"
    PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/model2/generated" "${SSF}/mednafen/include")
target_compile_definitions(gremlinamp-ssf-core PRIVATE HAVE_CONFIG_H=1 MDFN_DISABLE_PICPIE_ERRWARN=1
    MDFN_DISABLE_NO_OPT_ERRWARN=1 SSFPLAY_STATIC=1)
target_compile_options(gremlinamp-ssf-core PRIVATE -fsigned-char -fwrapv -fno-fast-math)
target_link_libraries(gremlinamp-ssf-core PRIVATE z)
add_library(gremlinamp-psf SHARED psf_bridge.cpp "${GSF_SOURCE}/psflib/psflib.c")
target_include_directories(gremlinamp-psf PRIVATE "${GSF_SOURCE}/psflib")
target_link_libraries(gremlinamp-psf PRIVATE sexypsf dreamcast lazyusf gremlinamp-ssf-core z)
target_link_options(gremlinamp-psf PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")

include(native-streams.cmake)
