package org.gpc4j.easements.controller;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.gpc4j.easements.model.EasementDoc;
import org.gpc4j.easements.model.EasementPage;
import org.gpc4j.easements.model.OcrResult;
import org.gpc4j.easements.services.TesseractService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import net.ravendb.client.documents.session.IDocumentSession;

/**
 * REST controller that ingests a scanned easement PDF, extracts text via
 * Tesseract OCR, and persists the result as an {@link EasementDoc} in RavenDB
 * with the rendered page images stored as attachments.
 */
@RestController
@RequestMapping("/api")
public class EasementController {

  private static final Logger log =
      LoggerFactory.getLogger(EasementController.class);

  private static final float RENDER_DPI = 150f;

  private final TesseractService tesseractService;
  private final IDocumentSession session;

  /**
   * Constructs the controller with its required dependencies.
   *
   * @param tesseractService service used to extract text lines from images
   * @param session          request-scoped RavenDB session
   */
  public EasementController(
      TesseractService tesseractService,
      IDocumentSession session) {

    this.tesseractService = tesseractService;
    this.session = session;
  }

  /**
   * Accepts a scanned easement PDF, renders each page to a PNG image, runs
   * Tesseract OCR, and stores the result in RavenDB.
   *
   * <p>Each rendered page produces one {@link EasementPage} stored in
   * {@link EasementDoc#getPages()}. A denormalised flat {@code lines} list is
   * also stored on the document so the full-text search query can cover all
   * pages without nested-field indexing.
   *
   * <p>The {@link EasementDoc} is stored under the original filename as its
   * document key. Each rendered page is attached as {@code page-1.png}, etc.
   *
   * @param file the uploaded PDF
   * @return the RavenDB document ID on success, or 400 if the filename is absent
   * @throws IOException if reading the PDF, rendering a page, or OCR fails
   */
  @PostMapping("/easement")
  public ResponseEntity<String> ingest(
      @RequestParam("file") MultipartFile file)
      throws IOException {

    String filename = file.getOriginalFilename();

    if (filename == null || filename.isBlank()) {
      return ResponseEntity.badRequest().body("File must have a filename");
    }

    log.info("Ingesting easement PDF: {}", filename);

    byte[] pdfBytes = file.getBytes();
    List<EasementPage> docPages = new ArrayList<>();
    List<String> allLines = new LinkedList<>();
    // Index access required (pageImages.get(i)), so ArrayList is correct here.
    List<byte[]> pageImages = new ArrayList<>();

    try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {

      PDFRenderer renderer = new PDFRenderer(pdf);
      int pageCount = pdf.getNumberOfPages();
      log.info("PDF has {} page(s)", pageCount);

      for (int i = 0; i < pageCount; i++) {
        BufferedImage image =
            renderer.renderImageWithDPI(i, RENDER_DPI, ImageType.RGB);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", bos);
        byte[] imageBytes = bos.toByteArray();
        pageImages.add(imageBytes);

        log.debug("Page {}: rendered {} bytes, running OCR", i + 1,
            imageBytes.length);

        OcrResult result = tesseractService.extractText(imageBytes);
        docPages.add(new EasementPage(i + 1, result.lines(), result.confidence()));
        allLines.addAll(result.lines());

        log.debug("Page {}: {} lines, confidence={:.1f}",
            i + 1, result.lines().size(), result.confidence());
      }
    }

    EasementDoc doc = new EasementDoc();
    doc.setId(filename);
    doc.setFilename(filename);
    doc.setPages(docPages);
    doc.setLines(allLines);
    doc.setPageCount(docPages.size());
    doc.setCreatedAt(Instant.now());

    session.store(doc, filename);

    for (int i = 0; i < pageImages.size(); i++) {
      String attachmentName = "page-" + (i + 1) + ".png";
      session.advanced().attachments().store(
          doc,
          attachmentName,
          new ByteArrayInputStream(pageImages.get(i)),
          "image/png");
      log.debug("Queued attachment: {}", attachmentName);
    }

    session.saveChanges();
    log.info("Stored EasementDoc '{}' with {} page(s)", filename, docPages.size());

    return ResponseEntity.ok(filename);
  }

}
