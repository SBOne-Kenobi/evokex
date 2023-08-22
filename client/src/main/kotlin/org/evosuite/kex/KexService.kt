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
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.transform.SymbolicTraceInstrumenter
import org.vorpal.research.kex.asm.util.AccessModifier
import org.vorpal.research.kex.asm.util.ClassWriter
import org.vorpal.research.kex.compile.CompilerHelper
import org.vorpal.research.kex.config.FileConfig
import org.vorpal.research.kex.config.RuntimeConfig
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.launcher.LauncherException
import org.vorpal.research.kex.launcher.prepareInstrumentedClasspath
import org.vorpal.research.kex.random.easyrandom.EasyRandomDriver
import org.vorpal.research.kex.reanimator.codegen.javagen.ReflectionUtilsPrinter
import org.vorpal.research.kex.util.*
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.KfgConfig
import org.vorpal.research.kfg.Package
import org.vorpal.research.kfg.container.asContainer
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.util.Flags
import org.vorpal.research.kfg.visitor.Pipeline
import ru.spbstu.wheels.mapToArray
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.time.ExperimentalTime


enum class KexInitMode {
    PRE_INIT,
    MASTER_INIT,
    CLIENT_INIT,
}

@ExperimentalTime
@InternalSerializationApi
@ExperimentalSerializationApi
@DelicateCoroutinesApi
object KexService {
    private val logger = LoggerFactory.getLogger(KexService::class.java)

    private var isFirst = true
    private var reflectionIsInited = false

    lateinit var ctx: ExecutionContext
    private lateinit var loader: KexClassLoader

    lateinit var reflectionUtils: ReflectionUtilsPrinter

    val fakeEmptyMethod: Method
        get() = ctx.cm[KexService::class.java.name].getMethod("fakeEmptyMethod", ctx.types.voidType)

    @JvmStatic
    @Suppress("UNUSED")
    fun fakeEmptyMethod() {
    }

    private fun instrument(mode: KexInitMode) {
        val classPaths = ClassPathHandler.getInstance().classPathElementsForTargetProject
        val containerPaths = classPaths.map { Paths.get(it).toAbsolutePath() }
        val containers = listOfNotNull(
            *containerPaths.mapToArray {
                it.asContainer() ?: throw LauncherException("Can't represent ${it.toAbsolutePath()} as class container")
            },
            getKexRuntime()
        )
        val analysisJars = listOfNotNull(*containers.toTypedArray(), getRuntime(), getIntrinsics())

        val containerClassLoader = URLClassLoader(containerPaths.mapToArray { it.toUri().toURL() })
        val instrumentedCodeDir = kexConfig.instrumentedCodeDirectory

        val prepare: (ExecutionContext) -> Pipeline.() -> Unit =
            if (mode != KexInitMode.CLIENT_INIT) {
                { ctx ->
                    {
                        +SymbolicTraceInstrumenter(ctx)
                        +ClassWriter(ctx, instrumentedCodeDir)
                    }
                }
            } else {
                { {} }
            }

        prepareInstrumentedClasspath(
            analysisJars,
            containerClassLoader,
            Package.defaultPackage,
            instrumentedCodeDir,
            prepare,
            withUnpacking = mode != KexInitMode.CLIENT_INIT
        )

        val cm = ClassManager(
            KfgConfig(
                flags = Flags.readAll,
                useCachingLoopManager = false,
                failOnError = false,
                verifyIR = false,
                checkClasses = false
            )
        )
        cm.initialize(analysisJars)
        ctx = ExecutionContext(cm, loader, EasyRandomDriver(), containers.map { it.path }, AccessModifier.Private)
    }

    @JvmStatic
    fun init(mode: KexInitMode) {
        logger.debug("Initialize KexService: {}", mode)

        if (isFirst) {
            kexConfig.initialize(RuntimeConfig, FileConfig("kex.ini"))
            val instrumentedDir = kexConfig.instrumentedCodeDirectory.toUri().toURL()
            loader = KexClassLoader(arrayOf(instrumentedDir))

            instrument(mode)

            isFirst = false
        }

        initReflectionUtils(mode)
        logger.debug("KexService initialized successfully")
    }

    private fun initReflectionUtils(mode: KexInitMode) {
        if (mode != KexInitMode.PRE_INIT) {
            if (reflectionIsInited) return
            reflectionIsInited = true

            logger.debug("Initialize reflection utils")
            val compileDir = kexConfig.compiledCodeDirectory.also {
                it.toFile().mkdirs()
            }
            ClassPathHacker.addURL(compileDir.toUri().toURL())

            val cp = ClassPathHandler.getInstance().evoSuiteClassPath.split(File.pathSeparator).toTypedArray()
            ClassPathHandler.getInstance().setEvoSuiteClassPath(cp + compileDir.absolutePathString())

            val pack = Properties.TARGET_CLASS.substringBeforeLast('.', "")

            if (mode == KexInitMode.MASTER_INIT) {
                logger.debug("Compile reflection utils class")
                reflectionUtils = ReflectionUtilsPrinter.reflectionUtils(pack)
                val reflectionFile =
                    kexConfig.testcaseDirectory / pack.asmString / "${ReflectionUtilsPrinter.REFLECTION_UTILS_CLASS}.java"
                val compiler = CompilerHelper(ctx)
                compiler.compileFile(reflectionFile)
            } else {
                reflectionUtils = ReflectionUtilsPrinter(pack)
            }
        }
    }

    @JvmStatic
    fun execute(
        defaultTestCase: DefaultTestCase,
        kexObserver: KexObserver
    ): ExecutionResult? {
        logger.debug("Execute kex concolic execution")
        defaultTestCase.changeClassLoader(loader)

        val originalExecutionObservers = TestCaseExecutor.getInstance().executionObservers
        TestCaseExecutor.getInstance().newObservers()
        TestCaseExecutor.getInstance().addObserver(kexObserver)

        // Execution
        return try {
            TestCaseExecutor.getInstance().execute(defaultTestCase)
        } catch (e: Exception) {
            logger.error("Exception during kex execution: ", e)
            null
        } finally {
            TestCaseExecutor.getInstance().executionObservers = originalExecutionObservers
        }.also {
            logger.debug("Kex execution is success: {}", it != null)
        }
    }

}
