'use strict';
const $ = id => document.getElementById(id);
const api = window.hud;
const defaults = { openrouter: 'openrouter/free', groq: 'llama-3.3-70b-versatile', cerebras: 'gpt-oss-120b' };
let preferences, archive = [], messages = [], conversationId = crypto.randomUUID(), requestId = null, retryContext = null;
let microphone, captureContext, playbackContext, captureNode, liveStarting = false, liveActive = false, liveGeneration = 0;
let playbackTime = 0, playbackSources = new Set(), assistantBody;
let playbackAnalyser, playbackSamples, speechActive = false, speechGeneration = 0;
const matrix = window.createDotMatrix($('dot-matrix'));
let hudRevision = 0;
function renderHudStatus(value) {
  if (!value?.modules || !Number.isFinite(value.level) || value.level < 0 || value.level > 1 ||
    typeof value.assistantText !== 'string' || value.assistantText.length > 1200) return;
  hudRevision++;
  $('clock').classList.toggle('hidden', !value.modules.clock);
  $('core').classList.toggle('hidden', !value.modules.reactor);
  $('dot-module').classList.toggle('hidden', !value.modules.dots);
  document.body.classList.toggle('effects-paused', value.effectsPaused);
  matrix.setPaused(value.effectsPaused);
  matrix.setEnabled(value.modules.dots);
  matrix.setLevel(value.level);
  $('dot-source').textContent = value.audioSource === 'SPEECH' ? 'CHAT / SPEECH ACTIVITY (NOT AMPLITUDE)' :
    `OUTPUT / ${value.audioSource}${value.audioSource === 'SILENT' ? '' : ' / PLAYBACK LEVEL'}`;
  $('assistant-box').classList.toggle('hidden', !value.modules.assistant);
  $('assistant-text').textContent = value.modules.assistant ?
    value.assistantText || 'Waiting for a new assistant response.' : '';
  $('assistant-state').textContent = value.assistantPending ? 'RECEIVING CHAT' :
    value.assistantSource !== 'NONE' ? `${value.assistantSource} / LATEST` : value.mode === 'LIVE LINK' ? 'LIVE OUTPUT' : 'STANDBY';
}
api.onHudStatus(renderHudStatus);
const initialHudRevision = hudRevision;
void api.readHudStatus().then(value => { if (hudRevision === initialHudRevision) renderHudStatus(value); });
const notices = (message, error = false) => { $('chat-notice').textContent = message; $('chat-notice').classList.toggle('error', error); };
const settingNotice = message => { $('settings-notice').textContent = message; };
let displayRevision = 0, displayBusy = false, currentDisplays;
function renderDisplays(value) {
  currentDisplays = value;
  displayRevision++;
  const select = $('hud-display');
  select.replaceChildren();
  const placeholder = document.createElement('option');
  placeholder.value = '';
  placeholder.textContent = 'Select a secondary display…';
  select.append(placeholder);
  const secondary = value.displays.filter(display => !display.primary);
  for (const display of secondary) {
    const option = document.createElement('option');
    option.value = String(display.id);
    option.textContent = `${display.label || 'Display'} [${display.id}] • ${display.width} × ${display.height}`;
    select.append(option);
  }
  select.value = value.selectedId === null ? '' : String(value.selectedId);
  select.disabled = displayBusy || !secondary.length;
  $('hud-show').disabled = displayBusy || !select.value || value.open;
  $('hud-close').disabled = displayBusy || !value.open;
  $('hud-display-notice').textContent = !secondary.length ?
    'No secondary display detected. Connect your glasses/display and choose Win+P > Extend. Duplicate mode cannot provide a separate HUD; controls remain here.' : value.notice;
}
async function displayAction(action) {
  displayBusy = true;
  if (currentDisplays) renderDisplays(currentDisplays);
  const revision = displayRevision;
  try {
    const result = await action();
    if (revision === displayRevision) {
      if (result.error) $('hud-display-notice').textContent = result.error;
      else renderDisplays(result);
    }
  } catch { $('hud-display-notice').textContent = 'Display operation unavailable. Check Windows Extend settings.'; }
  finally {
    displayBusy = false;
    if (currentDisplays) {
      $('hud-display').disabled = !currentDisplays.displays.some(display => !display.primary);
      $('hud-show').disabled = !$('hud-display').value || currentDisplays.open;
      $('hud-close').disabled = !currentDisplays.open;
    }
  }
}
$('hud-display').addEventListener('change', () => {
  if (!$('hud-display').value) { if (currentDisplays) renderDisplays(currentDisplays); return; }
  const id = Number($('hud-display').value);
  void displayAction(() => api.selectDisplay(id));
});
$('hud-show').addEventListener('click', () => { void displayAction(() => api.showHud()); });
$('hud-close').addEventListener('click', () => { void displayAction(() => api.closeHud()); });
api.onDisplays(renderDisplays);
const initialDisplayRevision = displayRevision;
void api.displays().then(value => {
  if (displayRevision === initialDisplayRevision && !value.error) renderDisplays(value);
});
function tab(name) {
  document.querySelectorAll('.tab').forEach(button => button.classList.toggle('selected', button.dataset.tab === name));
  document.querySelectorAll('.tab-page').forEach(page => page.classList.toggle('hidden', page.id !== `${name}-page`));
}
document.querySelectorAll('.tab').forEach(button => button.addEventListener('click', () => tab(button.dataset.tab)));
$('open-settings').addEventListener('click', () => tab('settings'));
function renderMessages() {
  $('messages').replaceChildren();
  assistantBody = null;
  for (const message of messages) {
    const item = document.createElement('article');
    item.className = `message ${message.role}`;
    const speaker = document.createElement('div');
    speaker.className = 'speaker';
    speaker.textContent = message.role === 'user' ? 'YOU / COMMAND' : 'JESSICA / RESPONSE';
    const body = document.createElement('div');
    body.className = 'body';
    body.textContent = message.content || (requestId ? 'Connecting…' : '');
    item.append(speaker, body);
    $('messages').append(item);
    if (message.role === 'assistant') assistantBody = body;
  }
  if (!messages.length) {
    const greeting = document.createElement('article');
    greeting.className = 'message';
    greeting.textContent = 'Hello. I’m Jessica. Configure a provider and memory-only API key in Settings, then send your first command.';
    $('messages').append(greeting);
  }
  $('messages').scrollTop = $('messages').scrollHeight;
}
function busy(value) {
  $('send').disabled = value;
  $('cancel').disabled = !value;
  $('retry').disabled = value || !retryContext;
  $('new-chat').disabled = value;
  $('provider').disabled = value;
  $('model').disabled = value;
  $('apply-model').disabled = value || preferences.provider === 'openrouter';
  $('chat-state').textContent = value ? 'RECEIVING STREAM' : 'READY FOR INPUT';
  visual();
}
function visual() {
  $('core').classList.toggle('active', Boolean(requestId || liveActive));
  $('mode').textContent = liveActive ? 'LIVE LINK' : requestId ? 'PROCESSING' : 'STANDBY';
}
function resetChat() {
  stopReading();
  void api.clearAssistant();
  messages = [];
  retryContext = null;
  conversationId = crypto.randomUUID();
  renderMessages();
  busy(false);
}
async function persistPreferences() {
  const result = await api.preferences(preferences);
  if (result.error) settingNotice(result.error);
}
function applyModules() {
  if (!preferences) return;
  for (const [name, enabled] of Object.entries(preferences.modules)) $('module-' + name).checked = enabled;
  $('speak-replies').checked = preferences.speakReplies;
  $('hud-shortcuts').checked = preferences.hudShortcuts;
  $('hud-brightness').value = String(preferences.hudBrightness);
  $('hud-brightness-value').textContent = `${preferences.hudBrightness}%`;
  $('shortcut-notice').textContent = preferences.hudShortcuts ? 'F8: pause/resume FX · F9: show/close selected HUD · focused PC app only.' : 'Keyboard/HID controls off.';
  for (const name of ['notes', 'music', 'navigation']) $(`${name}-module`).classList.toggle('hidden', !preferences.modules[name]);
  if (!preferences.modules.notes) $('manual-notes').value = '';
  if (!preferences.modules.navigation) $('navigation-destination').value = '';
  if (!preferences.modules.music) clearMusic();
  if (!preferences.speakReplies) stopReading();
  if (!preferences.modules.assistant) {
    $('assistant-text').textContent = '';
    $('assistant-box').classList.add('hidden');
  }
}
for (const name of ['clock', 'reactor', 'dots', 'assistant', 'notes', 'music', 'navigation']) {
  $('module-' + name).addEventListener('change', async () => {
    if (!preferences) return;
    preferences.modules[name] = $('module-' + name).checked;
    applyModules();
    await persistPreferences();
  });
}
$('speak-replies').addEventListener('change', async () => {
  if (!preferences) return;
  preferences.speakReplies = $('speak-replies').checked;
  applyModules();
  await persistPreferences();
});
$('hud-shortcuts').addEventListener('change', async () => {
  if (!preferences) return;
  preferences.hudShortcuts = $('hud-shortcuts').checked;
  applyModules();
  await persistPreferences();
});
$('hud-brightness').addEventListener('input', () => {
  $('hud-brightness-value').textContent = `${$('hud-brightness').value}%`;
});
$('hud-brightness').addEventListener('change', async () => {
  if (!preferences) return;
  const brightness = Number($('hud-brightness').value);
  if (!Number.isInteger(brightness) || brightness < 10 || brightness > 100) return;
  preferences.hudBrightness = brightness;
  await persistPreferences();
});
$('hud-brightness-reset').addEventListener('click', async () => {
  if (!preferences) return;
  preferences.hudBrightness = 100;
  $('hud-brightness').value = '100';
  $('hud-brightness-value').textContent = '100%';
  await persistPreferences();
});
document.addEventListener('keydown', event => {
  if (!preferences?.hudShortcuts || !document.hasFocus() || event.repeat || event.isComposing ||
    event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || !['F8', 'F9'].includes(event.key) ||
    (event.key === 'F9' && displayBusy) ||
    document.activeElement?.closest('input, textarea, select, [contenteditable]:not([contenteditable="false"]), [role="textbox"]') ||
    document.querySelector('dialog[open], [role="dialog"][aria-modal="true"]')) return;
  event.preventDefault();
  if (event.key === 'F9') void displayAction(() => api.hudShortcut({ action: 'toggle-hud' }));
  else void api.hudShortcut({ action: 'toggle-fx' }).then(result => {
    $('shortcut-notice').textContent = result.error || (result.effectsPaused ? 'Visual effects paused. Audio continues. F8 resumes.' : 'Visual effects resumed.');
  });
});
$('clear-notes').addEventListener('click', () => { $('manual-notes').value = ''; });
$('openrouter-web').addEventListener('click', async () => {
  const result = await api.openWebsite({ site: 'openrouter' });
  settingNotice(result.error || 'Opened OpenRouter in your browser. Website chat is separate from this app.');
});
$('navigation-open').addEventListener('click', async () => {
  const destination = $('navigation-destination').value.trim();
  if (!destination) { $('navigation-notice').textContent = 'Enter an address or place first.'; return; }
  const result = await api.openWebsite({ site: 'maps', destination });
  $('navigation-notice').textContent = result.error || 'Opened Google Maps in your browser. No directions are tracked in this app.';
});
function stopReading() {
  speechGeneration++;
  speechActive = false;
  window.speechSynthesis?.cancel();
  if (preferences?.speakReplies) void api.outputLevel({ source: 'speech', level: 0 });
}
function speakReply(content) {
  if (!preferences.speakReplies || liveActive || liveStarting || !content) return;
  if (!window.speechSynthesis || typeof SpeechSynthesisUtterance !== 'function') {
    notices('Chat complete. Local speech is unavailable on this system.'); return;
  }
  const voices = window.speechSynthesis.getVoices().filter(voice => voice.localService);
  const voice = voices.find(item => item.lang.toLowerCase() === navigator.language.toLowerCase()) || voices[0];
  if (!voice) { notices('Chat complete. No local system voice is available yet; read the text reply instead.'); return; }
  stopReading();
  const generation = speechGeneration;
  const utterance = new SpeechSynthesisUtterance(content.slice(0, 20000));
  utterance.voice = voice;
  utterance.onstart = utterance.onresume = () => { if (generation === speechGeneration) speechActive = true; };
  utterance.onend = utterance.onpause = () => { if (generation === speechGeneration) speechActive = false; };
  utterance.onerror = event => {
    if (generation !== speechGeneration) return;
    speechActive = false;
    if (!['canceled', 'interrupted'].includes(event.error)) notices('Chat complete. Local speech voice unavailable; read the text reply instead.');
  };
  window.speechSynthesis.speak(utterance);
}
$('stop-speech').addEventListener('click', stopReading);
window.speechSynthesis?.getVoices();
const musicAudio = new Audio();
musicAudio.preload = 'none';
let playlist = [], trackIndex = 0, musicContext, musicAnalyser, musicSamples, musicGain, musicRevision = 0;
function updateMusicUI() {
  const available = playlist.length > 0;
  $('music-track').disabled = !available;
  $('music-play').disabled = !available;
  $('music-prev').disabled = $('music-next').disabled = playlist.length < 2;
  $('music-play').textContent = musicAudio.paused ? 'Play' : 'Pause';
}
function clearMusic() {
  musicRevision++;
  musicAudio.pause();
  musicAudio.removeAttribute('src');
  musicAudio.load();
  for (const track of playlist) URL.revokeObjectURL(track.url);
  playlist = [];
  $('music-files').value = '';
  $('music-track').replaceChildren();
  $('music-notice').textContent = 'Import audio to begin. No autoplay on import.';
  updateMusicUI();
  void api.outputLevel({ source: 'music', level: 0 });
}
function selectTrack(index) {
  if (!playlist.length) return;
  musicRevision++;
  musicAudio.pause();
  trackIndex = (index + playlist.length) % playlist.length;
  musicAudio.src = playlist[trackIndex].url;
  $('music-track').value = String(trackIndex);
  $('music-notice').textContent = playlist[trackIndex].name;
  updateMusicUI();
}
async function playMusic() {
  if (!preferences?.modules.music || !playlist.length) return;
  const revision = musicRevision;
  try {
    if (!musicContext) {
      musicContext = new AudioContext();
      musicGain = musicContext.createGain();
      musicGain.gain.value = Number($('music-volume').value);
      musicAnalyser = musicContext.createAnalyser();
      musicAnalyser.fftSize = 1024;
      musicSamples = new Float32Array(musicAnalyser.fftSize);
      musicContext.createMediaElementSource(musicAudio).connect(musicGain).connect(musicAnalyser).connect(musicContext.destination);
    }
    await musicContext.resume();
    if (revision !== musicRevision || !preferences.modules.music) return;
    await musicAudio.play();
  } catch {
    if (revision === musicRevision) $('music-notice').textContent = 'Audio could not play. Try a supported MP3, WAV or OGG file and press Play.';
  }
  updateMusicUI();
}
$('music-files').addEventListener('change', () => {
  const files = [...$('music-files').files];
  if (!preferences?.modules.music) return;
  if (!files.length) return;
  if (files.length > 50 || files.some(file => file.size > 100 * 1024 * 1024 ||
    !(file.type.startsWith('audio/') || /\.(mp3|wav|ogg|m4a|aac|flac|webm)$/i.test(file.name))) ||
    files.reduce((total, file) => total + file.size, 0) > 500 * 1024 * 1024) {
    $('music-files').value = '';
    $('music-notice').textContent = 'Choose up to 50 audio files, each ≤100 MiB and ≤500 MiB total. Existing playlist unchanged.';
    return;
  }
  clearMusic();
  playlist = files.map(file => ({ name: file.name.slice(0, 240), url: URL.createObjectURL(file) }));
  playlist.forEach((track, index) => {
    const option = document.createElement('option');
    option.value = String(index); option.textContent = track.name; $('music-track').append(option);
  });
  selectTrack(0);
});
$('music-track').addEventListener('change', () => {
  const playing = !musicAudio.paused;
  selectTrack(Number($('music-track').value));
  if (playing) void playMusic();
});
$('music-play').addEventListener('click', () => {
  if (musicAudio.paused) void playMusic();
  else { musicRevision++; musicAudio.pause(); updateMusicUI(); }
});
for (const [id, offset] of [['music-prev', -1], ['music-next', 1]]) {
  $(id).addEventListener('click', () => {
    const playing = !musicAudio.paused;
    selectTrack(trackIndex + offset);
    if (playing) void playMusic();
  });
}
$('music-volume').addEventListener('input', () => { if (musicGain) musicGain.gain.value = Number($('music-volume').value); });
musicAudio.addEventListener('ended', () => {
  if (trackIndex + 1 < playlist.length) { selectTrack(trackIndex + 1); void playMusic(); }
  else { $('music-notice').textContent = 'Playlist complete. Press Play to replay the last track.'; updateMusicUI(); }
});
musicAudio.addEventListener('error', () => { if (playlist.length) $('music-notice').textContent = 'Unsupported or unreadable audio. Choose another track.'; });
musicAudio.addEventListener('play', updateMusicUI);
musicAudio.addEventListener('pause', updateMusicUI);
function outputRMS(analyser, samples, context) {
  if (!analyser || !samples || context?.state !== 'running') return 0;
  analyser.getFloatTimeDomainData(samples);
  return Math.min(1, Math.sqrt(samples.reduce((sum, sample) => sum + sample * sample, 0) / samples.length) * 4);
}
const outputTimer = setInterval(() => {
  if (liveActive) void api.outputLevel({ source: 'gemini', level: playbackSources.size ? outputRMS(playbackAnalyser, playbackSamples, playbackContext) : 0 });
  if (preferences?.modules.music) void api.outputLevel({ source: 'music', level: !musicAudio.paused ? outputRMS(musicAnalyser, musicSamples, musicContext) : 0 });
  if (preferences?.speakReplies) void api.outputLevel({ source: 'speech', level: speechActive ? 1 : 0 });
}, 100);
function providerUI() {
  $('provider').value = preferences.provider;
  $('provider-label').textContent = preferences.provider.toUpperCase();
  $('provider-warning').textContent = preferences.provider === 'openrouter' ?
    'Only live-catalog zero-price :free models and the free router are offered. Free models still have account/rate limits; availability changes. Your messages go to OpenRouter and its selected model provider.' :
    `${preferences.provider === 'groq' ? 'Groq' : 'Cerebras'} requires an API account/key. Free-tier quota is account-dependent, not unlimited or guaranteed; check your account plan and billing. Messages go directly to this provider.`;
  $('custom-model').disabled = preferences.provider === 'openrouter';
  $('apply-model').disabled = preferences.provider === 'openrouter';
  setModels([{ id: preferences.model, name: preferences.model }]);
}
function setModels(models) {
  $('model').replaceChildren();
  for (const model of models) {
    const option = document.createElement('option');
    option.value = model.id;
    option.textContent = `${model.name} (${model.id})`;
    $('model').append(option);
  }
  if (models.some(m => m.id === preferences.model)) $('model').value = preferences.model;
  else {
    const option = document.createElement('option');
    option.value = preferences.model;
    option.textContent = `${preferences.model} (current; catalog availability not confirmed)`;
    $('model').prepend(option);
    $('model').value = preferences.model;
  }
}
async function changeProvider(next) {
  if (next === preferences.provider) return true;
  if (!window.confirm('Change provider? This clears the current conversation. No existing messages will be forwarded. Saved chats stay local.')) {
    $('provider').value = preferences.provider;
    return false;
  }
  await api.cancelChat();
  requestId = null;
  preferences.provider = next;
  preferences.model = defaults[next];
  $('provider-key').value = '';
  resetChat();
  providerUI();
  await persistPreferences();
  notices('Provider changed. A fresh context is ready; previous messages were not forwarded.');
  return true;
}
$('provider').addEventListener('change', () => { void changeProvider($('provider').value); });
$('model').addEventListener('change', async () => {
  if (messages.length && !window.confirm('Change model and clear current conversation?')) { $('model').value = preferences.model; return; }
  preferences.model = $('model').value;
  resetChat();
  await persistPreferences();
});
$('apply-model').addEventListener('click', async () => {
  const model = $('custom-model').value.trim();
  if (preferences.provider === 'openrouter') return;
  if (!/^[a-zA-Z0-9_.:/-]{1,160}$/.test(model)) { settingNotice('Enter a valid exact model ID.'); return; }
  if (messages.length && !window.confirm('Change model and clear current conversation?')) return;
  preferences.model = model;
  providerUI();
  resetChat();
  await persistPreferences();
  settingNotice('Model applied. Account availability will be checked on the next request.');
});
$('refresh-models').addEventListener('click', async () => {
  const provider = preferences.provider;
  $('refresh-models').disabled = true;
  settingNotice('Reading live provider catalog…');
  const result = await api.models(provider);
  $('refresh-models').disabled = false;
  if (provider !== preferences.provider) return;
  if (result.error) settingNotice(result.error);
  else { setModels(result.models); settingNotice(`${result.models.length} models returned. Availability and limits depend on your account.`); }
});
async function setKey(provider, input, feedback) {
  const key = input.value.trim();
  input.value = '';
  const result = await api.setKey({ provider, key });
  feedback(result.error || (key ? 'API key set in memory for this session only.' : 'API key forgotten.'));
}
$('provider-key-set').addEventListener('click', () => { void setKey(preferences.provider, $('provider-key'), settingNotice); });
$('gemini-key-set').addEventListener('click', () => { void setKey('gemini', $('gemini-key'), message => { $('live-notice').textContent = message; }); });
$('clear-key').addEventListener('click', async () => {
  $('provider-key').value = '';
  await api.setKey({ provider: preferences.provider, key: '' });
  settingNotice('This provider key was forgotten.');
});
$('save-history').addEventListener('change', async () => {
  const checked = $('save-history').checked;
  if (!checked && archive.length && !window.confirm('Erase all saved chats from this app? Export anything you want to keep first.')) {
    $('save-history').checked = true; return;
  }
  preferences.saveChats = checked;
  if (!checked) archive = [];
  await persistPreferences();
  renderHistory();
  settingNotice(checked ? 'Local saving enabled. Use Save locally after a conversation.' : 'Saved archive erased. Local saving disabled.');
});
async function submit(context) {
  if (requestId) return;
  const input = $('prompt').value.trim();
  if (!context && !input) return;
  stopReading();
  const base = context || [...messages, { role: 'user', content: input }];
  if (base.length > 59 || base.reduce((n, m) => n + m.content.length, 0) > 80000) {
    notices('Conversation limit reached. Save/export, then start a new chat.', true); return;
  }
  messages = base.map(m => ({ ...m }));
  retryContext = base.map(m => ({ ...m }));
  requestId = crypto.randomUUID();
  const id = requestId;
  messages.push({ role: 'assistant', content: '' });
  $('prompt').value = '';
  renderMessages();
  busy(true);
  notices('Connecting directly to your selected provider…');
  const result = await api.chat({ id, provider: preferences.provider, model: preferences.model, messages: base });
  if (result.error && requestId === id) { requestId = null; busy(false); notices(result.error, true); }
}
$('compose').addEventListener('submit', event => { event.preventDefault(); void submit(); });
$('prompt').addEventListener('keydown', event => { if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) { event.preventDefault(); void submit(); } });
$('cancel').addEventListener('click', () => { void api.cancelChat(); });
$('retry').addEventListener('click', () => { if (retryContext) void submit(retryContext); });
$('new-chat').addEventListener('click', () => {
  if (messages.length && !window.confirm('Clear current conversation? Save/export first if you want to keep it.')) return;
  resetChat(); notices('New local conversation. Context cleared.');
});
api.onChat(event => {
  if (event.id !== requestId) return;
  if (event.type === 'delta') {
    messages.at(-1).content += event.text;
    if (assistantBody) assistantBody.textContent = messages.at(-1).content;
    $('messages').scrollTop = $('messages').scrollHeight;
  } else {
    const reply = event.type === 'done' ? messages.at(-1)?.content : '';
    requestId = null;
    if (event.type === 'done') retryContext = null;
    busy(false);
    notices(event.message, event.type === 'error');
    renderMessages();
    if (reply) speakReply(reply);
  }
});
function snapshot() { return { id: conversationId, provider: preferences.provider, model: preferences.model, messages: messages.filter(m => m.content) }; }
$('save-chat').addEventListener('click', async () => {
  if (requestId || !messages.length) { notices('Finish a conversation before saving.', true); return; }
  const result = await api.saveChat(snapshot());
  if (result.error) notices(result.error, true);
  else { archive = result.chats; renderHistory(); notices('Conversation saved locally. Keys and Live audio were not saved.'); }
});
$('export-chat').addEventListener('click', async () => {
  if (!messages.length) { notices('No conversation to export.', true); return; }
  const result = await api.exportChat(snapshot());
  if (result.error) notices(result.error, true);
  else if (result.ok) notices('Conversation exported as plain text.');
});
function renderHistory() {
  $('history').replaceChildren();
  const query = $('search').value.toLowerCase();
  const selected = archive.filter(c => c.messages.some(m => m.content.toLowerCase().includes(query)));
  if (!selected.length) $('history').textContent = 'No matching saved chats. Enable local saving in Settings to build an archive.';
  for (const chat of selected) {
    const item = document.createElement('article');
    item.className = 'history-item';
    const title = document.createElement('h2');
    title.textContent = `${chat.provider} / ${new Date(chat.updated).toLocaleDateString()}`;
    const preview = document.createElement('p');
    preview.textContent = chat.messages.find(m => m.role === 'user')?.content.slice(0, 150) || 'Conversation';
    const open = document.createElement('button');
    open.textContent = 'Open';
    open.addEventListener('click', async () => {
      if (requestId) { notices('Stop the active response before opening saved chat.', true); return; }
      if (chat.provider !== preferences.provider) {
        if (!await changeProvider(chat.provider)) return;
      } else if (messages.length && !window.confirm('Replace current conversation with this saved chat?')) return;
      preferences.model = chat.model;
      await persistPreferences();
      providerUI();
      stopReading();
      await api.clearAssistant();
      conversationId = chat.id;
      messages = chat.messages.map(m => ({ ...m }));
      retryContext = null;
      renderMessages(); busy(false); tab('chat');
      notices('Loaded local context. Nothing is sent until you send a new message.');
    });
    const remove = document.createElement('button');
    remove.textContent = 'Delete';
    remove.addEventListener('click', async () => {
      if (!window.confirm('Permanently delete this local saved conversation?')) return;
      const result = await api.deleteChat(chat.id);
      if (result.error) notices(result.error, true);
      else { archive = result.chats; renderHistory(); }
    });
    item.append(title, preview, open, remove); $('history').append(item);
  }
}
$('search').addEventListener('input', renderHistory);
function clearPlayback() {
  for (const source of playbackSources) { try { source.stop(); } catch {} }
  playbackSources.clear();
  playbackTime = 0;
  if (liveActive) void api.outputLevel({ source: 'gemini', level: 0 });
}
async function releaseAudio() {
  liveGeneration++;
  liveStarting = false;
  liveActive = false;
  $('wave').classList.remove('loud');
  microphone?.getTracks().forEach(track => track.stop());
  microphone = undefined;
  captureNode?.disconnect();
  captureNode = undefined;
  clearPlayback();
  const contexts = [captureContext, playbackContext];
  captureContext = playbackContext = undefined;
  playbackAnalyser = playbackSamples = undefined;
  await Promise.all(contexts.filter(Boolean).map(context => context.close().catch(() => {})));
  $('live-toggle').textContent = 'Start live • allow microphone';
  $('live-toggle').disabled = false;
  $('live-status').textContent = 'OFFLINE';
  visual();
}
async function stopVoice() {
  await releaseAudio();
  await api.stopLive();
}
async function startVoice() {
  if (liveStarting || liveActive) { await stopVoice(); return; }
  if (!window.confirm('Allow microphone audio to be sent to Google Gemini Live now? Your account may be billed. The session stops when this window loses focus.')) return;
  stopReading();
  liveStarting = true;
  const generation = ++liveGeneration;
  $('live-toggle').textContent = 'Stop connecting';
  $('live-notice').textContent = 'Requesting microphone permission…';
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true }, video: false });
    if (generation !== liveGeneration || !document.hasFocus()) { stream.getTracks().forEach(track => track.stop()); return; }
    microphone = stream;
    captureContext = new AudioContext();
    playbackContext = new AudioContext({ sampleRate: 24000 });
    playbackAnalyser = playbackContext.createAnalyser();
    playbackAnalyser.fftSize = 1024;
    playbackSamples = new Float32Array(playbackAnalyser.fftSize);
    playbackAnalyser.connect(playbackContext.destination);
    await captureContext.audioWorklet.addModule('mic-worklet.js');
    if (generation !== liveGeneration) return;
    captureNode = new AudioWorkletNode(captureContext, 'pcm-capture');
    const source = captureContext.createMediaStreamSource(stream);
    const silent = captureContext.createGain();
    silent.gain.value = 0;
    source.connect(captureNode).connect(silent).connect(captureContext.destination);
    captureNode.port.onmessage = event => {
      if (!liveActive || generation !== liveGeneration) return;
      const bytes = new Uint8Array(event.data);
      const samples = new DataView(event.data);
      let level = 0;
      for (let i = 0; i < bytes.length; i += 2) level += Math.abs(samples.getInt16(i, true));
      $('wave').classList.toggle('loud', level / (bytes.length / 2) > 3000);
      let binary = '';
      for (const byte of bytes) binary += String.fromCharCode(byte);
      void api.liveAudio(btoa(binary));
    };
    await Promise.all([captureContext.resume(), playbackContext.resume()]);
    preferences.liveModel = $('live-model').value.trim();
    await persistPreferences();
    const result = await api.startLive({ model: preferences.liveModel });
    if (generation !== liveGeneration) { await api.stopLive(); return; }
    if (result.error) { await releaseAudio(); $('live-notice').textContent = result.error; return; }
    $('transcript').textContent = '';
    if (!liveActive) {
      $('live-notice').textContent = 'Connecting to Gemini Live…';
      $('live-status').textContent = 'CONNECTING';
    }
  } catch {
    await stopVoice();
    $('live-notice').textContent = 'Microphone/audio unavailable or permission denied. Check system microphone permissions and model settings.';
  }
}
$('live-toggle').addEventListener('click', () => { void startVoice(); });
api.onLive(event => {
  if (event.type === 'closed') {
    void releaseAudio();
    $('live-notice').textContent = event.message;
    return;
  }
  if (event.type === 'ready') {
    liveStarting = false; liveActive = true;
    $('live-toggle').textContent = 'Stop live • microphone off';
    $('live-status').textContent = 'LISTENING';
    $('live-notice').textContent = event.message;
    visual();
  }
  if (event.type === 'transcript') {
    const transcript = $('transcript');
    transcript.textContent = (transcript.textContent + `\n${event.speaker}: ${event.text}`).slice(-12000);
    transcript.scrollTop = transcript.scrollHeight;
  }
  if (event.type === 'interrupted') clearPlayback();
  if (event.type === 'audio' && liveActive && playbackContext) {
    try {
      if (playbackTime - playbackContext.currentTime > 8 || playbackSources.size > 100) {
        void stopVoice(); $('live-notice').textContent = 'Playback buffer exceeded its safety limit. Restart Live.'; return;
      }
      const binary = atob(event.data);
      if (binary.length % 2) return;
      const bytes = Uint8Array.from(binary, c => c.charCodeAt(0));
      const view = new DataView(bytes.buffer);
      const buffer = playbackContext.createBuffer(1, bytes.length / 2, 24000);
      const output = buffer.getChannelData(0);
      for (let i = 0; i < output.length; i++) output[i] = view.getInt16(i * 2, true) / 32768;
      const source = playbackContext.createBufferSource();
      source.buffer = buffer; source.connect(playbackAnalyser);
      playbackTime = Math.max(playbackTime, playbackContext.currentTime);
      source.start(playbackTime); playbackTime += buffer.duration;
      playbackSources.add(source);
      source.onended = () => { playbackSources.delete(source); source.disconnect(); };
    } catch { void stopVoice(); $('live-notice').textContent = 'Audio playback failed. Restart Live.'; }
  }
});
window.addEventListener('blur', () => {
  if (liveActive || liveStarting) {
    void stopVoice();
    $('live-notice').textContent = 'Live stopped when the window lost focus. Start again to resume.';
  }
});
document.addEventListener('visibilitychange', () => { if (document.hidden) void stopVoice(); });
window.addEventListener('beforeunload', () => {
  microphone?.getTracks().forEach(track => track.stop());
  clearInterval(outputTimer);
  stopReading();
  clearMusic();
  matrix.destroy();
  void musicContext?.close();
});
setInterval(() => { $('clock').textContent = new Date().toLocaleTimeString(); }, 1000);
async function initialize() {
  const state = await api.initialize();
  preferences = state.preferences;
  archive = state.chats;
  $('live-model').value = preferences.liveModel;
  $('save-history').checked = preferences.saveChats;
  applyModules();
  providerUI(); renderMessages(); renderHistory(); busy(false);
}
void initialize();
