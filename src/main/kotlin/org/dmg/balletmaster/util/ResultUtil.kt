package org.dmg.balletmaster.util

import org.dmg.balletmaster.Try
import java.lang.NullPointerException

operator fun <T, R> Result<T>.plus(action: (T) -> Result<R>): Result<R> =
    fold(action, Result.Companion::failure)

infix fun <T, R> Result<T>.and(action: (T) -> Try<R>): Try<R> =
    fold(action, Try.Companion::error)

fun <T> Result<T?>.notNull(): Result<T> =
    fold({ it?.let(Result.Companion::success) ?: Result.failure(NullPointerException()) }, Result.Companion::failure)

infix fun <T, R> Result<T>.and(value: Try<R>): Try<R> =
    fold({ value }, Try.Companion::error)
