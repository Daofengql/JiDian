import { toMapPoint, fromMapPoint } from './geo.js';

export const MAP_LAYERS = {
  amap: { label: '标准地图', gcj02: true, maxZoom: 18,
    url: 'https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}' },
  satellite: { label: '卫星地图', gcj02: true, maxZoom: 18,
    url: 'https://webst0{s}.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}',
    labelsUrl: 'https://webst0{s}.is.autonavi.com/appmaptile?style=8&x={x}&y={y}&z={z}' },
  osm: { label: 'OpenStreetMap', gcj02: false, maxZoom: 19,
    url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png' },
};

export const normalizeLayer = value => Object.prototype.hasOwnProperty.call(MAP_LAYERS, value) ? value : 'amap';
export const toLayerPoint = (point, layer) => MAP_LAYERS[normalizeLayer(layer)].gcj02 ? toMapPoint(point) : { ...point };
export const fromLayerPoint = (point, layer) => MAP_LAYERS[normalizeLayer(layer)].gcj02 ? fromMapPoint(point) : { ...point };
