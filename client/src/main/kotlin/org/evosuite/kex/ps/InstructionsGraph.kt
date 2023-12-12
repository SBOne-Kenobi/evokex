package org.evosuite.kex.ps

import org.vorpal.research.kfg.ir.CatchBlock
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.MethodBody
import org.vorpal.research.kfg.ir.value.instruction.CallInst
import org.vorpal.research.kfg.ir.value.instruction.Instruction
import java.util.*

class InstructionsGraph {
    private val bodies = mutableListOf<MethodBody>()
    private val methods = mutableSetOf<Method>()

    val explicitEdges = mutableSetOf<Edge>()
    private val implicitEdges = mutableSetOf<Edge>()

    private val visitedInstructions = mutableSetOf<Instruction>()
    private val coveredEdges = mutableSetOf<Edge>()
    private val coveredImplicitEdges = mutableSetOf<Edge>()

    private val registeredEdges = mutableSetOf<Edge>()
    private val registeredMethods = mutableSetOf<Method>()

    private val edgesCount = IdentityHashMap<Instruction, Int>()
    private val coveredEdgesCount = IdentityHashMap<Instruction, Int>()
    private val registeredEdgesCount = IdentityHashMap<Instruction, Int>()

    private val successors = IdentityHashMap<Instruction, MutableList<Instruction>>()
    private val callSuccessor = IdentityHashMap<Instruction, Edge>()
    private val predecessors = IdentityHashMap<Instruction, MutableList<Instruction>>()

    data class Edge(val src: Instruction, val dst: Instruction) // TODO: add info about branch?

    private fun IdentityHashMap<Instruction, Int>.increase(instruction: Instruction) =
        compute(instruction) { _, v -> (v ?: 0) + 1 }

    private fun IdentityHashMap<Instruction, Int>.init(instruction: Instruction) = putIfAbsent(instruction, 0)

    private fun addExplicitEdge(edge: Edge, registerEdges: Boolean) {
        successors.compute(edge.src) { _, succ ->
            succ?.apply { add(edge.dst) } ?: mutableListOf(edge.dst)
        }
        predecessors.compute(edge.dst) { _, pred ->
            pred?.apply { add(edge.src) } ?: mutableListOf(edge.src)
        }

        explicitEdges += edge
        edgesCount.increase(edge.src)

        if (registerEdges) {
            registeredEdges += edge
            registeredEdgesCount.increase(edge.src)
        }
    }

    fun addMethod(methodBody: MethodBody, registerEdges: Boolean = true) {
        val method = methodBody.method
        val alreadyAdded = method in methods

        if (!alreadyAdded) {
            bodies += methodBody
            methods += method

            if (registerEdges) {
                registeredMethods += method
            }

            for (block in methodBody.basicBlocks) {
                block.forEach {
                    edgesCount.init(it)
                    coveredEdgesCount.init(it)
                    if (registerEdges) {
                        registeredEdgesCount.init(it)
                    }
                }
                block.zipWithNext(::Edge).forEach {
                    if (it.src is CallInst) {
                        callSuccessor[it.src] = it
                    }
                    addExplicitEdge(it, registerEdges)
                }

                block.terminator.let { src ->
                    src.successors.forEach { addExplicitEdge(Edge(src, it.first()), registerEdges) }
                }
            }
        }
    }

    private fun checkIfImplicit(edge: Edge): Boolean {
        val (src, dst) = edge
        if (src.parent != dst.parent && dst.parent is CatchBlock) {
            implicitEdges += edge
            return true
        }
        return false
    }

    private fun checkIfCall(edge: Edge): Boolean {
        // TODO: handle somehow methods unknown for the class; should we register or just add them? (make decision class)
        val (src, dst) = edge
        if (src is CallInst) {
            addMethod(dst.parent.parent, registerEdges = false)
            addExplicitEdge(
                edge, registerEdges = dst.parent.method in registeredMethods && src.parent.method in registeredMethods
            )
            return true
        }
        return false
    }

    fun consume(trace: List<Instruction>) {
        val expectedEdges = hashMapOf<Instruction, Edge>()

        for ((src, dst) in trace.zipWithNext()) {
            visitedInstructions += src
            visitedInstructions += dst

            expectedEdges[dst]?.let {
                if (it !in coveredEdges) {
                    coveredEdgesCount.increase(it.src)
                    coveredEdges += it
                }
                expectedEdges.remove(dst)
            }

            val edge = Edge(src, dst)

            if (edge !in explicitEdges && edge !in implicitEdges) {
                if (!checkIfImplicit(edge)) {
                    if (!checkIfCall(edge)) {
                        continue
                    } else {
                        callSuccessor[src]?.let { expectedEdge ->
                            expectedEdges[expectedEdge.dst] = expectedEdge
                        }
                    }
                }
            }

            when (edge) {
                in implicitEdges -> coveredImplicitEdges += edge
                !in coveredEdges -> {
                    coveredEdgesCount.increase(src)
                    coveredEdges += edge
                }
            }
        }
    }

    fun isCovered(src: Instruction, dst: Instruction): Boolean = Edge(src, dst).let { edge ->
        if (edge in implicitEdges) {
            edge in coveredImplicitEdges
        } else {
            edge in coveredEdges || edge !in registeredEdges
        }
    }

    fun isCovered(instruction: Instruction): Boolean =
        instruction !in registeredEdgesCount ||
                (instruction in visitedInstructions && coveredEdgesCount[instruction]!! >= edgesCount[instruction]!!)

    fun succ(instruction: Instruction): List<Instruction> =
        successors[instruction]?.toList() ?: emptyList()

    fun pred(instruction: Instruction): List<Instruction> =
        predecessors[instruction]?.toList() ?: emptyList()

    fun getEntries(): List<Instruction> =
        bodies.filter { it.method in registeredMethods }.map { it.entry.first() }

}