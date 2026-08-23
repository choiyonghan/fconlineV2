(function () {
  'use strict';

  var BASE_URL = 'https://fconlinev2-backend.onrender.com';

  var state = { ouid: null, matchType: 'CUSTOM', seasonId: null };
  var playersGridSort = { col: 'contributionScore', dir: 'desc' };

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

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
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

  // ---------------- static chrome ----------------
  var userSelect = document.getElementById('user-select');
  var seasonSelect = document.getElementById('season-select');
  var seasonPill = document.getElementById('season-pill');
  var loadStatus = document.getElementById('load-status');
  var mtButtons = document.querySelectorAll('#matchtype-toggle button');

  function setStatus(msg, isError) {
    if (!msg) { loadStatus.hidden = true; return; }
    loadStatus.hidden = false;
    loadStatus.textContent = msg;
    loadStatus.style.color = isError ? 'var(--status-critical)' : 'var(--text-muted)';
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

  userSelect.addEventListener('change', function () {
    state.ouid = userSelect.value;
    persist();
    loadSelection();
  });

  seasonSelect.addEventListener('change', function () {
    state.seasonId = seasonSelect.value ? Number(seasonSelect.value) : null;
    var s = allSeasons.filter(function (x) { return String(x.id) === seasonSelect.value; })[0];
    seasonPill.hidden = !(s && s.current);
    if (s && s.current) seasonPill.textContent = '진행중';
    persist();
    loadSelection();
  });

  var allUsers = [];
  var allSeasons = [];

  function init() {
    setStatus('유저/시즌 목록을 불러오는 중입니다… 백엔드가 잠들어 있으면 첫 로딩에 최대 1분 정도 걸릴 수 있어요.');
    return Promise.all([apiGet('/api/v1/users'), apiGet('/api/v1/seasons')])
      .then(function (results) {
        allUsers = results[0];
        allSeasons = results[1];
        if (!allUsers.length) { setStatus('추적 중인 유저가 없습니다.', true); return; }

        allUsers.slice().sort(function (a, b) { return a.displayOrder - b.displayOrder; }).forEach(function (u) {
          var opt = document.createElement('option');
          opt.value = u.ouid;
          opt.textContent = u.nickname;
          userSelect.appendChild(opt);
        });
        allSeasons.slice().sort(function (a, b) { return b.id - a.id; }).forEach(function (s) {
          var opt = document.createElement('option');
          opt.value = s.id;
          opt.textContent = s.name + (s.current ? ' · 진행중' : '');
          seasonSelect.appendChild(opt);
        });

        state.ouid = (savedSelection.ouid && allUsers.some(function (u) { return u.ouid === savedSelection.ouid; }))
          ? savedSelection.ouid
          : allUsers[0].ouid;

        var seasonMatch = allSeasons.filter(function (s) { return String(s.id) === String(savedSelection.seasonId); })[0];
        var currentSeason = allSeasons.filter(function (s) { return s.current; })[0];
        state.seasonId = seasonMatch ? seasonMatch.id : (currentSeason ? currentSeason.id : (allSeasons[0] ? allSeasons[0].id : null));

        userSelect.value = state.ouid;
        seasonSelect.value = state.seasonId != null ? String(state.seasonId) : '';
        var activeSeason = allSeasons.filter(function (s) { return s.id === state.seasonId; })[0];
        seasonPill.hidden = !(activeSeason && activeSeason.current);
        if (activeSeason && activeSeason.current) seasonPill.textContent = '진행중';
        mtButtons.forEach(function (b) { b.setAttribute('aria-pressed', String(b.getAttribute('data-mt') === state.matchType)); });

        setStatus(null);
        document.getElementById('snapshot-badge').innerHTML = '<strong>LIVE</strong> · 매 로딩마다 백엔드를 직접 호출';
        loadZoneAggregate();
        return loadSelection();
      })
      .catch(function (err) {
        setStatus('유저/시즌 목록을 불러오지 못했습니다 — 백엔드가 응답하지 않습니다. 새로고침해서 다시 시도해 주세요. (' + err.message + ')', true);
      });
  }

  function barChart(container, rows, opts) {
    // rows: [{label, value, color}]
    container.replaceChildren();
    if (!rows.length) { container.appendChild(el('p', 'card-empty', '표시할 데이터가 없습니다.')); return; }
    var max = Math.max.apply(null, rows.map(function (r) { return r.value; }), 1);
    rows.forEach(function (r) {
      var row = el('div', 'bar-row');
      row.appendChild(el('div', 'bar-cat', r.label));
      var track = el('div', 'bar-track');
      var fillPct = Math.max((r.value / max) * 100, r.value > 0 ? 2 : 0);
      var fill = el('div', 'bar-fill');
      fill.style.width = fillPct + '%';
      fill.style.background = r.color;
      fill.tabIndex = 0;
      fill.setAttribute('role', 'img');
      fill.setAttribute('aria-label', r.label + ' ' + r.value);
      var showFn = function (evt) { showTip(evt, [r.label, (opts && opts.unit ? r.value + opts.unit : String(r.value))]); };
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
  var RESULT_KO = { GOAL: '골', ON_TARGET: '온타겟', OFF_TARGET: '오프타겟' };

  function pitchHeatmap(container, points) {
    container.replaceChildren();
    var svgNS = 'http://www.w3.org/2000/svg';
    var W = 400, H = 260;
    var svg = document.createElementNS(svgNS, 'svg');
    svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
    svg.setAttribute('class', 'pitch');
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', '슈팅 위치 산점도, 총 ' + points.length + '건 (득점은 진하게 표시)');

    function line(x1, y1, x2, y2) {
      var l = document.createElementNS(svgNS, 'line');
      l.setAttribute('x1', x1); l.setAttribute('y1', y1);
      l.setAttribute('x2', x2); l.setAttribute('y2', y2);
      l.setAttribute('class', 'pitch-line');
      svg.appendChild(l);
    }
    function rect(x, y, w, h) {
      var r = document.createElementNS(svgNS, 'rect');
      r.setAttribute('x', x); r.setAttribute('y', y);
      r.setAttribute('width', w); r.setAttribute('height', h);
      r.setAttribute('class', 'pitch-line');
      svg.appendChild(r);
    }
    function circle(cx, cy, rad) {
      var c = document.createElementNS(svgNS, 'circle');
      c.setAttribute('cx', cx); c.setAttribute('cy', cy); c.setAttribute('r', rad);
      c.setAttribute('class', 'pitch-line');
      svg.appendChild(c);
    }

    rect(2, 2, W - 4, H - 4);
    line(W / 2, 2, W / 2, H - 2);
    circle(W / 2, H / 2, 30);
    // penalty areas (both ends — x is full-pitch normalized 0..1, own goal at x=0)
    rect(2, H / 2 - 55, 55, 110);
    rect(W - 57, H / 2 - 55, 55, 110);
    rect(2, H / 2 - 26, 22, 52);
    rect(W - 24, H / 2 - 26, 22, 52);

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
      c.setAttribute('class', isGoal ? 'goal-dot' : 'miss-dot');
      c.tabIndex = 0;
      var xgPct = p.xg != null ? Math.round(p.xg * 100) + '%' : null;
      var showFn = function (evt) {
        var lines = [p.shootType + ' · ' + (RESULT_KO[p.result] || p.result)];
        if (xgPct) lines.push('이 구역 실측 골 전환율(근사 xG) ' + xgPct);
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
    var mi = el('div', 'legend-item');
    var ms = el('span', 'legend-swatch'); ms.style.background = 'var(--gridline)'; ms.style.border = '1px solid var(--text-muted)'; ms.style.borderRadius = '50%';
    mi.appendChild(ms); mi.appendChild(document.createTextNode('무산(온타겟/오프타겟)'));
    legend.appendChild(gi); legend.appendChild(mi);
    container.appendChild(legend);
  }
  function xgZoneTable(container, rows, sampleSize) {
    container.replaceChildren();
    var table = document.createElement('table');
    var thead = document.createElement('thead');
    var htr = document.createElement('tr');
    ['구역', '슈팅 수', '골', '실측 전환율(근사 xG)'].forEach(function (h, i) {
      htr.appendChild(el('th', i >= 1 ? 'num' : '', h));
    });
    thead.appendChild(htr);
    table.appendChild(thead);
    var tbody = document.createElement('tbody');
    rows.forEach(function (r) {
      var tr = document.createElement('tr');
      tr.appendChild(el('td', 'name-cell', r.zone));
      tr.appendChild(el('td', 'num', fmt(r.shots)));
      tr.appendChild(el('td', 'num', fmt(r.goals)));
      tr.appendChild(el('td', 'num', Math.round(r.rate * 1000) / 10 + '%'));
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    container.appendChild(table);
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
      tr.appendChild(el('td', 'name-cell', c.assisterName));
      tr.appendChild(el('td', '', '→'));
      tr.appendChild(el('td', 'name-cell', c.scorerName));
      tr.appendChild(el('td', 'num', fmt(c.goals)));
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

  function statBlock(label, value) {
    var box = el('div', 'modal-stat');
    box.appendChild(el('p', 'modal-stat-label', label));
    box.appendChild(el('div', 'modal-stat-value', value));
    return box;
  }

  function openMatchModal(m) {
    modalLastFocus = document.activeElement;
    var d = new Date(m.matchDate);
    modalTitle.textContent = 'vs ' + m.opponentNickname + ' · ' + d.toLocaleDateString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit' });

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

    var grid = el('div', 'modal-stat-grid');
    grid.appendChild(statBlock('평점', m.averageRating != null ? fmt1(m.averageRating) : '-'));
    grid.appendChild(statBlock('점유율', m.possession != null ? m.possession + '%' : '-'));
    grid.appendChild(statBlock('슈팅 (유효/전체)', (m.effectiveShoot != null ? m.effectiveShoot : '-') + ' / ' + (m.shootTotal != null ? m.shootTotal : '-')));
    grid.appendChild(statBlock('패스 (성공/시도)', (m.passSuccess != null ? m.passSuccess : '-') + ' / ' + (m.passTry != null ? m.passTry : '-')));
    grid.appendChild(statBlock('태클 (성공/시도)', (m.tackleSuccess != null ? m.tackleSuccess : '-') + ' / ' + (m.tackleTry != null ? m.tackleTry : '-')));
    grid.appendChild(statBlock('파울', m.foul != null ? fmt(m.foul) : '-'));
    grid.appendChild(statBlock('옐로카드', m.yellowCards != null ? fmt(m.yellowCards) : '-'));
    grid.appendChild(statBlock('레드카드', m.redCards != null ? fmt(m.redCards) : '-'));
    modalBody.appendChild(grid);

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
      tr.appendChild(el('td', '', d.toLocaleDateString('ko-KR', { month: '2-digit', day: '2-digit' })));
      tr.appendChild(el('td', 'name-cell', m.opponentNickname));
    }
    var resTd = document.createElement('td');
    resTd.appendChild(el('span', 'chip result-' + m.result, m.result));
    tr.appendChild(resTd);
    tr.appendChild(el('td', 'num', m.goalsFor + ' : ' + m.goalsAgainst));
    tr.appendChild(el('td', 'num', m.averageRating != null ? fmt1(m.averageRating) : '-'));
    tr.appendChild(el('td', 'num', m.possession != null ? m.possession + '%' : '-'));
    if (withDate) {
      tr.appendChild(el('td', 'num', (m.effectiveShoot != null ? m.effectiveShoot : '-') + ' / ' + (m.shootTotal != null ? m.shootTotal : '-')));
      tr.appendChild(el('td', 'num', (m.passSuccess != null ? m.passSuccess : '-') + ' / ' + (m.passTry != null ? m.passTry : '-')));
      tr.appendChild(el('td', 'num', (m.tackleSuccess != null ? m.tackleSuccess : '-') + ' / ' + (m.tackleTry != null ? m.tackleTry : '-')));
    }
    var openFn = function () { openMatchModal(m); };
    tr.addEventListener('click', openFn);
    tr.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openFn(); } });
    return tr;
  }
  function opponentsTable(container, opponents) {
    container.replaceChildren();
    if (!opponents.length) { container.appendChild(el('p', 'card-empty', '상대 전적이 없습니다.')); return; }
    var sorted = opponents.slice().sort(function (a, b) { return b.dugsikScore - a.dugsikScore; });
    var table = document.createElement('table');
    var thead = document.createElement('thead');
    var htr = document.createElement('tr');
    ['', '상대', '전적 (승-무-패)', '현재 기록', '욱식 점수'].forEach(function (h, i) {
      htr.appendChild(el('th', i === 4 ? 'num' : '', h));
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
      tr.appendChild(el('td', 'num', fmt(o.dugsikScore)));

      var expandTr = document.createElement('tr');
      expandTr.className = 'opp-expand';
      expandTr.hidden = true;
      var expandTd = document.createElement('td');
      expandTd.colSpan = 5;
      var inner = el('div', 'expand-inner');
      expandTd.appendChild(inner);
      expandTr.appendChild(expandTd);

      // v2: 전체 상대의 최근 경기를 미리 다 받아두던 스냅샷 방식 대신, 펼칠 때 그 상대 것만 그때 불러온다
      // (백엔드가 상대당 페이지 API를 이미 갖고 있어서 — 미리 당겨둘 이유가 없다).
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
          apiGet('/api/v1/opponents/' + encodeURIComponent(o.opponentOuid) + '/matches',
            { ouid: state.ouid, matchType: state.matchType, seasonId: state.seasonId, page: 0, size: 10 })
            .then(function (page) {
              inner.replaceChildren();
              var matches = page.content;
              if (!matches.length) {
                inner.appendChild(el('p', 'card-empty', '최근 경기 기록이 없습니다.'));
                return;
              }
              var mtable = document.createElement('table');
              var mthead = document.createElement('thead');
              var mhtr = document.createElement('tr');
              ['결과', '스코어', '평점', '점유율'].forEach(function (h, i) {
                mhtr.appendChild(el('th', i >= 1 ? 'num' : '', h));
              });
              mthead.appendChild(mhtr);
              mtable.appendChild(mthead);
              var mtbody = document.createElement('tbody');
              matches.forEach(function (m) {
                var withName = {};
                for (var k in m) withName[k] = m[k];
                withName.opponentNickname = o.opponentNickname;
                mtbody.appendChild(buildMatchRow(withName, false));
              });
              mtable.appendChild(mtbody);
              inner.appendChild(mtable);
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

  function recentMatchesTable(container, matches) {
    container.replaceChildren();
    if (!matches.length) { container.appendChild(el('p', 'card-empty', '최근 경기 기록이 없습니다.')); return; }
    var table = document.createElement('table');
    var thead = document.createElement('thead');
    var htr = document.createElement('tr');
    ['날짜', '상대', '결과', '스코어', '평점', '점유율', '슈팅', '패스 성공', '태클 성공'].forEach(function (h, i) {
      htr.appendChild(el('th', i >= 3 ? 'num' : '', h));
    });
    thead.appendChild(htr);
    table.appendChild(thead);
    var tbody = document.createElement('tbody');
    matches.forEach(function (m) { tbody.appendChild(buildMatchRow(m, true)); });
    table.appendChild(tbody);
    container.appendChild(table);
    var hint = el('p', 'card-caption', '행을 클릭하면 상세 정보가 열립니다.');
    hint.style.marginTop = '8px';
    hint.style.marginBottom = '0';
    container.appendChild(hint);
  }

  function renderHeadlineRecords(opponents) {
    var container = document.getElementById('headline-records');
    container.replaceChildren();
    if (!opponents.length) {
      container.appendChild(el('p', 'card-empty', '집계된 상대 전적이 없습니다.'));
      return;
    }
    var specs = [
      { key: 'maxWin', label: '최다 연승', unit: '연승' },
      { key: 'maxLose', label: '최다 연패', unit: '연패' },
      { key: 'maxUnbeaten', label: '최다 무패', unit: '무패' },
      { key: 'maxWinless', label: '최다 무승', unit: '무승' }
    ];
    specs.forEach(function (spec) {
      var best = opponents.reduce(function (acc, o) {
        return (!acc || o.streak[spec.key] > acc.streak[spec.key]) ? o : acc;
      }, null);
      var tile = el('div', 'headline-tile');
      tile.appendChild(el('p', 'tile-label', spec.label));
      if (!best || best.streak[spec.key] === 0) {
        tile.appendChild(el('div', 'tile-value', '-'));
      } else {
        tile.appendChild(el('div', 'tile-value', best.streak[spec.key] + spec.unit));
        tile.appendChild(el('div', 'tile-sub', 'vs ' + best.opponentNickname));
      }
      container.appendChild(tile);
    });
  }

  var currentPlayersList = [];
  function renderPlayersGrid(players) {
    currentPlayersList = players;
    var container = document.getElementById('table-allplayers');
    container.replaceChildren();
    if (!players.length) { container.appendChild(el('p', 'card-empty', '표시할 선수 데이터가 없습니다.')); return; }

    var cols = [
      { key: 'playerName', label: '선수', numeric: false },
      { key: 'goals', label: '골', numeric: true },
      { key: 'assists', label: '어시스트', numeric: true },
      { key: 'saves', label: '세이브', numeric: true },
      { key: 'tackles', label: '태클', numeric: true },
      { key: 'intercepts', label: '인터셉트', numeric: true },
      { key: 'blocks', label: '블록', numeric: true },
      { key: 'contributionScore', label: '기여도', numeric: true }
    ];

    var sorted = players.slice().sort(function (a, b) {
      var av = a[playersGridSort.col], bv = b[playersGridSort.col];
      var cmp = typeof av === 'string' ? av.localeCompare(bv, 'ko') : av - bv;
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

    var tbody = document.createElement('tbody');
    sorted.forEach(function (p) {
      var tr = document.createElement('tr');
      tr.appendChild(el('td', 'name-cell', p.playerName));
      tr.appendChild(el('td', 'num', fmt(p.goals)));
      tr.appendChild(el('td', 'num', fmt(p.assists)));
      tr.appendChild(el('td', 'num', fmt(p.saves)));
      tr.appendChild(el('td', 'num', fmt(p.tackles)));
      tr.appendChild(el('td', 'num', fmt(p.intercepts)));
      tr.appendChild(el('td', 'num', fmt(p.blocks)));
      tr.appendChild(el('td', 'num', fmt1(p.contributionScore)));
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
  // ---------------- 근사 xG 구역 집계 ----------------
  // 원본 스냅샷 세션은 이 구역 표를 DB에서 직접 계산해뒀지만, 여기선 백엔드에 별도 엔드포인트가 없어
  // 전 유저 × 양쪽 매치타입의 슈팅 좌표(/api/v1/records/shot-heatmap)를 브라우저에서 직접 모아 집계한다.
  // 구역 경계는 위 pitchHeatmap이 그리는 박스/6야드 박스 사각형과 동일한 비율을 그대로 재사용한다 —
  // 즉 화면에 그려지는 박스 안에 찍힌 점은 실제로도 "박스 안" 구역으로 집계된다. 정식 xG 모델이 아닌
  // 근사치이며, 원본 세션이 썼던 정확한 경계값과 100% 동일하다는 보장은 없다(둘 다 근사치라는 점은 같다).
  var ZONE_CACHE_KEY = 'matchreport-zonecache-v1';
  var zoneAggregate = null; // { table:[{zone,shots,goals,rate}], sampleSize, rateMap:{zone:rate} }
  var lastPoints = null;

  var BOX = { xMin: 343 / 400, yMin: 75 / 260, yMax: 185 / 260 };
  var SIX = { xMin: 376 / 400, yMin: 104 / 260, yMax: 156 / 260 };

  function zoneKey(x, y) {
    var isCenter = y >= BOX.yMin && y <= BOX.yMax;
    var band;
    if (x >= SIX.xMin) band = '초근접(6야드 부근)';
    else if (x >= BOX.xMin) band = '박스 안';
    else if (x >= 0.70) band = '박스 근처';
    else if (x >= 0.55) band = '중거리';
    else band = '장거리';
    return band + ' · ' + (isCenter ? '중앙' : '측면');
  }

  function loadZoneAggregate() {
    var cached = null;
    try { cached = JSON.parse(sessionStorage.getItem(ZONE_CACHE_KEY) || 'null'); } catch (e) { /* ignore */ }
    if (cached && cached.table && cached.sampleSize) {
      applyZoneAggregate(cached);
      return Promise.resolve();
    }
    var requests = [];
    allUsers.forEach(function (u) {
      ['CUSTOM', 'OFFICIAL'].forEach(function (mt) {
        requests.push(
          apiGet('/api/v1/records/shot-heatmap', { ouid: u.ouid, matchType: mt, goalsOnly: false })
            .then(function (r) { return r.points; })
            .catch(function () { return []; })
        );
      });
    });
    return Promise.all(requests).then(function (results) {
      var counts = {};
      var total = 0;
      results.forEach(function (points) {
        points.forEach(function (p) {
          var key = zoneKey(p.x, p.y);
          if (!counts[key]) counts[key] = { zone: key, shots: 0, goals: 0 };
          counts[key].shots += 1;
          if (p.goal) counts[key].goals += 1;
          total += 1;
        });
      });
      var table = Object.keys(counts).map(function (k) {
        var c = counts[k];
        return { zone: c.zone, shots: c.shots, goals: c.goals, rate: c.shots ? c.goals / c.shots : 0 };
      }).sort(function (a, b) { return b.shots - a.shots; });
      var agg = { table: table, sampleSize: total };
      try { sessionStorage.setItem(ZONE_CACHE_KEY, JSON.stringify(agg)); } catch (e) { /* ignore */ }
      applyZoneAggregate(agg);
    });
  }

  function applyZoneAggregate(agg) {
    zoneAggregate = agg;
    zoneAggregate.rateMap = {};
    agg.table.forEach(function (r) { zoneAggregate.rateMap[r.zone] = r.rate; });
    document.getElementById('xg-sample-size').textContent = fmt(agg.sampleSize);
    xgZoneTable(document.getElementById('table-xgzones'), agg.table, agg.sampleSize);
    if (lastPoints) updateXgTile(lastPoints);
  }

  function updateXgTile(points) {
    var valueEl = document.getElementById('xg-tile-value');
    var subEl = document.getElementById('xg-tile-sub');
    if (!valueEl) return;
    var actualGoals = points.filter(function (p) { return p.goal; }).length;
    if (!zoneAggregate) {
      valueEl.textContent = actualGoals + ' : 계산 중…';
      subEl.textContent = '';
      return;
    }
    var expectedGoals = 0;
    points.forEach(function (p) {
      var r = zoneAggregate.rateMap[zoneKey(p.x, p.y)];
      if (r != null) expectedGoals += r;
    });
    var diff = actualGoals - expectedGoals;
    valueEl.textContent = actualGoals + ' : ' + fmt1(expectedGoals);
    subEl.textContent = (diff > 0 ? '+' : '') + fmt1(diff) + (diff >= 0 ? ' 기대 이상 마무리' : ' 기대 이하 마무리');
    subEl.style.color = diff >= 0 ? 'var(--success-text)' : 'var(--status-critical)';
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
    return Promise.all([
      apiGet('/api/v1/records/overall', qs),
      apiGet('/api/v1/opponents', qs),
      apiGet('/api/v1/records/players', qs),
      apiGet('/api/v1/records/assist-chains', qs),
      apiGet('/api/v1/records/shot-heatmap', { ouid: qs.ouid, matchType: qs.matchType, seasonId: qs.seasonId, goalsOnly: false })
    ]).then(function (r) {
      if (seq !== loadSeq) return; // 응답 도착 전에 선택이 또 바뀐 경우 — 낡은 응답은 버린다
      setStatus(null);
      renderAll(user, { overall: r[0], opponents: r[1], allPlayers: r[2], assistChains: r[3], heatmap: r[4] }, seq);
    }).catch(function (err) {
      if (seq !== loadSeq) return;
      setStatus('데이터를 불러오지 못했습니다. 새로고침해서 다시 시도해 주세요. (' + err.message + ')', true);
    });
  }

  // ---------------- main render ----------------
  function renderAll(user, d, seq) {
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

    var ratingTile = el('div', 'tile');
    ratingTile.appendChild(el('p', 'tile-label', '평균 평점'));
    ratingTile.appendChild(el('div', 'tile-value', fmt1(overall.averageRating)));
    tiles.appendChild(ratingTile);

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
    xgTile.appendChild(el('p', 'tile-label', '실제 득점 vs 근사 xG'));
    var xgValue = el('div', 'tile-value', '—'); xgValue.id = 'xg-tile-value';
    var xgSub = el('div', 'tile-sub', ''); xgSub.id = 'xg-tile-sub';
    xgTile.appendChild(xgValue);
    xgTile.appendChild(xgSub);
    tiles.appendChild(xgTile);

    // top players — 점수만, TOP 7 (전체 목록은 아래 그리드에서 더보기)
    var top7Rows = d.allPlayers.slice()
      .sort(function (a, b) { return b.contributionScore - a.contributionScore; })
      .slice(0, 7)
      .map(function (p) { return { label: p.playerName, value: Math.round(p.contributionScore * 10) / 10, color: 'var(--series-1)' }; });
    barChart(document.getElementById('chart-players'), top7Rows, { unit: '점' });

    // goal type distribution
    var typeRows = overall.goalTypeDistribution
      .filter(function (t) { return t.count > 0; })
      .sort(function (a, b) { return b.count - a.count; })
      .map(function (t) { return { label: t.shootType, value: t.count, color: 'var(--series-1)' }; });
    barChart(document.getElementById('chart-goaltypes'), typeRows, { unit: '골' });

    // goal time distribution
    var timeRows = overall.goalTimeDistribution.map(function (t) { return { label: t.periodLabel, value: t.count }; });
    verticalBarChart(document.getElementById('chart-goaltime'), timeRows);

    // heatmap (전체 슈팅 + 근사 xG)
    lastPoints = d.heatmap.points;
    var actualGoalsNow = d.heatmap.points.filter(function (p) { return p.goal; }).length;
    document.getElementById('heatmap-caption').textContent =
      '슈팅 ' + d.heatmap.points.length + '건 중 득점 ' + actualGoalsNow + '건 · 좌측이 자책골 방향, 우측이 상대 골대 방향';
    var shotsForPitch = d.heatmap.points.map(function (p) {
      var withXg = {};
      for (var k in p) withXg[k] = p[k];
      withXg.xg = zoneAggregate ? zoneAggregate.rateMap[zoneKey(p.x, p.y)] : null;
      return withXg;
    });
    pitchHeatmap(document.getElementById('chart-heatmap'), shotsForPitch);
    updateXgTile(d.heatmap.points);

    // assist chains
    assistTable(document.getElementById('table-assists'), d.assistChains);

    // headline records (역대 최고 연속 기록)
    renderHeadlineRecords(d.opponents);

    // opponents (행 클릭 시 해당 상대 최근 경기를 그때 불러와 펼침)
    opponentsTable(document.getElementById('table-opponents'), d.opponents);

    // recent matches — 전적이 가장 많은 상대와의 최근 경기만 그때 불러온다
    var topOpp = d.opponents.slice().sort(function (a, b) {
      var am = a.tally.win + a.tally.draw + a.tally.lose;
      var bm = b.tally.win + b.tally.draw + b.tally.lose;
      return bm - am;
    })[0];
    document.getElementById('recent-caption').textContent = topOpp
      ? ('전적이 가장 많은 상대 "' + topOpp.opponentNickname + '"와의 최근 경기')
      : '표시할 최근 경기가 없습니다.';
    if (topOpp) {
      apiGet('/api/v1/opponents/' + encodeURIComponent(topOpp.opponentOuid) + '/matches',
        { ouid: state.ouid, matchType: state.matchType, seasonId: state.seasonId, page: 0, size: 10 })
        .then(function (page) {
          if (seq !== loadSeq) return;
          var matches = page.content.map(function (m) {
            var withName = {};
            for (var k in m) withName[k] = m[k];
            withName.opponentNickname = topOpp.opponentNickname;
            return withName;
          });
          recentMatchesTable(document.getElementById('table-recent'), matches);
        })
        .catch(function () {
          if (seq === loadSeq) recentMatchesTable(document.getElementById('table-recent'), []);
        });
    } else {
      recentMatchesTable(document.getElementById('table-recent'), []);
    }

    // 전체 선수 스탯 그리드 — 유저/매치타입 바뀔 때마다 정렬 상태는 초기화(기여도 desc)
    playersGridSort = { col: 'contributionScore', dir: 'desc' };
    renderPlayersGrid(d.allPlayers);
  }

  init();
})();
