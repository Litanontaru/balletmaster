# Системное логирование Balletmaster SDK

## 1. Назначение и принципы

Системное логирование Balletmaster предназначено для диагностики исполнения шагов, конкурентных блокировок (lease/lock), переходов Replay -> Live и сбоев хранилища `Store` без вмешательства в бизнес-логику приложения и без обязательных внешних платформ наблюдаемости.

Ключевые принципы реализации:
* **Интеграция через SLF4J 2.0:** используется только `org.slf4j:slf4j-api` версии 2.0+ (`LoggerFactory`, `Logger`, `LoggingEventBuilder` / `addKeyValue`, `MDC`) без собственного JSON-форматирования. Приложение-хост само подключает и настраивает совместимый бэкенд (Logback 1.4+/1.5+, Log4j2 через `log4j-slf4j2-impl` и т.д.).
* **Минимизация утечек чувствительных данных:** SDK не записывает в логи содержимое пользовательских `input`/`output` и аргументы бизнес-лямбд; для бизнес-исключений (`step.exception`) фиксируется только имя класса исключения без `message` и stack trace (полное исключение возвращается приложению через `Try.failure`), а для не-`Enum` состояний в `state.resolved` логируется только имя класса без вызова `toString()`. При этом идентификаторы (`ballet.operation`, `ballet.operationId`, `ballet.suffix`), сформированные из них ключи `Store` (`key`) и инфраструктурные исключения `store.error` / `lock.wait_interrupted` передаются в логи как есть — приложение не должно помещать секреты или PII в идентификаторы операций, суффиксы и сообщения драйвера `Store`.
* **Минимальные накладные расходы:** построение атрибутов обёрнуто в `isTraceEnabled` / `isDebugEnabled` / `isInfoEnabled` / `isWarnEnabled` / `isErrorEnabled`, поэтому выключенные уровни не создают лишних аллокаций.
* **Тишина на нормальном трафике (`INFO`):** штатные операции на каждый шаг пишутся в `DEBUG` и `TRACE`. На `INFO` фиксируются только редкие значимые события смены режима/конфигурации (`replay.switched_to_live`, `state.config_saved`), на `WARN` — аномалии конкурентности, отсутствие контекста и непредвиденные сбои (бизнес-исключения шагов пишутся в `DEBUG` и возвращаются через `Try.failure`), на `ERROR` — инфраструктурные ошибки `Store`.

---

## 2. Категории логгеров

Все логгеры SDK сгруппированы в иерархию под корнем `org.dmg.balletmaster`:

| Категория | Назначение |
|---|---|
| `org.dmg.balletmaster` | Корневая категория SDK для глобального управления уровнем логирования |
| `org.dmg.balletmaster.step` | Жизненный цикл цепочки и шагов: старт, завершение, гейты, идемпотентность, `milestone`, `checkPoint`, бизнес-исключения (`StepExecutionException`), `NO_CALL` |
| `org.dmg.balletmaster.lock` | Захват и освобождение `:timeout`-аренды (`tryLock`/`unlock`), ожидание лока, перехват истекших аренд, `BLOCKED`, пропуск протухшего `unlock` |
| `org.dmg.balletmaster.replay` | Попадания и промахи кэша при `Replay`, переключение `Replay -> Live` |
| `org.dmg.balletmaster.store` | Системные ошибки чтения/записи [`Store`](../src/main/kotlin/org/dmg/balletmaster/store/Store.kt) (`Result.failure`) во всех компонентах SDK |
| `org.dmg.balletmaster.state` | Сохранение схем состояний и расчёт состояния процесса в `ProcessSupervisor` |

---

## 3. Структурированный формат (SLF4J 2.0 Fluent API) и корреляция (`MDC`)

### Передача событий через Fluent API
Каждое системное событие формируется через SLF4J 2.0 `LoggingEventBuilder` (`atTrace()`, `atDebug()`, `atInfo()`, `atWarn()`, `atError()`):

* в `message` события передаётся только идентификатор события (например, `step.completed`) без ручной конкатенации или JSON-сериализации;
* все атрибуты события, включая корреляционные поля, передаются через `addKeyValue(key, value)` — системные события не зависят от наличия MDC;
* числовые и булевы значения (`ballet.attempt`, `leaseMs`, `durationMs`, `ballet.replay` и др.) передаются нативными типами (`Int`, `Long`, `Boolean`), а строковые/объектные значения санитизируются от управляющих символов;
* при наличии исключения оно прикрепляется через `setCause(throwable)`.

### Единый словарь корреляционных атрибутов (Fluent API и MDC)
Названия корреляционных полей полностью унифицированы между `addKeyValue()` системных событий и `SLF4J MDC`:

| Ключ (`KeyValuePair` и `MDC`) | Значение |
|---|---|
| `ballet.operation` | Имя операции (`CallContext.operationName`) |
| `ballet.operationId` | Идентификатор экземпляра операции (`CallContext.operationId`) |
| `ballet.attempt` | Номер попытки (`CallContext.attempt`; `Int` в `KeyValuePair`, строка в `MDC`) |
| `ballet.suffix` | Суффикс текущего шага (`stepSuffix` в рамках шага; для `milestone` фиксирует старый суффикс до перехода к следующему шагу) |
| `ballet.step` | Идентификатор шага (`StepId`) |
| `ballet.replay` | Признак режима `Replay` (`Boolean` в `KeyValuePair`, строка в `MDC`) |

На время выполнения цепочки (`Chain.execute`) и каждого шага (`ContextImpl.execute`) SDK автоматически помещает эти ключи в `MDC` (для `chain.started` и `chain.completed` без `ballet.step`, для `chain.stopped` и внутри шага — с текущим `ballet.step`), чтобы системные события цепочки и логи прикладного кода внутри `.map { ... }`, `.gate { ... }` и `.milestone { ... }` автоматически получали корреляцию. Внешние ключи `MDC` приложения (например `traceId`, `requestId`) сохраняются, а прежние значения ключей `ballet.*` восстанавливаются в `finally` сразу по завершении шага и цепочки.

---

## 4. Справочник системных событий

### 4.1 `org.dmg.balletmaster.step`
| Событие | Уровень | Описание и основные атрибуты |
|---|---|---|
| `chain.started` | `DEBUG` | Старт выполнения непустой цепочки (`stepsCount`, `steps`) |
| `chain.completed` | `DEBUG` | Успешное завершение всей цепочки (`stepsCount`, `durationMs`) |
| `chain.stopped` | `DEBUG` | Досрочная остановка цепочки из-за неуспеха шага (`executedSteps`, `totalSteps`, `outcome`, `status`, `durationMs`) |
| `step.started` | `DEBUG` | Вход в защищённое исполнение шага после захвата лока (`leaseMs`, `ballet.replay`) |
| `step.completed` | `DEBUG` | Завершение шага с итогом и длительностью (`outcome`, `status`, `durationMs`) |
| `step.skipped_gate` | `DEBUG` | Предикат `gate` вернул `false` (`status=SKIP`) |
| `step.skipped_idempotent` | `DEBUG` | Шаг уже зафиксирован, `idempotencyGate` вернул `false` (`status=SKIP`) |
| `step.milestone_updated` | `DEBUG` | `milestone` обновил `operationSuffix` (`ballet.suffix`, `newSuffix`) |
| `step.checkpoint_saved` | `DEBUG` | `checkPoint` сохранил `Success` или `Failure` в `Store` (`outcome`) |
| `step.checkpoint_bypassed` | `TRACE` | `checkPoint` пропустил запись для `State` или `Error` (`outcome`, `status`) |
| `step.exception` | `DEBUG` / `WARN` | Бизнес-исключение внутри `gate`/`map`/`milestone` (`DEBUG`) или непредвиденный fallback-перехват в `ContextImpl.execute` (`WARN`); логируется только класс исключения без `message`/stack trace (`phase`, `exception`) |
| `step.no_call` | `WARN` | Шаг вызван без активного `CallContext` в `CallContextManager` (`ballet.step`, `status=NO_CALL`) |

### 4.2 `org.dmg.balletmaster.lock`
| Событие | Уровень | Описание и основные атрибуты |
|---|---|---|
| `lock.acquired` | `TRACE` | Захвачен новый `:timeout`-лок (`leaseMs`, `leaseUntil`, `waitMs`, `attempts`) |
| `lock.contention_wait` | `TRACE` | Очередная пауза ожидания занятого лока (`attemptNo`, `elapsedMs`) |
| `lock.released` | `TRACE` | Лок успешно снят владельцем (`leaseUntil`) |
| `lock.stolen_expired` | `WARN` | Захвачен истекший лок предыдущего/зависшего воркера (`leaseMs`, `leaseUntil`, `waitMs`, `attempts`) |
| `lock.blocked` | `DEBUG` | Лок не получен до истечения `lease` (`status=BLOCKED`, `leaseMs`, `waitMs`, `attempts`, `interrupted`) |
| `lock.wait_interrupted` | `WARN` | Поток ожидания лока был прерван (`attempts`, `waitMs`, `InterruptedException`) |
| `lock.stale_unlock_skipped` | `WARN` | Поздний `unlock` протухшего владельца отклонён CAS-проверкой (`expectedLeaseUntil`) |

### 4.3 `org.dmg.balletmaster.replay`
| Событие | Уровень | Описание и основные атрибуты |
|---|---|---|
| `replay.cache_hit` | `DEBUG` | Результат шага восстановлен из предыдущего `attempt` (`outcome`) |
| `replay.cache_miss` | `DEBUG` | Сохранённого результата в `attempt - 1` нет, переход к живому исполнению шага |
| `replay.switched_to_live` | `INFO` | Выключен режим `Replay`, дальнейшее исполнение идёт как Live |

### 4.4 `org.dmg.balletmaster.store`
| Событие | Уровень | Описание и основные атрибуты |
|---|---|---|
| `store.error` | `ERROR` | Ошибка хранилища `Store` (`Result.failure`) с указанием операции (`storeOp`: `lock_acquire`, `lock_steal`, `lock_unlock`, `set_input`, `idempotency_gate`, `set_output`, `original_output`, `is_done`, `state_config_save`, `state_versions_save`, `state_config_get`, `state_versions_get`, `supervisor_step_check`), ключа `key` и причины |

### 4.5 `org.dmg.balletmaster.state`
| Событие | Уровень | Описание и основные атрибуты |
|---|---|---|
| `state.config_saved` | `INFO` | Зарегистрирована конфигурация состояний процесса (`ballet.operation`, `version`, `rulesCount`) |
| `state.resolved` | `DEBUG` | Вычислено текущее состояние процесса в `ProcessSupervisor` (`ballet.operation`, `ballet.operationId`, `ballet.attempt`, `ballet.suffix`, `version`, `versionPrefix`, `state` — `Enum.name` или имя класса) |
| `state.config_not_found` | `WARN` | Конфигурация состояний не найдена (`ballet.operation`, `versionPrefix`) |

---

## 5. Примеры интеграции и требования к logging backend

### Минимальные требования к logging backend приложения
Поскольку Balletmaster передаёт атрибуты системных событий через `addKeyValue()` из SLF4J 2.0, приложение должно обеспечить:
1. **`org.slf4j:slf4j-api` >= 2.0.x** в runtime (старые биндинги SLF4J 1.7.x не поддерживают Fluent API и `KeyValuePair`).
2. **SLF4J 2.0-совместимый провайдер и layout/encoder, выводящий key-value пары:**
   * **Logback >= 1.3 / 1.4+** (рекомендуется `1.5.x`): в текстовом `PatternLayout` обязательно добавьте `%kvp`, либо используйте JSON-энкодер с поддержкой `keyValuePairs` (например, встроенный `ch.qos.logback.classic.encoder.JsonEncoder` или `logstash-logback-encoder`).
   * **Log4j 2** (через `org.apache.logging.log4j:log4j-slf4j2-impl`): используйте layout, включающий key-value пары (например, JSON Template Layout / `%K` для structured key-values).
3. Если в текстовом `<pattern>` оставить только `%msg` без `%kvp` (и не использовать JSON-энкодер), в лог попадёт только имя события без его атрибутов.

### Logback (`logback.xml`)
Рекомендуемая базовая конфигурация для production (с `%kvp` для системных событий и `MDC` для логов прикладного кода):

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{ISO8601} %-5level [%thread] %logger{36} %msg %kvp [op=%X{ballet.operation:-} id=%X{ballet.operationId:-} step=%X{ballet.step:-}]%n</pattern>
        </encoder>
    </appender>

    <!-- Базовый уровень SDK для production -->
    <logger name="org.dmg.balletmaster" level="INFO"/>

    <!-- При расследовании инцидентов можно точечно включить подсистемы: -->
    <!-- <logger name="org.dmg.balletmaster.step" level="DEBUG"/> -->
    <!-- <logger name="org.dmg.balletmaster.replay" level="DEBUG"/> -->
    <!-- <logger name="org.dmg.balletmaster.lock" level="TRACE"/> -->

    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

### Spring Boot (`application.yml`)
```yaml
logging:
  level:
    org.dmg.balletmaster: INFO
    # Для детальной отладки шагов и Replay:
    # org.dmg.balletmaster.step: DEBUG
    # org.dmg.balletmaster.replay: DEBUG
    # org.dmg.balletmaster.lock: TRACE
```

---

## 6. Известные ограничения и дальнейшее развитие

1. **Поточность MDC:** SLF4J `MDC` хранит контекст в `ThreadLocal`. Если прикладной код внутри `.map { ... }` уходит в пулы потоков или корутины без переноса `MDC`/`CallContext`, дочерние потоки не увидят `ballet.*` автоматически (связано с общей задачей переноса контекста при async execution).
2. **Операции над `StateConfigRepository` вне `Step`:** `state.config_saved` и `store.error` при работе с конфигом пишутся с атрибутом `ballet.operation` в `KeyValuePair`, но без `MDC`-контекста конкретного шага.
3. **Расширение в сторону OpenTelemetry и Execution Investigation:** все события централизованы в [`BalletLog.kt`](../src/main/kotlin/org/dmg/balletmaster/logging/BalletLog.kt) через единые `event`-идентификаторы, типовые атрибуты и `BalletMdc`. В будущем в этой же точке можно без ломки вызовов подключить:
   * мост к OpenTelemetry (`Span.current().addEvent(...)` / span-атрибуты `ballet.*`);
   * публикацию структурированных событий в будущий Execution Journal или аудит-приёмник.
