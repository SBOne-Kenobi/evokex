package org.evosuite.kex.ps

import org.evosuite.Properties
import org.slf4j.LoggerFactory
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.concolic.ConcolicPathSelector
import org.vorpal.research.kex.reanimator.codegen.validName
import org.vorpal.research.kex.trace.symbolic.PathClause
import org.vorpal.research.kex.trace.symbolic.PathClauseType
import org.vorpal.research.kex.trace.symbolic.PersistentSymbolicState
import org.vorpal.research.kex.trace.symbolic.protocol.ExecutionCompletedResult
import org.vorpal.research.kex.util.asmString
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.instruction.Instruction
import org.vorpal.research.kthelper.collection.stackOf
import org.vorpal.research.kthelper.graph.GraphView
import org.vorpal.research.kthelper.graph.Viewable
import java.util.*

class AdvancedBfsPathSelector(override val ctx: ExecutionContext) : ConcolicPathSelector, CandidatesObserver {
    private val statLogger = LoggerFactory.getLogger("StatLogger")

    private val instructionsGraph = InstructionsGraph()
    private val tree = PathConditionTree(ctx, this)

    private var needUpdate: Boolean = false
    private val condensationGraph = CondensationGraph()

    private data class Candidate(val covered: Boolean, val fullyCovered: Boolean, val clause: PathClauseVertex)

    private val _candidates = PriorityQueue(
        compareBy<Candidate> { it.covered }
            .thenBy { it.fullyCovered }
            .thenBy { it.clause.state.clauses.size * it.clause.multiplier }
    )

    init {
        val klass = ctx.cm[Properties.TARGET_CLASS.asmString]
        klass.methods.forEach {
            instructionsGraph.addMethod(it.body)
        }
        klass.constructors.forEach {
            instructionsGraph.addMethod(it.body)
        }
    }

    lateinit var lastCandidate: PathClauseVertex
        private set

    private val PathClauseVertex.instruction: Instruction
        get() = nextExpectedInstruction ?: pathClause.instruction

    private val PathClauseVertex.isFullyCovered: Boolean
        get() = condensationGraph.isFullyCovered(instruction)

    override suspend fun isEmpty(): Boolean = _candidates.isEmpty()

    override suspend fun hasNext(): Boolean = _candidates.isNotEmpty()

    override suspend fun next(): PersistentSymbolicState = _candidates.poll()!!.clause.also {
        statLogger.debug(
            "Candidate type, size, class, method: {}, {}, {}, {}",
            it.pathClause.type, it.state.clauses.size,
            it.method.klass.validName, it.method.validName
        )
        lastCandidate = it
    }.state

    fun recomputeCoverage() {
        if (!needUpdate) return
        needUpdate = false

        condensationGraph.clear()
        buildCondensationGraph()
        condensationGraph.computeCoverageOnCondensationGraph()

        val clauses = _candidates.map { it.clause }
        _candidates.clear()
        _candidates.addAll(clauses.map {
            Candidate(
                instructionsGraph.isCovered(it.instruction),
                it.isFullyCovered,
                it
            )
        })
    }

    private class CondensationGraph {
        val components: MutableList<MutableList<Instruction>> = mutableListOf()
        val instruction2Component = IdentityHashMap<Instruction, Int>()
        val successors = HashMap<Int, MutableList<Int>>()
        val covered = HashMap<Int, Boolean>()
        val fullyCovered = HashMap<Int, Boolean>()

        fun isFullyCovered(instruction: Instruction): Boolean {
            val component = instruction2Component[instruction] ?: return true
            return fullyCovered.getValue(component)
        }

        fun clear() {
            components.clear()
            instruction2Component.clear()
            successors.clear()
            covered.clear()
            fullyCovered.clear()
        }

        fun computeCoverageOnCondensationGraph() {
            components.indices.forEach {
                if (it !in fullyCovered) {
                    dfs(it)
                }
            }
        }

        private data class StackEntry(val component: Int, val children: List<Int>, var next: Int)

        private fun dfs(entryPoint: Int) {
            val stack = stackOf<StackEntry>()
            stack.push(StackEntry(entryPoint, successors.getValue(entryPoint), 0))
            loop@ while (stack.isNotEmpty()) {
                val current = stack.peek()
                if (current.next == 0) {
                    if (!covered.getValue(current.component)) {
                        fullyCovered[current.component] = false
                    }
                }
                if (current.next < current.children.size) {
                    val next = current.children[current.next]
                    when (fullyCovered[next]) {
                        true -> {}
                        false -> fullyCovered.putIfAbsent(current.component, false)
                        null -> {
                            stack.push(StackEntry(next, successors.getValue(next), 0))
                            continue@loop
                        }
                    }
                    current.next++
                } else {
                    fullyCovered.putIfAbsent(current.component, true)
                    stack.pop()
                }
            }
        }
    }

    private fun buildCondensationGraph() {
        val order = buildOrder()
        collectComponents(order)
    }

    private data class StackEntry(val instruction: Instruction, val children: List<Instruction>, var next: Int)

    private fun fullDfs(
        order: List<Instruction>,
        getChildren: (Instruction) -> List<Instruction>,
        onNextEntry: (Instruction) -> Unit = {},
        onExit: (Instruction) -> Unit = {}
    ) {
        val stack = stackOf<StackEntry>()
        val visited = mutableSetOf<Instruction>()
        for (entry in order) {
            if (entry !in visited) {
                onNextEntry(entry)
                stack.push(StackEntry(entry, getChildren(entry), 0))
                while (stack.isNotEmpty()) {
                    val current = stack.peek()
                    if (current.next == 0) {
                        visited += current.instruction
                    }
                    if (current.next < current.children.size) {
                        val next = current.children[current.next++]
                        if (next !in visited) {
                            stack.push(StackEntry(next, getChildren(next), 0))
                        }
                    } else {
                        onExit(current.instruction)
                        stack.pop()
                    }
                }
            }
        }
    }

    private fun buildOrder(): List<Instruction> {
        val order = mutableListOf<Instruction>()
        fullDfs(instructionsGraph.getEntries(), getChildren = instructionsGraph::succ, onExit = order::add)
        return order
    }

    private fun collectComponents(order: List<Instruction>) {
        fullDfs(
            order.asReversed(),
            getChildren = instructionsGraph::pred,
            onNextEntry = { condensationGraph.components.add(mutableListOf()) },
            onExit = {
                condensationGraph.components.last().add(it)
                condensationGraph.instruction2Component[it] = condensationGraph.components.lastIndex
            }
        )
        condensationGraph.components.forEachIndexed { index, instructions ->
            var covered = true
            val succ = mutableSetOf<Int>()
            instructions.forEach { instruction ->
                covered = covered && instructionsGraph.isCovered(instruction)
                instructionsGraph.succ(instruction).forEach {
                    val component = condensationGraph.instruction2Component.getValue(it)
                    if (component != index) {
                        succ += component
                    }
                }
            }
            condensationGraph.covered[index] = covered
            condensationGraph.successors[index] = succ.toMutableList()
        }
    }

    override suspend fun addExecutionTrace(method: Method, result: ExecutionCompletedResult) {
        tree.addTrace(method, result.trace)
    }

    fun addExecutionInstructionTrace(trace: List<Instruction>) {
        needUpdate = true
        instructionsGraph.consume(trace)
    }

    override fun reverse(pathClause: PathClause): PathClause? = tree.run {
        pathClause.getOtherBranches().randomOrNull(ctx.random)
    }

    override fun onNewCandidate(candidate: PathClauseVertex) {
        _candidates += Candidate(
            instructionsGraph.isCovered(candidate.instruction),
            candidate.isFullyCovered,
            candidate
        )
    }

    private val PathClauseVertex.multiplier: Double
        get() = when (pathClause.type) {
            PathClauseType.CONDITION_CHECK -> 1.0
            PathClauseType.NULL_CHECK -> 2.0
            PathClauseType.BOUNDS_CHECK -> 3.0
            PathClauseType.TYPE_CHECK -> 3.5
            PathClauseType.OVERLOAD_CHECK -> 4.0
        }

    override fun onCandidateInvalidate(candidate: PathClauseVertex) {
        _candidates -= Candidate(
            instructionsGraph.isCovered(candidate.instruction),
            candidate.isFullyCovered,
            candidate
        )
    }

    fun viewPathTree() {
        tree.view("Path_Tree", "/usr/bin/dot", "/usr/bin/display")
    }

    private inner class CoverageGraphVisualizer : Viewable {

        private fun createView(instruction: Instruction, name: String) =
            GraphView(name, "${instruction.parent.method.validName}#${instruction.print().replace("\"", "")}") {
                if (condensationGraph.isFullyCovered(instruction)) {
                    it.setColor("green")
                } else if (instructionsGraph.isCovered(instruction)) {
                    it.setColor("yellow")
                }
            }

        override val graphView: List<GraphView>
            get() {
                val graphNodes = mutableMapOf<Instruction, GraphView>()

                for ((src, dst) in instructionsGraph.explicitEdges) {
                    val srcView = graphNodes.computeIfAbsent(src) {
                        createView(src, "${graphNodes.size}")
                    }
                    val dstView = graphNodes.computeIfAbsent(dst) {
                        createView(dst, "${graphNodes.size}")
                    }
                    srcView.addSuccessor(dstView)
                }

                return graphNodes.values.toList()
            }
    }

    fun viewInstructionsGraph() {
        CoverageGraphVisualizer().view("Instructions_Graph", "/usr/bin/dot", "/usr/bin/display")
    }
}
