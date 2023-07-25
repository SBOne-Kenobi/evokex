package org.evosuite.kex

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.evosuite.kex.observers.KexStatementObserver
import org.evosuite.kex.ps.AdvancedBfsPathSelector
import org.evosuite.testcase.DefaultTestCase
import org.evosuite.testcase.TestCase
import org.evosuite.testsuite.TestSuiteChromosome
import org.vorpal.research.kex.trace.symbolic.SymbolicState
import org.vorpal.research.kex.trace.symbolic.protocol.SuccessResult
import org.vorpal.research.kfg.ir.Method
import kotlin.time.ExperimentalTime


@ExperimentalTime
@InternalSerializationApi
@ExperimentalSerializationApi
@DelicateCoroutinesApi
class KexTestGenerator {

    private val ctx get() = KexService.ctx
    private val fakeMethod get() = KexService.fakeEmptyMethod

    private val pathSelector = AdvancedBfsPathSelector(ctx)

    private suspend fun updateWithTrace(trace: SymbolicState, method: Method) {
        pathSelector.addExecutionTrace(method, SuccessResult(trace))
    }

    fun generateTest(suite: TestSuiteChromosome, needExecute: Boolean = true): TestCase? = runBlocking {
        if (needExecute) {
            val observer = KexStatementObserver(ctx)
            suite.testChromosomes.forEach { test ->
                val testCaseClone = test.testCase.clone() as DefaultTestCase
                KexService.execute(testCaseClone, observer)?.let {
                    observer.states.forEach { (key, state) ->
                        if (state.path.path.isNotEmpty()) {
                            updateWithTrace(state, key.method)
                        }
                    }
                }
            }
        }

        if (!pathSelector.hasNext()) {
            return@runBlocking null
        }
        val state = pathSelector.next()
        val model = state.check(ctx, pathSelector.lastCandidate.method) ?: return@runBlocking null

        null
    }

}