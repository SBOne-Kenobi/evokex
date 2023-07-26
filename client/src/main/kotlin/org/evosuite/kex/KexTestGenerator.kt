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
import org.vorpal.research.kex.asm.state.PredicateStateAnalysis
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.reanimator.actionsequence.ActionSequence
import org.vorpal.research.kex.reanimator.actionsequence.generator.ConcolicSequenceGenerator
import org.vorpal.research.kex.reanimator.rtUnmapped
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
    private val pathSelector = AdvancedBfsPathSelector(ctx)
    private val asGenerator = ConcolicSequenceGenerator(ctx, PredicateStateAnalysis(ctx.cm))

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
        val method = pathSelector.lastCandidate.method
        val parameters = state.checkAndGetParameters(ctx, method) ?: return@runBlocking null

        generateTest(parameters, method)
    }


    private val Descriptor.actionSequence: ActionSequence
        get() = asGenerator.generate(this)

    private val Parameters<Descriptor>.actionSequences: Parameters<ActionSequence>
        get() {
            val thisSequence = instance?.actionSequence
            val argSequences = arguments.map { it.actionSequence }
            val staticFields = statics.mapTo(mutableSetOf()) { it.actionSequence }
            return Parameters(thisSequence, argSequences, staticFields)
        }

    private fun generateTest(parameters: Parameters<Descriptor>, method: Method): TestCase {
        val actionParameters = parameters.actionSequences.rtUnmapped
        val testCase = DefaultTestCase()
        val generator = ActionSequence2EvosuiteStatements(ctx, testCase)

        for (seq in actionParameters.asList) {
            generator.generateStatements(seq)
        }

        generator.generateTestCall(method, actionParameters)

        return testCase
    }

}