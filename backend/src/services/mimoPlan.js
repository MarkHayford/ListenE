const { callMimoText } = require("./mimoText");

const PRACTICE_TYPES = [
  "对话听力",
  "文章听力",
  "单词/句子"
];

function formatDate(d) {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

function nextDays(count = 14) {
  const out = [];
  const base = new Date();
  base.setHours(0, 0, 0, 0);
  for (let i = 0; i < count; i++) {
    const d = new Date(base);
    d.setDate(base.getDate() + i);
    out.push(formatDate(d));
  }
  return out;
}

/**
 * 根据用户学习需求生成未来 N 天的计划任务（JSON）
 * @param {string} userGoal
 * @param {number} planDays 3–60
 */
async function generateStudyPlan(userGoal, planDays = 14) {
  const dayCount = Math.min(60, Math.max(3, parseInt(planDays, 10) || 14));
  const dates = nextDays(dayCount);
  const prompt = `你是英语学习规划师。根据用户的学习需求，为未来${dayCount}天制定可执行的学习计划。

用户需求：
${String(userGoal || "").trim()}

可选练习类型（practiceType 必须从中选一）：${PRACTICE_TYPES.join("、")}

日期范围（必须使用下列日期，可跳过某天但不新增范围外日期）：
${dates.join(", ")}

请只输出 JSON，不要 markdown：
{
  "summary": "一段简短的中文规划说明",
  "days": [
    {
      "date": "YYYY-MM-DD",
      "tasks": [
        {
          "title": "任务标题",
          "practiceType": "对话听力",
          "hour": 9,
          "minute": 0
        }
      ]
    }
  ]
}

要求：
- 每天至少0条、最多3条任务；合理分配强度
- hour 0-23, minute 0-59
- 任务 title 具体可执行`;

  const parsed = await callMimoText([
    { role: "system", content: "你只输出合法 JSON，不要 markdown 代码块。" },
    { role: "user", content: prompt }
  ], { temperature: 0.5, maxTokens: 4000 });
  if (!parsed || typeof parsed !== "object") throw new Error("AI 未返回有效计划");
  const days = Array.isArray(parsed.days) ? parsed.days : [];
  const normalized = days.map((d) => ({
    date: String(d.date || "").trim(),
    tasks: (Array.isArray(d.tasks) ? d.tasks : []).slice(0, 3).map((t, i) => ({
      title: String(t.title || "英语练习").trim().slice(0, 80),
      practiceType: PRACTICE_TYPES.includes(t.practiceType) ? t.practiceType : "对话听力",
      hour: Math.min(23, Math.max(0, parseInt(t.hour, 10) || 9)),
      minute: Math.min(59, Math.max(0, parseInt(t.minute, 10) || 0))
    }))
  })).filter((d) => dates.includes(d.date));
  return {
    summary: String(parsed.summary || "已为你生成学习计划").trim(),
    days: normalized
  };
}

module.exports = { generateStudyPlan, PRACTICE_TYPES };
