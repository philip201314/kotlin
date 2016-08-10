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
import org.jetbrains.kotlin.types.UnwrappedType
import java.util.*

enum class ConstraintKind {
    SUBTYPE,
    SUPERTYPE,
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
        val type: UnwrappedType, // may be SimpleType?
        val position: Position
) {

}

class VariableWithConstrains(
        val typeVariable: TypeVariable,
        val index: Int
) {
    val constraints: MutableList<Constraint> = ArrayList()
}

class InitialConstraint(
        val subType: UnwrappedType,
        val superType: UnwrappedType,
        val constraintKind: ConstraintKind,
        val position: Position
)

private const val ALLOWED_DEPTH_DELTA_FOR_INCORPORATION = 3

class ConstraintStorage {
    val typeVariables: MutableMap<TypeVariable, VariableWithConstrains> = HashMap()
    val initialConstraints: MutableList<InitialConstraint> = ArrayList()
    var allowedTypeDepth: Int = 1 + ALLOWED_DEPTH_DELTA_FOR_INCORPORATION

    private fun updateAllowedTypeDepth(initialType: UnwrappedType) {
        allowedTypeDepth = Math.max(allowedTypeDepth, initialType.typeDepth() + ALLOWED_DEPTH_DELTA_FOR_INCORPORATION)
    }

    fun registerVariable(variable: TypeVariable) {
        assert(!typeVariables.contains(variable)) {
            "Already registered: $variable"
        }

        typeVariables[variable] = VariableWithConstrains(variable, typeVariables.size)
    }

    fun addSubtypeConstraint(lowerType: UnwrappedType, upperType: UnwrappedType, position: Position) {
        initialConstraints.add(InitialConstraint(lowerType, upperType, ConstraintKind.SUBTYPE, position))
        updateAllowedTypeDepth(lowerType)
        updateAllowedTypeDepth(upperType)

    }

    fun addEqualityConstraint(a: UnwrappedType, b: UnwrappedType, position: Position) {
        initialConstraints.add(InitialConstraint(a, b, ConstraintKind.EQUALITY, position))
        updateAllowedTypeDepth(a)
        updateAllowedTypeDepth(b)

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