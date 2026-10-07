const { contextBridge, ipcRenderer } = require('electron');
const call = async (channel, ...args) => {
  const result = await ipcRenderer.invoke(channel, ...args);
  if (!result.ok) throw new Error(result.error);
  return result.value;
};
contextBridge.exposeInMainWorld('jessica', Object.freeze({
  getSettings: () => call('settings:get'),
  saveSettings: settings => call('settings:save', settings),
  setKey: key => call('session:key', key),
  send: messages => call('chat:send', messages),
  cancel: () => call('chat:cancel'),
  models: () => call('models:list'),
  voices: () => call('voice:list'),
  listen: () => call('voice:listen'),
  speak: text => call('voice:speak', text),
  stopVoice: () => call('voice:stop'),
  exportChat: messages => call('chat:export', messages)
}));
