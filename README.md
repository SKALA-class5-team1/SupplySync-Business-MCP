# Business MCP 기본 설정

AI의 MCP 요청을 받고 PostgreSQL에서 조회한 결과를 반환하기 위한 Spring Boot 서버입니다.
현재는 **DB 연결과 API 키 인증만 설정**되어 있으며, 등록된 Tool·업무 SQL은 없습니다.
다른 업무 서버의 주소는 필요하지 않습니다.

## 구성

- `BusinessMcpApplication`: 서버 실행 시작점
- `controller/`, `service/`, `dto/`: 기능이 정해지면 작성할 빈 폴더
- `config/InternalApiKeyFilter`: 요청 헤더의 API 키 검사
- `application.yml`: MCP·PostgreSQL 연결 설정
- `.env`: 로컬 접속 정보와 비밀값. Git에 포함하지 않음

추후 Tool 진입점에서 Service를 호출하고, Service에서 DB 조회 결과를 DTO로 반환하면 됩니다.
Spring JDBC를 사용하므로 조회에 필요한 `JdbcTemplate`은 자동으로 준비됩니다.
JPA·Flyway·테이블 생성 기능은 포함하지 않았습니다.

## PostgreSQL

프로젝트 루트의 `.env`를 자동으로 읽습니다. 처음 받는 환경에서는 `.env.example`을 복사하고 비밀번호·키를 채웁니다.
파일은 `KEY=value` 형식으로 작성하며 값에 따옴표를 붙이지 않습니다.

| 변수 | 의미 | 현재 설정 |
| --- | --- | --- |
| `DB_HOST` | PostgreSQL 주소 | `localhost` |
| `DB_PORT` | PostgreSQL 포트 | `5432` |
| `DB_NAME` | 데이터베이스 이름 | `supplysync` |
| `DB_SCHEMA` | 테이블을 찾을 기본 스키마 | `public` |
| `DB_USER` | 조회에 사용할 계정 | `rag_reader` |
| `DB_PASSWORD` | 해당 계정의 비밀번호 | `.env`에서 설정 |

제공된 `RAG_DB_USER`·`RAG_DB_PASSWORD` 값을 이 서버의 `DB_USER`·`DB_PASSWORD`로 사용했습니다.
마이그레이션용 `supplysync_app`과 관리자 계정의 자격 증명은 저장하지 않았습니다.
`rag_reader`가 실제 업무 테이블을 조회할 수 있는지는 DB에서 확인해야 합니다.

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

`/mcp`와 `/internal/**`를 포함한 모든 경로에 적용합니다. `/internal/**` 업무 API 자체는 아직 없습니다.
키가 없거나 틀리면 `401 Unauthorized`를 반환하고 요청 처리를 중단합니다.
서버의 `INTERNAL_API_KEY` 설정이 없거나 비어 있으면 시작에 실패합니다.
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

테스트는 키가 없거나 틀린 요청 차단, 올바른 키로 MCP 초기화, Tool 0개, 읽기 전용 연결 설정을 확인합니다.
테스트용 설정을 사용하며 실제 DB에는 접속하지 않습니다.
현재 로컬 `localhost:5432` 연결은 거부되어 실제 DB 접속·계정 권한·스키마는 확인하지 못했습니다.

`target/`은 Maven이나 IDE가 생성하는 빌드 결과입니다. 소스가 아니므로 삭제할 수 있고, 다음 빌드 때 다시 생성됩니다.
