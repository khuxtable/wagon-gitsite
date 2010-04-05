/*
 * Copyright (c) 2010 Kathryn Huxtable
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kathrynhuxtable.maven.wagon.gitsite.git;

import java.io.File;
import java.io.IOException;

import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.ScmFileStatus;
import org.apache.maven.scm.ScmVersion;
import org.apache.maven.scm.command.checkout.AbstractCheckOutCommand;
import org.apache.maven.scm.command.checkout.CheckOutScmResult;
import org.apache.maven.scm.provider.ScmProviderRepository;
import org.apache.maven.scm.provider.git.command.GitCommand;
import org.apache.maven.scm.provider.git.gitexe.command.GitCommandLineUtils;
import org.apache.maven.scm.provider.git.gitexe.command.list.GitListCommand;
import org.apache.maven.scm.provider.git.gitexe.command.list.GitListConsumer;
import org.apache.maven.scm.provider.git.repository.GitScmProviderRepository;

import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;

/**
 * Handle git check-out and pull from remote site.
 *
 * <p>Based on GitCheckOutCommand by Mark Struberg.</p>
 *
 * @author Kathryn Huxtable
 * @see    org.apache.maven.scm.provider.git.gitexe.command.checkout.GitCheckOutCommand
 */
public class GitSiteCheckOutCommand extends AbstractCheckOutCommand implements GitCommand {

    /**
     * @see org.apache.maven.scm.command.checkout.AbstractCheckOutCommand#executeCheckOutCommand(org.apache.maven.scm.provider.ScmProviderRepository,
     *      org.apache.maven.scm.ScmFileSet, org.apache.maven.scm.ScmVersion,
     *      boolean)
     */
    protected CheckOutScmResult executeCheckOutCommand(ScmProviderRepository repo, ScmFileSet fileSet, ScmVersion version,
            boolean recursive) throws ScmException {
        GitScmProviderRepository repository = (GitScmProviderRepository) repo;

        if (GitScmProviderRepository.PROTOCOL_FILE.equals(repository.getFetchInfo().getProtocol())
                && repository.getFetchInfo().getPath().indexOf(fileSet.getBasedir().getPath()) >= 0) {
            throw new ScmException("remote repository must not be the working directory");
        }

        int exitCode;

        CommandLineUtils.StringStreamConsumer stdout = new CommandLineUtils.StringStreamConsumer();
        CommandLineUtils.StringStreamConsumer stderr = new CommandLineUtils.StringStreamConsumer();

        // Create or empty the working directory.
        if (!fileSet.getBasedir().exists()) {
            if (!fileSet.getBasedir().mkdir()) {
                return new CheckOutScmResult("make directory", "The directory create failed.", "", false);
            }
        } else {
            try {
                FileUtils.cleanDirectory(fileSet.getBasedir());
            } catch (IOException e) {
                return new CheckOutScmResult("clean directory", "The directory cleanup failed.", e.getMessage(), false);
            }
        }

        Commandline cl = null;

        // Initialize a new git repo.
        cl       = createInitCommand(fileSet.getBasedir());
        exitCode = GitCommandLineUtils.execute(cl, stdout, stderr, getLogger());
        if (exitCode != 0 || !new File(fileSet.getBasedir(), ".git").exists()) {
            return new CheckOutScmResult(cl.toString(), "The git-init command failed.", stderr.getOutput(), false);
        }

        // Add the remote origin to the git repo.
        cl       = createRemoteAddOriginCommand(fileSet.getBasedir(), repository);
        exitCode = GitCommandLineUtils.execute(cl, stdout, stderr, getLogger());
        if (exitCode != 0) {
            return new CheckOutScmResult(cl.toString(), "The git-remote command failed.", stderr.getOutput(), false);
        }

        // Pull the site branch into master, which checks it out.
        cl       = createPullCommand(fileSet.getBasedir(), version);
        exitCode = GitCommandLineUtils.execute(cl, stdout, stderr, getLogger());
        if (exitCode != 0) {
            return new CheckOutScmResult(cl.toString(), "The git-pull command failed.", stderr.getOutput(), false);
        }

        // And now search for the files.
        GitListConsumer listConsumer = new GitListConsumer(getLogger(), fileSet.getBasedir(), ScmFileStatus.CHECKED_IN);

        cl       = GitListCommand.createCommandLine(repository, fileSet.getBasedir());
        exitCode = GitCommandLineUtils.execute(cl, listConsumer, stderr, getLogger());
        if (exitCode != 0) {
            return new CheckOutScmResult(cl.toString(), "The git-ls-files command failed.", stderr.getOutput(), false);
        }

        return new CheckOutScmResult(cl.toString(), listConsumer.getListedFiles());
    }

    /**
     * Create a "git init" command.
     *
     * @param  workingDirectory the working directory.
     *
     * @return the command line to init the local repository.
     */
    private Commandline createInitCommand(File workingDirectory) {
        Commandline cl = GitCommandLineUtils.getBaseGitCommandLine(workingDirectory, "init");

        return cl;
    }

    /**
     * Create a "git remote add origin" command.
     *
     * @param  workingDirectory the working directory.
     * @param  repository       the SCM repository.
     *
     * @return the command line to add the remote origin.
     */
    private Commandline createRemoteAddOriginCommand(File workingDirectory, GitScmProviderRepository repository) {
        Commandline cl = GitCommandLineUtils.getBaseGitCommandLine(workingDirectory, "remote");

        cl.createArg().setValue("add");
        cl.createArg().setValue("origin");

        cl.createArg().setValue(repository.getFetchUrl());

        return cl;
    }

    /**
     * Create a "git pull origin refs/heads/branch" command.
     *
     * @param  workingDirectory the working directory.
     * @param  version          the remote site branch to check out.
     *
     * @return the command line to pull the site branch into the working
     *         directory.
     */
    private Commandline createPullCommand(File workingDirectory, ScmVersion version) {
        Commandline cl = GitCommandLineUtils.getBaseGitCommandLine(workingDirectory, "pull");

        cl.createArg().setValue("origin");

        cl.createArg().setValue("refs/heads/" + version.getName());

        return cl;
    }
}
