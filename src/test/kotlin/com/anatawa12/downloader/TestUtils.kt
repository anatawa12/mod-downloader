package com.anatawa12.downloader

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object TestUtils {
    private val fieldModifiers: Field
    private val reflectionFactory: Any
    private val newFieldAccessor: Method
    private val fieldAccessorSet: Method

    init {
        fieldModifiers = Field::class.java.getDeclaredField("modifiers")
        fieldModifiers.isAccessible = true
        val getReflectionFactory: Method = Class.forName("sun.reflect.ReflectionFactory")
            .getDeclaredMethod("getReflectionFactory")
        reflectionFactory = getReflectionFactory.invoke(null)
        newFieldAccessor = Class.forName("sun.reflect.ReflectionFactory")
            .getDeclaredMethod("newFieldAccessor", Field::class.java, Boolean::class.javaPrimitiveType)
        fieldAccessorSet = Class.forName("sun.reflect.FieldAccessor")
            .getDeclaredMethod("set", Any::class.java, Any::class.java)
    }

    fun setStaticFinal(ofClass: Class<*>, name: String, value: Any?) {
        val field = ofClass.getDeclaredField(name)
        field.isAccessible = true
        fieldModifiers.setInt(field, field.modifiers and Modifier.FINAL.inv())
        val fieldAccessor: Any = newFieldAccessor.invoke(reflectionFactory, field, false)
        fieldAccessorSet.invoke(fieldAccessor, null, value)
        check(field.get(null) === value)
    }
}
