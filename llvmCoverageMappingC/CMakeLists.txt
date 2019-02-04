cmake_minimum_required(VERSION 3.13)
project(CoverageMappingC)

set(CMAKE_CXX_STANDARD 14)
set(LLVM_ROOT "/Users/jetbrains/.konan/dependencies/clang-llvm-6.0.1-darwin-macos" CACHE PATH "Root of LLVM install.")

if(NOT EXISTS ${LLVM_ROOT}/include/llvm)
    message(FATAL_ERROR "LLVM_ROOT (${LLVM_ROOT}) is invalid")
endif()

list(APPEND CMAKE_PREFIX_PATH "${LLVM_ROOT}/lib/cmake/llvm")
find_package(LLVM REQUIRED CONFIG)

list(APPEND CMAKE_MODULE_PATH "${LLVM_CMAKE_DIR}")
include(HandleLLVMOptions) # load additional config include(AddLLVM) # used to add our own modules
include(AddLLVM) # used to add our own modules

add_definitions(${LLVM_DEFINITIONS})
include_directories(${LLVM_INCLUDE_DIRS})

include_directories(${CMAKE_SOURCE_DIR}/include)

include_directories(
        src/main/include
)
# TODO
add_library(qqq src/main/cpp/CoverageMappingC.cpp)