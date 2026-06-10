package org.gpc4j.easements.model;

public record AIResponse(
  /*
   * Text response from AI.
   */
  String text,
  /*
   *
   * Tle class name of the {@link org.gpc4j.easements.services.AIService}
   * implementation that extracted this page's text, e.g.
   * {@code GeminiService}. May differ from the document-level value when a
   * per-page fallback (e.g. RECITATION handling) used a different provider.
   */
  String aiServiceName,

  /*
   * Model identifier reported by the AI service for this page, e.g.
   * {@code gemini-3.1-flash-lite} or {@code claude-haiku-4-5-20251001}.
   */
  String aiModel,

  /*
   * AI confidence score for this page (0–100).
   */
  float confidence) {

}
