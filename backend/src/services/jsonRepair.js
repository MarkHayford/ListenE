// 从 LLM 文本里稳健地抽取 + 解析 JSON。模型偶尔会输出代码围栏、前后多余文字、尾随逗号、
// 数组/对象间漏逗号等，这里做一组保守修复后再 JSON.parse。全为纯函数，零外部依赖，便于单测。
// （历史上这套逻辑内联在 mimoAgentMedia.js，现抽成独立模块——大文件拆分的第一刀；
//  mimoAgentMedia 仍 re-export extractJsonFromContent 以保持既有调用方不变。）

function extractJsonFromContent(content) {
  let text = String(content || "").trim();
  if (text.startsWith("```")) {
    text = text.replace(/^```json\s*/i, "").replace(/^```\s*/i, "").replace(/```$/i, "").trim();
  }
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start < 0 || end <= start) throw new Error("AI did not return valid JSON.");
  const jsonText = text.slice(start, end + 1);
  return parseJsonWithLooseRepairs(jsonText);
}

function parseJsonWithLooseRepairs(text) {
  const original = String(text || "");
  try {
    return JSON.parse(original);
  } catch (_) {
    // Continue with conservative repairs below.
  }

  let repaired = repairLooseJsonText(original);
  for (let attempt = 0; attempt < 6; attempt += 1) {
    try {
      return JSON.parse(repaired);
    } catch (error) {
      const next = repairJsonParseError(repaired, error);
      if (!next || next === repaired) throw error;
      repaired = repairLooseJsonText(next);
    }
  }

  return JSON.parse(repaired);
}

function repairLooseJsonText(text) {
  const cleaned = String(text || "")
    .replace(/,\s*([}\]])/g, "$1")
    .replace(/[\u0000-\u001F]+/g, " ");
  return insertMissingCommasBetweenJsonValues(cleaned);
}

function insertMissingCommasBetweenJsonValues(text) {
  let output = "";
  let whitespace = "";
  let inString = false;
  let escaped = false;
  let previousSignificant = "";

  for (const char of String(text || "")) {
    if (inString) {
      output += char;
      if (escaped) {
        escaped = false;
      } else if (char === "\\") {
        escaped = true;
      } else if (char === "\"") {
        inString = false;
        previousSignificant = char;
      }
      continue;
    }

    if (/\s/.test(char)) {
      whitespace += char;
      continue;
    }

    if (
      shouldInsertMissingJsonComma(previousSignificant, char) &&
      (whitespace || shouldInsertAdjacentMissingJsonComma(previousSignificant, char))
    ) {
      output += ",";
    }
    output += whitespace;
    whitespace = "";
    output += char;

    if (char === "\"") {
      inString = true;
      escaped = false;
    } else {
      previousSignificant = char;
    }
  }

  return output + whitespace;
}

function shouldInsertMissingJsonComma(previous, next) {
  if (!previous || !next) return false;
  return isJsonValueEnd(previous) && isJsonValueStart(next);
}

function shouldInsertAdjacentMissingJsonComma(previous, next) {
  if (previous === "}" || previous === "]") return isJsonValueStart(next);
  if (previous === "\"") return next === "{" ||
    next === "[" ||
    next === "\"" ||
    next === "-" ||
    /[0-9tfn]/.test(next);
  return false;
}

function repairJsonParseError(text, error) {
  const message = String(error?.message || "");
  if (!message.includes("Expected ',' or ']' after array element")) return "";
  const match = message.match(/position\s+(\d+)/);
  if (!match) return "";
  return insertMissingCommaNearPosition(text, Number(match[1]));
}

function insertMissingCommaNearPosition(text, position) {
  const value = String(text || "");
  if (!Number.isFinite(position)) return "";
  const from = Math.max(1, position - 12);
  const to = Math.min(value.length, position + 12);
  for (let index = from; index <= to; index += 1) {
    const previousIndex = previousNonWhitespaceIndex(value, index - 1);
    const nextIndex = nextNonWhitespaceIndex(value, index);
    if (previousIndex < 0 || nextIndex < 0) continue;
    if (isInsideJsonString(value, previousIndex) || isInsideJsonString(value, nextIndex)) continue;
    if (value.slice(previousIndex + 1, nextIndex).includes(",")) continue;
    if (isJsonValueEnd(value[previousIndex]) && isJsonValueStart(value[nextIndex])) {
      return `${value.slice(0, previousIndex + 1)},${value.slice(previousIndex + 1)}`;
    }
  }
  return "";
}

function previousNonWhitespaceIndex(text, start) {
  for (let index = Math.min(start, text.length - 1); index >= 0; index -= 1) {
    if (!/\s/.test(text[index])) return index;
  }
  return -1;
}

function isInsideJsonString(text, position) {
  let inString = false;
  let escaped = false;
  for (let index = 0; index <= position && index < text.length; index += 1) {
    const char = text[index];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (char === "\\") {
        escaped = true;
      } else if (char === "\"") {
        inString = false;
      }
    } else if (char === "\"") {
      inString = true;
      escaped = false;
    }
  }
  return inString;
}

function nextNonWhitespaceIndex(text, start) {
  for (let index = Math.max(start, 0); index < text.length; index += 1) {
    if (!/\s/.test(text[index])) return index;
  }
  return -1;
}

function isJsonValueEnd(char) {
  return char === "}" ||
    char === "]" ||
    char === "\"" ||
    /[0-9eElL]/.test(char);
}

function isJsonValueStart(char) {
  return char === "{" ||
    char === "[" ||
    char === "\"" ||
    char === "-" ||
    /[0-9tfn]/.test(char);
}

module.exports = {
  extractJsonFromContent,
  parseJsonWithLooseRepairs,
  __test: {
    repairLooseJsonText,
    insertMissingCommasBetweenJsonValues,
    isInsideJsonString,
    isJsonValueEnd,
    isJsonValueStart
  }
};
