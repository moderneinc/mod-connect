/**
 * Copyright 2023 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.moderne.connect.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.SPLIT_LINES;

public class GitLabYaml {

    public enum Stage {

        @JsonProperty("download")
        DOWNLOAD,

        @JsonProperty("build-lst")
        BUILD_LST
    }

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .disable(SPLIT_LINES))
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);


    public static String write(Pipeline pipeline) {
        try {
            return MAPPER.writeValueAsString(pipeline.prepareYamlMap());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to write pipeline as yaml", e);
        }
    }

    @Value
    @Builder
    public static class Pipeline {

        @Singular
        List<Stage> stages;
        Job download;

        @Singular
        Map<String, Job> jobs;

        Map<String, Object> prepareYamlMap() {
            Map<String, Object> pipeline = new LinkedHashMap<>();
            pipeline.put("stages", stages);
            pipeline.put("download", download);
            pipeline.putAll(jobs);
            return pipeline;
        }
    }

    @Value
    @Builder
    public static class Job {
        String image;
        Cache cache;
        Stage stage;
        List<String> tags;

        @Singular
        Map<String, Object> variables;

        @Singular("beforeCommand")
        List<String> beforeScript;

        @Singular("command")
        List<String> script;

        Artifacts artifacts;
        int retry;
    }

    @Value
    @Builder
    public static class Cache {
        String key;

        @Singular
        List<String> paths;

        @Builder.Default
        Policy policy = Policy.PUSH_AND_PULL;

        public enum Policy {

            @JsonProperty("push")
            PUSH,

            @JsonProperty("pull")
            PULL,

            @JsonProperty("pull-push")
            PUSH_AND_PULL;
        }
    }

    @Value
    @Builder
    public static class Artifacts {
        When when;
        @Singular
        List<String> paths;

        public enum When {

            @JsonProperty("always")
            ALWAYS,

            @JsonProperty("on_failure")
            ON_FAILURE,

            @JsonProperty("on_success")
            ON_SUCCESS;
        }
    }

}
