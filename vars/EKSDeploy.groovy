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
            PROJECT = "roboshop"
            COMPONENT = "catalogue"
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
                                aws eks update-kubeconfig --region us-east-1 --name ellamma-${PROJECT}-${deploy_to}
                                kubectl get nodes
                                sed -i "s/IMAGE_VERSION/${appVersion}
                            """
                        }
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
                echo "Build and Test completed successfully!"
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