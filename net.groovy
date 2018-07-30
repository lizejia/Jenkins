#!groovy

// 发布分支（默认develop）
def publishBranch = 'develop'
// 本次构建是否是回滚
def rollback = false
// 发布环境 [GQC,PRE,PRD]
def publishEnv = 'GQC'
//标签
def _TagNum 
//是否发布
def _bPublish = true
//是否静态扫描
def _bExceSonar = true

//获取代码并Build Agent名称
def GetCodeAndBuild_AgentName = 'BuildAgent_RomanPC'
//发布Agent名称
def Deploy_AgentName = ''

//凭证ID
def gitcredentialsId = '788cce82-9281-4866-9c9e-e943ca710c43'
def GitUrl = 'https://github.com/lizejia/Jenkins.Net.Web.git'

//docker镜像仓库登录账号密码
def dockercredentialsId = '097c174e-94cf-4936-aac8-29e6f1103b40'
//docker镜像仓库-地址
def docker_hub_url = '10.66.2.58'
//docker镜像仓库-命名空间
def docker_hub_namespace = 'ci4xa'
//docker镜像仓库-名称(也是容器名称)
def docker_hub_container_name = 'tmsapi'

def Ip = ''
def JsonList 
def Envconf 
def Webconf
//配置json
def JsonText = """{
  'GQC': [
    {
        'IP': '10.66.16.225',
        'Deploy_AgentName': 'xcxapi_windows_deploy_10.66.16.225',
        'apiSiteIISName': 'xcx-tms.gznb.com',
		'apiSiteIISPath': 'D:\\\\xcx_Server'
    }
  ],
  'PRE': [
    {
        'IP': '10.66.16.225',
        'Deploy_AgentName': 'xcxapi_windows_deploy_10.66.16.225',
        'apiSiteIISName': 'xcx-tms.gznb.com',
		'apiSiteIISPath': 'D:\\\\xcx_Server'
    }
  ]
}"""

node ('master'){
  JsonList = readJSON text: JsonText
  println JsonList
}


def Publish_IP
def Publish_All_Version

//编译git--目录
def SolutionFolderName = "Solution"
def ProjectFileName = "${SolutionFolderName}\\Jenkins.Net.Web.sln"
def BuildOutFolder = "${SolutionFolderName}\\Jenkins.Net.Web\\bin\\Publish"

// api 发布排除文件
def Exarfiles = []
// IIS 站点名称
def apiSiteIISName= ''
// 发布目录
def apiSiteIISPath = ''

// FUNC_DEFINE START
//邮件模板
def mailTemplate={ String messages ->
  emailext body: '${JELLY_SCRIPT,template="test"}',
	  subject: "${env.JOB_NAME} ${messages} - ${currentBuild.result}!!!",
	  mimeType: 'text/html',
	  from: 'jenkins@gznb.com',
	  replyTo: 'lizejia@gznb.com',
	  to: 'lizejia@gznb.com'
}

//Windows编译服务器
def windowsBuildFun={ String buildOutFolder,
  String projectFile,
  String packageName,
  String slnFolder,
  ArrayList<String> projectExarfiles ->
	try{
		dir(slnFolder){
			bat "nuget restore ."
		}
		bat "msbuild ${projectFile} /t:Rebuild /p:Configuration=${Webconf};PublishProfile=${Webconf};DeployOnBuild=true"
		def exarfiles = projectExarfiles.join(",")
		zip archive: true, dir: "${buildOutFolder}", glob: '', zipFile: "${packageName}"
	}
	catch(ex){
		currentBuild.result = 'FAILURE'
		mailTemplate "${Publish_All_Version} Windows Build "
		throw ex
	}
}

//下载部署包
def downloadZip={ packageName ->
	println "Start unarchive"
	unarchive mapping: [(packageName): packageName]
	println "End unarchive"
}

//Windows服务器IIS发布函数
def deployFunWindows={ String unZipPath,
                String backupPath,
				String packageName,
                String webSiteName->
    try{
        //停止服务
        bat "appcmd stop site \"${webSiteName}\""
        bat "appcmd recycle apppool /apppool.name:${webSiteName}"
		// 防止对应的hosting程序未退出
        sleep time: 10,
        	unit: 'SECONDS'
		if(_bPublish){
			try{
                bat "taskkill /im TMSXcx.WebAPI.dll /f"
			}
			catch(ex){}
		}
        if(rollback){
            // 还原
            unzip dir: "${unZipPath}", glob: '', zipFile: "${backupPath}\\${webSiteName}.zip"
        }
        else{
            // 备份到backupPath
            // 创建不存在文件夹
            dir(unZipPath){
                bat "if not exist ${backupPath} md ${backupPath}"
				// 保留备份文件
                bat "if exist ${backupPath}\\${webSiteName}.zip copy ${backupPath}\\${webSiteName}.zip ${backupPath}\\${webSiteName}_${Integer.parseInt(env.BUILD_ID)-1}.zip"
                // 删除老的备份, 仅备份最新一次的更改
                bat "if exist ${backupPath}\\${webSiteName}.zip del ${backupPath}\\${webSiteName}.zip"
                try{
                    zip archive: false, dir: '', glob: '', zipFile: "${backupPath}\\${webSiteName}.zip"
                }
                catch(ex){}

            }
            //部署
            unzip dir: "${unZipPath}", glob: '', zipFile: "${packageName}"
        }
    }
    catch(ex){
        currentBuild.result = 'FAILURE'
        mailTemplate "${Publish_All_Version} Deploy to ${Publish_IP} "
        throw ex
    }
    finally{
        //启动服务
        bat "appcmd start site \"${webSiteName}\""
    }
    //邮件通知
    currentBuild.result = 'SUCCESS'
    mailTemplate "${Publish_All_Version} Deploy to ${Publish_IP} "
}

//Docker部署
def deployFunDocker={ String containerName,
					  String imagesNameLatest ->
	//拉取最新镜像
	docker.image.pull(imagesNameLatest)
	// 需要删除旧版本的容器，否则会导致端口占用而无法启动。
	try{
		sh "docker rm -f ${containerName}"
	}catch(e){
		// err message
	}
	//run最新镜像
	docker.image(imagesNameLatest).run(" -p 5001:5001 --name ${containerName}") 
	
	//邮件通知
    currentBuild.result = 'SUCCESS'
    mailTemplate "${Publish_All_Version} Deploy to ${Publish_IP} "
}

// PROPERTIES_DEFINE START
properties([parameters([
choice(choices: "GQC\nPRE\nPRD", description: '请选择发布目标环境默认为(GQC)', name: 'PublishEnvironmental'),
booleanParam(defaultValue: false, description: '请选择此次发布是否为回滚(默认值为否)', name: 'RollbackVersion'),
booleanParam(defaultValue: false, description: '是否执行静态代码检查', name: 'bExceSonar'),
string(defaultValue: '', description: '请填写该环境下部署目标服务器IP(多服务器用逗号隔开；默认为空，部署该环境下所有服务器)', name: 'Ip'),
booleanParam(defaultValue: true, description: '是否发布Api', name: 'bp'),
string(defaultValue: 'master', description: '请填写分支(git)(默认为develop分支)', name: 'branch'),
string(defaultValue:'',description:'请填写Api分支tag标签号',name:'TagNum'),
text(defaultValue: '', description: '请简要描述本次上线功能点(选填)', name: 'deploySummary')
]),
pipelineTriggers([])
])


publishEnv = params.PublishEnvironmental
rollback = params.RollbackVersion
_bExceSonar = params.bExceSonar
currentBuild.description = params.deploySummary
publishBranch = params.branch.trim()
_TagNum = params.TagNum.trim()
_bPublish = params.bp
Ip = params.Ip.trim()
def IpList = Ip.trim().split(',')

Publish_All_Version = publishBranch + "_" + _TagNum
// PROPERTIES_DEFINE END

// INIT_DEFINE START
// 根据环境设置操作节点
switch (publishEnv){
  case 'GQC':
    Envconf = JsonList.GQC
	Webconf = 'Debug'
  break
  case 'PRE':
    Envconf = JsonList.PRE
	Webconf = 'Pre'
  break
}
// INIT_DEFINE END


// Pipeline Start
node(GetCodeAndBuild_AgentName){
	if(!rollback){
		//获取代码
		stage('SCM'){
			/*if(!_TagNum){
				error('TagNum字段必填')
			}*/
			if(!_bPublish){
				//git pull
				dir(SolutionFolderName) {
					git branch: publishBranch, credentialsId: gitcredentialsId, url: GitUrl
					if(_TagNum){
						sh "git checkout ${_TagNum}"
					}                    
				}
			}
		}
		//BUILD代码
		stage('BUILD'){
			def PackageName = "Publish_${env.BUILD_ID}.zip".toString()
			if(!_bPublish){
				// build api				
				windowsBuildFun BuildOutFolder, ProjectFileName, PackageName, SolutionFolderName, Exarfiles
			}
		}
		/*
		//编译镜像并push到仓库
		stage('Image Build And Push'){
			//镜像版本
			def docker_tag = "V${env.BUILD_NUMBER}"				
			//镜像名称latest
			def ImagesNameLatest = "${docker_hub_url}/${docker_hub_namespace}/${docker_hub_container_name}:latest"
			//镜像名称Tag
			def ImagesNameTag = "${docker_hub_url}/${docker_hub_namespace}/${docker_hub_container_name}:${docker_tag}"
			dir(BuildOutFolder){
				withDockerRegistry([credentialsId: dockercredentialsId, url: "https://${docker_hub_url}"]) {
					docker.build(ImagesNameTag).push("${docker_tag}")
					docker.build(ImagesNameLatest).push()
				}
			}
		}
		*/
		// stage('SonarQube analysis') {
		//     if(_bExceSonar){
		//         def sqScannerHome = tool 'SonarQube Scanner windows'
		//         withSonarQubeEnv('SonarQube_49_XA') {
		//             if(_bPublish){
		//                 dir(SolutionFolderName) {
		//                     bat "${sqScannerHome}/bin/sonar-scanner -Dsonar.projectKey=WX.API -Dsonar.projectName=WX.API -Dsonar.sources=. -Dsonar.projectVersion=${env.BUILD_ID}.0 -Dsonar.sourceEncoding=UTF-8 -Dsonar.host.url=http://10.66.30.49:9000 -Dsonar.inclusions=**/*.cs"
		//                 }
		//             }
		//         }
		//     }
		// }
	}
}



def Match = false 
for (conf in Envconf) {
	for (ipstr in IpList) {
	//输入ip,存在于配置中
		if(ipstr == conf.IP){
			Match = true
			break
		}
	}
	
	if(Match || Ip.trim() == ''){
		Match = false
		println conf.IP + '服务器发布'
		Deploy_AgentName =  conf.Deploy_AgentName
		apiSiteIISName       =  conf.apiSiteIISName
		apiSiteIISPath       =  conf.apiSiteIISPath
		//api server
		if(_bPublish && Deploy_AgentName != ''){
			node(Deploy_AgentName){
				def unzipPath = apiSiteIISPath
				def backupPath = env.WORKSPACE+'\\Backup'
				def packageName = "${BuildOutFolder}_${env.BUILD_ID}_Api.zip".toString()				
				def siteName = apiSiteIISName
				Publish_IP = conf.IP
                
				//下载部署包
				stage('DownloadZip'){
					downloadZip packageName				
				}
				
				//Windows部署
				stage('Windows_Deploy'){
					deployFunWindows unzipPath, backupPath, packageName, siteName
				}
				/*
				//Docker部署
				stage('Docker_Deploy'){				
					//镜像名称latest
					def imagesNameLatest = "${docker_hub_url}/${docker_hub_namespace}/${docker_hub_container_name}:latest"
					//容器名称
					def containerName = docker_hub_container_name					
					deployFunDocker containerName, imagesNameLatest
				}
				*/
			}
		}
	}
}

