cmake_minimum_required(VERSION 3.22.1)

project(xmax_yuv LANGUAGES C)

add_library(xmax_yuv SHARED yuv_converter.c)

target_compile_features(xmax_yuv PRIVATE c_std_11)
target_compile_options(xmax_yuv PRIVATE -O3 -fvisibility=hidden)
