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

package org.jetbrains.kotlin.idea.refactoring.move.moveDeclarations

import com.intellij.ide.util.EditorHelper
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.move.MoveMultipleElementsViewDescriptor
import com.intellij.refactoring.move.moveClassesOrPackages.MoveClassHandler
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.refactoring.util.*
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewBundle
import com.intellij.usageView.UsageViewDescriptor
import com.intellij.usageView.UsageViewUtil
import com.intellij.util.IncorrectOperationException
import com.intellij.util.SmartList
import com.intellij.util.containers.MultiMap
import gnu.trove.THashMap
import gnu.trove.TObjectHashingStrategy
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.asJava.namedUnwrappedElement
import org.jetbrains.kotlin.asJava.toLightElements
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.caches.resolve.KotlinCacheService
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.MutablePackageFragmentDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.*
import org.jetbrains.kotlin.idea.codeInsight.shorten.addToShorteningWaitSet
import org.jetbrains.kotlin.idea.core.deleteSingle
import org.jetbrains.kotlin.idea.refactoring.fqName.getKotlinFqName
import org.jetbrains.kotlin.idea.refactoring.getUsageContext
import org.jetbrains.kotlin.idea.refactoring.move.MoveRenameUsageInfoForExtension
import org.jetbrains.kotlin.idea.refactoring.move.createMoveUsageInfoIfPossible
import org.jetbrains.kotlin.idea.refactoring.move.getInternalReferencesToUpdateOnPackageNameChange
import org.jetbrains.kotlin.idea.refactoring.move.moveFilesOrDirectories.MoveKotlinClassHandler
import org.jetbrains.kotlin.idea.refactoring.move.postProcessMoveUsages
import org.jetbrains.kotlin.idea.references.KtSimpleNameReference.ShorteningMode
import org.jetbrains.kotlin.idea.search.projectScope
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.source.KotlinSourceElement
import org.jetbrains.kotlin.utils.SmartSet
import org.jetbrains.kotlin.utils.keysToMap
import java.util.*

interface Mover : (KtNamedDeclaration, KtElement) -> KtNamedDeclaration {
    object Default : Mover {
        override fun invoke(originalElement: KtNamedDeclaration, targetContainer: KtElement): KtNamedDeclaration {
            return when (targetContainer) {
                is KtFile -> targetContainer.add(originalElement) as KtNamedDeclaration
                is KtClassOrObject -> targetContainer.addDeclaration(originalElement) as KtNamedDeclaration
                else -> error("Unexpected element: ${targetContainer.getElementTextWithContext()}")
            }.apply { originalElement.deleteSingle() }
        }
    }

    object Idle : Mover {
        override fun invoke(originalElement: KtNamedDeclaration, targetContainer: KtElement) = originalElement
    }
}

class MoveDeclarationsDescriptor(
        val elementsToMove: Collection<KtNamedDeclaration>,
        val moveTarget: KotlinMoveTarget,
        val delegate: MoveDeclarationsDelegate,
        val searchInCommentsAndStrings: Boolean = true,
        val searchInNonCode: Boolean = true,
        val updateInternalReferences: Boolean = true,
        val deleteSourceFiles: Boolean = false,
        val moveCallback: MoveCallback? = null,
        val openInEditor: Boolean = false
)

class ConflictUsageInfo(element: PsiElement, val messages: Collection<String>) : UsageInfo(element)

class MoveKotlinDeclarationsProcessor(
        val project: Project,
        val descriptor: MoveDeclarationsDescriptor,
        val mover: Mover = Mover.Default) : BaseRefactoringProcessor(project) {
    companion object {
        private val REFACTORING_NAME = "Move declarations"
        val REFACTORING_ID = "move.kotlin.declarations"
    }

    private var nonCodeUsages: Array<NonCodeUsageInfo>? = null
    private val elementsToMove = descriptor.elementsToMove.filter { e -> e.parent != descriptor.moveTarget.getTargetPsiIfExists(e) }
    private val kotlinToLightElementsBySourceFile = elementsToMove
            .groupBy { it.getContainingKtFile() }
            .mapValues { it.value.keysToMap { it.toLightElements() } }
    private val conflicts = MultiMap<PsiElement, String>()

    private val resolutionFacade by lazy { KotlinCacheService.getInstance(project).getResolutionFacade(elementsToMove) }

    override fun getRefactoringId() = REFACTORING_ID

    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>): UsageViewDescriptor {
        val targetContainerFqName = descriptor.moveTarget.targetContainerFqName?.let {
            if (it.isRoot) UsageViewBundle.message("default.package.presentable.name") else it.asString()
        }
        return MoveMultipleElementsViewDescriptor(elementsToMove.toTypedArray(), targetContainerFqName)
    }

    private val usagesToProcessBeforeMove = SmartList<UsageInfo>()

    private val fakeFile = KtPsiFactory(project).createFile("")

    fun getConflictsAsUsages(): List<UsageInfo> = conflicts.entrySet().map { ConflictUsageInfo(it.key, it.value) }

    public override fun findUsages(): Array<UsageInfo> {
        val newContainerName = descriptor.moveTarget.targetContainerFqName?.asString() ?: ""

        fun collectUsages(kotlinToLightElements: Map<KtNamedDeclaration, List<PsiNamedElement>>, result: MutableList<UsageInfo>) {
            kotlinToLightElements.values.flatMap { it }.flatMapTo(result) { lightElement ->
                val newFqName = StringUtil.getQualifiedName(newContainerName, lightElement.name)

                val foundReferences = HashSet<PsiReference>()
                val projectScope = lightElement.project.projectScope()
                val results = ReferencesSearch
                        .search(lightElement, projectScope, false)
                        .mapNotNullTo(ArrayList()) { ref ->
                            if (foundReferences.add(ref) && elementsToMove.all { !it.isAncestor(ref.element)}) {
                                createMoveUsageInfoIfPossible(ref, lightElement, true)
                            }
                            else null
                        }

                val name = lightElement.getKotlinFqName()?.asString()
                if (name != null) {
                    TextOccurrencesUtil.findNonCodeUsages(
                            lightElement,
                            name,
                            descriptor.searchInCommentsAndStrings,
                            descriptor.searchInNonCode,
                            newFqName,
                            results
                    )
                }

                MoveClassHandler.EP_NAME.extensions.forEach { handler ->
                    if (handler !is MoveKotlinClassHandler) handler.preprocessUsages(results)
                }

                results
            }
        }

        fun PackageFragmentDescriptor.withSource(sourceFile: KtFile): PackageFragmentDescriptor {
            return object : PackageFragmentDescriptor by this {
                override fun getOriginal() = this
                override fun getSource() = KotlinSourceElement(sourceFile)
            }
        }

        fun DeclarationDescriptor.asPredicted(newContainer: DeclarationDescriptor): DeclarationDescriptor? {
            val originalVisibility = (this as? DeclarationDescriptorWithVisibility)?.visibility ?: return null
            val visibility = if (originalVisibility == Visibilities.PROTECTED && newContainer is PackageFragmentDescriptor) {
                Visibilities.PUBLIC
            } else {
                originalVisibility
            }
            return when (this) {
                // We rely on visibility not depending on more specific type of CallableMemberDescriptor
                is CallableMemberDescriptor -> object : CallableMemberDescriptor by this {
                    override fun getOriginal() = this
                    override fun getContainingDeclaration() = newContainer
                    override fun getVisibility(): Visibility = visibility
                    override fun getSource() = SourceElement { DescriptorUtils.getContainingSourceFile(newContainer) }
                }
                is ClassDescriptor -> object: ClassDescriptor by this {
                    override fun getOriginal() = this
                    override fun getContainingDeclaration() = newContainer
                    override fun getVisibility(): Visibility = visibility
                    override fun getSource() = SourceElement { DescriptorUtils.getContainingSourceFile(newContainer) }
                }
                else -> null
            }
        }

        fun KotlinMoveTarget.getContainerDescriptor(): DeclarationDescriptor? {
            return when (this) {
                is KotlinMoveTargetForExistingElement -> {
                    val targetElement = targetElement
                    when (targetElement) {
                        is KtNamedDeclaration -> resolutionFacade.resolveToDescriptor(targetElement)

                        is KtFile -> {
                            val packageFragment = resolutionFacade.analyze(targetElement)[BindingContext.FILE_TO_PACKAGE_FRAGMENT, targetElement]
                            packageFragment?.withSource(targetElement)
                        }

                        else -> null
                    }
                }

                is KotlinDirectoryBasedMoveTarget -> {
                    val packageFqName = targetContainerFqName ?: return null
                    val targetDir = directory
                    val targetModuleDescriptor = if (targetDir != null) {
                        val targetModule = ModuleUtilCore.findModuleForPsiElement(targetDir) ?: return null
                        val moduleFileIndex = ModuleRootManager.getInstance(targetModule).fileIndex
                        val targetModuleInfo = when {
                            moduleFileIndex.isInSourceContent(targetDir.virtualFile) -> targetModule.productionSourceInfo()
                            moduleFileIndex.isInTestSourceContent(targetDir.virtualFile) -> targetModule.testSourceInfo()
                            else -> return null
                        }
                        resolutionFacade.findModuleDescriptor(targetModuleInfo) ?: return null
                    }
                    else {
                        resolutionFacade.moduleDescriptor
                    }
                    MutablePackageFragmentDescriptor(targetModuleDescriptor, packageFqName).withSource(fakeFile)
                }

                else -> null
            }
        }

        fun DeclarationDescriptor.isVisibleIn(where: DeclarationDescriptor): Boolean {
            return when {
                this !is DeclarationDescriptorWithVisibility -> true
                !Visibilities.isVisibleIgnoringReceiver(this, where) -> false
                this is ConstructorDescriptor -> Visibilities.isVisibleIgnoringReceiver(containingDeclaration, where)
                else -> true
            }
        }

        fun render(declaration: PsiElement) = RefactoringUIUtil.getDescription(declaration, false)

        fun checkModuleConflictsInUsages(usages: List<UsageInfo>) {
            val sourceRoot = descriptor.moveTarget.targetFile ?: return
            RefactoringConflictsUtil.analyzeModuleConflicts(project, elementsToMove, usages.toTypedArray(), sourceRoot, conflicts)
        }

        fun checkModuleConflictsInDeclarations() {
            val sourceRoot = descriptor.moveTarget.targetFile ?: return
            val targetModule = ModuleUtilCore.findModuleForFile(sourceRoot, project) ?: return
            val resolveScope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(targetModule)
            for (declaration in elementsToMove) {
                declaration.forEachDescendantOfType<KtReferenceExpression> { refExpr ->
                    refExpr.references
                            .forEach { ref ->
                                val target = ref.resolve() ?: return@forEach
                                if (target.isInsideOf(elementsToMove)) return@forEach
                                if (target in resolveScope) return@forEach

                                val superMethods = SmartSet.create<PsiMethod>()
                                target.toLightMethods().forEach { superMethods += it.findDeepestSuperMethods() }
                                if (superMethods.any { it in resolveScope }) return@forEach

                                val refContainer = ref.element.getStrictParentOfType<KtNamedDeclaration>() ?: return@forEach
                                val scopeDescription = RefactoringUIUtil.getDescription(refContainer, true)
                                val message = RefactoringBundle.message("0.referenced.in.1.will.not.be.accessible.in.module.2",
                                                                        RefactoringUIUtil.getDescription(target, true),
                                                                        scopeDescription,
                                                                        CommonRefactoringUtil.htmlEmphasize(targetModule.name))
                                conflicts.putValue(target, CommonRefactoringUtil.capitalize(message))
                            }
                }
            }
        }

        fun checkVisibilityInUsages(usages: List<UsageInfo>) {
            val declarationToContainers = HashMap<KtNamedDeclaration, MutableSet<PsiElement>>()
            for (usage in usages) {
                val element = usage.element
                if (element == null || usage !is MoveRenameUsageInfo || usage is NonCodeUsageInfo) continue

                if (element.isInsideOf(elementsToMove)) continue

                val referencedElement = usage.referencedElement?.namedUnwrappedElement as? KtNamedDeclaration ?: continue
                val referencedDescriptor = resolutionFacade.resolveToDescriptor(referencedElement)

                val container = element.getUsageContext()
                if (!declarationToContainers.getOrPut(referencedElement) { HashSet<PsiElement>() }.add(container)) continue

                val referencingDescriptor = when (container) {
                    is KtDeclaration -> container.resolveToDescriptor()
                    is PsiMember -> container.getJavaMemberDescriptor()
                    else -> null
                } ?: continue
                val targetContainer = descriptor.moveTarget.getContainerDescriptor() ?: continue
                val descriptorToCheck = referencedDescriptor.asPredicted(targetContainer) ?: continue

                if (!descriptorToCheck.isVisibleIn(referencingDescriptor)) {
                    val message = "${render(container)} uses ${render(referencedElement)} which will be inaccessible after move"
                    conflicts.putValue(element, message.capitalize())
                }
            }
        }

        fun checkVisibilityInDeclarations() {
            val targetContainer = descriptor.moveTarget.getContainerDescriptor() ?: return
            for (declaration in elementsToMove) {
                declaration.forEachDescendantOfType<KtReferenceExpression> { refExpr ->
                    refExpr.references
                            .forEach { ref ->
                                val target = ref.resolve() ?: return@forEach
                                if (target.isInsideOf(elementsToMove)) return@forEach
                                val targetDescriptor = when (target) {
                                    is KtDeclaration -> target.resolveToDescriptor()
                                    is PsiMember -> target.getJavaMemberDescriptor()
                                    else -> null
                                } ?: return@forEach
                                if (!targetDescriptor.isVisibleIn(targetContainer)) {
                                    val message = "${render(declaration)} uses ${render(target)} which will be inaccessible after move"
                                    conflicts.putValue(refExpr, message.capitalize())
                                }
                            }
                }
            }
        }

        val usages = ArrayList<UsageInfo>()
        for ((sourceFile, kotlinToLightElements) in kotlinToLightElementsBySourceFile) {
            kotlinToLightElements.keys.forEach {
                if (descriptor.updateInternalReferences) {
                    val packageNameInfo = descriptor.delegate.getContainerChangeInfo(it, descriptor.moveTarget)
                    val (usagesToProcessLater, usagesToProcessEarly) = it
                            .getInternalReferencesToUpdateOnPackageNameChange(packageNameInfo)
                            .partition { it is MoveRenameUsageInfoForExtension }
                    usages.addAll(usagesToProcessLater)
                    usagesToProcessBeforeMove.addAll(usagesToProcessEarly)
                }
            }

            usages += descriptor.delegate.findUsages(descriptor)
            collectUsages(kotlinToLightElements, usages)
            checkModuleConflictsInUsages(usages)
            checkModuleConflictsInDeclarations()
            checkVisibilityInUsages(usages)
            checkVisibilityInDeclarations()
            descriptor.delegate.collectConflicts(descriptor, usages, conflicts)
        }

        descriptor.delegate.collectConflicts(descriptor, usagesToProcessBeforeMove, conflicts)

        return UsageViewUtil.removeDuplicatedUsages(usages.toTypedArray())
    }

    override fun preprocessUsages(refUsages: Ref<Array<UsageInfo>>): Boolean {
        return showConflicts(conflicts, refUsages.get())
    }

    override fun performRefactoring(usages: Array<out UsageInfo>) {
        fun moveDeclaration(declaration: KtNamedDeclaration, moveTarget: KotlinMoveTarget): KtNamedDeclaration? {
            val file = declaration.containingFile as? KtFile
            assert(file != null) { "${declaration.javaClass}: ${declaration.text}" }

            val targetContainer = moveTarget.getOrCreateTargetPsi(declaration)
                                  ?: throw AssertionError("Couldn't create Kotlin file for: ${declaration.javaClass}: ${declaration.text}")

            descriptor.delegate.preprocessDeclaration(descriptor, declaration)
            val newElement = mover(declaration, targetContainer)

            newElement.addToShorteningWaitSet()

            return newElement
        }

        try {
            val usageList = usages.toList()

            descriptor.delegate.preprocessUsages(project, usageList)

            postProcessMoveUsages(usagesToProcessBeforeMove, shorteningMode = ShorteningMode.NO_SHORTENING)

            val oldToNewElementsMapping = THashMap<PsiElement, PsiElement>(
                    object: TObjectHashingStrategy<PsiElement> {
                        override fun equals(e1: PsiElement?, e2: PsiElement?): Boolean {
                            if (e1 === e2) return true
                            // Name should be enough to distinguish different light elements based on the same original declaration
                            if (e1 is KtLightElement<*, *> && e2 is KtLightElement<*, *>) {
                                return e1.kotlinOrigin == e2.kotlinOrigin && e1.name == e2.name
                            }
                            return e1 == e2
                        }

                        override fun computeHashCode(e: PsiElement?): Int {
                            return when (e) {
                                null -> 0
                                is KtLightElement<*, *> -> (e.kotlinOrigin?.hashCode() ?: 0) * 31 + (e.name?.hashCode() ?: 0)
                                else -> e.hashCode()
                            }
                        }
                    }
            )
            for ((sourceFile, kotlinToLightElements) in kotlinToLightElementsBySourceFile) {
                for ((oldDeclaration, oldLightElements) in kotlinToLightElements) {
                    val newDeclaration = moveDeclaration(oldDeclaration, descriptor.moveTarget)
                    if (newDeclaration == null) {
                        for (oldElement in oldLightElements) {
                            oldToNewElementsMapping[oldElement] = oldElement
                        }
                        continue
                    }

                    oldToNewElementsMapping[sourceFile] = newDeclaration.getContainingKtFile()

                    transaction!!.getElementListener(oldDeclaration).elementMoved(newDeclaration)
                    for ((oldElement, newElement) in oldLightElements.asSequence().zip(newDeclaration.toLightElements().asSequence())) {
                        oldToNewElementsMapping[oldElement] = newElement
                    }

                    if (descriptor.openInEditor) {
                        EditorHelper.openInEditor(newDeclaration)
                    }
                }

                if (descriptor.deleteSourceFiles) {
                    sourceFile.delete()
                }
            }

            nonCodeUsages = postProcessMoveUsages(usageList, oldToNewElementsMapping).toTypedArray()
        }
        catch (e: IncorrectOperationException) {
            nonCodeUsages = null
            RefactoringUIUtil.processIncorrectOperation(myProject, e)
        }
    }

    override fun performPsiSpoilingRefactoring() {
        nonCodeUsages?.let { nonCodeUsages -> RenameUtil.renameNonCodeUsages(myProject, nonCodeUsages) }
        descriptor.moveCallback?.refactoringCompleted()
    }

    fun execute(usages: List<UsageInfo>) {
        execute(usages.toTypedArray())
    }

    override fun getCommandName(): String = REFACTORING_NAME
}

interface D : DeclarationDescriptorWithSource {

}