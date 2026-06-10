package org.gpc4j.easements;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the Easements Service.
 *
 * <p>{@link EnableScheduling} activates {@code @Scheduled} methods, including
 * the daily {@link org.gpc4j.easements.services.EasementReprocessingTask}.
 */
@SpringBootApplication
@EnableScheduling
public class EasementsApplication {

  public static void main(String[] args) {
    SpringApplication.run(EasementsApplication.class, args);
  }

}
