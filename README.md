# PricePilot

**Real-time price tracking for Indian e-commerce** — Track prices across Amazon.in, Flipkart, Croma, Reliance Digital, and more. Every price is scraped directly from the retailer's own product page, and every check is logged transparently.

![Java 17](https://img.shields.io/badge/Java-17-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-green) ![React](https://img.shields.io/badge/React-18.3-blue) ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)

## What It Does

1. **Paste a product URL** (Amazon.in, Flipkart, Croma, etc.) → the app reads the price directly from the retailer's page
2. **Tracks price changes** automatically with an adaptive scheduler — products that move get checked more often
3. **AI deal analysis** — Claude analyzes whether a price is genuinely good, catching fake discounts
4. **Price alerts** — set a target price and get notified when it drops
5. **Cross-retailer discovery** — automatically finds the same product on other stores (via Tavily)
6. **Full transparency** — every scrape attempt (success or failure) is logged and visible

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

- **Backend**: Spring Boot 3.2, JPA/Hibernate, Flyway migrations, JWT auth, Jsoup scraping
- **Frontend**: React 18, React Router, Vite (zero external UI libraries — all CSS is hand-written)
- **Database**: PostgreSQL 16 with Flyway-managed schema
- **AI**: Claude API for deal analysis (optional — app works without it)
- **Deployment**: Docker Compose with nginx + certbot for production

## Quick Start (Local Development)

### Prerequisites

- **Java 17+** (JDK)
- **Node.js 18+** & npm
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
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The first run downloads dependencies (~2-3 minutes). Flyway creates all tables automatically.

### 3. Start the frontend

```bash
cd frontend
npm install
npm run dev
```

### 4. Open the app

Go to **http://localhost:5173** — create an account and start tracking prices!

## Production Deployment (Docker)

```bash
cp .env.example .env
# Edit .env with real secrets and API keys

docker compose up -d --build
docker compose logs -f app
```

The app runs on a single box with 4 containers: PostgreSQL, Spring Boot, nginx (serving React + proxying API), and certbot for TLS.

## Supported Retailers

| Retailer | Status | Strategy |
|----------|--------|----------|
| Amazon.in | ✅ | JSON-LD + CSS selectors |
| Flipkart | ✅ | Embedded JSON + CSS |
| Croma | ✅ | JSON-LD + CSS |
| Reliance Digital | ✅ | JSON-LD + CSS |
| Vijay Sales | ✅ | CSS selectors |
| Tata CLiQ | ✅ | JSON patterns |
| Nykaa | ✅ | JSON patterns + CSS |
| Myntra | ✅ | State variable extraction |
| JioMart | ✅ | JSON-LD |
| Ajio | ✅ | JSON-LD |

## Key Design Decisions

- **No price is ever fabricated** — every number shown was read from a retailer's page, at the timestamp shown
- **Adaptive scheduling** — listings that change get checked more often; stable ones back off (saves API budget)
- **Budget guards** — hard monthly ceilings on Claude, Tavily, and unblocker APIs prevent runaway spending
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
| POST | `/api/products/:id/refresh` | ✅ | Manual price refresh |
| POST | `/api/products/:id/analyse` | ✅ | Trigger AI deal analysis |
| POST | `/api/products/:id/discover` | ✅ | Find on other retailers |
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
| Scraping | Jsoup + custom extraction chain | JSON-LD → Microdata → Embedded JSON → CSS selectors |
| AI | Claude API (Anthropic) | Deal analysis with budget guards |
| Discovery | Tavily Search API | Cross-retailer product matching |
| Frontend | React 18 + React Router 6 | SPA with client-side routing |
| Build | Vite 5 | Fast dev server with API proxy |
| Database | PostgreSQL 16 | Reliable, handles money types well |
| Deploy | Docker Compose + nginx | Single-box deployment with TLS |

## License

MIT
