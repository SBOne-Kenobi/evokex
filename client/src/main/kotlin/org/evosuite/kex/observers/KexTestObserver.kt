package org.evosuite.kex.observers

import org.evosuite.testcase.execution.ExecutionResult
import org.evosuite.testcase.execution.Scope
import org.evosuite.testcase.statements.*
import org.evosuite.testcase.statements.environment.EnvironmentDataStatement
import org.evosuite.testcase.variable.*
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.ktype.KexType
import org.vorpal.research.kex.ktype.kexType
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.state.predicate.Predicate
import org.vorpal.research.kex.state.predicate.state
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kex.state.term.TermBuilder.Terms.const
import org.vorpal.research.kex.state.term.TermBuilder.Terms.field
import org.vorpal.research.kex.state.term.TermBuilder.Terms.load
import org.vorpal.research.kex.state.term.TermBuilder.Terms.staticRef
import org.vorpal.research.kex.state.term.term
import org.vorpal.research.kex.trace.symbolic.InstructionTraceCollector
import org.vorpal.research.kex.trace.symbolic.StateClause
import org.vorpal.research.kex.trace.symbolic.SymbolicTraceBuilder
import org.vorpal.research.kex.trace.symbolic.TraceCollectorProxy.disableCollector
import org.vorpal.research.kex.trace.symbolic.TraceCollectorProxy.enableCollector
import org.vorpal.research.kex.trace.symbolic.TraceCollectorProxy.initializeEmptyCollector
import org.vorpal.research.kex.trace.symbolic.TraceCollectorProxy.setCurrentCollector
import org.vorpal.research.kex.util.toKfgType
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.ir.Field
import org.vorpal.research.kfg.ir.value.EmptyUsageContext
import org.vorpal.research.kfg.ir.value.NameMapperContext
import org.vorpal.research.kfg.ir.value.UsageContext
import org.vorpal.research.kfg.ir.value.Value
import org.vorpal.research.kfg.ir.value.instruction.BinaryOpcode
import org.vorpal.research.kfg.ir.value.instruction.CmpOpcode
import org.vorpal.research.kfg.ir.value.instruction.Instruction
import org.vorpal.research.kfg.ir.value.instruction.InstructionBuilder
import org.vorpal.research.kthelper.assert.unreachable
import ru.spbstu.wheels.runIf
import java.lang.reflect.Method

private typealias KFGMethod = org.vorpal.research.kfg.ir.Method

// TODO: refactor KexObserver and SymbolicTraceBuilder
class KexTestObserver(executionContext: ExecutionContext, private val id: Int) : KexObserver(executionContext),
    InstructionBuilder {
    sealed interface WrappedValue {
        val value: Value

        val type: KexType get() = value.type.kexType
        val name: String get() = value.name.toString()
    }

    private data class KexValue(override val value: Value) : WrappedValue

    private data class EvoVar(val refName: String, override val value: Value) : WrappedValue

    private fun Value.wrap() = KexValue(this)
    private fun VariableReference.wrap(value: Value) = EvoVar(name, value)

    override val cm: ClassManager
        get() = executionContext.cm
    override val ctx: UsageContext = EmptyUsageContext

    private var collector: SymbolicTraceBuilder
    private val emptyCollector: InstructionTraceCollector

    private val stateBuilder get() = collector.stateBuilder

    val valueCache = mutableMapOf<String, WrappedValue>()
    val termCache = mutableMapOf<WrappedValue, Term>()

    val trace get() = collector.symbolicState

    init {
        collector = enableCollector(executionContext, NameMapperContext()) as SymbolicTraceBuilder
        emptyCollector = initializeEmptyCollector()
    }

    override fun beforeStatement(statement: Statement, scope: Scope) {
        super.beforeStatement(statement, scope)
        setCurrentCollector(collector)
    }

    private fun buildField(field: Field, source: WrappedValue?, name: String): Pair<Instruction, Term> {
        val instruction = if (field.isStatic) {
            field.load(name)
        } else {
            source!!.value.load(name, field)
        }

        val ownerTerm = source?.let { mkTerm(it) }
        val actualOwner = ownerTerm ?: staticRef(field.klass)
        val term = actualOwner.field(field.type.kexType, field.name).load()

        return instruction to term
    }

    override fun beforeField(statement: FieldStatement, scope: Scope) {
        val field = statement.field.kfgField
        val source = statement.source?.let { mkValue(it) }

        val (instruction, loadTerm) = buildField(field, source, statement.returnValue.name)
        val valueTerm = register(statement.returnValue, instruction)
        val predicate = state { valueTerm equality loadTerm }

        postProcess(instruction, predicate)
    }

    override fun beforeArray(statement: ArrayStatement, scope: Scope) {
        // TODO: probably make dims symbolic
        val componentType = types.get(statement.arrayReference.componentClass)
        val dims = statement.lengths.map { it.asValue }
        val instruction = componentType.asArray.newArray(statement.arrayReference.name, dims)

        val valueTerm = register(statement.returnValue, instruction)
        val dimsTerm = statement.lengths.map { const(it) }
        val predicate = state {
            valueTerm.new(dimsTerm)
        }

        postProcess(instruction, predicate)
    }

    override fun beforeConstructor(statement: ConstructorStatement, scope: Scope) {
        val constructor = statement.constructor.constructor.kfgMethod
        val args = statement.parameterReferences.map { mkValue(it) }

        val newInst = constructor.klass.new(statement.returnValue.name)
        val newTerm = register(statement.returnValue, newInst)
        val predicate = state {
            newTerm.new()
        }
        postProcess(newInst, predicate)

        collector.lastCall = buildCall(constructor, null, mkValue(statement.returnValue), args, scope)
    }

    override fun beforeMethod(statement: MethodStatement, scope: Scope) {
        val method = statement.method.method.kfgMethod
        val callee = statement.callee?.let { mkValue(it) }
        val args = statement.parameterReferences.map { mkValue(it) }

        collector.lastCall = buildCall(method, statement.returnValue, callee, args, scope)
    }

    private fun buildCall(
        method: KFGMethod,
        returnValue: VariableReference?,
        callee: WrappedValue?,
        args: List<WrappedValue>,
        scope: Scope
    ): SymbolicTraceBuilder.Call {
        val unwrappedArgs = args.map { it.value }
        val name = returnValue?.name
        val isVoid = returnValue?.type?.let { it == Void.TYPE } ?: true
        val instruction = when {
            method.isStatic && isVoid -> method.staticCall(method.klass, unwrappedArgs)
            method.isStatic -> method.staticCall(method.klass, name!!, unwrappedArgs)

            method.isConstructor -> method.specialCall(method.klass, callee!!.value, unwrappedArgs)

            method.klass.isInterface && isVoid -> method.interfaceCall(method.klass, callee!!.value, unwrappedArgs)
            method.klass.isInterface -> method.interfaceCall(method.klass, name!!, callee!!.value, unwrappedArgs)

            isVoid -> method.virtualCall(method.klass, callee!!.value, unwrappedArgs)
            else -> method.virtualCall(method.klass, name!!, callee!!.value, unwrappedArgs)
        }

        val valueTerm = runIf(!isVoid) { register(returnValue!!, instruction) }
        val calleeTerm = callee?.let { mkTerm(it) }
        val argsTerm = args.map { mkTerm(it) }

        val predicate = state {
            val actualCallee = calleeTerm ?: staticRef(method.klass)
            val callTerm = actualCallee.call(method, argsTerm)
            valueTerm?.call(callTerm) ?: call(callTerm)
        }

        return SymbolicTraceBuilder.Call(
            instruction, method,
            valueTerm?.let { instruction to it },
            Parameters(calleeTerm, argsTerm), predicate
        )
    }

    override fun beforeMock(statement: FunctionalMockStatement, scope: Scope) {
        TODO("Not supported in Kex")
    }

    override fun beforeExpression(statement: PrimitiveExpression, scope: Scope) {
        val lhv = mkValue(statement.leftOperand)
        val rhv = mkValue(statement.rightOperand)

        val binOpcode = statement.operator.getBinOpcode()
        val cmpOpcode = statement.operator.getCmpOpcode()

        val name = statement.returnValue.name
        val instruction = if (binOpcode != null) {
            binary(name, binOpcode, lhv.value, rhv.value)
        } else if (cmpOpcode != null) {
            cmp(name, statement.returnClass.toKfgType(types), cmpOpcode, lhv.value, rhv.value)
        } else {
            unreachable { }
        }

        val valueTerm = register(statement.returnValue, instruction)
        val lhvTerm = mkTerm(lhv)
        val rhvTerm = mkTerm(rhv)

        val predicate = state {
            if (binOpcode != null) {
                valueTerm equality lhvTerm.apply(types, binOpcode, rhvTerm)
            } else if (cmpOpcode != null) {
                valueTerm equality lhvTerm.apply(cmpOpcode, rhvTerm)
            } else {
                unreachable { }
            }
        }

        postProcess(instruction, predicate)
    }

    private fun PrimitiveExpression.Operator.getCmpOpcode(): CmpOpcode? = when (this) {
        PrimitiveExpression.Operator.LESS -> CmpOpcode.LT
        PrimitiveExpression.Operator.GREATER -> CmpOpcode.GT
        PrimitiveExpression.Operator.LESS_EQUALS -> CmpOpcode.LE
        PrimitiveExpression.Operator.GREATER_EQUALS -> CmpOpcode.GE
        PrimitiveExpression.Operator.EQUALS -> CmpOpcode.EQ
        PrimitiveExpression.Operator.NOT_EQUALS -> CmpOpcode.NEQ
        else -> null
    }

    private fun PrimitiveExpression.Operator.getBinOpcode(): BinaryOpcode? = when (this) {
        PrimitiveExpression.Operator.TIMES -> BinaryOpcode.MUL
        PrimitiveExpression.Operator.DIVIDE -> BinaryOpcode.DIV
        PrimitiveExpression.Operator.REMAINDER -> BinaryOpcode.REM
        PrimitiveExpression.Operator.PLUS -> BinaryOpcode.ADD
        PrimitiveExpression.Operator.MINUS -> BinaryOpcode.SUB
        PrimitiveExpression.Operator.LEFT_SHIFT -> BinaryOpcode.SHL
        PrimitiveExpression.Operator.RIGHT_SHIFT_SIGNED -> BinaryOpcode.SHR
        PrimitiveExpression.Operator.RIGHT_SHIFT_UNSIGNED -> BinaryOpcode.USHR
        PrimitiveExpression.Operator.XOR -> BinaryOpcode.XOR
        PrimitiveExpression.Operator.AND, PrimitiveExpression.Operator.CONDITIONAL_AND -> BinaryOpcode.AND
        PrimitiveExpression.Operator.OR, PrimitiveExpression.Operator.CONDITIONAL_OR -> BinaryOpcode.OR
        else -> null
    }

    override fun beforeAssignment(statement: AssignmentStatement, scope: Scope) {
        val value = mkValue(statement.value)
        val termValue = mkTerm(value)

        val clause = when (val retval = statement.returnValue) {
            is ArrayIndex -> {
                // TODO: probably make index symbolic
                val array = mkValue(retval.array)
                val instruction = array.value.store(retval.arrayIndex, value.value)

                val arrayTerm = mkTerm(array)
                val predicate = state { arrayTerm[retval.arrayIndex].store(termValue) }

                StateClause(instruction, predicate)
            }

            is FieldReference -> {
                val field = retval.field.kfgField
                val owner = retval.source?.let { mkValue(it) }

                val instruction = if (retval.field.isStatic) {
                    field.store(value.value)
                } else {
                    owner!!.value.store(field, value.value)
                }

                val termOwner = owner?.let { mkTerm(it) }

                val predicate = state {
                    val actualOwner = termOwner ?: staticRef(field.klass)
                    actualOwner.field(field.type.kexType, field.name).store(termValue)
                }

                StateClause(instruction, predicate)
            }

            else -> unreachable { }
        }

        postProcess(clause)
    }

    override fun beforePrimitive(statement: PrimitiveStatement<*>, scope: Scope) {
        when (statement) {
            is EnumPrimitiveStatement<*> -> TODO()
            is EnvironmentDataStatement<*> -> TODO("need more research here")
            else -> {
                val value = buildValue(statement.value, statement.returnClass)
                register(statement.returnValue, value)
            }
        }
    }

    private fun postProcess(instruction: Instruction, predicate: Predicate) {
        postProcess(StateClause(instruction, predicate))
    }

    private fun postProcess(clause: StateClause) {
        stateBuilder += clause
    }

    private fun register(ref: VariableReference, value: Value): Term {
        val wrapped = ref.wrap(value)
        valueCache[ref.name] = wrapped
        return mkNewTerm(wrapped, ref.name)
    }

    private fun mkValue(ref: VariableReference): WrappedValue =
        valueCache.getOrElse(ref.name) { mkNewValue(ref) }

    private fun mkNewValue(ref: VariableReference): WrappedValue {
        var needCaching = true

        val value = when (ref) {
            is NullReference -> ref.wrap(values.nullConstant)
            is ConstantValue -> ref.wrap(buildValue(ref.value, ref.variableClass))
            is ArrayIndex -> {
                // TODO: probably make index symbolic
                needCaching = false

                val array = mkValue(ref.array)
                val instruction = array.value.load(TMP_NAME, ref.arrayIndex)
                val wrappedInstruction = instruction.wrap()

                val arrayTerm = mkTerm(array)
                val valueTerm = mkNewTerm(wrappedInstruction)
                val predicate = state { valueTerm equality arrayTerm[ref.arrayIndex].load() }
                postProcess(instruction, predicate)

                wrappedInstruction
            }

            is FieldReference -> {
                needCaching = false

                val field = ref.field.kfgField
                val source = ref.source?.let { mkValue(it) }

                val (instruction, loadTerm) = buildField(field, source, TMP_NAME)
                val wrappedInstruction = instruction.wrap()
                val valueTerm = mkNewTerm(wrappedInstruction)
                val predicate = state { valueTerm equality loadTerm }
                postProcess(instruction, predicate)

                wrappedInstruction
            }

            else -> unreachable { }
        }

        if (needCaching) {
            valueCache[ref.name] = value
        }
        return value
    }

    private fun buildValue(value: Any?, clazz: Class<*>): Value = when (clazz) {
        Boolean::class.java -> values.getBool(value as Boolean)
        Byte::class.java -> values.getByte(value as Byte)
        Char::class.java -> values.getChar(value as Char)
        Short::class.java -> values.getShort(value as Short)
        Int::class.java -> values.getInt(value as Int)
        Long::class.java -> values.getLong(value as Long)
        Float::class.java -> values.getFloat(value as Float)
        Double::class.java -> values.getDouble(value as Double)
        String::class.java -> values.getString(value as String)
        Class::class.java -> values.getClass(types.get(value as Class<*>))
        Method::class.java -> values.getMethod((value as Method).kfgMethod)
        else -> unreachable { }
    }

    private fun mkTerm(value: WrappedValue): Term = termCache.getOrElse(value) { mkNewTerm(value) }

    private fun mkNewTerm(value: WrappedValue, name: String? = null): Term = term {
        termFactory.getValue(
            value.type,
            collector.nameGenerator.nextName("$evoPrefix${name ?: value.name}")
        )
    }.also { termCache[value] = it }

    override fun afterStatement(statement: Statement, scope: Scope, exception: Throwable?) {
        setCurrentCollector(emptyCollector)

        collector.lastCall?.let {
            postProcess(it.call, it.predicate)
        }
        collector.lastCall = null
    }

    override fun testExecutionFinished(r: ExecutionResult, s: Scope) {
        disableCollector()
    }

    override fun clear() {
        collector = SymbolicTraceBuilder(executionContext, NameMapperContext())
        valueCache.clear()
        termCache.clear()
        setCurrentCollector(emptyCollector)
    }

    private val evoPrefix = "$EVO_NAME$id%"

    companion object {
        private const val TMP_NAME = "tmp"
        const val EVO_NAME = "%evo%"
    }

}
