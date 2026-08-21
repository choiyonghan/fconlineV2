# fconlineV2

FC Online 상대전적 트래커 v2. [v1(choiyonghan/fconline)](https://github.com/choiyonghan/fconline) 소스 분석을 바탕으로
Java/Spring Boot(DDD) 백엔드 + SvelteKit(CSR) 프론트엔드로 재구현했다.

## 구조

```
fconlineV2/
├── backend/        Spring Boot 3.5 백엔드 (Controller → Facade → Service → Repository)
├── apps/web/        SvelteKit CSR 프론트엔드
└── .github/workflows/  CI, 데이터 동기화 배치(cron), FE 배포
```

백엔드는 패키지-바이-피처(feature-first) 구조다. 최상위는 계층(`app`/`domain`/`infrastructure`), 그 아래는
기능 단위 패키지, 그 아래에 다시 계층 서브패키지가 온다:

```
com.fconline
├── app/<feature>/{controller, facade, dto}      # 응용 계층 — HTTP 진입점 + 유스케이스 오케스트레이션
│   예: app/record/controller/RecordController.java, app/record/facade/RecordFacade.java, app/record/dto/...
├── domain/<feature>/{service, repository, vo}   # 도메인 계층 — 애그리게잇(루트는 패키지 루트)/규칙/포트
│   예: domain/match/Match.java(엔티티), domain/match/service/MatchDomainService.java,
│       domain/match/repository/MatchRepository.java, domain/match/vo/MatchType.java
└── infrastructure/                              # 인프라 계층 — JPA/QueryDSL 구현체, 외부 API 클라이언트
```

`app.sync`는 컨트롤러 대신 배치 진입점(`runner/MatchSyncCliRunner.java`)을 갖는다. 도메인 애그리게잇
루트(예: `Match`, `TrackedUser`, `Season`)는 각 feature 패키지 루트에 그대로 두고, `service`/`repository`/`vo`만
서브패키지로 분리했다 — 단순 CRUD만 필요한 애그리게잇(`TrackedUser`, `Season` 등)은 리포지토리 포트/구현체를
따로 분리하지 않고 Spring Data JPA 인터페이스를 `domain/<feature>/repository/`에 직접 둔다.

도메인 모델, API 목록, 설계 근거는 `.claude/plans` 하위 계획 문서와 코드 내 주석(특히 `analysis N절` 참조 주석)에
상세히 남겨두었다 — v1의 어떤 문제를 어떻게 고쳤는지 각 클래스 주석에서 확인 가능하다.

## 사전 준비

- JDK 21
- Node.js 20+ (`apps/web`)
- PostgreSQL (기존 Supabase 프로젝트 그대로 사용 가능)
- Nexon Open API 키 (최대 3개, https://openapi.nexon.com 에서 발급)

### DB 접속정보 — v1 저장소에서 확인한 것 / 확인 못한 것

v1(choiyonghan/fconline) 클론에서 `app.js`/`official.js`를 확인해 아래를 찾았다.

- **Supabase 프로젝트 URL**: `https://jwqhpdtizrpyohlrqfgu.supabase.co` (project ref: `jwqhpdtizrpyohlrqfgu`)
- **anon(publishable) key**: `sb_publishable_aXnxmQfcuNVBYdbjyHf8xQ_RsJTeBIL` — 브라우저에 노출되는 게 정상인 공개 키.
  이 키로 REST API(`/rest/v1/...`)를 읽기 전용 호출해 실제 v1 스키마(`users`, `matches`, `match_details`,
  `user_opponent_streaks`)를 직접 확인했고, 그 결과로 `NexonApiClient`의 `shoot_detail`/`player_squad`
  파싱 버그 몇 개를 이미 고쳤다(아래 TODO 목록 참고).
- **`SUPABASE_SERVICE_ROLE_KEY`**: v1 GitHub Actions Secrets에만 존재, 저장소 어디에도 커밋되어 있지 않다
  (정상 — 이 키가 커밋되어 있었다면 그 자체가 심각한 보안 사고다).
- **JDBC용 DB 비밀번호(`SUPABASE_DB_PASSWORD`)는 찾지 못했다.** v1은 `@supabase/supabase-js`(REST API
  래퍼)만 썼지 원시 Postgres JDBC 접속을 한 적이 없어서, 애초에 이 비밀번호가 코드 어디에도 존재하지 않는다.
  anon key/service role key와는 별개의 값이라 유추도 불가능하다. Flyway(JPA)는 REST API가 아니라 실제
  JDBC 접속이 필요하므로, 아래 중 하나가 필요하다:
  1. Supabase 대시보드(해당 프로젝트) → **Project Settings → Database → Connection string**에서
     비밀번호를 확인(또는 Reset)해 `SUPABASE_DB_PASSWORD`로 설정, 또는
  2. 기존 프로젝트를 그대로 쓰지 않고 **새 Postgres 인스턴스**(로컬 Docker나 새 Supabase 프로젝트)로 시작.

  `application.yml`의 `SUPABASE_JDBC_URL` 기본값은 위 project ref로 만든 직접연결 호스트
  (`db.jwqhpdtizrpyohlrqfgu.supabase.co:5432`)로 이미 채워뒀다 — 비밀번호만 채우면 그대로 붙는다.
  (환경에 따라 direct connection이 막혀 있으면 Supabase의 connection pooler 호스트/포트로 바꿔야 할 수 있다.)

## 백엔드 실행

```bash
# 필수 환경변수
export SUPABASE_DB_PASSWORD=...             # 위 "DB 접속정보" 참고 — 직접 채워야 함
export NEXON_API_KEY=...
export NEXON_API_KEY_2=...   # 선택 (있으면 429 내성 3배)
export NEXON_API_KEY_3=...   # 선택
export APP_CORS_ALLOWED_ORIGINS=http://localhost:5173
# SUPABASE_JDBC_URL / SUPABASE_DB_USER은 기본값(위 참고)을 쓰지 않을 때만 재정의

./gradlew :backend:bootRun
```

첫 기동 시 Flyway가 `backend/src/main/resources/db/migration`의 마이그레이션을 순서대로 적용한다
(`V1`~`V7`). 최초 1회는 아래 시드 데이터를 직접 넣어야 조회 API가 정상 동작한다 (의도적으로 마이그레이션에
하드코딩하지 않았다 — v1의 "닉네임/시즌이 코드에 박혀있는" 문제를 반복하지 않기 위함):

- `seasons`에 최소 1개 시즌 (`end_date`를 NULL로 두면 진행 중인 시즌)
- `tracked_users`에 추적할 유저 (nickname/ouid)

### 데이터 동기화(배치) 로컬 실행

```bash
./gradlew :backend:bootJar
java -jar backend/build/libs/*.jar \
  --spring.profiles.active=sync \
  --sync.match-type=CUSTOM
```

`.github/workflows/sync.yml`이 6시간마다(KST 03/09/15/21시 15분) CUSTOM/OFFICIAL을 각각 동기화한다.

### 테스트

```bash
./gradlew :backend:test
```

Docker가 있으면 `org.testcontainers:*` 기반 통합 테스트도 함께 돌릴 수 있도록 의존성을 넣어뒀다.
`FconlineApplicationTests`는 Docker 없이도 H2로 컨텍스트 로딩(빈 배선/JPA 매핑 유효성)만 빠르게 검증한다 —
Flyway 마이그레이션 자체(Postgres 전용 문법)는 실제 Postgres/Testcontainers로 별도 검증이 필요하다.

## 프론트엔드 실행

```bash
cd apps/web
cp .env.example .env   # VITE_API_BASE_URL 확인
npm install
npm run dev
```

`npm run build`는 `adapter-static`으로 완전 정적 SPA(`build/`)를 만든다 — GitHub Pages 등 어디에나 그대로 올릴 수 있다.

## 구현 시 반드시 확인해야 하는 것 (TODO)

v1 Supabase(anon key, 읽기전용)에서 `match_details` 표본을 직접 조회해 `shoot_detail`/`player_squad`의
실제 원본 구조를 이미 확인했고(`NexonApiClient` 클래스 주석 참고), 그 결과로 아래 항목은 이미 고쳤다:

- `player_squad[].status`의 필드는 `spGoal`/`spAssist`가 아니라 접두사 없는 `goal`/`assist`/`tackle`/
  `intercept`/`block`이었다 — `parseSquadEntries` 수정 완료.
- `shoot_detail[].type`/`result`는 문자열이 아니라 **정수 코드**였다 — `parseShootType`/`parseShootResult`가
  `Integer`를 받도록 수정했지만, 코드가 실제로 어떤 슛 유형/결과를 의미하는지는 여전히 모른다(아래 참고).
- `shoot_detail[]`에 별도 `period` 필드는 없다 — `goalTime` 값 자체가 period를 인코딩하고 있는 것으로
  보여(표본값이 2^24 미만/이상 두 그룹으로 나뉨) 비트 분리 로직을 넣었다.

**여전히 남은 것** (코드에 `TODO(구현 착수 시 검증 필요)`로 표시):

1. **`shoot_detail[].type`/`result` 정수 코드의 실제 의미** — 표본 6건만으로는 코드 1/2/3(type), 1/3(result)이
   각각 무엇을 뜻하는지 확정할 수 없다. 지금은 항상 `ShootType.UNKNOWN`/`ShootResult.UNKNOWN`을 반환하므로,
   득점 유형별/시간대별 분포 API가 실제로는 빈 값을 낼 것이다. 더 많은 표본을 모으거나 Nexon 공식 문서를
   확인해 `NexonApiClient.parseShootType/parseShootResult`의 매핑을 채울 것.
2. **`goalTime`의 실제 단위** — `period = rawValue / 2^24 + 1`, `분 = (rawValue % 2^24) / 60`으로 근사
   디코딩해뒀지만 이 나눗셈(60)의 근거가 없다 — 실제 단위(초/게임틱 등) 확인 필요.
3. **`match_details.offside`** — v1 DB에는 이 컬럼이 없다(v1 프론트가 항상 0을 표시하던 버그, analysis 6.3).
   v2는 컬럼을 만들어뒀지만, 실제 Nexon match-detail 응답에 필드가 있는지 확인 후 없다면 컬럼/응답 필드를
   제거할 것.
4. **`matchDetail.goalTotal` 등 나머지 스탯 필드명** — `parseParticipant`의 `shootTotal`/`passTry`/`foul`
   등은 여전히 v1 DB 컬럼명을 근거로 한 추정이다(실제 조회한 표본은 스탯이 이미 DB에 저장된 형태였고,
   Nexon 원본 응답의 필드명과 100% 같다는 보장은 없다).

## 참고

- v1 소스 분석 리포트: 이 대화에서 별도 아티팩트로 발행됨 (요청 시 링크 재확인 가능)
