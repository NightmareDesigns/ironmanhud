'use strict';
const { contextBridge, ipcRenderer } = require('electron');
const invoke = name => payload => ipcRenderer.invoke(name, payload);
const subscribe = channel => callback => {
  const handler = (_event, value) => callback(value);
  ipcRenderer.on(channel, handler);
  return () => ipcRenderer.removeListener(channel, handler);
};
contextBridge.exposeInMainWorld('hud', Object.freeze({
  initialize: invoke('initialize'), setKey: invoke('set-key'), preferences: invoke('preferences'),
  models: invoke('models'), chat: invoke('chat'), cancelChat: invoke('cancel-chat'),
  saveChat: invoke('save-chat'), deleteChat: invoke('delete-chat'), exportChat: invoke('export-chat'),
  startLive: invoke('start-live'), stopLive: invoke('stop-live'), liveAudio: invoke('live-audio'),
  onChat: subscribe('chat-event'), onLive: subscribe('live-event'),
  displays: invoke('displays'), selectDisplay: invoke('select-display'),
  showHud: invoke('show-hud'), closeHud: invoke('close-hud'), onDisplays: subscribe('display-state'),
  readHudStatus: invoke('read-hud-status'), onHudStatus: subscribe('hud-status'),
  outputLevel: invoke('output-level'), clearAssistant: invoke('clear-assistant'), openWebsite: invoke('open-website'),
  hudShortcut: invoke('hud-shortcut'), navigation: invoke('navigation')
}));
