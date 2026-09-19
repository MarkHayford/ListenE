const { settings } = require("../config");
const { generateAudioFile } = require("./mimoTts");

function normalizeVoiceGender(raw) {
  const v = String(raw || "").toLowerCase().trim();
  if (v === "male" || v === "男" || v === "男声" || v === "m") return "male";
  return "female";
}

function buildFallbackVoiceProfile(name, gender, index) {
  const word = gender === "male" ? "Male" : "Female";
  const role = String(name || `Speaker ${index + 1}`).trim();
  return `${word} adult, clear natural English voice, ${role}.`;
}

function inferTtsSegmentsFromScript(script) {
  const lines = String(script || "")
    .replace(/\r\n/g, "\n")
    .split("\n")
    .map((part) => part.trim())
    .filter(Boolean);
  const speakerMap = new Map();
  const segments = [];
  let speakerIndex = 0;

  for (const line of lines) {
    const match = line.match(/^([^:：\n]+)[:：]\s*(.+)$/);
    if (!match) continue;
    const speakerName = match[1].trim();
    const text = match[2].trim();
    if (!text) continue;

    let speaker = speakerMap.get(speakerName);
    if (!speaker) {
      speakerIndex += 1;
      const gender = speakerIndex % 2 === 1 ? "female" : "male";
      speaker = {
        speakerId: `P${speakerIndex}`,
        speakerName,
        speakerGender: gender,
        voiceProfile: buildFallbackVoiceProfile(speakerName, gender, speakerIndex - 1)
      };
      speakerMap.set(speakerName, speaker);
    }

    segments.push({
      speakerId: speaker.speakerId,
      speakerName: speaker.speakerName,
      speakerGender: speaker.speakerGender,
      voiceProfile: speaker.voiceProfile,
      text
    });
  }

  return segments;
}

async function synthesizeListeningAudio(body = {}) {
  const script = String(body.script || "").trim();
  if (!script) throw new Error("script is required");

  const contentType = String(body.contentType || "dialogue").toLowerCase();
  let ttsSegments = Array.isArray(body.ttsSegments) ? body.ttsSegments : [];
  if (contentType === "dialogue" && ttsSegments.length === 0) {
    ttsSegments = inferTtsSegmentsFromScript(script);
  }

  const audio = await generateAudioFile(
    script,
    body.ttsPrompt || "",
    ttsSegments,
    {
      contentType,
      voiceGender: body.voiceGender,
      speechRate: body.speechRate,
      voiceProfile: body.voiceProfile,
      pitch: body.pitch,
      accent: body.accent,
      tone: body.tone
    }
  );

  return {
    audioUrl: `${settings.publicBaseUrl}/audio/${audio.filename}`,
    audioSegments: audio.audioSegments || []
  };
}

module.exports = {
  synthesizeListeningAudio,
  inferTtsSegmentsFromScript,
  normalizeVoiceGender
};
