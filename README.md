# Business MCP 기본 설정

AI의 MCP 요청을 받고 PostgreSQL에서 조회한 결과를 반환하기 위한 Spring Boot 서버입니다.
Tool 9개가 등록되어 있습니다. 명세는 `CLAUDE.md`, 출력 필드명 기준은 `src/test/resources/expected/`의 정답 JSON(5~7) 참고 (원본: 지영님이 공유한 `rfq_dataset_v2.2.zip`의 `05_expected/`).
- 읽기 5개 (`get_models`, `get_options`, `get_bom`, `check_parts_supply`, `get_parts_cost`): PostgreSQL 직접 조회 (읽기 전용)
- 쓰기 3개 (`save_review`, `save_quote`, `register_quote`): DB에 쓰지 않고 Spring 백엔드 `/internal/**` API를 호출
- RFQ 원문 조회 (`get_rfq_content`): 원문 파일은 백엔드가 보관하므로 백엔드 API를 호출

백엔드 API Tool을 쓰려면 백엔드 주소(`BACKEND_URL`)가 필요합니다. 백엔드 호출 헤더에는 `INTERNAL_API_KEY`를 그대로 씁니다.

## 구성

- `BusinessMcpApplication`: 서버 실행 시작점
- `controller/BusinessTools`(DB 읽기), `controller/BackendTools`(백엔드 API): `@McpTool` 진입점 (Spring AI 1.1 MCP 어노테이션)
- `client/BackendClient`: 백엔드 호출 (`RestClient`, 연결 3초·응답 10초, 응답 200KB 제한, 재시도 없음. `Idempotency-Key`는 `register_quote`에만)
- `service/`: BOM 전개, 공급·위험 계산, 원가 합계
- `repository/`: JdbcTemplate 조회 (표준 SQL, 파라미터 바인딩)
- `dto/`: Tool 입출력 record (JSON은 snake_case)
- `config/InternalApiKeyFilter`: 요청 헤더의 API 키 검사
- `application.yml`: MCP·PostgreSQL 연결 설정
- `.env`: 로컬 접속 정보와 비밀값. Git에 포함하지 않음

Tool 진입점(`controller/`)이 Service를 호출하고, Service가 repository로 DB를 조회해 DTO로 반환합니다.
DB 조회는 Spring JDBC의 `NamedParameterJdbcTemplate`을 씁니다.
JPA·Flyway·테이블 생성 기능은 포함하지 않았습니다.

## PostgreSQL

프로젝트 루트의 `.env`를 자동으로 읽습니다. 처음 받는 환경에서는 `.env.example`을 복사하고 비밀번호·키를 채웁니다.
파일은 `KEY=value` 형식으로 작성하며 값에 따옴표를 붙이지 않습니다.

| 변수 | 의미 | 현재 설정 |
| --- | --- | --- |
| `DB_HOST` | PostgreSQL 주소 | Supabase pooler 주소 |
| `DB_PORT` | PostgreSQL 포트 | `5432` (pooler 세션 모드) |
| `DB_NAME` | 데이터베이스 이름 | `postgres` |
| `DB_SCHEMA` | 테이블을 찾을 기본 스키마 | `public` |
| `DB_USER` | 조회에 사용할 계정 | 조회 전용 `business_mcp_user` (pooler에서는 `business_mcp_user.<project_ref>` 형식) |
| `DB_PASSWORD` | 해당 계정의 비밀번호 | `.env`에서 설정 |
| `DB_SSLMODE` | JDBC `sslmode` | Supabase 등 원격 DB는 `require` (기본 `prefer`) |
| `DB_POOL_SIZE` | 커넥션 풀 최대 크기 | 기본 5 (Supabase pooler 연결 수 제한) |
| `BACKEND_URL` | 백엔드 API Tool이 호출할 Spring 백엔드 | 기본 `http://localhost:8080` |

이 서버는 DB를 읽기만 합니다 (쓰기는 백엔드 API를 거침). 그래서 DB 계정은 **이 서버가 읽는 7개 테이블
(`models`, `options`, `model_options`, `parts`, `bom`, `inventory`, `part_substitutes`)의 SELECT만** 있으면 됩니다.
Supabase에서는 7개 테이블에 RLS가 켜져 있으므로, SELECT 권한과 함께 해당 계정용 RLS SELECT 정책도 있어야 합니다.
정책이 없으면 오류 없이 0행이 나오고, `LiveDatabaseToolsTest`가 실패해서 바로 알 수 있습니다.

연결 풀에 `read-only: true`, PostgreSQL 드라이버에 `readOnlyMode: always`를 설정했습니다.
자동 커밋 상태의 조회에도 읽기 전용 설정을 적용하기 위한 조합입니다. [pgJDBC 문서](https://jdbc.postgresql.org/documentation/use/)

이 설정은 DB 계정의 권한을 변경하지 않습니다. 실제 조회 전용 계정에는 DB의 `CONNECT`,
대상 스키마의 `USAGE`, 필요한 테이블의 `SELECT` 권한을 부여하고 쓰기 권한을 주지 않아야 합니다.
[PostgreSQL 권한 문서](https://www.postgresql.org/docs/18/ddl-priv.html)

## 스키마 정보는 언제 필요한가

접속 자체에는 호스트·포트·DB 이름·계정이 필요합니다. 스키마는 DB 안에서 테이블을 구분하는 이름 공간입니다.
`public`을 사용한다면 현재 설정을 그대로 두면 됩니다. 다른 스키마라면 `DB_SCHEMA`를 바꿉니다.

실제 조회 Tool을 만들 때는 **조회할 스키마·테이블·컬럼·자료형**, 조인한다면 **키와 테이블 관계**가 필요합니다.
그 단계에서 필요한 테이블의 DDL이나 ERD를 기준으로 SQL을 작성합니다.
지금은 이 정보를 추측하여 조회 기능을 만들지 않았습니다.

## API 키 인증

클라이언트는 모든 요청에 다음 헤더를 보냅니다.

```text
X-Internal-Api-Key: <INTERNAL_API_KEY 값>
```

`/mcp`와 `/internal/**`를 포함한 모든 경로에 적용합니다. 이 서버에는 `/internal/**` API가 없습니다 (백엔드 API Tool이 호출하는 `/internal/**`는 백엔드 쪽).
키가 없거나 틀리면 `401 Unauthorized`를 반환하고 요청 처리를 중단합니다.
서버의 `INTERNAL_API_KEY` 설정이 없거나 비어 있으면 시작에 실패합니다.
같은 키를 이 서버가 Spring 백엔드(`/internal/**`)를 호출할 때 `X-Internal-Api-Key` 헤더에도 씁니다.
AI 서버·이 서버·백엔드가 내부 호출 키 하나(`INTERNAL_API_KEY`)를 같은 값으로 공유합니다.
키를 가진 클라이언트의 접근을 허용하는 방식이며, 별도의 서버 신원 검증은 하지 않습니다.

MCP에는 HTTP GET·POST·DELETE 요청이 사용되므로 MCP 클라이언트의 공통 헤더 설정에 키를 넣어야 합니다.

## 실행과 확인

JDK 21과 Maven이 필요합니다. `.env`를 준비한 뒤 실행합니다.

```bash
mvn spring-boot:run
```

MCP 주소는 `http://127.0.0.1:8101/mcp`, 전송 방식은 `Streamable HTTP`입니다.
다른 호스트에서 접속해야 한다면 `.env`에 `SERVER_ADDRESS=0.0.0.0`을 설정하고 해당 서버의 주소로 접속합니다.

```bash
mvn test
```

테스트는 키가 없거나 틀린 요청 차단, 올바른 키로 MCP 초기화, Tool 9개, 읽기 전용 연결 설정을 확인하고,
H2(`MODE=PostgreSQL`)에 **예시** 시드(지영님이 공유한 `rfq_dataset_v2.2.zip`의 `02_postgres/`에서 복사)를 적재해 MCP `tools/call`로 Tool 결과를 정답 파일(`src/test/resources/expected/`, 같은 zip의 `05_expected/`에서 복사)과 비교합니다.
백엔드 API Tool은 `MockRestServiceServer`로 백엔드를 흉내 내 요청 경로·헤더·본문과 오류 변환(404, 409, 415, 5xx, 타임아웃, 200KB 초과)을 확인합니다.
실제 DB·백엔드에는 접속하지 않으므로, 준비되면 같은 호출로 다시 확인해야 합니다.
실제 DB(Supabase PostgreSQL)에서 같은 기대값을 확인하려면 `.env`를 맞춘 뒤 실행합니다. SELECT만 실행하며, 커넥션이 읽기 전용인지도 확인합니다.

```bash
LIVE_DB=true mvn test -Dtest=LiveDatabaseToolsTest
```

`target/`은 Maven이나 IDE가 생성하는 빌드 결과입니다. 소스가 아니므로 삭제할 수 있고, 다음 빌드 때 다시 생성됩니다.
