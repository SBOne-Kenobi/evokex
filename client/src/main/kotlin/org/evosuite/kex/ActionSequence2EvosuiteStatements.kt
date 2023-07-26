package org.evosuite.kex

import org.evosuite.testcase.TestCase
import org.evosuite.testcase.statements.*
import org.evosuite.testcase.variable.*
import org.evosuite.utils.generic.*
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.reanimator.actionsequence.*
import org.vorpal.research.kex.util.getConstructor
import org.vorpal.research.kex.util.getMethod
import org.vorpal.research.kex.util.loadClass
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.type.ArrayType
import java.lang.reflect.Type

private typealias KFGType = org.vorpal.research.kfg.type.Type
private typealias KFGClass = org.vorpal.research.kfg.ir.Class

class ActionSequence2EvosuiteStatements(private val ctx: ExecutionContext, private val testCase: TestCase) {

    private val loader get() = ctx.loader

    private val refs = mutableMapOf<String, VariableReference>()

    private val ActionSequence.ref: VariableReference get() = refs[name]!!

    private operator fun Statement.unaryPlus() {
        testCase.addStatement(this)
    }

    private fun ActionSequence.createRef(type: Type): VariableReference = VariableReferenceImpl(testCase, type).also {
        refs[name] = it
    }

    private val KFGType.java: Type
        get() = when (this) {
            is ArrayType -> GenericArrayTypeImpl.createArrayType(component.java)
            else -> loader.loadClass(this)
        }

    private val KFGClass.java: Class<*> get() = loader.loadClass(this)

    fun generateStatements(actionSequence: ActionSequence) {
        if (actionSequence.name in refs) return
        when (actionSequence) {
            is ActionList -> actionSequence.list.forEach { generateStatementsFromAction(actionSequence, it) }
            is ReflectionList -> actionSequence.list.forEach { generateStatementsFromReflection(actionSequence, it) }
            is TestCall -> TODO()
            is UnknownSequence -> TODO()
            is PrimaryValue<*>, is StringValue -> {
                // nothing
            }
        }
    }

    fun generateTestCall(method: Method, parameters: Parameters<ActionSequence>) {
        TODO()
    }

    private fun ActionSequence.generateAndGetRef(): VariableReference {
        generateStatements(this)
        return ref
    }

    private fun generateStatementsFromReflection(owner: ReflectionList, call: ReflectionCall) {
        when (call) {
            is ReflectionArrayWrite -> generateReflectionCall(owner, call)
            is ReflectionNewArray -> generateReflectionCall(owner, call)
            is ReflectionNewInstance -> generateReflectionCall(owner, call)
            is ReflectionSetField -> generateReflectionCall(owner, call)
            is ReflectionSetStaticField -> generateReflectionCall(owner, call)
        }
    }

    private fun generateReflectionCall(owner: ReflectionList, call: ReflectionArrayWrite) {

    }

    private fun generateReflectionCall(owner: ReflectionList, call: ReflectionNewArray) {

    }

    private fun generateReflectionCall(owner: ReflectionList, call: ReflectionNewInstance) {

    }

    private fun generateReflectionCall(owner: ReflectionList, call: ReflectionSetField) {

    }

    private fun generateReflectionCall(owner: ReflectionList, call: ReflectionSetStaticField) {

    }

    private fun generateStatementsFromAction(owner: ActionList, action: CodeAction) {
        when (action) {
            is ArrayWrite -> generateAction(owner, action)
            is ConstructorCall -> generateAction(owner, action)
            is DefaultConstructorCall -> generateAction(owner, action)
            is EnumValueCreation -> generateAction(owner, action)
            is ExternalConstructorCall -> generateAction(owner, action)
            is ExternalMethodCall -> generateAction(owner, action)
            is FieldSetter -> generateAction(owner, action)
            is InnerClassConstructorCall -> generateAction(owner, action)
            is MethodCall -> generateAction(owner, action)
            is NewArray -> generateAction(owner, action)
            is NewArrayWithInitializer -> generateAction(owner, action)
            is StaticFieldGetter -> generateAction(owner, action)
            is StaticFieldSetter -> generateAction(action)
            is StaticMethodCall -> generateAction(action)
        }
    }

    private fun generateAction(owner: ActionList, action: ArrayWrite) {
        val value = action.value.generateAndGetRef()
        val index = ArrayIndex(
            testCase, owner.ref as ArrayReference, (action.index as PrimaryValue<*>).value as Int
        )
        +AssignmentStatement(testCase, index, value)
    }

    private fun generateConstructorCall(owner: ActionSequence, constructor: Method, args: List<ActionSequence>) {
        val refArgs = args.map { it.generateAndGetRef() }
        val type = constructor.klass.java
        val genericConstructor = GenericConstructor(type.getConstructor(constructor, loader), type)
        val ref = owner.createRef(type)
        +ConstructorStatement(testCase, genericConstructor, ref, refArgs)
    }

    private fun generateAction(owner: ActionList, action: ConstructorCall) {
        generateConstructorCall(owner, action.constructor, action.args)
    }

    private fun generateAction(owner: ActionList, action: DefaultConstructorCall) {
        val type = action.klass.java
        val constructor = type.getConstructor()
        val ref = owner.createRef(type)
        +ConstructorStatement(testCase, GenericConstructor(constructor, type), ref, emptyList())
    }

    private fun generateAction(owner: ActionList, action: EnumValueCreation) {
        TODO("support Enums")
    }

    private fun generateAction(owner: ActionList, action: ExternalConstructorCall) {
        generateConstructorCall(owner, action.constructor, action.args)
    }

    private fun generateAction(owner: ActionList, action: ExternalMethodCall) {
        val callee = action.instance.generateAndGetRef()
        val refArgs = action.args.map { it.generateAndGetRef() }
        val type = action.method.klass.java
        val genericMethod = GenericMethod(type.getMethod(action.method, loader), type)
        val ref = owner.createRef(type)
        +MethodStatement(testCase, genericMethod, callee, refArgs, ref)
    }

    private fun generateAction(owner: ActionList, action: FieldSetter) {
        val value = action.value.generateAndGetRef()
        val fieldOwner = action.field.klass.java
        val fieldRef = FieldReference(
            testCase, GenericField(fieldOwner.getField(action.field.name), fieldOwner), owner.ref
        )
        +AssignmentStatement(testCase, fieldRef, value)
    }

    private fun generateAction(owner: ActionList, action: InnerClassConstructorCall) {
        TODO("it seems like problems in evosuite api")
    }

    private fun generateAction(owner: ActionList, action: MethodCall) {
        val args = action.args.map { it.generateAndGetRef() }
        val type = action.method.klass.java
        val method = GenericMethod(type.getMethod(action.method, loader), type)
        +MethodStatement(testCase, method, owner.ref, args)
    }

    private fun generateNewArray(owner: ActionSequence, type: Type, length: Int): ArrayReference {
        val arrayReference = ArrayReference(testCase, GenericClassImpl(type), length)
        refs[owner.name] = arrayReference
        +ArrayStatement(testCase, arrayReference, intArrayOf(length))
        return arrayReference
    }

    private fun generateAction(owner: ActionList, action: NewArray) {
        val type = action.klass.java
        val length = (action.length as PrimaryValue<*>).value as Int
        generateNewArray(owner, type, length)
    }

    private fun generateAction(owner: ActionList, action: NewArrayWithInitializer) {
        val type = action.klass.java
        val length = action.elements.size
        val array = generateNewArray(owner, type, length)
        action.elements.forEachIndexed { index, value ->
            val valueRef = value.generateAndGetRef()
            val indexRef = ArrayIndex(testCase, array, index)
            +AssignmentStatement(testCase, indexRef, valueRef)
        }
    }

    private fun generateAction(owner: ActionList, action: StaticFieldGetter) {
        val fieldOwner = action.field.klass.java
        val field = GenericField(fieldOwner.getField(action.field.name), fieldOwner)
        val ref = owner.createRef(action.field.type.java)
        +FieldStatement(testCase, field, null, ref)
    }

    private fun generateAction(action: StaticFieldSetter) {
        val value = action.value.generateAndGetRef()
        val fieldOwner = action.field.klass.java
        val field = GenericField(fieldOwner.getField(action.field.name), fieldOwner)
        val fieldRef = FieldReference(testCase, field)
        +AssignmentStatement(testCase, fieldRef, value)
    }

    private fun generateAction(action: StaticMethodCall) {
        val args = action.args.map { it.generateAndGetRef() }
        val type = action.method.klass.java
        val method = GenericMethod(type.getMethod(action.method, loader), type)
        +MethodStatement(testCase, method, null, args)
    }

}