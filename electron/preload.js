const { contextBridge, ipcRenderer } = require('electron')

const app = {
  read: () => ipcRenderer.invoke('config:read'),
  write: (data) => ipcRenderer.invoke('config:write', data),
  readPacks: () => ipcRenderer.invoke('packs:read'),
  writePacks: (data) => ipcRenderer.invoke('packs:write', data),
  readFavs: () => ipcRenderer.invoke('favs:read'),
  writeFavs: (data) => ipcRenderer.invoke('favs:write', data),
  showOpenDialog: (options) => ipcRenderer.invoke('dialog:showOpenDialog', options),
  fsMkdir: (path) => ipcRenderer.invoke('fs:mkdir', path),
  fsWriteFile: (path, content) => ipcRenderer.invoke('fs:writeFile', path, content)
}

contextBridge.exposeInMainWorld('app', app)