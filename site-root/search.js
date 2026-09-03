(function () {
  'use strict';

  var BASE_URL = 'https://fconlinev2-backend.onrender.com';

  // ---------------- 공용 헬퍼 (report.js와 동일 — 이 사이트는 빌드 도구가 없어 파일 간 공유가
  // 안 돼서 각 페이지가 자기 몫만큼 복사해서 쓴다. report.js를 고칠 때 여기도 같이 봐야 하는
  // 함수는 각자 주석에 표시해뒀다) ----------------

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }

  function fmt(n) { return Number(n).toLocaleString('ko-KR'); }
  function fmt1(n) { return Number(n).toFixed(1); }

  function fmtDateTime(d) {
    return d.toLocaleDateString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit' }) + ' ' +
      d.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false });
  }

  function apiGet(path, params) {
    var url = new URL(path, BASE_URL);
    if (params) {
      Object.keys(params).forEach(function (k) {
        var v = params[k];
        if (v !== undefined && v !== null) url.searchParams.set(k, v);
      });
    }
    return fetch(url.toString()).then(function (res) {
      if (!res.ok) {
        return res.json().catch(function () { return null; }).then(function (body) {
          throw new Error(body && body.message ? body.message : ('API 요청 실패 (HTTP ' + res.status + ')'));
        });
      }
      return res.json();
    });
  }

  // report.js의 seasonMetaOfSpId/playerNameBadge와 동일(카드 등급 강화 배지만 뺐다 — 검색 대상은
  // player-grades API가 없어서 어차피 계산할 방법이 없다).
  function seasonMetaOfSpId(spId) {
    var n = Number(spId);
    if (!n || typeof SEASON_META === 'undefined') return null;
    var meta = SEASON_META[Math.floor(n / 1000000)];
    return meta ? { name: meta[0], img: meta[1] } : null;
  }

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
    return wrap;
  }

  // report.js의 calcXg와 반드시 같은 공식이어야 한다(ExpectedGoalsCalculatorTest 골든 테스트가
  // Java 쪽 동기화는 검증하지만, 이 세 번째 사본은 그 테스트 대상이 아니다 — report.js를 고치면
  // 여기도 손으로 맞춰야 한다).
  var PITCH_LENGTH_M = 105.0, PITCH_WIDTH_M = 68.0, GOAL_WIDTH_M = 7.32;
  var GOAL_Y_MIN_M = (PITCH_WIDTH_M - GOAL_WIDTH_M) / 2;
  var GOAL_Y_MAX_M = (PITCH_WIDTH_M + GOAL_WIDTH_M) / 2;
  var GOAL_CENTER_Y_M = PITCH_WIDTH_M / 2;
  var XG_SHOT_TYPE_MULTIPLIER = { '헤더': 0.70, '발리': 0.82, '바이시클킥': 0.55, '플레어샷': 0.85, '무회전': 0.85, '프리킥': 0.80, 'PK': 1.15 };
  function calcXg(x, y, shootType) {
    if (x == null || y == null) return null;
    var xm = x * PITCH_LENGTH_M, ym = y * PITCH_WIDTH_M;
    var dist = Math.sqrt(Math.pow(PITCH_LENGTH_M - xm, 2) + Math.pow(GOAL_CENTER_Y_M - ym, 2));
    var d1 = Math.sqrt(Math.pow(PITCH_LENGTH_M - xm, 2) + Math.pow(GOAL_Y_MIN_M - ym, 2));
    var d2 = Math.sqrt(Math.pow(PITCH_LENGTH_M - xm, 2) + Math.pow(GOAL_Y_MAX_M - ym, 2));
    var cosAngle = Math.max(-1, Math.min(1, (d1 * d1 + d2 * d2 - GOAL_WIDTH_M * GOAL_WIDTH_M) / (2 * d1 * d2)));
    var angleDeg = Math.acos(cosAngle) * (180 / Math.PI);
    var logit = 0.5 - 0.15 * dist + 0.05 * angleDeg;
    var base = 1 / (1 + Math.exp(-logit));
    var mult = XG_SHOT_TYPE_MULTIPLIER[shootType] || 1;
    return Math.round(Math.min(1, base * mult) * 100) / 100;
  }

  var PERIOD_OFFSET_MINUTES = [0, 0, 45, 90, 105, 120];
  var PERIOD_KO = { 1: '전반', 2: '후반', 3: '연장 전반', 4: '연장 후반', 5: '승부차기' };
  function absoluteMinuteOf(minutes, period) {
    if (minutes == null) return null;
    var offset = (period != null && period >= 1 && period < PERIOD_OFFSET_MINUTES.length) ? PERIOD_OFFSET_MINUTES[period] : 0;
    return minutes + offset;
  }

  // ---------------- 검색 폼 ----------------
  var form = document.getElementById('search-form');
  var nicknameInput = document.getElementById('search-nickname');
  var matchTypeToggle = document.getElementById('search-matchtype');
  var submitBtn = document.getElementById('search-submit-btn');
  var statusEl = document.getElementById('search-status');
  var resultEl = document.getElementById('search-result');
  var currentMatchType = 'CUSTOM';

  Array.prototype.forEach.call(matchTypeToggle.querySelectorAll('button'), function (btn) {
    btn.addEventListener('click', function () {
      currentMatchType = btn.dataset.mt;
      Array.prototype.forEach.call(matchTypeToggle.querySelectorAll('button'), function (b) {
        b.setAttribute('aria-pressed', String(b === btn));
      });
    });
  });

  function setStatus(text, isError) {
    if (!text) { statusEl.hidden = true; statusEl.textContent = ''; return; }
    statusEl.hidden = false;
    statusEl.textContent = text;
    statusEl.style.color = isError ? 'var(--status-critical)' : '';
  }

  var searchSeq = 0;
  form.addEventListener('submit', function (e) {
    e.preventDefault();
    var nickname = nicknameInput.value.trim();
    if (!nickname) return;

    var seq = ++searchSeq;
    resultEl.hidden = true;
    submitBtn.disabled = true;
    setStatus('"' + nickname + '" 최근 경기를 Nexon에서 직접 불러오는 중입니다… (매치 수만큼 호출해서 몇 초 걸려요)');

    apiGet('/api/v1/search/players', { nickname: nickname, matchType: currentMatchType })
      .then(function (data) {
        if (seq !== searchSeq) return;
        setStatus(null);
        submitBtn.disabled = false;
        renderResult(data);
      })
      .catch(function (err) {
        if (seq !== searchSeq) return;
        submitBtn.disabled = false;
        setStatus('검색 실패 — ' + (err && err.message ? err.message : '알 수 없는 오류') + ' (닉네임 철자를 확인해 주세요)', true);
      });
  });

  // ---------------- 결과 렌더링 ----------------
  function renderResult(data) {
    resultEl.hidden = false;
    renderHead(data);
    renderTiles(data);
    renderPlayers(data);
    renderMatches(data);
  }

  function renderHead(data) {
    var head = document.getElementById('search-result-head');
    head.replaceChildren();
    head.appendChild(el('span', 'search-result-nickname', data.nickname));
    var mtLabel = data.matchType === 'CUSTOM' ? '커스텀' : '공식전';
    head.appendChild(el('span', 'search-result-meta',
      mtLabel + ' 최근 ' + data.sampleSize + '경기 기준'));
  }

  function renderTiles(data) {
    var tiles = document.getElementById('search-tiles');
    tiles.replaceChildren();

    function tile(label, value, sub, subColor) {
      var box = el('div', 'tile');
      box.appendChild(el('p', 'tile-label', label));
      box.appendChild(el('div', 'tile-value', value));
      if (sub) {
        var subEl = el('div', 'tile-sub', sub);
        if (subColor) subEl.style.color = subColor;
        box.appendChild(subEl);
      }
      tiles.appendChild(box);
    }

    var t = data.tally;
    var totalGames = t.win + t.draw + t.lose;
    var winRate = totalGames ? Math.round((t.win / totalGames) * 100) : 0;
    tile('전적 (승-무-패)', t.win + '-' + t.draw + '-' + t.lose, totalGames + '경기 · 승률 ' + winRate + '%');
    tile('득점 - 실점', t.goalsFor + ' : ' + t.goalsAgainst,
      '득실차 ' + ((t.goalsFor - t.goalsAgainst) >= 0 ? '+' : '') + (t.goalsFor - t.goalsAgainst));
    tile('평균 평점', data.avgRating == null ? '-' : fmt1(data.avgRating));
    tile('평균 점유율', data.avgPossession == null ? '-' : Math.round(data.avgPossession) + '%');

    var finishing = data.finishing;
    tile('실제 득점 vs 실제 xG값', t.goalsFor + ' : ' + fmt1(data.xgFor),
      (finishing >= 0 ? '+' : '') + fmt1(finishing) + (finishing >= 0 ? ' 기대 이상 마무리' : ' 기대 이하 마무리'),
      finishing >= 0 ? 'var(--success-text)' : 'var(--status-critical)');

    var assistDiff = data.assistsFor - data.xaFor;
    tile('실제 어시스트 vs 실제 xA값', data.assistsFor + ' : ' + fmt1(data.xaFor),
      (assistDiff >= 0 ? '+' : '') + fmt1(assistDiff) + (assistDiff >= 0 ? ' 기대 이상 창출' : ' 기대 이하 창출'),
      assistDiff >= 0 ? 'var(--success-text)' : 'var(--status-critical)');
  }

  var PLAYER_COLS = [
    { key: 'playerName', label: '선수', numeric: false },
    { key: 'appearances', label: '출전', numeric: true },
    { key: 'goals', label: '골', numeric: true },
    { key: 'assists', label: '도움', numeric: true },
    { key: 'xg', label: 'xG', numeric: true },
    { key: 'xa', label: 'xA', numeric: true },
    { key: 'saves', label: '세이브', numeric: true },
    { key: 'shootTotal', label: '총슈팅', numeric: true },
    { key: 'effectiveShoot', label: '유효슈팅', numeric: true },
    { key: 'passTry', label: '패스시도', numeric: true },
    { key: 'passSuccess', label: '패스성공', numeric: true },
    { key: 'tackles', label: '태클', numeric: true },
    { key: 'intercepts', label: '인터셉트', numeric: true },
    { key: 'blocks', label: '블록', numeric: true },
    { key: 'avgRating', label: '평점', numeric: true }
  ];

  function renderPlayers(data) {
    var caption = document.getElementById('search-players-caption');
    caption.textContent = '종합 점수(골×3+어시×2+(태클+인터셉트+블록+세이브)×0.5) 기준 정렬 · 최근 ' +
      data.sampleSize + '경기 총합입니다.';

    var container = document.getElementById('search-players-table');
    container.replaceChildren();
    var players = data.topPlayers || [];
    if (!players.length) {
      container.appendChild(el('p', 'card-empty', '표시할 선수 데이터가 없습니다.'));
      return;
    }

    // report.js의 "전체 선수 스탯" 표와 같은 방식 — .stats-grid-table 클래스가 좁은 화면에서
    // 자동으로 라벨:값 칩 카드로 접힌다(report.css @media max-width:760px 참고).
    var table = document.createElement('table');
    table.className = 'stats-grid-table';
    var thead = document.createElement('thead');
    var htr = document.createElement('tr');
    PLAYER_COLS.forEach(function (c) { htr.appendChild(el('th', c.numeric ? 'num' : '', c.label)); });
    thead.appendChild(htr);
    table.appendChild(thead);

    var tbody = document.createElement('tbody');
    players.forEach(function (p) {
      var tr = document.createElement('tr');
      var nameTd = el('td', 'name-cell');
      nameTd.appendChild(playerNameBadge(p.spId, p.playerName));
      tr.appendChild(nameTd);
      tr.appendChild(el('td', 'num', fmt(p.appearances)));
      tr.appendChild(el('td', 'num', fmt(p.goals)));
      tr.appendChild(el('td', 'num', fmt(p.assists)));
      tr.appendChild(el('td', 'num', fmt1(p.xg)));
      tr.appendChild(el('td', 'num', fmt1(p.xa)));
      tr.appendChild(el('td', 'num', fmt(p.saves)));
      tr.appendChild(el('td', 'num', fmt(p.shootTotal)));
      tr.appendChild(el('td', 'num', fmt(p.effectiveShoot)));
      tr.appendChild(el('td', 'num', fmt(p.passTry)));
      tr.appendChild(el('td', 'num', fmt(p.passSuccess)));
      tr.appendChild(el('td', 'num', fmt(p.tackles)));
      tr.appendChild(el('td', 'num', fmt(p.intercepts)));
      tr.appendChild(el('td', 'num', fmt(p.blocks)));
      tr.appendChild(el('td', 'num', p.avgRating == null ? '-' : fmt1(p.avgRating)));
      for (var i = 1; i < tr.children.length; i++) {
        tr.children[i].setAttribute('data-label', PLAYER_COLS[i].label);
      }
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    container.appendChild(table);
  }

  function renderMatches(data) {
    var caption = document.getElementById('search-matches-caption');
    caption.textContent = '상대 무관, 최신순입니다. 행을 클릭하면 상세 정보가 열립니다.';

    var list = document.getElementById('search-matches-list');
    list.className = 'search-match-list';
    list.replaceChildren();
    var matches = data.recentMatches || [];
    if (!matches.length) {
      list.appendChild(el('p', 'card-empty', '표시할 경기가 없습니다.'));
      return;
    }

    matches.forEach(function (m) {
      var row = el('div', 'search-match-row chip result-' + m.result);
      row.tabIndex = 0;
      row.setAttribute('role', 'button');
      row.setAttribute('aria-label', m.matchDate + ' vs ' + m.opponentNickname + ' 경기 상세 보기');
      row.appendChild(el('span', 'search-match-date', fmtDateTime(new Date(m.matchDate))));
      row.appendChild(el('span', 'search-match-score', m.result + ' ' + m.goalsFor + ':' + m.goalsAgainst));
      row.appendChild(el('span', 'search-match-opponent', 'vs ' + m.opponentNickname));
      var openFn = function () { openMatchModal(data.ouid, m); };
      row.addEventListener('click', openFn);
      row.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openFn(); } });
      list.appendChild(row);
    });
  }

  // ---------------- 매치 상세 모달 ----------------
  var modalOverlay = document.getElementById('modal-overlay');
  var modalTitle = document.getElementById('modal-title');
  var modalBody = document.getElementById('modal-body');
  var modalCloseBtn = document.getElementById('modal-close');
  var modalLastFocus = null;

  function openModal() {
    modalLastFocus = document.activeElement;
    modalOverlay.hidden = false;
    modalCloseBtn.focus();
    document.addEventListener('keydown', onModalKeydown);
  }
  function closeModal() {
    modalOverlay.hidden = true;
    document.removeEventListener('keydown', onModalKeydown);
    if (modalLastFocus) modalLastFocus.focus();
  }
  function onModalKeydown(e) { if (e.key === 'Escape') closeModal(); }
  modalCloseBtn.addEventListener('click', closeModal);
  modalOverlay.addEventListener('click', function (e) { if (e.target === modalOverlay) closeModal(); });

  var modalRequestSeq = 0;
  function openMatchModal(ouid, matchSummary) {
    var seq = ++modalRequestSeq;
    modalTitle.textContent = fmtDateTime(new Date(matchSummary.matchDate)) + ' · vs ' + matchSummary.opponentNickname +
      ' (' + matchSummary.result + ' ' + matchSummary.goalsFor + ':' + matchSummary.goalsAgainst + ')';
    modalBody.replaceChildren();
    modalBody.appendChild(el('p', 'card-empty', '불러오는 중…'));
    openModal();

    var matchId = matchSummary.matchId;
    var opponentOuid = matchSummary.opponentOuid;

    Promise.all([
      apiGet('/api/v1/search/players/match-shots', { ouid: ouid, matchId: matchId }),
      apiGet('/api/v1/search/players/match-squad', { ouid: ouid, matchId: matchId }),
      opponentOuid ? apiGet('/api/v1/search/players/match-squad', { ouid: opponentOuid, matchId: matchId }).catch(function () { return []; }) : Promise.resolve([]),
      apiGet('/api/v1/search/players/match-stats', { ouid: ouid, matchId: matchId }).catch(function () { return null; })
    ]).then(function (results) {
      if (seq !== modalRequestSeq) return;
      var shots = results[0], mySquad = results[1], oppSquad = results[2], stats = results[3];
      renderModalBody(shots, mySquad, oppSquad, stats, matchSummary.result);
    }).catch(function (err) {
      if (seq !== modalRequestSeq) return;
      modalBody.replaceChildren();
      modalBody.appendChild(el('p', 'card-empty', '상세 정보를 불러오지 못했습니다 — ' + (err && err.message ? err.message : '')));
    });
  }

  function renderModalBody(shots, mySquad, oppSquad, stats, matchResult) {
    modalBody.replaceChildren();

    mySquad.forEach(function (s) { s.team = 'mine'; });
    oppSquad.forEach(function (s) { s.team = 'opponent'; });
    var momSection = el('div', 'mom-worst-section');
    modalBody.appendChild(momSection);
    buildMomWorstSection(momSection, mySquad.concat(oppSquad), matchResult);

    var compareSection = el('div', 'compare-section');
    compareSection.appendChild(el('p', 'card-title', '⚖️ 상대 팀 비교'));
    modalBody.appendChild(compareSection);
    buildCompareSection(compareSection, stats, shots);

    var goals = shots.myShots.filter(function (s) { return s.isGoal; }).map(function (g) { g.mine = true; return g; })
      .concat(shots.concededShots.filter(function (s) { return s.isGoal; }).map(function (g) { g.mine = false; return g; }))
      .sort(function (a, b) { return (absoluteMinuteOf(a.goalTimeMinutes, a.period) || 0) - (absoluteMinuteOf(b.goalTimeMinutes, b.period) || 0); });

    if (goals.length) {
      var timelineSection = el('div', 'modal-shots-section');
      timelineSection.appendChild(el('p', 'card-title', '⏱️ 득점 타임라인'));
      modalBody.appendChild(timelineSection);
      goals.forEach(function (g) { goalTimelineRow(timelineSection, g); });
    }
  }

  /** report.js의 oneLinerFor/buildMomWorstSection과 동일 로직(카드 강화 배지만 없음). */
  function oneLinerFor(entry, isMom) {
    var ratingText = entry.rating != null ? '평점 ' + fmt1(entry.rating) : '평점 기록 없음';
    var defense = entry.tackle + entry.intercept;
    var parts = [];
    if (entry.goal > 0) parts.push(entry.goal + '골');
    if (entry.assist > 0) parts.push(entry.assist + '도움');
    if (entry.save > 0) parts.push(entry.save + '선방');
    if (defense > 0) parts.push('태클+인터셉트 ' + defense + '회');
    parts = parts.slice(0, 2);
    if (parts.length) {
      var recordText = parts.join(' ') + ' 기록';
      return isMom ? recordText + '으로 팀을 이끌었다 (' + ratingText + ')' : recordText + '에 그쳤다 (' + ratingText + ')';
    }
    return isMom ? ratingText + '로 안정적인 경기력을 보였다' : ratingText + '로 아쉬운 경기를 보냈다';
  }

  function buildMomWorstSection(container, squad, matchResult) {
    container.replaceChildren();
    var candidates = squad.filter(function (s) { return s.rating != null && s.rating > 0; });
    if (!candidates.length) return;

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
      var nameRow = el('p', 'mom-card-name');
      nameRow.appendChild(playerNameBadge(entry.spId, entry.playerName));
      nameRow.appendChild(el('span', 'mom-card-team', entry.team === 'opponent' ? ' (상대 팀)' : ' (내 팀)'));
      box.appendChild(nameRow);
      box.appendChild(el('p', 'mom-card-reason', oneLinerFor(entry, isMom)));
      return box;
    }

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

  /** report.js의 compareRow와 동일 — mineVal/oppVal이 null이면 "-"로 표시. */
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

    var mineFormat = opts.formatMine || format;
    var oppFormat = opts.formatOpp || format;
    mineSpan.textContent = mineVal == null ? '-' : mineFormat(mineVal);
    oppSpan.textContent = oppVal == null ? '-' : oppFormat(oppVal);
    var minePct = 50;
    if (typeof mineVal === 'number' && typeof oppVal === 'number' && (mineVal + oppVal) > 0) {
      minePct = mineVal / (mineVal + oppVal) * 100;
    } else if (typeof mineVal === 'number' && oppVal == null) {
      minePct = 100;
    } else if (mineVal == null && typeof oppVal === 'number') {
      minePct = 0;
    }
    mineFill.style.width = minePct + '%';
    oppFill.style.width = (100 - minePct) + '%';
  }

  function expectedGoalsOf(points) {
    var sum = 0;
    points.forEach(function (p) {
      var r = calcXg(p.x, p.y, p.shootType);
      if (r != null) sum += r;
    });
    return sum;
  }

  function buildCompareSection(container, stats, shots) {
    var mine = stats && stats.mine;
    var opp = stats && stats.opponent;
    if (!mine) {
      container.appendChild(el('p', 'card-empty', '비교 데이터를 불러오지 못했습니다.'));
      return;
    }
    function o(field) { return opp ? opp[field] : null; }
    var pctFormat = function (v) { return Math.round(v) + '%'; };

    compareRow(container, '득점', mine.goalsFor, mine.goalsAgainst);
    compareRow(container, 'xG값', expectedGoalsOf(shots.myShots), expectedGoalsOf(shots.concededShots), { format: fmt1 });
    var myAssisted = shots.myShots.filter(function (s) { return s.assist; });
    var oppAssisted = shots.concededShots.filter(function (s) { return s.assist; });
    compareRow(container, 'xA값', expectedGoalsOf(myAssisted), expectedGoalsOf(oppAssisted), { format: fmt1 });
    compareRow(container, '점유율', mine.possession, o('possession'), { format: pctFormat });
    compareRow(container, '슛', mine.shootTotal, o('shootTotal'));
    compareRow(container, '유효슛', mine.effectiveShoot, o('effectiveShoot'));
    compareRow(container, '패스', mine.passSuccess, o('passSuccess'), {
      formatMine: function (v) { return v + '/' + (mine.passTry != null ? mine.passTry : '-'); },
      formatOpp: function (v) { return v + '/' + (o('passTry') != null ? o('passTry') : '-'); }
    });
    compareRow(container, '태클', mine.tackleSuccess, o('tackleSuccess'), {
      formatMine: function (v) { return v + '/' + (mine.tackleTry != null ? mine.tackleTry : '-'); },
      formatOpp: function (v) { return v + '/' + (o('tackleTry') != null ? o('tackleTry') : '-'); }
    });
    compareRow(container, '파울', mine.foul, o('foul'));
    compareRow(container, '옐로카드', mine.yellowCards, o('yellowCards'));
    compareRow(container, '레드카드', mine.redCards, o('redCards'));
  }

  /** report.js의 goalTimelineRow와 동일(클릭해서 슈팅 위치를 강조하는 기능만 뺐다 — 이 페이지엔
   * 피치 시각화 자체가 없다). */
  function goalTimelineRow(container, g) {
    var row = el('div', 'goal-timeline-row ' + (g.mine ? 'mine' : 'conceded'));
    var minute = absoluteMinuteOf(g.goalTimeMinutes, g.period);
    row.appendChild(el('div', 'goal-timeline-minute', minute != null ? minute + "'" : '-'));
    var body = el('div', 'goal-timeline-body');
    var head = el('div', 'goal-timeline-head');
    head.appendChild(el('span', 'goal-timeline-icon', g.mine ? '⚽' : '🥅'));
    head.appendChild(playerNameBadge(g.spId, g.playerName));
    body.appendChild(head);
    var xg = calcXg(g.x, g.y, g.shootType);
    var detailParts = [];
    if (g.period != null && PERIOD_KO[g.period]) detailParts.push(PERIOD_KO[g.period]);
    detailParts.push(g.shootType);
    detailParts.push(g.assist && g.assistPlayerName ? '어시스트: ' + g.assistPlayerName : '어시스트 없음');
    if (xg != null) detailParts.push('이 구역 xG ' + xg.toFixed(2) + '골');
    body.appendChild(el('div', 'goal-timeline-meta', detailParts.join(' · ')));
    row.appendChild(body);
    container.appendChild(row);
  }
})();
