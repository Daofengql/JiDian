import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeLayer, MAP_LAYERS, toLayerPoint, fromLayerPoint } from '../map-layers.js';
import { distanceBetween } from '../geo.js';

test('standard and satellite layers align the same physical location', () => {
  for (const point of [{ lat: 26.194599, lng: 117.555403 }, { lat: 39.9087, lng: 116.3975 }, { lat: -33.9, lng: 151.2 }]) {
    assert.deepEqual(toLayerPoint(point, 'satellite'), toLayerPoint(point, 'amap'));
    assert.ok(distanceBetween(point, fromLayerPoint(toLayerPoint(point, 'satellite'), 'satellite')) < 0.02);
    assert.deepEqual(toLayerPoint(point, 'osm'), point);
  }
});

test('layer preferences accept satellite and fall back for invalid saved values', () => {
  assert.equal(normalizeLayer('satellite'), 'satellite');
  for (const value of [null, {}, 'toString', '__proto__', 'invalid']) assert.equal(normalizeLayer(value), 'amap');
  assert.ok(MAP_LAYERS.satellite.url.startsWith('https://'));
  assert.ok(MAP_LAYERS.satellite.labelsUrl.startsWith('https://'));
});
