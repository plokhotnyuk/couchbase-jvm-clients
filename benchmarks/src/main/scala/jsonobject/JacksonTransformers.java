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
package jsonobject;

import com.couchbase.client.core.deps.com.fasterxml.jackson.core.JsonGenerator;
import com.couchbase.client.core.deps.com.fasterxml.jackson.core.JsonParser;
import com.couchbase.client.core.deps.com.fasterxml.jackson.core.JsonToken;
import com.couchbase.client.core.deps.com.fasterxml.jackson.core.Version;
import com.couchbase.client.core.deps.com.fasterxml.jackson.databind.*;
import com.couchbase.client.core.deps.com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;

public class JacksonTransformers {

    public static final ObjectMapper MAPPER = new ObjectMapper();
    public static final SimpleModule JSON_VALUE_MODULE = new SimpleModule("JsonValueModule",
        new Version(1, 0, 0, null, null, null));

    private JacksonTransformers() {}

    static {
        JSON_VALUE_MODULE.addSerializer(JsonObjectExperiment.class, new JacksonTransformers.JsonObjectSerializer());
        JSON_VALUE_MODULE.addSerializer(JsonArrayExperiment.class, new JacksonTransformers.JsonArraySerializer());
        JSON_VALUE_MODULE.addDeserializer(JsonObjectExperiment.class, new JacksonTransformers.JsonObjectDeserializer());
        JSON_VALUE_MODULE.addDeserializer(JsonArrayExperiment.class, new JacksonTransformers.JsonArrayDeserializer());
        MAPPER.registerModule(JacksonTransformers.JSON_VALUE_MODULE);
    }

    static class JsonObjectSerializer extends JsonSerializer<JsonObjectExperiment> {
        @Override
        public void serialize(JsonObjectExperiment value, JsonGenerator jgen,
                              SerializerProvider provider) throws IOException {
            jgen.writeObject(value.toMap());
        }
    }

    static class JsonArraySerializer extends JsonSerializer<JsonArrayExperiment> {
        @Override
        public void serialize(JsonArrayExperiment value, JsonGenerator jgen,
                              SerializerProvider provider) throws IOException {
            jgen.writeObject(value.toJavaList());
        }
    }

    static abstract class AbstractJsonValueDeserializer<T> extends JsonDeserializer<T> {

        private final boolean decimalForFloat;

        public AbstractJsonValueDeserializer() {
            decimalForFloat = Boolean.parseBoolean(
                System.getProperty("com.couchbase.json.decimalForFloat", "false")
            );
        }

        protected JsonObjectExperiment decodeObject(final JsonParser parser, final JsonObjectExperiment target) throws IOException {
            JsonToken current = parser.nextToken();
            String field = null;
            while(current != null && current != JsonToken.END_OBJECT) {
                if (current == JsonToken.START_OBJECT) {
                    target.put(field, decodeObject(parser, JsonObjectExperiment.empty()));
                } else if (current == JsonToken.START_ARRAY) {
                    target.put(field, decodeArray(parser, JsonArrayExperiment.empty()));
                } else if (current == JsonToken.FIELD_NAME) {
                    field = parser.getCurrentName();
                } else {
                    switch(current) {
                        case VALUE_TRUE:
                        case VALUE_FALSE:
                            target.put(field, parser.getBooleanValue());
                            break;
                        case VALUE_STRING:
                            target.put(field, parser.getValueAsString());
                            break;
                        case VALUE_NUMBER_INT:
                        case VALUE_NUMBER_FLOAT:
                            Number numberValue = parser.getNumberValue();
                            if (numberValue instanceof Double && decimalForFloat) {
                                numberValue = parser.getDecimalValue();
                            }
                            target.put(field, numberValue);
                            break;
                        case VALUE_NULL:
                            target.put(field, (JsonObjectExperiment) null);
                            break;
                        default:
                            throw new IllegalStateException("Could not decode JSON token: " + current);
                    }
                }

                current = parser.nextToken();
            }
            return target;
        }

        protected JsonArrayExperiment decodeArray(final JsonParser parser, final JsonArrayExperiment target) throws IOException {
            JsonToken current = parser.nextToken();
            while (current != null && current != JsonToken.END_ARRAY) {
                if (current == JsonToken.START_OBJECT) {
                    target.add(decodeObject(parser, JsonObjectExperiment.empty()));
                } else if (current == JsonToken.START_ARRAY) {
                    target.add(decodeArray(parser, JsonArrayExperiment.empty()));
                } else {
                    switch(current) {
                        case VALUE_TRUE:
                        case VALUE_FALSE:
                            target.add(parser.getBooleanValue());
                            break;
                        case VALUE_STRING:
                            target.add(parser.getValueAsString());
                            break;
                        case VALUE_NUMBER_INT:
                        case VALUE_NUMBER_FLOAT:
                            Number numberValue = parser.getNumberValue();
                            if (numberValue instanceof Double && decimalForFloat) {
                                numberValue = parser.getDecimalValue();
                            }
                            target.add(numberValue);
                            break;
                        case VALUE_NULL:
                            target.add((JsonObjectExperiment) null);
                            break;
                        default:
                            throw new IllegalStateException("Could not decode JSON token.");
                    }
                }

                current = parser.nextToken();
            }
            return target;
        }
    }

    static class JsonArrayDeserializer extends AbstractJsonValueDeserializer<JsonArrayExperiment> {
        @Override
        public JsonArrayExperiment deserialize(JsonParser jp, DeserializationContext ctx)
            throws IOException {
            if (jp.getCurrentToken() == JsonToken.START_ARRAY) {
                return decodeArray(jp, JsonArrayExperiment.empty());
            } else {
                throw new IllegalStateException("Expecting Array as root level object, " +
                    "was: " + jp.getCurrentToken());
            }
        }
    }

    static class JsonObjectDeserializer extends AbstractJsonValueDeserializer<JsonObjectExperiment> {
        @Override
        public JsonObjectExperiment deserialize(JsonParser jp, DeserializationContext ctx)
            throws IOException {
            if (jp.getCurrentToken() == JsonToken.START_OBJECT) {
                return decodeObject(jp, JsonObjectExperiment.empty());
            } else {
                throw new IllegalStateException("Expecting Object as root level object, " +
                    "was: " + jp.getCurrentToken());
            }
        }
    }

    public static JsonObjectExperiment stringToJsonObject(String input) throws Exception {
        return MAPPER.readValue(input, JsonObjectExperiment.class);
    }

    public static JsonArrayExperiment stringToJsonArray(String input) throws Exception {
        return MAPPER.readValue(input, JsonArrayExperiment.class);
    }

    public static JsonArrayExperiment bytesToJsonArray(byte[] input) throws Exception {
        return MAPPER.readValue(input, JsonArrayExperiment.class);
    }

    public static JsonObjectExperiment bytesToJsonObject(byte[] input) throws Exception {
        return MAPPER.readValue(input, JsonObjectExperiment.class);
    }



}
