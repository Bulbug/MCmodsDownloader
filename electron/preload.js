const { contextBridge, ipcRenderer } = require('electron')

function readConfig() {
  return ipcRenderer.invoke('config:read')
}
function writeConfig(config) {
  return ipcRenderer.invoke('config:write', config)
}
function readPacks() {
  return ipcRenderer.invoke('packs:read')
}
function writePacks(packs) {
  return ipcRenderer.invoke('packs:write', packs)
}
function readFavs() {
  return ipcRenderer.invoke('favs:read')
}
function writeFavs(favs) {
  return ipcRenderer.invoke('favs:write', favs)
}
function readTheme() {
  return ipcRenderer.invoke('theme:read')
}
function writeTheme(theme) {
  return ipcRenderer.invoke('theme:write', theme)
}
function readVersion() {
  return ipcRenderer.invoke('version:read')
}
function writeVersion(version) {
  return ipcRenderer.invoke('version:write', version)
}
function showOpenDialog(options) {
  return ipcRenderer.invoke('dialog:showOpenDialog', options)
}
function showMessageBox(message, options) {
  return ipcRenderer.invoke('dialog:showMessageBox', options)
}
function fsWriteFile(filePath, content) {
  return ipcRenderer.invoke('fs:writeFile', filePath, content)
}
function fsMkdir(dirPath) {
  return ipcRenderer.invoke('fs:mkdir', dirPath)
}

const config = {
  read: readConfig,
  write: writeConfig,
  readPacks: readPacks,
  writePacks: writePacks,
  readFavs: readFavs,
  writeFavs: writeFavs,
  readTheme: readTheme,
  writeTheme: writeTheme,
  readVersion: readVersion,
  writeVersion: writeVersion,
  showOpenDialog,
  showMessageBox,
  fsWriteFile,
  fsMkdir
}

contextBridge.exposeInMainWorld('app', config)
