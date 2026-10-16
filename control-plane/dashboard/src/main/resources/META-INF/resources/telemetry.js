'use strict';

const COLORS = { green: '#2e9e5b', amber: '#e0a526', red: '#d64545', accent: '#8ab4f8', muted: '#9aa0a6',
  line: '#3c4043', none: '#2a2d34' };
const RING_SECONDS = 600;
const state = { snapshots: [], minutes: 5, charts: [], loaded: false, drawQueued: false };

main();

async function main() {
  const topology = await (await fetch('topology.json')).json();
  buildPage(topology.edges);
  const select = document.getElementById('window-select');
  select.addEventListener('change', () => { state.minutes = Number(select.value); scheduleDraw(); });
  new ResizeObserver(() => { resizeCharts(); scheduleDraw(); }).observe(document.getElementById('graphs'));
  connectLive();
  loadHistory();
}

/** Initial history, loaded once; the live socket keeps it current afterwards. */
async function loadHistory() {
  try {
    const response = await fetch(`api/telemetry/windows?from=${Date.now() - RING_SECONDS * 1000}`);
    if (!response.ok) return;
    const history = await response.json();
    const live = state.snapshots;
    state.snapshots = history.concat(live.filter(s => !history.length || s.timestamp > history[history.length - 1].timestamp));
    state.loaded = true;
    scheduleDraw();
  } catch (unreachable) {
    // The next live message retries.
  }
}

function connectLive() {
  const socket = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/live`);
  socket.onmessage = event => {
    const message = JSON.parse(event.data);
    if (message.type === 'window') {
      setConnection('live', 'live');
      document.getElementById('graphs').classList.remove('stale');
      append(message.window);
      if (!state.loaded) loadHistory();
    } else {
      setConnection('telemetry unavailable', 'down');
      document.getElementById('graphs').classList.add('stale');
    }
  };
  socket.onclose = () => {
    setConnection('reconnecting…', 'down');
    document.getElementById('graphs').classList.add('stale');
    setTimeout(connectLive, 2000);
  };
}

function setConnection(text, css) {
  const status = document.getElementById('connection');
  status.textContent = text;
  status.className = `connection ${css}`;
}

/** Same rule as app.js: a window with the timestamp we already hold replaces it. */
function append(snapshot) {
  const list = state.snapshots;
  const last = list[list.length - 1];
  if (last && last.timestamp === snapshot.timestamp) {
    list[list.length - 1] = snapshot;
  } else if (!last || snapshot.timestamp > last.timestamp) {
    list.push(snapshot);
    if (list.length > RING_SECONDS) list.shift();
  }
  scheduleDraw();
}

function scheduleDraw() {
  if (state.drawQueued) return;
  state.drawQueued = true;
  requestAnimationFrame(() => { state.drawQueued = false; draw(); });
}

/** The seconds on screen, oldest first, ending at the newest snapshot; seconds without a snapshot stay null. */
function visibleSeries() {
  const list = state.snapshots;
  if (!list.length) return null;
  const endSecond = Math.round(list[list.length - 1].timestamp / 1000);
  const count = state.minutes * 60;
  const seconds = Array.from({ length: count }, (_, i) => endSecond - count + 1 + i);
  const bySecond = new Map(list.map(s => [Math.round(s.timestamp / 1000), s]));
  return { seconds, snapshots: seconds.map(s => bySecond.get(s) || null) };
}

function dependencyOf(snapshot, edge) {
  return snapshot && snapshot.dependencies.find(d => d.caller === edge.source && d.callee === edge.target);
}

function draw() {
  const visible = visibleSeries();
  if (!visible) return;
  const { seconds, snapshots } = visible;
  const spsOf = pick => snapshots.map(s => (s ? s.sps[pick] : null));
  const control = spsOf('control');
  const experiment = spsOf('experiment');
  const sps = state.spsChart;
  sps.setData([seconds, spsOf('total'), control, experiment]);
  sps.setSeries(2, { show: control.some(v => v) });
  sps.setSeries(3, { show: experiment.some(v => v) });

  state.charts.forEach(({ edge, rate, errors, latency, strip }) => {
    const deps = snapshots.map(s => dependencyOf(s, edge) || null);
    const when = fn => deps.map(d => (d && d.callsPerSecond > 0 ? fn(d) : null));
    rate.setData([seconds, snapshots.map((s, i) => (s ? (deps[i] ? deps[i].callsPerSecond : 0) : null))]);
    errors.setData([seconds, when(d => d.errorPercent)]);
    latency.setData([seconds, when(d => d.p50Ms), when(d => d.p99Ms)]);
    drawStrip(strip, deps.map((d, i) => (snapshots[i] && d ? d.health.toLowerCase() : 'none')));
  });
}

function drawStrip(canvas, healths) {
  const ratio = window.devicePixelRatio || 1;
  const w = Math.round(canvas.clientWidth * ratio);
  const h = Math.round(canvas.clientHeight * ratio);
  if (canvas.width !== w) canvas.width = w;
  if (canvas.height !== h) canvas.height = h;
  const ctx = canvas.getContext('2d');
  ctx.clearRect(0, 0, w, h);
  const cell = w / healths.length;
  healths.forEach((health, i) => {
    ctx.fillStyle = COLORS[health] || COLORS.none;
    ctx.fillRect(Math.floor(i * cell), 0, Math.ceil(cell), h);
  });
}

function buildPage(edges) {
  state.spsChart = makeChart(document.getElementById('sps-chart'), 170, [
    { label: 'total', stroke: COLORS.green, width: 2 },
    { label: 'control', stroke: COLORS.accent, width: 1.5, show: false },
    { label: 'experiment', stroke: COLORS.amber, width: 1.5, show: false },
  ]);
  const container = document.getElementById('dependencies');
  edges.forEach(edge => {
    const card = element('section', 'card');
    const title = element('h2');
    title.append(`${edge.source} → ${edge.target}`);
    title.append(element('span', 'label kind', edge.kind));
    const charts = element('div', 'charts');
    const rate = chartIn(charts, 'calls/s', [{ label: 'calls/s', stroke: COLORS.accent, width: 2 }]);
    const errors = chartIn(charts, 'error %', [{ label: 'error %', stroke: COLORS.red, width: 2 }]);
    const latency = chartIn(charts, 'latency (ms)', [
      { label: 'p50', stroke: COLORS.green, width: 2 },
      { label: 'p99', stroke: COLORS.amber, width: 2 },
    ]);
    const strip = element('canvas', 'health-strip');
    strip.setAttribute('role', 'img');
    strip.setAttribute('aria-label', `Health of ${edge.source} to ${edge.target}, one cell per second`);
    card.append(title, charts, strip);
    container.append(card);
    state.charts.push({ edge, rate, errors, latency, strip });
  });
}

function chartIn(parent, title, series) {
  const box = element('div');
  box.append(element('div', 'chart-title', title));
  const holder = element('div', 'chart');
  box.append(holder);
  parent.append(box);
  return makeChart(holder, 110, series);
}

function makeChart(holder, height, series) {
  const axis = {
    stroke: COLORS.muted,
    grid: { stroke: COLORS.line, width: 1 },
    ticks: { stroke: COLORS.line, width: 1 },
    font: '11px system-ui, sans-serif',
  };
  const chart = new uPlot({
    width: Math.max(holder.clientWidth, 200),
    height,
    series: [{}, ...series],
    scales: { y: { range: (u, min, max) => [0, Math.max(max || 0, 1) * 1.1] } },
    axes: [{ ...axis }, { ...axis, size: 48 }],
    legend: { show: true },
    cursor: { drag: { x: false, y: false } },
  }, [[], ...series.map(() => [])], holder);
  chart.holder = holder;
  return chart;
}

function resizeCharts() {
  const all = [state.spsChart];
  state.charts.forEach(c => all.push(c.rate, c.errors, c.latency));
  all.forEach(chart => {
    const width = chart.holder.clientWidth;
    if (width > 0 && width !== chart.width) chart.setSize({ width, height: chart.height });
  });
}

function element(tag, css, text) {
  const el = document.createElement(tag);
  if (css) el.className = css;
  if (text) el.textContent = text;
  return el;
}
