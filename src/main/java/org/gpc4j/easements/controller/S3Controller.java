package org.gpc4j.easements.controller;

import java.io.IOException;

import org.gpc4j.easements.services.S3Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller for uploading files to AWS S3.
 */
@RestController
@RequestMapping("/api")
public class S3Controller {

  private static final Logger log = LoggerFactory.getLogger(S3Controller.class);

  private static final String BUCKET = "pdx-docs";

  private final S3Service s3Service;

  /**
   * Creates an S3Controller with the given {@link S3Service}.
   *
   * @param s3Service the service used to interact with AWS S3
   */
  public S3Controller(S3Service s3Service) {

    this.s3Service = s3Service;
  }


  /**
   * Accepts a multipart file upload and stores it in the {@code pdx-docs} S3
   * bucket using the original filename as the object key.
   *
   * @param file the uploaded file
   * @return the ETag of the created S3 object, or a 400 if no filename is
   *         present
   * @throws IOException if reading the file bytes fails
   */
  @PostMapping("/S3")
  public ResponseEntity<String> upload(@RequestParam("file") MultipartFile file)
    throws IOException {

    String key = file.getOriginalFilename();

    if (key == null || key.isBlank()) {
      return ResponseEntity.badRequest().body("File must have a filename");
    }

    log.info("Uploading {} bytes to s3://{}/{}", file.getSize(), BUCKET, key);

    String eTag = s3Service.putObject(file.getBytes(), BUCKET, key);

    return ResponseEntity.ok(eTag);
  }

}
