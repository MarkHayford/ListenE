// 场景/对话练习卡片相关：场景题库、出题校验(证据/专有名词/不实信息)、说话人角色推断、
// 干扰项与计数小工具。一组内聚的纯逻辑，集中此处便于维护与单测。
// 历史上内联在 mimoText.js（1 万行），现抽成独立模块——大文件拆分的又一刀(B 风格: 场景题)。

const { compactAgentCardText } = require("./agentCardText");
const { normalizeAgentAnswerLookupText } = require("./textNormalize");

function scenarioQuestionReferencesUnsupportedDetail(question = {}, scenario = {}, scope = {}) {
  const scenarioEvidence = scenarioQuestionEvidenceText(scenario, scope);
  const questionText = question?.questionText || "";
  const optionsText = Array.isArray(question?.options) ? question.options.join(" ") : "";
  const correctText = scenarioQuestionCorrectOptionText(question);
  const combined = `${questionText} ${optionsText}`;
  const properNouns = Array.from(new Set((combined.match(/\b[A-Z][a-z]{2,}\b/g) || [])
    .filter((item) => !scenarioQuestionAllowedProperNouns().has(item.toLowerCase()))));
  if (properNouns.some((name) => !scenarioQuestionEvidenceContains(scenarioEvidence, name))) return true;
  const unsupportedTerms = scenarioQuestionUnsupportedFactTerms(`${questionText} ${correctText}`);
  return unsupportedTerms.some((term) => !scenarioQuestionEvidenceContains(scenarioEvidence, term));
}

function scenarioQuestionEvidenceText(scenario = {}, scope = {}) {
  const values = [
    scope?.messageText || "",
    scenario.title || "",
    scenario.text || "",
    ...(Array.isArray(scenario.items) ? scenario.items : []),
    ...(Array.isArray(scenario.pairs)
      ? scenario.pairs.flatMap((pair) => [pair?.left, pair?.right, pair?.hint])
      : [])
  ];
  return normalizeAgentAnswerLookupText(values.filter(Boolean).join(" "));
}

function scenarioQuestionEvidenceContains(evidence = "", term = "") {
  const normalizedTerm = normalizeAgentAnswerLookupText(term);
  if (!normalizedTerm) return true;
  return evidence.includes(normalizedTerm);
}

function scenarioQuestionAllowedProperNouns() {
  return new Set([
    "a1", "a2", "b1", "b2", "c1", "c2",
    "speaker", "dialogue", "scenario", "question", "questions", "english",
    "pharmacy", "hotel", "airport", "restaurant", "school", "office"
  ]);
}

function scenarioQuestionUnsupportedFactTerms(value = "") {
  const text = String(value || "").toLowerCase();
  const terms = [];
  [
    "headache",
    "sore throat",
    "stomachache",
    "broken arm",
    "wallet",
    "passport",
    "ticket",
    "luggage",
    "breakfast",
    "checkout",
    "pool",
    "gym"
  ].forEach((term) => {
    if (text.includes(term)) terms.push(term);
  });
  return terms;
}

function scenarioQuestionCorrectOptionText(question = {}) {
  const options = Array.isArray(question.options) ? question.options : [];
  const index = Number.isInteger(Number(question.correctAnswer)) ? Number(question.correctAnswer) : -1;
  if (index >= 0 && index < options.length) return options[index] || "";
  return "";
}

function scenarioQuestionSetTitle(topic = "generic") {
  if (topic === "pharmacy") return "Pharmacy Scenario Questions";
  if (topic === "hotel_check_in") return "Hotel Check-in Questions";
  if (topic === "airport") return "Airport Scenario Questions";
  if (topic === "restaurant") return "Restaurant Scenario Questions";
  return "Scenario Questions";
}

function scenarioQuestionBank(topic = "generic", roles = [], participantCount = 2) {
  const primaryRole = roles[0] || "Speaker A";
  const secondRole = roles[1] || "Speaker B";
  const thirdRole = roles[2] || "Speaker C";
  const participantAnswer = `${englishCountWord(participantCount)} people`;
  const participantDistractors = scenarioParticipantDistractors(participantCount);
  if (topic === "pharmacy") {
    return [
      {
        questionText: "Where does the dialogue take place?",
        answer: "At a pharmacy",
        distractors: ["At a train station", "At a hotel", "At a classroom", "At an airport"]
      },
      {
        questionText: "How many people are in the dialogue?",
        answer: participantAnswer,
        distractors: participantDistractors
      },
      {
        questionText: "Who starts with a clear request?",
        answer: primaryRole,
        distractors: [secondRole, thirdRole, "The teacher", "The driver"]
      },
      {
        questionText: "What should one speaker suggest?",
        answer: "A solution",
        distractors: ["A boarding time", "A hotel key card", "A homework answer", "A restaurant bill"]
      }
    ];
  }
  if (topic === "hotel_check_in") {
    return [
      {
        questionText: "What should the guest give first at check-in?",
        answer: "A reservation name",
        distractors: ["A coffee order", "A city map", "A shopping list", "A postcard"]
      },
      {
        questionText: "What may the receptionist ask to see?",
        answer: "An ID",
        distractors: ["A postcard", "A city map", "A pencil", "A shopping list"]
      },
      {
        questionText: "What can the staff confirm at the end?",
        answer: "The key card and room number",
        distractors: ["The breakfast menu", "The swimming pool rules", "The airport gate", "The gym schedule"]
      },
      {
        questionText: "How many people are in the dialogue?",
        answer: participantAnswer,
        distractors: participantDistractors
      }
    ];
  }
  if (topic === "airport") {
    return [
      {
        questionText: "Where does the dialogue take place?",
        answer: "At an airport",
        distractors: ["At a pharmacy", "At a hotel", "At a restaurant", "At a classroom"]
      },
      {
        questionText: "What can a passenger give first?",
        answer: "A booking reference",
        distractors: ["A room key", "A medicine label", "A homework notebook", "A dinner menu"]
      },
      {
        questionText: "What detail can the staff confirm?",
        answer: "The gate or boarding time",
        distractors: ["The hotel deposit", "The restaurant bill", "The classroom seat", "The pharmacy price"]
      },
      {
        questionText: "How many people are in the dialogue?",
        answer: participantAnswer,
        distractors: participantDistractors
      }
    ];
  }
  if (topic === "restaurant") {
    return [
      {
        questionText: "Where does the dialogue take place?",
        answer: "At a restaurant",
        distractors: ["At an airport", "At a pharmacy", "At a hotel", "At a classroom"]
      },
      {
        questionText: "Who can ask about the menu?",
        answer: primaryRole,
        distractors: [secondRole, thirdRole, "The pilot", "The doctor"]
      },
      {
        questionText: "What can the server bring at the end?",
        answer: "The bill",
        distractors: ["A boarding pass", "A key card", "A prescription", "A textbook"]
      },
      {
        questionText: "How many people are in the dialogue?",
        answer: participantAnswer,
        distractors: participantDistractors
      }
    ];
  }
  return [
    {
      questionText: "How many people are in the dialogue?",
      answer: participantAnswer,
      distractors: participantDistractors
    },
    {
      questionText: "Who starts the conversation?",
      answer: primaryRole,
      distractors: [secondRole, thirdRole, "The teacher", "The driver"]
    },
    {
      questionText: "What should the next speaker ask?",
      answer: "A natural follow-up question",
      distractors: ["A song lyric", "A random number", "A weather report", "A grammar rule"]
    },
    {
      questionText: "How should the dialogue end?",
      answer: "Politely",
      distractors: ["Angrily", "Silently", "With a long essay", "With a test score"]
    }
  ];
}

function englishCountWord(count = 0) {
  const words = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight"];
  return words[count] || String(count || 0);
}

function scenarioParticipantDistractors(count = 0) {
  return [1, 2, 3, 4, 5, 6]
    .filter((item) => item !== Number(count))
    .map((item) => `${englishCountWord(item)} ${item === 1 ? "person" : "people"}`)
    .slice(0, 5);
}

function scenarioSpeakerRolesFromItems(items = []) {
  return new Set((Array.isArray(items) ? items : [])
    .map(inferScenarioSpeakerRoleFromItem)
    .filter(Boolean));
}

function inferScenarioSpeakerRoleFromItem(value = "") {
  const text = compactAgentCardText(value, 220).replace(/^\s*(?:[-*•]|\d+[\).、])\s+/, "");
  if (!text || !/:/.test(text)) return "";
  const [rawLabel, ...rest] = text.split(":");
  const label = compactAgentCardText(rawLabel, 48);
  const detail = rest.join(":").trim();
  if (!label || isNonSpeakerScenarioLabel(label)) return "";
  if (/^speaker\s*[0-9a-z]?$/i.test(label)) {
    return compactAgentCardText(
      detail
        .replace(/\s+[-–—]\s+[\s\S]*$/, "")
        .replace(/\s+\([^)]*\)\s*$/, ""),
      48
    );
  }
  return label;
}

function isNonSpeakerScenarioLabel(value = "") {
  return /^(?:location|place|setting|scene|situation|context|topic|level|difficulty|地点|场景|情景|背景|主题|难度)$/i.test(compactAgentCardText(value, 48));
}

module.exports = {
  scenarioQuestionReferencesUnsupportedDetail,
  scenarioQuestionEvidenceText,
  scenarioQuestionEvidenceContains,
  scenarioQuestionAllowedProperNouns,
  scenarioQuestionUnsupportedFactTerms,
  scenarioQuestionCorrectOptionText,
  scenarioQuestionSetTitle,
  scenarioQuestionBank,
  englishCountWord,
  scenarioParticipantDistractors,
  scenarioSpeakerRolesFromItems,
  inferScenarioSpeakerRoleFromItem,
  isNonSpeakerScenarioLabel,
};
