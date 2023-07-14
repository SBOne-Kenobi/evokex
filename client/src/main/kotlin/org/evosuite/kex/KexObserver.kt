package org.evosuite.kex

import org.evosuite.testcase.execution.ExecutionObserver
import org.evosuite.testcase.execution.ExecutionResult
import org.evosuite.testcase.execution.Scope
import org.evosuite.testcase.statements.*
import org.evosuite.testcase.variable.*
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.trace.symbolic.InstructionTraceCollector
import org.vorpal.research.kex.trace.symbolic.SymbolicTraceBuilder
import org.vorpal.research.kex.trace.symbolic.TraceCollectorProxy.disableCollector
import org.vorpal.research.kex.trace.symbolic.TraceCollectorProxy.enableCollector
import org.vorpal.research.kex.trace.symbolic.TraceCollectorProxy.initializeEmptyCollector
import org.vorpal.research.kex.trace.symbolic.TraceCollectorProxy.setCurrentCollector
import org.vorpal.research.kfg.ir.value.NameMapperContext
import org.vorpal.research.kfg.ir.value.Value


class KexObserver(private val ctx: ExecutionContext) : ExecutionObserver() {

    private var collector: SymbolicTraceBuilder
    private val emptyCollector: InstructionTraceCollector

    init {
        collector = enableCollector(ctx, NameMapperContext()) as SymbolicTraceBuilder
        emptyCollector = initializeEmptyCollector()
    }

    override fun output(position: Int, output: String) {
        // Nothing
    }

    override fun beforeStatement(statement: Statement, scope: Scope) {
        when (statement) {
            is FieldStatement -> handleField(statement, scope)
            is ArrayStatement -> handleArray(statement, scope)
            is EntityWithParametersStatement -> handleCallable(statement, scope)
            is PrimitiveExpression -> handleExpression(statement, scope)
            is AssignmentStatement -> handleAssignment(statement, scope)
            is PrimitiveStatement<*> -> handlePrimitive(statement, scope)
        }

        setCurrentCollector(collector)
    }

    private fun handleField(statement: FieldStatement, scope: Scope) {

    }

    private fun handleArray(statement: ArrayStatement, scope: Scope) {

    }

    private fun handleCallable(statement: EntityWithParametersStatement, scope: Scope) {

    }

    private fun handleExpression(statement: PrimitiveExpression, scope: Scope) {

    }

    private fun handleAssignment(statement: AssignmentStatement, scope: Scope) {
        when (statement.returnValue) {
            is ArrayIndex -> TODO()
            is FieldReference -> TODO()
            else -> TODO()
        }
    }

    private fun handlePrimitive(statement: PrimitiveStatement<*>, scope: Scope) {

    }

    private fun VariableReference.getValue(): Value = when (this) {
        is NullReference -> TODO()
        is ArrayReference -> TODO()
        is ArrayIndex -> TODO()
        is FieldReference -> TODO()
        is ConstantValue -> TODO()
        else -> TODO()
    }

    override fun afterStatement(statement: Statement, scope: Scope, exception: Throwable?) {
        setCurrentCollector(emptyCollector)
    }

    override fun testExecutionFinished(r: ExecutionResult, s: Scope) {
        disableCollector()
    }

    override fun clear() {
        collector = SymbolicTraceBuilder(ctx, NameMapperContext())
        setCurrentCollector(emptyCollector)
    }
}
