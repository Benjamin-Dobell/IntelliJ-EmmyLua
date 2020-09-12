/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.ty

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import com.intellij.util.Processor
import com.intellij.util.containers.ContainerUtil
import com.tang.intellij.lua.Constants
import com.tang.intellij.lua.codeInsight.inspection.MatchFunctionSignatureInspection
import com.tang.intellij.lua.comment.psi.LuaDocClassRef
import com.tang.intellij.lua.project.LuaSettings
import com.tang.intellij.lua.psi.LuaCallExpr
import com.tang.intellij.lua.psi.LuaClassMember
import com.tang.intellij.lua.psi.LuaPsiTreeUtil.findGenericDef
import com.tang.intellij.lua.psi.LuaTableExpr
import com.tang.intellij.lua.psi.argList
import com.tang.intellij.lua.psi.search.LuaShortNamesManager
import com.tang.intellij.lua.search.SearchContext
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

enum class TyKind {
    Unknown,
    Primitive,
    Array,
    Function,
    Class,
    Alias,
    Union,
    Generic,
    Nil,
    Void,
    MultipleResults,
    GenericParam,
    PrimitiveLiteral,
    Snippet
}
enum class TyPrimitiveKind {
    String,
    Number,
    Boolean,
    Table,
    Function
}
class TyFlags {
    companion object {
        const val ANONYMOUS = 0x1
        const val GLOBAL = 0x2
        const val SELF_FUNCTION = 0x4 // xxx.method()
        const val ANONYMOUS_TABLE = 0x8 // local xx = {}, flag of this table `{}`
        const val SHAPE = 0x10 // variance is considered per field
    }
}
class TyVarianceFlags {
    companion object {
        const val STRICT_UNKNOWN = 0x1
        const val ABSTRACT_PARAMS = 0x2 // A generic is to be considered contravariant if its TyParameter generic parameters are contravariant.
        const val WIDEN_TABLES = 0x4 // Generics (and arrays) are to be considered contravariant if their generic parameters (or base) are contravariant.
        const val STRICT_NIL = 0x8 // In certain contexts nil is always strict, irrespective of the user's 'Strict nil checks' setting.
    }
}

class SignatureMatchResult(val signature: IFunSignature, val substitutedSignature: IFunSignature)

interface ITy : Comparable<ITy> {
    val kind: TyKind

    val displayName: String

    val flags: Int

    val booleanType: ITy

    fun equals(other: ITy, context: SearchContext): Boolean

    fun union(ty: ITy): ITy

    fun not(ty: ITy): ITy

    fun contravariantOf(other: ITy, context: SearchContext, flags: Int): Boolean

    fun covariantOf(other: ITy, context: SearchContext, flags: Int): Boolean

    fun getSuperClass(context: SearchContext): ITy?

    fun getParams(context: SearchContext): Array<TyParameter>?

    fun visitSuper(searchContext: SearchContext, processor: Processor<ITyClass>)

    fun substitute(substitutor: ITySubstitutor): ITy

    fun eachTopClass(fn: Processor<ITy>)

    fun accept(visitor: ITyVisitor)

    fun acceptChildren(visitor: ITyVisitor)

    fun findMember(name: String, searchContext: SearchContext): LuaClassMember?
    fun findIndexer(indexTy: ITy, searchContext: SearchContext, exact: Boolean = false): LuaClassMember?

    fun isShape(searchContext: SearchContext): Boolean {
        return flags and TyFlags.SHAPE != 0
    }

    fun guessMemberType(name: String, searchContext: SearchContext): ITy? {
        return findMember(name, searchContext)?.guessType(searchContext)?.let {
            val substitutor = getMemberSubstitutor(searchContext)
            return if (substitutor != null) it.substitute(substitutor) else it
        }
    }

    fun guessIndexerType(indexTy: ITy, searchContext: SearchContext, exact: Boolean = false): ITy? {
        var ty: ITy? = null

        Ty.eachResolved(indexTy, searchContext) {
            val valueTy = if (it is TyPrimitiveLiteral && it.primitiveKind == TyPrimitiveKind.String) {
                guessMemberType(it.value, searchContext)
            } else {
                findIndexer(it, searchContext, exact)?.guessType(searchContext)?.let {
                    val substitutor = getMemberSubstitutor(searchContext)
                    if (substitutor != null) it.substitute(substitutor) else it
                }
            }

            ty = TyUnion.union(ty, valueTy)
        }

        return ty
    }

    fun processMembers(context: SearchContext, processor: (ITy, LuaClassMember) -> Boolean, deep: Boolean = true): Boolean

    fun processMembers(context: SearchContext, processor: (ITy, LuaClassMember) -> Boolean): Boolean {
        return processMembers(context, processor, true)
    }

    fun processSignatures(context: SearchContext, processor: Processor<IFunSignature>): Boolean

    fun findSuperMember(name: String, searchContext: SearchContext): LuaClassMember? {
        // Travel up the hierarchy to find the lowest member of this type on a superclass (excluding this class)
        var member: LuaClassMember? = null
        Ty.processSuperClasses(this, searchContext) { superType ->
            val superClass = (if (superType is ITyGeneric) superType.base else superType) as? ITyClass
            member = superClass?.findMember(name, searchContext)
            member == null
        }
        return member
    }

    fun getMemberSubstitutor(context: SearchContext): ITySubstitutor? {
        return getSuperClass(context)?.getMemberSubstitutor(context)
    }
}

fun ITy.hasFlag(flag: Int): Boolean = flags and flag == flag

val ITy.isGlobal: Boolean
    get() = hasFlag(TyFlags.GLOBAL)

val ITy.isAnonymous: Boolean
    get() = hasFlag(TyFlags.ANONYMOUS)

private val ITy.worth: Float get() {
    var value = 10f
    when(this) {
        is ITyArray, is ITyGeneric -> value = 80f
        is ITyPrimitive -> value = 70f
        is ITyFunction -> value = 60f
        is ITyClass -> {
            value = when {
                this is TyTable -> 9f
                this.isAnonymous -> 2f
                this.isGlobal -> 5f
                else -> 90f
            }
        }
    }
    return value
}



val ITy.isColonCall get() = hasFlag(TyFlags.SELF_FUNCTION)

fun ITy.findCandidateSignatures(context: SearchContext, nArgs: Int): Collection<IFunSignature> {
    val candidates = mutableListOf<IFunSignature>()
    var lastCandidate: IFunSignature? = null
    processSignatures(context, Processor {
        val params = it.params
        if (params == null || params.size >= nArgs || it.varargTy != null) {
            candidates.add(it)
        }
        lastCandidate = it
        true
    })
    if (candidates.size == 0) {
        lastCandidate?.let { candidates.add(it) }
    }
    return candidates
}

fun ITy.findCandidateSignatures(context: SearchContext, call: LuaCallExpr): Collection<IFunSignature> {
    val n = call.argList.size
    // 是否是 inst:method() 被用为 inst.method(self) 形式
    val isInstanceMethodUsedAsStaticMethod = isColonCall && call.isMethodDotCall
    if (isInstanceMethodUsedAsStaticMethod)
        return findCandidateSignatures(context, n - 1)
    val isStaticMethodUsedAsInstanceMethod = !isColonCall && call.isMethodColonCall
    return findCandidateSignatures(context, if(isStaticMethodUsedAsInstanceMethod) n + 1 else n)
}

fun ITy.matchSignature(context: SearchContext, call: LuaCallExpr, processProblem: ProcessProblem? = null): SignatureMatchResult? {
    val args = call.argList
    val concreteArgTypes = mutableListOf<MatchFunctionSignatureInspection.ConcreteTypeInfo>()
    var multipleResultsVariadicTypeInfo: MatchFunctionSignatureInspection.ConcreteTypeInfo? = null

    args.forEachIndexed { index, luaExpr ->
        // TODO: unknown vs. any - should be unknown
        val ty = (
                if (index == args.lastIndex)
                    context.withMultipleResults { luaExpr.guessType(context) }
                else
                    context.withIndex(0) { luaExpr.guessType(context) }
                ) ?: Ty.UNKNOWN

        if (ty is TyMultipleResults && index == args.lastIndex) {
            val concreteResults = if (ty.variadic) {
                multipleResultsVariadicTypeInfo = MatchFunctionSignatureInspection.ConcreteTypeInfo(luaExpr, ty.list.last())
                ty.list.dropLast(1)
            } else {
                ty.list
            }

            concreteArgTypes.addAll(concreteResults.map { MatchFunctionSignatureInspection.ConcreteTypeInfo(luaExpr, it) })
        } else {
            concreteArgTypes.add(MatchFunctionSignatureInspection.ConcreteTypeInfo(luaExpr, TyMultipleResults.getResult(ty, 0)))
        }
    }

    val variadicArg: MatchFunctionSignatureInspection.ConcreteTypeInfo? = multipleResultsVariadicTypeInfo
    val problems = if (processProblem != null) mutableMapOf<IFunSignature, Collection<Problem>>() else null
    val candidates = findCandidateSignatures(context, call)

    candidates.forEach {
        var parameterCount = 0
        var candidateFailed = false
        val signatureProblems = if (problems != null) mutableListOf<Problem>() else null

        val substitutor = call.createSubstitutor(it, context)
        val signature = it.substitute(substitutor)

        signature.processParameters(call) { i, pi ->
            parameterCount = i + 1
            val typeInfo = concreteArgTypes.getOrNull(i) ?: variadicArg

            if (typeInfo == null || typeInfo == variadicArg) {
                var problemElement = call.lastChild.lastChild

                // Some PSI elements injected by IntelliJ (e.g. PsiErrorElementImpl) can be empty and thus cannot be targeted for our own errors.
                while (problemElement != null && problemElement.textLength == 0) {
                    problemElement = problemElement.prevSibling
                }

                problemElement = problemElement ?: call.lastChild

                candidateFailed = true
                signatureProblems?.add(Problem(null, problemElement, "Missing argument: ${pi.name}: ${pi.ty}"))

                if (typeInfo == null) {
                    return@processParameters true
                }
            }

            val paramType = pi.ty
            val argType = typeInfo.ty
            val argExpr = args.getOrNull(i) ?: args.last()
            val varianceFlags = if (argExpr is LuaTableExpr) TyVarianceFlags.WIDEN_TABLES else 0

            if (processProblem != null) {
                val contravariant = ProblemUtil.contravariantOf(paramType, argType, context, varianceFlags, null, argExpr) { _, element, message, highlightProblem ->
                    val contextualMessage = if (i >= args.size &&
                            (concreteArgTypes.size > args.size || (variadicArg != null && concreteArgTypes.size >= args.size))) {
                        "Result ${i + 1}, ${message.decapitalize()}"
                    } else {
                        message
                    }

                    signatureProblems?.add(Problem(null, element, contextualMessage, highlightProblem))
                }

                if (!contravariant) {
                    candidateFailed = true
                }
            } else if (!paramType.contravariantOf(argType, context, varianceFlags)) {
                candidateFailed = true
            }

            true
        }

        val varargParamTy = signature.varargTy

        if (parameterCount < concreteArgTypes.size) {
            if (varargParamTy != null) {
                for (i in parameterCount until args.size) {
                    val argType = concreteArgTypes.get(i).ty.let {
                        if (it is TyMultipleResults) it.list.first() else it
                    }
                    val argExpr = args.get(i)
                    val varianceFlags = if (argExpr is LuaTableExpr) TyVarianceFlags.WIDEN_TABLES else 0

                    if (processProblem != null) {
                        val contravariant = ProblemUtil.contravariantOf(varargParamTy, argType, context, varianceFlags, null, argExpr) { _, element, message, highlightProblem ->
                            signatureProblems?.add(Problem(null, element, message, highlightProblem))
                        }

                        if (!contravariant) {
                            candidateFailed = true
                        }
                    } else if (!varargParamTy.contravariantOf(argType, context, varianceFlags)) {
                        candidateFailed = true
                    }
                }
            } else {
                if (parameterCount < args.size) {
                    for (i in parameterCount until args.size) {
                        candidateFailed = true
                        signatureProblems?.add(Problem(null, args[i], "Too many arguments."))
                    }
                } else {
                    // Last argument is TyMultipleResults, just a weak warning.
                    val excess = parameterCount - args.size
                    val message = if (excess == 1) "1 result is an excess argument." else "${excess} results are excess arguments."
                    signatureProblems?.add(Problem(null, args.last(), message, ProblemHighlightType.WEAK_WARNING))
                }
            }
        } else if (varargParamTy != null && variadicArg != null) {
            if (processProblem != null) {
                val contravariant = ProblemUtil.contravariantOf(varargParamTy, variadicArg.ty, context, 0, null, variadicArg.param) { _, element, message, highlightProblem ->
                    val contextualMessage = "Variadic result, ${message.decapitalize()}"
                    signatureProblems?.add(Problem(null, element, contextualMessage, highlightProblem))
                }

                if (!contravariant) {
                    candidateFailed = true
                }
            } else if (!varargParamTy.contravariantOf(variadicArg.ty, context, 0)) {
                candidateFailed = true
            }
        }

        if (!candidateFailed) {
            if (processProblem != null) {
                signatureProblems?.forEach {
                    processProblem(it.targetElement, it.sourceElement, it.message, it.highlightType)
                }
            }

            return SignatureMatchResult(it, signature)
        }

        if (signatureProblems != null) {
            problems?.put(it, signatureProblems)
        }
    }

    if (processProblem != null) {
        val multipleCandidates = candidates.size > 1

        problems?.forEach { signature, signatureProblems ->
            signatureProblems.forEach {
                val problem = if (multipleCandidates) "${it.message}. In: ${signature.displayName}\n" else it.message
                processProblem(it.targetElement, it.sourceElement, problem, it.highlightType)
            }
        }
    }

    return null
}

fun ITy.matchSignature(context: SearchContext, call: LuaCallExpr, problemsHolder: ProblemsHolder): SignatureMatchResult? {
    return matchSignature(context, call) { _, sourceElement, message, highlightType ->
        problemsHolder.registerProblem(sourceElement, message, highlightType)
    }
}

abstract class Ty(override val kind: TyKind) : ITy {

    final override var flags: Int = 0

    override val displayName: String
        get() = TyRenderer.SIMPLE.render(this)

    // Lazy initialization because Ty.TRUE is itself a Ty that needs to be instantiated and refers to itself.
    override val booleanType: ITy by lazy { Ty.TRUE }

    fun addFlag(flag: Int) {
        flags = flags or flag
    }

    override fun accept(visitor: ITyVisitor) {
        visitor.visitTy(this)
    }

    override fun acceptChildren(visitor: ITyVisitor) {
    }

    override fun union(ty: ITy): ITy {
        return TyUnion.union(this, ty)
    }

    override fun not(ty: ITy): ITy {
        return TyUnion.not(this, ty)
    }

    override fun toString(): String {
        val list = mutableListOf<String>()
        TyUnion.each(this) { //尽量不使用Global
            if (!it.isAnonymous && !(it is ITyClass && it.isGlobal)) {
                if (it is ITyFunction || it is TyMultipleResults) {
                    list.add("(${it.displayName})")
                } else {
                    list.add(it.displayName)
                }
            }
        }
        if (list.isEmpty()) { //使用Global
            TyUnion.each(this) {
                if (!it.isAnonymous && (it is ITyClass && it.isGlobal)) {
                    if (it is ITyFunction || it is TyMultipleResults) {
                        list.add("(${it.displayName})")
                    } else {
                        list.add(it.displayName)
                    }
                }
            }
        }
        return list.joinToString(" | ")
    }

    override fun contravariantOf(other: ITy, context: SearchContext, flags: Int): Boolean {
        if (this == other
                || (other.kind == TyKind.Unknown && flags and TyVarianceFlags.STRICT_UNKNOWN == 0)
                || (other.kind == TyKind.Nil && flags and TyVarianceFlags.STRICT_NIL == 0 && !LuaSettings.instance.isNilStrict)) {
            return true
        }

        val resolvedOther = Ty.resolve(other, context)

        if (resolvedOther !== other) {
            return contravariantOf(resolvedOther, context, flags)
        }

        if (resolvedOther is TyUnion) {
            TyUnion.each(resolvedOther) {
                if (it !is TySnippet && !contravariantOf(it, context, flags)) {
                    return false
                }
            }

            return true
        }

        if (isShape(context)) {
            return processMembers(context, { _, classMember ->
                val indexTy = classMember.guessIndexType(context)

                val memberTy = if (indexTy != null) {
                    guessIndexerType(indexTy, context)
                } else {
                    classMember.name?.let { guessMemberType(it, context) }
                } ?: Ty.UNKNOWN

                val otherMemberTy = if (indexTy != null) {
                    other.guessIndexerType(indexTy, context, true)
                } else {
                    classMember.name?.let { other.guessMemberType(it, context) }
                }

                if (otherMemberTy == null) {
                    return@processMembers TyUnion.find(memberTy, TyNil::class.java) != null
                }

                memberTy.contravariantOf(otherMemberTy, context, flags)
            }, true)
        } else {
            val otherSuper = other.getSuperClass(context)
            return otherSuper != null && contravariantOf(otherSuper, context, flags)
        }
    }

    override fun covariantOf(other: ITy, context: SearchContext, flags: Int): Boolean {
        return other.contravariantOf(this, context, flags)
    }

    override fun getSuperClass(context: SearchContext): ITy? {
        return null
    }

    override fun getParams(context: SearchContext): Array<TyParameter>? {
        return null
    }

    override fun visitSuper(searchContext: SearchContext, processor: Processor<ITyClass>) {
        val superType = getSuperClass(searchContext) as? ITyClass ?: return
        if (processor.process(superType))
            superType.visitSuper(searchContext, processor)
    }

    override fun compareTo(other: ITy): Int {
        return other.worth.compareTo(worth)
    }

    override fun substitute(substitutor: ITySubstitutor): ITy {
        return substitutor.substitute(this)
    }

    override fun eachTopClass(fn: Processor<ITy>) {
        when (this) {
            is ITyClass -> fn.process(this)
            is ITyGeneric -> fn.process(this)
            is TyUnion -> {
                ContainerUtil.process(getChildTypes()) {
                    if (it is ITyClass && !fn.process(it))
                        return@process false
                    true
                }
            }
            is TyMultipleResults -> {
                list.firstOrNull()?.eachTopClass(fn)
            }
        }
    }

    override fun findMember(name: String, searchContext: SearchContext): LuaClassMember? {
        return null
    }

    override fun findIndexer(indexTy: ITy, searchContext: SearchContext, exact: Boolean): LuaClassMember? {
        return null
    }

    override fun processMembers(context: SearchContext, processor: (ITy, LuaClassMember) -> Boolean, deep: Boolean): Boolean {
        return true
    }

    override fun processSignatures(context: SearchContext, processor: Processor<IFunSignature>): Boolean {
        return true
    }

    companion object {

        // Defined first because each Ty implements booleanType, which returns a reference to these constants.
        val TRUE = TyPrimitiveLiteral.getTy(TyPrimitiveKind.Boolean, Constants.WORD_TRUE)
        val FALSE = TyPrimitiveLiteral.getTy(TyPrimitiveKind.Boolean, Constants.WORD_FALSE)

        val UNKNOWN = TyUnknown()
        val VOID = TyVoid()
        val NIL = TyNil()

        val BOOLEAN = TyPrimitive(TyPrimitiveKind.Boolean, Constants.WORD_BOOLEAN)
        val FUNCTION = TyPrimitive(TyPrimitiveKind.Function, Constants.WORD_FUNCTION)
        val NUMBER = TyPrimitive(TyPrimitiveKind.Number, Constants.WORD_NUMBER)
        val STRING = TyPrimitiveClass(TyPrimitiveKind.String, Constants.WORD_STRING)
        val TABLE = TyPrimitive(TyPrimitiveKind.Table, Constants.WORD_TABLE)

        private val serializerMap = mapOf<TyKind, ITySerializer>(
                TyKind.Array to TyArraySerializer,
                TyKind.Class to TyClassSerializer,
                TyKind.Alias to TyAliasSerializer,
                TyKind.Function to TyFunctionSerializer,
                TyKind.Generic to TyGenericSerializer,
                TyKind.GenericParam to TyGenericParamSerializer,
                TyKind.Primitive to TyPrimitiveSerializer,
                TyKind.PrimitiveLiteral to TyPrimitiveLiteralSerializer,
                TyKind.Snippet to TySnippetSerializer,
                TyKind.MultipleResults to TyMultipleResultsSerializer,
                TyKind.Union to TyUnionSerializer
        )

        private fun getKind(ordinal: Int): TyKind {
            return TyKind.values().firstOrNull { ordinal == it.ordinal } ?: TyKind.Unknown
        }

        fun getBuiltin(name: String): ITy? {
            return when (name) {
                Constants.WORD_NIL -> NIL
                Constants.WORD_VOID -> VOID
                Constants.WORD_ANY -> UNKNOWN
                Constants.WORD_BOOLEAN -> BOOLEAN
                Constants.WORD_TRUE -> TRUE
                Constants.WORD_FALSE -> FALSE
                Constants.WORD_STRING -> STRING
                Constants.WORD_NUMBER -> NUMBER
                Constants.WORD_TABLE -> TABLE
                Constants.WORD_FUNCTION -> FUNCTION
                else -> null
            }
        }

        fun create(name: String): ITy {
            return getBuiltin(name) ?: TyLazyClass(name)
        }

        fun create(classRef: LuaDocClassRef): ITy {
            val simpleType = Ty.create(classRef.classNameRef.id.text)
            return if (classRef.tyList.size > 0) {
                TyGeneric(classRef.tyList.map { it.getType() }.toTypedArray(), simpleType)
            } else simpleType
        }

        @ExperimentalContracts
        fun isInvalid(ty: ITy?): Boolean {
            contract {
                returns(true) implies (ty == null || ty is TyVoid)
                returns(false) implies (ty != null && ty !is TyVoid)
            }
            return ty == null || ty is TyVoid
        }

        private fun getSerializer(kind: TyKind): ITySerializer? {
            return serializerMap[kind]
        }

        fun serialize(ty: ITy, stream: StubOutputStream) {
            stream.writeByte(ty.kind.ordinal)
            stream.writeInt(ty.flags)
            val serializer = getSerializer(ty.kind)
            serializer?.serialize(ty, stream)
        }

        fun deserialize(stream: StubInputStream): ITy {
            val kind = getKind(stream.readByte().toInt())
            val flags = stream.readInt()
            return when (kind) {
                TyKind.Nil -> NIL
                TyKind.Void -> VOID
                else -> {
                    val serializer = getSerializer(kind)
                    serializer?.deserialize(flags, stream) ?: UNKNOWN
                }
            }
        }

        fun processSuperClasses(start: ITy, searchContext: SearchContext, processor: (ITy) -> Boolean): Boolean {
            val processedName = mutableSetOf<String>()
            var cur: ITy? = start
            while (cur != null) {
                val superType = cur.getSuperClass(searchContext)
                if (superType != null) {
                    if (!processedName.add(superType.displayName)) {
                        // todo: Infinite inheritance
                        return true
                    }
                    if (!processor(superType))
                        return false
                }
                cur = superType
            }
            return true
        }

        inline fun eachResolved(ty: ITy, context: SearchContext, fn: (ITy) -> Unit) {
            val visitedTys = mutableSetOf<ITy>()
            val memberTys = mutableListOf(ty)

            while (memberTys.isNotEmpty()) {
                val memberTy = memberTys.removeAt(memberTys.lastIndex)
                val base = if (memberTy is ITyGeneric) memberTy.base else memberTy

                if (visitedTys.add(base)) {
                    val referencedTy = if (base is ITyAlias) {
                        base
                    } else if (base is ITyClass) {
                        base.lazyInit(context)

                        if (!base.isAnonymous && !base.isGlobal) {
                            val genericTy = findGenericDef(base.className, context)?.type

                            if (genericTy != null) {
                                genericTy
                            } else {
                                val aliasTy = LuaShortNamesManager.getInstance(context.project).findAlias(base.className, context)

                                if (aliasTy != null) {
                                    aliasTy.type
                                } else {
                                    base
                                }
                            }
                        } else {
                            base
                        }
                    } else {
                        base
                    }

                    val resolvedMemberTy = if (referencedTy is ITyAlias) {
                        val params = referencedTy.params

                        if (params?.size ?: 0 > 0) {
                            val paramMap = mutableMapOf<String, ITy>()

                            if (memberTy is ITyGeneric) {
                                params?.forEachIndexed { index, baseParam ->
                                    if (index < params.size) {
                                        paramMap[baseParam.className] = memberTy.params[index]
                                    }
                                }
                            } else {
                                params?.forEachIndexed { index, baseParam ->
                                    paramMap[baseParam.className] = params[index]
                                }
                            }

                            referencedTy.ty.substitute(TyParameterSubstitutor(paramMap))
                        } else {
                            referencedTy.ty
                        }
                    } else {
                        referencedTy
                    }

                    if (resolvedMemberTy is TyUnion) {
                        memberTys.addAll(resolvedMemberTy.getChildTypes())
                    } else if (resolvedMemberTy !== base && resolvedMemberTy != null) {
                        memberTys.add(resolvedMemberTy)
                    } else {
                        fn(memberTy)
                    }
                }
            }
        }

        fun resolve(ty: ITy, context: SearchContext): ITy {
            if (ty !is TyAlias && ty !is TyClass && ty !is TyGeneric) {
                return ty
            }

            var resolvedTy: ITy = Ty.VOID

            eachResolved(ty, context) {
                resolvedTy = resolvedTy.union(it)
            }

            return resolvedTy
        }
    }
}

class TyUnknown : Ty(TyKind.Unknown) {

    override fun equals(other: Any?): Boolean {
        return other is TyUnknown
    }

    override fun equals(other: ITy, context: SearchContext): Boolean {
        if (other === Ty.UNKNOWN) {
            return true
        }

        return Ty.resolve(other, context) is TyUnknown
    }

    override fun hashCode(): Int {
        return Constants.WORD_ANY.hashCode()
    }

    override fun contravariantOf(other: ITy, context: SearchContext, flags: Int): Boolean {
        return true
    }

    override fun guessMemberType(name: String, searchContext: SearchContext): ITy? {
        return if (LuaSettings.instance.isUnknownIndexable) Ty.UNKNOWN else null
    }

    override fun guessIndexerType(indexTy: ITy, searchContext: SearchContext, exact: Boolean): ITy? {
        return if (LuaSettings.instance.isUnknownIndexable) Ty.UNKNOWN else null
    }
}

class TyNil : Ty(TyKind.Nil) {

    override val booleanType = Ty.FALSE

    override fun equals(other: ITy, context: SearchContext): Boolean {
        if (other === Ty.NIL) {
            return true
        }

        return Ty.resolve(other, context) is TyNil
    }

    override fun contravariantOf(other: ITy, context: SearchContext, flags: Int): Boolean {
        return other.kind == TyKind.Nil || super.contravariantOf(other, context, flags)
    }
}

class TyVoid : Ty(TyKind.Void) {

    override fun equals(other: ITy, context: SearchContext): Boolean {
        if (other === Ty.VOID) {
            return true
        }

        return Ty.resolve(other, context) is TyVoid
    }

    override fun contravariantOf(other: ITy, context: SearchContext, flags: Int): Boolean {
        return other.kind == TyKind.Void || super.contravariantOf(other, context, flags)
    }
}
