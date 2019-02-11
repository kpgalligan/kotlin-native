/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

// TODO: add reference
enum LLVMCoverageRegionKind {
    CODE,
    GAP,
    EXPANSION,
    // Consider dropping this kind since it's used only for conditional compilation.
    SKIP
};

struct LLVMCoverageRegion {
    int fileId;
    int lineStart;
    int columnStart;
    int lineEnd;
    int columnEnd;
    int counterId;
    int expandedFileId;
    // 0 - code
    enum LLVMCoverageRegionKind kind;
};

struct FunctionCoverage {
    const char* coverageData;
    size_t size;
};

typedef struct {
    void *ptr;
} LLVMCounterExpressionBuilderHandler;

void LLVMBuilderAddCounters(LLVMCounterExpressionBuilderHandler handler, int firstCounterId, int secondCounterId);

LLVMCounterExpressionBuilderHandler LLVMCreateCounterExpressionBuilder();

void LLVMDisposeCounterExpressionBuilder(LLVMCounterExpressionBuilderHandler handler);

LLVMValueRef
LLVMAddFunctionMappingRecord(LLVMContextRef context, const char *name, uint64_t hash, struct FunctionCoverage coverageMapping);

struct FunctionCoverage LLVMWriteCoverageRegionMapping(unsigned int *fileIdMapping, size_t fileIdMappingSize,
                                           struct LLVMCoverageRegion **mappingRegions, size_t mappingRegionsSize,
                                           LLVMCounterExpressionBuilderHandler counterExpressionBuilderHandler);

LLVMValueRef LLVMCoverageEmit(LLVMModuleRef moduleRef, LLVMValueRef *records, size_t recordsSize,
        const char **filenames, int *filenamesIndices, size_t filenamesSize,
        struct FunctionCoverage** functionCoverages, size_t functionCoveragesSize);

void LLVMCoverageAddFunctionNamesGlobal(LLVMContextRef context, LLVMModuleRef moduleRef,
                                        LLVMValueRef *functionNames, size_t functionNamesSize);

LLVMValueRef LLVMInstrProfIncrement(LLVMModuleRef moduleRef);

LLVMValueRef LLVMCreatePGOFunctionNameVar(LLVMValueRef llvmFunction, const char *pgoFunctionName);

# ifdef __cplusplus
}
# endif
#endif
