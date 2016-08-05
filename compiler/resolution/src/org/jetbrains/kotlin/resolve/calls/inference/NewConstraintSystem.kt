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

import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.resolve.calls.model.*
import org.jetbrains.kotlin.types.SimpleType
import org.jetbrains.kotlin.types.TypeConstructor
import org.jetbrains.kotlin.types.TypeSubstitutor
import org.jetbrains.kotlin.types.UnwrappedType

sealed class TypeVariable() {
    val freshTypeConstructor: TypeConstructor = TODO()
    val defaultType: SimpleType = TODO()
}

class SimpleTypeVariable(
        val call: NewCall,
        val originalTypeParameter: TypeParameterDescriptor
) : TypeVariable()

class LambdaTypeVariable(
        val outerCall: NewCall,
        val lambdaArgument: LambdaArgument,
        val kind: Kind
) : TypeVariable() {
    enum class Kind {
        RECEIVER,
        PARAMETER,
        RETURN_TYPE
    }
}

// system is immutable. For each "mutable" operation we will create new ConstraintSystem.
interface NewConstraintSystem {
    val hasContradiction: Boolean
    val typeVariables: List<TypeVariable>

    fun getLowerProperTypeForVariable(variable: TypeVariable): UnwrappedType?

    fun getUpperProperTypeForVariable(variable: TypeVariable): UnwrappedType?

    fun fixTypeVariable(variable: TypeVariable, value: UnwrappedType): NewConstraintSystem

}

@Suppress("ABSTRACT_MEMBER_NOT_IMPLEMENTED")
object EmptyConstraintSystem : NewConstraintSystem {

}


interface ConstraintSystemBuilder {
    val hasContradiction: Boolean

    // If hasContradiction then this list should contains some diagnostic about problem
    val diagnostics: List<CallDiagnostic>

    fun registerVariable(variable: TypeVariable)

    fun addSubtypeConstraint(lowerType: UnwrappedType, upperType: UnwrappedType, position: Position)
    fun addEqualityConstraint(a: UnwrappedType, b: UnwrappedType, position: Position)

    fun addSubsystem(otherSystem: NewConstraintSystem)

    fun addIfIsCompatibleSubtypeConstraint(lowerType: UnwrappedType, upperType: UnwrappedType, position: Position): Boolean

    /**
     * This function remove variables for which we know exact type.
     * @return substitutor from typeVariable to result
     */
    fun simplify(): TypeSubstitutor

    fun build(): NewConstraintSystem // return immutable copy of constraint system
}

fun createNewConstraintSystemBuilder(): ConstraintSystemBuilder = TODO()


interface Position

class ExplicitTypeParameter(val typeArgument: SimpleTypeArgument) : Position
class DeclaredUpperBound(val typeParameterDescriptor: TypeParameterDescriptor) : Position
class ArgumentPosition(val argument: CallArgument) : Position