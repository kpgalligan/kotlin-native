package org.jetbrains.kotlin.backend.konan.llvm.coverage

import llvm.LLVMValueRef
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.KonanConfigKeys
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.resolve.descriptorUtil.module

internal class LLVMCoverageManager(val context: Context) {

    val enabled: Boolean
        get() = context.config.configuration.getBoolean(KonanConfigKeys.COVERAGE)

    private val filesRegionsInfo = mutableListOf<FileRegionInfo>()

    private fun getFunctionRegions(irFunction: IrFunction): FunctionRegions? =
            filesRegionsInfo.flatMap { it.functions }.firstOrNull { it.function == irFunction }

    // TODO: Add support for user-specified klibs
    private fun IrFile.shouldBeCovered(context: Context) =
            packageFragmentDescriptor.module == context.moduleDescriptor

    fun getInstrumentation(irFunction: IrFunction?, callSitePlacer: (function: LLVMValueRef, args: List<LLVMValueRef>) -> Unit): CoverageInstrumentation? =
            if (enabled && irFunction != null) {
                getFunctionRegions(irFunction)?.let { LLVMCoverageInstrumentation(context, it, callSitePlacer) }
            } else {
                null
            }

    fun collectRegions(irModuleFragment: IrModuleFragment) {
        if (enabled) {
            filesRegionsInfo += LLVMCoverageRegionCollector { it.shouldBeCovered(context) }.collectFunctionRegions(irModuleFragment)
        }
    }

    fun writeRegionInfo() {
        LLVMCoverageWriter(context, filesRegionsInfo).write()
    }
}