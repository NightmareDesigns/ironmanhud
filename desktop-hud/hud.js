'use strict';
const $ = id => document.getElementById(id);
let revision = 0;
const matrix = window.createDotMatrix($('dot-matrix'));
const renderNavigation = window.createNavigationView($('navigation-panel'));
function renderStatus(value) {
  if (!value || !['STANDBY', 'PROCESSING', 'LIVE LINK'].includes(value.mode) ||
    !['OPENROUTER', 'GROQ', 'CEREBRAS'].includes(value.provider) ||
    !['OFFLINE', 'CONNECTING', 'LISTENING'].includes(value.live) ||
    !value.modules || !['clock', 'reactor', 'dots', 'assistant', 'navigation'].every(key => typeof value.modules[key] === 'boolean') ||
    !['SILENT', 'GEMINI', 'MUSIC', 'SPEECH'].includes(value.audioSource) ||
    !['NONE', 'CHAT', 'GEMINI'].includes(value.assistantSource) ||
    typeof value.assistantPending !== 'boolean' ||
    typeof value.effectsPaused !== 'boolean' ||
    !Number.isInteger(value.brightness) || value.brightness < 10 || value.brightness > 100 ||
    !Number.isFinite(value.level) || value.level < 0 || value.level > 1 ||
    typeof value.assistantText !== 'string' || value.assistantText.length > 1200) return;
  revision++;
  $('hud-content').style.opacity = String(value.brightness / 100);
  $('mode').textContent = value.mode;
  $('provider').textContent = value.provider;
  $('live').textContent = value.live;
  $('reactor').classList.toggle('active', value.mode !== 'STANDBY');
  $('clock').classList.toggle('hidden', !value.modules.clock);
  $('reactor').classList.toggle('hidden', !value.modules.reactor);
  $('dot-module').classList.toggle('hidden', !value.modules.dots);
  document.body.classList.toggle('effects-paused', value.effectsPaused);
  matrix.setPaused(value.effectsPaused);
  matrix.setEnabled(value.modules.dots);
  matrix.setLevel(value.level);
  $('dot-source').textContent = value.audioSource === 'SPEECH' ? 'CHAT / SPEECH ACTIVITY' : `OUTPUT / ${value.audioSource}`;
  $('assistant-box').classList.toggle('hidden', !value.modules.assistant);
  document.body.classList.toggle('with-assistant', value.modules.assistant);
  document.body.classList.toggle('with-navigation', value.modules.navigation);
  renderNavigation(value.navigation, value.modules.navigation);
  $('assistant-text').textContent = value.modules.assistant ? value.assistantText || 'Waiting for new assistant output.' : '';
  $('assistant-state').textContent = value.assistantPending ? 'RECEIVING CHAT' :
    value.assistantSource !== 'NONE' ? `${value.assistantSource} / LATEST` : value.mode === 'LIVE LINK' ? 'LIVE OUTPUT' : 'STANDBY';
}
window.displayHud.onStatus(renderStatus);
const initialRevision = revision;
void window.displayHud.readStatus().then(value => {
  if (revision === initialRevision) renderStatus(value);
});
function clock() { $('clock').textContent = new Date().toLocaleTimeString(); }
clock();
setInterval(clock, 1000);
window.addEventListener('beforeunload', () => matrix.destroy());
document.addEventListener('keydown', event => {
  if (event.key === 'Escape') { event.preventDefault(); void window.displayHud.close(); }
});
