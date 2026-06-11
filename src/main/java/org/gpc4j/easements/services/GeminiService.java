package org.gpc4j.easements.services;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.gpc4j.easements.model.AIPrompt;
import org.gpc4j.easements.model.AIResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link AIService} implementation that submits prompts to the Google Gemini
 * API and returns the response text.
 *
 * <p>Both text-only and multimodal (text + image) prompts are supported.
 * Images are base64-encoded and sent as {@code inlineData} parts alongside
 * the text prompt, enabling Gemini's vision capabilities. PNG and JPEG
 * formats are auto-detected from the image byte header.
 *
 * <p>The API key is read from the {@code gemini.api.key} application property.
 * The HTTP client is forced to HTTP/1.1 to avoid 503 responses the Gemini API
 * returns for large multimodal payloads over HTTP/2.
 *
 * <p>When Gemini returns a {@code RECITATION} finish reason (indicating the
 * response may recite copyrighted material) the same prompt is forwarded to
 * {@link AnthropicService} as a fallback so the page is not silently skipped.
 */
@Service
public class GeminiService implements AIService {

  private static final Logger log = LoggerFactory.getLogger(GeminiService.class);

  private static final String MODEL = "gemini-3.1-flash-lite";

  private static final String GEMINI_URL = "https://generativelanguage.googleapis.com"
    + "/v1beta/models/" + MODEL + ":generateContent";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http;
  private final String apiKey;
  private final AnthropicService anthropicService;

  /**
   * Creates the service with the given Gemini API key and Anthropic fallback.
   *
   * @param apiKey           the {@code X-goog-api-key} header value sent with
   *                         every request to the Gemini API
   * @param anthropicService fallback service used when Gemini returns a
   *                         {@code RECITATION} finish reason with no partial
   *                         text
   */
  public GeminiService(@Value("${gemini.api.key}") String apiKey,
    AnthropicService anthropicService) {

    this.apiKey = apiKey;
    this.anthropicService = anthropicService;
    // HTTP/1.1 avoids 503s the Gemini API returns for large payloads over HTTP/2
    this.http = HttpClient.newBuilder().version(Version.HTTP_1_1).build();
  }


  /**
   * {@inheritDoc}
   */
  @Override
  public String getModel() {

    return MODEL;
  }


  /**
   * {@inheritDoc}
   *
   * <p>When Gemini returns a {@code RECITATION} finish reason the fallback
   * {@link AnthropicService#queryResponse} result is returned unchanged so
   * the {@link AIResponse#aiServiceName()} accurately reflects the provider
   * that produced the text.
   */
  @Override
  public AIResponse queryResponse(AIPrompt prompt) throws IOException {

    List<Map<String, Object>> parts = new LinkedList<>();

    if (prompt.getImage() != null && prompt.getImage().length > 0) {
      String mimeType = detectMimeType(prompt.getImage());
      String b64 = Base64.getEncoder().encodeToString(prompt.getImage());
      parts.add(Map.of("inlineData", Map.of("mimeType", mimeType, "data", b64)));
      log
        .debug("Attaching {} image ({} bytes) to Gemini request", mimeType,
          prompt.getImage().length);
    }

    parts.add(Map.of("text", prompt.getText()));

    Map<String, Object> body = Map.of("contents", List.of(Map.of("parts", parts)));

    String requestJson = MAPPER.writeValueAsString(body);
    log.debug("Sending Gemini request ({} chars)", requestJson.length());

    HttpRequest request = HttpRequest
      .newBuilder()
      .uri(URI.create(GEMINI_URL))
      .header("Content-Type", "application/json")
      .header("X-goog-api-key", apiKey)
      .POST(HttpRequest.BodyPublishers.ofString(requestJson))
      .build();

    HttpResponse<String> response = send(request);

    JsonNode root = MAPPER.readTree(response.body());
    String finishReason = root.at("/candidates/0/finishReason").asText();

    if ("RECITATION".equals(finishReason)) {
      log.warn("Gemini RECITATION; response: {}", response.body());
      return anthropicService.queryResponse(prompt);
    }

    JsonNode text = root.at("/candidates/0/content/parts/0/text");
    if (text.isMissingNode()) {
      throw new IOException(
        "Unexpected Gemini response structure: " + response.body());
    }
    return new AIResponse(text.asText(), getClass().getSimpleName(), MODEL, 0.0f);
  }


  /**
   * Sends {@code request} and retries up to two times on a 503 response,
   * waiting two seconds between attempts. The Gemini API returns 503 under
   * momentary load spikes and asks callers to retry.
   *
   * @param request the prepared HTTP request
   * @return the HTTP response with status 200
   * @throws IOException if the request fails, is interrupted, or returns a
   *                     non-200 status after all retry attempts
   */
  private HttpResponse<String> send(HttpRequest request) throws IOException {

    int attempts = 0;
    while (true) {
      HttpResponse<String> response;
      try {
        response = http.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Gemini API request interrupted", e);
      }

      log.debug("Gemini HTTP {}", response.statusCode());
      if (response.statusCode() == 429) {
        throw new QuotaExceededException("Gemini API error 429: " + response.body());
      }
      if (response.statusCode() != 503 || ++attempts >= 3) {
        if (response.statusCode() != 200) {
          throw new IOException(
            "Gemini API error " + response.statusCode() + ": " + response.body());
        }
        return response;
      }

      log.debug("Gemini 503 (attempt {}); retrying in 2 s", attempts);
      try {
        Thread.sleep(2_000);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new IOException("Gemini retry interrupted", ie);
      }
    }
  }


  /**
   * Returns {@code "image/png"} when {@code bytes} begins with the PNG magic
   * header ({@code \x89PNG}), {@code "image/jpeg"} otherwise.
   *
   * @param bytes raw image data
   * @return the detected MIME type string
   */
  private String detectMimeType(byte[] bytes) {

    if (bytes.length >= 4 && bytes[0] == (byte) 0x89 && bytes[1] == 'P'
      && bytes[2] == 'N' && bytes[3] == 'G') {
      return "image/png";
    }
    return "image/jpeg";
  }

}
