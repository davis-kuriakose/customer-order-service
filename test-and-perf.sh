#!/usr/bin/env bash
# =============================================================================
#  Customer Order Service — End-to-End Verification & Performance Script
#  Services:  order-service  (localhost:8080)
#             catalog-service (localhost:8081)
# =============================================================================
set -uo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
ORDER_BASE="http://localhost:8080"
CATALOG_BASE="http://localhost:8081"
ORDER_KEY="dev-api-key"
CATALOG_KEY="internal-catalog-key"
PERF_CONCURRENCY=10       # parallel curl jobs for perf test
PERF_REQUESTS=100         # total requests per perf scenario
REPORT_DIR="$(pwd)/test-reports"
REPORT_FILE="$REPORT_DIR/report-$(date +%Y%m%d-%H%M%S).txt"
HTML_REPORT="$REPORT_DIR/report-$(date +%Y%m%d-%H%M%S).html"

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

# ── Counters ──────────────────────────────────────────────────────────────────
PASS=0; FAIL=0; WARN=0
declare -a RESULTS=()   # collects "STATUS | label | detail"

mkdir -p "$REPORT_DIR"

# =============================================================================
# Helpers
# =============================================================================
log_section() {
    local msg="$1"
    echo ""
    echo -e "${BOLD}${BLUE}══════════════════════════════════════════════════════${RESET}"
    echo -e "${BOLD}${BLUE}  $msg${RESET}"
    echo -e "${BOLD}${BLUE}══════════════════════════════════════════════════════${RESET}"
}

pass() {
    local label="$1" detail="${2:-}"
    echo -e "  ${GREEN}✓ PASS${RESET} $label${detail:+ — $detail}"
    RESULTS+=("PASS|$label|$detail")
    PASS=$(( PASS + 1 ))
}

fail() {
    local label="$1" detail="${2:-}"
    echo -e "  ${RED}✗ FAIL${RESET} $label${detail:+ — $detail}"
    RESULTS+=("FAIL|$label|$detail")
    FAIL=$(( FAIL + 1 ))
}

warn() {
    local label="$1" detail="${2:-}"
    echo -e "  ${YELLOW}⚠ WARN${RESET} $label${detail:+ — $detail}"
    RESULTS+=("WARN|$label|$detail")
    WARN=$(( WARN + 1 ))
}

# Run curl; returns: "<http_code> <body>"
api() {
    # Usage: api METHOD URL [-H header]... [-d body] [-T content-type]
    local method="$1" url="$2"; shift 2
    local extra_args=()
    while [[ $# -gt 0 ]]; do
        extra_args+=("$1"); shift
    done
    curl -s -w "\n%{http_code}" -X "$method" "$url" "${extra_args[@]}" 2>/dev/null
}

# Split response into body + code
http_code()  { echo "$1" | tail -1; }
http_body()  { echo "$1" | head -n -1; }

# JSON field extractor
jq_get() { echo "$1" | jq -r "${2}" 2>/dev/null; }

assert_status() {
    local label="$1" resp="$2" expected="$3"
    local actual; actual=$(http_code "$resp")
    if [[ "$actual" == "$expected" ]]; then
        pass "$label" "HTTP $actual"
    else
        fail "$label" "expected HTTP $expected, got HTTP $actual — $(http_body "$resp" | jq -c . 2>/dev/null | head -c 200)"
    fi
}

assert_field() {
    local label="$1" resp="$2" path="$3" expected="$4"
    local actual; actual=$(jq_get "$(http_body "$resp")" "$path")
    if [[ "$actual" == "$expected" ]]; then
        pass "$label" "$path = \"$actual\""
    else
        fail "$label" "expected $path=\"$expected\", got \"$actual\""
    fi
}

assert_not_empty() {
    local label="$1" value="$2"
    if [[ -n "$value" && "$value" != "null" ]]; then
        pass "$label" "value present"
    else
        fail "$label" "expected non-empty/non-null value"
    fi
}

# =============================================================================
#  SECTION 0 — Preflight: Services Running?
# =============================================================================
log_section "SECTION 0 — PREFLIGHT"

for svc_url in "$ORDER_BASE/actuator/health" "$CATALOG_BASE/actuator/health"; do
    resp=$(curl -s -o /dev/null -w "%{http_code}" "$svc_url" 2>/dev/null || echo "000")
    svc_name=$(echo "$svc_url" | grep -oP 'localhost:\d+')
    if [[ "$resp" == "200" ]]; then
        pass "Service $svc_name is reachable"
    else
        fail "Service $svc_name unreachable (HTTP $resp) — aborting"
        echo -e "${RED}Cannot continue: service not running.${RESET}"
        exit 1
    fi
done

# =============================================================================
#  SECTION 1 — Actuator & Health
# =============================================================================
log_section "SECTION 1 — ACTUATOR & HEALTH"

for svc in "order-service $ORDER_BASE $ORDER_KEY" "catalog-service $CATALOG_BASE $CATALOG_KEY"; do
    read -r name base key <<< "$svc"

    r=$(api GET "$base/actuator/health")
    assert_status "$name /actuator/health" "$r" "200"
    assert_field  "$name health status=UP" "$r" ".status" "UP"

    r=$(api GET "$base/actuator/info" -H "X-API-Key: $key")
    code=$(http_code "$r")
    if [[ "$code" == "200" ]]; then
        pass "$name /actuator/info accessible"
    else
        warn "$name /actuator/info returned HTTP $code"
    fi
done

# =============================================================================
#  SECTION 2 — Authentication: API Key Enforcement
# =============================================================================
log_section "SECTION 2 — AUTHENTICATION"

# Order service — no key
r=$(api GET "$ORDER_BASE/customer-orders")
assert_status "Order list — no API key → 401" "$r" "401"

# Order service — wrong key
r=$(api GET "$ORDER_BASE/customer-orders" -H "X-API-Key: wrong-key")
assert_status "Order list — wrong API key → 401" "$r" "401"

# Catalog service — no key
r=$(api GET "$CATALOG_BASE/product-offerings")
assert_status "Catalog list — no API key → 401" "$r" "401"

# Catalog service — wrong key
r=$(api GET "$CATALOG_BASE/product-offerings" -H "X-API-Key: bad-key")
assert_status "Catalog list — wrong API key → 401" "$r" "401"

# =============================================================================
#  SECTION 3 — Catalog Service: Product Offerings
# =============================================================================
log_section "SECTION 3 — CATALOG SERVICE"

CAUTH="-H X-API-Key: $CATALOG_KEY"

# List all
r=$(api GET "$CATALOG_BASE/product-offerings" -H "X-API-Key: $CATALOG_KEY")
assert_status "GET /product-offerings — 200" "$r" "200"
count=$(jq_get "$(http_body "$r")" ". | length")
if [[ "$count" -ge 1 ]]; then
    pass "GET /product-offerings — returns $count offerings"
else
    fail "GET /product-offerings — expected at least 1 offering"
fi

# Get by id
for po_id in po-1 po-2 po-3; do
    r=$(api GET "$CATALOG_BASE/product-offerings/$po_id" -H "X-API-Key: $CATALOG_KEY")
    assert_status "GET /product-offerings/$po_id — 200" "$r" "200"
    id_val=$(jq_get "$(http_body "$r")" ".id")
    if [[ "$id_val" == "$po_id" ]]; then
        pass "GET /product-offerings/$po_id — id matches"
    else
        fail "GET /product-offerings/$po_id — id mismatch: got $id_val"
    fi
    price_val=$(jq_get "$(http_body "$r")" ".price")
    assert_not_empty "GET /product-offerings/$po_id — price present" "$price_val"
    name_val=$(jq_get "$(http_body "$r")" ".name")
    assert_not_empty "GET /product-offerings/$po_id — name present" "$name_val"
done

# Not found
r=$(api GET "$CATALOG_BASE/product-offerings/po-9999" -H "X-API-Key: $CATALOG_KEY")
assert_status "GET /product-offerings/po-9999 — 404" "$r" "404"

# =============================================================================
#  SECTION 4 — Order Service: Create Orders (POST)
# =============================================================================
log_section "SECTION 4 — ORDER SERVICE: CREATE"

OAUTH="-H" ; OAUTHVAL="X-API-Key: $ORDER_KEY"

ORDER_PAYLOAD_B2B='{
  "category": "B2B",
  "customerId": "cust-001",
  "siteId": "site-001",
  "orderItems": [{"productOfferingId": "po-1", "quantity": 2}],
  "paymentMethodType": "INVOICE"
}'

ORDER_PAYLOAD_B2C='{
  "category": "B2C",
  "customerId": "cust-002",
  "siteId": "site-002",
  "orderItems": [
    {"productOfferingId": "po-2", "quantity": 1},
    {"productOfferingId": "po-3", "quantity": 3}
  ],
  "paymentMethodType": "DIRECT_DEBIT",
  "paymentMethodIban": "DE89370400440532013000"
}'

# Create B2B order
r=$(api POST "$ORDER_BASE/customer-orders" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/json" \
    -d "$ORDER_PAYLOAD_B2B")
assert_status "POST /customer-orders B2B — 201" "$r" "201"
ORDER_B2B_ID=$(jq_get "$(http_body "$r")" ".id")
assert_not_empty "POST /customer-orders B2B — id in response" "$ORDER_B2B_ID"
assert_field "POST /customer-orders B2B — state=DRAFT" "$r" ".state" "DRAFT"
assert_field "POST /customer-orders B2B — category=B2B" "$r" ".category" "B2B"
assert_field "POST /customer-orders B2B — customerId" "$r" ".customerId" "cust-001"

# Create B2C order
r=$(api POST "$ORDER_BASE/customer-orders" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/json" \
    -d "$ORDER_PAYLOAD_B2C")
assert_status "POST /customer-orders B2C — 201" "$r" "201"
ORDER_B2C_ID=$(jq_get "$(http_body "$r")" ".id")
assert_not_empty "POST /customer-orders B2C — id in response" "$ORDER_B2C_ID"
assert_field "POST /customer-orders B2C — state=DRAFT" "$r" ".state" "DRAFT"
assert_field "POST /customer-orders B2C — category=B2C" "$r" ".category" "B2C"
assert_field "POST /customer-orders B2C — 2 items" "$r" ".orderItems | length" "2"

echo "  → Created B2B order: $ORDER_B2B_ID"
echo "  → Created B2C order: $ORDER_B2C_ID"

# =============================================================================
#  SECTION 5 — Order Service: Validation Errors
# =============================================================================
log_section "SECTION 5 — VALIDATION ERRORS"

# Missing customerId
r=$(api POST "$ORDER_BASE/customer-orders" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/json" \
    -d '{"category":"B2B","siteId":"s1","orderItems":[{"productOfferingId":"po-1","quantity":1}],"paymentMethodType":"INVOICE"}')
assert_status "POST missing customerId → 400" "$r" "400"

# Missing orderItems
r=$(api POST "$ORDER_BASE/customer-orders" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/json" \
    -d '{"category":"B2B","customerId":"c1","siteId":"s1","orderItems":[],"paymentMethodType":"INVOICE"}')
assert_status "POST empty orderItems → 400" "$r" "400"

# Missing category
r=$(api POST "$ORDER_BASE/customer-orders" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/json" \
    -d '{"customerId":"c1","siteId":"s1","orderItems":[{"productOfferingId":"po-1","quantity":1}],"paymentMethodType":"INVOICE"}')
assert_status "POST missing category → 400" "$r" "400"

# Zero quantity
r=$(api POST "$ORDER_BASE/customer-orders" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/json" \
    -d '{"category":"B2B","customerId":"c1","siteId":"s1","orderItems":[{"productOfferingId":"po-1","quantity":0}],"paymentMethodType":"INVOICE"}')
assert_status "POST quantity=0 → 400" "$r" "400"

# Missing paymentMethodType
r=$(api POST "$ORDER_BASE/customer-orders" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/json" \
    -d '{"category":"B2B","customerId":"c1","siteId":"s1","orderItems":[{"productOfferingId":"po-1","quantity":1}]}')
assert_status "POST missing paymentMethodType → 400" "$r" "400"

# =============================================================================
#  SECTION 6 — Order Service: GET (single + list)
# =============================================================================
log_section "SECTION 6 — ORDER GET & LIST"

# Get by ID
r=$(api GET "$ORDER_BASE/customer-orders/$ORDER_B2B_ID" -H "X-API-Key: $ORDER_KEY")
assert_status "GET /customer-orders/$ORDER_B2B_ID — 200" "$r" "200"
assert_field "GET order — id matches" "$r" ".id" "$ORDER_B2B_ID"
assert_field "GET order — state=DRAFT" "$r" ".state" "DRAFT"

# Not found
FAKE_ID="00000000-0000-0000-0000-000000000000"
r=$(api GET "$ORDER_BASE/customer-orders/$FAKE_ID" -H "X-API-Key: $ORDER_KEY")
assert_status "GET non-existent order → 404" "$r" "404"

# List all
r=$(api GET "$ORDER_BASE/customer-orders" -H "X-API-Key: $ORDER_KEY")
assert_status "GET /customer-orders — 200" "$r" "200"
total=$(jq_get "$(http_body "$r")" ".total")
if [[ "$total" -ge 2 ]]; then
    pass "GET /customer-orders — total=$total (≥2)"
else
    fail "GET /customer-orders — expected ≥2 orders, got $total"
fi

# Filter by category B2B
r=$(api GET "$ORDER_BASE/customer-orders?category=B2B" -H "X-API-Key: $ORDER_KEY")
assert_status "GET orders?category=B2B — 200" "$r" "200"
b2b_total=$(jq_get "$(http_body "$r")" ".total")
assert_not_empty "GET orders?category=B2B — has results" "$b2b_total"

# Filter by category B2C
r=$(api GET "$ORDER_BASE/customer-orders?category=B2C" -H "X-API-Key: $ORDER_KEY")
assert_status "GET orders?category=B2C — 200" "$r" "200"

# Pagination
r=$(api GET "$ORDER_BASE/customer-orders?limit=1&offset=0" -H "X-API-Key: $ORDER_KEY")
assert_status "GET orders?limit=1 — 200" "$r" "200"
items_count=$(jq_get "$(http_body "$r")" ".items | length")
if [[ "$items_count" -le 1 ]]; then
    pass "GET orders?limit=1 — returns ≤1 item"
else
    fail "GET orders?limit=1 — returned $items_count items (expected ≤1)"
fi

# Invalid limit (> 100)
r=$(api GET "$ORDER_BASE/customer-orders?limit=999" -H "X-API-Key: $ORDER_KEY")
assert_status "GET orders?limit=999 → 400" "$r" "400"

# =============================================================================
#  SECTION 7 — Order Service: PATCH (field updates)
# =============================================================================
log_section "SECTION 7 — ORDER PATCH (FIELD UPDATES)"

# Patch customerId in DRAFT
r=$(api PATCH "$ORDER_BASE/customer-orders/$ORDER_B2B_ID" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/merge-patch+json" \
    -d '{"customerId":"cust-updated"}')
assert_status "PATCH order — update customerId — 200" "$r" "200"
assert_field "PATCH order — customerId updated" "$r" ".customerId" "cust-updated"
assert_field "PATCH order — state still DRAFT" "$r" ".state" "DRAFT"

# Patch orderItems in DRAFT
r=$(api PATCH "$ORDER_BASE/customer-orders/$ORDER_B2B_ID" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/merge-patch+json" \
    -d '{"orderItems":[{"productOfferingId":"po-2","quantity":5}]}')
assert_status "PATCH order — update orderItems — 200" "$r" "200"
assert_field "PATCH order — orderItems updated (1 item)" "$r" ".orderItems | length" "1"

# Patch with wrong Content-Type (should fail)
r=$(api PATCH "$ORDER_BASE/customer-orders/$ORDER_B2B_ID" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/json" \
    -d '{"customerId":"bad-patch"}')
code=$(http_code "$r")
if [[ "$code" == "415" || "$code" == "400" ]]; then
    pass "PATCH wrong Content-Type → $code (unsupported)"
else
    warn "PATCH wrong Content-Type — got HTTP $code (expected 415/400)"
fi

# =============================================================================
#  SECTION 8 — State Machine: Full Lifecycle DRAFT→PREVIEW→SUBMITTED→CONFIRMED
# =============================================================================
log_section "SECTION 8 — STATE MACHINE TRANSITIONS"

# Create a fresh order for state machine test
r=$(api POST "$ORDER_BASE/customer-orders" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/json" \
    -d "$ORDER_PAYLOAD_B2B")
assert_status "State test — create order — 201" "$r" "201"
SM_ORDER_ID=$(jq_get "$(http_body "$r")" ".id")
echo "  → State machine order: $SM_ORDER_ID"

# DRAFT → PREVIEW
r=$(api PATCH "$ORDER_BASE/customer-orders/$SM_ORDER_ID" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/merge-patch+json" \
    -d '{"targetStateName":"PREVIEW"}')
assert_status "State: DRAFT → PREVIEW — 200" "$r" "200"
assert_field "State: now PREVIEW" "$r" ".state" "PREVIEW"

# PREVIEW: patch a field (still allowed)
r=$(api PATCH "$ORDER_BASE/customer-orders/$SM_ORDER_ID" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/merge-patch+json" \
    -d '{"siteId":"site-preview-updated"}')
assert_status "State: PREVIEW field patch — 200" "$r" "200"
assert_field "State: PREVIEW siteId updated" "$r" ".siteId" "site-preview-updated"

# PREVIEW → DRAFT (back-transition allowed)
r=$(api PATCH "$ORDER_BASE/customer-orders/$SM_ORDER_ID" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/merge-patch+json" \
    -d '{"targetStateName":"DRAFT"}')
assert_status "State: PREVIEW → DRAFT — 200" "$r" "200"
assert_field "State: back to DRAFT" "$r" ".state" "DRAFT"

# DRAFT → PREVIEW again → SUBMITTED
r=$(api PATCH "$ORDER_BASE/customer-orders/$SM_ORDER_ID" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/merge-patch+json" \
    -d '{"targetStateName":"PREVIEW"}')
assert_status "State: DRAFT → PREVIEW (2nd) — 200" "$r" "200"

r=$(api PATCH "$ORDER_BASE/customer-orders/$SM_ORDER_ID" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/merge-patch+json" \
    -d '{"targetStateName":"SUBMITTED"}')
assert_status "State: PREVIEW → SUBMITTED — 200" "$r" "200"
assert_field "State: now SUBMITTED" "$r" ".state" "SUBMITTED"

# SUBMITTED: reject payload mutation (only state transitions allowed)
r=$(api PATCH "$ORDER_BASE/customer-orders/$SM_ORDER_ID" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/merge-patch+json" \
    -d '{"customerId":"should-fail"}')
assert_status "State: SUBMITTED payload patch rejected → 422" "$r" "422"

# Invalid transition from SUBMITTED (SUBMITTED → PREVIEW is illegal)
r=$(api PATCH "$ORDER_BASE/customer-orders/$SM_ORDER_ID" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/merge-patch+json" \
    -d '{"targetStateName":"PREVIEW"}')
assert_status "State: SUBMITTED → PREVIEW invalid → 422" "$r" "422"

# SUBMITTED → CONFIRMED
r=$(api PATCH "$ORDER_BASE/customer-orders/$SM_ORDER_ID" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/merge-patch+json" \
    -d '{"targetStateName":"CONFIRMED"}')
assert_status "State: SUBMITTED → CONFIRMED — 200" "$r" "200"
assert_field "State: now CONFIRMED" "$r" ".state" "CONFIRMED"

# CONFIRMED: all patches rejected
r=$(api PATCH "$ORDER_BASE/customer-orders/$SM_ORDER_ID" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/merge-patch+json" \
    -d '{"customerId":"denied"}')
assert_status "State: CONFIRMED all patches rejected → 422" "$r" "422"

r=$(api PATCH "$ORDER_BASE/customer-orders/$SM_ORDER_ID" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/merge-patch+json" \
    -d '{"targetStateName":"DRAFT"}')
assert_status "State: CONFIRMED → DRAFT invalid → 422" "$r" "422"

# =============================================================================
#  SECTION 9 — Idempotency
# =============================================================================
log_section "SECTION 9 — IDEMPOTENCY"

IDEM_KEY="idem-$(date +%s)-$(( RANDOM % 9999 ))"

# First request
r1=$(api POST "$ORDER_BASE/customer-orders" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $IDEM_KEY" \
    -d "$ORDER_PAYLOAD_B2B")
assert_status "Idempotency first call — 201" "$r1" "201"
IDEM_ORDER_ID=$(jq_get "$(http_body "$r1")" ".id")
echo "  → Idempotent order: $IDEM_ORDER_ID"

# Replay: same key, same payload → must return same order
r2=$(api POST "$ORDER_BASE/customer-orders" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $IDEM_KEY" \
    -d "$ORDER_PAYLOAD_B2B")
code2=$(http_code "$r2")
replay_id=$(jq_get "$(http_body "$r2")" ".id")
replayed_hdr=$(echo "$r2" | grep -i "X-Idempotent-Replayed" || true)

if [[ "$code2" == "200" || "$code2" == "201" ]]; then
    if [[ "$replay_id" == "$IDEM_ORDER_ID" ]]; then
        pass "Idempotency replay — same order ID returned"
    else
        fail "Idempotency replay — different ID: got $replay_id, expected $IDEM_ORDER_ID"
    fi
else
    fail "Idempotency replay — expected 200/201, got $code2"
fi

# Conflict: same key, different payload → 409
CONFLICT_KEY="conflict-$(date +%s)-$(( RANDOM % 9999 ))"
r=$(api POST "$ORDER_BASE/customer-orders" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $CONFLICT_KEY" \
    -d "$ORDER_PAYLOAD_B2B")
assert_status "Idempotency conflict — first call 201" "$r" "201"

r=$(api POST "$ORDER_BASE/customer-orders" \
    -H "X-API-Key: $ORDER_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $CONFLICT_KEY" \
    -d "$ORDER_PAYLOAD_B2C")
assert_status "Idempotency conflict — different payload → 409" "$r" "409"

# =============================================================================
#  SECTION 10 — OpenAPI / Swagger
# =============================================================================
log_section "SECTION 10 — OPENAPI / SWAGGER"

r=$(api GET "$ORDER_BASE/v3/api-docs" -H "X-API-Key: $ORDER_KEY")
code=$(http_code "$r")
if [[ "$code" == "200" ]]; then
    paths_count=$(jq_get "$(http_body "$r")" ".paths | keys | length")
    pass "GET /v3/api-docs — $paths_count paths documented"
    title=$(jq_get "$(http_body "$r")" ".info.title")
    assert_not_empty "OpenAPI title present" "$title"
else
    warn "GET /v3/api-docs — HTTP $code"
fi

r=$(api GET "$ORDER_BASE/swagger-ui/index.html")
code=$(http_code "$r")
if [[ "$code" == "200" ]]; then
    pass "GET /swagger-ui/index.html — accessible"
else
    warn "GET /swagger-ui/index.html — HTTP $code"
fi

# =============================================================================
#  SECTION 11 — PERFORMANCE TESTING (curl-based)
# =============================================================================
log_section "SECTION 11 — PERFORMANCE TESTING"

# Runs N requests with C concurrency using a temp script + xargs -P
# Writes one "<http_code> <time_ms>" line per request into $PERF_TMPDIR
PERF_TMPDIR=""

run_perf() {
    local label="$1" n="$2" c="$3" script="$4"
    echo -e "  ${CYAN}Running: $label ($n requests, $c concurrent)${RESET}"

    PERF_TMPDIR=$(mktemp -d)
    export PERF_TMPDIR

    # xargs -P runs $c copies of bash in parallel; each copy executes $script
    seq 1 "$n" | xargs -P "$c" -I IDX bash -c "$script IDX"

    # Collect + analyse
    local total=0 min_ms=99999 max_ms=0 ok=0 err=0
    local all_ms=()
    for f in "$PERF_TMPDIR"/*; do
        [[ -f "$f" ]] || continue
        read -r code ms < "$f" 2>/dev/null || continue
        all_ms+=("$ms")
        total=$(( total + ms ))
        [[ $ms -lt $min_ms ]] && min_ms=$ms
        [[ $ms -gt $max_ms ]] && max_ms=$ms
        if [[ "$code" =~ ^[2] ]]; then ok=$(( ok + 1 ))
        else err=$(( err + 1 )); fi
    done
    rm -rf "$PERF_TMPDIR"; PERF_TMPDIR=""

    local count="${#all_ms[@]}"
    if [[ $count -eq 0 ]]; then
        fail "$label — no responses recorded"
        return
    fi

    local avg=$(( total / count ))
    local sorted_ms
    mapfile -t sorted_ms < <(printf "%s\n" "${all_ms[@]}" | sort -n)
    local median=${sorted_ms[$(( count / 2 ))]}
    local p95=${sorted_ms[$(( count * 95 / 100 ))]}
    local rps; rps=$(awk "BEGIN{if($avg>0) printf \"%.1f\", 1000/$avg; else print \"N/A\"}")

    echo -e "    Requests: $count | Success: ${GREEN}$ok${RESET} | Errors: ${RED}$err${RESET}"
    echo -e "    Latency  Min: ${min_ms}ms | Avg: ${avg}ms | Median: ${median}ms | P95: ${p95}ms | Max: ${max_ms}ms"
    echo -e "    Throughput: ~$rps req/s (per worker)"

    RESULTS+=("PERF|$label|success=$ok errors=$err avg=${avg}ms median=${median}ms p95=${p95}ms max=${max_ms}ms")
    if [[ $err -gt 0 ]]; then
        fail "$label — $err/$count requests failed"
    elif [[ $avg -gt 2000 ]]; then
        warn "$label — avg ${avg}ms exceeds 2000ms threshold"
    else
        pass "$label — avg=${avg}ms p95=${p95}ms"
    fi
}

# ── Scenario helpers (exported so xargs subshells can see them) ───────────────
_curl_ms() {
    # Runs curl, writes "<code> <ms>" to the given file.
    # Uses awk BEGIN{} so no stdin is needed for the multiplication.
    local out="$1"; shift
    local raw; raw=$(curl -s -o /dev/null -w "%{http_code} %{time_total}" "$@")
    local code="${raw%% *}" t="${raw##* }"
    local ms; ms=$(awk "BEGIN{printf \"%.0f\", $t * 1000}" 2>/dev/null || echo 1)
    echo "$code $ms" > "$out"
}
export -f _curl_ms

_ORDER_LIST_URL="$ORDER_BASE/customer-orders?limit=20"
_ORDER_KEY="$ORDER_KEY"
_ORDER_ID="$ORDER_B2B_ID"
export _ORDER_LIST_URL _ORDER_KEY _ORDER_ID

do_get_catalog() {
    _curl_ms "$PERF_TMPDIR/$1" \
        -H "X-API-Key: internal-catalog-key" \
        "http://localhost:8081/product-offerings"
}
export -f do_get_catalog

do_get_health() {
    _curl_ms "$PERF_TMPDIR/$1" \
        "http://localhost:8080/actuator/health"
}
export -f do_get_health

do_get_list() {
    _curl_ms "$PERF_TMPDIR/$1" \
        -H "X-API-Key: $_ORDER_KEY" "$_ORDER_LIST_URL"
}
export -f do_get_list

do_get_order() {
    _curl_ms "$PERF_TMPDIR/$1" \
        -H "X-API-Key: $_ORDER_KEY" \
        "http://localhost:8080/customer-orders/$_ORDER_ID"
}
export -f do_get_order

do_post_order() {
    _curl_ms "$PERF_TMPDIR/$1" \
        -X POST "http://localhost:8080/customer-orders" \
        -H "X-API-Key: $_ORDER_KEY" \
        -H "Content-Type: application/json" \
        -d '{"category":"B2B","customerId":"perf-cust","siteId":"perf-site","orderItems":[{"productOfferingId":"po-1","quantity":1}],"paymentMethodType":"INVOICE"}'
}
export -f do_post_order

# ── Run scenarios ─────────────────────────────────────────────────────────────
run_perf "GET /product-offerings (catalog)"      "$PERF_REQUESTS" "$PERF_CONCURRENCY" "do_get_catalog"
run_perf "GET /actuator/health (order-service)"  "$PERF_REQUESTS" "$PERF_CONCURRENCY" "do_get_health"
run_perf "GET /customer-orders (list)"           "$PERF_REQUESTS" "$PERF_CONCURRENCY" "do_get_list"
run_perf "GET /customer-orders/:id (single)"     "$PERF_REQUESTS" "$PERF_CONCURRENCY" "do_get_order"
run_perf "POST /customer-orders (write)"         20               5                   "do_post_order"

# =============================================================================
#  SECTION 12 — Container Health Summary
# =============================================================================
log_section "SECTION 12 — DOCKER CONTAINER STATUS"

while IFS= read -r line; do
    name=$(echo "$line" | awk '{print $1}')
    status=$(echo "$line" | grep -oP 'Up [^,]+')
    health=$(echo "$line" | grep -oP '\(healthy\)|\(unhealthy\)' || echo "")
    if echo "$line" | grep -q "(healthy)"; then
        pass "Container $name" "$status (healthy)"
    elif echo "$line" | grep -q "Up"; then
        warn "Container $name" "$status — no healthcheck or starting"
    else
        fail "Container $name" "not running"
    fi
done < <(docker compose -f "$(dirname "$0")/docker-compose.yml" ps 2>/dev/null | tail -n +2)

# =============================================================================
#  REPORT GENERATION
# =============================================================================
log_section "TEST SUMMARY"

TOTAL=$(( PASS + FAIL + WARN ))
echo ""
echo -e "  Total:   ${BOLD}$TOTAL${RESET}"
echo -e "  ${GREEN}Passed:  $PASS${RESET}"
echo -e "  ${RED}Failed:  $FAIL${RESET}"
echo -e "  ${YELLOW}Warnings:$WARN${RESET}"
echo ""
if [[ $FAIL -eq 0 ]]; then
    echo -e "  ${GREEN}${BOLD}✓ ALL CHECKS PASSED${RESET}"
else
    echo -e "  ${RED}${BOLD}✗ $FAIL CHECK(S) FAILED${RESET}"
fi

# ── Plain-text report ─────────────────────────────────────────────────────────
{
    echo "Customer Order Service — Verification Report"
    echo "Generated: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "Services:  order-service@8080  catalog-service@8081"
    echo "============================================================"
    echo ""
    echo "SUMMARY: PASS=$PASS  FAIL=$FAIL  WARN=$WARN  TOTAL=$TOTAL"
    echo ""
    echo "DETAILED RESULTS"
    echo "------------------------------------------------------------"
    for r in "${RESULTS[@]}"; do
        IFS='|' read -r status label detail <<< "$r"
        printf "%-6s  %-60s  %s\n" "$status" "$label" "$detail"
    done
} > "$REPORT_FILE"

# ── HTML report ───────────────────────────────────────────────────────────────
{
cat <<'HTMLHEAD'
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Customer Order Service — Test Report</title>
<style>
  body{font-family:monospace;background:#1e1e1e;color:#d4d4d4;padding:24px;}
  h1{color:#4ec9b0;} h2{color:#9cdcfe;border-bottom:1px solid #333;padding-bottom:6px;}
  .summary{display:flex;gap:32px;margin:16px 0;}
  .badge{padding:8px 20px;border-radius:6px;font-size:1.2em;font-weight:bold;}
  .pass-badge{background:#1e4d2b;color:#4ec994;}
  .fail-badge{background:#4d1e1e;color:#f44747;}
  .warn-badge{background:#4d3e1e;color:#dcdcaa;}
  table{border-collapse:collapse;width:100%;margin-top:12px;}
  th{background:#252526;color:#9cdcfe;text-align:left;padding:8px 12px;}
  td{padding:7px 12px;border-bottom:1px solid #2d2d2d;font-size:0.9em;}
  tr:hover td{background:#2a2d2e;}
  .PASS td:first-child{color:#4ec994;}
  .FAIL td:first-child{color:#f44747;}
  .WARN td:first-child{color:#dcdcaa;}
  .PERF td:first-child{color:#c586c0;}
  .footer{margin-top:32px;color:#6b6b6b;font-size:0.8em;}
</style>
</head>
<body>
HTMLHEAD

echo "<h1>Customer Order Service — Verification Report</h1>"
echo "<p>Generated: $(date '+%Y-%m-%d %H:%M:%S') &nbsp;|&nbsp; order-service:8080 &nbsp;|&nbsp; catalog-service:8081</p>"
echo "<div class='summary'>"
echo "  <div class='badge pass-badge'>✓ PASS: $PASS</div>"
echo "  <div class='badge fail-badge'>✗ FAIL: $FAIL</div>"
echo "  <div class='badge warn-badge'>⚠ WARN: $WARN</div>"
echo "</div>"
echo "<h2>Detailed Results</h2>"
echo "<table><tr><th>Status</th><th>Test</th><th>Detail</th></tr>"
for r in "${RESULTS[@]}"; do
    IFS='|' read -r status label detail <<< "$r"
    echo "<tr class='$status'><td>$status</td><td>$(echo "$label" | sed 's/</\&lt;/g;s/>/\&gt;/g')</td><td>$(echo "$detail" | sed 's/</\&lt;/g;s/>/\&gt;/g')</td></tr>"
done
echo "</table>"
echo "<div class='footer'>Script: test-and-perf.sh &nbsp;|&nbsp; Concurrency: $PERF_CONCURRENCY &nbsp;|&nbsp; Perf requests: $PERF_REQUESTS</div>"
echo "</body></html>"

} > "$HTML_REPORT"

echo ""
echo -e "  ${CYAN}Reports saved:${RESET}"
echo -e "    Text: $REPORT_FILE"
echo -e "    HTML: $HTML_REPORT"
echo ""

# Exit non-zero if any failures
[[ $FAIL -eq 0 ]]
