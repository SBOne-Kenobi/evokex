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
import org.vorpal.research.kex.util.instrumentedCodeDirectory
import kotlin.time.ExperimentalTime


@ExperimentalTime
@InternalSerializationApi
@ExperimentalSerializationApi
@DelicateCoroutinesApi
object KexService {
    private val logger = LoggerFactory.getLogger(KexService::class.java)

    private lateinit var launcher: ConcolicLauncher
    private lateinit var loader: KexClassLoader

    @JvmStatic
    fun init() {
        kexConfig.initialize(RuntimeConfig, FileConfig("kex.ini"))

        val instrumentedDir = kexConfig.instrumentedCodeDirectory.toUri().toURL()
        val classPaths = ClassPathHandler.getInstance().classPathElementsForTargetProject.asList()

        launcher = ConcolicLauncher(classPaths, Properties.TARGET_CLASS)
        loader = KexClassLoader(arrayOf(instrumentedDir))
    }

    @JvmStatic
    fun execute(defaultTestCase: DefaultTestCase) {
        // Kex preparation
        val kexObserver = KexObserver(launcher.context)

        // Evosuite preparation
        defaultTestCase.changeClassLoader(loader)

        // Execution preparation
        val originalExecutionObservers = TestCaseExecutor.getInstance().executionObservers
        TestCaseExecutor.getInstance().newObservers()
        TestCaseExecutor.getInstance().addObserver(kexObserver)

        // Execution
        var result = try {
            TestCaseExecutor.getInstance().execute(defaultTestCase, Properties.CONCOLIC_TIMEOUT)
        } catch (e: Exception) {
            logger.error("Exception during kex execution: ", e)
            null
        } finally {
            TestCaseExecutor.getInstance().executionObservers = originalExecutionObservers
        }
    }

}
