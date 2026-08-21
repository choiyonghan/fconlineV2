-- Nexon spid.json(선수ID -> 이름)의 서버측 캐시. v1은 이 대용량 정적 파일을 페이지
-- 로드마다 브라우저가 재요청했다(analysis 6.10) — 배치가 주기적으로 갱신한다.
CREATE TABLE player_meta (
    sp_id      VARCHAR(32)  PRIMARY KEY,
    sp_name    VARCHAR(100) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
