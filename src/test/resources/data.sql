-- [테스트 데이터] H2 테스트 DB 시드. BusinessToolsIntegrationTest가 이 데이터로 기대값을 검증한다.
-- H2 테스트 데이터
-- 지영님이 공유한 rfq_dataset_v2.2.zip의 02_postgres/02_seed.sql (예시 목업, 7개 테이블은 v2.1과 동일)에서 7개 테이블 INSERT만 그대로 복사.
-- 실제 DB 값이 아니다. 테스트 기대값은 이 예시 데이터 기준.

INSERT INTO models VALUES
('EWP-12-A', '소형 워터펌프', 15.0,  50, 9, 16, -40, 105, 'IP67',   43, 'PWM',     100, 85, 80, 0.55, 29000),
('EWP-12-B', '중형 워터펌프', 25.0,  80, 9, 16, -40, 110, 'IP67',   42, 'LIN,PWM', 110, 90, 90, 0.70, 34000),
('EWP-12-C', '대형 워터펌프', 35.0, 100, 9, 16, -40, 125, 'IP6K9K', 48, 'LIN',     118, 98, 94, 0.95, 45000);

INSERT INTO parts VALUES
('P-MTR-BL12',   '모터',                  '한결모터',   9500,  45, TRUE),
('P-MCU-STD',    '제어칩 (표준)',         '세온반도체', 3200,  60, TRUE),
('P-MCU-G1',     '제어칩 (고온용)',       '세온반도체', 5300, 120, TRUE),
('P-MCU-G1-ALT', '제어칩 (고온용, 대체)', '누리칩스',   5600,  30, TRUE),
('P-IMP-25',     '임펠러',                '대성 자체',  1200,  14, FALSE),
('P-HSG-AL',     '알루미늄 케이스',       '동명금속',   4100,  30, FALSE),
('P-SEAL-STD',   '방수 실링 (표준)',      '우성고무',    400,  21, FALSE),
('P-SEAL-9K',    '방수 실링 (강화)',      '우성고무',    950,  21, FALSE),
('P-CON-4P',     '4핀 커넥터',            '케이커넥트',  600,  21, FALSE);

INSERT INTO options VALUES
('OPT-HT',   '고온 대응 (125℃)',     'temp_max',  '125',    0.00, 0, 2400, 'P-MCU-STD',  'P-MCU-G1'),
('OPT-IP9K', '방수 강화 (IP6K9K)',  'ip_rating', 'IP6K9K', 0.05, 1,  900, 'P-SEAL-STD', 'P-SEAL-9K');

INSERT INTO model_options VALUES
('EWP-12-A', 'OPT-HT'),
('EWP-12-B', 'OPT-HT'),
('EWP-12-B', 'OPT-IP9K');

INSERT INTO bom VALUES
('EWP-12-A', 'P-MTR-BL12', 1), ('EWP-12-A', 'P-MCU-STD', 1), ('EWP-12-A', 'P-IMP-25', 1),
('EWP-12-A', 'P-HSG-AL',   1), ('EWP-12-A', 'P-SEAL-STD', 1), ('EWP-12-A', 'P-CON-4P', 1),
('EWP-12-B', 'P-MTR-BL12', 1), ('EWP-12-B', 'P-MCU-STD', 1), ('EWP-12-B', 'P-IMP-25', 1),
('EWP-12-B', 'P-HSG-AL',   1), ('EWP-12-B', 'P-SEAL-STD', 1), ('EWP-12-B', 'P-CON-4P', 1);

INSERT INTO inventory VALUES
('P-MTR-BL12',   18000,  6000, 10000, '2026-11-20'),
('P-MCU-STD',    12000,  4000,     0, NULL),
('P-MCU-G1',      9000,  7500,  5000, '2027-01-20'),   -- 가용 1,500 → 500 부족, 입고 36일 늦음
('P-MCU-G1-ALT',  3000,     0,     0, NULL),           -- 대체 칩, 가용 3,000
('P-IMP-25',     22000,  4000,     0, NULL),
('P-HSG-AL',     15000,  5000,  8000, '2026-12-01'),
('P-SEAL-STD',   20000,  6000,     0, NULL),
('P-SEAL-9K',     8000,  1000,     0, NULL),
('P-CON-4P',     30000, 10000,     0, NULL);

INSERT INTO part_substitutes VALUES
('P-MCU-G1', 'P-MCU-G1-ALT', 14, TRUE, 'alt-002');
