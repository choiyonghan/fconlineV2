(function () {
  'use strict';

  var BASE_URL = 'https://fconlinev2-backend.onrender.com';

  // ---------------- 공용 헬퍼 (report.js와 동일 — 이 사이트는 빌드 도구가 없어 파일 간 공유가
  // 안 돼서 각 페이지가 자기 몫만큼 복사해서 쓴다. report.js를 고칠 때 여기도 같이 봐야 하는
  // 함수는 각자 주석에 표시해뒀다. 이 페이지는 report.js의 CSR 패턴(화면마다 API를 따로 호출해
  // Promise.all로 병렬 로딩)을 그대로 따른다 — 요청) ----------------

  var tooltip = document.getElementById('tooltip');
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

  var YARD_TO_METER = 0.9144; // Nexon 드리블 거리 원본이 야드 단위라 표시 시점에 미터로 환산한다.

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

  function expectedGoalsOf(points) {
    var sum = 0;
    points.forEach(function (p) {
      var r = calcXg(p.x, p.y, p.shootType);
      if (r != null) sum += r;
    });
    return sum;
  }

  /** 매치별 xG값 추이(바이오리듬 지능 축)용 — 슛 포인트를 matchId로 묶어 매치당 xG값 합을 낸다. */
  function groupExpectedGoalsByMatch(points) {
    var byMatch = {};
    points.forEach(function (p) {
      var r = calcXg(p.x, p.y, p.shootType);
      if (r == null) return;
      byMatch[p.matchId] = (byMatch[p.matchId] || 0) + r;
    });
    return byMatch;
  }

  function pctOf(count, total) {
    return total > 0 ? Math.round((count / total) * 100) : 0;
  }

  var PERIOD_OFFSET_MINUTES = [0, 0, 45, 90, 105, 120];
  var PERIOD_KO = { 1: '전반', 2: '후반', 3: '연장 전반', 4: '연장 후반', 5: '승부차기' };
  function absoluteMinuteOf(minutes, period) {
    if (minutes == null) return null;
    var offset = (period != null && period >= 1 && period < PERIOD_OFFSET_MINUTES.length) ? PERIOD_OFFSET_MINUTES[period] : 0;
    return minutes + offset;
  }

  var RESULT_KO = { GOAL: '골', ON_TARGET: '온타겟', OFF_TARGET: '오프타겟' };

  // ---------------- 차트/표 렌더러 (report.js의 동명 함수와 동일 — barChart/divergingBarChart/
  // lineChart/drawPitchOutline/pitchHeatmap/assistTable/topAssistDuos/assistDuoTable/statMini) ----------------

  function barChart(container, rows, opts) {
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

  /**
   * 득점/실점 대비 시간대 분포 차트 — 같은 시간대 버킷 축을 위/아래로 공유한다.
   * upRows(득점, 파란색)는 기준선 위로, downRows(실점, 빨간색)는 기준선 아래로 자란다.
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

  var PITCH_NS = 'http://www.w3.org/2000/svg';
  var PITCH_W = 400, PITCH_H = 260;

  /** 피치 윤곽(외곽선/하프라인/센터서클/페널티박스×2)만 그려서 svg에 붙인다. */
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

    var misses = points.filter(function (p) { return !p.goal; });
    var goals = points.filter(function (p) { return p.goal; });

    function drawShot(p, isGoal) {
      var cx = (p.x * W).toFixed(1);
      var cy = (p.y * H).toFixed(1);
      var c = document.createElementNS(svgNS, 'circle');
      c.setAttribute('cx', cx);
      c.setAttribute('cy', cy);
      c.setAttribute('r', isGoal ? 5 : 3);
      c.setAttribute('class', isGoal ? (p.mine === false ? 'goal-dot-conceded' : 'goal-dot') : 'miss-dot');
      c.tabIndex = 0;
      var xgLabel = p.xg != null ? p.xg.toFixed(2) + '골' : null;
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

  function assistTable(container, chains) {
    container.replaceChildren();
    if (!chains.length) { container.appendChild(el('p', 'card-empty', '기록된 어시스트 조합이 없습니다.')); return; }
    var table = document.createElement('table');
    var thead = document.createElement('thead');
    var htr = document.createElement('tr');
    ['어시스트', '', '득점', '골 수'].forEach(function (h, i) {
      htr.appendChild(el('th', i === 3 ? 'num' : '', h));
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
   * chains(방향별 어시스트→득점 조합)를 사람 쌍(순서 무관) 기준으로 합산해 TOP N을 만든다.
   * 예: A→B 10골 + B→A 5골 → "A · B" 조합 15골.
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

  /**
   * tooltip은 문자열(1줄) 또는 문자열 배열(여러 줄) — 있으면 hover/focus 시 showTip으로 보여준다.
   * onClick을 주면 박스 자체도 버튼처럼 클릭/엔터 가능해진다(바이오리듬 스탯 박스 클릭용).
   */
  function statMini(container, label, value, sub, tooltip2, onClick) {
    var box = el('div', 'stat-mini');
    box.appendChild(el('p', 'stat-mini-label', label));
    box.appendChild(el('div', 'stat-mini-value', value));
    if (sub) box.appendChild(el('div', 'stat-mini-sub', sub));
    if (tooltip2) {
      var lines = Array.isArray(tooltip2) ? tooltip2 : [tooltip2];
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

  // ---------------- 선수 기여도(TOP7 차트 + 전체 그리드, report.js의 enrichPlayers/renderPlayersGrid와 동일) ----------------

  var playersGridSort = { col: 'overall', dir: 'desc' };
  var playersGridMode = 'total'; // 'total'(총합) | 'avg'(경기당 평균)
  var currentPlayersList = [];

  /**
   * 백엔드는 raw 합계만 주고, 비율/100점 만점 점수는 여기서 계산한다 — "1등이 100점"이 되려면
   * 그룹(현재 검색 결과의 전체 선수) 안에서의 최댓값을 알아야 하는데 그건 이 목록이 다 모여야
   * 알 수 있어서다. report.js의 동명 함수와 동일한 공식.
   */
  function enrichPlayers(players) {
    var maxScore = 0, maxAttack = 0, maxDefense = 0;
    var enriched = players.map(function (p) {
      var copy = {};
      for (var k in p) copy[k] = p[k];
      copy.attackPoints = p.goals + p.assists;
      copy.finishing = p.goals - p.xg;
      copy.shootAccuracy = p.shootTotal > 0 ? (p.effectiveShoot / p.shootTotal * 100) : null;
      copy.passAccuracy = p.passTry > 0 ? (p.passSuccess / p.passTry * 100) : null;
      copy.dribbleRate = p.dribbleTry > 0 ? (p.dribbleSuccess / p.dribbleTry * 100) : null;
      copy.aerialRate = p.aerialTry > 0 ? (p.aerialSuccess / p.aerialTry * 100) : null;
      var looksLikeKeeper = p.appearances > 0 && (p.saves / p.appearances) >= 1;
      copy.savePct = looksLikeKeeper ? (p.saves / (p.saves + (p.goalsAgainst || 0)) * 100) : null;
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

  var PLAYERS_GRID_AVG_KEYS = [
    'goals', 'assists', 'attackPoints', 'xg', 'xa', 'finishing', 'saves',
    'shootTotal', 'effectiveShoot', 'passTry', 'passSuccess',
    'dribbleTry', 'dribbleSuccess', 'dribbleDistance', 'aerialTry', 'aerialSuccess',
    'tackles', 'intercepts', 'blocks'
  ];

  function renderPlayersGrid(players) {
    currentPlayersList = players;
    var container = document.getElementById('table-allplayers');
    container.replaceChildren();
    if (!players.length) { container.appendChild(el('p', 'card-empty', '표시할 선수 데이터가 없습니다.')); return; }

    var working = players.map(function (p) {
      var copy = {};
      for (var k in p) copy[k] = p[k];
      if (playersGridMode === 'avg' && p.appearances > 0) {
        PLAYERS_GRID_AVG_KEYS.forEach(function (key) { copy[key] = copy[key] / p.appearances; });
      }
      return copy;
    });

    var cols = [
      { key: 'playerName', label: '선수', numeric: false },
      { key: 'appearances', label: '출전', numeric: true },
      { key: 'attackRating', label: '공격력', numeric: true },
      { key: 'defenseRating', label: '수비력', numeric: true },
      { key: 'goals', label: '골', numeric: true },
      { key: 'assists', label: '도움', numeric: true },
      { key: 'attackPoints', label: '공격P', numeric: true },
      { key: 'xg', label: 'xG', numeric: true },
      { key: 'xa', label: 'xA', numeric: true },
      { key: 'finishing', label: '결정력', numeric: true },
      { key: 'saves', label: '세이브', numeric: true },
      { key: 'savePct', label: '선방률', numeric: true },
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

    var sorted = working.slice().sort(function (a, b) {
      var av = a[playersGridSort.col], bv = b[playersGridSort.col];
      var cmp;
      if (typeof av === 'string') {
        cmp = av.localeCompare(bv, 'ko');
      } else {
        cmp = (av == null ? -1 : av) - (bv == null ? -1 : bv);
      }
      return playersGridSort.dir === 'asc' ? cmp : -cmp;
    });

    var table = document.createElement('table');
    table.className = 'stats-grid-table';
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
    var nf = playersGridMode === 'avg' ? fmt1 : fmt;

    var tbody = document.createElement('tbody');
    sorted.forEach(function (p) {
      var tr = document.createElement('tr');
      var nameTd = el('td', 'name-cell');
      nameTd.appendChild(playerNameBadge(p.spId, p.playerName));
      tr.appendChild(nameTd);
      tr.appendChild(el('td', 'num', fmt(p.appearances)));
      tr.appendChild(el('td', 'num', fmt(Math.round(p.attackRating))));
      tr.appendChild(el('td', 'num', fmt(Math.round(p.defenseRating))));
      tr.appendChild(el('td', 'num', nf(p.goals)));
      tr.appendChild(el('td', 'num', nf(p.assists)));
      tr.appendChild(el('td', 'num', nf(p.attackPoints)));
      tr.appendChild(el('td', 'num', fmt1(p.xg)));
      tr.appendChild(el('td', 'num', fmt1(p.xa)));
      tr.appendChild(el('td', 'num', (p.finishing >= 0 ? '+' : '') + fmt1(p.finishing)));
      tr.appendChild(el('td', 'num', nf(p.saves)));
      tr.appendChild(el('td', 'num', p.savePct == null ? '-' : fmt1(p.savePct) + '%'));
      tr.appendChild(el('td', 'num', nf(p.shootTotal)));
      tr.appendChild(el('td', 'num', nf(p.effectiveShoot)));
      tr.appendChild(el('td', 'num', pct(p.shootAccuracy)));
      tr.appendChild(el('td', 'num', pct(p.passAccuracy)));
      tr.appendChild(el('td', 'num', nf(p.passTry)));
      tr.appendChild(el('td', 'num', nf(p.passSuccess)));
      tr.appendChild(el('td', 'num', pct(p.dribbleRate)));
      tr.appendChild(el('td', 'num', nf(p.dribbleTry)));
      tr.appendChild(el('td', 'num', nf(p.dribbleSuccess)));
      var dribbleMeters = p.dribbleDistance * YARD_TO_METER;
      tr.appendChild(el('td', 'num', p.dribbleDistance ? (playersGridMode === 'avg' ? fmt1(dribbleMeters) : fmt(Math.round(dribbleMeters))) + 'm' : '0m'));
      tr.appendChild(el('td', 'num', pct(p.aerialRate)));
      tr.appendChild(el('td', 'num', nf(p.aerialTry)));
      tr.appendChild(el('td', 'num', nf(p.aerialSuccess)));
      tr.appendChild(el('td', 'num', nf(p.tackles)));
      tr.appendChild(el('td', 'num', nf(p.intercepts)));
      tr.appendChild(el('td', 'num', nf(p.blocks)));
      tr.appendChild(el('td', 'num', p.avgRating == null ? '-' : fmt1(p.avgRating)));
      tr.appendChild(el('td', 'num', fmt(Math.round(p.overall))));
      for (var i = 1; i < tr.children.length; i++) {
        tr.children[i].setAttribute('data-label', cols[i].label);
      }
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    container.appendChild(table);
  }

  var playersGridTotalBtn = document.getElementById('players-grid-total-btn');
  var playersGridAvgBtn = document.getElementById('players-grid-avg-btn');
  function setPlayersGridMode(mode) {
    playersGridMode = mode;
    playersGridTotalBtn.setAttribute('aria-pressed', String(mode === 'total'));
    playersGridAvgBtn.setAttribute('aria-pressed', String(mode === 'avg'));
    renderPlayersGrid(currentPlayersList);
  }
  playersGridTotalBtn.addEventListener('click', function () { setPlayersGridMode('total'); });
  playersGridAvgBtn.addEventListener('click', function () { setPlayersGridMode('avg'); });

  document.getElementById('players-more-btn').addEventListener('click', function () {
    var card = document.getElementById('players-grid-card');
    card.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'start' });
    card.classList.remove('grid-flash');
    void card.offsetWidth;
    card.classList.add('grid-flash');
  });

  // ---------------- 슈팅 위치 & xG/xA 타일 (report.js의 renderShotPitch/updateXgTile/updateXaTile과 동일) ----------------

  function renderShotPitch(points, concededPoints) {
    var actualGoalsNow = points.filter(function (p) { return p.goal; }).length;
    var concededGoalsNow = concededPoints.filter(function (p) { return p.goal; }).length;
    document.getElementById('heatmap-caption').textContent =
      '내 슈팅 ' + points.length + '건 중 득점 ' + actualGoalsNow + '건(우측, 상대 골대 방향) · ' +
      '실점 슈팅 ' + concededPoints.length + '건 중 실점 ' + concededGoalsNow + '건(좌측, 내 골대 방향)';
    var shotsForPitch = points.map(function (p) {
      var withXg = {};
      for (var k in p) withXg[k] = p[k];
      withXg.xg = calcXg(p.x, p.y, p.shootType);
      withXg.mine = true;
      return withXg;
    }).concat(concededPoints.map(function (p) {
      return {
        x: p.x != null ? 1 - p.x : null, y: p.y != null ? 1 - p.y : null,
        goal: p.goal, shootType: p.shootType, result: p.result,
        xg: calcXg(p.x, p.y, p.shootType),
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

  function updateXaTile(points) {
    var valueEl = document.getElementById('xa-tile-value');
    var subEl = document.getElementById('xa-tile-sub');
    if (!valueEl) return;
    var actualAssists = points.filter(function (p) { return p.goal; }).length;
    var expectedAssists = expectedGoalsOf(points);
    var diff = actualAssists - expectedAssists;
    valueEl.textContent = actualAssists + ' : ' + fmt1(expectedAssists);
    subEl.textContent = (diff > 0 ? '+' : '') + fmt1(diff) + (diff >= 0 ? ' 기대 이상 창출' : ' 기대 이하 창출');
    subEl.style.color = diff >= 0 ? 'var(--success-text)' : 'var(--status-critical)';
  }

  // ---------------- 플레이 성향 (report.js의 renderPlayStyle과 동일) ----------------

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

  function renderPlayStyle(overall, points, totalGames, concededPoints, concededSampleGames, matches, assistedPoints) {
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
    document.getElementById('playstyle-caption').textContent = totalGames + '경기 표본 기준';

    matches = matches || [];
    concededPoints = concededPoints || [];
    concededSampleGames = concededSampleGames || 0;
    assistedPoints = assistedPoints || [];

    var actualGoals = points.filter(function (p) { return p.goal; }).length;
    var expectedGoals = expectedGoalsOf(points);
    var expectedAssists = expectedGoalsOf(assistedPoints);
    var onTarget = points.filter(function (p) { return p.result !== 'OFF_TARGET'; }).length;
    var shotAccuracy = points.length ? (onTarget / points.length * 100) : null;

    statMini(attackContainer, '평균 득점', fmt1(overall.tally.goalsFor / totalGames), '경기당 실제 득점 · 총 ' + fmt(overall.tally.goalsFor) + '골');
    statMini(attackContainer, '평균 득점 xG값', fmt1(expectedGoals / totalGames), '경기당 기대 득점 · 총 ' + fmt1(expectedGoals) + '골');
    statMini(attackContainer, '평균 어시 xA값', fmt1(expectedAssists / totalGames), '경기당 기대 어시스트 · 총 ' + fmt1(expectedAssists) + '골');
    statMini(attackContainer, '결정력',
      (actualGoals - expectedGoals >= 0 ? '+' : '') + fmt1(actualGoals - expectedGoals),
      '실제 득점 − xG값 (양수면 기대 이상)');
    statMini(attackContainer, '슈팅 정확도', shotAccuracy == null ? '-' : Math.round(shotAccuracy) + '%',
      '유효슛 비율 · 총 ' + fmt(points.length) + '슈팅');
    statMini(attackContainer, '평균 평점', fmt1(overall.averageRating), '팀 스쿼드 평균');
    statMini(attackContainer, '경기당 슈팅', fmt1(points.length / totalGames), '표본 전체 평균 · 총 ' + fmt(points.length) + '슈팅');

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
    var tackleRate = overall.tackleTryTotal ? Math.round(overall.tackleSuccessTotal / overall.tackleTryTotal * 100) + '%' : '-';
    var blockRate = overall.blockTryTotal ? Math.round(overall.blockSuccessTotal / overall.blockTryTotal * 100) + '%' : '-';
    statMini(defenseContainer, '태클 시도/성공', fmt(overall.tackleTryTotal) + ' / ' + fmt(overall.tackleSuccessTotal), '성공률 ' + tackleRate);
    statMini(defenseContainer, '블락 시도/성공', fmt(overall.blockTryTotal) + ' / ' + fmt(overall.blockSuccessTotal), '성공률 ' + blockRate);
    statMini(defenseContainer, '표본', fmt(totalGames) + '경기', '이번 조회 기준');

    statMini(dirtyContainer, '게임 일시정지', fmt(overall.systemPauseTotal) + '회', '표본 전체 합계');
    statMini(dirtyContainer, '파울', fmt(overall.foulTotal) + '회', '표본 전체 합계');
    statMini(dirtyContainer, '경고(옐로카드)', fmt(overall.yellowCards) + '장', '표본 전체 합계');
    statMini(dirtyContainer, '퇴장(레드카드)', fmt(overall.redCards) + '장', '표본 전체 합계');

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

    var chronological = matches.slice().reverse();
    function matchIndexLabels(n) {
      var labels = [];
      for (var i = 1; i <= n; i++) labels.push(String(i));
      return labels;
    }

    lineChart(attackChart, [
      { label: '득점', color: 'var(--series-1)', values: chronological.map(function (m) { return m.goalsFor; }) }
    ], { labels: matchIndexLabels(chronological.length), unit: '골', yMin: 0, ariaLabel: '경기별 득점 추이' });

    document.getElementById('defense-trend-caption').textContent = '표본 전체 ' + chronological.length + '경기';
    lineChart(defenseChart, [
      { label: '실점', color: 'var(--series-2)', values: chronological.map(function (m) { return m.goalsAgainst; }) }
    ], { labels: matchIndexLabels(chronological.length), unit: '골', yMin: 0, ariaLabel: '경기별 실점 추이' });

    lineChart(passChart, [
      { label: '패스 시도', color: 'var(--series-1)', values: chronological.map(function (m) { return m.passTry || 0; }) },
      { label: '패스 성공', color: 'var(--status-good)', values: chronological.map(function (m) { return m.passSuccess || 0; }) }
    ], { labels: matchIndexLabels(chronological.length), unit: '회', yMin: 0, ariaLabel: '경기별 패스 시도 대 성공 추이' });

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
        values: chronological.map(function (m) { return (m.possession != null && m.possession > 0) ? m.possession : 50; })
      }
    ], { labels: matchIndexLabels(chronological.length), unit: '%', yMin: 0, yMax: 100, refLines: [45, 55], ariaLabel: '점유율 추이' });
  }

  // ---------------- 바이오리듬 (report.js의 renderBiorhythm과 동일) ----------------

  var BIORHYTHM_WINDOW = 5;
  var BIORHYTHM_RESULT_POINT = { '승': 3, '무': 1, '패': 0 };
  var biorhythmActiveAxis = '피지컬';
  var BIORHYTHM_TOOLTIPS = {
    '피지컬': ['🏃 피지컬', '경기별 팀 평균 평점(10점 만점)을', BIORHYTHM_WINDOW + '경기 이동평균으로 부드럽게 만든 뒤,', '이번 시즌 표본 안 최저=0점·최고=100점으로', '상대 환산한 값이에요.'],
    '멘탈': ['🧠 멘탈', '경기 결과(승 3점·무 1점·패 0점)를', BIORHYTHM_WINDOW + '경기 이동평균으로 부드럽게 만든 뒤,', '이번 시즌 표본 안 최저=0점·최고=100점으로', '상대 환산한 값이에요. 최근 승리가 많을수록 올라가요.'],
    '지능': ['🎯 지능', '결정력(경기별 실제 득점 − xG값)을', BIORHYTHM_WINDOW + '경기 이동평균으로 부드럽게 만든 뒤,', '이번 시즌 표본 안 최저=0점·최고=100점으로', '상대 환산한 값이에요. 기대보다 골을 더 넣을수록 올라가요.'],
    '종합': ['🌊 종합 컨디션', '피지컬·멘탈·지능 3개 점수의 평균이에요.']
  };

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
    caption.textContent = '이번 조회 전체 ' + matches.length + '경기 기준, ' + BIORHYTHM_WINDOW + '경기 이동평균으로 흐름만 부드럽게 봅니다(실제 능력치가 아니라 재미용 지표예요).';

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

    function setActiveAxis(label) { biorhythmActiveAxis = label; drawBiorhythmChart(); }

    var physicalBox = statMini(summary, '🏃 피지컬', latestPhysical + '점', '평점 흐름 · 표본 내 상대적 위치',
      BIORHYTHM_TOOLTIPS['피지컬'], function () { setActiveAxis('피지컬'); });
    var mentalBox = statMini(summary, '🧠 멘탈', latestMental + '점', '승무패 흐름 · 표본 내 상대적 위치',
      BIORHYTHM_TOOLTIPS['멘탈'], function () { setActiveAxis('멘탈'); });
    var intellectBox = statMini(summary, '🎯 지능', latestIntellect + '점', '결정력(득점−xG값) 흐름 · 표본 내 상대적 위치',
      BIORHYTHM_TOOLTIPS['지능'], function () { setActiveAxis('지능'); });
    var overallBox = statMini(summary, '🌊 종합 컨디션', overallScore + '점', biorhythmMoodLabel(overallScore),
      BIORHYTHM_TOOLTIPS['종합'], function () { setActiveAxis(null); });
    var axisBoxes = { '피지컬': physicalBox, '멘탈': mentalBox, '지능': intellectBox };
    var axisColors = { '피지컬': 'var(--series-1)', '멘탈': 'var(--series-2)', '지능': 'var(--series-3)' };
    Object.keys(axisBoxes).forEach(function (key) { axisBoxes[key].style.setProperty('--stat-active-color', axisColors[key]); });

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
        ariaLabel: '전체 바이오리듬 추이',
        activeLabel: biorhythmActiveAxis,
        legendTooltips: BIORHYTHM_TOOLTIPS,
        onLegendClick: setActiveAxis
      });
    }
    drawBiorhythmChart();
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

  // 백엔드 최대치와 동일(SearchFacade.MAX_LIMIT) — Nexon 매치 목록 API 자체가 한 유저당
  // 그 이상은 안 준다(요청, NexonApiClient.findRecentMatchIds 주석 참고).
  var SEARCH_MATCH_LIMIT = 100;

  // ---------------- 검색 로딩바 (요청 — 최대 100건이라 매치 상세만 30~40초 걸릴 수 있어
  // "몇 건째"를 실시간으로 보여준다) ----------------
  var progressEl = document.getElementById('search-progress');
  var progressFillEl = document.getElementById('search-progress-fill');
  var progressTextEl = document.getElementById('search-progress-text');
  var progressTimer = null;

  function pollProgress(nickname, matchType, seq) {
    apiGet('/api/v1/search/players/progress', { nickname: nickname, matchType: matchType }).then(function (p) {
      if (seq !== searchSeq || !progressTimer) return; // 검색이 이미 끝났거나(폴링 정지) 낡은 응답
      if (!p || !p.total) {
        progressFillEl.style.width = '3%';
        progressTextEl.textContent = '매치 목록을 확인하는 중…';
        return;
      }
      var pct = Math.round((p.fetched / p.total) * 100);
      progressFillEl.style.width = Math.max(pct, 3) + '%';
      progressTextEl.textContent = p.fetched + ' / ' + p.total + '경기 상세를 Nexon에서 불러오는 중…';
    }).catch(function () { /* 폴링 실패는 조용히 무시 — 다음 틱에 다시 시도 */ });
  }

  function startProgressPolling(nickname, matchType, seq) {
    stopProgressPolling();
    progressEl.hidden = false;
    progressFillEl.style.width = '0%';
    progressTextEl.textContent = '매치 목록을 확인하는 중…';
    pollProgress(nickname, matchType, seq);
    progressTimer = setInterval(function () { pollProgress(nickname, matchType, seq); }, 700);
  }

  function stopProgressPolling() {
    if (progressTimer) { clearInterval(progressTimer); progressTimer = null; }
    progressEl.hidden = true;
  }

  var searchSeq = 0;
  form.addEventListener('submit', function (e) {
    e.preventDefault();
    var nickname = nicknameInput.value.trim();
    if (!nickname) return;

    var seq = ++searchSeq;
    resultEl.hidden = true;
    submitBtn.disabled = true;
    setStatus('"' + nickname + '" 최근 경기를 Nexon에서 직접 불러오는 중입니다… (최대 ' + SEARCH_MATCH_LIMIT + '경기라 몇십 초 걸릴 수 있어요)');
    startProgressPolling(nickname, currentMatchType, seq);

    // CSR — report.js와 동일하게 화면(API)마다 따로 호출해 Promise.all로 병렬 로딩한다(요청).
    // 전부 같은 (nickname, matchType, limit) 조합이라 백엔드 SearchMatchDetailCache가 매치
    // 상세를 공유해서, 여기서 여러 API를 동시에 불러도 Nexon 매치 상세 호출은 매치당 한 번만
    // 나간다(SearchMatchDetailCache의 sync=true 캐시).
    var qs = { nickname: nickname, matchType: currentMatchType, limit: SEARCH_MATCH_LIMIT };
    Promise.all([
      apiGet('/api/v1/search/players/overall', qs),
      apiGet('/api/v1/search/players/players', qs),
      apiGet('/api/v1/search/players/shot-heatmap', { nickname: qs.nickname, matchType: qs.matchType, limit: qs.limit, goalsOnly: false }),
      apiGet('/api/v1/search/players/conceded-shot-heatmap', qs).catch(function () { return { points: [] }; }),
      apiGet('/api/v1/search/players/assisted-shot-heatmap', qs).catch(function () { return { points: [] }; }),
      apiGet('/api/v1/search/players/assist-chains', { nickname: qs.nickname, matchType: qs.matchType, limit: qs.limit, chainLimit: 100 }),
      apiGet('/api/v1/search/players/recent-matches', qs)
    ]).then(function (r) {
      if (seq !== searchSeq) return;
      setStatus(null);
      stopProgressPolling();
      submitBtn.disabled = false;
      renderResult(nickname, currentMatchType, {
        overall: r[0], players: r[1], heatmap: r[2], concededHeatmap: r[3],
        assistedHeatmap: r[4], assistChains: r[5], matches: r[6]
      });
    }).catch(function (err) {
      if (seq !== searchSeq) return;
      stopProgressPolling();
      submitBtn.disabled = false;
      setStatus('검색 실패 — ' + (err && err.message ? err.message : '알 수 없는 오류') + ' (닉네임 철자를 확인해 주세요)', true);
    });
  });

  // ---------------- 결과 렌더링 ----------------
  function renderResult(nickname, matchType, d) {
    resultEl.hidden = false;
    var overall = d.overall;
    var totalGames = overall.tally.win + overall.tally.draw + overall.tally.lose;

    renderHead(nickname, matchType, totalGames);
    renderTiles(overall, totalGames, d.heatmap.points, d.assistedHeatmap.points);

    var enrichedPlayers = enrichPlayers(d.players);
    var top7Rows = enrichedPlayers.slice()
      .sort(function (a, b) { return b.overall - a.overall; })
      .slice(0, 7)
      .map(function (p) {
        return { label: p.playerName, spId: p.spId, value: Math.round(p.overall), color: 'var(--series-1)', sub: playerRoleSub(p) };
      });
    barChart(document.getElementById('chart-players'), top7Rows, { unit: '점' });

    var typeRows = overall.goalTypeDistribution
      .filter(function (t) { return t.count > 0; })
      .sort(function (a, b) { return b.count - a.count; })
      .map(function (t) { return { label: t.shootType, value: t.count, color: 'var(--series-1)' }; });
    barChart(document.getElementById('chart-goaltypes'), typeRows, { unit: '골' });

    var timeRows = overall.goalTimeDistribution.map(function (t) { return { label: t.periodLabel, value: t.count }; });
    var concededTimeRows = (overall.concededGoalTimeDistribution || []).map(function (t) { return { label: t.periodLabel, value: t.count }; });
    divergingBarChart(document.getElementById('chart-goaltime'), timeRows, concededTimeRows);

    var concededMatchIdSet = {};
    d.concededHeatmap.points.forEach(function (p) { concededMatchIdSet[p.matchId] = true; });
    var concededSampleGames = Object.keys(concededMatchIdSet).length;

    renderPlayStyle(overall, d.heatmap.points, totalGames, d.concededHeatmap.points, concededSampleGames, d.matches,
      d.assistedHeatmap.points);
    renderBiorhythm(overall, d.matches, d.heatmap.points, totalGames);

    renderShotPitch(d.heatmap.points, d.concededHeatmap.points);
    updateXgTile(d.heatmap.points);
    updateXaTile(d.assistedHeatmap.points);

    assistTable(document.getElementById('table-assists'), d.assistChains.slice(0, 5));
    assistDuoTable(document.getElementById('table-assist-duos'), topAssistDuos(d.assistChains, 5));

    playersGridSort = { col: 'overall', dir: 'desc' };
    playersGridMode = 'total';
    playersGridTotalBtn.setAttribute('aria-pressed', 'true');
    playersGridAvgBtn.setAttribute('aria-pressed', 'false');
    renderPlayersGrid(enrichedPlayers);

    renderMatches(overall.ouid, matchType, totalGames, d.matches);
  }

  function renderHead(nickname, matchType, totalGames) {
    var head = document.getElementById('search-result-head');
    head.replaceChildren();
    head.appendChild(el('span', 'search-result-nickname', nickname));
    var mtLabel = matchType === 'CUSTOM' ? '커스텀' : '공식전';
    head.appendChild(el('span', 'search-result-meta', mtLabel + ' 최근 ' + totalGames + '경기 기준'));
  }

  function renderTiles(overall, totalGames, heatmapPoints, assistedPoints) {
    var tiles = document.getElementById('search-tiles');
    tiles.replaceChildren();

    function tile(label, value, sub, subColor, valueId, subId) {
      var box = el('div', 'tile');
      box.appendChild(el('p', 'tile-label', label));
      var valueEl = el('div', 'tile-value', value);
      if (valueId) valueEl.id = valueId;
      box.appendChild(valueEl);
      if (sub !== undefined) {
        var subEl = el('div', 'tile-sub', sub);
        if (subId) subEl.id = subId;
        if (subColor) subEl.style.color = subColor;
        box.appendChild(subEl);
      }
      tiles.appendChild(box);
    }

    var t = overall.tally;
    var winRate = totalGames ? Math.round((t.win / totalGames) * 100) : 0;
    tile('전적 (승-무-패)', t.win + '-' + t.draw + '-' + t.lose, totalGames + '경기 · 승률 ' + winRate + '%');
    tile('득점 - 실점', t.goalsFor + ' : ' + t.goalsAgainst,
      '득실차 ' + ((t.goalsFor - t.goalsAgainst) >= 0 ? '+' : '') + (t.goalsFor - t.goalsAgainst));
    tile('평균 평점', overall.averageRating == null ? '-' : fmt1(overall.averageRating));
    tile('평균 점유율', overall.possessionAverage == null ? '-' : Math.round(overall.possessionAverage) + '%');
    // xG/xA 타일 값은 updateXgTile/updateXaTile이 heatmap 응답 도착 후 채운다 — report.js와 동일.
    tile('실제 득점 vs 실제 xG값', '—', '', null, 'xg-tile-value', 'xg-tile-sub');
    tile('실제 어시스트 vs 실제 xA값', '—', '', null, 'xa-tile-value', 'xa-tile-sub');
  }

  function renderMatches(ouid, matchType, totalGames, matches) {
    var caption = document.getElementById('search-matches-caption');
    caption.textContent = '상대 무관, 최신순입니다. 행을 클릭하면 상세 정보가 열립니다.';

    var list = document.getElementById('search-matches-list');
    list.className = 'search-match-list';
    list.replaceChildren();
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
      var openFn = function () { openMatchModal(ouid, m); };
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
