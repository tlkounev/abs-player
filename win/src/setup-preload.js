const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('setup', {
  save: (url) => ipcRenderer.invoke('setup:save', url),
  lang: () => ipcRenderer.sendSync('i18n:get'),
  setLang: (code) => ipcRenderer.send('i18n:set', code),
  onLang: (cb) => ipcRenderer.on('i18n:changed', (_e, code) => cb(code))
});
