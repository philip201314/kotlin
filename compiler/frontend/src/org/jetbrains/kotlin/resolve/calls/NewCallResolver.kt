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
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowValue
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowValueFactory
import org.jetbrains.kotlin.resolve.calls.tower.NewResolutionOldInference
import org.jetbrains.kotlin.resolve.scopes.receivers.QualifierReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.types.UnwrappedType
import org.jetbrains.kotlin.types.checker.intersectTypes
import org.jetbrains.kotlin.utils.addToStdlib.check
import java.util.*

class NewCallResolver {
    val useNewInference = false

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

    fun toNewCall(context: BasicCallResolutionContext,
                  oldCall: Call): NewCall {
        val oldReceiver = oldCall.explicitReceiver
        val isSafeCall = oldCall.isSafeCall()

        val explicitReceiver = when(oldReceiver) {
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
            override val externalArgument: CallArgument?
    ) : NewCall
}