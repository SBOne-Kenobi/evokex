package org.evosuite.kex.ps

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.state.predicate.*
import org.vorpal.research.kex.state.term.ConstBoolTerm
import org.vorpal.research.kex.state.term.numericValue
import org.vorpal.research.kex.trace.symbolic.*
import org.vorpal.research.kfg.ir.BasicBlock
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.IntConstant
import org.vorpal.research.kfg.ir.value.Value
import org.vorpal.research.kfg.ir.value.instruction.BranchInst
import org.vorpal.research.kfg.ir.value.instruction.SwitchInst
import org.vorpal.research.kfg.ir.value.instruction.TableSwitchInst
import org.vorpal.research.kthelper.assert.unreachable
import org.vorpal.research.kthelper.graph.GraphView
import org.vorpal.research.kthelper.graph.PredecessorGraph
import org.vorpal.research.kthelper.graph.Viewable
import org.vorpal.research.kthelper.logging.log

sealed class Vertex(
    val state: PersistentSymbolicState = persistentSymbolicState(),
    val parent: Vertex? = null,
) : PredecessorGraph.PredecessorVertex<Vertex> {
    val path get() = state.path
    val clauses get() = state.clauses

    override val predecessors by lazy { setOfNotNull(parent) }
}

class EntryVertex : Vertex(), MutableMap<Method, MethodEntryVertex> by mutableMapOf() {
    override val successors: Set<Vertex>
        get() = values.toSet()

    override fun toString(): String = "Entry"
}

abstract class InnerVertex(state: PersistentSymbolicState, parent: Vertex, val method: Method) : Vertex(state, parent),
    MutableMap<PathClause, PathClauseVertex> by mutableMapOf() {
    override val successors: Set<Vertex>
        get() = values.toSet()
}

class MethodEntryVertex(method: Method, entry: EntryVertex) : InnerVertex(entry.state, entry, method) {
    override fun toString(): String = "MethodEntry ${method.name}${method.desc}"
}

class PathClauseVertex(
    val pathClause: PathClause,
    state: PersistentSymbolicState,
    parent: InnerVertex,
) : InnerVertex(state, parent, parent.method) {
    override fun toString(): String = "${pathClause.predicate}"
}

interface CandidatesObserver {
    fun onNewCandidate(candidate: PathClauseVertex)
    fun onCandidateInvalidate(candidate: PathClauseVertex)
}

class PathConditionTree(private val ctx: ExecutionContext, private val candidatesObserver: CandidatesObserver) :
    PredecessorGraph<Vertex>, Viewable {
    override val entry: EntryVertex = EntryVertex()
    private val _nodes = mutableSetOf<Vertex>(entry)

    override val nodes: Set<Vertex>
        get() = _nodes.toSet()

    private val coveredChildren = mutableMapOf<InnerVertex, Int>()
    private val InnerVertex.isCovered get() = coveredChildren[this] == size

    private val visited = mutableSetOf<InnerVertex>()
    private val InnerVertex.isVisited get() = this in visited

    fun addTrace(method: Method, trace: SymbolicState) {
        val methodEntryVertex = entry.getOrPut(method) {
            MethodEntryVertex(method, entry).also { _nodes += it }
        }

        var prevVertex: InnerVertex = methodEntryVertex
        var state: PersistentSymbolicState = methodEntryVertex.state

        for (clause in trace.clauses) {
            when (clause) {
                is PathClause -> {
                    var initialized = false
                    if (!prevVertex.isVisited) {
                        visited += prevVertex
                        prevVertex.initBy(state, clause)
                        initialized = true
                    }
                    if (prevVertex.isCovered) return

                    val currentVertex = prevVertex[clause]!!
                    if (!currentVertex.isVisited && !initialized) {
                        candidatesObserver.onCandidateInvalidate(currentVertex)
                    }
                    state = currentVertex.state
                    prevVertex = currentVertex
                }

                is StateClause -> state += clause
            }
        }
        if (!prevVertex.isVisited) {
            visited += prevVertex
            markCovered(prevVertex)
        } else {
            assert(prevVertex.isCovered)
        }
    }

    private fun markCovered(vertex: InnerVertex) {
        var currentVertex = vertex
        while (currentVertex.isCovered) {
            currentVertex = currentVertex.parent as? InnerVertex ?: return
            coveredChildren.compute(currentVertex) { _, old ->
                old?.let { it + 1 } ?: 1
            }
        }
    }

    private fun PersistentSymbolicState.updateWith(clause: PathClause): PersistentSymbolicState =
        PersistentSymbolicState(
            clauses = clauses + clause,
            path = path + clause,
            concreteValueMap = concreteValueMap,
            termMap = termMap
        )

    private fun InnerVertex.initBy(state: PersistentSymbolicState, pathClause: PathClause) {
        this[pathClause] = PathClauseVertex(pathClause, state.updateWith(pathClause), this).also {
            _nodes += it
        }

        pathClause.getOtherBranches().forEach {
            val vertex = PathClauseVertex(it, state.updateWith(it), this)
            this[it] = vertex
            _nodes += vertex
            candidatesObserver.onNewCandidate(vertex)
        }
    }

    fun PathClause.getOtherBranches(): List<PathClause> = when (type) {
        PathClauseType.NULL_CHECK -> listOf(copy(predicate = predicate.reverseBoolCond()))
        PathClauseType.TYPE_CHECK -> listOf(copy(predicate = predicate.reverseBoolCond()))
        PathClauseType.OVERLOAD_CHECK -> listOf(copy(predicate = predicate.reverseBoolCond()))
        PathClauseType.CONDITION_CHECK -> when (val inst = instruction) {
            is BranchInst -> listOf(copy(predicate = predicate.reverseBoolCond()))
            is SwitchInst -> predicate.getOtherSwitchBranches(inst.branches).map { copy(predicate = it) }
            is TableSwitchInst -> {
                val branches = inst.range.let { range ->
                    range.associateWith { inst.branches[it - range.first] }
                        .mapKeys { ctx.values.getInt(it.key) }
                }
                predicate.getOtherSwitchBranches(branches).map { copy(predicate = it) }
            }

            else -> unreachable { log.error("Unexpected instruction $inst") }
        }

        PathClauseType.BOUNDS_CHECK -> listOf(copy(predicate = predicate.reverseBoolCond()))
    }

    private fun Predicate.getOtherSwitchBranches(branches: Map<Value, BasicBlock>): List<Predicate> = when (this) {
        is DefaultSwitchPredicate -> {
            val candidates = branches.keys.mapTo(mutableSetOf()) { (it as IntConstant).value }
            candidates.map {
                predicate(type, location) {
                    cond equality it
                }
            }
        }

        is EqualityPredicate -> buildList {
            val candidates = branches.keys.mapTo(mutableSetOf()) { (it as IntConstant).value }
            candidates.forEach {
                if (it != rhv.numericValue) {
                    add(predicate(type, location) {
                        lhv equality it
                    })
                }
            }
            add(predicate(type, location) {
                lhv `!in` candidates.map { const(it) }
            })
        }

        else -> unreachable { log.error("Unexpected predicate in switch clause: $this") }
    }

    private fun Predicate.reverseBoolCond() = when (this) {
        is EqualityPredicate -> predicate(type, location) {
            lhv equality !(rhv as ConstBoolTerm).value
        }

        is InequalityPredicate -> predicate(type, location) {
            lhv inequality !(rhv as ConstBoolTerm).value
        }

        else -> unreachable { log.error("Unexpected predicate in bool cond: $this") }
    }

    override val graphView: List<GraphView>
        get() {
            val graphNodes = mutableMapOf<Vertex, GraphView>()

            for ((i, vertex) in _nodes.withIndex()) {
                val name = buildString {
                    if (vertex is InnerVertex) {
                        if (vertex.isCovered) {
                            appendLine("covered")
                        } else if (vertex.isVisited) {
                            appendLine("visited")
                        } else {
                            appendLine("candidate")
                        }
                    }
                    append(vertex)
                }
                graphNodes[vertex] = GraphView("$i", name)
            }

            for (vertex in _nodes) {
                val current = graphNodes.getValue(vertex)
                for (child in vertex.successors) {
                    current.addSuccessor(graphNodes.getValue(child))
                }
            }

            return graphNodes.values.toList()
        }

}
