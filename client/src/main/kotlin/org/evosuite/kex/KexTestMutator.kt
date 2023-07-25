package org.evosuite.kex

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.evosuite.kex.observers.KexTestObserver
import org.evosuite.kex.ps.AdvancedBfsPathSelector
import org.evosuite.testcase.DefaultTestCase
import org.evosuite.testcase.TestCase
import org.evosuite.testcase.statements.AssignmentStatement
import org.evosuite.testcase.statements.PrimitiveStatement
import org.evosuite.testsuite.TestSuiteChromosome
import org.vorpal.research.kex.smt.ObjectReanimator
import org.vorpal.research.kex.smt.SMTModel
import org.vorpal.research.kex.trace.symbolic.PersistentSymbolicState
import org.vorpal.research.kex.trace.symbolic.SymbolicState
import org.vorpal.research.kex.trace.symbolic.protocol.SuccessResult
import org.vorpal.research.kfg.ir.Method
import kotlin.time.ExperimentalTime


@ExperimentalTime
@InternalSerializationApi
@ExperimentalSerializationApi
@DelicateCoroutinesApi
class KexTestMutator {

    private val ctx get() = KexService.ctx
    private val pathSelector = AdvancedBfsPathSelector(ctx)

    private suspend fun updateWithTrace(trace: SymbolicState, method: Method? = null) {
        pathSelector.addExecutionTrace(method ?: KexService.fakeEmptyMethod, SuccessResult(trace))
    }

    private fun PersistentSymbolicState.detectTest(): Int? {
        for (clause in this.clauses) {
            val term = clause.predicate.operands.firstOrNull {
                it.name.startsWith(KexTestObserver.EVO_NAME)
            } ?: continue

            return term.name.removePrefix(KexTestObserver.EVO_NAME)
                .takeWhile { it.isDigit() }
                .toInt()
        }
        return null
    }

    fun mutateTest(suite: TestSuiteChromosome): TestCase? = runBlocking {
        val observers = suite.testChromosomes.mapIndexed { index, test ->
            val observer = KexTestObserver(ctx, index)
            val testCaseClone = test.testCase.clone() as DefaultTestCase
            KexService.execute(testCaseClone, observer)?.let { updateWithTrace(observer.trace) }
            observer
        }

        if (!pathSelector.hasNext()) {
            return@runBlocking null
        }
        val state = pathSelector.next()
        val index = state.detectTest() ?: return@runBlocking null
        val testCase = suite.getTestChromosome(index).testCase
        val observer = observers[index]

        val model = state.check(ctx) ?: return@runBlocking null
        updateTestCase(testCase, model, observer)
    }

    private fun updateTestCase(testCase: TestCase, model: SMTModel, observer: KexTestObserver): TestCase {
        val reanimator = ObjectReanimator(model, ctx)

        val result = testCase.clone()
        result.clearCoveredGoals()

        for ((index, statement) in result.withIndex()) {
            when (statement) {
                is AssignmentStatement -> TODO()
                is PrimitiveStatement<*> -> {
                    val returnValue = statement.returnValue
                    val term = observer.valueCache[returnValue.name]?.let { observer.termCache[it] } ?: continue
                    val obj = reanimator.reanimate(term)
                    statement.value = obj
                }
            }
        }

        return result
    }

}