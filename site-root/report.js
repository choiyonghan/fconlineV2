(function () {
  'use strict';

  var BASE_URL = 'https://fconlinev2-backend.onrender.com';

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
    aiAnswerText.textContent = text;
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

  var allUsers = [];
  var allSeasons = [];

  function init() {
    setStatus('유저/시즌 목록을 불러오는 중입니다… 백엔드가 잠들어 있으면 첫 로딩에 최대 1분 정도 걸릴 수 있어요.');
    return Promise.all([apiGet('/api/v1/users'), apiGet('/api/v1/seasons')])
      .then(function (results) {
        allUsers = results[0];
        allSeasons = results[1];
        if (!allUsers.length) { setStatus('추적 중인 유저가 없습니다.', true); return; }

        var sortedUsers = allUsers.slice().sort(function (a, b) { return a.displayOrder - b.displayOrder; });
        buildChips(userChipRow, sortedUsers,
          function (u) { return u.ouid; },
          function (u) { return u.nickname; },
          function (value) {
            state.ouid = value;
            persist();
            loadSelection();
          },
          function (u) { return u.nickname.charAt(0); });

        var sortedSeasons = allSeasons.slice().sort(function (a, b) { return b.id - a.id; });
        buildChips(seasonChipRow, sortedSeasons,
          function (s) { return s.id; },
          function (s) { return s.name; },
          function (value) {
            state.seasonId = Number(value);
            persist();
            loadSelection();
          });

        state.ouid = (savedSelection.ouid && allUsers.some(function (u) { return u.ouid === savedSelection.ouid; }))
          ? savedSelection.ouid
          : allUsers[0].ouid;

        var seasonMatch = allSeasons.filter(function (s) { return String(s.id) === String(savedSelection.seasonId); })[0];
        var currentSeason = allSeasons.filter(function (s) { return s.current; })[0];
        state.seasonId = seasonMatch ? seasonMatch.id : (currentSeason ? currentSeason.id : (allSeasons[0] ? allSeasons[0].id : null));

        setActiveChip(userChipRow, state.ouid);
        setActiveChip(seasonChipRow, state.seasonId);
        mtButtons.forEach(function (b) { b.setAttribute('aria-pressed', String(b.getAttribute('data-mt') === state.matchType)); });

        setStatus(null);
        loadZoneAggregate();
        return loadSelection();
      })
      .catch(function (err) {
        setStatus('유저/시즌 목록을 불러오지 못했습니다 — 백엔드가 응답하지 않습니다. 새로고침해서 다시 시도해 주세요. (' + err.message + ')', true);
      });
  }

  function barChart(container, rows, opts) {
    // rows: [{label, value, color, sub?}] — sub가 있으면 라벨 아래에 작은 보조 정보를 한 줄 더 보여준다.
    container.replaceChildren();
    if (!rows.length) { container.appendChild(el('p', 'card-empty', '표시할 데이터가 없습니다.')); return; }
    var max = Math.max.apply(null, rows.map(function (r) { return r.value; }), 1);
    rows.forEach(function (r) {
      var row = el('div', 'bar-row');
      if (r.sub) {
        var cat = el('div', 'bar-cat stacked');
        cat.appendChild(document.createTextNode(r.label));
        cat.appendChild(el('span', 'bar-cat-sub', r.sub));
        row.appendChild(cat);
      } else {
        row.appendChild(el('div', 'bar-cat', r.label));
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

    seriesList.forEach(function (s) {
      var pts = s.values.map(function (v, i) { return xAt(i) + ',' + yAt(v); }).join(' ');
      var poly = document.createElementNS(svgNS, 'polyline');
      poly.setAttribute('points', pts);
      poly.setAttribute('fill', 'none');
      poly.setAttribute('stroke', s.color);
      poly.setAttribute('stroke-width', '2');
      poly.setAttribute('stroke-linejoin', 'round');
      poly.setAttribute('stroke-linecap', 'round');
      svg.appendChild(poly);

      s.values.forEach(function (v, i) {
        var c = document.createElementNS(svgNS, 'circle');
        c.setAttribute('cx', xAt(i));
        c.setAttribute('cy', yAt(v));
        c.setAttribute('r', '3');
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
        var item = el('div', 'legend-item');
        var sw = el('span', 'legend-swatch');
        sw.style.background = s.color;
        sw.style.borderRadius = '50%';
        item.appendChild(sw);
        item.appendChild(document.createTextNode(s.label));
        legend.appendChild(item);
      });
      container.appendChild(legend);
    }
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
        if (xgPct) lines.push('이 구역 xG값 ' + xgPct);
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
              ['결과', '스코어', '점유율'].forEach(function (h, i) {
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
      { key: 'shootAccuracy', label: '슛정확', numeric: true },
      { key: 'passAccuracy', label: '패스', numeric: true },
      { key: 'dribbleRate', label: '드리블', numeric: true },
      { key: 'aerialRate', label: '공중볼', numeric: true },
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
      tr.appendChild(el('td', 'name-cell', p.playerName));
      tr.appendChild(el('td', 'num', fmt(p.appearances)));
      tr.appendChild(el('td', 'num', fmt(Math.round(p.attackRating))));
      tr.appendChild(el('td', 'num', fmt(Math.round(p.defenseRating))));
      tr.appendChild(el('td', 'num', fmt(p.goals)));
      tr.appendChild(el('td', 'num', fmt(p.assists)));
      tr.appendChild(el('td', 'num', fmt(p.attackPoints)));
      tr.appendChild(el('td', 'num', pct(p.shootAccuracy)));
      tr.appendChild(el('td', 'num', pct(p.passAccuracy)));
      tr.appendChild(el('td', 'num', pct(p.dribbleRate)));
      tr.appendChild(el('td', 'num', pct(p.aerialRate)));
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
  // ---------------- 근사 xG 구역 집계 ----------------
  // 원본 스냅샷 세션은 이 구역 표를 DB에서 직접 계산해뒀지만, 여기선 백엔드에 별도 엔드포인트가 없어
  // 전 유저 × 양쪽 매치타입의 슈팅 좌표(/api/v1/records/shot-heatmap)를 브라우저에서 직접 모아 집계한다.
  // 구역 경계는 위 pitchHeatmap이 그리는 박스/6야드 박스 사각형과 동일한 비율을 그대로 재사용한다 —
  // 즉 화면에 그려지는 박스 안에 찍힌 점은 실제로도 "박스 안" 구역으로 집계된다. 정식 xG 모델이 아닌
  // 근사치이며, 원본 세션이 썼던 정확한 경계값과 100% 동일하다는 보장은 없다(둘 다 근사치라는 점은 같다).
  var ZONE_CACHE_KEY = 'matchreport-zonecache-v1';
  var zoneAggregate = null; // { table:[{zone,shots,goals,rate}], sampleSize, rateMap:{zone:rate} }
  var lastPoints = null;
  var lastOverall = null;
  var lastTotalGames = 0;
  var lastConcededPoints = null;
  var lastConcededSampleGames = 0;
  var lastMatches = null;

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
    if (lastPoints) updateXgTile(lastPoints);
    if (lastOverall) {
      renderPlayStyle(lastOverall, lastPoints, lastTotalGames, lastConcededPoints, lastConcededSampleGames, lastMatches);
    }
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

  // ---------------- 플레이 성향 ----------------
  function statMini(container, label, value, sub) {
    var box = el('div', 'stat-mini');
    box.appendChild(el('p', 'stat-mini-label', label));
    box.appendChild(el('div', 'stat-mini-value', value));
    if (sub) box.appendChild(el('div', 'stat-mini-sub', sub));
    container.appendChild(box);
  }

  function pctOf(count, total) {
    return total > 0 ? Math.round((count / total) * 100) : 0;
  }

  function expectedGoalsOf(points) {
    if (!zoneAggregate) return 0;
    var sum = 0;
    points.forEach(function (p) {
      var r = zoneAggregate.rateMap[zoneKey(p.x, p.y)];
      if (r != null) sum += r;
    });
    return sum;
  }

  /** 매치별 xG값 추이 라인차트용 — 슛 포인트를 matchId로 묶어 매치당 xG값 합을 낸다. */
  function groupExpectedGoalsByMatch(points) {
    var byMatch = {};
    if (!zoneAggregate) return byMatch;
    points.forEach(function (p) {
      var r = zoneAggregate.rateMap[zoneKey(p.x, p.y)];
      if (r == null) return;
      byMatch[p.matchId] = (byMatch[p.matchId] || 0) + r;
    });
    return byMatch;
  }

  function round1(n) { return Math.round(n * 10) / 10; }

  function renderPlayStyle(overall, points, totalGames, concededPoints, concededSampleGames, matches) {
    var attackContainer = document.getElementById('playstyle-attack');
    var defenseContainer = document.getElementById('playstyle-defense');
    var possContainer = document.getElementById('playstyle-possession');
    var attackChart = document.getElementById('chart-playstyle-attack');
    var defenseChart = document.getElementById('chart-playstyle-defense');
    var possChart = document.getElementById('chart-playstyle-possession');
    attackContainer.replaceChildren();
    defenseContainer.replaceChildren();
    possContainer.replaceChildren();

    if (!totalGames) {
      document.getElementById('playstyle-caption').textContent = '표시할 경기가 없습니다.';
      attackChart.replaceChildren();
      defenseChart.replaceChildren();
      possChart.replaceChildren();
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

    statMini(attackContainer, '평균 득점', fmt1(overall.tally.goalsFor / totalGames), '경기당 실제 득점');
    statMini(attackContainer, '평균 득점 xG값', zoneAggregate ? fmt1(expectedGoals / totalGames) : '계산 중…', '경기당 기대 득점');
    statMini(attackContainer, '결정력',
      zoneAggregate && expectedGoals > 0 ? Math.round(actualGoals / expectedGoals * 100) + '%' : '-',
      '실제 득점 ÷ xG값');
    statMini(attackContainer, '슈팅 정확도', shotAccuracy == null ? '-' : Math.round(shotAccuracy) + '%', '유효슛 비율');
    statMini(attackContainer, '평균 평점', fmt1(overall.averageRating), '팀 스쿼드 평균');
    statMini(attackContainer, '경기당 슈팅', fmt1(points.length / totalGames), '표본 전체 평균');

    // 수비 성향 — "평균 실점 xG값"/"상대 결정력"은 상대도 추적 대상 유저인 매치만 반영된다.
    var concededExpectedGoals = expectedGoalsOf(concededPoints);
    var concededActualGoals = concededPoints.filter(function (p) { return p.goal; }).length;
    statMini(defenseContainer, '평균 실점', fmt1(overall.tally.goalsAgainst / totalGames), '경기당 실제 실점');
    statMini(defenseContainer, '평균 실점 xG값',
      zoneAggregate && concededSampleGames ? fmt1(concededExpectedGoals / concededSampleGames) : '-',
      concededSampleGames ? concededSampleGames + '경기' : '데이터 없음');
    statMini(defenseContainer, '클린시트', fmt(overall.cleanSheets) + '경기', pctOf(overall.cleanSheets, totalGames) + '%');
    statMini(defenseContainer, '다실점 경기(3실점↑)', fmt(overall.multiConcededGames) + '경기', pctOf(overall.multiConcededGames, totalGames) + '%');
    statMini(defenseContainer, '표본', fmt(totalGames) + '경기', '이번 조회 기준');
    statMini(defenseContainer, '상대 결정력',
      zoneAggregate && concededExpectedGoals > 0 ? Math.round(concededActualGoals / concededExpectedGoals * 100) + '%' : '-',
      '상대 실제 득점 ÷ 실점 xG값');

    // ---- 경기별 추이 라인차트 (최근 몇 경기가 아니라 표본 전체, 과거->최신 순) ----
    // API는 최신순으로 내려주므로 왼쪽(과거)->오른쪽(최신)이 되도록 뒤집는다.
    // x축은 날짜 대신 "몇 번째 매치인지"(1, 2, 3, ...) — 각 차트 자기 데이터 기준으로 센다.
    var chronological = matches.slice().reverse();
    function matchIndexLabels(n) {
      var labels = [];
      for (var i = 1; i <= n; i++) labels.push(String(i));
      return labels;
    }

    // 매치별 xG값 — shot-heatmap 포인트를 matchId로 묶어서 계산한다(그룹 안 되면 zoneAggregate가
    // 아직 준비 안 된 것 — 이 함수는 그때도 다시 불려서 자연히 채워진다).
    var xgByMatch = groupExpectedGoalsByMatch(points);
    lineChart(attackChart, [
      { label: '득점', color: 'var(--series-1)', values: chronological.map(function (m) { return m.goalsFor; }) },
      { label: 'xG값', color: 'var(--series-2)', values: chronological.map(function (m) { return round1(xgByMatch[m.matchId] || 0); }) }
    ], { labels: matchIndexLabels(chronological.length), unit: '골', yMin: 0, ariaLabel: '경기별 득점 대 xG값 추이' });

    // "실점" 라인은 상대도 추적 대상이라 xG값을 복원할 수 있는 경기만 골라 같은 x축에 맞춘다
    // (그래야 실점 선과 실점 xG값 선이 같은 경기끼리 비교된다).
    var concededXgByMatch = groupExpectedGoalsByMatch(concededPoints);
    var concededMatchIds = {};
    concededPoints.forEach(function (p) { concededMatchIds[p.matchId] = true; });
    var defenseMatches = chronological.filter(function (m) { return concededMatchIds[m.matchId]; });
    document.getElementById('defense-trend-caption').textContent = defenseMatches.length
      ? '상대도 추적 대상인 ' + defenseMatches.length + '경기만 표시'
      : '표시할 경기가 없습니다.';
    lineChart(defenseChart, [
      { label: '실점', color: 'var(--series-2)', values: defenseMatches.map(function (m) { return m.goalsAgainst; }) },
      { label: '실점 xG값', color: 'var(--series-3)', values: defenseMatches.map(function (m) { return round1(concededXgByMatch[m.matchId] || 0); }) }
    ], { labels: matchIndexLabels(defenseMatches.length), unit: '골', yMin: 0, ariaLabel: '경기별 실점 대 실점 xG값 추이' });

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
      apiGet('/api/v1/records/assist-chains', qs),
      apiGet('/api/v1/records/shot-heatmap', { ouid: qs.ouid, matchType: qs.matchType, seasonId: qs.seasonId, goalsOnly: false }),
      apiGet('/api/v1/records/conceded-shot-heatmap', qs).catch(function () { return { points: [] }; }),
      apiGet('/api/v1/records/recent-matches', { ouid: qs.ouid, matchType: qs.matchType, seasonId: qs.seasonId, page: 0, size: TREND_SAMPLE_SIZE })
        .then(function (page) { return page.content; })
        .catch(function () { return []; })
    ]).then(function (r) {
      if (seq !== loadSeq) return; // 응답 도착 전에 선택이 또 바뀐 경우 — 낡은 응답은 버린다
      setStatus(null);
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
        return { label: p.playerName, value: Math.round(p.overall), color: 'var(--series-1)', sub: playerRoleSub(p) };
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
    verticalBarChart(document.getElementById('chart-goaltime'), timeRows);

    // 플레이 성향 (공격/수비 성향 + 점유율 분포)
    // "평균 실점 xG값"의 표본 경기 수 — conceded-shot-heatmap 포인트에 matchId가 있으니
    // 그 안의 고유 매치 수를 그대로 센다(= 상대도 추적 대상이라 실제로 슛 데이터를 복원한 경기 수).
    var concededMatchIdSet = {};
    d.concededHeatmap.points.forEach(function (p) { concededMatchIdSet[p.matchId] = true; });
    var concededSampleGames = Object.keys(concededMatchIdSet).length;

    lastOverall = overall;
    lastTotalGames = totalGames;
    lastConcededPoints = d.concededHeatmap.points;
    lastConcededSampleGames = concededSampleGames;
    lastMatches = d.matches;
    renderPlayStyle(overall, d.heatmap.points, totalGames, d.concededHeatmap.points, concededSampleGames, d.matches);

    // heatmap (전체 슈팅 + xG값)
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

    // 환상의 콤비 (어시스트 체인) — 상위 5건만
    assistTable(document.getElementById('table-assists'), d.assistChains.slice(0, 5));

    // opponents (행 클릭 시 해당 상대 최근 경기를 그때 불러와 펼침)
    opponentsTable(document.getElementById('table-opponents'), d.opponents);

    // recent matches — 상대 무관, 이 유저의 진짜 최신 경기 (더보기 페이징)
    loadRecentMatches(true);

    // 전체 선수 스탯 그리드 — 유저/매치타입 바뀔 때마다 정렬 상태는 초기화(종합 desc)
    playersGridSort = { col: 'overall', dir: 'desc' };
    renderPlayersGrid(enrichedPlayers);
  }

  init();
})();
