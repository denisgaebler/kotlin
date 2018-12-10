/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.evaluate

import com.intellij.debugger.engine.evaluation.EvaluateExceptionUtil
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.jdi.LocalVariableProxyImpl
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.openapi.diagnostic.Attachment
import com.sun.jdi.*
import org.jetbrains.kotlin.codegen.AsmUtil
import org.jetbrains.kotlin.codegen.AsmUtil.getCapturedFieldName
import org.jetbrains.kotlin.codegen.AsmUtil.getLabeledThisName
import org.jetbrains.kotlin.codegen.inline.INLINE_FUN_VAR_SUFFIX
import org.jetbrains.kotlin.codegen.inline.INLINE_TRANSFORMATION_SUFFIX
import org.jetbrains.kotlin.codegen.inline.NUMBERED_FUNCTION_PREFIX
import org.jetbrains.kotlin.codegen.topLevelClassAsmType
import org.jetbrains.kotlin.idea.debugger.*
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.idea.util.attachment.mergeAttachments
import org.jetbrains.kotlin.resolve.jvm.AsmTypes
import org.jetbrains.kotlin.resolve.jvm.JvmClassName
import org.jetbrains.kotlin.resolve.jvm.JvmPrimitiveType
import org.jetbrains.org.objectweb.asm.Type as AsmType
import com.sun.jdi.Type as JdiType

class VariableFinder private constructor(private val context: EvaluationContextImpl, private val frameProxy: StackFrameProxyImpl) {
    companion object {
        fun instance(context: EvaluationContextImpl): VariableFinder? {
            val frameProxy = context.frameProxy ?: return null
            return VariableFinder(context, frameProxy)
        }

        fun unwrapRefValue(value: ObjectReference): Value? {
            return NamedEntity("ref", value.type()) { value }.unwrap().value()
        }

        private val inlinedThisRegex = getLocalVariableNameRegexInlineAware(AsmUtil.THIS + "_")

        private fun getCapturedVariableNameRegex(capturedName: String): Regex {
            val escapedName = Regex.escape(capturedName)
            val escapedSuffix = Regex.escape(INLINE_TRANSFORMATION_SUFFIX)
            return Regex("^$escapedName(?:$escapedSuffix)?$")
        }

        private fun getLocalVariableNameRegexInlineAware(name: String): Regex {
            val escapedName = Regex.escape(name)
            val escapedSuffix = Regex.escape(INLINE_FUN_VAR_SUFFIX)
            return Regex("^$escapedName(?:$escapedSuffix)*$")
        }

        private fun AsmType.isFunctionType(): Boolean {
            return sort == AsmType.OBJECT && internalName.startsWith(NUMBERED_FUNCTION_PREFIX)
        }
    }

    sealed class VariableKind(val type: AsmType?) {
        abstract fun capturedNameMatches(name: String): Boolean

        class Ordinary(val name: String, type: AsmType?) : VariableKind(type) {
            private val capturedNameRegex = getCapturedVariableNameRegex(getCapturedFieldName(this.name))
            override fun capturedNameMatches(name: String) = capturedNameRegex.matches(name)
        }

        class UnlabeledThis(type: AsmType?) : VariableKind(type) {
            override fun capturedNameMatches(name: String) =
                (name == AsmUtil.CAPTURED_RECEIVER_FIELD || name.startsWith(AsmUtil.getCapturedFieldName(AsmUtil.LABELED_THIS_FIELD)))
        }

        class LabeledThis(val label: String, type: AsmType?) : VariableKind(type) {
            private val capturedNameRegex = getCapturedVariableNameRegex(
                getCapturedFieldName(getLabeledThisName(label, AsmUtil.LABELED_THIS_FIELD, AsmUtil.CAPTURED_RECEIVER_FIELD))
            )

            override fun capturedNameMatches(name: String) = capturedNameRegex.matches(name)
        }

        fun typeMatches(type: JdiType?): Boolean {
            if (type == null) return true
            val asmType = this.type ?: return true

            // Main path
            if (asmType.descriptor == "Ljava/lang/Object;" || type.isSubtype(asmType)) {
                return true
            }

            // The latter is for boxing interventions
            fun box(desc: String) = JvmPrimitiveType.getByDesc(desc)?.wrapperFqName?.topLevelClassAsmType()?.descriptor

            val asmTypeDescriptor = asmType.descriptor
            val jdiTypeDescriptor = type.signature()

            val boxedAsmType = box(asmTypeDescriptor) ?: asmTypeDescriptor
            val boxedJdiType = box(jdiTypeDescriptor) ?: jdiTypeDescriptor
            return boxedAsmType == boxedJdiType
        }
    }

    class Result(val value: Value?)

    private class NamedEntity(val name: String, val type: JdiType?, val value: () -> Value?) {
        companion object {
            fun of(field: Field, owner: ObjectReference): NamedEntity {
                return NamedEntity(field.name(), field.safeType()) { owner.getValue(field) }.unwrap()
            }

            fun of(variable: LocalVariableProxyImpl, frameProxy: StackFrameProxyImpl): NamedEntity {
                return NamedEntity(variable.name(), variable.safeType()) { frameProxy.getValue(variable) }.unwrap()
            }
        }

        fun unwrap(): NamedEntity {
            if (type !is ClassType || !type.signature().startsWith("L" + AsmTypes.REF_TYPE_PREFIX)) {
                return this
            }

            val obj = this.value() as? ObjectReference ?: return this
            val field = type.fieldByName("element") ?: return this

            val newType = if (field.type() is PrimitiveType) field.type() else null
            return NamedEntity(name, newType) { obj.getValue(field) }
        }
    }

    fun get(name: String, type: AsmType?): Value? {
        val result = find(name, type) ?: throw variableNotFound(buildString {
            append("Cannot find local variable: name = '").append(name).append("'")
            if (type != null) {
                append(", type = " + type.className)
            }
        })

        return result.value
    }

    fun find(name: String, type: AsmType?): Result? {
        return when {
            name.startsWith(AsmUtil.THIS + "@") -> {
                val label = name.drop(AsmUtil.THIS.length + 1).also { require(it.isNotEmpty()) { "Invalid name '$name'" } }
                findLabeledThis(VariableKind.LabeledThis(label, type))
            }
            name == AsmUtil.THIS -> findUnlabeledThis(VariableKind.UnlabeledThis(type))
            else -> findOrdinary(VariableKind.Ordinary(name, type))
        }
    }

    private fun findOrdinary(kind: VariableKind.Ordinary): Result? {
        val variables = frameProxy.safeVisibleVariables()

        // Local variables – direct search
        findLocalVariable(variables, kind, kind.name)?.let { return it }

        // Recursive search in local receiver variables
        findCapturedVariableInReceiver(variables, kind)?.let { return it }

        // Recursive search in captured this
        val containingThis = frameProxy.thisObject() ?: return null
        return findCapturedVariable(kind, containingThis)
    }

    private fun findLabeledThis(kind: VariableKind.LabeledThis): Result? {
        val variables = frameProxy.safeVisibleVariables()

        // Local variables – direct search
        findLocalVariable(variables, kind, AsmUtil.LABELED_THIS_PARAMETER + kind.label)?.let { return it }

        // Recursive search in local receiver variables
        findCapturedVariableInReceiver(variables, kind)?.let { return it }

        // Recursive search in captured this
        val containingThis = frameProxy.thisObject()
        if (containingThis != null) {
            findCapturedVariable(kind, containingThis)?.let { return it }
        }

        // Fallback: find an unlabeled this with the compatible type
        return findUnlabeledThis(VariableKind.UnlabeledThis(kind.type))
    }

    private fun findUnlabeledThis(kind: VariableKind.UnlabeledThis): Result? {
        val variables = frameProxy.safeVisibleVariables()

        // Recursive search in local receiver variables
        findCapturedVariableInReceiver(variables, kind)?.let { return it }

        val containingThis = frameProxy.thisObject() ?: return null
        return findCapturedVariable(kind, containingThis)
    }

    private fun findLocalVariable(variables: List<LocalVariableProxyImpl>, kind: VariableKind, name: String): Result? {
        val canBeLocalFunction = kind is VariableKind.Ordinary
        val nameInlineAwareRegex = getLocalVariableNameRegexInlineAware(name)

        variables.namedEntitySequence()
            .filter { it.name == name && kind.typeMatches(it.type) }
            .firstOrNull()
            ?.let { return Result(it.value()) }

        if (canBeLocalFunction && kind.type?.isFunctionType() == true) {
            @Suppress("ConvertToStringTemplate")
            variables.namedEntitySequence()
                .filter { it.name == name + "$" && kind.typeMatches(it.type) }
                .firstOrNull()
                ?.let { return Result(it.value()) }
        }

        variables.namedEntitySequence()
            .filter { it.name.matches(nameInlineAwareRegex) }
            .filter { kind.typeMatches(it.type) }
            .toList() // Sorted by will make a list anyway
            .sortedByDescending { calculateInlineDepth(it.name) }
            .firstOrNull()
            ?.let { return Result(it.value()) }

        return null
    }

    private fun findCapturedVariableInReceiver(variables: List<LocalVariableProxyImpl>, kind: VariableKind): Result? {
        fun isReceiverOrPassedThis(name: String) =
            name.startsWith(AsmUtil.LABELED_THIS_PARAMETER)
                    || name == AsmUtil.RECEIVER_PARAMETER_NAME
                    || name == AsmUtil.getCapturedFieldName(AsmUtil.THIS)
                    || inlinedThisRegex.matches(name) // InlineMethodInstructionAdapter#visitLocalVariable

        return variables.namedEntitySequence()
            .filter { isReceiverOrPassedThis(it.name) && it.type is ReferenceType? }
            .mapNotNull { findCapturedVariable(kind, it.value()) }
            .firstOrNull()
    }

    private fun findCapturedVariable(kind: VariableKind, parent: Value?): Result? {
        if (parent !is ObjectReference) return null

        if (kind is VariableKind.UnlabeledThis && kind.typeMatches(parent.type())) {
            return Result(parent)
        }

        val fields = parent.referenceType().fields()

        // Captured variables - direct search
        fields.namedEntitySequence(parent)
            .filter { kind.capturedNameMatches(it.name) && kind.typeMatches(it.type) }
            .firstOrNull()
            ?.let { return Result(it.value()) }

        // Recursive search in captured receivers
        fields.namedEntitySequence(parent)
            .filter { isCapturedReceiverFieldName(it.name) && it.type is ReferenceType? }
            .mapNotNull { findCapturedVariable(kind, it.value()) }
            .firstOrNull()
            ?.let { return it }

        // Recursive search in outer and captured this
        fields.namedEntitySequence(parent)
            .filter { it.name == AsmUtil.getCapturedFieldName(AsmUtil.THIS) || it.name == AsmUtil.CAPTURED_THIS_FIELD }
            .filter { it.type is ReferenceType? }
            .firstOrNull()
            ?.let { return findCapturedVariable(kind, it.value()) }

        return null
    }

    private fun isCapturedReceiverFieldName(name: String): Boolean {
        return name.startsWith(getCapturedFieldName(AsmUtil.LABELED_THIS_FIELD))
                || name == AsmUtil.CAPTURED_RECEIVER_FIELD
    }

    private fun variableNotFound(message: String): Exception {
        val location = frameProxy.location()
        val scope = context.debugProcess.searchScope

        val locationText = location?.run { "Location: ${sourceName()}:${lineNumber()}" } ?: "No location available"

        val sourceName = location?.sourceName()
        val declaringTypeName = location?.declaringType()?.name()?.replace('.', '/')?.let { JvmClassName.byInternalName(it) }

        val sourceFile = if (sourceName != null && declaringTypeName != null) {
            DebuggerUtils.findSourceFileForClassIncludeLibrarySources(context.project, scope, declaringTypeName, sourceName, location)
        } else {
            null
        }

        val sourceFileText = runReadAction { sourceFile?.text }

        if (sourceName != null && sourceFileText != null) {
            val attachments = mergeAttachments(
                Attachment(sourceName, sourceFileText),
                Attachment("location.txt", locationText)
            )

            LOG.error(message, attachments)
        }

        return EvaluateExceptionUtil.createEvaluateException(message)
    }

    private fun List<Field>.namedEntitySequence(owner: ObjectReference) = asSequence().map { NamedEntity.of(it, owner) }
    private fun List<LocalVariableProxyImpl>.namedEntitySequence() = asSequence().map { NamedEntity.of(it, frameProxy) }
}