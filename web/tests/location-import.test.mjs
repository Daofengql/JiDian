import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseLocationImport, importToWgs84 } from '../location-import.js';
import { distanceBetween, toMapPoint } from '../geo.js';

test('reads nested map attachments without mistaking activity IDs for coordinates', () => {
  const result = parseLocationImport(JSON.stringify({ att_map_location: { latitude: 31.23, longitude: 121.47, name: '教学楼', address: '大学路 1 号', coorType: 'gcj02' } }));
  assert.equal(result.locations.length, 1);
  assert.deepEqual(result.locations[0].point, { lat: 31.23, lng: 121.47 });
  assert.equal(result.locations[0].system, 'GCJ02');
  assert.equal(result.locations[0].address, '大学路 1 号');
  const link = parseLocationImport('https://mobilelearn.chaoxing.com/newsign/preSign?activeId=123456&uid=987');
  assert.equal(link.locations.length, 0);
  assert.equal(link.addresses.length, 0);
  assert.equal(link.activityOnly, true);
});
test('reads encoded payloads, labels, zero coordinates, geo URIs and address-only text', () => {
  const data = encodeURIComponent(JSON.stringify({ lat: 0, lng: -180, address: '边界', coordType: 'wgs84' }));
  assert.deepEqual(parseLocationImport(`https://example.org/?data=${data}`).locations[0].point, { lat: 0, lng: -180 });
  assert.deepEqual(parseLocationImport('纬度：39.9， 经度：116.4').locations[0].point, { lat: 39.9, lng: 116.4 });
  assert.equal(parseLocationImport('geo:-33.9,151.2').locations[0].system, 'WGS84');
  assert.equal(parseLocationImport('签到地址：上海市大学路1号').addresses[0], '上海市大学路1号');
  const base64 = Buffer.from(JSON.stringify({ att_map_location: { lat: 31.23, lng: 121.47, address: '教学楼' } })).toString('base64');
  assert.equal(parseLocationImport(base64).locations[0].address, '教学楼');
});
test('does not invent coordinate systems, fetch links, or accept malformed coordinates', () => {
  assert.equal(parseLocationImport('39.9, 116.4').locations[0].system, null);
  for (const text of ['{"lat":null,"lng":false}', '{"lat":"","lng":1}', '91,181', '{"lat":1e999,"lng":2}']) assert.equal(parseLocationImport(text).locations.length, 0);
  assert.equal(parseLocationImport('x'.repeat(32769)).locations.length, 0);
  assert.ok(parseLocationImport('x'.repeat(32769)).error);
  assert.equal(parseLocationImport('<img src=x onerror=alert(1)>').locations.length, 0);
});
test('preserves multiple candidates and converts GCJ/Baidu to stored WGS-84', () => {
  assert.equal(parseLocationImport('[{"lat":1,"lng":2},{"lat":3,"lng":4}]').locations.length, 2);
  const gps = { lat: 39.9087, lng: 116.3975 };
  assert.ok(distanceBetween(gps, importToWgs84(toMapPoint(gps), 'GCJ02')) < .02);
  assert.ok(distanceBetween(gps, importToWgs84({ lat: 39.916443, lng: 116.410117 }, 'BD09')) < 3);
  assert.equal(importToWgs84(gps, ''), null);
});
