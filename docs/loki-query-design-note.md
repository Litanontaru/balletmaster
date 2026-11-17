# Technical Design Note (Шаг 1): Historical Query через Loki

> **Статус:** Draft (Шаг 1 по [`balletmaster-observability-design-v0.3.md`](balletmaster-observability-design-v0.3.md))  
> **Контрольный срез:** Кандидат A — Historical Query через Loki  
> **Связь с `core`:** только чтение уже существующих событий `core` (никаких изменений в алгоритмах `Step`/`ContextImpl`/`Store`)

---

## 1. Размещение и границы (ответ на вопрос про модуль)

- **Не в `core`.** `core` остаётся минималистичной библиотекой исполнения цепочек с единственными runtime-зависимостями `kotlin-stdlib-jdk8`, `jackson-module-kotlin` и `slf4j-api` (см. [`core/pom.xml`](../core/pom.xml)).
- **Один новый Maven-модуль** `loki-query` (`artifactId=loki-query`), подключённый в корневой [`pom.xml`](../pom.xml). При такой границе будущие другие интеграции с Loki (например, правила алертинга или экспорт/стриминг) не смешиваются с historical query клиентом.
- **Форма поставки на Шаге 2:** переиспользуемая JVM-библиотека (клиент + парсер + модель выборки и полноты) + минимальный runnable CLI-пример в том же модуле (без Spring и без постоянно работающего JVM-сервиса).
- **Что не создаётся на Шаге 2:** общие абстрактные модули `observation-model`, `query-api`, `investigation-engine`, `cloudwatch`/`azure`/`gcp` адаптеры — их границы определяются только по итогам проверки первого среза (Шаг 3).

---

## 2. Входные события и проверка Сценариев 1 и 2 на текущем `core`

После выполненного уточнения `Event-level policy` стандартный `INFO`-профиль в [`BalletEvents.kt`](../core/src/main/kotlin/org/dmg/balletmaster/logging/BalletEvents.kt) и [`logging.md`](logging.md) даёт следующие события:

| Категория | События на `INFO` / `WARN` / `ERROR` | Остаются на `DEBUG` / `TRACE` |
|---|---|---|
| `org.dmg.balletmaster.step` | `step.exception` (`INFO` для `gate`/`map`/`milestone`, `WARN` fallback), `chain.stopped` (`INFO` для `Failure`/`Error`/`BLOCKED`/`NO_CALL`), `step.no_call` (`WARN`) | `chain.started`, `chain.completed`, `chain.stopped` (`status=SKIP`), `step.started`, `step.completed`, `step.skipped_gate`, `step.skipped_idempotent`, `step.milestone_updated`, `step.checkpoint_saved` (`DEBUG`), `step.checkpoint_bypassed` (`TRACE`) |
| `org.dmg.balletmaster.lock` | `lock.blocked` (`INFO`), `lock.stolen_expired` (`WARN`), `lock.stale_unlock_skipped` (`WARN`), `lock.wait_interrupted` (`WARN`) | `lock.acquired`, `lock.contention_wait`, `lock.released` (`TRACE`) |
| `org.dmg.balletmaster.replay` | `replay.switched_to_live` (`INFO`) | `replay.cache_hit`, `replay.cache_miss` (`DEBUG`) |
| `org.dmg.balletmaster.store` | `store.error` (`ERROR`) | — |
| `org.dmg.balletmaster.state` | `state.config_saved` (`INFO`), `state.config_not_found` (`WARN`) | `state.resolved` (`DEBUG`) |

### Проверка эталонных сценариев на `INFO`-профиле

1. **Сценарий 1 — Неуспешная/остановленная цепочка:**
   - **Наблюдается на `INFO` при поиске по `operationId`:**
     - бизнес-исключение: `step.exception` (`INFO`: `phase`, `exception`) + `chain.stopped` (`INFO`: `outcome=FAILURE`, `executedSteps`, `totalSteps`, `durationMs`, `ballet.step`);
     - ошибка `Store`: `store.error` (`ERROR`: `storeOp`, `key`, exception cause) + `chain.stopped` (`INFO`: `outcome=ERROR`);
     - конкурентная блокировка: `lock.blocked` (`INFO`: `status=BLOCKED`, `leaseMs`, `waitMs`, `attempts`, `interrupted`) + `chain.stopped` (`INFO`: `outcome=STATE`, `status=BLOCKED`).
   - **Не наблюдается при поиске по `operationId`:**
     - успешное прохождение цепочки и ожидаемые `SKIP` (`step.skipped_gate`, `step.skipped_idempotent`, `chain.stopped(SKIP)`) — остаются в `DEBUG`;
     - потеря контекста (`step.no_call` на `WARN`, `chain.stopped(NO_CALL)`): при отсутствии `CallContext` в логах физически **нет ни `ballet.operation`, ни `ballet.operationId`**, поэтому запрос `|= "<operationId>"` такие записи не находит. `NO_CALL` выявляется общим алертом на уровне сервиса (Кандидат B: Native Alerting `|= "step.no_call"`), а не историческим поиском по конкретной операции.
   - **Важный вывод для Query:** отсутствие событий по `operationId` в Loki на `INFO` **не означает** ни «цепочка прошла успешно», ни «шаг был пропущен по `SKIP`» — это может быть также отставание доставки, retention, ошибка фильтра или выключенное логирование.

2. **Сценарий 2 — Истёкший lease и конкурентный воркер:**
   - **Наблюдается на `INFO`:** `lock.stolen_expired` (`WARN`), `lock.stale_unlock_skipped` (`WARN`), `lock.blocked` (`INFO`), `lock.wait_interrupted` (`WARN`), `chain.stopped(BLOCKED)` (`INFO`).
   - **Ограничение текущего `core`:** без `executionId` два параллельных запуска одной и той же `(operationId, attempt, suffix, step)` различаются только внешними полями среды хоста (`pod`/`host`/`thread`/`traceId`), если хост пишет их в JSON-лог; `timestamp` задаёт лишь приблизительную хронологию из-за clock skew.

---

## 3. Формат экспорта логов (`core` → Logging Backend → Loki)

`core` пишет атрибуты через SLF4J 2.0 `LoggingEventBuilder.addKeyValue(...)` (`message` = имя события), а также устанавливает `MDC` (`ballet.*`) на время шага/цепочки.

- **Почему JSON, а не текстовый `%kvp`:** текстовый `%kvp` удобен для локальной консоли, но уязвим при парсинге в LogQL (разные форматы строк у логов хоста и SDK, экранирование значений). Для Loki-интеграции требуется **JSON-формат строки**.
- **Базовый рекомендуемый энкодер для примера и round-trip тестов:** встроенный `ch.qos.logback.classic.encoder.JsonEncoder` (входит в `logback-classic:1.5.12`, уже прописанный в корневом [`pom.xml`](../pom.xml)), не требующий дополнительных библиотек.
- **Особенности форматов (что обязан учитывать парсер):**
  - Logback `JsonEncoder` сериализует `kvpList` / `keyValuePairs` как список `{ "key": "...", "value": ... }`, а `MDC` — как объект `"mdc": { "ballet.operation": "...", ... }`.
  - Альтернативные энкодеры (например, `logstash-logback-encoder` или collector-трансформация) могут раскладывать `keyValuePairs` и `MDC` в плоские поля JSON или Loki structured metadata.
  - Парсер на Шаге 2 должен строиться поверх `jackson-module-kotlin` (уже есть в `pom.xml`) и поддерживать нормализацию обоих вариантов представления (`nested mdc/keyValuePairs` и `flat JSON` / Loki stream metadata), а не зашиваться на один жёсткий макет, при этом в тест на Шаге 2 включить **реальный round-trip через `BalletMaster` + Logback `JsonEncoder`**.

---

## 4. Схема меток (Labels), LogQL и защита от high-cardinality

1. **Stream Labels (только low-cardinality):**
   - Обязательный узкий селектор: `app` (или `service_name`) и `environment` (опционально `namespace`/`tenant`).
   - **Жёсткое правило:** `ballet.operationId` и `ballet.suffix` **никогда не используются как индексируемые Loki labels** (иначе произойдёт взрыв cardinality потоков). `ballet.operation` допустим как structured metadata или JSON-фильтр, либо label только при заранее ограниченном справочнике операций.
2. **Построение LogQL-запроса (два режима фильтрации):**
   - Базовый stream selector: `{app="<app>", environment="<env>"}` (если передан `namespace`/`tenant` — добавляются в selector).
   - Быстрый текстовый префильтр до парсинга: `|= "<operationId>"` (экранированный литерал LogQL) — резко сокращает число строк, передаваемых в `| json`.
   - Точный структурный фильтр после `| json`: сверка полей `operationId` и (если задано) `operation` / `attempt` / `step` (чтобы `|= "ord-1"` не зацепило `ord-10`), чтобы учитывать различие схем `JsonEncoder` (`mdc_ballet_operationId` / `keyValuePairs`) и плоских JSON/structured metadata.
   - Флаг области выборки в запросе:
     - `SDK_ONLY` — только логгеры `org.dmg.balletmaster` / `org.dmg.balletmaster.*`;
     - `ALL_CORRELATED` — системные события SDK + прикладные логи хоста, содержащие `ballet.operationId` в MDC.

---

## 5. HTTP-взаимодействие, Query Budget и модель результата

### 5.1. Loki HTTP API
- Endpoint: `GET /loki/api/v1/query_range`.
- Параметры: `query`, `start` (RFC3339 или ns), `end`, `limit`, `direction` (`FORWARD` по умолчанию для хронологии; либо `BACKWARD`).
- Заголовки: `X-Scope-OrgID` (опционально для multi-tenant Loki) и заголовок авторизации (read-only Bearer/Basic через абстракцию провайдера credentials, не попадающую в логи).

### 5.2. Обязательный Query Budget (защита от дорогих запросов)
- `start` и `end` обязательны; дефолтный `maxWindow` (например, `24h`, валидируется на клиенте до HTTP-вызова).
- `pageLimit` (например, `500` записей на запрос) и `maxTotalEntries` (например, `5000`) + `maxPages` (например, `20`) — жёсткий потолок пагинации.
- `connectTimeout` / `requestTimeout` по умолчанию (например, `5s` / `15s`) + поддержка отмены при исчерпании бюджета.

### 5.3. Пагинация и одинаковые timestamps (nanosecond collisions)
- Loki `query_range` возвращает строки с наносекундным ключом `tsNs` внутри streams.
- При пагинации `FORWARD`: следующий `start` нельзя просто сдвинуть на `lastTsNs + 1`, если на `lastTsNs` могло остаться больше записей (в пределах границы страницы). Наоборот, сдвиг `start = lastTsNs` может повторно вернуть уже прочитанные записи с тем же `tsNs`.
- **Правило для Шага 2:** сдвигать курсор на `lastTsNs` и выполнять аккуратную дедупликацию по `(streamLabels, tsNs, rawLine)` в пределах граничного timestamp (и отслеживать прогресс курсора, чтобы не зациклиться, если на одном `tsNs` записей >= `pageLimit` — в этом редком случае останавливать пагинацию с признаком `TRUNCATED`).

### 5.4. Модель результата и полнота (`Completeness`)
- Запись наблюдения (`LokiLogObservation`, локально в модуле `loki-query`):
  - `timestampNs: Long`, `timestamp: Instant`
  - `streamLabels: Map<String, String>`
  - `rawLine: String`
  - `logger: String?`, `level: String?`, `event: String?` (обычно `message` для событий SDK)
  - `correlation: BalletCorrelation?` (`operation`, `operationId`, `attempt`, `suffix`, `step`, `replay`)
  - `attributes: Map<String, Any?>`
  - `parseError: String?` (повреждённая/не-JSON строка сохраняется с `rawLine` и `parseError`, а не выбрасывается молча).
- Итог запроса (`LokiQueryResult`):
  - `observations: List<LokiLogObservation>`
  - `completeness: Completeness` (`COMPLETE`, `TRUNCATED`, `PARTIAL_PARSE_ERRORS`)
  - `truncationReason: String?` (`PAGE_LIMIT`, `MAX_ENTRIES`, `MAX_PAGES`, `SAME_TIMESTAMP_OVERFLOW`)
  - `pagesFetched: Int`, `totalRawEntries: Int`, `duplicatesDropped: Int`, `parseFailures: Int`, `warnings: List<String>` (включая явное напоминание, что пустая/завершённая выборка Loki не доказывает полноту доставки или отсутствие фактов в `Store`).

---

## 6. Техническое решение по JVM / HTTP-клиенту на Шаге 2

В корневом [`pom.xml`](../pom.xml) зафиксирован единый таргет Java `21` (`<maven.compiler.release>21</maven.compiler.release>`, `<kotlin.compiler.jvmTarget>21</kotlin.compiler.jvmTarget>`), Kotlin `2.4.21` и `jackson-module-kotlin` `2.22.4`.

- **Выбор для `loki-query`:** стандартный `java.net.http.HttpClient` + `jackson-module-kotlin` — без сторонних HTTP-библиотек, с нативной поддержкой HTTP/1.1 и HTTP/2, request/connect таймаутов и синхронных/отменяемых запросов.

---

## 7. План проверки на Шаге 2 (тестируемо без Docker)

Все автоматические тесты в `loki-query` не должны требовать внешнего запущенного Loki: используется встроенный в JDK `com.sun.net.httpserver.HttpServer` (на `127.0.0.1:0`):

1. **Контракт сериализации `core` → Logback `JsonEncoder` → парсер Loki:** запустить реальный `BalletMaster` (с `InMemoryStore`) на уровне `INFO`, собрать JSON-строки через Logback `JsonEncoder`, упаковать их в ответ формата `/loki/api/v1/query_range` и убедиться, что парсер извлекает все поля `ballet.*` и атрибуты событий без потерь.
2. **Эталонные сценарии 1 и 2 в сквозной проверке:** воспроизвести бизнес-исключение (`step.exception` + `chain.stopped(FAILURE)`), ошибку `Store` (`store.error` + `chain.stopped(ERROR)`), конкурентную блокировку и перехват просроченного лока (`lock.stolen_expired`, `lock.stale_unlock_skipped`, `lock.blocked`, `chain.stopped(BLOCKED)`) на уровне `INFO` и подтвердить их чтение клиентом.
3. **Граничные случаи Loki (по критерию 4 из п. `11.1`):**
   - пустой ответ (`streams = []`) → `COMPLETE`, 0 записей;
   - не-JSON / битая строка → `PARTIAL_PARSE_ERRORS` + сохранённая запись с `parseError`;
   - дубликат строки и пагинация с одинаковыми `tsNs` → корректная дедупликация без потери и без зацикливания;
   - срабатывание `maxPages` / `maxTotalEntries` → `TRUNCATED` с причиной;
   - HTTP `400`/`401`/`429`/`500`, невалидный JSON ответа от Loki и `timeout` → явная типизированная ошибка клиента.
4. **Проверка LogQL-генерации:** валидация того, что `operationId` никогда не попадает в stream selector `{...}`.
