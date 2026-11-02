import { isValidPoint } from './geo.js';

export const MAX_WAYPOINTS = 200;
export const defaultConfig = () => ({
  mode: 'SINGLE_POINT', targetPoint: { lat: 39.9087, lng: 116.3975 }, waypoints: [],
  speedKmh: 15, accuracy: 3.5, isLoop: true, updateIntervalMs: 1000,
  enableSensorSync: true, cadenceMode: 'AUTO_ADAPTIVE', targetCadenceSpm: 175,
  enableCadenceJitter: true, cadenceJitterRatio: 0.04, enableCellSync: false,
  enableLocationJitter: false, locationJitterMeters: 1,
  enableAltitudeSimulation: false, altitudeMeters: 30,
});

export function normalizeConfig(value) {
  const config = defaultConfig();
  if (!value || typeof value !== 'object') return config;
  if (value.mode === 'ROUTE_TRACK') config.mode = value.mode;
  if (isValidPoint(value.targetPoint)) config.targetPoint = { ...value.targetPoint };
  if (Array.isArray(value.waypoints)) config.waypoints = value.waypoints.filter(isValidPoint).slice(0, MAX_WAYPOINTS).map(p => ({ lat: p.lat, lng: p.lng }));
  for (const [key, min, max] of [['speedKmh', 0, 60], ['accuracy', 1, 100], ['targetCadenceSpm', 40, 240], ['updateIntervalMs', 500, 5000], ['cadenceJitterRatio', 0, 0.2], ['locationJitterMeters', 0.5, 10], ['altitudeMeters', -500, 9000]]) {
    if (typeof value[key] === 'number' && Number.isFinite(value[key])) config[key] = Math.max(min, Math.min(max, value[key]));
  }
  for (const key of ['isLoop', 'enableSensorSync', 'enableCadenceJitter', 'enableCellSync', 'enableLocationJitter', 'enableAltitudeSimulation']) {
    if (typeof value[key] === 'boolean') config[key] = value[key];
  }
  if (value.cadenceMode === 'CUSTOM_FIXED') config.cadenceMode = value.cadenceMode;
  return config;
}

export function debounce(fn, delay) {
  let timer;
  const pending = (...args) => { clearTimeout(timer); timer = setTimeout(() => { timer = null; fn(...args); }, delay); };
  pending.cancel = () => { clearTimeout(timer); timer = null; };
  pending.flush = () => { if (timer) { clearTimeout(timer); timer = null; fn(); } };
  return pending;
}
