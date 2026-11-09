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
    fun <S : StepId, STATE : Any> serialize(config: StateConfig<S, STATE>): String {
        val rulesDto = config.rules.map { rule ->
            StateRuleDto(
                state = mapper.valueToTree(rule.state),
                condition = toConditionDto(rule.condition, mapper)
            )
        }
        val dto = StateConfigDto(
            operationName = config.operationName,
            version = config.version,
            rules = rulesDto,
            defaultState = mapper.valueToTree(config.defaultState)
        )
        return mapper.writeValueAsString(dto)
    }

    fun <S : StepId, STATE : Any> deserialize(
        json: String,
        stepClass: Class<S>,
        stateClass: Class<STATE>
    ): StateConfig<S, STATE> {
        val dto = mapper.readValue(json, StateConfigDto::class.java)
        val defaultState = mapper.treeToValue(dto.defaultState, stateClass)
        val rawRules = dto.rules.map { ruleDto ->
            val state = mapper.treeToValue(ruleDto.state, stateClass)
            val condition = fromConditionDto(ruleDto.condition, stepClass, mapper)
            StateRule(state, condition)
        }
        val allSteps = rawRules.flatMap { it.condition.referencedSteps }.toSet()
        val resolvedRules = rawRules.map { rule ->
            val resolvedCondition = if (rule.condition is IsEmpty) IsEmpty(allSteps) else rule.condition
            StateRule(rule.state, resolvedCondition)
        }
        return StateConfig(dto.operationName, dto.version, resolvedRules, defaultState)
    }

    private fun <S : StepId> toConditionDto(condition: Condition<S>, mapper: ObjectMapper): ConditionDto = when (condition) {
        is StepDone -> StepDoneDto(mapper.valueToTree(condition.step))
        is AllOf -> AllOfDto(condition.conditions.map { toConditionDto(it, mapper) })
        is AnyOf -> AnyOfDto(condition.conditions.map { toConditionDto(it, mapper) })
        is NoneOf -> NoneOfDto(condition.conditions.map { toConditionDto(it, mapper) })
        is IsEmpty -> IsEmptyDto()
    }

    private fun <S : StepId> fromConditionDto(dto: ConditionDto, stepClass: Class<S>, mapper: ObjectMapper): Condition<S> = when (dto) {
        is StepDoneDto -> StepDone(mapper.treeToValue(dto.step, stepClass))
        is AllOfDto -> AllOf(dto.conditions.map { fromConditionDto(it, stepClass, mapper) })
        is AnyOfDto -> AnyOf(dto.conditions.map { fromConditionDto(it, stepClass, mapper) })
        is NoneOfDto -> NoneOf(dto.conditions.map { fromConditionDto(it, stepClass, mapper) })
        is IsEmptyDto -> IsEmpty()
    }
}
