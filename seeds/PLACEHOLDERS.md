# Nebula 2.0 Seeds — PLACEHOLDERS

Values in the seed data that **must be replaced with your own** before the seeds are usable in production. Nothing here is a secret from the original system — the originals were either generic placeholders or were removed during sanitization.

## 1. Strategy placeholders

### `<YOUR_PAYMENT_PAGE_PATH>`

- **Legacy token in the old SQL:** `HOLDER`
- **Occurrences:** 7
- **Where:** strategy term condition `page contain <...>`
- **Required:** yes — the strategy will not fire correctly until this is set
- **Meaning:** A URL path fragment that uniquely identifies your checkout / payment page (e.g. "/order/pay" or "/checkout/confirm"). The strategy counts HTTP_DYNAMIC requests whose `page` field contains this fragment in order to tell whether a submitted order was actually paid for.

- **Affected strategy files (7):**

  - `strategies/IP下单不支付.json` — IP下单不支付
  - `strategies/IP请求注册前未访问必要资源.json` — IP请求注册前未访问必要资源
  - `strategies/IP请求登录前未访问必要资源.json` — IP请求登录前未访问必要资源
  - `strategies/用户下单不支付.json` — 用户下单不支付
  - `strategies/设备下单不支付.json` — 设备下单不支付
  - `strategies/设备请求注册前未访问必要资源.json` — 设备请求注册前未访问必要资源
  - `strategies/设备请求登录前未访问必要资源.json` — 设备请求登录前未访问必要资源

**How to fill it in:** open each file above and replace every `"<YOUR_PAYMENT_PAGE_PATH>"` string with your own path fragment, e.g. `"/order/pay"`. The comparison operator is `contain`, so a substring is enough.

## 2. Configuration defaults you should review

These live in `config-defaults.json`. Values marked *sanitized* held real data from the source system and were replaced with `example.*` stand-ins; values marked *empty* were already blank in the dump but still need a real value for the feature to work.

| Key | Seeded value | State | What it is |
|---|---|---|---|
| `alerting.mail.base_url` | `http://nebula.example.com` | sanitized | Public base URL of your Nebula console; used to build links in alert emails. |
| `alerting.mail.sender` | `alerts@example.com` | sanitized | From-address for alert emails. |
| `alerting.to_emails` | `alerts-primary@example.com,alerts-secondary@example.net` | sanitized | Comma-separated alert recipients. |
| `alerting.smtp_server` | (empty) | empty | SMTP hostname. |
| `alerting.smtp_port` | (empty) | empty | SMTP port. |
| `alerting.smtp_account` | (empty) | empty | SMTP username. |
| `alerting.smtp_password` | (empty) | empty | SMTP password. **Do not commit a real value** — inject it at deploy time. |
| `alerting.nebula_address` | (empty) | empty | Address the alerting service uses to reach Nebula. |
| `alerting.email_topic` | (empty) | empty | Subject prefix for alert emails. |
| `filter.encryption.salt` | (empty) | empty | Salt for hashing sensitive fields. **Generate your own** — never reuse another deployment's salt. |
| `filter.encryption.names` | (empty) | empty | Comma-separated field names to encrypt/hash on ingest. |
| `filter.log.domains` | (empty) | empty | Domains to keep/drop during log ingestion. |
| `filter.log.client_ips` | (empty) | empty | Client IP filter for log ingestion. |
| `filter.log.server_ips` | (empty) | empty | Server IP filter for log ingestion. |
| `filter.traffic.domains` | (empty) | empty | Domains to keep/drop from mirrored traffic. |
| `filter.traffic.client_ips` | (empty) | empty | Client IP filter for mirrored traffic. |
| `filter.traffic.server_ips` | (empty) | empty | Server IP filter for mirrored traffic. |
| `filter.traffic.server_ports` | (empty) | empty | Server port filter for mirrored traffic. |
| `filter.traffic.urls` | (empty) | empty | URL filter for mirrored traffic. |
| `sniffer.uid.keyset` | `user_name` | review | Name of the request field the sniffer reads the user id from. Change to match your app. |
| `sniffer.did.keyset` | `did` | review | Name of the request field the sniffer reads the device id from. Change to match your app. |

## 3. Not placeholders

The following look like they might need substituting but do **not**:

- `app: "nebula"` on every asset — this is the built-in application namespace, not a customer name.
- Field identifiers inside strategy terms (`c_ip`, `did`, `uid`, `page`, `order_id`, …) — these are Nebula's own event schema names and match the event models in `events/`.
- Regex literals such as `^\\s*$` — genuine "is blank" guards.
