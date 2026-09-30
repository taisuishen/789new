// CI/CD on a self-hosted Jenkins next to the GitLab server (docs/go-live.md §4).
//
// Agent (label "docker"): Linux with Docker and kubectl. Maven and the JDK come from the official Maven image, so
// nothing else is installed on the agent. Run the agent directly on the host (not inside a container), otherwise the
// workspace path mounted into the Maven container does not exist on the Docker host.
// Network: GitLab (webhook), Maven Central / Docker Hub, SWR (HTTPS), and the CCE API server (private address over
// the site-to-site VPN).
//
// Jenkins credentials:
//   swr-login              username/password: SWR long-term login ("<region>@<AK>" / login key, SWR console)
//   kubeconfig-staging     secret file: kubeconfig of the staging cluster
//   kubeconfig-prod        secret file: kubeconfig of the production cluster (IAM user that may only update
//                          workloads in namespace bingo)
//
// Only the images are updated (kubectl set image). Manifests (HPA, ConfigMaps, ...) are applied by hand, see
// deploy/README.md §7: re-applying an HPA in the middle of a peak resets its minReplicas.

def services() {
    params.SERVICES.split(',').collect { it.trim() }.findAll { it }
}

def moduleDir(String service) {
    service == 'bingo-gateway' ? service : "${service}/${service}-service"
}

def workloadOf(String service) {
    // bingo-gateway writes no rows; every other service is a StatefulSet (WORKER_ID = pod ordinal)
    service == 'bingo-gateway' ? "deployment/${service}" : "statefulset/${service}"
}

pipeline {
    agent { label 'docker' }

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timeout(time: 90, unit: 'MINUTES')
    }

    parameters {
        // deployment order: the wallet before its callers, the gateway last
        string(name: 'SERVICES', defaultValue: 'bingo-wallet,bingo-user,bingo-kyc,bingo-gateway',
                description: 'Comma-separated services to build and deploy, in deployment order')
        booleanParam(name: 'RUN_IT', defaultValue: true,
                description: 'Also run the Testcontainers integration tests (-Pit)')
        choice(name: 'DEPLOY_TO', choices: ['none', 'staging', 'prod'],
                description: 'Update the images in this environment after the build')
    }

    environment {
        // swr.<region>.myhuaweicloud.com/<organisation>
        REGISTRY = 'swr.ap-southeast-1.myhuaweicloud.com/bingo'
        MAVEN_IMAGE = 'maven:3.9-eclipse-temurin-25'
        OTEL_AGENT_VERSION = '2.31.1'
    }

    stages {
        stage('Test and package') {
            steps {
                script {
                    env.TAG = sh(script: 'git rev-parse --short=12 HEAD', returnStdout: true).trim()
                    env.MAVEN_PROFILE = params.RUN_IT ? '-Pit' : ''
                }
                // Runs as the agent user (workspace files stay deletable); the Docker socket is for Testcontainers.
                sh '''
                    mkdir -p "$HOME/.m2"
                    docker run --rm -u "$(id -u):$(id -g)" --group-add "$(stat -c %g /var/run/docker.sock)" \
                      -v "$WORKSPACE:/src" -v "$HOME/.m2:/var/maven/.m2" -e MAVEN_CONFIG=/var/maven/.m2 \
                      -v /var/run/docker.sock:/var/run/docker.sock \
                      -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true \
                      --add-host host.docker.internal:host-gateway \
                      -w /src "$MAVEN_IMAGE" mvn -B -ntp -Duser.home=/var/maven $MAVEN_PROFILE clean verify
                '''
            }
            post {
                always {
                    junit allowEmptyResults: true,
                            testResults: '**/target/surefire-reports/*.xml,**/target/failsafe-reports/*.xml'
                }
            }
        }

        stage('Images') {
            steps {
                sh '''
                    mkdir -p otel
                    [ -s otel/opentelemetry-javaagent.jar ] || curl -fsSL -o otel/opentelemetry-javaagent.jar \
                      "https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/$OTEL_AGENT_VERSION/opentelemetry-javaagent-$OTEL_AGENT_VERSION.jar"
                '''
                withCredentials([usernamePassword(credentialsId: 'swr-login',
                        usernameVariable: 'SWR_USER', passwordVariable: 'SWR_PASSWORD')]) {
                    sh 'echo "$SWR_PASSWORD" | docker login -u "$SWR_USER" --password-stdin "${REGISTRY%%/*}"'
                }
                script {
                    for (String service : services()) {
                        // the Spring Boot fat jar (the plain one is *.jar.original)
                        sh """
                            JAR=\$(ls ${moduleDir(service)}/target/*.jar)
                            docker build --build-arg JAR="\$JAR" -t "\$REGISTRY/${service}:\$TAG" .
                            docker push "\$REGISTRY/${service}:\$TAG"
                        """
                    }
                }
            }
        }

        stage('Approve') {
            when { expression { params.DEPLOY_TO == 'prod' } }
            steps {
                input message: "Deploy ${env.TAG} (${params.SERVICES}) to production?", ok: 'Deploy'
            }
        }

        stage('Deploy') {
            when { expression { params.DEPLOY_TO != 'none' } }
            steps {
                withCredentials([file(credentialsId: "kubeconfig-${params.DEPLOY_TO}", variable: 'KUBECONFIG')]) {
                    script {
                        for (String service : services()) {
                            // container name "app" in every bingo manifest
                            sh """
                                kubectl -n bingo set image ${workloadOf(service)} app="\$REGISTRY/${service}:\$TAG"
                                kubectl -n bingo rollout status ${workloadOf(service)} --timeout=15m
                            """
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            sh 'docker logout "${REGISTRY%%/*}" || true'
        }
    }
}
