// 阅读卡（Reading）：阅读理解请求识别、必需范围与构建、段落/题目推断与清洗
// 原内联于 mimoText.js，逐字搬出为独立模块；依赖闭合、无循环依赖。

const { escapeRegExp, sanitizeAgentCardText } = require("./agentCardText");
const { inferFallbackReadingTopic } = require("./cardTitles");
const { inferAgentRequestedQuestionCount, inferRequestedAgentCardItemCounts } = require("./explicitConstraints");
const { isGrammarOrMistakePatternTopicRequest } = require("./intent");
const { isOrderingCardRequest, isParagraphOrderingReadingRequest } = require("./ordering");
const { stripAgentQuestionOptionLabel } = require("./textNormalize");

function isReadingPassageQuizRequest(text = "") {
  const value = String(text || "").toLowerCase();
  if (isOrderingCardRequest(value)) return false;
  if (isGrammarOrMistakePatternTopicRequest(value)) return false;
  const readingHit = /reading|阅读|仔细阅读|信息匹配/.test(value);
  const questionHit = /comprehension|理解|quiz|questions?|mcq|题|题目|配\s*[0-9一二两俩三四五六七八九十]+题|匹配/.test(value);
  const passageHit = /passage|\barticle\b|\btext\b|短文|文章|篇章|一篇|[0-9一二两俩三四五六七八九十]\s*篇|段落|材料/.test(value);
  return readingHit && (questionHit || /仔细阅读|信息匹配/.test(value)) && passageHit;
}

function isSingleReadingChoiceRequest(text = "") {
  const value = String(text || "").toLowerCase();
  const readingSkill = /main\s+idea|gist|central|主旨|中心|infer|inference|imply|suggest|推断|暗示/.test(value);
  const singleCount = /(?:one|1)\s+(?:question|choice)|(?:一|1)\s*(?:道|个)?\s*(?:题|选择题)|(?:题|选择题).{0,12}(?:one|1|一)(?:\s|道|个|[，,。；;]|$)|(?:来|出|给|做)\s*(?:one|1|一)\s*(?:道|个)?(?:\s|[，,。；;]|$)/i.test(value);
  const passageHit = /passage|\barticle\b|\btext\b|short\s+paragraph|短文|文章|原文|小段|一小段|段落/.test(value);
  return readingSkill && singleCount && passageHit;
}

function enforceReadingPassageCurrentRequestScope(components = [], scope = {}) {
  if (!scope?.readingPassageQuiz) return components;
  const next = components.filter((component) =>
    ["header", "summary", "question_set"].includes(component?.type)
  );
  const topic = inferFallbackReadingTopic(scope.messageText || "");
  const forceFallbackReadingContent = topic === "school_plastic_waste";
  const requestedCount = (scope.requestedCounts && scope.requestedCounts.question_set) ||
    inferAgentRequestedQuestionCount(scope.messageText || "") ||
    3;
  const summaryIndex = next.findIndex((component) => component?.type === "summary");
  const summary = summaryIndex >= 0 ? next[summaryIndex] : null;
  if (
    summaryIndex < 0 ||
    forceFallbackReadingContent ||
    !isUsableReadingPassageText(summary?.text || "", scope.messageText || "")
  ) {
    const fallback = buildFallbackReadingPassageComponent(scope.messageText || "");
    if (summaryIndex >= 0) next[summaryIndex] = { ...summary, ...fallback };
    else next.splice(Math.min(1, next.length), 0, fallback);
  }
  const questionSetIndex = next.findIndex((component) => component?.type === "question_set");
  if (questionSetIndex >= 0) {
    const questionSet = next[questionSetIndex];
    const questions = Array.isArray(questionSet.questions) ? questionSet.questions : [];
    const wrongQuestionCount = questions.length !== requestedCount;
    const optionCount = inferAgentRequestedOptionCount(scope.messageText || "") || 4;
    const wrongOptionCount = questions.some((question) => !Array.isArray(question?.options) || question.options.length !== optionCount);
    const topicLeak = readingComponentMentionsExcludedPriorTopic(questionSet, scope.messageText || "");
    const wrongReadingQuestionType = readingQuestionSetDoesNotMatchRequestedType(questionSet, scope.messageText || "");
    if (forceFallbackReadingContent || wrongQuestionCount || wrongOptionCount || topicLeak || wrongReadingQuestionType) {
      next[questionSetIndex] = buildFallbackReadingQuestionSetComponent(scope.messageText || "");
    } else {
      next[questionSetIndex] = { ...questionSet, source: "" };
    }
  } else {
    next.push(buildFallbackReadingQuestionSetComponent(scope.messageText || ""));
  }
  return next;
}

function readingQuestionSetDoesNotMatchRequestedType(questionSet = {}, message = "") {
  const questions = Array.isArray(questionSet.questions) ? questionSet.questions : [];
  if (!questions.length) return true;
  const text = String(message || "").toLowerCase();
  if (isParagraphOrderingReadingRequest(text)) {
    return questions.some((question) => !/order|sequence|顺序|排序/i.test(question?.questionText || ""));
  }
  if (/infer|inference|imply|suggest|推断|暗示/.test(text)) {
    return questions.some((question) => !/infer|imply|suggest|推断|暗示/i.test(question?.questionText || ""));
  }
  if (/main\s+idea|gist|central|主旨|中心/.test(text)) {
    return questions.some((question) => !/main\s+idea|gist|central|主旨|中心/i.test(question?.questionText || ""));
  }
  return false;
}

function isUsableReadingPassageText(text = "", request = "") {
  const passage = sanitizeAgentCardText(text);
  if (passage.length < 120) return false;
  if (/\b(?:swipe|switch|instructions?|read each passage|answer the questions below)\b|滑动|切换|说明/.test(passage.toLowerCase())) return false;
  const requestedTopic = inferFallbackReadingTopic(request);
  if (requestedTopic === "lost_wallet" && !/wallet/.test(passage.toLowerCase())) return false;
  if (requestedTopic === "lost_backpack" && !/backpack/.test(passage.toLowerCase())) return false;
  if (requestedTopic === "online_shopping" && !/shopping|online/.test(passage.toLowerCase())) return false;
  return !readingTextMentionsExcludedPriorTopic(passage, request);
}

function readingComponentMentionsExcludedPriorTopic(component = {}, request = "") {
  const values = [
    component.title,
    component.text,
    component.source,
    ...(Array.isArray(component.questions)
      ? component.questions.flatMap((question) => [
          question?.questionText,
          ...(Array.isArray(question?.options) ? question.options : [])
        ])
      : [])
  ];
  return readingTextMentionsExcludedPriorTopic(values.filter(Boolean).join(" "), request);
}

function readingTextMentionsExcludedPriorTopic(text = "", request = "") {
  const lower = String(text || "").toLowerCase();
  const requestLower = String(request || "").toLowerCase();
  if (/lost\s+wallet|钱包/.test(requestLower) && /backpack|背包/.test(lower)) return true;
  if (/lost\s+backpack|背包/.test(requestLower) && /wallet|钱包/.test(lower)) return true;
  return false;
}

function readingCardHeadingNeedsCurrentRequestRepair(cardSpec = {}, scope = {}) {
  const heading = `${cardSpec?.title || ""} ${cardSpec?.subtitle || ""}`;
  return /×\s*\d+|swipe|switch|多个|多张|滑动|切换/i.test(heading) ||
    readingTextMentionsExcludedPriorTopic(heading, scope?.messageText || "");
}

function readingCardSubtitleForRequest(message = "") {
  const questionCount = inferAgentRequestedQuestionCount(message) || 3;
  return `1 short passage · ${questionCount} questions`;
}

function buildFallbackReadingPassageComponent(text = "") {
  const topic = inferFallbackReadingTopic(text);
  return {
    type: "summary",
    title: "Short Passage",
    text: fallbackReadingPassageText(topic)
  };
}

function buildFallbackReadingQuestionSetComponent(text = "") {
  const paragraphOrdering = isParagraphOrderingReadingRequest(text);
  const questionCount = paragraphOrdering ? 1 : (
    inferAgentRequestedQuestionCount(text) ||
    inferRequestedAgentCardItemCounts(text).question_set ||
    5
  );
  const optionCount = inferAgentRequestedOptionCount(text) || 4;
  const topic = inferFallbackReadingTopic(text);
  const baseQuestions = fallbackReadingQuestionBank(topic, text);
  const questions = Array.from({ length: Math.max(1, Math.min(questionCount, 8)) }, (_, index) => {
    const source = baseQuestions[index % baseQuestions.length];
    const options = [source.answer, ...source.distractors].slice(0, Math.max(2, Math.min(optionCount, 6)));
    const balanced = placeCorrectAgentQuestionOption(options, 0, index % options.length);
    return {
      questionText: source.questionText,
      options: balanced.options,
      correctAnswer: balanced.correctAnswer,
      explanation: ""
    };
  });
  return {
    type: "question_set",
    title: "Reading Questions",
    text: `${questions.length} comprehension questions`,
    questions
  };
}

function buildFallbackSingleReadingChoiceComponent(text = "") {
  const topic = inferFallbackReadingTopic(text);
  const lower = String(text || "").toLowerCase();
  const mainIdea = /main\s+idea|gist|central|主旨|中心/.test(lower);
  const options = mainIdea
    ? [
        readingMainIdeaAnswer(topic),
        "The passage mainly lists unrelated details.",
        "The passage mainly gives a personal address.",
        "The passage mainly explains a grammar rule."
      ]
    : [
        "The writer probably wants readers to understand the hidden meaning.",
        "The writer is only naming a place.",
        "The writer is giving a dictionary definition.",
        "The writer is changing the topic completely."
      ];
  return {
    type: "cloze",
    title: mainIdea ? "Main Idea Question" : "Reading Choice",
    text: mainIdea ? "What is the main idea of the passage?" : "What can you infer from the passage?",
    options,
    answer: options[0],
    explanation: ""
  };
}

function readingMainIdeaAnswer(topic = "general") {
  if (topic === "online_shopping") return "Online shopping is useful, but shoppers should check important details.";
  if (topic === "travel") return "Careful planning can make travel easier and reduce problems.";
  if (topic === "school") return "Digital tools can help students, but steady habits still matter.";
  if (topic === "lost_wallet") return "Emily lost her wallet because she left quickly, then found it with help.";
  if (topic === "lost_backpack") return "Sarah retraced her steps and found her missing backpack.";
  return "Small daily actions can build successful habits over time.";
}

function fallbackReadingPassageText(topic = "general") {
  if (topic === "community_garden_rain") {
    return "After two days of heavy rain, the community garden behind Maple Street looked messy. Some young tomato plants were bent, and water covered the path near the tool shed. On Saturday morning, Mr. Lewis opened the gate early and invited neighbors to help. Mia and her brother moved the small plants to higher boxes. Mrs. Chen cleared leaves from the drain so the extra water could run away. By noon, the path was safe again, and the gardeners made a simple plan: after every storm, two volunteers would check the drain and cover the youngest plants. The garden did not look perfect, but everyone felt proud because they had protected it together.";
  }
  if (topic === "school_plastic_waste") {
    return "At Green Hill School, students noticed that many plastic bottles and snack wrappers were thrown away after lunch. The environmental club asked each class to try three simple changes for one month. First, students brought reusable water bottles. Second, the canteen sold fruit in paper bags instead of plastic cups. Third, each classroom placed a recycling box near the door and checked it before the last lesson. At first, some students forgot their bottles, so the club put friendly reminders beside the timetable and lent spare cups from the office. By the end of the month, the school had much less plastic rubbish. The head teacher said the project worked because the steps were small, clear, and easy to repeat every day.";
  }
  if (topic === "lost_wallet") {
    return "On Monday afternoon, Emily stopped at a small cafe after school. She paid for a sandwich, put her wallet on the table, and started checking a message from her mother. Ten minutes later, she left the cafe in a hurry because it began to rain. When she reached the bus stop, she noticed that her wallet was missing. Emily went back to the cafe and asked the worker for help. The worker had found the wallet under a menu and kept it behind the counter. Emily thanked him and decided to check the table before leaving next time.";
  }
  if (topic === "lost_backpack") {
    return "Last Friday, Sarah stayed after school to finish an art project. She carried her backpack to the art room, but later walked to the library with only her notebook. When she was ready to go home, she could not find the backpack in her classroom. Sarah felt worried because her keys and homework were inside. She asked her teacher for help, and they checked the places she had visited. Finally, they found the backpack beside a desk in the art room. Sarah felt relieved and promised to make a checklist before leaving school.";
  }
  if (topic === "online_shopping") {
    return "Online shopping has become part of daily life for many people. It saves time, offers a wide choice of products, and lets customers compare prices quickly. However, shoppers still need to check sizes, delivery dates, return rules, and payment security before they buy.";
  }
  if (topic === "travel") {
    return "Planning a trip is easier when travellers compare transport times, ticket prices, and hotel locations before they leave. Good planning can prevent delays, but travellers should still check messages from airlines, stations, or hotels because schedules sometimes change.";
  }
  if (topic === "school") {
    return "Many students use digital tools to organise homework, review notes, and ask questions after class. These tools can make study more flexible, but students still need a quiet routine and clear goals if they want to improve steadily.";
  }
  return "A successful habit usually starts with a small, clear action. When people repeat that action every day, it becomes easier to continue. Over time, small improvements can create a bigger change than one short burst of effort.";
}

function fallbackReadingQuestionBank(topic = "general", request = "") {
  const lowerRequest = String(request || "").toLowerCase();
  if (isParagraphOrderingReadingRequest(lowerRequest)) {
    return [
      {
        questionText: "What is the best order for the three paragraphs?",
        answer: "A - B - C",
        distractors: ["B - A - C", "C - A - B", "A - C - B", "B - C - A"]
      }
    ];
  }
  if (/infer|inference|imply|suggest|推断|暗示/.test(lowerRequest)) {
    return [
      {
        questionText: "What can you infer from the passage?",
        answer: "The main person is trying to solve a practical problem.",
        distractors: ["The main person wants to stop learning.", "The passage is only about weather.", "The writer dislikes all routines."]
      },
      {
        questionText: "What does the passage suggest about steady practice?",
        answer: "It becomes more useful when repeated regularly.",
        distractors: ["It works only once.", "It should replace all feedback.", "It is unrelated to improvement."]
      },
      {
        questionText: "What is implied by the final sentence?",
        answer: "Small steps can lead to larger progress.",
        distractors: ["Large changes never happen.", "Short effort is always enough.", "Planning is not useful."]
      }
    ];
  }
  if (/main\s+idea|gist|central|主旨|中心/.test(lowerRequest)) {
    return [
      {
        questionText: "What is the main idea of the passage?",
        answer: readingMainIdeaAnswer(topic),
        distractors: ["The passage mainly lists unrelated details.", "The passage mainly gives a grammar rule.", "The passage mainly describes one word's spelling."]
      }
    ];
  }
  if (topic === "community_garden_rain") {
    return [
      {
        questionText: "What problem did the heavy rain cause in the garden?",
        answer: "Some tomato plants were bent and water covered part of the path.",
        distractors: ["The tool shed disappeared.", "The neighbors stopped visiting forever.", "All the vegetables were already picked."]
      },
      {
        questionText: "What did Mrs. Chen do to help the garden?",
        answer: "She cleared leaves from the drain.",
        distractors: ["She locked the garden gate.", "She bought new tomato plants.", "She painted the tool shed."]
      },
      {
        questionText: "What plan did the gardeners make for future storms?",
        answer: "Two volunteers would check the drain and cover the youngest plants.",
        distractors: ["They would close the garden every weekend.", "They would stop growing tomatoes.", "They would move the tool shed to another street."]
      }
    ];
  }
  if (topic === "lost_wallet") {
    return [
      {
        questionText: "Where did Emily stop after school?",
        answer: "At a small cafe.",
        distractors: ["At a train station.", "At the school library.", "At a bus office."]
      },
      {
        questionText: "When did Emily notice that her wallet was missing?",
        answer: "When she reached the bus stop.",
        distractors: ["Before she bought lunch.", "After she got home.", "While she was in class."]
      },
      {
        questionText: "Where did the worker keep the wallet?",
        answer: "Behind the counter.",
        distractors: ["Inside Emily's schoolbag.", "On the bus seat.", "Under the bus stop sign."]
      },
      {
        questionText: "What did Emily decide to do next time?",
        answer: "Check the table before leaving.",
        distractors: ["Stop using cafes.", "Leave earlier every day.", "Carry no money."]
      }
    ];
  }
  if (topic === "lost_backpack") {
    return [
      {
        questionText: "Why did Sarah stay after school?",
        answer: "To finish an art project.",
        distractors: ["To meet her cousin.", "To buy a sandwich.", "To wait for a bus."]
      },
      {
        questionText: "What was inside Sarah's backpack?",
        answer: "Her keys and homework.",
        distractors: ["A wallet and a phone.", "A train ticket.", "A cafe menu."]
      },
      {
        questionText: "Where did they find the backpack?",
        answer: "Beside a desk in the art room.",
        distractors: ["At the bus stop.", "Behind a cafe counter.", "In the school canteen."]
      },
      {
        questionText: "How did Sarah feel at the end?",
        answer: "Relieved.",
        distractors: ["Angry.", "Bored.", "Confused."]
      }
    ];
  }
  if (topic === "online_shopping") {
    return [
      {
        questionText: "Why do many people like online shopping?",
        answer: "It saves time and offers many choices.",
        distractors: ["It always has free delivery.", "It lets customers touch products first.", "It removes the need to compare prices."]
      },
      {
        questionText: "What should shoppers check before buying clothes online?",
        answer: "Sizes and return rules.",
        distractors: ["The shop's music.", "The colour of the delivery truck.", "The number of staff in the store."]
      },
      {
        questionText: "What safety issue does the passage mention?",
        answer: "Payment security.",
        distractors: ["Noisy shops.", "Crowded streets.", "Weather reports."]
      },
      {
        questionText: "What can customers compare quickly online?",
        answer: "Prices.",
        distractors: ["Traffic lights.", "Classroom rules.", "Train drivers."]
      },
      {
        questionText: "What is the main idea of the passage?",
        answer: "Online shopping is useful, but shoppers should check important details.",
        distractors: ["Online shopping is always dangerous.", "Physical stores should close.", "Delivery dates are never important."]
      }
    ];
  }
  if (topic === "travel") {
    return [
      {
        questionText: "What can good planning prevent?",
        answer: "Delays.",
        distractors: ["All ticket costs.", "Every weather problem.", "The need for hotels."]
      },
      {
        questionText: "What should travellers compare before leaving?",
        answer: "Transport times, prices, and hotel locations.",
        distractors: ["Restaurant music only.", "Class notes.", "Office salaries."]
      },
      {
        questionText: "Why should travellers check messages?",
        answer: "Schedules sometimes change.",
        distractors: ["Tickets never change.", "Hotels do not have locations.", "Stations are always closed."]
      }
    ];
  }
  if (topic === "school") {
    return [
      {
        questionText: "What do digital tools help students organise?",
        answer: "Homework and notes.",
        distractors: ["Hotel rooms.", "Delivery dates.", "Payment cards."]
      },
      {
        questionText: "What do students still need?",
        answer: "A quiet routine and clear goals.",
        distractors: ["More noise.", "No questions.", "A shopping list."]
      },
      {
        questionText: "What can digital tools make more flexible?",
        answer: "Study.",
        distractors: ["Weather.", "Train schedules.", "Shop returns."]
      }
    ];
  }
  if (topic === "school_plastic_waste") {
    return [
      {
        questionText: "What problem did students notice after lunch?",
        answer: "Many plastic bottles and wrappers were thrown away.",
        distractors: ["The canteen had no fruit.", "The classrooms were too small.", "The timetable was missing."]
      },
      {
        questionText: "What did the environmental club ask students to bring?",
        answer: "Reusable water bottles.",
        distractors: ["Plastic cups.", "New school bags.", "Extra snack wrappers."]
      },
      {
        questionText: "Why did the project work well?",
        answer: "The steps were small, clear, and easy to repeat.",
        distractors: ["Only teachers joined it.", "Students stopped eating lunch.", "The school bought more plastic cups."]
      },
      {
        questionText: "Where did each classroom place a recycling box?",
        answer: "Near the door.",
        distractors: ["Beside the canteen.", "Under the timetable.", "Inside the library."]
      }
    ];
  }
  return [
    {
      questionText: "What does a successful habit often start with?",
      answer: "A small, clear action.",
      distractors: ["A long delay.", "A confusing plan.", "A single large reward."]
    },
    {
      questionText: "What happens when people repeat an action every day?",
      answer: "It becomes easier to continue.",
      distractors: ["It always disappears.", "It becomes impossible.", "It stops being useful."]
    },
    {
      questionText: "What can small improvements create over time?",
      answer: "A bigger change.",
      distractors: ["No progress.", "A shorter day.", "Less practice."]
    }
  ];
}

function placeCorrectAgentQuestionOption(options = [], currentIndex = 0, targetIndex = 0) {
  const cleaned = options.map(stripAgentQuestionOptionLabel);
  if (!cleaned.length || currentIndex < 0 || currentIndex >= cleaned.length) {
    return {
      options: cleaned,
      correctAnswer: 0
    };
  }
  const safeTarget = Math.max(0, Math.min(targetIndex, cleaned.length - 1));
  if (safeTarget === currentIndex) {
    return {
      options: cleaned,
      correctAnswer: currentIndex
    };
  }
  const reordered = cleaned.slice();
  const [correctOption] = reordered.splice(currentIndex, 1);
  reordered.splice(safeTarget, 0, correctOption);
  return {
    options: reordered,
    correctAnswer: safeTarget
  };
}

function inferAgentRequestedOptionCount(text = "") {
  const value = String(text || "").toLowerCase();
  const match = /(?:each\s+question\s+)?([2-6])\s*(?:个|個)?\s*(?:options?|选项)/i.exec(value) ||
    /(?:options?|选项)\D{0,12}([2-6])(?:\s|$)/i.exec(value);
  if (match) return Number(match[1]);
  const words = [
    ["two", 2], ["three", 3], ["four", 4], ["five", 5], ["six", 6], ["seven", 7], ["eight", 8], ["nine", 9],
    ["二", 2], ["两", 2], ["三", 3], ["四", 4], ["五", 5], ["六", 6], ["七", 7], ["八", 8], ["九", 9]
  ];
  const found = words.find(([word]) => new RegExp(`${escapeRegExp(String(word))}\\s*(?:个|個)?\\s*(?:options?|选项)|(?:options?|选项)\\D{0,12}${escapeRegExp(String(word))}`, "i").test(value));
  return found ? found[1] : null;
}

module.exports = {
  isReadingPassageQuizRequest,
  isSingleReadingChoiceRequest,
  enforceReadingPassageCurrentRequestScope,
  readingQuestionSetDoesNotMatchRequestedType,
  isUsableReadingPassageText,
  readingComponentMentionsExcludedPriorTopic,
  readingTextMentionsExcludedPriorTopic,
  readingCardHeadingNeedsCurrentRequestRepair,
  readingCardSubtitleForRequest,
  buildFallbackReadingPassageComponent,
  buildFallbackReadingQuestionSetComponent,
  buildFallbackSingleReadingChoiceComponent,
  readingMainIdeaAnswer,
  fallbackReadingPassageText,
  fallbackReadingQuestionBank,
  placeCorrectAgentQuestionOption,
  inferAgentRequestedOptionCount,
};
