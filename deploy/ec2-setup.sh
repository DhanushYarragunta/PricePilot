#!/bin/bash
# ============================================================================
# PricePilot — EC2 Server Setup Script
# Run this ON the EC2 instance after SSH-ing in.
# It installs Docker, clones the repo, and starts the app.
# ============================================================================
set -euo pipefail

echo "═══════════════════════════════════════════════════════"
echo "  PricePilot — EC2 Server Setup"
echo "═══════════════════════════════════════════════════════"

# ── Install Docker ──
echo ""
echo "── Installing Docker ──"
if ! command -v docker &> /dev/null; then
    sudo yum update -y
    sudo yum install -y docker git
    sudo systemctl start docker
    sudo systemctl enable docker
    sudo usermod -a -G docker ec2-user
    echo "✅ Docker installed"
else
    echo "✅ Docker already installed"
fi

# ── Install Docker Compose ──
if ! docker compose version &> /dev/null 2>&1; then
    sudo mkdir -p /usr/local/lib/docker/cli-plugins
    sudo curl -SL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-$(uname -m)" \
        -o /usr/local/lib/docker/cli-plugins/docker-compose
    sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
    echo "✅ Docker Compose installed"
else
    echo "✅ Docker Compose already installed"
fi

echo ""
echo "═══════════════════════════════════════════════════════"
echo "  Docker is ready."
echo ""
echo "  IMPORTANT: Log out and log back in so Docker group"
echo "  permissions take effect, then run:"
echo ""
echo "    cd PricePilot"
echo "    cp .env.example .env"
echo "    nano .env           # fill in your secrets"
echo "    docker compose up -d --build"
echo "    docker compose logs -f app"
echo ""
echo "═══════════════════════════════════════════════════════"
