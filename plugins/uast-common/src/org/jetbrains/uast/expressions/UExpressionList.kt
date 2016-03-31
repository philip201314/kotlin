/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package org.jetbrains.uast

import org.jetbrains.uast.visitor.UastVisitor

interface UExpressionList : UExpression {
    val expressions: List<UExpression>

    override fun accept(visitor: UastVisitor) {
        if (visitor.visitExpressionList(this)) return
        expressions.acceptList(visitor)
    }

    override fun logString() = log("UExpressionList", expressions)
    override fun renderString() = log("", expressions)
}

interface USpecialExpressionList : UExpressionList {
    val kind: UastSpecialExpressionKind

    fun firstOrNull(): UExpression? = expressions.firstOrNull()

    override fun logString() = log("USpecialExpressionList (${kind.name})", expressions)
    override fun renderString() = kind.name + " " + expressions.joinToString(" : ") { it.renderString() }
}