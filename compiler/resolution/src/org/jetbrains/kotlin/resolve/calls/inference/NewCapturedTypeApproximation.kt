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

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.resolve.calls.inference.CapturedTypeConstructor
import org.jetbrains.kotlin.resolve.calls.inference.isCaptured
import org.jetbrains.kotlin.resolve.descriptorUtil.builtIns
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker
import org.jetbrains.kotlin.types.checker.NewCapturedType
import org.jetbrains.kotlin.types.checker.NewKotlinTypeChecker
import org.jetbrains.kotlin.types.checker.intersectTypes
import org.jetbrains.kotlin.types.typeUtil.builtIns
import org.jetbrains.kotlin.utils.SmartList
import org.jetbrains.kotlin.utils.addToStdlib.check
import java.util.*

data class ApproximationBounds<out T : UnwrappedType>(
        val lower: T,
        val upper: T
)

// todo: dynamic & raw type?
// null if there is no captured types
fun approximateCapturedTypes(
        type: UnwrappedType,
        predicate: (NewCapturedType) -> Boolean
): ApproximationBounds<UnwrappedType>? {
    // dynamic and RawType not have captured type inside => null will be returned

    return when (type) {
        is FlexibleType -> {
            val boundsForFlexibleLower = approximateCapturedTypes(type.lowerBound, predicate)
            val boundsForFlexibleUpper = approximateCapturedTypes(type.upperBound, predicate)

            if (boundsForFlexibleLower == null && boundsForFlexibleUpper == null) return null

            ApproximationBounds(
                    KotlinTypeFactory.flexibleType(boundsForFlexibleLower?.lower ?: type.lowerBound,
                                                   boundsForFlexibleUpper?.lower ?: type.upperBound),

                    KotlinTypeFactory.flexibleType(boundsForFlexibleLower?.upper ?: type.lowerBound,
                                                   boundsForFlexibleUpper?.upper ?: type.upperBound))
        }
        is SimpleType -> approximateCapturedTypes(type, predicate)
    }
}

fun approximateCapturedTypes(
        type: SimpleType,
        predicate: (NewCapturedType) -> Boolean
): ApproximationBounds<SimpleType>? {
    val builtIns = type.builtIns

    if (type is NewCapturedType) {
        if (!predicate(type)) return null

        val supertypes = type.constructor.supertypes
        val upper = if (supertypes.isNotEmpty()) {
            intersectTypes(supertypes).upperIfFlexible() // supertypes can be flexible
        }
        else {
            builtIns.nullableAnyType
        }
        // todo review approximation
        val lower = type.lowerType?.lowerIfFlexible() ?: builtIns.nothingType

        return if (!type.isMarkedNullable) {
            ApproximationBounds(lower, upper)
        }
        else {
            ApproximationBounds(lower.makeNullableAsSpecified(true), upper.makeNullableAsSpecified(true))
        }
    }

    val typeConstructor = type.constructor
    val arguments = type.arguments
    if (arguments.isEmpty() || arguments.size != typeConstructor.parameters.size) {
        return null
    }

    val approximatedArguments = Array(arguments.size) l@ {
        val typeProjection = arguments[it]
        if (typeProjection.isStarProjection) return@l null

        approximateCapturedTypes(typeProjection.type.unwrap(), predicate)
    }

    if (approximatedArguments.all { it == null }) return null

    val lowerArguments = SmartList<TypeProjection>()
    val upperArguments = SmartList<TypeProjection>()
    var lowerIsConsistent = true
    for ((index, typeProjection) in arguments.withIndex()) {
        val typeParameter = typeConstructor.parameters[index]
        val approximatedType = approximatedArguments[index]

        if (typeProjection.isStarProjection) {
            lowerArguments.add(typeProjection)
            upperArguments.add(typeProjection)
            continue
        }

        val effectiveVariance = NewKotlinTypeChecker.effectiveVariance(typeParameter.variance, typeProjection.projectionKind)
        if (effectiveVariance == null) { // actually it is error type
            lowerIsConsistent = false
            upperArguments.add(StarProjectionImpl(typeParameter))
            lowerArguments.add(StarProjectionImpl(typeParameter))
            continue
        }

        when (effectiveVariance) {
            Variance.INVARIANT -> {
                if (approximatedType == null) {
                    upperArguments.add(typeProjection)
                    lowerArguments.add(typeProjection)
                }
                else {
                    upperArguments.add(TypeProjectionImpl())
                }
            }
            Variance.IN_VARIANCE -> {

            }
            Variance.OUT_VARIANCE -> {

            }
        }
    }




    val lowerBoundArguments = ArrayList<TypeArgument>()
    val upperBoundArguments = ArrayList<TypeArgument>()
    for ((typeProjection, typeParameter) in arguments.zip(typeConstructor.parameters)) {
        val typeArgument = typeProjection.toTypeArgument(typeParameter)

        // Protection from infinite recursion caused by star projection
        if (typeProjection.isStarProjection) {
            lowerBoundArguments.add(typeArgument)
            upperBoundArguments.add(typeArgument)
        }
        else {
            val (lower, upper) = approximateProjection(typeArgument)
            lowerBoundArguments.add(lower)
            upperBoundArguments.add(upper)
        }
    }
    val lowerBoundIsTrivial = lowerBoundArguments.any { !it.isConsistent }
    return ApproximationBounds(
            if (lowerBoundIsTrivial) type.builtIns.nothingType else type.replaceTypeArguments(lowerBoundArguments),
            type.replaceTypeArguments(upperBoundArguments))
}

private fun KotlinType.replaceTypeArguments(newTypeArguments: List<TypeArgument>): KotlinType {
    assert(arguments.size == newTypeArguments.size) { "Incorrect type arguments $newTypeArguments" }
    return replace(newTypeArguments.map { it.toTypeProjection() })
}

private fun approximateProjection(typeArgument: TypeArgument): ApproximationBounds<TypeArgument> {
    val (inLower, inUpper) = approximateCapturedTypes(typeArgument.inProjection)
    val (outLower, outUpper) = approximateCapturedTypes(typeArgument.outProjection)
    return ApproximationBounds(
            lower = TypeArgument(typeArgument.typeParameter, inUpper, outLower),
            upper = TypeArgument(typeArgument.typeParameter, inLower, outUpper))
}


private class TypeArgument(
        val typeParameter: TypeParameterDescriptor,
        val inProjection: KotlinType,
        val outProjection: KotlinType
) {
    val isConsistent: Boolean
        get() = KotlinTypeChecker.DEFAULT.isSubtypeOf(inProjection, outProjection)
}

private fun TypeArgument.toTypeProjection(): TypeProjection {
    assert(isConsistent) { "Only consistent enhanced type propection can be converted to type projection" }
    fun removeProjectionIfRedundant(variance: Variance) = if (variance == typeParameter.variance) Variance.INVARIANT else variance
    return when {
        inProjection == outProjection -> TypeProjectionImpl(inProjection)
        KotlinBuiltIns.isNothing(inProjection) && typeParameter.variance != Variance.IN_VARIANCE ->
            TypeProjectionImpl(removeProjectionIfRedundant(Variance.OUT_VARIANCE), outProjection)
        KotlinBuiltIns.isNullableAny(outProjection) -> TypeProjectionImpl(removeProjectionIfRedundant(Variance.IN_VARIANCE), inProjection)
        else -> TypeProjectionImpl(removeProjectionIfRedundant(Variance.OUT_VARIANCE), outProjection)
    }
}

private fun TypeProjection.toTypeArgument(typeParameter: TypeParameterDescriptor) =
        when (TypeSubstitutor.combine(typeParameter.variance, this)) {
            Variance.INVARIANT -> TypeArgument(typeParameter, type, type)
            Variance.IN_VARIANCE -> TypeArgument(typeParameter, type, typeParameter.builtIns.nullableAnyType)
            Variance.OUT_VARIANCE -> TypeArgument(typeParameter, typeParameter.builtIns.nothingType, type)
        }