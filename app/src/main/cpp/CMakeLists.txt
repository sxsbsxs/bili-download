cmake_minimum_required(VERSION 3.22.1)
project(bilidl_core LANGUAGES CXX)

add_library(bilidl_core SHARED native_transfer.cpp)
target_compile_features(bilidl_core PRIVATE cxx_std_17)
target_compile_options(bilidl_core PRIVATE -Wall -Wextra -Werror)
