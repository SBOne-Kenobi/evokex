package org.evosuite.kex

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.evosuite.Properties
import org.evosuite.classpath.ClassPathHacker
import org.evosuite.classpath.ClassPathHandler
import org.evosuite.kex.observers.KexObserver
import org.evosuite.testcase.DefaultTestCase
import org.evosuite.testcase.execution.ExecutionResult
import org.evosuite.testcase.execution.TestCaseExecutor
import org.slf4j.LoggerFactory
import org.vorpal.research.kex.compile.JavaCompilerDriver
import org.vorpal.research.kex.config.FileConfig
import org.vorpal.research.kex.config.RuntimeConfig
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.launcher.ConcolicLauncher
import org.vorpal.research.kex.reanimator.codegen.javagen.ReflectionUtilsPrinter
import org.vorpal.research.kex.util.asmString
import org.vorpal.research.kex.util.compiledCodeDirectory
import org.vorpal.research.kex.util.instrumentedCodeDirectory
import org.vorpal.research.kex.util.testcaseDirectory
import org.vorpal.research.kfg.ir.Method
import java.io.File
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.time.ExperimentalTime


@ExperimentalTime
@InternalSerializationApi
@ExperimentalSerializationApi
@DelicateCoroutinesApi
object KexService {
    private val logger = LoggerFactory.getLogger(KexService::class.java)

    private lateinit var launcher: ConcolicLauncher
    private lateinit var loader: KexClassLoader

    lateinit var reflectionUtils: ReflectionUtilsPrinter

    val ctx get() = launcher.context
    val fakeEmptyMethod: Method
        get() = ctx.cm[KexService::class.java.name].getMethod("fakeEmptyMethod", ctx.types.voidType)

    @JvmStatic
    @Suppress("UNUSED")
    fun fakeEmptyMethod() {
    }

    @JvmStatic
    fun init() {
        kexConfig.initialize(RuntimeConfig, FileConfig("kex.ini"))

        val instrumentedDir = kexConfig.instrumentedCodeDirectory.toUri().toURL()
        val classPaths = ClassPathHandler.getInstance().classPathElementsForTargetProject.asList()

        launcher = ConcolicLauncher(classPaths, Properties.TARGET_CLASS)
        loader = KexClassLoader(arrayOf(instrumentedDir))

        initReflectionUtils()
    }

    private fun initReflectionUtils() {
        val pack = Properties.TARGET_CLASS.substringBeforeLast('.', "")
        reflectionUtils = ReflectionUtilsPrinter.reflectionUtils(pack)
        val compileDir = kexConfig.compiledCodeDirectory.also {
            it.toFile().mkdirs()
        }
        val reflectionFile =
            kexConfig.testcaseDirectory / pack.asmString / "${ReflectionUtilsPrinter.REFLECTION_UTILS_CLASS}.java"
        val driver = JavaCompilerDriver(emptyList(), compileDir)
        driver.compile(listOf(reflectionFile))

        ClassPathHacker.addURL(compileDir.toUri().toURL())

        val cp = ClassPathHandler.getInstance().evoSuiteClassPath.split(File.pathSeparator).toTypedArray()
        ClassPathHandler.getInstance().setEvoSuiteClassPath(cp + compileDir.absolutePathString())
    }

    @JvmStatic
    fun execute(
        defaultTestCase: DefaultTestCase,
        kexObserver: KexObserver
    ): ExecutionResult? {
        defaultTestCase.changeClassLoader(loader)

        val originalExecutionObservers = TestCaseExecutor.getInstance().executionObservers
        TestCaseExecutor.getInstance().newObservers()
        TestCaseExecutor.getInstance().addObserver(kexObserver)

        // Execution
        return try {
            TestCaseExecutor.getInstance().execute(defaultTestCase, Properties.CONCOLIC_TIMEOUT)
        } catch (e: Exception) {
            logger.error("Exception during kex execution: ", e)
            null
        } finally {
            TestCaseExecutor.getInstance().executionObservers = originalExecutionObservers
        }
    }

}
