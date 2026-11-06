-- V4__seed_badges.sql
--
-- 배지 정의(마스터) 5종 적재. Issue #131.
--
-- badge / user_badge 테이블은 V1 에 이미 있으나 badge 정의 데이터가 비어 있어,
-- 지급 로직(badge_code 로 badge 조회)이 동작하지 않습니다. 이 마이그레이션은 정의만
-- 넣습니다. 사용자 획득 기록(user_badge)은 건드리지 않습니다.
--
-- 버전 선정: dev 최신 마이그레이션은 V2 이고, 아직 머지되지 않은 PR #133(면접 일정)이
-- V3__calendar_event_title_time_specified.sql 을 추가합니다. 충돌을 피하려고 V3 를
-- 재사용/수정하지 않고 V4 를 씁니다. Flyway 기본 설정은 버전 간 공백(V3 없이 V4)을
-- 허용하므로, 이 브랜치 단독(dev 기반) 테스트에서는 V1 → V2 → V4 로 적용됩니다.
-- 실제 머지 순서는 #133(V3) 이 먼저 들어가 V1 → V2 → V3 → V4 가 되는 것을 전제로 합니다.
--
-- 멱등성: badge_code 가 UNIQUE(자연키)입니다. 이미 같은 코드의 배지가 있는 환경에서도
-- 실패 없이 넘어가도록 ON CONFLICT (badge_code) DO NOTHING 을 씁니다. 기존 정의는
-- 덮어쓰지 않습니다. Flyway 는 같은 버전을 재실행하지 않으므로 신규 DB 에서는 1회만
-- 적재되지만, 수동 재실행·기존 데이터 존재 상황에서도 중복이 생기지 않습니다.
--
-- 표시값 정책(보고 대상):
--   - badge_name / description 은 프론트 홈 배지 목업(home/api/homeMock.ts)의 확정
--     5종 정의를 그대로 따릅니다(프론트가 표시의 원천).
--   - tone(brand/warning/danger/info/success)은 DB badge 테이블에 별도 컬럼이 없어
--     condition_data jsonb 에 함께 저장합니다(성장 관리 응답이 tone 을 요구).
--   - condition_type / condition_data(threshold 등)은 지급 로직이 badge_code 로 분기하므로
--     판정에는 쓰지 않고, 정의를 사람이 읽을 수 있게 남기는 메타데이터입니다.
--   - category 는 값 목록 미확정이라 'ACHIEVEMENT' 로 통일합니다(엔티티 주석과 일치).
--   - status 는 'ACTIVE'(지금도 주는 배지).
--   - icon_url 은 프론트가 badge_code 로 아이콘을 고르므로 null 입니다.

insert into badge
    (badge_code, badge_name, description, category, condition_type, condition_data, icon_url, status, created_at, updated_at)
values
    ('FIRST_SESSION', '첫 연습', '첫 면접 완료', 'ACHIEVEMENT', 'SESSION_COUNT',
     '{"tone": "brand", "threshold": 1}'::jsonb, NULL, 'ACTIVE', now(), now()),
    ('STREAK_3', '3일 연속', '스트릭 3일', 'ACHIEVEMENT', 'STREAK_DAYS',
     '{"tone": "warning", "days": 3}'::jsonb, NULL, 'ACTIVE', now(), now()),
    ('PRESSURE_CLEAR', '압박 극복', '매운맛 완주', 'ACHIEVEMENT', 'PRESSURE_COMPLETED',
     '{"tone": "danger"}'::jsonb, NULL, 'ACTIVE', now(), now()),
    ('RISING', '우상향', '3회 연속 상승', 'ACHIEVEMENT', 'SCORE_RISING',
     '{"tone": "info", "consecutiveRises": 3}'::jsonb, NULL, 'ACTIVE', now(), now()),
    ('TEN_SESSIONS', '10회 달성', '누적 10회', 'ACHIEVEMENT', 'SESSION_COUNT',
     '{"tone": "success", "threshold": 10}'::jsonb, NULL, 'ACTIVE', now(), now())
on conflict (badge_code) do nothing;
