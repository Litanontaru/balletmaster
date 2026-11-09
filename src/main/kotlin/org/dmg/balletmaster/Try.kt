package org.dmg.balletmaster

import org.dmg.balletmaster.store.Value
import java.io.Serializable

class Try<out T>(private val value: Any?) : Serializable {
    val isSuccess: Boolean get() = (value !is Failure) && (value !is State)

    val isFailure: Boolean get() = value is Failure

    val isState: Boolean get() = value is State

    override fun toString(): String =
        when (value) {
            is Failure -> value.toString() // "Failure($exception)"
            is State -> value.toString() // "State($status)"
            else -> "Success($value)"
        }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Try<*> && value == other.value)

    override fun hashCode(): Int = value?.hashCode() ?: 0

    fun <R> fold(
        onFailure: (exception: Throwable) -> R,
        onState: (status: Value) -> R,
        onSuccess: (value: T) -> R
    ): R = when (value) {
        is Failure -> onFailure(value.exception)
        is State -> onState(value.status)
        else -> onSuccess(@Suppress("UNCHECKED_CAST") (value as T))
    }

    fun <R> then(onSuccess: (value: T) -> Try<R>): Try<R> = fold(Companion::failure, Companion::state, onSuccess)

    companion object {
        fun <T> success(value: T): Try<T> = Try(value)
        fun <T> failure(exception: Throwable): Try<T> = Try(Failure(exception))
        fun <T> state(status: Value): Try<T> = Try(State(status))
    }

    internal class Failure(val exception: Throwable) : Serializable {
        override fun equals(other: Any?): Boolean = other is Failure && exception == other.exception
        override fun hashCode(): Int = exception.hashCode()
        override fun toString(): String = "Failure($exception)"
    }

    internal class State(val status: Value) : Serializable {
        override fun equals(other: Any?): Boolean = other is State && status == other.status
        override fun hashCode(): Int = status.hashCode()
        override fun toString(): String = "State($status)"
    }
}

infix fun <T> Try<T>.also(action: (T) -> Unit): Try<T> = fold(Try.Companion::failure, Try.Companion::state) {
    action(it)
    Try.success(it)
}

infix fun <T, R> Try<T>.map(value: (T) -> Try<R>) = fold(Try.Companion::failure, Try.Companion::state) {
    value(it)
}

infix fun <T, R> Try<T>.and(value: (T) -> Result<R>): Try<R> = fold(Try.Companion::failure, Try.Companion::state) {
    value(it).fold(Try.Companion::success, Try.Companion::failure)
}
