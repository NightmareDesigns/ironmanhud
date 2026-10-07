'use strict';

(() => {
  const directions = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];
  const angle = value => value === null || Number.isFinite(value) && value >= 0 && value < 360;
  const point = value => value && Number.isFinite(value.x) && Number.isFinite(value.y) &&
    value.x >= 0 && value.x <= 1 && value.y >= 0 && value.y <= 1;
  function valid(value) {
    return value && ['walking', 'driving'].includes(value.mode) && typeof value.shared === 'boolean' &&
      ['NONE', 'MANUAL'].includes(value.source) && angle(value.heading) && angle(value.bearing) &&
      [null, ...directions].includes(value.headingCardinal) && [null, ...directions].includes(value.targetCardinal) &&
      ['COMPASS', 'DIRECT BEARING / NO STREET ROUTE', 'SUPPLIED WAYPOINTS / NO STREET ROUTE'].includes(value.cue) &&
      typeof value.targetLabel === 'string' && value.targetLabel.length <= 80 &&
      Number.isInteger(value.count) && value.count >= 0 && value.count <= 100 &&
      Number.isInteger(value.targetIndex) && value.targetIndex >= 0 && value.targetIndex < Math.max(1, value.count) &&
      Number.isInteger(value.remaining) && value.remaining >= 0 && value.remaining <= value.count &&
      (value.distance === null || Number.isFinite(value.distance) && value.distance >= 0 && value.distance <= 20100000) &&
      value.referenceRadius === (value.mode === 'walking' ? 10 : 25) &&
      (value.withinReference === null || typeof value.withinReference === 'boolean') &&
      typeof value.hasPosition === 'boolean' && value.sketch && Array.isArray(value.sketch.points) &&
      value.sketch.points.length === value.count && value.sketch.points.every(point) &&
      (value.sketch.current === null || point(value.sketch.current));
  }
  window.createNavigationView = root => {
    const find = name => root.querySelector(`[data-nav="${name}"]`);
    const canvas = find('sketch'), context = canvas.getContext('2d');
    let lastSignature = '';
    return (value, enabled) => {
      root.classList.toggle('hidden', !enabled);
      if (!enabled) {
        lastSignature = '';
        for (const name of ['heading', 'cue', 'target', 'metrics', 'source']) find(name).textContent = '';
        context?.clearRect(0, 0, canvas.width, canvas.height);
        return;
      }
      if (!valid(value)) return;
      const signature = JSON.stringify(value);
      if (signature === lastSignature) return;
      lastSignature = signature;
      find('heading').textContent = value.heading === null ? 'HEADING UNAVAILABLE' :
        `${value.headingCardinal} / ${value.heading.toFixed(1)}° / MANUAL`;
      find('cue').textContent = value.cue;
      find('target').textContent = value.count ? `${value.targetIndex + 1}/${value.count} · ${value.targetLabel}` : 'NO ROUTE LOADED';
      const distance = value.distance === null ? 'DISTANCE UNAVAILABLE' : value.distance < 1000 ?
        `${Math.round(value.distance)} m` : `${(value.distance / 1000).toFixed(2)} km`;
      find('metrics').textContent = `${value.mode.toUpperCase()} · ${value.remaining} POINTS LEFT\n` +
        (value.count ? `${value.targetCardinal || 'BEARING UNAVAILABLE'}${value.bearing === null ? '' : ` ${value.bearing.toFixed(1)}°`} · ${distance}` : 'N · E · S · W') +
        (value.distance === null ? '' : `\nMANUAL ${value.referenceRadius} m REFERENCE: ${value.withinReference ? 'WITHIN' : 'OUTSIDE'} / NOT VERIFIED ARRIVAL`);
      find('source').textContent = `${value.source === 'MANUAL' ? 'MANUAL / NOT LIVE GPS' : 'NO POSITION OR SENSOR'}\n` +
        'SCHEMATIC / NORTH UP / NOT A ROAD MAP';
      if (!context) return;
      const size = canvas.width;
      const xy = p => [p.x * size, p.y * size];
      context.clearRect(0, 0, size, size);
      context.strokeStyle = '#357181';
      context.lineWidth = 2;
      context.beginPath();
      value.sketch.points.forEach((p, i) => context[i ? 'lineTo' : 'moveTo'](...xy(p)));
      context.stroke();
      value.sketch.points.forEach((p, i) => {
        context.fillStyle = i === value.targetIndex ? '#f4b86b' : '#66eaff';
        context.beginPath(); context.arc(...xy(p), i === value.targetIndex ? 6 : 3, 0, Math.PI * 2); context.fill();
      });
      if (value.sketch.current) {
        const [x, y] = xy(value.sketch.current);
        context.strokeStyle = '#ffffff';
        context.lineWidth = 2;
        context.strokeRect(x - 5, y - 5, 10, 10);
      }
      context.fillStyle = '#66eaff'; context.font = '12px monospace'; context.fillText('N ↑', 6, 14);
    };
  };
})();
