'use strict';
const { app, BrowserWindow, ipcMain, dialog, session } = require('electron');
const path = require('node:path');
const fs = require('node:fs/promises');
const crypto = require('node:crypto');

const ENDPOINTS = Object.freeze({
  openrouter: 'https://openrouter.ai/api/v1/chat/completions',
  groq: 'https://api.groq.com/openai/v1/chat/completions',
  cerebras: 'https://api.cerebras.ai/v1/chat/completions'
});
const MODEL_ENDPOINTS = Object.freeze({
  openrouter: 'https://openrouter.ai/api/v1/models',
  groq: 'https://api.groq.com/openai/v1/models',
  cerebras: 'https://api.cerebras.ai/v1/models'
});
const keys = { openrouter: '', groq: '', cerebras: '', gemini: '' };
let win, activeChat, live, writing = Promise.resolve();
let state = { preferences: { provider: 'openrouter', model: 'openrouter/free', liveModel: 'gemini-3.8-live', saveChats: false }, chats: [] };
const text = (value, max) => typeof value === 'string' && value.length <= max;
const modelOK = value => text(value, 160) && /^[a-zA-Z0-9_.:/-]+$/.test(value);
const idOK = value => text(value, 80) && /^[a-zA-Z0-9-]+$/.test(value);
const providerOK = value => Object.hasOwn(ENDPOINTS, value);
const messagesOK = value => Array.isArray(value) && value.length <= 60 &&
  value.every(m => m && ['user', 'assistant'].includes(m.role) && text(m.content, 20000)) &&
  value.reduce((n, m) => n + m.content.length, 0) <= 100000;
function send(channel, payload) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, payload);
}
function persist() {
  const contents = JSON.stringify(state);
  writing = writing.catch(() => {}).then(async () => {
    await fs.mkdir(app.getPath('userData'), { recursive: true });
    const file = path.join(app.getPath('userData'), 'preferences.json');
    await fs.writeFile(file + '.new', contents, { mode: 0o600 });
    await fs.rename(file + '.new', file);
  });
  return writing;
}
function stopChat() {
  if (activeChat) activeChat.abort.abort();
}
function stopLive(reason = 'Live session stopped.') {
  if (!live) return;
  const old = live;
  live = undefined;
  clearTimeout(old.timer);
  clearTimeout(old.durationTimer);
  old.socket.close();
  send('live-event', { type: 'closed', message: reason });
}
function trusted(event) {
  return win && event.sender === win.webContents &&
    event.senderFrame === win.webContents.mainFrame &&
    event.senderFrame.url === require('node:url').pathToFileURL(path.join(__dirname, 'index.html')).href;
}
function handle(name, fn) {
  ipcMain.handle(name, async (event, payload) => {
    if (!trusted(event)) return { error: 'Request rejected.' };
    try { return await fn(payload); }
    catch { return { error: 'Operation unavailable. Check your settings and try again.' }; }
  });
}
async function limitedJSON(response) {
  if (!response.ok) throw new Error('HTTP failure');
  const reader = response.body.getReader();
  let result = '';
  const decoder = new TextDecoder();
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      result += decoder.decode(value, { stream: true });
      if (result.length > 2000000) throw new Error('Response limit');
    }
    return JSON.parse(result + decoder.decode());
  } finally { await reader.cancel().catch(() => {}); }
}
async function streamChat(request, operation) {
  const { provider, model, messages, id } = request;
  let timer = setTimeout(() => operation.abort.abort(), 120000);
  let reader;
  const finish = (type, message) => send('chat-event', { id, type, message });
  try {
    const response = await fetch(ENDPOINTS[provider], {
      method: 'POST',
      headers: { Authorization: ['Bearer', keys[provider]].join(' '), 'Content-Type': 'application/json' },
      body: JSON.stringify({ model, messages, stream: true, max_tokens: 2048 }),
      redirect: 'error',
      signal: operation.abort.signal
    });
    if (!response.ok) {
      finish('error', response.status === 401 || response.status === 403 ? 'Authentication rejected. Check your key and account.' :
        response.status === 429 ? 'Rate limit or quota reached. Wait before retrying.' : 'Provider request failed. Check model availability and account billing.');
      return;
    }
    reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '', total = 0;
    let completed = false;
    while (!completed) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n');
      if (buffer.length > 262144) throw new Error('Stream limit');
      let boundary;
      while ((boundary = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        const data = frame.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
        if (!data) continue;
        if (data === '[DONE]') { completed = true; break; }
        const packet = JSON.parse(data);
        if (packet.error) throw new Error('Provider stream error');
        const delta = packet.choices?.[0]?.delta?.content;
        if (typeof delta === 'string' && delta) {
          total += delta.length;
          if (total > 100000) throw new Error('Output limit');
          send('chat-event', { id, type: 'delta', text: delta });
        }
      }
    }
    finish('done', 'Response complete.');
  } catch {
    finish('error', operation.abort.signal.aborted ? 'Request stopped or timed out. You can retry.' : 'Connection interrupted or response limit reached. You can retry.');
  } finally {
    clearTimeout(timer);
    if (reader) await reader.cancel().catch(() => {});
    if (activeChat === operation) activeChat = undefined;
  }
}
handle('initialize', () => state);
handle('set-key', value => {
  if (!value || !Object.hasOwn(keys, value.provider) || !text(value.key, 512) || /[^\x21-\x7e]/.test(value.key)) return { error: 'Invalid key.' };
  if (value.provider === 'gemini') stopLive();
  else stopChat();
  keys[value.provider] = value.key;
  return { ok: true };
});
handle('preferences', async value => {
  if (!value || !providerOK(value.provider) || !modelOK(value.model) ||
    !modelOK(value.liveModel) || typeof value.saveChats !== 'boolean') return { error: 'Invalid preferences.' };
  if (state.preferences.provider !== value.provider) stopChat();
  state.preferences = { provider: value.provider, model: value.model, liveModel: value.liveModel, saveChats: value.saveChats };
  if (!value.saveChats) state.chats = [];
  await persist();
  return { ok: true };
});
handle('models', async provider => {
  if (!providerOK(provider)) return { error: 'Invalid provider.' };
  if (provider !== 'openrouter' && !keys[provider]) return { error: 'Enter your provider key first.' };
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15000);
  try {
    const result = await limitedJSON(await fetch(MODEL_ENDPOINTS[provider], {
      headers: provider === 'openrouter' ? {} : { Authorization: ['Bearer', keys[provider]].join(' ') },
      redirect: 'error',
      signal: controller.signal
    }));
    const models = (Array.isArray(result.data) ? result.data : []).filter(m => modelOK(m.id) &&
      (provider !== 'openrouter' || (Number(m.pricing?.prompt) === 0 && Number(m.pricing?.completion) === 0 &&
        (m.id.endsWith(':free') || m.id === 'openrouter/free')))).slice(0, 250).map(m => ({
      id: m.id, name: text(m.name, 200) ? m.name : m.id
    }));
    if (provider === 'openrouter' && !models.some(m => m.id === 'openrouter/free')) models.unshift({ id: 'openrouter/free', name: 'OpenRouter Free Router' });
    return { models };
  } finally { clearTimeout(timer); }
});
handle('chat', request => {
  if (!request || !idOK(request.id) || !providerOK(request.provider) || !modelOK(request.model) ||
    !messagesOK(request.messages) || !request.messages.length || request.messages.at(-1).role !== 'user') return { error: 'Invalid or oversized conversation.' };
  if (request.provider !== state.preferences.provider) return { error: 'Provider changed. Start a new conversation.' };
  if (request.provider === 'openrouter' && !request.model.endsWith(':free') && request.model !== 'openrouter/free') return { error: 'Choose a free OpenRouter model.' };
  if (!keys[request.provider]) return { error: 'Enter an API key first.' };
  if (activeChat) return { error: 'Stop the current response first.' };
  const operation = { abort: new AbortController() };
  activeChat = operation;
  void streamChat(request, operation);
  return { ok: true };
});
handle('cancel-chat', () => { stopChat(); return { ok: true }; });
handle('save-chat', async value => {
  if (!state.preferences.saveChats) return { error: 'Enable local history first.' };
  if (!value || !idOK(value.id) || !providerOK(value.provider) || !modelOK(value.model) || !messagesOK(value.messages)) return { error: 'Invalid conversation.' };
  const item = { id: value.id, provider: value.provider, model: value.model,
    messages: value.messages.map(m => ({ role: m.role, content: m.content })), updated: Date.now() };
  state.chats = [item, ...state.chats.filter(c => c.id !== value.id)].slice(0, 20);
  await persist();
  return { chats: state.chats };
});
handle('delete-chat', async id => {
  if (!idOK(id)) return { error: 'Invalid conversation.' };
  state.chats = state.chats.filter(c => c.id !== id);
  await persist();
  return { chats: state.chats };
});
handle('export-chat', async value => {
  if (!value || !messagesOK(value.messages) || !providerOK(value.provider) || !modelOK(value.model)) return { error: 'Invalid conversation.' };
  const result = await dialog.showSaveDialog(win, { title: 'Export conversation (plain text, no API keys)', defaultPath: 'Jessica-chat.txt', filters: [{ name: 'Text', extensions: ['txt'] }] });
  if (result.canceled || !result.filePath) return { canceled: true };
  await fs.writeFile(result.filePath, `Jessica HUD • ${value.provider} • ${value.model}\n\n` +
    value.messages.map(m => `${m.role.toUpperCase()}\n${m.content}`).join('\n\n'), 'utf8');
  return { ok: true };
});
handle('start-live', request => {
  if (!request || !modelOK(request.model) || !keys.gemini || !win.isFocused()) return { error: 'Live needs a Gemini key, valid model and focused window.' };
  stopLive();
  // The official Live API authenticates the socket with a query key. Never log this URL.
  const socket = new WebSocket(`wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${encodeURIComponent(keys.gemini)}`);
  const operation = { socket, ready: false, timer: setTimeout(() => stopLive('Live connection timed out.'), 20000) };
  live = operation;
  operation.durationTimer = setTimeout(() => stopLive('Live session reached its 15-minute safety limit. Start again to continue.'), 15 * 60 * 1000);
  socket.addEventListener('open', () => {
    if (live !== operation) return;
    socket.send(JSON.stringify({ setup: {
      model: request.model.startsWith('models/') ? request.model : `models/${request.model}`,
      generationConfig: { responseModalities: ['AUDIO'] },
      inputAudioTranscription: {}, outputAudioTranscription: {}
    } }));
  });
  socket.addEventListener('message', async event => {
    if (live !== operation) return;
    try {
      const raw = typeof event.data === 'string' ? event.data : await event.data.text();
      if (live !== operation) return;
      if (raw.length > 2000000) throw new Error('Frame limit');
      const data = JSON.parse(raw);
      if (data.error) { stopLive('Gemini rejected the session. Check the model, API key, access and billing.'); return; }
      if (data.setupComplete) {
        clearTimeout(operation.timer);
        operation.ready = true;
        send('live-event', { type: 'ready', message: 'Live connected. Microphone active.' });
      }
      const server = data.serverContent;
      if (!server) return;
      if (server.interrupted) send('live-event', { type: 'interrupted' });
      for (const field of ['inputTranscription', 'outputTranscription']) {
        if (text(server[field]?.text, 20000)) send('live-event', { type: 'transcript', speaker: field === 'inputTranscription' ? 'You' : 'Jessica', text: server[field].text });
      }
      for (const part of (server.modelTurn?.parts || []).slice(0, 32)) {
        const audio = part.inlineData;
        if (audio && text(audio.data, 1000000) && /^audio\/pcm(?:;rate=24000)?$/.test(audio.mimeType)) send('live-event', { type: 'audio', data: audio.data });
      }
      if (data.goAway) stopLive('Gemini session ending. Start a fresh session to reconnect.');
    } catch { stopLive('Live response unavailable or exceeded safety limits.'); }
  });
  socket.addEventListener('error', () => { if (live === operation) stopLive('Live connection failed. Check connectivity, key, model and account access.'); });
  socket.addEventListener('close', () => { if (live === operation) stopLive('Live disconnected. Microphone stopped.'); });
  return { ok: true };
});
handle('stop-live', () => { stopLive(); return { ok: true }; });
handle('live-audio', value => {
  if (!live?.ready || !win.isFocused() || !text(value, 12000) || !/^[a-zA-Z0-9+/]+=*$/.test(value)) return { ok: false };
  if (live.socket.bufferedAmount > 262144) { stopLive('Live network backpressure. Restart when connection improves.'); return { ok: false }; }
  live.socket.send(JSON.stringify({ realtimeInput: { audio: { mimeType: 'audio/pcm;rate=16000', data: value } } }));
  return { ok: true };
});
app.whenReady().then(async () => {
  try {
    const file = path.join(app.getPath('userData'), 'preferences.json');
    if ((await fs.stat(file)).size <= 12500000) {
      const saved = JSON.parse(await fs.readFile(file, 'utf8'));
      const p = saved.preferences;
      if (p && providerOK(p.provider) && modelOK(p.model) && modelOK(p.liveModel) && typeof p.saveChats === 'boolean') {
        state.preferences = { provider: p.provider, model: p.model, liveModel: p.liveModel, saveChats: p.saveChats };
        state.chats = p.saveChats && Array.isArray(saved.chats) ? saved.chats.filter(c => c && idOK(c.id) && providerOK(c.provider) && modelOK(c.model) && messagesOK(c.messages)).slice(0, 20).map(c => ({
          id: c.id, provider: c.provider, model: c.model,
          updated: Number.isFinite(c.updated) ? c.updated : 0,
          messages: c.messages.map(m => ({ role: m.role, content: m.content }))
        })) : [];
      }
    }
  } catch { /* Missing or invalid local state falls back to safe defaults. */ }
  session.defaultSession.setPermissionRequestHandler((contents, permission, callback, details) => {
    callback(contents === win?.webContents && win.isFocused() && permission === 'media' &&
      details.mediaTypes?.length === 1 && details.mediaTypes[0] === 'audio');
  });
  session.defaultSession.setPermissionCheckHandler((contents, permission) =>
    contents === win?.webContents && win.isFocused() && permission === 'media');
  win = new BrowserWindow({ width: 1380, height: 920, minWidth: 780, minHeight: 600, backgroundColor: '#050b14',
    title: 'Jessica • Desktop HUD', autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false, webSecurity: true } });
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', event => event.preventDefault());
  win.webContents.on('will-attach-webview', event => event.preventDefault());
  win.on('blur', () => stopLive('Live paused: window lost focus. Start again to resume.'));
  win.on('closed', () => { stopChat(); stopLive(); Object.keys(keys).forEach(k => { keys[k] = ''; }); });
  await win.loadFile('index.html');
});
app.on('window-all-closed', () => app.quit());
app.on('before-quit', () => { stopChat(); stopLive(); });
