-- V3__calendar_event_title_time_specified.sql
--
-- 면접 일정 관리 API(Issue #130). 프론트 일정 등록 모달은 제목(필수)·날짜(필수)·시간(선택)·
-- 메모(선택)만 받고 회사명/직무/면접방식은 보내지 않습니다. 기존 calendar_event 는 "실제 면접
-- 일정" 모델이라 company_name·job_role·interview_method 가 NOT NULL 이고 title·시간미정 구분
-- 칸이 없어 프론트 계약과 맞지 않았습니다. 이슈 "방향 A"(기존 테이블을 프론트 현행에 맞춰 확장)
-- 를 따릅니다.
--
-- 안전성:
--   * V1/V2 는 건드리지 않고 새 번호(V3)로만 추가합니다.
--   * 기존 데이터를 지우지 않습니다. title 은 nullable 로 먼저 추가하고 기존 행을 백필한
--     뒤에만 NOT NULL 로 조입니다(백필 전에 NOT NULL 을 걸면 기존 행 때문에 실패).
--   * NOT NULL 완화는 제약을 "푸는" 방향이라 기존 행·다른 기능에 영향이 없습니다. 기존 행은
--     이미 값이 있고, 완화 후에도 그 값은 그대로입니다. 기존 코드가 이 세 컬럼에 값을 넣는
--     경로가 있어도(현재는 없음) 계속 동작합니다.
--   * interview_method 의 CHECK (ONLINE/OFFLINE) 제약은 유지됩니다. NULL 은 CHECK 를
--     통과하므로(미지정 → unknown → 허용) 완화와 충돌하지 않습니다.

-- 1) 제목 칸 추가 (우선 nullable). 프론트 제한과 같은 50자.
alter table calendar_event
    add column title varchar(50);

-- 2) 시간 지정 여부 칸 추가. 날짜만 있는 일정(그날 00:00, time_specified=false)과
--    "자정 면접"(00:00, time_specified=true)을 구분합니다. 기존 행은 모두 실제 시각이
--    찍힌 면접이라 기본값 true 로 봅니다.
alter table calendar_event
    add column time_specified boolean not null default false;

update calendar_event
   set time_specified = true
 where time_specified = false;

-- 3) 기존 행 title 백필. 기존 모델은 회사명·직무가 필수라 "회사명 직무 면접" 으로 조합합니다.
--    혹시 모를 결측(과거 데이터 이상)에도 NULL 이 남지 않도록 coalesce 로 방어합니다.
update calendar_event
   set title = left(
           trim(both ' ' from
               coalesce(company_name, '') || ' ' ||
               coalesce(job_role, '')     ||
               case when coalesce(company_name, '') <> '' or coalesce(job_role, '') <> ''
                    then ' 면접' else '' end),
           50)
 where title is null;

update calendar_event
   set title = '면접 일정'
 where title is null or btrim(title) = '';

-- 4) 백필이 끝났으니 제목을 필수로 조입니다.
alter table calendar_event
    alter column title set not null;

-- 5) 프론트가 보내지 않는 컬럼의 NOT NULL 완화. 새 API 는 이 세 값을 채우지 않습니다.
alter table calendar_event
    alter column company_name drop not null;

alter table calendar_event
    alter column job_role drop not null;

alter table calendar_event
    alter column interview_method drop not null;
