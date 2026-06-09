package org.gpc4j.easements.model;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * RavenDB document that holds the OCR-extracted text from a scanned easement
 * PDF. Stored under the original PDF filename as its document key. Rendered
 * page images are stored as named attachments ({@code page-1.png}, …).
 *
 * <p>Full-text search queries {@code pages[].lines} directly via RavenDB's
 * nested-array indexing; there is no separate denormalised {@code lines} field.
 *
 * <p>{@link JsonIgnoreProperties} is set to ignore unknown fields so that
 * callback payloads from older PaddleOCR service versions that still include
 * a {@code lines} field are accepted without error.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EasementDoc {

  /** RavenDB document identifier — set to the original PDF filename. */
  private String id;

  /** Original filename of the uploaded PDF. */
  private String filename;

  /**
   * Rich per-page OCR results, one entry per rendered PDF page. Each entry
   * carries that page's text lines and Tesseract/PaddleOCR confidence score.
   * Full-text search is performed against {@code pages[].lines}.
   */
  private List<EasementPage> pages;

  /** Number of pages rendered from the PDF; equals {@code pages.size()}. */
  private int pageCount;

  /** UTC timestamp recorded when the document was first ingested. */
  private Instant createdAt;

  /**
   * Simple class name of the {@link org.gpc4j.easements.services.AIService}
   * implementation that produced this document (e.g. {@code "AnthropicService"}).
   */
  private String aiServiceName;

  /**
   * Model identifier used by the AI provider when extracting text from page
   * images (e.g. {@code "claude-haiku-4-5-20251001"}, {@code "gpt-4o"},
   * {@code "gemini-1.5-flash"}).
   */
  private String aiModel;

}
