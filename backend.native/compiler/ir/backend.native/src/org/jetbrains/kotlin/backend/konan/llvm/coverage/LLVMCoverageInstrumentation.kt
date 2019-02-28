package org.jetbrains.kotlin.backend.konan.llvm.coverage

import llvm.LLVMConstBitCast
import llvm.LLVMCreatePGOFunctionNameVar
import llvm.LLVMInstrProfIncrement
import llvm.LLVMValueRef
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction

/**
 * Places calls to `llvm.instrprof.increment` in the beginning of the each
 * region in [functionRegions].
 */
internal class LLVMCoverageInstrumentation(
        override val context: Context,
        private val functionRegions: FunctionRegions,
        private val callSitePlacer: (function: LLVMValueRef, args: List<LLVMValueRef>) -> Unit
) : ContextUtils {

    private val functionNameGlobal = createFunctionNameGlobal(functionRegions.function)

    private val functionHash = Int64(functionRegions.structuralHash).llvm

    fun instrumentIrElement(element: IrElement) {
        functionRegions.regions[element]?.let {
            placeRegionIncrement(it)
        }
    }

    /**
     * See https://llvm.org/docs/LangRef.html#llvm-instrprof-increment-intrinsic
     */
    private fun placeRegionIncrement(region: Region) {
        val numberOfRegions = Int32(functionRegions.regions.size).llvm
        val regionNumber = Int32(functionRegions.regionEnumeration.getValue(region)).llvm
        val args = listOf(functionNameGlobal, functionHash, numberOfRegions, regionNumber)
        callSitePlacer(LLVMInstrProfIncrement(context.llvmModule)!!, args)
    }

    // Each profiled function should have a global with it's name in a specific format.
    private fun createFunctionNameGlobal(function: IrFunction): LLVMValueRef {
        val pgoFunctionName = LLVMCreatePGOFunctionNameVar(function.llvmFunction, function.symbolName)!!
        return LLVMConstBitCast(pgoFunctionName, int8TypePtr)!!
    }
}