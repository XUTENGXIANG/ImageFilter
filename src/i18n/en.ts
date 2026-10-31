// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
// English translation
// Convention: every UI string lives here, components call t("key") only
export default {
  titlebar: {
    help: "Help",
    settings: "Settings",
    themeLight: "Switch to light theme",
    themeDark: "Switch to dark theme",
    min: "Minimize",
    max: "Maximize",
    close: "Close",
  },

  settings: {
    title: "Settings",
    subtitle: "ImageFilter application settings",
    version: "Version {v}",
    // Update check (version compare only, no download). The button label doubles as the status.
    checkUpdate: "Check for updates",
    checkUpdateHint: "Compare the version with the latest release on GitHub",
    checkingUpdate: "Checking…",
    updateLatest: "Up to date",
    updateLatestHint: "You have the latest release (click to check again)",
    updateAvailable: "{v} available",
    updateAvailableHint: "Open the GitHub release page",
    updateFailed: "Check failed, retry",
    updateFailedHint: "Check failed: {reason}",
    updateErrNetwork: "network unavailable",
    updateErrTimeout: "timed out",
    updateErrHttp: "HTTP {code}",
    updateErrUnexpected: "unexpected response",
    theme: "Theme",
    themeDesc: "Dark / Light",
    dark: "Dark",
    light: "Light",
    language: "Language",
    languageDesc: "Interface language",
    preload: "Preload visible full images",
    preloadDesc: "Preload visible photos for a faster viewer",
    autoAdvance: "Auto-advance after rating",
    autoAdvanceDesc: "Auto-next after rating (← back)",
    showCounts: "Show folder counts",
    showCountsDesc: "Show counts in the left sidebar",
    labelKeys: "Color label shortcut",
    labelKeysDesc: "Label modifier (Alt if taken)",
    // Phase 5 · XMP sidecar (three-step switch, default "Ask" — changed from "Off" in session 6,
    // see src/xmp.ts::DEFAULT_XMP_MODE)
    // Status text shares this row's desc on purpose (adding a row means re-checking total height)
    // Kept to one line each (session ⑤ cleanup); the buttons carry the rest
    xmpMode: "Write ratings/labels to .xmp",
    xmpModeDesc: "Write .xmp sidecar; Off = app only",
    xmpModeOff: "Off",
    xmpModeAsk: "Ask",
    xmpModeOn: "On",
    xmpStatusOff: "Not enabled",
    xmpStatusWritable: "{dir} is writable",
    xmpStatusUnwritable: "{dir} is not writable: {reason}",
    xmpStatusDegraded: " (no atomic replace; wrote directly)",
    // Phase 7 · Lightroom handoff (send mode in Settings; status text is appended to desc)
    lrcMode: "Lightroom handoff",
    lrcModeDialog: "Open import",
    lrcModeSilent: "Silent import (unsupported)",
    lrcModeSilentWhy: "Requires Lightroom Auto Import (not supported yet)",
    lrcReprobe: "Re-detect",
    lrcProbing: "Detecting…",
    lrcNotFound: "Lightroom Classic not detected",
    lrcRunning: "running",
    lrcNotRunning: "not running",
    transparentBg: "Transparent frosted background",
    transparentBgDesc: "Windows Mica glass background",
  transparentBgDescMac: "macOS transparent glass background",
  lrcUnsupportedMac: "Lightroom handoff is not supported on macOS (Windows only)",
  macPermTitle: "macOS needs file access permission",
  macPermBody: "Reading photos and writing .xmp triggers permission prompts. Grant Full Disk Access once to stop them.",
  macPermPath: "System Settings → Privacy & Security → Full Disk Access → enable ImageFilter",
  macPermOpen: "Open System Settings",
  macPermLater: "Later",
    transparentBgUnsupported: "Not available on this system (requires Windows 11)",
    transparentBgOpacity: "Title bar glass opacity",
    transparentBgOpacityDesc: "Title bar only",
    backgroundOpacity: "Background glass opacity",
    backgroundOpacityDesc: "Backdrop behind panels only",
  },

  help: {
    title: "Quick Guide",
    subtitle: "ImageFilter — Photo Importer",
    step1Title: "Insert SD card",
    step1Desc: "Insert your camera card; devices are detected automatically",
    step2Title: "Browse photos",
    step2Desc: "Filter by format, view EXIF, preview thumbnails, view RAW originals...",
    step3Title: "Filter & rate",
    step3Desc: "Run AI analysis to check",
    step4Title: "Import",
    step4Desc: "Select photos → pick destination → click Import",
    shortcuts: "Keyboard Shortcuts",
    keep: "Keep (3★)",
    trash: "Reject (0★)",
    star: "Rate 1-5★",
    label: "Ctrl/Alt+1-5",
    labelDesc: "Color label (grid & viewer)",
    autoAdvance: "Auto-advance after rating",
    rotate: "Rotate (viewer)",
    nav: "Navigate (viewer)",
    reset: "Reset (viewer)",
    actual: "1:1 actual pixels",
    undo: "Ctrl+Z",
    undoDesc: "Undo rating / selection (this session only; cleared when switching folder or device)",
    select: "Select (viewer)",
    space: "Space",
    ctrlClick: "Ctrl+Click",
    shiftClick: "Shift+Click",
    multi: "Multi-select",
    range: "Range select",
    contextMenu: "Context Menu",
    ctxPhoto: "Photo: import / rate / EXIF / reveal in Explorer",
    ctxEmpty: "Empty: refresh / import all / AI analysis",
    ctxDevice: "Device: open / eject (removable)",
    ctxFolder: "Folder: open / import all",
  },

  devices: {
    panel: "Devices",
    disks: "Disks",
    refresh: "Refresh",
    open: "Open",
    eject: "Eject",
    ejectOk: "Ejected {dir}",
    ejectFail: "Eject failed",
    fixedDisk: "Fixed drive cannot be ejected",
    refreshList: "Refresh device list",
    noDevices: "No devices detected",
    scanning: "Scanning folder structure...",
    root: "Root",
    notScanned: "Not scanned",
    selectDevice: "Select a device",
    browsing: "Browsing...",
    loading: "Loading...",
    counting: "Counting photos...",
    photos: "{n} photos",
    folders: "{n} folders",
    videos: "{n} videos",
    ready: "Ready",
  },

  toolbar: {
    selectAll: "Select all",
    clear: "Clear",
    selected: "{n}/{total} selected",
    sortName: "Name",
    sortType: "Type",
    sortDate: "Date",
    all: "All",
    starFilter: "{n}★+",
    stop: "Stop",
    ai: "AI Analyze",
    aiCount: "AI Analyze ({n} selected)",
    aiSelected: "Analyze only the {n} selected",
    filter: "Filters",
    flags: "Analysis",
    sortDir: "Toggle sort direction",
    sortBy: "Sort by",
    starFilterLabel: "Star filter",
    clearFilters: "Clear filters",
    cols: "{n} cols",
    empty: "Open a photo folder to show toolbar",
  },

  grid: {
    browsing: "Browsing...",
    loading: "Loading photos...",
    noPhotos: "No photos in this folder",
    clickFolder: "Click a folder on the left to view photos",
    video: "Video",
    select: "Select this photo",
    unselect: "Deselect this photo",
    blurry: "Blurry",
    overexposed: "Overexposed",
    underexposed: "Underexposed",
    duplicate: "Duplicate",
    best: "Best",
    pendingAnalysis: "{n} photos not analyzed yet",
    analyzePending: "Analyze these {n}",
    noMatch: "No photos match the current filters",
  },

  import: {
    pickDest: "Choose destination",
    pickDestTitle: "Choose import destination folder",
    openFolder: "Open folder",
    needDest: "Choose a destination folder first",
    needSelect: "Select photos to import",
    importing: "Importing...",
    importingCount: "Importing {done}/{total}",
    importCount: "Import {n}",
    error: "Error: {msg}",
    doneOk: "Import complete ✓ {n} succeeded",
    // Phase 6 / 6.3 · four-line breakdown. "of which" makes clear renamed ⊆ imported;
    // skipped and failed are deliberately separate lines.
    doneRenamed: "of which {n} renamed to avoid a clash",
    doneSkipped: "{n} already present, skipped",
    doneFail: "{n} failed",
    advanced: "Advanced options",
    dateFolder: "Organize by date folders",
    dateFolderEx: "e.g. 2024-08-08/photo.jpg",
    cameraFolder: "Organize by camera model",
    cameraFolderEx: "e.g. Sony-A7M4/photo.jpg",
    seqRename: "Rename by sequence number",
    seqRenameEx: "e.g. 0001.ARW",
    subFolder: "Import into subfolder",
    subFolderPlaceholder: "Enter folder name",
    // Phase 6 / 6.1 · import history (its own dialog, opened from the import bar)
    history: "Import history",
    historyHint: "Once renamed, the original filename can only be recovered here; click an archive path to open its folder",
    historyTotal: "{n} records",
    historyShown: "Showing {shown} / {total}",
    historyEmpty: "No imports yet",
    historyError: "Failed to read import history: {msg}",
    historyLoadMore: "Load more",
    historyColSource: "Source",
    historyColDest: "Archive",
    historyOpenFolder: "Open containing folder",
    // Phase 6 / 6.2 · naming schemes (import_rules)
    scheme: "Scheme",
    schemeCustom: "Custom",
    schemeSaveAs: "Save as",
    schemeNamePlaceholder: "Scheme name",
    schemeSave: "Save",
    schemeError: "Naming scheme error: {msg}",
    seqKeepOriginal: "Keep original filename",
    seqKeepEx: "e.g. 0001_IMG_1234.ARW",
    fileRuleNowLabel: "Filename: ",
    fileRuleOriginal: "original",
  },

  // ── Color labels (Phase 4) ──
  label: {
    title: "Color labels",
    red: "Red",
    yellow: "Yellow",
    green: "Green",
    blue: "Blue",
    purple: "Purple",
    clear: "Clear label",
    none: "None",
  },

  menu: {
    importSelected: "Import selected",
    importCount: "Import {n}",
    rating: "Rating",
    clearRating: "Clear rating",
    viewExif: "View EXIF",
    openLocation: "Reveal in Explorer",
    selectAll: "Select all",
    deselect: "Deselect",
    refresh: "Refresh",
    importAll: "Import all",
    ai: "AI Analyze",
  },

  exif: {
    panel: "Details",
    hint: "Select a photo to view EXIF",
    fileInfo: "File Info",
    fileName: "File name",
    size: "Size",
    type: "Type",
    typeImage: "Image",
    typeVideo: "Video",
    camera: "Camera",
    brand: "Make",
    model: "Model",
    lens: "Lens",
    params: "Exposure",
    aperture: "Aperture",
    shutter: "Shutter",
    iso: "ISO",
    focal: "Focal length",
    date: "Date",
    dims: "Dimensions",
  },

  welcome: {
    subtitle: "Photo filter & import tool",
  },

  viewer: {
    rotateCCW: "Rotate counter-clockwise (Shift+R)",
    rotateCW: "Rotate clockwise (R)",
    prev: "Previous (←)",
    next: "Next (→)",
    nav: "← → navigate",
    zoom: "Wheel zoom",
    actual: "1:1 actual pixels (Z)",
    pan: "Drag pan",
    reset: "0 reset",
    rotate: "R rotate",
    rate: "J keep / X reject / 1-5 rate",
    select: "Select (Space)",
    unselect: "Deselect (Space)",
  },

  // ── Collapsible bars ──
  bars: {
    expand: "Expand",
    collapse: "Collapse",
  },

  panel: {
    expandLeft: "Expand devices panel",
    expandRight: "Expand details panel",
    collapseLeft: "Collapse devices panel",
    collapseRight: "Collapse details panel",
  },

  // ── Toast ──
  toast: {
    // 单括号插值: 见 i18n/index.ts 的 prefix/suffix 设置(不是 i18next 默认的 {{}})
    undo: "Undid",
    undoRedo: "Redid",
    ratingChange: "{prev}★ → {next}★",
    labelChange: "{prev} → {next}",
    selectionChange: "{n} selected",
  },

  // ── Phase 5 · XMP sidecar ──
  // Error codes mirror XmpError::code() in src-tauri/src/xmp.rs (whitelisted on the
  // front end, then used as the dynamic prefix `xmp.err.<code>`; unknown falls back).
  xmp: {
    askTitle: "Write ratings/labels into .xmp?",
    askBody: "A sidecar file with the same name will be created next to each photo (e.g. IMG_1234.xmp) so Lightroom / digiKam can read these decisions. Only .xmp files are added or updated — your photos are never modified or deleted.",
    askYes: "Write .xmp",
    askNo: "Local only",
    toastFailed: "Failed to write .xmp: {reason}",
    toastDowngraded: "This location is not writable; switched back to \"Local only\": {reason}",
    toastQueueOverflow: "Too many changes — only the latest {n} were synced",
    err: {
      writeProtect: "the card is write-protected (read-only)",
      readOnly: "the file or folder is marked read-only",
      permission: "permission denied",
      diskFull: "not enough disk space",
      busy: "the file is in use by another program",
      notXmp: "the existing .xmp is not recognizable XMP (left untouched)",
      unsupportedForm: "the .xmp uses an unsupported form (left untouched)",
      tooLarge: "the .xmp exceeds the 1 MB limit (left untouched)",
      encoding: "the .xmp is not UTF-8 encoded (left untouched)",
      pathTooLong: "the path is too long",
      unknown: "unknown error",
    },
  },

  // ── Phase 7 · Lightroom handoff ──
  // Error codes mirror LrcError::code() in src-tauri/src/lightroom.rs (whitelisted on the
  // front end, then used as the dynamic prefix `lrc.err.<code>`; unknown falls back).
  lrc: {
    send: "Import to LrC",
    sendTip: "Import the selected photos into the destination folder, then open Lightroom's Import dialog showing only this batch",
    sending: "Working…",
    importingPhotos: "Importing photos…",
    startingLightroom: "Starting Lightroom…",
    importingPhotosBody: "Copying and verifying {done}/{total} photo(s) into the destination folder. Please keep this window open.",
    startingLightroomBody: "The photos are in place; opening Lightroom's Import dialog now. This can take many seconds (longer with a big catalog). This box closes by itself once the window appears.",
    keepInBackground: "Continue in background",
    sent: "Imported {n} photo(s): {dir}",
    sentStaged: " (destination was not empty — placed in a subfolder; import from that path in LrC)",
    runningTitle: "Lightroom is already running",
    runningBody: "When Lightroom is already open it ignores the folder passed to it, and the Import dialog stays wherever it was last. Close Lightroom first, then click Retry. “Force close and continue” terminates the Lightroom process — unsaved adjustments will be lost.",
    runningRetry: "I closed it — retry",
    runningForce: "Force close and continue",
    toastFailed: "Failed to hand off to Lightroom: {reason}",
    err: {
      notFound: "Lightroom was not found — use “Re-detect” in Settings",
      noFolder: "no usable destination folder",
      alreadyRunning: "Lightroom is already running (close it first)",
      launchFailed: "failed to launch Lightroom",
      notSupported: "not supported on this platform",
      notImplemented: "that mode is not implemented",
      noNewPhotos: "all {n} selected photo(s) already exist at the destination with identical content — nothing new to import",
      unknown: "unknown error",
    },
  },

  // ── Phase 8 · Gallery service (phone access) ──
  // The panel lives inside the settings dialog (ServerPanel in settings-dialog.tsx):
  // switch / listening state / pairing code / shared folders / troubleshooting list.
  // Status shape: ServerStatus in src-tauri/src/server/mod.rs (camelCase).
  server: {
    title: "LAN remote browsing",
    switchDesc: "Remote service switch, off by default",
    on: "On",
    off: "Off",
    listening: "Listening on {url}",
    // Deliberately NOT rolling the switch back on a failed start (Task 8): "you asked for on,
    // it is not running" is a legal state — shown next to lastError, or the user just thinks
    // the click missed.
    wantedNotRunning: "Service is not running",
    // "Off" is not instantaneous: after stop() the port still has a *bounded* settling window
    // (worst measured 63ms) during which a handshake can complete. Never promise more than that.
    offNote: "Off",
    lastError: "Last error",
    tokenLabel: "Pairing code",
    tokenDesc: "Pair again after resetting",
    tokenEmpty: "Generated when the service starts",
    copy: "Copy",
    copied: "Copied",
    copyFailed: "Copy failed — copy manually",
    rotate: "Reset",
    rotateConfirm: "Confirm reset",
    sharesLabel: "Shared folders",
    sharesDesc: "Allowed folders",
    shareEmpty: "No shared folders — the phone has no access",
    pathPlaceholder: "Enter a folder path",
    browse: "Browse…",
    add: "Add",
    remove: "Remove",
    unavailable: "Cannot get service status: {reason}",
    actionFailed: "Action failed: {reason}",
    tipsTitle: "Cannot connect?",
    sleepTip: "Disable sleep (set it to “Never” in system power settings)",
    firewallTip: "Allow “Private networks” in Windows Firewall on first launch",
    // M4′ Task 2: after the LAN-only decision (2026-10-07) there is **no tunnel option any more**
    // — the old “or both joined the same EasyTier/Tailscale network” wording is gone by decision.
    appRunningTip: "Check: PC sleep, app running, service started, same LAN",

    // ── M4′ Task 2 · LAN addresses / firewall / Public-network guard (LAN-only) ──
    // Rendered by ServerPanel; the data comes from `get_server_network_hints`
    // (NetHints, camelCase). Same rule as the Chinese side: no entry point for
    // tunnels / port forwarding / public exposure — this design only warns.
    lanTitle: "LAN address",
    lanDesc: "Phone and PC must be on the same LAN",
    lanEmpty: "No LAN address detected",
    rescan: "Scan again",
    firewallHintTitle: "Firewall issue",
    firewallHint: "Windows Security → Firewall & network protection → Allow an app through firewall → ImageFilter → tick “Private”.",
    firewallHintGeneric: "Allow ImageFilter through Windows Firewall on the “Private” profile.",
    publicTitle: "Windows marks the current network as “Public”",
    publicWarn: "“Public” is an untrusted network — there is some risk",
    publicOffer: "Use a private network instead",
    publicConfirm: "I understand the risk — turn it on anyway",
    publicAck: "Risk accepted at {time}",
    publicAckNoTime: "Risk accepted for this run",
    publicIpTitle: "Wrong LAN address",
    publicIpWarn: "{list} may be exposed; risk acknowledged.",
    profileUnknown: "Unknown network; risk acknowledged.",

    // ── M4′ Task 1 · pairing QR code (generated on the desktop) ──
    // Data comes from `get_server_pairing_qr` (PairingQr, camelCase); null = no QR code this time.
    // The backend never falls back to a public address or 127.0.0.1 when there is no private
    // address, so the reason must be spelled out here: an empty LAN list is explained by
    // lanEmpty above, and only "address present, pairing code missing" lands on qrNoToken.
    // Fix 1: a QR code exists only while the service is actually listening (the backend
    // returns null otherwise). qrNotRunning explains that state and keeps the manual path —
    // a code for the configured port would point the phone at a port nobody is listening on.
    qrShow: "Connect with QR code",
    qrHide: "Hide QR code",
    qrScanHint: "Scan with ImageFilter on the phone",
    qrNoToken: "A pairing code and QR code are generated when the service starts.",
    qrNotRunning: "Service error — check the service status, or pair manually below.",
    // Scanning is an enhancement, not the only path (M4′ Task 1 Step 4, item 3: refusing the
    // camera permission must still allow pairing by hand).
    qrFallback: "Cannot use it? Try manual pairing.",

    // ── M4′ Task 3 · read-only mode ──
    // On = every write endpoint answers 403 forbiddenPath (the existing code, not a new one);
    // read endpoints keep working.
    readOnly: "Read-only mode",
    readOnlyDesc: "Browse only on the phone",

    // ── M4′ Task 3 · who is connecting ──
    // Discipline: only what the HTTP layer really knows. Never invent a device name or location.
  },

  // ── 2026-10-25 · mDNS pairing (desktop) ──
  // Pairing window / pending requests / paired devices. Commands and events live in
  // src/lib/pairing.ts (frozen contract §0.3). Discipline: in v1 "Forget" only drops the
  // record — it does not revoke the pairing code; forgetNote must say so plainly, or users
  // will think removing a device has kicked it offline (only Rotate does that).
  pairing: {
    sectionTitle: "Pair a new device",
    windowHint: "Click this button before pairing to receive pairing requests",
    openWindow: "Pair a new device",
    closeWindow: "Close pairing window",
    windowOpen: "Pairing window is open",
    windowClosed: "Pairing window is closed",
    serviceOff: "Turn on the remote service first",
    remaining: "{time} left",
    pendingTitle: "Pending requests",
    pendingDesc: "Requests expire in 60 seconds",
    morePending: "{n} more pending request(s)",
    pairedDevices: "Paired devices",
    noDevices: "No paired devices",
    forget: "Forget",
    lastSeen: "last seen {time}",
    requestTitle: "Pairing request",
    requestBody: "This device wants to connect. Confirm it is yours and on the same LAN.",
    deviceOn: "Device: {name} ({ip})",
    requestedAt: "Requested at {time}",
    expiresIn: "This request expires in {seconds}s",
    expired: "This request has expired",
    allow: "Allow",
    deny: "Deny",
    advanced: "Advanced / manual pairing",
    actionFailed: "Action failed: {reason}",
    unavailable: "Unknown pairing status: {reason}",
  },

};
