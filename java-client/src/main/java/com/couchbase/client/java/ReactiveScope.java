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

import com.couchbase.client.core.Core;
import com.couchbase.client.core.annotation.Stability;
import com.couchbase.client.core.io.CollectionIdentifier;
import com.couchbase.client.java.env.ClusterEnvironment;
import reactor.core.publisher.Mono;

/**
 * The scope identifies a group of collections and allows high application
 * density as a result.
 *
 * <p>If no scope is explicitly provided, the default scope is used.</p>
 *
 * @since 3.0.0
 */
public class ReactiveScope {

  /**
   * The name of the bucket at which this scope belongs.
   */
  private final String bucketName;

  /**
   * The underlying async scope which actually performs the actions.
   */
  private final AsyncScope asyncScope;

  /**
   * Creates a new {@link ReactiveScope}.
   *
   * @param asyncScope the underlying async scope.
   * @param bucketName the name of the bucket this scope belongs to.
   */
  ReactiveScope(final AsyncScope asyncScope, final String bucketName) {
    this.asyncScope = asyncScope;
    this.bucketName = bucketName;
  }

  /**
   * The name of the scope.
   *
   * @return the name of the scope.
   */
  public String name() {
    return asyncScope.name();
  }

  /**
   * The name of the bucket this scope is attached to.
   */
  public String bucketName() {
    return bucketName;
  }

  /**
   * Returns the underlying async scope.
   */
  public AsyncScope async() {
    return asyncScope;
  }

  /**
   * Provides access to the underlying {@link Core}.
   *
   * <p>This is advanced API, use with care!</p>
   */
  @Stability.Uncommitted
  public Core core() {
    return asyncScope.core();
  }

  /**
   * Provides access to the configured {@link ClusterEnvironment} for this scope.
   */
  public ClusterEnvironment environment() {
    return asyncScope.environment();
  }

  /**
   * Opens the default collection for this scope.
   *
   * @return the default collection once opened.
   */
  public Mono<ReactiveCollection> defaultCollection() {
    return collection(CollectionIdentifier.DEFAULT_COLLECTION);
  }

  /**
   * Opens a collection for this scope with an explicit name.
   *
   * @param name the collection name.
   * @return the requested collection if successful.
   */
  public Mono<ReactiveCollection> collection(final String name) {
    return Mono
      .fromFuture(asyncScope.collection(name))
      .map(ReactiveCollection::new);
  }

}
