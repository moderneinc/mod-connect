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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.moderne.connect.utils.GitLabYaml.write;
import static org.assertj.core.api.Assertions.assertThat;

class GitLabYamlTest {

    @Test
    void writePipelineYaml() {

        GitLabYaml.Job download = GitLabYaml.Job.builder()
                .cache(GitLabYaml.Cache.builder().key("cli-v4.4.0")
                        .paths(List.of("mod"))
                        .policy(GitLabYaml.Cache.Policy.PUSH_AND_PULL)
                        .build())
                .stage(GitLabYaml.Stage.DOWNLOAD)
                .variables(Map.of("GITLAB_HOST", "gitlab.com"))
                .command("echo \"download CLI if not exists\"")
                .build();


        GitLabYaml.Job job = GitLabYaml.Job.builder()
                .cache(GitLabYaml.Cache.builder().key("key")
                        .paths(List.of("a", "b"))
                        .policy(GitLabYaml.Cache.Policy.PULL)
                        .build())
                .stage(GitLabYaml.Stage.BUILD_LST)
                .variables(Map.of("GITLAB_HOST", "gitlab.com"))
                .command("echo \"Building LST\"")
                .build();


        GitLabYaml.Pipeline pipeline = GitLabYaml.Pipeline.builder()
                .image("ruby:latest")
                .stage(GitLabYaml.Stage.DOWNLOAD)
                .download(download)
                .stage(GitLabYaml.Stage.BUILD_LST)
                .job("build-a", job)
                .job("build-b", job)
                .build();

        String yaml = write(pipeline);

        //language=yaml
        String expected = """
                image: ruby:latest
                stages:
                - download
                - build-lst
                download:
                  cache:
                    key: cli-v4.4.0
                    paths:
                    - mod
                    policy: pull-push
                  stage: download
                  variables:
                    GITLAB_HOST: gitlab.com
                  before_script: []
                  script:
                  - echo "download CLI if not exists"
                build-a:
                  cache:
                    key: key
                    paths:
                    - a
                    - b
                    policy: pull
                  stage: build-lst
                  variables:
                    GITLAB_HOST: gitlab.com
                  before_script: []
                  script:
                  - echo "Building LST"
                build-b:
                  cache:
                    key: key
                    paths:
                    - a
                    - b
                    policy: pull
                  stage: build-lst
                  variables:
                    GITLAB_HOST: gitlab.com
                  before_script: []
                  script:
                  - echo "Building LST"
                """;
        assertThat(yaml).isEqualTo(expected);

    }

}