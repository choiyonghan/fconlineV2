# fconlineV2

FC Online 상대전적 트래커 v2. [v1(choiyonghan/fconline)](https://github.com/choiyonghan/fconline) 소스 분석을 바탕으로
Java/Spring Boot(DDD) 백엔드 + SvelteKit(CSR) 프론트엔드로 재구현했다.

## 구조

```
fconlineV2/
├── backend/        Spring Boot 3.5 백엔드 (Controller → Facade(application) → Service(domain) → Repository)
├── apps/web/        SvelteKit CSR 프론트엔드
└── .github/workflows/  CI, 데이터 동기화 배치(cron), FE 배포
```

백엔드 패키지 구조, 도메인 모델, API 목록, 설계 근거는 `.claude/plans` 하위 계획 문서와 코드 내 주석(특히
`analysis N절` 참조 주석)에 상세히 남겨두었다 — v1의 어떤 문제를 어떻게 고쳤는지 각 클래스 주석에서 확인 가능하다.

## 사전 준비

- JDK 21
- Node.js 20+ (`apps/web`)
- PostgreSQL (기존 Supabase 프로젝트 그대로 사용 가능)
- Nexon Open API 키 (최대 3개, https://openapi.nexon.com 에서 발급)

## 백엔드 실행

```bash
# 필수 환경변수
export SUPABASE_JDBC_URL=jdbc:postgresql://<host>:5432/<db>
export SUPABASE_DB_USER=...
export SUPABASE_DB_PASSWORD=...
export NEXON_API_KEY=...
export NEXON_API_KEY_2=...   # 선택 (있으면 429 내성 3배)
export NEXON_API_KEY_3=...   # 선택
export APP_CORS_ALLOWED_ORIGINS=http://localhost:5173

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

코드 곳곳에 `TODO(구현 착수 시 검증 필요)` 주석으로 표시해뒀다. 실제 Nexon API 응답을 1회 호출해 확인 후 보정할 것:

1. **`match_details.offside`** — v1은 이 컬럼이 없어 프론트가 항상 0을 표시했다. v2는 컬럼을 만들어뒀지만
   실제 응답에 필드가 있는지 확인 후, 없다면 컬럼/응답 필드를 제거할 것 (`MatchStats`, V2 마이그레이션).
2. **`ShootType`/`ShootResult` 값 매핑** — `NexonApiClient.parseShootType/parseShootResult`가 v1 코드에
   등장한 라벨만으로 우선 채워져 있다. 실제 `shoot` 배열의 `type`/`result` 값 전체 목록 확인 필요.
3. **`matchInfo`/`shoot`/`player` 필드명 전반** — `NexonApiClient.parseParticipant/parseShootEvents/
   parseSquadEntries`의 JsonNode 경로는 v1이 최종적으로 만든 DB 컬럼명을 근거로 추정한 것이다.

## 참고

- v1 소스 분석 리포트: 이 대화에서 별도 아티팩트로 발행됨 (요청 시 링크 재확인 가능)
