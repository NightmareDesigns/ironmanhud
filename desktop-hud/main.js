'use strict';
const { app, BrowserWindow, ipcMain, dialog, session, screen, shell } = require('electron');
const path = require('node:path');
const fs = require('node:fs/promises');
const crypto = require('node:crypto');
const PAGE_URL = require('node:url').pathToFileURL(path.join(__dirname, 'index.html')).href;
const HUD_URL = require('node:url').pathToFileURL(path.join(__dirname, 'hud.html')).href;

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
let hudWindow, selectedDisplayId = null, displayNotice = 'Select a secondary display. Windows must use Extend, not Duplicate.';
let placingControls = false;
const MODULE_DEFAULTS = Object.freeze({ clock: true, reactor: true, dots: true, assistant: false, notes: false, music: false, navigation: false });
let state = { preferences: { provider: 'openrouter', model: 'openrouter/free', liveModel: 'gemini-3.8-live',
  saveChats: false, speakReplies: false, hudShortcuts: false, hudBrightness: 100, modules: { ...MODULE_DEFAULTS } }, chats: [] };
let assistantText = '', assistantSource = 'NONE';
let effectsPaused = false, dialogActive = false;
const outputLevels = { gemini: { level: 0, time: 0 }, music: { level: 0, time: 0 }, speech: { level: 0, time: 0 } };
const text = (value, max) => typeof value === 'string' && value.length <= max;
const exactObject = (value, fields) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === fields.length && fields.every(field => Object.hasOwn(value, field));
function validPreferences(value, migrate = false) {
  if (!value || !providerOK(value.provider) || !modelOK(value.model) ||
    !modelOK(value.liveModel) || typeof value.saveChats !== 'boolean') return null;
  const modules = migrate && value.modules === undefined ? { ...MODULE_DEFAULTS } : value.modules;
  const speakReplies = migrate && value.speakReplies === undefined ? false : value.speakReplies;
  const hudShortcuts = migrate && value.hudShortcuts === undefined ? false : value.hudShortcuts;
  const hudBrightness = migrate && value.hudBrightness === undefined ? 100 : value.hudBrightness;
  if ((!migrate && !exactObject(value, ['provider', 'model', 'liveModel', 'saveChats', 'speakReplies', 'hudShortcuts', 'hudBrightness', 'modules'])) ||
    !exactObject(modules, Object.keys(MODULE_DEFAULTS)) ||
    !Object.keys(MODULE_DEFAULTS).every(key => typeof modules[key] === 'boolean') ||
    typeof speakReplies !== 'boolean' || typeof hudShortcuts !== 'boolean' ||
    !Number.isInteger(hudBrightness) || hudBrightness < 10 || hudBrightness > 100) return null;
  return { provider: value.provider, model: value.model, liveModel: value.liveModel,
    saveChats: value.saveChats, speakReplies, hudShortcuts, hudBrightness, modules: { ...modules } };
}
const modelOK = value => text(value, 160) && /^[a-zA-Z0-9_.:/-]+$/.test(value);
const idOK = value => text(value, 80) && /^[a-zA-Z0-9-]+$/.test(value);
const providerOK = value => Object.hasOwn(ENDPOINTS, value);
const messagesOK = value => Array.isArray(value) && value.length <= 60 &&
  value.every(m => m && ['user', 'assistant'].includes(m.role) && text(m.content, 20000)) &&
  value.reduce((n, m) => n + m.content.length, 0) <= 100000;
function send(channel, payload) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, payload);
  if (channel === 'live-event' && ['ready', 'closed'].includes(payload.type)) publishHudStatus();
}
function hudStatus() {
  const now = Date.now();
  const levels = Object.entries(outputLevels).map(([source, value]) => ({
    source, level: now - value.time < 600 ? value.level : 0
  })).sort((a, b) => b.level - a.level);
  return {
    mode: live?.ready ? 'LIVE LINK' : activeChat ? 'PROCESSING' : 'STANDBY',
    provider: state.preferences.provider.toUpperCase(),
    live: live?.ready ? 'LISTENING' : live ? 'CONNECTING' : 'OFFLINE',
    modules: { clock: state.preferences.modules.clock, reactor: state.preferences.modules.reactor,
      dots: state.preferences.modules.dots, assistant: state.preferences.modules.assistant },
    assistantText: state.preferences.modules.assistant ? assistantText : '',
    assistantSource: state.preferences.modules.assistant ? assistantSource : 'NONE',
    assistantPending: Boolean(activeChat),
    effectsPaused,
    brightness: state.preferences.hudBrightness,
    level: levels[0].level, audioSource: levels[0].level > 0 ? levels[0].source.toUpperCase() : 'SILENT'
  };
}
function publishHudStatus() {
  const value = hudStatus();
  send('hud-status', value);
  if (hudWindow && !hudWindow.isDestroyed()) hudWindow.webContents.send('hud-status', value);
}
function clearAssistant() {
  assistantText = '';
  assistantSource = 'NONE';
  if (activeChat) activeChat.hudText = '';
  if (live) live.hudText = '';
}
function assistantOutput(operation, source, chunk) {
  if (!state.preferences.modules.assistant) return;
  operation.hudText = ((operation.hudText || '') + chunk).slice(-1200);
  assistantText = operation.hudText;
  assistantSource = source;
  publishHudStatus();
}
function secondaryDisplay(id) {
  const primary = screen.getPrimaryDisplay();
  return screen.getAllDisplays().find(display => display.id === id && display.id !== primary.id &&
    (display.bounds.x + display.bounds.width <= primary.bounds.x ||
      primary.bounds.x + primary.bounds.width <= display.bounds.x ||
      display.bounds.y + display.bounds.height <= primary.bounds.y ||
      primary.bounds.y + primary.bounds.height <= display.bounds.y));
}
function displayState() {
  return {
    displays: screen.getAllDisplays().map(display => ({
      id: display.id, primary: display.id === screen.getPrimaryDisplay().id,
      label: typeof display.label === 'string' ? display.label.slice(0, 120) : '',
      width: display.bounds.width, height: display.bounds.height
    })),
    selectedId: selectedDisplayId, open: Boolean(hudWindow && !hudWindow.isDestroyed()), notice: displayNotice
  };
}
function publishDisplays() { send('display-state', displayState()); }
function keepControlsOnPrimary() {
  if (!win || win.isDestroyed() || placingControls || win.isMinimized()) return;
  const primary = screen.getPrimaryDisplay();
  const area = primary.workArea;
  const bounds = win.getBounds();
  if ((win.isMaximized() || win.isFullScreen()) && screen.getDisplayMatching(bounds).id === primary.id) return;
  placingControls = true;
  try {
    if (win.isMaximized()) win.unmaximize();
    if (win.isFullScreen()) win.setFullScreen(false);
    win.setMinimumSize(Math.min(780, area.width), Math.min(600, area.height));
    const width = Math.min(bounds.width, area.width);
    const height = Math.min(bounds.height, area.height);
    const next = {
      x: Math.max(area.x, Math.min(bounds.x, area.x + area.width - width)),
      y: Math.max(area.y, Math.min(bounds.y, area.y + area.height - height)), width, height
    };
    if (['x', 'y', 'width', 'height'].some(key => bounds[key] !== next[key])) win.setBounds(next);
  } finally { placingControls = false; }
}
function closeHud(notice = 'HUD closed. Controls remain on the PC.') {
  const old = hudWindow;
  hudWindow = undefined;
  displayNotice = notice;
  if (old && !old.isDestroyed()) {
    old.hide();
    old.destroy();
  }
  publishDisplays();
}
function reconcileDisplays() {
  keepControlsOnPrimary();
  const display = secondaryDisplay(selectedDisplayId);
  if (selectedDisplayId !== null && !display) {
    selectedDisplayId = null;
    closeHud('Selected display disconnected or became primary. HUD closed; explicitly select a secondary display again.');
    return;
  }
  if (hudWindow && display) {
    // Borderless display bounds avoid native fullscreen's automatic monitor reassignment.
    hudWindow.setBounds(display.bounds);
  }
  publishDisplays();
}
function hudPlacementSafe(window) {
  const display = secondaryDisplay(selectedDisplayId);
  const bounds = window.getBounds();
  return display && screen.getDisplayMatching(bounds).id === display.id &&
    ['x', 'y', 'width', 'height'].every(key => bounds[key] === display.bounds[key]);
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
function stopChat(message = 'Request stopped. You can retry.') {
  const old = activeChat;
  activeChat = undefined;
  if (old) {
    old.abort.abort();
    send('chat-event', { id: old.id, type: 'error', message });
  }
  clearAssistant();
  outputLevels.speech.level = 0;
  publishHudStatus();
}
function stopLive(reason = 'Live session stopped.') {
  if (!live) return;
  const old = live;
  live = undefined;
  clearAssistant();
  outputLevels.gemini.level = 0;
  clearTimeout(old.timer);
  clearTimeout(old.durationTimer);
  old.socket.close();
  send('live-event', { type: 'closed', message: reason });
}
function trusted(event) {
  return win && !win.isDestroyed() && event.sender === win.webContents &&
    event.senderFrame === win.webContents.mainFrame &&
    event.senderFrame.url === PAGE_URL;
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
  const timer = setTimeout(() => {
    if (activeChat === operation) stopChat('Request timed out. You can retry.');
  }, 120000);
  let reader;
  const finish = (type, message) => {
    if (activeChat !== operation || operation.abort.signal.aborted) return;
    activeChat = undefined;
    publishHudStatus();
    send('chat-event', { id, type, message });
  };
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
    let buffer = '', total = 0, incomingBytes = 0, hasText = false;
    let completed = false;
    while (!completed) {
      const { done, value } = await reader.read();
      if (activeChat !== operation || operation.abort.signal.aborted) return;
      if (done) break;
      incomingBytes += value.byteLength;
      if (incomingBytes > 2000000) throw new Error('Incoming stream limit');
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
        const choice = packet.choices?.[0];
        const delta = choice?.delta?.content;
        if (typeof delta === 'string' && delta) {
          total += delta.length;
          if (total > 20000) throw new Error('Output limit');
          hasText ||= delta.trim().length > 0;
          assistantOutput(operation, 'CHAT', delta);
          send('chat-event', { id, type: 'delta', text: delta });
        }
        if (choice?.finish_reason != null) {
          if (choice.finish_reason === 'stop') { completed = true; break; }
          finish('error', choice.finish_reason === 'content_filter' ? 'Provider filtered this reply. Revise your prompt before retrying.' :
            choice.finish_reason === 'length' ? 'Reply reached the provider token limit and is incomplete. Ask for a shorter response.' :
              'Provider could not complete a text reply. Try a different prompt or model.');
          return;
        }
      }
    }
    if (!completed || !hasText) throw new Error('Incomplete or empty response');
    finish('done', 'Response complete.');
  } catch {
    finish('error', 'Connection interrupted or response limit reached. You can retry.');
  } finally {
    clearTimeout(timer);
    if (reader) await reader.cancel().catch(() => {});
    if (activeChat === operation) { activeChat = undefined; publishHudStatus(); }
  }
}
handle('initialize', () => state);
handle('read-hud-status', payload => payload === undefined ? hudStatus() : { error: 'Invalid request.' });
handle('clear-assistant', payload => {
  if (payload !== undefined) return { error: 'Invalid request.' };
  clearAssistant();
  publishHudStatus();
  return { ok: true };
});
handle('output-level', value => {
  if (!exactObject(value, ['source', 'level']) || !Object.hasOwn(outputLevels, value.source) ||
    !Number.isFinite(value.level) || value.level < 0 || value.level > 1) return { error: 'Invalid output level.' };
  if ((value.source === 'gemini' && !live?.ready) ||
    (value.source === 'music' && !state.preferences.modules.music) ||
    (value.source === 'speech' && (!state.preferences.speakReplies || value.level !== 0 && value.level !== 1))) return { ok: false };
  outputLevels[value.source] = { level: value.level, time: Date.now() };
  return { ok: true };
});
handle('open-website', async value => {
  if (!win.isFocused() || !value) return { error: 'Use the launch button in the focused PC window.' };
  let target;
  if (exactObject(value, ['site']) && value.site === 'openrouter') target = new URL('https://openrouter.ai/chat');
  else if (exactObject(value, ['site', 'destination']) && value.site === 'maps' && state.preferences.modules.navigation &&
    text(value.destination, 240) && value.destination.trim() && !/[\x00-\x1f\x7f]/.test(value.destination)) {
    target = new URL('https://www.google.com/maps/dir/');
    target.searchParams.set('api', '1');
    target.searchParams.set('destination', value.destination.trim());
  } else return { error: 'Invalid website or destination.' };
  if (target.protocol !== 'https:' || !['openrouter.ai', 'www.google.com'].includes(target.hostname) ||
    target.username || target.password || target.port) return { error: 'Website rejected.' };
  await shell.openExternal(target.href);
  return { ok: true };
});
handle('displays', payload => payload === undefined ? displayState() : { error: 'Invalid request.' });
handle('select-display', id => {
  if (!Number.isSafeInteger(id) || !secondaryDisplay(id)) {
    reconcileDisplays();
    return { error: 'Choose a connected non-primary display. Use Windows Settings > System > Display > Extend these displays.' };
  }
  if (selectedDisplayId !== id) closeHud('Display selected. Choose Show HUD to open it.');
  selectedDisplayId = id;
  displayNotice = 'Secondary display selected. Show HUD opens only on this display.';
  publishDisplays();
  return displayState();
});
async function showHud(payload) {
  if (payload !== undefined) return { error: 'Invalid request.' };
  keepControlsOnPrimary();
  const display = secondaryDisplay(selectedDisplayId);
  if (!display) {
    reconcileDisplays();
    return { error: 'No valid secondary display selected. Connect glasses, choose Windows Extend, then select the display explicitly.' };
  }
  if (hudWindow && !hudWindow.isDestroyed()) return displayState();
  const hudSession = session.fromPartition('hud-view');
  hudSession.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
  hudSession.setPermissionCheckHandler(() => false);
  const window = new BrowserWindow({
    ...display.bounds, frame: false, thickFrame: false, backgroundColor: '#000000',
    show: false, focusable: false, resizable: false, movable: false, minimizable: false,
    maximizable: false, skipTaskbar: true, alwaysOnTop: true, title: 'Jessica • HUD-only',
    webPreferences: { preload: path.join(__dirname, 'hud-preload.js'), session: hudSession,
      contextIsolation: true, sandbox: true, nodeIntegration: false, webSecurity: true,
      backgroundThrottling: false, devTools: false }
  });
  hudWindow = window;
  window.setMenu(null);
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  window.webContents.on('will-navigate', event => event.preventDefault());
  window.webContents.on('will-redirect', event => event.preventDefault());
  window.webContents.on('will-attach-webview', event => event.preventDefault());
  window.webContents.on('before-input-event', (event, input) => {
    if (input.key === 'Escape') { event.preventDefault(); closeHud(); }
  });
  const checkPlacement = () => {
    if (hudWindow === window && !hudPlacementSafe(window)) {
      selectedDisplayId = null;
      closeHud('HUD placement changed unexpectedly. Closed for safety; select a secondary display again.');
    }
  };
  window.on('move', checkPlacement);
  window.on('resize', checkPlacement);
  window.on('closed', () => {
    if (hudWindow === window) { hudWindow = undefined; displayNotice = 'HUD closed.'; publishDisplays(); }
  });
  try { await window.loadFile('hud.html'); }
  catch { if (hudWindow === window) closeHud('HUD could not load. Try opening it again.'); return { error: 'HUD unavailable.' }; }
  if (hudWindow !== window || !hudPlacementSafe(window) || !win || win.isDestroyed()) {
    if (hudWindow === window) closeHud('Display changed while opening. HUD closed for safety.');
    return { error: 'Display unavailable. Select a secondary display again.' };
  }
  window.setBounds(secondaryDisplay(selectedDisplayId).bounds);
  window.showInactive();
  displayNotice = 'HUD-only view open. Escape on the PC or Close HUD exits. Live still stops if PC controls lose focus.';
  publishHudStatus();
  publishDisplays();
  return displayState();
}
handle('show-hud', showHud);
handle('hud-shortcut', async value => {
  if (!state.preferences.hudShortcuts || !win.isFocused() || dialogActive ||
    !exactObject(value, ['action']) || !['toggle-fx', 'toggle-hud'].includes(value.action)) return { error: 'HUD shortcut unavailable.' };
  if (value.action === 'toggle-fx') {
    effectsPaused = !effectsPaused;
    publishHudStatus();
    return { ok: true, effectsPaused };
  }
  if (hudWindow && !hudWindow.isDestroyed()) {
    closeHud();
    return displayState();
  }
  return showHud();
});
handle('close-hud', payload => {
  if (payload !== undefined) return { error: 'Invalid request.' };
  closeHud();
  return displayState();
});
for (const channel of ['hud-read-status', 'hud-request-close']) {
  ipcMain.handle(channel, (event, payload) => {
    if (payload !== undefined || !hudWindow || hudWindow.isDestroyed() ||
      event.sender !== hudWindow.webContents || event.senderFrame !== hudWindow.webContents.mainFrame ||
      event.senderFrame.url !== HUD_URL) return { error: 'Request rejected.' };
    if (channel === 'hud-read-status') return hudStatus();
    closeHud();
    return { ok: true };
  });
}
handle('set-key', value => {
  if (!value || !Object.hasOwn(keys, value.provider) || !text(value.key, 512) || /[^\x21-\x7e]/.test(value.key)) return { error: 'Invalid key.' };
  if (value.provider === 'gemini') stopLive();
  else stopChat();
  keys[value.provider] = value.key;
  return { ok: true };
});
handle('preferences', async value => {
  const next = validPreferences(value);
  if (!next) return { error: 'Invalid preferences.' };
  if (state.preferences.provider !== next.provider || state.preferences.model !== next.model) stopChat();
  if (!next.modules.assistant) clearAssistant();
  if (!next.modules.music) outputLevels.music.level = 0;
  if (!next.speakReplies) outputLevels.speech.level = 0;
  if (!next.hudShortcuts) effectsPaused = false;
  state.preferences = next;
  publishHudStatus();
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
  const operation = { abort: new AbortController(), id: request.id, hudText: '' };
  clearAssistant();
  activeChat = operation;
  publishHudStatus();
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
  if (dialogActive) return { error: 'Finish the current export dialog first.' };
  let result;
  dialogActive = true;
  try {
    result = await dialog.showSaveDialog(win, { title: 'Export conversation (plain text, no API keys)', defaultPath: 'Jessica-chat.txt', filters: [{ name: 'Text', extensions: ['txt'] }] });
  } finally { dialogActive = false; }
  if (result.canceled || !result.filePath) return { canceled: true };
  await fs.writeFile(result.filePath, `Jessica HUD • ${value.provider} • ${value.model}\n\n` +
    value.messages.map(m => `${m.role.toUpperCase()}\n${m.content}`).join('\n\n'), 'utf8');
  return { ok: true };
});
handle('start-live', request => {
  if (!request || !modelOK(request.model) || !keys.gemini || !win.isFocused()) return { error: 'Live needs a Gemini key, valid model and focused window.' };
  stopLive();
  clearAssistant();
  // The official Live API authenticates the socket with a query key. Never log this URL.
  const socket = new WebSocket(`wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${encodeURIComponent(keys.gemini)}`);
  const operation = { socket, ready: false, timer: setTimeout(() => stopLive('Live connection timed out.'), 20000) };
  live = operation;
  publishHudStatus();
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
      if (data.goAway) { stopLive('Gemini session ending. Start a fresh session to reconnect.'); return; }
      if (data.setupComplete) {
        clearTimeout(operation.timer);
        operation.ready = true;
        send('live-event', { type: 'ready', message: 'Live connected. Microphone active.' });
      }
      const server = data.serverContent;
      if (!server) return;
      if (server.interrupted) {
        clearAssistant();
        outputLevels.gemini.level = 0;
        publishHudStatus();
        send('live-event', { type: 'interrupted' });
      }
      for (const field of ['inputTranscription', 'outputTranscription']) {
        if (text(server[field]?.text, 20000)) {
          if (field === 'outputTranscription') assistantOutput(operation, 'GEMINI', server[field].text);
          send('live-event', { type: 'transcript', speaker: field === 'inputTranscription' ? 'You' : 'Jessica', text: server[field].text });
        }
      }
      for (const part of (server.modelTurn?.parts || []).slice(0, 32)) {
        const audio = part.inlineData;
        if (audio && text(audio.data, 1000000) && /^audio\/pcm(?:;rate=24000)?$/.test(audio.mimeType)) send('live-event', { type: 'audio', data: audio.data });
      }
      if (server.turnComplete) operation.hudText = '';
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
      const p = validPreferences(saved.preferences, true);
      if (p) {
        state.preferences = p;
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
      contents.mainFrame.url === PAGE_URL && details.isMainFrame === true && details.requestingUrl === PAGE_URL &&
      details.mediaTypes?.length === 1 && details.mediaTypes[0] === 'audio');
  });
  session.defaultSession.setPermissionCheckHandler((contents, permission, _origin, details) =>
    contents === win?.webContents && win.isFocused() && permission === 'media' &&
    contents.mainFrame.url === PAGE_URL && details.isMainFrame === true &&
    details.requestingUrl === PAGE_URL && details.mediaType === 'audio');
  const area = screen.getPrimaryDisplay().workArea;
  const width = Math.min(1380, area.width), height = Math.min(920, area.height);
  win = new BrowserWindow({ width, height,
    x: area.x + Math.floor((area.width - width) / 2), y: area.y + Math.floor((area.height - height) / 2),
    minWidth: Math.min(780, area.width), minHeight: Math.min(600, area.height), backgroundColor: '#050b14',
    title: 'Jessica • Desktop HUD', autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false, webSecurity: true } });
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', event => event.preventDefault());
  win.webContents.on('will-attach-webview', event => event.preventDefault());
  win.webContents.on('before-input-event', (event, input) => {
    if (input.key === 'Escape' && hudWindow) { event.preventDefault(); closeHud(); }
  });
  screen.on('display-added', reconcileDisplays);
  screen.on('display-removed', reconcileDisplays);
  screen.on('display-metrics-changed', reconcileDisplays);
  win.on('move', keepControlsOnPrimary);
  win.on('resize', keepControlsOnPrimary);
  win.on('restore', keepControlsOnPrimary);
  win.on('blur', () => stopLive('Live paused: window lost focus. Start again to resume.'));
  win.on('close', () => closeHud());
  win.on('closed', () => { stopChat(); stopLive(); win = undefined; Object.keys(keys).forEach(k => { keys[k] = ''; }); });
  await win.loadFile('index.html');
  const statusTimer = setInterval(publishHudStatus, 100);
  win.on('closed', () => clearInterval(statusTimer));
});
app.on('window-all-closed', () => app.quit());
app.on('before-quit', () => { closeHud(); stopChat(); stopLive(); });
