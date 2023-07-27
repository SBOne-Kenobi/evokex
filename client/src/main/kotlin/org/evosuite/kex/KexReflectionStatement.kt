package org.evosuite.kex

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.evosuite.testcase.TestCase
import org.evosuite.testcase.TestFactory
import org.evosuite.testcase.statements.MethodStatement
import org.evosuite.testcase.statements.Statement
import org.evosuite.testcase.variable.VariableReference
import org.evosuite.testcase.variable.VariableReferenceImpl
import org.evosuite.utils.generic.GenericMethod
import java.lang.reflect.Method
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
    parameters, retvar ?: VariableReferenceImpl(tc, method.returnType)
) {

    constructor(
        tc: TestCase, methodName: String,
        parameters: List<VariableReference>,
        retvar: VariableReference? = null
    ) : this(tc, Companion.getMethod(methodName), parameters, retvar)

    companion object {
        private val utils = KexService.reflectionUtils.klass.run { Class.forName("$pkg.$name") }
        private val methods = mutableMapOf(
            "newInstance" to GenericMethod(utils.getMethod("newInstance", String::class.java), utils)
        )

        private fun getMethod(methodName: String): GenericMethod = methods.getOrPut(methodName) {
            GenericMethod(utils.methods.first { it.name == methodName }, utils)
        }
    }

    override fun copy(newTestCase: TestCase, offset: Int): Statement {
        val retvar = returnValue?.copy(newTestCase, offset)
        val parameters = parameters.map { it.copy(newTestCase, offset) }
        return KexReflectionStatement(newTestCase, method, parameters, retvar)
    }

    override fun isReflectionStatement(): Boolean = true

    override fun mutate(test: TestCase?, factory: TestFactory?): Boolean {
        return false
    }
}