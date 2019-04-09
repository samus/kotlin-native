/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan

import llvm.*
import org.jetbrains.kotlin.backend.konan.llvm.parseBitcodeFile
import org.jetbrains.kotlin.konan.KonanExternalToolFailure
import org.jetbrains.kotlin.konan.exec.Command
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.konan.file.isBitcode
import org.jetbrains.kotlin.konan.target.*

typealias BitcodeFile = String
typealias ObjectFile = String
typealias ExecutableFile = String

internal fun mangleSymbol(target: KonanTarget,symbol: String) =
        if (target.family == Family.IOS || target.family == Family.OSX) {
            "_$symbol"
        } else {
            symbol
        }

internal fun shouldRunLateBitcodePasses(context: Context): Boolean {
    return context.coverage.enabled
}

internal fun runLateBitcodePasses(context: Context, llvmModule: LLVMModuleRef) {
    val passManager = LLVMCreatePassManager()!!
    val targetLibraryInfo = LLVMGetTargetLibraryInfo(llvmModule)
    LLVMAddTargetLibraryInfo(targetLibraryInfo, passManager)
    context.coverage.addLateLlvmPasses(passManager)
    LLVMRunPassManager(passManager, llvmModule)
    LLVMDisposePassManager(passManager)
}

private fun determineLinkerOutput(context: Context): LinkerOutputKind =
    when (context.config.produce) {
        CompilerOutputKind.FRAMEWORK -> {
            val staticFramework = context.config.produceStaticFramework
            if (staticFramework) LinkerOutputKind.STATIC_LIBRARY else LinkerOutputKind.DYNAMIC_LIBRARY
        }
        CompilerOutputKind.DYNAMIC -> LinkerOutputKind.DYNAMIC_LIBRARY
        CompilerOutputKind.STATIC -> LinkerOutputKind.STATIC_LIBRARY
        CompilerOutputKind.PROGRAM -> LinkerOutputKind.EXECUTABLE
        else -> TODO("${context.config.produce} should not reach native linker stage")
    }

internal class BitcodeCompiler(val context: Context) {

    private val target = context.config.target
    private val platform = context.config.platform
    private val optimize = context.shouldOptimize()
    private val debug = context.config.debug

    private fun MutableList<String>.addNonEmpty(elements: List<String>) {
        addAll(elements.filter { !it.isEmpty() })
    }

    private val exportedSymbols = context.coverage.addExportedSymbols()

    private fun runTool(command: List<String>) = runTool(*command.toTypedArray())
    private fun runTool(vararg command: String) =
            Command(*command)
                    .logWith(context::log)
                    .execute()

    private fun temporary(name: String, suffix: String): String =
            context.config.tempFiles.create(name, suffix).absolutePath

    private fun targetTool(tool: String, vararg arg: String) {
        val absoluteToolName = if (platform.configurables is AppleConfigurables) {
            "${platform.absoluteTargetToolchain}/usr/bin/$tool"
        } else {
            "${platform.absoluteTargetToolchain}/bin/$tool"
        }
        runTool(absoluteToolName, *arg)
    }

    private fun hostLlvmTool(tool: String, vararg arg: String) {
        val absoluteToolName = "${platform.absoluteLlvmHome}/bin/$tool"
        runTool(absoluteToolName, *arg)
    }

    private fun llvmLto(files: List<BitcodeFile>): ObjectFile {
        val configurables = platform.configurables as LlvmLtoFlags
        val combined = temporary("combined", ".o")
        val arguments = mutableListOf<String>().apply {
            addNonEmpty(configurables.llvmLtoFlags)
            addNonEmpty(llvmProfilingFlags())
            when {
                optimize -> addNonEmpty(configurables.llvmLtoOptFlags)
                debug -> addNonEmpty(platform.llvmDebugOptFlags)
                else -> addNonEmpty(configurables.llvmLtoNooptFlags)
            }
            addNonEmpty(configurables.llvmLtoDynamicFlags)
            addNonEmpty(files)
            // Prevent symbols from being deleted by DCE.
            addNonEmpty(exportedSymbols.map { "-exported-symbol=${mangleSymbol(target, it)}"} )
        }
        hostLlvmTool("llvm-lto", "-o", combined, *arguments.toTypedArray())
        return combined
    }

    private fun llvmLink(bitcodeFiles: List<BitcodeFile>): BitcodeFile {
        val combinedBc = temporary("link_output", ".bc")
        hostLlvmTool("llvm-link", *bitcodeFiles.toTypedArray(), "-o", combinedBc)
        return combinedBc
    }

    private fun opt(bitcodeFile: BitcodeFile): BitcodeFile {
        val optFlags = platform.configurables as OptFlags
        val flags = (optFlags.optFlags + when {
            optimize -> optFlags.optOptFlags
            debug -> optFlags.optDebugFlags
            else -> optFlags.optNooptFlags
        } + llvmProfilingFlags()).toTypedArray()
        val optimizedBc = temporary("opt_output", ".bc")
        hostLlvmTool("opt", bitcodeFile, "-o", optimizedBc, *flags)

        if (shouldRunLateBitcodePasses(context)) {
            val module = parseBitcodeFile(optimizedBc)
            runLateBitcodePasses(context, module)
            LLVMWriteBitcodeToFile(module, optimizedBc)
        }

        return optimizedBc
    }

    private fun llc(bitcodeFile: BitcodeFile): ObjectFile {
        val llcFlags = platform.configurables as LlcFlags
        val flags = (llcFlags.llcFlags + when {
            optimize -> llcFlags.llcOptFlags
            debug -> llcFlags.llcDebugFlags
            else -> llcFlags.llcNooptFlags
        } + llvmProfilingFlags()).toTypedArray()
        val combinedO = temporary("llc_output", ".o")
        hostLlvmTool("llc", bitcodeFile, "-o", combinedO, *flags, "-filetype=obj")
        return combinedO
    }

    private fun bitcodeToWasm(bitcodeFiles: List<BitcodeFile>): String {
        val combinedBc = llvmLink(bitcodeFiles)
        val optimizedBc = opt(combinedBc)
        val compiled = llc(optimizedBc)

        // TODO: should be moved to linker.
        val lldFlags = platform.configurables as LldFlags
        val linkedWasm = temporary("linked", ".wasm")
        hostLlvmTool("wasm-ld", compiled, "-o", linkedWasm, *lldFlags.lldFlags.toTypedArray())

        return linkedWasm
    }

    private fun llvmLinkAndLlc(bitcodeFiles: List<BitcodeFile>): String {
        val combinedBc = llvmLink(bitcodeFiles)

        val optimizedBc = temporary("optimized", ".bc")
        val optFlags = llvmProfilingFlags() + listOf("-O3", "-internalize", "-globaldce")
        hostLlvmTool("opt", combinedBc, "-o=$optimizedBc", *optFlags.toTypedArray())

        val combinedO = temporary("combined", ".o")
        val llcFlags = llvmProfilingFlags() + listOf("-function-sections", "-data-sections")
        hostLlvmTool("llc", optimizedBc, "-filetype=obj", "-o", combinedO, *llcFlags.toTypedArray())

        return combinedO
    }

    private fun clang(file: BitcodeFile): ObjectFile {
        val clangFlags = platform.configurables as ClangFlags
        val objectFile = temporary("result", ".o")

        val flags = mutableListOf<String>().apply {
            addNonEmpty(clangFlags.clangFlags)
            addNonEmpty(listOf("-triple", context.llvm.targetTriple))
            addNonEmpty(when {
                optimize -> clangFlags.clangOptFlags
                debug -> clangFlags.clangDebugFlags
                else -> clangFlags.clangNooptFlags
            })
            addNonEmpty(BitcodeEmbedding.getClangOptions(context.config))
            addNonEmpty(clangFlags.clangDynamicFlags)
        }
        targetTool("clang++", *flags.toTypedArray(), file, "-o", objectFile)
        return objectFile
    }

    // llvm-lto, opt and llc share same profiling flags, so we can
    // reuse this function.
    private fun llvmProfilingFlags(): List<String> {
        val flags = mutableListOf<String>()
        if (context.shouldProfilePhases()) {
            flags += "-time-passes"
        }
        if (context.inVerbosePhase) {
            flags += "-debug-pass=Structure"
        }
        return flags
    }

    fun makeObjectFiles(bitcodeFile: BitcodeFile): List<ObjectFile> {
        val bitcodeLibraries = context.llvm.bitcodeToLink
        val additionalBitcodeFilesToLink = context.llvm.additionalProducedBitcodeFiles
        val bitcodeFiles = listOf(bitcodeFile) + additionalBitcodeFilesToLink +
                bitcodeLibraries.map { it.bitcodePaths }.flatten().filter { it.isBitcode }
        return listOf(when (platform.configurables) {
            is AppleConfigurables ->
                clang(opt(llvmLink(bitcodeFiles)))
            is WasmConfigurables ->
                bitcodeToWasm(bitcodeFiles)
            is ZephyrConfigurables ->
                llvmLinkAndLlc(bitcodeFiles)
            is LlvmLtoFlags ->
                llvmLto(bitcodeFiles)
            else ->
                error("Unsupported configurables kind: ${platform.configurables::class.simpleName}!")
        })
    }
}

// TODO: We have a Linker.kt file in the shared module.
internal class Linker(val context: Context) {

    private val platform = context.config.platform
    private val config = context.config.configuration
    private val linkerOutput = determineLinkerOutput(context)
    private val nomain = config.get(KonanConfigKeys.NOMAIN) ?: false
    private val linker = platform.linker
    private val target = context.config.target
    private val optimize = context.shouldOptimize()
    private val debug = context.config.debug

    // Ideally we'd want to have
    //      #pragma weak main = Konan_main
    // in the launcher.cpp.
    // Unfortunately, anything related to weak linking on MacOS
    // only seems to be working with dynamic libraries.
    // So we stick to "-alias _main _konan_main" on Mac.
    // And just do the same on Linux.
    private val entryPointSelector: List<String>
        get() = if (nomain || linkerOutput != LinkerOutputKind.EXECUTABLE) emptyList() else platform.entrySelector

    fun link(objectFiles: List<ObjectFile>) {
        val nativeDependencies = context.llvm.nativeDependenciesToLink
        val includedBinaries = nativeDependencies.map { it.includedPaths }.flatten()
        val libraryProvidedLinkerFlags = nativeDependencies.map { it.linkerOpts }.flatten()
        runLinker(objectFiles, includedBinaries, libraryProvidedLinkerFlags)
    }

    private fun asLinkerArgs(args: List<String>): List<String> {
        if (linker.useCompilerDriverAsLinker) {
            return args
        }

        val result = mutableListOf<String>()
        for (arg in args) {
            // If user passes compiler arguments to us - transform them to linker ones.
            if (arg.startsWith("-Wl,")) {
                result.addAll(arg.substring(4).split(','))
            } else {
                result.add(arg)
            }
        }
        return result
    }

    private fun runLinker(objectFiles: List<ObjectFile>,
                          includedBinaries: List<String>,
                          libraryProvidedLinkerFlags: List<String>): ExecutableFile? {
        val frameworkLinkerArgs: List<String>
        val executable: String

        if (context.config.produce != CompilerOutputKind.FRAMEWORK) {
            frameworkLinkerArgs = emptyList()
            executable = context.config.outputFile
        } else {
            val framework = File(context.config.outputFile)
            val dylibName = framework.name.removeSuffix(".framework")
            val dylibRelativePath = when (target.family) {
                Family.IOS -> dylibName
                Family.OSX -> "Versions/A/$dylibName"
                else -> error(target)
            }
            frameworkLinkerArgs = listOf("-install_name", "@rpath/${framework.name}/$dylibRelativePath")
            val dylibPath = framework.child(dylibRelativePath)
            dylibPath.parentFile.mkdirs()
            executable = dylibPath.absolutePath
        }

        try {
            File(executable).delete()
            linker.linkCommands(objectFiles = objectFiles, executable = executable,
                    libraries = linker.linkStaticLibraries(includedBinaries) + context.config.defaultSystemLibraries,
                    linkerArgs = entryPointSelector +
                            asLinkerArgs(config.getNotNull(KonanConfigKeys.LINKER_ARGS)) +
                            BitcodeEmbedding.getLinkerOptions(context.config) +
                            libraryProvidedLinkerFlags + frameworkLinkerArgs,
                    optimize = optimize, debug = debug, kind = linkerOutput,
                    outputDsymBundle = context.config.outputFile + ".dSYM").forEach {
                it.logWith(context::log)
                it.execute()
            }
        } catch (e: KonanExternalToolFailure) {
            context.reportCompilationError("${e.toolName} invocation reported errors")
        }
        return executable
    }

}
