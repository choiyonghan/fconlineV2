-- v1의 site_visitors / increment_visitor_count RPC를 대체.
CREATE TABLE site_visitors (
    visit_date DATE PRIMARY KEY,
    count      BIGINT NOT NULL DEFAULT 0
);
