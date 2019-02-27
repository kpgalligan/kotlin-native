package org.jetbrains.kotlin.backend.konan.llvm.coverage

import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.llvm.column
import org.jetbrains.kotlin.backend.konan.llvm.line
import org.jetbrains.kotlin.backend.konan.llvm.symbolName
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
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

class FunctionRegionsImpl(
        override val function: IrFunction,
        override val regions: Map<IrElement, RegionImpl>
) : FunctionRegions {
    val regionEnumeration = (regions.values).mapIndexed { index, region -> region to index }.toMap()

    // Actually, it should be computed. But since we don't support PGO structural hash doesn't really matter for now.
    val structuralHash: Long = 0
}

private fun Region.dump() = buildString {
    append("${file.name}${(kind as? RegionKind.Expansion)?.let { " expand to " + it.expandedFile.name} ?: ""}: " +
            "${kind::class.simpleName} $startLine, $startColumn -> $endLine, $endColumn")
}

private fun FunctionRegionsImpl.dump() = buildString {
    appendln("${function.symbolName} regions:")
    regions.forEach { (irElem, region) -> appendln("${ir2string(irElem)} -> (${region.dump()})") }
}

class FileRegionInfoImpl(
        override val file: IrFile,
        override val functions: List<FunctionRegionsImpl>
) : FileRegionInfo

// TODO: Add support for user-specified klibs
private fun IrFile.shouldBeCovered(context: Context) =
        packageFragmentDescriptor.module == context.moduleDescriptor

internal class LLVMCoverageRegionCollector(val context: Context) : CoverageRegionCollector {
    override fun collectFunctionRegions(irModuleFragment: IrModuleFragment): List<FileRegionInfoImpl> =
            irModuleFragment.files
                    .filter { it.shouldBeCovered(context) }
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
                    println(regions.dump())
                    functionRegions += regions
                }
            }
            declaration.acceptChildrenVoid(this)
        }
        private fun collectRegions(irFile: IrFile, irFunction: IrFunction) =
                IrFunctionRegionsCollector(context, irFile).collect(irFunction)
    }
}

private class IrFunctionRegionsCollector(val context: Context, val irFile: IrFile) {

    fun collect(irFunction: IrFunction): FunctionRegionsImpl {
        val visitor = CollectorVisitor(context, irFile)
        visitor.visitFunction(irFunction)
        return FunctionRegionsImpl(irFunction, visitor.regions)
    }

    private class CollectorVisitor(val context: Context, irFile: IrFile) : IrElementVisitorVoid {

        private val irFileStack = mutableListOf(irFile)

        private val curFile: IrFile
            get() = irFileStack.last()

        val regions = mutableMapOf<IrElement, RegionImpl>()

        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitVariable(declaration: IrVariable) {
            declaration.initializer?.acceptVoid(this)
        }

        override fun visitSetVariable(expression: IrSetVariable) {
            expression.value.acceptVoid(this)
        }

        override fun <T> visitConst(expression: IrConst<T>) {
            recordRegion(expression, RegionKind.Code)
        }

        override fun visitBody(body: IrBody) = when (body) {
            is IrExpressionBody -> body.acceptChildrenVoid(this)
            is IrBlockBody -> body.acceptChildrenVoid(this)
            else -> error("Unexpected function body type: $body")
        }

        override fun visitCall(expression: IrCall) {
            recordRegion(expression, RegionKind.Code)
            expression.acceptChildrenVoid(this)
        }

        override fun visitWhen(expression: IrWhen) {
            expression.branches.forEach { branch ->
                // Do not record location for else branch since it doesn't look correct.
                if (branch.condition !is IrConst<*>) {
                    recordRegion(branch.condition, RegionKind.Code)
                    branch.condition.acceptChildrenVoid(this)
                }
                recordRegion(branch.result, RegionKind.Code)
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

        // Returnable block wraps the inlined subtree.
        private fun visitReturnableBlock(returnableBlock: IrReturnableBlock) {
            val file = (returnableBlock.sourceFileSymbol?.owner)
            if (file == null || file.packageFragmentDescriptor.module != context.moduleDescriptor) {
                return
            }
            println("Ruturnable block: ${RegionImpl.fromIr(returnableBlock, curFile, RegionKind.Expansion(file)).dump()}")
            recordRegion(returnableBlock, RegionKind.Code)
            // TODO: Should we cover block that is came from stdlib?
            irFileStack.push(file)
            returnableBlock.acceptChildrenVoid(this)
            irFileStack.pop()
        }

        private fun recordRegion(irElement: IrElement, kind: RegionKind) {
            if (irElement.startOffset == UNDEFINED_OFFSET || irElement.endOffset == UNDEFINED_OFFSET) {
                println("WARNING: ${ir2string(irElement)} has undefined offset. Region won't be recorded.")
                return
            }
            regions[irElement] = RegionImpl.fromIr(irElement, curFile, kind)
        }
    }
}