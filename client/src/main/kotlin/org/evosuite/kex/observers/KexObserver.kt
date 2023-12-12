package org.evosuite.kex.observers

import org.evosuite.testcase.execution.ExecutionObserver
import org.evosuite.testcase.execution.ExecutionResult
import org.evosuite.testcase.execution.Scope
import org.evosuite.testcase.statements.*
import org.evosuite.testcase.statements.environment.EnvironmentDataStatement
import org.evosuite.testcase.statements.numeric.NumericalPrimitiveStatement
import org.evosuite.utils.generic.GenericField
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kfg.ir.Field
import org.vorpal.research.kfg.ir.MethodDescriptor
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

abstract class KexObserver(protected val executionContext: ExecutionContext) : ExecutionObserver() {

    override fun output(position: Int, output: String) {
        // nothing
    }

    override fun beforeStatement(statement: Statement, scope: Scope) {
        when (statement) {
            is FieldStatement -> beforeField(statement, scope)
            is ArrayStatement -> beforeArray(statement, scope)
            is PrimitiveExpression -> beforeExpression(statement, scope)
            is AssignmentStatement -> beforeAssignment(statement, scope)
            is PrimitiveStatement<*> -> beforePrimitive(statement, scope)
            is EntityWithParametersStatement -> beforeEntityWithParameters(statement, scope)
        }
    }

    protected open fun beforeField(statement: FieldStatement, scope: Scope) {}

    protected open fun beforeArray(statement: ArrayStatement, scope: Scope) {}

    protected open fun beforeEntityWithParameters(statement: EntityWithParametersStatement, scope: Scope) {
        when (statement) {
            is ConstructorStatement -> beforeConstructor(statement, scope)
            is MethodStatement -> beforeMethod(statement, scope)
            is FunctionalMockStatement -> beforeMock(statement, scope)
        }
    }

    protected open fun beforeConstructor(statement: ConstructorStatement, scope: Scope) {}

    protected open fun beforeMethod(statement: MethodStatement, scope: Scope) {}

    protected open fun beforeMock(statement: FunctionalMockStatement, scope: Scope) {}

    protected open fun beforeExpression(statement: PrimitiveExpression, scope: Scope) {}

    protected open fun beforeAssignment(statement: AssignmentStatement, scope: Scope) {}

    protected open fun beforePrimitive(statement: PrimitiveStatement<*>, scope: Scope) {
        when (statement) {
            is EnumPrimitiveStatement<*> -> beforeEnumPrimitive(statement, scope)
            is EnvironmentDataStatement<*> -> beforeEnvironmentData(statement, scope)
            is NullStatement -> beforeNull(statement, scope)
            is NumericalPrimitiveStatement<*> -> beforeNumericalPrimitive(statement, scope)
            is StringPrimitiveStatement -> beforeStringPrimitive(statement, scope)
            is ClassPrimitiveStatement -> beforeClassPrimitive(statement, scope)
        }
    }

    protected open fun beforeEnumPrimitive(statement: EnumPrimitiveStatement<*>, scope: Scope) {}

    protected open fun beforeEnvironmentData(statement: EnvironmentDataStatement<*>, scope: Scope) {}

    protected open fun beforeNull(statement: NullStatement, scope: Scope) {}

    protected open fun beforeNumericalPrimitive(statement: NumericalPrimitiveStatement<*>, scope: Scope) {}

    protected open fun beforeStringPrimitive(statement: StringPrimitiveStatement, scope: Scope) {}

    protected open fun beforeClassPrimitive(statement: ClassPrimitiveStatement, scope: Scope) {}

    protected val Executable.kfgMethod
        get() = executionContext.cm[declaringClass.name.replace('.', '/')].getMethod(
            when (this) {
                is Constructor<*> -> org.vorpal.research.kfg.ir.Method.CONSTRUCTOR_NAME
                else -> name
            },
            MethodDescriptor(
                parameterTypes.map(executionContext.types::get),
                when {
                    this is Method && returnType != Void.TYPE -> executionContext.types.get(returnType)
                    else -> executionContext.types.voidType
                }
            )
        )

    protected val GenericField.kfgField: Field
        get() {
            val cl = executionContext.cm[ownerClass.className.replace('.', '/')]
            val type = executionContext.types.get(rawGeneratedType)
            return cl.getField(name, type)
        }

    override fun afterStatement(statement: Statement, scope: Scope, exception: Throwable?) {
        when (statement) {
            is FieldStatement -> afterField(statement, scope, exception)
            is ArrayStatement -> afterArray(statement, scope, exception)
            is PrimitiveExpression -> afterExpression(statement, scope, exception)
            is AssignmentStatement -> afterAssignment(statement, scope, exception)
            is PrimitiveStatement<*> -> afterPrimitive(statement, scope, exception)
            is EntityWithParametersStatement -> afterEntityWithParameters(statement, scope, exception)
        }
    }

    protected open fun afterField(statement: FieldStatement, scope: Scope, exception: Throwable?) {}

    protected open fun afterArray(statement: ArrayStatement, scope: Scope, exception: Throwable?) {}

    protected open fun afterEntityWithParameters(
        statement: EntityWithParametersStatement,
        scope: Scope,
        exception: Throwable?
    ) {
        when (statement) {
            is ConstructorStatement -> afterConstructor(statement, scope, exception)
            is MethodStatement -> afterMethod(statement, scope, exception)
            is FunctionalMockStatement -> afterMock(statement, scope, exception)
        }
    }

    protected open fun afterConstructor(statement: ConstructorStatement, scope: Scope, exception: Throwable?) {}

    protected open fun afterMethod(statement: MethodStatement, scope: Scope, exception: Throwable?) {}

    protected open fun afterMock(statement: FunctionalMockStatement, scope: Scope, exception: Throwable?) {}

    protected open fun afterExpression(statement: PrimitiveExpression, scope: Scope, exception: Throwable?) {}

    protected open fun afterAssignment(statement: AssignmentStatement, scope: Scope, exception: Throwable?) {}

    protected open fun afterPrimitive(statement: PrimitiveStatement<*>, scope: Scope, exception: Throwable?) {
        when (statement) {
            is EnumPrimitiveStatement<*> -> afterEnumPrimitive(statement, scope, exception)
            is EnvironmentDataStatement<*> -> afterEnvironmentData(statement, scope, exception)
            is NullStatement -> afterNull(statement, scope, exception)
            is NumericalPrimitiveStatement<*> -> afterNumericalPrimitive(statement, scope, exception)
            is StringPrimitiveStatement -> afterStringPrimitive(statement, scope, exception)
            is ClassPrimitiveStatement -> afterClassPrimitive(statement, scope, exception)
        }
    }

    protected open fun afterEnumPrimitive(statement: EnumPrimitiveStatement<*>, scope: Scope, exception: Throwable?) {}

    protected open fun afterEnvironmentData(
        statement: EnvironmentDataStatement<*>,
        scope: Scope,
        exception: Throwable?
    ) {
    }

    protected open fun afterNull(statement: NullStatement, scope: Scope, exception: Throwable?) {}

    protected open fun afterNumericalPrimitive(
        statement: NumericalPrimitiveStatement<*>,
        scope: Scope,
        exception: Throwable?
    ) {
    }

    protected open fun afterStringPrimitive(statement: StringPrimitiveStatement, scope: Scope, exception: Throwable?) {}

    protected open fun afterClassPrimitive(statement: ClassPrimitiveStatement, scope: Scope, exception: Throwable?) {}

    abstract override fun testExecutionFinished(r: ExecutionResult, s: Scope)
}