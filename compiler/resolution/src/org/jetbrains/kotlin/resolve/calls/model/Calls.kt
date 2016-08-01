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

package org.jetbrains.kotlin.resolve.calls.model

import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.calls.ArgumentsToParametersMapper
import org.jetbrains.kotlin.resolve.calls.BaseResolvedCall
import org.jetbrains.kotlin.resolve.calls.MockReceiverForCallableReference
import org.jetbrains.kotlin.resolve.calls.inference.NewConstraintSystem
import org.jetbrains.kotlin.resolve.calls.inference.TypeVariable
import org.jetbrains.kotlin.resolve.calls.tower.CandidateWithBoundDispatchReceiver
import org.jetbrains.kotlin.resolve.calls.util.createFunctionType
import org.jetbrains.kotlin.resolve.scopes.receivers.QualifierReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.types.SimpleType
import org.jetbrains.kotlin.types.TypeSubstitutor
import org.jetbrains.kotlin.types.UnwrappedType
import org.jetbrains.kotlin.types.typeUtil.builtIns
import org.jetbrains.kotlin.utils.addToStdlib.check


/*sealed*/ interface CallArgument {
    val isSpread: Boolean
    val argumentName: Name?
}

/*sealed*/ interface SimpleCallArgument : CallArgument {
    val isSafeCall: Boolean
}

class FakeArgumentForCallableReference(
        val callableReference: ChosenCallableReferenceDescriptor<FunctionDescriptor>,
        val index: Int
) : CallArgument {
    override val isSpread: Boolean get() = false
    override val argumentName: Name? get() = null
}

interface ExpressionArgument : SimpleCallArgument {
    val type: UnwrappedType // with all smart casts if stable

    val unstableType: UnwrappedType? // if expression is not stable and has smart casts, then we create this type
}

// will be used for implicit this and may be for variable + invoke
interface ImplicitExpressionArgument : ExpressionArgument {
    override val isSafeCall: Boolean get() = false
    override val isSpread: Boolean get() = false
    override val argumentName: Name? get() = null
}

interface SubCall : SimpleCallArgument {
    val resolvedCall: BaseResolvedCall.OnlyResolvedCall<*>
}

interface LambdaArgument : CallArgument {
    override val isSpread: Boolean
        get() = false // todo error on call -- function type is not subtype of Array<out ...>

    /**
     * parametersTypes == null means, that there is no declared arguments
     * null inside array means that this type is not declared explicitly
     */
    val parametersTypes: Array<UnwrappedType?>?
}

interface FunctionExpression : LambdaArgument {
    override val parametersTypes: Array<UnwrappedType?>

    // null means that there function can not have receiver
    val receiverType: UnwrappedType?

    // null means that return type is not declared, for fun(){ ... } returnType == Unit
    val returnType: UnwrappedType?
}

interface CallableReferenceArgument : CallArgument {
    override val isSpread: Boolean
        get() = false // todo error on call -- function type is not subtype of Array<out ...>

    // Foo::bar lhsType = Foo. For a::bar where a is expression, this type is null
    val lhsType: UnwrappedType?

    val constraintSystem: NewConstraintSystem
}

interface ChosenCallableReferenceDescriptor<out D : CallableDescriptor> : CallableReferenceArgument {
    val candidate: CandidateWithBoundDispatchReceiver<D>

    val extensionReceiver: ReceiverValue?
}

/*sealed*/ interface TypeArgument

// todo allow '_' in frontend
object TypeArgumentPlaceholder : TypeArgument

interface SimpleTypeArgument: TypeArgument {
    val type: UnwrappedType
}

interface NewCall {
    val explicitReceiver: SimpleCallArgument?
    val qualifierReceiver: QualifierReceiver?

    // a.(foo)() -- (foo) is dispatchReceiverForInvoke
    val dispatchReceiverForInvokeExtension: SimpleCallArgument? get() = null

    val name: Name

    val typeArguments: List<TypeArgument>

    val argumentsInParenthesis: List<CallArgument>

    val externalArgument: CallArgument?
}

private fun SimpleCallArgument.checkReceiverInvariants() {
    assert(!isSpread) {
        "Receiver cannot be a spread: $this"
    }
    assert(argumentName == null) {
        "Argument name should be null for receiver: $this, but it is $argumentName"
    }
}

fun NewCall.checkCallInvariants() {
    assert(explicitReceiver !is LambdaArgument && explicitReceiver !is CallableReferenceArgument) {
        "Lambda argument or callable reference is not allowed as explicit receiver: $explicitReceiver"
    }

    explicitReceiver?.checkReceiverInvariants()
    dispatchReceiverForInvokeExtension?.checkReceiverInvariants()

    assert(externalArgument == null || !externalArgument!!.isSpread) {
        "External argument cannot nave spread element: $externalArgument"
    }

    assert(externalArgument?.argumentName == null) {
        "Illegal external argument with name: $externalArgument"
    }

    assert(dispatchReceiverForInvokeExtension == null || !dispatchReceiverForInvokeExtension!!.isSafeCall) {
        "Dispatch receiver for invoke cannot be safe: $dispatchReceiverForInvokeExtension"
    }

    assert(explicitReceiver == null || qualifierReceiver == null) {
        "Explicit receiver may be explicitReceiver or qualifierReceiver but not both: $explicitReceiver, $qualifierReceiver"
    }
}


// this receiver will be used as ExplicitReceiver for TowerResolver. Also similar receiver will be used for variable as function call.
class ExplicitReceiverWrapper(val callReceiver: SimpleCallArgument, private val type: UnwrappedType): ReceiverValue {
    override fun getType() = type
}




//-----------------------------


interface NotCompletedResolvedCall {
    val constraintSystem: CommonConstrainSystem

//    fun complete(info: CompletingInfo): NewResolvedCall<*>
}

sealed class CompletingInfo {
    class Substitutor(val substitutor: TypeSubstitutor) : CompletingInfo()

    class ExpectedType(val expectedType: UnwrappedType?) : CompletingInfo()
}

interface CommonConstrainSystem {

}


class ResolvedLambdaArgument(
        val outerCall: NewCall,
        val argument: LambdaArgument,
        val freshVariables: Collection<TypeVariable>,
        val receiver: UnwrappedType?,
        val parameters: List<UnwrappedType>,
        val returnType: UnwrappedType
) {
    val type: SimpleType = createFunctionType(returnType.builtIns, Annotations.EMPTY, receiver, parameters, returnType) // todo support annotations
}


class ResolvedPropertyReference(
        val outerCall: NewCall,
        val argument: ChosenCallableReferenceDescriptor<PropertyDescriptor>,
        val reflectionType: UnwrappedType
) {
    val boundDispatchReceiver: ReceiverValue? get() = argument.candidate.dispatchReceiver?.check { it !is MockReceiverForCallableReference }
    val boundExtensionReceiver: ReceiverValue? get() = argument.extensionReceiver?.check { it !is MockReceiverForCallableReference }
}

class ResolvedFunctionReference(
        val outerCall: NewCall,
        val argument: ChosenCallableReferenceDescriptor<FunctionDescriptor>,
        val reflectionType: UnwrappedType,
        val argumentsMapping: ArgumentsToParametersMapper.ArgumentMapping?
) {
    val boundDispatchReceiver: ReceiverValue? get() = argument.candidate.dispatchReceiver?.check { it !is MockReceiverForCallableReference }
    val boundExtensionReceiver: ReceiverValue? get() = argument.extensionReceiver?.check { it !is MockReceiverForCallableReference }
}
