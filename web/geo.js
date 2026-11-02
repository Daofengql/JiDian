const PI = Math.PI;
const A = 6378245;
const EE = 0.006693421622965943;
const outsideChina = ({ lat, lng }) => lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;

export function isValidPoint(point) {
  return !!point && Number.isFinite(point.lat) && Number.isFinite(point.lng)
    && Math.abs(point.lat) <= 90 && Math.abs(point.lng) <= 180;
}

// AMap tiles use GCJ-02. Stored coordinates and Android's GPS provider use WGS-84.
export function toMapPoint(point) {
  if (outsideChina(point)) return { ...point };
  const x = point.lng - 105;
  const y = point.lat - 35;
  let dLat = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
  dLat += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3;
  dLat += (20 * Math.sin(y * PI) + 40 * Math.sin(y / 3 * PI)) * 2 / 3;
  dLat += (160 * Math.sin(y / 12 * PI) + 320 * Math.sin(y * PI / 30)) * 2 / 3;
  let dLng = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
  dLng += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3;
  dLng += (20 * Math.sin(x * PI) + 40 * Math.sin(x / 3 * PI)) * 2 / 3;
  dLng += (150 * Math.sin(x / 12 * PI) + 300 * Math.sin(x / 30 * PI)) * 2 / 3;
  const radLat = point.lat / 180 * PI;
  const magic = 1 - EE * Math.sin(radLat) ** 2;
  dLat = dLat * 180 / ((A * (1 - EE)) / (magic * Math.sqrt(magic)) * PI);
  dLng = dLng * 180 / (A / Math.sqrt(magic) * Math.cos(radLat) * PI);
  return { lat: point.lat + dLat, lng: point.lng + dLng };
}

export function fromMapPoint(point) {
  if (outsideChina(point)) return { ...point };
  let guess = { ...point };
  for (let i = 0; i < 5; i++) {
    const projected = toMapPoint(guess);
    guess = { lat: guess.lat + point.lat - projected.lat, lng: guess.lng + point.lng - projected.lng };
  }
  return guess;
}

export function distanceBetween(a, b) {
  const rad = PI / 180;
  const h = Math.sin((b.lat - a.lat) * rad / 2) ** 2
    + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin((b.lng - a.lng) * rad / 2) ** 2;
  return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
}

export function routeDistance(points) {
  return points.reduce((total, point, i) => total + (i ? distanceBetween(points[i - 1], point) : 0), 0);
}

export function parseCoordinate(latText, lngText) {
  if (!String(latText).trim() || !String(lngText).trim()) return null;
  const point = { lat: Number(latText), lng: Number(lngText) };
  return isValidPoint(point) ? point : null;
}

export function formatDistance(meters) {
  return meters >= 1000 ? `${(meters / 1000).toFixed(2)} km` : `${Math.round(meters)} m`;
}

export function formatDuration(meters, speedKmh) {
  if (speedKmh <= 0 || meters <= 0) return '—';
  const minutes = meters / (speedKmh * 1000) * 60;
  if (minutes < 1) return '不足 1 分钟';
  return minutes >= 60 ? `${Math.floor(minutes / 60)} 小时 ${Math.round(minutes % 60)} 分钟` : `约 ${Math.round(minutes)} 分钟`;
}
