package org.gpc4j.easements.model;

import java.time.Instant;
import java.util.List;

import lombok.Data;

/**
 * RavenDB document that holds the OCR-extracted text lines from a scanned
 * easement PDF. The document is stored under the original PDF filename as its
 * key. The rendered page images are stored as named attachments
 * ({@code page-1.png}, {@code page-2.png}, …) on the same document.
 */
@Data
public class EasementDoc {

  /** RavenDB document identifier — set to the original PDF filename. */
  private String id;

  /** Original filename of the uploaded PDF. */
  private String filename;

  /**
   * Text lines extracted by AWS Textract from every page of the PDF, in
   * page-then-top-to-bottom order.
   */
  private List<String> lines;

  /** UTC timestamp recorded when the document was first ingested. */
  private Instant createdAt;

}
