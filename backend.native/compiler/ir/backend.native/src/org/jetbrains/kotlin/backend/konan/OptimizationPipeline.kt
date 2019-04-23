package org.jetbrains.kotlin.backend.konan

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import llvm.*
import org.jetbrains.kotlin.konan.target.KonanTarget

// Initialize all required LLVM machinery, ex. target registry.
private fun initializeLlvm() {
    LLVMKotlinInitialize()

    val passRegistry = LLVMGetGlobalPassRegistry()

    LLVMInitializeCore(passRegistry)
    LLVMInitializeTransformUtils(passRegistry)
    LLVMInitializeScalarOpts(passRegistry)
    LLVMInitializeObjCARCOpts(passRegistry)
    LLVMInitializeVectorization(passRegistry)
    LLVMInitializeInstCombine(passRegistry)
    LLVMInitializeIPO(passRegistry)
    LLVMInitializeInstrumentation(passRegistry)
    LLVMInitializeAnalysis(passRegistry)
    LLVMInitializeIPA(passRegistry)
    LLVMInitializeCodeGen(passRegistry)
    LLVMInitializeTarget(passRegistry)
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

private val KonanTarget.cpuArchitecture: String
        get() = when (this) {
            KonanTarget.IOS_ARM32 -> "armv7"
            KonanTarget.IOS_ARM64 -> "arm64"
            KonanTarget.LINUX_X64 -> "x86-64"
            KonanTarget.MINGW_X86 -> "sandybridge"
            KonanTarget.MACOS_X64 -> "core2"
            KonanTarget.LINUX_ARM32_HFP -> "arm1136jf-s"
            else -> error("There is no support for ${this.name} target yet")
        }

private val KonanTarget.cpuFeatures: String
        get() = ""

private fun getInlineThreshold(context: Context): Int? = when {
    context.shouldOptimize() -> 100
    context.shouldContainDebugInfo() -> null
    else -> null
}

private fun getOptimizationLevel(context: Context): Int = when {
    context.shouldOptimize() -> 2
    context.shouldContainDebugInfo() -> 0
    else -> 1
}

internal fun runBitcodeOptimizationPipeline(context: Context) {
    val llvmModule = context.llvmModule!!
    val targetTriple = context.llvm.targetTriple

    memScoped {
        initializeLlvm()
        val passBuilder = LLVMPassManagerBuilderCreate()
        val modulePasses = LLVMCreatePassManager()
        LLVMPassManagerBuilderSetOptLevel(passBuilder, getOptimizationLevel(context))
        LLVMPassManagerBuilderSetSizeLevel(passBuilder, 0)
        // TODO: use LLVMGetTargetFromName instead.
        val target = alloc<LLVMTargetRefVar>()
        if (LLVMGetTargetFromTriple(targetTriple, target.ptr, null) != 0) {
            context.reportCompilationError("Cannot get target from triple $targetTriple.")
        }
        val targetMachine = LLVMCreateTargetMachine(
                target.value,
                targetTriple,
                context.config.target.cpuArchitecture,
                context.config.target.cpuFeatures,
                LLVMCodeGenOptLevel.LLVMCodeGenLevelAggressive,
                LLVMRelocMode.LLVMRelocDefault,
                LLVMCodeModel.LLVMCodeModelDefault)

        val targetLibraryInfo = LLVMGetTargetLibraryInfo(llvmModule)
        LLVMAddTargetLibraryInfo(targetLibraryInfo, modulePasses)
        // TargetTransformInfo pass.
        LLVMAddAnalysisPasses(targetMachine, modulePasses)
        LLVMAddInternalizePass(modulePasses, 0)
        LLVMAddGlobalDCEPass(modulePasses)
        getInlineThreshold(context)?.let { threshold ->
            LLVMPassManagerBuilderUseInlinerWithThreshold(passBuilder, threshold)
        }
        LLVMPassManagerBuilderPopulateLTOPassManager(passBuilder, modulePasses, 0, 1)
        LLVMPassManagerBuilderDispose(passBuilder)

        LLVMRunPassManager(modulePasses, llvmModule)

        LLVMDisposeTargetMachine(targetMachine)
        LLVMDisposePassManager(modulePasses)
    }
    if (shouldRunLateBitcodePasses(context)) {
        runLateBitcodePasses(context, llvmModule)
    }
}