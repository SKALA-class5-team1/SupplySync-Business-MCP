-- [테스트 데이터] H2 테스트 DB 스키마. 이 서버가 읽는 7개 테이블만 포함.
-- H2 테스트 스키마 (MODE=PostgreSQL)
-- 지영님이 공유한 rfq_dataset_v2.2.zip의 02_postgres/01_schema.sql (7개 테이블은 v2.1과 동일)에서 이 서버가 읽는 7개 테이블 DDL만 그대로 복사.
-- 실제 DB에서 추출한 것이 아니므로, 실제 DB 준비 후 DDL 차이를 다시 확인해야 한다.

CREATE TABLE models (
    model_id        VARCHAR(20)  PRIMARY KEY,               -- EWP-12-A / B / C
    model_name      VARCHAR(50)  NOT NULL,
    flow_lpm        NUMERIC(5,1) NOT NULL,                  -- 유량 L/min
    head_kpa        INT          NOT NULL,                  -- 압력 kPa
    voltage_min     NUMERIC(4,1) NOT NULL,
    voltage_max     NUMERIC(4,1) NOT NULL,
    temp_min        INT          NOT NULL,                  -- ℃
    temp_max        INT          NOT NULL,                  -- ℃
    ip_rating       VARCHAR(10)  NOT NULL,                  -- IP67 < IP6K9K
    noise_db        INT          NOT NULL,
    comm            VARCHAR(20)  NOT NULL,                  -- 'LIN,PWM'
    size_l_mm       INT          NOT NULL,
    size_w_mm       INT          NOT NULL,
    size_h_mm       INT          NOT NULL,
    weight_kg       NUMERIC(4,2) NOT NULL,
    base_price_krw  INT          NOT NULL                   -- 기본 판매 단가
);

CREATE TABLE parts (
    part_id         VARCHAR(20) PRIMARY KEY,
    part_name       VARCHAR(50) NOT NULL,
    supplier        VARCHAR(50) NOT NULL,
    unit_cost_krw   INT         NOT NULL,                   -- 부품 비용 (개당 원가)
    lead_time_days  INT         NOT NULL,                   -- 신규 발주 조달기간
    is_critical     BOOLEAN     NOT NULL                    -- 부족하면 생산 불가
);

CREATE TABLE options (
    option_code       VARCHAR(20)  PRIMARY KEY,             -- OPT-HT / OPT-IP9K
    option_name       VARCHAR(50)  NOT NULL,
    changes_field     VARCHAR(20)  NOT NULL,                -- 바뀌는 models 컬럼
    new_value         VARCHAR(20)  NOT NULL,
    add_weight_kg     NUMERIC(4,2) NOT NULL DEFAULT 0,
    add_noise_db      INT          NOT NULL DEFAULT 0,
    add_price_krw     INT          NOT NULL,                -- 옵션 추가 판매 단가
    replaces_part_id  VARCHAR(20)  REFERENCES parts(part_id), -- BOM에서 빠지는 부품
    uses_part_id      VARCHAR(20)  REFERENCES parts(part_id)  -- BOM에 들어가는 부품
);

CREATE TABLE model_options (
    model_id     VARCHAR(20) REFERENCES models(model_id),
    option_code  VARCHAR(20) REFERENCES options(option_code),
    PRIMARY KEY (model_id, option_code)
);

CREATE TABLE bom (
    model_id      VARCHAR(20) REFERENCES models(model_id),
    part_id       VARCHAR(20) REFERENCES parts(part_id),
    qty_per_unit  INT NOT NULL CHECK (qty_per_unit > 0),
    PRIMARY KEY (model_id, part_id)
);

CREATE TABLE inventory (
    part_id        VARCHAR(20) PRIMARY KEY REFERENCES parts(part_id),
    on_hand        INT  NOT NULL CHECK (on_hand >= 0),      -- 창고 실물
    allocated      INT  NOT NULL CHECK (allocated >= 0),    -- 기존 주문 배정분
    incoming_qty   INT  NOT NULL DEFAULT 0,                 -- 다음 입고 예정 수량
    incoming_date  DATE                                     -- 다음 입고 예정일
    -- 가용 = on_hand - allocated (저장하지 않고 계산)
);

CREATE TABLE part_substitutes (
    part_id             VARCHAR(20) REFERENCES parts(part_id),   -- 원래 부품
    substitute_part_id  VARCHAR(20) REFERENCES parts(part_id),   -- 대체 부품
    validation_days     INT     NOT NULL,                        -- 적용 전 검증기간
    pin_compatible      BOOLEAN NOT NULL,                        -- 회로 변경 불필요 여부
    ref_chunk_id        VARCHAR(30),                             -- 근거 청크 (논리 연결)
    PRIMARY KEY (part_id, substitute_part_id),
    CHECK (part_id <> substitute_part_id)
    -- 추가 원가는 저장하지 않음: parts.unit_cost_krw 차이로 계산
);
