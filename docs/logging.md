# Системное логирование Balletmaster SDK

## 1. Назначение и принципы

Системное логирование Balletmaster предназначено для диагностики исполнения шагов, конкурентных блокировок (lease/lock), переходов Replay -> Live и сбоев хранилища `Store` без вмешательства в бизнес-логику приложения и без обязательных внешних платформ наблюдаемости.

Ключевые принципы реализации:
* **Интеграция через SLF4J:** используется только `org.slf4j:slf4j-api` (`LoggerFactory`, `Logger`, `MDC`). Приложение-хост само подключает и настраивает любой совместимый бэкенд (Logback, Log4j2 и т.д.).
* **Нулевые потери безопасности (PII-safe):** по умолчанию SDK никогда не записывает в логи содержимое пользовательских `input`/`output`, аргументы бизнес-лямбд или секреты — логируются только метаданные исполнения, идентификаторы корреляции, статусы, длительность и исключения.
* **Минимальные накладные расходы:** построение атрибутов обёрнуто в `isTraceEnabled` / `isDebugEnabled` / `isInfoEnabled` / `isWarnEnabled` / `isErrorEnabled`, поэтому выключенные уровни не создают лишних аллокаций.
* **Тишина на нормальном трафике (`INFO`):** штатные операции на каждый шаг пишутся в `DEBUG` и `TRACE`. На `INFO` фиксируются только редкие значимые события смены режима/конфигурации (`replay.switched_to_live`, `state.config_saved`), на `WARN` — аномалии конкурентности и ошибки бизнес-кода шага, на `ERROR` — инфраструктурные ошибки `Store`.

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

## 3. Формат сообщений и корреляция (`MDC`)

### Формат строки лога
Каждое событие форматируется единообразно (в `logfmt`-подобном виде), что упрощает парсинг в Kibana / Grafana Loki / ELK и чтение человеком:

```text
[<event>] key1=value1 key2=value2 ...
```

Пример:
```text
[step.completed] op=orderFlow id=ord-100 attempt=0 suffix="" step=CHARGE_CARD outcome=SUCCESS durationMs=14
```

### Стандартные атрибуты корреляции в сообщениях
* `op` — имя операции (`CallContext.operationName`);
* `id` — идентификатор экземпляра операции (`CallContext.operationId`);
* `attempt` — номер попытки (`CallContext.attempt`);
* `suffix` — суффикс шага / витка спирали (`stepSuffix` / `operationSuffix`);
* `step` — идентификатор шага (`StepId`).

### Ключи SLF4J MDC
На время выполнения шага в `ContextImpl.execute` SDK автоматически устанавливает следующие ключи `MDC`:

| Ключ MDC | Значение |
|---|---|
| `ballet.operation` | `CallContext.operationName` |
| `ballet.operationId` | `CallContext.operationId` |
| `ballet.attempt` | `CallContext.attempt` |
| `ballet.suffix` | Текущий `suffix` шага (обновляется при `milestone`) |
| `ballet.step` | Текущий `StepId` |
| `ballet.replay` | Признак режима `Replay` (`true`/`false`, обновляется при переходе в Live) |

Все логи самого прикладного кода внутри `.map { ... }`, `.gate { ... }` и `.milestone { ... }` автоматически получают эти поля из `MDC`. Внешние ключи `MDC` приложения (например `traceId`, `requestId`) не затрагиваются, а прежние значения ключей `ballet.*` аккуратно восстанавливаются в `finally` сразу по завершении шага.

---

## 4. Справочник системных событий

### 4.1 `org.dmg.balletmaster.step`
| Событие | Уровень | Описание и основные атрибуты |
|---|---|---|
| `chain.started` | `DEBUG` | Старт выполнения непустой цепочки (`stepsCount`, `steps`) |
| `chain.completed` | `DEBUG` | Успешное завершение всей цепочки (`stepsCount`, `durationMs`) |
| `chain.stopped` | `DEBUG` | Досрочная остановка цепочки из-за неуспеха шага (`executedSteps`, `totalSteps`, `outcome`, `status`, `durationMs`) |
| `step.started` | `DEBUG` | Вход в защищённое исполнение шага после захвата лока (`leaseMs`, `replay`) |
| `step.completed` | `DEBUG` | Завершение шага с итогом и длительностью (`outcome`, `status`, `durationMs`) |
| `step.skipped_gate` | `DEBUG` | Предикат `gate` вернул `false` (`status=SKIP`) |
| `step.skipped_idempotent` | `DEBUG` | Шаг уже зафиксирован, `idempotencyGate` вернул `false` (`status=SKIP`) |
| `step.milestone_updated` | `DEBUG` | `milestone` обновил `operationSuffix` (`suffix`, `newSuffix`) |
| `step.checkpoint_saved` | `DEBUG` | `checkPoint` сохранил `Success` или `Failure` в `Store` (`outcome`) |
| `step.checkpoint_bypassed` | `TRACE` | `checkPoint` пропустил запись для `State` или `Error` (`outcome`, `status`) |
| `step.exception` | `WARN` | Бизнес-исключение внутри `gate`/`map`/`milestone`/`execute` завёрнуто в `StepExecutionException` (`phase`, `exception`, stack trace) |
| `step.no_call` | `WARN` | Шаг вызван без активного `CallContext` в `CallContextManager` (`step`, `status=NO_CALL`) |

### 4.2 `org.dmg.balletmaster.lock`
| Событие | Уровень | Описание и основные атрибуты |
|---|---|---|
| `lock.acquired` | `TRACE` | Захвачен новый `:timeout`-лок (`leaseMs`, `leaseUntil`, `waitMs`, `attempts`) |
| `lock.contention_wait` | `TRACE` | Очередная пауза ожидания занятого лока (`attemptNo`, `elapsedMs`) |
| `lock.released` | `TRACE` | Лок успешно снят владельцем (`leaseUntil`) |
| `lock.stolen_expired` | `WARN` | Захвачен истекший лок предыдущего/зависшего воркера (`leaseMs`, `leaseUntil`, `waitMs`, `attempts`) |
| `lock.blocked` | `WARN` | Лок не получен до истечения `lease` (`status=BLOCKED`, `leaseMs`, `waitMs`, `attempts`, `interrupted`) |
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
| `state.config_saved` | `INFO` | Зарегистрирована конфигурация состояний процесса (`op`, `version`, `rulesCount`) |
| `state.resolved` | `DEBUG` | Вычислено текущее состояние процесса в `ProcessSupervisor` (`op`, `id`, `attempt`, `suffix`, `version`, `versionPrefix`, `state`) |
| `state.config_not_found` | `WARN` | Конфигурация состояний не найдена (`op`, `versionPrefix`) |

---

## 5. Примеры интеграции и настройки

### Logback (`logback.xml`)
Рекомендуемая базовая конфигурация для production (с расширенной диагностикой блокировок и Replay при необходимости):

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{ISO8601} %-5level [%thread] %logger{36} [op=%X{ballet.operation:-} id=%X{ballet.operationId:-} step=%X{ballet.step:-}] - %msg%n</pattern>
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
2. **Операции над `StateConfigRepository` вне `Step`:** `state.config_saved` и `store.error` при работе с конфигом пишутся с атрибутом `op`, но без `MDC`-контекста конкретного шага.
3. **Расширение в сторону OpenTelemetry и Execution Investigation:** все события централизованы в [`BalletLog.kt`](../src/main/kotlin/org/dmg/balletmaster/logging/BalletLog.kt) через единые `event`-идентификаторы, типовые атрибуты и `BalletMdc`. В будущем в этой же точке можно без ломки вызовов подключить:
   * мост к OpenTelemetry (`Span.current().addEvent(...)` / span-атрибуты `ballet.*`);
   * публикацию структурированных событий в будущий Execution Journal или аудит-приёмник.
