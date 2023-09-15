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
    credentials-binding:631.v861c06d062b_4 \
    config-file-provider:953.v0432a_802e4d2 \
    jdk-tool:66.vd8fa_64ee91b_d \
    pipeline-model-definition:2.2121.vd87fb_6536d1e \
    workflow-cps:3691.v28b_14c465a_b_b_ \
    workflow-job:1308.v58d48a_763b_31 \
    ws-cleanup:0.45

