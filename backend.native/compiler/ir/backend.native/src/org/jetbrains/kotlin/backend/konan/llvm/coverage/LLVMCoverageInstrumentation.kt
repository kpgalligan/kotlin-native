package org.jetbrains.kotlin.backend.konan.llvm.coverage

import llvm.LLVMConstBitCast
import llvm.LLVMCreatePGOFunctionNameVar
import llvm.LLVMInstrProfIncrement
import llvm.LLVMValueRef
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction

internal class LLVMCoverageInstrumentation(
        override val context: Context,
        private val functionRegions: FunctionRegions,
        private val callSitePlacer: (function: LLVMValueRef, args: List<LLVMValueRef>) -> Unit
) : CoverageInstrumentation, ContextUtils {

    private val functionNameGlobal = createFunctionNameGlobal(functionRegions.function)

    private val functionHash = Int64(functionRegions.structuralHash).llvm

    override fun instrumentIrElement(element: IrElement) {
        functionRegions.regions[element]?.let {
            placeRegionIncrement(it)
        }
    }

    private fun placeRegionIncrement(region: Region) {
        val numberOfRegions = Int32(functionRegions.regions.size).llvm
        val regionNum = Int32(functionRegions.regionEnumeration.getValue(region)).llvm
        callSitePlacer(LLVMInstrProfIncrement(context.llvmModule)!!, listOf(functionNameGlobal, functionHash, numberOfRegions, regionNum))
    }

    private fun createFunctionNameGlobal(function: IrFunction): LLVMValueRef {
        val name = function.symbolName
        val x = LLVMCreatePGOFunctionNameVar(function.llvmFunction, name)!!
        return LLVMConstBitCast(x, int8TypePtr)!!
    }
}