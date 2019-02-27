package org.jetbrains.kotlin.backend.konan.llvm.coverage

import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.backend.konan.llvm.column
import org.jetbrains.kotlin.backend.konan.llvm.line
import org.jetbrains.kotlin.backend.konan.llvm.symbolName
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.name


sealed class RegionKind {
    object Code : RegionKind()
    object Gap : RegionKind()
    class Expansion(val expandedFile: IrFile) : RegionKind()
}

class Region(
        val file: IrFile,
        val startLine: Int,
        val startColumn: Int,
        val endLine: Int,
        val endColumn: Int,
        val kind: RegionKind
) {

    companion object {
        fun fromIr(irElement: IrElement, irFile: IrFile, kind: RegionKind) =
                fromOffset(irElement.startOffset, irElement.endOffset, irFile, kind)

        fun fromOffset(startOffset: Int, endOffset: Int, irFile: IrFile, kind: RegionKind) =
                Region(
                        irFile,
                        irFile.fileEntry.line(startOffset),
                        irFile.fileEntry.column(startOffset),
                        irFile.fileEntry.line(endOffset),
                        irFile.fileEntry.column(endOffset),
                        kind
                )
    }
}

class FunctionRegions(
        val function: IrFunction,
        val regions: Map<IrElement, Region>
)  {
    val regionEnumeration = (regions.values).mapIndexed { index, region -> region to index }.toMap()
    // Actually, it should be computed. But since we don't support PGO structural hash doesn't really matter for now.
    val structuralHash: Long = 0
}

internal fun Region.dump() = buildString {
    append("${file.name}${(kind as? RegionKind.Expansion)?.let { " expand to " + it.expandedFile.name }
            ?: ""}: " +
            "${kind::class.simpleName} $startLine, $startColumn -> $endLine, $endColumn")
}

internal fun FunctionRegions.dump() = buildString {
    appendln("${function.symbolName} regions:")
    regions.forEach { (irElem, region) -> appendln("${ir2string(irElem)} -> (${region.dump()})") }
}

class FileRegionInfo(
        val file: IrFile,
        val functions: List<FunctionRegions>
)

interface CoverageRegionCollector {
    fun collectFunctionRegions(irModuleFragment: IrModuleFragment): List<FileRegionInfo>
}

interface CoverageInstrumentation {
    fun instrumentIrElement(element: IrElement)
}