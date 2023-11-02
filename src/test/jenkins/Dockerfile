FROM jenkins/jenkins:2.401.1-lts-jdk11
ENV JAVA_OPTS -Djenkins.install.runSetupWizard=false
ENV CASC_JENKINS_CONFIG /var/jenkins_home/casc.yaml
COPY casc.yaml /var/jenkins_home/casc.yaml
RUN jenkins-plugin-cli --plugins \
    adoptopenjdk:1.5 \
    cloudbees-folder:6.815.v0dd5a_cb_40e0e \
    configuration-as-code:1647.ve39ca_b_829b_42 \
    git:5.1.0 \
    git-client:4.4.0 \
    gradle:2.8 \
    credentials-binding:636.v55f1275c7b_27 \
    config-file-provider:953.v0432a_802e4d2 \
    jdk-tool:66.vd8fa_64ee91b_d \
    pipeline-model-definition:2.2121.vd87fb_6536d1e \
    workflow-cps:3791.va_c0338ea_b_59c \
    workflow-job:1326.ve643e00e9220 \
    ws-cleanup:0.45

