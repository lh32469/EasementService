package org.gpc4j.easements.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OCR result for a single rendered page of a scanned easement PDF.
 * Stored as a nested object inside {@link EasementDoc}.
 *
 * <p>{@link NoArgsConstructor} is required for RavenDB Jackson deserialization.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EasementPage {

  /**
   * 1-based page index within the source PDF.
   */
  private int pageNumber;

  /**
   * Non-blank text lines extracted from this page.
   */
  private List<String> lines;

  /**
   * AI confidence score for this page (0–100).
   */
  private float confidence;

  /**
   * Simple class name of the {@link org.gpc4j.easements.services.AIService}
   * implementation that extracted this page's text, e.g.
   * {@code GeminiService}. May differ from the document-level value when a
   * per-page fallback (e.g. RECITATION handling) used a different provider.
   */
  private String aiServiceName;

  /**
   * Model identifier reported by the AI service for this page, e.g.
   * {@code gemini-3.1-flash-lite} or {@code claude-haiku-4-5-20251001}.
   */
  private String aiModel;

}
