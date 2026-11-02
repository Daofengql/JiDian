import { isValidPoint, fromMapPoint } from './geo.js';

const MAX_INPUT = 32768;
const key = value => String(value).toLowerCase().replace(/[\s_-]/g, '');
const clean = value => typeof value === 'string' ? value.trim().slice(0, 300) : '';
const number = value => (typeof value === 'number' || typeof value === 'string' && /^[+-]?\d+(?:\.\d+)?$/.test(value.trim())) ? Number(value) : NaN;
export function normalizeCoordinateSystem(value) {
  const name = key(value || '');
  if (['wgs84', 'gps'].includes(name)) return 'WGS84';
  if (['gcj02', 'gcj'].includes(name)) return 'GCJ02';
  if (['bd09ll', 'bd09', 'baidu'].includes(name)) return 'BD09';
  return null;
}

export function importToWgs84(point, system) {
  if (!isValidPoint(point)) return null;
  if (system === 'WGS84') return { ...point };
  if (system === 'GCJ02') return fromMapPoint(point);
  if (system !== 'BD09') return null;
  const x = point.lng - .0065, y = point.lat - .006;
  const z = Math.sqrt(x * x + y * y) - .00002 * Math.sin(y * Math.PI * 3000 / 180);
  const theta = Math.atan2(y, x) - .000003 * Math.cos(x * Math.PI * 3000 / 180);
  return fromMapPoint({ lat: z * Math.sin(theta), lng: z * Math.cos(theta) });
}

/** Bounded local parsing only. A URL is data, never fetched or executed. */
export function parseLocationImport(input) {
  const text = String(input || '').trim();
  if (text.length > MAX_INPUT) return { locations: [], addresses: [], error: '内容过长，请只保留地点相关内容（最多 32 KB）' };
  const locations = [], addresses = [];
  let activityOnly = false, invalidCoordinates = false, visited = 0;
  const seen = new Set();
  function add(lat, lng, meta = {}) {
    const point = { lat: number(lat), lng: number(lng) };
    if (!isValidPoint(point)) { invalidCoordinates = true; return; }
    const location = { point, name: clean(meta.name), address: clean(meta.address), system: meta.system || null };
    const id = `${point.lat},${point.lng},${location.system}`;
    if (!locations.some(item => item.id === id) && locations.length < 20) locations.push({ ...location, id });
  }
  function visit(value, depth = 0, inherited = {}) {
    if (depth > 7 || ++visited > 400 || value == null) return;
    if (Array.isArray(value)) { value.slice(0, 40).forEach(item => visit(item, depth + 1, inherited)); return; }
    if (typeof value === 'object') {
      const data = Object.fromEntries(Object.entries(value).map(([name, item]) => [key(name), item]));
      const first = names => names.map(name => data[name]).find(item => item !== undefined && item !== null && item !== '');
      const declaredSystem = first(['coordtype', 'coortype', 'coordinatesystem', 'coordsys', '坐标系']);
      const meta = { name: clean(first(['name', 'title', 'poiname', 'locationname'])) || inherited.name,
        address: clean(first(['address', 'addr', 'formattedaddress', 'locationtext', '签到地址', '地址'])) || inherited.address,
        system: normalizeCoordinateSystem(declaredSystem) || inherited.system };
      if (meta.address && !addresses.includes(meta.address) && addresses.length < 20) addresses.push(meta.address);
      const lat = first(['latitude', 'lat', '纬度']), lng = first(['longitude', 'lng', 'lon', '经度']);
      if (lat !== undefined && lng !== undefined) add(lat, lng, meta);
      if (data.activeid || data.activityid) activityOnly = true;
      for (const item of Object.values(value)) if (typeof item === 'object' || typeof item === 'string' && (/[{}?=&%]/.test(item) || /^(?:eyJ|W3s)/.test(item))) visit(item, depth + 1, meta);
      return;
    }
    if (typeof value !== 'string') return;
    const source = value.trim().replace(/&amp;/gi, '&');
    if (!source || source.length > MAX_INPUT || seen.has(source)) return;
    seen.add(source);
    try { const json = JSON.parse(source); if (json && typeof json === 'object') { visit(json, depth + 1, inherited); return; } } catch { /* Other local formats below. */ }
    if (/%[0-9a-f]{2}/i.test(source)) {
      try { const decoded = decodeURIComponent(source); if (decoded !== source) visit(decoded, depth + 1, inherited); } catch { /* Keep malformed URLs readable. */ }
    }
    if (/^(?:eyJ|W3s)[A-Za-z0-9+/_=-]+$/.test(source)) {
      try {
        const bytes = Uint8Array.from(atob(source.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));
        visit(new TextDecoder().decode(bytes), depth + 1, inherited);
      } catch { /* Not a UTF-8 JSON payload. */ }
    }
    const urlText = source.match(/(?:https?:\/\/|chaoxing:\/\/)[^\s<>"']+/i)?.[0];
    if (urlText) {
      try {
        const url = new URL(urlText);
        const params = Object.fromEntries(url.searchParams);
        const host = url.hostname.toLowerCase();
        // Baidu's documented marker URI uses latitude,longitude and defaults to BD-09.
        if ((host === 'api.map.baidu.com' || host.endsWith('.map.baidu.com')) && /marker/.test(url.pathname) && params.location) {
          const pair = params.location.split(',');
          if (pair.length === 2) add(pair[0], pair[1], { ...inherited, name: params.title, address: params.content, system: normalizeCoordinateSystem(params.coord_type) || 'BD09' });
        }
        visit(params, depth + 1, inherited);
        if (url.hash.length > 1) visit(url.hash.slice(1), depth + 1, inherited);
      } catch { /* No network fallback. */ }
    } else if (/(?:^|&)(?:lat|latitude|longitude|lng|lon|activeId)=/i.test(source)) {
      visit(Object.fromEntries(new URLSearchParams(source.replace(/^\?/, ''))), depth + 1, inherited);
    }
    const geo = source.match(/^geo:([+-]?\d+(?:\.\d+)?),([+-]?\d+(?:\.\d+)?)/i);
    if (geo) { add(geo[1], geo[2], { ...inherited, system: 'WGS84' }); return; }
    const lat = source.match(/(?:^|[\s,{;，；])(?:latitude|lat|纬度)\s*[=:：]\s*([+-]?\d+(?:\.\d+)?)/i);
    const lng = source.match(/(?:^|[\s,{;，；])(?:longitude|lng|lon|经度)\s*[=:：]\s*([+-]?\d+(?:\.\d+)?)/i);
    if (lat && lng) add(lat[1], lng[1], inherited);
    const pair = source.match(/^([+-]?\d+(?:\.\d+)?)\s*[,，]\s*([+-]?\d+(?:\.\d+)?)$/);
    if (pair) add(pair[1], pair[2], inherited);
    const address = source.match(/(?:签到地址|签到地点|地址)\s*[：:]\s*([^\n\r{}<>]{2,200})/);
    if (address && !addresses.includes(address[1].trim())) addresses.push(address[1].trim());
  }
  visit(text);
  // Plain copied address text is useful for explicit system-geocoder search.
  if (!locations.length && !addresses.length && text.length >= 2 && text.length <= 200 && !/[{}<>=]|:\/\/|%[0-9a-f]{2}/i.test(text) && !/^geo:/i.test(text) && !invalidCoordinates) addresses.push(text);
  return { locations, addresses, activityOnly, invalidCoordinates, error: '' };
}
