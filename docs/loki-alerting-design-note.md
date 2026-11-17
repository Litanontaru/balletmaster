# Technical Design Note: Native Monitoring & Alerting через Loki Ruler

> **Статус:** Draft (Шаг 1 для Кандидата B по [`balletmaster-observability-design-v0.3.md`](balletmaster-observability-design-v0.3.md))  
> **Контрольный срез:** Кандидат B — Native Monitoring & Alerting (Loki Ruler / Alertmanager)  
> **Связь с `loki-query`:** разделение ответственности — `loki-query` расследует конкретный `operationId`, а `Native Alerting` непрерывно мониторит поток сервиса на стороне хранилища логов.

---

## 1. Зачем нужен отдельный контур Native Alerting

В ходе реализации исторического поиска (`loki-query`) было установлено фундаментальное различие двух классов задач:

1. **Диагностика конкретной операции (Investigation / Historical Query):**
   - Запрос строится вокруг известного `operationId`.
   - Позволяет понять, почему конкретный заказ/транзакция завершилась `FAILURE`, `BLOCKED` или `ERROR`.
   - **Не способна** обнаружить аномалии, где `operationId` отсутствует (например, `step.no_call`), а также не заменяет непрерывный мониторинг фоновых процессов.

2. **Непрерывный мониторинг и раннее обнаружение (Native Alerting):**
   - Выполняется на стороне самой платформы (Loki Ruler / Prometheus Alertmanager / Grafana) по всему потоку логов сервиса.
   - Непрерывно агрегирует частоту событий (`rate()`), обнаруживает всплески ошибок и сигнализирует до того, как пользователь обратится в поддержку.
   - Позволяет выявлять глобальные сбои инфраструктуры (`store.error`), дефекты вызова SDK (`step.no_call`) и систематические проблемы конкуренции (`lock.stolen_expired`).

---

## 2. Формат и способ поставки

По [п. 5.4 диздока](balletmaster-observability-design-v0.3.md): *«Не каждый артефакт обязан быть JAR: конфигурации, правила и примеры развёртывания также являются поставляемыми компонентами.»*

- **Формат поставки:** стандартный YAML-манифест группы правил Loki Ruler / Prometheus (`rule_files`) + готовые GitOps-манифесты Kubernetes.
- **Пути в репозитории:**
  - канонические правила: [`monitoring/rules/loki/balletmaster-loki-rules.yaml`](../monitoring/rules/loki/balletmaster-loki-rules.yaml);
  - GitOps/K8s-шаблоны: [`monitoring/deploy/k8s/kustomization.yaml`](../monitoring/deploy/k8s/kustomization.yaml), [`monitoring/deploy/k8s/alerting-rule-cr.yaml`](../monitoring/deploy/k8s/alerting-rule-cr.yaml) (`loki.grafana.com/v1 AlertingRule`), [`monitoring/deploy/k8s/configmap.yaml`](../monitoring/deploy/k8s/configmap.yaml);
  - инструкция для SRE: [`monitoring/README.md`](../monitoring/README.md).
- **Совместимость:**
  - Loki Ruler (штатный сервис агрегации и вычисления алертов в Grafana Loki).
  - Grafana 9/10/11 Unified Alerting (файловый provisioning правил).
  - Prometheus Alertmanager (маршрутизация сигналов в Slack, Telegram, PagerDuty, Webhook).

---

## 3. Каталог правил базового пакета

В базовый пакет входят 4 ключевых правила для событий, реально доступных в production на уровнях `INFO`, `WARN` и `ERROR`:

### 3.1. `BalletmasterNoCallDetected` (Критический дефект интеграции)
- **Событие:** `step.no_call` (`WARN`).
- **Severity:** `critical` (`P1`).
- **Смысл:** код приложения вызвал шаг цепочки без активного `CallContext` в `CallContextManager`. Это грубый баг конфигурации/интеграции хост-приложения, из-за которого цепочка не может связать исполнение с бизнес-операцией.
- **Порог:** любое появление (> 0) за 5 минут.
- **LogQL:**
  ```logql
  sum by (app, environment, ballet_step) (
    rate({app=~".+"} |= "step.no_call" [5m])
  ) > 0
  ```

### 3.2. `BalletmasterStoreErrorsHigh` (Инфраструктурный сбой хранилища)
- **Событие:** `store.error` (`ERROR`).
- **Severity:** `critical` (`P0`/`P1`).
- **Смысл:** сбой драйвера `Store` (отказ соединения с Redis/Postgres/БД, connection pool exhaustion, таймаут записи input/output или ошибки CAS-блокировок).
- **Порог:** частота ошибок превышает 0.05 событий в секунду за окно 5 минут (или более 15 ошибок за 5m).
- **LogQL:**
  ```logql
  sum by (app, environment, storeOp) (
    rate({app=~".+"} |= "store.error" [5m])
  ) > 0.05
  ```

### 3.3. `BalletmasterLeaseTakeoverDetected` (Зависшие воркеры / короткий lease)
- **Событие:** `lock.stolen_expired` (`WARN`) и `lock.stale_unlock_skipped` (`WARN`).
- **Severity:** `warning` (`P2`).
- **Смысл:** один из воркеров не уложился в выделенное время аренды (`leaseMs`) — из-за GC-паузы, долгого HTTP-вызова или подвисшего потока — и другой воркер принудительно перехватил владение шагом. Это симптом риска гонок или необходимости увеличить таймаут `lease`.
- **Порог:** любое появление (> 0) за 10 минут.
- **LogQL:**
  ```logql
  sum by (app, environment, ballet_operation, ballet_step) (
    rate({app=~".+"} |= "lock.stolen_expired" [10m])
  ) > 0
  ```

### 3.4. `BalletmasterStepFailureRateSpike` (Всплеск бизнес-ошибок шага)
- **Событие:** `step.exception` (`INFO`, `phase=map`) или `chain.stopped` с `outcome="FAILURE"`.
- **Severity:** `warning` (`P2`).
- **Смысл:** резкий рост падений конкретного шага с определённым типом исключения (например, внешний платёжный шлюз вернул ошибку, либо невалидные входные данные).
- **Порог:** частота падений превышает 0.2 событий в секунду за 5 минут по связке `(app, environment, ballet_step, exception)`.
- **LogQL:**
  ```logql
  sum by (app, environment, ballet_step, exception) (
    rate({app=~".+"} |= "step.exception" [5m])
  ) > 0.2
  ```

---

## 4. Требования к селекторам и кардинальности (Защита от OOM в Loki Ruler)

1. **Никаких high-cardinality меток в селекторах потоков:**
   - Селектор потока обязан фильтровать только по индексируемым меткам инфраструктуры: `{app=~".+", environment=~".+"}` (или `{namespace=~".+"}`).
   - Метки `operationId`, `suffix` **не должны** использоваться в выражениях агрегации `by (...)`! В выражениях `by (...)` допускаются только ограниченные справочные поля: `app`, `environment`, `ballet_step`, `storeOp`, `exception`, `ballet_operation`.
2. **Текстовые префильтры до парсинга:**
   - Выражение `rate({app=~".+"} |= "<event_name>" [5m])` использует быстрый строковый префильтр по чанкам, минимизируя нагрузку на CPU/память Loki Ruler.

---

## 5. План тестирования и автоматической валидации

Для исключения опечаток и битых правил в репозитории создаётся модульный тест:
1. Валидация YAML-синтаксиса файла правил.
2. Проверка обязательных полей для каждого правила (`alert`, `expr`, `for`, `labels.severity`, `annotations.summary`, `annotations.description`).
3. Проверка выражений `expr` на отсутствие запрещённых high-cardinality полей в `by (...)` (`operationId`, `suffix`).
