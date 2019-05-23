package org.jetbrains.kotlin

import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecSpec

import org.jetbrains.kotlin.konan.target.*

import java.io.FileWriter
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Test task for -produce framework testing. Requires a framework to be built by the Konan plugin
 * with konanArtifacts { framework(frameworkName, targets: [ testTarget] ) } and a dependency set
 * according to a pattern "compileKonan${frameworkName}".
 *
 * @property swiftSources  Swift-language test sources that use a given framework
 * @property frameworkName a framework name
 */
open class FrameworkTest : DefaultTask() {
    @Input
    lateinit var swiftSources: List<String>

    @Input
    lateinit var frameworkName: String

    @Input
    var fullBitcode: Boolean = false

    private val testOutput: String by lazy {
        project.file(project.property("testOutputFramework")!!).absolutePath
    }

    override fun configure(config: Closure<*>): Task {
        super.configure(config)
        val target = project.testTarget().name

        // set crossdist build dependency if custom konan.home wasn't set
        if (!(project.property("useCustomDist") as Boolean)) {
            setRootDependency("${target}CrossDist", "${target}CrossDistRuntime", "commonDistRuntime", "distCompiler")
        }
        check(::frameworkName.isInitialized, { "Framework name should be set" })
        dependsOn(project.tasks.getByName("compileKonan$frameworkName"))
        return this
    }

    private fun setRootDependency(vararg s: String) = s.forEach { dependsOn(project.rootProject.tasks.getByName(it)) }

    @TaskAction
    fun run() {
        val frameworkParentDirPath = "$testOutput/$frameworkName/${project.testTarget().name}"
        val frameworkPath = "$frameworkParentDirPath/$frameworkName.framework"
        val frameworkBinaryPath = "$frameworkPath/$frameworkName"
        validateBitcodeEmbedding(frameworkBinaryPath)
        codesign(project, frameworkPath)

        // create a test provider and get main entry point
        val provider = Paths.get(testOutput, frameworkName, "provider.swift")
        FileWriter(provider.toFile()).use {
            it.write("""
                |// THIS IS AUTOGENERATED FILE
                |// This method is invoked by the main routine to get a list of tests
                |func registerProvider() {
                |    // TODO: assuming this naming for now
                |    ${frameworkName}Tests()
                |}
                """.trimMargin())
        }
        val testHome = project.file("framework").toPath()
        val swiftMain = Paths.get(testHome.toString(), "main.swift").toString()

        // Compile swift sources
        val sources = swiftSources.map { Paths.get(it).toString() } +
                listOf(provider.toString(), swiftMain)
        val options = listOf("-g", "-Xlinker", "-rpath", "-Xlinker", frameworkParentDirPath, "-F", frameworkParentDirPath)
        val testExecutable = Paths.get(testOutput, frameworkName, "swiftTestExecutable")
        swiftc(sources, options, testExecutable)

        runTest(testExecutable)
    }

    private fun setupTestEnvironment(): Map<String, String> {
        val target = project.testTarget()
        val platform = project.platformManager().platform(target)
        val configs = platform.configurables as AppleConfigurables
        val swiftPlatform = when (target) {
            KonanTarget.IOS_X64 -> "iphonesimulator"
            KonanTarget.IOS_ARM32, KonanTarget.IOS_ARM64 -> "iphoneos"
            KonanTarget.MACOS_X64 -> "macosx"
            else -> throw IllegalStateException("Test target $target is not supported")
        }
        // Hopefully, lexicographical comparison will work.
        val newMacos = System.getProperty("os.version").compareTo("10.14.4") >= 0
        val libraryPath = configs.absoluteTargetToolchain + "/usr/lib/swift/$swiftPlatform"

        return if (newMacos) emptyMap() else mapOf("DYLD_LIBRARY_PATH" to libraryPath, "SIMCTL_DYLD_LIBRARY_PATH" to libraryPath)
    }

    private fun runTest(testExecutable: Path) {
        val executor = (project.convention.plugins["executor"] as? ExecutorService)
                ?: throw RuntimeException("Executor wasn't found")

        val (stdOut, stdErr, exitCode) = runProcess(
                executor = executor.add(Action { it.environment = setupTestEnvironment() })::execute,
                executable = testExecutable.toString())

        println("""
            |$testExecutable
            |exitCode: $exitCode
            |stdout: $stdOut
            |stderr: $stdErr
            """.trimMargin())
        check(exitCode == 0, { "Execution failed with exit code: $exitCode "})
    }

    private fun swiftc(sources: List<String>, options: List<String>, output: Path) {
        val target = project.testTarget()
        val platform = project.platformManager().platform(target)
        assert(platform.configurables is AppleConfigurables)
        val configs = platform.configurables as AppleConfigurables
        val compiler = configs.absoluteTargetToolchain + "/usr/bin/swiftc"

        val swiftTarget = when (target) {
            KonanTarget.IOS_X64   -> "x86_64-apple-ios" + configs.osVersionMin
            KonanTarget.IOS_ARM64 -> "arm64_64-apple-ios" + configs.osVersionMin
            KonanTarget.MACOS_X64 -> "x86_64-apple-macosx" + configs.osVersionMin
            else -> throw IllegalStateException("Test target $target is not supported")
        }

        val args = listOf("-sdk", configs.absoluteTargetSysRoot, "-target", swiftTarget) +
                options + "-o" + output.toString() + sources +
                if (fullBitcode) listOf("-embed-bitcode", "-Xlinker", "-bitcode_verify") else listOf("-embed-bitcode-marker")

        val executor = { a: Action<in ExecSpec> ->
            project.exec {
                it.environment = setupTestEnvironment()
                a.execute(it)
            }
        }
        val (stdOut, stdErr, exitCode) = runProcess(executor = executor, executable = compiler, args = args)

        println("""
            |$compiler finished with exit code: $exitCode
            |options: ${args.joinToString(separator = " ")}
            |stdout: $stdOut
            |stderr: $stdErr
            """.trimMargin())
        check(exitCode == 0, { "Compilation failed" })
        check(output.toFile().exists(), { "Compiler swiftc hasn't produced an output file: $output" })
    }

    private fun validateBitcodeEmbedding(frameworkBinary: String) {
        // Check only the full bitcode embedding for now.
        if (!fullBitcode) {
            return
        }
        val testTarget = project.testTarget()
        val configurables = project.platformManager().platform(testTarget).configurables as AppleConfigurables

        val bitcodeBuildTool = "${configurables.absoluteAdditionalToolsDir}/bin/bitcode-build-tool"
        val ldPath = "${configurables.absoluteTargetToolchain}/usr/bin/ld"
        val sdk = when (testTarget) {
            KonanTarget.IOS_X64 -> return // bitcode-build-tool doesn't support iPhone Simulator.
            KonanTarget.IOS_ARM64, KonanTarget.IOS_ARM32 -> Xcode.current.iphoneosSdk
            KonanTarget.MACOS_X64 -> Xcode.current.macosxSdk
            else -> error("Cannot validate bitcode for test target $testTarget")
        }

        val args = listOf("--sdk", sdk, "-v", "-t", ldPath, frameworkBinary)
        val (stdOut, stdErr, exitCode) = runProcess(executor = localExecutor(project), executable = bitcodeBuildTool, args = args)
        check(exitCode == 0) {
            """
            |bitcode-build-tool failed:
            |stdout: $stdOut
            |stderr: $stdErr
            """.trimMargin()
        }
    }
}
