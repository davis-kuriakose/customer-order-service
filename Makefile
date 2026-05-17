# ============================================================
#  Makefile — customer-order-service convenience targets
#
#  Requires: Docker with Compose v2 plugin (docker compose)
#            Maven 3.9+ and Java 21 for local test targets
#
#  Usage:
#    make build        — build both service images
#    make up           — start all services (detached)
#    make down         — stop and remove containers (keep volumes)
#    make logs         — tail logs for all services
#    make test-unit    — run unit tests only (skips ITs, no Docker)
#    make test-all     — run full test suite (skips ITs by default;
#                        add -DskipITs=false to include them)
# ============================================================

COMPOSE        := docker compose
COMPOSE_FILE   := -f docker-compose.yml
MVN            := mvn

.PHONY: build up down logs test-unit test-all

## Build both service images (uses docker-compose.yml, no override)
build:
	$(COMPOSE) $(COMPOSE_FILE) build --no-cache

## Start all services in detached mode (override applied automatically)
up:
	$(COMPOSE) up -d

## Stop and remove containers; volumes are preserved
down:
	$(COMPOSE) down

## Follow logs for all running services (Ctrl-C to stop)
logs:
	$(COMPOSE) logs -f

## Run unit tests only — skips integration tests, no Docker needed
## Passes -DskipITs=true so any Failsafe-bound IT classes are excluded
test-unit:
	$(MVN) verify -DskipITs=true --no-transfer-progress

## Run the full Maven test suite
## Integration tests that require a running DB are skipped unless
## you have a local DB or pass -DskipITs=false explicitly
test-all:
	$(MVN) verify --no-transfer-progress
