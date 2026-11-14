// Bridge between the reader window and the main process.
'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('absReader', {
  config: () => ipcRenderer.sendSync('reader:config'),
  bookData: () => ipcRenderer.invoke('reader:book'),
  relocated: (json) => ipcRenderer.send('reader:relocated', json),
  saveLocations: (json) => ipcRenderer.send('reader:locations', json),
  listen: (seconds) => ipcRenderer.send('reader:listen', seconds),
  saveSettings: (json) => ipcRenderer.send('reader:settings', json),
  lang: () => ipcRenderer.sendSync('i18n:get')
});
