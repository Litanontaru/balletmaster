package org.dmg.balletmaster

import org.dmg.balletmaster.store.Value
import java.io.Serializable

class Try<out T>(private val value: Any?) : Serializable {
    val isSuccess: Boolean get() = (value !is Failure) && (value !is Error) && (value !is State)

    val isFailure: Boolean get() = value is Failure

    val isError: Boolean get() = value is Error

    val isState: Boolean get() = value is State

    override fun toString(): String =
        when (value) {
            is Failure -> value.toString() // "Failure($exception)"
            is Error -> value.toString() // "Error($exception)"
            is State -> value.toString() // "State($status)"
            else -> "Success($value)"
        }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Try<*> && value == other.value)

    override fun hashCode(): Int = value?.hashCode() ?: 0

    fun <R> fold(
        onFailure: (exception: Throwable) -> R,
        onError: (exception: Throwable) -> R,
        onState: (status: Value) -> R,
        onSuccess: (value: T) -> R
    ): R = when (value) {
        is Failure -> onFailure(value.exception)
        is Error -> onError(value.exception)
        is State -> onState(value.status)
        else -> onSuccess(@Suppress("UNCHECKED_CAST") (value as T))
    }

    fun <R> then(onSuccess: (value: T) -> Try<R>): Try<R> =
        fold(Companion::failure, Companion::error, Companion::state, onSuccess)

    companion object {
        fun <T> success(value: T): Try<T> = Try(value)
        fun <T> failure(exception: Throwable): Try<T> = Try(Failure(exception))
        fun <T> error(exception: Throwable): Try<T> = Try(Error(exception))
        fun <T> state(status: Value): Try<T> = Try(State(status))
    }

    internal class Failure(val exception: Throwable) : Serializable {
        override fun equals(other: Any?): Boolean = other is Failure && exception == other.exception
        override fun hashCode(): Int = exception.hashCode()
        override fun toString(): String = "Failure($exception)"
    }

    internal class Error(val exception: Throwable) : Serializable {
        override fun equals(other: Any?): Boolean = other is Error && exception == other.exception
        override fun hashCode(): Int = exception.hashCode()
        override fun toString(): String = "Error($exception)"
    }

    internal class State(val status: Value) : Serializable {
        override fun equals(other: Any?): Boolean = other is State && status == other.status
        override fun hashCode(): Int = status.hashCode()
        override fun toString(): String = "State($status)"
    }
}

infix fun <T> Try<T>.also(action: (T) -> Unit): Try<T> = then {
    action(it)
    Try.success(it)
}

infix fun <T, R> Try<T>.map(value: (T) -> Try<R>): Try<R> = then(value)

infix fun <T, R> Try<T>.and(value: (T) -> Result<R>): Try<R> = then {
    value(it).fold(Try.Companion::success, Try.Companion::error)
}
