package com.dabsquared.gitlabjenkins.util;

import com.dabsquared.gitlabjenkins.cause.GitLabWebHookCause;
import com.dabsquared.gitlabjenkins.connection.GitLabConnectionProperty;
import com.dabsquared.gitlabjenkins.gitlab.api.GitLabApi;
import com.dabsquared.gitlabjenkins.gitlab.api.model.BuildState;

import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;

import hudson.EnvVars;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.util.BuildData;
import jenkins.model.Jenkins;

/**
 * @author Robin Müller
 */
public class CommitStatusUpdater {

    private final static Logger LOGGER = Logger.getLogger(CommitStatusUpdater.class.getName());

    public static void updateCommitStatus(Run<?, ?> build, TaskListener listener, BuildState state) {
        GitLabApi client = getClient(build);
        if (client == null) {
            println(listener, "No GitLab connection configured");
            return;
        }
        String commitHash = getBuildRevision(build);
        String buildUrl = getBuildUrl(build);
        try {
            for (String gitlabProjectId : retrieveGitlabProjectIds(build, build.getEnvironment(listener))) {
                try {
                    if (existsCommit(client, gitlabProjectId, commitHash)) {
                        client.changeBuildStatus(gitlabProjectId, commitHash, state, getBuildBranch(build), "jenkins", buildUrl, null);
                    }
                } catch (WebApplicationException e) {
                    printf(listener, "Failed to update Gitlab commit status for project '%s': %s%n", gitlabProjectId, e.getMessage());
                    LOGGER.log(Level.SEVERE, String.format("Failed to update Gitlab commit status for project '%s'", gitlabProjectId), e);
                }
            }
        } catch (IOException | InterruptedException e) {
            printf(listener, "Failed to update Gitlab commit status: %s%n", e.getMessage());
        }
    }

    private static void println(TaskListener listener, String message) {
        if (listener == null) {
            LOGGER.log(Level.FINE, "failed to print message {0} due to null TaskListener", message);
        } else {
            listener.getLogger().println(message);
        }
    }

    private static void printf(TaskListener listener, String message, Object... args) {
        if (listener == null) {
            LOGGER.log(Level.FINE, "failed to print message {0} due to null TaskListener", String.format(message, args));
        } else {
            listener.getLogger().printf(message, args);
        }
    }

    private static String getBuildRevision(Run<?, ?> build) {
        BuildData action = build.getAction(BuildData.class);

        return action.getLastBuild(action.getLastBuiltRevision().getSha1()).getMarked().getSha1String();
    }

    private static boolean existsCommit(GitLabApi client, String gitlabProjectId, String commitHash) {
        try {
            client.getCommit(gitlabProjectId, commitHash);
            return true;
        } catch (NotFoundException e) {
            LOGGER.log(Level.FINE, String.format("Project (%s) and commit (%s) combination not found", gitlabProjectId, commitHash));
            return false;
        }
    }

    private static String getBuildBranch(Run<?, ?> build) {
        GitLabWebHookCause cause = build.getCause(GitLabWebHookCause.class);
        return cause == null ? null : cause.getData().getSourceBranch();
    }

    private static String getBuildUrl(Run<?, ?> build) {
        return Jenkins.getInstance().getRootUrl() + build.getUrl();
    }

    private static GitLabApi getClient(Run<?, ?> build) {
        GitLabConnectionProperty connectionProperty = build.getParent().getProperty(GitLabConnectionProperty.class);
        if (connectionProperty != null) {
            return connectionProperty.getClient();
        }
        return null;
    }

    private static List<String> retrieveGitlabProjectIds(Run<?, ?> build, EnvVars environment) {
        List<String> result = new ArrayList<>();
        GitLabApi gitLabClient = getClient(build);
        if (gitLabClient == null) {
            return result;
        }
        for (String remoteUrl : build.getAction(BuildData.class).getRemoteUrls()) {
            try {
                String projectNameWithNameSpace = ProjectIdUtil.retrieveProjectId(environment.expand(remoteUrl));
                if (StringUtils.isNotBlank(projectNameWithNameSpace)) {
                    String projectId = projectNameWithNameSpace;
                    if (projectNameWithNameSpace.contains(".")) {
                         projectId = gitLabClient.getProject(projectNameWithNameSpace).getId().toString();
                    }
                    result.add(projectId);
                }
            } catch (ProjectIdUtil.ProjectIdResolutionException e) {
                // nothing to do
            }
        }
        return result;
    }

}
