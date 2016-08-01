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

import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.calls.BaseResolvedCall
import org.jetbrains.kotlin.resolve.scopes.receivers.Receiver
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeSubstitutor

/*sealed*/ interface CallReceiver

interface ResolvedReceiver {
    val receiver: Receiver
}

/*sealed*/ interface CallArgument {
    val isSpread: Boolean
    val argumentName: Name?
}

interface ExpressionArgument : CallArgument {
    val type: KotlinType
}

interface SubCall : CallArgument, CallReceiver {
    val resolvedCall: BaseResolvedCall.OnlyResolvedCall<*>
}

interface LambdaArgument : CallArgument {
    override val isSpread: Boolean
        get() = false // todo error on call -- function type is not subtype of Array<out ...>
}

interface CallableReferenceArgument : CallArgument {
    override val isSpread: Boolean
        get() = false // todo error on call -- function type is not subtype of Array<out ...>
}

/*sealed*/ interface TypeArgument

// todo allow '_' in frontend
object TypeArgumentPlaceholder : TypeArgument

interface SimpleTypeArgument: TypeArgument {
    val type: KotlinType
}

interface NewCall {
    val explicitReceiver: CallReceiver?

    val name: Name

    val typeArguments: List<TypeArgument>

    val argumentsInParenthesis: List<CallArgument>

    val externalArgument: CallArgument?
}

fun NewCall.checkCallInvariants() {
    assert(explicitReceiver !is LambdaArgument && explicitReceiver !is CallableReferenceArgument) {
        "Lambda argument or callable reference is not allowed as explicit receiver: $explicitReceiver"
    }

    assert(externalArgument == null || !externalArgument!!.isSpread) {
        "External argument cannot nave spread element: $externalArgument"
    }

    assert(externalArgument?.argumentName == null) {
        "Illegal external argument with name: $externalArgument"
    }
}



//-----------------------------


interface NotCompletedResolvedCall {
    val constraintSystem: CommonConstrainSystem

//    fun complete(info: CompletingInfo): NewResolvedCall<*>
}

sealed class CompletingInfo {
    class Substitutor(val substitutor: TypeSubstitutor) : CompletingInfo()

    class ExpectedType(val expectedType: KotlinType?) : CompletingInfo()
}

interface CommonConstrainSystem {

}
