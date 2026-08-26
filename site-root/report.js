(function () {
  'use strict';

  var BASE_URL = 'https://fconlinev2-backend.onrender.com';

  /**
   * 유저 칩 맨 앞의 "전체" 가짜 유저 — 기본 선택값(개인별 실시간 리포트 대신 9명 요약
   * 대시보드를 먼저 보여준다). 백엔드를 전혀 거치지 않고 DASHBOARD_SNAPSHOT_URL(매일 아침
   * dashboard-snapshot.yml이 커밋해둔 정적 JSON)만 읽는다 — 백엔드 콜드 스타트와 무관하게
   * 항상 즉시 뜨는 게 목적. 실제 트래킹 유저 ouid와 절대 겹치지 않는 값이라 buildChips/
   * setActiveChip 등 기존 칩 로직을 그대로 재사용할 수 있다.
   */
  var ALL_OUID = '__ALL__';
  var DASHBOARD_SNAPSHOT_URL = 'https://raw.githubusercontent.com/choiyonghan/fconlineV2/main/data/dashboard-snapshot.json';

  /**
   * "욱식 점수"는 닉네임에 "욱"이 들어가는 두 유저(지린성에사는욱구, 욱냥0I)만의 재미 요소 규칙
   * (승5/무3/패1)이다 — v1이 WOOK_NICKNAMES에 하드코딩했던 값과 동일. 백엔드 score_rules 테이블은
   * 건드리지 않고(데이터 마이그레이션 없이) 여기서 노출할 때만 계산한다.
   *
   * 상대별 전적 한 행에는 "내 승점"과 "상대 승점" 둘 다 있고, 각자 자기 자신의 승/무/패 기준으로
   * 따로 계산한다 — 욱식 가중치는 그 점수의 주인이 욱(지린성에사는욱구/욱냥0I)일 때만 그 사람
   * 점수에 붙는다("내 승점"에 상대가 욱이라고 묻어가는 게 아니다). 예: 내혀를가져가(비욱) vs
   * 욱냥0I(욱)가 1승1무1패면 — 내혀를가져가 승점 = 1×3+1×1+1×0 = 4(표준), 욱냥0I 승점은 그
   * 미러 전적(1승1무1패, 이기고 진 게 뒤집힘)에 욱식 가중치 = 1×5+1×3+1×1 = 9.
   * 서버가 내려주는 dugsikScore(항상 3/1/0)는 쓰지 않는다.
   */
  var WOOK_OUIDS = ['1894adb89b4a7953381bdd5671ce7610', '7f3fabc284ffe4b6bedf702a307f0f2e'];
  function isWook(ouid) { return WOOK_OUIDS.indexOf(ouid) !== -1; }
  function scoreFromTally(tally, subjectIsWook) {
    return subjectIsWook
      ? (tally.win * 5) + (tally.draw * 3) + (tally.lose * 1)
      : (tally.win * 3) + (tally.draw * 1);
  }
  /** "내 승점" — 보고 있는 유저 본인의 승/무/패 기준. */
  function myScore(o) {
    return scoreFromTally(o.tally, isWook(state.ouid));
  }
  /** "상대 승점" — 상대 입장에서의 승/무/패(내 승↔상대 패, 무는 그대로)로 뒤집어서 계산. */
  function opponentScore(o) {
    var mirrored = { win: o.tally.lose, draw: o.tally.draw, lose: o.tally.win };
    return scoreFromTally(mirrored, isWook(o.opponentOuid));
  }

  var state = { ouid: null, matchType: 'CUSTOM', seasonId: null };
  var playersGridSort = { col: 'overall', dir: 'desc' };

  var savedSelection = {};
  try {
    savedSelection = JSON.parse(localStorage.getItem('matchreport-selection') || '{}');
    if (savedSelection.matchType === 'CUSTOM' || savedSelection.matchType === 'OFFICIAL') {
      state.matchType = savedSelection.matchType;
    }
  } catch (e) { /* private mode / storage blocked — fine, use defaults */ }

  var tooltip = document.getElementById('tooltip');
  function persist() {
    try { localStorage.setItem('matchreport-selection', JSON.stringify(state)); } catch (e) { /* ignore */ }
  }

  function showTip(evt, lines) {
    tooltip.replaceChildren();
    lines.forEach(function (line, i) {
      var row = document.createElement('div');
      if (i === 0) row.className = 'tt-strong';
      row.appendChild(document.createTextNode(line));
      tooltip.appendChild(row);
    });
    tooltip.classList.add('show');
    moveTip(evt);
  }
  function moveTip(evt) {
    var x = evt.clientX + 14, y = evt.clientY + 14;
    var vw = window.innerWidth, vh = window.innerHeight;
    tooltip.style.left = Math.min(x, vw - 230) + 'px';
    tooltip.style.top = Math.min(y, vh - 70) + 'px';
  }
  function hideTip() { tooltip.classList.remove('show'); }

  function fmt(n) { return Number(n).toLocaleString('ko-KR'); }
  function fmt1(n) { return Number(n).toFixed(1); }

  /** 날짜만 있고 시:분이 없어 몇 경기를 이어서 뛰었는지 구분이 안 된다는 피드백 — 분까지 표시. */
  function fmtDateTime(d, withYear) {
    var opts = withYear ? { year: 'numeric', month: '2-digit', day: '2-digit' } : { month: '2-digit', day: '2-digit' };
    return d.toLocaleDateString('ko-KR', opts) + ' ' + d.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false });
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }

  // ---------------- 선수 카드 등급/시즌 배지 (report-seasons.js의 SEASON_META 사용) ----------------
  // spId(9자리) 앞부분(= Math.floor(spId / 1000000))이 seasonId와 정확히 일치한다(검증 내용은
  // report-seasons.js 주석 참고). DB/백엔드 변경 없이 spId만으로 카드 등급 아이콘을 붙인다.
  function seasonMetaOfSpId(spId) {
    var n = Number(spId);
    if (!n || typeof SEASON_META === 'undefined') return null;
    var meta = SEASON_META[Math.floor(n / 1000000)];
    return meta ? { name: meta[0], img: meta[1] } : null;
  }

  // spId -> 강화 단계(0~11강). loadSelection이 매번 갱신한다(유저/시즌 바뀔 때마다). 슛을 한
  // 번도 안 쏜 선수는 이 맵에 없다 — playerNameBadge는 그 경우 강화 배지를 그냥 생략한다.
  var playerGradeMap = {};

  /** 선수 이름 앞뒤에 카드 등급/시즌 아이콘 + 강화 단계 배지를 붙인 <span>을 반환한다. */
  function playerNameBadge(spId, name) {
    var wrap = el('span', 'player-name-badge');
    var meta = seasonMetaOfSpId(spId);
    if (meta) {
      var icon = document.createElement('img');
      icon.className = 'player-season-icon';
      icon.src = meta.img;
      icon.alt = meta.name;
      icon.title = meta.name;
      icon.loading = 'lazy';
      icon.width = 16;
      icon.height = 16;
      icon.addEventListener('error', function () { icon.style.display = 'none'; });
      wrap.appendChild(icon);
    }
    wrap.appendChild(document.createTextNode(name));
    var grade = playerGradeMap[spId];
    if (grade != null) {
      wrap.appendChild(el('span', 'player-grade-badge', grade + '강'));
    }
    return wrap;
  }

  // ---------------- 아주 가벼운 마크다운 → 안전한 HTML 변환 (AI 답변 렌더링용) ----------------
  // AI(Gemini) 답변은 굵게/목록 같은 마크다운 서식이 섞여 오는데, 지금까지 textContent로만
  // 꽂아 넣어서 **별표**가 그대로 문자로 보이고 스타일이 하나도 안 먹혔다. 원문은 먼저
  // HTML 이스케이프하고, 그 위에 굵게/기울임/코드/목록/문단만 화이트리스트로 태그를 입혀서
  // XSS 걱정 없이 실제로 스타일이 적용되게 만든다.
  function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }

  function inlineMarkdown(escapedText) {
    return escapedText
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/(^|[^*])\*([^*\n]+?)\*(?!\*)/g, '$1<em>$2</em>')
      .replace(/`([^`\n]+?)`/g, '<code>$1</code>');
  }

  function renderMarkdownSafe(container, raw) {
    container.replaceChildren();
    var lines = String(raw).replace(/\r\n/g, '\n').split('\n');
    var i = 0;
    var isBullet = function (l) { return /^\s*[-*]\s+/.test(l); };
    var isNumbered = function (l) { return /^\s*\d+[.)]\s+/.test(l); };
    while (i < lines.length) {
      if (!lines[i].trim()) { i++; continue; }
      if (isBullet(lines[i])) {
        var ul = document.createElement('ul');
        while (i < lines.length && isBullet(lines[i])) {
          var li = el('li');
          li.innerHTML = inlineMarkdown(escapeHtml(lines[i].replace(/^\s*[-*]\s+/, '')));
          ul.appendChild(li);
          i++;
        }
        container.appendChild(ul);
        continue;
      }
      if (isNumbered(lines[i])) {
        var ol = document.createElement('ol');
        while (i < lines.length && isNumbered(lines[i])) {
          var oli = el('li');
          oli.innerHTML = inlineMarkdown(escapeHtml(lines[i].replace(/^\s*\d+[.)]\s+/, '')));
          ol.appendChild(oli);
          i++;
        }
        container.appendChild(ol);
        continue;
      }
      var paraLines = [];
      while (i < lines.length && lines[i].trim() && !isBullet(lines[i]) && !isNumbered(lines[i])) {
        paraLines.push(lines[i]);
        i++;
      }
      var p = document.createElement('p');
      p.innerHTML = inlineMarkdown(escapeHtml(paraLines.join('\n'))).replace(/\n/g, '<br>');
      container.appendChild(p);
    }
  }
  // ---------------- API helpers ----------------
  function apiGet(path, params) {
    var url = new URL(path, BASE_URL);
    if (params) {
      Object.keys(params).forEach(function (k) {
        var v = params[k];
        if (v !== undefined && v !== null) url.searchParams.set(k, v);
      });
    }
    return fetch(url.toString()).then(function (res) {
      if (!res.ok) throw new Error('API 요청 실패 (HTTP ' + res.status + ') ' + path);
      return res.json();
    });
  }

  function apiPost(path, body) {
    var url = new URL(path, BASE_URL);
    return fetch(url.toString(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }).then(function (res) {
      if (!res.ok) throw new Error('API 요청 실패 (HTTP ' + res.status + ') ' + path);
      return res.json();
    });
  }

  // ---------------- static chrome ----------------
  var userChipRow = document.getElementById('user-select');
  var seasonChipRow = document.getElementById('season-select');
  var loadStatus = document.getElementById('load-status');
  var loadingOverlay = document.getElementById('loading-overlay');
  var loadingText = document.getElementById('loading-text');
  var mtButtons = document.querySelectorAll('#matchtype-toggle button');

  /**
   * 로딩 중엔 배경을 완전히 가리지 않고 블러 처리한 모달로 보여준다(계속 화면이 보이는 채로
   * "불러오는 중"임을 알림). 에러는 모달 대신 조용한 배너로 — 막지 않고 계속 보이면서 재시도를
   * 유도한다.
   */
  function setStatus(msg, isError) {
    if (isError) {
      loadingOverlay.hidden = true;
      loadStatus.hidden = false;
      loadStatus.textContent = msg;
      loadStatus.style.color = 'var(--status-critical)';
      return;
    }
    loadStatus.hidden = true;
    if (!msg) { loadingOverlay.hidden = true; return; }
    loadingText.textContent = msg;
    loadingOverlay.hidden = false;
  }

  /** select 대신 줄바꿈되는 칩 버튼 목록 — 유저/시즌처럼 값이 몇 개 안 되는 선택지에 더 직관적이다.
      getAvatarText가 주어지면 칩 앞에 원형 아바타(글자 1개)를 붙인다(유저 칩용). */
  function buildChips(container, items, getValue, getLabel, onSelect, getAvatarText) {
    container.replaceChildren();
    items.forEach(function (item) {
      var value = getValue(item);
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = getAvatarText ? 'chip' : 'chip chip--plain';
      if (getAvatarText) {
        var avatar = el('span', 'chip-avatar', getAvatarText(item));
        avatar.setAttribute('aria-hidden', 'true');
        btn.appendChild(avatar);
      }
      btn.appendChild(document.createTextNode(getLabel(item)));
      btn.setAttribute('aria-pressed', 'false');
      btn.dataset.value = String(value);
      btn.addEventListener('click', function () {
        if (btn.getAttribute('aria-pressed') === 'true') return;
        container.querySelectorAll('.chip').forEach(function (b) { b.setAttribute('aria-pressed', 'false'); });
        btn.setAttribute('aria-pressed', 'true');
        btn.scrollIntoView({ behavior: 'smooth', inline: 'center', block: 'nearest' });
        onSelect(value);
      });
      container.appendChild(btn);
    });
  }

  function setActiveChip(container, value) {
    container.querySelectorAll('.chip').forEach(function (b) {
      b.setAttribute('aria-pressed', String(b.dataset.value === String(value)));
    });
  }

  mtButtons.forEach(function (btn) {
    btn.addEventListener('click', function () {
      if (btn.getAttribute('data-mt') === state.matchType) return;
      state.matchType = btn.getAttribute('data-mt');
      mtButtons.forEach(function (b) { b.setAttribute('aria-pressed', String(b === btn)); });
      persist();
      loadSelection();
    });
  });

  // 플레이 성향 탭 — 공격/수비/점유율 중 하나만 보여준다. 데이터와 무관한 순수 UI 상태라
  // 한 번만 연결해두면 되고, loadSelection과 별개로 항상 동작한다.
  var playstyleTabButtons = document.querySelectorAll('#playstyle-tabs button');
  var playstylePanels = {
    attack: document.getElementById('playstyle-panel-attack'),
    defense: document.getElementById('playstyle-panel-defense'),
    pass: document.getElementById('playstyle-panel-pass'),
    dirty: document.getElementById('playstyle-panel-dirty'),
    possession: document.getElementById('playstyle-panel-possession')
  };
  playstyleTabButtons.forEach(function (btn) {
    btn.addEventListener('click', function () {
      var tab = btn.getAttribute('data-tab');
      if (btn.getAttribute('aria-pressed') === 'true') return;
      playstyleTabButtons.forEach(function (b) { b.setAttribute('aria-pressed', String(b === btn)); });
      Object.keys(playstylePanels).forEach(function (key) { playstylePanels[key].hidden = key !== tab; });
    });
  });

  // AI에게 물어보기 — 현재 선택된 유저/매치타입/시즌 기준으로 백엔드 AI 인사이트 API를 호출한다.
  var aiAskForm = document.getElementById('ai-ask-form');
  var aiQuestionInput = document.getElementById('ai-question');
  var aiAskBtn = document.getElementById('ai-ask-btn');
  var aiAnswerBox = document.getElementById('ai-answer-box');
  var aiAnswerText = document.getElementById('ai-answer-text');

  function setAiAsking(asking) {
    aiAskBtn.disabled = asking;
    aiAskBtn.textContent = asking ? '답변을 기다리는 중…' : '질문하기';
  }

  function showAiAnswer(text, isError) {
    aiAnswerBox.classList.toggle('error', !!isError);
    renderMarkdownSafe(aiAnswerText, text);
    aiAnswerBox.hidden = false;
  }

  aiAskForm.addEventListener('submit', function (e) {
    e.preventDefault();
    var question = aiQuestionInput.value.trim();
    if (!question) return;
    if (question.length > 500) {
      showAiAnswer('질문은 500자 이내로 입력해 주세요.', true);
      return;
    }
    setAiAsking(true);
    apiPost('/api/v1/insights/ask', {
      ouid: state.ouid,
      matchType: state.matchType,
      seasonId: state.seasonId,
      question: question
    }).then(function (res) {
      showAiAnswer(res.answer || '답변을 받지 못했습니다.', false);
    }).catch(function () {
      showAiAnswer('AI 답변을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.', true);
    }).finally(function () {
      setAiAsking(false);
    });
  });

  // 방문자수 배지 — 유저/시즌 선택과 무관하게 페이지 로드 시 한 번만 기록/조회한다.
  // 기록(POST)에 실패해도(백엔드 콜드 스타트 등) 조회(GET)로 폴백하고, 둘 다 실패하면 조용히
  // 숨긴다 — 방문자수 실패가 나머지 화면 표시를 막으면 안 된다.
  function loadVisitorBadge() {
    var badge = document.getElementById('visitor-badge');
    if (!badge) return;
    function show(summary) {
      badge.textContent = '오늘 ' + fmt(summary.today) + ' · 누적 ' + fmt(summary.total);
      badge.hidden = false;
    }
    apiPost('/api/v1/visitors/visits').then(show).catch(function () {
      apiGet('/api/v1/visitors/summary').then(show).catch(function () { /* 조용히 숨김 유지 */ });
    });
  }

  // ---------------- "전체" 대시보드(9명 요약) ----------------
  var matchtypeFilterGroup = document.getElementById('matchtype-filter-group');
  var seasonFilterGroup = document.getElementById('season-filter-group');
  var userReportContent = document.getElementById('user-report-content');
  var dashboardSummaryEl = document.getElementById('dashboard-summary');
  var dashboardSnapshotPromise = null; // 칩을 왔다갔다 눌러도 재요청하지 않도록 메모이즈

  /** "전체" 칩 선택 시 개인 리포트 섹션들을 숨기고 대시보드 섹션만, 나머지는 반대로. */
  function showAllMode(isAll) {
    userReportContent.hidden = isAll;
    dashboardSummaryEl.hidden = !isAll;
    matchtypeFilterGroup.hidden = isAll; // 대시보드는 항상 "모두의 커스텀" 고정 스코프라 무의미
    seasonFilterGroup.hidden = isAll;
    if (isAll) document.getElementById('page-title').textContent = '전체 유저 요약';
  }

  /** 백엔드를 전혀 거치지 않는다(raw.githubusercontent.com 정적 fetch) — 초기 진입 시 chip
      목록도 이 응답의 data.ranking(ouid+nickname)에서 뽑아 쓴다(init 참고), 그래야 첫 화면이
      백엔드 콜드 스타트와 완전히 무관해진다. 실패하면 호출부가 알아서 폴백한다. */
  function fetchDashboardSnapshot() {
    if (!dashboardSnapshotPromise) {
      dashboardSnapshotPromise = fetch(DASHBOARD_SNAPSHOT_URL, { cache: 'no-store' })
        .then(function (res) { if (!res.ok) throw new Error('HTTP ' + res.status); return res.json(); })
        .catch(function (err) {
          dashboardSnapshotPromise = null; // 실패하면 다음 시도 때 재요청할 수 있게 캐시를 비운다
          throw err;
        });
    }
    return dashboardSnapshotPromise;
  }

  function renderDashboardError(err) {
    dashboardSummaryEl.replaceChildren();
    dashboardSummaryEl.appendChild(el('p', 'card-empty',
      '대시보드 스냅샷을 아직 불러올 수 없습니다(' + err.message + '). 아직 첫 배치가 안 돌았을 수 있어요 — ' +
      '위에서 유저를 직접 선택하면 실시간 데이터를 볼 수 있어요.'));
  }

  /** "전체" 칩을 다시 누를 때(칩은 이미 만들어져 있는 상태) 쓴다 — 최초 진입 시 chip 구성까지
      같이 하는 init()과는 별개 경로. */
  function loadDashboardSummary() {
    dashboardSummaryEl.replaceChildren(el('p', 'card-caption', '대시보드를 불러오는 중…'));
    return fetchDashboardSnapshot().then(renderDashboardSummary).catch(renderDashboardError);
  }

  function renderDashboardSummary(data) {
    dashboardSummaryEl.replaceChildren();

    var d = new Date(data.generatedAt);
    var updatedLine = el('p', 'card-caption', '업데이트: ' + d.toLocaleString('ko-KR', {
      year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'
    }) + ' · ' + (data.currentSeasonName || '') + ' 커스텀 매치 기준');
    dashboardSummaryEl.appendChild(updatedLine);

    if (data.introText) {
      var introBox = el('div', 'card');
      introBox.style.marginBottom = '14px';
      data.introText.split('\n').forEach(function (line) {
        if (line) introBox.appendChild(el('p', 'card-caption', line));
      });
      dashboardSummaryEl.appendChild(introBox);
    }

    if (data.aiRankingFailed && data.aiRankingNote) {
      var noteBox = el('div', 'card');
      noteBox.style.marginBottom = '14px';
      noteBox.appendChild(el('p', 'card-title', '⚠️ AI 랭킹 안내'));
      noteBox.appendChild(el('p', 'card-caption', data.aiRankingNote));
      dashboardSummaryEl.appendChild(noteBox);
    }

    dashboardSummaryEl.appendChild(buildDashboardTable(data));

    if (data.outroText) {
      var outroBox = el('div', 'card');
      outroBox.style.marginTop = '14px';
      data.outroText.split('\n').forEach(function (line) {
        if (line) outroBox.appendChild(el('p', 'card-caption', line));
      });
      dashboardSummaryEl.appendChild(outroBox);
    }

    // 최근 경기 10건은 별도 API 호출(loadRecentActivityFeed)로 채워진다 — 여기선 자리만 만든다.
    var recentActivityBox = el('div', 'card dashboard-recent-activity');
    recentActivityBox.id = 'dashboard-recent-activity';
    recentActivityBox.style.marginTop = '14px';
    recentActivityBox.hidden = true; // loadRecentActivityFeed가 다 모아지면 채우고 보여준다
    dashboardSummaryEl.appendChild(recentActivityBox);
  }

  // 순위표 열 정의 — [헤더라벨, s에서 값을 뽑는 함수]. 득점/득점xG/실점/실점xG는 제목만 짧게
  // 줄이고 값은 상세와 동일한 "경기당 평균"을 그대로 쓴다(요청) — 시즌 합계가 아니다.
  var DASHBOARD_TABLE_COLS = [
    { label: '경기', get: function (s) { return fmt(s.games); } },
    { label: '승', get: function (s) { return fmt(s.wins); } },
    { label: '무', get: function (s) { return fmt(s.draws); } },
    { label: '패', get: function (s) { return fmt(s.losses); } },
    { label: '득점', get: function (s) { return fmt1(s.avgGoalsFor); } },
    { label: '득점xG', get: function (s) { return fmt1(s.avgGoalsForXg); } },
    { label: '결정력', get: function (s) { return (s.finishing >= 0 ? '+' : '') + fmt1(s.finishing); } },
    { label: '실점', get: function (s) { return fmt1(s.avgGoalsAgainst); } },
    { label: '실점xG', get: function (s) { return s.avgGoalsAgainstXg == null ? '-' : fmt1(s.avgGoalsAgainstXg); } },
    { label: '클린시트', get: function (s) { return fmt(s.cleanSheets); } }
  ];

  /**
   * 프리미어리그 순위표 스타일 — 유저 한 명이 한 행. 행을 클릭하면 아코디언으로 펼쳐져서
   * 기존 카드에 있던 상세 스탯(12칸 그리드 + 환상의 콤비 + TOP3)이 그 아래 줄에 나온다.
   * 좁은 화면에서는 가로 스크롤 대신 각 행이 라벨:값 칩으로 줄바꿈되는 카드형으로 바뀐다
   * (report.css의 @media (max-width: 760px) 참고).
   */
  function buildDashboardTable(data) {
    var wrap = el('div', 'dashboard-table-wrap');
    wrap.appendChild(el('p', 'card-caption', '행을 클릭하면 상세 데이터가 펼쳐집니다.'));

    var table = document.createElement('table');
    table.className = 'dashboard-table';
    var thead = document.createElement('thead');
    var htr = document.createElement('tr');
    htr.appendChild(el('th', 'dashboard-rank-col', '순위'));
    htr.appendChild(el('th', '', '유저'));
    DASHBOARD_TABLE_COLS.forEach(function (c) { htr.appendChild(el('th', 'num', c.label)); });
    thead.appendChild(htr);
    table.appendChild(thead);

    var colCount = DASHBOARD_TABLE_COLS.length + 2;
    var tbody = document.createElement('tbody');
    if (!data.ranking || !data.ranking.length) {
      var emptyTr = document.createElement('tr');
      var emptyTd = document.createElement('td');
      emptyTd.colSpan = colCount;
      emptyTd.appendChild(el('p', 'card-empty', '표시할 유저가 없습니다.'));
      emptyTr.appendChild(emptyTd);
      tbody.appendChild(emptyTr);
    } else {
      data.ranking.forEach(function (entry, i) {
        var snapshot = data.users[entry.ouid];
        var rows = buildDashboardTableRow(entry, snapshot && snapshot.summary, colCount, i);
        tbody.appendChild(rows.mainRow);
        tbody.appendChild(rows.detailRow);
      });
    }
    table.appendChild(tbody);
    wrap.appendChild(table);
    return wrap;
  }

  function buildDashboardTableRow(entry, s, colCount, index) {
    var hasData = !!(s && s.games > 0);

    var tr = document.createElement('tr');
    tr.className = 'dashboard-row' + (index % 2 === 1 ? ' dashboard-row-alt' : '');
    tr.tabIndex = 0;
    tr.setAttribute('role', 'button');
    tr.setAttribute('aria-expanded', 'false');
    tr.setAttribute('aria-label', (entry.displayName || entry.nickname) + ' 상세 펼치기');

    var rankTd = el('td', 'num dashboard-rank-cell', entry.rank + '위');
    if (entry.rank <= 3) rankTd.classList.add('dashboard-rank-top' + entry.rank);
    tr.appendChild(rankTd);

    var nameTd = el('td', 'name-cell dashboard-row-name-cell');
    var caret = el('span', 'expand-caret', '▸');
    nameTd.appendChild(caret);
    nameTd.appendChild(el('span', 'dashboard-row-name', entry.displayName || entry.nickname));
    tr.appendChild(nameTd);

    DASHBOARD_TABLE_COLS.forEach(function (c) {
      var td = el('td', 'num', hasData ? c.get(s) : '-');
      td.setAttribute('data-label', c.label);
      tr.appendChild(td);
    });

    var detailTr = document.createElement('tr');
    detailTr.className = 'dashboard-detail-row' + (index % 2 === 1 ? ' dashboard-row-alt' : '');
    detailTr.hidden = true;
    var detailTd = document.createElement('td');
    detailTd.colSpan = colCount;
    var inner = el('div', 'dashboard-detail-inner');
    if (hasData) {
      renderDashboardDetailBody(inner, s, entry.reason);
    } else {
      inner.appendChild(el('p', 'card-empty', '표본 경기가 없습니다.'));
    }
    detailTd.appendChild(inner);
    detailTr.appendChild(detailTd);

    var expanded = false;
    var toggle = function () {
      expanded = !expanded;
      tr.setAttribute('aria-expanded', String(expanded));
      caret.textContent = expanded ? '▾' : '▸';
      detailTr.hidden = !expanded;
    };
    tr.addEventListener('click', toggle);
    tr.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); } });

    return { mainRow: tr, detailRow: detailTr };
  }

  /** 아코디언 안쪽 — 예전 카드 본문 그대로(경기당 평균 12칸 그리드 + 환상의 콤비 + TOP3). */
  function renderDashboardDetailBody(container, s, reason) {
    if (reason) container.appendChild(el('p', 'dashboard-detail-reason', reason));
    container.appendChild(el('p', 'card-caption',
      s.games + '전 ' + s.wins + '승 ' + s.draws + '무 ' + s.losses + '패'));

    var grid = el('div', 'stat-mini-grid');
    function mini(label, value, sub) {
      var box = el('div', 'stat-mini');
      box.appendChild(el('p', 'stat-mini-label', label));
      box.appendChild(el('div', 'stat-mini-value', value));
      if (sub) box.appendChild(el('div', 'stat-mini-sub', sub));
      grid.appendChild(box);
    }
    mini('평균득점', fmt1(s.avgGoalsFor));
    mini('평균득점 xG값', fmt1(s.avgGoalsForXg));
    mini('결정력', (s.finishing >= 0 ? '+' : '') + fmt1(s.finishing), '실제 득점 − xG값');
    mini('경기당 슈팅', fmt1(s.shotsPerGame));
    mini('평균실점', fmt1(s.avgGoalsAgainst));
    mini('평균실점 xG값', s.avgGoalsAgainstXg == null ? '-' : fmt1(s.avgGoalsAgainstXg),
      s.concededSampleGames ? s.concededSampleGames + '경기 표본' : '데이터 없음');
    mini('클린시트', s.cleanSheets + '경기', Math.round(s.cleanSheetPct) + '%');
    mini('다실점(3실점↑)', s.multiConcededGames + '경기', Math.round(s.multiConcededPct) + '%');
    mini('저점유(45%↓)', s.lowPossessionGames + '경기', Math.round(s.lowPossessionPct) + '%');
    mini('고점유(55%↑)', s.highPossessionGames + '경기', Math.round(s.highPossessionPct) + '%');
    mini('균형점유(46~54%)', s.balancedPossessionGames + '경기', Math.round(s.balancedPossessionPct) + '%');
    mini('평균점유율', Math.round(s.avgPossession) + '%');
    // 이 그리드는 나머지 전부 "경기당 평균"이라 패스만 총합으로 튀어 보였다(요청) — totalPassTry/
    // totalPassSuccess(표본 전체 합계)를 games로 나눠 다른 칸들과 같은 평균 기준으로 맞춘다.
    mini('평균 패스시도', s.totalPassTry != null ? fmt1(s.totalPassTry / s.games) : '-');
    mini('평균 패스성공', s.totalPassSuccess != null ? fmt1(s.totalPassSuccess / s.games) : '-');
    mini('패스 성공률', (s.totalPassTry) ? Math.round(s.totalPassSuccess / s.totalPassTry * 100) + '%' : '-');
    container.appendChild(grid);

    var comboBox = el('div', 'dashboard-combo');
    comboBox.appendChild(el('p', 'card-title', '⚡ 환상의 콤비'));
    if (s.combo) {
      comboBox.appendChild(el('p', 'card-caption',
        s.combo.playerAName + ' ↔ ' + s.combo.playerBName + ' — 합산 ' + s.combo.goals + '골'));
    } else {
      comboBox.appendChild(el('p', 'card-empty', '기록된 어시스트 조합이 없습니다.'));
    }
    container.appendChild(comboBox);

    var top3Wrap = el('div', 'dashboard-top3-wrap');
    top3Wrap.appendChild(dashboardTop3Block('⚽ 최다골', s.topGoals));
    top3Wrap.appendChild(dashboardTop3Block('🅰️ 최다도움', s.topAssists));
    top3Wrap.appendChild(dashboardTop3Block('🔥 최다 공격포인트', s.topAttackPoints));
    top3Wrap.appendChild(dashboardTop3Block('🛡️ 최다 태클+인터셉트', s.topDefense));
    top3Wrap.appendChild(dashboardTop3Block('🧤 최다 선방', s.topSaves));
    container.appendChild(top3Wrap);
  }

  function dashboardTop3Block(title, list) {
    var box = el('div', 'dashboard-top3-block');
    box.appendChild(el('p', 'card-title', title));
    if (!list || !list.length) {
      box.appendChild(el('p', 'card-empty', '기록 없음'));
      return box;
    }
    var ol = document.createElement('ol');
    ol.className = 'dashboard-top3-list';
    list.forEach(function (p) {
      var li = document.createElement('li');
      li.appendChild(el('span', 'dashboard-top3-name', p.playerName));
      li.appendChild(el('span', 'dashboard-top3-value', String(p.value)));
      ol.appendChild(li);
    });
    box.appendChild(ol);
    return box;
  }

  var allUsers = [];
  var allSeasons = [];
  // "전체" 화면에선 allUsers가 비어있을 수 있다(백엔드를 안 거쳐서) — 대시보드 스냅샷의
  // data.ranking에서 9명 ouid를 미리 채워둬서, 대시보드 최근 경기 피드에서 모달을 열 때도
  // "상대가 추적 대상인지" 판정(MOM/Worst 양팀 합산·상대 팀 비교에 필요)이 정확히 되게 한다.
  var dashboardTrackedOuids = {};
  function isTrackedOuid(ouid) {
    return allUsers.some(function (u) { return u.ouid === ouid; }) || !!dashboardTrackedOuids[ouid];
  }

  /**
   * /api/v1/users, /api/v1/seasons(둘 다 백엔드 실시간 호출)는 실제 유저 칩을 처음 클릭하는
   * 순간에만 불러온다 — 기본 화면("전체")은 이게 전혀 필요 없어서, 여기서 당기면 콜드 스타트를
   * 그대로 다시 겪는다(대시보드를 만든 이유가 무색해짐). 시즌 칩 구성/기본 시즌 계산도 여기서
   * 같이 한다(예전엔 init()이 다 했음).
   */
  var liveDataPromise = null;
  function ensureLiveData() {
    if (!liveDataPromise) {
      setStatus('유저/시즌 목록을 불러오는 중입니다… 백엔드가 잠들어 있으면 첫 로딩에 최대 1분 정도 걸릴 수 있어요.');
      liveDataPromise = Promise.all([apiGet('/api/v1/users'), apiGet('/api/v1/seasons')])
        .then(function (results) {
          allUsers = results[0];
          allSeasons = results[1];
          setStatus(null);

          var sortedSeasons = allSeasons.slice().sort(function (a, b) { return b.id - a.id; });
          buildChips(seasonChipRow, sortedSeasons,
            function (s) { return s.id; },
            function (s) { return s.name; },
            function (value) {
              state.seasonId = Number(value);
              persist();
              loadSelection();
            });

          var seasonMatch = allSeasons.filter(function (s) { return String(s.id) === String(savedSelection.seasonId); })[0];
          var currentSeason = allSeasons.filter(function (s) { return s.current; })[0];
          state.seasonId = seasonMatch ? seasonMatch.id : (currentSeason ? currentSeason.id : (allSeasons[0] ? allSeasons[0].id : null));
          setActiveChip(seasonChipRow, state.seasonId);
          mtButtons.forEach(function (b) { b.setAttribute('aria-pressed', String(b.getAttribute('data-mt') === state.matchType)); });
        })
        .catch(function (err) {
          liveDataPromise = null;
          setStatus('유저/시즌 목록을 불러오지 못했습니다 — 백엔드가 응답하지 않습니다. 새로고침해서 다시 시도해 주세요. (' + err.message + ')', true);
          throw err;
        });
    }
    return liveDataPromise;
  }

  function selectUser(value) {
    state.ouid = value;
    persist();
    if (value === ALL_OUID) {
      showAllMode(true);
      loadDashboardSummary();
      return;
    }
    showAllMode(false);
    ensureLiveData().then(function () {
      loadSelection();
    }).catch(function () { /* ensureLiveData가 이미 에러 상태를 보여줬다 */ });
  }

  function buildUserChips(items) {
    buildChips(userChipRow, [{ ouid: ALL_OUID, nickname: '전체' }].concat(items),
      function (u) { return u.ouid; },
      function (u) { return u.nickname; },
      selectUser,
      function (u) { return u.nickname.charAt(0); });
    setActiveChip(userChipRow, state.ouid);
  }

  function byDisplayOrder(users) {
    return users.slice().sort(function (a, b) { return a.displayOrder - b.displayOrder; });
  }

  /**
   * 기본 화면은 "전체"(대시보드) — 유저 칩 목록조차 백엔드가 아니라 대시보드 스냅샷의
   * data.ranking(ouid+nickname)에서 뽑는다. 스냅샷 자체가 없으면(첫 배치 전 등) 그때만
   * ensureLiveData()로 폴백해 칩을 채운다 — 그래도 첫 화면이 완전히 안 뜨는 것보단 낫다.
   */
  /**
   * "전체" 기본 화면은 백엔드를 안 거치지만(대시보드 스냅샷만 읽음), 그래서 백엔드가 잠들어
   * 있으면 나중에 실제 유저 칩을 눌렀을 때 콜드 스타트를 그대로 겪는다. 그냥 빈 핑을 보내
   * 깨우기만 하던 것 대신, 9명 전체 기준 최근 경기 10건을 모아 대시보드 아래에 보여주는
   * 용도로 바꿨다(요청) — 어차피 백엔드를 호출하니 결과를 버리지 않고 화면에 쓴다.
   * 유저 목록은 백엔드가 아니라 스냅샷의 data.ranking에서 온다(init 참고).
   */
  function loadRecentActivityFeed(users) {
    var container = document.getElementById('dashboard-recent-activity');
    if (!container) return;
    var requests = users.map(function (u) {
      return apiGet('/api/v1/records/recent-matches', { ouid: u.ouid, matchType: 'CUSTOM', page: 0, size: 10 })
        .then(function (page) {
          return (page.content || []).map(function (m) {
            m.__ouid = u.ouid;
            m.__nickname = u.nickname;
            return m;
          });
        })
        .catch(function () { return []; });
    });
    Promise.all(requests).then(function (results) {
      var all = [].concat.apply([], results);
      // 같은 매치가 양쪽 트래킹 유저 관점에서 두 번 잡힐 수 있어(둘 다 서로의 상대로 추적 중이면)
      // matchId로 한 번만 남긴다.
      var seen = {};
      var deduped = all.filter(function (m) {
        if (seen[m.matchId]) return false;
        seen[m.matchId] = true;
        return true;
      });
      deduped.sort(function (a, b) { return new Date(b.matchDate) - new Date(a.matchDate); });
      renderDashboardRecentActivity(container, deduped.slice(0, 10));
    }).catch(function () {
      container.replaceChildren();
    });
  }

  function renderDashboardRecentActivity(container, matches) {
    container.replaceChildren();
    container.hidden = !matches.length;
    if (!matches.length) return;
    container.appendChild(el('p', 'card-title', '🕐 최근 경기 (전체 9명)'));
    container.appendChild(el('p', 'card-caption', '9명 전체 기준 가장 최근 커스텀 매치 10건 — 클릭하면 상세 정보가 열립니다.'));
    var wrap = el('div', 'table-scroll');
    var table = document.createElement('table');
    var thead = document.createElement('thead');
    var htr = document.createElement('tr');
    ['날짜', '대진', '결과', '스코어'].forEach(function (h, i) {
      htr.appendChild(el('th', i === 3 ? 'num' : '', h));
    });
    thead.appendChild(htr);
    table.appendChild(thead);
    var tbody = document.createElement('tbody');
    matches.forEach(function (m) {
      var tr = document.createElement('tr');
      tr.className = 'match-row';
      tr.tabIndex = 0;
      tr.setAttribute('role', 'button');
      tr.setAttribute('aria-label', m.__nickname + ' vs ' + m.opponentNickname + ' 경기 상세 보기');
      var d = new Date(m.matchDate);
      tr.appendChild(el('td', '', fmtDateTime(d, false)));
      tr.appendChild(el('td', 'name-cell', m.__nickname + ' vs ' + m.opponentNickname));
      var resTd = document.createElement('td');
      resTd.appendChild(el('span', 'chip result-' + m.result, m.result));
      tr.appendChild(resTd);
      tr.appendChild(el('td', 'num', m.goalsFor + ' : ' + m.goalsAgainst));
      var openFn = function () {
        // 이 매치의 주인 관점으로 모달을 열어야 match-shots/match-squad 등이 맞게 조회된다.
        state.ouid = m.__ouid;
        state.matchType = 'CUSTOM';
        openMatchModal(m);
      };
      tr.addEventListener('click', openFn);
      tr.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openFn(); } });
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    wrap.appendChild(table);
    container.appendChild(wrap);
  }

  function init() {
    state.ouid = ALL_OUID;
    showAllMode(true);
    setStatus(null);
    dashboardSummaryEl.replaceChildren(el('p', 'card-caption', '대시보드를 불러오는 중…'));

    fetchDashboardSnapshot().then(function (data) {
      renderDashboardSummary(data);
      var items = (data.ranking || []).map(function (r) { return { ouid: r.ouid, nickname: r.nickname }; });
      items.forEach(function (u) { dashboardTrackedOuids[u.ouid] = true; });
      if (items.length) {
        buildUserChips(items);
        loadRecentActivityFeed(items);
      } else {
        ensureLiveData().then(function () { buildUserChips(byDisplayOrder(allUsers)); }).catch(function () {});
      }
    }).catch(function (err) {
      renderDashboardError(err);
      ensureLiveData().then(function () { buildUserChips(byDisplayOrder(allUsers)); }).catch(function () {});
    });
  }

  function barChart(container, rows, opts) {
    // rows: [{label, value, color, sub?, spId?}] — sub가 있으면 라벨 아래에 작은 보조 정보를
    // 한 줄 더 보여준다. spId가 있으면 라벨 앞에 카드 등급/시즌 아이콘을 붙인다.
    container.replaceChildren();
    if (!rows.length) { container.appendChild(el('p', 'card-empty', '표시할 데이터가 없습니다.')); return; }
    var max = Math.max.apply(null, rows.map(function (r) { return r.value; }), 1);
    rows.forEach(function (r) {
      var row = el('div', 'bar-row');
      if (r.sub) {
        var cat = el('div', 'bar-cat stacked');
        cat.appendChild(r.spId ? playerNameBadge(r.spId, r.label) : document.createTextNode(r.label));
        cat.appendChild(el('span', 'bar-cat-sub', r.sub));
        row.appendChild(cat);
      } else {
        var catPlain = el('div', 'bar-cat');
        catPlain.appendChild(r.spId ? playerNameBadge(r.spId, r.label) : document.createTextNode(r.label));
        row.appendChild(catPlain);
      }
      var track = el('div', 'bar-track');
      var fillPct = Math.max((r.value / max) * 100, r.value > 0 ? 2 : 0);
      var fill = el('div', 'bar-fill');
      fill.style.width = fillPct + '%';
      fill.style.background = r.color;
      fill.tabIndex = 0;
      fill.setAttribute('role', 'img');
      fill.setAttribute('aria-label', r.label + (r.sub ? ' (' + r.sub + ')' : '') + ' ' + r.value);
      var showFn = function (evt) {
        var lines = [r.label];
        if (r.sub) lines.push(r.sub);
        lines.push(opts && opts.unit ? r.value + opts.unit : String(r.value));
        showTip(evt, lines);
      };
      fill.addEventListener('pointerenter', showFn);
      fill.addEventListener('pointermove', moveTip);
      fill.addEventListener('pointerleave', hideTip);
      fill.addEventListener('focus', showFn);
      fill.addEventListener('blur', hideTip);
      track.appendChild(fill);
      row.appendChild(track);
      row.appendChild(el('div', 'bar-value', fmt(r.value)));
      container.appendChild(row);
    });
  }
  function verticalBarChart(container, rows) {
    container.replaceChildren();
    if (!rows.length || rows.every(function (r) { return r.value === 0; })) {
      container.appendChild(el('p', 'card-empty', '표시할 득점 데이터가 없습니다.'));
      return;
    }
    var wrap = el('div', 'vbars');
    var max = Math.max.apply(null, rows.map(function (r) { return r.value; }), 1);
    rows.forEach(function (r) {
      var col = el('div', 'vbar-col');
      col.appendChild(el('div', 'vbar-value', r.value > 0 ? fmt(r.value) : ''));
      var barBox = el('div', '');
      barBox.style.width = '100%';
      barBox.style.display = 'flex';
      barBox.style.justifyContent = 'center';
      var fill = el('div', 'vbar-fill');
      var h = Math.max((r.value / max) * 96, r.value > 0 ? 4 : 0);
      fill.style.height = h + 'px';
      fill.style.background = 'var(--series-1)';
      fill.tabIndex = 0;
      var showFn = function (evt) { showTip(evt, [r.label + '분', r.value + '골']); };
      fill.addEventListener('pointerenter', showFn);
      fill.addEventListener('pointermove', moveTip);
      fill.addEventListener('pointerleave', hideTip);
      fill.addEventListener('focus', showFn);
      fill.addEventListener('blur', hideTip);
      barBox.appendChild(fill);
      col.appendChild(barBox);
      col.appendChild(el('div', 'vbar-label', r.label));
      wrap.appendChild(col);
    });
    container.appendChild(wrap);
  }

  /**
   * verticalBarChart의 대칭(득점/실점 대비) 버전 — 같은 시간대 버킷 축을 위/아래로 공유한다.
   * upRows(득점, 파란색)는 기준선 위로, downRows(실점, 빨간색)는 기준선 아래로 자란다.
   * 두 배열은 백엔드에서 같은 버킷 로직(bucketLabelFor)으로 만들어져 라벨 순서가 항상 같다고
   * 가정한다. 눈으로 크기를 바로 비교할 수 있게 위/아래 막대는 같은 max(공통 최댓값) 기준으로 그린다.
   */
  function divergingBarChart(container, upRows, downRows) {
    container.replaceChildren();
    var hasUp = upRows.some(function (r) { return r.value > 0; });
    var hasDown = downRows.some(function (r) { return r.value > 0; });
    if (!upRows.length || (!hasUp && !hasDown)) {
      container.appendChild(el('p', 'card-empty', '표시할 득점/실점 데이터가 없습니다.'));
      return;
    }
    var wrap = el('div', 'vbars-diverging');
    var allValues = upRows.map(function (r) { return r.value; }).concat(downRows.map(function (r) { return r.value; }));
    var max = Math.max.apply(null, allValues.concat([1]));
    upRows.forEach(function (r, i) {
      var d = downRows[i] || { label: r.label, value: 0 };
      var col = el('div', 'vbar-col-diverging');

      var upBox = el('div', 'vbar-half vbar-half-up');
      var upValue = el('div', 'vbar-value', r.value > 0 ? fmt(r.value) : '');
      var upFill = el('div', 'vbar-fill vbar-fill-up');
      var upH = Math.max((r.value / max) * 72, r.value > 0 ? 4 : 0);
      upFill.style.height = upH + 'px';
      upFill.tabIndex = 0;
      var showUp = function (evt) { showTip(evt, [r.label + '분', r.value + '득점']); };
      upFill.addEventListener('pointerenter', showUp);
      upFill.addEventListener('pointermove', moveTip);
      upFill.addEventListener('pointerleave', hideTip);
      upFill.addEventListener('focus', showUp);
      upFill.addEventListener('blur', hideTip);
      upBox.appendChild(upValue);
      upBox.appendChild(upFill);

      var downBox = el('div', 'vbar-half vbar-half-down');
      var downFill = el('div', 'vbar-fill vbar-fill-conceded');
      var downH = Math.max((d.value / max) * 72, d.value > 0 ? 4 : 0);
      downFill.style.height = downH + 'px';
      downFill.tabIndex = 0;
      var showDown = function (evt) { showTip(evt, [d.label + '분', d.value + '실점']); };
      downFill.addEventListener('pointerenter', showDown);
      downFill.addEventListener('pointermove', moveTip);
      downFill.addEventListener('pointerleave', hideTip);
      downFill.addEventListener('focus', showDown);
      downFill.addEventListener('blur', hideTip);
      var downValue = el('div', 'vbar-value', d.value > 0 ? fmt(d.value) : '');
      downBox.appendChild(downFill);
      downBox.appendChild(downValue);

      col.appendChild(upBox);
      col.appendChild(el('div', 'vbar-label', r.label));
      col.appendChild(downBox);
      wrap.appendChild(col);
    });
    container.appendChild(wrap);
    var legend = el('div', 'legend');
    legend.style.marginTop = '4px';
    legend.style.justifyContent = 'center';
    function legendItem(label, color) {
      var item = el('div', 'legend-item');
      var sw = el('span', 'legend-swatch');
      sw.style.background = color;
      item.appendChild(sw);
      item.appendChild(document.createTextNode(label));
      legend.appendChild(item);
    }
    legendItem('득점', 'var(--series-1)');
    legendItem('실점', 'var(--status-critical)');
    container.appendChild(legend);
  }

  /**
   * 최근 경기 추이용 꺾은선 그래프. seriesList: [{label, color, values:[...]}] — 전부 같은 길이,
   * opts.labels[i]가 각 포인트의 x축 라벨(날짜 등). 단일/복수 시리즈 둘 다 지원(복수면 범례 표시).
   */
  function lineChart(container, seriesList, opts) {
    container.replaceChildren();
    var labels = (opts && opts.labels) || [];
    var hasData = seriesList.some(function (s) { return s.values.length > 0; });
    if (!hasData || !labels.length) {
      container.appendChild(el('p', 'card-empty', '표시할 경기가 없습니다.'));
      return;
    }

    var W = 480, H = 170, padL = 30, padR = 12, padT = 16, padB = 22;
    var n = labels.length;
    var allValues = [];
    seriesList.forEach(function (s) { allValues = allValues.concat(s.values); });
    var maxV = opts.yMax != null ? opts.yMax : Math.max.apply(null, allValues.concat([1]));
    var minV = opts.yMin != null ? opts.yMin : Math.min(0, Math.min.apply(null, allValues));
    if (maxV === minV) maxV = minV + 1;
    var innerW = W - padL - padR, innerH = H - padT - padB;

    function xAt(i) { return n <= 1 ? padL + innerW / 2 : padL + (innerW * i) / (n - 1); }
    function yAt(v) { return padT + innerH - ((v - minV) / (maxV - minV)) * innerH; }

    var svgNS = 'http://www.w3.org/2000/svg';
    var svg = document.createElementNS(svgNS, 'svg');
    svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
    svg.setAttribute('class', 'linechart');
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', (opts.ariaLabel || '추이 그래프') + ', ' + n + '경기');

    // y축 — 눈금 4단계(최솟값~최댓값)를 가로 그리드선 + 왼쪽 숫자 라벨로 표시.
    var Y_TICKS = 4;
    for (var t = 0; t <= Y_TICKS; t++) {
      var tickValue = minV + (maxV - minV) * (t / Y_TICKS);
      var tickY = yAt(tickValue);
      var gridLine = document.createElementNS(svgNS, 'line');
      gridLine.setAttribute('x1', padL); gridLine.setAttribute('x2', W - padR);
      gridLine.setAttribute('y1', tickY); gridLine.setAttribute('y2', tickY);
      gridLine.setAttribute('class', 'linechart-axis');
      gridLine.setAttribute('opacity', t === 0 ? '1' : '0.4');
      svg.appendChild(gridLine);

      var tickLabel = document.createElementNS(svgNS, 'text');
      tickLabel.setAttribute('x', padL - 5);
      tickLabel.setAttribute('y', tickY + 3);
      tickLabel.setAttribute('text-anchor', 'end');
      tickLabel.setAttribute('class', 'linechart-axis-label');
      tickLabel.textContent = String(Math.round(tickValue));
      svg.appendChild(tickLabel);
    }
    if (opts.refLines) {
      opts.refLines.forEach(function (rv) {
        var rl = document.createElementNS(svgNS, 'line');
        rl.setAttribute('x1', padL); rl.setAttribute('x2', W - padR);
        rl.setAttribute('y1', yAt(rv)); rl.setAttribute('y2', yAt(rv));
        rl.setAttribute('class', 'linechart-axis');
        rl.setAttribute('stroke-dasharray', '3,3');
        svg.appendChild(rl);
      });
    }

    // x축 라벨 — 너무 촘촘하면 일부만(최대 6개)
    var xLabelStep = Math.max(1, Math.ceil(n / 6));
    labels.forEach(function (lab, i) {
      if (i % xLabelStep !== 0 && i !== n - 1) return;
      var t = document.createElementNS(svgNS, 'text');
      t.setAttribute('x', xAt(i));
      t.setAttribute('y', H - 6);
      t.setAttribute('text-anchor', 'middle');
      t.setAttribute('class', 'linechart-axis-label');
      t.textContent = lab;
      svg.appendChild(t);
    });

    // opts.activeLabel이 있으면(바이오리듬처럼 "한 축만 선택해서 보기") 그 라벨만 진하게,
    // 나머지는 흐리게 그린다 — 없으면(기존 모든 호출부) 지금까지처럼 전부 동일하게 그린다.
    function isDimmed(s) { return opts.activeLabel != null && s.label !== opts.activeLabel; }

    seriesList.forEach(function (s) {
      var dimmed = isDimmed(s);
      var pts = s.values.map(function (v, i) { return xAt(i) + ',' + yAt(v); }).join(' ');
      var poly = document.createElementNS(svgNS, 'polyline');
      poly.setAttribute('points', pts);
      poly.setAttribute('fill', 'none');
      poly.setAttribute('stroke', s.color);
      poly.setAttribute('stroke-width', dimmed ? '1.5' : '2.5');
      poly.setAttribute('stroke-linejoin', 'round');
      poly.setAttribute('stroke-linecap', 'round');
      poly.setAttribute('opacity', dimmed ? '0.25' : '1');
      svg.appendChild(poly);

      s.values.forEach(function (v, i) {
        var c = document.createElementNS(svgNS, 'circle');
        c.setAttribute('cx', xAt(i));
        c.setAttribute('cy', yAt(v));
        c.setAttribute('r', dimmed ? '2' : '3');
        c.setAttribute('opacity', dimmed ? '0.25' : '1');
        c.setAttribute('fill', s.color);
        c.setAttribute('class', 'linechart-dot');
        c.tabIndex = 0;
        var showFn = function (evt) {
          showTip(evt, [labels[i], s.label + ': ' + v + (opts.unit || '')]);
        };
        c.addEventListener('pointerenter', showFn);
        c.addEventListener('pointermove', moveTip);
        c.addEventListener('pointerleave', hideTip);
        c.addEventListener('focus', showFn);
        c.addEventListener('blur', hideTip);
        svg.appendChild(c);
      });
    });

    container.appendChild(svg);

    if (seriesList.length > 1) {
      var legend = el('div', 'legend');
      legend.style.marginTop = '8px';
      legend.style.justifyContent = 'center';
      seriesList.forEach(function (s) {
        var clickable = typeof opts.onLegendClick === 'function';
        var item = el('div', 'legend-item' + (clickable ? ' legend-item-clickable' : ''));
        if (isDimmed(s)) item.classList.add('legend-item-dimmed');
        var sw = el('span', 'legend-swatch');
        sw.style.background = s.color;
        sw.style.borderRadius = '50%';
        item.appendChild(sw);
        item.appendChild(document.createTextNode(s.label));
        if (clickable) {
          item.tabIndex = 0;
          item.setAttribute('role', 'button');
          item.addEventListener('click', function () { opts.onLegendClick(s.label); });
          item.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); opts.onLegendClick(s.label); }
          });
        }
        if (opts.legendTooltips && opts.legendTooltips[s.label]) {
          var lines = opts.legendTooltips[s.label];
          var showFn = function (evt) { showTip(evt, lines); };
          item.addEventListener('pointerenter', showFn);
          item.addEventListener('pointermove', moveTip);
          item.addEventListener('pointerleave', hideTip);
          item.addEventListener('focus', showFn);
          item.addEventListener('blur', hideTip);
        }
        legend.appendChild(item);
      });
      container.appendChild(legend);
    }
  }

  var RESULT_KO = { GOAL: '골', ON_TARGET: '온타겟', OFF_TARGET: '오프타겟' };

  // 백엔드 MatchDomainService.PERIOD_OFFSET_MINUTES와 동일 — period(1~5)별 절대 분 환산.
  // goalTimeMinutes는 "그 period 시작 기준 경과분"이라, 매치 상세 모달에 "실제 몇 분"으로
  // 보여주려면 이 오프셋을 더해야 한다(안 더하면 후반/연장 골이 전부 0~45분대로 보임).
  var PERIOD_OFFSET_MINUTES = [0, 0, 45, 90, 105, 120];
  function absoluteMinuteOf(minutes, period) {
    if (minutes == null) return null;
    var offset = (period != null && period >= 1 && period < PERIOD_OFFSET_MINUTES.length)
      ? PERIOD_OFFSET_MINUTES[period] : 0;
    return minutes + offset;
  }
  var PERIOD_KO = { 1: '전반', 2: '후반', 3: '연장 전반', 4: '연장 후반', 5: '승부차기' };

  var PITCH_NS = 'http://www.w3.org/2000/svg';
  var PITCH_W = 400, PITCH_H = 260;

  /** 피치 윤곽(외곽선/하프라인/센터서클/페널티박스×2)만 그려서 svg에 붙인다 — 여러 피치 뷰가 공용. */
  function drawPitchOutline(svg) {
    function line(x1, y1, x2, y2) {
      var l = document.createElementNS(PITCH_NS, 'line');
      l.setAttribute('x1', x1); l.setAttribute('y1', y1);
      l.setAttribute('x2', x2); l.setAttribute('y2', y2);
      l.setAttribute('class', 'pitch-line');
      svg.appendChild(l);
    }
    function rect(x, y, w, h) {
      var r = document.createElementNS(PITCH_NS, 'rect');
      r.setAttribute('x', x); r.setAttribute('y', y);
      r.setAttribute('width', w); r.setAttribute('height', h);
      r.setAttribute('class', 'pitch-line');
      svg.appendChild(r);
    }
    function circle(cx, cy, rad) {
      var c = document.createElementNS(PITCH_NS, 'circle');
      c.setAttribute('cx', cx); c.setAttribute('cy', cy); c.setAttribute('r', rad);
      c.setAttribute('class', 'pitch-line');
      svg.appendChild(c);
    }
    var W = PITCH_W, H = PITCH_H;
    rect(2, 2, W - 4, H - 4);
    line(W / 2, 2, W / 2, H - 2);
    circle(W / 2, H / 2, 30);
    // penalty areas (both ends — x is full-pitch normalized 0..1, own goal at x=0)
    rect(2, H / 2 - 55, 55, 110);
    rect(W - 57, H / 2 - 55, 55, 110);
    rect(2, H / 2 - 26, 22, 52);
    rect(W - 24, H / 2 - 26, 22, 52);
  }

  function pitchHeatmap(container, points) {
    container.replaceChildren();
    var svgNS = PITCH_NS;
    var W = PITCH_W, H = PITCH_H;
    var svg = document.createElementNS(svgNS, 'svg');
    svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
    svg.setAttribute('class', 'pitch');
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', '슈팅 위치 산점도, 총 ' + points.length + '건 (득점은 진하게 표시)');

    drawPitchOutline(svg);

    // 미스(온타겟/오프타겟) 먼저 그리고 골을 위에 덧그려서 득점이 항상 눈에 띄게 한다.
    var misses = points.filter(function (p) { return !p.goal; });
    var goals = points.filter(function (p) { return p.goal; });

    function drawShot(p, isGoal) {
      var cx = (p.x * W).toFixed(1);
      var cy = (p.y * H).toFixed(1);
      var c = document.createElementNS(svgNS, 'circle');
      c.setAttribute('cx', cx);
      c.setAttribute('cy', cy);
      c.setAttribute('r', isGoal ? 5 : 3);
      // p.mine === false면 상대가 넣은 골(실점) — 다른 색으로 구분한다. mine이 없는 호출부
      // (기존 전체 슛 히트맵 등)는 전부 "내 슛"으로 취급해 기존 색 그대로 나온다.
      c.setAttribute('class', isGoal ? (p.mine === false ? 'goal-dot-conceded' : 'goal-dot') : 'miss-dot');
      c.tabIndex = 0;
      var xgLabel = p.xg != null ? round1(p.xg) + '골' : null;
      var showFn = function (evt) {
        var lines = [p.shootType + ' · ' + (RESULT_KO[p.result] || p.result)];
        if (xgLabel) lines.push('이 구역 xG값 ' + xgLabel);
        showTip(evt, lines);
      };
      c.addEventListener('pointerenter', showFn);
      c.addEventListener('pointermove', moveTip);
      c.addEventListener('pointerleave', hideTip);
      c.addEventListener('focus', showFn);
      c.addEventListener('blur', hideTip);
      svg.appendChild(c);
    }
    misses.forEach(function (p) { drawShot(p, false); });
    goals.forEach(function (p) { drawShot(p, true); });

    container.appendChild(svg);

    var legend = el('div', 'legend');
    legend.style.marginTop = '8px';
    legend.style.justifyContent = 'center';
    var gi = el('div', 'legend-item');
    var gs = el('span', 'legend-swatch'); gs.style.background = 'var(--series-1)'; gs.style.borderRadius = '50%';
    gi.appendChild(gs); gi.appendChild(document.createTextNode('득점'));
    legend.appendChild(gi);
    var hasConceded = points.some(function (p) { return p.goal && p.mine === false; });
    if (hasConceded) {
      var ci = el('div', 'legend-item');
      var cs = el('span', 'legend-swatch'); cs.style.background = 'var(--status-critical)'; cs.style.borderRadius = '50%';
      ci.appendChild(cs); ci.appendChild(document.createTextNode('상대 득점(실점)'));
      legend.appendChild(ci);
    }
    var mi = el('div', 'legend-item');
    var ms = el('span', 'legend-swatch'); ms.style.background = 'var(--gridline)'; ms.style.border = '1px solid var(--text-muted)'; ms.style.borderRadius = '50%';
    mi.appendChild(ms); mi.appendChild(document.createTextNode('무산(온타겟/오프타겟)'));
    legend.appendChild(mi);
    container.appendChild(legend);
  }
  // ---------------- tables ----------------
  function assistTable(container, chains) {
    container.replaceChildren();
    if (!chains.length) { container.appendChild(el('p', 'card-empty', '기록된 어시스트 조합이 없습니다.')); return; }
    var table = document.createElement('table');
    var thead = document.createElement('thead');
    var htr = document.createElement('tr');
    ['어시스트', '', '득점', '골 수'].forEach(function (h, i) {
      var th = el('th', i === 3 ? 'num' : '', h);
      htr.appendChild(th);
    });
    thead.appendChild(htr);
    table.appendChild(thead);
    var tbody = document.createElement('tbody');
    chains.forEach(function (c) {
      var tr = document.createElement('tr');
      var assisterTd = el('td', 'name-cell');
      assisterTd.appendChild(playerNameBadge(c.assisterSpId, c.assisterName));
      tr.appendChild(assisterTd);
      tr.appendChild(el('td', '', '→'));
      var scorerTd = el('td', 'name-cell');
      scorerTd.appendChild(playerNameBadge(c.scorerSpId, c.scorerName));
      tr.appendChild(scorerTd);
      tr.appendChild(el('td', 'num', fmt(c.goals)));
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    container.appendChild(table);
  }

  /**
   * chains(방향별 어시스트→득점 조합)를 사람 쌍(순서 무관) 기준으로 합산해 TOP5를 만든다.
   * 예: 루카쿠→반페르시 10골 + 반페르시→루카쿠 5골 → "루카쿠 · 반페르시" 조합 15골.
   */
  function topAssistDuos(chains, limit) {
    var byPair = {};
    chains.forEach(function (c) {
      var key = [c.assisterSpId, c.scorerSpId].sort().join('|');
      if (!byPair[key]) {
        byPair[key] = { nameA: c.assisterName, spIdA: c.assisterSpId, nameB: c.scorerName, spIdB: c.scorerSpId, goals: 0 };
      }
      byPair[key].goals += c.goals;
    });
    return Object.keys(byPair).map(function (key) { return byPair[key]; })
      .sort(function (a, b) { return b.goals - a.goals; })
      .slice(0, limit);
  }

  function assistDuoTable(container, duos) {
    container.replaceChildren();
    if (!duos.length) { container.appendChild(el('p', 'card-empty', '기록된 어시스트 조합이 없습니다.')); return; }
    var table = document.createElement('table');
    var thead = document.createElement('thead');
    var htr = document.createElement('tr');
    ['조합', '합산 골 수'].forEach(function (h, i) {
      htr.appendChild(el('th', i === 1 ? 'num' : '', h));
    });
    thead.appendChild(htr);
    table.appendChild(thead);
    var tbody = document.createElement('tbody');
    duos.forEach(function (d) {
      var tr = document.createElement('tr');
      var duoTd = el('td', 'name-cell');
      duoTd.appendChild(playerNameBadge(d.spIdA, d.nameA));
      duoTd.appendChild(document.createTextNode(' · '));
      duoTd.appendChild(playerNameBadge(d.spIdB, d.nameB));
      tr.appendChild(duoTd);
      tr.appendChild(el('td', 'num', fmt(d.goals)));
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    container.appendChild(table);
  }

  function streakBadges(container, streak) {
    var wrap = el('div', 'streak-badges');
    if (streak.curWin > 0) wrap.appendChild(el('span', 'streak-badge win', streak.curWin + '연승'));
    if (streak.curLose > 0) wrap.appendChild(el('span', 'streak-badge lose', streak.curLose + '연패'));
    if (streak.curUnbeaten > 1) wrap.appendChild(el('span', 'streak-badge unbeaten', streak.curUnbeaten + '무패'));
    if (streak.curWinless > 1) wrap.appendChild(el('span', 'streak-badge winless', streak.curWinless + '무승'));
    if (!wrap.children.length) wrap.appendChild(el('span', 'streak-badge winless', '-'));
    container.appendChild(wrap);
  }

  // ---------------- match detail modal ----------------
  var modalOverlay = document.getElementById('modal-overlay');
  var modalTitle = document.getElementById('modal-title');
  var modalBody = document.getElementById('modal-body');
  var modalLastFocus = null;

  /**
   * MOM/Worst Player 한줄평 — Nexon API에 MOM 플래그가 없어(백엔드 MatchSquadEntryRaw 주석 참고)
   * 언제나 이 방식(평점 최댓값/최솟값)으로만 뽑는다. 이유는 그 선수의 가장 두드러진 스탯
   * 하나를 우선순위(골>어시스트>세이브>태클+인터셉트)로 골라 문장화하고, 특별한 스탯이 없으면
   * 평점만 언급한다.
   */
  function oneLinerFor(entry, isMom) {
    var ratingText = entry.rating != null ? '평점 ' + fmt1(entry.rating) : '평점 기록 없음';
    if (entry.goal > 0) return entry.goal + '골로 ' + (isMom ? '팀을 이끌었다' : '만은 넣었다') + ' (' + ratingText + ')';
    if (entry.assist > 0) return entry.assist + '도움으로 공격을 살렸다 (' + ratingText + ')';
    if (entry.save > 0) return entry.save + '선방으로 골문을 지켰다 (' + ratingText + ')';
    var defense = entry.tackle + entry.intercept;
    if (defense > 0) return '태클+인터셉트 ' + defense + '회로 수비에 기여했다 (' + ratingText + ')';
    return isMom ? ratingText + '로 안정적인 경기력을 보였다' : ratingText + '로 아쉬운 경기를 보냈다';
  }

  /**
   * squad는 양팀 스쿼드를 합친 목록이다 — 각 항목에 team:'mine'|'opponent'가 붙어 있다(openMatchModal
   * 참고, 상대가 추적 대상이 아니면 'mine'만 옴). 승리팀/패배팀을 나눠 각각 베스트·워스트를 따로
   * 뽑는다(요청) — matchResult('승'/'무'/'패', 내 팀 기준)로 어느 team이 이겼는지 판정한다.
   * 무승부는 승/패 구분이 없으니 예전처럼 양팀 통합 베스트·워스트 1쌍만 보여준다.
   */
  function buildMomWorstSection(container, squad, matchResult) {
    container.replaceChildren();
    // rating이 정확히 0인 선수는 "출전은 등록됐지만 실제로 안 뛴" 경우다 — Worst로 뽑히면 안 된다.
    var candidates = squad.filter(function (s) { return s.rating != null && s.rating > 0; });
    if (!candidates.length) return; // 평점 결측이면 조용히 생략(칸을 비우지 않고 아예 안 만듦)

    function bestWorstOf(list) {
      if (!list.length) return null;
      return {
        best: list.reduce(function (a, b) { return b.rating > a.rating ? b : a; }),
        worst: list.reduce(function (a, b) { return b.rating < a.rating ? b : a; })
      };
    }

    function card(icon, label, entry, isMom) {
      var box = el('div', 'mom-card ' + (isMom ? 'mom-card-best' : 'mom-card-worst'));
      var head = el('p', 'mom-card-head');
      head.appendChild(el('span', 'mom-card-icon', icon));
      head.appendChild(el('span', 'mom-card-label', label));
      box.appendChild(head);
      // playerNameBadge가 시즌 아이콘 + 강화 단계(N강) 배지를 같이 붙여준다(요청).
      var nameRow = el('p', 'mom-card-name');
      nameRow.appendChild(playerNameBadge(entry.spId, entry.playerName));
      nameRow.appendChild(el('span', 'mom-card-team', entry.team === 'opponent' ? ' (상대 팀)' : ' (내 팀)'));
      box.appendChild(nameRow);
      box.appendChild(el('p', 'mom-card-reason', oneLinerFor(entry, isMom)));
      return box;
    }

    // 항상 "팀 A vs 팀 B" 2x2를 시도한다(요청) — 승/패 경기는 승리팀/패배팀으로, 무승부는
    // 이길팀/질팀이 없으니 내 팀/상대 팀으로 나눈다. 상대가 추적 대상이 아니면 상대 쪽 데이터
    // 자체가 없어서(oppSquad가 빈 배열) 이 경우엔 어쩔 수 없이 내 팀 카드 2장만 나온다 —
    // 레이아웃 문제가 아니라 상대 스쿼드를 복원할 방법이 없는 데이터 한계다.
    var groupA, groupB, labelA, labelB;
    if (matchResult === '승') { groupA = 'mine'; groupB = 'opponent'; labelA = '승리팀'; labelB = '패배팀'; }
    else if (matchResult === '패') { groupA = 'opponent'; groupB = 'mine'; labelA = '승리팀'; labelB = '패배팀'; }
    else { groupA = 'mine'; groupB = 'opponent'; labelA = '내 팀'; labelB = '상대 팀'; }

    var bwA = bestWorstOf(candidates.filter(function (s) { return s.team === groupA; }));
    var bwB = bestWorstOf(candidates.filter(function (s) { return s.team === groupB; }));

    if (bwA) {
      container.appendChild(card('🏆', labelA + ' 베스트', bwA.best, true));
      if (bwA.worst !== bwA.best) container.appendChild(card('😓', labelA + ' 워스트', bwA.worst, false));
    }
    if (bwB) {
      container.appendChild(card('💪', labelB + ' 베스트', bwB.best, true));
      if (bwB.worst !== bwB.best) container.appendChild(card('🥶', labelB + ' 워스트', bwB.worst, false));
    }
  }

  /**
   * 게이지 바 1행 — 왼쪽=나, 오른쪽=상대(요청: "내가 높으면 A색, 상대가 높으면 B색"). 값이 큰
   * 쪽 색(--series-1=나/파랑, --status-critical=상대/빨강)이 그만큼 넓게 채워져서 우세가 한눈에 보인다.
   * mineVal/oppVal이 undefined면 "계산 중…"(비동기로 나중에 채워질 값, xG값 행 전용),
   * null이면 "-"(데이터 자체가 없음)로 구분한다.
   */
  function compareRow(container, label, mineVal, oppVal, opts) {
    opts = opts || {};
    var format = opts.format || fmt;
    var row = el('div', 'compare-row');
    row.appendChild(el('p', 'compare-row-label', label));
    var barRow = el('div', 'compare-row-bar');
    var mineSpan = el('span', 'compare-value compare-value-mine');
    var track = el('div', 'compare-track');
    var mineFill = el('div', 'compare-fill compare-fill-mine');
    var oppFill = el('div', 'compare-fill compare-fill-opp');
    track.appendChild(mineFill);
    track.appendChild(oppFill);
    var oppSpan = el('span', 'compare-value compare-value-opp');
    barRow.appendChild(mineSpan);
    barRow.appendChild(track);
    barRow.appendChild(oppSpan);
    row.appendChild(barRow);
    container.appendChild(row);

    function update(mv, ov) {
      var mineFormat = opts.formatMine || format;
      var oppFormat = opts.formatOpp || format;
      mineSpan.textContent = mv === undefined ? '계산 중…' : (mv == null ? '-' : mineFormat(mv));
      oppSpan.textContent = ov === undefined ? '계산 중…' : (ov == null ? '-' : oppFormat(ov));
      var minePct = 50;
      if (opts.minePct) {
        minePct = opts.minePct(mv, ov);
      } else if (typeof mv === 'number' && typeof ov === 'number' && (mv + ov) > 0) {
        minePct = mv / (mv + ov) * 100;
      } else if (typeof mv === 'number' && ov == null) {
        minePct = 100;
      } else if (mv == null && typeof ov === 'number') {
        minePct = 0;
      }
      mineFill.style.width = minePct + '%';
      oppFill.style.width = (100 - minePct) + '%';
    }
    update(mineVal, oppVal);
    return { update: update };
  }

  /**
   * "⚖️ 상대 팀 비교" — 상대도 추적 대상이어야 상대 쪽 팀 스탯(match-stats)을 가져올 수 있어서
   * (concededShots·MOM/Worst와 같은 제약), oppStats가 없으면 섹션 전체를 안내 문구로 대체한다.
   * xG값 행은 match-shots 응답이 따로 필요해 처음엔 "계산 중…"으로 시작하고, openMatchModal이
   * 반환된 setXg로 나중에 채운다.
   */
  function buildCompareSection(container, mine, oppStats, mySquad, oppSquad) {
    container.replaceChildren();
    container.appendChild(el('p', 'card-title', '⚖️ 상대 팀 비교'));

    if (!oppStats) {
      container.appendChild(el('p', 'card-empty', '상대가 추적 대상이 아니라서 비교할 수 없어요.'));
      return { setXg: function () {} };
    }
    var mySaves = mySquad.reduce(function (sum, s) { return sum + (s.save || 0); }, 0);
    var oppSaves = oppSquad.reduce(function (sum, s) { return sum + (s.save || 0); }, 0);
    var myShotAcc = mine.shootTotal > 0 ? (mine.effectiveShoot / mine.shootTotal * 100) : null;
    var oppShotAcc = oppStats.shootTotal > 0 ? (oppStats.effectiveShoot / oppStats.shootTotal * 100) : null;
    var pctFormat = function (v) { return Math.round(v) + '%'; };

    compareRow(container, '득점', mine.goalsFor, mine.goalsAgainst);
    var xgRow = compareRow(container, 'xG값', undefined, undefined, { format: fmt1 });
    compareRow(container, '점유율', mine.possession, oppStats.possession, {
      format: pctFormat, minePct: function (mv) { return mv == null ? 50 : mv; }
    });
    compareRow(container, '슛', mine.shootTotal, oppStats.shootTotal);
    compareRow(container, '유효슛', mine.effectiveShoot, oppStats.effectiveShoot);
    compareRow(container, '슛 성공률', myShotAcc, oppShotAcc, { format: pctFormat });
    // 패스/태클은 "성공/시도"로 표시(요청) — 막대 비율은 성공 횟수 기준으로 계산.
    compareRow(container, '패스', mine.passSuccess, oppStats.passSuccess, {
      formatMine: function (v) { return v + '/' + (mine.passTry != null ? mine.passTry : '-'); },
      formatOpp: function (v) { return v + '/' + (oppStats.passTry != null ? oppStats.passTry : '-'); }
    });
    compareRow(container, '태클', mine.tackleSuccess, oppStats.tackleSuccess, {
      formatMine: function (v) { return v + '/' + (mine.tackleTry != null ? mine.tackleTry : '-'); },
      formatOpp: function (v) { return v + '/' + (oppStats.tackleTry != null ? oppStats.tackleTry : '-'); }
    });
    compareRow(container, '선방 횟수', mySaves, oppSaves);
    compareRow(container, '파울', mine.foul, oppStats.foul);
    compareRow(container, '옐로카드', mine.yellowCards, oppStats.yellowCards);
    compareRow(container, '레드카드', mine.redCards, oppStats.redCards);

    return { setXg: function (xgFor, xgAgainst) { xgRow.update(xgFor, xgAgainst); } };
  }

  function xgOfShot(p) {
    return calcXg(p.x, p.y);
  }

  /**
   * 득점 타임라인 한 줄 — 분, 내 골/상대 골 구분, 선수, 유형, 어시스트, 이 구역 xG값(0~1).
   * onSelect가 있으면 행을 클릭/엔터 가능하게 만든다(슈팅 위치에서 그 슛만 활성화하는 용도).
   */
  function goalTimelineRow(container, g, onSelect) {
    var row = el('div', 'goal-timeline-row ' + (g.mine ? 'mine' : 'conceded'));
    var minute = absoluteMinuteOf(g.goalTimeMinutes, g.period);
    row.appendChild(el('div', 'goal-timeline-minute', minute != null ? minute + "'" : '-'));
    var body = el('div', 'goal-timeline-body');
    var head = el('div', 'goal-timeline-head');
    head.appendChild(el('span', 'goal-timeline-icon', g.mine ? '⚽' : '🥅'));
    head.appendChild(playerNameBadge(g.spId, g.playerName));
    body.appendChild(head);
    var xg = xgOfShot(g);
    var detailParts = [];
    if (g.period != null && PERIOD_KO[g.period]) detailParts.push(PERIOD_KO[g.period]);
    detailParts.push(g.shootType);
    detailParts.push(g.assist && g.assistPlayerName ? '어시스트: ' + g.assistPlayerName : '어시스트 없음');
    if (xg != null) detailParts.push('이 구역 xG ' + round1(xg) + '골');
    body.appendChild(el('div', 'goal-timeline-meta', detailParts.join(' · ')));
    row.appendChild(body);
    if (onSelect) {
      row.classList.add('goal-timeline-row-clickable');
      row.tabIndex = 0;
      row.setAttribute('role', 'button');
      row.setAttribute('aria-label', '아래 슈팅 위치에서 이 골 활성화');
      row.addEventListener('click', onSelect);
      row.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect(); } });
    }
    container.appendChild(row);
  }

  // pitchHeatmap이 그리는 페널티박스 사각형과 같은 비율 — describeZone이 "중앙/측면"을 가를 때
  // 박스 폭 기준으로 y 오프셋을 재는 용도로 쓴다(예전엔 근사 xG 구역 집계 코드와 같이 있었는데,
  // 그 코드를 지우면서 실수로 같이 지워 describeZone이 ReferenceError로 깨졌었다 — 재발 방지로
  // 여기 단독으로 옮겨둔다).
  var BOX = { yMin: 75 / 260, yMax: 185 / 260 };

  /**
   * 슛/어시스트 좌표를 사람이 읽을 만한 위치 설명으로 바꾼다(예: "박스 안 중앙", "중거리 측면").
   * 중앙/측면만 구분하고 좌/우는 구분하지 않는다 — 좌표계에서 어느 쪽이 실제 왼쪽/오른쪽
   * 터치라인인지 검증된 바가 없어, 틀린 방향을 단정하지 않기 위함(zone18은 이 검증 없이도
   * 요청받은 좌/우 명칭을 그대로 쓰기로 한 것 — 서로 다른 정책이라 여기선 그대로 둔다).
   */
  function describeZone(x, y) {
    if (x == null || y == null) return null;
    var distance;
    if (x >= 0.94) distance = '골문 바로 앞';
    else if (x >= 0.86) distance = '페널티스팟 부근';
    else if (x >= 0.80) distance = '박스 안';
    else if (x >= 0.70) distance = '박스 바로 앞';
    else if (x >= 0.55) distance = '중거리';
    else distance = '먼 거리';

    var boxCenter = (BOX.yMin + BOX.yMax) / 2;
    var boxHalfWidth = (BOX.yMax - BOX.yMin) / 2;
    var offCenter = Math.abs(y - boxCenter) / boxHalfWidth;
    var side = offCenter <= 0.6 ? '중앙' : '측면';
    return distance + ' ' + side;
  }

  /**
   * "18존" 개념(가로 3등분: 수비 진영/미드필드/공격 진영 × 세로 6채널) 기반 구역 판정.
   * 어시스트가 어디서 출발했는지처럼 "깊이 + 채널" 조합이 의미 있는 경우에 쓴다(골 근처
   * 슛 자체의 정밀 위치는 describeZone의 거리 구간이 더 적합해 그대로 둔다).
   * 세로 채널도 describeZone과 같은 이유로 실제 왼쪽/오른쪽을 확정하지 않고 A/B로만 구분한다.
   * number는 1~18(third*6+channel+1) — 표준 18존 표기와 같은 번호 체계를 쓰되, 공격 진영의
   * 비균등 세분화(Zone 14 골든스퀘어 등)까지는 재현하지 않은 균등 3×6 격자다.
   */
  /**
   * 18존 명칭 — x축(골대까지 거리 방향) 6단계 × y축(좌우) 3단계, index = xIdx*3 + yIdx.
   * 요청받은 고정 명칭 목록을 그대로 쓴다. 주의: y축(좌측/우측)이 실제 어느 터치라인인지는
   * 이 좌표계에서 검증된 바 없다(describeZone 주석 참고) — "좌측"/"우측" 표기는 이 명칭
   * 목록을 그대로 적용해달라는 요청에 따른 것이고, 실제 방향과 다를 수 있다.
   */
  var ZONE18_LABELS = [
    '좌측 수비 측면', '수비 중앙', '우측 수비 측면',
    '좌측 수비 전개', '후방 미드필더', '우측 중앙선 측면',
    '좌측 중앙선 측면', '중앙 미드필더', '우측 중앙선 공격 전개',
    '좌측 공격 하프스페이스', '상대 3선 중앙', '우측 공격 하프스페이스',
    '좌측 박스 침투', '페널티 박스 정면', '우측 박스 침투',
    '좌측 골라인 컷백', '골키퍼 바로 앞', '우측 골라인 컷백'
  ];
  function zone18(x, y) {
    if (x == null || y == null) return null;
    var xIdx = Math.min(Math.floor(x * 6), 5);
    var yIdx = Math.min(Math.floor(y * 3), 2);
    var number = xIdx * 3 + yIdx + 1;
    return { number: number, label: ZONE18_LABELS[number - 1] };
  }
  /** 어시스트 출발 지점을 18존 라벨 문장으로("Zone 11 · 상대 3선 중앙"). */
  function describeAssistZone18(x, y) {
    var z = zone18(x, y);
    // "Zone 17" 같은 번호 접두어는 빼고 설정해둔 구역 명칭만 보여준다(요청) — 내부적으로는
    // zone18()이 여전히 번호를 계산하지만 해설 문장에는 이제 노출하지 않는다.
    return z ? z.label : null;
  }

  /** 풋볼매니저 스타일 텍스트 해설 한 줄 — 클릭한 슛 1건을 자연어 문장으로 조립한다. */
  function buildShotCommentary(shot) {
    var minute = absoluteMinuteOf(shot.goalTimeMinutes, shot.period);
    var sentence = minute != null ? minute + "' " : '';
    if (shot.assist && shot.assistPlayerName) {
      var assistZone = describeAssistZone18(shot.assistX, shot.assistY);
      sentence += (assistZone ? assistZone + '에서 올라온 ' : '') + shot.assistPlayerName + '의 패스를 받은 ';
    }
    var zone = describeZone(shot.x, shot.y);
    sentence += (shot.mine === false ? '상대 ' : '') + shot.playerName + '의'
      + (zone ? ' ' + zone : '') + ' ' + shot.shootType;
    if (shot.hitPost) sentence += '(골대를 맞고)';
    if (shot.inPenalty === false) sentence += '(박스 밖 중거리)';
    sentence += shot.isGoal ? ' — 골입니다!' : (shot.result === 'ON_TARGET' ? ' — 골키퍼 선방에 막혔습니다.' : ' — 아쉽게 빗나갔습니다.');
    return sentence;
  }

  /**
   * 매치 상세 모달의 인터랙티브 슈팅 위치 — 어시스트가 있는 슛은 전부 화살표 선으로 미리
   * 다 그려서 보여준다(득점 타임라인처럼 하나만 콕 집는 게 아니라 전체 그림 먼저). 슛 점이나
   * (반환하는 컨트롤러를 통해) 타임라인 행을 클릭하면 그 슛만 활성화되고 나머지는 회색으로
   * 흐려진다. 실점(상대 슛)은 180도 반전(x,y,assistX,assistY 전부 1-값)해서 좌측(내 골대
   * 방향)에 함께 그린다. container에는 피치 SVG만 그리고, 해설 텍스트 박스는 별도 줄로
   * 붙일 수 있도록 commentaryEl로 반환만 한다(호출부가 원하는 위치에 appendChild한다).
   *
   * @returns {{commentaryEl: HTMLElement, selectGoal: function(Object): void}}
   *   commentaryEl은 해설 텍스트가 표시되는 박스(호출부가 붙여야 화면에 보인다),
   *   selectGoal은 득점 타임라인 행 클릭 시 그 골에 해당하는 슛만 활성화하기 위한 함수
   *   (mine+spId+goalTimeMinutes+period로 매칭한다).
   */
  function renderInteractiveMatchPitch(container, myShots, concededShots) {
    container.replaceChildren();
    var allShots = myShots.map(function (s) {
      var copy = {}; for (var k in s) copy[k] = s[k];
      copy.mine = true;
      return copy;
    }).concat(concededShots.map(function (s) {
      var copy = {}; for (var k in s) copy[k] = s[k];
      copy.mine = false;
      copy.x = s.x != null ? 1 - s.x : null;
      copy.y = s.y != null ? 1 - s.y : null;
      copy.assistX = s.assistX != null ? 1 - s.assistX : null;
      copy.assistY = s.assistY != null ? 1 - s.assistY : null;
      return copy;
    })).filter(function (p) { return p.x != null && p.y != null; });

    var svg = document.createElementNS(PITCH_NS, 'svg');
    svg.setAttribute('viewBox', '0 0 ' + PITCH_W + ' ' + PITCH_H);
    svg.setAttribute('class', 'pitch');
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', '슈팅·어시스트 위치, 클릭하면 상세 해설이 표시됩니다');

    // 어시스트 화살표 끝머리 마커 — svg마다 새로 생성되니 defs도 매번 같이 넣어준다.
    var defs = document.createElementNS(PITCH_NS, 'defs');
    var marker = document.createElementNS(PITCH_NS, 'marker');
    marker.setAttribute('id', 'assist-arrowhead');
    marker.setAttribute('viewBox', '0 0 8 8');
    marker.setAttribute('refX', '6');
    marker.setAttribute('refY', '4');
    marker.setAttribute('markerWidth', '6');
    marker.setAttribute('markerHeight', '6');
    marker.setAttribute('orient', 'auto-start-reverse');
    var arrowPath = document.createElementNS(PITCH_NS, 'path');
    arrowPath.setAttribute('d', 'M0,0 L8,4 L0,8 Z');
    arrowPath.style.fill = 'var(--assist-arrow)'; // 초록 피치 위 대비를 위해 전용 골드 변수 사용(--series-2 주황 대신)
    marker.appendChild(arrowPath);
    defs.appendChild(marker);
    svg.appendChild(defs);

    drawPitchOutline(svg);

    var commentaryBox = el('div', 'match-pitch-commentary', '슛이나 위 득점 타임라인을 클릭하면 상세 해설이 나와요.');
    var entries = [];

    function activate(entry) {
      entries.forEach(function (e) {
        var isActive = e === entry;
        e.dotEl.classList.toggle('shot-dot-active', isActive);
        e.dotEl.classList.toggle('shot-dot-dim', !isActive);
        if (e.lineEl) {
          e.lineEl.classList.toggle('assist-line-active', isActive);
          e.lineEl.classList.toggle('assist-line-dim', !isActive);
        }
      });
      commentaryBox.textContent = buildShotCommentary(entry.shot);
    }

    allShots.forEach(function (p) {
      var cx = (p.x * PITCH_W).toFixed(1);
      var cy = (p.y * PITCH_H).toFixed(1);

      // 어시스트가 있는 슛은 처음부터 화살표 선을 그려둔다(선택 전까지는 전체가 다 보임).
      var lineEl = null;
      if (p.assist && p.assistX != null && p.assistY != null) {
        lineEl = document.createElementNS(PITCH_NS, 'line');
        lineEl.setAttribute('x1', (p.assistX * PITCH_W).toFixed(1));
        lineEl.setAttribute('y1', (p.assistY * PITCH_H).toFixed(1));
        lineEl.setAttribute('x2', cx);
        lineEl.setAttribute('y2', cy);
        lineEl.setAttribute('class', 'assist-line');
        svg.appendChild(lineEl);
      }

      var dot = document.createElementNS(PITCH_NS, 'circle');
      dot.setAttribute('cx', cx);
      dot.setAttribute('cy', cy);
      dot.setAttribute('r', p.isGoal ? 5 : 3);
      dot.setAttribute('class', p.isGoal ? (p.mine === false ? 'goal-dot-conceded' : 'goal-dot') : 'miss-dot');
      dot.tabIndex = 0;
      dot.setAttribute('role', 'button');
      dot.setAttribute('aria-label', p.playerName + '의 슛, 클릭하면 해설 표시');
      svg.appendChild(dot);

      var entry = { shot: p, dotEl: dot, lineEl: lineEl };
      entries.push(entry);
      var selectFn = function () { activate(entry); };
      dot.addEventListener('click', selectFn);
      dot.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); selectFn(); } });
    });

    container.appendChild(svg);

    return {
      commentaryEl: commentaryBox,
      selectGoal: function (g) {
        var match = entries.filter(function (e) {
          return e.shot.mine === g.mine && e.shot.spId === g.spId
            && e.shot.goalTimeMinutes === g.goalTimeMinutes && e.shot.period === g.period;
        })[0];
        if (match) activate(match);
      },
      // 아무것도 안 골라도 해설이 비어 보이지 않도록 renderModalShots가 기본값으로 하나 선택해둔다.
      selectFirst: function () {
        if (entries.length) activate(entries[0]);
      }
    };
  }

  /**
   * 매치 상세 모달의 "⏱️ 득점 타임라인"(상단 오른쪽) / "📝 해설" / "🎯 슈팅 위치" / "📈 xG 추이"
   * (이 순서로 하단에 각각 전체 폭 줄) 네 섹션 — 컨테이너를 따로 받는다(openMatchModal이
   * 득점 타임라인은 "⚖️ 상대 팀 비교"와 나란히, 나머지 셋은 그 아래 순서대로 배치하기 위함).
   * concededShots가 비어 있으면(상대가 추적 대상이 아니면) 실점은 조용히 빠지고 내 득점만 나온다.
   */
  function renderModalShots(timelineContainer, commentaryContainer, pitchContainer, xgRaceContainer,
                             myShots, concededShots) {
    timelineContainer.replaceChildren();
    commentaryContainer.replaceChildren();
    pitchContainer.replaceChildren();
    xgRaceContainer.replaceChildren();

    if (!myShots.length && !concededShots.length) {
      pitchContainer.appendChild(el('p', 'card-empty', '기록된 슈팅이 없습니다.'));
      return;
    }

    var myGoals = myShots.filter(function (s) { return s.isGoal; }).map(function (g) {
      var copy = {}; for (var k in g) copy[k] = g[k]; copy.mine = true; return copy;
    });
    var concededGoals = concededShots.filter(function (s) { return s.isGoal; }).map(function (g) {
      var copy = {}; for (var k in g) copy[k] = g[k]; copy.mine = false; return copy;
    });
    var timeline = myGoals.concat(concededGoals).sort(function (a, b) {
      var ma = absoluteMinuteOf(a.goalTimeMinutes, a.period);
      var mb = absoluteMinuteOf(b.goalTimeMinutes, b.period);
      if (ma == null && mb == null) return 0;
      if (ma == null) return 1;
      if (mb == null) return -1;
      return ma - mb;
    });

    // 피치를 먼저 만들어 컨트롤러를 얻어둔다 — 득점 타임라인 행을 클릭했을 때 슈팅 위치에서
    // 그 골만 활성화하기 위함.
    var pitchWrap = el('div', 'pitch-wrap');
    var pitchController = renderInteractiveMatchPitch(pitchWrap, myShots, concededShots);

    if (timeline.length) {
      timelineContainer.appendChild(el('p', 'card-title', '⏱️ 득점 타임라인'));
      timelineContainer.appendChild(el('p', 'card-caption', '클릭하면 아래 슈팅 위치·해설에서 그 골만 활성화됩니다.'));
      var tl = el('div', 'goal-timeline');
      timeline.forEach(function (g) {
        goalTimelineRow(tl, g, function () { pitchController.selectGoal(g); });
      });
      timelineContainer.appendChild(tl);
    }

    commentaryContainer.appendChild(el('p', 'card-title', '📝 해설'));
    commentaryContainer.appendChild(pitchController.commentaryEl);

    pitchContainer.appendChild(el('p', 'card-title', '🎯 슈팅 위치'));
    if (concededShots.length) {
      pitchContainer.appendChild(el('p', 'card-caption', '좌측: 상대가 쏜 슛(내 골대 방향) · 우측: 내가 쏜 슛(상대 골대 방향)'));
    }
    pitchContainer.appendChild(pitchWrap);

    xgRaceContainer.appendChild(el('p', 'card-title', '📈 xG 추이'));
    xgRaceContainer.appendChild(el('p', 'card-caption', '슛이 나온 시각마다 xG값이 누적됩니다 — 선이 더 가파르게 올라간 쪽이 그 시간대에 우세했다는 뜻이에요.'));
    var xgRaceChartEl = el('div', '');
    xgRaceContainer.appendChild(xgRaceChartEl);
    renderXgRaceChart(xgRaceChartEl, myShots, concededShots);

    // 아무것도 안 고르면 해설 칸이 계속 비어 보인다는 피드백 — 기본으로 첫 골(없으면 첫 슛)을 선택해둔다.
    if (timeline.length) pitchController.selectGoal(timeline[0]);
    else pitchController.selectFirst();
  }

  /** 슛 목록을 시각 순 누적 xG로 바꾼다 — 0분 (0,0)에서 시작해 슛이 나올 때마다 계단식으로 오른다. */
  function cumulativeXgSeries(shots) {
    var points = shots
      .filter(function (p) { return p.x != null && p.y != null; })
      .map(function (p) { return { minute: absoluteMinuteOf(p.goalTimeMinutes, p.period), xg: calcXg(p.x, p.y), goal: !!p.isGoal }; })
      .filter(function (p) { return p.minute != null; })
      .sort(function (a, b) { return a.minute - b.minute; });
    var series = [{ minute: 0, cum: 0, goal: false }];
    var cum = 0;
    points.forEach(function (p) { cum += p.xg; series.push({ minute: p.minute, cum: cum, goal: p.goal }); });
    return series;
  }

  /**
   * "xG 추이(momentum)" 계단식 라인차트 — 슈팅 위치의 x,y 좌표를 그대로 써서(match-shots는
   * player 단위가 아니라 매치 단위라 추가 API 호출 없음) 시간축(분) 위에 누적 xG를 그린다.
   * lineChart(경기별 추이용, index 기반 x축)와 달리 x축이 실제 "분"이라 별도로 그린다.
   */
  function renderXgRaceChart(container, myShots, concededShots) {
    container.replaceChildren();
    var mine = cumulativeXgSeries(myShots);
    var hasOpp = concededShots.length > 0;
    var opp = hasOpp ? cumulativeXgSeries(concededShots) : null;
    if (mine.length <= 1 && (!opp || opp.length <= 1)) {
      container.appendChild(el('p', 'card-empty', '표시할 xG 데이터가 없습니다.'));
      return;
    }

    var maxMinute = Math.max(90, mine[mine.length - 1].minute, opp ? opp[opp.length - 1].minute : 0);
    function extend(series) {
      var last = series[series.length - 1];
      return last.minute < maxMinute ? series.concat([{ minute: maxMinute, cum: last.cum }]) : series;
    }
    mine = extend(mine);
    if (opp) opp = extend(opp);
    // 최댓값에 딱 붙지 않도록 여유(20%)를 조금 둔다 — 선이 그래프 맨 위에 눌린 것처럼 보이던 것 개선.
    var maxCum = Math.max(1, mine[mine.length - 1].cum, opp ? opp[opp.length - 1].cum : 0) * 1.2;

    var W = 480, H = 170, padL = 30, padR = 12, padT = 16, padB = 22;
    var innerW = W - padL - padR, innerH = H - padT - padB;
    function xAt(minute) { return padL + (innerW * minute) / maxMinute; }
    function yAt(cum) { return padT + innerH - (innerH * cum) / maxCum; }

    function stepPoints(series) {
      var pts = [];
      for (var i = 0; i < series.length; i++) {
        if (i > 0) pts.push(xAt(series[i].minute).toFixed(1) + ',' + yAt(series[i - 1].cum).toFixed(1));
        pts.push(xAt(series[i].minute).toFixed(1) + ',' + yAt(series[i].cum).toFixed(1));
      }
      return pts.join(' ');
    }

    var svgNS = 'http://www.w3.org/2000/svg';
    var svg = document.createElementNS(svgNS, 'svg');
    svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
    svg.setAttribute('class', 'linechart');
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', '시간대별 누적 xG 추이');

    var Y_TICKS = 4;
    for (var t = 0; t <= Y_TICKS; t++) {
      var tickValue = (maxCum * t) / Y_TICKS;
      var tickY = yAt(tickValue);
      var gridLine = document.createElementNS(svgNS, 'line');
      gridLine.setAttribute('x1', padL); gridLine.setAttribute('x2', W - padR);
      gridLine.setAttribute('y1', tickY); gridLine.setAttribute('y2', tickY);
      gridLine.setAttribute('class', 'linechart-axis');
      gridLine.setAttribute('opacity', t === 0 ? '1' : '0.4');
      svg.appendChild(gridLine);
      var tickLabel = document.createElementNS(svgNS, 'text');
      tickLabel.setAttribute('x', padL - 5);
      tickLabel.setAttribute('y', tickY + 3);
      tickLabel.setAttribute('text-anchor', 'end');
      tickLabel.setAttribute('class', 'linechart-axis-label');
      tickLabel.textContent = round1(tickValue);
      svg.appendChild(tickLabel);
    }
    [0, 15, 30, 45, 60, 75, 90].forEach(function (min) {
      if (min > maxMinute) return;
      var t = document.createElementNS(svgNS, 'text');
      t.setAttribute('x', xAt(min));
      t.setAttribute('y', H - 6);
      t.setAttribute('text-anchor', 'middle');
      t.setAttribute('class', 'linechart-axis-label');
      t.textContent = min + "'";
      svg.appendChild(t);
    });

    function drawSeries(series, color) {
      var poly = document.createElementNS(svgNS, 'polyline');
      poly.setAttribute('points', stepPoints(series));
      poly.setAttribute('fill', 'none');
      poly.setAttribute('stroke', color);
      poly.setAttribute('stroke-width', '2');
      poly.setAttribute('stroke-linejoin', 'round');
      svg.appendChild(poly);
    }
    // 골이 터진 지점엔 축구공 이모지 마커를 얹는다 — 계단이 오르는 순간(누적 xG가 그 슛의 xg만큼
    // 뛴 지점)을 눈으로 바로 짚을 수 있게.
    // font-family를 안 정해주면 SVG <text>가 UA 기본 폰트(보통 세리프)로 렌더링을 시도하다
    // 이모지 대체 글리프 없이 이상한 글자(십자 모양 등)로 깨져 보이는 문제가 있었다 — 이모지
    // 컬러 폰트를 명시적으로 지정해야 실제 축구공 이모지가 뜬다.
    var EMOJI_FONT_STACK = "'Segoe UI Emoji','Apple Color Emoji','Noto Color Emoji',sans-serif";
    // 축구공 이모지 자체는 색을 못 입혀서(고정 컬러 글리프) 내 골/상대 골이 구분이 안 된다는
    // 피드백 — 이모지 뒤에 그 시리즈 색(나=파랑/상대=빨강) 원형 배지를 깔아서 구분되게 한다.
    function drawGoalMarkers(series, color, ownerLabel) {
      series.forEach(function (p) {
        if (!p.goal) return;
        var cx = xAt(p.minute), cy = yAt(p.cum) - 8;
        var badge = document.createElementNS(svgNS, 'circle');
        badge.setAttribute('cx', cx);
        badge.setAttribute('cy', cy);
        badge.setAttribute('r', '7');
        badge.setAttribute('fill', color);
        svg.appendChild(badge);
        var mark = document.createElementNS(svgNS, 'text');
        mark.setAttribute('x', cx);
        mark.setAttribute('y', cy + 3.5);
        mark.setAttribute('text-anchor', 'middle');
        mark.setAttribute('font-size', '10');
        mark.setAttribute('font-family', EMOJI_FONT_STACK);
        mark.textContent = '⚽';
        var title = document.createElementNS(svgNS, 'title');
        title.textContent = round1(p.minute) + "' " + ownerLabel + ' 골';
        mark.appendChild(title);
        svg.appendChild(mark);
      });
    }
    drawSeries(mine, 'var(--series-1)');
    if (opp) drawSeries(opp, 'var(--status-critical)');
    drawGoalMarkers(mine, 'var(--series-1)', '나');
    if (opp) drawGoalMarkers(opp, 'var(--status-critical)', '상대');

    container.appendChild(svg);

    var legend = el('div', 'legend');
    legend.style.marginTop = '8px';
    legend.style.justifyContent = 'center';
    function legendItem(label, color) {
      var item = el('div', 'legend-item');
      var sw = el('span', 'legend-swatch');
      sw.style.background = color;
      item.appendChild(sw);
      item.appendChild(document.createTextNode(label));
      legend.appendChild(item);
    }
    legendItem('나 (누적 xG ' + round1(mine[mine.length - 1].cum) + ')', 'var(--series-1)');
    if (opp) legendItem('상대 (누적 xG ' + round1(opp[opp.length - 1].cum) + ')', 'var(--status-critical)');
    container.appendChild(legend);
    if (!hasOpp) {
      container.appendChild(el('p', 'card-caption', '상대가 추적 대상이 아니라 상대 쪽 xG 추이는 표시할 수 없어요.'));
    }
  }

  var modalRequestSeq = 0;

  function openMatchModal(m) {
    modalLastFocus = document.activeElement;
    var seq = ++modalRequestSeq;
    var d = new Date(m.matchDate);
    var myUser = allUsers.filter(function (u) { return u.ouid === state.ouid; })[0];
    // allUsers는 "전체" 화면에선 비어있을 수 있다(백엔드를 안 거쳐서) — 대시보드 최근 경기
    // 피드에서 연 경우 m.__nickname(그 매치를 미리 붙일 때 넣어둔 값)으로 대신한다.
    var myNickname = myUser ? myUser.nickname : (m.__nickname || '나');
    modalTitle.textContent = myNickname + ' vs ' + m.opponentNickname + ' · ' + fmtDateTime(d, true);

    modalBody.replaceChildren();
    var resultLine = el('div', '');
    resultLine.style.marginBottom = '14px';
    resultLine.appendChild(el('span', 'chip result-' + m.result, m.result));
    var scoreSpan = el('span', '');
    scoreSpan.style.marginLeft = '8px';
    scoreSpan.style.fontFamily = 'var(--font-display)';
    scoreSpan.style.fontSize = '20px';
    scoreSpan.style.fontWeight = '600';
    scoreSpan.textContent = m.goalsFor + ' : ' + m.goalsAgainst;
    resultLine.appendChild(scoreSpan);
    modalBody.appendChild(resultLine);

    var momSection = el('div', 'mom-worst-section');
    modalBody.appendChild(momSection);

    // 상단 줄: 왼쪽 "⚖️ 상대 팀 비교", 오른쪽 "⏱️ 득점 타임라인"(요청) — 그 아래로 해설 →
    // 슈팅 위치 → xG 추이 순서로 전체 폭 줄이 이어진다.
    var topRow = el('div', 'modal-top-row');
    var compareSection = el('div', 'match-compare-section');
    var timelineSection = el('div', 'modal-shots-section modal-timeline-section');
    topRow.appendChild(compareSection);
    topRow.appendChild(timelineSection);
    modalBody.appendChild(topRow);

    // MOM/Worst·상대 스탯 비교는 둘 다 상대도 추적 대상이어야 가능하다(concededShots와 같은 제약,
    // allUsers로 확인). 하나의 Promise.all로 스쿼드(양팀) + 상대 팀 스탯을 같이 불러온다.
    var opponentTracked = m.opponentOuid && isTrackedOuid(m.opponentOuid);
    var mySquadPromise = apiGet('/api/v1/records/match-squad', { ouid: state.ouid, matchType: state.matchType, matchId: m.matchId })
      .then(function (squad) { return squad.map(function (s) { s.team = 'mine'; return s; }); })
      .catch(function () { return []; });
    var oppSquadPromise = opponentTracked
      ? apiGet('/api/v1/records/match-squad', { ouid: m.opponentOuid, matchType: state.matchType, matchId: m.matchId })
          .then(function (squad) { return squad.map(function (s) { s.team = 'opponent'; return s; }); })
          .catch(function () { return []; })
      : Promise.resolve([]);
    var oppStatsPromise = opponentTracked
      ? apiGet('/api/v1/records/match-stats', { ouid: m.opponentOuid, matchType: state.matchType, matchId: m.matchId })
          .catch(function () { return null; })
      : Promise.resolve(null);
    // 카드 강화 배지(playerGradeMap)는 loadSelection이 state.ouid 기준으로만 채워둔다 — 득점
    // 타임라인 등 모달 안에서 상대 선수 이름에 배지를 붙이려면 상대 ouid 몫도 따로 받아와
    // 병합해야 한다(그동안 "가끔 상대 선수만 강화 배지가 안 뜨는" 버그의 원인).
    var oppGradesPromise = opponentTracked
      ? apiGet('/api/v1/records/player-grades', { ouid: m.opponentOuid, matchType: state.matchType, seasonId: state.seasonId })
          .catch(function () { return []; })
      : Promise.resolve([]);

    // xG값 행은 match-shots(별도 요청, 아래)가 끝나야 채워진다 — 어느 쪽이 먼저 끝나든 서로
    // 기다리지 않고 준비되는 대로 반영하기 위한 작은 상태 저장소.
    var compareXgState = { for: null, against: null, ready: false };
    var compareRefs = null;
    function applyCompareXg() {
      if (compareRefs && compareXgState.ready) compareRefs.setXg(compareXgState.for, compareXgState.against);
    }

    Promise.all([mySquadPromise, oppSquadPromise, oppStatsPromise, oppGradesPromise])
      .then(function (results) {
        if (seq !== modalRequestSeq) return;
        var mySquad = results[0], oppSquad = results[1], oppStats = results[2], oppGrades = results[3];
        oppGrades.forEach(function (g) { playerGradeMap[g.spId] = g.grade; });
        buildMomWorstSection(momSection, mySquad.concat(oppSquad), m.result);
        compareRefs = buildCompareSection(compareSection, m, oppStats, mySquad, oppSquad);
        applyCompareXg();
      })
      .catch(function () { /* MOM/Worst·비교는 부가 정보라 실패해도 나머지 모달 표시는 막지 않는다 */ });

    // ⚽ 득점 상세 / 🥅 실점 상세(누가 골, 누가 어시, xG값) — 매치 1건의 슛 이벤트를 그때 불러온다.
    // concededShots는 상대도 추적 대상이어야 채워진다(아니면 조용히 생략). 예전엔 여기서
    // 평점/점유율/슈팅/패스/태클/파울/카드/xG값을 표(모달-스탯-그리드)로 따로 보여줬는데,
    // "⚖️ 상대 팀 비교"가 같은 정보를 상대와 나란히 더 잘 보여줘서 뺐다(요청).
    timelineSection.appendChild(el('p', 'card-empty', '불러오는 중…'));
    var commentarySection = el('div', 'modal-shots-section modal-commentary-section');
    commentarySection.appendChild(el('p', 'card-empty', '불러오는 중…'));
    modalBody.appendChild(commentarySection);

    // 슈팅 위치 | xG 추이 — 2열(요청). 좁은 화면에서는 위아래로 쌓인다(모달-top-row와 같은 규칙).
    var bottomRow = el('div', 'modal-top-row');
    var pitchSection = el('div', 'modal-shots-section modal-pitch-section');
    var xgRaceSection = el('div', 'modal-shots-section modal-xgrace-section');
    pitchSection.appendChild(el('p', 'card-empty', '불러오는 중…'));
    xgRaceSection.appendChild(el('p', 'card-empty', '불러오는 중…'));
    bottomRow.appendChild(pitchSection);
    bottomRow.appendChild(xgRaceSection);
    modalBody.appendChild(bottomRow);

    // oppGradesPromise도 같이 기다린다 — 득점 타임라인(goalTimelineRow)이 상대 선수 이름에
    // 강화 배지를 붙이려면 playerGradeMap에 상대 몫이 먼저 병합돼 있어야 한다. 두 요청 중
    // 어느 게 먼저 끝나든(경쟁 상태) 여기서 항상 병합 후에 렌더링하도록 순서를 고정한다.
    Promise.all([
      apiGet('/api/v1/records/match-shots', { ouid: state.ouid, matchType: state.matchType, matchId: m.matchId }),
      oppGradesPromise
    ])
      .then(function (all) {
        var result = all[0], oppGrades = all[1];
        if (seq !== modalRequestSeq) return; // 응답 도착 전에 모달이 다른 매치로 다시 열린 경우
        oppGrades.forEach(function (g) { playerGradeMap[g.spId] = g.grade; });
        renderModalShots(timelineSection, commentarySection, pitchSection, xgRaceSection,
          result.myShots, result.concededShots);

        compareXgState.for = expectedGoalsOf(result.myShots);
        compareXgState.against = result.concededShots.length ? expectedGoalsOf(result.concededShots) : null;
        compareXgState.ready = true;
        applyCompareXg();
      })
      .catch(function () {
        if (seq !== modalRequestSeq) return;
        timelineSection.replaceChildren();
        commentarySection.replaceChildren();
        pitchSection.replaceChildren();
        xgRaceSection.replaceChildren();
        pitchSection.appendChild(el('p', 'card-empty', '슛 상세를 불러오지 못했습니다.'));
      });

    var idLine = el('p', 'card-caption', '매치 ID ' + m.matchId);
    idLine.style.marginTop = '10px';
    idLine.style.marginBottom = '0';
    modalBody.appendChild(idLine);

    modalOverlay.hidden = false;
    modalOverlay.querySelector('.modal-dialog').focus();
    document.addEventListener('keydown', onModalKeydown);
  }
  function closeMatchModal() {
    modalOverlay.hidden = true;
    document.removeEventListener('keydown', onModalKeydown);
    if (modalLastFocus && typeof modalLastFocus.focus === 'function') modalLastFocus.focus();
  }
  function onModalKeydown(e) { if (e.key === 'Escape') closeMatchModal(); }
  document.getElementById('modal-close').addEventListener('click', closeMatchModal);
  modalOverlay.addEventListener('click', function (e) { if (e.target === modalOverlay) closeMatchModal(); });
  modalOverlay.querySelector('.modal-dialog').tabIndex = -1;

  function buildMatchRow(m, withDate) {
    var tr = document.createElement('tr');
    tr.className = 'match-row';
    tr.tabIndex = 0;
    tr.setAttribute('role', 'button');
    tr.setAttribute('aria-label', 'vs ' + m.opponentNickname + ' 경기 상세 보기');
    if (withDate) {
      var d = new Date(m.matchDate);
      tr.appendChild(el('td', '', fmtDateTime(d, false)));
      tr.appendChild(el('td', 'name-cell', m.opponentNickname));
    }
    var resTd = document.createElement('td');
    resTd.appendChild(el('span', 'chip result-' + m.result, m.result));
    tr.appendChild(resTd);
    tr.appendChild(el('td', 'num', m.goalsFor + ' : ' + m.goalsAgainst));
    tr.appendChild(el('td', 'num', m.possession != null ? m.possession + '%' : '-'));
    tr.appendChild(el('td', 'num', (m.effectiveShoot != null ? m.effectiveShoot : '-') + ' / ' + (m.shootTotal != null ? m.shootTotal : '-')));
    tr.appendChild(el('td', 'num', (m.passSuccess != null ? m.passSuccess : '-') + ' / ' + (m.passTry != null ? m.passTry : '-')));
    tr.appendChild(el('td', 'num', (m.tackleSuccess != null ? m.tackleSuccess : '-') + ' / ' + (m.tackleTry != null ? m.tackleTry : '-')));
    var openFn = function () { openMatchModal(m); };
    tr.addEventListener('click', openFn);
    tr.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openFn(); } });
    return tr;
  }
  function opponentsTable(container, opponents) {
    container.replaceChildren();
    if (!opponents.length) { container.appendChild(el('p', 'card-empty', '상대 전적이 없습니다.')); return; }
    var sorted = opponents.slice()
      .sort(function (a, b) { return myScore(b) - myScore(a); });
    document.getElementById('opponents-caption').textContent =
      '내 승점 높은 순 · ⚡ 표시는 욱식 점수(승5·무3·패1) 적용, 나머지는 표준 승점(승3·무1·패0) · ' +
      '행을 클릭하면 그 상대와의 최근 경기가 펼쳐집니다';
    var table = document.createElement('table');
    var thead = document.createElement('thead');
    var htr = document.createElement('tr');
    ['', '상대', '전적 (승-무-패)', '현재 기록', '내 승점', '상대 승점'].forEach(function (h, i) {
      htr.appendChild(el('th', i >= 4 ? 'num' : '', h));
    });
    thead.appendChild(htr);
    table.appendChild(thead);
    var tbody = document.createElement('tbody');
    sorted.forEach(function (o) {
      var tr = document.createElement('tr');
      tr.className = 'opp-row';
      tr.tabIndex = 0;
      tr.setAttribute('role', 'button');
      tr.setAttribute('aria-expanded', 'false');
      tr.setAttribute('aria-label', o.opponentNickname + ' 상대 최근 경기 펼치기');
      tr.appendChild(el('td', 'expand-caret', '▸'));
      tr.appendChild(el('td', 'name-cell', o.opponentNickname));
      var wdl = el('td', '');
      var w = el('span', ''); w.style.color = 'var(--status-good)'; w.style.fontWeight = '600'; w.textContent = o.tally.win;
      var dsep = el('span', ''); dsep.textContent = ' - ' + o.tally.draw + ' - ';
      var l = el('span', ''); l.style.color = 'var(--status-critical)'; l.style.fontWeight = '600'; l.textContent = o.tally.lose;
      wdl.appendChild(w); wdl.appendChild(dsep); wdl.appendChild(l);
      tr.appendChild(wdl);
      var streakTd = document.createElement('td');
      streakBadges(streakTd, o.streak);
      tr.appendChild(streakTd);

      var myWook = isWook(state.ouid);
      var myScoreTd = el('td', 'num', (myWook ? '⚡ ' : '') + fmt(myScore(o)));
      if (myWook) myScoreTd.title = '욱식 점수(승5·무3·패1)';
      tr.appendChild(myScoreTd);

      var oppWook = isWook(o.opponentOuid);
      var oppScoreTd = el('td', 'num', (oppWook ? '⚡ ' : '') + fmt(opponentScore(o)));
      if (oppWook) oppScoreTd.title = '욱식 점수(승5·무3·패1)';
      tr.appendChild(oppScoreTd);

      var expandTr = document.createElement('tr');
      expandTr.className = 'opp-expand';
      expandTr.hidden = true;
      var expandTd = document.createElement('td');
      expandTd.colSpan = 6;
      var inner = el('div', 'expand-inner');
      expandTd.appendChild(inner);
      expandTr.appendChild(expandTd);

      // v2: 전체 상대의 최근 경기를 미리 다 받아두던 스냅샷 방식 대신, 펼칠 때 그 상대 것만 그때 불러온다
      // (백엔드가 상대당 페이지 API를 이미 갖고 있어서 — 미리 당겨둘 이유가 없다).
      // OPPONENT_SAMPLE_SIZE: 이 상대와의 전체 경기 수 만큼 한 번에 받아 평균/TOP3를 정확히 계산한다
      // (친구 그룹 특성상 한 상대와 수백 경기씩 붙는 경우는 없어 페이징 없이도 충분히 가볍다).
      var OPPONENT_SAMPLE_SIZE = 500;
      var expanded = false;
      var loaded = false;
      var toggle = function () {
        expanded = !expanded;
        tr.setAttribute('aria-expanded', String(expanded));
        tr.querySelector('.expand-caret').textContent = expanded ? '▾' : '▸';
        expandTr.hidden = !expanded;
        if (expanded && !loaded) {
          loaded = true;
          inner.replaceChildren();
          inner.appendChild(el('p', 'card-empty', '불러오는 중…'));
          var qs = { ouid: state.ouid, matchType: state.matchType, seasonId: state.seasonId };
          Promise.all([
            apiGet('/api/v1/opponents/' + encodeURIComponent(o.opponentOuid) + '/matches',
              { ouid: qs.ouid, matchType: qs.matchType, seasonId: qs.seasonId, page: 0, size: OPPONENT_SAMPLE_SIZE })
              .then(function (page) { return page.content; }),
            // 이 상대전 선수 기여도 TOP3(득점/도움/선방/수비)용 — 실패해도 나머지 표시는 막지 않는다.
            apiGet('/api/v1/records/players',
              { ouid: qs.ouid, matchType: qs.matchType, seasonId: qs.seasonId, opponentOuid: o.opponentOuid })
              .catch(function () { return []; }),
            // 이 상대전 평균 득점/실점 xG값용 — 실패해도 나머지 표시는 막지 않는다.
            apiGet('/api/v1/records/shot-heatmap',
              { ouid: qs.ouid, matchType: qs.matchType, seasonId: qs.seasonId, opponentOuid: o.opponentOuid, goalsOnly: false })
              .catch(function () { return { points: [] }; }),
            apiGet('/api/v1/records/conceded-shot-heatmap',
              { ouid: qs.ouid, matchType: qs.matchType, seasonId: qs.seasonId, opponentOuid: o.opponentOuid })
              .catch(function () { return { points: [] }; })
          ]).then(function (results) {
              var matches = results[0];
              var vsOpponentPlayers = results[1];
              var shotPoints = results[2].points;
              var concededPoints = results[3].points;
              inner.replaceChildren();
              if (!matches.length) {
                inner.appendChild(el('p', 'card-empty', '최근 경기 기록이 없습니다.'));
                return;
              }

              // 결과+스코어를 가장 먼저 가로로 나열 — 점유율/슈팅/패스/태클 등 나머지는
              // 클릭했을 때 뜨는 매치 상세 모달에서 본다(표로 늘어놓지 않는다).
              var matchCaption = el('p', 'card-caption', '전체 ' + fmt(matches.length) + '경기 · 클릭하면 상세 정보가 열립니다');
              inner.appendChild(matchCaption);
              var chipRow = el('div', 'match-chip-row');
              matches.forEach(function (m) {
                var withName = {};
                for (var k in m) withName[k] = m[k];
                withName.opponentNickname = o.opponentNickname;
                withName.opponentOuid = o.opponentOuid; // MOM/Worst 양팀 합산에 필요(openMatchModal)
                var chip = el('div', 'match-chip chip result-' + m.result,
                  m.result + ' (' + m.goalsFor + ':' + m.goalsAgainst + ')');
                chip.tabIndex = 0;
                chip.setAttribute('role', 'button');
                chip.setAttribute('aria-label', 'vs ' + o.opponentNickname + ' 경기 상세 보기');
                var openFn = function () { openMatchModal(withName); };
                chip.addEventListener('click', openFn);
                chip.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openFn(); } });
                chipRow.appendChild(chip);
              });
              inner.appendChild(chipRow);

              // 평균 득실점 / xG값 / 유효슈팅 비율 — 이 상대와의 전체 경기 기준.
              var n = matches.length;
              var sum = function (key) { return matches.reduce(function (s, m) { return s + (m[key] || 0); }, 0); };
              var goalsFor = sum('goalsFor'), goalsAgainst = sum('goalsAgainst');
              var shootTotal = sum('shootTotal'), effectiveShoot = sum('effectiveShoot');
              var expectedGoalsFor = expectedGoalsOf(shotPoints);
              var expectedGoalsAgainst = expectedGoalsOf(concededPoints);
              var summaryGrid = el('div', 'stat-mini-grid opp-summary-grid');
              statMini(summaryGrid, '평균 득점', fmt1(goalsFor / n), '경기당 실제 득점');
              statMini(summaryGrid, '평균 득점 xG값', fmt1(expectedGoalsFor / n), '경기당 기대 득점');
              statMini(summaryGrid, '평균 실점', fmt1(goalsAgainst / n), '경기당 실제 실점');
              statMini(summaryGrid, '평균 실점 xG값', fmt1(expectedGoalsAgainst / n), '경기당 기대 실점');
              statMini(summaryGrid, '평균 슈팅', fmt1(effectiveShoot / n) + ' / ' + fmt1(shootTotal / n), '유효 / 전체');
              statMini(summaryGrid, '유효슈팅 비율', shootTotal > 0 ? pctOf(effectiveShoot, shootTotal) + '%' : '-',
                '총 ' + fmt(effectiveShoot) + '회');
              inner.appendChild(summaryGrid);

              // 이 상대전 선수 기여도 TOP3.
              var top3Wrap = el('div', 'opp-top3-wrap');
              [
                ['⚽ 이 상대전 최다 득점 TOP3', function (p) { return p.goals; }, '골'],
                ['👟 이 상대전 최다 도움 TOP3', function (p) { return p.assists; }, '도움'],
                ['🧤 이 상대전 최다 선방 TOP3', function (p) { return p.saves; }, '선방'],
                ['🛡️ 이 상대전 수비의 핵 TOP3', function (p) { return p.tackles + p.intercepts + p.blocks; }, '회 차단']
              ].forEach(function (spec) {
                var section = el('div', 'opp-top3-section');
                section.appendChild(el('p', 'card-caption', spec[0]));
                var list = el('div', 'top3-list');
                topPlayersList(list, vsOpponentPlayers, spec[1], spec[2]);
                section.appendChild(list);
                top3Wrap.appendChild(section);
              });
              inner.appendChild(top3Wrap);
            })
            .catch(function () {
              loaded = false;
              inner.replaceChildren();
              inner.appendChild(el('p', 'card-empty', '불러오지 못했습니다 — 행을 다시 눌러 재시도해 주세요.'));
            });
        }
      };
      tr.addEventListener('click', toggle);
      tr.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); } });

      tbody.appendChild(tr);
      tbody.appendChild(expandTr);
    });
    table.appendChild(tbody);
    container.appendChild(table);
  }

  // ---------------- 최근 경기 (상대 무관, 더보기 페이징) ----------------
  var recentMoreBtn = document.getElementById('recent-more-btn');
  var recentMoreBtnLabel = recentMoreBtn.textContent;
  var recentMatchesState = { page: 0, size: 10, hasMore: false, loading: false };

  function setMoreBtnLoading(loading) {
    recentMoreBtn.replaceChildren();
    if (loading) {
      var spin = el('span', 'btn-spinner');
      spin.setAttribute('aria-hidden', 'true');
      recentMoreBtn.appendChild(spin);
      recentMoreBtn.appendChild(document.createTextNode('불러오는 중…'));
    } else {
      recentMoreBtn.appendChild(document.createTextNode(recentMoreBtnLabel));
    }
  }

  function recentTableBody() {
    var container = document.getElementById('table-recent');
    var existing = container.querySelector('table');
    if (existing) return existing.querySelector('tbody');
    container.replaceChildren();
    var table = document.createElement('table');
    var thead = document.createElement('thead');
    var htr = document.createElement('tr');
    ['날짜', '상대', '결과', '스코어', '점유율', '슈팅', '패스 성공', '태클 성공'].forEach(function (h, i) {
      htr.appendChild(el('th', i >= 3 ? 'num' : '', h));
    });
    thead.appendChild(htr);
    table.appendChild(thead);
    var tbody = document.createElement('tbody');
    table.appendChild(tbody);
    container.appendChild(table);
    return tbody;
  }

  function loadRecentMatches(reset) {
    if (recentMatchesState.loading) return Promise.resolve();
    if (reset) recentMatchesState.page = 0;
    recentMatchesState.loading = true;
    recentMoreBtn.disabled = true;
    setMoreBtnLoading(true);
    var seq = loadSeq;
    return apiGet('/api/v1/records/recent-matches', {
      ouid: state.ouid, matchType: state.matchType, seasonId: state.seasonId,
      page: recentMatchesState.page, size: recentMatchesState.size
    }).then(function (result) {
      recentMatchesState.loading = false;
      recentMoreBtn.disabled = false;
      setMoreBtnLoading(false);
      if (seq !== loadSeq) return; // 응답 도착 전에 선택이 또 바뀐 경우 — 낡은 응답은 버린다

      var container = document.getElementById('table-recent');
      if (reset) {
        container.replaceChildren();
        if (!result.content.length) {
          container.appendChild(el('p', 'card-empty', '최근 경기 기록이 없습니다.'));
          recentMoreBtn.hidden = true;
          return;
        }
      }
      var tbody = recentTableBody();
      result.content.forEach(function (m) { tbody.appendChild(buildMatchRow(m, true)); });
      recentMatchesState.hasMore = (result.number + 1) * result.size < result.totalElements;
      recentMatchesState.page = result.number + 1;
      recentMoreBtn.hidden = !recentMatchesState.hasMore;
    }).catch(function () {
      recentMatchesState.loading = false;
      recentMoreBtn.disabled = false;
      setMoreBtnLoading(false);
      if (seq !== loadSeq) return;
      recentMoreBtn.hidden = true;
      if (reset) {
        var container = document.getElementById('table-recent');
        container.replaceChildren();
        container.appendChild(el('p', 'card-empty', '최근 경기를 불러오지 못했습니다.'));
      }
    });
  }

  recentMoreBtn.addEventListener('click', function () { loadRecentMatches(false); });

  /**
   * 백엔드는 raw 합계만 주고, 비율/100점 만점 점수는 여기서 계산한다 — "1등이 100점"이 되려면
   * 그룹(현재 유저·매치타입·시즌의 전체 선수) 안에서의 최댓값을 알아야 하는데 그건 이 목록이
   * 다 모여야 알 수 있어서다.
   *
   * 종합 = (골×3 + 도움×2 + (태클+인터셉트+블록+세이브)×0.5)를 최댓값 100점 기준으로 재조정.
   * 공격력/수비력은 그 자체로 요청받은 정식 지표가 아니라, 종합과 같은 방식(그룹 내 최댓값=100)으로
   * 계산한 별도의 참고용 합성 점수다 — 공격력은 골/도움/유효슛/드리블 성공, 수비력은
   * 태클/인터셉트/블록/공중볼 성공에 가중치를 둔다.
   */
  function enrichPlayers(players) {
    var maxScore = 0, maxAttack = 0, maxDefense = 0;
    var enriched = players.map(function (p) {
      var copy = {};
      for (var k in p) copy[k] = p[k];
      copy.attackPoints = p.goals + p.assists;
      copy.finishing = p.goals - p.xg; // 결정력 = 실제 득점 − xG값(백엔드가 선수별 슛 좌표로 합산)
      copy.shootAccuracy = p.shootTotal > 0 ? (p.effectiveShoot / p.shootTotal * 100) : null;
      copy.passAccuracy = p.passTry > 0 ? (p.passSuccess / p.passTry * 100) : null;
      copy.dribbleRate = p.dribbleTry > 0 ? (p.dribbleSuccess / p.dribbleTry * 100) : null;
      copy.aerialRate = p.aerialTry > 0 ? (p.aerialSuccess / p.aerialTry * 100) : null;
      copy.attackRaw = (p.goals * 3) + (p.assists * 2) + (p.effectiveShoot * 0.3) + (p.dribbleSuccess * 0.2);
      copy.defenseRaw = p.tackles + p.intercepts + p.blocks + (p.aerialSuccess * 0.3);
      if (p.contributionScore > maxScore) maxScore = p.contributionScore;
      if (copy.attackRaw > maxAttack) maxAttack = copy.attackRaw;
      if (copy.defenseRaw > maxDefense) maxDefense = copy.defenseRaw;
      return copy;
    });
    enriched.forEach(function (p) {
      p.overall = maxScore > 0 ? (p.contributionScore / maxScore * 100) : 0;
      p.attackRating = maxAttack > 0 ? (p.attackRaw / maxAttack * 100) : 0;
      p.defenseRating = maxDefense > 0 ? (p.defenseRaw / maxDefense * 100) : 0;
    });
    return enriched;
  }

  /**
   * 선수 기여도 TOP7의 보조 라벨 — 이 선수의 가장 두드러진 지표군을 보여준다.
   * 세이브가 가장 크면(골키퍼 프로필) 선방만, 수비 지표(태클+인터셉트)가 공격 지표(골+어시)보다
   * 크면 태클·인터셉트를, 그 외(공격 지표가 두드러지는 필드 플레이어)엔 득점·도움을 보여준다.
   */
  function playerRoleSub(p) {
    var attackSum = p.goals + p.assists;
    var defenseSum = p.tackles + p.intercepts;
    if (p.saves > 0 && p.saves >= attackSum && p.saves >= defenseSum) {
      return '선방 ' + fmt(p.saves);
    }
    if (defenseSum > attackSum) {
      return '태클 ' + fmt(p.tackles) + ' · 인터셉트 ' + fmt(p.intercepts);
    }
    return '득점 ' + fmt(p.goals) + ' · 도움 ' + fmt(p.assists);
  }

  var currentPlayersList = [];
  function renderPlayersGrid(players) {
    currentPlayersList = players;
    var container = document.getElementById('table-allplayers');
    container.replaceChildren();
    if (!players.length) { container.appendChild(el('p', 'card-empty', '표시할 선수 데이터가 없습니다.')); return; }

    var cols = [
      { key: 'playerName', label: '선수', numeric: false },
      { key: 'appearances', label: '출전', numeric: true },
      { key: 'attackRating', label: '공격력', numeric: true },
      { key: 'defenseRating', label: '수비력', numeric: true },
      { key: 'goals', label: '골', numeric: true },
      { key: 'assists', label: '도움', numeric: true },
      { key: 'attackPoints', label: '공격P', numeric: true },
      { key: 'xg', label: 'xG', numeric: true },
      { key: 'finishing', label: '결정력', numeric: true },
      { key: 'shootTotal', label: '총슈팅', numeric: true },
      { key: 'effectiveShoot', label: '유효슈팅', numeric: true },
      { key: 'shootAccuracy', label: '슛정확', numeric: true },
      { key: 'passAccuracy', label: '패스', numeric: true },
      { key: 'passTry', label: '패스시도', numeric: true },
      { key: 'passSuccess', label: '패스성공', numeric: true },
      { key: 'dribbleRate', label: '드리블', numeric: true },
      { key: 'dribbleTry', label: '드리블시도', numeric: true },
      { key: 'dribbleSuccess', label: '드리블성공', numeric: true },
      { key: 'dribbleDistance', label: '드리블거리', numeric: true },
      { key: 'aerialRate', label: '공중볼', numeric: true },
      { key: 'aerialTry', label: '공중볼시도', numeric: true },
      { key: 'aerialSuccess', label: '공중볼성공', numeric: true },
      { key: 'tackles', label: '태클', numeric: true },
      { key: 'intercepts', label: '인터셉트', numeric: true },
      { key: 'blocks', label: '블록', numeric: true },
      { key: 'avgRating', label: '평점', numeric: true },
      { key: 'overall', label: '종합', numeric: true }
    ];

    var sorted = players.slice().sort(function (a, b) {
      var av = a[playersGridSort.col], bv = b[playersGridSort.col];
      var cmp;
      if (typeof av === 'string') {
        cmp = av.localeCompare(bv, 'ko');
      } else {
        // 슛정확/패스/드리블/공중볼은 시도가 0건이면 null — 정렬 시엔 맨 뒤로 보낸다.
        cmp = (av == null ? -1 : av) - (bv == null ? -1 : bv);
      }
      return playersGridSort.dir === 'asc' ? cmp : -cmp;
    });

    var table = document.createElement('table');
    var thead = document.createElement('thead');
    var htr = document.createElement('tr');
    cols.forEach(function (c) {
      var th = el('th', c.numeric ? 'num sortable' : 'sortable');
      th.tabIndex = 0;
      th.setAttribute('role', 'button');
      th.setAttribute('aria-sort', playersGridSort.col === c.key ? (playersGridSort.dir === 'asc' ? 'ascending' : 'descending') : 'none');
      var label = c.label + (playersGridSort.col === c.key ? (playersGridSort.dir === 'asc' ? ' ▲' : ' ▼') : '');
      th.textContent = label;
      var sortFn = function () {
        if (playersGridSort.col === c.key) {
          playersGridSort.dir = playersGridSort.dir === 'asc' ? 'desc' : 'asc';
        } else {
          playersGridSort.col = c.key;
          playersGridSort.dir = c.numeric ? 'desc' : 'asc';
        }
        renderPlayersGrid(currentPlayersList);
      };
      th.addEventListener('click', sortFn);
      th.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); sortFn(); }
      });
      htr.appendChild(th);
    });
    thead.appendChild(htr);
    table.appendChild(thead);

    function pct(v) { return v == null ? '-' : Math.round(v) + '%'; }

    var tbody = document.createElement('tbody');
    sorted.forEach(function (p) {
      var tr = document.createElement('tr');
      var nameTd = el('td', 'name-cell');
      nameTd.appendChild(playerNameBadge(p.spId, p.playerName));
      tr.appendChild(nameTd);
      tr.appendChild(el('td', 'num', fmt(p.appearances)));
      tr.appendChild(el('td', 'num', fmt(Math.round(p.attackRating))));
      tr.appendChild(el('td', 'num', fmt(Math.round(p.defenseRating))));
      tr.appendChild(el('td', 'num', fmt(p.goals)));
      tr.appendChild(el('td', 'num', fmt(p.assists)));
      tr.appendChild(el('td', 'num', fmt(p.attackPoints)));
      tr.appendChild(el('td', 'num', fmt1(p.xg)));
      tr.appendChild(el('td', 'num', (p.finishing >= 0 ? '+' : '') + fmt1(p.finishing)));
      tr.appendChild(el('td', 'num', fmt(p.shootTotal)));
      tr.appendChild(el('td', 'num', fmt(p.effectiveShoot)));
      tr.appendChild(el('td', 'num', pct(p.shootAccuracy)));
      tr.appendChild(el('td', 'num', pct(p.passAccuracy)));
      tr.appendChild(el('td', 'num', fmt(p.passTry)));
      tr.appendChild(el('td', 'num', fmt(p.passSuccess)));
      tr.appendChild(el('td', 'num', pct(p.dribbleRate)));
      tr.appendChild(el('td', 'num', fmt(p.dribbleTry)));
      tr.appendChild(el('td', 'num', fmt(p.dribbleSuccess)));
      tr.appendChild(el('td', 'num', fmt(p.dribbleDistance)));
      tr.appendChild(el('td', 'num', pct(p.aerialRate)));
      tr.appendChild(el('td', 'num', fmt(p.aerialTry)));
      tr.appendChild(el('td', 'num', fmt(p.aerialSuccess)));
      tr.appendChild(el('td', 'num', fmt(p.tackles)));
      tr.appendChild(el('td', 'num', fmt(p.intercepts)));
      tr.appendChild(el('td', 'num', fmt(p.blocks)));
      tr.appendChild(el('td', 'num', p.avgRating == null ? '-' : fmt1(p.avgRating)));
      tr.appendChild(el('td', 'num', fmt(Math.round(p.overall))));
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    container.appendChild(table);
  }

  document.getElementById('players-more-btn').addEventListener('click', function () {
    var card = document.getElementById('players-grid-card');
    card.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'start' });
    card.classList.remove('grid-flash');
    void card.offsetWidth; // 리플로우 강제 — 같은 버튼 연타해도 애니메이션이 다시 재생되게 한다
    card.classList.add('grid-flash');
  });
  // ---------------- 근사 xG (거리·각도 로지스틱 회귀) ----------------
  // 이전 버전은 전 유저 슈팅 표본을 모아 "이 구역에서 실제로 골이 난 비율"을 쓰는 경험적 방식이었다
  // (표본을 모으는 API 호출이 여러 건 필요했고, 표본이 도착하기 전까진 "계산 중…"으로 표시됨).
  // 지금은 골대까지 거리·시야각만으로 계산하는 순수 함수라 표본 수집이 필요 없고 항상 즉시 나온다.
  var PITCH_LENGTH_M = 105.0;
  var PITCH_WIDTH_M = 68.0;
  var GOAL_WIDTH_M = 7.32;
  var GOAL_Y_MIN_M = (PITCH_WIDTH_M - GOAL_WIDTH_M) / 2;
  var GOAL_Y_MAX_M = (PITCH_WIDTH_M + GOAL_WIDTH_M) / 2;
  var GOAL_CENTER_Y_M = PITCH_WIDTH_M / 2;

  /**
   * 정규화 좌표(x,y ∈ [0,1], x=1이 상대 골대 방향)를 실제 미터 좌표(105×68)로 환산해 골대까지
   * 거리와 골대를 바라보는 시야각(양쪽 골포스트까지의 거리로 코사인 법칙 적용)을 구하고,
   * 거리·각도 로지스틱 회귀 근사식(logit = 0.5 − 0.15×거리 + 0.05×각도)에 넣는다. 정식 xG
   * 모델은 아니다 — 수비수 배치·압박·패스 난이도 등은 반영하지 않는 거리·각도만의 근사치.
   */
  function calcXg(x, y) {
    if (x == null || y == null) return null;
    var xm = x * PITCH_LENGTH_M;
    var ym = y * PITCH_WIDTH_M;
    var dist = Math.sqrt(Math.pow(PITCH_LENGTH_M - xm, 2) + Math.pow(GOAL_CENTER_Y_M - ym, 2));
    var d1 = Math.sqrt(Math.pow(PITCH_LENGTH_M - xm, 2) + Math.pow(GOAL_Y_MIN_M - ym, 2));
    var d2 = Math.sqrt(Math.pow(PITCH_LENGTH_M - xm, 2) + Math.pow(GOAL_Y_MAX_M - ym, 2));
    var cosAngle = (d1 * d1 + d2 * d2 - GOAL_WIDTH_M * GOAL_WIDTH_M) / (2 * d1 * d2);
    cosAngle = Math.max(-1, Math.min(1, cosAngle)); // 부동소수 오차로 [-1,1] 살짝 벗어나는 것 방지
    var angleDeg = Math.acos(cosAngle) * (180 / Math.PI);
    var logit = 0.5 - 0.15 * dist + 0.05 * angleDeg;
    return 1 / (1 + Math.exp(-logit));
  }

  /**
   * "슈팅 위치 & 실제 xG값" 카드 — 내 슈팅은 우측(상대 골대 방향), 실점(상대가 쏜 슛)은
   * 180도 반전(x,y 모두 1-값)해서 좌측(내 골대 방향)에 함께 그린다.
   */
  function renderShotPitch(points, concededPoints) {
    var actualGoalsNow = points.filter(function (p) { return p.goal; }).length;
    var concededGoalsNow = concededPoints.filter(function (p) { return p.goal; }).length;
    document.getElementById('heatmap-caption').textContent =
      '내 슈팅 ' + points.length + '건 중 득점 ' + actualGoalsNow + '건(우측, 상대 골대 방향) · ' +
      '실점 슈팅 ' + concededPoints.length + '건 중 실점 ' + concededGoalsNow + '건(좌측, 내 골대 방향)';
    var shotsForPitch = points.map(function (p) {
      var withXg = {};
      for (var k in p) withXg[k] = p[k];
      withXg.xg = calcXg(p.x, p.y);
      withXg.mine = true;
      return withXg;
    }).concat(concededPoints.map(function (p) {
      return {
        x: p.x != null ? 1 - p.x : null, y: p.y != null ? 1 - p.y : null,
        goal: p.goal, shootType: p.shootType, result: p.result,
        xg: calcXg(p.x, p.y),
        mine: false
      };
    })).filter(function (p) { return p.x != null && p.y != null; });
    pitchHeatmap(document.getElementById('chart-heatmap'), shotsForPitch);
  }

  function updateXgTile(points) {
    var valueEl = document.getElementById('xg-tile-value');
    var subEl = document.getElementById('xg-tile-sub');
    if (!valueEl) return;
    var actualGoals = points.filter(function (p) { return p.goal; }).length;
    var expectedGoals = expectedGoalsOf(points);
    var diff = actualGoals - expectedGoals;
    valueEl.textContent = actualGoals + ' : ' + fmt1(expectedGoals);
    subEl.textContent = (diff > 0 ? '+' : '') + fmt1(diff) + (diff >= 0 ? ' 기대 이상 마무리' : ' 기대 이하 마무리');
    subEl.style.color = diff >= 0 ? 'var(--success-text)' : 'var(--status-critical)';
  }

  // ---------------- 플레이 성향 ----------------
  /**
   * tooltip은 문자열(1줄) 또는 문자열 배열(여러 줄) — 있으면 hover/focus 시 showTip으로 보여준다.
   * onClick을 주면 박스 자체도 버튼처럼 클릭/엔터 가능해진다(바이오리듬 스탯 박스를 눌러도
   * 그래프의 해당 축이 활성화되도록 하는 용도 — 그동안 아래 범례만 눌러야 했던 것 개선).
   */
  function statMini(container, label, value, sub, tooltip, onClick) {
    var box = el('div', 'stat-mini');
    box.appendChild(el('p', 'stat-mini-label', label));
    box.appendChild(el('div', 'stat-mini-value', value));
    if (sub) box.appendChild(el('div', 'stat-mini-sub', sub));
    if (tooltip) {
      var lines = Array.isArray(tooltip) ? tooltip : [tooltip];
      box.classList.add('stat-mini-hoverable');
      box.tabIndex = 0;
      var showFn = function (evt) { showTip(evt, lines); };
      box.addEventListener('pointerenter', showFn);
      box.addEventListener('pointermove', moveTip);
      box.addEventListener('pointerleave', hideTip);
      box.addEventListener('focus', showFn);
      box.addEventListener('blur', hideTip);
    }
    if (onClick) {
      box.classList.add('stat-mini-clickable');
      box.tabIndex = 0;
      box.setAttribute('role', 'button');
      box.addEventListener('click', onClick);
      box.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick(); }
      });
    }
    container.appendChild(box);
    return box;
  }

  /**
   * players(TopPlayerResponse[])에서 statFn 기준 상위 3명을 뽑아 렌더링한다 — "상대별 전적"
   * 행을 펼쳤을 때 그 상대전 최다 득점/도움/선방/수비 TOP3를 보여주는 용도.
   */
  function topPlayersList(container, players, statFn, unit) {
    container.replaceChildren();
    var ranked = players
      .map(function (p) { return { p: p, val: statFn(p) }; })
      .filter(function (r) { return r.val > 0; })
      .sort(function (a, b) { return b.val - a.val; })
      .slice(0, 3);
    if (!ranked.length) { container.appendChild(el('p', 'card-empty', '기록 없음')); return; }
    ranked.forEach(function (r, i) {
      var row = el('div', 'top3-row');
      row.appendChild(el('span', 'top3-rank', (i + 1) + '.'));
      row.appendChild(playerNameBadge(r.p.spId, r.p.playerName));
      var avg = r.p.appearances > 0 ? round1(r.val / r.p.appearances) : 0;
      row.appendChild(el('span', 'top3-stat', r.p.appearances + '경기 ' + fmt(r.val) + unit + ' (평균 ' + avg + ')'));
      container.appendChild(row);
    });
  }

  function pctOf(count, total) {
    return total > 0 ? Math.round((count / total) * 100) : 0;
  }

  function expectedGoalsOf(points) {
    var sum = 0;
    points.forEach(function (p) {
      var r = calcXg(p.x, p.y);
      if (r != null) sum += r;
    });
    return sum;
  }

  /** 매치별 xG값 추이 라인차트용 — 슛 포인트를 matchId로 묶어 매치당 xG값 합을 낸다. */
  function groupExpectedGoalsByMatch(points) {
    var byMatch = {};
    points.forEach(function (p) {
      var r = calcXg(p.x, p.y);
      if (r == null) return;
      byMatch[p.matchId] = (byMatch[p.matchId] || 0) + r;
    });
    return byMatch;
  }

  function round1(n) { return Math.round(n * 10) / 10; }

  function renderPlayStyle(overall, points, totalGames, concededPoints, concededSampleGames, matches) {
    var attackContainer = document.getElementById('playstyle-attack');
    var defenseContainer = document.getElementById('playstyle-defense');
    var passContainer = document.getElementById('playstyle-pass');
    var dirtyContainer = document.getElementById('playstyle-dirty');
    var possContainer = document.getElementById('playstyle-possession');
    var attackChart = document.getElementById('chart-playstyle-attack');
    var defenseChart = document.getElementById('chart-playstyle-defense');
    var passChart = document.getElementById('chart-playstyle-pass');
    var possChart = document.getElementById('chart-playstyle-possession');
    attackContainer.replaceChildren();
    defenseContainer.replaceChildren();
    passContainer.replaceChildren();
    dirtyContainer.replaceChildren();
    possContainer.replaceChildren();

    if (!totalGames) {
      document.getElementById('playstyle-caption').textContent = '표시할 경기가 없습니다.';
      attackChart.replaceChildren();
      defenseChart.replaceChildren();
      passChart.replaceChildren();
      document.getElementById('defense-trend-caption').textContent = '';
      return;
    }
    document.getElementById('playstyle-caption').textContent =
      totalGames + '경기 표본 기준';

    matches = matches || [];
    concededPoints = concededPoints || [];
    concededSampleGames = concededSampleGames || 0;

    // 공격 성향
    var actualGoals = points.filter(function (p) { return p.goal; }).length;
    var expectedGoals = expectedGoalsOf(points);
    var onTarget = points.filter(function (p) { return p.result !== 'OFF_TARGET'; }).length;
    var shotAccuracy = points.length ? (onTarget / points.length * 100) : null;

    // 서브텍스트에 총 수량을 작은 글씨로 같이 보여준다(요청) — 패스 성향 탭과 같은 방식.
    statMini(attackContainer, '평균 득점', fmt1(overall.tally.goalsFor / totalGames), '경기당 실제 득점 · 총 ' + fmt(overall.tally.goalsFor) + '골');
    statMini(attackContainer, '평균 득점 xG값', fmt1(expectedGoals / totalGames), '경기당 기대 득점 · 총 ' + fmt1(expectedGoals) + '골');
    statMini(attackContainer, '결정력',
      (actualGoals - expectedGoals >= 0 ? '+' : '') + fmt1(actualGoals - expectedGoals),
      '실제 득점 − xG값 (양수면 기대 이상)');
    statMini(attackContainer, '슈팅 정확도', shotAccuracy == null ? '-' : Math.round(shotAccuracy) + '%',
      '유효슛 비율 · 총 ' + fmt(points.length) + '슈팅');
    statMini(attackContainer, '평균 평점', fmt1(overall.averageRating), '팀 스쿼드 평균');
    statMini(attackContainer, '경기당 슈팅', fmt1(points.length / totalGames), '표본 전체 평균 · 총 ' + fmt(points.length) + '슈팅');

    // 수비 성향 — "평균 실점 xG값"/"상대 결정력"은 상대도 추적 대상 유저인 매치만 반영된다.
    // 상대 결정력은 평균 실점 xG값 바로 다음에 나오도록 순서 조정(요청).
    var concededExpectedGoals = expectedGoalsOf(concededPoints);
    var concededActualGoals = concededPoints.filter(function (p) { return p.goal; }).length;
    statMini(defenseContainer, '평균 실점', fmt1(overall.tally.goalsAgainst / totalGames), '경기당 실제 실점 · 총 ' + fmt(overall.tally.goalsAgainst) + '골');
    statMini(defenseContainer, '평균 실점 xG값',
      concededSampleGames ? fmt1(concededExpectedGoals / concededSampleGames) : '-',
      concededSampleGames ? concededSampleGames + '경기 표본 · 총 ' + fmt1(concededExpectedGoals) + '골' : '데이터 없음');
    statMini(defenseContainer, '상대 결정력',
      concededSampleGames
        ? (concededActualGoals - concededExpectedGoals >= 0 ? '+' : '') + fmt1(concededActualGoals - concededExpectedGoals)
        : '-',
      '상대 실제 득점 − 실점 xG값 (양수면 상대가 기대 이상)');
    statMini(defenseContainer, '클린시트', fmt(overall.cleanSheets) + '경기', pctOf(overall.cleanSheets, totalGames) + '%');
    statMini(defenseContainer, '다실점 경기(3실점↑)', fmt(overall.multiConcededGames) + '경기', pctOf(overall.multiConcededGames, totalGames) + '%');
    // 태클/블락 시도-성공(매치 단위 팀 합계, MatchStats 기반) — 성공률도 같이.
    var tackleRate = overall.tackleTryTotal ? Math.round(overall.tackleSuccessTotal / overall.tackleTryTotal * 100) + '%' : '-';
    var blockRate = overall.blockTryTotal ? Math.round(overall.blockSuccessTotal / overall.blockTryTotal * 100) + '%' : '-';
    statMini(defenseContainer, '태클 시도/성공', fmt(overall.tackleTryTotal) + ' / ' + fmt(overall.tackleSuccessTotal), '성공률 ' + tackleRate);
    statMini(defenseContainer, '블락 시도/성공', fmt(overall.blockTryTotal) + ' / ' + fmt(overall.blockSuccessTotal), '성공률 ' + blockRate);
    statMini(defenseContainer, '표본', fmt(totalGames) + '경기', '이번 조회 기준');

    // "더티 성향" — 수비 성향이 아니라 별도 탭(요청): 게임 일시정지·파울·경고·퇴장.
    statMini(dirtyContainer, '게임 일시정지', fmt(overall.systemPauseTotal) + '회', '표본 전체 합계');
    statMini(dirtyContainer, '파울', fmt(overall.foulTotal) + '회', '표본 전체 합계');
    statMini(dirtyContainer, '경고(옐로카드)', fmt(overall.yellowCards) + '장', '표본 전체 합계');
    statMini(dirtyContainer, '퇴장(레드카드)', fmt(overall.redCards) + '장', '표본 전체 합계');

    // 패스 성향 — 매치 단위 팀 합계(MatchStats, 전체/숏/롱 패스 각각 시도-성공-성공률)를 경기당
    // 평균으로 보여주고(요청, 다른 탭과 통일감), 표본 전체 합계는 작은 글씨 서브텍스트로만 곁들인다.
    // 미스(시도-성공)는 성공률과 중복 정보라 빼고, 대신 숏/롱 세부 유형을 보여준다.
    function passRateOf(t, s) { return t ? Math.round(s / t * 100) + '%' : '-'; }
    function passAvgMini(label, total, totalLabel) {
      statMini(passContainer, label, total != null ? fmt1(total / totalGames) : '-', '전체 ' + totalLabel + ' ' + fmt(total));
    }
    passAvgMini('평균 패스시도', overall.passTryTotal, '시도');
    passAvgMini('평균 패스성공', overall.passSuccessTotal, '성공');
    statMini(passContainer, '패스 성공률', passRateOf(overall.passTryTotal, overall.passSuccessTotal), '표본 전체 기준');
    passAvgMini('평균 숏패스시도', overall.shortPassTryTotal, '시도');
    passAvgMini('평균 숏패스성공', overall.shortPassSuccessTotal, '성공');
    statMini(passContainer, '숏패스 성공률', passRateOf(overall.shortPassTryTotal, overall.shortPassSuccessTotal), '표본 전체 기준');
    passAvgMini('평균 롱패스시도', overall.longPassTryTotal, '시도');
    passAvgMini('평균 롱패스성공', overall.longPassSuccessTotal, '성공');
    statMini(passContainer, '롱패스 성공률', passRateOf(overall.longPassTryTotal, overall.longPassSuccessTotal), '표본 전체 기준');

    // ---- 경기별 추이 라인차트 (최근 몇 경기가 아니라 표본 전체, 과거->최신 순) ----
    // API는 최신순으로 내려주므로 왼쪽(과거)->오른쪽(최신)이 되도록 뒤집는다.
    // x축은 날짜 대신 "몇 번째 매치인지"(1, 2, 3, ...) — 각 차트 자기 데이터 기준으로 센다.
    var chronological = matches.slice().reverse();
    function matchIndexLabels(n) {
      var labels = [];
      for (var i = 1; i <= n; i++) labels.push(String(i));
      return labels;
    }

    // 득점/실점 추이 — xG값 라인은 뺐다(요청). 실점은 이제 xG 복원 가능 여부와 무관하게(goalsAgainst는
    // 상대 추적 여부와 상관없이 항상 있는 값) 표본 전체 경기를 그대로 보여준다.
    lineChart(attackChart, [
      { label: '득점', color: 'var(--series-1)', values: chronological.map(function (m) { return m.goalsFor; }) }
    ], { labels: matchIndexLabels(chronological.length), unit: '골', yMin: 0, ariaLabel: '경기별 득점 추이' });

    document.getElementById('defense-trend-caption').textContent = '표본 전체 ' + chronological.length + '경기';
    lineChart(defenseChart, [
      { label: '실점', color: 'var(--series-2)', values: chronological.map(function (m) { return m.goalsAgainst; }) }
    ], { labels: matchIndexLabels(chronological.length), unit: '골', yMin: 0, ariaLabel: '경기별 실점 추이' });

    // 경기별 패스 시도 vs 성공 추이 — "최근 경기" 목록이 이미 매치당 passTry/passSuccess를 갖고 있다.
    lineChart(passChart, [
      { label: '패스 시도', color: 'var(--series-1)', values: chronological.map(function (m) { return m.passTry || 0; }) },
      { label: '패스 성공', color: 'var(--status-good)', values: chronological.map(function (m) { return m.passSuccess || 0; }) }
    ], { labels: matchIndexLabels(chronological.length), unit: '회', yMin: 0, ariaLabel: '경기별 패스 시도 대 성공 추이' });

    // 점유율 — 공격/수비 성향과 같은 stat-mini-grid + 추이 라인차트 구성.
    var high = overall.highPossessionGames || 0;
    var low = overall.lowPossessionGames || 0;
    var mid = Math.max(totalGames - high - low, 0);
    statMini(possContainer, '저점유(45%↓)', fmt(low) + '경기', pctOf(low, totalGames) + '%');
    statMini(possContainer, '균형(46~54%)', fmt(mid) + '경기', pctOf(mid, totalGames) + '%');
    statMini(possContainer, '고점유(55%↑)', fmt(high) + '경기', pctOf(high, totalGames) + '%');
    statMini(possContainer, '평균 점유율', Math.round(overall.possessionAverage) + '%', '표본 전체 평균');
    lineChart(possChart, [
      {
        label: '점유율', color: 'var(--series-3)',
        // possession=0%는 실제 값이 아니라 결측치로 본다(0%로 뛴 경기는 없다) — 50%(중립)로 보정.
        values: chronological.map(function (m) { return (m.possession != null && m.possession > 0) ? m.possession : 50; })
      }
    ], { labels: matchIndexLabels(chronological.length), unit: '%', yMin: 0, yMax: 100, refLines: [45, 55], ariaLabel: '점유율 추이' });
  }

  var BIORHYTHM_WINDOW = 5; // 이동평균 폭 — 값 자체는 시즌 전체 경기를 다 쓰고, 곡선만 부드럽게 다듬는 용도.
  var BIORHYTHM_RESULT_POINT = { '승': 3, '무': 1, '패': 0 };
  // 클릭해서 활성화하는 축 — 기본은 피지컬(요청), 유저/시즌을 바꿔도 선택이 유지되는 모듈 전역.
  var biorhythmActiveAxis = '피지컬';
  // "이 데이터가 왜 이런 값인지 모르겠다"는 피드백 — 각 축이 정확히 뭘 어떻게 계산한 값인지
  // 스탯 박스/범례에 hover하면 보이는 툴팁으로 설명한다.
  var BIORHYTHM_TOOLTIPS = {
    '피지컬': ['🏃 피지컬', '경기별 팀 평균 평점(10점 만점)을', BIORHYTHM_WINDOW + '경기 이동평균으로 부드럽게 만든 뒤,', '이번 시즌 표본 안 최저=0점·최고=100점으로', '상대 환산한 값이에요.'],
    '멘탈': ['🧠 멘탈', '경기 결과(승 3점·무 1점·패 0점)를', BIORHYTHM_WINDOW + '경기 이동평균으로 부드럽게 만든 뒤,', '이번 시즌 표본 안 최저=0점·최고=100점으로', '상대 환산한 값이에요. 최근 승리가 많을수록 올라가요.'],
    '지능': ['🎯 지능', '결정력(경기별 실제 득점 − xG값)을', BIORHYTHM_WINDOW + '경기 이동평균으로 부드럽게 만든 뒤,', '이번 시즌 표본 안 최저=0점·최고=100점으로', '상대 환산한 값이에요. 기대보다 골을 더 넣을수록 올라가요.'],
    '종합': ['🌊 종합 컨디션', '피지컬·멘탈·지능 3개 점수의 평균이에요.']
  };

  /** i번째까지의 최근 WINDOW경기 평균(앞부분은 있는 만큼만) — 시즌 시작부터 곡선이 나오게 확장형으로 처리. */
  /**
   * 확장형(경기 수가 WINDOW 미만인 초반엔 있는 만큼만 평균)이었을 때 버그가 있었다 —
   * 첫 1~4경기처럼 표본이 아주 적은 지점은 노이즈가 커서 우연히 시즌 최고/최저를 찍기 쉬운데,
   * 그 지점이 이후 biorhythmNormalize의 min/max 기준이 돼버려 시즌 전체가 그 노이즈 하나에
   * 눌려버렸다(예: 첫 경기 평점이 시즌 최고로 잡혀 이후 좋은 흐름도 전부 낮게 나옴).
   * WINDOW가 다 찬 지점부터만 계산해 이 왜곡을 없앤다 — 전체 경기 수가 WINDOW보다 적은
   * 유저는 그마저도 없어서 어쩔 수 없이 확장형으로 폴백한다.
   */
  function biorhythmRollingAvg(rawValues) {
    if (rawValues.length < BIORHYTHM_WINDOW) {
      return rawValues.map(function (_, i) {
        var slice = rawValues.slice(0, i + 1);
        return slice.reduce(function (s, v) { return s + v; }, 0) / slice.length;
      });
    }
    var result = [];
    for (var i = BIORHYTHM_WINDOW - 1; i < rawValues.length; i++) {
      var slice = rawValues.slice(i - BIORHYTHM_WINDOW + 1, i + 1);
      result.push(slice.reduce(function (s, v) { return s + v; }, 0) / slice.length);
    }
    return result;
  }

  /** 시즌 표본 안에서의 상대적 위치로 0~100 환산(최저=0, 최고=100) — 재미용 지표라 절대치보다 흐름이 중요하다. */
  function biorhythmNormalize(values) {
    var min = Math.min.apply(null, values);
    var max = Math.max.apply(null, values);
    if (max === min) return values.map(function () { return 50; });
    return values.map(function (v) { return Math.round(((v - min) / (max - min)) * 100); });
  }

  function biorhythmMoodLabel(score) {
    if (score >= 75) return '🔥 물올랐다';
    if (score >= 50) return '🙂 평범한 흐름';
    if (score >= 25) return '😐 살짝 침체기';
    return '🥶 바닥 찍는 중';
  }

  /**
   * "바이오리듬" — 실제 생년월일 기반 유사과학이 아니라, 시즌 전체 경기를 이동평균으로 다듬어
   * 피지컬(평점)/멘탈(승무패)/지능(결정력=득점−xG값) 3축의 상대적 컨디션 흐름을 보여주는 재미용 지표.
   * "최근 몇 경기"가 아니라 이번에 조회된 시즌의 전 경기(최대 TREND_SAMPLE_SIZE)를 표본으로 쓴다.
   */
  function renderBiorhythm(overall, matches, points, totalGames) {
    var summary = document.getElementById('biorhythm-summary');
    var caption = document.getElementById('biorhythm-caption');
    var chart = document.getElementById('chart-biorhythm');
    summary.replaceChildren();

    matches = matches || [];
    if (!totalGames || !matches.length) {
      caption.textContent = '표시할 경기가 없습니다.';
      chart.replaceChildren();
      return;
    }
    caption.textContent = '이번 시즌 전체 ' + matches.length + '경기 기준, ' + BIORHYTHM_WINDOW + '경기 이동평균으로 흐름만 부드럽게 봅니다(실제 능력치가 아니라 재미용 지표예요).';

    var chronological = matches.slice().reverse();
    var xgByMatch = groupExpectedGoalsByMatch(points);

    var physicalRaw = chronological.map(function (m) {
      return m.averageRating != null ? m.averageRating : (overall.averageRating != null ? overall.averageRating : 0);
    });
    var mentalRaw = chronological.map(function (m) {
      return BIORHYTHM_RESULT_POINT[m.result] != null ? BIORHYTHM_RESULT_POINT[m.result] : 0;
    });
    var intellectRaw = chronological.map(function (m) {
      return m.goalsFor - (xgByMatch[m.matchId] || 0);
    });

    var physical = biorhythmNormalize(biorhythmRollingAvg(physicalRaw));
    var mental = biorhythmNormalize(biorhythmRollingAvg(mentalRaw));
    var intellect = biorhythmNormalize(biorhythmRollingAvg(intellectRaw));

    var latestPhysical = physical[physical.length - 1];
    var latestMental = mental[mental.length - 1];
    var latestIntellect = intellect[intellect.length - 1];
    var overallScore = Math.round((latestPhysical + latestMental + latestIntellect) / 3);

    function matchIndexLabels(n) {
      var labels = [];
      for (var i = 1; i <= n; i++) labels.push(String(i));
      return labels;
    }

    // 차트는 "클릭한 축 하나만 활성화, 나머지는 흐리게" 방식(요청) — 기본은 피지컬, 스탯 박스
    // 자체를 눌러도(아래 범례뿐 아니라) 활성화되게 한다. "종합"을 누르면 특정 축 없이 전체를
    // 고르게 보여준다(activeLabel=null). biorhythmActiveAxis는 모듈 전역이라 유저/시즌을
    // 바꿔도 선택이 유지된다.
    function setActiveAxis(label) { biorhythmActiveAxis = label; drawBiorhythmChart(); }

    var physicalBox = statMini(summary, '🏃 피지컬', latestPhysical + '점', '평점 흐름 · 시즌 내 상대적 위치',
      BIORHYTHM_TOOLTIPS['피지컬'], function () { setActiveAxis('피지컬'); });
    var mentalBox = statMini(summary, '🧠 멘탈', latestMental + '점', '승무패 흐름 · 시즌 내 상대적 위치',
      BIORHYTHM_TOOLTIPS['멘탈'], function () { setActiveAxis('멘탈'); });
    var intellectBox = statMini(summary, '🎯 지능', latestIntellect + '점', '결정력(득점−xG값) 흐름 · 시즌 내 상대적 위치',
      BIORHYTHM_TOOLTIPS['지능'], function () { setActiveAxis('지능'); });
    var overallBox = statMini(summary, '🌊 종합 컨디션', overallScore + '점', biorhythmMoodLabel(overallScore),
      BIORHYTHM_TOOLTIPS['종합'], function () { setActiveAxis(null); });
    var axisBoxes = { '피지컬': physicalBox, '멘탈': mentalBox, '지능': intellectBox };
    var axisColors = { '피지컬': 'var(--series-1)', '멘탈': 'var(--series-2)', '지능': 'var(--series-3)' };
    Object.keys(axisBoxes).forEach(function (key) { axisBoxes[key].style.setProperty('--stat-active-color', axisColors[key]); });

    // biorhythmRollingAvg가 이제 WINDOW 미만 구간을 건너뛰어서(위 주석) physical/mental/intellect
    // 길이가 chronological보다 짧을 수 있다 — 라벨은 실제 배열 길이에 맞춘다.
    var labels = matchIndexLabels(physical.length);
    function drawBiorhythmChart() {
      Object.keys(axisBoxes).forEach(function (key) {
        axisBoxes[key].classList.toggle('stat-mini-active', key === biorhythmActiveAxis);
      });
      overallBox.classList.toggle('stat-mini-active', biorhythmActiveAxis == null);
      lineChart(chart, [
        { label: '피지컬', color: 'var(--series-1)', values: physical },
        { label: '멘탈', color: 'var(--series-2)', values: mental },
        { label: '지능', color: 'var(--series-3)', values: intellect }
      ], {
        labels: labels, unit: '점', yMin: 0, yMax: 100, refLines: [50],
        ariaLabel: '시즌 전체 바이오리듬 추이',
        activeLabel: biorhythmActiveAxis,
        legendTooltips: BIORHYTHM_TOOLTIPS,
        onLegendClick: setActiveAxis
      });
    }
    drawBiorhythmChart();
  }

  // ---------------- 선택 변경 시 데이터 로딩 ----------------
  var loadSeq = 0;
  function loadSelection() {
    if (!state.ouid || !state.seasonId) return Promise.resolve();
    var seq = ++loadSeq;
    var user = allUsers.filter(function (u) { return u.ouid === state.ouid; })[0];
    if (!user) return Promise.resolve();
    document.getElementById('page-title').textContent = user.nickname + ' — 매치 리포트';
    setStatus('데이터를 불러오는 중입니다…');

    var qs = { ouid: state.ouid, matchType: state.matchType, seasonId: state.seasonId };
    // 플레이 성향 추이 차트는 "최근 몇 경기"가 아니라 표본 전체를 쓴다 — 한 번에 크게 받아온다
    // (더보기 페이징을 쓰는 "최근 경기" 표와는 별개 용도).
    var TREND_SAMPLE_SIZE = 1000;
    return Promise.all([
      apiGet('/api/v1/records/overall', qs),
      apiGet('/api/v1/opponents', qs),
      apiGet('/api/v1/records/players', qs),
      // limit을 넉넉하게(100) 요청 — "합쳐서 TOP5"(양방향 조합 합산)를 정확히 계산하려면
      // 방향별 상위 몇 건만으론 부족할 수 있다(한쪽 방향이 밀려나면 합산이 과소해짐).
      apiGet('/api/v1/records/assist-chains', { ouid: qs.ouid, matchType: qs.matchType, seasonId: qs.seasonId, limit: 100 }),
      apiGet('/api/v1/records/shot-heatmap', { ouid: qs.ouid, matchType: qs.matchType, seasonId: qs.seasonId, goalsOnly: false }),
      apiGet('/api/v1/records/conceded-shot-heatmap', qs).catch(function () { return { points: [] }; }),
      apiGet('/api/v1/records/recent-matches', { ouid: qs.ouid, matchType: qs.matchType, seasonId: qs.seasonId, page: 0, size: TREND_SAMPLE_SIZE })
        .then(function (page) { return page.content; })
        .catch(function () { return []; }),
      // 카드 강화 단계 배지용 — 실패해도 나머지 화면 표시를 막으면 안 되니 조용히 빈 목록 폴백.
      apiGet('/api/v1/records/player-grades', qs).catch(function () { return []; })
    ]).then(function (r) {
      if (seq !== loadSeq) return; // 응답 도착 전에 선택이 또 바뀐 경우 — 낡은 응답은 버린다
      setStatus(null);
      playerGradeMap = {};
      r[7].forEach(function (g) { playerGradeMap[g.spId] = g.grade; });
      renderAll(user, {
        overall: r[0], opponents: r[1], allPlayers: r[2], assistChains: r[3],
        heatmap: r[4], concededHeatmap: r[5], matches: r[6]
      });
    }).catch(function (err) {
      if (seq !== loadSeq) return;
      setStatus('데이터를 불러오지 못했습니다. 새로고침해서 다시 시도해 주세요. (' + err.message + ')', true);
    });
  }

  // ---------------- main render ----------------
  function renderAll(user, d) {
    var overall = d.overall;

    var tiles = document.getElementById('tiles');
    tiles.replaceChildren();

    var wdlTile = el('div', 'tile');
    wdlTile.appendChild(el('p', 'tile-label', '전적 (승-무-패)'));
    var wdlRow = el('div', 'wdl-row');
    wdlRow.appendChild(el('span', 'wdl-chip win', overall.tally.win));
    wdlRow.appendChild(el('span', 'wdl-sep', '-'));
    wdlRow.appendChild(el('span', 'wdl-chip draw', overall.tally.draw));
    wdlRow.appendChild(el('span', 'wdl-sep', '-'));
    wdlRow.appendChild(el('span', 'wdl-chip lose', overall.tally.lose));
    wdlTile.appendChild(wdlRow);
    var totalGames = overall.tally.win + overall.tally.draw + overall.tally.lose;
    var winRate = totalGames ? Math.round((overall.tally.win / totalGames) * 100) : 0;
    wdlTile.appendChild(el('div', 'tile-sub', totalGames + '경기 · 승률 ' + winRate + '%'));
    tiles.appendChild(wdlTile);

    var goalTile = el('div', 'tile');
    goalTile.appendChild(el('p', 'tile-label', '득점 - 실점'));
    var diff = overall.tally.goalsFor - overall.tally.goalsAgainst;
    goalTile.appendChild(el('div', 'tile-value', overall.tally.goalsFor + ' : ' + overall.tally.goalsAgainst));
    goalTile.appendChild(el('div', 'tile-sub', '득실차 ' + (diff > 0 ? '+' : '') + diff));
    tiles.appendChild(goalTile);

    var avgGoalTile = el('div', 'tile');
    avgGoalTile.appendChild(el('p', 'tile-label', '경기당 득점 - 실점'));
    var avgGoalsFor = totalGames ? overall.tally.goalsFor / totalGames : 0;
    var avgGoalsAgainst = totalGames ? overall.tally.goalsAgainst / totalGames : 0;
    avgGoalTile.appendChild(el('div', 'tile-value', fmt1(avgGoalsFor) + ' : ' + fmt1(avgGoalsAgainst)));
    tiles.appendChild(avgGoalTile);

    var possTile = el('div', 'tile');
    possTile.appendChild(el('p', 'tile-label', '평균 점유율'));
    possTile.appendChild(el('div', 'tile-value', Math.round(overall.possessionAverage) + '%'));
    tiles.appendChild(possTile);

    var cardTile = el('div', 'tile');
    cardTile.appendChild(el('p', 'tile-label', '파울 / 경고 / 퇴장'));
    cardTile.appendChild(el('div', 'tile-value', overall.foulTotal));
    cardTile.appendChild(el('div', 'tile-sub', '옐로 ' + overall.yellowCards + ' · 레드 ' + overall.redCards));
    tiles.appendChild(cardTile);

    var xgTile = el('div', 'tile');
    xgTile.appendChild(el('p', 'tile-label', '실제 득점 vs 실제 xG값'));
    var xgValue = el('div', 'tile-value', '—'); xgValue.id = 'xg-tile-value';
    var xgSub = el('div', 'tile-sub', ''); xgSub.id = 'xg-tile-sub';
    xgTile.appendChild(xgValue);
    xgTile.appendChild(xgSub);
    tiles.appendChild(xgTile);

    // 선수 스탯 — raw 합계로부터 100점 만점 종합/공격력/수비력, 슛정확/패스/드리블/공중볼 %를 계산
    var enrichedPlayers = enrichPlayers(d.allPlayers);

    // top players — TOP 7 (전체 목록은 아래 그리드에서 더보기)
    var top7Rows = enrichedPlayers.slice()
      .sort(function (a, b) { return b.overall - a.overall; })
      .slice(0, 7)
      .map(function (p) {
        return { label: p.playerName, spId: p.spId, value: Math.round(p.overall), color: 'var(--series-1)', sub: playerRoleSub(p) };
      });
    barChart(document.getElementById('chart-players'), top7Rows, { unit: '점' });

    // goal type distribution
    var typeRows = overall.goalTypeDistribution
      .filter(function (t) { return t.count > 0; })
      .sort(function (a, b) { return b.count - a.count; })
      .map(function (t) { return { label: t.shootType, value: t.count, color: 'var(--series-1)' }; });
    barChart(document.getElementById('chart-goaltypes'), typeRows, { unit: '골' });

    // goal time distribution
    var timeRows = overall.goalTimeDistribution.map(function (t) { return { label: t.periodLabel, value: t.count }; });
    var concededTimeRows = (overall.concededGoalTimeDistribution || []).map(function (t) { return { label: t.periodLabel, value: t.count }; });
    divergingBarChart(document.getElementById('chart-goaltime'), timeRows, concededTimeRows);

    // 플레이 성향 (공격/수비 성향 + 점유율 분포)
    // "평균 실점 xG값"의 표본 경기 수 — conceded-shot-heatmap 포인트에 matchId가 있으니
    // 그 안의 고유 매치 수를 그대로 센다(= 상대도 추적 대상이라 실제로 슛 데이터를 복원한 경기 수).
    var concededMatchIdSet = {};
    d.concededHeatmap.points.forEach(function (p) { concededMatchIdSet[p.matchId] = true; });
    var concededSampleGames = Object.keys(concededMatchIdSet).length;

    renderPlayStyle(overall, d.heatmap.points, totalGames, d.concededHeatmap.points, concededSampleGames, d.matches);
    renderBiorhythm(overall, d.matches, d.heatmap.points, totalGames);

    // heatmap (전체 슈팅 + xG값) — 내 슈팅은 우측(상대 골대 방향), 실점(상대가 쏜 슛)은
    // 180도 반전해서 좌측(내 골대 방향)에 함께 표시한다(매치 상세 모달과 동일한 방식).
    renderShotPitch(d.heatmap.points, d.concededHeatmap.points);
    updateXgTile(d.heatmap.points);

    // 환상의 콤비 (어시스트 체인) — 상위 5건만
    assistTable(document.getElementById('table-assists'), d.assistChains.slice(0, 5));
    // 양방향 조합 합산 TOP5 (예: A→B + B→A를 한 조합으로 합산)
    assistDuoTable(document.getElementById('table-assist-duos'), topAssistDuos(d.assistChains, 5));

    // opponents (행 클릭 시 해당 상대 최근 경기를 그때 불러와 펼침) — 공식경기는 매치메이킹이라
    // "상대별 전적"이 의미가 없어(같은 상대를 다시 만날 일이 거의 없음) 커스텀에서만 보여준다.
    var opponentsCard = document.getElementById('opponents-card');
    opponentsCard.hidden = state.matchType === 'OFFICIAL';
    if (!opponentsCard.hidden) {
      opponentsTable(document.getElementById('table-opponents'), d.opponents);
    }

    // recent matches — 상대 무관, 이 유저의 진짜 최신 경기 (더보기 페이징)
    loadRecentMatches(true);

    // 전체 선수 스탯 그리드 — 유저/매치타입 바뀔 때마다 정렬 상태는 초기화(종합 desc)
    playersGridSort = { col: 'overall', dir: 'desc' };
    renderPlayersGrid(enrichedPlayers);
  }

  init();
  loadVisitorBadge();
})();
