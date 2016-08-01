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
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.calls.TypeArgumentsToParametersMapper.TypeArgumentsMapping
import org.jetbrains.kotlin.resolve.calls.inference.EmptyConstraintSystem
import org.jetbrains.kotlin.resolve.calls.inference.NewConstraintSystem
import org.jetbrains.kotlin.resolve.calls.inference.TypeVariable
import org.jetbrains.kotlin.resolve.calls.model.*
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.calls.tower.*
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.types.IndexedParametersSubstitution
import org.jetbrains.kotlin.types.TypeProjection
import org.jetbrains.kotlin.types.TypeProjectionImpl
import org.jetbrains.kotlin.types.TypeSubstitutor
import org.jetbrains.kotlin.utils.SmartList
import java.util.*

class NewCandidateResolver(
        val argumentsToParametersMapper: ArgumentsToParametersMapper,
        val typeArgumentsToParametersMapper: TypeArgumentsToParametersMapper
) {

    private inner class SimpleContext<D : CallableDescriptor>(override val name: Name, override val scopeTower: ScopeTower) : TowerContext<D, ResolutionCandidate<D>> {

        override fun createCandidate(
                towerCandidate: CandidateWithBoundDispatchReceiver<D>,
                explicitReceiverKind: ExplicitReceiverKind,
                extensionReceiver: ReceiverValue?
        ): ResolutionCandidate<D> {
            throw UnsupportedOperationException()
        }

    }


    private inner abstract class AbstractResolutionCandidate<D : CallableDescriptor> : Candidate<D> {
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
                val diagnostics = resolutionSequence[step].run { self().process(this@NewCandidateResolver) }
                step++
                hasErrors = diagnostics.any { !it.candidateApplicability.isSuccess  }
                this.diagnostics.addAll(diagnostics)
            }
        }

        protected abstract fun self(): ResolutionCandidate<D>
    }

    private interface ResolutionPart {
        fun <D : CallableDescriptor> ResolutionCandidate<D>.process(resolver: NewCandidateResolver): List<CallDiagnostic>
    }

    private inner class ResolutionCandidate<D : CallableDescriptor>(
            val call: NewCall,
            val containingDescriptor: DeclarationDescriptor,
            val towerCandidate: CandidateWithBoundDispatchReceiver<D>,
            val explicitReceiverKind: ExplicitReceiverKind,
            val extensionReceiver: ReceiverValue?
    ) : AbstractResolutionCandidate<D>() {
        override val candidateDescriptor: D
            get() = towerCandidate.descriptor

        val dispatchReceiver: ReceiverValue? = towerCandidate.dispatchReceiver

        var constraintSystem: NewConstraintSystem = EmptyConstraintSystem

        lateinit var typeArgumentMappingByOriginal: TypeArgumentsMapping
        lateinit var argumentMappingByOriginal: Map<ValueParameterDescriptor, ResolvedCallArgument>
        lateinit var descriptorWithFreshTypes: D


        override fun self(): ResolutionCandidate<D> = this
    }

    private object ApplyResolutionDiagnostics : ResolutionPart {
        override fun <D : CallableDescriptor> ResolutionCandidate<D>.process(resolver: NewCandidateResolver) = towerCandidate.diagnostics
    }

    private object CheckVisibility : ResolutionPart {
        override fun <D : CallableDescriptor> ResolutionCandidate<D>.process(resolver: NewCandidateResolver): List<CallDiagnostic> {

            val invisibleMember = Visibilities.findInvisibleMember(
                    dispatchReceiver, candidateDescriptor, containingDescriptor) ?: return emptyList()

            return listOf(VisibilityError(invisibleMember))
        }
    }

    private object MapTypeArguments : ResolutionPart {
        override fun <D : CallableDescriptor> ResolutionCandidate<D>.process(resolver: NewCandidateResolver): List<CallDiagnostic> {
            typeArgumentMappingByOriginal = resolver.typeArgumentsToParametersMapper.mapTypeArguments(call, candidateDescriptor.original)
            return typeArgumentMappingByOriginal.diagnostics
        }
    }

    private object MapArguments : ResolutionPart {
        override fun <D : CallableDescriptor> ResolutionCandidate<D>.process(resolver: NewCandidateResolver): List<CallDiagnostic> {
            val mapping = resolver.argumentsToParametersMapper.mapArguments(call, candidateDescriptor.original)
            argumentMappingByOriginal = mapping.parameterToCallArgumentMap
            return mapping.diagnostics
        }
    }

    private object CreteDescriptorWithFreshTypeVariables : ResolutionPart {
        override fun <D : CallableDescriptor> ResolutionCandidate<D>.process(resolver: NewCandidateResolver): List<CallDiagnostic> {
            if (candidateDescriptor.typeParameters.isEmpty()) {
                descriptorWithFreshTypes = candidateDescriptor
                return emptyList()
            }
            else {
                val typeParameters = candidateDescriptor.typeParameters.toTypedArray()
                val newTypeVariables = SmartList<TypeVariable>()

                val typeArgumentsForFunction = Array<TypeProjection>(typeParameters.size) {
                    val typeParameter = typeParameters[it]
                    val typeArgument = typeArgumentMappingByOriginal.getTypeArgument(typeParameter)

                    return@Array when(typeArgument) {
                        TypeArgumentPlaceholder -> {


                            TODO() // todo
                        }
                        is SimpleTypeArgument -> {
                            TypeProjectionImpl(typeArgument.type)
                        }
                        else -> throw AssertionError("Illegal type of typeArgument: ${typeArgument.javaClass.canonicalName}")
                    }
                }
                val substitutor = TypeSubstitutor.create(IndexedParametersSubstitution(typeParameters, typeArgumentsForFunction))


                descriptorWithFreshTypes = @Suppress("UNCHECKED_CAST") (candidateDescriptor.substitute(substitutor) as D)
                return TODO()
            }
        }
    }

    companion object {
        private val resolutionSequence = listOf<ResolutionPart>(
                ApplyResolutionDiagnostics,
                CheckVisibility,
                MapTypeArguments,
                MapArguments,
                CreteDescriptorWithFreshTypeVariables)
    }
}