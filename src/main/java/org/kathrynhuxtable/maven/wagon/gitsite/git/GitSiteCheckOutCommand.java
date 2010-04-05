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
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;

/**
 * DOCUMENT ME!
 *
 * @author  <a href="mailto:struberg@yahoo.de">Mark Struberg</a>
 * @version $Id: GitSiteCheckOutCommand.java 823147 2009-10-08 12:39:23Z struberg $
 */
public class GitSiteCheckOutCommand extends AbstractCheckOutCommand implements GitCommand {

    /**
     * For git, the given repository is a remote one. We have to clone it first
     * if the working directory does not contain a git repo yet, otherwise we
     * have to git-pull it.
     *
     * <p>TODO We currently assume a '.git' directory, so this does not work for
     * --bare repos {@inheritDoc}</p>
     *
     * @param  repo      DOCUMENT ME!
     * @param  fileSet   DOCUMENT ME!
     * @param  version   DOCUMENT ME!
     * @param  recursive DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     *
     * @throws ScmException DOCUMENT ME!
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

        // Initialize a new repo.
        cl       = createInitCommand(repository, fileSet.getBasedir());
        exitCode = GitCommandLineUtils.execute(cl, stdout, stderr, getLogger());
        if (exitCode != 0) {
            return new CheckOutScmResult(cl.toString(), "The git-init command failed.", stderr.getOutput(), false);
        }

        // Add the remote origin to the repo.
        if (fileSet.getBasedir().exists() && new File(fileSet.getBasedir(), ".git").exists()) {
            cl       = createRemoteAddOriginCommand(repository, fileSet.getBasedir());
            exitCode = GitCommandLineUtils.execute(cl, stdout, stderr, getLogger());
            if (exitCode != 0) {
                return new CheckOutScmResult(cl.toString(), "The git-remote command failed.", stderr.getOutput(), false);
            }
        }

        if (fileSet.getBasedir().exists() && new File(fileSet.getBasedir(), ".git").exists()) {
            // pull the site branch into master, which checks it out.
            cl = createPullCommand(repository, fileSet.getBasedir(), version);

            exitCode = GitCommandLineUtils.execute(cl, stdout, stderr, getLogger());
            if (exitCode != 0) {
                return new CheckOutScmResult(cl.toString(), "The git-pull command failed.", stderr.getOutput(), false);
            }
        }

        // and now search for the files
        GitListConsumer listConsumer = new GitListConsumer(getLogger(), fileSet.getBasedir(), ScmFileStatus.CHECKED_IN);

        Commandline clList = GitListCommand.createCommandLine(repository, fileSet.getBasedir());

        exitCode = GitCommandLineUtils.execute(clList, listConsumer, stderr, getLogger());
        if (exitCode != 0) {
            return new CheckOutScmResult(clList.toString(), "The git-ls-files command failed.", stderr.getOutput(), false);
        }

        return new CheckOutScmResult(cl.toString(), listConsumer.getListedFiles());
    }

    /**
     * ----------------------------------------------------------------------
     * ----------------------------------------------------------------------
     *
     * @param  repository       DOCUMENT ME!
     * @param  workingDirectory DOCUMENT ME!
     * @param  version          DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    public static Commandline createCommandLine(GitScmProviderRepository repository, File workingDirectory, ScmVersion version) {
        Commandline cl = GitCommandLineUtils.getBaseGitCommandLine(workingDirectory, "checkout");

        if (version != null && StringUtils.isNotEmpty(version.getName())) {
            cl.createArg().setValue(version.getName());
        }

        return cl;
    }

    /**
     * create a git-init repository command
     *
     * @param  repository       DOCUMENT ME!
     * @param  workingDirectory DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    private Commandline createInitCommand(GitScmProviderRepository repository, File workingDirectory) {
        Commandline cl = GitCommandLineUtils.getBaseGitCommandLine(workingDirectory, "init");

        return cl;
    }

    /**
     * create a git-remote add origin command
     *
     * <p>git remote add origin ${gitRepoUrl}</p>
     *
     * @param  repository       DOCUMENT ME!
     * @param  workingDirectory DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    private Commandline createRemoteAddOriginCommand(GitScmProviderRepository repository, File workingDirectory) {
        Commandline cl = GitCommandLineUtils.getBaseGitCommandLine(workingDirectory, "remote");

        cl.createArg().setValue("add");
        cl.createArg().setValue("origin");

        cl.createArg().setValue(repository.getFetchUrl());

        return cl;
    }

    /**
     * create a git-pull repository command
     *
     * <p>git pull origin refs/heads/version</p>
     *
     * @param  repository       DOCUMENT ME!
     * @param  workingDirectory DOCUMENT ME!
     * @param  version          DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    private Commandline createPullCommand(GitScmProviderRepository repository, File workingDirectory, ScmVersion version) {
        Commandline cl = GitCommandLineUtils.getBaseGitCommandLine(workingDirectory, "pull");

        cl.createArg().setValue("origin");

        cl.createArg().setValue("refs/heads/" + version.getName());

        return cl;
    }
}
