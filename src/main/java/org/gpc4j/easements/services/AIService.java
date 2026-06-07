package org.gpc4j.easements.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import jakarta.annotation.PreDestroy;

import org.gpc4j.easements.model.AIPrompt;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.safari.SafariDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Submits prompts to ChatGPT via a Safari WebDriver session and returns the
 * response text.
 *
 * <p>The Safari session is opened lazily on the first call and reused for the
 * lifetime of the application. Because SafariDriver uses the real Safari user
 * profile, existing ChatGPT login cookies are honoured automatically. If the
 * browser lands on a login page the service waits up to five minutes for the
 * user to authenticate manually.
 *
 * <p>Each call starts a fresh chat by navigating to the ChatGPT root URL,
 * so no conversation context bleeds between calls.
 *
 * <p><strong>Prerequisite:</strong> run {@code safaridriver --enable} once in
 * a terminal to allow WebDriver automation of Safari.
 */
@Service
public class AIService {

  private static final Logger log = LoggerFactory.getLogger(AIService.class);

  private static final String CHATGPT_URL = "https://chatgpt.com";

  /** Maximum time to wait for the ChatGPT prompt input to become ready. */
  private static final Duration PAGE_TIMEOUT = Duration.ofMinutes(5);

  /** Maximum time to wait for ChatGPT to finish streaming a response. */
  private static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(3);

  // CSS selectors for ChatGPT UI elements.
  private static final By PROMPT_INPUT = By.id("prompt-textarea");
  private static final By SEND_BUTTON = By
    .cssSelector("button[data-testid='send-button']");
  private static final By STOP_BUTTON = By
    .cssSelector("button[data-testid='stop-button']");
  private static final By ATTACH_BUTTON = By
    .cssSelector("button[aria-label='Attach files']");
  private static final By FILE_INPUT = By.cssSelector("input[type='file']");
  private static final By ASSISTANT_MESSAGE = By
    .cssSelector("[data-message-author-role='assistant']");

  private WebDriver driver;

  /**
   * Submits {@code prompt} to ChatGPT and returns the full response text.
   *
   * <p>If {@link AIPrompt#getImage()} is non-null and non-empty the image is
   * written to a temporary file, uploaded via ChatGPT's attachment button, and
   * the temp file is deleted once the upload is accepted.
   *
   * @param prompt the text (and optional image) to send
   * @return the response text produced by ChatGPT
   * @throws IOException if a temporary file cannot be created, or if the
   *                     WebDriver interaction fails
   */
  public synchronized String query(AIPrompt prompt) throws IOException {

    try {
      ensureDriver();

      // Start a fresh conversation.
      driver.get(CHATGPT_URL);

      WebDriverWait wait = new WebDriverWait(driver, PAGE_TIMEOUT);
      WebElement input = wait
        .until(ExpectedConditions.elementToBeClickable(PROMPT_INPUT));

      if (prompt.getImage() != null && prompt.getImage().length > 0) {
        uploadImage(wait, prompt.getImage());
      }

      new Actions(driver).click(input).sendKeys(prompt.getText()).perform();

      WebElement sendBtn = wait
        .until(ExpectedConditions.elementToBeClickable(SEND_BUTTON));
      sendBtn.click();
      log.debug("Prompt submitted; waiting for response stream to start");

      // Wait for ChatGPT to begin streaming (stop button appears).
      new WebDriverWait(driver, Duration.ofSeconds(30))
        .until(ExpectedConditions.presenceOfElementLocated(STOP_BUTTON));

      // Wait for streaming to finish (stop button disappears).
      new WebDriverWait(driver, RESPONSE_TIMEOUT)
        .until(ExpectedConditions.invisibilityOfElementLocated(STOP_BUTTON));

      log.debug("Response stream complete; reading text");
      List<WebElement> messages = driver.findElements(ASSISTANT_MESSAGE);
      if (messages.isEmpty()) {
        throw new IOException("No assistant response found in ChatGPT page");
      }
      return messages.get(messages.size() - 1).getText();

    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException("ChatGPT interaction failed: " + e.getMessage(), e);
    }
  }


  /**
   * Uploads image bytes to ChatGPT by writing them to a temp file and
   * sending the path to the hidden file input. The temp file is deleted once
   * ChatGPT acknowledges the upload.
   *
   * @param wait      a {@link WebDriverWait} configured with the page timeout
   * @param imageBytes raw image bytes (PNG or JPEG)
   * @throws IOException if the temp file cannot be created or written
   */
  private void uploadImage(WebDriverWait wait, byte[] imageBytes)
    throws IOException {

    Path tmp = Files.createTempFile("ai-prompt-", ".png");
    try {
      Files.write(tmp, imageBytes);
      log.debug("Uploading image ({} bytes) from temp file {}", imageBytes.length,
        tmp);

      WebElement attachBtn = wait
        .until(ExpectedConditions.elementToBeClickable(ATTACH_BUTTON));
      attachBtn.click();

      WebElement fileInput = wait
        .until(ExpectedConditions.presenceOfElementLocated(FILE_INPUT));
      fileInput.sendKeys(tmp.toAbsolutePath().toString());

      // Attempt to confirm the upload was accepted via a preview element; not
      // all ChatGPT versions show one, so a timeout here is non-fatal.
      try {
        new WebDriverWait(driver, Duration.ofSeconds(10))
          .until(ExpectedConditions.presenceOfElementLocated(
            By.cssSelector("[data-testid='attachment-preview']")));
      } catch (TimeoutException e) {
        log.debug("No attachment preview detected; assuming upload accepted");
      }

    } finally {
      Files.deleteIfExists(tmp);
    }
  }


  /**
   * Lazily initialises the Safari WebDriver session. Navigates to ChatGPT and
   * waits up to five minutes for the prompt input to become available, giving
   * the user time to log in if the Safari session has no active ChatGPT cookie.
   */
  private void ensureDriver() {

    if (driver != null) {
      return;
    }

    log.info("Opening Safari WebDriver session");
    driver = new SafariDriver();
    driver.manage().window().maximize();
    driver.get(CHATGPT_URL);

    log.info(
      "Waiting for ChatGPT to be ready (log in if prompted, " + "up to 5 minutes)…");
    new WebDriverWait(driver, PAGE_TIMEOUT)
      .until(ExpectedConditions.presenceOfElementLocated(PROMPT_INPUT));
    log.info("ChatGPT session ready");
  }


  /**
   * Quits the Safari WebDriver session when the Spring context closes.
   */
  @PreDestroy
  public void shutdown() {

    if (driver != null) {
      log.info("Closing ChatGPT Safari WebDriver session");
      driver.quit();
      driver = null;
    }
  }

}
