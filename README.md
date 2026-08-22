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
- Nexon Open API 키 (최대 6개, https://openapi.nexon.com 에서 발급)

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
export NEXON_API_KEY_2=...   # 선택 (있으면 429 내성 상승)
export NEXON_API_KEY_3=...   # 선택
export NEXON_API_KEY_4=...   # 선택
export NEXON_API_KEY_5=...   # 선택
export NEXON_API_KEY_6=...   # 선택
export GEMINI_API_KEY=...    # 선택 — /api/v1/insights/ask(자연어 질문 분석)용, Google AI Studio 무료 티어 키
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

## 배포

- **프론트엔드**: `.github/workflows/deploy-web.yml`이 `apps/web/**` 변경을 `main`에 push하면
  자동으로 GitHub Pages(`https://<owner>.github.io/<repo>/`)에 배포한다. 저장소 Settings →
  Pages → Source가 **"GitHub Actions"**로 설정돼 있어야 한다("Deploy from a branch"면 GitHub이
  README.md를 대신 렌더링해 보여준다). 빌드 시 `BASE_PATH`(프로젝트 페이지 서브패스)와
  `vars.VITE_API_BASE_URL`(아래 백엔드 URL)을 주입한다 — 후자는 저장소 Settings → Secrets and
  variables → Actions → **Variables** 탭에 등록해야 한다.
- **백엔드**: GitHub Pages는 정적 파일만 서빙하므로, 정적 프론트가 실제로 데이터를 부르려면
  백엔드가 어딘가 상시 떠 있어야 한다. `backend/Dockerfile` + `render.yaml`(Render Blueprint)로
  준비해뒀다 — Render 대시보드 → New → Blueprint → 이 저장소 선택하면 `render.yaml`을 읽어
  자동 구성한다. `sync: false`로 표시된 환경변수(DB 접속정보, Nexon 키)는 Render 대시보드에서
  직접 입력해야 한다(파일에 실제 값을 커밋하면 안 됨). 배포된 URL을 위 `VITE_API_BASE_URL`
  변수에 넣고 `deploy-web.yml`을 재실행하면 프론트가 그 백엔드를 바라보게 된다.

## Nexon match-detail 매핑 (해결됨)

v1 Supabase(anon key, 읽기전용) 표본 조회로 1차 구조를 확인한 뒤, Nexon 공식 match-detail API 문서로
아래 항목을 전부 확정했다(`NexonApiClient` 클래스 주석 참고). 이전에 이 문서가 TODO로 남겨뒀던 4가지는
전부 해결됐고, 그 과정에서 실제로는 코드가 잘못된 노드에서 값을 읽어 **매치당 골/슛/패스/태클 수가
항상 0으로, 개별 슛 이벤트(`shoot_events`)는 아예 저장되지 않고 있던 버그**를 같이 발견해 고쳤다:

- **응답 구조**: `matchInfo[]` 원소당 통계가 `matchDetail`/`shoot`/`pass`/`defence`/`shootDetail`/`player`
  6개 형제 객체로 나뉜다. 골/슛 집계는 `shoot`, 패스는 `pass`, 태클/블락은 `defence`에 있다 — 전부
  `matchDetail`에서 읽던 예전 코드는 항상 0을 저장하고 있었다.
- **개별 슛 리스트**: 실제 배열은 `shootDetail`이다(`shoot`은 집계 객체라 배열이 아님) — 예전 코드가
  `shoot`을 가리켜 매 경기 슛 이벤트가 0건으로 저장되던 것을 고쳤다.
- **`shootDetail[].type`/`result` 코드**: 공식 문서로 전체 표를 확정 (`ShootType`에 `FINESSE`/`FLARE`/`LOW`/
  `KNUCKLE`/`BICYCLE_KICK` 추가, `ShootResult`는 온타겟/오프타겟/골 3종뿐이라 존재하지 않던 `SAVED`/
  `BLOCKED`/`POST`는 제거).
- **`goalTime` 인코딩**: `period = floor(raw / 2^24) + 1`, `기간 내 경과분 = (raw % 2^24) / 60` — 기존
  비트 분리 로직이 공식 문서와 일치함을 확인, 그대로 유지.
- **`offside`**: `matchDetail.offsideCount`가 실제 필드명이었다(`offside` 단독 필드는 없음) — 컬럼 제거
  대신 필드명만 정정.
- **`player_squad[].status`**: 접두사 없는 `goal`/`assist`/`tackle`/`intercept`/`block` — 기존에 이미 정정됨.

## 참고

- v1 소스 분석 리포트: 이 대화에서 별도 아티팩트로 발행됨 (요청 시 링크 재확인 가능)
