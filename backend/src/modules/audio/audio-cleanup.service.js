const fs = require("fs");
const path = require("path");
const { settings } = require("../../config");

function cleanupExpiredAudioFiles(maxAgeMs = 15 * 60 * 1000) {
  try {
    const files = fs.readdirSync(settings.audioDir);
    const now = Date.now();
    files.forEach((file) => {
      const fullPath = path.join(settings.audioDir, file);
      if (now - fs.statSync(fullPath).mtimeMs > maxAgeMs) {
        fs.unlinkSync(fullPath);
      }
    });
  } catch (error) {}
}

function startAudioCleanup() {
  return setInterval(() => cleanupExpiredAudioFiles(), 10 * 60 * 1000);
}

module.exports = { cleanupExpiredAudioFiles, startAudioCleanup };
