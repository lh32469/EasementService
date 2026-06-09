package org.gpc4j.easements.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Input payload for the AI query service.
 *
 * <p>At minimum a {@link #text} prompt must be supplied. An optional
 * {@link #image} (raw PNG/JPEG bytes) may be included; when present it is
 * uploaded as an attachment alongside the text prompt.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIPrompt {

  /** The text prompt to send to the AI. Must not be null or blank. */
  private String text;

  /**
   * Optional image to attach to the prompt (PNG or JPEG bytes).
   * {@code null} means text-only.
   */
  private byte[] image;

}
