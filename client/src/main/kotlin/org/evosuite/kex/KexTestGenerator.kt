package org.evosuite.kex

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.evosuite.Properties
import org.evosuite.kex.observers.KexStatementObserver
import org.evosuite.kex.ps.AdvancedBfsPathSelector
import org.evosuite.testcase.DefaultTestCase
import org.evosuite.testcase.TestCase
import org.evosuite.testcase.TestChromosome
import org.slf4j.LoggerFactory
import org.vorpal.research.kex.asm.state.PredicateStateAnalysis
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.reanimator.actionsequence.ActionSequence
import org.vorpal.research.kex.reanimator.actionsequence.generator.ConcolicSequenceGenerator
import org.vorpal.research.kex.reanimator.codegen.klassName
import org.vorpal.research.kex.reanimator.codegen.validName
import org.vorpal.research.kex.reanimator.rtUnmapped
import org.vorpal.research.kex.trace.symbolic.SymbolicState
import org.vorpal.research.kex.trace.symbolic.protocol.SuccessResult
import org.vorpal.research.kex.util.javaString
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.instruction.Instruction
import java.util.*
import kotlin.time.Duration.Companion.seconds
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
    private val cache = WeakHashMap<TestChromosome, Boolean>()

    private val Method.isTargetMethod: Boolean
        get() = klass.fullName.javaString == Properties.TARGET_CLASS

    fun collectTraces(testChromosomes: List<TestChromosome>, stoppingCondition: () -> Boolean) {
        runBlocking {
            logger.info("Trace collection")
            for (test in testChromosomes) {
                if (stoppingCondition()) break
                if (test in cache) continue

                try {
                    val observer = KexStatementObserver(ctx)
                    val testCaseClone = test.testCase.clone() as DefaultTestCase
                    KexService.execute(testCaseClone, observer)?.let {
                        observer.results.forEach { (key, value) ->
                            val (state, trace) = value
                            if (state.isNotEmpty()
                                && trace.first().parent.method.isTargetMethod
                            ) {
                                updateWithTrace(trace, state, key.method)
                            }
                        }
                    }
                    cache[test] = true
                } catch (e: Throwable) {
                    logger.error("Error occurred while running test:\n{}", test, e)
                }
            }
            pathSelector.recomputeCoverage()
        }
    }

    private suspend fun updateWithTrace(instructionsTrace: List<Instruction>, trace: SymbolicState, method: Method) {
        pathSelector.addExecutionTrace(method, SuccessResult(trace))
        pathSelector.addExecutionInstructionTrace(instructionsTrace)
    }

    fun generateTest(stoppingCondition: () -> Boolean): TestCase? = runBlocking {
        logger.debug("Generating test with kex")

        while (pathSelector.hasNext() && !stoppingCondition()) {
            val state = pathSelector.next()
            val method = pathSelector.lastCandidate.method
            val timeout = 4 * kexConfig.getIntValue("smt", "timeout", 3)
            try {
                val test = withTimeoutOrNull(timeout.seconds) {
                    val parameters = state.checkAndGetParameters(ctx, method)
                    parameters?.let { generateTest(it, method) }
                } ?: continue
                return@runBlocking test
            } catch (e: Throwable) {
                logger.error("Error occurred while generating test for state:\n{}", state, e)
                continue
            }
        }
        null
    }.also {
        logger.debug("Kex produce new test:\n{}", it)
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