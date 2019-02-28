// TODO: LICENSE

#include <CoverageMappingC.h>

#include "CoverageMappingC.h"

#include <llvm/ProfileData/Coverage/CoverageMapping.h>
#include <llvm/ProfileData/Coverage/CoverageMappingWriter.h>
#include <llvm/IR/GlobalVariable.h>
#include <llvm/Support/raw_ostream.h>
#include <llvm/ADT/Triple.h>
#include <llvm/IR/LLVMContext.h>
#include <llvm/IR/Module.h>
#include <llvm/IR/Type.h>
#include <llvm/IR/DerivedTypes.h>
#include <llvm/IR/Constants.h>
#include <llvm/IR/Intrinsics.h>
#include <llvm/Support/FileSystem.h>
#include <llvm/Support/Path.h>
#include <string>
#include <vector>
#include <iostream>
#include <iomanip>

using namespace llvm;
using namespace llvm::coverage;

static coverage::CounterMappingRegion::RegionKind determineRegionKind(const struct LLVMCoverageRegion& region) {
    switch (region.kind) {
        case LLVMCoverageRegionKind::CODE:
            return coverage::CounterMappingRegion::RegionKind::CodeRegion;
        case LLVMCoverageRegionKind::GAP:
            return coverage::CounterMappingRegion::RegionKind::GapRegion;
        case LLVMCoverageRegionKind::EXPANSION:
            return coverage::CounterMappingRegion::RegionKind::ExpansionRegion;
    }
}

static coverage::CounterMappingRegion createCounterMappingRegion(struct LLVMCoverageRegion& region) {
    auto regionKind = determineRegionKind(region);
    int expandedFileId = 0;
    if (regionKind == coverage::CounterMappingRegion::RegionKind::ExpansionRegion) {
        expandedFileId = region.expandedFileId;
    }
    const Counter &counter = coverage::Counter::getCounter(region.counterId);
    return coverage::CounterMappingRegion(counter, region.fileId, expandedFileId, region.lineStart,
                                                        region.columnStart, region.lineEnd, region.columnEnd, regionKind);
}

LLVMFunctionCoverage LLVMWriteCoverageRegionMapping(unsigned int *fileIdMapping, size_t fileIdMappingSize,
                                                struct LLVMCoverageRegion **mappingRegions, size_t mappingRegionsSize) {
    std::vector<coverage::CounterMappingRegion> counterMappingRegions;
    for (size_t i = 0; i < mappingRegionsSize; ++i) {
        struct LLVMCoverageRegion region = *mappingRegions[i];
        counterMappingRegions.emplace_back(createCounterMappingRegion(region));
    }
    CoverageMappingWriter writer(ArrayRef<unsigned int>(fileIdMapping, fileIdMappingSize), None, counterMappingRegions);
    std::string CoverageMapping;
    raw_string_ostream OS(CoverageMapping);
    writer.write(OS);
    OS.flush();
    char *coverageData = new char[CoverageMapping.length() + 1];
    // Use memcpy because CoverageMapping could contain '\0' in it.
    std::memcpy(coverageData, CoverageMapping.c_str(), CoverageMapping.length());
    return LLVMFunctionCoverage{.coverageData = coverageData, .size = CoverageMapping.length()};
}

static StructType *getFunctionRecordTy(LLVMContext &Ctx) {
#define COVMAP_FUNC_RECORD(Type, LLVMType, Name, Init) LLVMType,
    Type *FunctionRecordTypes[] = {
#include "llvm/ProfileData/InstrProfData.inc"
    };
    StructType *FunctionRecordTy = StructType::get(Ctx, makeArrayRef(FunctionRecordTypes), true);
    return FunctionRecordTy;
}

static llvm::Constant *addFunctionMappingRecord(llvm::LLVMContext &Ctx, StringRef NameValue, uint64_t FuncHash,
                                                const std::string &CoverageMapping) {
    llvm::StructType *FunctionRecordTy = getFunctionRecordTy(Ctx);

#define COVMAP_FUNC_RECORD(Type, LLVMType, Name, Init) Init,
    llvm::Constant *FunctionRecordVals[] = {
#include "llvm/ProfileData/InstrProfData.inc"
    };
    return llvm::ConstantStruct::get(FunctionRecordTy, makeArrayRef(FunctionRecordVals));
}

// See https://github.com/llvm/llvm-project/blob/fa8fa044ec46b94e64971efa8852df0d58114062/clang/lib/CodeGen/CoverageMappingGen.cpp#L1284.
LLVMValueRef LLVMAddFunctionMappingRecord(LLVMContextRef context, const char *name, uint64_t hash,
                             struct LLVMFunctionCoverage coverageMapping) {
    return llvm::wrap(addFunctionMappingRecord(*llvm::unwrap(context), name, hash,
                                               std::string{coverageMapping.coverageData, coverageMapping.size}));
}

static std::string normalizeFilename(StringRef Filename) {
    llvm::SmallString<256> Path(Filename);
    llvm::sys::fs::make_absolute(Path);
    llvm::sys::path::remove_dots(Path, true);
    return Path.str().str();
}

// See https://github.com/llvm/llvm-project/blob/fa8fa044ec46b94e64971efa8852df0d58114062/clang/lib/CodeGen/CoverageMappingGen.cpp#L1335.
// Please note that llvm/ProfileData/InstrProfData.inc refers to variable names of the function that includes it. So be careful with renaming.
static llvm::GlobalVariable *emitCoverageGlobal(
        llvm::LLVMContext &Ctx,
        llvm::Module &module,
        std::vector<llvm::Constant *> &FunctionRecords,
        llvm::SmallDenseMap<const char *, unsigned, 8> &FileEntries,
        std::string &RawCoverageMappings,
        llvm::StructType *FunctionRecordTy) {
    auto *Int32Ty = llvm::Type::getInt32Ty(Ctx);

    // Create the filenames and merge them with coverage mappings
    llvm::SmallVector<std::string, 16> FilenameStrs;
    llvm::SmallVector<StringRef, 16> FilenameRefs;
    FilenameStrs.resize(FileEntries.size());
    FilenameRefs.resize(FileEntries.size());
    for (const auto &Entry : FileEntries) {
        auto I = Entry.second;
        FilenameStrs[I] = normalizeFilename(Entry.first);
        FilenameRefs[I] = FilenameStrs[I];
    }
    std::string FilenamesAndCoverageMappings;
    llvm::raw_string_ostream OS(FilenamesAndCoverageMappings);
    CoverageFilenamesSectionWriter(FilenameRefs).write(OS);
    OS << RawCoverageMappings;
    size_t CoverageMappingSize = RawCoverageMappings.size();
    size_t FilenamesSize = OS.str().size() - CoverageMappingSize;
    // Append extra zeroes if necessary to ensure that the size of the filenames
    // and coverage mappings is a multiple of 8.
    if (size_t Rem = OS.str().size() % 8) {
        CoverageMappingSize += 8 - Rem;
        for (size_t I = 0, S = 8 - Rem; I < S; ++I)
            OS << '\0';
    }
    auto *FilenamesAndMappingsVal =
            llvm::ConstantDataArray::getString(Ctx, OS.str(), false);

    // Create the deferred function records array
    auto RecordsTy =
            llvm::ArrayType::get(FunctionRecordTy, FunctionRecords.size());
    auto RecordsVal = llvm::ConstantArray::get(RecordsTy, FunctionRecords);

    llvm::Type *CovDataHeaderTypes[] = {
#define COVMAP_HEADER(Type, LLVMType, Name, Init) LLVMType,

#include "llvm/ProfileData/InstrProfData.inc"
    };
    auto CovDataHeaderTy =
            llvm::StructType::get(Ctx, makeArrayRef(CovDataHeaderTypes));
    llvm::Constant *CovDataHeaderVals[] = {
#define COVMAP_HEADER(Type, LLVMType, Name, Init) Init,

#include "llvm/ProfileData/InstrProfData.inc"
    };
    auto CovDataHeaderVal = llvm::ConstantStruct::get(
            CovDataHeaderTy, makeArrayRef(CovDataHeaderVals));

    // Create the coverage data record
    llvm::Type *CovDataTypes[] = {CovDataHeaderTy, RecordsTy, FilenamesAndMappingsVal->getType()};
    auto CovDataTy = llvm::StructType::get(Ctx, makeArrayRef(CovDataTypes));

    llvm::Constant *TUDataVals[] = {CovDataHeaderVal, RecordsVal, FilenamesAndMappingsVal};
    auto CovDataVal = llvm::ConstantStruct::get(CovDataTy, makeArrayRef(TUDataVals));

    return new llvm::GlobalVariable(
            module, CovDataTy, true, llvm::GlobalValue::InternalLinkage,
            CovDataVal, llvm::getCoverageMappingVarName());
}

LLVMValueRef LLVMCoverageEmit(LLVMModuleRef moduleRef,
        LLVMValueRef *records, size_t recordsSize,
        const char **filenames, int *filenamesIndices, size_t filenamesSize,
        struct LLVMFunctionCoverage **functionCoverages, size_t functionCoveragesSize) {
    LLVMContext &Ctx = *unwrap(LLVMGetModuleContext(moduleRef));
    Module &module = *unwrap(moduleRef);

    std::vector<Constant *> FunctionRecords;
    for (size_t i = 0; i < recordsSize; ++i) {
        FunctionRecords.push_back(dyn_cast<Constant>(unwrap(records[i])));
    }
    SmallDenseMap<const char *, unsigned, 8> FileEntries;
    for (size_t i = 0; i < filenamesSize; ++i) {
        FileEntries.insert(std::make_pair(filenames[i], filenamesIndices[i]));
    }
    std::vector<std::string> CoverageMappings;
    for (size_t i = 0; i < functionCoveragesSize; ++i) {
        CoverageMappings.emplace_back(std::string{(*functionCoverages[i]).coverageData, (*functionCoverages[i]).size});
    }
    StructType *FunctionRecordTy = getFunctionRecordTy(Ctx);
    std::string RawCoverageMappings = llvm::join(CoverageMappings.begin(), CoverageMappings.end(), "");
    GlobalVariable *coverageGlobal = emitCoverageGlobal(Ctx, module, FunctionRecords, FileEntries, RawCoverageMappings, FunctionRecordTy);

    const std::string &section = getInstrProfSectionName(IPSK_covmap, Triple(module.getTargetTriple()).getObjectFormat());
    coverageGlobal->setSection(section);
    coverageGlobal->setAlignment(8);
    return wrap(coverageGlobal);
}

LLVMValueRef LLVMInstrProfIncrement(LLVMModuleRef moduleRef) {
    Module &module = *unwrap(moduleRef);
    return wrap(Intrinsic::getDeclaration(&module, Intrinsic::instrprof_increment, None));
}

LLVMValueRef LLVMCreatePGOFunctionNameVar(LLVMValueRef llvmFunction, const char *pgoFunctionName) {
    auto *fnPtr = cast<llvm::Function>(unwrap(llvmFunction));
    return wrap(createPGOFuncNameVar(*fnPtr, pgoFunctionName));
}