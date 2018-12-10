/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger

import com.intellij.debugger.engine.evaluation.AbsentInformationEvaluateException
import com.intellij.debugger.impl.DebuggerUtilsEx
import com.intellij.debugger.jdi.LocalVariableProxyImpl
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.sun.jdi.*

fun StackFrameProxyImpl.safeVisibleVariables(): List<LocalVariableProxyImpl> {
    return try {
        visibleVariables()
    } catch (e: AbsentInformationEvaluateException) {
        // Current implementation of visibleVariables() wraps an AbsentInformationException into EvaluateException
        emptyList()
    } catch (e: AbsentInformationException) {
        emptyList()
    }
}

fun Method.safeAllLineLocations(): List<Location> {
    return DebuggerUtilsEx.allLineLocations(this) ?: emptyList()
}

fun ReferenceType.safeAllLineLocations(): List<Location> {
    return DebuggerUtilsEx.allLineLocations(this) ?: emptyList()
}

fun Method.safeLocationsOfLine(line: Int): List<Location> {
    return try {
        locationsOfLine(line)
    } catch (e: AbsentInformationException) {
        emptyList()
    }
}

fun Method.safeVariables(): List<LocalVariable>? {
    return try {
        variables()
    } catch (e: AbsentInformationException) {
        null
    }
}

fun Method.safeArguments(): List<LocalVariable>? {
    return try {
        arguments()
    } catch (e: AbsentInformationException) {
        null
    }
}

fun Location.safeSourceName(): String? {
    return try {
        sourceName()
    } catch (e: AbsentInformationException) {
        null
    } catch (e: InternalError) {
        null
    }
}

fun Location.safeLineNumber(): Int {
    return DebuggerUtilsEx.getLineNumber(this, false)
}

fun Location.safeSourceLineNumber(): Int {
    return DebuggerUtilsEx.getLineNumber(this, true)
}

fun Location.safeMethod(): Method? {
    return DebuggerUtilsEx.getMethod(this)
}

fun LocalVariableProxyImpl.safeType(): Type? {
    return try {
        type
    } catch (e: ClassNotLoadedException) {
        null
    }
}

fun Field.safeType(): Type? {
    return try {
        type()
    } catch (e: ClassNotLoadedException) {
        null
    }
}