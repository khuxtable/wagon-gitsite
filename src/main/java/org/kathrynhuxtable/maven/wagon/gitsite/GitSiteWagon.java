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
package org.kathrynhuxtable.maven.wagon.gitsite;

import java.io.File;
import java.io.IOException;

import java.text.DecimalFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Stack;

import org.apache.maven.scm.CommandParameter;
import org.apache.maven.scm.CommandParameters;
import org.apache.maven.scm.ScmBranch;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFile;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.ScmResult;
import org.apache.maven.scm.ScmVersion;
import org.apache.maven.scm.command.add.AddScmResult;
import org.apache.maven.scm.command.checkin.CheckInScmResult;
import org.apache.maven.scm.command.checkout.CheckOutScmResult;
import org.apache.maven.scm.command.list.ListScmResult;
import org.apache.maven.scm.log.ScmLogger;
import org.apache.maven.scm.manager.NoSuchScmProviderException;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.provider.ScmProvider;
import org.apache.maven.scm.provider.ScmProviderRepository;
import org.apache.maven.scm.provider.ScmProviderRepositoryWithHost;
import org.apache.maven.scm.provider.git.command.GitCommand;
import org.apache.maven.scm.provider.git.gitexe.GitExeScmProvider;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.scm.repository.ScmRepositoryException;
import org.apache.maven.wagon.AbstractWagon;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.proxy.ProxyInfoProvider;
import org.apache.maven.wagon.repository.Repository;
import org.apache.maven.wagon.resource.Resource;

import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;

import org.kathrynhuxtable.maven.wagon.gitsite.git.GitSiteCheckInCommand;
import org.kathrynhuxtable.maven.wagon.gitsite.git.GitSiteCheckOutCommand;

/**
 * Wagon provider to deploy site documentation to GitHub's pages system.
 *
 * <p>This should do more or less the following, but doesn't because it doesn't
 * actually delete old files.</p>
 *
 * <pre>
 * mkdir ${checkoutDirectory}
 * cd ${checkoutDirectory}
 * git init
 * git remote add origin ${gitRepoUrl}
 * git pull origin refs/heads/${siteBranch}
 * <replace the contents of the checkout directory, except for the .git subdirectory, with the site docs>
 * git add .
 * git commit -a -m "Wagon: Deploying site to repository"
 * git push origin master:${siteBranch}
 * rm -Rf ${checkoutDirectory}
 * </pre>
 * 
 * We <em>need</em> to create the gh-pages branch if it doesn't already exist:
 * 
 * <pre>
 * cd ${checkoutDirectory}
 * git symbolic-ref HEAD refs/heads/gh-pages
 * rm .git/index
 * git clean -fdx
 * git add .
 * git commit -a -m "First pages commit"
 * git push origin gh-pages
 * </pre>
 *
 * @plexus.component role="org.apache.maven.wagon.Wagon" role-hint="gitsite"
 *                   instantiation-strategy="per-lookup"
 * @author           <a href="kathryn@kathrynhuxtable.org">Kathryn Huxtable</a>
 * @author           <a href="brett@apache.org">Brett Porter</a>
 * @author           <a href="evenisse@apache.org">Emmanuel Venisse</a>
 * @author           <a href="carlos@apache.org">Carlos Sanchez</a>
 * @author           Jason van Zyl
 */
public class GitSiteWagon extends AbstractWagon {

    /**
     * The SCM Manager.
     *
     * @plexus.requirement
     */
    private ScmManager scmManager;

    /** The site branch. Set in connect. */
    private String siteBranch;

    /** The check-out directory. */
    private File checkoutDirectory;

    /**
     * Get the {@link ScmManager} used in this Wagon.
     *
     * @return the {@link ScmManager}.
     */
    public ScmManager getScmManager() {
        return scmManager;
    }

    /**
     * Set the {@link ScmManager} used in this Wagon.
     *
     * @param scmManager the scmManager to set.
     */
    public void setScmManager(ScmManager scmManager) {
        this.scmManager = scmManager;
    }

    /**
     * Get the {@link siteBranch} used in this Wagon.
     *
     * @return the {@link siteBranch}.
     */
    public String getSiteBranch() {
        return siteBranch;
    }

    /**
     * Set the {@link siteBranch} used in this Wagon.
     *
     * @param siteBranch the siteBranch to set.
     */
    public void setSiteBranch(String siteBranch) {
        this.siteBranch = siteBranch;
    }

    /**
     * Get the directory where Wagon will checkout files from SCM. This
     * directory will be deleted!
     *
     * @return the {@link checkoutDirectory}.
     */
    public File getCheckoutDirectory() {
        return checkoutDirectory;
    }

    /**
     * Set the directory where Wagon will checkout files from SCM. This
     * directory will be deleted!
     *
     * @param checkoutDirectory the check-out directory to set.
     */
    public void setCheckoutDirectory(File checkoutDirectory) {
        this.checkoutDirectory = checkoutDirectory;
    }

    /**
     * Convenience method to get the {@link ScmProvider} implementation to
     * handle the provided SCM type.
     *
     * @param  scmType type of SCM, eg. <code>svn</code>, <code>cvs</code>
     *
     * @return the {@link ScmProvider} that will handle provided SCM type.
     *
     * @throws NoSuchScmProviderException if there is no {@link ScmProvider}
     *                                    able to handle that SCM type.
     */
    public ScmProvider getScmProvider(String scmType) throws NoSuchScmProviderException {
        return getScmManager().getProviderByType(scmType);
    }

    /**
     * This will clean up the checkout directory.
     *
     * @throws ConnectionException
     */
    public void openConnectionInternal() throws ConnectionException {
        if (checkoutDirectory == null) {
            checkoutDirectory = createCheckoutDirectory();
        }

        if (checkoutDirectory.exists()) {
            removeCheckoutDirectory();
        }

        checkoutDirectory.mkdirs();
    }

    /**
     * Create the checkout directory.
     *
     * @return the File representing the checkout directory.
     */
    private File createCheckoutDirectory() {
        File checkoutDirectory;

        DecimalFormat fmt = new DecimalFormat("#####");

        Random rand = new Random(System.currentTimeMillis() + Runtime.getRuntime().freeMemory());

        do {
            checkoutDirectory = new File(System.getProperty("java.io.tmpdir"),
                                         "wagon-scm" + fmt.format(Math.abs(rand.nextInt())) + ".checkout");
        } while (checkoutDirectory.exists());

        return checkoutDirectory;
    }

    /**
     * Remove (delete) the checkout directory.
     *
     * @throws ConnectionException if unable to clean up the checkout directory.
     */
    private void removeCheckoutDirectory() throws ConnectionException {
        if (checkoutDirectory == null) {
            return; // Silently return.
        }

        try {
            FileUtils.deleteDirectory(checkoutDirectory);
        } catch (IOException e) {
            throw new ConnectionException("Unable to cleanup checkout directory", e);
        }
    }

    /**
     * Get the SCM repository from the URL.
     *
     * @param  url the URL.
     *
     * @return the SCM repository.
     *
     * @throws ScmRepositoryException     if an SCM error occurs.
     * @throws NoSuchScmProviderException if there is no matching provider for
     *                                    the URL.
     */
    private ScmRepository getScmRepository(String url) throws ScmRepositoryException, NoSuchScmProviderException {
        String username = null;

        String password = null;

        String privateKey = null;

        String passphrase = null;

        if (authenticationInfo != null) {
            username = authenticationInfo.getUserName();

            password = authenticationInfo.getPassword();

            privateKey = authenticationInfo.getPrivateKey();

            passphrase = authenticationInfo.getPassphrase();
        }

        ScmRepository scmRepository = getScmManager().makeScmRepository(url);

        ScmProviderRepository providerRepository = scmRepository.getProviderRepository();

        if (StringUtils.isNotEmpty(username)) {
            providerRepository.setUser(username);
        }

        if (StringUtils.isNotEmpty(password)) {
            providerRepository.setPassword(password);
        }

        if (providerRepository instanceof ScmProviderRepositoryWithHost) {
            ScmProviderRepositoryWithHost providerRepo = (ScmProviderRepositoryWithHost) providerRepository;

            if (StringUtils.isNotEmpty(privateKey)) {
                providerRepo.setPrivateKey(privateKey);
            }

            if (StringUtils.isNotEmpty(passphrase)) {
                providerRepo.setPassphrase(passphrase);
            }
        }

        return scmRepository;
    }

    /**
     * Configure and perform the check-in process.
     *
     * @param  scmProvider   the SCM provider.
     * @param  scmRepository the SCM repository.
     * @param  msg           the commit message.
     *
     * @throws ScmException
     */
    private void checkIn(ScmProvider scmProvider, ScmRepository scmRepository, String msg) throws ScmException {
        CommandParameters parameters = new CommandParameters();

        parameters.setScmVersion(CommandParameter.SCM_VERSION, new ScmBranch(siteBranch));

        parameters.setString(CommandParameter.MESSAGE, msg);

        ScmResult result = (CheckInScmResult) executeCommand((GitExeScmProvider) scmProvider, new GitSiteCheckInCommand(),
                                                             scmRepository.getProviderRepository(),
                                                             new ScmFileSet(checkoutDirectory), parameters);

        checkScmResult(result);
    }

    /**
     * Configure and perform the check-out process.
     *
     * <p>Returns the relative path to targetName in the checkout dir. If the
     * targetName already exists in the scm, this will be the empty string.</p>
     *
     * @param  scmProvider   the SCM provider.
     * @param  scmRepository the SCM repository.
     * @param  targetName    the check-out directory.
     * @param  resource      the resource.
     *
     * @return the relative path to targetName in the check-out directory.
     *
     * @throws TransferFailedException
     */
    private String checkOut(ScmProvider scmProvider, ScmRepository scmRepository, String targetName, Resource resource)
        throws TransferFailedException {
        checkoutDirectory = createCheckoutDirectory();

        Stack<String> stack = new Stack<String>();

        String target = targetName;

        // Totally ignore scmRepository parent stuff since that is not supported by all SCMs.
        // Instead, assume that that url exists. If not, then that's an error.
        // Check whether targetName, which is a relative path into the scm, exists.
        // If it doesn't, check the parent, etc.

        try {
            while (target.length() > 0
                    && !scmProvider.list(scmRepository, new ScmFileSet(new File("."), new File(target)), false, (ScmVersion) null)
                    .isSuccess()) {
                stack.push(getFilename(target));
                target = getDirname(target);
            }
        } catch (ScmException e) {
            fireTransferError(resource, e, TransferEvent.REQUEST_PUT);

            throw new TransferFailedException("Error listing repository: " + e.getMessage(), e);
        }

        /* A URL for a module will look like: 
         *   scm:git:ssh://github.com/auser/project.git/module
         * so we strip the module to get just:
         *   scm:git:ssh://github.com/auser/project.git
         * to ensure a successful checkout, then adjust the relative path.
         */
        String url = getRepository().getUrl() + targetName;
        String relPath = "";
        
        if (!url.endsWith(".git")) {
            final int iGitSuffix = url.lastIndexOf(".git");
            if (iGitSuffix > 0) {
                relPath = url.substring(iGitSuffix + 5) + '/';
                url = url.substring(0, iGitSuffix + 4);
            }
        }
        final ScmLogger logger = ((GitExeScmProvider)scmProvider).getLogger();
        logger.debug("checkOut url: " + url);
        logger.debug("checkOut relPath: " + relPath);

        // ok, we've established that target exists, or is empty.
        // Check the resource out; if it doesn't exist, that means we're in the svn repo url root,
        // and the configuration is incorrect. We will not try repo.getParent since most scm's don't
        // implement that.

        try {
            scmRepository = getScmRepository(url);

            CommandParameters parameters = new CommandParameters();

            parameters.setScmVersion(CommandParameter.SCM_VERSION, new ScmBranch(siteBranch));

            parameters.setString(CommandParameter.RECURSIVE, "false");

            CheckOutScmResult ret = (CheckOutScmResult) executeCommand((GitExeScmProvider) scmProvider, new GitSiteCheckOutCommand(),
                                                                       scmRepository.getProviderRepository(),
                                                                       new ScmFileSet(new File(checkoutDirectory, "")), parameters);

            checkScmResult(ret);
        } catch (ScmException e) {
            fireTransferError(resource, e, TransferEvent.REQUEST_PUT);

            throw new TransferFailedException("Error checking out: " + e.getMessage(), e);
        }

        // now create the subdirs in target, if it's a parent of targetName

        while (!stack.isEmpty()) {
            String p = (String) stack.pop();

            relPath += p + '/';
            logger.debug(" * checkOut relPath: " + relPath);

            File newDir = new File(checkoutDirectory, relPath);

            if (!newDir.mkdirs()) {
                throw new TransferFailedException("Failed to create directory " + newDir.getAbsolutePath() + "; parent should exist: "
                                                  + checkoutDirectory);
            }

            try {
                addFiles(scmProvider, scmRepository, checkoutDirectory, relPath);
            } catch (ScmException e) {
                fireTransferError(resource, e, TransferEvent.REQUEST_PUT);

                throw new TransferFailedException("Failed to add directory " + newDir + " to working copy", e);
            }
        }

        return relPath;
    }

    /**
     * Add a file or directory to a SCM repository. If it's a directory all its
     * contents are added recursively.
     *
     * <p>TODO this is less than optimal, SCM API should provide a way to add a
     * directory recursively</p>
     *
     * @param  scmProvider   SCM provider
     * @param  scmRepository SCM repository
     * @param  basedir       local directory corresponding to scmRepository
     * @param  scmFilePath   path of the file or directory to add, relative to
     *                       basedir
     *
     * @return the number of files added.
     *
     * @throws ScmException
     */
    private int addFiles(ScmProvider scmProvider, ScmRepository scmRepository, File basedir, String scmFilePath) throws ScmException {
        int addedFiles = 0;

        File scmFile = new File(basedir, scmFilePath);

        if (scmFilePath.length() != 0) {
            AddScmResult result = scmProvider.add(scmRepository, new ScmFileSet(basedir, new File(scmFilePath)));

            /*
             * TODO dirty fix to work around files with property svn:eol-style=native if a file has that property, first
             * time file is added it fails, second time it succeeds the solution is check if the scm provider is svn and
             * unset that property when the SCM API allows it
             */
            if (!result.isSuccess()) {
                result = scmProvider.add(scmRepository, new ScmFileSet(basedir, new File(scmFilePath)));
            }

            addedFiles = result.getAddedFiles().size();
        }

        String reservedScmFile = scmProvider.getScmSpecificFilename();

        if (scmFile.isDirectory()) {
            File[] files = scmFile.listFiles();

            for (int i = 0; i < files.length; i++) {
                if (reservedScmFile != null && !reservedScmFile.equals(files[i].getName())) {
                    addedFiles += addFiles(scmProvider, scmRepository, basedir,
                                           (scmFilePath.length() == 0 ? "" : scmFilePath + "/") + files[i].getName());
                }
            }
        }

        return addedFiles;
    }

    /**
     * Return whether or not this wagon supports directory copy.
     *
     * @return {@code true}
     *
     * @see    org.apache.maven.wagon.AbstractWagon#supportsDirectoryCopy()
     */
    public boolean supportsDirectoryCopy() {
        return true;
    }

    /**
     * @see org.apache.maven.wagon.AbstractWagon#connect(org.apache.maven.wagon.repository.Repository,
     *      org.apache.maven.wagon.authentication.AuthenticationInfo,
     *      org.apache.maven.wagon.proxy.ProxyInfoProvider)
     */
    public void connect(Repository repository, AuthenticationInfo authenticationInfo, ProxyInfoProvider proxyInfoProvider)
        throws ConnectionException, AuthenticationException {
        String url = repository.getUrl();

        if (url.startsWith("gitsite:")) {
            url = url.substring(8);
            int index = url.indexOf(':');

            if (index > -1) {
                siteBranch = url.substring(index + 1);
                url        = url.substring(0, index);
            } else {
                siteBranch = "gh-pages";
            }

            if (url.startsWith("file:///"))
                url = "scm:git:" + url;
            else
                url = "scm:git:ssh://" + url;
            repository.setUrl(url);
        }

        super.connect(repository, authenticationInfo, proxyInfoProvider);
    }

    /**
     * @see org.apache.maven.wagon.Wagon#put(java.io.File, java.lang.String)
     */
    public void put(File source, String destination) throws TransferFailedException {
    	putResource(source, destination);
    }

    /**
     * @see org.apache.maven.wagon.AbstractWagon#putDirectory(java.io.File, java.lang.String)
     */
    public void putDirectory(File sourceDirectory, String destinationDirectory) throws TransferFailedException,
        ResourceDoesNotExistException, AuthorizationException {
        if (!sourceDirectory.isDirectory()) {
            throw new IllegalArgumentException("Source is not a directory: " + sourceDirectory);
        }

        putResource(sourceDirectory, destinationDirectory);
    }

	private void putResource(File sourceDirectory, String destinationDirectory) throws TransferFailedException {
		Resource target = new Resource(destinationDirectory);

        firePutInitiated(target, sourceDirectory);

        try {
            ScmRepository scmRepository = getScmRepository(getRepository().getUrl());

            target.setContentLength(sourceDirectory.length());
            target.setLastModified(sourceDirectory.lastModified());

            firePutStarted(target, sourceDirectory);

            String msg = "Wagon: Deploying " + sourceDirectory.getName() + " to repository";

            ScmProvider scmProvider = getScmProvider(scmRepository.getProvider());

            String checkoutTargetName = sourceDirectory.isDirectory() ? destinationDirectory : getDirname(destinationDirectory);
            String relPath            = checkOut(scmProvider, scmRepository, checkoutTargetName, target);

            File newCheckoutDirectory = new File(checkoutDirectory, relPath);

            File scmFile = new File(newCheckoutDirectory, sourceDirectory.isDirectory() ? "" : getFilename(destinationDirectory));

            boolean fileAlreadyInScm = scmFile.exists();

            if (!scmFile.equals(sourceDirectory)) {
                if (sourceDirectory.isDirectory()) {
                    FileUtils.copyDirectoryStructure(sourceDirectory, scmFile);
                } else {
                    FileUtils.copyFile(sourceDirectory, scmFile);
                }
            }

            if (!fileAlreadyInScm || scmFile.isDirectory()) {
                int addedFiles = addFiles(scmProvider, scmRepository, newCheckoutDirectory,
                                          sourceDirectory.isDirectory() ? "" : scmFile.getName());

                if (!fileAlreadyInScm && addedFiles == 0) {
                    throw new ScmException("Unable to add file to SCM: " + scmFile + "; see error messages above for more information");
                }
            }

            checkIn(scmProvider, scmRepository, msg);
        } catch (ScmException e) {
            e.printStackTrace();
            fireTransferError(target, e, TransferEvent.REQUEST_GET);

            System.exit(1);
            throw new TransferFailedException("Error interacting with SCM: " + e.getMessage(), e);
        } catch (IOException e) {
            fireTransferError(target, e, TransferEvent.REQUEST_GET);

            throw new TransferFailedException("Error interacting with SCM: " + e.getMessage(), e);
        }

        if (sourceDirectory.isFile()) {
            postProcessListeners(target, sourceDirectory, TransferEvent.REQUEST_PUT);
        }

        firePutCompleted(target, sourceDirectory);
	}

    /**
     * Check that the ScmResult was a successful operation
     *
     * @param  result the SCM result.
     *
     * @throws ScmException
     */
    private void checkScmResult(ScmResult result) throws ScmException {
        if (!result.isSuccess()) {
            throw new ScmException("Unable to commit file. " + result.getProviderMessage() + " "
                                   + (result.getCommandOutput() == null ? "" : result.getCommandOutput()));
        }
    }

    /**
     * @see org.apache.maven.wagon.AbstractWagon#closeConnection()
     */
    public void closeConnection() throws ConnectionException {
        removeCheckoutDirectory();
    }

    /**
     * @see org.apache.maven.wagon.Wagon#getIfNewer(java.lang.String,java.io.File,
     *      long)
     */
    public boolean getIfNewer(String resourceName, File destination, long timestamp) throws TransferFailedException,
        ResourceDoesNotExistException, AuthorizationException {
        throw new UnsupportedOperationException("Not currently supported: getIfNewer");
    }

    /**
     * @see org.apache.maven.wagon.Wagon#get(java.lang.String, java.io.File)
     */
    public void get(String resourceName, File destination) throws TransferFailedException, ResourceDoesNotExistException,
        AuthorizationException {
        throw new UnsupportedOperationException("Not currently supported: get");
    }

    /**
     * Get the file list for the resource.
     *
     * @param  resourcePath the resource path.
     *
     * @return a List&lt;String&gt; with filenames/directories at the
     *         resourcepath.
     *
     * @throws TransferFailedException
     * @throws ResourceDoesNotExistException
     * @throws AuthorizationException
     *
     * @see    org.apache.maven.wagon.AbstractWagon#getFileList(java.lang.String)
     */
    public List<String> getFileList(String resourcePath) throws TransferFailedException, ResourceDoesNotExistException,
        AuthorizationException {
        try {
            ScmRepository repository = getScmRepository(getRepository().getUrl());
            ScmProvider   provider   = getScmProvider(repository.getProvider());
            ListScmResult result     = provider.list(repository,
                                                     new ScmFileSet(new File("."), new File(resourcePath)), false, (ScmVersion) null);

            if (!result.isSuccess()) {
                throw new ResourceDoesNotExistException(result.getProviderMessage());
            }

            List<String> files = new ArrayList<String>();

            for (ScmFile f : getListScmResultFiles(result)) {
                files.add(f.getPath());
                System.out.println("LIST FILE: " + f + " (path=" + f.getPath() + ")");
            }

            return files;
        } catch (ScmException e) {
            throw new TransferFailedException("Error getting filelist from SCM", e);
        }
    }

    /**
     * Wrapper around listScmResult.getFiles() to avoid having a larger method
     * needing the suppressWarnings attribute.
     *
     * @param  result the ListScmResult.
     *
     * @return the files.
     */
    @SuppressWarnings("unchecked")
    private List<ScmFile> getListScmResultFiles(ListScmResult result) {
        return (List<ScmFile>) result.getFiles();
    }

    /**
     * @see org.apache.maven.wagon.AbstractWagon#resourceExists(java.lang.String)
     */
    public boolean resourceExists(String resourceName) throws TransferFailedException, AuthorizationException {
        try {
            getFileList(resourceName);
            return true;
        } catch (ResourceDoesNotExistException e) {
            return false;
        }
    }

    /**
     * Get the filename format for a file.
     *
     * @param  filename the file name.
     *
     * @return the file name.
     */
    private String getFilename(String filename) {
        String fname = StringUtils.replace(filename, "/", File.separator);

        return FileUtils.filename(fname);
    }

    /**
     * Get the directory format for a file.
     *
     * @param  filename the file name.
     *
     * @return the directory name.
     */
    private String getDirname(String filename) {
        String fname = StringUtils.replace(filename, "/", File.separator);

        return FileUtils.dirname(fname);
    }

    /**
     * Wrapper around gitCommand.execute to handle setting the logger.
     *
     * @param  scmProvider the SCM provider.
     * @param  command     the command.
     * @param  repository  the SCM repository.
     * @param  fileSet     the file set.
     * @param  parameters  any parameters to the command.
     *
     * @return the SCM result.
     *
     * @throws ScmException
     */
    protected ScmResult executeCommand(GitExeScmProvider scmProvider, GitCommand command, ScmProviderRepository repository,
            ScmFileSet fileSet, CommandParameters parameters) throws ScmException {
        command.setLogger(scmProvider.getLogger());

        return command.execute(repository, fileSet, parameters);
    }
}
