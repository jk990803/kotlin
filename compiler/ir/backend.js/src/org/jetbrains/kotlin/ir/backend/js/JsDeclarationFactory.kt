/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.js

import org.jetbrains.kotlin.backend.common.getOrPut
import org.jetbrains.kotlin.backend.common.ir.DeclarationFactory
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.ir.copyTypeParametersFrom
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.backend.js.JsMapping
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.backend.js.utils.Namer
import org.jetbrains.kotlin.ir.builders.declarations.buildConstructor
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.name.Name

class JsDeclarationFactory(mapping: JsMapping) : DeclarationFactory {
    private val singletonFieldDescriptors = mapping.singletonFieldDescriptors
    private val outerThisFieldSymbols = mapping.outerThisFieldSymbols
    private val innerClassConstructors = mapping.innerClassConstructors
    private val originalInnerClassPrimaryConstructorByClass = mapping.originalInnerClassPrimaryConstructorByClass

    override fun getFieldForEnumEntry(enumEntry: IrEnumEntry): IrField = TODO()

    override fun getOuterThisField(innerClass: IrClass): IrField =
        if (!innerClass.isInner) throw AssertionError("Class is not inner: ${innerClass.dump()}")
        else {
            outerThisFieldSymbols.getOrPut(innerClass) {
                val outerClass = innerClass.parent as? IrClass
                    ?: throw AssertionError("No containing class for inner class ${innerClass.dump()}")


                val name = Name.identifier("\$this")
                val fieldType = outerClass.defaultType
                val visibility = Visibilities.PROTECTED

                createPropertyWithBackingField(name, visibility, innerClass, fieldType, DeclarationFactory.FIELD_FOR_OUTER_THIS)
            }
        }

    private fun createPropertyWithBackingField(
        name: Name, visibility: Visibility, parent: IrClass, fieldType: IrType, origin: IrDeclarationOrigin
    ): IrField =
        buildField {
            this.origin = origin
            this.name = name
            this.type = fieldType
            this.visibility = visibility
            this.isFinal = true
            this.isExternal = false
            this.isStatic = false
        }.also {
            it.parent = parent
        }

    override fun getInnerClassConstructorWithOuterThisParameter(innerClassConstructor: IrConstructor): IrConstructor {
        val innerClass = innerClassConstructor.parent as IrClass
        assert(innerClass.isInner) { "Class is not inner: $innerClass" }

        return innerClassConstructors.getOrPut(innerClassConstructor) {
            createInnerClassConstructorWithOuterThisParameter(innerClassConstructor)
        }.also {
            if (innerClassConstructor.isPrimary) {
                originalInnerClassPrimaryConstructorByClass[innerClass] = innerClassConstructor
            }
        }
    }

    override fun getInnerClassOriginalPrimaryConstructorOrNull(innerClass: IrClass): IrConstructor? {
        assert(innerClass.isInner) { "Class is not inner: $innerClass" }

        return originalInnerClassPrimaryConstructorByClass[innerClass]
    }

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    private fun createInnerClassConstructorWithOuterThisParameter(oldConstructor: IrConstructor): IrConstructor {
        val irClass = oldConstructor.parent as IrClass
        val outerThisType = (irClass.parent as IrClass).defaultType

        val newConstructor = buildConstructor(oldConstructor.descriptor) {
            updateFrom(oldConstructor)
            returnType = oldConstructor.returnType
        }.also {
            it.parent = oldConstructor.parent
        }

        newConstructor.copyTypeParametersFrom(oldConstructor)

        val outerThisValueParameter = JsIrBuilder.buildValueParameter(newConstructor, Namer.OUTER_NAME, 0, outerThisType)

        val newValueParameters = mutableListOf(outerThisValueParameter)

        for (p in oldConstructor.valueParameters) {
            newValueParameters += p.copyTo(newConstructor, index = p.index + 1)
        }

        newConstructor.valueParameters += newValueParameters

        return newConstructor
    }

    override fun getFieldForObjectInstance(singleton: IrClass): IrField =
        singletonFieldDescriptors.getOrPut(singleton) {
            createObjectInstanceFieldDescriptor(singleton, JsIrBuilder.SYNTHESIZED_DECLARATION)
        }

    private fun createObjectInstanceFieldDescriptor(singleton: IrClass, origin: IrDeclarationOrigin): IrField {
        assert(singleton.kind == ClassKind.OBJECT) { "Should be an object: $singleton" }

        val name = Name.identifier("INSTANCE")

        return createPropertyWithBackingField(name, Visibilities.PUBLIC, singleton, singleton.defaultType, origin)
    }
}
