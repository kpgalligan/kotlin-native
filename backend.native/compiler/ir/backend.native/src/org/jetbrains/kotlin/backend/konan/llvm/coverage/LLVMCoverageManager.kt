package org.jetbrains.kotlin.backend.konan.llvm.coverage

import llvm.LLVMValueRef
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.KonanConfigKeys
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.resolve.descriptorUtil.module

internal class LLVMCoverageManager(val context: Context) {

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

    private fun shouldBeCovered(file: IrFile) =
            file.packageFragmentDescriptor.module in coveredModules

    fun getInstrumentation(irFunction: IrFunction?, callSitePlacer: (function: LLVMValueRef, args: List<LLVMValueRef>) -> Unit): CoverageInstrumentation? =
            if (enabled && irFunction != null) {
                getFunctionRegions(irFunction)?.let { LLVMCoverageInstrumentation(context, it, callSitePlacer) }
            } else {
                null
            }

    fun collectRegions(irModuleFragment: IrModuleFragment) {
        if (enabled) {
            filesRegionsInfo += LLVMCoverageRegionCollector(this::shouldBeCovered).collectFunctionRegions(irModuleFragment).also {
                it.flatMap { it.functions }.forEach { println(it.dump()) }
            }
        }
    }

    fun writeRegionInfo() {
        LLVMCoverageWriter(context, filesRegionsInfo).write()
    }
}