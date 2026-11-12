package org.dmg.balletmaster.state

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.dmg.balletmaster.StepId

internal data class StateConfigDto(
    val operationName: String,
    val version: String,
    val rules: List<StateRuleDto>,
    val defaultState: JsonNode
)

internal data class StateRuleDto(
    val state: JsonNode,
    val condition: ConditionDto
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = StepDoneDto::class, name = "step"),
    JsonSubTypes.Type(value = AllOfDto::class, name = "allOf"),
    JsonSubTypes.Type(value = AnyOfDto::class, name = "anyOf"),
    JsonSubTypes.Type(value = NoneOfDto::class, name = "noneOf"),
    JsonSubTypes.Type(value = IsEmptyDto::class, name = "empty")
)
internal sealed interface ConditionDto

internal data class StepDoneDto(val step: JsonNode) : ConditionDto
internal data class AllOfDto(val conditions: List<ConditionDto>) : ConditionDto
internal data class AnyOfDto(val conditions: List<ConditionDto>) : ConditionDto
internal data class NoneOfDto(val conditions: List<ConditionDto>) : ConditionDto
internal class IsEmptyDto : ConditionDto {
    override fun equals(other: Any?): Boolean = other is IsEmptyDto
    override fun hashCode(): Int = IsEmptyDto::class.hashCode()
}

class StateConfigSerializer(val mapper: ObjectMapper = jacksonObjectMapper()) {
    fun <S : StepId, STATE : Any> serialize(config: StateConfig<S, STATE>): String =
        StateConfigDto(
            operationName = config.operationName,
            version = config.version,
            rules = config.rules.map { StateRuleDto(mapper.valueToTree(it.state), toConditionDto(it.condition)) },
            defaultState = mapper.valueToTree(config.defaultState)
        ).let(mapper::writeValueAsString)

    fun <S : StepId, STATE : Any> deserialize(
        json: String,
        stepClass: Class<S>,
        stateClass: Class<STATE>
    ): StateConfig<S, STATE> = mapper.readValue(json, StateConfigDto::class.java).let { dto ->
        val rawRules = dto.rules.map {
            StateRule(mapper.treeToValue(it.state, stateClass), fromConditionDto(it.condition, stepClass))
        }
        StateConfig(
            operationName = dto.operationName,
            version = dto.version,
            rules = resolveEmptyConditions(rawRules),
            defaultState = mapper.treeToValue(dto.defaultState, stateClass)
        )
    }

    private fun <S : StepId> toConditionDto(condition: Condition<S>): ConditionDto = when (condition) {
        is StepDone -> StepDoneDto(mapper.valueToTree(condition.step))
        is AllOf -> AllOfDto(condition.conditions.map(::toConditionDto))
        is AnyOf -> AnyOfDto(condition.conditions.map(::toConditionDto))
        is NoneOf -> NoneOfDto(condition.conditions.map(::toConditionDto))
        is IsEmpty -> IsEmptyDto()
    }

    private fun <S : StepId> fromConditionDto(dto: ConditionDto, stepClass: Class<S>): Condition<S> = when (dto) {
        is StepDoneDto -> StepDone(mapper.treeToValue(dto.step, stepClass))
        is AllOfDto -> AllOf(dto.conditions.map { fromConditionDto(it, stepClass) })
        is AnyOfDto -> AnyOf(dto.conditions.map { fromConditionDto(it, stepClass) })
        is NoneOfDto -> NoneOf(dto.conditions.map { fromConditionDto(it, stepClass) })
        is IsEmptyDto -> IsEmpty()
    }
}
