# PricePilot scraping audit and validation

Audited 29 October 2026. This report describes the local checkout, including the unfinished BrowserAct changes already present when work began. Production was not accessed or deployed. No paid API calls were made.

## Actual architecture

- React 18.3 / Vite 5.4 SPA, React Router, custom CSS (no Tailwind dependency). Relative `/api` requests use Vite's proxy in development and nginx in Compose. JWT is stored in sessionStorage and memory.
- Java 17 target / Spring Boot 3.2.5, Spring Security, Jsoup 1.17.2, JPA, Flyway. The local test JVM may differ from the Java 17 container; see validation below.
- PostgreSQL 16 with one existing Flyway migration. Separate product, listing, tracker, price_point, scrape_attempt, deal analysis, alerts, and API usage tables. Listings have a unique URL hash; trackers have a user/product constraint.
- Compose services: `db`, `app`, `web`, `certbot`. Database storage is the named `pgdata` volume. Only nginx publishes application ports; database and backend have no public host ports. The app waits for the database health check; nginx starts independently of backend readiness.
- Thirteen retailer rules. Host rules determine extraction order. Direct HTTP fetches pass through URL/redirect checks, a concurrency and per-host delay gate, block detection, and extractors. Optional unblockers handle direct blocking responses.

## How a price becomes history

1. `ProductTrackingService.track` canonicalizes the submitted URL, hashes its identity, and reuses an existing listing if found. New URLs are fetched before creating a product. Equivalent Amazon `/dp/` and `/gp/product/` URLs resolve to the same ASIN and hash; tracking parameters are removed.
2. `ScrapeEngine` chooses the retailer rule. Amazon gets one BrowserAct attempt if credentials and a positive call budget are present. Otherwise it uses the HTML path. Other retailers retain their existing Jsoup strategies.
3. BrowserAct must return an explicit selling price in the documented **PricePilot workflow contract below**. Amazon CSS fallback uses product price selectors, excludes MRP/installment contexts, and accepts complete numeric amounts rather than searching prose. Generic embedded JSON and metadata price fallback is disabled for Amazon because it can belong to another offer or product.
4. `ProductSnapshot.isVerified` checks a positive, bounded amount at monetary precision, INR, title, and MRP at least as high as the selling price. Conflicting price fields are rejected, never swapped. This validates the data contract; it does not independently prove that a workflow read the page correctly.
5. `RefreshWriter.recordSuccess` validates again before appending a price point with separate price/MRP, availability, capture time, and strategy. Failed validation uses the failure path.
6. `recordFailure` records the attempt and adjusts retry scheduling/status; it does not modify price history or `lastSuccessAt`. Existing historical mistakes are not rewritten by this fix. A successful future refresh appends a new observation.
7. Manual refreshes reuse observations less than 60 seconds old; scheduled refreshes use the configured freshness window. Successful changed observations may trigger alerts and optional Claude analysis.

## Findings

### Confirmed bugs corrected

- BrowserAct polled an undocumented `/v2/workflow/task/{id}` route and guessed response wrappers. The client now submits once, reads the documented `id`, polls `/v2/workflow/get-task?task_id=...`, and parses the finished task's `output.string`. Source: [BrowserAct Workflow API](https://www.browseract.com/docs-api).
- A BrowserAct failure could cause a second paid submission from the HTML extractor. Network work is now performed only before the HTML strategy loop; the mapper is a pure operation.
- BrowserAct swapped selling price and MRP when they conflicted, defaulted missing currency/availability, and did not verify product identity or extraction status. These conditions now reject output. Equal MRP is retained as valid evidence of no discount.
- The configured BrowserAct timeout was ignored by a fixed polling loop, and raw error responses were logged. Submission, polling and waits now share a deadline; provider bodies and exception messages are not logged. Non-200 responses, malformed output, canceled/paused/failed tasks and interruption safely return no result.
- BrowserAct bypassed normal validation, recorded a fictitious retailer HTTP 200 and zero latency, and had no call cap. It now records elapsed time with an unknown retailer HTTP status, uses shared validation, and reserves a submission against the existing monthly usage counter. The cap defaults to **zero**.
- Amazon CSS exclusions alone did not protect against generic metadata, embedded JSON and recommendation prices later in the chain. Amazon now opts out of default price/MRP selectors and restricts its fallback to CSS. All twelve other retailer strategies and selectors are retained.
- Amazon search/category links fell through generic canonicalization. URLs without a recognized product ASIN are now rejected. Redirects to another Amazon product are rejected.
- The snapshot builder checked MRP inconsistently depending on contribution order. Final snapshot validation and the persistence guard reject contradictions in either order.
- Security error handlers used `sendError`, which can dispatch `/error` after a stateless context has been cleared. They now write JSON 401/403 directly; Spring owns context cleanup. Removing the JWT filter's cleanup alone would not reliably fix the later dispatcher issue.
- Unsupported content types reached the generic 500 handler. They now return JSON HTTP 415. Existing malformed-JSON HTTP 400 behavior is tested.

### Confirmed issues outside this focused patch

- `JsonLdExtractor` chooses among products using price presence/name length; that does not establish product identity. Generic embedded/microdata extractors also need retailer-specific fixtures. Amazon no longer uses those price fallbacks; other retailers still need independent verification.
- `DiscoveryService` claims the canonicalizer rejects articles/categories, but generic canonicalization rejects mainly homepages. Non-Amazon article URLs can survive. `seenHosts` is updated before title similarity filtering, which can suppress a better later result from the same retailer.
- `SiblingDiscoveryService` automatically adds scraped candidates using search-title overlap and a broad price ratio (0.55–1.80). This contradicts a comment saying discovery requires human confirmation, and is not strong variant matching. No general keyword shopping search endpoint exists; the implementation is URL tracking with optional sibling discovery.
- CSS/structured-data fallback for other retailers still has heuristic availability and identity limitations. The shared INR/title/amount check may now reject incomplete records that were previously accepted; preserving verified history is intentional.

### Likely bugs requiring reproduction

- `TrackingWriter.addListing` and `trackFor` catch constraint violations inside a transaction. PostgreSQL may leave that transaction rollback-only, making the documented concurrency recovery ineffective. Test with a real disposable database before changing transaction boundaries.
- Simultaneous manual/scheduled refreshes have no listing-level single-flight lock spanning the fetch and persistence. Host throttling serializes HTTP calls but does not guarantee one fetch per listing.
- nginx's existing 90-second read timeout can expire before a 90-second BrowserAct attempt plus fallback, or a refresh of several listings, finishes. The client deadline bounds BrowserAct network work, not queue time, the full refresh request or remote workflow execution. A future async refresh API would address this without arbitrarily long proxy timeouts.
- Actual selectors, stock detection, delivery-location differences and price variants need current retailer pages. No claim of live accuracy across all thirteen sites follows from these builds.

### Configuration and credentials

Only presence/absence was inspected, never secret values:

| Local `.env` setting | Observed state |
|---|---|
| BrowserAct API key and workflow ID | Present; validity/publication not verified |
| Claude API key | Empty |
| Tavily API key | Empty |
| Unblocker provider/key | `none` / empty |
| Email | Disabled |
| Scheduler | `true` in file; the Spring `dev` profile overrides it to false |
| Signup invite | Empty in local file; production unknown |

Spring Boot does not automatically load the repository `.env` file when launched directly with Maven. Compose does. `run-local.sh` also does not source it. No local backend was listening on port 8080 during the check, so these file settings are not a verified running deployment configuration. A shell environment can override them. Production's configuration, instance address and branch remain unverified.

Claude uses the Anthropic API for deal analysis; Tavily supplies discovery URLs, not prices. A coding assistant model has no connection to the application's runtime model. The configured default is `claude-haiku-4-5`, but the empty local key disables calls. No AI/search/email feature was enabled.

The earlier handover reports an unpublished BrowserAct skill. That is historical evidence, not a fresh API check. The current workflow must be built/published and its API input/output inspected before enabling paid calls. A bot ID, credential ID and workflow API ID are not interchangeable.

### Missing tests / remaining coverage

The baseline Maven build reported **no tests to run** and the frontend has no test script. This patch adds unit/fixture tests, mocked HTTP lifecycle tests, persistence repository-mock tests and Spring MVC/security tests. These do not prove real PostgreSQL migration/locking behavior, actual retailer responses, remote workflow publication, browser UI behavior, Docker image startup or EC2 deployment health. Those remain integration checks.

### Optional architectural improvements

- Add retailer fixtures and identity/variant-aware candidate confirmation before expanding automation to additional stores.
- Move long refreshes to jobs with status polling; add per-listing single-flight coordination.
- Add transaction/concurrency tests against a disposable PostgreSQL database and CI checks before image publication.
- Monitor BrowserAct credits in the provider dashboard. A submission count is not a monetary cap; tasks have variable credit costs and can continue remotely after a local timeout.
- Keep ECS, RDS, IAM, S3, monitoring and infrastructure-as-code experiments separate from this production scraping change.

## Approved local unlimited setting

On 29 October 2026 the user requested unlimited BrowserAct usage. The local ignored `.env` now sets `BROWSERACT_MONTHLY_CALL_BUDGET=-1`. The API key supplied by the user was saved to this local environment after confirming that the accompanying label was its credential name; the workflow ID was retained. This disables only BrowserAct’s submission cap; calls are still metered and provider credits/charges still apply. Zero remains the default for unconfigured deployments. Production has not been changed. The previously prepared initial deployment commands retain zero unless the approved production configuration is explicitly changed to -1.

## BrowserAct workflow contract

This is a contract to **configure in the user's workflow**, not an assumed BrowserAct built-in extraction schema. The workflow must accept a string input named `url` and produce JSON as its final text output. A single object or a one-element array is accepted; multiple products, markdown fences, prose, guessed alternative field names and trailing data are rejected.

Example output, with fixture values for illustration only:

```json
{
  "status": "success",
  "url": "https://www.amazon.in/dp/B07CR28HLL",
  "asin": "B07CR28HLL",
  "title": "Honeywell USB C Hub",
  "selling_price": "1609.50",
  "mrp": "3999.00",
  "currency": "INR",
  "availability": "in_stock",
  "image_url": null
}
```

Required: `status=success`, matching Amazon.in product `url` and `asin`, nonempty title (3–500 characters), explicit `selling_price`, `currency=INR`, and `availability` equal to `in_stock` or `out_of_stock`. `mrp` and `image_url` may be missing/null. An invalid optional image is discarded. A malformed or contradictory MRP rejects the observation. Selling amounts are bounded to ₹1–₹50,00,000 for this workflow. Prices must come from the main selected product's current one-time purchase offer; exclude MRP, EMI, bank/card incentives, coupons requiring eligibility, bundles, delivery and recommended products. On a block or missing price, return a failure status and no invented amount.

The protocol uses bearer authorization and a workflow ID; `run-task` receives `workflow_id` and an `input_parameters` array. Credentials stay in backend environment variables. The official API does not define the product-specific fields above. See [BrowserAct API documentation](https://www.browseract.com/docs-api) when checking the published workflow.

Environment settings added to `.env.example`:

- `BROWSERACT_API_KEY` and `BROWSERACT_WORKFLOW_ID`: supply privately on the machine that runs the backend.
- `BROWSERACT_TIMEOUT_MS=90000`: total local submission/poll deadline, excluding scheduling queue time.
- `BROWSERACT_MONTHLY_CALL_BUDGET=0`: paid submissions disabled. Use a positive integer for a cap, or `-1` for explicitly approved unlimited submissions. Calls reserve a slot before submission, including failed submissions, using the existing PostgreSQL usage counter. Polls do not reserve additional submission slots. This is a count limit, not a guarantee about the provider's credit bill.

## Validation results

- Baseline: frontend production build passed; backend Maven test lifecycle passed with zero existing tests.
- Before the unlimited-setting follow-up: **72 passed, zero failures/errors/skips** with `./mvnw -B -ntp clean verify`; the executable JAR was packaged successfully. All BrowserAct HTTP requests and persistence repositories are mocked. Test amounts are fixtures, not claims about today's selling price.
- The final `npm run build` passed (61 modules).
- `docker compose config --quiet` and `git diff --check` passed without printing interpolated credentials.
- Tests ran on Temurin Java 21.0.12.1 with Maven compiling to Java 17. A Java 17 runtime/Docker execution was not tested. Mockito instrumentation required a build run outside the filesystem sandbox.
- No production deployment, database changes, external AI/search calls, or live BrowserAct task submissions performed.

## Mac Terminal — review and publish only after approval

Run in the Mac project folder. Review the full changeset: it includes the BrowserAct work that was already uncommitted at handover.

```bash
cd ~/Downloads/Projects/PricePilot
(cd backend && ./mvnw -B -ntp clean verify)
(cd frontend && npm run build)
docker compose config --quiet
git diff --check
git diff
```

After review, create a branch and commit only the intended files. These commands are prepared, **not executed**:

```bash
git switch -c codex/amazon-price-validation
git add backend/src .env.example docker-compose.yml README.md docs/scraping-audit.md
git diff --cached --check
git diff --cached --stat
git commit -m "Validate Amazon selling prices and correct BrowserAct workflow API"
git push -u origin codex/amazon-price-validation
git rev-parse HEAD
```

Do not copy the Mac `.env` to EC2. The printed commit ID is the deployment candidate. Review/merge it according to your GitHub workflow before choosing the approved SHA below.

## EC2 Terminal — backup and deployment after approval

First connect using your existing SSH key and **currently verified** EC2 address. The handover address is historical; this session did not verify it. These commands run inside the EC2 SSH session and assume the existing checkout is `~/PricePilot` and the Compose services still use the checked-in names.

```bash
cd ~/PricePilot
git status --short
docker compose ps
```

Stop if the checkout has uncommitted source changes or the services/names differ; preserve and review them before switching commits. Keep the production `.env` and database volume. Create a private logical backup and save the current rules/config and image tag:

```bash
umask 077
mkdir -p backups
chmod 700 backups
PRICEPILOT_STAMP=$(date -u +%Y%m%dT%H%M%SZ)
PRICEPILOT_BACKUP="backups/pre-amazon-${PRICEPILOT_STAMP}"
mkdir "$PRICEPILOT_BACKUP"
cp .env "$PRICEPILOT_BACKUP/production.env"
cp docker-compose.yml "$PRICEPILOT_BACKUP/docker-compose.yml"
cp backend/src/main/resources/scraping/domain-rules.yml "$PRICEPILOT_BACKUP/domain-rules.yml"
git rev-parse HEAD > "$PRICEPILOT_BACKUP/commit.txt"
docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$PRICEPILOT_BACKUP/database.dump"
test -s "$PRICEPILOT_BACKUP/database.dump"
docker compose exec -T db pg_restore --list < "$PRICEPILOT_BACKUP/database.dump" > "$PRICEPILOT_BACKUP/database.contents"
docker image tag "$(docker compose images -q app)" pricepilot-app:before-amazon-validation
```

Check every command succeeds. `pg_restore --list` only inspects the archive; it does not restore or change the database. Keep a recoverable backup off the instance using your established method before deploying.

```bash
PRICEPILOT_APPROVED_COMMIT="PASTE_REVIEWED_COMMIT_SHA"
git fetch origin
git switch --detach "$PRICEPILOT_APPROVED_COMMIT"
nano .env
```

In the **EC2** `.env`, retain every existing production value and add `BROWSERACT_MONTHLY_CALL_BUDGET=0` for the initial deployment. Retain the existing BrowserAct key/workflow privately if present. Do not alter the signup invite unless you intend to change registration policy. A positive BrowserAct budget must wait for workflow verification and approval of its credit use.

```bash
docker compose config --quiet
docker compose build app
docker compose up -d --no-deps app
docker compose exec -T web nginx -s reload
docker compose ps
docker compose exec -T app curl -fsS http://localhost:8080/actuator/health/readiness
docker compose exec -T app curl -fsS http://localhost:8080/api/meta/capabilities
```

Readiness may take time after replacement; check it again when the service is healthy. Reloading nginx refreshes its upstream address after the app container is replaced. No `compose down`, volume deletion or database reset is needed. No migration is added by this patch.

Verify login, product details/history, registration invite behavior, and one direct refresh through the UI. BrowserAct remains off until separately enabled. For live BrowserAct validation: verify/publish the workflow contract, approve the provider credit cost, use a positive submission cap and disable scheduling temporarily for the isolated check, then refresh one known Amazon listing. Account for the 60-second manual freshness window. Compare its newly captured selling price, MRP and ASIN against the same page/delivery context; the handover's ₹1,609 is not an expected current price. If unsuccessful, confirm the previous timestamp/price remains visible and the failed check is recorded. Restore the approved scheduling setting after the check.

### Rollback before any paid workflow was enabled

This reuses the image and files saved above; leave the database running. In the same EC2 shell, with `PRICEPILOT_BACKUP` still set:

```bash
docker image tag pricepilot-app:before-amazon-validation pricepilot-app:latest
cp "$PRICEPILOT_BACKUP/domain-rules.yml" backend/src/main/resources/scraping/domain-rules.yml
cp "$PRICEPILOT_BACKUP/docker-compose.yml" docker-compose.yml
docker compose config --quiet
docker compose up -d --no-deps --force-recreate app
docker compose exec -T web nginx -s reload
docker compose ps
```

The database and `.env` are retained; the backup of `.env` is for recovery, not automatic replacement. Review the restored rules/Compose changes before later Git operations. If BrowserAct has already been used, its `BROWSERACT` usage rows require a rollback build that retains the new enum value; an older binary may fail to read those rows. Disable the BrowserAct budget and prepare a compatible rollback rather than deleting usage records or price history.

## Files changed

Includes preserved handover work plus this patch; changes remain uncommitted.

```text
.env.example
README.md
backend/src/main/java/com/pricepilot/config/AppProperties.java
backend/src/main/java/com/pricepilot/domain/ApiProvider.java
backend/src/main/java/com/pricepilot/schedule/RefreshWriter.java
backend/src/main/java/com/pricepilot/scrape/FetchedPage.java
backend/src/main/java/com/pricepilot/scrape/PriceParser.java
backend/src/main/java/com/pricepilot/scrape/ProductSnapshot.java
backend/src/main/java/com/pricepilot/scrape/ScrapeEngine.java
backend/src/main/java/com/pricepilot/scrape/ScrapeResult.java
backend/src/main/java/com/pricepilot/scrape/SnapshotBuilder.java
backend/src/main/java/com/pricepilot/scrape/UrlCanonicalizer.java
backend/src/main/java/com/pricepilot/scrape/browseract/BrowserActClient.java
backend/src/main/java/com/pricepilot/scrape/browseract/BrowserActPriceExtractor.java
backend/src/main/java/com/pricepilot/scrape/browseract/BrowserActResult.java
backend/src/main/java/com/pricepilot/scrape/extract/CssRuleExtractor.java
backend/src/main/java/com/pricepilot/scrape/rules/DomainRules.java
backend/src/main/java/com/pricepilot/security/JwtAuthFilter.java
backend/src/main/java/com/pricepilot/security/SecurityConfig.java
backend/src/main/java/com/pricepilot/web/ApiExceptionHandler.java
backend/src/main/resources/application.yml
backend/src/main/resources/scraping/domain-rules.yml
backend/src/test/java/com/pricepilot/schedule/RefreshWriterTest.java
backend/src/test/java/com/pricepilot/scrape/PriceParserTest.java
backend/src/test/java/com/pricepilot/scrape/ProductSnapshotTest.java
backend/src/test/java/com/pricepilot/scrape/ScrapeEngineTest.java
backend/src/test/java/com/pricepilot/scrape/browseract/BrowserActClientTest.java
backend/src/test/java/com/pricepilot/service/ProductTrackingServiceTest.java
backend/src/test/java/com/pricepilot/web/SecurityApiTest.java
docker-compose.yml
docs/scraping-audit.md
```

## Unlimited-setting follow-up validation

- Offline Maven package build passed, including compilation of the four new regression test cases. Tests were skipped in that packaging run.
- Twelve separate offline checks of the actual BudgetGuard and BrowserActClient classes passed: unlimited submissions remain metered, positive limits are enforced, zero and unsupported negative values disable calls, and other providers remain unchanged. The temporary smoke harness used an in-memory repository proxy and made no HTTP or database calls.
- Full Maven regression execution was blocked before launch by automatic approval review returning HTTP 503. The previous 72-test result predates the unlimited-setting change; no claim is made that the updated full suite ran.
- Compose configuration and diff whitespace checks passed. `.env` remains Git-ignored.
- Added regression file: `backend/src/test/java/com/pricepilot/usage/BudgetGuardTest.java`; also updated BrowserActClientTest.
