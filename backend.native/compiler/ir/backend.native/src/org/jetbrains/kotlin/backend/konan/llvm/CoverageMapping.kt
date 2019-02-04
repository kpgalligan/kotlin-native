package org.jetbrains.kotlin.backend.konan.llvm

import org.jetbrains.kotlin.backend.common.IrElementVisitorVoidWithContext
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.name
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid


// #coverage TODO: Probably should hold either irFile ref or FileID
internal class SourceMappingRegion(
        val startLine: Int,
        val startColumn: Int,
        val endLine: Int,
        val endColumn: Int
) {
    override fun toString(): String =
            "$startLine:$startColumn - $endLine:$endColumn"
}

internal class FunctionMapping(
        val pathToFile: String,
        val function: String,
        val regions: List<SourceMappingRegion>
)

internal class CoverageMappings(
        val functionMappings: List<FunctionMapping>
)

internal class CoverageMappingsBuilder {

    private val toCover = mutableMapOf<IrFile, MutableList<IrFunction>>()

    // TODO: #coverage Store file id as integer.
    fun collect(file: IrFile, irFunction: IrFunction) {
        if (file !in toCover) {
            toCover[file] = mutableListOf()
        }
        toCover.getValue(file).add(irFunction)
    }

    fun build(): CoverageMappings =
        toCover.flatMap { (file, functions) -> functions.map { file to it} }
            .map { (file, function) ->
                val regions = collectFunctionRegions(file, function)
                FunctionMapping(file.name, function.functionName, regions)
            }
            .filter { it.regions.isEmpty() }
            .let {
                CoverageMappings(it)
            }

    private fun collectFunctionRegions(file: IrFile, irFunction: IrFunction): List<SourceMappingRegion> =
        irFunction.body?.let { body ->
            val coverageVisitor = CoverageVisitor(file)
            body.acceptVoid(coverageVisitor)
            coverageVisitor.regionStack
        } ?: emptyList()

    // TODO: #coverage extend to other IrElements
    // TODO: #coverage What if we meet nested function?
    private class CoverageVisitor(val file: IrFile) : IrElementVisitorVoidWithContext() {

        val regionStack = mutableListOf<SourceMappingRegion>()

        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitBody(body: IrBody) {

            regionStack.push(SourceMappingRegion(
                    file.fileEntry.line(body.startOffset),
                    file.fileEntry.column(body.startOffset),
                    file.fileEntry.line(body.endOffset),
                    file.fileEntry.column(body.endOffset)
            ))
        }
    }
}

internal interface CoverageMappingsWriter {
    fun write(coverageMappings: CoverageMappings)
}

internal class PrintCoverageMappingsWriter : CoverageMappingsWriter {
    override fun write(coverageMappings: CoverageMappings) {
        coverageMappings.functionMappings.forEach {
            println("${it.pathToFile} ${it.function}")
            it.regions.forEachIndexed { index, region ->
                println("$index: $region")
            }
        }
    }
}

internal class LLVMCoverageMappingsWriter(val context: Context) : CoverageMappingsWriter {
    override fun write(coverageMappings: CoverageMappings) {
        val module = context.llvmModule ?: error("LLVM module should be initialized")


    }
}

