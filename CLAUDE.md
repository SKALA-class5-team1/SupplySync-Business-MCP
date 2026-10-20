# Business MCP: 작업 지시서

SupplySync-AI(RFQ·재고·구매 대응 Agent)의 **business-mcp 서버**.
AI Agent(LangGraph)가 MCP로 호출하면,
- **읽기 Tool**: 이 서버가 PostgreSQL을 직접 조회(JdbcTemplate, 읽기 전용)해 결과를 돌려준다.
- **쓰기 Tool**: 이 서버가 Spring 백엔드의 `/internal/**` API를 호출한다. DB에 직접 쓰지 않는다 (팀 원칙: 쓰기는 모두 Spring 백엔드를 거친다).
- **RFQ 원문 조회** (`get_rfq_content`): 원문 파일은 백엔드가 보관하므로(`rfqs.file_path`) 읽기지만 백엔드 API를 호출한다.
기본 설정(서버, DB 연결, API 키 필터)은 README.md 참고. 이 문서는 **추가로 구현할 Tool 명세**다.

## 현재 상태
- Spring Boot 3.5.16, Java 21, Spring AI 1.1.8 (`spring-ai-starter-mcp-server-webmvc`), Spring JDBC
- `http://127.0.0.1:8101/mcp`, Streamable HTTP, 모든 요청에 `X-Internal-Api-Key` 헤더 필요
- DB: Supabase PostgreSQL (pooler 세션 모드, `DB_SSLMODE=require`). 7개 테이블 모두 RLS 켜짐
- DB 계정: 조회 전용 `business_mcp_user` (7개 테이블 SELECT + RLS SELECT 정책 `business_mcp_user_select`, `default_transaction_read_only = on`). 다른 테이블 읽기·모든 쓰기는 권한에서 막힘. 서버 커넥션도 읽기 전용(`read-only: true`, `readOnlyMode: always`)
- Tool 9개 구현 완료. 읽기 5개는 실제 DB에서 확인(`LiveDatabaseToolsTest`), 백엔드 API 4개는 백엔드 API가 아직 없어 MockRestServiceServer로만 확인
- 구조는 `ARCHITECTURE.md` 참고

## 작업 범위: Tool 9개
rag-mcp(VectorDB 검색)는 별도 서버라 이 레포 범위가 아니다.

| # | Tool 이름 | 하는 일 | 종류 | 사용 SG |
|---|---|---|---|---|
| 1 | `get_models` | 표준 모델 사양·기본 단가 목록 | 읽기 (DB) | SG1 |
| 2 | `get_options` | 모델에 붙일 수 있는 옵션과 효과·단가·교체 부품 | 읽기 (DB) | SG1, SG3 |
| 3 | `get_bom` | 모델+옵션 기준 BOM 전개 → 부품별 필요 수량 | 읽기 (DB) | SG2, SG4 |
| 4 | `check_parts_supply` | 부품별 재고 충분 여부, 입고·조달 일정, 비용, 대체 부품, 위험 표시 | 읽기 (DB) | SG2, SG4 |
| 5 | `get_parts_cost` | 모델+옵션 기준 부품 원가 합계 | 읽기 (DB) | SG3 |
| 6 | `save_review` | SG1·SG2 분석 결과 저장 | 쓰기 (백엔드 API) | SG1, SG2 |
| 7 | `save_quote` | 견적 초안 저장 (DRAFT) | 쓰기 (백엔드 API) | SG3 |
| 8 | `register_quote` | 승인된 견적 등록. 되돌리기 어려움 | 쓰기 (백엔드 API) | SG4 |
| 9 | `get_rfq_content` | RFQ 번호로 고객이 올린 견적요청서 원문 조회 | 읽기 (백엔드 API) | SG1 |

팀 노션 「MCP」 페이지 번호와의 대응: 노션 1~8 = 이 문서 1~8, 노션 11 = 이 문서 9 (노션 9·10은 rag-mcp).

진행 순서:
- **1단계**: 읽기 Tool 1~5 (DB 없이 H2로 개발 가능, 아래 참고)
- **2단계**: 백엔드 API Tool 6~9 (백엔드 API 없이 MockRestServiceServer로 개발 가능, 아래 참고)

- Tool 이름에는 접두어를 붙이지 않는다. Agent의 MCP 클라이언트가 서버 이름으로 `business_` 접두어를 붙인다.
- Tool은 **사실(수량·날짜·비용)과 데이터에서 바로 나오는 위험 표시만** 반환한다. 대안 비교·추천은 Agent가 한다.
- 3 → 4 순서로 쓴다: Agent가 `get_bom`으로 필요 부품 목록을 받아 그대로 `check_parts_supply`에 넣는다.

## 데이터 기준
- 데이터셋: 지영님이 공유한 `rfq_dataset_v2.2.zip` (`02_postgres/01_schema.sql`, `02_seed.sql`, `rfq_db_v2.dbml`). 테이블·컬럼은 v2.1과 동일
  - 이 문서의 `01_rfq/`, `02_postgres/`, `05_expected/` 경로는 모두 이 zip 안의 경로다. 로컬에서는 zip을 `dataset/`에 풀어서 쓴다 (`dataset/`은 Git 제외)
  - 테스트에 쓰는 사본(정답 JSON 5·6·7, RFQ 원문)은 `src/test/resources/expected/`에 있어 zip 없이도 볼 수 있다
- 출력 필드명 기준: `05_expected/5_sg2_supply.json`(SG2), `6_sg3_quote.json`(SG3), `7_sg4_register.json`(SG4). Agent가 Tool 결과를 그대로 State에 옮길 수 있게 이름을 맞춘다
- 사용 테이블: `models`, `options`, `model_options`, `parts`, `bom`, `inventory`, `part_substitutes`
- 조회 전용 계정(`business_mcp_user`)은 위 7개 테이블의 SELECT 권한과 RLS SELECT 정책이 모두 있어야 한다 (RLS만 켜져 있고 정책이 없으면 오류 없이 0행이 나온다)
- 주의: 시드에는 **EWP-12-C의 BOM이 없다** (C는 SG1에서 탈락). C로 `get_bom`/`get_parts_cost` 호출 시 오류로 처리

## 공통 규칙
| 항목 | 규칙 |
|---|---|
| 구조 | Tool 진입점 → `service/`(SQL, 계산) → `dto/`(Java record) |
| Tool 등록 | Spring AI 1.1.8 방식으로 등록 (`@Tool` 메서드 + `ToolCallbackProvider` 빈, 또는 1.1의 MCP 어노테이션). 버전 문서 확인 후 하나로 통일 |
| 설명 | 각 Tool과 파라미터에 한국어 description 작성. LLM이 언제 이 Tool을 써야 하는지 알 수 있게 |
| 금액 | 정수(원). 필드명 끝에 `_krw` |
| 날짜 | `YYYY-MM-DD` 문자열 |
| JSON 필드명 | snake_case (아래 명세 그대로) |
| 오류 | 잘못된 입력(없는 모델, 허용되지 않은 옵션, BOM 없음)은 원인 메시지를 담은 예외 → MCP 오류 결과로 반환. 형식은 아래 "오류 처리 (MCP 표준)" |
| SQL | 파라미터 바인딩만 사용. 문자열 연결 금지 |
| 쓰기 | DB 커넥션은 읽기 전용 유지. 쓰기 Tool과 `get_rfq_content`는 백엔드 API만 호출 |

### 오류 처리 (MCP 표준)
[MCP 공식 명세](https://modelcontextprotocol.io/specification)의 오류 처리 방식을 따른다.

| 구분 | 언제 | 처리 |
|---|---|---|
| 프로토콜 오류 (JSON-RPC `error`) | 없는 Tool 이름, 잘못된 JSON 등 MCP 메시지 자체의 문제 | Spring AI가 처리. 직접 만들지 않는다 |
| Tool 실행 오류 (`result.isError: true`) | 잘못된 입력, 데이터 없음, 백엔드 실패 등 Tool 안에서 생긴 문제 | 예외를 던지면 Spring AI가 `isError: true` + `content` 텍스트로 바꾼다 |

- Tool 실행 오류는 원인 메시지(텍스트)만 반환한다. **`error_code`·`success_code`·`retryable` 같은 별도 필드는 두지 않는다** (MCP 명세에 Tool 오류용 코드 체계가 없음)
- 메시지는 LLM이 읽고 입력을 고치거나 사람에게 물을 수 있게 원인과 허용 값을 담는다 (예: "모델 EWP-12-A에 허용되지 않은 옵션입니다: OPT-IP9K (허용: [OPT-HT])")
- 성공은 결과 JSON을 그대로 반환한다 (성공 코드 없음)
- 예외는 cause를 연결하지 않는다. 오류 결과에는 예외 체인의 가장 안쪽 메시지가 담기므로, 연결하면 Agent에 보여줄 메시지가 라이브러리 메시지로 가려진다

---

## Tool 명세

### 1. `get_models`
고객 요구 사양과 비교할 표준 모델 전체 목록. (SG1 `match_spec`)

```jsonc
// input
{}

// output
[
  { "model_id": "EWP-12-A", "model_name": "소형 워터펌프", "flow_lpm": 15.0, "head_kpa": 50,
    "voltage_min": 9, "voltage_max": 16, "temp_min": -40, "temp_max": 105, "ip_rating": "IP67",
    "noise_db": 43, "comm": "PWM", "size_l_mm": 100, "size_w_mm": 85, "size_h_mm": 80,
    "weight_kg": 0.55, "base_price_krw": 29000 },
  { "model_id": "EWP-12-B", "model_name": "중형 워터펌프", "flow_lpm": 25.0, "head_kpa": 80,
    "voltage_min": 9, "voltage_max": 16, "temp_min": -40, "temp_max": 110, "ip_rating": "IP67",
    "noise_db": 42, "comm": "LIN,PWM", "size_l_mm": 110, "size_w_mm": 90, "size_h_mm": 90,
    "weight_kg": 0.70, "base_price_krw": 34000 },
  { "model_id": "EWP-12-C", "model_name": "대형 워터펌프", "flow_lpm": 35.0, "head_kpa": 100,
    "voltage_min": 9, "voltage_max": 16, "temp_min": -40, "temp_max": 125, "ip_rating": "IP6K9K",
    "noise_db": 48, "comm": "LIN", "size_l_mm": 118, "size_w_mm": 98, "size_h_mm": 94,
    "weight_kg": 0.95, "base_price_krw": 45000 }
]
```
SQL: `models` 전체, `model_id` 순.

### 2. `get_options`
모델 기본 단가와 붙일 수 있는 옵션. (SG1 `match_spec`, SG3 `draft_quote`: 단가 = 34,000 + 2,400 + 900 = 37,300)

```jsonc
// input
{ "model_id": "EWP-12-B" }

// output
{
  "model_id": "EWP-12-B",
  "base_price_krw": 34000,
  "options": [
    { "option_code": "OPT-HT", "option_name": "고온 대응 (125℃)",
      "changes_field": "temp_max", "new_value": "125",
      "add_weight_kg": 0.00, "add_noise_db": 0, "add_price_krw": 2400,
      "replaces_part_id": "P-MCU-STD", "uses_part_id": "P-MCU-G1" },
    { "option_code": "OPT-IP9K", "option_name": "방수 강화 (IP6K9K)",
      "changes_field": "ip_rating", "new_value": "IP6K9K",
      "add_weight_kg": 0.05, "add_noise_db": 1, "add_price_krw": 900,
      "replaces_part_id": "P-SEAL-STD", "uses_part_id": "P-SEAL-9K" }
  ]
}
```
SQL: `models` + `model_options` JOIN `options`. 옵션 없는 모델(C)은 `options: []`. 없는 모델이면 오류.

### 3. `get_bom`
옵션을 반영해 BOM을 전개하고 부품별 필요 수량을 계산. (SG2 시작, SG4 재확인)
필드명은 `05_expected/5_sg2_supply.json`의 `bom_effective`와 맞춘다.

```jsonc
// input
{ "model_id": "EWP-12-B", "options": ["OPT-HT", "OPT-IP9K"], "qty": 2000 }

// output
{
  "model_id": "EWP-12-B",
  "options": ["OPT-HT", "OPT-IP9K"],
  "qty": 2000,
  "parts": [
    { "part_id": "P-MTR-BL12", "part_name": "모터",            "qty_per_unit": 1, "required_qty": 2000, "source": "BASE" },
    { "part_id": "P-MCU-G1",   "part_name": "제어칩 (고온용)",  "qty_per_unit": 1, "required_qty": 2000, "source": "OPTION:OPT-HT",   "replaced_part_id": "P-MCU-STD" },
    { "part_id": "P-IMP-25",   "part_name": "임펠러",           "qty_per_unit": 1, "required_qty": 2000, "source": "BASE" },
    { "part_id": "P-HSG-AL",   "part_name": "알루미늄 케이스",  "qty_per_unit": 1, "required_qty": 2000, "source": "BASE" },
    { "part_id": "P-SEAL-9K",  "part_name": "방수 실링 (강화)", "qty_per_unit": 1, "required_qty": 2000, "source": "OPTION:OPT-IP9K", "replaced_part_id": "P-SEAL-STD" },
    { "part_id": "P-CON-4P",   "part_name": "4핀 커넥터",       "qty_per_unit": 1, "required_qty": 2000, "source": "BASE" }
  ]
}
```
계산:
1. `bom`에서 모델의 기본 부품 조회 → `source: "BASE"`
2. 각 옵션의 `replaces_part_id`를 `uses_part_id`로 교체 (`qty_per_unit` 유지) → `source: "OPTION:{option_code}"`, `replaced_part_id` 기록
3. `required_qty = qty_per_unit × qty`
4. 출력 순서: `part_id` 순. `bom`에 순서 컬럼이 없어 정답 JSON(`bom_effective`)의 순서는 재현할 수 없다 → 순서에 의미를 두지 않고 `part_id`로 찾아 쓴다

오류: 없는 모델, `model_options`에 없는 옵션 조합(예: A + OPT-IP9K), BOM 없는 모델(C), `qty <= 0`.

### 4. `check_parts_supply`
부품 목록과 필요 수량을 받아 공급 상태와 위험을 반환. (SG2 대안 비교 재료, SG4 재고 재확인)
필드명은 `05_expected/5_sg2_supply.json`의 `supply_check`·`shortages`와 맞춘다.

```jsonc
// input
{
  "due_date": "2026-12-15",    // 고객 납기 (필수)
  "base_date": "2026-10-19",   // 검토일 (선택, 없으면 오늘 Asia/Seoul). 신규 발주 도착일·대체 부품 사용 가능일 계산 기준
  "parts": [
    { "part_id": "P-MTR-BL12", "required_qty": 2000 },
    { "part_id": "P-MCU-G1",   "required_qty": 2000 },
    { "part_id": "P-IMP-25",   "required_qty": 2000 },
    { "part_id": "P-HSG-AL",   "required_qty": 2000 },
    { "part_id": "P-SEAL-9K",  "required_qty": 2000 },
    { "part_id": "P-CON-4P",   "required_qty": 2000 }
  ]
}

// output
{
  "due_date": "2026-12-15",
  "base_date": "2026-10-19",
  "has_risk": true,
  "parts": [
    {
      "part_id": "P-MTR-BL12", "part_name": "모터", "supplier": "한결모터", "is_critical": true,
      "required_qty": 2000, "on_hand": 18000, "allocated": 6000, "available": 12000,
      "short_qty": 0, "status": "OK",
      "incoming_qty": 10000, "incoming_date": "2026-11-20", "incoming_before_due": true, "incoming_delay_days": 0,
      "unit_cost_krw": 9500, "lead_time_days": 45, "new_order_arrival": "2026-12-03",
      "risks": [], "substitutes": []
    },
    {
      "part_id": "P-MCU-G1", "part_name": "제어칩 (고온용)", "supplier": "세온반도체", "is_critical": true,
      "required_qty": 2000, "on_hand": 9000, "allocated": 7500, "available": 1500,
      "short_qty": 500, "status": "SHORT",
      "incoming_qty": 5000, "incoming_date": "2027-01-20", "incoming_before_due": false, "incoming_delay_days": 36,
      "unit_cost_krw": 5300, "lead_time_days": 120, "new_order_arrival": "2027-02-16",
      "risks": ["SHORTAGE", "INCOMING_AFTER_DUE", "NEW_ORDER_AFTER_DUE"],
      "substitutes": [
        {
          "substitute_part_id": "P-MCU-G1-ALT", "part_name": "제어칩 (고온용, 대체)", "supplier": "누리칩스",
          "available": 3000, "covers_shortage": true,
          "unit_cost_krw": 5600, "unit_cost_diff_krw": 300,
          "validation_days": 14, "usable_date": "2026-11-02",
          "lead_time_days": 30, "pin_compatible": true, "ref_chunk_id": "alt-002"
        }
      ]
    }
    // P-IMP-25, P-HSG-AL, P-SEAL-9K, P-CON-4P: 아래 기대값 표 참고
  ]
}
```

필드 계산:
| 필드 | 출처 / 계산 |
|---|---|
| on_hand, allocated | `inventory` |
| available | `on_hand − allocated` |
| short_qty | `max(0, required_qty − available)` |
| status | `short_qty > 0` 이면 `"SHORT"`, 아니면 `"OK"` |
| incoming_qty, incoming_date | `inventory` (입고 예정 없으면 0 / null) |
| incoming_before_due | `incoming_date != null && incoming_date <= due_date` |
| incoming_delay_days | `incoming_date > due_date`이면 그 차이(일), 아니면 0. 입고 예정 없으면 null |
| part_name, supplier, unit_cost_krw, lead_time_days, is_critical | `parts` |
| new_order_arrival | `base_date + lead_time_days` |
| substitutes | **SHORT인 부품에만** 채움. `part_substitutes` JOIN `parts`, `inventory` (대체 부품 기준). OK면 `[]` |
| substitutes[].covers_shortage | 대체 부품 `available >= short_qty` |
| substitutes[].unit_cost_diff_krw | 대체 부품 단가 − 원래 부품 단가 |
| substitutes[].usable_date | `base_date + validation_days` |
| has_risk | `risks`가 하나라도 있는 부품이 있으면 true |

위험 코드 (**SHORT인 부품에만** 판단, OK면 `[]`):
| 코드 | 조건 |
|---|---|
| `SHORTAGE` | `available < required_qty` |
| `INCOMING_AFTER_DUE` | 입고 예정 없음, 또는 `incoming_date > due_date` |
| `INCOMING_INSUFFICIENT` | 납기 전 입고는 있지만 `incoming_qty < short_qty` (입고로 부족분을 다 채우지 못함). `INCOMING_AFTER_DUE`와 동시에 붙지 않음 |
| `NEW_ORDER_AFTER_DUE` | `new_order_arrival > due_date` |

SG4 재확인: 승인 후 Agent가 `final_plan`(예: `P-MCU-G1` 1,500 + `P-MCU-G1-ALT` 500)대로 다시 호출한다. 대체 부품도 일반 `part_id`로 넣을 수 있어야 한다 (`7_sg4_register.json`의 `recheck`).

오류: 없는 `part_id`, `inventory` 행 없음, `required_qty <= 0`, 날짜 형식 오류, 빈 `parts`.

### 5. `get_parts_cost`
옵션 반영 BOM 기준 부품 원가 합계. 가공·물류비 미포함. (SG3: 마진 = (단가 − 원가) / 단가, 기준 30%)
필드명은 `05_expected/6_sg3_quote.json`의 `parts_cost_krw`·`parts_cost_breakdown`과 맞춘다.

```jsonc
// input
{ "model_id": "EWP-12-B", "options": ["OPT-HT", "OPT-IP9K"] }

// output
{
  "model_id": "EWP-12-B",
  "options": ["OPT-HT", "OPT-IP9K"],
  "parts_cost_krw": 21650,
  "parts_cost_breakdown": [
    { "part_id": "P-MTR-BL12", "qty_per_unit": 1, "unit_cost_krw": 9500, "cost_krw": 9500 },
    { "part_id": "P-MCU-G1",   "qty_per_unit": 1, "unit_cost_krw": 5300, "cost_krw": 5300 },
    { "part_id": "P-IMP-25",   "qty_per_unit": 1, "unit_cost_krw": 1200, "cost_krw": 1200 },
    { "part_id": "P-HSG-AL",   "qty_per_unit": 1, "unit_cost_krw": 4100, "cost_krw": 4100 },
    { "part_id": "P-SEAL-9K",  "qty_per_unit": 1, "unit_cost_krw": 950,  "cost_krw": 950 },
    { "part_id": "P-CON-4P",   "qty_per_unit": 1, "unit_cost_krw": 600,  "cost_krw": 600 }
  ]
}
```
계산: `get_bom`과 같은 전개 로직(공통 service 메서드로 재사용) → `cost_krw = unit_cost_krw × qty_per_unit`, `parts_cost_krw = Σ cost_krw`. 오류 조건도 `get_bom`과 동일.

---

### 백엔드 API Tool 공통 (6~9)

| 항목 | 규칙 |
|---|---|
| 호출 대상 | Spring 백엔드 `/internal/**`. 이 서버는 요청을 전달하고 응답을 돌려줄 뿐, 업무 규칙(상태 전이·검증)과 파일 보관은 백엔드가 책임진다 |
| HTTP 클라이언트 | Spring `RestClient`. `client/BackendClient`로 분리 |
| 설정 (.env) | `BACKEND_URL` (예: `http://localhost:8080`). 요청 헤더 `X-Internal-Api-Key`에는 `INTERNAL_API_KEY`를 그대로 쓴다 (AI·business-mcp·백엔드가 같은 키 하나를 공유) |
| 중복 방지 | 6·7은 `PUT` upsert라 같은 요청을 여러 번 보내도 결과가 같다 → `Idempotency-Key` 불필요 (붙이면 반려 후 재작성한 견적이 첫 결과로 막힐 수 있음). 8만 `Idempotency-Key: {quote_id}:register` |
| 타임아웃 | 연결 3초, 응답 10초 |
| 재시도 | 이 서버에서는 하지 않는다. 재시도 여부는 클라이언트(Agent·Harness)가 판단한다. 오류 메시지에 HTTP 상태 코드가 들어간다 (예: "백엔드 오류 (503): ...") |
| 오류 변환 | 4xx → 백엔드 응답의 메시지를 담아 Tool 오류. 409(상태 충돌) → "현재 상태에서 불가" 메시지. 5xx·타임아웃 → "백엔드 오류" Tool 오류 |
| 표시 | `register_quote`는 `destructiveHint=true`, 6·7은 `idempotentHint=true` (Spring AI 1.1 MCP 어노테이션이 지원하면 사용, 아니면 description에 명시) |

`.env.example`에 `BACKEND_URL`을 추가하고, README의 "다른 업무 서버의 주소는 필요하지 않습니다" 문구를 수정한다. 백엔드용 키 변수는 따로 두지 않는다.

### 6. `save_review`
SG1·SG2 결과를 `rfq_reviews`에 저장. 같은 `thread_id`로 다시 호출하면 덮어쓴다 (upsert). (SG1 마지막, SG2 마지막)

백엔드 API: `PUT /internal/reviews/{thread_id}`

```jsonc
// input (SG1)
{
  "thread_id": "th_7f3a...",
  "rfq_number": "HB-EV7-EWP-001",
  "stage": "SG1",
  "selected_model": "EWP-12-B",
  "options": ["OPT-HT", "OPT-IP9K"],
  "fit_type": "파생 사양",
  "issues": [ ... ],              // 05_expected/2_validation_issues.json 구조
  "passed_checks": [ ... ],
  "assumptions": [ ... ],
  "customer_questions": [ ... ],
  "contract_risks": [ ... ]
}

// input (SG2)
{
  "thread_id": "th_7f3a...",
  "rfq_number": "HB-EV7-EWP-001",
  "stage": "SG2",
  "shortages": [ ... ],           // 5_sg2_supply.json의 shortages (part_id, short_qty, incoming_delay_days ...)
  "alternatives": [ ... ],        // 5_sg2_supply.json의 alternatives 4건 (WAIT_INCOMING / PARTIAL_DELIVERY / SUBSTITUTE_PART / NEW_ORDER)
  "recommended_alt": "SUBSTITUTE_PART",
  "final_plan": { "P-MCU-G1": 1500, "P-MCU-G1-ALT": 500 }
}

// output
{ "review_id": 1, "thread_id": "th_7f3a...", "status": "WAITING" }
```
- `final_plan`은 `rfq_reviews`에 컬럼이 없다 → 백엔드가 `alternatives` JSONB에 함께 넣을지, 컬럼을 추가할지 백엔드 담당과 결정 (SG4 재확인에 필요)
- `stage`에 따라 해당 컬럼만 갱신 (SG2 저장이 SG1 컬럼을 지우면 안 됨). 이 규칙은 백엔드 책임이며, 이 서버는 `stage`를 그대로 전달
- JSONB 필드(issues 등)는 내용 검사 없이 그대로 전달

### 7. `save_quote`
견적 초안을 `quotes`에 DRAFT로 저장. 같은 `thread_id`로 다시 호출하면 덮어쓴다 (반려 후 재작성). (SG3 `validate_quote` 통과 후)

백엔드 API: `PUT /internal/quotes/{thread_id}`

```jsonc
// input: thread_id, review_id + 6_sg3_quote.json의 quote_draft (quote_id 제외)
{
  "thread_id": "th_7f3a...",
  "review_id": 1,
  "rfq_number": "HB-EV7-EWP-001",
  "recipient": "한별모터스 구매본부",
  "item": "전동식 워터펌프 (EWP, 12V)",
  "model_id": "EWP-12-B",
  "options": ["OPT-HT", "OPT-IP9K"],
  "unit_price_krw": 37300,
  "price_breakdown": [ { "code": "EWP-12-B", "amount_krw": 34000 }, { "code": "OPT-HT", "amount_krw": 2400 }, { "code": "OPT-IP9K", "amount_krw": 900 } ],
  "parts_cost_krw": 21650,
  "parts_cost_breakdown": [ { "part_id": "P-MTR-BL12", "cost_krw": 9500 }, ... ],
  "margin_pct": 42.0,
  "price_by_year": [
    { "year": 1, "cr_applied": 0, "unit_price_krw": 37300, "margin_pct": 42.0 },
    ...
    { "year": 6, "cr_applied": 5, "unit_price_krw": 32031, "margin_pct": 32.4 }
  ],
  "eau": 120000,
  "sample_qty": 2000,
  "sample_due_date": "2026-12-15",
  "sop_date": "2027-09-01",
  "incoterms": "DAP 한별모터스 울산 2공장",
  "issue_date": "2026-10-19",
  "valid_until": "2026-11-18",
  "conditions": [ ... ],          // type: ASSUMPTION / SUBSTITUTE_PART / CONTRACT_PROPOSAL
  "checks": [ ... ]               // rule: PRICE / ANNUAL_CR / MARGIN / PRICE_RECALC / FORMAT
}

// output
{ "quote_id": "Q-2026-1019-001", "status": "DRAFT" }
```
- `quote_id` 채번은 백엔드가 한다 (`Q-YYYY-MMDD-NNN`). `6_sg3_quote.json` 주석은 이 API를 `create_quote`라 부른다 → Tool 이름은 `save_quote` 유지, 백엔드 API 이름만 맞추면 됨
- `quotes` 테이블에 컬럼이 없는 필드: `recipient`, `item`, `price_breakdown`, `parts_cost_breakdown`, `eau`, `sop_date`, `incoterms`, `issue_date`. 이 서버는 그대로 전달하고, 저장 방식(컬럼 추가 / JSONB / rfqs에서 재조회)은 백엔드 담당과 결정
- `price_by_year`는 이제 객체 배열이다 (JSONB 컬럼이라 스키마 변경 없음)
- 이미 APPROVED·REGISTERED인 견적이면 백엔드가 409 → Tool 오류

### 8. `register_quote`
사람이 승인한 견적을 등록 상태로 바꾼다. **되돌리기 어려운 작업.** (SG4: 승인 → `check_parts_supply` 재확인 통과 후에만)

백엔드 API: `POST /internal/quotes/{quote_id}/register`

```jsonc
// input
{ "quote_id": "Q-2026-1019-001", "thread_id": "th_7f3a..." }

// output
{ "quote_id": "Q-2026-1019-001", "status": "REGISTERED", "rfq_status": "QUOTED", "registered_at": "2026-10-19T15:30:00" }
```
- 승인 여부 확인은 **백엔드가 한다**: `quotes.status`가 `APPROVED`가 아니면 409 → Tool 오류 "승인되지 않은 견적"
- 승인자(`approved_by`)는 FE 승인 API에서 이미 기록됨. 이 Tool은 받지 않는다
- 백엔드는 등록과 함께 `rfqs.status`를 `QUOTED`로 바꾼다 (`7_sg4_register.json`)
- 같은 `Idempotency-Key`로 다시 오면 백엔드는 처음 결과를 그대로 반환 (이미 REGISTERED → 200)
- description에 "사람 승인 후에만 호출" 명시

### 9. `get_rfq_content`
RFQ 번호로 고객이 업로드한 견적요청서 원문을 가져온다. (SG1 `parse_rfq`의 입력)

백엔드 API: `GET /internal/rfqs/{rfqNumber}/content`

```jsonc
// input
{ "rfq_number": "HB-EV7-EWP-001" }

// output
{
  "rfq_number": "HB-EV7-EWP-001",
  "customer": "한별모터스",
  "item": "전동식 워터펌프 (EWP, 12V)",
  "file_name": "RFQ_HB-EV7-EWP-001.md",
  "content_type": "text/markdown",
  "received_at": "2026-10-12T09:00:00",
  "content": "# 견적 요청서 (RFQ)\n..."   // 원문 그대로 (01_rfq/RFQ_HB-EV7-EWP-001.md)
}
```
- 원문은 가공하지 않고 그대로 반환한다. 파싱(`rfq_schema.json` 형식 추출)은 SG1 `parse_rfq`의 몫
- 원문 위치는 `rfqs.file_path`이며 파일은 백엔드가 읽는다. 이 서버는 파일 시스템에 접근하지 않는다
- 조회라서 `Idempotency-Key` 없음
- 오류: 404 없는 RFQ → "RFQ를 찾을 수 없음", 415 텍스트가 아닌 파일(PDF 등) → "지원하지 않는 형식" Tool 오류
- 응답이 너무 크면 LLM 컨텍스트를 넘칠 수 있다 → 백엔드 응답이 200KB를 넘으면 Tool 오류로 알림 (데모 RFQ는 수 KB)

### 백엔드와 맞출 계약
백엔드 API Tool 4개(6~9)는 백엔드 담당이 아래 API를 만들어야 실제로 동작한다. 응답 JSON은 위 output과 동일하게 맞춘다.

| API | 성공 | 실패 |
|---|---|---|
| `PUT /internal/reviews/{thread_id}` | 200 + `{review_id, thread_id, status}` | 400 입력 오류 |
| `PUT /internal/quotes/{thread_id}` | 200 + `{quote_id, status}` | 400 입력 오류, 409 이미 승인·등록됨 |
| `POST /internal/quotes/{quote_id}/register` | 200 + `{quote_id, status, rfq_status, registered_at}` | 404 없음, 409 APPROVED 아님 |
| `GET /internal/rfqs/{rfqNumber}/content` | 200 + `{rfq_number, customer, item, file_name, content_type, received_at, content}` | 404 없음, 415 텍스트 아님 |

---

## 테스트 기대값 (v2.2 시드, B + OPT-HT + OPT-IP9K, 2,000개, 납기 2026-12-15, 검토일 2026-10-19)

`check_parts_supply`:
| part_id | available | status | short_qty | incoming | lead | new_order_arrival | risks |
|---|---|---|---|---|---|---|---|
| P-MTR-BL12 | 12,000 | OK | 0 | 10,000 / 2026-11-20 | 45 | 2026-12-03 | [] |
| P-MCU-G1 | 1,500 | SHORT | 500 | 5,000 / 2027-01-20 | 120 | 2027-02-16 | SHORTAGE, INCOMING_AFTER_DUE, NEW_ORDER_AFTER_DUE |
| P-IMP-25 | 18,000 | OK | 0 | 0 / null | 14 | 2026-11-02 | [] |
| P-HSG-AL | 10,000 | OK | 0 | 8,000 / 2026-12-01 | 30 | 2026-11-18 | [] |
| P-SEAL-9K | 7,000 | OK | 0 | 0 / null | 21 | 2026-11-09 | [] |
| P-CON-4P | 20,000 | OK | 0 | 0 / null | 21 | 2026-11-09 | [] |

P-MCU-G1 incoming_delay_days 36. 정답 파일: `05_expected/5_sg2_supply.json`의 `supply_check`와 수량·날짜가 일치해야 함.

대체 부품 (P-MCU-G1): P-MCU-G1-ALT, available 3,000, covers_shortage true, 5,600원(unit_cost_diff_krw 300), 검증 14일 → 2026-11-02, lead 30, pin_compatible true, alt-002.

기타:
- `get_options("EWP-12-B")` → 34,000 + 옵션 2개 (2,400 / 900)
- `get_parts_cost(B, [OPT-HT, OPT-IP9K])` → **21,650**
- `get_parts_cost(B, [])` → 9,500 + 3,200 + 1,200 + 4,100 + 400 + 600 = **19,000**
- `get_bom("EWP-12-A", ["OPT-IP9K"])` → 오류 (허용되지 않은 옵션)
- `get_bom("EWP-12-C", [])` → 오류 (BOM 없음)

테스트 방법:
- 읽기 service 단위 테스트: 위 기대값으로 검증. 실제 DB가 필요하면 Testcontainers(PostgreSQL)에 v2.2 `01_schema.sql` + `02_seed.sql`을 적재 (의존성 추가 필요, Docker 필요)
- `BasicConfigurationTest.acceptsCorrectKeyAndExposesNoBusinessTools`는 Tool 0개를 가정하므로, Tool 9개 목록 확인으로 수정
- 백엔드 API Tool(6~9) 테스트: `MockRestServiceServer`로 백엔드 응답을 흉내 내고 확인할 것
  - `get_rfq_content`: mock 응답 content에 v2.2 `01_rfq/RFQ_HB-EV7-EWP-001.md` 내용을 넣고 그대로 반환되는지, 404 → Tool 오류
  - 요청 경로·메서드·`X-Internal-Api-Key` 헤더·body가 명세대로 나가는지, `register_quote`에만 `Idempotency-Key`가 붙는지
  - 200 → output 그대로 반환 / 409 → "현재 상태에서 불가" Tool 오류 / 500·타임아웃 → "백엔드 오류" Tool 오류

## DB 없이 개발하기 (기본 테스트)
실제 DB(Supabase)는 연결되어 있지만, 기본 `mvn test`는 네트워크 없이 돌도록 H2와 mock을 쓴다. 실제 DB 확인은 `LIVE_DB=true mvn test -Dtest=LiveDatabaseToolsTest`.

| 항목 | 방식 |
|---|---|
| 서버 실행 | DB 없이도 기동됨 (Hikari는 첫 쿼리 때 연결). `tools/list`까지 확인 가능 |
| 데이터 접근 | `repository/`에 JdbcTemplate 기반 조회 클래스. SQL은 PostgreSQL 기준으로 작성하되 표준 SQL만 사용 (H2와 공용) |
| 테스트 DB | **H2 (test scope, `MODE=PostgreSQL`)**. `src/test/resources/schema.sql`에 7개 테이블(`models`, `options`, `model_options`, `parts`, `bom`, `inventory`, `part_substitutes`)만 v2.2 DDL 그대로 옮기고, `data.sql`에 v2.2 시드의 해당 INSERT만 복사 |
| 계산 로직 | BOM 전개, 위험 코드, 날짜 계산은 service에 순수 메서드로 두고 단위 테스트 |
| 통합 테스트 | H2를 띄운 `@SpringBootTest`에서 MCP 클라이언트로 `tools/call` 호출 → 위 기대값 검증 |
| 기존 테스트 | `BasicConfigurationTest`는 DB 연결 없이 도는 설정 테스트이므로 유지 (Tool 개수 확인만 9개로 수정) |
| 백엔드 없음 | 백엔드 API Tool(6~9)은 `MockRestServiceServer`로 테스트. 실제 백엔드 API가 생기면 `.env`의 `BACKEND_URL`만 맞춤 |

- `pom.xml`에 `com.h2database:h2` (test scope) 추가
- 7개 테이블은 VARCHAR/INT/DECIMAL/BOOLEAN/DATE만 써서 H2와 호환된다. `doc_chunks`(vector), `rfq_reviews`(JSONB), `TEXT[]` 컬럼 테이블은 이 서버가 읽지 않으므로 H2 스키마에 넣지 않는다
- 실제 DB 확인: `.env`를 맞추고 `LIVE_DB=true mvn test -Dtest=LiveDatabaseToolsTest` (같은 기대값 + 읽기 전용 확인)

## 이 레포 범위 밖 / 결정 대기
- **백엔드 `/internal/**` API 4개** (쓰기 3 + RFQ 원문 조회 1): 백엔드 담당 구현 (위 "백엔드와 맞출 계약")
- **읽기 경로**: 교재는 business-mcp가 DB 대신 Spring 내부 API를 호출하는 구조. 이 서버는 읽기를 DB 직접 조회로 한다 → 유지할지(발표 때 이유 설명), 백엔드 API로 바꿀지 팀 결정. 바뀌어도 Tool 이름·입출력은 그대로 유지
- **rag-mcp**: 별도 서버. `search_docs` Tool 1개 + 원문 Resource `doc://chunks/{chunk_id}` (교재 요건)
- 필드 이름은 SG 담당자·Agent 쪽 mock 클라이언트와 공유되므로, 변경 시 이 문서를 먼저 갱신
