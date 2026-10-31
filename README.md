# PricePilot

**Real-time price tracking for Indian e-commerce** — Track prices across Amazon.in, Flipkart, Croma, Reliance Digital, and more. Every price is scraped directly from the retailer's own product page, and every check is logged transparently.

![Java 17](https://img.shields.io/badge/Java-17-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-green) ![React](https://img.shields.io/badge/React-18.3-blue) ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)

## What It Does

1. **Paste a product URL** (Amazon.in, Flipkart, Croma, etc.) → the app reads the price directly from the retailer's page
2. **Tracks price changes** automatically with an adaptive scheduler — products that move get checked more often
3. **AI deal analysis** — a configured AI provider analyzes price history and potential fake discounts
4. **Price alerts** — set a target price and get notified when it drops
5. **Cross-retailer discovery** — automatically finds the same product on other stores (via Tavily)
6. **Full transparency** — every scrape attempt (success or failure) is logged and visible
7. **Shopping assistant** — after adding a link, enter your budget and must-have features, compare sourced offers and alternatives, and ask follow-up questions

## Architecture

```
┌─────────────┐      ┌──────────────────┐      ┌────────────┐
│  React SPA  │──────│  Spring Boot API │──────│ PostgreSQL │
│  (Vite)     │ /api │  (Port 8080)     │ JDBC │  (Port 5432│
│  Port 5173  │      │                  │      │            │
└─────────────┘      └──────────────────┘      └────────────┘
                            │
                     ┌──────┴──────┐
                     │  Retailers  │  (Amazon, Flipkart, etc.)
                     └─────────────┘
```

- **Backend**: Spring Boot 3.2, JPA/Hibernate, Flyway migrations, JWT auth, BrowserAct workflow scraping
- **Frontend**: React 18, React Router, Vite (zero external UI libraries — all CSS is hand-written)
- **Database**: PostgreSQL 16 with Flyway-managed schema
- **AI**: OpenAI-compatible gateways such as AgentRouter, or the optional legacy Anthropic API
- **Deployment**: Docker Compose with nginx + certbot for production

## Quick Start (Local Development)

### Prerequisites

- **Java 17+** (JDK)
- **Node.js 18+** & npm
- **Python 3** (loads local backend environment settings)
- **PostgreSQL 16** (running locally)

### 1. Set up the database

```bash
# Start PostgreSQL (if installed via Homebrew)
brew services start postgresql@16

# Create the database and user
createdb pricepilot
psql -d pricepilot -c "CREATE USER pricepilot WITH PASSWORD 'pricepilot';"
psql -d pricepilot -c "GRANT ALL PRIVILEGES ON DATABASE pricepilot TO pricepilot;"
psql -d pricepilot -c "ALTER USER pricepilot CREATEDB;"
psql -d pricepilot -c "GRANT ALL ON SCHEMA public TO pricepilot;"
```

### 2. Start the backend

```bash
cd backend
python3 ../scripts/with-env.py ../.env ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The launcher loads the root `.env` into the backend, with explicitly exported settings taking precedence. The first run downloads dependencies (~2-3 minutes). Flyway creates all tables automatically.

### 3. Start the frontend

```bash
cd frontend
npm install
npm run dev
```

### 4. Open the app

Go to **http://localhost:5173** — create an account and start tracking prices!

## Production Deployment (Docker)

The app runs on a single box with 4 containers: PostgreSQL, Spring Boot, nginx (serving React + proxying API), and certbot for TLS.

Preserve the server's existing `.env`, database credentials, JWT secret and volumes. Use `./deploy/compose.sh` to load configuration safely.

Before upgrading: run `python3 deploy/check-config.py` and `./deploy/compose.sh config --quiet`, back up PostgreSQL, then release app and web together. Keep one backend instance. Never use `docker compose down -v`. Flyway applies pending migrations at startup; an older image may not support newly stored provider values, so check compatibility before rollback.

Set `DOMAIN`, `TLS_ENABLED=true`, `APP_PUBLIC_URL` and `CORS_ALLOWED_ORIGINS` to your public HTTPS site. For first-time certificates, use `./deploy/init-letsencrypt.sh`. Recreate containers after environment changes; restarting does not load new values. Verify backend health, HTTPS and sign-in after release.

Manual price refreshes return a background job immediately. The product page polls progress and resumes that job after a page reload, without submitting another scrape. CloudWatch metrics are optional and require an explicit `cloudwatch` Spring profile.

## Supported Retailers

Configure AI, Tavily and email through the backend settings in `.env.example`. AI analyzes requirements, Tavily discovers product URLs, and BrowserAct verifies live prices. AgentRouter must authorize custom-server API access; an active token alone does not establish that permission.

All 13 configured retailers use BrowserAct as their only price source: Amazon.in, Flipkart, Croma, Reliance Digital, Vijay Sales, Tata CLiQ, JioMart, Snapdeal, Myntra, AJIO, Nykaa, Sangeetha Mobiles and Poorvika.

All retailers use the same existing published workflow configured by `BROWSERACT_WORKFLOW_ID`. Each product URL is passed through the existing `url` input parameter; no retailer-specific workflow IDs are needed. Failed or invalid extraction keeps the previous valid price. There is no direct scraping or alternate-provider fallback.

### BrowserAct workflow

Set `BROWSERACT_API_KEY` and one `BROWSERACT_WORKFLOW_ID` in backend configuration. The published workflow receives `input_parameters: [{"name":"url","value":"<product URL>"}]`. Its completed `output.string` must contain one JSON object (or one-element array) with `status: "success"`, `url`, `title`, `selling_price`, `currency: "INR"` and `availability` (`in_stock` or `out_of_stock`). Amazon also requires a matching `asin`; `mrp` and `image_url` are optional.

The workflow must verify the exact product/variant and main selling offer. Return failure if it cannot distinguish the offer from EMI, exchange prices, MRP or recommendations. The backend validates identity and price shape, but cannot prove a plausible mislabelled number is the correct offer. Failures retain the last valid price without false history or alerts.

`BROWSERACT_MONTHLY_CALL_BUDGET=0` disables submissions; a positive value caps monthly submissions; `-1` permits unlimited metered calls. Cache reuse and deduplication reduce requests. Live accuracy across all retailers remains unmeasured; automated tests do not establish it.

## Key Design Decisions

- **No price is ever fabricated** — every number shown was read from a retailer's page, at the timestamp shown
- **Adaptive scheduling** — listings that change get checked more often; stable ones back off (saves API budget)
- **Budget guards** — AI and Tavily allowances plus a shared BrowserAct submission limit; BrowserAct also supports an explicit unlimited setting
- **URL deduplication** — if 50 users track the same Flipkart URL, there's one listing and one fetch
- **Append-only price history** — price points are never updated or deleted

## Project Structure

```
PricePilot/
├── backend/                     # Spring Boot API
│   └── src/main/java/com/pricepilot/
│       ├── ai/                  # Claude integration (deal analysis)
│       ├── alerts/              # Price alert detection & email
│       ├── config/              # App configuration properties
│       ├── discovery/           # Tavily cross-retailer discovery
│       ├── domain/              # JPA entities (Product, Listing, etc.)
│       ├── metrics/             # Operational metrics
│       ├── repo/                # Spring Data JPA repositories
│       ├── schedule/            # Adaptive scrape scheduler
│       ├── scrape/              # Scraping engine + extractors
│       ├── security/            # JWT auth + Spring Security
│       ├── service/             # Business logic
│       ├── usage/               # API budget tracking
│       └── web/                 # REST controllers + DTOs
├── frontend/                    # React SPA
│   └── src/
│       ├── components/          # Reusable UI components
│       ├── lib/                 # API client, auth, utilities
│       ├── pages/               # Route pages
│       └── styles/              # CSS (tokens, base, app)
├── deploy/                      # Deployment scripts
├── docker-compose.yml           # Production setup
└── .env.example                 # Configuration template
```

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/auth/register` | — | Create account |
| POST | `/api/auth/login` | — | Sign in (returns JWT) |
| GET | `/api/auth/me` | ✅ | Current user |
| GET | `/api/products` | ✅ | Dashboard (all tracked products) |
| POST | `/api/products` | ✅ | Track a new URL |
| GET | `/api/products/:id` | ✅ | Product detail with price history |
| POST | `/api/products/:id/refresh` | ✅ | Start/join a background price refresh (202) |
| GET | `/api/products/:id/refresh/:jobId` | ✅ | Read refresh progress; never starts a scrape |
| POST | `/api/products/:id/analyse` | ✅ | Trigger AI deal analysis |
| POST | `/api/products/:id/discover` | ✅ | Find on other retailers |
| POST | `/api/products/:id/assistant` | ✅ | Compare sourced options against shopping requirements |
| POST | `/api/products/:id/listings` | ✅ | Add another retailer URL |
| GET | `/api/alerts` | ✅ | Alert history |
| PATCH | `/api/trackers/:id` | ✅ | Update alert preferences |
| GET | `/api/metrics` | — | Public system metrics |
| GET | `/api/meta/capabilities` | — | What this deployment supports |

## Tech Stack Summary

| Layer | Technology | Why |
|-------|-----------|-----|
| Backend | Spring Boot 3.2.5 + Java 17 | Industry standard, great for REST APIs |
| ORM | Hibernate + JPA | Type-safe queries, entity mapping |
| Migrations | Flyway | Version-controlled schema |
| Auth | JWT (jjwt) + Spring Security | Stateless, simple |
| Scraping | BrowserAct Workflow API | Per-retailer workflows with strict product/price validation; no fallback |
| AI | Configurable OpenAI-compatible / Anthropic API | Shopping advice and deal analysis with usage limits |
| Discovery | Tavily Search API | Cross-retailer product matching |
| Frontend | React 18 + React Router 6 | SPA with client-side routing |
| Build | Vite 5 | Fast dev server with API proxy |
| Database | PostgreSQL 16 | Reliable, handles money types well |
| Deploy | Docker Compose + nginx | Single-box deployment with TLS |

## License

MIT

### Product research and buying plans

The advisor uses saved conversations, budget and feature requirements, sourced research and stored price history to support buying decisions. Forecasts require sufficient historical evidence.
