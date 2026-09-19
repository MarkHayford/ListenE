import { Body, Controller, Delete, Get, HttpCode, HttpException, Param, Patch, Post, Put, Query, Req, Res } from "@nestjs/common";

const { settings } = require("../config");
const { logger } = require("../services/logger");
const { recordAnalysis } = require("../services/learningAgent");
const {
  createWorkspace,
  deleteWorkspace,
  listWorkspaces,
  listWorkspaceMessages,
  recordWorkspaceEvent,
  saveWorkspaceMessages,
  updateWorkspace
} = require("../services/learningWorkspace");
const {
  loginUser,
  logoutUser,
  optionalUserFromRequest,
  refreshSession,
  registerUser,
  requireUserFromRequest
} = require("../services/auth");
const {
  createLibraryItem,
  deleteLibraryItem,
  listLibraryItems
} = require("../services/userLibrary");
const {
  analyzeMistakes,
  analyzePracticeSheet,
  generateAgentChatReply,
  isExplicitAgentPracticeCardRequest,
  generateListeningContent,
  translateText
} = require("../services/mimoText");
const { synthesizeAgentSpeech, transcribeAudio, assessSpeaking, assessWriting, roleplayTurn, roleplayFeedback, generateShadowingSentences } = require("../services/mimoAgentMedia");
const { solveQuestion } = require("../services/mimoSolve");
const { organizeItems } = require("../services/mimoOrganize");
const { getCategories, putCategories } = require("../services/userCategories");
const { getAppVersion } = require("../services/appVersion");
const { getUserModel, updateUserModel } = require("../services/userModel");
const { generateMicroCardReply } = require("../services/microCardGenerate");
const { generateAgentChatReplyWithMicro } = require("../services/agentChatMicro");
const { synthesizeListeningAudio } = require("../services/listeningSynthesize");
const { buildListeningPackageZip } = require("../services/listeningPackage");
const { generateAudioFile } = require("../services/mimoTts");
const { sseFrame, SSE_HEADERS, startSseHeartbeat } = require("../services/sse");
const { generateStudyPlan } = require("../services/mimoPlan");
const { listPlans, createPlans, updatePlan, deletePlan } = require("../services/studyPlan");
const { recordProgress, summarizeProgress } = require("../services/studyProgress");
const { addWrong, listItems, gradeItem, removeItem } = require("../services/reviewBook");
const { lookupWord, addWord, listWords, gradeWord, removeWord } = require("../services/vocabBook");
const { getDaily, submitDaily } = require("../services/dailyChallenge");
const { query } = require("../services/db");
const { INPUT_LIMITS, ensureWithinLimit } = require("../services/inputLimits");
const { snapshot: metricsSnapshot } = require("../services/metrics");
const { authMonitorForEmail } = require("../services/authMonitor");
const crypto = require("crypto");

// 受保护指标端点的令牌比对：定长比较，避免计时侧信道；空/长度不符直接拒绝。
function metricsTokenMatches(provided: string, expected: string): boolean {
  const a = Buffer.from(String(provided || ""));
  const b = Buffer.from(String(expected || ""));
  if (a.length === 0 || a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

function requireMetricsAccess(request: any) {
  if (!settings.metricsToken) throw new HttpException({ detail: "metrics endpoint disabled" }, 404);
  const header = String(request?.headers?.authorization || "");
  const bearer = /^Bearer\s+(.+)$/i.exec(header);
  const provided = bearer ? bearer[1].trim() : String(request?.headers?.["x-metrics-token"] || "");
  if (!metricsTokenMatches(provided, settings.metricsToken)) {
    throw new HttpException({ detail: "unauthorized" }, 401);
  }
}

type StatusRule = { pattern: RegExp; status: number };

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function statusFromRules(error: unknown, rules: StatusRule[] = []): number {
  const message = errorMessage(error);
  return rules.find((rule) => rule.pattern.test(message))?.status || 500;
}

async function respond<T>(handler: () => T | Promise<T>, rules: StatusRule[] = [], logLabel = ""): Promise<T> {
  try {
    return await handler();
  } catch (error) {
    const message = errorMessage(error);
    const status = statusFromRules(error, rules);
    // 5xx 一律记录服务端日志（无论是否传 logLabel），便于排障。
    if (logLabel || status >= 500) logger.error("request handler failed", { label: logLabel || "unknown", detail: message });
    // 4xx 的 message 是面向用户的可读错误，按原样返回；5xx 是内部错误，返回通用文案避免泄露 MiMo/DB 细节。
    const clientDetail = status >= 500 ? "服务暂时不可用，请稍后重试。" : message;
    throw new HttpException({ detail: clientDetail }, status);
  }
}

async function optionalUser(request: any) {
  return optionalUserFromRequest(request);
}

async function requiredUser(request: any) {
  return requireUserFromRequest(request).then((result: any) => result.user);
}

const authRules = [{ pattern: /unauthorized/i, status: 401 }];

@Controller()
export class HealthController {
  @Get("healthz")
  liveness() {
    return { status: "ok", uptime: Math.round(process.uptime()), timestamp: Date.now() };
  }

  @Get("readyz")
  async readiness() {
    try {
      await query("SELECT 1");
      return { status: "ok", db: "up" };
    } catch (error) {
      logger.error("health readiness failed", { detail: errorMessage(error) });
      throw new HttpException({ status: "degraded", db: "down" }, 503);
    }
  }
}

@Controller("api/v1/auth")
export class AuthController {
  @Post("register")
  register(@Body() body: any, @Req() request: any) {
    return respond(
      () => registerUser(body || {}, request),
      [
        { pattern: /already registered/i, status: 409 },
        { pattern: /valid email|required|password/i, status: 400 }
      ],
      "auth/register"
    );
  }

  @Post("login")
  @HttpCode(200)
  login(@Body() body: any, @Req() request: any) {
    return respond(
      () => loginUser(body || {}, request),
      [{ pattern: /invalid email or password/i, status: 401 }],
      "auth/login"
    );
  }

  @Get("me")
  me(@Req() request: any) {
    return respond(async () => ({ user: await requiredUser(request) }), authRules);
  }

  @Post("logout")
  @HttpCode(200)
  logout(@Req() request: any) {
    return respond(async () => {
      const user = await requiredUser(request);
      return logoutUser(user.id, request);
    }, authRules, "auth/logout");
  }

  // 滑动续期：用当前仍有效的 token 换一枚 exp 顺延的新 token，免重新登录。
  @Post("refresh")
  @HttpCode(200)
  refresh(@Req() request: any) {
    return respond(async () => refreshSession(request), authRules, "auth/refresh");
  }
}

@Controller("api/v1/listening")
export class ListeningController {
  @Post("synthesize")
  @HttpCode(200)
  synthesize(@Body() body: any) {
    return respond(async () => {
      const t0 = Date.now();
      const audio = await synthesizeListeningAudio(body);
      logger.info("listening synthesize", { contentType: body?.contentType || "dialogue", totalMs: Date.now() - t0 });
      return audio;
    }, [], "listening/synthesize");
  }

  @Post("generate")
  @HttpCode(200)
  generate(@Body() body: any) {
    return respond(async () => {
      const t0 = Date.now();
      const content = await generateListeningContent(body);
      const tText = Date.now();
      const contentType = (body?.contentType || "dialogue").toLowerCase();
      const voiceGender = body?.voiceGender || content.voiceGender || "";
      const speechRate = body?.speechRate || content.speechRate || "";
      const voiceProfile = body?.voiceProfile || content.voiceProfile || "";
      const pitch = body?.pitch || content.pitch || "";
      const accent = body?.accent || content.accent || "";
      const tone = body?.tone || content.tone || "";
      const skipAudio = body?.skipAudio === true || body?.skipAudio === "true";
      if (skipAudio) {
        logger.info("listening generate", { contentType, textMs: tText - t0, audio: "skipped", segments: (content.ttsSegments || []).length });
        return {
          id: `lesson_${Date.now()}`,
          title: content.title,
          script: content.script,
          questions: content.questions,
          ttsPrompt: content.ttsPrompt,
          ttsSegments: content.ttsSegments || [],
          speakers: content.speakers || [],
          voiceGender,
          speechRate,
          voiceProfile,
          pitch,
          accent,
          tone
        };
      }

      const audio = await generateAudioFile(content.script, content.ttsPrompt, content.ttsSegments, {
        contentType,
        voiceGender,
        speechRate,
        voiceProfile,
        pitch,
        accent,
        tone
      });
      const tTts = Date.now();
      logger.info("listening generate", { contentType, textMs: tText - t0, ttsMs: tTts - tText, totalMs: tTts - t0, segments: (content.ttsSegments || []).length });
      return {
        id: `lesson_${Date.now()}`,
        title: content.title,
        script: content.script,
        questions: content.questions,
        ttsPrompt: content.ttsPrompt,
        ttsSegments: content.ttsSegments || [],
        speakers: content.speakers || [],
        voiceGender,
        speechRate,
        voiceProfile,
        pitch,
        accent,
        tone,
        audioUrl: `${settings.publicBaseUrl}/audio/${audio.filename}`,
        audioSegments: audio.audioSegments || []
      };
    }, [], "listening/generate");
  }

  // SSE 流式生成：文本先到、音频后到。先推 generateListeningContent 的文本（客户端可立即渲染
  // 原文/题目），再推 TTS 音频地址。相比一次性 /generate（要等 TTS 完成才有任何响应），
  // 大幅缩短「看到听力原文」的等待。事件：text → audio → done；失败推 error。
  @Post("generate/stream")
  async generateStream(@Body() body: any, @Res() reply: any) {
    const t0 = Date.now();
    const raw = reply.raw;
    if (typeof reply.hijack === "function") reply.hijack();
    raw.writeHead(200, SSE_HEADERS);
    // 心跳保活：文本/TTS 生成期间无事件下发，注释帧防止反代/移动网络掐断空闲连接。
    const stopHeartbeat = startSseHeartbeat(raw);
    const send = (event: string, data: unknown) => raw.write(sseFrame(event, data));
    try {
      const content = await generateListeningContent(body);
      const tText = Date.now();
      const contentType = (body?.contentType || "dialogue").toLowerCase();
      const voiceGender = body?.voiceGender || content.voiceGender || "";
      const speechRate = body?.speechRate || content.speechRate || "";
      const voiceProfile = body?.voiceProfile || content.voiceProfile || "";
      const pitch = body?.pitch || content.pitch || "";
      const accent = body?.accent || content.accent || "";
      const tone = body?.tone || content.tone || "";
      const id = `lesson_${Date.now()}`;
      send("text", {
        id,
        title: content.title,
        script: content.script,
        questions: content.questions,
        ttsPrompt: content.ttsPrompt,
        ttsSegments: content.ttsSegments || [],
        speakers: content.speakers || [],
        voiceGender,
        speechRate,
        voiceProfile,
        pitch,
        accent,
        tone
      });

      const skipAudio = body?.skipAudio === true || body?.skipAudio === "true";
      if (skipAudio) {
        logger.info("listening generate(stream)", { contentType, textMs: tText - t0, audio: "skipped", segments: (content.ttsSegments || []).length });
        send("done", { id, audio: false });
        raw.end();
        return;
      }

      const audio = await generateAudioFile(content.script, content.ttsPrompt, content.ttsSegments, {
        contentType,
        voiceGender,
        speechRate,
        voiceProfile,
        pitch,
        accent,
        tone
      });
      const tTts = Date.now();
      send("audio", {
        id,
        audioUrl: `${settings.publicBaseUrl}/audio/${audio.filename}`,
        audioSegments: audio.audioSegments || []
      });
      logger.info("listening generate(stream)", { contentType, textMs: tText - t0, ttsMs: tTts - tText, totalMs: tTts - t0, segments: (content.ttsSegments || []).length });
      send("done", { id, audio: true });
      raw.end();
    } catch (error) {
      const message = errorMessage(error);
      logger.error("request handler failed", { label: "listening/generate/stream", detail: message });
      // 与 respond() 一致：不向客户端泄露内部细节。
      send("error", { detail: "服务暂时不可用，请稍后重试。" });
      try { raw.end(); } catch { /* socket 已关闭 */ }
    } finally {
      stopHeartbeat();
    }
  }

  @Post("package")
  @HttpCode(200)
  packageListening(@Body() body: any) {
    return respond(async () => {
      const t0 = Date.now();
      const result = await buildListeningPackageZip(body || {});
      logger.info("listening package", { audioIncluded: result.audioIncluded, sizeBytes: result.sizeBytes, totalMs: Date.now() - t0 });
      return result;
    }, [{ pattern: /no listening material|invalid listening record/i, status: 400 }], "listening/package");
  }
}

@Controller("api/v1")
export class TextController {
  @Post("translate")
  @HttpCode(200)
  translate(@Body() body: any) {
    return respond(async () => {
      const text = ensureWithinLimit(body?.text, INPUT_LIMITS.translateText, "text");
      return { translation: await translateText(text, body?.context) };
    }, [{ pattern: /too long|required/i, status: 400 }]);
  }

  @Post("analyze-mistakes")
  @HttpCode(200)
  analyze(@Body() body: any) {
    return respond(() => analyzeMistakes(body));
  }

  // 练习卡（微元卡）作答分析：客户端本地判分后上传作答表，模型只解释错因。
  @Post("analyze-practice")
  @HttpCode(200)
  analyzePractice(@Body() body: any) {
    return respond(() => analyzePracticeSheet(body), [{ pattern: /作答表为空/, status: 400 }]);
  }
}

@Controller("api/v1/agent")
export class AgentController {
  @Post("analysis")
  @HttpCode(200)
  analysis(@Body() body: any, @Req() request: any) {
    return respond(async () => {
      const result = recordAnalysis(body || {});
      const workspaceId = String(body?.workspaceId || body?.workspace?.id || "").trim();
      const user = await optionalUser(request);
      if (workspaceId && user) {
        await recordWorkspaceEvent(workspaceId, {
          type: "analysis_completed",
          title: body?.title || result?.event?.title || "AI 错因分析完成",
          description: result?.event?.summary || "",
          recordId: body?.recordId || result?.event?.recordId || body?.record?.id || "",
          currentStep: "review",
          result: {
            weakPoints: result?.event?.weakPoints || [],
            diagnosisTags: result?.event?.diagnosisTags || [],
            wrongQuestionInsights: result?.event?.wrongQuestionInsights || []
          },
          weakPoints: result?.event?.weakPoints || []
        }, user);
      }
      return result;
    }, authRules);
  }

  @Post("chat")
  @HttpCode(200)
  chat(@Body() body: any) {
    return respond(() => generateAgentChatReplyWithMicro(body || {}), [{ pattern: /required/i, status: 400 }], "agent/chat");
  }

  // SSE 流式：推送生成阶段进度（理解意图→AI 组卡→校验→完成），practice_card 末尾 done 事件带原生微元卡。
  // 与阻塞 /agent/chat 等价产物，仅多了过程进度；客户端可优先用它、失败回退 /agent/chat。
  @Post("chat/stream")
  async chatStream(@Body() body: any, @Res() reply: any) {
    const raw = reply.raw;
    if (typeof reply.hijack === "function") reply.hijack();
    raw.writeHead(200, SSE_HEADERS);
    // 心跳保活：微元生成的模型调用非流式（可静默 20-60s），注释帧防止反代/移动网络掐断空闲连接。
    const stopHeartbeat = startSseHeartbeat(raw);
    const send = (event: string, data: unknown) => { try { raw.write(sseFrame(event, data)); } catch { /* socket 已关闭 */ } };
    try {
      send("stage", { stage: "intent", text: "正在理解你的需求…" });
      const msg = String((body && (body.message || body.userMessage || body.text)) || "");
      // 并行：预判像练习卡时，微元生成与意图分类重叠跑（其阶段进度即时下发）；非练习卡则丢弃。
      const microPromise = isExplicitAgentPracticeCardRequest(msg)
        ? generateMicroCardReply(body || {}, { onStage: (s: unknown) => send("stage", s) }).catch(() => null)
        : null;
      // delta 事件：reply 正文的流式增量（打字机上屏）；done 里的完整 reply 仍是权威版本，客户端以其收尾替换。
      const decision = await generateAgentChatReply(body || {}, {
        onReplyDelta: (text: string) => send("delta", { text })
      });
      if (decision && decision.intent === "practice_card") {
        const micro = await (microPromise || generateMicroCardReply(body || {}, { onStage: (s: unknown) => send("stage", s) }).catch(() => null));
        if (micro && micro.card) { decision.microCard = micro.card; decision.microReport = micro.report; decision.microOk = micro.ok; }
        decision.cardSpec = null;
      }
      send("done", decision);
      raw.end();
    } catch (error) {
      logger.error("request handler failed", { label: "agent/chat/stream", detail: errorMessage(error) });
      send("error", { detail: "服务暂时不可用，请稍后重试。" });
      try { raw.end(); } catch { /* socket 已关闭 */ }
    } finally {
      stopHeartbeat();
    }
  }

  @Post("asr")
  @HttpCode(200)
  asr(@Body() body: any) {
    return respond(() => transcribeAudio(body || {}), [{ pattern: /required|无效|过大/i, status: 400 }], "agent/asr");
  }

  @Post("speaking")
  @HttpCode(200)
  speaking(@Body() body: any) {
    return respond(() => assessSpeaking(body || {}), [{ pattern: /required|无效|过大/i, status: 400 }], "agent/speaking");
  }

  @Post("writing")
  @HttpCode(200)
  writing(@Body() body: any) {
    return respond(() => assessWriting(body || {}), [{ pattern: /required|为空/i, status: 400 }], "agent/writing");
  }

  // 拍照答疑：用户上传题目（图片/文档/文字），返回结构化解题（读题→考点→分步解析→答案→易错点→生词）。
  @Post("solve")
  @HttpCode(200)
  solve(@Body() body: any) {
    return respond(() => solveQuestion(body || {}), [{ pattern: /required|为空|无效|过大|不支持|失败/i, status: 400 }], "agent/solve");
  }

  // AI 一键整理：把用户的收藏项目(工作区/卡片/文件)归纳成几个分类并逐项归类，客户端预览后套用。
  @Post("organize")
  @HttpCode(200)
  organize(@Body() body: any) {
    return respond(() => organizeItems(body || {}), [{ pattern: /没有可整理|required|为空|无效|失败/i, status: 400 }], "agent/organize");
  }

  // 客户端更新检测(公开，登录前可查)：返回最新 versionCode/name、下载地址、是否强制更新、更新说明。
  @Get("app-version")
  @HttpCode(200)
  appVersion() {
    return respond(() => getAppVersion(), [], "agent/app-version");
  }

  // 相册式分类的云端同步：读取 / 覆盖用户的分类清单+归属(整块 last-write-wins)。需登录。
  @Get("categories")
  @HttpCode(200)
  getCategoriesRoute(@Req() request: any) {
    return respond(async () => getCategories(await requiredUser(request)), authRules, "agent/categories");
  }

  @Put("categories")
  @HttpCode(200)
  putCategoriesRoute(@Body() body: any, @Req() request: any) {
    return respond(async () => putCategories(await requiredUser(request), (body && body.data) || {}), authRules, "agent/categories");
  }

  // 统一用户模型（跨功能长期画像）：读取 / 合并上报。未登录时返回/合并空模型（不落库）。
  @Get("user-model")
  @HttpCode(200)
  getUserModelRoute(@Req() request: any) {
    return respond(async () => getUserModel(await optionalUser(request)), [], "agent/user-model");
  }

  @Post("user-model")
  @HttpCode(200)
  updateUserModelRoute(@Body() body: any, @Req() request: any) {
    return respond(async () => updateUserModel(await optionalUser(request), body || {}), [], "agent/user-model");
  }

  @Post("roleplay/turn")
  @HttpCode(200)
  roleTurn(@Body() body: any) {
    return respond(() => roleplayTurn(body || {}), [{ pattern: /required|empty|为空/i, status: 400 }], "agent/roleplay/turn");
  }

  @Post("roleplay/feedback")
  @HttpCode(200)
  roleFeedback(@Body() body: any) {
    return respond(() => roleplayFeedback(body || {}), [{ pattern: /required|empty|为空/i, status: 400 }], "agent/roleplay/feedback");
  }

  @Post("shadowing")
  @HttpCode(200)
  shadowing(@Body() body: any) {
    return respond(() => generateShadowingSentences(body || {}), [{ pattern: /required|empty/i, status: 400 }], "agent/shadowing");
  }

  @Post("tts")
  @HttpCode(200)
  tts(@Body() body: any) {
    return respond(() => synthesizeAgentSpeech(body || {}), [{ pattern: /required/i, status: 400 }], "agent/tts");
  }

  // 微元卡（实验）：AI 实时把微元拼成一张卡，自带“校验→回灌自修”闭环；与旧题型管线隔离。
  @Post("micro")
  @HttpCode(200)
  micro(@Body() body: any) {
    return respond(() => generateMicroCardReply(body || {}), [{ pattern: /required/i, status: 400 }], "agent/micro");
  }

  @Get("workspaces")
  workspaces(@Req() request: any) {
    return respond(async () => ({ workspaces: await listWorkspaces(await optionalUser(request)) }), authRules);
  }

  @Post("workspaces")
  createWorkspace(@Body() body: any, @Req() request: any) {
    return respond(
      async () => createWorkspace(body || {}, await optionalUser(request)),
      [
        { pattern: /required/i, status: 400 },
        ...authRules
      ]
    );
  }

  @Patch("workspaces/:id")
  @HttpCode(200)
  updateWorkspace(@Param("id") id: string, @Body() body: any, @Req() request: any) {
    return respond(async () => updateWorkspace(id, body || {}, await optionalUser(request)), [
      { pattern: /not found/i, status: 404 },
      { pattern: /cannot rewind/i, status: 400 },
      ...authRules
    ]);
  }

  @Delete("workspaces/:id")
  @HttpCode(200)
  deleteWorkspace(@Param("id") id: string, @Req() request: any) {
    return respond(async () => deleteWorkspace(id, await optionalUser(request)), [
      { pattern: /not found/i, status: 404 },
      ...authRules
    ]);
  }

  @Post("workspaces/:id/events")
  @HttpCode(200)
  workspaceEvent(@Param("id") id: string, @Body() body: any, @Req() request: any) {
    return respond(async () => recordWorkspaceEvent(id, body || {}, await optionalUser(request)), [
      { pattern: /not found/i, status: 404 },
      ...authRules
    ]);
  }

  @Get("workspaces/:id/messages")
  workspaceMessages(@Param("id") id: string, @Req() request: any) {
    return respond(async () => listWorkspaceMessages(id, await optionalUser(request)), authRules);
  }

  @Put("workspaces/:id/messages")
  @HttpCode(200)
  saveMessages(@Param("id") id: string, @Body() body: any, @Req() request: any) {
    return respond(async () => saveWorkspaceMessages(id, body?.messages || [], await optionalUser(request)), [
      { pattern: /not found/i, status: 404 },
      ...authRules
    ]);
  }
}

@Controller("api/v1/library")
export class LibraryController {
  @Get("files")
  files(@Req() request: any) {
    return respond(async () => listLibraryItems("file", await optionalUser(request)), authRules);
  }

  @Post("files")
  @HttpCode(201)
  createFile(@Body() body: any, @Req() request: any) {
    return respond(async () => createLibraryItem("file", await requiredUser(request), body || {}), authRules);
  }

  @Delete("files/:id")
  @HttpCode(200)
  deleteFile(@Param("id") id: string, @Req() request: any) {
    return respond(async () => deleteLibraryItem("file", id, await requiredUser(request)), authRules);
  }

  @Get("cards")
  cards(@Req() request: any) {
    return respond(async () => listLibraryItems("card", await optionalUser(request)), authRules);
  }

  @Post("cards")
  @HttpCode(201)
  createCard(@Body() body: any, @Req() request: any) {
    return respond(async () => createLibraryItem("card", await requiredUser(request), body || {}), authRules);
  }

  @Delete("cards/:id")
  @HttpCode(200)
  deleteCard(@Param("id") id: string, @Req() request: any) {
    return respond(async () => deleteLibraryItem("card", id, await requiredUser(request)), authRules);
  }

  @Get("plugins")
  plugins(@Req() request: any) {
    return respond(async () => listLibraryItems("plugin", await optionalUser(request)), authRules);
  }

  @Post("plugins")
  @HttpCode(201)
  createPlugin(@Body() body: any, @Req() request: any) {
    return respond(async () => createLibraryItem("plugin", await requiredUser(request), body || {}), authRules);
  }

  @Delete("plugins/:id")
  @HttpCode(200)
  deletePlugin(@Param("id") id: string, @Req() request: any) {
    return respond(async () => deleteLibraryItem("plugin", id, await requiredUser(request)), authRules);
  }
}

@Controller("api/v1/plan")
export class PlanController {
  @Post("generate")
  @HttpCode(200)
  generate(@Body() body: any) {
    return respond(() => {
      const goal = ensureWithinLimit(body?.goal || body?.userGoal || "", INPUT_LIMITS.planGoal, "goal");
      const planDays = body?.days ?? body?.planDays ?? 14;
      return generateStudyPlan(goal, planDays);
    }, [{ pattern: /too long/i, status: 400 }], "plan/generate");
  }
}

@Controller("api/v1/plans")
export class StudyPlansController {
  @Get()
  list(@Req() request: any) {
    return respond(async () => listPlans(await requiredUser(request)), authRules, "plans/list");
  }

  @Post()
  @HttpCode(201)
  create(@Body() body: any, @Req() request: any) {
    return respond(async () => createPlans(await requiredUser(request), body?.items || body?.plans || []), authRules, "plans/create");
  }

  @Patch(":id")
  update(@Param("id") id: string, @Body() body: any, @Req() request: any) {
    return respond(async () => updatePlan(await requiredUser(request), id, body || {}), authRules, "plans/update");
  }

  @Delete(":id")
  delete(@Param("id") id: string, @Req() request: any) {
    return respond(async () => deletePlan(await requiredUser(request), id), authRules, "plans/delete");
  }
}

@Controller("api/v1/progress")
export class StudyProgressController {
  @Get("summary")
  summary(@Req() request: any) {
    return respond(async () => summarizeProgress(await requiredUser(request)), authRules, "progress/summary");
  }

  @Post()
  @HttpCode(201)
  record(@Body() body: any, @Req() request: any) {
    return respond(async () => recordProgress(await requiredUser(request), body || {}), authRules, "progress/record");
  }
}

@Controller("api/v1/review")
export class ReviewController {
  @Get()
  list(@Req() request: any) {
    return respond(async () => listItems(await requiredUser(request)), authRules, "review/list");
  }

  @Post()
  @HttpCode(201)
  add(@Body() body: any, @Req() request: any) {
    return respond(async () => addWrong(await requiredUser(request), body || {}), authRules, "review/add");
  }

  @Post(":id/grade")
  @HttpCode(200)
  grade(@Param("id") id: string, @Body() body: any, @Req() request: any) {
    return respond(async () => gradeItem(await requiredUser(request), id, Boolean(body?.remembered)), authRules, "review/grade");
  }

  @Delete(":id")
  delete(@Param("id") id: string, @Req() request: any) {
    return respond(async () => removeItem(await requiredUser(request), id), authRules, "review/delete");
  }
}

@Controller("api/v1/vocab")
export class VocabController {
  @Get()
  list(@Req() request: any) {
    return respond(async () => listWords(await requiredUser(request)), authRules, "vocab/list");
  }

  @Post("lookup")
  @HttpCode(200)
  lookup(@Body() body: any, @Req() request: any) {
    return respond(async () => { await requiredUser(request); return lookupWord(body || {}); }, [...authRules, { pattern: /required/i, status: 400 }], "vocab/lookup");
  }

  @Post()
  @HttpCode(201)
  add(@Body() body: any, @Req() request: any) {
    return respond(async () => addWord(await requiredUser(request), body || {}), authRules, "vocab/add");
  }

  @Post(":id/grade")
  @HttpCode(200)
  grade(@Param("id") id: string, @Body() body: any, @Req() request: any) {
    return respond(async () => gradeWord(await requiredUser(request), id, Boolean(body?.remembered)), authRules, "vocab/grade");
  }

  @Delete(":id")
  delete(@Param("id") id: string, @Req() request: any) {
    return respond(async () => removeWord(await requiredUser(request), id), authRules, "vocab/delete");
  }
}

@Controller("api/v1/daily")
export class DailyChallengeController {
  @Get()
  today(@Req() request: any) {
    return respond(async () => getDaily(await requiredUser(request)), authRules, "daily/today");
  }

  @Post("submit")
  @HttpCode(200)
  submit(@Body() body: any, @Req() request: any) {
    return respond(async () => submitDaily(await requiredUser(request), body?.answers || []), authRules, "daily/submit");
  }
}

@Controller("api/v1/metrics")
export class MetricsController {
  // 受保护的内存指标快照（5xx/慢请求比率、时延等）。默认禁用：未配置 METRICS_TOKEN 时返回 404；
  // 配置后需带 Bearer <METRICS_TOKEN>（或 x-metrics-token 头）才放行，避免运营指标对外暴露。
  @Get()
  metrics(@Req() request: any) {
    requireMetricsAccess(request);
    return metricsSnapshot();
  }

  @Get("auth/account")
  accountAuthMonitor(@Req() request: any, @Query("email") email: string) {
    requireMetricsAccess(request);
    if (!String(email || "").trim()) throw new HttpException({ detail: "email is required" }, 400);
    return respond(() => authMonitorForEmail(email), [], "metrics/auth/account");
  }
}
