package org.gpc4j.easements.services;

import java.util.LinkedList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound configuration properties for the Google Gemini API.
 *
 * <p>Reads the {@code gemini.api.keys} list from {@code application.yaml}.
 * Each entry may be a Jasypt-encrypted {@code ENC(...)} value; decryption is
 * handled transparently by {@code jasypt-spring-boot} at binding time.
 *
 * <p>The list is consumed by {@link GeminiService}, which rotates through the
 * keys round-robin to distribute quota usage across accounts.
 */
@Component
@ConfigurationProperties(prefix = "gemini.api")
public class GeminiProperties {

  /** Ordered list of Gemini API keys rotated round-robin across requests. */
  private List<String> keys = new LinkedList<>();

  /**
   * Returns the configured API keys.
   *
   * @return the list of API keys; never {@code null}
   */
  public List<String> getKeys() {

    return keys;
  }


  /**
   * Sets the API key list; called by Spring Boot's property binder.
   *
   * @param keys the keys to use
   */
  public void setKeys(List<String> keys) {

    this.keys = keys;
  }

}
