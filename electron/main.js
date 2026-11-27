const { app, BrowserWindow, ipcMain, dialog } = require('electron')
const path = require('node:path')
const fs = require('node:fs')

let mainWindow = null
const CONFIG_PATH = path.join(app.getPath('userData'), 'config.json')
const PACKS_PATH = path.join(app.getPath('userData'), 'packs.json')
const FAV_PATH = path.join(app.getPath('userData'), 'favs.json')
const THEME_PATH = path.join(app.getPath('userData'), 'theme.json')
const VERSION_PATH = path.join(app.getPath('userData'), 'version.json')

function readConfig() {
  try {
    const data = fs.readFileSync(CONFIG_PATH, 'utf8')
    return JSON.parse(data)
  } catch (e) {
    return { version: '', launcherFolder: '', theme: 'dark', loader: 'fabric', ploader: 'paper' }
  }
}

function writeConfig(config) {
  const dir = path.dirname(CONFIG_PATH)
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(config, null, 2))
}

function readPacks() {
  try {
    const data = fs.readFileSync(PACKS_PATH, 'utf8')
    return JSON.parse(data)
  } catch (e) {
    return []
  }
}

function writePacks(packs) {
  const dir = path.dirname(PACKS_PATH)
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })
  fs.writeFileSync(PACKS_PATH, JSON.stringify(packs, null, 2))
}

function readFavs() {
  try {
    const data = fs.readFileSync(FAV_PATH, 'utf8')
    return JSON.parse(data)
  } catch (e) {
    return []
  }
}

function writeFavs(favs) {
  const dir = path.dirname(FAV_PATH)
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })
  fs.writeFileSync(FAV_PATH, JSON.stringify(favs, null, 2))
}

function readTheme() {
  try {
    const data = fs.readFileSync(THEME_PATH, 'utf8')
    return JSON.parse(data)
  } catch (e) {
    return { theme: 'dark' }
  }
}

function writeTheme(theme) {
  const dir = path.dirname(THEME_PATH)
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })
  fs.writeFileSync(THEME_PATH, JSON.stringify(theme, null, 2))
}

function readVersion() {
  try {
    const data = fs.readFileSync(VERSION_PATH, 'utf8')
    return JSON.parse(data)
  } catch (e) {
    return {}
  }
}

function writeVersion(version) {
  const dir = path.dirname(VERSION_PATH)
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })
  fs.writeFileSync(VERSION_PATH, JSON.stringify(version, null, 2))
}

function showOpenDialog(options) {
  return dialog.showOpenDialog(options)
}

function showMessageBox(message, options) {
  return dialog.showMessageBoxSync(message, options)
}

function createWindow() {
  const iconPath = path.join(__dirname, '../src/assets/icon.png')
  const icon = fs.existsSync(iconPath) ? iconPath : undefined

  mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 900,
    minHeight: 600,
    title: 'DIV Modpack Builder',
    ...(icon && { icon }),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      enableRemoteModule: false
    },
    show: false
  })

  if (process.env.VITE_DEV_SERVER_URL) {
    mainWindow.loadURL(process.env.VITE_DEV_SERVER_URL)
    mainWindow.webContents.openDevTools()
  } else {
    mainWindow.loadFile(path.join(__dirname, '../dist/index.html'))
  }

  mainWindow.once('ready-to-show', () => mainWindow.show())
  mainWindow.on('closed', () => { mainWindow = null })
}

app.whenReady().then(createWindow)

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) createWindow()
})

ipcMain.handle('app:quit', () => app.quit())

// File system IPC handlers
ipcMain.handle('fs:readFile', (event, path) => {
  try {
    return fs.readFileSync(path, 'utf8')
  } catch (err) {
    return { error: err.message }
  }
})

ipcMain.handle('fs:writeFile', (event, filePath, content) => {
  try {
    const dir = path.dirname(filePath)
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })

    // Handle Buffer or ArrayBuffer from the renderer
    if (content instanceof ArrayBuffer) {
      fs.writeFileSync(filePath, Buffer.from(content))
    } else if (Buffer.isBuffer(content)) {
      fs.writeFileSync(filePath, content)
    } else {
      fs.writeFileSync(filePath, content, 'utf8')
    }

    return true
  } catch (err) {
    return { error: err.message }
  }
})

ipcMain.handle('fs:readdir', (event, path) => {
  try {
    return fs.readdirSync(path)
  } catch (err) {
    return { error: err.message }
  }
})

ipcMain.handle('fs:mkdir', (event, path) => {
  try {
    const dir = path.dirname(path)
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })
    fs.mkdirSync(path, { recursive: true })
    return true
  } catch (err) {
    return { error: err.message }
  }
})

ipcMain.handle('fs:stat', (event, path) => {
  try {
    return fs.statSync(path)
  } catch (err) {
    return { error: err.message }
  }
})

ipcMain.handle('fs:unlink', (event, path) => {
  try {
    fs.unlinkSync(path)
    return true
  } catch (err) {
    return { error: err.message }
  }
})

ipcMain.handle('dialog:showOpenDialog', (event, options) => {
  return dialog.showOpenDialog(options)
})

ipcMain.handle('dialog:showMessageBox', (event, options) => {
  return dialog.showMessageBoxSync(options)
})

// Config IPC handlers
ipcMain.handle('config:read', () => readConfig())
ipcMain.handle('config:write', (event, data) => writeConfig(data))

// Packs IPC handlers
ipcMain.handle('packs:read', () => readPacks())
ipcMain.handle('packs:write', (event, data) => writePacks(data))

// Favs IPC handlers
ipcMain.handle('favs:read', () => readFavs())
ipcMain.handle('favs:write', (event, data) => writeFavs(data))