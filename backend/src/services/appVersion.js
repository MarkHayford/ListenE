"use strict";

// 客户端强制更新检测：返回最新版本信息（环境变量驱动，发版时改 env 即可）。公开端点，登录前也能查。
// 部署方通过 APP_LATEST_VERSION_CODE / APP_DOWNLOAD_URL 等环境变量自行配置；未配置时不触发强更、不下发下载地址。
function getAppVersion() {
  const codeEnv = Number(process.env.APP_LATEST_VERSION_CODE);
  const latestVersionCode = Number.isFinite(codeEnv) ? Math.max(0, Math.trunc(codeEnv)) : 0;
  const latestVersionName = String(process.env.APP_LATEST_VERSION_NAME || "").trim().slice(0, 40);
  const downloadUrl = String(process.env.APP_DOWNLOAD_URL || "").trim().slice(0, 500);
  const forceUpdate = String(process.env.APP_UPDATE_FORCE == null ? "1" : process.env.APP_UPDATE_FORCE) !== "0";
  const releaseNotes = String(process.env.APP_RELEASE_NOTES || "").trim().slice(0, 1000);
  return { latestVersionCode, latestVersionName, downloadUrl, forceUpdate, releaseNotes };
}

module.exports = { getAppVersion };
