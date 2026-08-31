# fconlineV2

FC Online 상대전적 트래커 v2. [v1(choiyonghan/fconline)](https://github.com/choiyonghan/fconline) 소스 분석을 바탕으로
Java/Spring Boot(DDD) 백엔드 + 정적 HTML/JS 프론트엔드로 재구현했다. **"왜 이렇게 만들었는지"는
[`docs/ADR.md`](docs/ADR.md)에 굵직한 결정 11가지로 정리해뒀다** — 특히 프론트 구조나 AI 벤더처럼
한 번 바꿔봤던 결정을 다시 건드리기 전에 먼저 읽는 걸 권한다.

## 구조

```
fconlineV2/
├── backend/            Spring Boot 3.5 백엔드 (Controller → Facade → Service → Repository)
├── site-root/          메인 프론트엔드 — 정적 HTML/CSS/JS, 빌드 스텝 없음(사이트 루트 "/"로 배포)
│                       report.html이 유일한 실제 페이지 — 유저 칩 "전체"가 9인 요약 대시보드
│                       (AI 랭킹), 실제 유저 칩이 개인별 실시간 리포트. index.html은 report.html로
│                       리다이렉트만 하는 빈 페이지(docs/ADR.md §5 참고 — 한때 2페이지였다가 재통합됨)
├── apps/web/           레거시 SvelteKit CSR — 더 이상 기능 개발 안 함, "/app" 서브패스로만 배포됨
│                       (docs/ADR.md §5 참고 — site-root가 사실상의 메인 프론트다)
├── data/               GitHub Actions 배치가 매일 커밋하는 읽기 전용 캐시(JSON) — 새 DB 테이블 대신 씀
│                       insight-snapshots/(AI 질문용), dashboard-snapshot.json(대시보드 요약+AI 랭킹)
├── docs/ADR.md         아키텍처 결정 기록(ADR) — 이 프로젝트를 고치기 전에 먼저 읽을 문서
└── .github/workflows/  CI, 데이터 동기화/스냅샷 배치(cron), 프론트 배포, 백엔드 콜드스타트 방지 핑
```

**실제로 브라우저에 뜨는 사이트는 `site-root/`다** — `https://<owner>.github.io/<repo>/`가
`site-root/`, `.../app/`가 `apps/web`(SvelteKit) 빌드 결과다. 신규 기능은 전부 `site-root/`에서
개발한다.

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
- Node.js 20+ (레거시 `apps/web`을 로컬에서 직접 개발/빌드할 때만 필요 — `site-root/`는 빌드 도구
  없이 파일을 바로 열거나 아무 정적 서버로 서빙하면 된다)
- PostgreSQL (기존 Supabase 프로젝트 그대로 사용 가능 — v1과 같은 프로젝트를 별도 스키마로 격리해
  재사용한다, `docs/ADR.md` §2 참고)
- Nexon Open API 키 (최대 6개, https://openapi.nexon.com 에서 발급)

### DB 접속정보 — v1 저장소에서 확인한 것 / 확인 못한 것

v1(choiyonghan/fconline) 클론에서 `app.js`/`official.js`를 확인해 아래를 찾았다.

- **Supabase 프로젝트 URL**: `https://jwqhpdtizrpyohlrqfgu.supabase.co` (project ref: `jwqhpdtizrpyohlrqfgu`)
- **anon(publishable) key**: `sb_publishable_aXnxmQfcuNVBYdbjyHf8xQ_RsJTeBIL` — 브라우저에 노출되는 게 정상인 공개 키.
  이 키로 REST API(`/rest/v1/...`)를 읽기 전용 호출해 실제 v1 스키마(`users`, `matches`, `match_details`,
  `user_opponent_streaks`)를 직접 확인했고, 그 결과로 `NexonApiClient`의 `shoot_detail`/`player_squad`
  파싱 버그 몇 개를 이미 고쳤다(아래 "Nexon match-detail 매핑" 절 참고).
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
export GEMINI_API_KEY=...    # 선택 — /api/v1/insights/ask(자연어 질문 분석)용, Gemini 무료 티어 키
                              # (gemini-3.1-flash-lite 등 *-flash-lite 계열로 발급받을 것 — 일반
                              # -flash/-pro 계열은 신규 사용자 RPD가 20으로 막혀있다)
export APP_CORS_ALLOWED_ORIGINS=http://localhost:5173
# SUPABASE_JDBC_URL / SUPABASE_DB_USER은 기본값(위 참고)을 쓰지 않을 때만 재정의

./gradlew :backend:bootRun
```

첫 기동 시 Flyway가 `backend/src/main/resources/db/migration`의 마이그레이션을 순서대로 적용한다
(`V1`~`V16`, 최신 목록은 해당 디렉터리 직접 확인). 최초 1회는 아래 시드 데이터를 직접 넣어야 조회
API가 정상 동작한다 (의도적으로 마이그레이션에 하드코딩하지 않았다 — v1의 "닉네임/시즌이 코드에
박혀있는" 문제를 반복하지 않기 위함):

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

이 외에도 CLI 러너 + 전용 프로파일로 도는 배치가 두 개 더 있다(전부 새 DB 테이블 대신
`data/`에 JSON 파일로 결과를 남기는 패턴 — `docs/ADR.md` §4/§9 참고):

- `--spring.profiles.active=insight-snapshot`: 매일 KST 09:30, AI 질문(`insights/ask`)용 데이터를
  미리 조립해 `data/insight-snapshots/`에 저장(`.github/workflows/insight-snapshot.yml`).
- `--spring.profiles.active=dashboard-snapshot`: 매일 KST 09:45, 대시보드 요약 + AI 랭킹을
  `data/dashboard-snapshot.json`에 저장(`.github/workflows/dashboard-snapshot.yml`).

### 테스트

```bash
./gradlew :backend:test
```

Docker가 있으면 `org.testcontainers:*` 기반 통합 테스트도 함께 돌릴 수 있도록 의존성을 넣어뒀다.
`FconlineApplicationTests`는 Docker 없이도 H2로 컨텍스트 로딩(빈 배선/JPA 매핑 유효성)만 빠르게 검증한다 —
Flyway 마이그레이션 자체(Postgres 전용 문법)는 실제 Postgres/Testcontainers로 별도 검증이 필요하다.

## 프론트엔드 실행

### 메인 사이트(`site-root/`) — 실제로 배포되는 화면

빌드 스텝이 없다. 아무 정적 파일 서버로 그 디렉터리를 서빙하면 된다:

```bash
cd site-root
npx serve .   # 또는 python -m http.server, VSCode Live Server 등 아무거나
```

`report.js`가 백엔드 URL을 하드코딩하고 있으므로(로컬 백엔드를 보게 하려면 그
부분을 직접 `http://localhost:8080`으로 바꿔야 한다 — 별도 `.env` 개념 없음), 라이브 백엔드를
그대로 바라보며 프론트만 로컬에서 고칠 수도 있다.

### 레거시 SvelteKit(`apps/web/`) — `/app` 서브패스, 기능 개발 안 함

`docs/ADR.md` §5에 왜 이게 더 이상 메인이 아닌지 정리해뒀다. 이미 배포 파이프라인에 남아있어
로컬 개발 방법만 남겨둔다:

```bash
cd apps/web
cp .env.example .env   # VITE_API_BASE_URL 확인
npm install
npm run dev
```

`npm run build`는 `adapter-static`으로 완전 정적 SPA(`build/`)를 만든다.

## 배포

- **프론트엔드**: `.github/workflows/deploy-web.yml`이 `site-root/**` 또는 `apps/web/**` 변경을
  `main`에 push하면 자동으로 GitHub Pages(`https://<owner>.github.io/<repo>/`)에 배포한다. **두
  산출물을 하나의 사이트로 합친다**: `apps/web`을 `BASE_PATH=/<repo>/app`로 빌드한 뒤
  `site-root/*`(사이트 루트) + `apps/web/build/*`(`/app` 서브패스)를 합쳐 올린다(`docs/ADR.md`
  §5 참고). 저장소 Settings → Pages → Source가 **"GitHub Actions"**로 설정돼 있어야 한다
  ("Deploy from a branch"면 GitHub이 README.md를 대신 렌더링해 보여준다). `apps/web` 빌드 시
  `vars.VITE_API_BASE_URL`(아래 백엔드 URL)을 주입한다 — 저장소 Settings → Secrets and
  variables → Actions → **Variables** 탭에 등록해야 한다. `site-root/`는 백엔드 URL을 코드에
  하드코딩하므로(`report.js` 상단) 이 변수와 무관하게 동작한다 — 백엔드 URL이
  바뀌면 그 파일도 직접 고쳐야 한다.
- **백엔드**: GitHub Pages는 정적 파일만 서빙하므로, 정적 프론트가 실제로 데이터를 부르려면
  백엔드가 어딘가 상시 떠 있어야 한다. `backend/Dockerfile` + `render.yaml`(Render Blueprint)로
  준비해뒀다 — Render 대시보드 → New → Blueprint → 이 저장소 선택하면 `render.yaml`을 읽어
  자동 구성한다. `sync: false`로 표시된 환경변수(DB 접속정보, Nexon 키, `GEMINI_API_KEY` 등)는
  Render 대시보드에서 직접 입력해야 한다(파일에 실제 값을 커밋하면 안 됨). Render 무료 티어는
  15분 미접속 시 슬립되는데, 별도 워크플로우가 10분마다 헬스체크를 찔러 재우지 않는다
  (`docs/ADR.md` §6). 배포된 URL을 `site-root/report.js`의 `BASE_URL`과 위
  `VITE_API_BASE_URL` 변수 양쪽에 반영해야 프론트가 그 백엔드를 바라보게 된다.

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

- **[`docs/ADR.md`](docs/ADR.md)** — 아키텍처 결정 기록. 패키지 구조, DB 스키마 격리, 프론트
  구조(`site-root` vs `apps/web`), 배포 토폴로지, AI 벤더 선택(Gemini↔Groq 왕복), 캐싱 전략,
  배치 스케줄링, 성격 리포트 저장, xG/MOM 근사 산출까지 11개 결정을 정리해뒀다.
- `data/insight-snapshots/README.md` — AI 질문 답변용 스냅샷 파일 스키마.
- `.claude/skills/ai-insight/SKILL.md` — AI 질문 답변(`insights/ask`) 기능을 고칠 때 참고하는
  아키텍처 문서(데이터 흐름, 실명 별칭, 캡 상수 등).
- v1 소스 분석 리포트: 이 대화에서 별도 아티팩트로 발행됨 (요청 시 링크 재확인 가능)
