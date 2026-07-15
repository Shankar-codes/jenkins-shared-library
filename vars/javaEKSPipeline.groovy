def call (Map configMap){

        pipeline {
            agent {
                node {
                    label 'AGENT-1'
                }
            }

            environment { 
                COURSE = 'Jenkins'
                appVersion = ""
                ACC_ID = "367012942501"
                PROJECT = configMap.get("project")
                COMPONENT = configMap.get("component")
            }

            options {
                timeout(time: 1, unit: 'HOURS') 
                disableConcurrentBuilds()
            }

    // this is build section. added comment for just webhook checking
            stages {
                stage('Read Version') {
                    steps {
                        script {
                            def pom = readMavenPom file: 'pom.xml'
                            appVersion = pom.version
                            echo "app Version: ${appVersion}"
                        }
                    }
                }
                stage('Install Dependencies') {
                    steps {
                        sh """
                            mvn clean package
                        """
                    }
                }

                stage('Unit Test') {
                    steps {
                        sh """
                            echo test
                        """
                    }
                }
        // this is sonar qube SAST(static application security testing) stage.
            /* stage('Sonar Scan') {
                    environment {
                        def scannerHome = tool 'sonar-8.0'
                    }
                    steps {
                        script {
                            // sonarqube server name is sonar-server. this is configured in jenkins global tool configuration. using token for authentication. token is generated from sonarqube server.
                            withSonarQubeEnv('sonar-server') {
                                sh "${scannerHome}/bin/sonar-scanner"
                            }
                    }
                }
                stage('Quality Gate') {
                    steps {
                        timeout(time: 1, unit: 'HOURS') {
                            // Wait for the quality gate status
                            // abortPipeline: true will fail the Jenkins job if the quality gate is 'FAILED'
                            waitForQualityGate abortPipeline: true 
                        }
                    }
                }*/

                stage('Dependabot Security Gate')
                {
                    when {
                        expression { false }
                    }
                    environment {
                        GITHUB_OWNER = 'Shankar-codes'
                        GITHUB_REPO  = 'catalogue'
                        GITHUB_API   = 'https://api.github.com'
                        GITHUB_TOKEN = credentials('GITHUB_TOKEN')
                    }
                    steps {
                        script{
                            /* Use sh """ when you want to use Groovy variables inside the shell.
                            Use sh ''' when you want the script to be treated as pure shell. */
                            sh '''
                            echo "Fetching Dependabot alerts..."

                            response=$(curl -s \
                                -H "Authorization: token ${GITHUB_TOKEN}" \
                                -H "Accept: application/vnd.github+json" \
                                "${GITHUB_API}/repos/${GITHUB_OWNER}/${GITHUB_REPO}/dependabot/alerts?per_page=100")

                            echo "${response}" > dependabot_alerts.json

                            high_critical_open_count=$(echo "${response}" | jq '[.[] 
                                | select(
                                    .state == "open"
                                    and (.security_advisory.severity == "high"
                                        or .security_advisory.severity == "critical")
                                )
                            ] | length')

                            echo "Open HIGH/CRITICAL Dependabot alerts: ${high_critical_open_count}"

                            if [ "${high_critical_open_count}" -gt 0 ]; then
                                echo "❌ Blocking pipeline due to OPEN HIGH/CRITICAL Dependabot alerts"
                                echo "Affected dependencies:"
                                echo "$response" | jq '.[] 
                                | select(.state=="open" 
                                and (.security_advisory.severity=="high" 
                                or .security_advisory.severity=="critical"))
                                | {dependency: .dependency.package.name, severity: .security_advisory.severity, advisory: .security_advisory.summary}'
                                exit 1
                            else
                                echo "✅ No OPEN HIGH/CRITICAL Dependabot alerts found"
                            fi
                            '''
                            
                        }
                    }
                }

                stage('Build Image') {
                    steps {
                        script{
                            withAWS(region:'us-east-1',credentials:'aws-creds') {
                                sh """
                                    aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com
                                    docker build -t ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion} .
                                    docker images
                                    docker push ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion}
                                """
                            }
                        }
                    }
                }

                stage('Trigger DEV Deploy') {
                    steps{
                        script{
                            build job: '../${COMPONENT}-deploy',
                                wait: true, // wait for completion
                                propagate: false, // Propagate status
                                parameters: [
                                    string(name: 'appVersion', value: "${appVersion}"),
                                    string(name: 'deploy_to', value: "dev")
                                    ]
                        }
                    }
                }

                // stage('Trivy Scan'){
                //     steps {
                //         script{
                //             sh """
                //                 trivy image \
                //                 --scanners vuln \
                //                 --severity HIGH,CRITICAL,MEDIUM \
                //                 --pkg-types os \
                //                 --exit-code 1 \
                //                 --no-progress \
                //                 --format table \
                //                 ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion}
                //             """
                //         }
                //     }
                // }
                stage('Deploy') {
                    when { 
                        expression { "$params.DEPLOY" == "true" }
                        }

                    steps {
                        echo "Deploying..."
                    }
                }

            }
            post { 
                always { 
                    echo 'I will always say Hello again!'
                    cleanWs()
                }
                success{
                    script {
                        withCredentials([string(credentialsId: 'slack-token', variable: 'SLACK_WEBHOOK')]) {

                            def payload = """
                            {
                            "attachments": [
                                {
                                "color": "#2eb886",
                                "title": "✅ Jenkins Build Successful",
                                "fields": [
                                    {
                                    "title": "Job Name",
                                    "value": "${env.JOB_NAME}",
                                    "short": true
                                    },
                                    {
                                    "title": "Build Number",
                                    "value": "${env.BUILD_NUMBER}",
                                    "short": true
                                    },
                                    {
                                    "title": "Status",
                                    "value": "SUCCESS",
                                    "short": true
                                    },
                                    {
                                    "title": "Build URL",
                                    "value": "${env.BUILD_URL}",
                                    "short": false
                                    }
                                ],
                                "footer": "Jenkins CI",
                                "ts": ${System.currentTimeMillis() / 1000}
                                }
                            ]
                            }
                            """

                            sh """
                            curl -X POST \
                            -H 'Content-type: application/json' \
                            --data '${payload}' \
                            ${SLACK_WEBHOOK}
                            """
                        }
                    }
                }
                failure{
                    echo "Build or Test failed!"
                }
                aborted{
                    echo "Build or Test aborted!"
                }
            }
        }

}