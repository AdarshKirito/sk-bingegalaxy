pipeline {
    agent any

    environment {
        DOCKER_REGISTRY = 'skbingegalaxy'
        DOCKER_CREDENTIALS_ID = 'docker-hub-creds'
        KUBE_CONFIG_ID = 'kubeconfig'
        PRODUCTION_ENV_CREDENTIALS_ID = 'production-env'
        K8S_NAMESPACE = 'sk-binge-galaxy'
        CERT_MANAGER_VERSION = 'v1.14.5'
        INGRESS_NGINX_VERSION = 'controller-v1.11.5'
        GIT_COMMIT_SHORT = ''

        // Every image this pipeline ships, in one place. The build/push/scan/rollout
        // loops below all read from these, so a new service can no longer be added to
        // one loop and forgotten in the next four — which is exactly how
        // distribution-service came to be built, migrated and deployed by manifest
        // while never being built, pushed, scanned or verified.
        BACKEND_SERVICES = 'discovery-server config-server api-gateway auth-service availability-service booking-service payment-service notification-service distribution-service'
        ALL_IMAGES = 'discovery-server config-server api-gateway auth-service availability-service booking-service payment-service notification-service distribution-service frontend'
        // Deployments (not statefulsets) whose rollout must be verified.
        K8S_DEPLOYMENTS = 'config-server api-gateway auth-service availability-service booking-service payment-service notification-service distribution-service frontend'

        // The k8s manifests set SPRING_PROFILES_ACTIVE=kubernetes,production (see
        // k8s/namespace.yml), and the `kubernetes` Spring profile disables Eureka and
        // switches every service to spring.cloud.kubernetes discovery. That support
        // ships ONLY under the `kubernetes` MAVEN profile in backend/pom.xml. Building
        // without it produced jars that answer "discovery: kubernetes" with no
        // Kubernetes discovery on the classpath and Eureka switched off — no discovery
        // at all, so the gateway could not route to anything. The Dockerfiles have
        // always accepted MAVEN_EXTRA_ARGS; nothing ever passed it.
        MAVEN_IMAGE_PROFILE = '-Pkubernetes'
    }

    tools {
        maven 'Maven-3.9'
        nodejs 'Node-20'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.GIT_COMMIT_SHORT = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
                }
            }
        }

        stage('Build Backend') {
            steps {
                dir('backend') {
                    sh 'mvn clean package -DskipTests'
                }
            }
        }

        stage('Test Backend') {
            steps {
                dir('backend') {
                    // `verify`, not `test` — deliberately.
                    //
                    // jacoco:check is bound to the verify phase. This stage used to run
                    // `mvn test`, which never reaches it, so the coverage gate had never
                    // executed in CI: it was configured at 0.60/0.50 while four modules
                    // sat at roughly half that, and nothing reported it. A gate nothing
                    // runs is not a gate.
                    //
                    // Thresholds are now a per-module RATCHET at each module's measured
                    // baseline (see backend/pom.xml). The build therefore fails on a
                    // coverage REGRESSION — the failure that actually matters — while the
                    // 0.60/0.50 target is raised module by module as tests are added.
                    //
                    // -Dtestcontainers.enabled=true turns ON the database-level tests
                    // (OccupancyBackstopIT, OccupancyContentionIT). They are gated OFF by
                    // default so a contributor without Docker still gets a green build;
                    // CI has Docker, so CI opts in — otherwise the occupancy trigger and
                    // the Flyway chain would have no automated coverage (TEST-01).
                    sh 'mvn verify -Dtestcontainers.enabled=true'
                }
            }
            post {
                always {
                    junit '**/target/surefire-reports/*.xml'
                }
            }
        }

        stage('Build Frontend') {
            steps {
                withCredentials([file(credentialsId: PRODUCTION_ENV_CREDENTIALS_ID, variable: 'PRODUCTION_ENV_FILE')]) {
                    dir('frontend') {
                        sh '''
                            set -euo pipefail
                            set -a
                            . "$PRODUCTION_ENV_FILE"
                            set +a
                            export VITE_GOOGLE_CLIENT_ID="$GOOGLE_CLIENT_ID"
                            npm ci
                            npm run build
                        '''
                    }
                }
            }
        }

        stage('Test Frontend') {
            steps {
                dir('frontend') {
                    sh 'npm test -- --run --reporter=default'
                }
            }
        }

        stage('Security Scan') {
            parallel {
                stage('Dependency Audit') {
                    steps {
                        dir('frontend') {
                            sh 'npm audit --audit-level=high'
                        }
                        dir('backend') {
                            sh 'mvn org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7'
                        }
                    }
                }
            }
        }

        stage('Build Docker Images') {
            parallel {
                // ── Backend images ───────────────────────────────────────────────
                //
                // Context is `backend/`, NOT the service directory.
                //
                // Every backend Dockerfile does `COPY . .` followed by
                // `mvn package -pl <service> -am`, so it needs the aggregator pom and
                // the common-lib module in its context. Building from
                // `backend/<service>` gave Docker a context containing neither, and
                // `-am` had nothing to resolve the parent from: every backend image
                // build in this pipeline failed at the first Maven step. docker-compose
                // has always done this correctly (context: backend, dockerfile:
                // <service>/Dockerfile) — the pipeline simply disagreed with it.
                //
                // Sequential, not one parallel stage per service: every backend
                // Dockerfile shares the BuildKit cache mount at /root/.m2, and
                // concurrent Maven processes writing one local repository is a known
                // way to corrupt it. The layer cache still makes the shared `COPY . .`
                // and dependency-resolution work cheap after the first service.
                stage('Backend Images') {
                    steps {
                        dir('backend') {
                            sh '''
                                # -eu, not -euo pipefail: Jenkins runs `sh` steps with
                                # /bin/sh, which is dash on Debian agents, and dash exits
                                # with "Illegal option -o pipefail". There is no pipe in
                                # this block, so pipefail buys nothing anyway.
                                set -eu
                                for svc in ${BACKEND_SERVICES}; do
                                    echo "Building ${svc}..."
                                    # One line on purpose: a backslash continuation inside a
                                    # Groovy triple-quoted string is consumed by Groovy, not
                                    # passed to the shell.
                                    docker build -f ${svc}/Dockerfile --build-arg MAVEN_EXTRA_ARGS="${MAVEN_IMAGE_PROFILE}" -t ${DOCKER_REGISTRY}/${svc}:${GIT_COMMIT_SHORT} .
                                done
                            '''
                        }
                    }
                }
                stage('Frontend') {
                    steps {
                        withCredentials([file(credentialsId: PRODUCTION_ENV_CREDENTIALS_ID, variable: 'PRODUCTION_ENV_FILE')]) {
                            dir('frontend') {
                                sh """
                                    set -euo pipefail
                                    set -a
                                    . "\$PRODUCTION_ENV_FILE"
                                    set +a
                                    docker build --build-arg VITE_GOOGLE_CLIENT_ID="\$GOOGLE_CLIENT_ID" \
                                                                            -t ${DOCKER_REGISTRY}/frontend:${GIT_COMMIT_SHORT} .
                                """
                            }
                        }
                    }
                }
            }
        }

        stage('Push Docker Images') {
            steps {
                withCredentials([usernamePassword(credentialsId: DOCKER_CREDENTIALS_ID, usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                    sh "echo \$DOCKER_PASS | docker login -u \$DOCKER_USER --password-stdin"
                    sh '''
                        set -eu
                        for svc in ${ALL_IMAGES}; do
                            docker push ${DOCKER_REGISTRY}/${svc}:${GIT_COMMIT_SHORT}
                        done
                    '''
                    sh 'docker logout'
                }
            }
        }

        stage('Container Image Scan') {
            steps {
                sh '''
                    if ! command -v trivy &>/dev/null; then
                        echo "ERROR: trivy is required on this Jenkins agent for production image scanning."
                        echo "Install trivy before running this pipeline: https://aquasecurity.github.io/trivy/"
                        exit 1
                    fi
                    for svc in ${ALL_IMAGES}; do
                        echo "Scanning ${DOCKER_REGISTRY}/${svc}:${GIT_COMMIT_SHORT}..."
                        trivy image --exit-code 1 --severity HIGH,CRITICAL ${DOCKER_REGISTRY}/${svc}:${GIT_COMMIT_SHORT}
                    done
                '''
            }
        }

        stage('Migration Safety Check') {
            steps {
                sh '''
                    set -euo pipefail
                    chmod +x scripts/check-migration-safety.sh
                    # Block any migration with DROP/TRUNCATE/DELETE that lacks the explicit override tag.
                    # This prevents accidental destructive DDL from reaching production.
                    bash scripts/check-migration-safety.sh backend
                '''
            }
        }

        stage('Verify Flyway Migrations') {
            steps {
                dir('backend') {
                    sh '''
                        set -euo pipefail
                        echo "Verifying Flyway migration checksums..."
                        # distribution-service now exposes a real API (the OCTO supplier
                        # surface plus the venue console) and has k8s/services.yml
                        # coverage, so it is built, pushed, scanned, deployed and
                        # verified alongside everything else. The comment that used to
                        # live here explained why it was validated but not shipped; that
                        # is no longer the case, and leaving the exemption in place is
                        # what kept a service with 22 endpoints out of production.
                        for svc in auth-service availability-service booking-service payment-service distribution-service; do
                            echo "Checking ${svc} migrations..."
                            mvn -pl ${svc} flyway:validate -Dflyway.validateMigrationNaming=true -DskipTests || {
                                echo "ERROR: Flyway validation failed for ${svc}"
                                exit 1
                            }
                        done
                        echo "All Flyway migrations validated."
                    '''
                }
            }
        }

        stage('Deploy to Kubernetes') {
            steps {
                withKubeConfig(credentialsId: KUBE_CONFIG_ID) {
                    withCredentials([file(credentialsId: PRODUCTION_ENV_CREDENTIALS_ID, variable: 'PRODUCTION_ENV_FILE')]) {
                        sh '''
                            set -euo pipefail
                            set -a
                            . "$PRODUCTION_ENV_FILE"
                            set +a

                            : "${MANAGED_POSTGRES_HOST:?MANAGED_POSTGRES_HOST must be set for production deployment}"

                            kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/${INGRESS_NGINX_VERSION}/deploy/static/provider/cloud/deploy.yaml
                            kubectl rollout status deployment/ingress-nginx-controller -n ingress-nginx --timeout=300s

                            kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/${CERT_MANAGER_VERSION}/cert-manager.yaml
                            kubectl rollout status deployment/cert-manager -n cert-manager --timeout=300s
                            kubectl rollout status deployment/cert-manager-webhook -n cert-manager --timeout=300s
                            kubectl rollout status deployment/cert-manager-cainjector -n cert-manager --timeout=300s

                            bash scripts/render-k8s-manifests.sh "$PRODUCTION_ENV_FILE" .rendered-k8s "${GIT_COMMIT_SHORT}"

                            kubectl apply -f .rendered-k8s/namespace.yml
                            bash scripts/sync-k8s-secrets.sh "$PRODUCTION_ENV_FILE" "${K8S_NAMESPACE}"
                            kubectl apply -f .rendered-k8s/cert-manager.yml
                            kubectl apply -f .rendered-k8s/postgres-managed.yml
                            kubectl apply -f .rendered-k8s/mongodb.yml
                            kubectl apply -f .rendered-k8s/kafka.yml
                            kubectl apply -f .rendered-k8s/network-policy.yml
                            kubectl apply -f .rendered-k8s/rbac.yml
                            kubectl apply -f .rendered-k8s/infrastructure.yml
                            kubectl apply -f .rendered-k8s/services.yml
                            kubectl apply -f .rendered-k8s/frontend.yml
                            kubectl apply -f .rendered-k8s/hpa.yml
                            kubectl apply -f .rendered-k8s/pdb.yml
                            kubectl apply -f .rendered-k8s/monitoring.yml
                            kubectl apply -f .rendered-k8s/backups.yml
                        '''
                    }
                }
            }
        }

        stage('Verify Deployment') {
            steps {
                withKubeConfig(credentialsId: KUBE_CONFIG_ID) {
                    withCredentials([file(credentialsId: PRODUCTION_ENV_CREDENTIALS_ID, variable: 'PRODUCTION_ENV_FILE')]) {
                        sh '''
                            set -euo pipefail
                            set -a
                            . "$PRODUCTION_ENV_FILE"
                            set +a

                            echo "Waiting for rollouts to complete..."
                            for stateful in discovery-server mongodb kafka; do
                                kubectl rollout status statefulset/${stateful} -n sk-binge-galaxy --timeout=300s
                            done
                            kubectl delete job mongodb-rs-init -n sk-binge-galaxy --ignore-not-found=true
                            kubectl apply -f .rendered-k8s/mongodb.yml
                            kubectl wait --for=condition=complete job/mongodb-rs-init -n sk-binge-galaxy --timeout=300s
                            for svc in ${K8S_DEPLOYMENTS}; do
                                kubectl rollout status deployment/${svc} -n sk-binge-galaxy --timeout=180s || {
                                    echo "ROLLBACK: ${svc} deployment failed, rolling back..."
                                    kubectl rollout undo deployment/${svc} -n sk-binge-galaxy
                                    exit 1
                                }
                            done
                            echo "All deployments verified successfully."
                        '''
                    }
                }
            }
        }
    }

    post {
        success {
            echo 'Pipeline completed successfully!'
        }
        failure {
            echo 'Pipeline failed!'
        }
        always {
            cleanWs()
        }
    }
}
