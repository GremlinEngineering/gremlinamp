cmake_minimum_required(VERSION 3.22.1)
project(gremlinamp)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory(game-music-emu)
add_library(gremlinamp SHARED bridge.cpp)
target_link_libraries(gremlinamp gme_static log z)

target_link_options(gremlinamp PRIVATE "-Wl,-z,max-page-size=16384")
