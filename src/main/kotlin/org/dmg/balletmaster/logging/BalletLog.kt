package org.dmg.balletmaster.logging

import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.Try
import org.dmg.balletmaster.context.CallContext
import org.dmg.balletmaster.store.Value
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Hierarchical SLF4J logger categories for Balletmaster subsystems.
 */
internal object BalletLoggers {
    const val ROOT = "org.dmg.balletmaster"
    const val STEP = "org.dmg.balletmaster.step"
    const val LOCK = "org.dmg.balletmaster.lock"
    const val REPLAY = "org.dmg.balletmaster.replay"
    const val STORE = "org.dmg.balletmaster.store"
    const val STATE = "org.dmg.balletmaster.state"

    val step: Logger = LoggerFactory.getLogger(STEP)
    val lock: Logger = LoggerFactory.getLogger(LOCK)
    val replay: Logger = LoggerFactory.getLogger(REPLAY)
    val store: Logger = LoggerFactory.getLogger(STORE)
    val state: Logger = LoggerFactory.getLogger(STATE)
}

/**
 * Internal bridge allowing components inside the SDK module to inspect correlation metadata
 * without altering the public [org.dmg.balletmaster.context.InternalContext] interface.
 */
internal interface LoggingContext {
    fun currentCall(): CallContext?
    fun currentSuffix(): Any
}

/**
 * Manages SLF4J MDC correlation attributes strictly within a step execution scope,
 * preserving and restoring any pre-existing host application MDC values.
 */
internal object BalletMdc {
    const val OPERATION = "ballet.operation"
    const val OPERATION_ID = "ballet.operationId"
    const val ATTEMPT = "ballet.attempt"
    const val SUFFIX = "ballet.suffix"
    const val STEP = "ballet.step"
    const val REPLAY = "ballet.replay"

    private val KEYS = arrayOf(OPERATION, OPERATION_ID, ATTEMPT, SUFFIX, STEP, REPLAY)

    inline fun <T> withStepContext(call: CallContext?, stepId: StepId, suffix: Any, block: () -> T): T {
        val previous = snapshot()
        applyStepContext(call, stepId, suffix)
        return try {
            block()
        } finally {
            restore(previous)
        }
    }

    @PublishedApi
    internal fun snapshot(): Array<String?>? = try {
        Array(KEYS.size) { index -> MDC.get(KEYS[index]) }
    } catch (_: Exception) {
        null
    }

    @PublishedApi
    internal fun applyStepContext(call: CallContext?, stepId: StepId, suffix: Any) {
        try {
            if (call != null) {
                MDC.put(OPERATION, safeToString(call.operationName))
                MDC.put(OPERATION_ID, safeToString(call.operationId))
                MDC.put(ATTEMPT, call.attempt.toString())
                MDC.put(SUFFIX, safeToString(suffix))
                MDC.put(REPLAY, call.isReplay.toString())
            } else {
                MDC.remove(OPERATION)
                MDC.remove(OPERATION_ID)
                MDC.remove(ATTEMPT)
                MDC.remove(SUFFIX)
                MDC.remove(REPLAY)
            }
            MDC.put(STEP, safeToString(stepId))
        } catch (_: Exception) {
        }
    }

    @PublishedApi
    internal fun restore(previous: Array<String?>?) {
        if (previous == null) return
        try {
            for (index in KEYS.indices) {
                val value = previous[index]
                if (value == null) MDC.remove(KEYS[index])
                else MDC.put(KEYS[index], value)
            }
        } catch (_: Exception) {
        }
    }

    fun updateReplay(isReplay: Boolean) {
        try {
            if (MDC.get(REPLAY) != null) {
                MDC.put(REPLAY, isReplay.toString())
            }
        } catch (_: Exception) {
        }
    }

    fun updateSuffix(suffix: Any) {
        try {
            if (MDC.get(SUFFIX) != null) {
                MDC.put(SUFFIX, safeToString(suffix))
            }
        } catch (_: Exception) {
        }
    }
}

internal fun safeToString(value: Any): String = try {
    value.toString()
} catch (e: Exception) {
    "<toString_error:${e.javaClass.simpleName}>"
}

/**
 * Builder for structured log attributes formatted cleanly without exposing user payloads.
 */
@PublishedApi
internal class LogAttrs {
    private val builder = StringBuilder()

    fun correlation(call: CallContext?, stepId: StepId? = null, suffix: Any? = null) {
        if (call != null) {
            attr("op", call.operationName)
            attr("id", call.operationId)
            attr("attempt", call.attempt)
            attr("suffix", suffix ?: call.operationSuffix)
        }
        if (stepId != null) {
            attr("step", stepId)
        }
    }

    fun attr(key: String, value: Any?) {
        if (value == null) return
        val rendered = safeToString(value)
        if (builder.isNotEmpty()) builder.append(' ')
        builder.append(key).append('=')
        appendFormatted(rendered)
    }

    fun format(event: String): String =
        if (builder.isEmpty()) "[$event]" else "[$event] $builder"

    private fun appendFormatted(raw: String) {
        val needsQuotes = raw.isEmpty() || raw.any {
            it.isWhitespace() || it.isISOControl() || it == '"' || it == '=' || it == '\\'
        }
        if (!needsQuotes) {
            builder.append(raw)
            return
        }
        builder.append('"')
        for (ch in raw) {
            when {
                ch == '\\' -> builder.append("\\\\")
                ch == '"' -> builder.append("\\\"")
                ch == '\n' -> builder.append("\\n")
                ch == '\r' -> builder.append("\\r")
                ch == '\t' -> builder.append("\\t")
                ch.isISOControl() || ch == '\u2028' || ch == '\u2029' -> appendUnicodeEscape(ch)
                else -> builder.append(ch)
            }
        }
        builder.append('"')
    }

    private fun appendUnicodeEscape(ch: Char) {
        builder.append("\\u")
        val hex = ch.code.toString(16)
        for (i in hex.length until 4) {
            builder.append('0')
        }
        builder.append(hex)
    }
}

@PublishedApi
internal inline fun buildEventMessage(event: String, attrs: LogAttrs.() -> Unit): String =
    LogAttrs().apply(attrs).format(event)

internal inline fun Logger.traceEvent(event: String, attrs: LogAttrs.() -> Unit = {}) {
    try {
        if (isTraceEnabled) {
            trace(buildEventMessage(event, attrs))
        }
    } catch (_: Exception) {
    }
}

internal inline fun Logger.debugEvent(event: String, attrs: LogAttrs.() -> Unit = {}) {
    try {
        if (isDebugEnabled) {
            debug(buildEventMessage(event, attrs))
        }
    } catch (_: Exception) {
    }
}

internal inline fun Logger.infoEvent(event: String, attrs: LogAttrs.() -> Unit = {}) {
    try {
        if (isInfoEnabled) {
            info(buildEventMessage(event, attrs))
        }
    } catch (_: Exception) {
    }
}

internal inline fun Logger.warnEvent(event: String, throwable: Throwable? = null, attrs: LogAttrs.() -> Unit = {}) {
    try {
        if (isWarnEnabled) {
            val msg = buildEventMessage(event, attrs)
            if (throwable != null) warn(msg, throwable) else warn(msg)
        }
    } catch (_: Exception) {
    }
}

internal inline fun Logger.errorEvent(event: String, throwable: Throwable? = null, attrs: LogAttrs.() -> Unit = {}) {
    try {
        if (isErrorEnabled) {
            val msg = buildEventMessage(event, attrs)
            if (throwable != null) error(msg, throwable) else error(msg)
        }
    } catch (_: Exception) {
    }
}

internal fun Try<*>.outcomeLabel(): String =
    fold({ "FAILURE" }, { "ERROR" }, { "STATE" }, { "SUCCESS" })

internal fun Try<*>.stateStatusOrNull(): Value? =
    fold({ null }, { null }, { it }, { null })

internal fun Logger.startDebugTimer(): Long? = try {
    if (isDebugEnabled) System.nanoTime() else null
} catch (_: Exception) {
    null
}

internal fun Logger.startWarnTimer(): Long? = try {
    if (isWarnEnabled) System.nanoTime() else null
} catch (_: Exception) {
    null
}

internal fun elapsedMillis(startNanos: Long?): Long? =
    startNanos?.let { (System.nanoTime() - it) / 1_000_000 }
