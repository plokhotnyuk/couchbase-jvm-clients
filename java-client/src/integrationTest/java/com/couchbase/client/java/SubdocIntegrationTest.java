/*
 * Copyright (c) 2018 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.java;

import com.couchbase.client.core.error.subdoc.PathNotFoundException;
import com.couchbase.client.java.env.ClusterEnvironment;

import com.couchbase.client.java.json.JsonArray;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.*;
import com.couchbase.client.java.util.JavaIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SubdocIntegrationTest extends JavaIntegrationTest {

  private static Cluster cluster;
  private static ClusterEnvironment environment;
  private static Collection collection;

  @BeforeAll
  static void setup() {
    environment = environment().build();
    cluster = Cluster.connect(environment);
    Bucket bucket = cluster.bucket(config().bucketname());
    collection = bucket.defaultCollection();
  }

  @AfterAll
  static void tearDown() {
    cluster.shutdown();
    environment.shutdown();
  }

  @Test
  void emptyIfNotFound() {
    assertFalse(collection.lookupIn("does_not_exist", Arrays.asList(LookupInSpec.get("foo"))).isPresent());
  }

  @Test
  void loadPrimitives() {
    String id = UUID.randomUUID().toString();

    collection.upsert(
      id, JsonObject.create().put("foo", "bar").put("num", 1234)
    );

    Optional<LookupInResult> result = collection.lookupIn(id, Arrays.asList(LookupInSpec.get("foo"), LookupInSpec.get("num")));
    assertTrue(result.isPresent());
    result.ifPresent(r -> {
      assertEquals("bar", r.contentAs(0, String.class));
      assertEquals(1234, (int) r.contentAs(1, Integer.class));
      assertTrue(r.exists(0));
      assertTrue(r.exists(1));
      assertFalse(r.exists(2));
      assertTrue(r.cas() != 0);
    });
  }

  @Test
  void loadObjectAndArray() {
    String id = UUID.randomUUID().toString();

    collection.upsert(id, JsonObject.create()
      .put("obj", JsonObject.create())
      .put("arr", JsonArray.create())
    );

    Optional<LookupInResult> result = collection.lookupIn(id, Arrays.asList(LookupInSpec.get("obj"), LookupInSpec.get("arr")));
    assertTrue(result.isPresent());
    result.ifPresent(r -> {
      assertEquals(JsonObject.empty(), r.contentAsObject(0));
      assertEquals(JsonArray.empty(), r.contentAsArray(1));
      assertTrue(r.exists(0));
      assertTrue(r.exists(1));
      assertTrue(r.cas() != 0);
    });
  }

  @Test
  void insertPrimitive() {
    String id = UUID.randomUUID().toString();

    collection.upsert(id, JsonObject.empty());

    MutateInResult result = collection.mutateIn(id, Arrays.asList(MutateInSpec.insert("foo", "bar")));
    assertTrue(result.cas() != 0);

    assertEquals(
      JsonObject.create().put("foo", "bar"),
      collection.get(id).get().contentAsObject()
    );
  }

  @Test
  void pathDoesNotExistSingle() {
    String id = UUID.randomUUID().toString();

    collection.upsert(id, JsonObject.empty());

    assertThrows(PathNotFoundException.class, () ->
            collection.lookupIn(id, Arrays.asList(LookupInSpec.get("not_exist")))
    );
  }

  @Test
  void pathDoesNotExistMulti() {
    String id = UUID.randomUUID().toString();

    collection.upsert(id, JsonObject.create().put("foo", "bar"));

    LookupInResult result = collection.lookupIn(id, Arrays.asList(LookupInSpec.get("not_exist"), LookupInSpec.get("foo"))).get();

    assertFalse(result.exists(0));
    assertTrue(result.exists(1));
    assertThrows(PathNotFoundException.class, () ->
      assertTrue(result.contentAs(0, Boolean.class))
    );
    assertEquals("bar", result.contentAs(1, String.class));
  }


  // TODO this throws and shouldn't. need to implement single subdoc path. check old client AsyncLookupInBuilder
//  @Test
//  void existsSingle() {
//    String id = UUID.randomUUID().toString();
//
//    collection.upsert(id, JsonObject.create().put("foo", "bar"));
//
//    LookupInResult result = collection.lookupIn(id, lookupInOps().exists("not_exist")).get();
//
//    assertFalse(result.exists(0));
//    assertThrows(PathNotFoundException.class, () ->
//            assertTrue(result.contentAs(0, Boolean.class))
//    );
//  }

  @Test
  void existsMulti() {
    String id = UUID.randomUUID().toString();

    collection.upsert(id, JsonObject.create().put("foo", "bar"));


    LookupInResult result = collection.lookupIn(id, Arrays.asList(LookupInSpec.exists("not_exist"), LookupInSpec.get("foo"))).get();

    assertFalse(result.exists(0));
    assertThrows(PathNotFoundException.class, () ->
            assertTrue(result.contentAs(0, Boolean.class))
    );

    assertTrue(result.exists(1));
    assertEquals("bar", result.contentAs(1, String.class));
  }

  @Test
  void count() {
    String id = UUID.randomUUID().toString();

    collection.upsert(id, JsonObject.create().put("foo", JsonArray.from("hello", "world")));

    LookupInResult result = collection.lookupIn(id, Arrays.asList(LookupInSpec.count("foo"))).get();

    assertTrue(result.exists(0));
    assertEquals(2, (int) result.contentAs(0, Integer.class));
  }


  @Test
  void getFullDocument() {
    String id = UUID.randomUUID().toString();

    JsonObject content = JsonObject.create().put("foo", "bar");
    collection.upsert(id, content);

    Optional<LookupInResult> result = collection.lookupIn(id, Arrays.asList(LookupInSpec.getFullDocument()));

    assertEquals(
            content,
            result.get().contentAsObject(0)
    );
  }

  @Test
  void upsertFullDocument() {
    String id = UUID.randomUUID().toString();

    JsonObject content = JsonObject.create().put("foo", "bar");

    MutateInResult result = collection.mutateIn(id,
            Arrays.asList(
                    // Server doesn't allow fulLDocument to be only op here, get "key not found"
                    MutateInSpec.upsert("qix", "qux"),
                    MutateInSpec.fullDocument(content)),
            MutateInOptions.mutateInOptions().upsertDocument(true));

    Optional<GetResult> doc = collection.get(id);

    assertEquals(
            content,
            doc.get().contentAsObject()
    );
  }

  @Test
  void insertFullDocument() {
    String id = UUID.randomUUID().toString();

    JsonObject content = JsonObject.create().put("foo", "bar");

    MutateInResult result = collection.mutateIn(id,
            Arrays.asList(
                    // Server doesn't allow fulLDocument to be only op here, get "key not found"
                    MutateInSpec.upsert("qix", "qux"),
                    MutateInSpec.fullDocument(content)),
            MutateInOptions.mutateInOptions().insertDocument(true));

    Optional<GetResult> doc = collection.get(id);

    assertEquals(
            content,
            doc.get().contentAsObject()
    );
  }


}
