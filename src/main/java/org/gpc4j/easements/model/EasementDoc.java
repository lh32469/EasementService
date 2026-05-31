package org.gpc4j.easements.model;

import java.time.Instant;
import java.util.List;

import lombok.Data;

/**
 * RavenDB document that holds the OCR-extracted text from a scanned easement
 * PDF. Stored under the original PDF filename as its document key. Rendered
 * page images are stored as named attachments ({@code page-1.png}, …).
 *
 * <p>Per-page text and confidence are available in {@link #pages}. The flat
 * {@link #lines} field is a denormalised concatenation of all page lines kept
 * solely to support the RQL {@code search(lines, …)} query without requiring
 * nested-field indexing.
 */
@Data
public class EasementDoc {

  /** RavenDB document identifier — set to the original PDF filename. */
  private String id;

  /** Original filename of the uploaded PDF. */
  private String filename;

  /**
   * Rich per-page OCR results, one entry per rendered PDF page. Each entry
   * carries that page's text lines and Tesseract confidence score.
   */
  private List<EasementPage> pages;

  /**
   * Denormalised flat list of every text line across all pages, in
   * page-then-top-to-bottom order. Used by the RQL {@code search(lines, …)}
   * query so that full-text search covers the entire document without
   * requiring nested-field indexing.
   */
  private List<String> lines;

  /** Number of pages rendered from the PDF; equals {@code pages.size()}. */
  private int pageCount;

  /** UTC timestamp recorded when the document was first ingested. */
  private Instant createdAt;

}
