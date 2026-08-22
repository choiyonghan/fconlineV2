-- V9의 shoot_detail/player_squad는 shootDetail[]/player[] "배열 두 개"만 원본으로 남겼다 —
-- matchDetail/shoot/pass/defence 같은 집계 객체(division, matchEndType, cornerKick, pass의
-- 세부 성공률, defence.blockTry/blockSuccess 등)는 여전히 원본이 안 남아 "전부 저장"이 아니었다.
-- 참가자 객체(self) 전체를 통째로 하나 더 저장해서, 진짜로 아무 필드도 안 버려지게 한다.

ALTER TABLE match_details
    ADD COLUMN raw_participant JSONB;

COMMENT ON COLUMN match_details.raw_participant IS
    'Nexon match-detail 응답의 matchInfo[] 참가자 원소 하나를 통째로 저장 (matchDetail/shoot/shootDetail/pass/defence/player 전부 포함). shoot_detail/player_squad 컬럼과 내용이 겹치지만, 이 컬럼 하나만으로도 완전한 원본 복구가 가능하도록 유지한다.';
