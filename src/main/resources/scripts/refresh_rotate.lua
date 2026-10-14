-- refresh token 회전. TokenService 가 씁니다.
--
-- 회전은 "구 jti 확인 → 삭제 → 신 jti 등록" 세 단계인데, 중간에 다른 요청이
-- 끼어들면 멀쩡한 사용자가 재사용으로 몰려 로그아웃됩니다. 한 덩어리로 돕니다.
--
-- KEYS[1] refresh:{userId}
-- ARGV[1] 구 jti, ARGV[2] 신 jti, ARGV[3] 발급시각, ARGV[4] TTL(초)
-- 반환: 1 회전 성공 / 0 등록되지 않은 jti (재사용 의심 → 전 기기 폐기)
if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then
    -- 서명과 만료는 통과했는데 등록돼 있지 않다면, 이미 회전됐거나 폐기된
    -- 토큰입니다. 탈취된 것으로 보고 이 사용자의 모든 기기를 끊습니다.
    redis.call('DEL', KEYS[1])
    return 0
end
redis.call('HDEL', KEYS[1], ARGV[1])
redis.call('HSET', KEYS[1], ARGV[2], ARGV[3])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
return 1
