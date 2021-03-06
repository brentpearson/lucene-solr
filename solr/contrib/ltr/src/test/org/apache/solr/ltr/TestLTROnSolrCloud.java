/* * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.ltr;

import java.io.File;
import java.util.SortedMap;

import org.apache.commons.io.FileUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.embedded.JettyConfig;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.cloud.AbstractDistribZkTestBase;
import org.apache.solr.cloud.MiniSolrCloudCluster;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.ltr.feature.SolrFeature;
import org.apache.solr.ltr.feature.ValueFeature;
import org.apache.solr.ltr.model.LinearModel;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.AfterClass;
import org.junit.Test;

public class TestLTROnSolrCloud extends TestRerankBase {

  private MiniSolrCloudCluster solrCluster;
  String solrconfig = "solrconfig-ltr.xml";
  String schema = "schema.xml";

  SortedMap<ServletHolder,String> extraServlets = null;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    extraServlets = setupTestInit(solrconfig, schema, true);
    System.setProperty("enable.update.log", "true");

    int numberOfShards = random().nextInt(4)+1;
    int numberOfReplicas = random().nextInt(2)+1;
    int maxShardsPerNode = numberOfShards+random().nextInt(4)+1;

    int numberOfNodes = numberOfShards * maxShardsPerNode;

    setupSolrCluster(numberOfShards, numberOfReplicas, numberOfNodes, maxShardsPerNode);


  }


  @Override
  public void tearDown() throws Exception {
    restTestHarness.close();
    restTestHarness = null;
    jetty.stop();
    jetty = null;
    solrCluster.shutdown();
    super.tearDown();
  }

  @Test
  public void testSimpleQuery() throws Exception {
    // will randomly pick a configuration with [1..5] shards and [1..3] replicas

    // Test regular query, it will sort the documents by inverse
    // popularity (the less popular, docid == 1, will be in the first
    // position
    SolrQuery query = new SolrQuery("{!func}sub(8,field(popularity))");

    query.setRequestHandler("/query");
    query.setFields("*,score");
    query.setParam("rows", "8");

    QueryResponse queryResponse =
        solrCluster.getSolrClient().query(COLLECTION,query);
    assertEquals(8, queryResponse.getResults().getNumFound());
    assertEquals("1", queryResponse.getResults().get(0).get("id").toString());
    assertEquals("2", queryResponse.getResults().get(1).get("id").toString());
    assertEquals("3", queryResponse.getResults().get(2).get("id").toString());
    assertEquals("4", queryResponse.getResults().get(3).get("id").toString());

    // Test re-rank and feature vectors returned
    query.setFields("*,score,features:[fv]");
    query.add("rq", "{!ltr model=powpularityS-model reRankDocs=8}");
    queryResponse =
        solrCluster.getSolrClient().query(COLLECTION,query);
    assertEquals(8, queryResponse.getResults().getNumFound());
    assertEquals("8", queryResponse.getResults().get(0).get("id").toString());
    assertEquals("powpularityS:64.0;c3:2.0",
        queryResponse.getResults().get(0).get("features").toString());
    assertEquals("7", queryResponse.getResults().get(1).get("id").toString());
    assertEquals("powpularityS:49.0;c3:2.0",
        queryResponse.getResults().get(1).get("features").toString());
    assertEquals("6", queryResponse.getResults().get(2).get("id").toString());
    assertEquals("powpularityS:36.0;c3:2.0",
        queryResponse.getResults().get(2).get("features").toString());
    assertEquals("5", queryResponse.getResults().get(3).get("id").toString());
    assertEquals("powpularityS:25.0;c3:2.0",
        queryResponse.getResults().get(3).get("features").toString());
  }

  private void setupSolrCluster(int numShards, int numReplicas, int numServers, int maxShardsPerNode) throws Exception {
    JettyConfig jc = buildJettyConfig("/solr");
    jc = JettyConfig.builder(jc).withServlets(extraServlets).build();
    solrCluster = new MiniSolrCloudCluster(numServers, tmpSolrHome.toPath(), jc);
    File configDir = tmpSolrHome.toPath().resolve("collection1/conf").toFile();
    solrCluster.uploadConfigSet(configDir.toPath(), "conf1");

    solrCluster.getSolrClient().setDefaultCollection(COLLECTION);

    createCollection(COLLECTION, "conf1", numShards, numReplicas, maxShardsPerNode);
    indexDocuments(COLLECTION);

    createJettyAndHarness(tmpSolrHome.getAbsolutePath(), solrconfig, schema,
            "/solr", true, extraServlets);
    loadModelsAndFeatures();
  }


  private void createCollection(String name, String config, int numShards, int numReplicas, int maxShardsPerNode)
      throws Exception {
    CollectionAdminResponse response;
    CollectionAdminRequest.Create create =
        CollectionAdminRequest.createCollection(name, config, numShards, numReplicas);
    create.setMaxShardsPerNode(maxShardsPerNode);
    response = create.process(solrCluster.getSolrClient());

    if (response.getStatus() != 0 || response.getErrorMessages() != null) {
      fail("Could not create collection. Response" + response.toString());
    }
    ZkStateReader zkStateReader = solrCluster.getSolrClient().getZkStateReader();
    AbstractDistribZkTestBase.waitForRecoveriesToFinish(name, zkStateReader, false, true, 100);
  }


  void indexDocument(String collection, String id, String title, String description, int popularity)
    throws Exception{
    SolrInputDocument doc = new SolrInputDocument();
    doc.setField("id", id);
    doc.setField("title", title);
    doc.setField("description", description);
    doc.setField("popularity", popularity);
    solrCluster.getSolrClient().add(collection, doc);
  }

  private void indexDocuments(final String collection)
       throws Exception {
    final int collectionSize = 8;
    for (int docId = 1; docId <= collectionSize;  docId++) {
      final int popularity = docId;
      indexDocument(collection, String.valueOf(docId), "a1", "bloom", popularity);
    }
    solrCluster.getSolrClient().commit(collection);
  }


  private void loadModelsAndFeatures() throws Exception {
    final String featureStore = "test";
    final String[] featureNames = new String[] {"powpularityS","c3"};
    final String jsonModelParams = "{\"weights\":{\"powpularityS\":1.0,\"c3\":1.0}}";

    loadFeature(
            featureNames[0],
            SolrFeature.class.getCanonicalName(),
            featureStore,
            "{\"q\":\"{!func}pow(popularity,2)\"}"
    );
    loadFeature(
            featureNames[1],
            ValueFeature.class.getCanonicalName(),
            featureStore,
            "{\"value\":2}"
    );

    loadModel(
             "powpularityS-model",
             LinearModel.class.getCanonicalName(),
             featureNames,
             featureStore,
             jsonModelParams
    );
    reloadCollection(COLLECTION);
  }

  private void reloadCollection(String collection) throws Exception {
    CollectionAdminRequest.Reload reloadRequest = CollectionAdminRequest.reloadCollection(collection);
    CollectionAdminResponse response = reloadRequest.process(solrCluster.getSolrClient());
    assertEquals(0, response.getStatus());
    assertTrue(response.isSuccess());
  }

  @AfterClass
  public static void after() throws Exception {
    FileUtils.deleteDirectory(tmpSolrHome);
    System.clearProperty("managed.schema.mutable");
  }

}
