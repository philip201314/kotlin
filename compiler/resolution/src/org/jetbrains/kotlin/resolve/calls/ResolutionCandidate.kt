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

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.resolve.calls.inference.ConstraintSystemBuilder
import org.jetbrains.kotlin.resolve.calls.inference.createNewConstraintSystemBuilder
import org.jetbrains.kotlin.resolve.calls.model.CallDiagnostic
import org.jetbrains.kotlin.resolve.calls.model.NewCall
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCallArgument
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.calls.tower.Candidate
import org.jetbrains.kotlin.resolve.calls.tower.CandidateWithBoundDispatchReceiver
import org.jetbrains.kotlin.resolve.calls.tower.ResolutionCandidateStatus
import org.jetbrains.kotlin.resolve.calls.tower.isSuccess
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import java.util.*


interface ResolutionPart {
    fun <D : CallableDescriptor> SimpleResolutionCandidate<D>.process(resolver: NewCandidateResolver): List<CallDiagnostic>
}

sealed class NewResolutionCandidate<D : CallableDescriptor>(protected val candidateResolver: NewCandidateResolver) : Candidate<D>

sealed class AbstractSimpleResolutionCandidate<D : CallableDescriptor>(
        candidateResolver: NewCandidateResolver,
        val resolutionSequence: List<ResolutionPart>
) : NewResolutionCandidate<D>(candidateResolver) {
    override val isSuccessful: Boolean
        get() {
            process(stopOnFirstError = true)
            return hasErrors
        }

    private var _status: ResolutionCandidateStatus? = null

    override val status: ResolutionCandidateStatus
        get() {
            if (_status == null) {
                process(stopOnFirstError = false)
                _status = ResolutionCandidateStatus(diagnostics)
            }
            return _status!!
        }

    private val diagnostics = ArrayList<CallDiagnostic>()
    private var step = 0
    private var hasErrors = false

    private fun process(stopOnFirstError: Boolean) {
        while (step < resolutionSequence.size && (!stopOnFirstError || !hasErrors)) {
            val diagnostics = resolutionSequence[step].run { self().process(candidateResolver) }
            step++
            hasErrors = diagnostics.any { !it.candidateApplicability.isSuccess }
            this.diagnostics.addAll(diagnostics)
        }
    }

    protected abstract fun self(): SimpleResolutionCandidate<D>
}

class SimpleResolutionCandidate<D : CallableDescriptor>(
        candidateResolver: NewCandidateResolver,
        resolutionSequence: List<ResolutionPart>,
        val call: NewCall,
        val containingDescriptor: DeclarationDescriptor,
        val towerCandidate: CandidateWithBoundDispatchReceiver<D>,
        val explicitReceiverKind: ExplicitReceiverKind,
        val extensionReceiver: ReceiverValue?
) : AbstractSimpleResolutionCandidate<D>(candidateResolver, resolutionSequence) {
    override val candidateDescriptor: D get() = towerCandidate.descriptor
    val dispatchReceiver: ReceiverValue? get() = towerCandidate.dispatchReceiver
    val csBuilder: ConstraintSystemBuilder = createNewConstraintSystemBuilder()

    lateinit var typeArgumentMappingByOriginal: TypeArgumentsToParametersMapper.TypeArgumentsMapping
    lateinit var argumentMappingByOriginal: Map<ValueParameterDescriptor, ResolvedCallArgument>
    lateinit var descriptorWithFreshTypes: D

    override fun self() = this
}

class VariableAsFunction(
        candidateResolver: NewCandidateResolver,
        val resolvedVariable: SimpleResolutionCandidate<VariableDescriptor>,
        val invokeCandidate: SimpleResolutionCandidate<FunctionDescriptor>
) : NewResolutionCandidate<FunctionDescriptor>(candidateResolver) {
    override val candidateDescriptor: FunctionDescriptor get() = invokeCandidate.candidateDescriptor
    override val isSuccessful: Boolean get() = resolvedVariable.isSuccessful && invokeCandidate.isSuccessful
    override val status: ResolutionCandidateStatus
        get() = ResolutionCandidateStatus(resolvedVariable.status.diagnostics + invokeCandidate.status.diagnostics)
}
