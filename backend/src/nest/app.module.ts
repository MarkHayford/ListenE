import { Module } from "@nestjs/common";
import {
  AgentController,
  AuthController,
  DailyChallengeController,
  HealthController,
  LibraryController,
  ListeningController,
  MetricsController,
  PlanController,
  ReviewController,
  StudyPlansController,
  StudyProgressController,
  TextController,
  VocabController
} from "./listene.controller";

@Module({
  controllers: [
    AgentController,
    AuthController,
    DailyChallengeController,
    HealthController,
    LibraryController,
    ListeningController,
    MetricsController,
    PlanController,
    ReviewController,
    StudyPlansController,
    StudyProgressController,
    TextController,
    VocabController
  ]
})
export class AppModule {}
