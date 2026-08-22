package com.fconline.domain.match.vo;

/** 어시스트 선수 -> 득점 선수 조합별 골 수. GOAL이면서 assist=true, assistSpId가 있는 슛만 집계 대상. */
public record AssistChainCount(String assisterSpId, String scorerSpId, long goals) {
}
