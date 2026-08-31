# 인사이트 스냅샷

AI 질문 답변(`POST /api/v1/insights/ask`)이 매번 종합 전적/선수단 기여도/어시스트 체인/
최근 경기/상대별 전적을 전부 재조회하면 DB 부하가 쌓입니다. 별도 DB 테이블을 두는 대신
**이 디렉터리를 캐시 저장소로 씁니다**:

- 매일 아침(KST 09:30) `.github/workflows/insight-snapshot.yml`이 추적 대상 유저 ×
  매치타입(CUSTOM/OFFICIAL)마다 `{ouid}_{matchTypeCode}.json` 파일을 생성해 이 디렉터리에
  커밋합니다. (matchTypeCode: CUSTOM=40, OFFICIAL=50)
- 백엔드(`InsightFacade` → `GithubInsightSnapshotClient`)는 이 파일을 `raw.githubusercontent.com`에서
  그대로 읽습니다. 파일이 없거나 조회에 실패하면 즉석에서 조립하는 것으로 대체합니다.
- 이 커밋은 `render.yaml`의 `buildFilter.ignoredPaths`에 의해 백엔드 재배포를 유발하지
  않습니다 — 데이터만 갱신될 뿐 코드 배포가 아니기 때문입니다.

## 파일 스키마

```json
{
  "ouid": "추적 대상 ouid",
  "matchType": "CUSTOM | OFFICIAL",
  "seasonId": 3,
  "snapshotAt": "스냅샷 생성 시각(ISO-8601 UTC)",
  "summaryText": "종합 전적 + 선수단 기여도 + 어시스트 체인 + 최근 경기 + 상대별 전적 요약",
  "opponentDetailByNickname": {
    "상대 닉네임": "그 상대와의 경기별 상세 기록(평점/점유율/슈팅/패스/태클/카드 등)"
  }
}
```

`opponentDetailByNickname`은 질문에 해당 닉네임이 등장할 때만 `summaryText` 뒤에 덧붙여
AI(Groq)에 전달됩니다(`InsightFacade.appendMentionedOpponent`).
