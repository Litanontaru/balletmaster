# Мониторинг и алертинг Balletmaster (GitOps / Loki Ruler)

В этой директории лежат готовые правила алертинга для системных событий Balletmaster SDK и шаблоны их доставки в Kubernetes через GitOps (ArgoCD / Flux / Kustomize / Helm).

## Структура

```text
monitoring/
├── rules/
│   └── loki/
│       ├── balletmaster-loki-rules.yaml   # Канонический файл правил Loki Ruler (источник истины)
│       └── kustomization.yaml             # Каноническая Kustomize-база (ConfigMapGenerator из локального файла правил)
└── deploy/
    └── k8s/
        ├── kustomization.yaml             # Kustomize-слой для K8s (подключает ../../rules/loki + общие метки)
        ├── alerting-rule-cr.yaml          # Kubernetes CRD: loki.grafana.com/v1 AlertingRule (Loki Operator)
        └── configmap.yaml                 # Статический ConfigMap для монтирования в loki-ruler / sidecar
```

Синхронность правил между каноническим файлом и K8s-манифестами, отсутствие high-cardinality меток (`operationId`, `suffix`) в агрегациях, а также реальная сборка Kustomize (`kustomize build` / `kubectl kustomize` со стандартным `LoadRestrictionsRootOnly`) автоматически проверяются тестом [`LokiRulesValidationTest`](../loki-query/src/test/kotlin/org/dmg/balletmaster/loki/LokiRulesValidationTest.kt).

---

## Подключение через GitOps (ArgoCD / Flux)

### Вариант 1: Kustomize + `ConfigMap` (рекомендуется для Loki Ruler / sidecar)

В GitOps-репозитории вашего сервиса или кластера создайте overlay `kustomization.yaml`, ссылающийся на базу по тегу/коммиту:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: monitoring

resources:
  - https://github.com/<your-org>/balletmaster//monitoring/deploy/k8s?ref=<tag-or-commit>

# При необходимости добавьте метку для sidecar-обнаружения (например, k8s-sidecar):
labels:
  - pairs:
      loki_rule: "1"
```

Преимущество: базовая директория `monitoring/rules/loki` формирует `ConfigMap` через `configMapGenerator` напрямую из локального файла `balletmaster-loki-rules.yaml` с хэш-суффиксом для автоматического обновления конфигурации, а `monitoring/deploy/k8s` подключает её как Kustomize resource (`../../rules/loki`) и добавляет общие метки — всё работает со стандартным `--load-restrictor=LoadRestrictionsRootOnly` без дублирования файла правил.

Если общие метки из `monitoring/deploy/k8s` не нужны, можно ссылаться напрямую на базу `monitoring/rules/loki`.

Локальная проверка сборки манифестов:

```bash
kubectl kustomize monitoring/deploy/k8s
# или при наличии standalone kustomize:
kustomize build monitoring/deploy/k8s
```

---

### Вариант 2: Loki Operator CRD (`AlertingRule`)

Если в кластере установлен **Grafana Loki Operator / LokiStack**, подключите [`deploy/k8s/alerting-rule-cr.yaml`](deploy/k8s/alerting-rule-cr.yaml) в ваш ArgoCD `Application` или `kustomization.yaml`:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: payments-prod

resources:
  - alerting-rule-cr.yaml
```

> **Примечание по tenant/selector:** при необходимости ограничьте селектор `{app=~".+"}` до конкретного сервиса (`{app="payment-service", environment="prod"}`) и добавьте метки маршрутизации вашего LokiStack (например, `tenantID` или `openshift.io/cluster-logging: "true"`).

---

### Вариант 3: Helm-чарт сервиса

Скопируйте `rules/loki/balletmaster-loki-rules.yaml` в директорию `files/` вашего Helm-чарта и смонтируйте через `ConfigMap`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "app.fullname" . }}-balletmaster-rules
  labels:
    loki_rule: "1"
data:
  balletmaster-loki-rules.yaml: |-
{{ .Files.Get "files/balletmaster-loki-rules.yaml" | indent 4 }}
```

---

## Локальное окружение Loki и интеграционное тестирование

Для локальной проверки правил, выполнения LogQL-запросов и сквозного тестирования Investigation Engine в директории подготовлен `docker-compose-loki.yml`:

```bash
# Запуск локального Grafana Loki (порт 3100)
docker compose -f monitoring/docker-compose-loki.yml up -d

# Проверка готовности
curl http://localhost:3100/ready
```

### Запуск интеграционных тестов с реальным Loki

Модули `loki-query` и `investigation-service` содержат интеграционные тесты с реальным движком Loki:
- `LokiRealQueryIntegrationTest` (`loki-query`): проверяет выполнение реального LogQL, точную фильтрацию по `operationId` (отсечение префиксных коллизий вроде `ord-100` при поиске `ord-1`), разбор потоков и нормализацию.
- `InvestigationRealLokiIntegrationTest` (`investigation-service`): проверяет полный цикл — генерация структурированных логов `BalletMaster` -> прием логов через push API Loki -> LogQL запрос -> диагностика в `OperationInvestigator` и через REST API.

Интеграционные тесты автоматически обнаруживают работающий Loki:
1. По параметру `-Dballetmaster.test.loki.url=http://...` или переменной `LOKI_URL`.
2. На стандартном адресе `http://127.0.0.1:3100`.
3. Через Testcontainers (если запущен Docker daemon).
4. Если ни один источник недоступен, тесты пропускаются (`assumeTrue`), не ломая обычную сборку `mvn test`.

Для обязательного выполнения реальных интеграционных проверок (например, в CI) активируйте Maven-профиль `real-loki`:

```bash
mvn test -Preal-loki
# или с явным URL:
mvn test -Preal-loki -Dballetmaster.test.loki.url=http://localhost:3100
```

## Формат структурированного логирования и совместимость с Loki

SDK Balletmaster логирует системные события через SLF4J 2.0 Fluent API:
- Атрибуты корреляции (`ballet.operation`, `ballet.operationId`, `ballet.step`, `ballet.attempt`, `ballet.suffix`, `ballet.replay`) помещаются в SLF4J MDC.
- Дополнительные структурированные поля (`storeOp`, `exception`, `key`, `leaseMs` и т.д.) передаются через SLF4J Key-Value Pairs (`addKeyValue`).

В зависимости от конфигурации приложения-хоста поддерживаются два production-профиля:

### Профиль 1: Рекомендованный Native Flat JSON (In-Process)

**Конфигурация:** [`collectors/logback-spring-recommended.xml`](collectors/logback-spring-recommended.xml) на базе `net.logstash.logback:logstash-logback-encoder`.

```xml
<encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
    <providers>
        <timestamp><timeZone>UTC</timeZone></timestamp>
        <logLevel/><loggerName/><threadName/><message/>
        <!-- Сплющивает MDC в корневые поля JSON -->
        <mdc/>
        <!-- Сплющивает KeyValuePairs (storeOp, exception) в корневые поля JSON -->
        <keyValuePairs/>
        <stackTrace/>
    </providers>
</encoder>
```

**Преимущества:**
- Все атрибуты попадают в корень JSON (`ballet.step`, `storeOp`, `exception`).
- Стандартный парсер Loki `| json` заменяет точки на подчёркивания (`ballet.step` -> `ballet_step`) и автоматически делает все поля доступными для агрегаций.
- Правила Loki Ruler (`sum by (storeOp)`, `sum by (ballet_step)`) работают сразу «из коробки».
- `LogQlQueryBuilder` (`ballet_operationId = "..."`) находит записи без дополнительных трансформаций.
- Коллекторы (Promtail, Alloy, Vector, Fluent Bit) передают поток в Loki без сложных пайплайнов перепаковки.

---

### Профиль 2: Стандартный Logback `JsonEncoder` + Нормализация коллектором

Если в приложении используется стандартный `ch.qos.logback.classic.encoder.JsonEncoder` из `logback-classic`:
- MDC сериализуется во вложенный объект: `"mdc": { "ballet.operationId": "...", "ballet.step": "..." }`.
- Key-Value Pairs сериализуются во вложенный массив: `"kvpList": [ {"storeOp": "CAS"}, {"key": "..."} ]`.

> [!WARNING]
> Парсер Loki `| json` автоматически преобразует вложенные объекты в поля с префиксом (`mdc_ballet_operationId`), но **не распаковывает массивы** (`kvpList`). Поэтому поля `storeOp` и `exception` остаются внутри строки массива и не могут использоваться в агрегациях `sum by (storeOp)` в Loki Ruler без предварительной нормализации.

Для этого профиля коллектор логов выполняет распаковку и нормализацию перед отправкой в Loki:
- **Grafana Alloy:** [`collectors/alloy-balletmaster.alloy`](collectors/alloy-balletmaster.alloy) — использует `stage.json` и `stage.structured_metadata` (Loki 3.0+) для перевода correlation-полей и KVP в Structured Metadata.
- **Vector (VRL):** [`collectors/vector-balletmaster.vrl`](collectors/vector-balletmaster.vrl) — сплющивает `mdc` и распаковывает массив `kvpList` в плоские поля JSON через Remap Language.

---

### Таблица соответствия атрибутов и меток

| Атрибут Core / SLF4J | Стандартный Logback JsonEncoder | Flat JSON (LogstashEncoder) | Коллектор (Alloy / Vector) | Извлечение в Loki LogQL | Использование в мониторинге |
|---|---|---|---|---|---|
| `BalletMdc.OPERATION_ID` (`ballet.operationId`) | `mdc["ballet.operationId"]` | `ballet_operationId` | `ballet_operationId` | `ballet_operationId` / `mdc_ballet_operationId` | Фильтр в `LogQlQueryBuilder` / Investigation Engine |
| `BalletMdc.OPERATION` (`ballet.operation`) | `mdc["ballet.operation"]` | `ballet_operation` | `ballet_operation` | `ballet_operation` | `sum by (ballet_operation)` в алерте Lease Takeover |
| `BalletMdc.STEP` (`ballet.step`) | `mdc["ballet.step"]` | `ballet_step` | `ballet_step` | `ballet_step` | `sum by (ballet_step)` в алертах No Call / Step Exception |
| `BalletMdc.ATTEMPT` (`ballet.attempt`) | `mdc["ballet.attempt"]` | `ballet_attempt` | `ballet_attempt` | `ballet_attempt` | Фильтрация по попытке в `LogQlQueryBuilder` |
| `BalletMdc.SUFFIX` (`ballet.suffix`) | `mdc["ballet.suffix"]` | `ballet_suffix` | `ballet_suffix` | `ballet_suffix` | Корреляция в Investigation Engine |
| `storeOp` (KVP) | `kvpList[{"storeOp": "..."}]` | `storeOp` | `storeOp` | `storeOp` | `sum by (storeOp)` в `BalletmasterStoreErrorsHigh` |
| `exception` (KVP) | `kvpList[{"exception": "..."}]` | `exception` | `exception` | `exception` | `sum by (exception)` в `BalletmasterStepFailureRateSpike` |

---

## Важные замечания по безопасности и производительности

1. **JSON-пайплайн (`| json`):** правила рассчитывают, что поля `ballet_step`, `ballet_operation`, `storeOp` и `exception` извлекаются из плоского JSON-лога (через collector / `logstash-logback-encoder`) либо перенесены коллектором в Loki structured metadata. Если хост отправляет вложенный массив `kvpList` от Logback `JsonEncoder` без нормализации в коллекторе, используйте готовый шаблон из [`collectors/`](collectors/) или переключите хост на рекомендуемый `logback-spring-recommended.xml`.
2. **Защита от высокой кардинальности:** никогда не добавляйте `ballet_operationId`, `operationId` или `ballet_suffix` в `sum by (...)` и в stream labels `{...}` — это приведёт к взрыву числа серий в Loki Ruler. Для разбора конкретного `operationId` используйте модуль [`loki-query`](../loki-query).
