# Business MCP 기본 설정

AI의 MCP 요청을 받아 Spring 백엔드(`SupplySync-BE`)의 `/internal/**` API를 호출하고, 결과를 Agent가 쓰기 좋은 형태로 돌려주는 Spring Boot 서버입니다.
**DB에 접근하지 않습니다.** 조회든 변경이든 모두 백엔드를 거칩니다 (업무 규칙은 백엔드 한 곳에만).
Tool 10개가 등록되어 있습니다. 명세는 `CLAUDE.md`, 출력 필드명 기준은 `src/test/resources/expected/`의 정답 JSON(5~7) 참고 (원본: 지영님이 공유한 `rfq_dataset_v2.2.zip`의 `05_expected/`).
- 조회 6개 (`get_models`, `get_options`, `get_bom`, `check_parts_supply`, `get_parts_cost`, `get_substitutes`): 백엔드 조회 API(`/internal/models`, `/internal/parts`)를 조합해 출력 형식으로 변환
- 쓰기 3개 (`save_review`, `save_quote`, `register_quote`): 백엔드 쓰기 API 호출
- RFQ 원문 조회 (`get_rfq_content`): 원문 파일은 백엔드가 보관하므로 백엔드 API를 호출

## 구성

- `BusinessMcpApplication`: 서버 실행 시작점
- `controller/BusinessTools`(조회), `controller/BackendTools`(쓰기·RFQ 원문): `@McpTool` 진입점 (Spring AI 1.1 MCP 어노테이션)
- `service/`: 백엔드 응답 조합과 출력용 계산 (부족 수량, 상태, 날짜 차이, 위험 코드, 원가 합계)
- `client/BackendClient`: 백엔드 호출 (`RestClient`, 연결 3초·응답 10초, 응답 200KB 제한, 재시도 없음. `Idempotency-Key`는 `register_quote`에만)
- `client/CatalogClient`: 백엔드 조회 API 경로와 상태 코드별 오류 메시지
- `dto/`: Tool 입출력과 백엔드 응답 record (JSON은 snake_case)
- `config/InternalApiKeyFilter`: 요청 헤더의 API 키 검사
- `application.yml`: MCP·백엔드 연결 설정
- `.env`: 로컬 설정과 비밀값. Git에 포함하지 않음

Tool 진입점(`controller/`)이 Service를 호출하고, Service가 백엔드 API를 호출해 DTO로 반환합니다.
백엔드 응답에 이미 계산된 값(가용 재고, 옵션 적용 BOM, 대체 부품 단가 차이)은 그대로 쓰고 다시 계산하지 않습니다.

## 설정 (.env)

프로젝트 루트의 `.env`를 자동으로 읽습니다. 처음 받는 환경에서는 `.env.example`을 복사하고 키를 채웁니다.
파일은 `KEY=value` 형식으로 작성하며 값에 따옴표를 붙이지 않습니다.

| 변수 | 의미 | 기본값 |
| --- | --- | --- |
| `INTERNAL_API_KEY` | 이 서버가 받는 요청의 키이자, 백엔드 호출 헤더에 쓰는 키 | 없음 (필수) |
| `BACKEND_URL` | 호출할 Spring 백엔드 | `http://localhost:8080` |
| `SERVER_ADDRESS` | 이 서버의 수신 주소 | `127.0.0.1` |

DB 접속 정보(`DB_*`)는 더 이상 쓰지 않습니다. 예전 `.env`에 남아 있으면 지워도 됩니다.

## API 키 인증

클라이언트는 모든 요청에 다음 헤더를 보냅니다.

```text
X-Internal-Api-Key: <INTERNAL_API_KEY 값>
```

`/mcp`와 `/internal/**`를 포함한 모든 경로에 적용합니다. 이 서버에는 `/internal/**` API가 없습니다 (Tool이 호출하는 `/internal/**`는 백엔드 쪽).
키가 없거나 틀리면 `401 Unauthorized`를 반환하고 요청 처리를 중단합니다.
서버의 `INTERNAL_API_KEY` 설정이 없거나 비어 있으면 시작에 실패합니다.
같은 키를 이 서버가 Spring 백엔드(`/internal/**`)를 호출할 때 `X-Internal-Api-Key` 헤더에도 씁니다.
AI 서버·이 서버·백엔드가 내부 호출 키 하나(`INTERNAL_API_KEY`)를 같은 값으로 공유합니다.
키를 가진 클라이언트의 접근을 허용하는 방식이며, 별도의 서버 신원 검증은 하지 않습니다.

MCP에는 HTTP GET·POST·DELETE 요청이 사용되므로 MCP 클라이언트의 공통 헤더 설정에 키를 넣어야 합니다.

## 실행과 확인

JDK 21과 Maven이 필요합니다. `.env`를 준비한 뒤 실행합니다. 백엔드가 꺼져 있어도 서버는 뜨고, Tool 호출만 "백엔드 오류: 연결 실패"로 실패합니다.

```bash
mvn spring-boot:run
```

MCP 주소는 `http://127.0.0.1:8101/mcp`, 전송 방식은 `Streamable HTTP`입니다.
다른 호스트에서 접속해야 한다면 `.env`에 `SERVER_ADDRESS=0.0.0.0`을 설정하고 해당 서버의 주소로 접속합니다.

```bash
mvn test
```

기본 테스트는 네트워크 없이 돕니다.
- 설정: 키가 없거나 틀린 요청 차단, 올바른 키로 MCP 초기화, Tool 10개 등록
- 조회 Tool: `MockRestServiceServer`가 `src/test/resources/backend/`의 백엔드 응답 fixture(v2.2 **예시** 시드, 백엔드 응답 형식 그대로)를 돌려주고, MCP `tools/call` 결과를 정답 파일(`src/test/resources/expected/`)과 비교합니다. 호출 경로·쿼리·헤더, 부족한 부품에만 대체 부품 조회, 같은 부품 중복 호출 없음도 확인합니다
- 쓰기·RFQ 원문 Tool: 요청 경로·헤더·본문과 오류 변환(400 details, 404, 409, 5xx, 타임아웃, 200KB 초과)을 확인합니다

실제 백엔드에서 같은 기대값을 확인하려면 백엔드를 `SPRING_PROFILES_ACTIVE=demo`로 띄우고(v2.2 시드 적재) `.env`의 `BACKEND_URL`·`INTERNAL_API_KEY`를 맞춘 뒤 실행합니다. 조회 Tool과 `get_rfq_content`만 확인하고, 데이터를 바꾸는 쓰기 Tool은 제외합니다.

```bash
LIVE_BACKEND=true mvn test -Dtest=LiveBackendToolsTest
```

`target/`은 Maven이나 IDE가 생성하는 빌드 결과입니다. 소스가 아니므로 삭제할 수 있고, 다음 빌드 때 다시 생성됩니다.
