const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('abs', {
  state: () => ipcRenderer.invoke('abs:state'),
  cmd: (name, arg) => ipcRenderer.invoke('abs:cmd', name, arg),
  openMain: () => ipcRenderer.send('popup:open-main'),
  hide: () => ipcRenderer.send('popup:hide'),
  cursor: () => ipcRenderer.invoke('popup:cursor'),
  list: (open, h) => ipcRenderer.invoke('popup:list', open, h),
  listHeight: 300,
  onShown: (cb) => ipcRenderer.on('popup-shown', () => cb()),
  lang: () => ipcRenderer.sendSync('i18n:get'),
  onLang: (cb) => ipcRenderer.on('i18n:changed', (_e, code) => cb(code)),
  devices: () => ipcRenderer.invoke('devices:get'),
  selectDevice: (id) => ipcRenderer.invoke('devices:select', id),
  playHere: () => ipcRenderer.invoke('devices:playHere'),
  deviceInfo: (host, port) => ipcRenderer.invoke('devices:info', host, port),
  pair: (p) => ipcRenderer.invoke('devices:pair', p),
  unpair: (id) => ipcRenderer.invoke('devices:unpair', id),
  renameDevice: (id, name) => ipcRenderer.invoke('devices:rename', id, name)
});
