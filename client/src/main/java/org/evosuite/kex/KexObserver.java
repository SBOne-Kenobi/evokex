package org.evosuite.kex;

import org.evosuite.testcase.execution.ExecutionObserver;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.Statement;
import org.vorpal.research.kex.ExecutionContext;
import org.vorpal.research.kex.trace.symbolic.InstructionTraceCollector;
import org.vorpal.research.kex.trace.symbolic.SymbolicTraceBuilder;
import org.vorpal.research.kex.trace.symbolic.TraceCollectorProxy;
import org.vorpal.research.kfg.ir.value.NameMapperContext;

public class KexObserver extends ExecutionObserver {

    private InstructionTraceCollector collector;
    private final InstructionTraceCollector emptyCollector;

    private final ExecutionContext ctx;

    public KexObserver(ExecutionContext ctx) {
        this.ctx = ctx;
        this.collector = TraceCollectorProxy.enableCollector(ctx, new NameMapperContext());
        this.emptyCollector = TraceCollectorProxy.initializeEmptyCollector();
    }

    @Override
    public void output(int position, String output) {
        // Nothing
    }

    @Override
    public void beforeStatement(Statement statement, Scope scope) {
        TraceCollectorProxy.setCurrentCollector(collector);
    }

    @Override
    public void afterStatement(Statement statement, Scope scope, Throwable exception) {
        TraceCollectorProxy.setCurrentCollector(emptyCollector);
    }

    @Override
    public void testExecutionFinished(ExecutionResult r, Scope s) {
        TraceCollectorProxy.disableCollector();
    }

    @Override
    public void clear() {
        this.collector = new SymbolicTraceBuilder(ctx, new NameMapperContext());
    }
}
