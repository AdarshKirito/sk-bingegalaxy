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
                    sh 'mvn test'
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
                stage('Discovery Server') {
                    steps {
                        dir('backend/discovery-server') {
                            sh "docker build -t ${DOCKER_REGISTRY}/discovery-server:${GIT_COMMIT_SHORT} ."
                        }
                    }
                }
                stage('Config Server') {
                    steps {
                        dir('backend/config-server') {
                            sh "docker build -t ${DOCKER_REGISTRY}/config-server:${GIT_COMMIT_SHORT} ."
                        }
                    }
                }
                stage('API Gateway') {
                    steps {
                        dir('backend/api-gateway') {
                            sh "docker build -t ${DOCKER_REGISTRY}/api-gateway:${GIT_COMMIT_SHORT} ."
                        }
                    }
                }
                stage('Auth Service') {
                    steps {
                        dir('backend/auth-service') {
                            sh "docker build -t ${DOCKER_REGISTRY}/auth-service:${GIT_COMMIT_SHORT} ."
                        }
                    }
                }
                stage('Availability Service') {
                    steps {
                        dir('backend/availability-service') {
                            sh "docker build -t ${DOCKER_REGISTRY}/availability-service:${GIT_COMMIT_SHORT} ."
                        }
                    }
                }
                stage('Booking Service') {
                    steps {
                        dir('backend/booking-service') {
                            sh "docker build -t ${DOCKER_REGISTRY}/booking-service:${GIT_COMMIT_SHORT} ."
                        }
                    }
                }
                stage('Payment Service') {
                    steps {
                        dir('backend/payment-service') {
                            sh "docker build -t ${DOCKER_REGISTRY}/payment-service:${GIT_COMMIT_SHORT} ."
                        }
                    }
                }
                stage('Notification Service') {
                    steps {
                        dir('backend/notification-service') {
                            sh "docker build -t ${DOCKER_REGISTRY}/notification-service:${GIT_COMMIT_SHORT} ."
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
                        for svc in discovery-server config-server api-gateway auth-service availability-service booking-service payment-service notification-service frontend; do
                            docker push ${DOCKER_REGISTRY}/${svc}:${GIT_COMMIT_SHORT}
                        done
                    '''
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
                    for svc in discovery-server config-server api-gateway auth-service availability-service booking-service payment-service notification-service frontend; do
                        echo "Scanning ${DOCKER_REGISTRY}/${svc}:${GIT_COMMIT_SHORT}..."
                        trivy image --exit-code 1 --severity HIGH,CRITICAL ${DOCKER_REGISTRY}/${svc}:${GIT_COMMIT_SHORT}
                    done
                '''
            }
        }

        stage('Verify Flyway Migrations') {
            steps {
                dir('backend') {
                    sh '''
                        set -euo pipefail
                        echo "Verifying Flyway migration checksums..."
                        for svc in auth-service availability-service booking-service payment-service; do
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
                            for stateful in mongodb kafka; do
                                kubectl rollout status statefulset/${stateful} -n sk-binge-galaxy --timeout=300s
                            done
                            kubectl delete job mongodb-rs-init -n sk-binge-galaxy --ignore-not-found=true
                            kubectl apply -f .rendered-k8s/mongodb.yml
                            kubectl wait --for=condition=complete job/mongodb-rs-init -n sk-binge-galaxy --timeout=300s
                            for svc in discovery-server config-server api-gateway auth-service availability-service booking-service payment-service notification-service frontend; do
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
