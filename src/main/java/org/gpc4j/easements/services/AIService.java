package org.gpc4j.easements.services;

import java.io.IOException;

import org.gpc4j.easements.model.AIPrompt;

/**
 * Contract for submitting prompts to an AI language model and returning the
 * response text.
 *
 * <p>Implementations target specific providers (e.g. {@link GeminiService}
 * for Google Gemini, future implementations for OpenAI, etc.). Callers
 * depend only on this interface so the underlying provider can be swapped
 * without changes to the calling code.
 */
public interface AIService {

  /**
   * Submits {@code prompt} to the AI provider and returns the full response
   * text. When {@link AIPrompt#getImage()} is non-null and non-empty the
   * image is included alongside the text, enabling vision-capable providers
   * to analyse the image.
   *
   * @param prompt the text (and optional image) to send; must not be null
   * @return the response text; never null or blank on success
   * @throws IOException if the request cannot be completed or the provider
   *                     returns an error response
   */
  String query(AIPrompt prompt) throws IOException;


  /**
   * Returns the model identifier used by this implementation when calling the
   * AI provider (e.g. {@code "gemini-1.5-flash"}, {@code "gpt-4o"},
   * {@code "claude-haiku-4-5-20251001"}).
   *
   * @return the model ID string; never null
   */
  String getModel();

}
