package org.dmg.balletmaster.context

import org.dmg.balletmaster.store.Value

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
