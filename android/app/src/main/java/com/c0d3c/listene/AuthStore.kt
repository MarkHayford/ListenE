package com.c0d3c.listene

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class AuthUser(
    val id: String,
    val email: String,
    val displayName: String,
    val avatar: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

data class AuthSession(
    val user: AuthUser,
    val token: String
)

object AuthStore {
    private const val PREFS_NAME = "listene_auth_store"
    private const val KEY_SESSION = "session_v1"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun loadSession(ctx: Context): AuthSession? {
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SESSION, "") ?: ""
        if (raw.isBlank()) return null
        return runCatching {
            parseSession(parseJsonObjectOrNull(raw) ?: error("invalid session json"))
        }.getOrNull()
    }

    fun saveSession(ctx: Context, session: AuthSession) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SESSION, sessionToJson(session).toString())
            .apply()
    }

    fun clearSession(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SESSION)
            .apply()
    }

    /** 当前登录用户 id；未登录返回空串。 */
    fun currentUserId(ctx: Context): String =
        loadSession(ctx.applicationContext)?.user?.id.orEmpty()

    /**
     * 按当前账号隔离的 SharedPreferences 名：base 拼上 userId（未登录用 anon）。
     * 让工作区/收藏/聊天/学习模型等本地数据各账号各自一份，切号不再串数据。
     * 注意：AuthStore 自身与设备级 AppSettings 保持全局，不参与隔离。
     */
    fun scopedPrefsName(ctx: Context, base: String): String {
        val uid = currentUserId(ctx)
        return if (uid.isBlank()) "${base}__anon" else "${base}__u_$uid"
    }

    fun applyAuth(builder: Request.Builder, ctx: Context): Request.Builder {
        val token = loadSession(ctx.applicationContext)?.token.orEmpty()
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        return builder
    }

    suspend fun register(ctx: Context, email: String, password: String, displayName: String): AuthSession =
        authenticate(ctx, "/auth/register", email, password, displayName)

    suspend fun login(ctx: Context, email: String, password: String): AuthSession =
        authenticate(ctx, "/auth/login", email, password, "")

    /** 通知服务端登出，使该 token（及该用户所有旧 token）立即失效。best-effort：失败不影响本地登出。 */
    suspend fun notifyLogout(token: String) = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext
        runCatching {
            val req = Request.Builder()
                .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/auth/logout")
                .header("Authorization", "Bearer $token")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { it.body.string() }
        }
        Unit
    }

    /**
     * 更新资料（昵称/头像，传 null 表示不改）：PATCH /auth/profile，Bearer 鉴权。
     * 服务端若返回最新 user 则采用；否则本地合并。成功后持久化并返回新会话。
     */
    suspend fun updateProfile(ctx: Context, displayName: String? = null, avatar: String? = null): Result<AuthSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                val current = loadSession(ctx.applicationContext) ?: error("未登录")
                val payload = buildJsonObject {
                    displayName?.let { put("displayName", it.trim()) }
                    avatar?.let { put("avatar", it) }
                }
                val req = applyAuth(
                    Request.Builder()
                        .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/auth/profile")
                        .patch(payload.toString().toRequestBody("application/json".toMediaType())),
                    ctx
                ).build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "修改失败"))
                    val serverUser = parseJsonObjectOrNull(body)?.objOrNull("user")?.let { parseUser(it) }
                    val user = if (serverUser != null && serverUser.id.isNotBlank()) serverUser else current.user.copy(
                        displayName = displayName?.trim() ?: current.user.displayName,
                        avatar = avatar ?: current.user.avatar
                    )
                    current.copy(user = user).also { saveSession(ctx.applicationContext, it) }
                }
            }
        }

    /**
     * 修改密码：POST /auth/change-password {oldPassword,newPassword}，Bearer 鉴权。
     * 服务端若返回新 token（旧 token 失效场景）则更新本地会话。
     */
    suspend fun changePassword(ctx: Context, oldPassword: String, newPassword: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val current = loadSession(ctx.applicationContext) ?: error("未登录")
                val payload = buildJsonObject {
                    put("oldPassword", oldPassword)
                    put("newPassword", newPassword)
                }
                val req = applyAuth(
                    Request.Builder()
                        .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/auth/change-password")
                        .post(payload.toString().toRequestBody("application/json".toMediaType())),
                    ctx
                ).build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "修改密码失败"))
                    parseJsonObjectOrNull(body)?.str("token")?.takeIf { it.isNotBlank() }?.let { newToken ->
                        saveSession(ctx.applicationContext, current.copy(token = newToken))
                    }
                }
                Unit
            }
        }

    /**
     * 修改邮箱：POST /auth/change-email {newEmail,password}（密码确认身份），Bearer 鉴权。
     * 成功后本地 user.email 更新（服务端返回 user/token 则优先采用）。
     */
    suspend fun changeEmail(ctx: Context, newEmail: String, password: String): Result<AuthSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                val current = loadSession(ctx.applicationContext) ?: error("未登录")
                val payload = buildJsonObject {
                    put("newEmail", newEmail.trim())
                    put("password", password)
                }
                val req = applyAuth(
                    Request.Builder()
                        .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/auth/change-email")
                        .post(payload.toString().toRequestBody("application/json".toMediaType())),
                    ctx
                ).build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "修改邮箱失败"))
                    val obj = parseJsonObjectOrNull(body)
                    val serverUser = obj?.objOrNull("user")?.let { parseUser(it) }
                    val user = if (serverUser != null && serverUser.id.isNotBlank()) serverUser
                    else current.user.copy(email = newEmail.trim())
                    val token = obj?.str("token")?.takeIf { it.isNotBlank() } ?: current.token
                    AuthSession(user = user, token = token).also { saveSession(ctx.applicationContext, it) }
                }
            }
        }

    /**
     * 忘记密码（当前无验证码版）：POST /auth/forgot-password {email,newPassword,code}，无需鉴权。
     * code 预留：后端接入邮箱验证码后传验证码，客户端结构不变。
     */
    suspend fun forgotPassword(email: String, newPassword: String, code: String = ""): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = buildJsonObject {
                    put("email", email.trim())
                    put("newPassword", newPassword)
                    if (code.isNotBlank()) put("code", code)
                }
                val req = Request.Builder()
                    .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/auth/forgot-password")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "重置密码失败"))
                }
                Unit
            }
        }

    /**
     * 注销账号：请求服务端永久删除当前用户及其全部数据（DELETE /auth/account，Bearer 鉴权）。
     * 成功（2xx）后清除本地会话；失败抛出带 detail 的错误信息。
     */
    suspend fun deleteAccount(ctx: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = applyAuth(
                Request.Builder()
                    .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/auth/account")
                    .delete(),
                ctx
            ).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "注销失败"))
            }
            clearSession(ctx.applicationContext)
        }
    }

    suspend fun refreshMe(ctx: Context): AuthSession? = withContext(Dispatchers.IO) {
        val current = loadSession(ctx.applicationContext) ?: return@withContext null
        val req = applyAuth(
            Request.Builder()
                .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/auth/me")
                .get(),
            ctx
        ).build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "登录状态已失效"))
                val user = parseUser(parseJsonObjectBody(body, "用户信息响应不是 JSON").objOrNull("user") ?: JsonObject(emptyMap()))
                current.copy(user = user).also { saveSession(ctx.applicationContext, it) }
            }
        }.getOrElse {
            clearSession(ctx.applicationContext)
            null
        }
    }

    /**
     * 滑动续期：用当前有效 token 调 /auth/refresh 换一枚 exp 顺延的新 token 并持久化。
     * 成功返回新会话；失败（token 已过期/被 logout 失效/网络错误）清除本地会话并返回 null，
     * 由调用方回落到登录页 ——即「token 失效自动重登」。
     */
    suspend fun refreshToken(ctx: Context): AuthSession? = withContext(Dispatchers.IO) {
        loadSession(ctx.applicationContext) ?: return@withContext null
        val req = applyAuth(
            Request.Builder()
                .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/auth/refresh")
                .post("{}".toRequestBody("application/json".toMediaType())),
            ctx
        ).build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "登录状态已失效"))
                val obj = parseJsonObjectBody(body, "续期响应不是 JSON")
                val session = AuthSession(
                    user = parseUser(obj.objOrNull("user") ?: JsonObject(emptyMap())),
                    token = obj.str("token")
                )
                if (session.token.isBlank() || session.user.id.isBlank()) error("续期响应缺少登录凭证")
                saveSession(ctx.applicationContext, session)
                session
            }
        }.getOrElse {
            clearSession(ctx.applicationContext)
            null
        }
    }

    private suspend fun authenticate(
        ctx: Context,
        path: String,
        email: String,
        password: String,
        displayName: String
    ): AuthSession = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("email", email.trim())
            put("password", password)
            if (displayName.trim().isNotBlank()) put("displayName", displayName.trim())
        }
        val req = Request.Builder()
            .url(BuildConfig.API_BASE_URL.trimEnd('/') + path)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "账号请求失败"))
            val obj = parseJsonObjectBody(body, "账号响应不是 JSON")
            val session = AuthSession(
                user = parseUser(obj.objOrNull("user") ?: JsonObject(emptyMap())),
                token = obj.str("token")
            )
            if (session.token.isBlank() || session.user.id.isBlank()) error("账号响应缺少登录凭证")
            saveSession(ctx.applicationContext, session)
            session
        }
    }

    private fun parseSession(obj: JsonObject): AuthSession =
        AuthSession(
            user = parseUser(obj.objOrNull("user") ?: JsonObject(emptyMap())),
            token = obj.str("token")
        ).takeIf { it.token.isNotBlank() && it.user.id.isNotBlank() } ?: error("invalid session")

    private fun parseUser(obj: JsonObject): AuthUser =
        AuthUser(
            id = obj.str("id"),
            email = obj.str("email"),
            displayName = obj.str("displayName"),
            avatar = obj.str("avatar"),
            createdAt = obj.long("createdAt", 0L),
            updatedAt = obj.long("updatedAt", 0L)
        )

    private fun sessionToJson(session: AuthSession): JsonObject = buildJsonObject {
        put("token", session.token)
        putJsonObject("user") {
            put("id", session.user.id)
            put("email", session.user.email)
            put("displayName", session.user.displayName)
            put("avatar", session.user.avatar)
            put("createdAt", session.user.createdAt)
            put("updatedAt", session.user.updatedAt)
        }
    }

    private fun parseJsonObjectBody(body: String, fallback: String): JsonObject =
        parseJsonObjectOrNull(body) ?: error("$fallback：${responsePreview(body)}")

    private fun httpErrorMessage(code: Int, body: String, fallback: String): String {
        val detail = parseJsonObjectOrNull(body)?.str("detail")?.takeIf { it.isNotBlank() }
        return detail ?: "$fallback (HTTP $code)：${responsePreview(body)}"
    }

    private fun responsePreview(body: String): String {
        val text = body.replace(Regex("""\s+"""), " ").trim()
        return text.ifBlank { "响应为空" }.take(180)
    }
}
