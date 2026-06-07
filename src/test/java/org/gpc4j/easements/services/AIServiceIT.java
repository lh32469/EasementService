package org.gpc4j.easements.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;

import org.gpc4j.easements.model.AIPrompt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@link AIService}. Opens a real Safari WebDriver
 * session and submits a prompt to ChatGPT. Requires Safari WebDriver to be
 * enabled ({@code safaridriver --enable}) and an active ChatGPT login in
 * Safari before running.
 *
 * <p>Run manually with:
 * {@code ./mvnw test -Dtest=AIServiceIT}
 */
class AIServiceIT {

  private final AIService aiService = new AIService();

  /** Closes the Safari WebDriver session after each test. */
  @AfterEach
  void tearDown() {

    aiService.shutdown();
  }


  /**
   * Sends {@code file.png} from test resources together with the text prompt
   * {@code "read the text from this image"} to ChatGPT and asserts that a
   * non-blank response is returned.
   *
   * @throws Exception if the image cannot be loaded or the WebDriver
   *                   interaction fails
   */
  @Test
  void queryChatGPTWithImageAndText() throws Exception {

    byte[] image;
    try (InputStream in = getClass().getResourceAsStream("/file.png")) {
      assertNotNull(in, "file.png must be present in src/test/resources");
      image = in.readAllBytes();
    }

    AIPrompt prompt = AIPrompt.builder().text("read the text from this image")
      .image(image).build();

    String response = aiService.query(prompt);

    System.out.println("ChatGPT response:\n" + response);
    assertNotNull(response, "Response must not be null");
    assertFalse(response.isBlank(), "Response must not be blank");
  }

}
