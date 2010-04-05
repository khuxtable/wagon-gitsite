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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFile;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.ScmFileStatus;
import org.apache.maven.scm.ScmVersion;
import org.apache.maven.scm.command.checkin.AbstractCheckInCommand;
import org.apache.maven.scm.command.checkin.CheckInScmResult;
import org.apache.maven.scm.provider.ScmProviderRepository;
import org.apache.maven.scm.provider.git.command.GitCommand;
import org.apache.maven.scm.provider.git.gitexe.command.GitCommandLineUtils;
import org.apache.maven.scm.provider.git.gitexe.command.add.GitAddCommand;
import org.apache.maven.scm.provider.git.gitexe.command.status.GitStatusCommand;
import org.apache.maven.scm.provider.git.gitexe.command.status.GitStatusConsumer;
import org.apache.maven.scm.provider.git.repository.GitScmProviderRepository;
import org.apache.maven.scm.provider.git.util.GitUtil;

import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;

/**
 * Handle git check-in and push to remote site.
 *
 * <p>Based on GitCheckInCommand by Mark Struberg.</p>
 *
 * @author Kathryn Huxtable
 * @see    org.apache.maven.scm.provider.git.gitexe.command.checkin.GitCheckInCommand
 */
public class GitSiteCheckInCommand extends AbstractCheckInCommand implements GitCommand {

    /**
     * @see org.apache.maven.scm.command.checkin.AbstractCheckInCommand#executeCheckInCommand(org.apache.maven.scm.provider.ScmProviderRepository,
     *      org.apache.maven.scm.ScmFileSet, java.lang.String,
     *      org.apache.maven.scm.ScmVersion)
     */
    protected CheckInScmResult executeCheckInCommand(ScmProviderRepository repo, ScmFileSet fileSet, String message, ScmVersion version)
        throws ScmException {
        GitScmProviderRepository repository = (GitScmProviderRepository) repo;

        CommandLineUtils.StringStreamConsumer stderr = new CommandLineUtils.StringStreamConsumer();
        CommandLineUtils.StringStreamConsumer stdout = new CommandLineUtils.StringStreamConsumer();

        int exitCode;

        File messageFile = FileUtils.createTempFile("maven-scm-", ".commit", null);

        try {
            FileUtils.fileWrite(messageFile.getAbsolutePath(), message);
        } catch (IOException ex) {
            return new CheckInScmResult(null, "Error while making a temporary file for the commit message: " + ex.getMessage(), null,
                                        false);
        }

        try {
            Commandline cl = null;

            if (!fileSet.getFileList().isEmpty()) {
                // If specific fileSet is given, we have to git-add them first,
                // otherwise we will use 'git-commit -a' later.
                cl       = GitAddCommand.createCommandLine(fileSet.getBasedir(), fileSet.getFileList());
                exitCode = GitCommandLineUtils.execute(cl, stdout, stderr, getLogger());
                if (exitCode != 0) {
                    return new CheckInScmResult(cl.toString(), "The git-add command failed.", stderr.getOutput(), false);
                }
            }

            // The git-commit command doesn't show single files, but only summary :/
            // so we must run git-status and consume the output.
            // Borrow a few things from the git-status command.
            GitStatusConsumer statusConsumer = new GitStatusConsumer(getLogger(), fileSet.getBasedir());

            cl       = GitStatusCommand.createCommandLine(repository, fileSet);
            exitCode = GitCommandLineUtils.execute(cl, statusConsumer, stderr, getLogger());
            if (exitCode != 0) {
                // git-status returns non-zero if nothing to do.
                if (getLogger().isInfoEnabled()) {
                    getLogger().info("nothing added to commit but untracked files present (use \"git add\" to "
                                     + "track)");
                }
            }

            cl       = createCommitCommandLine(fileSet, messageFile);
            exitCode = GitCommandLineUtils.execute(cl, stdout, stderr, getLogger());
            if (exitCode != 0) {
                return new CheckInScmResult(cl.toString(), "The git-commit command failed.", stderr.getOutput(), false);
            }

            cl       = createPushCommandLine(fileSet, repository, version);
            exitCode = GitCommandLineUtils.execute(cl, stdout, stderr, getLogger());
            if (exitCode != 0) {
                return new CheckInScmResult(cl.toString(), "The git-push command failed.", stderr.getOutput(), false);
            }

            List<ScmFile> checkedInFiles = new ArrayList<ScmFile>(statusConsumer.getChangedFiles().size());

            // rewrite all detected files to now have status 'checked_in'
            for (Object foo : statusConsumer.getChangedFiles()) {
                ScmFile scmfile = new ScmFile(((ScmFile) foo).getPath(), ScmFileStatus.CHECKED_IN);

                if (fileSet.getFileList().isEmpty()) {
                    checkedInFiles.add(scmfile);
                } else {
                    // If a specific fileSet is given, we have to check if the file is really tracked.
                    for (Iterator<?> itfl = fileSet.getFileList().iterator(); itfl.hasNext();) {
                        File f = (File) itfl.next();

                        if (f.toString().equals(scmfile.getPath())) {
                            checkedInFiles.add(scmfile);
                        }
                    }
                }
            }

            return new CheckInScmResult(cl.toString(), checkedInFiles);
        } finally {
            try {
                FileUtils.forceDelete(messageFile);
            } catch (IOException ex) {
                // ignore
            }
        }
    }

    /**
     * Create the "git commit" command line.
     *
     * @param  fileSet     the file set to commit.
     * @param  messageFile the file containing the commit message.
     *
     * @return the command line to commit the changes.
     *
     * @throws ScmException if an error occurs.
     */
    private Commandline createCommitCommandLine(ScmFileSet fileSet, File messageFile) throws ScmException {
        Commandline cl = GitCommandLineUtils.getBaseGitCommandLine(fileSet.getBasedir(), "commit");

        cl.createArg().setValue("--verbose");

        cl.createArg().setValue("--allow-empty");

        cl.createArg().setValue("-F");

        cl.createArg().setValue(messageFile.getAbsolutePath());

        if (fileSet.getFileList().isEmpty()) {
            // commit all tracked files
            cl.createArg().setValue("-a");
        } else {
            // specify exactly which files to commit
            GitCommandLineUtils.addTarget(cl, fileSet.getFileList());
        }

        if (GitUtil.getSettings().isCommitNoVerify()) {
            cl.createArg().setValue("--no-verify");
        }

        return cl;
    }

    /**
     * Create the "git push origin" command.
     *
     * @param  fileSet    the file set.
     * @param  repository the SCM repository.
     * @param  version    the site branch.
     *
     * @return the command line to push the changes to the site branch.
     */
    private Commandline createPushCommandLine(ScmFileSet fileSet, GitScmProviderRepository repository, ScmVersion version) {
        Commandline cl = GitCommandLineUtils.getBaseGitCommandLine(fileSet.getBasedir(), "push");

        cl.createArg().setValue("origin");

        cl.createArg().setValue("master:" + version.getName());

        return cl;
    }
}
