---
name: ai-insight
description: fconlineV2의 자연어 질문 답변 기능(/api/v1/insights/ask, InsightFacade, Gemini 연동, 인사이트 스냅샷)을 만들거나 고치거나 디버깅할 때 먼저 읽는다. "AI 질문", "insights/ask", "Gemini", "InsightFacade", "인사이트 스냅샷", "insight-snapshot" 언급 시 트리거.
---

# AI 질문 답변(insights/ask) 아키텍처

`POST /api/v1/insights/ask`는 자연어 질문 + 컨텍스트(ouid/matchType/seasonId)를 받아
Gemini(AI)가 통계 데이터를 근거로 답하는 화면 밖 부가 기능이다. 핵심은 **질문마다 DB를
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

## 실명 별칭(TrackedUserAliasResolver)

질문이 게임 닉네임("서울쥐") 대신 실명("김상기")으로 들어와도 같은 사람을 알아보게
하려고, `tracked-user.real-names`(= `TRACKED_USER_REAL_NAMES` 환경변수, "닉네임:실명"
콤마 구분)로 별칭을 주입한다. **실명은 이 리포지토리가 public이라 절대 git에 커밋하지
않는다** — 값은 두 군데에 각각 따로 등록해야 실제로 동작한다:

1. **GitHub Actions repo secret** `TRACKED_USER_REAL_NAMES` — `insight-snapshot.yml`이
   스냅샷 JSON을 만들 때 씀(`InsightSnapshotBuilder.withAlias`가 요약 텍스트에
   "닉네임(실명)"으로 구워 넣음). 이게 없으면 스냅샷엔 실명이 안 들어감.
2. **Render 환경변수** `TRACKED_USER_REAL_NAMES` — 스냅샷이 없어 즉석 조립(폴백)할 때 씀.

`InsightFacade.appendMentionedOpponent`도 `TrackedUserAliasResolver.mentions()`로
닉네임/실명 둘 다 매칭한다 — 질문에 실명이 있으면 그 상대의 상세 기록도 정상적으로
덧붙는다.

## 다른 추적 유저 비교 질문

"A랑 B 중 누가 잘해?"처럼 **현재 선택된 유저가 아닌 다른 추적 유저**를 질문에서
언급하면, `InsightFacade.ask()`가 `UserFacade.listTrackedUsers()`로 전체 추적
유저 목록을 조회해 언급된 유저를 찾고, 그 유저 자신의 스냅샷도 `loadContent()`로
따로 불러와 프롬프트에 덧붙인다. 이게 없으면 AI가 "현재 선택된 유저 기준 상대
전적"만 갖고 있어서, 관계없는 두 유저를 비교할 때 공통 상대를 거쳐 추론하는
식으로 답이 새곤 했다(직접 맞대결 기록이 실제로는 각자의 스냅샷 안에 있는데도
못 씀). 한 실명이 계정 2개에 매핑된 경우(예: 전승욱=욱냥0I+지린성에사는욱구)
`TrackedUserAliasResolver.mentions()`가 둘 다 걸려서 두 계정 스냅샷이 모두
붙는다 — 의도된 동작(어느 쪽인지 애매하면 둘 다 보여주는 게 안전).

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
