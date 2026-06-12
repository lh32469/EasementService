package org.gpc4j.easements.tasks;

import static org.gpc4j.easements.controller.EasementController.CONFIDENCE_PATTERN;
import static org.gpc4j.easements.controller.EasementController.OCR_PROMPT;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;

import org.gpc4j.easements.model.AIPrompt;
import org.gpc4j.easements.model.AIResponse;
import org.gpc4j.easements.model.EasementDoc;
import org.gpc4j.easements.model.EasementPage;
import org.gpc4j.easements.services.AIService;
import org.gpc4j.easements.services.QuotaExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.ravendb.client.documents.IDocumentStore;
import net.ravendb.client.documents.operations.attachments.CloseableAttachmentResult;
import net.ravendb.client.documents.session.IDocumentSession;

/**
 * Background task that finds one {@link EasementDoc} containing at least one
 * {@link EasementPage} whose {@code aiServiceName} field is absent, null, or
 * blank, fetches the corresponding page-image attachment, submits it to
 * {@link AIService} for OCR, and updates the page in place with the response.
 *
 * <p>Only the first incomplete page found is updated per invocation to limit
 * AI API pressure.
 *
 * <p>Waits 15 minutes after each run before starting the next, preventing
 * concurrent invocations. Active only under the {@code k8s} or {@code test}
 * Spring profile.
 */
@Profile({"k8s", "test"})
@Component
public class PageRefreshTask {

  private static final Logger log = LoggerFactory.getLogger(PageRefreshTask.class);

  private static final long QUOTA_PAUSE_MS = 10 * 60 * 1000L;

  private final IDocumentStore store;
  private final AIService aiService;

  /** Epoch-ms timestamp before which processing is suppressed after a 429. */
  private volatile long pauseUntil = 0L;

  /**
   * Constructs the task with the RavenDB document store and the primary AI
   * service.
   *
   * @param store     the singleton RavenDB document store; sessions are opened
   *                  per-run because this task runs outside an HTTP request scope
   * @param aiService the AI vision service used to extract text from page images
   */
  public PageRefreshTask(IDocumentStore store, AIService aiService) {

    this.store = store;
    this.aiService = aiService;
  }


  /**
   * Finds one {@link EasementDoc} with an incomplete {@link EasementPage},
   * delegates processing to {@link #refreshPage}, and persists the result.
   *
   * <p>Delay is configured via {@code page-refresh.delay-minutes} (default
   * 15 minutes). Waits that duration after each invocation completes before
   * running again, so concurrent executions cannot occur.
   */
  @Scheduled(fixedDelayString = "#{${page-refresh.delay-minutes:15} * 60000}")
  public void refreshOne() {

    long now = System.currentTimeMillis();
    if (now < pauseUntil) {
      log
        .debug("PageRefreshTask paused for {} more seconds due to quota limit",
          (pauseUntil - now) / 1000);
      return;
    }

    log.debug("PageRefreshTask: searching for EasementDoc with incomplete page");

    try (IDocumentSession session = store.openSession()) {

      EasementDoc doc = session
        .query(EasementDoc.class)
        .whereExists("pages")
        .andAlso()
        .openSubclause()
        .negateNext()
        .whereExists("pages[].aiServiceName")
        .orElse()
        .whereEquals("pages[].aiServiceName", (Object) null)
        .orElse()
        .whereEquals("pages[].aiServiceName", "")
        .closeSubclause()
        .firstOrDefault();

      if (doc == null) {
        log.debug("PageRefreshTask: all pages are up to date");
        return;
      }

      if (doc.getPages() == null || doc.getPages().isEmpty()) {
        log.debug("PageRefreshTask: '{}' has no pages to refresh", doc.getId());
        return;
      }

      EasementPage page = doc
        .getPages()
        .stream()
        .filter(p -> p.getAiServiceName() == null || p.getAiServiceName().isBlank())
        .findFirst()
        .orElse(null);

      if (page == null) {
        log
          .debug("PageRefreshTask: no incomplete page found in '{}' (index stale?)",
            doc.getId());
        return;
      }

      log.debug("Refreshing page {} of '{}'", page.getPageNumber(), doc.getId());
      refreshPage(session, doc, page);
      session.saveChanges();
      log
        .info("Refreshed page {}/{} of '{}' via {}/{}", page.getPageNumber(),
          doc.getPageCount(), doc.getId(), page.getAiServiceName(),
          page.getAiModel());

    } catch (QuotaExceededException e) {
      pauseUntil = System.currentTimeMillis() + QUOTA_PAUSE_MS;
      log.warn("Quota exceeded; pausing PageRefreshTask for 10 minutes");
    } catch (Exception e) {
      log.error("PageRefreshTask failed unexpectedly", e);
    }
  }


  /**
   * Fetches the page-image attachment for {@code page} from RavenDB, submits
   * it to {@link AIService}, and updates {@code page} in place with the
   * extracted text, confidence score, and AI provenance fields.
   *
   * @param session open RavenDB session used to fetch the attachment
   * @param doc     the parent document; used to look up the attachment by page
   *                number
   * @param page    the page to refresh; mutated in place on success
   * @throws IOException if the attachment is missing or the AI call fails
   */
  void refreshPage(IDocumentSession session, EasementDoc doc, EasementPage page)
    throws IOException {

    String attachmentName = "page-" + page.getPageNumber() + ".png";

    try (CloseableAttachmentResult att = session
      .advanced()
      .attachments()
      .get(doc.getId(), attachmentName)) {

      if (att == null) {
        throw new IOException(
          "Attachment '" + attachmentName + "' not found for '" + doc.getId() + "'");
      }

      byte[] imageBytes = att.getData().readAllBytes();

      AIPrompt prompt = AIPrompt
        .builder()
        .text(OCR_PROMPT)
        .image(imageBytes)
        .build();

      AIResponse aiResponse = aiService.queryResponse(prompt);
      log
        .debug("Page {}: AI returned {} chars via {}", page.getPageNumber(),
          aiResponse.text().length(), aiResponse.aiServiceName());

      float confidence = 0f;
      List<String> lines = new LinkedList<>();
      for (String raw : aiResponse.text().split("\n")) {
        String trimmed = raw.trim();
        if (trimmed.isBlank()) {
          continue;
        }
        Matcher cm = CONFIDENCE_PATTERN.matcher(trimmed);
        if (cm.find()) {
          confidence = Float.parseFloat(cm.group(1));
        } else {
          lines.add(trimmed);
        }
      }

      page.setLines(lines);
      page.setConfidence(confidence);
      page.setAiServiceName(aiResponse.aiServiceName());
      page.setAiModel(aiResponse.aiModel());
      page.setDateTranscribed(Instant.now());
    }
  }

}
