package com.entagen.jenkins

class TemplateJob {
    String jobName
    String baseJobName
    String templateBranchName

    String jobNameForBranch(String branchName) {
        // Jenkins doesn't support job names with slashes or hashes, but sometimes git branches with those in appear.
        String safeBranchName = branchName.replaceAll('[/#]', '_')
        return "$baseJobName-$safeBranchName"
    }

    ConcreteJob concreteJobForBranch(String branchName) {
        ConcreteJob concreteJob = new ConcreteJob(templateJob: this, branchName: branchName, jobName: jobNameForBranch(branchName) )
    }
}
