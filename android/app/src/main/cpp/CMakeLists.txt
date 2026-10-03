cmake_minimum_required(VERSION 3.22)
project(scandroid C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# SDL is downloaded by android/scripts/fetch-mobile-deps.sh. Building it here
# ensures the Android Java glue and libSDL3.so always come from the same tag.
set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST_LIBRARY OFF CACHE BOOL "" FORCE)
set(SDL_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../../../vendor/SDL ${CMAKE_BINARY_DIR}/SDL EXCLUDE_FROM_ALL)

add_library(scandroid SHARED sc_android_launcher.c)

target_link_libraries(
  scandroid
  PRIVATE
  SDL3::SDL3
  android
  log
  dl
)
