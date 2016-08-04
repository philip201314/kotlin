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

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageFeatureSettings
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.ModifierCheckerCore
import org.jetbrains.kotlin.resolve.TypeResolver
import org.jetbrains.kotlin.resolve.calls.callResolverUtil.ResolveArgumentsMode
import org.jetbrains.kotlin.resolve.calls.callUtil.isSafeCall
import org.jetbrains.kotlin.resolve.calls.context.BasicCallResolutionContext
import org.jetbrains.kotlin.resolve.calls.context.ContextDependency
import org.jetbrains.kotlin.resolve.calls.inference.EmptyConstraintSystem
import org.jetbrains.kotlin.resolve.calls.inference.NewConstraintSystem
import org.jetbrains.kotlin.resolve.calls.model.*
import org.jetbrains.kotlin.resolve.calls.model.LambdaArgument
import org.jetbrains.kotlin.resolve.calls.results.OverloadResolutionResultsImpl
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowValue
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowValueFactory
import org.jetbrains.kotlin.resolve.calls.tower.NewResolutionOldInference
import org.jetbrains.kotlin.resolve.descriptorUtil.builtIns
import org.jetbrains.kotlin.resolve.lazy.ForceResolveUtil
import org.jetbrains.kotlin.resolve.scopes.receivers.QualifierReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.Receiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.UnwrappedType
import org.jetbrains.kotlin.types.checker.intersectTypes
import org.jetbrains.kotlin.types.expressions.DoubleColonExpressionResolver
import org.jetbrains.kotlin.types.expressions.DoubleColonLHS
import org.jetbrains.kotlin.types.expressions.ExpressionTypingContext
import org.jetbrains.kotlin.utils.addToStdlib.check
import java.util.*

class NewCallResolver(
        val typeResolver: TypeResolver,
        val doubleColonExpressionResolver: DoubleColonExpressionResolver,
        val languageFeatureSettings: LanguageFeatureSettings
) {
    val useNewInference = languageFeatureSettings.supportsFeature(LanguageFeature.CommonConstraintSystem)

    fun <D : CallableDescriptor> runResolutionAndInference(
            context: BasicCallResolutionContext,
            name: Name,
            resolutionKind: NewResolutionOldInference.ResolutionKind<D>
    ) : OverloadResolutionResultsImpl<D> {

    }

    fun DataFlowInfo.collectedType(dataFlowValue: DataFlowValue): UnwrappedType? {
        val collectedTypes = getCollectedTypes(dataFlowValue).check { it.isNotEmpty() } ?: return null
        val types = ArrayList<UnwrappedType>(collectedTypes.size + 1)
        collectedTypes.mapTo(types) { it.unwrap() }
        types.add(dataFlowValue.type.unwrap())

        return intersectTypes(types)
    }

    fun toNewCall(context: BasicCallResolutionContext, oldCall: Call, name: Name): NewCall {
        val resolvedExplicitReceiver = resolveExplicitReceiver(context, oldCall.explicitReceiver, oldCall.isSafeCall())
        val resolvedTypeArguments = resolveTypeArguments(context, oldCall.typeArguments)

        val argumentsInParenthesis = if (oldCall.callType != Call.CallType.ARRAY_SET_METHOD) {
            oldCall.valueArguments
        }
        else {
            oldCall.valueArguments.dropLast(1)
        }

        val (resolvedArgumentsInParenthesis, dataFlowInfoAfterArgumentsInParenthesis) = resolveArgumentsInParenthesis(
                context, context.dataFlowInfoForArguments.resultInfo, argumentsInParenthesis)

        val externalLambdaArguments = oldCall.functionLiteralArguments
        val externalArgument = if (oldCall.callType == Call.CallType.ARRAY_SET_METHOD) {
            assert(externalLambdaArguments.isEmpty()) {
                "Unexpected lambda parameters for call $oldCall"
            }
            oldCall.valueArguments.last()
        }
        else {
            if (externalLambdaArguments.size > 2) {
                externalLambdaArguments.drop(1).forEach {
                    context.trace.report(Errors.MANY_LAMBDA_EXPRESSION_ARGUMENTS.on(it.getLambdaExpression()))
                }
            }

            externalLambdaArguments.firstOrNull()
        }

        val externalArgumentAndDFInfo = externalArgument?.let { resolveValueArgument(context, dataFlowInfoAfterArgumentsInParenthesis, it) }
        val resultDataFlowInfo = externalArgumentAndDFInfo?.first ?: dataFlowInfoAfterArgumentsInParenthesis

        return NewCallImpl(resolvedExplicitReceiver, name, resolvedTypeArguments, resolvedArgumentsInParenthesis,
                           externalArgumentAndDFInfo?.second, resultDataFlowInfo)
    }

    fun resolveExplicitReceiver(context: BasicCallResolutionContext, oldReceiver: Receiver?, isSafeCall: Boolean): ReceiverCallArgument? =
            when(oldReceiver) {
                null -> null
                is QualifierReceiver -> oldReceiver
                is ReceiverValue -> {
                    val nativeType = oldReceiver.type.unwrap()
                    val dataFlowValue = DataFlowValueFactory.createDataFlowValue(oldReceiver, context)
                    val collectedType = context.dataFlowInfo.collectedType(dataFlowValue)

                    if (dataFlowValue.isStable) {
                        ExplicitReceiver(collectedType ?: nativeType, null, isSafeCall)
                    }
                    else {
                        ExplicitReceiver(nativeType, collectedType, isSafeCall)
                    }
                }
                else -> error("Incorrect receiver: $oldReceiver")
            }

    fun resolveType(context: BasicCallResolutionContext, typeReference: KtTypeReference?): UnwrappedType? {
        if (typeReference == null) return null

        val type = typeResolver.resolveType(context.scope, typeReference, context.trace, checkBounds = true)
        ForceResolveUtil.forceResolveAllContents(type)
        return type.unwrap()
    }

    fun resolveTypeArguments(context: BasicCallResolutionContext, typeArguments: List<KtTypeProjection>): List<TypeArgument> =
            typeArguments.map { projection ->
                ModifierCheckerCore.check(projection, context.trace, null, languageFeatureSettings)

                if (projection.projectionKind != KtProjectionKind.NONE &&
                    !(projection.projectionKind == KtProjectionKind.STAR &&
                      languageFeatureSettings.supportsFeature(LanguageFeature.PlaceholderInTypeParameters))
                ) {
                    context.trace.report(Errors.PROJECTION_ON_NON_CLASS_TYPE_ARGUMENT.on(projection))
                }

                resolveType(context, projection.typeReference)?.let(::SimpleTypeArgumentImpl) ?: TypeArgumentPlaceholder
            }

    fun resolveArgumentsInParenthesis(
            context: BasicCallResolutionContext,
            dataFlowInfoForArguments: DataFlowInfo,
            arguments: List<ValueArgument>
    ): Pair<List<CallArgument>, DataFlowInfo> {
        var dataFlowInfo = dataFlowInfoForArguments

        val resolvedArguments = arguments.map {
            val argumentAndDFInfo = resolveValueArgument(context, dataFlowInfo, it)
            dataFlowInfo = argumentAndDFInfo.first
            argumentAndDFInfo.second
        }

        return resolvedArguments to dataFlowInfo
    }

    fun resolveValueArgument(
            outerCallContext: BasicCallResolutionContext,
            startDataFlowInfo: DataFlowInfo,
            valueArgument: ValueArgument
    ): Pair<DataFlowInfo, CallArgument> {
        val ktExpression = KtPsiUtil.deparenthesize(valueArgument.getArgumentExpression()) ?:
                                        return startDataFlowInfo to ParseErrorArgument(valueArgument, outerCallContext.scope.ownerDescriptor.builtIns)

        val argumentName = valueArgument.getArgumentName()?.asName

        val lambdaArgument = when (ktExpression) {
            is KtLambdaExpression ->
                LambdaArgumentIml(ktExpression, argumentName, resolveParametersTypes(outerCallContext, ktExpression.functionLiteral))
            is KtNamedFunction -> {
                val receiverType = resolveType(outerCallContext, ktExpression.receiverTypeReference)
                val parametersTypes = resolveParametersTypes(outerCallContext, ktExpression) ?: emptyArray()
                val returnType = resolveType(outerCallContext, ktExpression.typeReference)
                FunctionExpressionImpl(ktExpression, argumentName, receiverType, parametersTypes, returnType)
            }
            else -> null
        }
        if (lambdaArgument != null) {
            checkNoSpread(outerCallContext, valueArgument)
            return startDataFlowInfo to lambdaArgument
        }

        val context = outerCallContext.replaceContextDependency(ContextDependency.DEPENDENT)
                .replaceExpectedType(TypeUtils.NO_EXPECTED_TYPE).replaceDataFlowInfo(startDataFlowInfo)

        if (ktExpression is KtCallableReferenceExpression) {
            checkNoSpread(outerCallContext, valueArgument)

            // todo analyze left expression and get constraint system
            val (lhsResult, rightResults) = doubleColonExpressionResolver.resolveCallableReference(
                    ktExpression, ExpressionTypingContext.newContext(context), ResolveArgumentsMode.SHAPE_FUNCTION_ARGUMENTS)

            val newDataFlowInfo = (lhsResult as? DoubleColonLHS.Expression)?.dataFlowInfo ?: startDataFlowInfo

            // todo ChosenCallableReferenceDescriptor
            val argument = CallableReferenceArgumentImpl(ktExpression, argumentName, (lhsResult as? DoubleColonLHS.Type)?.type?.unwrap(),
                                                         EmptyConstraintSystem) // todo

            return newDataFlowInfo to argument
        }


        TODO() // todo expressions

    }

    private fun checkNoSpread(context: BasicCallResolutionContext, valueArgument: ValueArgument) {
        valueArgument.getSpreadElement()?.let {
            context.trace.report(Errors.SPREAD_OF_LAMBDA_OR_CALLABLE_REFERENCE.on(it))
        }
    }

    private fun resolveParametersTypes(context: BasicCallResolutionContext, ktFunction: KtFunction): Array<UnwrappedType?>? {
        val parameterList = ktFunction.valueParameterList ?: return null

        return Array(parameterList.parameters.size) {
            parameterList.parameters[it]?.typeReference?.let { resolveType(context, it) }
        }
    }

    class ExplicitReceiver(
            override val type: UnwrappedType,
            override val unstableType: UnwrappedType?,
            override val isSafeCall: Boolean
    ) : ExpressionArgument {
        override val isSpread: Boolean get() = false
        override val argumentName: Name? get() = null
    }


    class NewCallImpl(
            override val explicitReceiver: ReceiverCallArgument?,
            override val name: Name,
            override val typeArguments: List<TypeArgument>,
            override val argumentsInParenthesis: List<CallArgument>,
            override val externalArgument: CallArgument?,
            val resultDataFlowInfo: DataFlowInfo
    ) : NewCall

    class SimpleTypeArgumentImpl(override val type: UnwrappedType): SimpleTypeArgument

    class ParseErrorArgument(val valueArgument: ValueArgument, builtIns: KotlinBuiltIns): ExpressionArgument {
        override val type: UnwrappedType = builtIns.nothingType

        override val unstableType: UnwrappedType? get() = null
        override val isSafeCall: Boolean get() = false

        override val isSpread: Boolean get() = valueArgument.getSpreadElement() != null
        override val argumentName: Name? get() = valueArgument.getArgumentName()?.asName
    }

    class LambdaArgumentIml(
            val ktLambdaExpression: KtLambdaExpression,
            override val argumentName: Name?,
            override val parametersTypes: Array<UnwrappedType?>?
    ) : LambdaArgument

    class FunctionExpressionImpl(
            val ktFunction: KtNamedFunction,
            override val argumentName: Name?,
            override val receiverType: UnwrappedType?,
            override val parametersTypes: Array<UnwrappedType?>,
            override val returnType: UnwrappedType?
    ) : FunctionExpression

    class CallableReferenceArgumentImpl(
            val ktCallableReferenceExpression: KtCallableReferenceExpression,
            override val argumentName: Name?,
            override val lhsType: UnwrappedType?,
            override val constraintSystem: NewConstraintSystem
    ) : CallableReferenceArgument

}