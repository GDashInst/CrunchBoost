cmake_minimum_required(VERSION 3.22.1)
project(crunch LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Oboe comes from the com.google.oboe:oboe Gradle dependency via Prefab.
find_package(oboe REQUIRED CONFIG)

add_library(crunch SHARED CrunchEngine.cpp jni_bridge.cpp)
target_compile_options(crunch PRIVATE -Wall -O2)
target_link_libraries(crunch oboe::oboe log)
