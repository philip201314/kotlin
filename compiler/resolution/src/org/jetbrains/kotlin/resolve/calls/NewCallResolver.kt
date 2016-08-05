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
import org.jetbrains.kotlin.resolve.calls.inference.NewConstraintSystem
import org.jetbrains.kotlin.resolve.calls.model.NewCall
import org.jetbrains.kotlin.resolve.calls.tower.TowerResolver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.UnwrappedType


sealed class BaseResolvedCall<D : CallableDescriptor> {
    class CompletedResolvedCall<D : CallableDescriptor>: BaseResolvedCall<D>() {

    }

    class VariableAsFunctionCompletedResolvedCall(
            val variableCall: CompletedResolvedCall<VariableDescriptor>,
            val functionCall: CompletedResolvedCall<FunctionDescriptor>
    ) : BaseResolvedCall<FunctionDescriptor>()


    class OnlyResolvedCall<D : CallableDescriptor> : BaseResolvedCall<D>() {
        val currentReturnType: UnwrappedType = TODO()
        val constraintSystem: NewConstraintSystem = TODO()
    }
}

class NewCallResolver(
        val argumentsToParametersMapper: ArgumentsToParametersMapper,
        val typeArgumentsToParametersMapper: TypeArgumentsToParametersMapper,
        val towerResolver: TowerResolver
) {

    fun resolveVariable(call: NewCall): BaseResolvedCall<VariableDescriptor> {
        TODO()
    }




}