package org.gpc4j.easements.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.gpc4j.easements.model.EasementDoc;
import org.gpc4j.easements.model.EasementPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import net.ravendb.client.documents.IDocumentStore;
import net.ravendb.client.documents.operations.attachments.CloseableAttachmentResult;
import net.ravendb.client.documents.session.IAdvancedSessionOperations;
import net.ravendb.client.documents.session.IAttachmentsSessionOperations;
import net.ravendb.client.documents.session.IDocumentQuery;
import net.ravendb.client.documents.session.IDocumentSession;

/**
 * Integration test for {@link EasementReprocessingTask}. Uses the live
 * {@link AIService} (Gemini by default) to verify that a legacy
 * {@link EasementDoc} with no {@code aiServiceName} is fully reprocessed:
 * page-image attachments are run through AI vision, text lines and confidence
 * are extracted, and the document's AI provenance fields are populated.
 *
 * <p>{@link IDocumentStore} is mocked so RavenDB does not need to be running.
 * The mock session is wired to return a one-page test document and to serve
 * {@code attachment-4.png} from test resources as the page-image attachment.
 *
 * <p>The query mock uses {@link RETURNS_SELF} so that fluent {@code whereEquals}
 * calls (which return the generic bound {@code TSelf}) pass through without
 * Mockito needing to resolve the erased return type.
 *
 * <p>Run manually with: {@code ./mvnw test -Dtest=EasementReprocessingTaskIT}
 */
@SpringBootTest(properties = "ravendb.urls=http://localhost:8080")
class EasementReprocessingTaskIT {

  @Autowired
  private EasementReprocessingTask reprocessingTask;

  @MockitoBean
  private IDocumentStore documentStore;

  /**
   * Verifies that {@link EasementReprocessingTask#reprocessOne()} finds the
   * legacy document, sends its page image to the AI service, populates text
   * lines and confidence on the {@link EasementPage}, sets
   * {@code aiServiceName} and {@code aiModel}, and calls
   * {@code session.saveChanges()}.
   *
   * @throws Exception if the test image cannot be loaded or the AI API call
   *                   fails
   */
  @Test
  @SuppressWarnings("unchecked")
  void reprocessOneUpdatesDocumentWithAiText() throws Exception {

    // Legacy doc: one page, no AI provenance set.
    EasementDoc doc = new EasementDoc();
    doc.setId("test-easement.pdf");
    doc.setFilename("test-easement.pdf");
    doc.setPageCount(1);

    byte[] imageBytes;
    try (InputStream in = getClass().getResourceAsStream("/attachment-4.png")) {
      assertNotNull(in, "attachment-4.png must be present in src/test/resources");
      imageBytes = in.readAllBytes();
    }

    // RETURNS_SELF: whereEquals() returns TSelf (generic bound); this answer
    // makes the mock return itself for any method whose return type is compatible,
    // so the fluent chain works without Mockito needing to resolve the erased type.
    IDocumentQuery<EasementDoc> query = mock(IDocumentQuery.class, RETURNS_SELF);
    doReturn(doc).when(query).firstOrDefault();

    IDocumentSession session = mock(IDocumentSession.class);
    when(documentStore.openSession()).thenReturn(session);
    doReturn(query).when(session).query(EasementDoc.class);

    IAdvancedSessionOperations advanced = mock(IAdvancedSessionOperations.class);
    when(session.advanced()).thenReturn(advanced);

    IAttachmentsSessionOperations attachmentOps = mock(
      IAttachmentsSessionOperations.class);
    when(advanced.attachments()).thenReturn(attachmentOps);

    CloseableAttachmentResult att = mock(CloseableAttachmentResult.class);
    doReturn(new ByteArrayInputStream(imageBytes)).when(att).getData();
    when(attachmentOps.get("test-easement.pdf", "page-1.png")).thenReturn(att);

    reprocessingTask.reprocessOne();

    verify(session).saveChanges();

    assertNotNull(doc.getAiServiceName(), "aiServiceName must be set");
    assertNotNull(doc.getAiModel(), "aiModel must be set");

    assertNotNull(doc.getPages(), "pages must not be null");
    assertFalse(doc.getPages().isEmpty(), "at least one page must be extracted");

    EasementPage page = doc.getPages().get(0);
    assertEquals(1, page.getPageNumber(), "page number must be 1");
    assertNotNull(page.getLines(), "lines must not be null");
    assertFalse(page.getLines().isEmpty(), "page must have at least one text line");

    System.out.println("AI service : " + doc.getAiServiceName());
    System.out.println("AI model   : " + doc.getAiModel());
    System.out.println("Lines      : " + page.getLines().size());
    System.out.println("Confidence : " + page.getConfidence() + "%");
    page.getLines().forEach(l -> System.out.println("  " + l));
  }


  /**
   * Verifies that {@link EasementReprocessingTask#reprocessOne()} does nothing
   * when the query returns {@code null} (all documents already have
   * {@code aiServiceName} set). {@code session.saveChanges()} must not be
   * called.
   */
  @Test
  @SuppressWarnings("unchecked")
  void reprocessOneDoesNothingWhenAllDocumentsAreUpToDate() {

    IDocumentQuery<EasementDoc> query = mock(IDocumentQuery.class, RETURNS_SELF);
    doReturn(null).when(query).firstOrDefault();

    IDocumentSession session = mock(IDocumentSession.class);
    when(documentStore.openSession()).thenReturn(session);
    doReturn(query).when(session).query(EasementDoc.class);

    reprocessingTask.reprocessOne();

    verify(session, never()).saveChanges();
  }

}
