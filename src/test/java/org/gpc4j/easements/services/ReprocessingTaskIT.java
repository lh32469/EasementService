package org.gpc4j.easements.services;

import org.gpc4j.easements.model.EasementDoc;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import net.ravendb.client.documents.IDocumentStore;
import net.ravendb.client.documents.session.IDocumentSession;

@SpringBootTest(properties = "ravendb.urls=http://192.168.0.5:8080")
public class ReprocessingTaskIT {

  @Autowired
  EasementReprocessingTask task;

  @Autowired
  IDocumentStore store;

  @Test
  public void testReprocessingTask() throws Exception {

    try (IDocumentSession session = store.openSession()) {
      EasementDoc doc = session
        .query(EasementDoc.class)
        .whereEquals("filename", "488.pdf")
        .firstOrDefault();

      task.processDoc(session, doc);

      session.saveChanges();
    }

  }

}
