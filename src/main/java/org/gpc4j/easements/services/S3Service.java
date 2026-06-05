package org.gpc4j.easements.services;

import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

@Slf4j
@Service
public class S3Service {

  public S3Service() {
    log.info("Creating S3Service..");
  }


  // Create the S3Client object.
  private S3Client getClient() {

    Region region = Region.US_WEST_2;
    S3Client s3 = S3Client.builder().region(region).build();

    return s3;
  }


  // Get the byte[] from this AWS S3 object.
  public byte[] getObjectBytes(String bucketName, String keyName) {

    S3Client s3 = getClient();

    try {
      GetObjectRequest objectRequest = GetObjectRequest.builder().key(keyName)
        .bucket(bucketName).build();

      ResponseBytes<GetObjectResponse> objectBytes = s3
        .getObjectAsBytes(objectRequest);
      byte[] data = objectBytes.asByteArray();
      return data;

    } catch (S3Exception e) {
      System.err.println(e.awsErrorDetails().errorMessage());
      System.exit(1);
    }
    return null;
  }


  /**
   * Returns the names of all images and data within an XML document.
   *
   * @param bucketName
   * @return
   */
  public List<String> ListAllObjects(String bucketName) {

    S3Client s3 = getClient();

    List<String> bucketItems = new LinkedList<>();

    try {
      ListObjectsRequest listObjects = ListObjectsRequest.builder()
        .bucket(bucketName).build();

      ListObjectsResponse res = s3.listObjects(listObjects);
      List<S3Object> objects = res.contents();

      for (ListIterator iterVals = objects.listIterator(); iterVals.hasNext();) {
        S3Object myValue = (S3Object) iterVals.next();
        // Push the key to  the list.
        bucketItems.add(myValue.key());
      }

      return bucketItems;

    } catch (S3Exception e) {
      log.warn(e.awsErrorDetails().errorMessage());
    }
    return null;
  }


  /**
   * Places a PDF object into an Amazon S3 bucket.
   *
   * @param data
   * @param bucketName
   * @param objectKey
   * @return
   */
  public String putObject(byte[] data, String bucketName, String objectKey) {

    S3Client s3 = getClient();

    try {
      PutObjectResponse response = s3.putObject(
        PutObjectRequest.builder().bucket(bucketName).key(objectKey).build(),
        RequestBody.fromBytes(data));

      log.info("response = {}", response);

      return response.eTag();

    } catch (S3Exception e) {
      log.warn(e.toString());
    }
    return "";
  }

}
