package io.moderne.connect.commands;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openrewrite.internal.StringUtils;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

public class JenkinsTest {
    private static final String JENKINS_HOST = "https://my.jenkins";
    private static final String JENKINS_API_TOKEN = "blahblah";
    private static final String GIT_CREDS = "myGitCreds";
    private static final String JENKINS_TESTING_USER = "admin";

    private final Jenkins jenkins = new Jenkins();

    @BeforeEach
    void setupJenkins() {
        jenkins.csvFile = Paths.get("src/test/csv/repos-without-java.csv").toAbsolutePath();
        jenkins.controllerUrl = JENKINS_HOST;
        jenkins.jenkinsUser = JENKINS_TESTING_USER;
        jenkins.userSecret = new Jenkins.UserSecret();
        jenkins.userSecret.apiToken = JENKINS_API_TOKEN;
        jenkins.gitCredentialsId = GIT_CREDS;
    }

    @Test
    void modConfigModerne() {
        jenkins.tenant = new Jenkins.Tenant();
        jenkins.tenant.moderneUrl = "https://app.moderne.io";
        jenkins.tenant.moderneToken = "modToken";

        jenkins.publishUrl = "https://my.artifactory/moderne-ingest";
        jenkins.publishCredsId ="artifactCreds";

        String actual = StringUtils.trimIndent(jenkins.createStagePublish("", "", "", ""));
        //language=groovy
        assertThat(actual).isEqualToIgnoringWhitespace("""
                stage('Publish') {
                    steps {
                        withCredentials([string(credentialsId: 'modToken', variable: 'MODERNE_TOKEN')]) {
                            sh 'mod config moderne https://app.moderne.io --token ${MODERNE_TOKEN}'
                        }
                        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'artifactCreds', usernameVariable: 'ARTIFACTS_PUBLISH_CRED_USR', passwordVariable: 'ARTIFACTS_PUBLISH_CRED_PWD']]) {
                            sh 'mod config artifacts https://my.artifactory/moderne-ingest --user ${ARTIFACTS_PUBLISH_CRED_USR} --password ${ARTIFACTS_PUBLISH_CRED_PWD}'
                        }
                        sh 'mod build . --no-download'
                        sh 'mod publish .'
                    }
                }
                """
        );
    }
}
