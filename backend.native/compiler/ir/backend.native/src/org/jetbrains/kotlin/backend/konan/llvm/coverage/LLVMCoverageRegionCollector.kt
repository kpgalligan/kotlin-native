package org.jetbrains.kotlin.backend.konan.llvm.coverage

import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.konan.ir.ir2stringWholezzz
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

// TODO: rename `shouldBeCovered`
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

    // Very similar to CodeGeneratorVisitor but instead generation of bitcode
    // we collect regions.
    private inner class CollectorVisitor : IrElementVisitorVoid {

        private val irFileStack = mutableListOf(irFile)

        private val currentFile: IrFile
            get() = irFileStack.last()

        val regions = mutableMapOf<IrElement, Region>()

        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitExpression(expression: IrExpression) {
            collectRegions(expression)
        }

        override fun visitVariable(declaration: IrVariable) {
            declaration.initializer?.let { collectRegions(it) }
        }

        override fun visitBody(body: IrBody) = when (body) {
            is IrExpressionBody -> body.acceptChildrenVoid(this)
            is IrBlockBody -> body.acceptChildrenVoid(this)
            else -> error("Unexpected function body type: $body")
        }

        fun collectRegions(value: IrExpression): Unit =  when (value) {
            is IrTypeOperatorCall -> collectTypeOperator(value)
            is IrCall -> collectCall(value)
            is IrDelegatingConstructorCall -> collectCall(value)
            is IrInstanceInitializerCall -> collectInstanceInitializerCall(value)
            is IrGetValue -> collectGetValue(value)
            is IrSetVariable -> collectSetVariable(value)
            is IrGetField -> collectGetField(value)
            is IrSetField -> collectSetField(value)
            is IrConst<*> -> collectConst(value)
            is IrReturn -> collectReturn(value)
            is IrWhen -> collectWhen(value)
            is IrThrow -> collectThrow(value)
            is IrTry -> collectTry(value)
            is IrReturnableBlock -> collectReturnableBlock(value)
            is IrContainerExpression -> collectContainerExpression(value)
            is IrWhileLoop -> collectWhileLoop(value)
            is IrDoWhileLoop -> collectDoWhileLoop(value)
            is IrVararg -> collectVararg(value)
            is IrBreak -> collectBreak(value)
            is IrContinue -> collectContinue(value)
            is IrGetObjectValue -> collectGetObjectValue(value)
            is IrFunctionReference -> collectFunctionReference(value)
            is IrSuspendableExpression -> collectSuspendableExpression(value)
            is IrSuspensionPoint -> collectSuspensionPoint(value)
            else -> {}
        }

        private fun collectInstanceInitializerCall(instanceInitializerCall: IrInstanceInitializerCall) {

        }

        private fun collectGetValue(getValue: IrGetValue) {
            recordRegion(getValue)
        }

        private fun collectSetVariable(setVariable: IrSetVariable) {
            setVariable.value.acceptVoid(this)
        }

        private fun collectGetField(getField: IrGetField) {
            getField.receiver?.let { collectRegions(it) }
        }

        private fun collectSetField(setField: IrSetField) {
            collectRegions(setField.value)
            setField.receiver?.let { collectRegions(it) }
        }

        private fun collectConst(const: IrConst<*>) {
            recordRegion(const)
        }

        private fun collectReturn(irReturn: IrReturn) {
//            recordRegion(irReturn)
            collectRegions(irReturn.value)
        }

        private fun collectWhen(irWhen: IrWhen) {
            irWhen.branches.forEach { branch ->
                // Do not record location for else branch since it doesn't look correct.
                if (branch.condition !is IrConst<*>) {
                    collectRegions(branch.condition)
                }
                collectRegions(branch.result)
            }
        }

        private fun collectThrow(irThrow: IrThrow) {
            collectRegions(irThrow.value)
            recordRegion(irThrow)
        }

        private fun collectTry(irTry: IrTry) {
        }

        private fun collectReturnableBlock(returnableBlock: IrReturnableBlock) {
            val file = (returnableBlock.sourceFileSymbol?.owner)
            if (file != null && shouldBeCovered(file)) {
                recordRegion(returnableBlock)
                irFileStack.push(file)
                returnableBlock.acceptChildrenVoid(this)
                irFileStack.pop()
            }
        }

        private fun collectContainerExpression(containerExpression: IrContainerExpression) {
            containerExpression.acceptChildrenVoid(this)
        }

        private fun collectWhileLoop(whileLoop: IrWhileLoop) {
            collectRegions(whileLoop.condition)
            whileLoop.body?.let { collectRegions(it) }
        }

        private fun collectDoWhileLoop(doWhileLoop: IrDoWhileLoop) {
            collectRegions(doWhileLoop.condition)
            doWhileLoop.body?.let { collectRegions(it) }
        }

        private fun collectVararg(vararg: IrVararg) {
            vararg.elements.forEach { it.acceptVoid(this) }
        }

        private fun collectBreak(irBreak: IrBreak) {
            recordRegion(irBreak)
        }

        private fun collectContinue(irContinue: IrContinue) {
            recordRegion(irContinue)
        }

        private fun collectGetObjectValue(getObjectValue: IrGetObjectValue) {

        }

        private fun collectFunctionReference(functionReference: IrFunctionReference) {

        }


        private fun collectSuspendableExpression(suspendableExpression: IrSuspendableExpression) {

        }

        private fun collectSuspensionPoint(suspensionPoint: IrSuspensionPoint) {

        }

        private fun collectTypeOperator(typeOperatorCall: IrTypeOperatorCall) {

        }

        private fun collectCall(call: IrFunctionAccessExpression) {
            recordRegion(call, RegionKind.Code)
            call.acceptChildrenVoid(this)
        }

        private fun recordRegion(irElement: IrElement, kind: RegionKind = RegionKind.Code) {
            if (irElement.startOffset == UNDEFINED_OFFSET || irElement.endOffset == UNDEFINED_OFFSET) {
                println("WARNING: ${ir2string(irElement)} has undefined offset. Region won't be recorded.")
                return
            }
            regions[irElement] = Region.fromIr(irElement, currentFile, kind)
        }
    }
}