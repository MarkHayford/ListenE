# ListenE

面向英语学习的 Android AI Agent。用自然语言提出学习需求，生成听力素材、练习卡片和音频，再把精听、练习和错因复盘收进工作区。

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)
![NestJS](https://img.shields.io/badge/NestJS-E0234E?logo=nestjs&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?logo=postgresql&logoColor=white)

## 仓库结构

```text
android/   Kotlin + Jetpack Compose 客户端
backend/   NestJS 后端，听力生成、卡片、工作区和 TTS
```

## 能做什么

- 用对话提出听力练习需求
- 生成对话、题目和音频
- 精听回放、练习卡片、词句复盘
- 把学习过程沉淀成可持续的工作区

## 运行

### 后端

```bash
cd backend
cp .env.example .env
npm install
npm run dev
```

需要 PostgreSQL，以及 `.env` 里的模型与 TTS 配置。

### Android

用 Android Studio 打开 `android/`。客户端连到本地或你自己配置的后端地址。

## 说明

独立开发。签名密钥、口令和 `.env` 不入库。
