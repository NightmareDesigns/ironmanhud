'use strict';

const exact = (value, fields) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === fields.length && fields.every(field => Object.hasOwn(value, field));
const radians = value => value * Math.PI / 180;
const degrees = value => value * 180 / Math.PI;
const longitudeDelta = value => ((value + 180) % 360 + 360) % 360 - 180;

function coordinate(value, min, max) {
  if (typeof value !== 'string' || value.length > 32 ||
    !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(value.trim())) return null;
  const number = Number(value.trim());
  return Number.isFinite(number) && number >= min && number <= max ? number : null;
}

function parseRoute(value) {
  if (typeof value !== 'string' || value.length > 20000) return null;
  const lines = value.split(/\r?\n/).filter(line => line.trim());
  if (!lines.length || lines.length > 100) return null;
  const route = [];
  for (const line of lines) {
    const fields = line.split(',');
    if (fields.length < 2 || fields.length > 3) return null;
    const latitude = coordinate(fields[0], -90, 90);
    const longitude = coordinate(fields[1], -180, 180);
    const label = fields[2]?.trim() || `Waypoint ${route.length + 1}`;
    if (latitude === null || longitude === null || label.length > 80 || /[\x00-\x1f\x7f]/.test(label)) return null;
    route.push({ latitude, longitude, label });
  }
  return route;
}

function bearingAndDistance(from, to) {
  const a = radians(from.latitude), b = radians(to.latitude);
  const delta = radians(longitudeDelta(to.longitude - from.longitude));
  const h = Math.sin((b - a) / 2) ** 2 + Math.cos(a) * Math.cos(b) * Math.sin(delta / 2) ** 2;
  const x = Math.sin(delta) * Math.cos(b);
  const y = Math.cos(a) * Math.sin(b) - Math.sin(a) * Math.cos(b) * Math.cos(delta);
  return {
    distance: 6371008.8 * 2 * Math.asin(Math.sqrt(Math.max(0, Math.min(1, h)))),
    bearing: Math.hypot(x, y) < 1e-12 ? null : (degrees(Math.atan2(x, y)) + 360) % 360
  };
}

function cardinal(heading) {
  return heading === null ? null : ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'][Math.round(heading / 45) % 8];
}

function sketch(route, position) {
  const all = [...route, ...(position ? [position] : [])];
  if (!all.length) return { points: [], current: null };
  const origin = all[0];
  const scale = Math.max(0.000001, Math.cos(radians(origin.latitude)));
  const projected = all.map(point => ({
    x: longitudeDelta(point.longitude - origin.longitude) * scale, y: origin.latitude - point.latitude
  }));
  const minX = Math.min(...projected.map(point => point.x)), maxX = Math.max(...projected.map(point => point.x));
  const minY = Math.min(...projected.map(point => point.y)), maxY = Math.max(...projected.map(point => point.y));
  const span = Math.max(maxX - minX, maxY - minY, 0.000001);
  const normalized = projected.map(point => ({
    x: 0.5 + (point.x - (minX + maxX) / 2) / span * 0.84,
    y: 0.5 + (point.y - (minY + maxY) / 2) / span * 0.84
  }));
  return { points: normalized.slice(0, route.length), current: position ? normalized.at(-1) : null };
}

function createNavigation() {
  let route, position, heading, index, mode, shared;
  function clear() {
    route = []; position = null; heading = null; index = 0; mode = 'walking'; shared = false;
  }
  clear();
  function snapshot(displayOnly = false) {
    const visible = !displayOnly || shared;
    const points = visible ? route : [];
    const current = visible ? position : null;
    const direction = visible ? heading : null;
    const target = points[index];
    const metrics = current && target ? bearingAndDistance(current, target) : { distance: null, bearing: null };
    const referenceRadius = mode === 'walking' ? 10 : 25;
    return {
      mode, shared, source: current || direction !== null ? 'MANUAL' : 'NONE',
      heading: direction, headingCardinal: cardinal(direction),
      cue: points.length === 1 ? 'DIRECT BEARING / NO STREET ROUTE' :
        points.length ? 'SUPPLIED WAYPOINTS / NO STREET ROUTE' : 'COMPASS',
      targetLabel: target?.label || '', targetIndex: target ? index : 0,
      count: points.length, remaining: target ? points.length - index : 0,
      distance: metrics.distance, bearing: metrics.bearing, targetCardinal: cardinal(metrics.bearing),
      referenceRadius, withinReference: metrics.distance === null ? null : metrics.distance <= referenceRadius,
      hasPosition: Boolean(current), sketch: sketch(points, current)
    };
  }
  function apply(value) {
    const invalid = { error: 'Invalid navigation request.' };
    if (!value || typeof value.action !== 'string') return invalid;
    switch (value.action) {
      case 'route': {
        if (!exact(value, ['action', 'text'])) return invalid;
        const parsed = parseRoute(value.text);
        if (!parsed) return { error: 'Use 1–100 lines of lat,lon[,label]; decimal coordinates within ±90/±180, labels up to 80 characters, input up to 20,000 characters.' };
        route = parsed; index = 0;
        break;
      }
      case 'position': {
        if (!exact(value, ['action', 'latitude', 'longitude', 'heading'])) return invalid;
        const latitude = coordinate(value.latitude, -90, 90);
        const longitude = coordinate(value.longitude, -180, 180);
        const nextHeading = value.heading === '' ? null : coordinate(value.heading, 0, 360);
        if (latitude === null || longitude === null || (value.heading !== '' && (nextHeading === null || nextHeading === 360))) {
          return { error: 'Enter decimal latitude (−90 to 90), longitude (−180 to 180), and optional heading (0 to less than 360).' };
        }
        position = { latitude, longitude }; heading = nextHeading;
        break;
      }
      case 'heading': {
        if (!exact(value, ['action', 'heading'])) return invalid;
        const nextHeading = value.heading === '' ? null : coordinate(value.heading, 0, 360);
        if (value.heading !== '' && (nextHeading === null || nextHeading === 360)) return invalid;
        heading = nextHeading;
        break;
      }
      case 'mode':
        if (!exact(value, ['action', 'mode']) || !['walking', 'driving'].includes(value.mode)) return invalid;
        mode = value.mode;
        break;
      case 'share':
        if (!exact(value, ['action', 'enabled']) || typeof value.enabled !== 'boolean') return invalid;
        shared = value.enabled;
        break;
      case 'previous':
      case 'next':
        if (!exact(value, ['action']) || !route.length) return invalid;
        index = Math.max(0, Math.min(route.length - 1, index + (value.action === 'next' ? 1 : -1)));
        break;
      case 'clear-route':
        if (!exact(value, ['action'])) return invalid;
        route = []; index = 0;
        break;
      case 'clear-position':
        if (!exact(value, ['action'])) return invalid;
        position = null; heading = null;
        break;
      case 'clear':
        if (!exact(value, ['action'])) return invalid;
        clear();
        break;
      default: return invalid;
    }
    return { ok: true };
  }
  return Object.freeze({ apply, clear, snapshot });
}

module.exports = { createNavigation, parseRoute, bearingAndDistance, cardinal };
