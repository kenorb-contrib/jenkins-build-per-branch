package com.entagen.jenkins

import java.util.regex.Pattern
import groovy.json.JsonSlurper

class JenkinsJobManager {
    String templateJobPrefix
    String templateBranchName
    String gitUrl
    String nestedView
    String jenkinsUrl
    String branchNameRegex
    String viewRegex
    String jenkinsUser
    String jenkinsPassword
    String workspacePath
    String folderPath
    String jenkinsToken

    Boolean dryRun = false
    Boolean noViews = false
    Boolean noDelete = false
    Boolean startOnCreate = false
    Boolean enableJob = false
    String days
    Boolean disableLastCommit

    String[] booleanOpts = [ "dryRun", "noViews", "noDelete", "startOnCreate" ]

    JenkinsApi jenkinsApi
    GitApi gitApi

    JenkinsJobManager(Map props) {
        for (property in props) {
            if (property.key in booleanOpts) {
                this."${property.key}" = property.value.toBoolean()
            } else {
                this."${property.key}" = property.value
            }
        }
        initJenkinsApi()
        initGitApi()
    }

    void syncWithRepo() {
        List<String> allBranchNames = gitApi.branchNames.unique{ it.toLowerCase() }
        List<String> allJobNames = jenkinsApi.jobNames

        // ensure that there is at least one job matching the template pattern, collect the set of template jobs
        List<TemplateJob> templateJobs = findRequiredTemplateJobs(allJobNames)

        // create any missing template jobs and delete any jobs matching the template patterns that no longer have branches
        syncJobs(allBranchNames, allJobNames, templateJobs)

        // create any missing branch views, scoped within a nested view if we were given one
        if (!noViews) {
            syncViews(allBranchNames)
        }
    }

    public void syncJobs(List<String> allBranchNames, List<String> allJobNames, List<TemplateJob> templateJobs) {
        List<String> nonTemplateBranchNames = allBranchNames - templateBranchName
        List<String> currentTemplateDrivenJobNames = templateDrivenJobNames(templateJobs, allJobNames, nonTemplateBranchNames)
        List<ConcreteJob> expectedJobs = this.expectedJobs(templateJobs, nonTemplateBranchNames)

        createMissingJobs(expectedJobs, currentTemplateDrivenJobNames, templateJobs)
        if (!noDelete) {
            deleteDeprecatedJobs(currentTemplateDrivenJobNames - expectedJobs.jobName)
        }
    }

    public void createMissingJobs(List<ConcreteJob> expectedJobs, List<String> currentJobs, List<TemplateJob> templateJobs) {
        List<String> lowercaseCurrentJobs = currentJobs.collect()*.toLowerCase()
        List<ConcreteJob> missingJobs = expectedJobs.findAll { !lowercaseCurrentJobs.contains(it.jobName.toLowerCase()) }
        if (!missingJobs) return

        for (ConcreteJob missingJob in missingJobs) {
            println "Creating missing job: ${missingJob.jobName} from ${missingJob.templateJob.jobName}"
            jenkinsApi.cloneJobForBranch(missingJob, templateJobs)
            if (enableJob) {
                jenkinsApi.enableJob(missingJob.jobName)
            }
            if (startOnCreate) {
                jenkinsApi.startJob(missingJob)
            }
        }
    }

    public void deleteDeprecatedJobs(List<String> deprecatedJobNames) {
        if (!deprecatedJobNames) return
        println "Deleting deprecated jobs:\n\t${deprecatedJobNames.join('\n\t')}"
        deprecatedJobNames.each { String jobName ->
            try {
                jenkinsApi.wipeOutWorkspace(jobName)
            }
            catch(Exception ex) {
                println "Attempting to stop $jobName since wiping out the workspace failed"
                jenkinsApi.stopJob(jobName)
                println "Giving $jobName 15 seconds before wiping out the workspace again"
                sleep(15000)
                jenkinsApi.wipeOutWorkspace(jobName)
            }

            jenkinsApi.deleteJob(jobName)
            deleteDeprecatedDir(jobName)
        }
    }

    private void deleteDeprecatedDir(String deprecatedDirName) {
        if (!workspacePath) return

        def workspace = new File(workspacePath)
        def deprecatedDir = new File(workspace, deprecatedDirName)
        if (deprecatedDir.exists()) {
            println "Deleting deprecated dir: $deprecatedDir"
            deprecatedDir.deleteDir()
        }
    }

    public List<ConcreteJob> expectedJobs(List<TemplateJob> templateJobs, List<String> branchNames) {
        branchNames.collect { String branchName ->
            templateJobs.collect { TemplateJob templateJob -> templateJob.concreteJobForBranch(branchName) }
        }.flatten()
    }

    public List<String> templateDrivenJobNames(List<TemplateJob> templateJobs, List<String> allJobNames, List<String>nonTemplateBranchNames) {
        List<String> templateJobNames = templateJobs.jobName
        List<String> templateBaseJobNames = templateJobs.baseJobName

        // Filter out jobs that do not match the prefix-.*-branch pattern
        String branchRegex=nonTemplateBranchNames.join('|');
        println "branchRegex: (${branchRegex})"
        List<String> managedJobNames = allJobNames.findResults { String jobName ->
        jobName.find(/^($templateJobPrefix-[^-]*)-($branchRegex)$/) { name, base, branch -> name } };

        // Don't want actual template jobs, just the jobs that were created from the templates.
        return (managedJobNames - templateJobNames).findAll { String jobName ->
            templateBaseJobNames.find { String baseJobName -> jobName.startsWith(baseJobName) && jobName.tokenize("-")[2] ==~ branchNameRegex.replace("/", "_") }
        }
    }

    List<TemplateJob> findRequiredTemplateJobs(List<String> allJobNames) {
        String regex = ""
        if(templateJobPrefix) {
            regex = /^($templateJobPrefix(?:-[^-])*(?:-[^-]*)?)-($templateBranchName)$/
        }
        else {
            regex = /^([^-]*(?:-[^-]*)?)-($templateBranchName)$/
        }

        List<TemplateJob> templateJobs = allJobNames.findResults { String jobName ->
            TemplateJob templateJob = null
            jobName.find(regex) { full, baseJobName, branchName ->
                templateJob = new TemplateJob(jobName: full, baseJobName: baseJobName, templateBranchName: branchName)
            }
            return templateJob
        }

        assert templateJobs?.size() > 0, "Unable to find any jobs matching template regex: $regex\nYou need at least one job to match the templateJobPrefix and templateBranchName suffix arguments"
        return templateJobs
    }

    public void syncViews(List<String> allBranchNames) {
        List<String> existingViewNames = jenkinsApi.getViewNames(this.nestedView)
        List<BranchView> expectedBranchViews = allBranchNames.collect { String branchName -> new BranchView(branchName: branchName, templateJobPrefix: this.templateJobPrefix) }

        List<BranchView> missingBranchViews = expectedBranchViews.findAll { BranchView branchView -> !existingViewNames.contains(branchView.viewName) }
        addMissingViews(missingBranchViews)

        if (!noDelete) {
            List<String> deprecatedViewNames = getDeprecatedViewNames(existingViewNames, expectedBranchViews)
            deleteDeprecatedViews(deprecatedViewNames)
        }
    }

    public void addMissingViews(List<BranchView> missingViews) {
        println "Missing views: $missingViews"
        for (BranchView missingView in missingViews) {
            jenkinsApi.createViewForBranch(missingView, this.nestedView, this.viewRegex)
        }
    }

    public List<String> getDeprecatedViewNames(List<String> existingViewNames, List<BranchView> expectedBranchViews) {
        if (this.templateJobPrefix) {
            return existingViewNames?.findAll {
                 it.startsWith(this.templateJobPrefix)
            } - expectedBranchViews?.viewName ?: []
        }
        else {
            return existingViewNames - expectedBranchViews?.viewName ?: []
        }
    }

    public void deleteDeprecatedViews(List<String> deprecatedViewNames) {
        println "Deprecated views: $deprecatedViewNames"

        for (String deprecatedViewName in deprecatedViewNames) {
            jenkinsApi.deleteView(deprecatedViewName, this.nestedView)
        }

    }

    JenkinsApi initJenkinsApi() {
        if (!jenkinsApi) {
            assert jenkinsUrl != null
            if (dryRun) {
                println "DRY RUN! Not executing any POST commands to Jenkins, only GET commands"
                this.jenkinsApi = new JenkinsApiReadOnly(jenkinsServerUrl: jenkinsUrl, folderPath: folderPath)
            } else {
                this.jenkinsApi = new JenkinsApi(jenkinsServerUrl: jenkinsUrl, folderPath: folderPath)
            }

            if (jenkinsUser || jenkinsPassword) this.jenkinsApi.addBasicAuth(jenkinsUser, jenkinsPassword, jenkinsToken)

            if (this.branchNameRegex){
                String workingBranchNameRegex = '.*' + this.branchNameRegex.replaceAll('/','_') + '$' + '|.*'+ templateBranchName + '$'
                this.jenkinsApi.branchNameFilter = ~workingBranchNameRegex
            }
        }

        return this.jenkinsApi
    }

    GitApi initGitApi() {
        if (!gitApi) {
            assert gitUrl != null
            this.gitApi = new GitApi(gitUrl: gitUrl, daysSinceLastCommit: days.toInteger(), disableLastCommit: disableLastCommit)
            if (this.branchNameRegex) {
                this.gitApi.branchNameFilter = ~this.branchNameRegex
            }
        }

        return this.gitApi
    }
}
