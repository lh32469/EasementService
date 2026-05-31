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

  /** 1-based page index within the source PDF. */
  private int pageNumber;

  /** Non-blank text lines extracted by Tesseract from this page. */
  private List<String> lines;

  /**
   * Mean Tesseract word-level confidence for this page (0–100).
   * Derived from {@code getWords(RIL_WORD)} after {@code doOCR()}.
   */
  private float confidence;

}
