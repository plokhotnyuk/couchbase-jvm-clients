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

package com.couchbase.client.core.cnc;

import java.util.HashMap;
import java.util.Map;

/**
 * Common parent method for all contexts.
 *
 * <p>Contexts are encouraged to derive from this abstract class because all they have
 * to do then is to implement/override {@link #injectExportableParams(Map)} and feed
 * the data they want to be extracted. The actual extraction and formatting then
 * comes for free.</p>
 */
public abstract class AbstractContext implements Context {

  /**
   * This method needs to be implemented by the actual context implementations to
   * inject the params they need for exporting.
   *
   * @param input pass exportable params in here.
   */
  protected void injectExportableParams(final Map<String, Object> input) {
  }

  @Override
  public String exportAsString(final ExportFormat format) {
    Map<String, Object> input = new HashMap<>();
    injectExportableParams(input);
    return format.apply(input);
  }

  @Override
  public String toString() {
    return this.getClass().getSimpleName() + exportAsString(ExportFormat.STRING);
  }
}
