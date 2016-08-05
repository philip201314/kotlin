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

package org.jetbrains.kotlin.resolve.calls

import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.resolve.calls.TypeArgumentsToParametersMapper.TypeArgumentsMapping
import org.jetbrains.kotlin.resolve.calls.inference.ArgumentPosition
import org.jetbrains.kotlin.resolve.calls.inference.DeclaredUpperBound
import org.jetbrains.kotlin.resolve.calls.inference.ExplicitTypeParameter
import org.jetbrains.kotlin.resolve.calls.inference.SimpleTypeVariable
import org.jetbrains.kotlin.resolve.calls.model.*
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind.*
import org.jetbrains.kotlin.resolve.calls.tower.ResolutionCandidateApplicability
import org.jetbrains.kotlin.resolve.calls.tower.ResolutionCandidateApplicability.IMPOSSIBLE_TO_GENERATE
import org.jetbrains.kotlin.resolve.calls.tower.VisibilityError
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.types.IndexedParametersSubstitution
import org.jetbrains.kotlin.types.UnwrappedType
import org.jetbrains.kotlin.types.typeUtil.asTypeProjection

class NewCandidateResolver(
        val argumentsToParametersMapper: ArgumentsToParametersMapper,
        val typeArgumentsToParametersMapper: TypeArgumentsToParametersMapper,
        val callableReferenceResolver: CallableReferenceResolver
) {

    lateinit var newResolutionAndInference: NewResolutionAndInference

    private object ApplyResolutionDiagnostics : ResolutionPart {
        override fun <D : CallableDescriptor> SimpleResolutionCandidate<D>.process(resolver: NewCandidateResolver) = towerCandidate.diagnostics
    }

    private object CheckVisibility : ResolutionPart {
        override fun <D : CallableDescriptor> SimpleResolutionCandidate<D>.process(resolver: NewCandidateResolver): List<CallDiagnostic> {

            val invisibleMember = Visibilities.findInvisibleMember(
                    dispatchReceiver, candidateDescriptor, containingDescriptor) ?: return emptyList()

            return listOf(VisibilityError(invisibleMember))
        }
    }

    private object MapTypeArguments : ResolutionPart {
        override fun <D : CallableDescriptor> SimpleResolutionCandidate<D>.process(resolver: NewCandidateResolver): List<CallDiagnostic> {
            typeArgumentMappingByOriginal = resolver.typeArgumentsToParametersMapper.mapTypeArguments(call, candidateDescriptor.original)
            return typeArgumentMappingByOriginal.diagnostics
        }
    }

    private object MapArguments : ResolutionPart {
        override fun <D : CallableDescriptor> SimpleResolutionCandidate<D>.process(resolver: NewCandidateResolver): List<CallDiagnostic> {
            val mapping = resolver.argumentsToParametersMapper.mapArguments(call, candidateDescriptor.original)
            argumentMappingByOriginal = mapping.parameterToCallArgumentMap
            return mapping.diagnostics
        }
    }

    private object CreteDescriptorWithFreshTypeVariables : ResolutionPart {
        override fun <D : CallableDescriptor> SimpleResolutionCandidate<D>.process(resolver: NewCandidateResolver): List<CallDiagnostic> {
            if (candidateDescriptor.typeParameters.isEmpty()) {
                descriptorWithFreshTypes = candidateDescriptor
                return emptyList()
            }
            val typeParameters = candidateDescriptor.typeParameters

            val freshTypeVariables = typeParameters.map { SimpleTypeVariable(call, it) }
            val toFreshVariables = IndexedParametersSubstitution(typeParameters,
                                                                 freshTypeVariables.map { it.defaultType.asTypeProjection() }).buildSubstitutor()

            for (freshVariable in freshTypeVariables) {
                csBuilder.registerVariable(freshVariable)
            }

            for (index in typeParameters.indices) {
                val typeParameter = typeParameters[index]
                val freshVariable = freshTypeVariables[index]
                val position = DeclaredUpperBound(typeParameter)

                for (upperBound in typeParameter.upperBounds) {
                    csBuilder.addSubtypeConstraint(freshVariable.defaultType, upperBound.unwrap().substitute(toFreshVariables), position)
                }
            }

            // bad function -- error on declaration side
            if (csBuilder.hasContradiction) {
                descriptorWithFreshTypes = candidateDescriptor
                return csBuilder.diagnostics
            }

            // optimization
            if (typeArgumentMappingByOriginal == TypeArgumentsMapping.NoExplicitArguments) {
                descriptorWithFreshTypes = candidateDescriptor.safeSubstitute(toFreshVariables)
                csBuilder.simplify().let { assert(it.isEmpty) { "Substitutor should be empty: $it, call: $call" } }
                return emptyList()
            }

            // add explicit type parameter
            for (index in typeParameters.indices) {
                val typeParameter = typeParameters[index]
                val typeArgument = typeArgumentMappingByOriginal.getTypeArgument(typeParameter)

                if (typeArgument is SimpleTypeArgument) {
                    val freshVariable = freshTypeVariables[index]
                    csBuilder.addEqualityConstraint(freshVariable.defaultType, typeArgument.type, ExplicitTypeParameter(typeArgument))
                }
                else {
                    assert(typeArgument == TypeArgumentPlaceholder) {
                        "Unexpected typeArgument: $typeArgument, ${typeArgument.javaClass.canonicalName}"
                    }
                }
            }

            /**
             * Note: here we can fix also placeholders arguments.
             * Example:
             *  fun <X : Array<Y>, Y> foo()
             *
             *  foo<Array<String>, *>()
             */
            val toFixedTypeParameters = csBuilder.simplify()
            // todo optimize -- composite substitutions before run safeSubstitute
            descriptorWithFreshTypes = candidateDescriptor.safeSubstitute(toFreshVariables).safeSubstitute(toFixedTypeParameters)

            return csBuilder.diagnostics
        }
    }

    private object CheckExplicitReceiverKindConsistency : ResolutionPart {
        private fun SimpleResolutionCandidate<*>.hasError(): Nothing =
                error("Inconsistent call: $call. \n" +
                      "Candidate: $candidateDescriptor, explicitReceiverKind: $explicitReceiverKind.\n" +
                      "Explicit receiver: ${call.explicitReceiver}, dispatchReceiverForInvokeExtension: ${call.dispatchReceiverForInvokeExtension}")

        override fun <D : CallableDescriptor> SimpleResolutionCandidate<D>.process(resolver: NewCandidateResolver): List<CallDiagnostic> {
            when (explicitReceiverKind) {
                NO_EXPLICIT_RECEIVER -> if (call.explicitReceiver != null || call.dispatchReceiverForInvokeExtension != null) hasError()
                DISPATCH_RECEIVER, EXTENSION_RECEIVER ->    if (call.explicitReceiver == null || call.dispatchReceiverForInvokeExtension != null) hasError()
                BOTH_RECEIVERS -> if (call.explicitReceiver == null || call.dispatchReceiverForInvokeExtension == null) hasError()
            }
            return emptyList()
        }
    }

    private object CheckReceivers : ResolutionPart {
        fun SimpleResolutionCandidate<*>.checkReceiver(
                resolver: NewCandidateResolver,
                receiver: ReceiverValue?,
                receiverParameter: ReceiverParameterDescriptor?
        ): CallDiagnostic? {
            if ((receiver == null) != (receiverParameter == null)) {
                error("Inconsistency receiver state for call $call and candidate descriptor: $candidateDescriptor")
            }

            val receiverArgument = resolver.newResolutionAndInference.transformToCallReceiver(receiver ?: return null)
            val expectedType = receiverParameter!!.type.unwrap()

            return when (receiverArgument) {
                is ExpressionArgument -> checkExpressionArgument(receiverArgument, expectedType)
                is SubCall -> checkSubCallArgument(receiverArgument, expectedType)
                else -> incorrectReceiver(receiverArgument)
            }
        }

        override fun <D : CallableDescriptor> SimpleResolutionCandidate<D>.process(resolver: NewCandidateResolver) =
                listOfNotNull(checkReceiver(resolver, dispatchReceiver, descriptorWithFreshTypes.dispatchReceiverParameter),
                              checkReceiver(resolver, extensionReceiver, descriptorWithFreshTypes.extensionReceiverParameter))
    }


    companion object {
        // todo: hidden in resolution and other from NewResolutionOldInference.SimpleContext
        val resolutionSequence = listOf(
                ApplyResolutionDiagnostics,
                CheckVisibility,
                MapTypeArguments,
                MapArguments,
                CreteDescriptorWithFreshTypeVariables,
                CheckExplicitReceiverKindConsistency,
                CheckReceivers,
                CheckArgumentsResolutionPart
                )



        fun SimpleResolutionCandidate<*>.checkExpressionArgument(
                expressionArgument: ExpressionArgument,
                expectedType: UnwrappedType
        ): CallDiagnostic? {
            fun SimpleResolutionCandidate<*>.unstableSmartCast(
                    unstableType: UnwrappedType?, expectedType: UnwrappedType, position: ArgumentPosition
            ): CallDiagnostic? {
                if (unstableType != null) {
                    if (csBuilder.addIfIsCompatibleSubtypeConstraint(unstableType, expectedType, position)) {
                        return UnstableSmartCast(expressionArgument, unstableType)
                    }
                }
                return null
            }

            val expectedNullableType = expectedType.makeNullableAsSpecified(true)
            val position = ArgumentPosition(expressionArgument)
            if (expressionArgument.isSafeCall) {
                if (!csBuilder.addIfIsCompatibleSubtypeConstraint(expressionArgument.type, expectedNullableType, position)) {
                    unstableSmartCast(expressionArgument.unstableType, expectedNullableType, position)?.let { return it }
                }
                return null
            }

            if (!csBuilder.addIfIsCompatibleSubtypeConstraint(expressionArgument.type, expectedType, position)) {
                if (csBuilder.addIfIsCompatibleSubtypeConstraint(expressionArgument.type, expectedNullableType, position)) {
                    return UnsafeCallError(expressionArgument)
                }
                else {
                    unstableSmartCast(expressionArgument.unstableType, expectedType, position)?.let { return it }
                }
            }

            return null
        }

        fun SimpleResolutionCandidate<*>.checkSubCallArgument(
                subCall: SubCall,
                expectedType: UnwrappedType
        ): CallDiagnostic? {
            val resolvedCall = subCall.resolvedCall
            val expectedNullableType = expectedType.makeNullableAsSpecified(true)
            val position = ArgumentPosition(subCall)

            csBuilder.addSubsystem(resolvedCall.constraintSystem)

            if (subCall.isSafeCall) {
                csBuilder.addSubtypeConstraint(resolvedCall.currentReturnType, expectedNullableType, position)
                return null
            }

            if (!csBuilder.addIfIsCompatibleSubtypeConstraint(resolvedCall.currentReturnType, expectedType, position) &&
                csBuilder.addIfIsCompatibleSubtypeConstraint(resolvedCall.currentReturnType, expectedNullableType, position)
            ) {
                return UnsafeCallError(subCall)
            }

            return null
        }
    }
}

class UnstableSmartCast(val expressionArgument: ExpressionArgument, val targetType: UnwrappedType) :
        CallDiagnostic(ResolutionCandidateApplicability.MAY_THROW_RUNTIME_ERROR) {
    override fun report(reporter: DiagnosticReporter) = reporter.onCallArgument(expressionArgument, this)
}

class UnsafeCallError(val receiver: SimpleCallArgument) : CallDiagnostic(ResolutionCandidateApplicability.MAY_THROW_RUNTIME_ERROR) {
    override fun report(reporter: DiagnosticReporter) = reporter.onCallReceiver(receiver, this)
}

class ExpectedLambdaParametersCountMismatch(
        val lambdaArgument: LambdaArgument,
        val expected: Int,
        val actual: Int
) : CallDiagnostic(IMPOSSIBLE_TO_GENERATE) {
    override fun report(reporter: DiagnosticReporter) = reporter.onCallArgument(lambdaArgument, this)
}

class UnexpectedReceiver(val functionExpression: FunctionExpression) : CallDiagnostic(IMPOSSIBLE_TO_GENERATE) {
    override fun report(reporter: DiagnosticReporter) = reporter.onCallArgument(functionExpression, this)
}

class MissingReceiver(val functionExpression: FunctionExpression) : CallDiagnostic(IMPOSSIBLE_TO_GENERATE) {
    override fun report(reporter: DiagnosticReporter) = reporter.onCallArgument(functionExpression, this)
}

class ErrorCallableMapping(val functionReference: ResolvedFunctionReference) : CallDiagnostic(IMPOSSIBLE_TO_GENERATE) {
    override fun report(reporter: DiagnosticReporter) = reporter.onCallArgument(functionReference.argument, this)
}