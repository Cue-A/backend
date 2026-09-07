-- 고정 윈도 카운터. @RateLimit 이 씁니다.
-- KEYS[1] 카운터 키
-- ARGV[1] 허용 횟수, ARGV[2] 윈도(초)
-- 반환: 남은 호출 수. 음수면 초과.
local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
end
return tonumber(ARGV[1]) - current
