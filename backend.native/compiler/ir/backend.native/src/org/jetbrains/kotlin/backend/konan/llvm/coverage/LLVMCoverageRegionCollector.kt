package org.jetbrains.kotlin.backend.konan.llvm.coverage

import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.ir.IrFileImpl
import org.jetbrains.kotlin.backend.konan.ir.KonanIrReturnableBlockImpl
import org.jetbrains.kotlin.backend.konan.ir.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.backend.konan.llvm.column
import org.jetbrains.kotlin.backend.konan.llvm.line
import org.jetbrains.kotlin.backend.konan.llvm.symbolName
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.name
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.resolve.descriptorUtil.module

class RegionImpl(
        override val file: IrFile,
        override val startLine: Int,
        override val startColumn: Int,
        override val endLine: Int,
        override val endColumn: Int,
        override val kind: RegionKind
) : Region {
    companion object {
        fun fromIr(irElement: IrElement, irFile: IrFile, kind: RegionKind) =
                fromOffset(irElement.startOffset, irElement.endOffset, irFile, kind)

        fun fromOffset(startOffset: Int, endOffset: Int, irFile: IrFile, kind: RegionKind) =
                RegionImpl(
                        irFile,
                        irFile.fileEntry.line(startOffset),
                        irFile.fileEntry.column(startOffset),
                        irFile.fileEntry.line(endOffset),
                        irFile.fileEntry.column(endOffset),
                        kind
                )
    }
}

private data class Gap(val startOffset: Int, val endOffset: Int) {
    fun toRegion(irFile: IrFile): RegionImpl =
            RegionImpl.fromOffset(startOffset, endOffset, irFile, RegionKind.Gap)
}

class FunctionRegionsImpl(
        override val function: IrFunction,
        override val regions: Map<IrElement, RegionImpl>,
        val gaps: List<RegionImpl>
) : FunctionRegions {
    val regionEnumeration = (regions.values + gaps).mapIndexed { index, region -> region to index }.toMap()

    // Actually, it should be computed. But since we don't support PGO it doesn't really matter for now.
    val structuralHash: Long = 0
}

private fun Region.dump() = buildString {
    append("${file.name}: ${kind::class.simpleName} $startLine, $startColumn -> $endLine, $endColumn")
}

private fun FunctionRegionsImpl.dump() = buildString {
    appendln("${function.symbolName} regions:")
    regions.forEach { (irElem, region) -> appendln("$irElem -> (${region.dump()})") }
    gaps.forEach { appendln("(${it.dump()})") }
}

class FileRegionInfoImpl(
        override val file: IrFile,
        override val functions: List<FunctionRegionsImpl>
) : FileRegionInfo

internal class LLVMCoverageRegionCollector(val context: Context) : CoverageRegionCollector {
    override fun collectFunctionRegions(irModuleFragment: IrModuleFragment): List<FileRegionInfoImpl> =
            irModuleFragment.files
                    // TODO: Add support for user-specified klibs
                    .filter { it.packageFragmentDescriptor.module == context.moduleDescriptor }
                    .map {
                        val collector = FunctionsCollector(it, context)
                        collector.visitFile(it)
                        FileRegionInfoImpl(it, collector.functionRegions)
                    }

    private class FunctionsCollector(val file: IrFile, val context: Context) : IrElementVisitorVoid {

        val functionRegions = mutableListOf<FunctionRegionsImpl>()

        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: IrFunction) {
            if (!declaration.isInline && !declaration.isExternal) {
                val regions = collectRegions(file, declaration)
                if (regions.regions.isNotEmpty()) {
                    functionRegions += regions
                }
            }
            declaration.acceptChildrenVoid(this)
        }
        private fun collectRegions(irFile: IrFile, irFunction: IrFunction) =
                IrFunctionRegionsCollector(context, irFile).collect(irFunction).also { println(it.dump()) }
    }
}

private class IrFunctionRegionsCollector(val context: Context, val irFile: IrFile) {

    private val dummyFile = IrFileImpl(NaiveSourceBasedFileEntryImpl("no source file"))

    fun collect(irFunction: IrFunction): FunctionRegionsImpl {
        val visitor = Visitor()
        visitor.visitFunction(irFunction)
        return FunctionRegionsImpl(irFunction, visitor.regions, visitor.gaps)
    }

    private inner class Visitor : IrElementVisitorVoid {

        private val irFileStack = mutableListOf(irFile)

        private val curFile: IrFile
            get() = irFileStack.last()

        val regions = mutableMapOf<IrElement, RegionImpl>()

        val gaps = mutableListOf<RegionImpl>()

        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitBody(body: IrBody) = when (body) {
            is IrExpressionBody -> body.acceptChildrenVoid(this)
            is IrBlockBody -> body.acceptChildrenVoid(this)
            else -> error("Unexpected function body type: $body")
        }

        override fun visitCall(expression: IrCall) {
            recordRegion(expression, RegionKind.Code, "callsite")
            expression.acceptChildrenVoid(this)
        }

        override fun visitWhen(expression: IrWhen) {
            expression.branches.forEach { branch ->
                // Do not record location for else branch since it doesn't look correct.
                if (branch.condition !is IrConst<*>) {
                    recordRegion(branch.condition, RegionKind.Code, "condition")
                    branch.condition.acceptChildrenVoid(this)
                }
                recordRegion(branch.result, RegionKind.Code, "branch")
                branch.result.acceptChildrenVoid(this)
            }
        }

        override fun visitContainerExpression(expression: IrContainerExpression) {
            expression.acceptChildrenVoid(this)
        }

        override fun visitBlock(expression: IrBlock) {
            if (expression is IrReturnableBlock)
                visitReturnableBlock(expression)
            else
                expression.acceptChildrenVoid(this)
        }

        private fun visitReturnableBlock(returnableBlock: IrReturnableBlock) {
            val file = (returnableBlock as? KonanIrReturnableBlockImpl)?.sourceFile
                    ?: dummyFile
            if (file.packageFragmentDescriptor.module != context.moduleDescriptor) {
                return
            }
            // Returnable block wraps the inlined subtree.
            // TODO: Should we cover block that is came from stdlib?
            irFileStack.push(file)
            returnableBlock.acceptChildrenVoid(this)
            irFileStack.pop()
        }

        private fun recordRegion(irElement: IrElement, kind: RegionKind, comment: String = "") {
            regions[irElement] = RegionImpl.fromIr(irElement, curFile, kind)
            if (comment.isNotEmpty()) {
                println("Added region for $comment")
                println("$irElement -> ${regions.getValue(irElement).dump()}")
            }
        }
    }
}