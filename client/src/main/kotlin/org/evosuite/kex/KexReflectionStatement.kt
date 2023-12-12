package org.evosuite.kex

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.evosuite.TestGenerationContext
import org.evosuite.testcase.TestCase
import org.evosuite.testcase.TestFactory
import org.evosuite.testcase.statements.MethodStatement
import org.evosuite.testcase.statements.Statement
import org.evosuite.testcase.variable.VariableReference
import org.evosuite.testcase.variable.VariableReferenceImpl
import org.evosuite.utils.generic.GenericMethod
import kotlin.time.ExperimentalTime

@OptIn(
    InternalSerializationApi::class,
    ExperimentalTime::class,
    ExperimentalSerializationApi::class,
    DelicateCoroutinesApi::class
)
class KexReflectionStatement private constructor(
    tc: TestCase, method: GenericMethod,
    parameters: List<VariableReference>,
    retvar: VariableReference? = null
) : MethodStatement(
    tc, method, null,
    parameters.toMutableList(),
    retvar ?: VariableReferenceImpl(tc, method.returnType)
) {

    constructor(
        tc: TestCase, methodName: String,
        parameters: List<VariableReference>,
        retvar: VariableReference? = null
    ) : this(tc, getMethod(methodName), parameters, retvar)

    companion object {
        private val loader by lazy { TestGenerationContext.getInstance().classLoaderForSUT }

        private val utils by lazy {
            KexService.reflectionUtils.klass.run {
                loader.loadClass("$pkg.$name")
            }
        }

        private val methods by lazy {
            mutableMapOf(
                "newInstance" to GenericMethod(
                    utils.getMethod("newInstance", loader.loadClass("java.lang.String")),
                    utils
                )
            )
        }

        private fun getMethod(methodName: String): GenericMethod = methods.compute(methodName) { _, method ->
            method?.copy() ?: GenericMethod(utils.methods.first { it.name == methodName }, utils)
        }!!
    }

    override fun copy(newTestCase: TestCase, offset: Int): Statement {
        val parameters = parameters.map { it.copy(newTestCase, offset) }
        return KexReflectionStatement(newTestCase, method.copy(), parameters).also {
            it.returnValue.type = returnType
        }
    }

    override fun isReflectionStatement(): Boolean = true

    override fun mutate(test: TestCase?, factory: TestFactory?): Boolean {
        return false
    }
}