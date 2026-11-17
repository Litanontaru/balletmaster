# Technical Design Note: Investigation Engine и самостоятельный Investigation Service

> **Статус:** Draft (Шаг 1 для Контура D — Investigation по [`balletmaster-observability-design-v0.3.md`](balletmaster-observability-design-v0.3.md))  
> **Архитектурный стиль:** Ports & Adapters (Hexagonal) + самостоятельный ops-микросервис на Spring Boot  
> **Область первого релиза:** синхронный REST API расследования по `operationId` (без Alertmanager webhooks и без прямого доступа к `Store` бизнес-сервисов)

---

## 1. Архитектурное решение и модульные границы

Поскольку `Investigation` появляется как **второй потребитель** нормализованных наблюдений (после `loki-query`), фиксируем разделение на четыре независимых модуля Maven:

```text
                ┌─────────────────────────┐
                │   observation-model     │  (только kotlin-stdlib)
                │  BalletObservation      │
                │  ObservationBatch       │
                │  OperationObservationSource
                └───────────▲─────────────┘
                            │
           ┌────────────────┴────────────────┐
           │                                 │
┌──────────┴──────────┐           ┌──────────┴───────────┐
│     loki-query      │           │ investigation-engine │  (только observation-model)
│ LogQL, HTTP, Parser │           │ ObservationAnalyzer  │
│ LokiObservationSrc  │           │ InvestigationReport  │
└──────────▲──────────┘           └──────────▲───────────┘
           │                                 │
           └──────────────┬──────────────────┘
                          │ DI, конфиг, REST API
                ┌─────────┴──────────────┐
                │  investigation-service │  (Spring Boot 3.5.16)
                └────────────────────────┘
```

### Ответственность каждого модуля

1. **`observation-model`** (`org.example:observation-model`):
   - **Зависимости:** только `kotlin-stdlib` (никакого Spring, Jackson, Loki или HTTP).
   - **Содержимое:**
     - `BalletCorrelation` (`operation`, `operationId`, `attempt`, `suffix`, `step`, `replay`);
     - `Completeness` (`COMPLETE`, `TRUNCATED`, `PARTIAL_PARSE_ERRORS`);
     - `ObservationScope` (`SDK_ONLY`, `ALL_CORRELATED`);
     - `BalletObservation` (нормализованное наблюдение с `timestampNs`, `timestamp`, `provider`, `originLabels`, `rawLine`, `logger`, `level`, `event`, `correlation`, `attributes`, `parseError`);
     - `ObservationBatch` (результат выборки + `completeness`, `truncationReason`, `pagesFetched`, `totalRawEntries`, `duplicatesDropped`, `parseFailures`, `warnings`, `provider`, `sourceQuery`);
     - `OperationQuery` (провайдеро-независимый запрос по `app`, `environment`, `operationId`, `start`, `end`, `scope`, опциональным `operation`/`step`/`attempt`);
     - Порт **`OperationObservationSource`**: `val providerName: String`, `fun fetchByOperation(query: OperationQuery): ObservationBatch`;
     - Исключение **`ObservationSourceException`** (типизированная ошибка недоступности/отказа источника без привязки к HTTP-кодам Loki).
   - **Что НЕ выносится в `observation-model`:** языки запросов (`LogQL`, `KQL`, CloudWatch Insights) и специфика пагинации/транспорта конкретного провайдера.

2. **`loki-query`** (`org.example:loki-query`):
   - Подключает `observation-model`, использует его общие `BalletCorrelation` и `Completeness`.
   - Предоставляет адаптер **`LokiOperationObservationSource : OperationObservationSource`**, делегирующий в `LogQlQueryBuilder` + `LokiQueryClient` и маппящий `LokiQueryResult` → `ObservationBatch`, а `LokiQueryException` → `ObservationSourceException`.
   - Существующие `LokiQueryClient` и `LokiQueryCli` сохраняют работоспособность.

3. **`investigation-engine`** (`org.example:investigation-engine`):
   - **Зависимости:** только `observation-model` (чистая JVM-библиотека, без Spring и без Loki).
   - **Компоненты:**
     - `ObservationAnalyzer` — чистая детерминированная функция `analyze(query: OperationQuery, batch: ObservationBatch): InvestigationReport`;
     - `OperationInvestigator(source: OperationObservationSource, analyzer: ObservationAnalyzer)` — оркестратор `investigate(query: OperationQuery): InvestigationReport`.

4. **`investigation-service`** (`org.example:investigation-service`):
   - **Стек:** **Spring Boot `3.5.16`** (совместим с нашим Jackson 2 `com.fasterxml.jackson.*` `2.22.4` и Java 21 без конфликта с Jackson 3 из Boot 4) + `spring-boot-starter-web` + `spring-boot-starter-actuator`.
   - **Безопасность зависимостей:** корневой `pom.xml` закрепляет `logback:1.6.7`, `jackson:2.22.4` и `kotlin:2.4.21` приоритетнее импортируемого Spring Boot BOM.
   - **Конфигурируемость провайдера:** бин `OperationObservationSource` подключается через конфиг (`balletmaster.investigation.provider=loki`, `balletmaster.investigation.loki.*`). В будущем добавление CloudWatch / OpenSearch сводится к подключению нового адаптера без правок в `investigation-engine` и REST-контроллере.

---

## 2. Правила диагностики `investigation-engine`

Анализатор работает на реально доступных на `INFO`/`WARN`/`ERROR` событиях `core` (и корректно переваривает `DEBUG`-события, если они включены на хосте):

| Наблюдаемые события | `FindingType` | `Severity` | `Confidence` | Смысл |
|---|---|---|---|---|
| `step.exception` (`INFO`, `gate`/`map`/`milestone`) и/или `chain.stopped(outcome=FAILURE)` | `BUSINESS_STEP_FAILURE` | `HIGH` | `HIGH` | Бизнес-исключение внутри шага остановило цепочку (`phase`, `exception`, `executedSteps/totalSteps`) |
| `step.exception` (`WARN`, `phase=execute`) | `UNEXPECTED_STEP_EXCEPTION` | `CRITICAL` | `HIGH` | Непредвиденный fallback-перехват исключения в `ContextImpl.execute` |
| `store.error` (`ERROR`) и/или `chain.stopped(outcome=ERROR)` | `STORE_INFRASTRUCTURE_ERROR` | `CRITICAL` | `HIGH` | Технический сбой хранилища `Store` (`storeOp`, `key`) |
| `lock.stolen_expired` (`WARN`) + опционально `lock.stale_unlock_skipped` (`WARN`) | `LEASE_EXPIRY_RACE` | `HIGH` | `HIGH` (оба события) / `MEDIUM` (одно) | Просрочка аренды lease и перехват лока конкурирующим воркером |
| `lock.blocked` (`INFO`) и/или `chain.stopped(status=BLOCKED)` | `LOCK_CONTENTION_BLOCKED` | `MEDIUM` | `HIGH` | Шаг не получил лок за время `leaseMs` из-за конкуренции |
| `lock.wait_interrupted` (`WARN`) | `LOCK_WAIT_INTERRUPTED` | `MEDIUM` | `HIGH` | Прерывание потока во время ожидания лока |
| `replay.switched_to_live` (`INFO`) | `REPLAY_SWITCHED_TO_LIVE` | `INFO` | `HIGH` | Replay дошёл до несохранённого шага и переключился в Live |
| Записи с `parseError != null` | `UNPARSED_OBSERVATIONS` | `MEDIUM` | `LOW` | Часть логов в выборке не удалось разобрать как JSON |
| `observations.isEmpty()` | `NO_EVENTS_INCONCLUSIVE` | `INFO` | `LOW` | События не найдены; на `INFO` это не доказывает ни успех, ни `SKIP` |

### Итоговый статус отчёта (`InvestigationStatus`)
- `INFRASTRUCTURE_ERROR` — обнаружены ошибки `Store` или неожиданный fallback-сбой;
- `LEASE_RACE_DETECTED` — обнаружен перехват просроченного лока / `stale_unlock_skipped`;
- `FAILURE_DETECTED` — бизнес-падение шага (`BUSINESS_STEP_FAILURE`);
- `CONTENTION_DETECTED` — только блокировки/прерывания лока без просрочки lease;
- `MIXED_ANOMALIES` — одновременно присутствуют аномалии нескольких классов (например, `STORE_INFRASTRUCTURE_ERROR` + `LEASE_EXPIRY_RACE`);
- `INCONCLUSIVE_NO_EVENTS` — событий нет;
- `OBSERVATIONS_ONLY` — есть только информационные наблюдения (например, `replay.switched_to_live` или `DEBUG`-трассировка без ошибок).

### Обязательные ограничения (`limitations`)
В каждый `InvestigationReport` включаются явные ограничения достоверности:
1. Логи являются наблюдениями и не заменяют авторитетную проверку состояния в `Store`.
2. При пустой выборке на `INFO` невозможно отличить штатный успех/`SKIP` от потери/задержки доставки логов.
3. Сортировка по `timestamp` не гарантирует строгий причинно-следственный порядок между разными нодами (clock skew).
4. При `completeness == TRUNCATED` часть событий осталась за пределами бюджета запроса (указывается `truncationReason`).
5. Без `executionId` параллельные запуски одной `(operationId, attempt, step)` не разделяются средствами SDK.

---

## 3. Синхронный REST API `investigation-service` (v1)

### `GET /api/v1/investigations`

**Параметры запроса:**
- `app` *(обязательный)* — имя приложения (stream label);
- `env` *(обязательный)* — окружение (`prod`, `staging`, `dev`);
- `operationId` *(обязательный)* — идентификатор экземпляра операции;
- `windowMinutes` *(опциональный, по умолчанию `60`)* — окно назад от текущего времени, если не заданы `from`/`to`;
- `from`, `to` *(опциональные ISO-8601 `Instant`)* — явный временной диапазон (если указаны, используются вместо `windowMinutes`);
- `scope` *(опциональный, по умолчанию `SDK_ONLY`)* — `SDK_ONLY` или `ALL_CORRELATED`;
- `operation`, `step`, `attempt` *(опциональные фильтры)*.

**Коды ответов:**
- `200 OK` — JSON `InvestigationReport`;
- `400 Bad Request` — невалидные параметры (`from > to`, превышение `maxWindow`, пустой `operationId`) в формате `{ "error": "INVALID_REQUEST", "message": "..." }`;
- `502 Bad Gateway` — ошибка или таймаут бэкенда логов (`ObservationSourceException`) без утечки секретов/токенов в ответ.

**Служебные endpoints:**
- `GET /actuator/health` (liveness / readiness для Kubernetes).

---

## 4. План поэтапной реализации (Шаг 2)

Чтобы каждый diff оставался небольшим и легко проверяемым, делим реализацию на 2 последовательных этапа:

1. **Этап A (модель + движок, без Spring):**
   - Создать `observation-model`;
   - Адаптировать `loki-query` к `observation-model` (`LokiOperationObservationSource`);
   - Создать `investigation-engine` (`ObservationAnalyzer`, `OperationInvestigator`) и покрыть юнит-тестами все классы `Finding` + сквозной тест `BalletMaster → Logback JSON → LokiOperationObservationSource → OperationInvestigator`.
2. **Этап B (самостоятельный Spring Boot микросервис):**
   - Создать `investigation-service` на Spring Boot `3.5.16` (`InvestigationController`, `InvestigationProperties`, конфигурация провайдера `loki`, `GlobalExceptionHandler`, `application.yml`);
   - Добавить интеграционные тесты (`@SpringBootTest` + `MockMvc` с фейковым `OperationObservationSource` и end-to-end тест против локального `HttpServer`).
