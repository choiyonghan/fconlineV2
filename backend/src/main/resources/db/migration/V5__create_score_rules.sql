-- "욱식 점수" 가중치 하드코딩(app.js:5, 686-692)을 데이터로 분리 (analysis 6.8, 7.8).
CREATE TABLE score_rules (
    id          BIGSERIAL PRIMARY KEY,
    target_ouid VARCHAR(64) NOT NULL,
    win_points  INTEGER NOT NULL DEFAULT 3,
    draw_points INTEGER NOT NULL DEFAULT 1,
    lose_points INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_score_rules_target UNIQUE (target_ouid)
);
