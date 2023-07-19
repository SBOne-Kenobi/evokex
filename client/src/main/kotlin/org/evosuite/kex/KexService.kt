package org.evosuite.kex

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.evosuite.Properties
import org.evosuite.classpath.ClassPathHandler
import org.evosuite.testcase.DefaultTestCase
import org.evosuite.testcase.execution.TestCaseExecutor
import org.slf4j.LoggerFactory
import org.vorpal.research.kex.config.FileConfig
import org.vorpal.research.kex.config.RuntimeConfig
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.launcher.ConcolicLauncher
import org.vorpal.research.kex.trace.symbolic.SymbolicState
import org.vorpal.research.kex.util.instrumentedCodeDirectory
import org.vorpal.research.kfg.ir.Method
import kotlin.time.ExperimentalTime


@ExperimentalTime
@InternalSerializationApi
@ExperimentalSerializationApi
@DelicateCoroutinesApi
object KexService {
    private val logger = LoggerFactory.getLogger(KexService::class.java)

    private lateinit var launcher: ConcolicLauncher
    private lateinit var loader: KexClassLoader

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
    }

    @JvmStatic
    fun execute(defaultTestCase: DefaultTestCase): SymbolicState? {
        // Kex preparation
        val kexObserver = KexObserver(ctx)

        // Evosuite preparation
        defaultTestCase.changeClassLoader(loader)

        // Execution preparation
        val originalExecutionObservers = TestCaseExecutor.getInstance().executionObservers
        TestCaseExecutor.getInstance().newObservers()
        TestCaseExecutor.getInstance().addObserver(kexObserver)

        // Execution
        return try {
            TestCaseExecutor.getInstance().execute(defaultTestCase, Properties.CONCOLIC_TIMEOUT)
            kexObserver.trace
        } catch (e: Exception) {
            logger.error("Exception during kex execution: ", e)
            null
        } finally {
            TestCaseExecutor.getInstance().executionObservers = originalExecutionObservers
        }
    }

}
