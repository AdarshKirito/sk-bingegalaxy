pipeline {
    agent any

    environment {
        DOCKER_REGISTRY = 'skbingegalaxy'
        DOCKER_CREDENTIALS_ID = 'docker-hub-creds'
        KUBE_CONFIG_ID = 'kubeconfig'
    }

    tools {
        maven 'Maven-3.9'
        nodejs 'Node-20'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
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
                dir('frontend') {
                    sh 'npm ci'
                    sh 'npm run build'
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
                            sh 'npm audit --audit-level=high || true'
                        }
                        dir('backend') {
                            sh 'mvn org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7 || true'
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
                            sh "docker build -t ${DOCKER_REGISTRY}/discovery-server:${BUILD_NUMBER} -t ${DOCKER_REGISTRY}/discovery-server:latest ."
                        }
                    }
                }
                stage('Config Server') {
                    steps {
                        dir('backend/config-server') {
                            sh "docker build -t ${DOCKER_REGISTRY}/config-server:${BUILD_NUMBER} -t ${DOCKER_REGISTRY}/config-server:latest ."
                        }
                    }
                }
                stage('API Gateway') {
                    steps {
                        dir('backend/api-gateway') {
                            sh "docker build -t ${DOCKER_REGISTRY}/api-gateway:${BUILD_NUMBER} -t ${DOCKER_REGISTRY}/api-gateway:latest ."
                        }
                    }
                }
                stage('Auth Service') {
                    steps {
                        dir('backend/auth-service') {
                            sh "docker build -t ${DOCKER_REGISTRY}/auth-service:${BUILD_NUMBER} -t ${DOCKER_REGISTRY}/auth-service:latest ."
                        }
                    }
                }
                stage('Availability Service') {
                    steps {
                        dir('backend/availability-service') {
                            sh "docker build -t ${DOCKER_REGISTRY}/availability-service:${BUILD_NUMBER} -t ${DOCKER_REGISTRY}/availability-service:latest ."
                        }
                    }
                }
                stage('Booking Service') {
                    steps {
                        dir('backend/booking-service') {
                            sh "docker build -t ${DOCKER_REGISTRY}/booking-service:${BUILD_NUMBER} -t ${DOCKER_REGISTRY}/booking-service:latest ."
                        }
                    }
                }
                stage('Payment Service') {
                    steps {
                        dir('backend/payment-service') {
                            sh "docker build -t ${DOCKER_REGISTRY}/payment-service:${BUILD_NUMBER} -t ${DOCKER_REGISTRY}/payment-service:latest ."
                        }
                    }
                }
                stage('Notification Service') {
                    steps {
                        dir('backend/notification-service') {
                            sh "docker build -t ${DOCKER_REGISTRY}/notification-service:${BUILD_NUMBER} -t ${DOCKER_REGISTRY}/notification-service:latest ."
                        }
                    }
                }
                stage('Frontend') {
                    steps {
                        dir('frontend') {
                            sh "docker build -t ${DOCKER_REGISTRY}/frontend:${BUILD_NUMBER} -t ${DOCKER_REGISTRY}/frontend:latest ."
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
                            docker push ${DOCKER_REGISTRY}/${svc}:${BUILD_NUMBER}
                            docker push ${DOCKER_REGISTRY}/${svc}:latest
                        done
                    '''
                }
            }
        }

        stage('Container Image Scan') {
            steps {
                sh '''
                    for svc in discovery-server config-server api-gateway auth-service availability-service booking-service payment-service notification-service frontend; do
                        echo "Scanning ${DOCKER_REGISTRY}/${svc}:${BUILD_NUMBER}..."
                        trivy image --exit-code 0 --severity HIGH,CRITICAL ${DOCKER_REGISTRY}/${svc}:${BUILD_NUMBER} || true
                    done
                '''
            }
        }

        stage('Deploy to Kubernetes') {
            steps {
                withKubeConfig(credentialsId: KUBE_CONFIG_ID) {
                    sh '''
                        kubectl apply -f k8s/namespace.yml
                        kubectl apply -f k8s/secrets.yml
                        kubectl apply -f k8s/postgres.yml
                        kubectl apply -f k8s/mongodb.yml
                        kubectl apply -f k8s/kafka.yml
                        kubectl apply -f k8s/network-policy.yml
                        kubectl apply -f k8s/rbac.yml
                        kubectl apply -f k8s/infrastructure.yml
                        kubectl apply -f k8s/services.yml
                        kubectl apply -f k8s/frontend.yml
                        kubectl apply -f k8s/hpa.yml
                        kubectl apply -f k8s/pdb.yml
                        kubectl apply -f k8s/monitoring.yml

                        # Rolling update with new image tags
                        for svc in discovery-server config-server api-gateway auth-service availability-service booking-service payment-service notification-service frontend; do
                            kubectl set image deployment/${svc} ${svc}=${DOCKER_REGISTRY}/${svc}:${BUILD_NUMBER} -n sk-binge-galaxy || true
                        done
                    '''
                }
            }
        }

        stage('Verify Deployment') {
            steps {
                withKubeConfig(credentialsId: KUBE_CONFIG_ID) {
                    sh '''
                        echo "Waiting for rollouts to complete..."
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
