package org.gpc4j.easements.services;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextRequest;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextResponse;
import software.amazon.awssdk.services.textract.model.Document;

/**
 * Spring service that sends image bytes to AWS Textract and returns the
 * detected lines of text.
 */
@Service
public class TextractService {

  private static final Logger log =
      LoggerFactory.getLogger(TextractService.class);

  private final TextractClient textractClient;

  /** Constructs the service and initialises the Textract client. */
  public TextractService() {

    textractClient = TextractClient.builder()
        .region(Region.US_WEST_2)
        .build();

    log.info("TextractService initialised");
  }

  /**
   * Sends {@code imageBytes} to AWS Textract {@code DetectDocumentText} and
   * returns all {@code LINE}-type blocks as a list of strings.
   *
   * @param imageBytes PNG or JPEG image bytes (max 5 MB for synchronous API)
   * @return ordered list of text lines detected in the image; never null
   */
  public List<String> extractLines(byte[] imageBytes) {

    Document document = Document.builder()
        .bytes(SdkBytes.fromByteArray(imageBytes))
        .build();

    DetectDocumentTextRequest request = DetectDocumentTextRequest.builder()
        .document(document)
        .build();

    DetectDocumentTextResponse response =
        textractClient.detectDocumentText(request);

    List<String> lines = response.blocks().stream()
        .filter(b -> b.blockType() == BlockType.LINE)
        .map(Block::text)
        .toList();

    log.debug("Extracted {} lines from image", lines.size());
    return lines;
  }

}
