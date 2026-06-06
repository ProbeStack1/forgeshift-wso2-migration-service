# DECK_BUILD_PLAN — switch migration delivery from Kong REST API to decK bundle

> **Goal:** stop pushing entities straight into Kong with the Konnect Admin REST API.
> Instead, turn the already-translated Kong objects into a **decK `kong.yaml`**, package it
> into a downloadable **bundle** (yaml + pipeline workflow + README), and let a
> **GitHub Actions pipeline run `deck gateway apply`** to load it into Kong Konnect.

This mirrors how `kong-wrapper` (Node.js) and `pipeline-template-poc` already work, but built
into `forgeshift-wso2-migration-service` and reusing its existing WSO2→Kong translators.

---

## 0. Decisions locked in

| Decision | Choice | Why |
|---|---|---|
| **How we deliver to git** | **Build a bundle like the Node service** (`kong.yaml` + workflow + README, zipped, downloadable). The service does **not** push to git itself. | Matches `kong-wrapper`. Whoever downloads it commits it to the Kong-config repo, which triggers the pipeline. (Auto-commit via GitHub API can be added later — see §9.) |
| **decK mode** | **`deck gateway apply`** (adds/updates, **never deletes**) | Safe for incremental migration. Re-running is harmless. No risk of wiping entities that aren't in the file. |

> ⚠️ **Reality check on the Node wrapper:** `kong-wrapper` does **not** push to git. Its
> `createKongBundle()` (`src/services/kong-bundle.service.ts`) builds the 3 files, zips them,
> and returns a `downloadUrl`. The git commit is a separate manual/CI step. We replicate that
> exact behaviour.

---

## 1. Old vs new flow

**Today (REST):**
```
WSO2 (Mongo) → translate → KongDeployer → KonnectAdminClient (POST/PUT) → Kong → save Kong UUIDs in entity_mappings
```

**New (decK bundle):**
```
WSO2 (Mongo) → translate → DeckYamlBuilder (kong.yaml) → BundleBuilder (zip) → store + downloadUrl
                                                                                   │
                          (human/CI commits the bundle to the Kong-config repo)   ▼
                                              GitHub Actions pipeline → deck gateway apply → Kong
```

The translation half is unchanged. Only the "write to Kong" half changes.

---

## 2. Reuse vs Replace vs New

### ✅ Reuse unchanged
- `controller/MigrationController` — same endpoints.
- `service/MigrationService` — same job state machine; only the **deploy block** changes (see §5).
- All **translators**: `ApiTranslator`, `SubscriptionTranslator`, `CertificateTranslator`, `ApiProductTranslator`, `MediationPolicyTranslator`.
- All **Kong domain objects**: `domain/kong/KongService`, `KongRoute`, `KongPlugin`, `KongConsumer`, `KongUpstream`, `KongTarget`, `KongCaCertificate` — they already use `@JsonInclude(NON_NULL)` and snake_case names, so they serialize straight to decK YAML.
- Readers: `DiscoverySnapshotReader`, `KongKonnectProfileReader`, `Wso2ProfileReader`, `Wso2BundleDownloadService`, `AssessmentSourceReader`.
- Persistence: `MigrationReport`, `MigrationJob` + repos (with small additions, §5).

### ❌ Replace (the REST writer)
- `service/KongDeployer` — the deploy orchestrator (POST/PUT loops).
- `client/KonnectAdminClient` + `client/KonnectUpsertResult` — the HTTP calls.
- `repository/EntityMappingRepository` + `domain/EntityMapping` — **kept**, but now populated *after* apply by reading Kong back by tag instead of from POST responses. See **§6b** (rebuild from `deck dump` / tag-filtered GET + `apply --json-output` errors).

### 🆕 New (the bundle writer)
| Class | Job |
|---|---|
| `deck/DeckYamlBuilder` | translated objects → `kong.yaml` string |
| `deck/BundleBuilder` | yaml + workflow + README → `bundle.zip` (bytes) |
| `deck/BundleResult` | result holder: `bundlePath`, `downloadUrl`, `files`, `controlPlaneName`, `kongConfigPath` |
| `service/DeckBundleDeployer` | the new "deploy" entry point — replaces `KongDeployer` in the orchestrator |
| `config/DeckProperties` | `forgeshift.migration.deck.*` config (see §7) |
| (opt) `controller` download endpoint | `GET /migrations/{id}/bundle` streams the zip (mirrors Node's download route) |

---

## 3. The bundle layout (mirror of `kong-wrapper`)

Three files, zipped — same shape `createKongBundle()` produces:

```
bundle.zip
├── kong/<env>/kong.yaml              ← from DeckYamlBuilder
├── .github/workflows/deploy-<env>.yml ← calls the pipeline template, deck_mode: apply
└── README.md                          ← how to use it
```

**`kong/<env>/kong.yaml`** (example shape):
```yaml
_format_version: "3.0"
_transform: true

services:
  - name: petstore-api-2-0
    protocol: https
    host: petstore.example.com
    port: 443
    path: /
    tags: ["wso2-source-id:abc123", "migrated-by:forgeshift-wso2-migrator"]
    routes:                       # routes nested under their service
      - name: petstore-api-2-0-get-pet
        protocols: ["http", "https"]
        methods: ["GET"]
        paths: ["/petstore/pet/{id}"]
        strip_path: true
        plugins:                  # route-scoped plugins nested under the route
          - name: rate-limiting
            config: { minute: 200, policy: local }
    plugins:                      # service-scoped plugins nested under the service
      - name: jwt
consumers:
  - username: mobile-app
    custom_id: app-uuid-987
upstreams:
  - name: petstore-api-2-0-upstream
    algorithm: round-robin
    targets:
      - target: petstore.example.com:443
        weight: 100
```

**Generated workflow** (`deploy-<env>.yml`) — like Node's `buildWorkflowYaml()` but with `deck_mode: apply`, parameterized, and the secret **referenced not hardcoded**:
```yaml
name: Deploy Kong (<env>)
on:
  workflow_dispatch:
  push:
    branches: [ main ]
jobs:
  deploy:
    uses: ForgeCrux/pipeline-template/.github/workflows/kong.yaml@main
    permissions: { contents: read, id-token: write }
    with:
      environment: <env>
      kong_config_path: kong/<env>/kong.yaml
      control_plane_name: <controlPlaneName>
      deck_mode: apply                              # ← never deletes
      konnect_addr: https://us.api.konghq.com       # ← region of the control plane
      validate_only: false
    secrets:
      konnect_token: ${{ secrets.KONNECT_TOKEN }}   # ← set this as a repo secret
```

---

## 4. Pipeline template change (`pipeline-template-poc/.github/workflows/kong.yaml`)

The template currently hard-codes `deck gateway sync`. Add two inputs and switch the deploy
command so it can run **apply**, and pass the Konnect region.

**Add to `inputs:`**
```yaml
      deck_mode:
        required: false
        type: string
        default: sync          # existing callers keep syncing; migration passes "apply"
      konnect_addr:
        required: false
        type: string
        default: https://us.api.konghq.com
```

**Add `--konnect-addr` to validate + diff, and change the deploy step:**
```yaml
      - name: Deploy Kong Configuration (${{ inputs.deck_mode }})
        if: ${{ inputs.validate_only == false }}
        run: |
          deck gateway ${{ inputs.deck_mode }} \
            --konnect-token "${{ secrets.konnect_token }}" \
            --konnect-addr "${{ inputs.konnect_addr }}" \
            --konnect-control-plane-name "${{ inputs.control_plane_name }}" \
            "${{ inputs.kong_config_path }}"
```

> Notes:
> - **`--konnect-addr`** matters: the Node wrapper used region `in` (`https://in.api.konghq.com`).
>   The current template omits it → defaults to **US**. If the target control plane is not in US,
>   `apply` hits the wrong region. Always pass the correct addr.
> - `deck gateway diff` previews **sync-style** changes (it will list deletions that `apply` will
>   NOT perform). Keep it as informational only, or gate it to `deck_mode == 'sync'` to avoid
>   confusing logs.

---

## 5. Wiring into `MigrationService.runMigration()`

The translation stages (lines 1–400) stay. Only the **deploy section changes**.

**Replace the deploy loop — `MigrationService.java` lines 418–564** (the `DEPLOYING` block that
calls `deployer.deployApi/deployConsumer/deployCertificate/deployApiProduct/deployMediationPolicy`).

New shape (feature-flagged so REST and decK can coexist during cutover):
```java
job.setState(MigrationState.DEPLOYING);          // or new GENERATING_BUNDLE
jobRepository.save(job);

if (deckProps.isEnabled()) {
    // ---- NEW: build kong.yaml + bundle ----
    BundleResult bundle = deckBundleDeployer.buildBundle(
            job, translatedApis, translatedConsumers, translatedCertificates,
            translatedApiProducts, translatedMediations);

    // report: everything that made it into the file counts as "included"
    // (the real apply result lives in the GitHub Actions run — see §6)
    writeReport(job, translatedApis, translatedConsumers, warnings, null,
            new ResourceCounters(translatedApis.size(),        translatedApis.size(),        0,0,0, List.of()),
            new ResourceCounters(translatedConsumers.size(),   translatedConsumers.size(),   0,0,0, List.of()),
            new ResourceCounters(translatedCertificates.size(),translatedCertificates.size(),0,0,0, List.of()),
            new ResourceCounters(translatedApiProducts.size(), translatedApiProducts.size(), 0,0,0, List.of()),
            new ResourceCounters(translatedMediations.size(),  translatedMediations.size(),  0,0,0, List.of()),
            new ResourceCounters(0,0,0,0, scopeSnapshots.size(), List.of()),
            bundle);   // overload writeReport to also persist bundle.downloadUrl etc.

    job.setState(MigrationState.COMPLETED);
    job.setCompletedAt(Instant.now());
    jobRepository.save(job);
    return;
}

// ---- else: existing REST path (lines 421–568) stays unchanged ----
```

**Dry-run** (lines 403–416): in decK mode, build the yaml only (no zip, no store) and optionally
attach a short preview/diff to the report; keep `DRY_RUN_DONE`.

**Small additions to persistence:**
- `MigrationReport`: add `bundleDownloadUrl`, `bundlePath`, `controlPlaneName`, `kongConfigPath`.
  Overload `writeReport(...)` to set them.
- `MigrationState` (optional): add `GENERATING_BUNDLE` / `BUNDLE_READY`, or just reuse
  `DEPLOYING → COMPLETED`.
- `dto/MigrationResultResponse`: surface `bundleDownloadUrl` so callers see where to download.

---

## 6. The two things you asked about — IDs and failure — in this model

**Do we get IDs back?**
- Kong assigns entity IDs **when `deck apply` runs in the pipeline**, not in this service.
- Our translators already produce **stable names** (`slug(name)-version`), and `apply` matches
  by name, so re-runs update the same entities — no duplicates, no UUIDs needed at build time.
- If you specifically need the UUIDs in Mongo: either set explicit `id:` fields in the yaml
  (derive a deterministic UUID from `wso2SourceId`), or run `deck gateway dump` after apply and
  backfill `entity_mappings`. **Default: don't bother — rely on stable names + `apiKongDetails`.**

**How do we know if it failed? (now two stages)**
1. **Bundle build** — this service knows instantly. If `DeckYamlBuilder`/`BundleBuilder` throws,
   the job goes `FAILED` with `lastError` (existing catch at lines 572–577). Success → the
   download URL is in the report.
2. **`deck apply`** — happens in **GitHub Actions**. The run goes red on failure; `validate`
   runs before `apply` to catch bad config early. *Bundle-built ≠ applied.*
   - Optional later: have the service call the GitHub Actions API to read the run status and fold
     it back into the report (only possible once we know which repo/commit — see §9).

---

## 6b. Capturing what was deployed (IDs + parents) and building the WSO2→Kong mapping

decK hands the info back via **two JSON outputs**; together they cover "what / where / with-which-id"
and "what failed". The thread that links each Kong entity to its WSO2 source is the **tag** the
translator already stamps: `wso2-source-id:<wso2 uuid>` (+ `migrated-by:forgeshift-wso2-migrator`).

| Need | Source | Gives you |
|---|---|---|
| Which entities **failed** + why | `deck gateway apply --json-output` | `changes.creating/updating/deleting`, `summary` counts, and an **`errors[]`** array (entity + reason) |
| Each deployed entity's **id** + **parent** ("under which thing") | `deck gateway dump --select-tag migrated-by:forgeshift-wso2-migrator --format json` | every service/route/plugin/consumer/target with its `id` and parent ref (route→service, plugin→service/route/consumer, target→upstream) |

**Build the mapping (this is how `entity_mappings` is rebuilt in the decK world):**
1. (Already done) every entity carries `wso2-source-id:<wso2 id>`.
2. Pipeline: `deck gateway apply --json-output` → `apply-report.json`.
3. Pipeline: `deck gateway dump --select-tag migrated-by:forgeshift-wso2-migrator --format json -o kong-state.json`.
4. Get both JSONs to the service (Option A or B below).
5. For each entity in `kong-state.json` → write a row: `wso2SourceId` (from the tag),
   `kongEntityType`, `kongUuid` = `id`, `parentKongUuid` = parent's id. (= existing `EntityMapping` shape.)
6. For each item in `apply-report.json.errors` → mark that WSO2 source **failed** and store the reason
   in `MigrationReport.warnings` / `failedSourceIds`.
7. Anything present in the yaml but absent from the dump = also failed / needs attention.

**Getting the JSON back to the service** (deploy runs in the pipeline, not the service):
- **Option A (recommended) — service reads Kong itself.** Once apply has succeeded, the service makes
  **read-only** Konnect Admin GET calls filtered by tag (`?tags=migrated-by:forgeshift-wso2-migrator`)
  — no decK binary needed; it already has WebClient + creds — and builds the mapping. Failure list =
  yaml entities missing from the read-back; for exact reasons, also capture `apply-report.json`.
- **Option B — pipeline pushes results back.** Pipeline runs the two commands and POSTs both JSONs to a
  new `POST /migrations/{id}/deck-result`; the service builds mapping + errors from them.

**New class:** `deck/DeckResultMapper` — parse `kong-state.json` + `apply-report.json` →
`List<EntityMapping>` + failed-source list. This **un-deprecates** `EntityMapping`/repo from §2.

> IDs source of truth = the **dump** (`apply --json-output` is for the change/error summary, not for
> reliably reading back every assigned id).

## 7. Config (`forgeshift.migration.deck.*`)

New `@ConfigurationProperties` class `config/DeckProperties`:
```
forgeshift.migration.deck.enabled            = true          # feature flag: decK vs REST
forgeshift.migration.deck.format-version      = "3.0"
forgeshift.migration.deck.transform           = true
forgeshift.migration.deck.env-name            = dev           # folder + workflow env
forgeshift.migration.deck.pipeline-template-ref= ForgeCrux/pipeline-template/.github/workflows/kong.yaml@main
forgeshift.migration.deck.konnect-addr        = https://us.api.konghq.com
forgeshift.migration.deck.kong-config-path     = kong/{env}/kong.yaml
forgeshift.migration.deck.storage             = temp          # temp | gcs
forgeshift.migration.deck.bundle-dir          = /tmp/kong-bundles
forgeshift.migration.deck.gcs-bucket          = <bucket>      # if storage=gcs
forgeshift.migration.deck.download-base-url    = https://.../migrations
```
- **Storage**: `temp` (mirror Node: write `bundle.zip` under `bundle-dir/{jobId}/`, serve via a
  download endpoint) is simplest. **`gcs` is basically free** — the `pom.xml` already depends on
  `google-cloud-storage` — and gives a durable link with no local disk.
- **`control_plane_name`**: decK needs the control-plane **name**, but
  `KongKonnectCredentials` today carries `controlPlaneId`. Add the **name** to
  `kong_konnect_profiles` (and to `KongKonnectCredentials`) so the workflow can be generated.

---

## 8. Key design note — relationships by NAME (replaces `entity_mappings` lookups)

In REST mode, `KongDeployer` resolves a member API's Kong **service UUID** from `entity_mappings`
for **API products** (`deployApiProduct`, lines 188–201) and **mediation policies**
(`deployMediationPolicy`, lines 238–250). Those UUIDs don't exist at bundle-build time.

In decK that's actually simpler: **reference the service by name.** All member/target APIs are
translated in the same run, so `DeckYamlBuilder` builds an in-memory map
`wso2SourceId → kongServiceName` from `translatedApis`, then:
- **Product routes** → top-level `routes:` with `service: { name: <serviceName> }` (plugins nested under each route).
- **Mediation plugins** → top-level `plugins:` with `service: { name: <serviceName> }`.

No `entity_mappings` read needed. ⚠️ In decK mode, **do not** call `KongRoute.setService(Map.of("id", ...))`
(the REST deployer does this) — leave it null for nested routes, or set `{name: ...}` for top-level ones.

---

## 9. Open decisions to confirm before coding

1. **Which git repo holds the Kong config, and who commits the bundle?** (The Node flow assumes a
   human/CI commits it.) If you later want the service to **auto-commit**, add a `GitPublisher`
   using the GitHub Contents API (`PUT /repos/{owner}/{repo}/contents/{path}`) via the existing
   WebClient — small add-on.
2. **`control_plane_name` + `konnect_addr` per company/tenant/env** — where stored? (Proposal:
   extend `kong_konnect_profiles`.)
3. **Environment mapping** (dev/stage/prod) — one bundle per env? Driven by the migration request?
4. **`KONNECT_TOKEN` secret** — must be a real GitHub repo/org secret in the config repo (never
   hardcode like the Node sample at `buildWorkflowYaml()` line 362).
5. **Keep or drop `entity_mappings`** — drop (simplest) or keep for audit via `deck dump`.

---

## 10. Phase-by-phase build order

| Phase | Work | Files |
|---|---|---|
| **P0** | Add `jackson-dataformat-yaml` dep (version managed by Spring Boot parent); add `DeckProperties`; add `forgeshift.migration.deck.*` to `.env`/props. | `pom.xml`, `config/DeckProperties.java` |
| **P1** | `DeckYamlBuilder` — translated objects → `kong.yaml` (nest routes/plugins/targets; products & mediations by service **name**). | `deck/DeckYamlBuilder.java` |
| **P2** | `BundleBuilder` — write 3 files + zip (`java.util.zip`); generate workflow with `deck_mode: apply` + correct `konnect_addr` + secret **reference**. | `deck/BundleBuilder.java`, `deck/BundleResult.java` |
| **P3** | `DeckBundleDeployer`; wire into `MigrationService` (replace lines 418–564 behind `deck.enabled`); add report fields; dry-run = yaml-only. | `service/DeckBundleDeployer.java`, `service/MigrationService.java`, `domain/MigrationReport.java`, `dto/MigrationResultResponse.java` |
| **P3c** | `DeckResultMapper` + result capture → rebuild `entity_mappings` (id + parent) + record errors. Option A (read Kong by tag) or Option B (`POST /migrations/{id}/deck-result`). See §6b. | `deck/DeckResultMapper.java`, (opt) `controller/MigrationController.java` |
| **P4** | Patch pipeline template: `deck_mode` + `konnect_addr` inputs; `deck gateway ${deck_mode}`; emit `apply --json-output` + `dump --select-tag --format json` as artifacts. | `pipeline-template-poc/.github/workflows/kong.yaml` |
| **P5** | Download endpoint `GET /migrations/{id}/bundle` (temp) or GCS signed URL. | `controller/MigrationController.java` |
| **P6** | Tests: `DeckYamlBuilder` (parse yaml back, assert nesting + by-name refs + no `service.id` on nested routes); `BundleBuilder` (zip has 3 paths, workflow has `apply`, no hardcoded secret). | `src/test/...` |
| **P7** | Cutover: flip `deck.enabled=true`; once verified, delete `KonnectAdminClient` + `KongDeployer` (or keep behind flag); decide on `entity_mappings`. | — |

---

## ✅ Build status (2026-06-06)

**v2 additions: auto-commit + per-API split files + profile-sourced git/control-plane. `mvn -o test` → BUILD SUCCESS, 5/5 tests pass.**
- **Single / incremental migration now works** — `DeckYamlBuilder.buildFiles()` emits **one file per API**
  under `kong/<env>/` (+ grouped `consumers.yaml`, `ca-certificates.yaml`, `api-products.yaml`); the
  pipeline applies the **whole directory** (decK merges). A single shared `kong.yaml` would overwrite
  the others on each run — split files avoid that.
- **Auto-commit** — `deck/GitPublisher` pushes the files to the Kong-config repo via the GitHub
  Contents API (one commit per file, existing files preserved → incremental). Behind
  `forgeshift.migration.deck.git.enabled` (env `DECK_GIT_*`). When off, the downloadable bundle is
  still produced. `MigrationReport` now also carries `gitRepo/gitBranch/gitCommitSha/gitCommitUrl`.
- **From profiles** — control-plane **name** + git **repo/branch/token** are resolved from
  `kong_konnect_profiles` (config fallback). `KongKonnectCredentials` gained `controlPlaneName`,
  `gitRepo`, `gitBranch`, `gitToken`.

**Implemented P0–P6. (Original pass: 4/4; after v2: 5/5 tests.)**
No new Maven dependency was needed — YAML is produced with **Jackson `convertValue` → Map** then
**snakeyaml `dump`** (both already on the classpath), instead of `jackson-dataformat-yaml`.

- New: `deck/DeckYamlBuilder`, `deck/BundleBuilder`, `deck/BundleResult`, `deck/DeckResultMapper`,
  `service/DeckBundleDeployer`, `dto/DeckResultRequest`, + tests `DeckYamlBuilderTest`, `BundleBuilderTest`.
- Edited: `MigrationProperties` (+`Deck`), `application.yml` (+`deck` block, `DECK_ENABLED` default `true`),
  `KongKonnectCredentials`/`KongKonnectProfileReader` (+`controlPlaneName`), `MigrationState`
  (+`GENERATING_BUNDLE`), `MigrationReport` (+bundle fields), `MigrationService` (deck branch behind
  `deck.enabled`), `MigrationController` (+`GET /migrations/{id}/bundle`, +`POST /migrations/{id}/deck-result`),
  pipeline `kong.yaml` (`deck_mode`/`konnect_addr`/`select_tag`/`result_callback_url` + result artifacts).
- Coexistence: `KongDeployer`/`KonnectAdminClient` kept and compile clean; unused while `deck.enabled=true`.
  **P7 (delete them) is optional cleanup, deferred.**

## 11. Quick test checklist
- Translate one real WSO2 API → eyeball generated `kong.yaml`.
- Run `deck gateway validate` on it locally (binary v1.43.0) — must pass.
- Commit a bundle to a **test** config repo → pipeline runs → check `validate` + `apply` green
  against a **test** control plane.
- Re-run the same migration → `apply` shows updates, **no duplicates, no deletes**.
- Then enable for real environments.
