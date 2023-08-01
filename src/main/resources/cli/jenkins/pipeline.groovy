pipeline {
    agent %s
    options {
        timeout(time: 1, unit: 'HOURS')
        disableConcurrentBuilds(abortPrevious: true)
    }
    triggers { cron('%s') }
    stages {
%s
%s
%s
    }
    post {
        always {
            cleanWs()
        }
    }
}
