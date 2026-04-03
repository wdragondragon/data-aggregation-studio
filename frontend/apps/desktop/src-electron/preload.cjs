const { contextBridge } = require("electron");

contextBridge.exposeInMainWorld("studioDesktop", {
  mode: "desktop",
});
