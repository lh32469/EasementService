package org.gpc4j.easements.services;

import static org.gpc4j.easements.controller.EasementController.OCR_PROMPT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;

import org.gpc4j.easements.model.AIPrompt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import net.ravendb.client.documents.IDocumentStore;

/**
 * Integration test for the {@link AIService} interface as implemented by
 * {@link GeminiService}. Sends real HTTP requests to the Google Gemini API
 * using the Spring application context. The API key is read from
 * {@code gemini.api.key} in {@code application.yaml}.
 *
 * <p>{@link IDocumentStore} is mocked so RavenDB does not need to be
 * running. The {@code ravendb.urls} placeholder is satisfied via the
 * {@code properties} attribute to prevent conte  xt startup failure.
 *
 * <p>Run manually with:
 * {@code ./mvnw test -Dtest=AIServiceIT}
 */
@SpringBootTest(properties = "ravendb.urls=http://localhost:8080")
class AIServiceIT {

  @Autowired
  @Qualifier("geminiService")
  private AIService aiService;

  /**
   * Sends {@code image.jpg} from test resources together with the text prompt
   * {@code "read the text from this image"} to Gemini and asserts that a
   * non-blank response is returned.
   *
   * @throws Exception if the image cannot be loaded or the Gemini API call
   *                   fails
   */
  @Test
  void queryGeminiWithImageAndText() throws Exception {

    byte[] image;
    try (InputStream in = getClass().getResourceAsStream("/image.jpg")) {
      assertNotNull(in, "image.jpg must be present in src/test/resources");
      image = in.readAllBytes();
    }

    AIPrompt prompt = AIPrompt.builder().text(OCR_PROMPT).image(image).build();

    String response = aiService.query(prompt);

    System.out.println("Gemini response:\n" + response);
    assertNotNull(response, "Response must not be null");
    assertFalse(response.isBlank(), "Response must not be blank");
  }


  /**
   * Sends {@code cursive.png} from test resources together with the text
   * prompt {@code "read the text from this image"} to Gemini and asserts that
   * a non-blank response is returned.
   *
   * @throws Exception if the image cannot be loaded or the Gemini API call
   *                   fails
   */
  @Test
  void cursiveTest() throws Exception {

    byte[] image;
    try (InputStream in = getClass().getResourceAsStream("/cursive.png")) {
      assertNotNull(in, "cursive.png must be present in src/test/resources");
      image = in.readAllBytes();
    }

    AIPrompt prompt = AIPrompt
      .builder()
      .text("read the text from this image")
      .image(image)
      .build();

    String response = aiService.query(prompt);

    System.out.println("Gemini response:\n" + response);
    assertNotNull(response, "Response must not be null");
    assertFalse(response.isBlank(), "Response must not be blank");
  }

}
