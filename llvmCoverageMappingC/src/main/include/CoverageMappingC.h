/*
 * Copyright 2010-2019 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef __COVERAGE_MAPPING_C_H__
# define __COVERAGE_MAPPING_C_H__

#include <llvm-c/Core.h>

# ifdef __cplusplus
extern "C" {
# endif

/**
 * See org.jetbrains.kotlin.backend.konan.llvm.coverage.RegionKind.
 */
enum LLVMCoverageRegionKind {
    CODE,
    GAP,
    EXPANSION
};

/**
 * See org.jetbrains.kotlin.backend.konan.llvm.coverage.Region.
 */
struct LLVMCoverageRegion {
    int fileId;
    int lineStart;
    int columnStart;
    int lineEnd;
    int columnEnd;
    int counterId;
    int expandedFileId;
    enum LLVMCoverageRegionKind kind;
};

struct LLVMFunctionCoverage {
    const char* coverageData;
    size_t size;
};

/**
 * Add record in the following format: https://llvm.org/docs/CoverageMappingFormat.html#function-record.
 */
LLVMValueRef
LLVMAddFunctionMappingRecord(LLVMContextRef context, const char *name, uint64_t hash, struct LLVMFunctionCoverage coverageMapping);

/**
 * Wraps creation of coverage::CoverageMappingWriter and call to coverage::CoverageMappingWriter::write.
 */
struct LLVMFunctionCoverage LLVMWriteCoverageRegionMapping(unsigned int *fileIdMapping, size_t fileIdMappingSize,
                                           struct LLVMCoverageRegion **mappingRegions, size_t mappingRegionsSize);

/**
 * Create __llvm_coverage_mapping global.
 */
LLVMValueRef LLVMCoverageEmit(LLVMModuleRef moduleRef, LLVMValueRef *records, size_t recordsSize,
        const char **filenames, int *filenamesIndices, size_t filenamesSize,
        struct LLVMFunctionCoverage** functionCoverages, size_t functionCoveragesSize);

/**
 * Wrapper for `llvm.instrprof.increment` declaration.
 */
LLVMValueRef LLVMInstrProfIncrement(LLVMModuleRef moduleRef);

/**
 * Wrapper for llvm::createPGOFuncNameVar.
 */
LLVMValueRef LLVMCreatePGOFunctionNameVar(LLVMValueRef llvmFunction, const char *pgoFunctionName);

# ifdef __cplusplus
}
# endif
#endif
