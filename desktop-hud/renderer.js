'use strict';
const $ = id => document.getElementById(id);
const api = window.hud;
const defaults = { openrouter: 'openrouter/free', groq: 'llama-3.3-70b-versatile', cerebras: 'gpt-oss-120b' };
let preferences, archive = [], messages = [], conversationId = crypto.randomUUID(), requestId = null, retryContext = null;
let microphone, captureContext, playbackContext, captureNode, liveStarting = false, liveActive = false, liveGeneration = 0;
let playbackTime = 0, playbackSources = new Set(), assistantBody;
const notices = (message, error = false) => { $('chat-notice').textContent = message; $('chat-notice').classList.toggle('error', error); };
const settingNotice = message => { $('settings-notice').textContent = message; };
function tab(name) {
  document.querySelectorAll('.tab').forEach(button => button.classList.toggle('selected', button.dataset.tab === name));
  document.querySelectorAll('.tab-page').forEach(page => page.classList.toggle('hidden', page.id !== `${name}-page`));
}
document.querySelectorAll('.tab').forEach(button => button.addEventListener('click', () => tab(button.dataset.tab)));
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
    greeting.textContent = 'Hello. I’m Jessica. Configure a provider and memory-only API key in Uplink, then send your first command.';
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
    requestId = null;
    if (event.type === 'done') retryContext = null;
    busy(false);
    notices(event.message, event.type === 'error');
    renderMessages();
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
  if (!selected.length) $('history').textContent = 'No matching saved chats. Enable local saving in Uplink to build an archive.';
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
      source.buffer = buffer; source.connect(playbackContext.destination);
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
window.addEventListener('beforeunload', () => { microphone?.getTracks().forEach(track => track.stop()); });
setInterval(() => { $('clock').textContent = new Date().toLocaleTimeString(); }, 1000);
async function initialize() {
  const state = await api.initialize();
  preferences = state.preferences;
  archive = state.chats;
  $('live-model').value = preferences.liveModel;
  $('save-history').checked = preferences.saveChats;
  providerUI(); renderMessages(); renderHistory(); busy(false);
}
void initialize();
