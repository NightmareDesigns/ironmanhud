const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('node:path');
const fs = require('node:fs/promises');
const { pathToFileURL } = require('node:url');
const { settingsFrom, requestAI } = require('./local-ai');
const { windowsVoice, stopVoice } = require('./voice');
let window, settings, activeRequest;
let sessionKey = '';
const indexUrl = pathToFileURL(path.join(__dirname, 'index.html')).href;
const settingsPath = () => path.join(app.getPath('userData'), 'settings.json');

function handle(channel, action) {
  ipcMain.handle(channel, async (event, ...args) => {
    if (event.sender !== window?.webContents || event.senderFrame !== event.sender.mainFrame ||
        event.senderFrame.url !== indexUrl) throw new Error('Untrusted IPC sender.');
    try { return { ok: true, value: await action(...args) }; }
    catch (error) {
      return { ok: false, error: error.name === 'AbortError' ? 'Request cancelled.' :
        error.name === 'TimeoutError' ? 'Local server timed out. Increase the timeout or check the loaded model.' : error.message };
    }
  });
}

app.whenReady().then(async () => {
  try { settings = settingsFrom(JSON.parse(await fs.readFile(settingsPath(), 'utf8'))); }
  catch { settings = settingsFrom(null); }
  handle('settings:get', () => settings);
  handle('settings:save', async input => {
    const next = settingsFrom(input);
    await fs.mkdir(app.getPath('userData'), { recursive: true });
    await fs.writeFile(settingsPath(), JSON.stringify(next, null, 2), { mode: 0o600 });
    settings = next;
    return settings;
  });
  handle('session:key', key => {
    if (typeof key !== 'string' || key.length > 4096 || /[\r\n]/.test(key)) throw new Error('Invalid session token.');
    sessionKey = key;
  });
  const request = async (messages, modelsOnly) => {
    if (activeRequest) throw new Error('A local request is already running.');
    const controller = new AbortController();
    activeRequest = controller;
    try { return await requestAI(settings, messages, sessionKey, controller.signal, modelsOnly); }
    finally { if (activeRequest === controller) activeRequest = null; }
  };
  handle('chat:send', messages => request(messages, false));
  handle('models:list', () => request([], true));
  handle('chat:cancel', () => { activeRequest?.abort(); });
  handle('voice:list', () => windowsVoice('voices'));
  handle('voice:listen', () => { stopVoice(); return windowsVoice('listen'); });
  handle('voice:speak', text => {
    if (typeof text !== 'string' || text.length > 16000) throw new Error('Invalid speech text.');
    stopVoice();
    return windowsVoice('speak', { text, voice: settings.voice, rate: settings.rate, volume: settings.volume });
  });
  handle('voice:stop', stopVoice);
  handle('chat:export', async messages => {
    if (!Array.isArray(messages) || messages.length > 200 ||
        messages.some(m => !m || !['user', 'assistant'].includes(m.role) ||
          typeof m.content !== 'string' || m.content.length > 16000)) throw new Error('Invalid transcript.');
    const result = await dialog.showSaveDialog(window, {
      title: 'Export chat (contains your conversation)', defaultPath: 'jessica-chat.txt',
      filters: [{ name: 'Text', extensions: ['txt'] }]
    });
    if (!result.canceled) await fs.writeFile(result.filePath,
      messages.map(m => `${m.role === 'user' ? 'You' : 'Jessica'}:\n${m.content}`).join('\n\n'), 'utf8');
    return !result.canceled;
  });
  window = new BrowserWindow({
    width: 1100, height: 800, minWidth: 640, minHeight: 540, title: 'Jessica — Nightmare Suite',
    backgroundColor: '#070e16',
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true,
      nodeIntegration: false, sandbox: true, webSecurity: true }
  });
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  window.webContents.on('will-navigate', event => event.preventDefault());
  window.webContents.session.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
  window.webContents.session.setPermissionCheckHandler(() => false);
  window.on('closed', () => {
    activeRequest?.abort();
    stopVoice();
    sessionKey = '';
    window = null;
  });
  await window.loadFile(path.join(__dirname, 'index.html'));
});
app.on('window-all-closed', () => app.quit());
