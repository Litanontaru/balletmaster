# Investigation Service — Безопасность и Production Deployment

`investigation-service` — самостоятельный ops-микросервис для диагностики операций Balletmaster по историческим наблюдениям из логов (`GET /api/v1/investigations`).

---

## Обязательная граница безопасности (Security Boundary)

> **ВАЖНО:** `investigation-service` **не реализует собственную аутентификацию пользователей (OIDC/JWT/login)**.  
> В production-среде сервис **обязан** разворачиваться строго за внешним защищённым периметром (API Gateway, Ingress с OIDC/OAuth2-прокси или Service Mesh с mTLS) и **не должен** публиковаться напрямую в недоверенную сеть.

Ответственность разделена следующим образом:

| Уровень | Что обеспечивает |
|---|---|
| **Внешний периметр (API Gateway / Ingress / Service Mesh)** | Аутентификация вызывающей стороны (OIDC/JWT/mTLS), авторизация ролей поддержки/SRE, rate limiting по клиентам, терминация TLS |
| **Встроенная политика `investigation-service` (Defense-in-Depth)** | Белые списки `app`/`env` (fail-closed по умолчанию), запрет `ALL_CORRELATED` по умолчанию, ограничение окна (`max-window`) и числа одновременных запросов (`429`), валидация параметров, аудит-лог обращений, санитизация ошибок и маскировка секретов |
| **Подключение к Loki / провайдеру логов** | Только **read-only** учётные данные (`auth-header` передаётся через Kubernetes Secret / env, не хранится в Git) |

---

## Безопасные настройки по умолчанию (`application.yml`)

1. **Fail-closed для `app` и `env`:**
   - `balletmaster.investigation.security.allowed-apps: []`
   - `balletmaster.investigation.security.allowed-environments: []`
   - `balletmaster.investigation.security.allow-unrestricted-targets: false`
   - Пока `allowed-apps` и `allowed-environments` не заданы явно, любой запрос к `/api/v1/investigations` отклоняется с `403 Forbidden`.
   - Флаг `allow-unrestricted-targets: true` допустим **только** для локальной разработки и изолированных тестовых стендов.
2. **Ограничение `ALL_CORRELATED`:**
   - `allow-all-correlated: false` — по умолчанию доступен только `scope=SDK_ONLY` (системные события Balletmaster без прикладных логов хоста).
   - Включайте `allow-all-correlated: true` только в изолированной среде с подтверждённым redaction/маскированием PII в логах приложений.
3. **Защита от дорогих запросов:**
   - `max-window: 6h` — жёсткий предел временного интервала на REST-уровне (независимо от провайдера);
   - `max-concurrent-queries: 10` — лимит одновременных запросов в бэкенд логов (сверх лимита возвращается `429 Too Many Requests`);
   - `max-parameter-length: 128` + запрет управляющих символов.
4. **Аудит-лог обращений (`org.dmg.balletmaster.investigation.audit`):**
   - Каждый запрос (`ALLOWED`, `DENIED`, `RATE_LIMITED`) фиксируется структурированным событием `investigation.audit` с полями `outcome`, `app`, `env`, `operationId`, `scope` и опциональными метаданными заголовков `X-Forwarded-User` / `X-Request-Id` от доверенного прокси (используются исключительно для аудита, не для авторизации).
5. **Отсутствие утечек секретов и внутренних деталей:**
   - `server.error.include-stacktrace: never`, `include-message: never`, `include-exception: false`, `management.endpoint.health.show-details: never`;
   - `502 Bad Gateway` и `500 Internal Server Error` не возвращают клиенту внутренние URL/IP Loki, тела ответов upstream или stack trace;
   - `LokiProperties.toString()` маскирует `authHeader`.

---

## Пример конфигурации для production

```yaml
balletmaster:
  investigation:
    provider: loki
    default-window-minutes: 60
    security:
      allowed-apps:
        - payment-service
        - checkout-service
      allowed-environments:
        - prod
      allow-unrestricted-targets: false
      allow-all-correlated: false
      max-window: 6h
      max-concurrent-queries: 10
    loki:
      base-url: https://loki-gateway.monitoring.svc.cluster.local
      org-id: payments-tenant
      # Передаётся через Kubernetes Secret -> env BALLETMASTER_INVESTIGATION_LOKI_AUTH_HEADER
      auth-header: ${LOKI_READONLY_AUTH_HEADER}
```

---

## Пример Kubernetes `NetworkPolicy` (доступ только от API Gateway)

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: investigation-service-ingress
  namespace: ops
spec:
  podSelector:
    matchLabels:
      app: investigation-service
  policyTypes:
    - Ingress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: api-gateway
          podSelector:
            matchLabels:
              app: internal-api-gateway
      ports:
        - protocol: TCP
          port: 8080
```
