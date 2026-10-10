#!/usr/bin/env bash
# 인증서 최초 발급. 서버를 처음 세팅할 때 한 번만 실행합니다.
# 이후 갱신은 certbot 컨테이너가 알아서 합니다.
#
#   cd ~/cue-a && ./init-letsencrypt.sh
#
# 실행 전에 확인할 것
#   - .env 에 API_DOMAIN, CERTBOT_EMAIL 이 있다
#   - 도메인 A 레코드가 이 서버 IP 를 가리킨다 (nslookup 으로 확인)
#   - 보안그룹에 80 이 열려 있다
set -euo pipefail
cd "$(dirname "$0")"

set -a; source .env; set +a
: "${API_DOMAIN:?.env 에 API_DOMAIN 이 없습니다}"
: "${CERTBOT_EMAIL:?.env 에 CERTBOT_EMAIL 이 없습니다}"

COMPOSE="docker compose -f docker-compose.prod.yml"

# nginx 가 80 을 잡고 있으면 standalone 발급이 실패합니다.
$COMPOSE stop nginx 2>/dev/null || true

$COMPOSE run --rm -p 80:80 --entrypoint certbot certbot \
  certonly --standalone \
  -d "$API_DOMAIN" \
  --email "$CERTBOT_EMAIL" --agree-tos --no-eff-email \
  --non-interactive

$COMPOSE up -d
echo "[init-letsencrypt] https://$API_DOMAIN 준비 완료"
