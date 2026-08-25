(function () {
  'use strict';

  // data/dashboard-snapshot.json은 DashboardSnapshotBuilder(백엔드) 배치가 매일 아침
  // dashboard-snapshot.yml(GitHub Actions)로 커밋해둔다 — 이 페이지는 백엔드를 전혀 거치지
  // 않고 raw.githubusercontent.com에서 그 파일을 직접 읽는다(insight-snapshots와 같은
  // 컨벤션). 백엔드가 잠들어 있어도(Render 무료 티어 콜드 스타트) 항상 즉시 뜨는 게 목적.
  var SNAPSHOT_URL = 'https://raw.githubusercontent.com/choiyonghan/fconlineV2/main/data/dashboard-snapshot.json';

  var statusEl = document.getElementById('dashboard-status');
  var updatedEl = document.getElementById('dashboard-updated');
  var cardsEl = document.getElementById('dashboard-cards');
  var noteCard = document.getElementById('ranking-note-card');
  var noteText = document.getElementById('ranking-note-text');

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }
  function fmt1(n) { return (n == null ? 0 : Number(n)).toFixed(1); }
  function fmt2(n) { return (n == null ? 0 : Number(n)).toFixed(2); }
  function pctFmt(n) { return Math.round(n == null ? 0 : n) + '%'; }
  function signed1(n) { var v = fmt1(n); return n >= 0 ? '+' + v : v; }

  fetch(SNAPSHOT_URL, { cache: 'no-store' })
    .then(function (res) { if (!res.ok) throw new Error('HTTP ' + res.status); return res.json(); })
    .then(render)
    .catch(function (err) {
      statusEl.hidden = false;
      statusEl.textContent = '대시보드 스냅샷을 아직 불러올 수 없습니다(' + err.message + '). ' +
        '아직 첫 배치가 안 돌았을 수 있어요 — 개인별 상세 리포트에서 실시간 데이터를 확인해주세요.';
    });

  function render(data) {
    statusEl.hidden = true;

    var d = new Date(data.generatedAt);
    updatedEl.textContent = '업데이트: ' + d.toLocaleString('ko-KR', {
      year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'
    }) + ' · ' + (data.currentSeasonName || '') + ' 기준';

    if (data.aiRankingFailed) {
      noteCard.hidden = false;
      noteText.textContent = data.aiRankingNote || 'AI 랭킹 호출에 실패해 대체 기준으로 정렬했습니다.';
    }

    cardsEl.replaceChildren();
    if (!data.ranking || !data.ranking.length) {
      cardsEl.appendChild(el('p', 'card-empty', '표시할 유저가 없습니다.'));
      return;
    }
    data.ranking.forEach(function (entry) {
      cardsEl.appendChild(buildUserCard(entry, data.users[entry.ouid]));
    });
  }

  function buildUserCard(entry, snapshot) {
    var card = el('div', 'card dashboard-user-card');

    var head = el('div', 'dashboard-user-head');
    head.appendChild(el('div', 'dashboard-rank-badge', '#' + entry.rank));
    var nameCol = el('div', 'dashboard-user-name-col');
    nameCol.appendChild(el('p', 'dashboard-user-name', entry.nickname));
    nameCol.appendChild(el('p', 'dashboard-user-reason', entry.reason || ''));
    head.appendChild(nameCol);
    card.appendChild(head);

    var hasCustom = snapshot && snapshot.custom && snapshot.custom.games > 0;
    var hasSeason = snapshot && snapshot.season && snapshot.season.games > 0;

    if (!hasCustom && !hasSeason) {
      card.appendChild(el('p', 'card-empty', '표본 경기가 없습니다.'));
      return card;
    }

    var tabs = el('div', 'segmented dashboard-scope-tabs');
    tabs.setAttribute('role', 'group');
    var customBtn = document.createElement('button');
    customBtn.type = 'button'; customBtn.textContent = '모두의 커스텀'; customBtn.dataset.scope = 'custom';
    var seasonBtn = document.createElement('button');
    seasonBtn.type = 'button'; seasonBtn.textContent = '현재시즌'; seasonBtn.dataset.scope = 'season';
    tabs.appendChild(customBtn);
    tabs.appendChild(seasonBtn);
    card.appendChild(tabs);

    var body = el('div', 'dashboard-scope-body');
    card.appendChild(body);

    function activate(scope) {
      customBtn.setAttribute('aria-pressed', String(scope === 'custom'));
      seasonBtn.setAttribute('aria-pressed', String(scope === 'season'));
      var summary = scope === 'custom' ? snapshot.custom : snapshot.season;
      renderScopeBody(body, summary);
    }
    customBtn.addEventListener('click', function () { activate('custom'); });
    seasonBtn.addEventListener('click', function () { activate('season'); });

    if (!hasSeason) { customBtn.disabled = false; seasonBtn.disabled = true; activate('custom'); }
    else if (!hasCustom) { customBtn.disabled = true; activate('season'); }
    else { activate('season'); }

    return card;
  }

  function renderScopeBody(container, s) {
    container.replaceChildren();
    if (!s || s.games === 0) {
      container.appendChild(el('p', 'card-empty', '표본 경기가 없습니다.'));
      return;
    }

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
    mini('결정력', signed1(s.finishing), '실제 득점 − xG값');
    mini('경기당 슈팅', fmt1(s.shotsPerGame));
    mini('평균실점', fmt1(s.avgGoalsAgainst));
    mini('평균실점 xG값', s.avgGoalsAgainstXg == null ? '-' : fmt1(s.avgGoalsAgainstXg),
      s.concededSampleGames ? s.concededSampleGames + '경기 표본' : '데이터 없음');
    mini('클린시트', s.cleanSheets + '경기', pctFmt(s.cleanSheetPct));
    mini('다실점(3실점↑)', s.multiConcededGames + '경기', pctFmt(s.multiConcededPct));
    mini('저점유(45%↓)', s.lowPossessionGames + '경기', pctFmt(s.lowPossessionPct));
    mini('고점유(55%↑)', s.highPossessionGames + '경기', pctFmt(s.highPossessionPct));
    mini('균형점유(46~54%)', s.balancedPossessionGames + '경기', pctFmt(s.balancedPossessionPct));
    mini('평균점유율', Math.round(s.avgPossession) + '%');
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
    top3Wrap.appendChild(top3Block('⚽ 최다골', s.topGoals));
    top3Wrap.appendChild(top3Block('🅰️ 최다도움', s.topAssists));
    top3Wrap.appendChild(top3Block('🔥 최다 공격포인트', s.topAttackPoints));
    top3Wrap.appendChild(top3Block('🛡️ 최다 태클+인터셉트', s.topDefense));
    top3Wrap.appendChild(top3Block('🧤 최다 선방', s.topSaves));
    container.appendChild(top3Wrap);
  }

  function top3Block(title, list) {
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
})();
