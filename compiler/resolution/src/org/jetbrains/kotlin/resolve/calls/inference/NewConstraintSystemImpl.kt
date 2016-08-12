/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.resolve.calls.inference

import org.jetbrains.kotlin.types.FlexibleType
import org.jetbrains.kotlin.types.SimpleType
import org.jetbrains.kotlin.types.TypeConstructor
import org.jetbrains.kotlin.types.UnwrappedType
import org.jetbrains.kotlin.types.checker.NewKotlinTypeChecker
import java.util.*

enum class ConstraintKind {
    LOWER,
    UPPER,
    EQUALITY
}

enum class ResolveDirection {
    TO_SUBTYPE,
    TO_SUPERTYPE,
    UNKNOWN
}


class IncorporationPosition(val from: Position) : Position

class Constraint(
        val kind: ConstraintKind,
        val type: UnwrappedType, // flexible types here is allowed
        val position: Position,
        val typeHashCode: Int = type.hashCode()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false

        other as Constraint

        if (typeHashCode != other.typeHashCode) return false
        if (kind != other.kind) return false
        if (type != other.type) return false
        if (position != other.position) return false

        return true
    }

    override fun hashCode() = typeHashCode
}


class VariableWithConstrains(
        val typeVariable: TypeVariable,
        val index: Int
) {
    val constraints: List<Constraint> get() = mutableConstraints

    private val mutableConstraints = MyArrayList<Constraint>()

    // return constraint, if this constraint is new
    fun addConstraint(constraintKind: ConstraintKind, type: UnwrappedType, position: Position): Constraint? {
        val typeHashCode = type.hashCode()
        val previousConstraints = constraintsWithType(typeHashCode, type)
        if (previousConstraints.any { newConstraintIsUseless(it.kind, constraintKind) }) {
            return null
        }

        val constraint = Constraint(constraintKind, type, position, typeHashCode)
        mutableConstraints.add(constraint)
        return constraint
    }

    fun removeLastConstraints(shouldRemove: (Constraint) -> Boolean) {
        mutableConstraints.removeLast(shouldRemove)
    }

    private fun newConstraintIsUseless(oldKind: ConstraintKind, newKind: ConstraintKind) =
            when (oldKind) {
                ConstraintKind.EQUALITY -> true
                ConstraintKind.LOWER -> newKind == ConstraintKind.LOWER
                ConstraintKind.UPPER -> newKind == ConstraintKind.UPPER
            }

    private fun constraintsWithType(typeHashCode: Int, type: UnwrappedType) =
            constraints.filter { it.typeHashCode == typeHashCode && it.type == type }

    private class MyArrayList<E>(): ArrayList<E>() {
        fun removeLast(predicate: (E) -> Boolean) {
            val newSize = indexOfLast { !predicate(it) } + 1

            if (newSize != size) {
                removeRange(newSize, size)
            }
        }
    }
}

class InitialConstraint(
        val subType: UnwrappedType,
        val superType: UnwrappedType,
        val constraintKind: ConstraintKind,
        val position: Position
)

private const val ALLOWED_DEPTH_DELTA_FOR_INCORPORATION = 3

class ConstraintError(val lowerType: UnwrappedType, val upperType: UnwrappedType, val position: Position)

class ConstraintStorage {
    val typeVariables: MutableMap<TypeConstructor, VariableWithConstrains> = HashMap()
    val initialConstraints: MutableList<InitialConstraint> = ArrayList()
    var allowedTypeDepth: Int = 1 + ALLOWED_DEPTH_DELTA_FOR_INCORPORATION
    val errors: MutableList<ConstraintError> = ArrayList()

    private fun updateAllowedTypeDepth(initialType: UnwrappedType) {
        allowedTypeDepth = Math.max(allowedTypeDepth, initialType.typeDepth() + ALLOWED_DEPTH_DELTA_FOR_INCORPORATION)
    }

    fun registerVariable(variable: TypeVariable) {
        assert(!typeVariables.contains(variable.freshTypeConstructor)) {
            "Already registered: $variable"
        }

        typeVariables[variable.freshTypeConstructor] = VariableWithConstrains(variable, typeVariables.size)
    }

    fun addSubtypeConstraint(lowerType: UnwrappedType, upperType: UnwrappedType, position: Position) {
        initialConstraints.add(InitialConstraint(lowerType, upperType, ConstraintKind.LOWER, position))
        updateAllowedTypeDepth(lowerType)
        updateAllowedTypeDepth(upperType)
        newConstraint(lowerType, upperType, position)
    }

    fun addEqualityConstraint(a: UnwrappedType, b: UnwrappedType, position: Position) {
        initialConstraints.add(InitialConstraint(a, b, ConstraintKind.EQUALITY, position))
        updateAllowedTypeDepth(a)
        updateAllowedTypeDepth(b)
        newConstraint(a, b, position)
        newConstraint(b, a, position)
    }

    internal fun newConstraint(lowerType: UnwrappedType, upperType: UnwrappedType, position: Position) {
        val typeCheckerContext = TypeCheckerContext(position)
        with(NewKotlinTypeChecker) {
            if (!typeCheckerContext.isSubtypeOf(lowerType, upperType)) {
                errors.add(ConstraintError(lowerType, upperType, position))
            }
        }
    }

    fun incorporateNewConstraint(typeVariable: TypeVariable, constraint: Constraint, position: Position) {
        if (constraint.type.typeDepth() > allowedTypeDepth) return

        val newPosition = if (position is IncorporationPosition) position else IncorporationPosition(position)

        with(ConstraintIncorporator) {
            incorporate(typeVariable, constraint, newPosition)
        }
    }

    inner class TypeCheckerContext(val position: Position) : TypeCheckerContextForConstraintSystem() {

        override fun isMyTypeVariable(type: SimpleType): Boolean = typeVariables.containsKey(type.constructor)

        override fun addUpperConstraint(typeVariable: TypeConstructor, superType: UnwrappedType) =
                addConstraint(typeVariable, superType, ConstraintKind.UPPER)

        override fun addLowerConstraint(typeVariable: TypeConstructor, subType: UnwrappedType) =
                addConstraint(typeVariable, subType, ConstraintKind.LOWER)

        fun addConstraint(typeVariable: TypeConstructor, type: UnwrappedType, kind: ConstraintKind) {
            val variableWithConstrains = typeVariables[typeVariable] ?: error("Should by type variable: $typeVariable. ${typeVariables.keys}")
            val addedConstraint = variableWithConstrains.addConstraint(kind, type, position) ?: return

            incorporateNewConstraint(variableWithConstrains.typeVariable, addedConstraint, position)
        }
    }

}

fun UnwrappedType.typeDepth() =
    when (this) {
        is SimpleType -> typeDepth()
        is FlexibleType -> Math.max(lowerBound.typeDepth(), upperBound.typeDepth())
    }

fun SimpleType.typeDepth(): Int {
    val maxInArguments = arguments.asSequence().map {
        if (it.isStarProjection) 1 else it.type.unwrap().typeDepth()
    }.max() ?: 0

    return maxInArguments + 1
}