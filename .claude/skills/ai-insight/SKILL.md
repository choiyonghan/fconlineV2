---
name: ai-insight
description: fconlineV2의 자연어 질문 답변 기능(/api/v1/insights/ask, InsightFacade, Gemini 연동, 인사이트 스냅샷)을 만들거나 고치거나 디버깅할 때 먼저 읽는다. "AI 질문", "insights/ask", "Gemini", "InsightFacade", "인사이트 스냅샷", "insight-snapshot" 언급 시 트리거.
---

# AI 질문 답변(insights/ask) 아키텍처

`POST /api/v1/insights/ask`는 자연어 질문 + 컨텍스트(ouid/matchType/seasonId)를 받아
Gemini가 통계 데이터를 근거로 답하는 화면 밖 부가 기능이다. 핵심은 **질문마다 DB를
재조회하지 않는다**는 것 — 매일 아침 배치가 미리 만든 스냅샷 파일을 GitHub에서 읽는다.

## 데이터 흐름

```
InsightSnapshotCliRunner (매일 KST 09:30, GitHub Actions)
  └─ InsightSnapshotBuilder.build(ouid, matchType, seasonId)
       ├─ RecordFacade.getOverallRecord / getAllPlayers / getAssistChains / getRecentMatches
       └─ OpponentFacade.listOpponents + listOpponentMatches(상대 1명씩)
  └─ data/insight-snapshots/{ouid}_{matchType코드}.json 로 로컬에 씀
       (matchType코드: CUSTOM=40, OFFICIAL=50)
  └─ 워크플로우가 git commit + push

InsightFacade.ask() (요청마다, 실시간)
  └─ GithubInsightSnapshotClient.fetch(ouid, matchType)
       → raw.githubusercontent.com/choiyonghan/fconlineV2/main/data/insight-snapshots/*.json
  └─ 있으면: 그 파일의 summaryText + (질문에 상대 닉네임이 있으면) opponentDetailByNickname 사용
  └─ 없으면(첫 실행/신규 시즌 등): InsightSnapshotBuilder.build(...)로 즉석 조립 + WARN 로그
  └─ GeminiApiClient.ask(SYSTEM_INSTRUCTION, dataSummary + 질문)
```

**DB 테이블을 따로 두지 않는다** — GitHub 저장소(`data/insight-snapshots/`)를 캐시로 쓴다.
이 디렉터리에 대한 커밋은 `render.yaml`의 `buildFilter.ignoredPaths: data/**`로 백엔드
재배포를 유발하지 않는다(데이터만 갱신, 코드 배포 아님).

## 핵심 파일

| 파일 | 역할 |
|---|---|
| `backend/.../app/insight/facade/InsightFacade.java` | ask() 엔트리 — 스냅샷 조회 + 폴백 + Gemini 호출 |
| `backend/.../app/insight/facade/InsightSnapshotBuilder.java` | 스냅샷 콘텐츠 실제 조립(관련 API 전부 호출) |
| `backend/.../app/insight/runner/InsightSnapshotCliRunner.java` | 배치 진입점(`--spring.profiles.active=insight-snapshot`) |
| `backend/.../infrastructure/insight/GithubInsightSnapshotClient.java` | raw.githubusercontent.com에서 스냅샷 fetch |
| `backend/.../infrastructure/insight/GithubInsightSnapshotFile.java` | 스냅샷 JSON 스키마(record) |
| `.github/workflows/insight-snapshot.yml` | 매일 KST 09:30 cron + commit/push |
| `data/insight-snapshots/README.md` | 파일 스키마 문서 |

## 캡(용량/비용 통제) — `InsightSnapshotBuilder`의 상수

- `RECENT_MATCH_LIMIT = 10` — 상대 무관 "최근 경기" 개별 기록 개수
- `OPPONENT_MATCH_LIMIT = 15` — 상대 1명당 경기별 상세 기록 개수
- `ASSIST_CHAIN_LIMIT`은 `RecordFacade`쪽 상수(현재 10)

집계값(종합 전적, 선수단 전체 기여도, 득점 유형/시간대 분포, 상대별 승무패)은 캡 없이
전체 경기를 반영한다 — 캡은 "경기별 개별 로그"에만 걸린다. 늘리면 파일 용량 +
Gemini 프롬프트 토큰(비용)이 커진다.

## 자주 하는 작업

- **스냅샷 수동 트리거**: GitHub 저장소 → Actions → "Build Insight Snapshot" →
  Run workflow (workflow_dispatch). 매치 동기화(`sync.yml`, KST 09:15) 이후 실행해야
  최신 매치가 반영된다.
- **스냅샷이 실제로 쓰였는지 확인**: `insights/ask` 응답에 나온 경기 날짜/스탯이
  `data/insight-snapshots/{ouid}_{matchType코드}.json`의 `opponentDetailByNickname`
  내용과 일치하는지 대조 — 일치하면 캐시 히트, 안 하면 즉석 조립 폴백(로그에
  "인사이트 스냅샷이 없어 즉석에서 조립합니다" WARN 확인).
- **로컬 배치 실행**: `java -jar backend/build/libs/*.jar --spring.profiles.active=insight-snapshot`
  (Supabase DB 접속 정보 필요, `application-insight-snapshot.yml` 참고). 결과는
  `data/insight-snapshots/`에 로컬로 써지기만 하고 커밋은 안 됨 — 워크플로우의
  "Commit and push" 스텝이 별도로 처리.
- **캡/스키마 바꿀 때**: `InsightSnapshotBuilder`와 `GithubInsightSnapshotFile`을
  같이 고치고, `InsightFacade`의 파싱 로직(`opponentDetailByNickname` 등)도 맞춰야
  깨지지 않는다.

## 관련 배경(과거 이슈)

- `GlobalExceptionHandler`가 요청 검증 실패(`MethodArgumentNotValidException`)와
  JSON 파싱 실패(`HttpMessageNotReadableException`)를 500으로 뭉개던 버그를
  고친 적 있다(현재는 각각 400으로 분리됨) — `insights/ask`에 빈 질문이나 잘못된
  `matchType`을 보내면 이제 400이 정상이다.
