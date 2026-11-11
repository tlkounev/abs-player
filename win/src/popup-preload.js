const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('abs', {
  state: () => ipcRenderer.invoke('abs:state'),
  cmd: (name, arg) => ipcRenderer.invoke('abs:cmd', name, arg),
  openMain: () => ipcRenderer.send('popup:open-main'),
  hide: () => ipcRenderer.send('popup:hide'),
  cursor: () => ipcRenderer.invoke('popup:cursor'),
  list: (open) => ipcRenderer.invoke('popup:list', open),
  listHeight: 300,
  onShown: (cb) => ipcRenderer.on('popup-shown', () => cb()),
  lang: () => ipcRenderer.sendSync('i18n:get'),
  onLang: (cb) => ipcRenderer.on('i18n:changed', (_e, code) => cb(code)),
  devices: () => ipcRenderer.invoke('devices:get'),
  selectDevice: (id) => ipcRenderer.invoke('devices:select', id),
  deviceInfo: (host, port) => ipcRenderer.invoke('devices:info', host, port),
  pair: (p) => ipcRenderer.invoke('devices:pair', p),
  unpair: (id) => ipcRenderer.invoke('devices:unpair', id)
});
