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
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.Call
import org.jetbrains.kotlin.resolve.calls.callUtil.isSafeCall
import org.jetbrains.kotlin.resolve.calls.context.BasicCallResolutionContext
import org.jetbrains.kotlin.resolve.calls.model.*
import org.jetbrains.kotlin.resolve.calls.results.OverloadResolutionResultsImpl
import org.jetbrains.kotlin.resolve.calls.tower.NewResolutionOldInference
import org.jetbrains.kotlin.types.UnwrappedType

class NewCallResolver {
    val useNewInference = false

    fun <D : CallableDescriptor> runResolutionAndInference(
            context: BasicCallResolutionContext,
            name: Name,
            resolutionKind: NewResolutionOldInference.ResolutionKind<D>
    ) : OverloadResolutionResultsImpl<D> {

    }

    fun toNewCall(oldCall: Call): NewCall {
        oldCall.isSafeCall()
    }

    class Receiver(
            override val type: UnwrappedType,
            override val unstableType: UnwrappedType?,
            override val isSafeCall: Boolean,
            override val isSpread: Boolean,
            override val argumentName: Name?
    ) : ExpressionArgument


    class NewCallImpl(
            override val explicitReceiver: SimpleCallArgument?,
            override val name: Name,
            override val typeArguments: List<TypeArgument>,
            override val argumentsInParenthesis: List<CallArgument>,
            override val externalArgument: CallArgument?
    ) : NewCall
}