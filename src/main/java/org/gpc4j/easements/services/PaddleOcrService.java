package org.gpc4j.easements.services;

import org.gpc4j.easements.model.EasementDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Spring service that delegates OCR to an external PaddleOCR microservice.
 * The service accepts a PDF as a {@code multipart/form-data} POST and returns
 * a fully-populated {@link EasementDoc} JSON response including per-page text,
 * confidence scores, and a flat {@code lines} list for full-text search.
 *
 * <p>Configure the endpoint via {@code paddle.ocr.url} in
 * {@code application.yaml} (e.g. {@code http://paddleocr.paddleocr-main/api/easement}).
 */
@Service
public class PaddleOcrService {

  private static final Logger log =
      LoggerFactory.getLogger(PaddleOcrService.class);

  private final String url;
  private final RestClient restClient;

  /**
   * Constructs the service with the configured PaddleOCR endpoint URL.
   *
   * @param url full URL of the PaddleOCR service endpoint
   */
  public PaddleOcrService(@Value("${paddle.ocr.url}") String url) {

    this.url = url;
    this.restClient = RestClient.create();
    log.info("PaddleOcrService initialised — url={}", url);
  }

  /**
   * Posts the supplied PDF bytes to the PaddleOCR service and returns the
   * resulting {@link EasementDoc}. The service is responsible for page
   * segmentation, OCR, and confidence scoring.
   *
   * @param filename  original filename of the PDF; sent as the multipart
   *                  file name so the service can populate {@code id} and
   *                  {@code filename} on the response
   * @param pdfBytes  raw PDF content
   * @return populated {@link EasementDoc} as returned by the service
   */
  public EasementDoc process(String filename, byte[] pdfBytes) {

    log.info("Posting {} ({} bytes) to PaddleOCR service", filename, pdfBytes.length);

    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", new ByteArrayResource(pdfBytes) {

      @Override
      public String getFilename() {

        return filename;
      }
    });

    EasementDoc doc = restClient.post()
        .uri(url)
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(body)
        .retrieve()
        .body(EasementDoc.class);

    log.info("PaddleOCR returned doc '{}' with {} page(s)",
        doc != null ? doc.getFilename() : "null",
        doc != null ? doc.getPageCount() : 0);

    return doc;
  }

}
