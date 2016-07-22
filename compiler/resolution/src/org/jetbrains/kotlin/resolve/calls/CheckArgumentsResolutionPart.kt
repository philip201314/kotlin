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

import org.jetbrains.kotlin.builtins.getValueParameterTypesFromFunctionType
import org.jetbrains.kotlin.builtins.isExtensionFunctionType
import org.jetbrains.kotlin.builtins.isFunctionType
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.resolve.calls.NewCandidateResolver.Companion.checkExpressionArgument
import org.jetbrains.kotlin.resolve.calls.NewCandidateResolver.Companion.checkSubCallArgument
import org.jetbrains.kotlin.resolve.calls.inference.ArgumentPosition
import org.jetbrains.kotlin.resolve.calls.inference.LambdaTypeVariable
import org.jetbrains.kotlin.resolve.calls.inference.TypeVariable
import org.jetbrains.kotlin.resolve.calls.model.*
import org.jetbrains.kotlin.resolve.calls.tower.isSuccess
import org.jetbrains.kotlin.types.UnwrappedType
import org.jetbrains.kotlin.types.typeUtil.supertypes
import org.jetbrains.kotlin.utils.SmartList
import org.jetbrains.kotlin.utils.addIfNotNull

object CheckArgumentsResolutionPart : ResolutionPart {
    override fun <D : CallableDescriptor> SimpleResolutionCandidate<D>.process(resolver: NewCandidateResolver): List<CallDiagnostic> {
        val diagnostics = SmartList<CallDiagnostic>()
        for (parameterDescriptor in descriptorWithFreshTypes.valueParameters) {
            // error was reported in ArgumentsToParametersMapper
            val resolvedCallArgument = argumentMappingByOriginal[parameterDescriptor.original] ?: continue
            for (argument in resolvedCallArgument.arguments) {

                val expectedType =
                        if (argument.isSpread) {
                            // error was reported in ArgumentsToParametersMapper
                            parameterDescriptor.varargElementType?.unwrap() ?: continue
                        }
                        else {
                            parameterDescriptor.type.unwrap()
                        }

                val diagnostic =
                        when (argument) {
                            is ExpressionArgument -> checkExpressionArgument(argument, expectedType)
                            is SubCall -> checkSubCallArgument(argument, expectedType)
                            is LambdaArgument -> processLambdaArgument(argument, expectedType)
                            is CallableReferenceArgument -> processCallableReferenceArgument(resolver, argument, expectedType)
                            else -> error("Incorrect argument type: $argument, ${argument.javaClass.canonicalName}.")
                        }

                if (diagnostic != null && !diagnostic.candidateApplicability.isSuccess) break

                diagnostics.addIfNotNull(diagnostic)
            }
        }
        return diagnostics
    }

    inline fun computeParameterTypes(
            argument: LambdaArgument,
            expectedType: UnwrappedType,
            createFreshType: () -> UnwrappedType
    ): List<UnwrappedType> {
        argument.parametersTypes?.map { it ?: createFreshType() } ?.let { return it }

        if (expectedType.isFunctionType) {
            return getValueParameterTypesFromFunctionType(expectedType).map { createFreshType() }
        }

        // if expected type is non-functional type and there is no declared parameters
        return emptyList()
    }

    inline fun computeReceiver(
            argument: LambdaArgument,
            expectedType: UnwrappedType,
            createFreshType: () -> UnwrappedType
    ) : UnwrappedType? {
        if (argument is FunctionExpression) return argument.receiverType

        if (expectedType.isExtensionFunctionType) return createFreshType()

        return null
    }

    inline fun computeReturnType(
            argument: LambdaArgument,
            createFreshType: () -> UnwrappedType
    ) : UnwrappedType {
        if (argument is FunctionExpression) return argument.receiverType ?: createFreshType()

        return createFreshType()
    }

    fun SimpleResolutionCandidate<*>.processLambdaArgument(
            argument: LambdaArgument,
            expectedType: UnwrappedType
    ): CallDiagnostic? {
        // initial checks
        if (expectedType.isFunctionType) {
            val expectedParameterCount = getValueParameterTypesFromFunctionType(expectedType).size

            argument.parametersTypes?.size?.let {
                if (expectedParameterCount != it) return ExpectedLambdaParametersCountMismatch(argument, expectedParameterCount, it)
            }

            if (argument is FunctionExpression) {
                if (argument.receiverType != null && !expectedType.isExtensionFunctionType) return UnexpectedReceiver(argument)
                if (argument.receiverType == null && expectedType.isExtensionFunctionType) return MissingReceiver(argument)
            }
        }

        val freshVariables = SmartList<TypeVariable>()
        val receiver = computeReceiver(argument, expectedType) {
            LambdaTypeVariable(call, argument, LambdaTypeVariable.Kind.RECEIVER).apply { freshVariables.add(this) }.defaultType
        }

        val parameters = computeParameterTypes(argument, expectedType) {
            LambdaTypeVariable(call, argument, LambdaTypeVariable.Kind.PARAMETER).apply { freshVariables.add(this) }.defaultType
        }

        val returnType = computeReturnType(argument) {
            LambdaTypeVariable(call, argument, LambdaTypeVariable.Kind.RETURN_TYPE).apply { freshVariables.add(this) }.defaultType
        }

        val resolvedArgument = ResolvedLambdaArgument(call, argument, freshVariables, receiver, parameters, returnType)
        // todo register resolvedArgument

        freshVariables.forEach(csBuilder::registerVariable)
        csBuilder.addSubtypeConstraint(resolvedArgument.type, expectedType, ArgumentPosition(argument))

        return null
    }

    fun SimpleResolutionCandidate<*>.processCallableReferenceArgument(
            resolver: NewCandidateResolver,
            argument: CallableReferenceArgument,
            expectedType: UnwrappedType
    ): CallDiagnostic? {
        val position = ArgumentPosition(argument)

        if (argument !is ChosenCallableReferenceDescriptor<*>) {
            val lhsType = argument.lhsType
            if (lhsType != null) {
                // todo: case with two receivers
                val expectedReceiverType = expectedType.supertypes().firstOrNull { it.isFunctionType }?.arguments?.first()?.type?.unwrap()
                if (expectedReceiverType != null) {
                    // (lhsType) -> .. <: (expectedReceiverType) -> ... => expectedReceiverType <: lhsType
                    csBuilder.addSubtypeConstraint(expectedReceiverType, lhsType, position)
                }
            }

            return null
        }

        val descriptor = argument.candidate.descriptor
        when (descriptor) {
            is PropertyDescriptor -> {
                @Suppress("UNCHECKED_CAST")
                val functionReference = argument as ChosenCallableReferenceDescriptor<FunctionDescriptor>

                // todo store resolved
                val resolvedFunctionReference = resolver.callableReferenceResolver.resolveFunctionReference(
                        functionReference, call, expectedType)

                csBuilder.addSubtypeConstraint(resolvedFunctionReference.reflectionType, expectedType, position)
                return resolvedFunctionReference.argumentsMapping?.diagnostics?.let {
                    ErrorCallableMapping(resolvedFunctionReference)
                }
            }
            is FunctionDescriptor -> {
                @Suppress("UNCHECKED_CAST")
                val propertyReference = argument as ChosenCallableReferenceDescriptor<PropertyDescriptor>

                // todo store resolved
                val resolvedPropertyReference = resolver.callableReferenceResolver.resolvePropertyReference(
                        propertyReference, call, containingDescriptor)
                csBuilder.addSubtypeConstraint(resolvedPropertyReference.reflectionType, expectedType, position)
            }
            else -> throw UnsupportedOperationException("Callable reference resolved to an unsupported descriptor: $descriptor")
        }
        return null
    }
}
