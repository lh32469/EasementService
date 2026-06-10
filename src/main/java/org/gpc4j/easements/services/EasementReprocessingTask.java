package org.gpc4j.easements.services;

import static org.gpc4j.easements.controller.EasementController.CONFIDENCE_PATTERN;
import static org.gpc4j.easements.controller.EasementController.OCR_PROMPT;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;

import org.gpc4j.easements.model.AIPrompt;
import org.gpc4j.easements.model.EasementDoc;
import org.gpc4j.easements.model.EasementPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.ravendb.client.documents.IDocumentStore;
import net.ravendb.client.documents.operations.attachments.AttachmentName;
import net.ravendb.client.documents.operations.attachments.CloseableAttachmentResult;
import net.ravendb.client.documents.session.IDocumentSession;

/**
 * Daily background task that finds one {@link EasementDoc} whose
 * {@code aiServiceName} field is unset (legacy documents processed before AI
 * extraction was introduced) and reprocesses its page-image attachments through
 * {@link AIService}.
 *
 * <p>Each page attachment is fetched from RavenDB, submitted to the primary
 * {@link AIService} implementation for vision-based OCR, and the resulting text
 * lines and confidence score are stored back on the document. The
 * {@code aiServiceName} and {@code aiModel} fields are populated once all pages
 * have been processed, so a document is not marked as done unless the full
 * reprocessing succeeds.
 *
 * <p>Runs once per day at 02:00 server time. Only one document is processed per
 * run to avoid long-running requests or rate-limit pressure against the AI API.
 * Re-enable {@link EnableScheduling} on
 * {@link org.gpc4j.easements.EasementsApplication} to activate this task.
 */
@Component
public class EasementReprocessingTask {

  private static final Logger log = LoggerFactory
    .getLogger(EasementReprocessingTask.class);

  private final IDocumentStore store;
  private final AIService aiService;

  /**
   * Constructs the task with the RavenDB document store and the primary AI
   * service implementation.
   *
   * @param store     the singleton RavenDB document store; sessions are opened
   *                  per-run because this task runs outside an HTTP request scope
   * @param aiService the AI vision service used to extract text from page images
   */
  public EasementReprocessingTask(IDocumentStore store, AIService aiService) {

    this.store = store;
    this.aiService = aiService;
  }


  /**
   * Selects one {@link EasementDoc} whose {@code aiServiceName} is unset,
   * delegates processing to {@link #processDoc}, and persists the result.
   *
   * <p>Runs daily at 02:00. Only one document is processed per invocation so
   * that long multi-page jobs do not accumulate across runs.
   */
  @Scheduled(cron = "0 0 2 * * *")
  public void reprocessOne() {

    log.info("Reprocessing task: searching for EasementDoc with no aiServiceName");

    try (IDocumentSession session = store.openSession()) {

      EasementDoc doc = session
        .query(EasementDoc.class)
        .openSubclause()
        .negateNext()
        .whereExists("aiServiceName")
        .orElse()
        .whereEquals("aiServiceName", (Object) null)
        .orElse()
        .whereEquals("aiServiceName", "")
        .closeSubclause()
        .firstOrDefault();

      if (doc == null) {
        log.info("Reprocessing task: all documents are up to date");
        return;
      }

      log.info("Reprocessing '{}'", doc.getId());
      processDoc(session, doc);
      session.saveChanges();
      log
        .info("Reprocessed '{}': {} page(s) via {}/{}", doc.getId(),
          doc.getPageCount(), doc.getAiServiceName(), doc.getAiModel());

    } catch (Exception e) {
      log.error("Reprocessing task failed unexpectedly", e);
    }
  }


  /**
   * Fetches each page-image attachment for {@code doc} from RavenDB, submits
   * it to {@link AIService} for text extraction, and updates {@code doc} in
   * place with the resulting {@link EasementPage} list and AI provenance fields.
   *
   * <p>Individual page failures are logged and skipped. Throws
   * {@link IOException} if no pages could be extracted at all, leaving
   * {@code doc} unmodified so the caller can skip the save.
   *
   * @param session open RavenDB session used to fetch page-image attachments
   * @param doc     the document to reprocess; mutated in place on success
   * @throws IOException if no pages were successfully extracted
   */
  void processDoc(IDocumentSession session, EasementDoc doc) throws IOException {

    AttachmentName[] names = session.advanced().attachments().getNames(doc);
    int totalPages = (int) Arrays
      .stream(names)
      .filter(a -> a.getName().matches("page-\\d+\\.png"))
      .count();

    List<EasementPage> pages = new LinkedList<>();
    for (int i = 1; i <= totalPages; i++) {
      String attachmentName = "page-" + i + ".png";
      log
        .info("Extracting text from attachment '{}' ({}/{})", attachmentName, i,
          totalPages);

      try (CloseableAttachmentResult att = session
        .advanced()
        .attachments()
        .get(doc.getId(), attachmentName)) {

        if (att == null) {
          log
            .warn("Attachment '{}' not found for '{}'; skipping page",
              attachmentName, doc.getId());
          continue;
        }

        byte[] imageBytes = att.getData().readAllBytes();

        AIPrompt prompt = AIPrompt
          .builder()
          .text(OCR_PROMPT)
          .image(imageBytes)
          .build();

        String aiText = aiService.query(prompt);
        log.debug("Page {}: AI returned {} chars", i, aiText.length());

        float confidence = 0f;
        List<String> lines = new LinkedList<>();
        for (String raw : aiText.split("\n")) {
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

        log.debug("Page {}: {} lines, confidence {}%", i, lines.size(), confidence);
        pages
          .add(new EasementPage(i, lines, confidence,
            aiService.getClass().getSimpleName(), aiService.getModel()));

      } catch (IOException e) {
        log
          .error("Failed to process page {} of '{}': {}", i, doc.getId(),
            e.getMessage());
      }
    }

    if (pages.isEmpty()) {
      throw new IOException("No pages could be extracted for '" + doc.getId() + "'");
    }

    doc.setPages(pages);
    doc.setPageCount(pages.size());
    doc.setAiServiceName(aiService.getClass().getSimpleName());
    doc.setAiModel(aiService.getModel());
  }

}
