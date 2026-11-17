# Мониторинг и алертинг Balletmaster (GitOps / Loki Ruler)

В этой директории лежат готовые правила алертинга для системных событий Balletmaster SDK и шаблоны их доставки в Kubernetes через GitOps (ArgoCD / Flux / Kustomize / Helm).

## Структура

```text
monitoring/
├── rules/
│   └── loki/
│       └── balletmaster-loki-rules.yaml   # Канонический файл правил Loki Ruler (источник истины)
└── deploy/
    └── k8s/
        ├── kustomization.yaml             # Kustomize-база (ConfigMapGenerator из rules/loki/)
        ├── alerting-rule-cr.yaml          # Kubernetes CRD: loki.grafana.com/v1 AlertingRule (Loki Operator)
        └── configmap.yaml                 # Статический ConfigMap для монтирования в loki-ruler / sidecar
```

Синхронность правил между каноническим файлом и K8s-манифестами, а также отсутствие high-cardinality меток (`operationId`, `suffix`) в агрегациях автоматически проверяются тестом [`LokiRulesValidationTest`](../loki-query/src/test/kotlin/org/dmg/balletmaster/loki/LokiRulesValidationTest.kt).

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

Преимущество: `configMapGenerator` формирует `ConfigMap` напрямую из `monitoring/rules/loki/balletmaster-loki-rules.yaml` с хэш-суффиксом для автоматического обновления конфигурации.

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

## Замечания по формату логов и кардинальности

1. **JSON-пайплайн (`| json`):** правила рассчитывают, что поля `ballet_step`, `ballet_operation`, `storeOp` и `exception` извлекаются из плоского JSON-лога (через collector / `logstash-logback-encoder`) либо уже перенесены коллектором в Loki structured metadata. Если хост отправляет вложенный массив `kvpList` от Logback `JsonEncoder` без нормализации в коллекторе, добавьте нормализацию полей на уровне коллектора (Alloy / Vector / Fluent Bit) или скорректируйте LogQL-выражение в своём overlay.
2. **Защита от высокой кардинальности:** никогда не добавляйте `ballet_operationId`, `operationId` или `ballet_suffix` в `sum by (...)` и в stream labels `{...}` — это приведёт к взрыву числа серий в Loki Ruler. Для разбора конкретного `operationId` используйте модуль [`loki-query`](../loki-query).
