package org.jetbrains.kotlin.backend.konan.llvm.coverage

import llvm.LLVMValueRef
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.KonanConfigKeys
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

internal class LLVMCoverageManager(val context: Context) {

    val enabled: Boolean
        get() = context.config.configuration.getBoolean(KonanConfigKeys.COVERAGE)

    private val filesRegionsInfo = mutableListOf<FileRegionInfoImpl>()

    private fun getFunctionRegions(irFunction: IrFunction): FunctionRegionsImpl? =
            filesRegionsInfo.flatMap { it.functions }.firstOrNull { it.function == irFunction }

    fun getInstrumentation(irFunction: IrFunction?, callSitePlacer: (function: LLVMValueRef, args: List<LLVMValueRef>) -> Unit): CoverageInstrumentation =
            if (enabled && irFunction != null) {
                val regions = getFunctionRegions(irFunction)
                if (regions == null) {
                    EmptyCoverageInstrumentation()
                } else {
                    LLVMCoverageInstrumentation(context, regions, callSitePlacer)
                }
            } else {
                EmptyCoverageInstrumentation()
            }

    fun collectRegions(irModuleFragment: IrModuleFragment) {
        filesRegionsInfo += if (enabled)
            LLVMCoverageRegionCollector(context).collectFunctionRegions(irModuleFragment)
        else
            emptyList()
    }

    fun writeRegionInfo() {
        LLVMCoverageWriter(context, filesRegionsInfo).write()
    }
}