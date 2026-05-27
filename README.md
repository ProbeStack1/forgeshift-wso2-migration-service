# forgeshift-wso2-migration-service

Translates WSO2 API Manager artifacts into Kong Konnect entities and deploys
them via the Konnect Admin REST API.

Mirrors the structure of `probestack-apigee-migration-service` but for the
WSO2 → Kong path. Uses the **same translation rules** as the Python migrator
in `Archive/migrate_wso2_to_konnect.py`, just lifted into Spring Boot with
proper idempotency, multi-tenancy, and a job state machine.

---

## How it fits with the other services

```
┌─────────────────────────────────┐                                ┌──────────────────────┐
│ profile-config-service          │ writes Kong + WSO2 profiles ──▶│ MongoDB              │
│ (port 8082)                     │                                │ - profiles           │
└─────────────────────────────────┘                                │ - kong_konnect_      │
                                                                   │   profiles           │
┌─────────────────────────────────┐ writes discovery_wso2_*  ─────▶│ - discovery_wso2_*   │
│ discovery-service               │                                │ - discovery_         │
│ (port 8081)                     │                                │   revisions          │
└─────────────────────────────────┘                                │                      │
                                                                   │                      │
┌─────────────────────────────────┐                                │                      │
│ migration-service  ← this one   │ reads everything ──────────────│                      │
│ (port 8083)                     │ writes:                        │ - migration_jobs     │
│                                 │ - migration_jobs               │ - entity_mappings    │
│                                 │ - entity_mappings              │ - migration_reports  │
│                                 │ - migration_reports            └──────────────────────┘
└─────────────────────────────────┘
              │
              │ HTTPS Admin API (POST/PATCH)
              ▼
        ┌─────────────────┐
        │ Kong Konnect    │
        │ control plane   │
        └─────────────────┘
```

No HTTP calls between services — everything is mediated through the shared
Atlas database. Same pattern as the Apigee reference.

## Tech stack

| Concern | Choice |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.2.5 |
| HTTP client | Spring WebClient (Reactor Netty) |
| Database | MongoDB 6 (Atlas in dev) |
| API docs | springdoc-openapi |
| Tests | JUnit 5, Testcontainers (MongoDB) |

## REST API at a glance

Context path: `/wso2/migration/v1`. Default port: 8083.

| Group | Surface |
|---|---|
| Top-level migrate | `POST /migrations` |
| Per-resource shortcuts | `POST /wso2/migrate/apis`, `/wso2/migrate/subscriptions` |
| Dry-run | `POST /wso2/migrate/dry-run` |
| Jobs | `GET/DELETE /migrations/{id}`, `GET /migrations` |
| Reports | `GET /migrations/{id}/report` |
| Health | `/actuator/health`, `/swagger-ui.html` |

## Request body (every migrate endpoint shares the same shape)

```json
{
  "companyName": "probestack",
  "wso2Tenant": "carbon.super",
  "discoveryId": "32abcc62-...",         // optional - omit to use latest snapshot
  "kongProfileName": "primary",          // optional - which entry in kong_konnect_profiles
  "resourceTypes": ["apis", "applications", "subscriptions"],
  "dryRun": false,
  "userEmail": "sdmoh@local"
}
```

## Translation rules (mirrors the Archive Python migrator)

| WSO2 source field | Kong target |
|---|---|
| `name` + `version` (lowercased, hyphenated) | Service `name` |
| `endpointConfig.production_endpoints.url` | Service `host` / `port` / `protocol` / `path` |
| Multiple production endpoints | Upstream + Targets (one per URL); Service points at upstream |
| `context` joined with each resource's `target` | Route `paths` |
| `operations[].verb` | Route `methods` (one Route per resource for traceability) |
| `transport` list | Service + Route `protocols` |
| `policies` containing Bronze/Silver/Gold/Platinum tier | service-level `rate-limiting` plugin via the configured tier→RPM map |
| `operations[].throttlingPolicy` tier name | route-level `rate-limiting` plugin |
| `securityScheme` contains `oauth2` | service-level `jwt` plugin |
| `securityScheme` contains `api_key` | service-level `key-auth` plugin |
| `corsConfiguration.corsConfigurationEnabled == true` | permissive `cors` plugin with wildcard origins/headers/exposed headers and all HTTP methods |
| `responseCachingEnabled == true` | `proxy-cache` plugin |
| `tags` | Kong `tags` on every entity (plus `wso2-source-id:...` and `migrated-by:forgeshift-wso2-migrator`) |
| WSO2 Application | Kong Consumer (`custom_id = applicationId`, `username = slug(name)`) |
| Application `throttlingPolicy` | consumer-level `rate-limiting` plugin |
| Custom mediation policies | **NOT translated** in MVP — emits a warning in the report |

Default throttling-tier map (see `application.yml`):

| Tier | Requests/min |
|---|---|
| Bronze | 10 |
| Silver | 50 |
| Gold | 200 |
| Platinum | 500 |
| Unlimited | 10000 |

## MongoDB collections

| Collection | Read or Write | Purpose |
|---|---|---|
| `discovery_wso2_*` | read | Source snapshots written by the discovery service |
| `discovery_revisions` | read | Revision counter from the discovery service |
| `profiles` | read | WSO2 connection profiles (informational; we don't call WSO2) |
| `kong_konnect_profiles` | read | Konnect admin URL + PAT + available control planes |
| `migration_jobs` | write | One row per migration run + state machine |
| `entity_mappings` | write | Source-id → Kong-UUID mapping table (idempotency) |
| `migration_reports` | write | Per-job final report (outcomes + warnings + diff) |
| `migration_audit_info` | write | Reserved for an async audit filter (post-MVP) |

### `entity_mappings` — the idempotency layer

Every Kong write goes through `KonnectAdminClient.upsert(...)`. Before each
write the client looks up `(controlPlaneId, wso2SourceId, kongEntityType,
parentKongUuid)` in `entity_mappings`. If a `kongUuid` is recorded the call
becomes a PATCH; otherwise a POST. On a successful POST the new UUID is
persisted for next time.

Side effect: deleting the migration job document does **not** delete the
mapping rows. A rollback endpoint (read mappings → DELETE each Kong entity)
is a planned follow-up.

## Build & run

```bash
# 1. Copy .env.example to .env and fill in (or use the included .env with Atlas)
# 2. Build + run
mvn clean package
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Or container:

```bash
docker build -t forgeshift-wso2-migration-service .
docker run -p 8083:8083 --env-file .env forgeshift-wso2-migration-service
```

## Full pipeline walkthrough

Assumes both the discovery service (8081) and the profile-config service (8082)
are already running against the same MongoDB cluster.

```bash
# 1. (One time) Save the Kong Konnect profile via the config service
curl -X POST http://localhost:8082/config/v1/kong-konnect/profiles \
  -H "Content-Type: application/json" -H "X-Partner-Id: probestack" \
  -d '{"companyName":"probestack","profileName":"primary",
       "adminUrl":"https://us.api.konghq.com",
       "konnectPat":"kpat_...",
       "region":"us","userEmail":"sdmoh@local"}'

# 2. Discover WSO2 (writes snapshots the migration service will read)
curl -X POST http://localhost:8081/discovery/v1/discoveries \
  -H "Content-Type: application/json" \
  -d '{"wso2Tenant":"carbon.super","userEmail":"sdmoh@local"}'

# 3. Dry-run the migration first (no Konnect writes)
curl -X POST http://localhost:8083/wso2/migration/v1/wso2/migrate/dry-run \
  -H "Content-Type: application/json" -H "X-Partner-Id: probestack" \
  -d '{"companyName":"probestack","wso2Tenant":"carbon.super",
       "kongProfileName":"primary","resourceTypes":["apis"],
       "userEmail":"sdmoh@local"}'
# → returns a job id; state ends at DRY_RUN_DONE
# → GET /migrations/{id}/report to see the planned diff

# 4. Real migration
curl -X POST http://localhost:8083/wso2/migration/v1/migrations \
  -H "Content-Type: application/json" -H "X-Partner-Id: probestack" \
  -d '{"companyName":"probestack","wso2Tenant":"carbon.super",
       "kongProfileName":"primary","userEmail":"sdmoh@local"}'
# → poll GET /migrations/{id} until state == COMPLETED
# → GET /migrations/{id}/report for the final outcome
```

## Configuration

| Env var | Property | Default |
|---|---|---|
| `MONGODB_URI` | `spring.data.mongodb.uri` | local |
| `MONGODB_DATABASE` | `spring.data.mongodb.database` | `forgeshift_migration` |
| `SERVER_PORT` | `server.port` | `8083` |
| `KONNECT_BASE_URL` | `forgeshift.migration.konnect.base-url-fallback` | `https://us.api.konghq.com` |
| `KONNECT_ACCESS_TOKEN` | `forgeshift.migration.konnect.access-token-fallback` | (none — must come from profile) |
| `KONNECT_CONTROL_PLANE_ID` | `forgeshift.migration.konnect.control-plane-id-fallback` | (none) |
| `TENANT_HEADER` | `forgeshift.migration.tenant.header-name` | `X-Partner-Id` |
| `DEFAULT_TENANT` | `forgeshift.migration.tenant.default-tenant` | `probestack` |

## What's deferred for the MVP

- **Mediation policy translation** — Synapse XML → Lua / `pre-function` plugin. Currently emits a warning per affected API.
- **Credential migration** — generating Kong `jwt`/`key-auth` credentials per Consumer from WSO2 application keys. Currently just creates the empty Consumer.
- **Konnect-side rollback** — read `entity_mappings` and DELETE every recorded UUID. Planned `POST /migrations/{id}/rollback`.
- **Live-state diff** — the dry-run currently classifies based on `entity_mappings` presence (CREATE vs UPDATE). A field-level diff against live Konnect state is a follow-up.
- **Per-API key managers / OIDC plugin** — currently emits `jwt`. The IDP-aware path needs the `openid-connect` plugin (Konnect Enterprise) and a real key-manager lookup.
