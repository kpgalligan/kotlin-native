package org.jetbrains.kotlin.backend.konan.llvm.coverage

import llvm.LLVMValueRef
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.KonanConfigKeys
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.resolve.descriptorUtil.module

/**
 * "Umbrella" class of all the of the code coverage related logic.
 */
internal class CoverageManager(val context: Context) {

    val enabled: Boolean
        get() = context.config.configuration.getBoolean(KonanConfigKeys.COVERAGE)

    private val filesRegionsInfo = mutableListOf<FileRegionInfo>()

    private fun getFunctionRegions(irFunction: IrFunction) =
            filesRegionsInfo.flatMap { it.functions }.firstOrNull { it.function == irFunction }

    // TODO: make coverage mode explicit
    private val coveredModules: Set<ModuleDescriptor> by lazy {
        val librariesToCover = context.config.configuration.getList(KonanConfigKeys.LIBRARIES_TO_COVER).toSet()
        if (librariesToCover.isEmpty()) {
            setOf(context.moduleDescriptor)
        } else {
            context.irModules.filter { it.key in librariesToCover }.values.map { it.descriptor }.toSet()
        }
    }

    private fun fileCoverageFilter(file: IrFile) =
            file.packageFragmentDescriptor.module in coveredModules

    /**
     * Walk [irModuleFragment] subtree and collect [FileRegionInfo] for files that are part of [coveredModules].
     */
    fun collectRegions(irModuleFragment: IrModuleFragment) {
        if (enabled) {
            val regions = CoverageRegionCollector(this::fileCoverageFilter).collectFunctionRegions(irModuleFragment)
            filesRegionsInfo += regions
        }
    }

    /**
     * @return [LLVMCoverageInstrumentation] instance if [irFunction] should be covered.
     */
    fun getInstrumentation(irFunction: IrFunction?, callSitePlacer: (function: LLVMValueRef, args: List<LLVMValueRef>) -> Unit) =
            if (enabled && irFunction != null) {
                getFunctionRegions(irFunction)?.let { LLVMCoverageInstrumentation(context, it, callSitePlacer) }
            } else {
                null
            }

    /**
     * Add __llvm_coverage_mapping to the LLVM module.
     */
    fun writeRegionInfo() {
        LLVMCoverageWriter(context, filesRegionsInfo).write()
    }
}