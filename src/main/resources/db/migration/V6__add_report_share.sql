-- 리포트 공유 링크 (#135). share_id 가 null 이면 공유하지 않은 리포트입니다.
-- reportId(public_id)와 다른 값이라 해제 후 다시 공유하면 새 링크가 나옵니다.
alter table report add column share_id uuid;
alter table report add column shared_at timestamp(6) with time zone;
alter table report add constraint uk_report_share_id unique (share_id);
