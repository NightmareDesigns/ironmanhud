'use strict';
const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('displayHud', Object.freeze({
  readStatus: () => ipcRenderer.invoke('hud-read-status'),
  close: () => ipcRenderer.invoke('hud-request-close'),
  onStatus: callback => {
    const handler = (_event, value) => callback(value);
    ipcRenderer.on('hud-status', handler);
    return () => ipcRenderer.removeListener('hud-status', handler);
  }
}));
