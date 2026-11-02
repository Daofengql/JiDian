import { test } from 'node:test';
import assert from 'node:assert/strict';
import { toMapPoint, fromMapPoint, distanceBetween, routeDistance, parseCoordinate } from '../geo.js';
import { normalizeConfig, debounce } from '../model.js';

test('AMap projection converts WGS-84 and reverses without accumulating drift', () => {
  for (const point of [{ lat: 39.9087, lng: 116.3975 }, { lat: 31.2304, lng: 121.4737 }, { lat: 22.5431, lng: 114.0579 }]) {
    assert.ok(distanceBetween(point, toMapPoint(point)) > 100);
    assert.ok(distanceBetween(point, fromMapPoint(toMapPoint(point))) < 0.02);
  }
  const outside = { lat: 40.7128, lng: -74.006 };
  assert.deepEqual(toMapPoint(outside), outside);
});

test('coordinate entry rejects blanks, non-numbers, and out-of-range values', () => {
  for (const pair of [['', '116'], [' ', '0'], ['NaN', '0'], ['91', '0'], ['0', '181'], ['Infinity', '0']]) assert.equal(parseCoordinate(...pair), null);
  assert.deepEqual(parseCoordinate('0', '-180'), { lat: 0, lng: -180 });
  assert.deepEqual(parseCoordinate('-33.9', '151.2'), { lat: -33.9, lng: 151.2 });
});

test('route distance sums consecutive segments, including repeated points', () => {
  const points = [{ lat: 0, lng: 0 }, { lat: 0, lng: .001 }, { lat: 0, lng: .001 }, { lat: .001, lng: .001 }];
  assert.ok(Math.abs(routeDistance(points) - 222.38985) < .01);
  assert.equal(routeDistance([]), 0);
  assert.ok(Number.isFinite(distanceBetween({ lat: 90, lng: 0 }, { lat: -90, lng: 180 })));
});

test('corrupt saved settings cannot poison map or service input', () => {
  const result = normalizeConfig({ speedKmh: Infinity, targetPoint: { lat: 91, lng: 2 }, waypoints: [{ lat: 0, lng: 0 }, { lat: NaN, lng: 3 }], isLoop: 'false', targetCadenceSpm: -10 });
  assert.equal(result.speedKmh, 15);
  assert.equal(result.targetPoint.lat, 39.9087);
  assert.equal(result.waypoints.length, 1);
  assert.equal(result.isLoop, true);
  assert.equal(result.targetCadenceSpm, 40);
});

test('rapid slider updates are coalesced and a final value can be flushed', async () => {
  let calls = 0;
  const update = debounce(() => calls++, 20);
  for (let i = 0; i < 40; i++) update();
  await new Promise(resolve => setTimeout(resolve, 50));
  assert.equal(calls, 1);
  update(); update.flush();
  assert.equal(calls, 2);
  await new Promise(resolve => setTimeout(resolve, 30));
  assert.equal(calls, 2);
});

test('older preferences migrate to optional point features without losing route settings', () => {
  const config = normalizeConfig({ speedKmh: 3, enableCellSync: true, targetCadenceSpm: 180 });
  assert.equal(config.enableLocationJitter, false);
  assert.equal(config.enableAltitudeSimulation, false);
  assert.equal(config.enableCellSync, true);
  assert.equal(config.speedKmh, 3);
  assert.equal(config.targetCadenceSpm, 180);
  assert.equal(normalizeConfig(null).enableCellSync, false);
});

test('point settings validate units, bounds and independent switches', () => {
  const config = normalizeConfig({ enableLocationJitter: true, enableAltitudeSimulation: true, enableCellSync: false, locationJitterMeters: 999, altitudeMeters: -999, speedKmh: 20 });
  assert.equal(config.locationJitterMeters, 10);
  assert.equal(config.altitudeMeters, -500);
  assert.equal(config.enableLocationJitter, true);
  assert.equal(config.enableAltitudeSimulation, true);
  assert.equal(config.enableCellSync, false);
  assert.equal(normalizeConfig({ altitudeMeters: NaN }).altitudeMeters, 30);
  assert.equal(config.speedKmh, 20);
});
