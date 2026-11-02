import 'mdui/mdui.css';
import 'mdui/components/button.js';
import 'mdui/components/button-icon.js';
import 'mdui/components/segmented-button.js';
import 'mdui/components/segmented-button-group.js';
import 'mdui/components/slider.js';
import 'mdui/components/switch.js';
import 'mdui/components/checkbox.js';
import 'mdui/components/text-field.js';
import 'mdui/components/dialog.js';
import 'mdui/components/snackbar.js';
import { setColorScheme } from 'mdui/functions/setColorScheme.js';
import { setTheme } from 'mdui/functions/setTheme.js';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import './styles.css';
import { icon, hydrateIcons } from './icons.js';
import { debounce, MAX_WAYPOINTS, normalizeConfig } from './model.js';
import { isValidPoint, parseCoordinate, routeDistance, formatDistance, formatDuration } from './geo.js';
import { MAP_LAYERS, normalizeLayer, toLayerPoint, fromLayerPoint } from './map-layers.js';
import { parseLocationImport, importToWgs84 } from './location-import.js';

const $ = id => document.getElementById(id);
const bridge = window.AndroidBridge;
const overlayMode = new URLSearchParams(location.search).get('overlay') === '1';
document.body.classList.toggle('system-overlay', overlayMode);
const storage = {
  get(key) { try { return JSON.parse(localStorage.getItem(key)); } catch { return null; } },
  set(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* Native preferences also persist settings. */ } },
};
let config = normalizeConfig(storage.get('mock-config'));
let running = false;
let pending = false;
let pendingTimer;
let panelExpanded = false;
let joystickExpanded = overlayMode;
let joystickActive = false;
let joystickTimer = null;
let joystickVector = { x: 0, y: 0 };
let nativeState = { backend: 'HOOK', backendReady: false, moduleActive: false, hookTargets: [], hookConnectedTargets: [], root: 'unknown', rootReady: false, rootDetail: '' };
let hookApps = [];
let hookAppsLoading = false;
let draftHookTargets = new Set();
let backendPending = false;
let imported = { locations: [], addresses: [] };
let importIndex = 0;
let importSystem = '';
let geocodeRequest = 0;
let geocodePending = false;
let nativeHydrated = false;
let routeLength = routeDistance(config.waypoints);
let followLive = false;
let lastStats = null;
let liveFrame = 0;
let undoStack = [];
let undoSnackbar = null;
let layerName = normalizeLayer(storage.get('map-layer'));
let tileFailures = 0;
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');

hydrateIcons();
setColorScheme('#286b56');
let theme = storage.get('theme') || 'auto';
const systemTheme = matchMedia('(prefers-color-scheme: dark)');
function applyTheme() {
  const dark = theme === 'dark' || (theme === 'auto' && systemTheme.matches);
  setTheme(dark ? 'dark' : 'light');
  $('themeButton').innerHTML = icon(dark ? 'sun' : 'moon');
  $('themeButton').setAttribute('aria-label', dark ? '切换浅色模式' : '切换深色模式');
  document.querySelector('meta[name="theme-color"]').content = dark ? '#141c18' : '#f7f9f8';
  post('theme', { dark });
}
systemTheme.addEventListener('change', () => { if (theme === 'auto') applyTheme(); });
applyTheme();

function post(action, payload = {}) {
  if (bridge) bridge.postMessage(JSON.stringify({ action, payload }));
}

const syncConfig = debounce(() => {
  storage.set('mock-config', config);
  post('config', config);
}, 180);

function notify(message, undo = null) {
  $('snackbarText').textContent = message;
  undoSnackbar = undo;
  $('snackbarAction').hidden = !undo;
  $('snackbar').open = true;
}

const map = L.map('map', {
  zoomControl: false, attributionControl: true, preferCanvas: true,
  doubleClickZoom: false, zoomAnimation: !reducedMotion.matches,
  fadeAnimation: !reducedMotion.matches, markerZoomAnimation: !reducedMotion.matches,
  minZoom: 3, maxZoom: 19, worldCopyJump: true,
});
map.attributionControl.setPrefix(false);
const project = point => {
  const p = toLayerPoint(point, layerName);
  return [p.lat, p.lng];
};
const unproject = point => fromLayerPoint({ lat: point.lat, lng: point.lng }, layerName);
let tiles;
let tileLabels;
function setLayer(next) {
  if (next === layerName) { $('layerDialog').open = false; return; }
  const center = tiles ? unproject(map.getCenter()) : config.targetPoint;
  if (tiles) map.removeLayer(tiles);
  if (tileLabels) { map.removeLayer(tileLabels); tileLabels = null; }
  layerName = normalizeLayer(next);
  storage.set('map-layer', layerName);
  loadTiles();
  map.setView(project(center), Math.min(map.getZoom(), MAP_LAYERS[layerName].maxZoom), { animate: false });
  renderGeometry();
  if (lastStats) renderLive();
  $('layerDialog').open = false;
  notify(`已切换为${MAP_LAYERS[layerName].label}`);
}
function loadTiles() {
  tileFailures = 0;
  $('tileMessage').hidden = true;
  const layer = MAP_LAYERS[layerName];
  document.body.classList.toggle('satellite-mode', layerName === 'satellite');
  tiles = L.tileLayer(layer.url, {
    subdomains: ['1', '2', '3', '4'], maxZoom: layer.maxZoom,
    updateWhenIdle: true, updateWhenZooming: false, keepBuffer: 2,
    attribution: layer.gcj02 ? '© 高德地图' : '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a>',
  });
  tiles.on('tileerror', () => {
    tileFailures++;
    if (tileFailures >= 3) $('tileMessage').hidden = false;
  });
  tiles.on('tileload', () => { tileFailures = 0; $('tileMessage').hidden = true; });
  tiles.addTo(map);
  if (layer.labelsUrl) tileLabels = L.tileLayer(layer.labelsUrl, {
    subdomains: ['1', '2', '3', '4'], maxZoom: layer.maxZoom,
    updateWhenIdle: true, updateWhenZooming: false, keepBuffer: 1,
  }).addTo(map);
  $('mapLayer').setAttribute('aria-label', `选择地图图层，当前${layer.label}`);
  $('mapLayer').title = `图层 · ${layer.label}`;
  document.querySelectorAll('[data-layer]').forEach(button => {
    const selected = button.dataset.layer === layerName;
    button.variant = selected ? 'tonal' : 'outlined';
    button.setAttribute('aria-pressed', String(selected));
  });
}
map.setView(project(config.targetPoint), 15);
loadTiles();

const singleIcon = L.divIcon({ className: 'map-marker', html: '<span class="point-marker"><svg viewBox="0 0 32 40" aria-hidden="true"><path d="M16 2C8.8 2 3 7.7 3 14.8c0 9.1 13 22.8 13 22.8s13-13.7 13-22.8C29 7.7 23.2 2 16 2Z" fill="#087f72" stroke="#fff" stroke-width="2.5"/><circle cx="16" cy="15" r="4.5" fill="#fff"/></svg></span>', iconSize: [32, 40], iconAnchor: [16, 38] });
const singleMarker = L.marker(project(config.targetPoint), { icon: singleIcon, draggable: true, keyboard: true, title: '所选位置，拖动可调整', autoPan: true });
const routeOutline = L.polyline([], { color: '#fff', weight: 7, opacity: .8, lineCap: 'round', lineJoin: 'round', interactive: false });
const routeLine = L.polyline([], { color: '#087f72', weight: 4, opacity: 1, lineCap: 'round', lineJoin: 'round', interactive: false });
const routeMarkers = [];
let liveMarker;

function renderGeometry() {
  const route = config.mode === 'ROUTE_TRACK';
  if (route) {
    singleMarker.remove();
    if (!map.hasLayer(routeOutline)) routeOutline.addTo(map);
    if (!map.hasLayer(routeLine)) routeLine.addTo(map);
    routeOutline.setLatLngs(config.waypoints.map(project));
    routeLine.setLatLngs(config.waypoints.map(project));
  } else {
    routeOutline.remove();
    routeLine.remove();
    singleMarker.setLatLng(project(config.targetPoint));
    if (!map.hasLayer(singleMarker)) singleMarker.addTo(map);
    singleMarker.dragging[pending ? 'disable' : 'enable']();
  }
  while (routeMarkers.length > (route ? config.waypoints.length : 0)) routeMarkers.pop().remove();
  if (route) config.waypoints.forEach((point, i) => {
    const kind = i === 0 ? 'first' : i === config.waypoints.length - 1 ? 'last' : '';
    const nodeIcon = L.divIcon({ className: 'map-marker', html: `<span class="number-marker ${kind}">${i + 1}</span>`, iconSize: [24, 24], iconAnchor: [12, 12] });
    if (routeMarkers[i]) routeMarkers[i].setLatLng(project(point)).setIcon(nodeIcon);
    else {
      const marker = L.marker(project(point), { icon: nodeIcon, draggable: !running && !overlayMode, autoPan: true, title: `路线点 ${i + 1}，拖动可调整` });
      marker.on('dragstart', rememberRoute);
      marker.on('dragend', () => {
        if (running || pending) { renderGeometry(); return; }
        const index = routeMarkers.indexOf(marker);
        const point = unproject(marker.getLatLng());
        if (index >= 0 && isValidPoint(point)) { config.waypoints[index] = point; changed(true); }
      });
      routeMarkers.push(marker.addTo(map));
    }
    routeMarkers[i].dragging[running || pending || overlayMode ? 'disable' : 'enable']();
  });
}

function rememberRoute() {
  undoStack.push(config.waypoints.map(p => ({ ...p })));
  if (undoStack.length > 40) undoStack.shift();
}
function addPoint(point) {
  if (overlayMode && config.mode === 'ROUTE_TRACK') return false;
  if (pending || (running && config.mode === 'ROUTE_TRACK')) { notify('停止模拟后即可编辑路线，运行中可用摇杆移动'); return false; }
  if (!isValidPoint(point)) { notify('坐标超出有效范围'); return false; }
  if (config.mode === 'SINGLE_POINT') config.targetPoint = point;
  else {
    if (config.waypoints.length >= MAX_WAYPOINTS) { notify(`最多支持 ${MAX_WAYPOINTS} 个路线点`); return false; }
    rememberRoute();
    config.waypoints.push(point);
  }
  changed(true);
  if (running) syncConfig.flush();
  return true;
}
map.on('click', event => addPoint(unproject(event.latlng.wrap())));
singleMarker.on('dragend', () => {
  if (pending) { renderGeometry(); return; }
  const point = unproject(singleMarker.getLatLng().wrap());
  if (isValidPoint(point)) { config.targetPoint = point; changed(true); if (running) syncConfig.flush(); }
  else renderGeometry();
});
map.on('dragstart', () => { followLive = false; renderFollowing(); });
const resizeMap = new ResizeObserver(() => requestAnimationFrame(() => map.invalidateSize({ pan: false })));
resizeMap.observe($('mapCard'));

function changed(geometry = false) {
  if (geometry) { routeLength = routeDistance(config.waypoints); renderGeometry(); }
  renderControls();
  syncConfig();
}

function renderControls() {
  const route = config.mode === 'ROUTE_TRACK';
  document.body.classList.toggle('route-mode', route);
  $('pointCard').hidden = route;
  $('movementCard').hidden = !route;
  $('pointLiveGrid').hidden = route;
  $('routeLiveGrid').hidden = !route;
  $('modeGroup').value = config.mode;
  $('modeGroup').disabled = running || pending;
  $('routeTools').hidden = !route || running || pending;
  $('undoPoint').disabled = !undoStack.length;
  $('clearRoute').disabled = !config.waypoints.length;
  $('coordinateButton').disabled = pending || (route && running);
  $('refreshPoint').hidden = route || !running;
  $('joystickToggle').hidden = !route || !running;
  $('joystickPanel').hidden = !route || !running || !joystickExpanded;
  $('joystickToggle').setAttribute('aria-expanded', String(joystickExpanded));
  $('joystickToggle').classList.toggle('selected', joystickExpanded);
  $('selectionIcon').innerHTML = icon(route ? 'route' : 'pin');
  $('selectionTitle').textContent = route ? `路线 · ${config.waypoints.length} 个点` : '已选位置';
  renderSelectionValue();
  $('selectionHint').textContent = running ? (route ? '运行中可用摇杆移动，停止后编辑路线' : '点选地图或拖动标记，立即更新模拟位置') : route ? '依次点选，拖动路线点可微调 · 直线行进' : '点击地图更换位置，也可以拖动标记';
  $('selectionAction').innerHTML = icon(route ? 'expand' : 'copy');
  $('selectionAction').setAttribute('aria-label', route ? '查看整条路线' : '复制所选坐标');
  renderSpeed();
  $('cadenceSummary').textContent = cadenceSummary();
  $('advancedSummary').textContent = `${config.isLoop ? '往返循环' : '单程行进'} · ${config.updateIntervalMs / 1000} 秒更新`;
  renderPointOptions();
  $('sensorSwitch').checked = config.enableSensorSync;
  $('cadenceControls').hidden = !config.enableSensorSync;
  $('cadenceGroup').value = config.cadenceMode;
  $('fixedCadence').hidden = config.cadenceMode !== 'CUSTOM_FIXED';
  $('cadenceHelp').textContent = config.cadenceMode === 'AUTO_ADAPTIVE' ? '随速度自动调整步频，静止时不计步。' : '锁定目标步频，步长随速度调整；静止时不计步。';
  if (Number($('cadenceSlider').value) !== config.targetCadenceSpm) $('cadenceSlider').value = config.targetCadenceSpm;
  $('cadenceValue').textContent = config.targetCadenceSpm;
  $('jitterSwitch').checked = config.enableCadenceJitter;
  $('cellSwitch').checked = config.enableCellSync;
  $('loopSwitch').checked = config.isLoop;
  $('intervalGroup').value = String(config.updateIntervalMs);
  $('startContextDetail').textContent = running ? '参数调整即时生效' : route ? `${config.waypoints.length} 个点 · ${config.isLoop ? '往返循环' : '单程行进'}` : config.enableLocationJitter ? `${config.locationJitterMeters} 米内轻微抖动` : '坐标固定';
  $('startContextDetail').hidden = running;
  $('liveDetailsButton').hidden = !running;
  $('startLabel').textContent = pending ? (running ? '正在停止…' : '正在启动…') : running ? '停止模拟' : '开始模拟';
  $('startIcon').innerHTML = icon(running ? 'stop' : 'play');
  $('toggleMock').classList.toggle('is-running', running);
  $('toggleMock').loading = pending;
  $('toggleMock').disabled = pending;
  $('connectionStatus').classList.toggle('running', running);
  $('connectionLabel').textContent = pending ? '处理中' : running ? '模拟中' : '未运行';
  $('liveCard').classList.toggle('running', running);
  $('liveStateLabel').textContent = running ? '实时更新' : lastStats ? '已停止' : '等待开始';
  $('overlaySwitch').checked = !!nativeState.overlayEnabled;
  $('systemOverlayButton').classList.toggle('selected', !!nativeState.overlayEnabled);
  $('overlayStatus').textContent = nativeState.overlayEnabled ? '已开启，切到其他应用后显示' : nativeState.backend === 'ROOT' ? '通过 Root 自动配置悬浮窗权限' : '无需 Root；首次开启需允许悬浮窗权限';
}

function renderPointOptions() {
  $('locationJitterSwitch').checked = config.enableLocationJitter;
  $('altitudeSwitch').checked = config.enableAltitudeSimulation;
  $('locationJitterSummary').textContent = config.enableLocationJitter ? `在所选位置 ${config.locationJitterMeters} 米内轻微变化` : '关闭时精确保持所选坐标';
  $('altitudeSummary').textContent = config.enableAltitudeSimulation ? `上报海拔 ${config.altitudeMeters} 米` : '关闭时不上报模拟海拔';
  $('pointSummary').textContent = `抖动范围、海拔 · ${config.updateIntervalMs / 1000} 秒更新`;
  $('locationJitterSlider').disabled = !config.enableLocationJitter;
  if (Number($('locationJitterSlider').value) !== config.locationJitterMeters) $('locationJitterSlider').value = config.locationJitterMeters;
  $('locationJitterValue').textContent = config.locationJitterMeters;
  $('locationJitterHelp').textContent = config.enableLocationJitter ? '以所选坐标为中心，不会持续偏移。' : '开启轻微抖动模拟后可调整';
  $('altitudeInput').disabled = !config.enableAltitudeSimulation;
  $('altitudeHelp').textContent = config.enableAltitudeSimulation ? '上报你设定的海拔值，不自动增加波动。' : '开启海拔模拟后可调整';
}

function renderSelectionValue() {
  $('selectionValue').textContent = config.mode === 'ROUTE_TRACK' ? `${formatDistance(routeLength)} · ${formatDuration(routeLength, config.speedKmh)}` : formatPoint(config.targetPoint);
}

function cadenceSummary() {
  return !config.enableSensorSync ? '步频模拟已关闭' : `${config.cadenceMode === 'AUTO_ADAPTIVE' ? '智能步频' : `${config.targetCadenceSpm} 步/分`}${config.enableCadenceJitter ? ' · 自然波动' : ''}`;
}

// Slider input only updates its dependent labels, leaving map markers and other controls intact.
function renderSpeed() {
  const route = config.mode === 'ROUTE_TRACK';
  $('speedValue').textContent = Number(config.speedKmh.toFixed(1));
  if (Number($('speedSlider').value) !== config.speedKmh) $('speedSlider').value = config.speedKmh;
  const paceSeconds = config.speedKmh > 0 ? Math.round(3600 / config.speedKmh) : 0;
  $('pacePreview').textContent = paceSeconds ? `${Math.floor(paceSeconds / 60)}′${String(paceSeconds % 60).padStart(2, '0')}″ /km` : '保持静止';
  document.querySelectorAll('[data-speed]').forEach(button => {
    const active = Number(button.dataset.speed) === config.speedKmh;
    button.classList.toggle('active', active);
    button.setAttribute('aria-pressed', String(active));
  });
  $('speedNote').innerHTML = `${icon('info')}<span>${route ? (config.speedKmh > 0 ? '按设定速度沿路线行进，可在运行时调整' : '速度为 0，模拟位置将保持静止') : '定点模式保持静止，速度设置用于路线模拟'}</span>`;
  renderSelectionValue();
}

function formatPoint(point) {
  return `${Math.abs(point.lat).toFixed(5)}° ${point.lat < 0 ? 'S' : 'N'}, ${Math.abs(point.lng).toFixed(5)}° ${point.lng < 0 ? 'W' : 'E'}`;
}

function renderEnvironment() {
  const hook = nativeState.backend !== 'ROOT';
  const ready = !!nativeState.backendReady;
  const checking = !hook && (nativeState.root === 'checking' || nativeState.root === 'unknown');
  const targets = nativeState.hookTargets || [];
  const connected = nativeState.hookConnectedTargets || [];
  $('permissionButton').classList.toggle('needs-permission', !!bridge && !ready);
  const label = !bridge ? '浏览器预览' : hook ? (nativeState.moduleActive ? (targets.length ? 'Hook 已就绪' : '模块已激活') : '模块未激活') : ({ checking: '正在获取 Root', unknown: '正在检测 Root', granted: 'Root 已授权', denied: 'Root 未授权', unavailable: '未检测到 Root', error: 'Root 配置未完成' })[nativeState.root] || 'Root 未就绪';
  $('permissionButton').setAttribute('aria-label', `运行环境，${label}`);
  $('permissionButton').title = label;
  $('rootStatusCard').classList.toggle('ready', ready);
  $('rootStatusCard').classList.toggle('checking', !!bridge && checking);
  $('rootStateLabel').textContent = label;
  $('rootDetail').textContent = !bridge ? '请在 Android 应用中配置运行环境。' : hook ? (nativeState.moduleActive ? (targets.length ? `${targets.length} 个目标应用，${connected.length} 个已连接。${connected.length ? '开始模拟后生效。' : '请重新打开已加入框架作用域的目标应用。'}` : '请选择目标应用，并在框架作用域中勾选它们。') : '请在 Xposed／LSPosed 中启用 JiDian，作用域勾选目标应用，然后重新打开。') : nativeState.rootDetail || '正在自动检测并请求 SU 授权…';
  $('backendGroup').value = hook ? 'HOOK' : 'ROOT';
  $('backendGroup').disabled = running || pending || backendPending;
  $('backendDescription').textContent = hook ? '通过 Xposed／LSPosed 在指定应用内生效，JiDian 不请求 SU。' : '自动请求 SU 并配置系统定位，影响设备上的定位应用。';
  $('hookBackendSettings').hidden = !hook;
  $('hookTargetsSummary').textContent = targets.length ? `已选 ${targets.length} 个应用：${targets.slice(0, 3).map(pkg => hookApps.find(app => app.packageName === pkg)?.label || pkg).join('、')}${targets.length > 3 ? '…' : ''}` : '尚未选择目标应用';
  const launches = $('hookTargetLaunches'); launches.replaceChildren();
  targets.slice(0, 10).forEach(pkg => {
    const button = document.createElement('mdui-button'); button.variant = 'outlined';
    button.textContent = `打开${hookApps.find(app => app.packageName === pkg)?.label || (pkg === 'com.chaoxing.mobile' ? '学习通' : pkg)}`;
    button.addEventListener('click', () => post('openHookTarget', { packageName: pkg })); launches.append(button);
  });
}

function renderFollowing() {
  $('followPosition').classList.toggle('selected', followLive);
  $('followPosition').setAttribute('aria-pressed', String(followLive));
}
function renderLive() {
  liveFrame = 0;
  if (!lastStats || document.hidden) return;
  const stats = lastStats;
  // Keep data from the previous mode out of the new mode's status card.
  if (stats.mode && stats.mode !== config.mode) return;
  $('liveSteps').innerHTML = `${Number(stats.steps || 0).toLocaleString('zh-CN')}<small>步</small>`;
  $('liveCadence').innerHTML = `${Math.round(stats.spm || 0)}<small>步/分</small>`;
  $('liveSpeed').innerHTML = `${Number(stats.speed || 0).toFixed(1)}<small>km/h</small>`;
  $('livePace').innerHTML = `${String(stats.pace || '—').replace(/[^\d'"′″—\-:]/g, '')}<small>/km</small>`;
  $('liveJitter').innerHTML = stats.locationJitterEnabled ? `${Number(stats.locationJitterMeters)}<small>米内</small>` : '关闭';
  $('liveCell').textContent = stats.cellEnabled ? '已开启' : '关闭';
  $('liveAltitude').innerHTML = Number.isFinite(stats.altitude) ? `${Number(stats.altitude.toFixed(1))}<small>米</small>` : '未模拟';
  $('liveAccuracy').innerHTML = Number.isFinite(stats.accuracy) ? `${stats.accuracy.toFixed(1)}<small>米</small>` : '—';
  $('manualState').textContent = stats.manualControl ? (Number(stats.speed) > 0 ? '摇杆移动中' : '已停在当前位置') : '沿路线行进';
  $('resumeRoute').disabled = !stats.manualControl;
  const point = { lat: stats.lat, lng: stats.lng };
  if (isValidPoint(point)) {
    $('liveLocation').textContent = `${point.lat.toFixed(5)}, ${point.lng.toFixed(5)}`;
    const position = project(point);
    if (!liveMarker) liveMarker = L.marker(position, { icon: L.divIcon({ className: 'map-marker', html: '<span class="live-marker"></span>', iconSize: [18, 18], iconAnchor: [9, 9] }), interactive: false, zIndexOffset: 1000 }).addTo(map);
    else liveMarker.setLatLng(position);
    if (followLive) map.panInside(position, { ...mapPadding(), animate: !reducedMotion.matches && !stats.manualControl, duration: .4 });
  }
}

function receive(message) {
  if (!message || typeof message !== 'object') return;
  if (message.type === 'importText') { openImport(message.text); return; }
  if (message.type === 'geocode') {
    if (message.requestId !== geocodeRequest || !$('importDialog').open) return;
    geocodePending = false;
    imported = { locations: (message.locations || []).filter(item => isValidPoint(item.point)), addresses: imported.addresses };
    importIndex = 0; importSystem = imported.locations[0]?.system || '';
    renderImport(message.error || (!imported.locations.length ? '未找到地址坐标，请补充更完整的地址或直接粘贴坐标。' : '请核对查找结果后导入。'));
    return;
  }
  if (message.type === 'hookApps') { hookApps = Array.isArray(message.apps) ? message.apps : []; hookAppsLoading = false; renderHookApps(); return; }
  if (message.type === 'state') {
    backendPending = false;
    const wasRunning = running;
    running = !!message.running;
    if (overlayMode && running && !wasRunning) { followLive = true; renderFollowing(); }
    if (pending) { pending = false; clearTimeout(pendingTimer); }
    Object.assign(nativeState, message);
    if (message.config && (!nativeHydrated || message.configChanged || (!wasRunning && running))) {
      config = normalizeConfig(message.config);
      routeLength = routeDistance(config.waypoints);
      storage.set('mock-config', config);
      if (!nativeHydrated) {
        if (config.mode === 'ROUTE_TRACK' && config.waypoints.length) fitSelection();
        else centerPoint(config.targetPoint, 15);
      }
      nativeHydrated = true;
    }
    if (message.stats) lastStats = message.stats;
    if (!running) { followLive = false; renderFollowing(); }
    if (!running && joystickActive) releaseJoystick();
    renderControls(); renderEnvironment(); renderGeometry();
    if (wasRunning !== running && $('importDialog').open) renderImport();
    if (lastStats) renderLive();
  } else if (message.type === 'stats') {
    if (!isValidPoint(message) || !running) return;
    lastStats = message;
    if (!liveFrame && !document.hidden) liveFrame = requestAnimationFrame(renderLive);
  } else if (message.type === 'error') {
    pending = false; backendPending = false; hookAppsLoading = false; clearTimeout(pendingTimer); renderControls(); renderEnvironment(); renderGeometry();
    notify(message.message || '操作未完成，请重试');
  } else if (message.type === 'notice') notify(message.message);
}
window.MockApp = { receive, suspend() { syncConfig.flush(); releaseJoystick(); }, back() {
  const dialog = [...document.querySelectorAll('mdui-dialog')].reverse().find(d => d.open);
  if (dialog) { dialog.open = false; return true; }
  if (panelExpanded) { setPanelExpanded(false); return true; }
  if (joystickExpanded && running && config.mode === 'ROUTE_TRACK') { setJoystickExpanded(false); return true; }
  return false;
} };

function setPanelExpanded(expanded) {
  if (expanded && joystickExpanded) setJoystickExpanded(false);
  panelExpanded = expanded;
  $('floatingSettings').hidden = !expanded;
  $('floatingPanel').classList.toggle('expanded', expanded);
  $('panelToggle').setAttribute('aria-expanded', String(expanded));
  $('panelToggle').setAttribute('aria-label', expanded ? '收起模拟参数' : '展开模拟参数');
  $('panelToggleLabel').textContent = expanded ? '收起' : '参数';
}
$('panelToggle').addEventListener('click', () => setPanelExpanded(!panelExpanded));
function setJoystickExpanded(expanded) {
  joystickExpanded = expanded;
  if (!expanded) releaseJoystick();
  if (expanded && panelExpanded) setPanelExpanded(false);
  renderControls();
}
$('joystickToggle').addEventListener('click', () => setJoystickExpanded(!joystickExpanded));
$('resumeRoute').addEventListener('click', () => { releaseJoystick(); post('resumeRoute'); });
function sendJoystick() { if (joystickActive) post('joystick', joystickVector); }
function releaseJoystick() {
  if (!joystickActive) return;
  joystickActive = false; clearInterval(joystickTimer); joystickTimer = null;
  joystickVector = { x: 0, y: 0 };
  $('joystickKnob').style.transform = 'translate(0, 0)';
  $('joystickPad').setAttribute('aria-valuenow', '0');
  post('joystick', joystickVector);
}
function moveJoystick(x, y) {
  const magnitude = Math.hypot(x, y);
  const ratio = magnitude > 1 ? 1 / magnitude : 1;
  joystickVector = { x: x * ratio, y: y * ratio };
  const travel = overlayMode ? 17 : 30;
  $('joystickKnob').style.transform = `translate(${joystickVector.x * travel}px, ${joystickVector.y * travel}px)`;
  $('joystickPad').setAttribute('aria-valuenow', String(Math.round(Math.min(magnitude, 1) * 100)));
}
const joystickPad = $('joystickPad');
joystickPad.addEventListener('pointerdown', event => {
  if (!running || config.mode !== 'ROUTE_TRACK') return;
  event.preventDefault(); joystickPad.setPointerCapture(event.pointerId); joystickActive = true;
  const rect = joystickPad.getBoundingClientRect();
  moveJoystick((event.clientX - rect.left - rect.width / 2) / 32, (event.clientY - rect.top - rect.height / 2) / 32);
  sendJoystick(); clearInterval(joystickTimer); joystickTimer = setInterval(sendJoystick, 100);
});
joystickPad.addEventListener('pointermove', event => {
  if (!joystickActive) return;
  const rect = joystickPad.getBoundingClientRect();
  moveJoystick((event.clientX - rect.left - rect.width / 2) / 32, (event.clientY - rect.top - rect.height / 2) / 32);
});
for (const event of ['pointerup', 'pointercancel', 'lostpointercapture', 'blur']) joystickPad.addEventListener(event, releaseJoystick);
joystickPad.addEventListener('keydown', event => {
  const vectors = { ArrowUp: [0, -1], ArrowDown: [0, 1], ArrowLeft: [-1, 0], ArrowRight: [1, 0] };
  if (!vectors[event.key] || !running || config.mode !== 'ROUTE_TRACK') return;
  event.preventDefault(); moveJoystick(...vectors[event.key]);
  if (!joystickActive) { joystickActive = true; sendJoystick(); joystickTimer = setInterval(sendJoystick, 100); }
});
joystickPad.addEventListener('keyup', releaseJoystick);
window.addEventListener('blur', releaseJoystick);
$('systemOverlayButton').addEventListener('click', () => { $('overlayDialog').open = true; });
$('overlaySwitch').addEventListener('change', () => {
  if (!bridge) { $('overlaySwitch').checked = false; notify('请在 Android 应用中开启系统悬浮小地图'); return; }
  post('overlayToggle', { enabled: $('overlaySwitch').checked });
});
$('refreshPoint').addEventListener('click', () => { syncConfig.flush(); post('refreshLocation'); notify('已刷新当前定点位置'); });
const resizePanel = new ResizeObserver(entries => {
  const height = entries[0].borderBoxSize?.[0]?.blockSize || $('floatingPanel').getBoundingClientRect().height;
  $('appShell').style.setProperty('--panel-height', `${height}px`);
  $('snackbar').style.bottom = `${height + 28}px`;
  requestAnimationFrame(() => {
    if (config.mode === 'ROUTE_TRACK' && config.waypoints.length > 1 && !followLive) { fitSelection(); return; }
    const point = followLive && lastStats ? lastStats : config.mode === 'SINGLE_POINT' ? config.targetPoint : config.waypoints[config.waypoints.length - 1];
    if (point) map.panInside(project(point), { ...mapPadding(), animate: false });
  });
});
resizePanel.observe($('floatingPanel'));

// MDUI permits deselecting a single segment; these settings always need one value.
document.querySelectorAll('mdui-segmented-button-group').forEach(group => {
  group.addEventListener('click', event => {
    const segment = event.composedPath().find(element => element.localName === 'mdui-segmented-button');
    if (segment && segment.value === group.value) event.stopPropagation();
  }, { capture: true });
});
function restoreSegment(group, value) { setTimeout(() => { group.value = value; }, 0); }

$('modeGroup').addEventListener('change', () => {
  if (running || pending) return;
  const mode = $('modeGroup').value;
  if (mode !== 'SINGLE_POINT' && mode !== 'ROUTE_TRACK') { restoreSegment($('modeGroup'), config.mode); return; }
  config.mode = mode;
  lastStats = null;
  if (liveMarker) { liveMarker.remove(); liveMarker = null; }
  for (const id of ['liveSteps', 'liveCadence', 'liveSpeed', 'livePace', 'liveJitter', 'liveCell', 'liveAltitude', 'liveAccuracy']) $(id).textContent = '—';
  $('liveLocation').textContent = '开始后显示实时位置';
  changed(true);
  fitSelection();
});
function updateSpeed(value) { config.speedKmh = value; renderSpeed(); syncConfig(); }
$('speedSlider').addEventListener('input', () => updateSpeed(Number($('speedSlider').value)));
$('speedSlider').addEventListener('change', () => syncConfig.flush());
document.querySelectorAll('[data-speed]').forEach(button => button.addEventListener('click', () => updateSpeed(Number(button.dataset.speed))));
for (const [id, key] of [['sensorSwitch', 'enableSensorSync'], ['jitterSwitch', 'enableCadenceJitter'], ['cellSwitch', 'enableCellSync'], ['loopSwitch', 'isLoop'], ['locationJitterSwitch', 'enableLocationJitter'], ['altitudeSwitch', 'enableAltitudeSimulation']]) {
  $(id).addEventListener('change', () => { config[key] = $(id).checked; changed(); });
}
$('cadenceGroup').addEventListener('change', () => {
  const value = $('cadenceGroup').value;
  if (!['AUTO_ADAPTIVE', 'CUSTOM_FIXED'].includes(value)) { restoreSegment($('cadenceGroup'), config.cadenceMode); return; }
  config.cadenceMode = value; changed();
});
$('cadenceSlider').addEventListener('input', () => {
  config.targetCadenceSpm = Number($('cadenceSlider').value);
  $('cadenceValue').textContent = config.targetCadenceSpm;
  $('cadenceSummary').textContent = cadenceSummary();
  syncConfig();
});
$('cadenceSlider').addEventListener('change', () => syncConfig.flush());
$('locationJitterSlider').addEventListener('input', () => {
  config.locationJitterMeters = Number($('locationJitterSlider').value);
  renderPointOptions(); syncConfig();
});
$('locationJitterSlider').addEventListener('change', () => syncConfig.flush());
function saveAltitude(report = false) {
  const field = $('altitudeInput');
  const value = Number(field.value);
  const valid = field.value.trim() !== '' && Number.isFinite(value) && value >= -500 && value <= 9000;
  field.setCustomValidity(valid ? '' : '请输入 −500 至 9000 之间的海拔');
  if (!valid) { if (report) field.reportValidity(); return false; }
  config.altitudeMeters = value;
  renderPointOptions(); syncConfig();
  return true;
}
$('altitudeInput').addEventListener('input', () => saveAltitude());
$('altitudeInput').addEventListener('change', () => { if (saveAltitude(true)) syncConfig.flush(); });
$('intervalGroup').addEventListener('change', () => {
  const value = Number($('intervalGroup').value);
  if (![500, 1000, 2000].includes(value)) { restoreSegment($('intervalGroup'), String(config.updateIntervalMs)); return; }
  config.updateIntervalMs = value; changed();
});

function openSettings(section) {
  if (overlayMode && section === 'pointSection') post('overlayFocus', { focus: true });
  for (const id of ['pointSection', 'cadenceSection', 'advancedSection']) $(id).hidden = section !== id;
  $('intervalSection').hidden = section === 'cadenceSection';
  $('altitudeInput').value = String(config.altitudeMeters);
  $('altitudeInput').setCustomValidity('');
  $('settingsDialog').headline = ({ pointSection: '定点参数', cadenceSection: '步频模拟', advancedSection: '路线设置' })[section];
  $('settingsDialog').open = true;
}
$('pointSettings').addEventListener('click', () => openSettings('pointSection'));
$('cadenceSettings').addEventListener('click', () => openSettings('cadenceSection'));
$('advancedSettings').addEventListener('click', () => openSettings('advancedSection'));
$('helpButton').addEventListener('click', () => { $('helpDialog').open = true; });
$('themeButton').addEventListener('click', () => { theme = document.documentElement.classList.contains('mdui-theme-dark') ? 'light' : 'dark'; storage.set('theme', theme); applyTheme(); });
document.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', () => {
  if (button.dataset.close === 'settingsDialog' && !$('pointSection').hidden && config.enableAltitudeSimulation && !saveAltitude(true)) return;
  $(button.dataset.close).open = false;
}));
$('permissionButton').addEventListener('click', () => { $('permissionDialog').open = true; post('refresh'); });
$('backendGroup').addEventListener('change', () => {
  const backend = $('backendGroup').value;
  if (!['HOOK', 'ROOT'].includes(backend) || running || pending) { restoreSegment($('backendGroup'), nativeState.backend); return; }
  if (bridge) { backendPending = true; post('backend', { backend }); }
  else nativeState.backend = backend;
  renderEnvironment();
});
$('openFramework').addEventListener('click', () => post('openFramework'));
$('chooseHookApps').addEventListener('click', () => {
  draftHookTargets = new Set(nativeState.hookTargets || []);
  $('hookAppSearch').value = ''; hookAppsLoading = !!bridge;
  $('hookAppsDialog').open = true; renderHookApps(); post('listHookApps');
});
function renderHookApps() {
  const query = String($('hookAppSearch').value || '').trim().toLowerCase();
  const list = $('hookAppList'); list.replaceChildren();
  const apps = hookApps.filter(app => `${app.label} ${app.packageName}`.toLowerCase().includes(query));
  if (hookAppsLoading || !apps.length) {
    const text = document.createElement('p'); text.className = 'hook-list-empty';
    text.textContent = hookAppsLoading ? '正在加载应用…' : bridge ? '没有匹配的应用' : '应用列表将在 Android 中加载'; list.append(text);
  } else for (const app of apps) {
    const row = document.createElement('label'); row.className = 'hook-app-row';
    const checkbox = document.createElement('mdui-checkbox'); checkbox.checked = draftHookTargets.has(app.packageName);
    checkbox.setAttribute('aria-label', `${app.label}，${app.packageName}`);
    const updateSelection = () => { if (checkbox.checked) draftHookTargets.add(app.packageName); else draftHookTargets.delete(app.packageName); renderHookCount(); };
    checkbox.addEventListener('change', updateSelection);
    row.addEventListener('click', event => {
      if (event.composedPath().includes(checkbox)) return;
      event.preventDefault(); checkbox.checked = !checkbox.checked; updateSelection();
    });
    const text = document.createElement('span'); const title = document.createElement('strong'); title.textContent = app.label;
    const pkg = document.createElement('small'); pkg.textContent = app.packageName; text.append(title, pkg); row.append(checkbox, text); list.append(row);
  }
  renderHookCount();
}
function renderHookCount() { $('hookSelectionCount').textContent = `已选 ${draftHookTargets.size} 个应用`; }
$('hookAppSearch').addEventListener('input', renderHookApps);
$('hookAppSearch').addEventListener('clear', renderHookApps);
$('saveHookApps').addEventListener('click', () => { post('hookTargets', { packages: [...draftHookTargets] }); if (!bridge) nativeState.hookTargets = [...draftHookTargets]; $('hookAppsDialog').open = false; renderEnvironment(); });
$('coordinateButton').addEventListener('click', () => {
  if (overlayMode) post('overlayFocus', { focus: true });
  const p = config.mode === 'ROUTE_TRACK' ? config.waypoints[config.waypoints.length - 1] || config.targetPoint : config.targetPoint;
  $('latitudeInput').value = String(Number(p.lat.toFixed(6)));
  $('longitudeInput').value = String(Number(p.lng.toFixed(6)));
  $('latitudeInput').setCustomValidity(''); $('longitudeInput').setCustomValidity('');
  $('coordinateIntro').textContent = config.mode === 'ROUTE_TRACK' ? '输入 GPS 坐标，将这个位置添加到路线末尾。' : running ? '输入新的位置，立即更新正在运行的定点模拟。' : '输入 GPS 坐标，在地图上找到准确位置。';
  $('applyCoordinate').textContent = config.mode === 'ROUTE_TRACK' ? '添加路线点' : running ? '立即更新位置' : '定位到这里';
  $('coordinateDialog').open = true;
});

const importEntry = document.createElement('mdui-button');
importEntry.id = 'openImport'; importEntry.variant = 'text'; importEntry.fullWidth = true;
importEntry.textContent = '从地址或链接导入'; importEntry.className = 'import-entry';
$('coordinateDialog').insertBefore(importEntry, $('coordinateDialog').querySelector('.dialog-note'));
importEntry.addEventListener('click', () => { $('coordinateDialog').open = false; openImport(); });
function openImport(text = '') {
  if (overlayMode) { post('openMain'); return; }
  scheduleImportAnalysis.cancel();
  $('snackbar').open = false;
  $('importText').value = String(text || '').slice(0, 32768);
  $('importDialog').open = true;
  analyzeImport();
}
function analyzeImport() {
  geocodeRequest++; geocodePending = false;
  imported = parseLocationImport($('importText').value);
  importIndex = 0; importSystem = imported.locations[0]?.system || '';
  renderImport();
}
function renderImport(message = '') {
  const candidate = imported.locations[importIndex];
  const list = $('importCandidates'); list.replaceChildren();
  if (imported.locations.length > 1) imported.locations.forEach((item, index) => {
    const button = document.createElement('mdui-button'); button.variant = index === importIndex ? 'tonal' : 'outlined';
    button.textContent = item.name || item.address || `地点 ${index + 1}`;
    button.setAttribute('aria-pressed', String(index === importIndex));
    button.addEventListener('click', () => { importIndex = index; importSystem = item.system || ''; renderImport(); });
    list.append(button);
  });
  $('importPreview').hidden = !candidate;
  $('lookupAddress').hidden = !!candidate || !imported.addresses.length;
  $('lookupAddress').disabled = geocodePending;
  $('lookupAddress').textContent = geocodePending ? '正在查找…' : '查找地址坐标';
  $('importStatus').textContent = message || imported.error || (candidate ? `识别到 ${imported.locations.length} 个地点，请核对坐标系。` : imported.activityOnly ? '链接只包含活动 ID，没有地点信息。请复制签到详情中的地址或坐标。' : imported.invalidCoordinates ? '坐标超出有效范围或格式不完整，请检查。' : imported.addresses.length ? `已识别地址：${imported.addresses[0]}` : '内容只在本机解析；不会打开链接或执行签到。');
  if (candidate) {
    $('importName').textContent = candidate.name || '识别到位置';
    $('importAddress').textContent = candidate.address || '';
    $('importCoordinates').textContent = `${candidate.point.lat.toFixed(6)}, ${candidate.point.lng.toFixed(6)}`;
    $('importSystemHelp').textContent = candidate.system ? '已根据来源选择坐标系，仍可手动更改。' : '来源未注明坐标系，请选择 GPS／高德／百度。';
  }
  $('importSystem').value = importSystem;
  const blocked = pending || (running && config.mode === 'ROUTE_TRACK');
  $('applyImport').disabled = !candidate || !importSystem || blocked;
  $('applyImport').textContent = blocked ? '请先停止路线' : config.mode === 'ROUTE_TRACK' ? '添加路线点' : running ? '更新定点位置' : '导入到地图';
}
const scheduleImportAnalysis = debounce(analyzeImport, 150);
$('importText').addEventListener('input', () => {
  geocodeRequest++; geocodePending = false; imported = { locations: [], addresses: [] }; importSystem = '';
  renderImport('正在解析…'); scheduleImportAnalysis();
});
$('importSystem').addEventListener('change', () => { importSystem = $('importSystem').value; renderImport(); });
$('lookupAddress').addEventListener('click', () => {
  if (!bridge) { renderImport('地址查找需在 Android 应用中使用；也可以粘贴坐标。'); return; }
  geocodePending = true; renderImport('正在通过系统地理编码查找地址…');
  post('geocode', { address: imported.addresses[0], requestId: ++geocodeRequest });
});
$('applyImport').addEventListener('click', () => {
  const candidate = imported.locations[importIndex];
  const point = candidate && importToWgs84(candidate.point, importSystem);
  if (point && addPoint(point)) { centerPoint(point, 16); syncConfig.flush(); $('importDialog').open = false; notify('地点已导入，可在地图上核对和微调'); }
});
$('importDialog').addEventListener('closed', () => { scheduleImportAnalysis.cancel(); geocodeRequest++; geocodePending = false; });
document.querySelectorAll('mdui-dialog').forEach(dialog => dialog.addEventListener('closed', () => { if (overlayMode) post('overlayFocus', { focus: false }); }));
function applyCoordinate() {
  const point = parseCoordinate($('latitudeInput').value, $('longitudeInput').value);
  for (const [id, limit] of [['latitudeInput', 90], ['longitudeInput', 180]]) {
    const field = $(id);
    field.setCustomValidity(field.value.trim() && Number.isFinite(Number(field.value)) && Math.abs(Number(field.value)) <= limit ? '' : `请输入 −${limit} 至 ${limit} 之间的数字`);
  }
  if (!point) { $('latitudeInput').reportValidity(); $('longitudeInput').reportValidity(); return; }
  if (addPoint(point)) { centerPoint(point, Math.max(map.getZoom(), 15)); $('coordinateDialog').open = false; }
}
$('applyCoordinate').addEventListener('click', applyCoordinate);
['latitudeInput', 'longitudeInput'].forEach(id => {
  $(id).addEventListener('input', () => $(id).setCustomValidity(''));
  $(id).addEventListener('keydown', event => { if (event.key === 'Enter') applyCoordinate(); });
});
$('zoomIn').addEventListener('click', () => map.zoomIn());
$('zoomOut').addEventListener('click', () => map.zoomOut());
function fitSelection() {
  if (config.mode === 'ROUTE_TRACK' && config.waypoints.length > 1) map.fitBounds(config.waypoints.map(project), { ...mapPadding(), maxZoom: 17, animate: false });
  else centerPoint(config.mode === 'ROUTE_TRACK' ? config.waypoints[0] || config.targetPoint : config.targetPoint, 15);
}
function mapPadding() {
  if (overlayMode) return { paddingTopLeft: [12, 44], paddingBottomRight: [12, $('floatingPanel').getBoundingClientRect().height + 16] };
  const landscape = innerHeight <= 520 && innerWidth > innerHeight;
  const bottom = innerWidth <= 600 && !landscape ? innerHeight - $('selectionCard').getBoundingClientRect().top + 24 : 28;
  return { paddingTopLeft: [24, 76], paddingBottomRight: [landscape ? $('floatingPanel').getBoundingClientRect().width + 36 : 24, bottom] };
}
function centerPoint(point, zoom) {
  const padding = mapPadding();
  const offset = L.point((padding.paddingBottomRight[0] - padding.paddingTopLeft[0]) / 2, (padding.paddingBottomRight[1] - padding.paddingTopLeft[1]) / 2);
  map.setView(map.unproject(map.project(project(point), zoom).add(offset), zoom), zoom, { animate: false });
}
$('centerMap').addEventListener('click', () => {
  if (overlayMode && running && lastStats) { followLive = true; renderFollowing(); renderLive(); }
  else fitSelection();
});
$('selectionAction').addEventListener('click', async () => {
  if (config.mode === 'ROUTE_TRACK') { fitSelection(); return; }
  const text = `${config.targetPoint.lat.toFixed(6)}, ${config.targetPoint.lng.toFixed(6)}`;
  if (bridge) post('copy', { text });
  else {
    try { await navigator.clipboard.writeText(text); notify('坐标已复制'); }
    catch { notify('无法访问剪贴板，可通过输入坐标查看位置'); }
  }
});
$('mapLayer').addEventListener('click', () => { $('layerDialog').open = true; });
document.querySelectorAll('[data-layer]').forEach(button => button.addEventListener('click', () => setLayer(button.dataset.layer)));
$('retryTiles').addEventListener('click', () => { tileFailures = 0; $('tileMessage').hidden = true; tiles.redraw(); tileLabels?.redraw(); });
function undoRoute() {
  if (running || pending || !undoStack.length) return;
  config.waypoints = undoStack.pop(); changed(true);
}
$('undoPoint').addEventListener('click', undoRoute);
$('clearRoute').addEventListener('click', () => { $('clearDialog').open = true; });
$('confirmClear').addEventListener('click', () => {
  if (running || pending) return;
  const clearedRoute = config.waypoints.map(p => ({ ...p }));
  rememberRoute(); config.waypoints = []; changed(true); $('clearDialog').open = false;
  notify('路线已清空', () => {
    if (running || pending) return;
    rememberRoute(); config.waypoints = clearedRoute; changed(true);
  });
});
$('snackbarAction').addEventListener('click', () => { undoSnackbar?.(); $('snackbar').open = false; });
$('followPosition').addEventListener('click', () => {
  if (!lastStats || !running) { notify('开始模拟后可跟随实时位置'); return; }
  followLive = !followLive; renderFollowing(); renderLive();
});
$('liveDetailsButton').addEventListener('click', () => {
  $('mobileLiveContent').append($('liveCard'));
  $('liveDialog').open = true;
});
$('liveDialog').addEventListener('closed', () => { $('floatingSettings').append($('liveCard')); });
$('toggleMock').addEventListener('click', () => {
  if (pending) return;
  if (!bridge) { notify('当前为浏览器预览，请在 Android 应用中启动模拟'); return; }
  if (!running && config.mode === 'ROUTE_TRACK' && config.waypoints.length < 2) { notify('请至少添加 2 个路线点'); return; }
  if (!running && !nativeState.backendReady) { $('permissionDialog').open = true; post('refresh'); return; }
  syncConfig.flush();
  pending = true; renderControls(); renderGeometry();
  post(running ? 'stop' : 'start', config);
  pendingTimer = setTimeout(() => { pending = false; renderControls(); renderGeometry(); post('refresh'); notify('服务响应较慢，请检查运行环境'); }, 10000);
});
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && window.MockApp.back()) event.preventDefault();
  const editing = event.composedPath().some(el => el.tagName === 'INPUT' || el.tagName === 'TEXTAREA');
  if (!editing && !document.querySelector('mdui-dialog[open]') && (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z') { event.preventDefault(); undoRoute(); }
});
document.addEventListener('visibilitychange', () => {
  if (document.hidden) { syncConfig.flush(); releaseJoystick(); }
  else { map.invalidateSize({ pan: false }); if (lastStats) renderLive(); post('refresh'); }
});
window.addEventListener('pagehide', () => { syncConfig.flush(); releaseJoystick(); });

Promise.all(['mdui-slider', 'mdui-segmented-button-group', 'mdui-dialog', 'mdui-switch', 'mdui-text-field'].map(tag => customElements.whenDefined(tag))).then(() => {
  if (overlayMode) for (const id of ['latitudeInput', 'longitudeInput']) $(id).helper = '';
  renderControls(); renderEnvironment(); renderGeometry();
  post('ready');
});
