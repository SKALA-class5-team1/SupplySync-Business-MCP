# Business MCP: 작업 지시서

SupplySync-AI(RFQ·재고·구매 대응 Agent)의 **business-mcp 서버**.
AI Agent(LangGraph)가 MCP로 호출하면, 이 서버는 **조회든 변경이든 모두 Spring 백엔드의 `/internal/**` API를 호출**해 결과를 돌려준다. **DB에 접근하지 않는다.**

- 근거 (교재 5장): "business mcp는 DB에 직접 접근하지 않고 Spring Boot 내부 API `/internal/**`를 호출한다", 금지 사항 "업무 규칙 복제". 업무 규칙(옵션 BOM 전개, 상태 전이, 승인 검사)은 백엔드 한 곳에만 둔다
- 이 서버가 하는 일: 도구 설명 노출(Agent가 고를 수 있게) → 백엔드 호출(여러 API를 조합) → Agent가 쓰기 좋은 형태로 응답 변환(정답 JSON 필드명) → 오류를 LLM이 읽을 수 있는 메시지로 변환
- (이력) 2026-10-21까지는 조회 도구 1~5가 DB를 조회 전용 계정으로 직접 읽었다. 교재 구조에 맞추려고 백엔드 조회 API 호출로 바꾼다. **Tool 이름·입력·출력은 그대로 유지**한다 (Agent 쪽 변경 없음)

기본 설정(서버, API 키 필터)은 README.md, 구조는 ARCHITECTURE.md 참고. 이 문서는 **Tool 명세와 작업 지시**다.

## 현재 상태
- Spring Boot 3.5.16, Java 21, Spring AI 1.1.8 (`spring-ai-starter-mcp-server-webmvc`)
- `http://127.0.0.1:8101/mcp`, Streamable HTTP, 모든 요청에 `X-Internal-Api-Key` 헤더 필요
- 백엔드: `SupplySync-BE` (`BACKEND_URL`, 기본 `http://localhost:8080`). 엔드포인트는 BE README와 Swagger(`/swagger-ui.html`, OpenAPI `/v3/api-docs/2-internal`)가 기준이며 **백엔드는 수정하지 않는다.** 이 서버가 맞춘다
- Tool 10개 구현됨. 모두 백엔드 API 호출 (1~5는 2026-10-21에 DB 직접 조회에서 전환, 10번 `get_substitutes` 추가). mock 백엔드로 확인했고, 실제 백엔드 연결 확인(`LiveBackendToolsTest`)은 남음

## 작업 범위: Tool 10개
rag-mcp(VectorDB 검색)는 별도 서버라 이 레포 범위가 아니다.

| # | Tool 이름 | 하는 일 | 종류 | 사용 SG |
|---|---|---|---|---|
| 1 | `get_models` | 표준 모델 사양·기본 단가 목록 | 읽기 (백엔드 API) | SG1 |
| 2 | `get_options` | 모델에 붙일 수 있는 옵션과 효과·단가·교체 부품 | 읽기 (백엔드 API) | SG1, SG3 |
| 3 | `get_bom` | 모델+옵션 기준 BOM 전개 → 부품별 필요 수량 | 읽기 (백엔드 API) | SG2, SG4 |
| 4 | `check_parts_supply` | 부품별 재고 충분 여부, 입고·조달 일정, 비용, 대체 부품, 위험 표시 | 읽기 (백엔드 API) | SG2, SG4 |
| 5 | `get_parts_cost` | 모델+옵션 기준 부품 원가 합계 | 읽기 (백엔드 API) | SG3 |
| 6 | `save_review` | SG1·SG2 분석 결과 저장 | 쓰기 (백엔드 API) | SG1, SG2 |
| 7 | `save_quote` | 견적 초안 생성 (DRAFT, revision별) | 쓰기 (백엔드 API) | SG3 |
| 8 | `register_quote` | 승인된 견적 등록. 되돌리기 어려움 | 쓰기 (백엔드 API) | SG4 |
| 9 | `get_rfq_content` | RFQ 번호로 고객이 올린 견적요청서 원문 조회 | 읽기 (백엔드 API) | SG1 |
| 10 | `get_substitutes` | 부품 하나의 대체 부품 후보 조회 (단가 차이, 검증 기간, 재고) | 읽기 (백엔드 API) | SG2 |

팀 노션 「MCP」 페이지 번호와의 대응: 노션 1~8 = 이 문서 1~8, 노션 11 = 이 문서 9 (노션 9·10은 rag-mcp). 노션 12 = 이 문서 10 (`get_substitutes`, 초기 설계의 대체 부품 조회를 별도 도구로 다시 둔 것).

`check_parts_supply`(4)와 `get_substitutes`(10)의 관계: 4는 부족한 부품의 대체 부품까지 한 번에 돌려준다. 10은 Agent가 특정 부품의 대체 후보만 따로 보고 싶을 때(예: 대안 비교 중 다른 부품 확인, 재고 재확인 없이 후보만 조회) 쓴다. 두 도구의 대체 부품 필드명과 계산은 같아야 하므로 **같은 service 메서드를 쓴다**.

## 작업 지시: 조회 도구 1~5를 백엔드 API로 전환 (완료, 2026-10-21)
1. **백엔드 응답 스키마 확인**: `SupplySync-BE`의 `internal/` 컨트롤러·DTO 또는 실행 중인 서버의 `/v3/api-docs/2-internal`에서 아래 조회 API의 응답 필드를 확인한다. 필드명은 데이터셋 정답지와 같은 snake_case
2. **`BackendClient`로 조회 호출 추가**: 기존 쓰기 호출과 같은 클라이언트·헤더·타임아웃·오류 변환을 쓴다 (GET, 본문 없음)
3. **service에서 조합·변환**: 도구별 "백엔드 API" 항목대로 호출하고, 출력은 아래 명세(기존과 동일)로 만든다
4. **DB 관련 코드·설정 제거**: `repository/`, `spring-boot-starter-jdbc`·`postgresql`·`h2` 의존성, `application.yml`의 `spring.datasource`, `.env.example`의 `DB_*`, `src/test/resources/schema.sql`·`data.sql`, `LiveDatabaseToolsTest`, `BasicConfigurationTest`의 읽기 전용 DB 검사. JDBC 의존성이 남아 있으면 DataSource 자동 설정 때문에 기동이 실패하므로 같이 지운다
5. **테스트 전환**: 아래 "테스트" 절대로. 기대값(정답 JSON)은 그대로
6. **문서 갱신**: README(DB 설명 → 백엔드 연결), ARCHITECTURE.md(조회 경로), `.env.example`
7. **10번 `get_substitutes` 추가** (SG2 요청, 2026-10-21): 4번 `check_parts_supply`는 그대로 둔다 (SG4 재고 재확인이 `available`·`short_qty`·`status`를 쓰고 있음, `approval_registration/graph.py`의 `recheck_supply`). 10번은 아래 명세대로 새로 만들고, 4번의 `substitutes`와 같은 service 메서드를 쓴다

백엔드 값 우선 원칙: 백엔드 응답에 이미 계산된 값이 있으면(예: 가용 재고, 옵션 적용 BOM) **그 값을 그대로 쓰고 다시 계산하지 않는다.** 이 서버에서 계산하는 것은 백엔드에 없는 출력용 파생값(부족 수량, 상태 OK/SHORT, 날짜 차이, 위험 코드, 합계)뿐이다.

- Tool 이름에는 접두어를 붙이지 않는다. Agent의 MCP 클라이언트가 서버 이름으로 `business_` 접두어를 붙인다.
- Tool은 **사실(수량·날짜·비용)과 데이터에서 바로 나오는 위험 표시만** 반환한다. 대안 비교·추천은 Agent가 한다.
- 3 → 4 순서로 쓴다: Agent가 `get_bom`으로 필요 부품 목록을 받아 그대로 `check_parts_supply`에 넣는다.

## 데이터 기준
- 데이터셋: 지영님이 공유한 `rfq_dataset_v2.2.zip` (`02_postgres/01_schema.sql`, `02_seed.sql`, `rfq_db_v2.dbml`). 테이블·컬럼은 v2.1과 동일
  - 이 문서의 `01_rfq/`, `02_postgres/`, `05_expected/` 경로는 모두 이 zip 안의 경로다. 로컬에서는 zip을 `dataset/`에 풀어서 쓴다 (`dataset/`은 Git 제외)
  - 테스트에 쓰는 사본(정답 JSON 5·6·7, RFQ 원문)은 `src/test/resources/expected/`에 있어 zip 없이도 볼 수 있다
- 출력 필드명 기준: `05_expected/5_sg2_supply.json`(SG2), `6_sg3_quote.json`(SG3), `7_sg4_register.json`(SG4). Agent가 Tool 결과를 그대로 State에 옮길 수 있게 이름을 맞춘다
- 데이터 원천: 백엔드가 읽는 테이블 `models`, `options`, `model_options`, `parts`, `bom`, `inventory`, `part_substitutes`, `rfqs`, `rfq_reviews`, `quotes`. 이 서버는 테이블을 직접 보지 않는다
- 백엔드 데모 데이터: BE를 `SPRING_PROFILES_ACTIVE=demo`로 실행하면 v2.2 시드가 적재된다 (실제 백엔드 연결 테스트용)
- 주의: 시드에는 **EWP-12-C의 BOM이 없다** (C는 SG1에서 탈락). C로 `get_bom`/`get_parts_cost` 호출 시 오류로 처리

## 공통 규칙
| 항목 | 규칙 |
|---|---|
| 구조 | Tool 진입점(`controller/`) → `service/`(백엔드 호출 조합, 출력 변환) → `client/BackendClient`(HTTP) → `dto/`(Java record) |
| Tool 등록 | Spring AI 1.1.8 방식으로 등록 (`@Tool` 메서드 + `ToolCallbackProvider` 빈, 또는 1.1의 MCP 어노테이션). 버전 문서 확인 후 하나로 통일 |
| 설명 | 각 Tool과 파라미터에 한국어 description 작성. LLM이 언제 이 Tool을 써야 하는지 알 수 있게 |
| 금액 | 정수(원). 필드명 끝에 `_krw` |
| 날짜 | `YYYY-MM-DD` 문자열 |
| JSON 필드명 | snake_case (아래 명세 그대로) |
| 오류 | 잘못된 입력(없는 모델, 허용되지 않은 옵션, BOM 없음)은 원인 메시지를 담은 예외 → MCP 오류 결과로 반환. 형식은 아래 "오류 처리 (MCP 표준)" |
| URL | 경로 변수·쿼리는 `RestClient` URI 템플릿으로만 넣는다 (문자열 연결 금지, 인코딩 자동) |
| DB | 접근하지 않는다. DB 접속 정보·드라이버를 두지 않는다 |

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
백엔드 API: `GET /internal/models` (표준 모델과 옵션 목록) → 모델 사양 필드만 골라 위 형식으로 반환, `model_id` 순.

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
백엔드 API: `GET /internal/models/{modelId}` (표준 모델). 응답에 붙일 수 있는 옵션이 없으면 `GET /internal/models`의 해당 모델 옵션 목록을 쓴다 (Swagger에서 확인). 옵션 없는 모델(C)은 `options: []`. 404 → "존재하지 않는 모델" Tool 오류.

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
백엔드 API: `GET /internal/models/{modelId}/bom?options=OPT-HT,OPT-IP9K` (옵션 적용 BOM, **전개는 백엔드가 한다**). 붙일 수 없는 옵션이면 백엔드가 400 → 메시지 그대로 Tool 오류.

이 서버에서 하는 일: `required_qty = qty_per_unit × qty` 계산, 응답에 없는 출력 필드(`part_name` 등)가 있으면 `GET /internal/parts/{partId}`로 보충. 백엔드 응답에 `source`·`replaced_part_id`가 없으면 Swagger를 확인하고, 없을 때는 이 두 필드를 빼고 출력한다(Agent는 `part_id`·`required_qty`만 필수로 쓴다).

(참고: 백엔드의 전개 규칙은 아래와 같다. 이 서버에서 다시 구현하지 않는다)
1. `bom`에서 모델의 기본 부품 조회 → `source: "BASE"`
2. 각 옵션의 `replaces_part_id`를 `uses_part_id`로 교체 (`qty_per_unit` 유지) → `source: "OPTION:{option_code}"`, `replaced_part_id` 기록
3. `required_qty = qty_per_unit × qty`
4. 출력 순서: `part_id` 순. `bom`에 순서 컬럼이 없어 정답 JSON(`bom_effective`)의 순서는 재현할 수 없다 → 순서에 의미를 두지 않고 `part_id`로 찾아 쓴다

오류: 없는 모델(404), 허용되지 않은 옵션 조합(예: A + OPT-IP9K, 400), BOM 없는 모델(C: 백엔드가 빈 목록을 주면 이 서버가 "BOM이 없습니다" 오류), `qty <= 0`(이 서버에서 검사).

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

백엔드 API (부품마다):
1. `GET /internal/parts/{partId}`: 부품 마스터 (part_name, supplier, unit_cost_krw, lead_time_days, is_critical)
2. `GET /internal/parts/{partId}/inventory`: 재고·입고 예정 (on_hand, allocated, incoming_qty, incoming_date)
3. SHORT인 부품만 `GET /internal/parts/{partId}/substitutes`: 대체 부품 후보 (validation_days, pin_compatible, ref_chunk_id). 후보마다 1·2를 호출해 단가·재고를 채운다 (응답에 이미 있으면 생략)

호출 수: 데모 기준 부품 6개 × 2 + 부족 1개 × (대체 후보 1 + 후보 부품 마스터 1) = 14회 (후보 재고는 `/substitutes` 응답의 `available_qty`, 단가 차이는 `unit_cost_diff_krw`를 쓰므로 후보 `/inventory`는 부르지 않는다). 같은 요청 안에서 같은 부품을 두 번 부르지 않게 한다.

필드 계산 (백엔드 응답에 같은 값이 있으면 그 값을 쓴다):
| 필드 | 출처 / 계산 |
|---|---|
| on_hand, allocated | `/inventory` 응답 |
| available | `on_hand − allocated` |
| short_qty | `max(0, required_qty − available)` |
| status | `short_qty > 0` 이면 `"SHORT"`, 아니면 `"OK"` |
| incoming_qty, incoming_date | `/inventory` 응답 (입고 예정 없으면 0 / null) |
| incoming_before_due | `incoming_date != null && incoming_date <= due_date` |
| incoming_delay_days | `incoming_date > due_date`이면 그 차이(일), 아니면 0. 입고 예정 없으면 null |
| part_name, supplier, unit_cost_krw, lead_time_days, is_critical | `/internal/parts/{partId}` 응답 |
| new_order_arrival | `base_date + lead_time_days` |
| substitutes | **SHORT인 부품에만** 채움. `/substitutes` 응답 + 후보 부품의 마스터·재고. OK면 `[]` |
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

오류: 없는 `part_id`(백엔드 404), 재고 정보 없음(404), `required_qty <= 0`·날짜 형식 오류·빈 `parts`·중복 `part_id`(이 서버에서 백엔드 호출 전에 검사).

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
백엔드 API: `GET /internal/models/{modelId}/bom?options=...` → 부품마다 단가가 응답에 없으면 `GET /internal/parts/{partId}`. 계산: `cost_krw = unit_cost_krw × qty_per_unit`, `parts_cost_krw = Σ cost_krw` (`get_bom`과 같은 service 메서드로 BOM을 가져온다). 오류 조건도 `get_bom`과 동일.

---

### 10. `get_substitutes`
부품 하나의 대체 부품 후보를 조회한다. (SG2 대안 비교: 대체 부품 사용 가능일·단가 차이·재고 확인)
초기 설계(노션 4번 `business_get_substitutes`)를 별도 도구로 다시 둔 것이다. 필드명은 `check_parts_supply`의 `substitutes`와 같게 맞춘다 (초기 설계의 `cost_diff_krw` → `unit_cost_diff_krw`).

백엔드 API:
1. `GET /internal/parts/{partId}/substitutes`: 대체 부품 후보 (validation_days, pin_compatible, ref_chunk_id)
2. 원래 부품과 후보마다 `GET /internal/parts/{partId}`(단가·공급사·조달기간), 후보마다 `GET /internal/parts/{partId}/inventory`(재고). 응답에 이미 있으면 생략

```jsonc
// input
{
  "part_id": "P-MCU-G1",          // 필수. 원래 부품
  "short_qty": 500,               // 선택. 주면 covers_shortage 계산
  "base_date": "2026-10-19"       // 선택. 없으면 오늘(Asia/Seoul). usable_date 계산 기준
}

// output
{
  "part_id": "P-MCU-G1",
  "part_name": "제어칩 (고온용)",
  "unit_cost_krw": 5300,
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
```
- `available`: 후보 부품의 `on_hand − allocated` (백엔드 재고 응답에 가용 값이 있으면 그 값)
- `unit_cost_diff_krw`: 후보 단가 − 원래 부품 단가
- `usable_date`: `base_date + validation_days`
- `covers_shortage`: `short_qty`를 줬을 때만 `available >= short_qty`, 안 주면 `null`
- 대체 후보가 없으면 `substitutes: []` (오류 아님)
- 오류: 없는 `part_id`(백엔드 404), `short_qty <= 0`, 날짜 형식 오류
- 힌트: `readOnlyHint=true`, `idempotentHint=true`

### 백엔드 API Tool 공통 (6~9)

| 항목 | 규칙 |
|---|---|
| 호출 대상 | Spring 백엔드 `/internal/**`. 이 서버는 요청을 전달하고 응답을 돌려줄 뿐, 업무 규칙(상태 전이·검증)과 파일 보관은 백엔드가 책임진다 |
| HTTP 클라이언트 | Spring `RestClient`. `client/BackendClient`로 분리 |
| 설정 (.env) | `BACKEND_URL` (예: `http://localhost:8080`). 요청 헤더 `X-Internal-Api-Key`에는 `INTERNAL_API_KEY`를 그대로 쓴다 (AI·business-mcp·백엔드가 같은 키 하나를 공유) |
| 중복 방지 | 6은 `PUT` upsert, 7은 같은 `thread_id`+`revision`이면 백엔드가 기존 `quote_id`를 반환 → 둘 다 같은 요청을 여러 번 보내도 결과가 같아 `Idempotency-Key` 불필요. 8은 `Idempotency-Key: {quote_id}:register`를 붙인다 (백엔드는 상태로 멱등 처리하므로 헤더는 쓰지 않음) |
| 타임아웃 | 연결 3초, 응답 10초 |
| 재시도 | 이 서버에서는 하지 않는다. 재시도 여부는 클라이언트(Agent·Harness)가 판단한다. 오류 메시지에 HTTP 상태 코드가 들어간다 (예: "백엔드 오류 (503): ...") |
| 오류 변환 | 백엔드 오류 응답은 `{code, message, details, timestamp}`. 4xx → `message`(+ `details` 필드별 사유)를 담아 Tool 오류. 409(상태 충돌) → "현재 상태에서 불가" 메시지. 5xx·타임아웃 → "백엔드 오류" Tool 오류 |
| 표시 | `register_quote`는 `destructiveHint=true`, 6·7은 `idempotentHint=true` (Spring AI 1.1 MCP 어노테이션이 지원하면 사용, 아니면 description에 명시) |

`.env.example`에 `BACKEND_URL`을 추가하고, README의 "다른 업무 서버의 주소는 필요하지 않습니다" 문구를 수정한다. 백엔드용 키 변수는 따로 두지 않는다.

### 6. `save_review`
SG1·SG2 결과를 `rfq_reviews`에 저장. 같은 `thread_id`·`rfq_number`로 다시 호출하면 **보낸 필드만** 갱신한다 (upsert). (SG1 마지막, SG2 마지막)
`demand`가 있으면 RFQ 요구값도 `rfqs`에 저장한다.

백엔드 API (호출 순서대로):
1. `demand`가 있을 때만 `PATCH /internal/rfqs/{rfq_number}` (body = `demand`)
2. `PUT /internal/rfq-reviews` (body에 `rfq_number`, `thread_id` 포함)

```jsonc
// input (SG1)
{
  "thread_id": "th_7f3a...",
  "rfq_number": "HB-EV7-EWP-001",
  "stage": "SG1",                 // 단계 구분·검증용 (SG1 / SG2). 백엔드로는 보내지 않음
  "status": "WAITING",            // WAITING(담당자 입력 대기) / DONE. 생략하면 바꾸지 않음
  "demand": {                     // RFQ에서 추출한 요구값 → rfqs (PATCH). 전달한 필드만 갱신
    "eau": 120000, "sample_qty": 2000, "sample_due_date": "2026-12-15",
    "sop_date": "2027-09-01", "target_price_krw": 38000, "quote_due_date": "2026-10-30"
  },
  "selected_model": "EWP-12-B",
  "options": ["OPT-HT", "OPT-IP9K"],
  "fit_type": "파생 사양",
  "issues": [ ... ],              // 05_expected/2_validation_issues.json 구조 + 항목별 evidence
  "passed_checks": [ ... ],       // 문자열 배열
  "assumptions": [ ... ],
  "customer_questions": [ ... ],  // 문자열 배열
  "contract_risks": [ ... ]       // 항목별 evidence
}

// input (SG2)
{
  "thread_id": "th_7f3a...",
  "rfq_number": "HB-EV7-EWP-001",
  "stage": "SG2",
  "shortages": [ ... ],           // 5_sg2_supply.json의 shortages (part_id, short_qty, incoming_delay_days ...)
  "alternatives": [ ... ],        // 5_sg2_supply.json의 alternatives 4건 (WAIT_INCOMING / PARTIAL_DELIVERY / SUBSTITUTE_PART / NEW_ORDER) + 항목별 evidence
  "recommended_alt": "SUBSTITUTE_PART",
  "final_plan": { "P-MCU-G1": 1500, "P-MCU-G1-ALT": 500 }
}

// output (PUT 응답 그대로)
{ "review_id": 1, "rfq_number": "HB-EV7-EWP-001", "thread_id": "th_7f3a...", "status": "WAITING", "updated_at": "2026-10-19T10:00:00" }
```
- SG1은 담당자 입력 대기 시 `WAITING`으로 한 번 저장하고, 끝나면 `DONE`으로 다시 저장한다. SG2는 자기 필드만 보낸다 (백엔드가 보낸 필드만 갱신하므로 SG1 결과가 지워지지 않음)
- `evidence`: `issues`·`contract_risks`·`alternatives` 각 항목 안의 `[{chunk_id, section, excerpt}]`. 이 서버는 내용 검사 없이 그대로 전달한다. **백엔드는 아직 저장하지 않음** (백엔드 DTO에 필드 추가 필요, 백엔드 담당과 협의 중)
- `confirmed_by`·`confirmed_at`은 받지 않는다. 담당자가 화면에서 가정을 확인할 때(`POST /api/ai/tasks/{taskId}/confirmation`) 백엔드가 로그인 사용자와 시각으로 기록한다
- 두 호출 중 하나가 실패하면 어느 단계에서 실패했는지 오류 메시지에 담는다. 두 API 모두 멱등이라 같은 입력으로 다시 호출하면 된다
- 404: 없는 RFQ

### 7. `save_quote`
견적 초안을 `quotes`에 DRAFT로 **새로 생성**한다. (SG3 `validate_quote` 통과 후)

백엔드 API: `POST /internal/quotes` (201)

```jsonc
// input: thread_id, revision + 6_sg3_quote.json의 quote_draft (quote_id, status 제외)
{
  "thread_id": "th_7f3a...",
  "revision": 1,
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
{ "quote_id": "Q-2026-1019-001", "status": "DRAFT", "review_id": 1 }
```
- `quote_id` 채번은 백엔드가 한다 (`Q-YYYY-MMDD-NNN`, `issue_date` 기준. 생략하면 오늘). `6_sg3_quote.json` 주석은 이 API를 `create_quote`라 부른다 → Tool 이름은 `save_quote` 유지
- 필수: `thread_id`, `revision`, `rfq_number`, `model_id`, `unit_price_krw`, `price_by_year` (백엔드 검증과 같음)
- 백엔드가 `thread_id`+`rfq_number`로 분석 결과(review)를 찾는다 → `review_id`는 받지 않는다 (보내도 무시됨). review가 없으면 404
- `revision`: 첫 견적 1, 담당자 수정 요청(REVISE)마다 1 증가 (최대 4). 같은 `thread_id`+`revision`이 다시 오면 기존 `quote_id`를 반환 (중복 생성 방지). REVISE 승인 없이 다음 revision이면 409
- `recipient`, `incoterms`, `price_breakdown`, `parts_cost_breakdown`은 **평평하게** 보낸다. 백엔드가 `quotes.details` JSONB에 모은다 (`details`로 묶어 보내면 백엔드가 읽지 않음). `item`, `eau`, `sop_date`는 백엔드가 `rfqs`에서 읽으므로 저장하지 않는다
- 검토 결과 갱신(`PATCH /internal/quotes/{quoteId}`, DRAFT일 때 `checks`·`conditions`)은 Tool로 만들지 않았다 (필요하면 추가)

### 8. `register_quote`
사람이 승인한 견적을 등록 상태로 바꾼다. **되돌리기 어려운 작업.** (SG4: 승인 → `check_parts_supply` 재확인 통과 후에만)

백엔드 API: `POST /internal/quotes/{quote_id}/register`

```jsonc
// input
{ "quote_id": "Q-2026-1019-001", "thread_id": "th_7f3a..." }

// output
{ "quote_id": "Q-2026-1019-001", "status": "REGISTERED", "rfq_status": "QUOTED", "outcome": "CREATED" }
```
- 승인 여부 확인은 **백엔드가 한다**: `quotes.status`가 `APPROVED`가 아니면 409 → Tool 오류 "승인되지 않은 견적"
- 승인자(`approved_by`)는 FE 승인 API에서 이미 기록됨. 이 Tool은 받지 않는다
- 백엔드는 등록과 함께 `rfqs.status`를 `QUOTED`로 바꾼다 (`7_sg4_register.json`)
- 멱등: 새로 등록하면 201 `outcome: CREATED`, 이미 REGISTERED면 200 `outcome: ALREADY_PROCESSED`
- description에 "사람 승인 후에만 호출" 명시

### 9. `get_rfq_content`
RFQ 번호로 고객이 업로드한 견적요청서 원문을 가져온다. (SG1 `parse_rfq`의 입력)

백엔드 API (호출 순서대로):
1. `GET /internal/rfqs/{rfqNumber}`: RFQ 정보 JSON (`item`, `file_path` 등)
2. `GET /internal/rfqs/{rfqNumber}/content`: 원문 텍스트 (`text/markdown`, JSON 아님)

```jsonc
// input
{ "rfq_number": "HB-EV7-EWP-001" }

// output
{
  "rfq_number": "HB-EV7-EWP-001",
  "item": "전동식 워터펌프 (EWP, 12V)",
  "file_name": "RFQ_HB-EV7-EWP-001.md",       // rfqs.file_path의 파일 이름
  "content": "# 견적 요청서 (RFQ)\n..."   // 원문 그대로 (01_rfq/RFQ_HB-EV7-EWP-001.md)
}
```
- 백엔드 RFQ 정보에 고객사 이름·접수 시각이 없어 `customer`, `received_at`은 반환하지 않는다
- 원문은 가공하지 않고 그대로 반환한다. 파싱(`rfq_schema.json` 형식 추출)은 SG1 `parse_rfq`의 몫
- 원문 위치는 `rfqs.file_path`이며 파일은 백엔드가 읽는다. 이 서버는 파일 시스템에 접근하지 않는다
- 조회라서 `Idempotency-Key` 없음
- 오류: RFQ 정보 404 → "RFQ를 찾을 수 없음", 원문 404 → "RFQ 원문이 없거나 읽을 수 없음" Tool 오류. 백엔드는 업로드를 `.md`만 받고(아니면 400) 415는 보내지 않는다 (백엔드 담당 확인, 2026-10-21)
- 응답이 너무 크면 LLM 컨텍스트를 넘칠 수 있다 → 백엔드 응답이 200KB를 넘으면 Tool 오류로 알림 (데모 RFQ는 수 KB)

### 백엔드와 맞출 계약
백엔드 `SupplySync-BE`(main, 2026-10-21)에 아래 API가 있다. 이 서버는 이 계약에 맞춰 호출한다 (조회 API는 아래 "조회 API" 표). 오류 응답은 `{code, message, details, timestamp}`.

| API | 성공 | 실패 |
|---|---|---|
| `PATCH /internal/rfqs/{rfqNumber}` | 200 + RFQ 정보 | 404 없는 RFQ |
| `PUT /internal/rfq-reviews` | 200 + `{review_id, rfq_number, thread_id, status, updated_at}` | 400 입력 오류, 404 없는 RFQ |
| `POST /internal/quotes` | 201 + `{quote_id, status, review_id}` (같은 revision이면 기존 견적) | 400 입력 오류, 404 분석 결과·모델 없음, 409 revision 순서 위반 |
| `POST /internal/quotes/{quote_id}/register` | 201 신규 / 200 이미 등록 + `{quote_id, status, rfq_status, outcome}` | 404 없음, 409 APPROVED 아님 |
| `GET /internal/rfqs/{rfqNumber}` | 200 + `{rfq_number, item, file_path, ...}` | 404 없음 |
| `GET /internal/rfqs/{rfqNumber}/content` | 200 + 원문 텍스트 (`text/markdown`) | 404 원문 없음·읽을 수 없음 |

조회 API (도구 1~5, 응답 필드는 Swagger 기준):

| API | 쓰는 도구 | 실패 |
|---|---|---|
| `GET /internal/models` | get_models, (get_options) | — |
| `GET /internal/models/{modelId}` | get_options | 404 없는 모델 |
| `GET /internal/models/{modelId}/bom?options=…` | get_bom, get_parts_cost | 404 없는 모델, 400 붙일 수 없는 옵션 |
| `GET /internal/parts/{partId}` | check_parts_supply, (get_bom, get_parts_cost 보충) | 404 없는 부품 |
| `GET /internal/parts/{partId}/inventory` | check_parts_supply | 404 |
| `GET /internal/parts/{partId}/substitutes` | check_parts_supply (SHORT일 때만), get_substitutes | 404 |

백엔드 담당과 남은 것: `evidence` 저장 (백엔드 DTO에 필드 추가)

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
- 조회 도구 1~5도 `MockRestServiceServer`로 테스트한다. 백엔드 응답 fixture(JSON)는 v2.2 시드 값으로 `src/test/resources/backend/`에 두고(Swagger 응답 형식 그대로), 도구 출력은 위 기대값·정답 JSON과 비교한다 (기존 기대값 재사용)
- 확인할 것: 호출 경로·쿼리(`options=OPT-HT,OPT-IP9K`)·`X-Internal-Api-Key` 헤더, 부족 부품에만 `/substitutes` 호출, 같은 부품 중복 호출 없음, 404·400 → Tool 오류 메시지
- 계산(부족 수량, 상태, 날짜, 위험 코드, 합계)은 service 순수 메서드 단위 테스트 유지
- `BasicConfigurationTest`: API 키 검사·Tool 10개 노출만 확인 (DB 설정 검사 삭제)
- `get_substitutes`: `P-MCU-G1`, `short_qty` 500, `base_date` 2026-10-19 → 위 예시와 같은 값. `check_parts_supply`의 P-MCU-G1 `substitutes`와 필드·값이 완전히 같은지 비교. 대체 후보 없는 부품(예: `P-MTR-BL12`) → `substitutes: []`
- 백엔드 API Tool(6~9) 테스트: `MockRestServiceServer`로 백엔드 응답을 흉내 내고 확인할 것
  - `get_rfq_content`: mock 원문 응답(text/markdown)에 v2.2 `01_rfq/RFQ_HB-EV7-EWP-001.md` 내용을 넣고 그대로 반환되는지, 404 → Tool 오류
  - 요청 경로·메서드·`X-Internal-Api-Key` 헤더·body가 명세대로 나가는지, `register_quote`에만 `Idempotency-Key`가 붙는지
  - 200 → output 그대로 반환 / 409 → "현재 상태에서 불가" Tool 오류 / 500·타임아웃 → "백엔드 오류" Tool 오류

## 실제 백엔드 연결 테스트
- 기본 `mvn test`는 네트워크 없이 mock만 쓴다
- 실제 확인: BE를 `SPRING_PROFILES_ACTIVE=demo`로 띄우고 `.env`의 `BACKEND_URL`·`INTERNAL_API_KEY`를 맞춘 뒤 `LIVE_BACKEND=true mvn test -Dtest=LiveBackendToolsTest` (조회 도구 1~5와 `get_rfq_content`를 위 기대값으로 확인. 쓰기 도구는 데이터를 바꾸므로 제외)

## 이 레포 범위 밖 / 결정 대기
- **백엔드 `/internal/**` API**: 백엔드 담당 구현 완료, 수정하지 않는다. 실제 백엔드에 연결해 확인하는 일은 남음
- **읽기 경로**: 교재대로 조회도 백엔드 API를 거치도록 결정 (2026-10-21). DB 조회 전용 계정(`business_mcp_user`)은 더 이상 쓰지 않는다 → 팀에 알리고 Supabase에서 정리할지 결정
- **위험 코드·부족 수량 계산 위치**: 백엔드 조회 API에 같은 계산이 있으면 그 값을 쓰도록 바꾼다 (규칙은 한 곳에만). 없으면 출력용 파생값으로 이 서버에 둔다
- **진행 이벤트** (`POST /internal/tasks/{id}/events`): MCP Tool이 아니다. AI 서버가 백엔드로 직접 보낸다 (`SupplySync-AI`의 `get_internal_api_client()`, 백엔드 담당 확정 2026-10-21). 이벤트가 와야 SSE와 NEEDS_CLARIFICATION·WAITING_* 전이가 일어난다. 백엔드 계약: payload `type`(필수, 모르는 값은 400)·`step`·`message`·`trace_url`·`data`, 이미 DONE/FAILED인 작업이면 409. 보내는 노드(interrupt 시점)와 409·5xx 처리는 Agent 팀이 정한다. "Agent는 백엔드를 직접 부르지 않는다"는 업무 데이터 조회·저장(Tool 10개)에 한정된 말이다
- **rag-mcp**: 별도 서버. `search_docs` Tool 1개 + 원문 Resource `doc://chunks/{chunk_id}` (교재 요건)
- 필드 이름은 SG 담당자·Agent 쪽 mock 클라이언트와 공유되므로, 변경 시 이 문서를 먼저 갱신
