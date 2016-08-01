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
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.calls.model.*
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.calls.tower.CandidateWithBoundDispatchReceiver
import org.jetbrains.kotlin.resolve.calls.tower.InvokeTowerContext
import org.jetbrains.kotlin.resolve.calls.tower.ScopeTower
import org.jetbrains.kotlin.resolve.calls.tower.TowerContext
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.util.OperatorNameConventions

class NewResolutionAndInference(val newCandidateResolver: NewCandidateResolver) {


    private inner class SimpleContext<D : CallableDescriptor>(
            val call: NewCall,
            override val scopeTower: ScopeTower
    ) : TowerContext<D, SimpleResolutionCandidate<D>> {
        override val name: Name get() = call.name

        override fun createCandidate(
                towerCandidate: CandidateWithBoundDispatchReceiver<D>,
                explicitReceiverKind: ExplicitReceiverKind,
                extensionReceiver: ReceiverValue?
        ) = SimpleResolutionCandidate(newCandidateResolver, NewCandidateResolver.resolutionSequence, call, scopeTower.lexicalScope.ownerDescriptor,
                                      towerCandidate, explicitReceiverKind, extensionReceiver)

    }

    private inner class InvokeContext(
            val call: NewCall,
            val scopeTower: ScopeTower
    ) : InvokeTowerContext<NewResolutionCandidate<FunctionDescriptor>, SimpleResolutionCandidate<VariableDescriptor>> {
        init {
            assert(call.dispatchReceiverForInvokeExtension == null) { call }
        }

        override fun transformCandidate(
                variable: SimpleResolutionCandidate<VariableDescriptor>,
                invoke: NewResolutionCandidate<FunctionDescriptor>
        ): NewResolutionCandidate<FunctionDescriptor> {
            assert(invoke is SimpleResolutionCandidate) {
                "VariableAsFunction candidate is not allowed here: $invoke"
            }
            return VariableAsFunction(newCandidateResolver, variable, invoke as SimpleResolutionCandidate)
        }

        override fun contextForVariable(stripExplicitReceiver: Boolean): TowerContext<VariableDescriptor, SimpleResolutionCandidate<VariableDescriptor>> {
            assert(call.typeArguments.isEmpty()) {
                "Call with explicit arguments cannot be variable as function call: $call" // todo
            }

            val explicitReceiver = if (stripExplicitReceiver) null else call.explicitReceiver
            val variableCall = CallForVariable(explicitReceiver, call.name)
            return SimpleContext<VariableDescriptor>(variableCall, scopeTower)
        }

        // a.foo() -- variable here is foo
        override fun contextForInvoke(
                variable: SimpleResolutionCandidate<VariableDescriptor>,
                useExplicitReceiver: Boolean
        ): Pair<ReceiverValue, TowerContext<FunctionDescriptor, NewResolutionCandidate<FunctionDescriptor>>> {
            // todo when we construct ScopeTower for invoke, we should fix smartCasts for receiver a.foo
            // see NewResolutionOldInference.InvokeContext.contextForInvoke

            val variableReceiver = transformToCallReceiver(variable)
            val receiverValue = transformToReceiverValue(variableReceiver)
            val explicitReceiver = call.explicitReceiver
            val callForInvoke = if (useExplicitReceiver && explicitReceiver is SimpleCallArgument) {
                CallForInvoke(call, explicitReceiver, variableReceiver)
            }
            else {
                CallForInvoke(call, variableReceiver, null)
            }
            return receiverValue to SimpleContext(callForInvoke, scopeTower)
        }
    }

    class CallForVariable(override val explicitReceiver: ReceiverCallArgument?, override val name: Name) : NewCall {
        override val typeArguments: List<TypeArgument> get() = emptyList()
        override val argumentsInParenthesis: List<CallArgument> get() = emptyList()
        override val externalArgument: CallArgument? get() = null
    }

    class CallForInvoke(
            val baseCall: NewCall,
            override val explicitReceiver: SimpleCallArgument,
            override val dispatchReceiverForInvokeExtension: SimpleCallArgument?
    ) : NewCall {
        override val name: Name get() = OperatorNameConventions.INVOKE
        override val typeArguments: List<TypeArgument> get() = listOf() // type arguments is not allowed for variable as function call
        override val argumentsInParenthesis: List<CallArgument> get() = baseCall.argumentsInParenthesis
        override val externalArgument: CallArgument? get() = baseCall.externalArgument
    }

    fun transformToCallReceiver(candidate: SimpleResolutionCandidate<*>): SimpleCallArgument = TODO() // TODO

    fun transformToReceiverValue(callReceiver: SimpleCallArgument): ReceiverValue {
        return when (callReceiver) {
            is ExpressionArgument -> ExplicitReceiverWrapper(callReceiver, callReceiver.type)
            is SubCall -> TODO()
            else -> incorrectReceiver(callReceiver)
        }
    } // TODO also see [contextForInvoke]

    fun transformToCallReceiver(receiverValue: ReceiverValue): SimpleCallArgument {
        if (receiverValue is ExplicitReceiverWrapper) return receiverValue.callReceiver

        return TODO() // todo
    }
}
