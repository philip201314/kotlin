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

open class UastFunctionKind(val text: String) {
    class UastInitializerKind(val name: String) : UastFunctionKind("INITIALIZER ($name)")
    class UastVariableAccessor(val name: String) : UastFunctionKind(name)

    companion object {
        @JvmField
        val FUNCTION = UastFunctionKind("function")

        @JvmField
        val CONSTRUCTOR = UastFunctionKind("constructor")

        @JvmField
        val GETTER = UastVariableAccessor("getter")

        @JvmField
        val SETTER = UastVariableAccessor("setter")
    }

    override fun toString(): String{
        return "UastFunctionKind(text='$text')"
    }
}