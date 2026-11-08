package org.dmg.balletmaster

import java.io.Serializable

interface Value : Serializable {
    operator fun not() = Not(this)

    fun evaluate(another: Any): Boolean = this == another
}

class Not(val original: Any): Value {
    override fun evaluate(another: Any): Boolean =
        if (original is Value) !original.evaluate(another) else original != another

    override fun equals(other: Any?): Boolean = other is Not && original == other.original
    override fun hashCode(): Int = original.hashCode() * 31
    override fun toString(): String = "!$original"
}

class LessThen<T : Comparable<T>>(private val original: T) : Value {
    private val type = original.javaClass

    override fun evaluate(another: Any): Boolean = type.isInstance(another) && type.cast(another) < original
}

fun <T : Comparable<T>> lessThen(value: T) = LessThen(value)

enum class StepStatus : Value {
    DONE,
    EXCEPTION
}

enum class TechStatus : Value {
    SKIP,
    BLOCKED,
    NO_CALL,
    NO_OP,
    OLD_EXCEPTION
}