/*
 * Copyright (c) 2016 Couchbase, Inc.
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

package com.couchbase.client.core.config;

import com.couchbase.client.core.service.ServiceType;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClusterConfig {

  /**
   * Holds all current bucket configurations.
   */
  private final Map<String, BucketConfig> bucketConfigs;

  /**
   * Creates a new {@link ClusterConfig}.
   */
  public ClusterConfig() {
    bucketConfigs = new ConcurrentHashMap<>();
  }

  public BucketConfig bucketConfig(final String bucketName) {
    return bucketConfigs.get(bucketName);
  }

  public void setBucketConfig(final BucketConfig config) {
    bucketConfigs.put(config.name(), config);
  }

  public void deleteBucketConfig(String bucketName) {
    bucketConfigs.remove(bucketName);
  }

  public boolean hasBucket(final String bucketName) {
    return bucketConfigs.containsKey(bucketName);
  }

  public Map<String, BucketConfig> bucketConfigs() {
    return bucketConfigs;
  }

  public Set<String> allNodeAddresses() {
    Set<String> nodes = new HashSet<>();
    for (BucketConfig bc : bucketConfigs().values()) {
      for (NodeInfo ni : bc.nodes()) {
        nodes.add(ni.hostname());
      }
    }
    return nodes;
  }

  @Override
  public String toString() {
    return "ClusterConfig{" +
      "bucketConfigs=" + bucketConfigs +
      '}';
  }

  /**
   * Returns the current cluster capabilities.
   *
   * <p>Right now this needs at least one bucket open, and it grabs the capabilities from the first bucket
   * config available. If needed, in the future this can be made more intelligent (caching?).</p>
   */
  public Map<ServiceType, Set<ClusterCapabilities>> clusterCapabilities() {
    for (BucketConfig bc : bucketConfigs().values()) {
      return bc.clusterCapabilities();
    }
    return Collections.emptyMap();
  }

}
