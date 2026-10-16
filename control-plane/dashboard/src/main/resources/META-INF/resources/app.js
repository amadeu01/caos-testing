'use strict';

const COLORS = { green: '#2e9e5b', amber: '#e0a526', red: '#d64545', unknown: '#7a7f87', idle: '#5f6368' };
const SPS_HISTORY_SECONDS = 60;
const state = { snapshot: null, spsHistory: [], lastTimestamp: null };

main();

async function main() {
  const topology = await (await fetch('topology.json')).json();
  const cy = createGraph(topology);
  cy.on('select unselect', () => renderDrawer(cy));
  connectLive(cy);
  setupRateSlider();
  animateEdges(cy);
}

function createGraph(topology) {
  return cytoscape({
    container: document.getElementById('topology'),
    layout: { name: 'preset', padding: 40 },
    autoungrabify: true,
    elements: [
      ...topology.nodes.map(n => ({
        data: { id: n.id, label: n.label }, position: { x: n.x, y: n.y }, classes: n.kind,
      })),
      ...topology.edges.map(e => ({
        data: { id: `${e.source}->${e.target}`, source: e.source, target: e.target, kind: e.kind, label: '', rate: 0 },
        classes: e.kind,
      })),
    ],
    style: [
      { selector: 'node', style: {
        'background-color': COLORS.unknown, label: 'data(label)', color: '#e8eaed', 'font-size': 14,
        'text-valign': 'bottom', 'text-margin-y': 6, width: 36, height: 36 } },
      { selector: 'node.emulator', style: { shape: 'round-rectangle' } },
      { selector: 'node.control', style: { shape: 'diamond' } },
      ...['green', 'amber', 'red'].map(h => ({ selector: `node.health-${h}`, style: { 'background-color': COLORS[h] } })),
      { selector: 'edge', style: {
        width: 2, 'line-color': COLORS.idle, 'target-arrow-color': COLORS.idle, 'target-arrow-shape': 'triangle',
        'curve-style': 'bezier', 'line-style': 'dashed', 'line-dash-pattern': [8, 6],
        label: 'data(label)', color: '#bdc1c6', 'font-size': 11,
        'text-background-color': '#16181d', 'text-background-opacity': 0.85, 'text-background-padding': 2 } },
      { selector: 'edge.critical', style: { width: 3 } },
      { selector: 'edge.async', style: { 'line-dash-pattern': [2, 6] } },
      ...['green', 'amber', 'red'].map(h => ({
        selector: `edge.health-${h}`, style: { 'line-color': COLORS[h], 'target-arrow-color': COLORS[h] } })),
      { selector: ':selected', style: { 'border-width': 3, 'border-color': '#8ab4f8', 'overlay-opacity': 0 } },
    ],
  });
}

function connectLive(cy) {
  const socket = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/live`);
  socket.onmessage = event => {
    const message = JSON.parse(event.data);
    if (message.type === 'window') {
      setConnection('live', 'live');
      render(cy, message.window);
    } else {
      setConnection('telemetry unavailable', 'down');
    }
  };
  socket.onclose = () => {
    setConnection('reconnecting…', 'down');
    setTimeout(() => connectLive(cy), 2000);
  };
}

function setConnection(text, css) {
  const status = document.getElementById('connection');
  status.textContent = text;
  status.className = `connection ${css}`;
}

function render(cy, snapshot) {
  state.snapshot = snapshot;
  const health = new Map(snapshot.services.map(s => [s.name, s.health.toLowerCase()]));
  cy.nodes().forEach(node => {
    node.removeClass('health-green health-amber health-red');
    if (health.has(node.id())) node.addClass(`health-${health.get(node.id())}`);
  });

  const dependencies = new Map(snapshot.dependencies.map(d => [`${d.caller}->${d.callee}`, d]));
  cy.edges().forEach(edge => {
    const d = dependencies.get(edge.id());
    edge.removeClass('health-green health-amber health-red');
    edge.data('rate', d ? d.callsPerSecond : 0);
    edge.data('label', d ? edgeLabel(d) : '');
    if (d) edge.addClass(`health-${d.health.toLowerCase()}`);
  });

  if (snapshot.timestamp !== state.lastTimestamp) {
    state.lastTimestamp = snapshot.timestamp;
    pushSps(snapshot.sps.total);
  } else {
    state.spsHistory[state.spsHistory.length - 1] = snapshot.sps.total;
    document.getElementById('sps-value').textContent = snapshot.sps.total;
    drawSpsChart();
  }
  renderDrawer(cy);
}

function edgeLabel(d) {
  return d.errorPercent > 0 ? `${d.callsPerSecond}/s · ${Math.round(d.errorPercent)}% err` : `${d.callsPerSecond}/s`;
}

function pushSps(total) {
  state.spsHistory.push(total);
  if (state.spsHistory.length > SPS_HISTORY_SECONDS) state.spsHistory.shift();
  document.getElementById('sps-value').textContent = total;
  drawSpsChart();
}

function drawSpsChart() {
  const canvas = document.getElementById('sps-chart');
  const ratio = window.devicePixelRatio || 1;
  canvas.width = canvas.clientWidth * ratio;
  canvas.height = canvas.clientHeight * ratio;
  const ctx = canvas.getContext('2d');
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  const values = state.spsHistory;
  if (values.length < 2) return;
  const max = Math.max(10, ...values) * 1.1;
  const step = canvas.width / (SPS_HISTORY_SECONDS - 1);
  const offset = (SPS_HISTORY_SECONDS - values.length) * step;
  ctx.strokeStyle = COLORS.green;
  ctx.lineWidth = 2 * ratio;
  ctx.beginPath();
  values.forEach((v, i) => {
    const x = offset + i * step;
    const y = canvas.height - (v / max) * canvas.height;
    if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
  });
  ctx.stroke();
}

/** Dashes march along each Dependency at a speed that follows its call rate. */
function animateEdges(cy) {
  const frame = () => {
    cy.edges().forEach(edge => {
      const rate = edge.data('rate') || 0;
      const offset = (edge.scratch('_dashOffset') || 0) - Math.min(4, rate / 15);
      edge.scratch('_dashOffset', offset);
      edge.style('line-dash-offset', offset);
    });
    requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
}

function renderDrawer(cy) {
  const drawer = document.getElementById('drawer');
  const selected = cy.$(':selected');
  drawer.replaceChildren();
  if (selected.empty() || !state.snapshot) {
    drawer.append(element('p', 'Click a Service or a Dependency.', 'hint'));
    return;
  }
  const item = selected[0];
  if (item.isNode()) {
    const service = state.snapshot.services.find(s => s.name === item.id());
    drawer.append(element('h2', item.data('label')));
    drawer.append(details([['Health', service ? service.health.toLowerCase() : 'no data']]));
    return;
  }
  const d = state.snapshot.dependencies.find(dep => `${dep.caller}->${dep.callee}` === item.id());
  drawer.append(element('h2', `${item.data('source')} → ${item.data('target')}`));
  const rows = [['Kind', item.data('kind')]];
  if (d) {
    rows.push(['Calls/s', d.callsPerSecond], ['Errors', `${d.errorPercent.toFixed(1)} %`],
      ['p50', `${d.p50Ms} ms`], ['p99', `${d.p99Ms} ms`], ['Health', d.health.toLowerCase()],
      ...Object.entries(d.outcomes).map(([outcome, count]) => [outcome, count]));
  } else {
    rows.push(['Calls/s', 0]);
  }
  drawer.append(details(rows));
}

function element(tag, text, css) {
  const el = document.createElement(tag);
  el.textContent = text;
  if (css) el.className = css;
  return el;
}

function details(rows) {
  const list = document.createElement('dl');
  rows.forEach(([term, value]) => list.append(element('dt', term), element('dd', String(value))));
  return list;
}

async function setupRateSlider() {
  const slider = document.getElementById('rate');
  const output = document.getElementById('rate-value');
  try {
    const response = await fetch('api/viewers-per-second');
    if (response.ok) {
      const { viewersPerSecond } = await response.json();
      slider.value = viewersPerSecond;
      output.value = viewersPerSecond;
    }
  } catch (unreachable) {
    output.value = '?';
  }
  slider.addEventListener('input', () => { output.value = slider.value; });
  slider.addEventListener('change', () => fetch(`api/viewers-per-second?value=${slider.value}`, { method: 'PUT' }));
}
