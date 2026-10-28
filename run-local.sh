#!/bin/bash
# ============================================================================
# PricePilot — Local Development Setup & Run
# Run this once to set up everything, then use it to start the app.
# ============================================================================
set -e

cd "$(dirname "$0")"

echo "═══════════════════════════════════════════════════════"
echo "  PricePilot — Local Development"
echo "═══════════════════════════════════════════════════════"

# ── Check prerequisites ──
check_command() {
    if ! command -v "$1" &> /dev/null; then
        echo "❌ $1 is not installed."
        echo "   Install with: brew install $2"
        return 1
    fi
    echo "✅ $1 found"
    return 0
}

MISSING=0
check_command java openjdk@17 || MISSING=1
check_command mvn maven || MISSING=1
check_command node node || MISSING=1
check_command npm node || MISSING=1
check_command psql postgresql@16 || MISSING=1

if [ $MISSING -eq 1 ]; then
    echo ""
    echo "Install missing tools first, then re-run this script."
    exit 1
fi

# ── Set up PostgreSQL database ──
echo ""
echo "── Setting up database ──"

# Check if PostgreSQL is running
if ! pg_isready -q 2>/dev/null; then
    echo "Starting PostgreSQL..."
    brew services start postgresql@16 2>/dev/null || brew services start postgresql 2>/dev/null || true
    sleep 3
fi

# Create database if it doesn't exist
if psql -lqt 2>/dev/null | cut -d \| -f 1 | grep -qw pricepilot; then
    echo "✅ Database 'pricepilot' already exists"
else
    echo "Creating database 'pricepilot'..."
    createdb pricepilot 2>/dev/null || true
    echo "✅ Database created"
fi

# Create user if needed (using the current OS user's superuser access)
psql -d pricepilot -c "
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'pricepilot') THEN
        CREATE ROLE pricepilot WITH LOGIN PASSWORD 'pricepilot';
    END IF;
END
\$\$;
GRANT ALL PRIVILEGES ON DATABASE pricepilot TO pricepilot;
GRANT ALL ON SCHEMA public TO pricepilot;
" 2>/dev/null && echo "✅ Database user ready" || echo "⚠️  Could not set up user (may already exist)"

# ── Install frontend dependencies ──
echo ""
echo "── Installing frontend dependencies ──"
if [ ! -d "frontend/node_modules" ]; then
    cd frontend
    npm install
    cd ..
    echo "✅ Frontend dependencies installed"
else
    echo "✅ Frontend dependencies already installed"
fi

echo ""
echo "═══════════════════════════════════════════════════════"
echo "  Setup complete! Starting the app..."
echo "═══════════════════════════════════════════════════════"
echo ""
echo "  Backend:  http://localhost:8080"
echo "  Frontend: http://localhost:5173"
echo ""
echo "  Press Ctrl+C to stop both."
echo ""

# ── Start backend and frontend together ──
# Use dev profile (disables scheduler, enables debug logging)
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev \
    -Dspring-boot.run.jvmArguments="-DDB_URL=jdbc:postgresql://localhost:5432/pricepilot -DPOSTGRES_USER=pricepilot -DPOSTGRES_PASSWORD=pricepilot -DJWT_SECRET=dev-only-secret-change-me-this-must-be-32-bytes-or-more" &
BACKEND_PID=$!
cd ..

cd frontend
npm run dev &
FRONTEND_PID=$!
cd ..

# Handle Ctrl+C gracefully
trap "echo ''; echo 'Stopping...'; kill $BACKEND_PID $FRONTEND_PID 2>/dev/null; exit 0" INT TERM

echo "Backend PID: $BACKEND_PID"
echo "Frontend PID: $FRONTEND_PID"
echo ""

wait
