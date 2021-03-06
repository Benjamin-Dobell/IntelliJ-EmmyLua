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

import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import com.tang.intellij.lua.ext.recursionGuard
import com.tang.intellij.lua.psi.LuaClassMember
import com.tang.intellij.lua.search.SearchContext
import java.util.*
import kotlin.collections.ArrayList
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

private val displayNameComparator: Comparator<ITy> = Comparator { a, b -> a.displayName.compareTo(b.displayName) }

class TyUnion : Ty {
    private val childSet: LinkedHashSet<ITy>

    private constructor(childSet: LinkedHashSet<ITy>) : super(TyKind.Union) {
        if (childSet.size < 2) {
            throw IllegalArgumentException("Unions must contain two or more types. ${childSet.size} were provided.")
        }

        this.childSet = childSet
    }

    constructor(childTys: Collection<ITy>) : this(linkedSetOf<ITy>().apply { addAll(childTys.sortedWith(displayNameComparator)) })

    fun getChildTypes() = childSet

    val size:Int
        get() = childSet.size

    override val booleanType: ITy
        get() {
            var resolvedType: ITy? = null
            childSet.forEach {
                when (it.booleanType) {
                    Primitives.TRUE -> {
                        if (resolvedType == Primitives.FALSE) return Primitives.BOOLEAN
                        resolvedType = Primitives.TRUE
                    }
                    Primitives.FALSE -> {
                        if (resolvedType == Primitives.TRUE) return Primitives.BOOLEAN
                        resolvedType = Primitives.FALSE
                    }
                    else -> return Primitives.BOOLEAN
                }
            }
            return resolvedType ?: Primitives.BOOLEAN
        }

    override fun union(ty: ITy, context: SearchContext): ITy {
        if (ty is TyVoid) {
            return this
        } else if (ty.isUnknown && childSet.find { it is TyMultipleResults } == null) {
            return Primitives.UNKNOWN
        }

        val unionTys = mutableListOf<ITy>()
        unionTys.addAll(childSet)

        if (ty is TyUnion) {
            unionTys.addAll(ty.childSet)
        } else {
            unionTys.add(ty)
        }

        return TyUnion.union(unionTys, context)
    }

    override fun not(ty: ITy, context: SearchContext): ITy {
        val resultantChildTys = ArrayList<ITy>(childSet.size)
        var altered = false

        childSet.forEach { childTy ->
            val resultantChildTy = childTy.not(ty, context)

            if (resultantChildTy !== childTy) {
                altered = true

                if (resultantChildTy is TyUnion) {
                    resultantChildTys.addAll(resultantChildTy.childSet)
                } else {
                    resultantChildTys.add(resultantChildTy)
                }
            } else {
                resultantChildTys.add(childTy)
            }
        }

        if (!altered) {
            return this
        }

        return TyUnion.union(resultantChildTys, context)
    }

    override fun contravariantOf(other: ITy, context: SearchContext, flags: Int): Boolean {
        return super.contravariantOf(other, context, flags)
                || childSet.any { type -> type.contravariantOf(other, context, flags) }
    }

    override fun substitute(substitutor: ITySubstitutor): ITy {
        var substituted = false
        val substitutedChildren = childSet.map {
            val substitutedChild = it.substitute(substitutor)

            if (substitutedChild !== it) {
                substituted = true
            }

            substitutedChild
        }

        return if (substituted) {
            TyUnion.union(substitutedChildren, substitutor.searchContext)
        } else this
    }

    override fun processMember(context: SearchContext, name: String, deep: Boolean, process: (ITy, LuaClassMember) -> Boolean): Boolean {
        childSet.forEach {
            if (!it.processMember(context, name, deep, process)) {
                return false
            }
        }

        return true
    }

    override fun processIndexer(context: SearchContext, indexTy: ITy, exact: Boolean, deep: Boolean, process: (ITy, LuaClassMember) -> Boolean): Boolean {
        childSet.forEach {
            if (!it.processIndexer(context, indexTy, exact, deep, process)) {
                return false
            }
        }

        return true
    }

    override fun guessMemberType(name: String, searchContext: SearchContext): ITy? {
        return childSet.reduce<ITy?, ITy?> { ty, childTy ->
            TyUnion.union(ty, childTy?.guessMemberType(name, searchContext), searchContext)
        }
    }

    override fun guessIndexerType(indexTy: ITy, searchContext: SearchContext, exact: Boolean): ITy? {
        return childSet.reduce<ITy?, ITy?> { ty, childTy ->
            TyUnion.union(ty, childTy?.guessIndexerType(indexTy, searchContext, exact), searchContext)
        }
    }

    override fun accept(visitor: ITyVisitor) {
        visitor.visitUnion(this)
    }

    override fun acceptChildren(visitor: ITyVisitor) {
        childSet.forEach { it.accept(visitor) }
    }

    override fun equals(other: ITy, context: SearchContext): Boolean {
        val resolvedTy = childSet.reduce { resolved, ty ->
            resolved.union(Ty.resolve(ty, context), context)
        }

        val otherResolvedTy = Ty.resolve(other, context).let {
            if (it is TyUnion) {
                it.childSet.reduce { resolved, ty ->
                    resolved.union(Ty.resolve(ty, context), context)
                }
            } else it
        }

        val resolvedSet = if (resolvedTy is TyUnion) {
            resolvedTy.childSet
        } else setOf(resolvedTy)

        val resolvedOtherSet = if (otherResolvedTy is TyUnion) {
            otherResolvedTy.childSet
        } else setOf(otherResolvedTy)

        if (resolvedSet.size == resolvedOtherSet.size) {
            val allMembersMatch = resolvedSet.all { ty ->
                resolvedOtherSet.contains(ty) || resolvedOtherSet.any { otherTy ->
                    ty.equals(otherTy, context)
                }
            }

            if (allMembersMatch) {
                return true
            }
        }

        return false
    }

    override fun equals(other: Any?): Boolean {
        return other is TyUnion && other.hashCode() == hashCode()
    }

    override fun hashCode(): Int {
        var code = 0
        childSet.forEach { code = code * 31 + it.hashCode() }
        return code
    }

    companion object {
        fun isUnion(ty: ITy, context: SearchContext): Boolean {
            if (ty is TyUnion) {
                return true
            }

            if (ty is TyAlias || ty is TyClass) {
                val resolvedTy = Ty.resolve(ty, context)
                return resolvedTy is TyUnion
            }

            return false
        }

        fun <T : ITy> find(ty: ITy, clazz: Class<T>): T? {
            if (clazz.isInstance(ty))
                return clazz.cast(ty)
            each(ty) {
                if (clazz.isInstance(it)) {
                    return clazz.cast(it)
                }
            }
            return null
        }

        inline fun all(ty: ITy, fn: (ITy) -> Boolean): Boolean {
            if (ty is TyUnion) {
                return ty.getChildTypes().all(fn)
            }

            return fn(ty)
        }

        inline fun any(ty: ITy, fn: (ITy) -> Boolean): Boolean {
            if (ty is TyUnion) {
                return ty.getChildTypes().any(fn)
            }

            return fn(ty)
        }

        inline fun each(ty: ITy, fn: (ITy) -> Unit) {
            if (ty is TyUnion) {
                for (child in ty.getChildTypes()) {
                    fn(child)
                }
            } else {
                fn(ty)
            }
        }

        @ExperimentalContracts
        @JvmName("nullableUnion")
        fun union(t1: ITy?, t2: ITy?, context: SearchContext): ITy? {
            contract {
                returns(null) implies (t1 == null && t2 == null)
                returnsNotNull() implies (t1 != null || t2 != null)
            }
            return when {
                t1 === null -> t2
                t2 === null -> t1
                else -> union(t1, t2, context)
            }
        }

        fun union(t1: ITy, t2: ITy, context: SearchContext): ITy {
            return when {
                t1 === t2 || t1 == t2 -> t1
                t1 is TyUnion -> t1.union(t2, context)
                t2 is TyUnion -> t2.union(t1, context)
                t1 is TyMultipleResults || t2 is TyMultipleResults -> union(listOf(t1, t2), context)
                t1 is TyVoid -> t2
                t2 is TyVoid -> t1
                else -> union(listOf(t1, t2), context)
            }
        }

        fun union(tys: Iterable<ITy>, context: SearchContext): ITy {
            val expandedTys = mutableListOf<ITy>()

            tys.forEach {
                if (it is TyUnion) {
                    expandedTys.addAll(it.childSet)
                } else if (it != Primitives.VOID) {
                    expandedTys.add(it)
                }
            }

            if (expandedTys.size == 0) {
                return Primitives.VOID
            }

            val childSet = sortedSetOf(displayNameComparator)

            expandedTys.forEach {
                val varianceFlags = TyVarianceFlags.STRICT_NIL or TyVarianceFlags.STRICT_UNKNOWN or TyVarianceFlags.NON_STRUCTURAL
                val covariant = childSet.contains(it) || childSet.find { childTy ->
                    recursionGuard(childTy, {
                        childTy.contravariantOf(it, context, varianceFlags)
                    }) ?: false
                } != null

                if (!covariant) {
                    if (it == Primitives.TRUE) {
                        if (childSet.remove(Primitives.FALSE)) {
                            childSet.add(Primitives.BOOLEAN)
                        } else {
                            childSet.add(Primitives.TRUE)
                        }
                    } else if (it == Primitives.FALSE) {
                        if (childSet.remove(Primitives.TRUE)) {
                            childSet.add(Primitives.BOOLEAN)
                        } else {
                            childSet.add(Primitives.FALSE)
                        }
                    } else {
                        childSet.removeIf { childTy ->
                            recursionGuard(childTy, {
                                it.contravariantOf(childTy, context, varianceFlags)
                            }) ?: false
                        }

                        childSet.add(it)
                    }
                }
            }

            return if (childSet.size == 1) {
                childSet.first()
            } else {
                TyUnion(childSet)
            }
        }

        fun getPerfectClass(ty: ITy): ITyClass? {
            var anonymous: ITyClass? = null
            var global: ITyClass? = null
            each(ty) {
                if (it is ITyClass) {
                    when {
                        it.isAnonymous -> anonymous = it
                        it.isGlobal -> global = it
                        else -> return it
                    }
                }
            }
            return global ?: anonymous
        }
    }
}

object TyUnionSerializer : TySerializer<TyUnion>() {
    override fun serializeTy(ty: TyUnion, stream: StubOutputStream) {
        stream.writeInt(ty.size)
        TyUnion.each(ty) { Ty.serialize(it, stream) }
    }

    override fun deserializeTy(flags: Int, stream: StubInputStream): TyUnion {
        val size = stream.readInt()
        val tys = ArrayList<ITy>(size)

        for (i in 0 until size) {
            tys.add(Ty.deserialize(stream))
        }

        return TyUnion(tys)
    }
}
