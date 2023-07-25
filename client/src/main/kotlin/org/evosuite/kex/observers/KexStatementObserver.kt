package org.evosuite.kex.observers

import org.evosuite.testcase.execution.ExecutionResult
import org.evosuite.testcase.execution.Scope
import org.evosuite.testcase.statements.ConstructorStatement
import org.evosuite.testcase.statements.MethodStatement
import org.evosuite.testcase.statements.Statement
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.trace.symbolic.SymbolicState
import org.vorpal.research.kex.trace.symbolic.SymbolicTraceBuilder
import org.vorpal.research.kex.trace.symbolic.TraceCollectorProxy
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.NameMapperContext

class KexStatementObserver(executionContext: ExecutionContext) : KexObserver(executionContext) {

    data class Index(val statement: Statement, val method: Method)

    private infix fun Statement.to(method: Method) = Index(this, method)

    private val collectors = mutableMapOf<Index, SymbolicTraceBuilder>()

    val states: Map<Index, SymbolicState>
        get() = collectors.mapValues { it.value.symbolicState }

    override fun beforeConstructor(statement: ConstructorStatement, scope: Scope) {
        collectors[statement to statement.constructor.constructor.kfgMethod] =
            TraceCollectorProxy.enableCollector(executionContext, NameMapperContext()) as SymbolicTraceBuilder
    }

    override fun beforeMethod(statement: MethodStatement, scope: Scope) {
        collectors[statement to statement.method.method.kfgMethod] =
            TraceCollectorProxy.enableCollector(executionContext, NameMapperContext()) as SymbolicTraceBuilder
    }

    override fun afterConstructor(statement: ConstructorStatement, scope: Scope, exception: Throwable?) {
        TraceCollectorProxy.disableCollector()
    }

    override fun afterMethod(statement: MethodStatement, scope: Scope, exception: Throwable?) {
        TraceCollectorProxy.disableCollector()
    }

    override fun testExecutionFinished(r: ExecutionResult, s: Scope) {
        // Nothing
    }

    override fun clear() {
        collectors.clear()
    }

}