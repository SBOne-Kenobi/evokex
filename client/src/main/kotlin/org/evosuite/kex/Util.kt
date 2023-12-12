package org.evosuite.kex

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.evosuite.runtime.RuntimeSettings
import org.evosuite.runtime.sandbox.Sandbox
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.util.checkAsync
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.ktype.KexRtManager.isJavaRt
import org.vorpal.research.kex.ktype.KexRtManager.rtMapped
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.smt.AsyncChecker
import org.vorpal.research.kex.smt.Result
import org.vorpal.research.kex.smt.SMTModel
import org.vorpal.research.kex.state.transformer.toTypeMap
import org.vorpal.research.kex.trace.symbolic.SymbolicState
import org.vorpal.research.kfg.ir.Method
import kotlin.time.ExperimentalTime

internal inline fun <T> withoutSandbox(block: () -> T): T {
    // FIXME: do smth else here with security manager
    val mode = RuntimeSettings.sandboxMode
    RuntimeSettings.sandboxMode = Sandbox.SandboxMode.OFF
    return try {
        block()
    } finally {
        RuntimeSettings.sandboxMode = mode
    }
}

@ExperimentalTime
@InternalSerializationApi
@ExperimentalSerializationApi
@DelicateCoroutinesApi
suspend fun SymbolicState.check(ctx: ExecutionContext, method: Method = KexService.fakeEmptyMethod): SMTModel? =
    withoutSandbox {
        val checker = AsyncChecker(method, ctx)
        val clauses = clauses.asState()
        val query = path.asState()
        val concreteTypeInfo = concreteValueMap
            .mapValues { it.value.type }
            .filterValues { it.isJavaRt }
            .mapValues { it.value.rtMapped }
            .toTypeMap()
        val result = checker.prepareAndCheck(method, clauses + query, concreteTypeInfo, enableInlining = true)
        (result as? Result.SatResult)?.model
    }

suspend fun SymbolicState.checkAndGetParameters(ctx: ExecutionContext, method: Method): Parameters<Descriptor>? =
    withoutSandbox {
        method.checkAsync(ctx, this, enableInlining = true)
    }
