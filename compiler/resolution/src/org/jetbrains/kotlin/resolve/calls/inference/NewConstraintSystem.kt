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
import org.jetbrains.kotlin.resolve.calls.model.NewCall
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeConstructor

class TypeVariable(val call: NewCall,
                   val originalTypeParameter: TypeParameterDescriptor) {
    val freshTypeConstructor: TypeConstructor = TODO()
}

interface Position

// system is immutable. For each "mutable" operation we will create new ConstraintSystem.
interface NewConstraintSystem {
    val hasContradiction: Boolean
    val typeVariables: List<TypeVariable>

    fun getLowerProperTypeForVariable(variable: TypeVariable): KotlinType?

    fun getUpperProperTypeForVariable(variable: TypeVariable): KotlinType?

    fun fixTypeVariable(variable: TypeVariable, value: KotlinType): NewConstraintSystem

    fun addConstraint(lowerType: KotlinType, upperType: KotlinType, position: Position): NewConstraintSystem

    fun concat(otherSystem: NewConstraintSystem): NewConstraintSystem
}

@Suppress("ABSTRACT_MEMBER_NOT_IMPLEMENTED")
object EmptyConstraintSystem : NewConstraintSystem {

}