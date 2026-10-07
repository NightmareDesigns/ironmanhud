'use strict';
const $ = id => document.getElementById(id);
let revision = 0;
function renderStatus(value) {
  if (!value || !['STANDBY', 'PROCESSING', 'LIVE LINK'].includes(value.mode) ||
    !['OPENROUTER', 'GROQ', 'CEREBRAS'].includes(value.provider) ||
    !['OFFLINE', 'CONNECTING', 'LISTENING'].includes(value.live)) return;
  revision++;
  $('mode').textContent = value.mode;
  $('provider').textContent = value.provider;
  $('live').textContent = value.live;
  $('reactor').classList.toggle('active', value.mode !== 'STANDBY');
}
window.displayHud.onStatus(renderStatus);
const initialRevision = revision;
void window.displayHud.readStatus().then(value => {
  if (revision === initialRevision) renderStatus(value);
});
function clock() { $('clock').textContent = new Date().toLocaleTimeString(); }
clock();
setInterval(clock, 1000);
document.addEventListener('keydown', event => {
  if (event.key === 'Escape') { event.preventDefault(); void window.displayHud.close(); }
});
