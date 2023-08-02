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
import org.slf4j.LoggerFactory
import org.vorpal.research.kex.asm.state.PredicateStateAnalysis
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.reanimator.actionsequence.ActionSequence
import org.vorpal.research.kex.reanimator.actionsequence.generator.ConcolicSequenceGenerator
import org.vorpal.research.kex.reanimator.rtUnmapped
import org.vorpal.research.kex.state.term.TermBuilder.Terms.toStr
import org.vorpal.research.kex.trace.symbolic.SymbolicState
import org.vorpal.research.kex.trace.symbolic.protocol.SuccessResult
import org.vorpal.research.kfg.ir.Method
import kotlin.time.ExperimentalTime

@ExperimentalTime
@InternalSerializationApi
@ExperimentalSerializationApi
@DelicateCoroutinesApi
class KexTestGenerator {

    companion object {
        private val logger = LoggerFactory.getLogger(KexTestGenerator::class.java)
    }

    private val ctx get() = KexService.ctx
    private val pathSelector = AdvancedBfsPathSelector(ctx)
    private val asGenerator = ConcolicSequenceGenerator(ctx, PredicateStateAnalysis(ctx.cm))

    private suspend fun updateWithTrace(trace: SymbolicState, method: Method) {
        pathSelector.addExecutionTrace(method, SuccessResult(trace))
    }

    fun generateTest(suite: TestSuiteChromosome): TestCase? = runBlocking {
        logger.info("Generating test with kex")

        val observer = KexStatementObserver(ctx)
        suite.testChromosomes.forEach { test ->
            try {
                val testCaseClone = test.testCase.clone() as DefaultTestCase
                KexService.execute(testCaseClone, observer)?.let {
                    observer.states.forEach { (key, state) ->
                        if (state.isNotEmpty()) {
                            updateWithTrace(state, key.method)
                        }
                    }
                }
            } catch (e: Throwable) {
                logger.error("Error occurred while running test:\n{}", test, e)
            }
        }

        while (pathSelector.hasNext()) {
            val state = pathSelector.next()
            val method = pathSelector.lastCandidate.method

            logger.debug("Choose state of {} for test generation:\n{}", method, state)

            try {
                val parameters = state.checkAndGetParameters(ctx, method) ?: continue
                return@runBlocking generateTest(parameters, method)
            } catch (e: Throwable) {
                logger.error("Error occurred while generating test for state:\n{}", state, e)
                continue
            }
        }
        null
    }.also {
        logger.debug("Generated test by kex:\n{}", it)
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
        logger.debug("Start test generation for {} with {}", method.toString(), parameters.toString())

        val actionParameters = parameters.actionSequences.rtUnmapped
        val testCase = DefaultTestCase()
        val generator = ActionSequence2EvosuiteStatements(testCase)

        for (seq in actionParameters.asList) {
            generator.generateStatements(seq)
        }

        generator.generateTestCall(method, actionParameters)

        return testCase
    }

}