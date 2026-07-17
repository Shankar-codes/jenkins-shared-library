def call (Map configMap){
    pipeline {
        agent {
            node {
                label 'AGENT-1'
            }
        }

        environment { 
            COURSE = 'Jenkins'
            appVersion = configMap.get("appVersion")
            deploy_to = configMap.get("deploy_to")
            ACC_ID = "367012942501"
            PROJECT = configMap.get("project")
            COMPONENT = configMap.get("component")
        }

        options {
            timeout(time: 1, unit: 'HOURS') 
            disableConcurrentBuilds()
        }

        // parameters {
        //     string(name: 'appVersion', description: 'what app version you want to deploy')
        //     choice(name: 'deploy_to', choices: ['dev','qa','prod'], description: 'pick something')
        // }

    // this is build section. added comment for just webhook checking
        stages {
            stage('Deploy') {
                steps {
                    withAWS(region:'us-east-1',credentials:'aws-creds'){
                        script {
                            sh """
                                set -e
                                aws eks update-kubeconfig --region us-east-1 --name ellamma-${PROJECT}-${deploy_to}
                                kubectl get nodes
                                cat values.yaml
                                sed -i "s/IMAGE_VERSION/${appVersion}/g" values.yaml
                                cat values.yaml
                                echo
                                echo "appVersion=${appVersion}"
                                helm upgrade --install ${COMPONENT} -f values-${deploy_to}.yaml -n ellamma-ecommerce --atomic --wait --timeout=5m .
                            """
                        }
                    }
                }
            }

            stage("Function test") {
                steps{
                    script {
                        sh """
                            echo "Functional tests in DEV environment"
                        """
                    }
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