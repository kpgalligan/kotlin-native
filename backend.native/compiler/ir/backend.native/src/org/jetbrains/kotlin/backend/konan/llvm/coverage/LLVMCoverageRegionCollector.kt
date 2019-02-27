package org.jetbrains.kotlin.backend.konan.llvm.coverage

import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

internal class LLVMCoverageRegionCollector(private val shouldBeCovered: (IrFile) -> Boolean) : CoverageRegionCollector {

    override fun collectFunctionRegions(irModuleFragment: IrModuleFragment): List<FileRegionInfo> =
            irModuleFragment.files
                    .filter(shouldBeCovered)
                    .map { file ->
                        val collector = FunctionsCollector(file)
                        collector.visitFile(file)
                        FileRegionInfo(file, collector.functionRegions)
                    }

    private inner class FunctionsCollector(val file: IrFile) : IrElementVisitorVoid {

        val functionRegions = mutableListOf<FunctionRegions>()

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
                IrFunctionRegionsCollector(shouldBeCovered, irFile).collect(irFunction)
    }
}

private class IrFunctionRegionsCollector(val shouldBeCovered: (IrFile) -> Boolean, val irFile: IrFile) {

    fun collect(irFunction: IrFunction): FunctionRegions {
        val visitor = CollectorVisitor()
        visitor.visitFunction(irFunction)
        return FunctionRegions(irFunction, visitor.regions)
    }

    private inner class CollectorVisitor : IrElementVisitorVoid {

        private val irFileStack = mutableListOf(irFile)

        private val currentFile: IrFile
            get() = irFileStack.last()

        val regions = mutableMapOf<IrElement, Region>()

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
            if (file != null && shouldBeCovered(file)) {
                println("Ruturnable block: ${Region.fromIr(returnableBlock, currentFile, RegionKind.Expansion(file)).dump()}")
                recordRegion(returnableBlock, RegionKind.Code)
                irFileStack.push(file)
                returnableBlock.acceptChildrenVoid(this)
                irFileStack.pop()
            }
        }

        private fun recordRegion(irElement: IrElement, kind: RegionKind) {
            if (irElement.startOffset == UNDEFINED_OFFSET || irElement.endOffset == UNDEFINED_OFFSET) {
                println("WARNING: ${ir2string(irElement)} has undefined offset. Region won't be recorded.")
                return
            }
            regions[irElement] = Region.fromIr(irElement, currentFile, kind)
        }
    }
}