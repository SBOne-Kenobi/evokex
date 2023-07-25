package org.evosuite.kex.ps

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.concolic.ConcolicPathSelector
import org.vorpal.research.kex.trace.symbolic.PathClause
import org.vorpal.research.kex.trace.symbolic.PersistentSymbolicState
import org.vorpal.research.kex.trace.symbolic.protocol.ExecutionCompletedResult
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kthelper.collection.dequeOf

class AdvancedBfsPathSelector(override val ctx: ExecutionContext) : ConcolicPathSelector, CandidatesObserver {
    private val tree = PathConditionTree(ctx, this)
    private val _candidates = dequeOf<PathClauseVertex>()

    lateinit var lastCandidate: PathClauseVertex
        private set

    override suspend fun isEmpty(): Boolean = _candidates.isEmpty()

    override suspend fun hasNext(): Boolean = _candidates.isNotEmpty()

    override suspend fun next(): PersistentSymbolicState = _candidates.pollFirst()!!.also { lastCandidate = it }.state

    override suspend fun addExecutionTrace(method: Method, result: ExecutionCompletedResult) {
        tree.addTrace(method, result.trace)
    }

    override fun reverse(pathClause: PathClause): PathClause? = tree.run {
        pathClause.getOtherBranches().randomOrNull(ctx.random)
    }

    override fun onNewCandidate(candidate: PathClauseVertex) {
        _candidates += candidate
    }

    override fun onCandidateInvalidate(candidate: PathClauseVertex) {
        _candidates -= candidate
    }

    fun view() {
        tree.view("tree", "/usr/bin/dot", "/usr/bin/display")
    }
}
