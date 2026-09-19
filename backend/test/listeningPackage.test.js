"use strict";
// 听力素材「真二进制 zip」打包测试：生成真正的 .zip，含 transcript/questions/audio 三项且音频字节一致。
// 仅本机文件 IO，不调模型/网络。
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const AdmZip = require("adm-zip");
const { settings } = require("../src/config");
const { buildListeningPackageZip, __test } = require("../src/services/listeningPackage");

(async () => {
  // 题目文本格式化
  const qtext = __test.formatPackageQuestionsText(
    [{ questionText: "Where?", options: ["Airport", "Hotel"], correctAnswer: 0, explanation: "ctx" }],
    "T"
  );
  assert.ok(/1\. Where\?/.test(qtext) && /A\. Airport/.test(qtext) && /Correct answer: A/.test(qtext), "题目文本应含题干/选项/答案");

  // audioFileNameFromUrl
  assert.strictEqual(__test.audioFileNameFromUrl("http://x/api/v1/audio/abc.wav?t=1"), "abc.wav");
  assert.strictEqual(__test.audioFileNameFromUrl("/api/v1/audio/no_ext"), "");

  // SSRF 防护：只放行 publicBaseUrl 同源；内网/元数据/异协议一律拒绝（防未授权 SSRF）。
  {
    const base = new URL(settings.publicBaseUrl); // 测试环境为 http://127.0.0.1:8001
    assert.strictEqual(__test.isAllowedAudioFetchUrl(`${base.protocol}//${base.host}/api/v1/audio/x.wav`), true, "同源应放行");
    assert.strictEqual(__test.isAllowedAudioFetchUrl("http://169.254.169.254/latest/meta-data/"), false, "云元数据应拒绝");
    assert.strictEqual(__test.isAllowedAudioFetchUrl("http://127.0.0.1:6379/"), false, "同主机异端口(内网服务)应拒绝");
    assert.strictEqual(__test.isAllowedAudioFetchUrl("http://evil.example.com/api/v1/audio/x.wav"), false, "他域应拒绝");
    assert.strictEqual(__test.isAllowedAudioFetchUrl("file:///etc/passwd"), false, "非 http(s) 协议应拒绝");
    assert.strictEqual(__test.isAllowedAudioFetchUrl("not a url"), false, "非法 URL 应拒绝");
    // 额外 CDN 主机名可经 PACKAGE_AUDIO_FETCH_HOSTS 显式放行
    process.env.PACKAGE_AUDIO_FETCH_HOSTS = "cdn.example.com";
    assert.strictEqual(__test.isAllowedAudioFetchUrl("https://cdn.example.com/a/x.mp3"), true, "白名单 CDN 应放行");
    assert.strictEqual(__test.isAllowedAudioFetchUrl("https://other.example.com/a/x.mp3"), false, "白名单外仍拒绝");
    delete process.env.PACKAGE_AUDIO_FETCH_HOSTS;
    // 端到端：内网 audioUrl 不会被 fetch，resolveAudioBytes 返回 null（退回“音频不可达”）
    assert.strictEqual(await __test.resolveAudioBytes("http://169.254.169.254/latest/meta-data/"), null, "SSRF 目标不应被抓取");
  }

  // 准备一个本机音频文件
  fs.mkdirSync(settings.audioDir, { recursive: true });
  const audioName = `__pkgtest_${Date.now()}.wav`;
  const audioPath = path.join(settings.audioDir, audioName);
  const audioBytes = Buffer.from("RIFF$$$$FAKE-WAV-BYTES-1234567890", "utf8");
  fs.writeFileSync(audioPath, audioBytes);

  const record = {
    title: "Airport Check-in",
    script: "A: May I see your passport?\nB: Sure, here you go.",
    questions: [{ questionText: "Where does it take place?", options: ["Airport", "Hotel", "Bank", "School"], correctAnswer: 0 }],
    audioUrl: `${settings.publicBaseUrl}/audio/${audioName}`
  };

  const result = await buildListeningPackageZip(record);
  assert.ok(result && result.storedName && result.path.endsWith(".zip"), "应生成 zip 包");
  assert.strictEqual(result.audioIncluded, true, "音频应被嵌入");

  const zipPath = path.join(settings.packagesDir, result.storedName);
  assert.ok(fs.existsSync(zipPath), "zip 文件应落盘");
  const zip = new AdmZip(zipPath);
  const names = zip.getEntries().map((e) => e.entryName);
  assert.ok(names.includes("transcript.txt"), "应含 transcript.txt");
  assert.ok(names.includes("questions.txt"), "应含 questions.txt");
  assert.ok(names.includes("audio.wav"), "应含 audio.wav");
  assert.ok(names.includes("README.txt"), "应含 README.txt");
  assert.strictEqual(zip.readAsText("transcript.txt").trim(), record.script.trim(), "transcript 内容应一致");
  assert.ok(/Where does it take place/.test(zip.readAsText("questions.txt")), "questions 应含题干");
  assert.deepStrictEqual(zip.readFile("audio.wav"), audioBytes, "音频二进制字节应与原文件一致");

  // 音频不可达（相对 URL，无网络）：不嵌音频，留 audio_url.txt
  const record2 = { title: "No Audio", script: "x", questions: [], audioUrl: "/api/v1/audio/__pkgtest_missing_zzz.wav" };
  const r2 = await buildListeningPackageZip(record2);
  assert.strictEqual(r2.audioIncluded, false, "音频不可达时不应嵌入");
  const zip2 = new AdmZip(path.join(settings.packagesDir, r2.storedName));
  const names2 = zip2.getEntries().map((e) => e.entryName);
  assert.ok(names2.includes("audio_url.txt"), "未嵌音频时应留 audio_url.txt");

  // 空素材应报错
  await assert.rejects(() => buildListeningPackageZip({ title: "empty" }), /no listening material/i, "空素材应报错");

  // 清理
  try { fs.unlinkSync(audioPath); } catch (_) {}
  try { fs.unlinkSync(zipPath); } catch (_) {}
  try { fs.unlinkSync(path.join(settings.packagesDir, r2.storedName)); } catch (_) {}

  console.log("listeningPackage.test.js passed");
})().catch((e) => { console.error(e); process.exit(1); });
