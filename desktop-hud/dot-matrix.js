'use strict';
// The matrix receives output level only; it never reads a microphone or audio file.
window.createDotMatrix = canvas => {
  const context = canvas.getContext('2d');
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
  let level = 0, enabled = false, paused = false, frame = 0;
  function draw(time = 0) {
    if (!context) return;
    context.clearRect(0, 0, canvas.width, canvas.height);
    const phase = reduced.matches ? 0 : time / 180;
    for (let column = 0; column < 36; column++) {
      const height = level * (1.5 + 4.5 * Math.abs(Math.sin(column * .43 + phase)));
      for (let row = 0; row < 10; row++) {
        const lit = level > .015 && Math.abs(row - 4.5) < height;
        context.fillStyle = lit ? '#66eaff' : '#12303a';
        context.beginPath();
        context.arc(7.5 + column * 15, 10.5 + row * 15, lit ? 3.4 : 2.3, 0, Math.PI * 2);
        context.fill();
      }
    }
  }
  function tick(time) {
    frame = 0;
    if (!enabled || paused) return;
    draw(time);
    if (!reduced.matches) frame = requestAnimationFrame(tick);
  }
  function schedule() { if (enabled && !paused && !frame) frame = requestAnimationFrame(tick); }
  reduced.addEventListener('change', schedule);
  return Object.freeze({
    setLevel(value) { level = Math.max(0, Math.min(1, Number.isFinite(value) ? value : 0)); if (reduced.matches && !paused) draw(); },
    setEnabled(value) { enabled = Boolean(value); if (enabled) schedule(); else { cancelAnimationFrame(frame); frame = 0; } },
    setPaused(value) { paused = Boolean(value); if (paused) { cancelAnimationFrame(frame); frame = 0; } else schedule(); },
    destroy() { enabled = false; cancelAnimationFrame(frame); reduced.removeEventListener('change', schedule); }
  });
};
