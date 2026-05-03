##
## Fleet Management gRPC Demo — Makefile
## Run `make help` to see all available targets.
##

GRADLE       := ./gradlew
LOG_DIR      := .logs
PID_DIR      := .pids

.PHONY: help build test test-vehicle test-trip test-document \
        start-vehicle start-trip start-document start-all stop-all \
        run-client docker-up docker-up-bg docker-up-standalone docker-down clean logs token \
        grpcui-vehicle grpcui-trip grpcui-document grpcui-all grpcui-stop \
        grpcurl-list grpcurl-describe-vehicle grpcurl-describe-trip grpcurl-describe-document

# ─── Default ──────────────────────────────────────────────────────────────────

help: ## Show this help
	@echo ""
	@echo "  Fleet Management gRPC Demo"
	@echo "  ─────────────────────────"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'
	@echo ""

# ─── Build ────────────────────────────────────────────────────────────────────

build: ## Compile all modules (generates proto stubs + compiles Kotlin)
	$(GRADLE) build -x test

generate-proto: ## Re-generate Java/Kotlin stubs from .proto files
	$(GRADLE) :proto:generateProto

# ─── Tests ────────────────────────────────────────────────────────────────────

test: ## Run ALL integration tests (no services need to be running)
	$(GRADLE) test --info

test-vehicle: ## Run vehicle-service tests only
	$(GRADLE) :vehicle-service:test --info

test-trip: ## Run trip-service tests only
	$(GRADLE) :trip-service:test --info

test-document: ## Run document-service tests only
	$(GRADLE) :document-service:test --info

test-report: ## Open HTML test report in browser (macOS)
	open vehicle-service/build/reports/tests/test/index.html
	open trip-service/build/reports/tests/test/index.html
	open document-service/build/reports/tests/test/index.html

# ─── Start / Stop Services ────────────────────────────────────────────────────

start-vehicle: _ensure-dirs ## Start vehicle-service in background (gRPC :9091, REST :8081)
	@echo "Starting vehicle-service..."
	@java -jar vehicle-service/build/libs/vehicle-service-1.0.0.jar \
		> $(LOG_DIR)/vehicle.log 2>&1 & echo $$! > $(PID_DIR)/vehicle.pid
	@echo "  PID $$(cat $(PID_DIR)/vehicle.pid) — logs: $(LOG_DIR)/vehicle.log"

start-trip: _ensure-dirs ## Start trip-service in background (gRPC :9092, REST :8082)
	@echo "Starting trip-service..."
	@java -jar trip-service/build/libs/trip-service-1.0.0.jar \
		> $(LOG_DIR)/trip.log 2>&1 & echo $$! > $(PID_DIR)/trip.pid
	@echo "  PID $$(cat $(PID_DIR)/trip.pid) — logs: $(LOG_DIR)/trip.log"

start-document: _ensure-dirs ## Start document-service in background (gRPC :9093, REST :8083)
	@echo "Starting document-service..."
	@java -jar document-service/build/libs/document-service-1.0.0.jar \
		> $(LOG_DIR)/document.log 2>&1 & echo $$! > $(PID_DIR)/document.pid
	@echo "  PID $$(cat $(PID_DIR)/document.pid) — logs: $(LOG_DIR)/document.log"

start-all: build _ensure-dirs ## Build jars then start all 3 services in background
	@echo "Starting all services (java -jar — no Gradle lock contention)..."
	@$(MAKE) --no-print-directory start-vehicle
	@$(MAKE) --no-print-directory start-trip
	@$(MAKE) --no-print-directory start-document
	@echo "  Waiting for Spring Boot startup..."; sleep 6
	@echo ""
	@echo "All services running:"
	@echo "  vehicle-service  → gRPC :9091  REST :8081"
	@echo "  trip-service     → gRPC :9092  REST :8082"
	@echo "  document-service → gRPC :9093  REST :8083"
	@echo ""
	@echo "Next: run 'make run-client' to execute the demo"

stop-all: ## Stop all running services
	@for svc in vehicle trip document; do \
		if [ -f $(PID_DIR)/$$svc.pid ]; then \
			PID=$$(cat $(PID_DIR)/$$svc.pid); \
			echo "Stopping $$svc-service (PID $$PID)..."; \
			kill $$PID 2>/dev/null && rm $(PID_DIR)/$$svc.pid || true; \
		else \
			echo "$$svc-service not running (no PID file)"; \
		fi; \
	done
	@echo "All services stopped."

# ─── Demo Client ──────────────────────────────────────────────────────────────

run-client: ## Run the gRPC demo client (requires all 3 services to be running)
	@echo "Running Fleet gRPC Demo Client..."
	@echo "Make sure services are started with: make start-all"
	@echo ""
	@java -jar grpc-client/build/libs/grpc-client-1.0.0.jar

# ─── Docker ───────────────────────────────────────────────────────────────────

docker-up: build ## Build jars then start all services via Docker Compose (foreground)
	docker-compose up --build

docker-up-bg: build ## Build jars then start all services via Docker Compose (background)
	docker-compose up --build -d
	@echo "Services started. Use 'make docker-logs' to follow output."

docker-up-standalone: ## Build from source inside Docker — no local toolchain needed (slower first run)
	docker-compose -f docker-compose.standalone.yml up --build

docker-up-standalone-bg: ## Build from source inside Docker, run in background
	docker-compose -f docker-compose.standalone.yml up --build -d
	@echo "Services started. Use 'make docker-logs-standalone' to follow output."

docker-down: ## Stop Docker Compose services (pre-built workflow)
	docker-compose down

docker-down-standalone: ## Stop standalone Docker Compose services
	docker-compose -f docker-compose.standalone.yml down

docker-logs-standalone: ## Tail logs from standalone Docker services
	docker-compose -f docker-compose.standalone.yml logs -f

docker-logs: ## Tail logs from all Docker services
	docker-compose logs -f

# ─── Logs & Status ────────────────────────────────────────────────────────────

logs: ## Tail all service logs (requires services started with make start-all)
	@tail -f $(LOG_DIR)/vehicle.log $(LOG_DIR)/trip.log $(LOG_DIR)/document.log 2>/dev/null \
		|| echo "No logs found. Start services first with: make start-all"

log-vehicle: ## Tail vehicle-service log
	@tail -f $(LOG_DIR)/vehicle.log

log-trip: ## Tail trip-service log
	@tail -f $(LOG_DIR)/trip.log

log-document: ## Tail document-service log
	@tail -f $(LOG_DIR)/document.log

status: ## Show running service PIDs
	@echo "Service Status:"
	@for svc in vehicle trip document; do \
		if [ -f $(PID_DIR)/$$svc.pid ]; then \
			PID=$$(cat $(PID_DIR)/$$svc.pid); \
			if kill -0 $$PID 2>/dev/null; then \
				echo "  ✓ $$svc-service  PID $$PID  (running)"; \
			else \
				echo "  ✗ $$svc-service  PID $$PID  (dead, stale PID file)"; \
			fi; \
		else \
			echo "  - $$svc-service  (not started)"; \
		fi; \
	done

# ─── Utilities ────────────────────────────────────────────────────────────────

token: ## Generate a signed test JWT for use in grpcui / grpcurl
	@python3 -c 'import hmac,hashlib,base64,json,time; b=lambda d:base64.urlsafe_b64encode(d.encode() if isinstance(d,str) else d).rstrip(b"=").decode(); h=b(json.dumps({"alg":"HS256","typ":"JWT"},separators=(",",":"))); p=b(json.dumps({"sub":"test-user","roles":["ADMIN"],"iat":int(time.time())},separators=(",",":"))); m=h+"."+p; s=b(hmac.new("your-256-bit-secret-change-in-production".encode(),m.encode(),hashlib.sha256).digest()); print("\nTest JWT (copy the Bearer value below):\n"); print("Bearer "+m+"."+s+"\n")'

clean: ## Clean all build artifacts
	$(GRADLE) clean
	rm -rf $(LOG_DIR) $(PID_DIR)

_ensure-dirs:
	@mkdir -p $(LOG_DIR) $(PID_DIR)

# ─── gRPC-UI (Swagger equivalent for gRPC) ───────────────────────────────────
# Install: brew install grpcui
# Requires services to be running (make start-all) and grpc-services dep on classpath

grpcui-vehicle: ## Open Swagger-like UI for vehicle-service in browser
	grpcui -plaintext 127.0.0.1:9091

grpcui-trip: ## Open Swagger-like UI for trip-service in browser
	grpcui -plaintext 127.0.0.1:9092

grpcui-document: ## Open Swagger-like UI for document-service in browser
	grpcui -plaintext 127.0.0.1:9093

grpcui-stop: ## Kill any running grpcui background processes
	@pkill -f "grpcui.*plaintext" 2>/dev/null && echo "grpcui processes stopped." || echo "No grpcui processes running."

grpcui-all: grpcui-stop ## Open Swagger-like UI for all 3 services in separate browser tabs
	@grpcui -plaintext -port 8091 127.0.0.1:9091 &
	@grpcui -plaintext -port 8092 127.0.0.1:9092 &
	@grpcui -plaintext -port 8093 127.0.0.1:9093 &
	@sleep 1
	@open http://localhost:8091 http://localhost:8092 http://localhost:8093
	@echo "  vehicle-service UI  → http://localhost:8091"
	@echo "  trip-service    UI  → http://localhost:8092"
	@echo "  document-service UI → http://localhost:8093"

# ─── grpcurl Quick-Reference ──────────────────────────────────────────────────
# (requires brew install grpcurl)

grpcurl-list: ## List available gRPC services on all ports
	@echo "=== vehicle-service ==="
	grpcurl -plaintext 127.0.0.1:9091 list
	@echo "=== trip-service ==="
	grpcurl -plaintext 127.0.0.1:9092 list
	@echo "=== document-service ==="
	grpcurl -plaintext 127.0.0.1:9093 list

grpcurl-describe-vehicle: ## Describe VehicleService methods
	grpcurl -plaintext 127.0.0.1:9091 describe fleetmanagement.vehicle.VehicleService

grpcurl-describe-trip: ## Describe TripService methods
	grpcurl -plaintext 127.0.0.1:9092 describe fleetmanagement.trip.TripService

grpcurl-describe-document: ## Describe DocumentService methods
	grpcurl -plaintext 127.0.0.1:9093 describe fleetmanagement.document.DocumentService
