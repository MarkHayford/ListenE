import "reflect-metadata";
import fs from "fs";
import fastifyStatic from "@fastify/static";
import { NestFactory } from "@nestjs/core";
import { FastifyAdapter, NestFastifyApplication } from "@nestjs/platform-fastify";
import { AppModule } from "./nest/app.module";

const crypto = require("crypto");
const { settings, collectStartupConfigErrors } = require("./config");
const { startAudioCleanup } = require("./modules/audio/audio-cleanup.service");
const { startPackageCleanup } = require("./services/listeningPackage");
const { createRateLimiter, classifyRateLimitRoute, clientIpFromRequest } = require("./services/rateLimit");
const { logger, requestLogLevel } = require("./services/logger");
const { securityHeaders } = require("./services/securityHeaders");
const metrics = require("./services/metrics");
const { bodyLimitForRoute, LARGE_BODY_LIMIT } = require("./services/bodyLimits");
const { DocumentBuilder, SwaggerModule } = require("@nestjs/swagger");

async function bootstrap() {
  const configErrors = collectStartupConfigErrors();
  if (configErrors.length) {
    for (const detail of configErrors) logger.error("启动配置校验失败", { detail });
    logger.error("ListenE Backend 拒绝启动：请修正上述配置后重试");
    process.exit(1);
  }

  fs.mkdirSync(settings.audioDir, { recursive: true });
  fs.mkdirSync(settings.packagesDir, { recursive: true });

  const adapter = new FastifyAdapter({
    bodyLimit: LARGE_BODY_LIMIT,
    logger: false
  });
  const fastify = adapter.getInstance();

  const rateLimiter = createRateLimiter();
  fastify.addHook("onRequest", (request, reply, done) => {
    const routeClass = classifyRateLimitRoute(request.method, request.url);
    if (routeClass) {
      const max = routeClass === "auth"
        ? settings.rateLimitAuthMax
        : routeClass === "probe"
          ? settings.rateLimitProbeMax
          : settings.rateLimitAiMax;
      const result = rateLimiter.hit(
        `${routeClass}:${clientIpFromRequest(request)}`,
        max,
        settings.rateLimitWindowMs
      );
      if (!result.allowed) {
        reply.header("Retry-After", String(Math.ceil(result.retryAfterMs / 1000)));
        reply.code(429).send({ detail: "请求过于频繁，请稍后再试。" });
        return;
      }
    }
    done();
  });

  // 请求体体积按路由分级限制：解析前按 Content-Length 提前拒绝过大请求，收窄廉价端点的
  // 放大式 DoS 面（如对 /api/v1/auth/login 灌 32MB）。无 Content-Length 的请求由全局 bodyLimit 兜底。
  fastify.addHook("onRequest", (request, reply, done) => {
    const limit = bodyLimitForRoute(request.method, request.url);
    if (Number.isFinite(limit)) {
      const len = Number(request.headers["content-length"]);
      if (Number.isFinite(len) && len > limit) {
        reply.code(413).send({ detail: "请求体过大。" });
        return;
      }
    }
    done();
  });

  fastify.addHook("onRequest", (request, reply, done) => {
    const incoming = request.headers["x-request-id"];
    const requestId = typeof incoming === "string" && incoming.trim()
      ? incoming.trim().slice(0, 64)
      : crypto.randomUUID();
    (request as any).requestId = requestId;
    (request as any).startTime = Date.now();
    reply.header("X-Request-Id", requestId);
    done();
  });

  fastify.addHook("onResponse", (request, reply, done) => {
    const status = reply.statusCode;
    const durationMs = Date.now() - ((request as any).startTime || Date.now());
    const slow = settings.slowRequestMs > 0 && durationMs >= settings.slowRequestMs;
    // 指标累计：跳过 /api/v1/metrics 自身及其子路由，避免拉取动作污染统计。
    const requestPath = String(request.url || "").split("?")[0].replace(/\/+$/, "") || "/";
    if (requestPath !== "/api/v1/metrics" && !requestPath.startsWith("/api/v1/metrics/")) {
      metrics.recordRequest({ status, durationMs, slow });
    }
    const level = requestLogLevel(status, durationMs, settings.slowRequestMs);
    logger[level]("request", {
      requestId: (request as any).requestId,
      method: request.method,
      url: request.url,
      status,
      durationMs,
      ...(slow ? { slow: true } : {})
    });
    done();
  });

  const securityResponseHeaders = securityHeaders();
  fastify.addHook("onSend", (request, reply, payload, done) => {
    for (const [key, value] of Object.entries(securityResponseHeaders)) {
      if (reply.getHeader(key) == null) reply.header(key, value as string);
    }
    done(null, payload);
  });

  await fastify.register(fastifyStatic, {
    root: settings.audioDir,
    prefix: "/api/v1/audio/"
  });
  await fastify.register(fastifyStatic, {
    root: settings.packagesDir,
    prefix: "/api/v1/packages/",
    decorateReply: false
  });

  const app = await NestFactory.create<NestFastifyApplication>(AppModule, adapter, {
    logger: ["error", "warn", "log"]
  });
  app.enableCors({
    origin: settings.corsOrigins.includes("*") ? true : settings.corsOrigins
  });
  if (settings.corsOrigins.includes("*")) {
    logger.warn("CORS 允许所有来源；生产环境请用 CORS_ORIGINS 设置白名单", { corsOrigins: settings.corsOrigins });
  }

  if (settings.swaggerEnabled) {
    const swaggerConfig = new DocumentBuilder()
      .setTitle("ListenE API")
      .setDescription("ListenE 后端 API 交互文档（开发调试用，生产默认关闭）")
      .setVersion("1.0")
      .addBearerAuth()
      .build();
    const document = SwaggerModule.createDocument(app, swaggerConfig);
    SwaggerModule.setup("api/v1/docs", app, document);
    logger.info("Swagger UI enabled", { path: "/api/v1/docs" });
  }

  startAudioCleanup();
  startPackageCleanup();
  await app.listen(settings.port, settings.host);
  logger.info("ListenE Backend running", { host: settings.host, port: settings.port });
}

bootstrap().catch((error) => {
  logger.error("ListenE Backend failed to start", { detail: error?.message || String(error) });
  process.exit(1);
});
