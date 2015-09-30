/*
 * Copyright (c) 2013-2015, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2015, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.scm
import groovy.transform.CompileStatic
import groovy.transform.Memoized
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import nextflow.Const
import nextflow.cli.HubOptions
import nextflow.config.ComposedConfigSlurper
import nextflow.exception.AbortOperationException
import nextflow.util.IniFile
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.errors.RefNotFoundException
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
/**
 * Handles operation on remote and local installed pipelines
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */

@Slf4j
@CompileStatic
class AssetManager {

    static final String MANIFEST_FILE_NAME = 'nextflow.config'

    static final String DEFAULT_MAIN_FILE_NAME = 'main.nf'

    static final String DEFAULT_BRANCH = 'master'

    static final String DEFAULT_ORGANIZATION = System.getenv('NXF_ORG') ?: 'nextflow-io'

    static final String DEFAULT_HUB = System.getenv('NXF_HUB') ?: 'github'

    static final File DEFAULT_ROOT = System.getenv('NXF_ASSETS') ? new File(System.getenv('NXF_ASSETS')) : Const.APP_HOME_DIR.resolve('assets').toFile()

    /**
     * The folder all pipelines scripts are installed
     */
    @PackageScope
    static File root = DEFAULT_ROOT

    /**
     * The pipeline name. It must be in the form {@code username/repo} where 'username'
     * is a valid user name or organisation account, while 'repo' is the repository name
     * containing the pipeline code
     */
    private String project

    /**
     * Directory where the pipeline is cloned (i.e. downloaded)
     */
    private File localPath

    private Git _git

    private String mainScript

    private RepositoryProvider provider

    private String hub

    private List<ProviderConfig> providerConfigs

    /**
     * Create a new asset manager object with default parameters
     */
    AssetManager() {
        this.providerConfigs = ProviderConfig.createDefault()
    }

    /**
     * Create a new asset manager with the specified pipeline name
     *
     * @param pipeline The pipeline to be managed by this manager e.g. {@code nextflow-io/hello}
     */
    AssetManager( String pipelineName, HubOptions cliOpts = null) {
        assert pipelineName
        // read the default config file (if available)
        def config = ProviderConfig.getDefault()
        // build the object
        build(pipelineName, config, cliOpts)
    }

    @PackageScope
    AssetManager build( String pipelineName, Map config = null, HubOptions cliOpts = null ) {

        this.providerConfigs = ProviderConfig.createFromMap(config)

        this.project = resolveName(pipelineName)
        this.localPath = new File(root, project)

        if( !hub )
            this.hub = cliOpts?.getHubProvider()
        if( !hub )
            hub = guessHubProviderFromGitConfig()
        if( !hub )
            hub = DEFAULT_HUB

        // create the hub provider
        this.provider = createHubProviderFor(hub)

        if( cliOpts?.hubUser ) {
            cliOpts.hubProvider = hub
            final user = cliOpts.getHubUser()
            final pwd = cliOpts.getHubPassword()
            provider.setCredentials(user, pwd)
        }

        return this
    }

    String resolveName( String name ) {
        assert name

        if( name.endsWith('.git') ) {
            try {
                def url = new GitUrlParser(name)

                if( url.protocol == 'file' ) {
                    this.hub = "file:${url.location}"
                    providerConfigs << new ProviderConfig(this.hub, [path:url.location])
                }

                return url.project
            }
            catch( IllegalArgumentException e ) {
                log.debug e.message
            }
        }

        String[] parts = name.split('/')
        def last = parts[-1]
        if( last.endsWith('.nf') || last.endsWith('.nxf') ) {
            if( parts.size()==1 )
                throw new AbortOperationException("Not a valid pipeline name: $name")

            if( parts.size()==2 ) {
                mainScript = last
                parts = [ parts.first() ]
            }
            else {
                mainScript = parts[2..-1].join('/')
                parts = parts[0..1]
            }
        }

        if( parts.size() == 2 ) {
            return parts.join('/')
        }
        else if( parts.size()>2 ) {
            throw new AbortOperationException("Not a valid pipeline name: $name")
        }
        else {
            name = parts[0]
        }

        def qualifiedName = find(name)
        if( !qualifiedName ) {
            return "$DEFAULT_ORGANIZATION/$name".toString()
        }

        if( qualifiedName instanceof List ) {
            throw new AbortOperationException("Which one do you mean?\n${qualifiedName.join('\n')}")
        }

        return qualifiedName
    }

    String getProject() { project }

    @PackageScope
    RepositoryProvider createHubProviderFor(String providerName) {

        final config = providerConfigs.find { it.name == providerName }
        if( !config )
            throw new AbortOperationException("Unknown pipeline repository configuration provider: $providerName")

        RepositoryProvider .create(config, project)

    }

    AssetManager setLocalPath(File path) {
        this.localPath = path
        return this
    }

    AssetManager setForce( boolean value ) {
        this.force = value
        return this
    }

    void checkValidRemoteRepo() {
        def scriptName = getMainScriptName()
        provider.validateFor(scriptName)
    }

    @Memoized
    String getGitRepositoryUrl() {

        if( localPath.exists() ) {
            return localPath.toURI().toString()
        }

        provider.getCloneUrl()
    }

    File getLocalPath() { localPath }

    File getMainScriptFile() {
        if( !localPath.exists() ) {
            throw new AbortOperationException("Unknown pipeline folder: $localPath")
        }

        def mainScript = getMainScriptName()
        def result = new File(localPath, mainScript)
        if( !result.exists() )
            throw new AbortOperationException("Missing pipeline script: $result")

        return result
    }

    String getMainScriptName() {
        if( mainScript )
            return mainScript

        readManifest().mainScript ?: DEFAULT_MAIN_FILE_NAME
    }

    String getHomePage() {
        def manifest = readManifest()
        manifest.homePage ?: provider.getHomePage()
    }

    String getDefaultBranch() {
        readManifest().defaultBranch ?: DEFAULT_BRANCH
    }

    String getDescription() {
        // note: if description is not set it will return an empty ConfigObject
        // thus use the elvis operator to return null
        readManifest().description ?: null
    }

    protected Map readManifest() {
        ConfigObject result = null
        try {
            def text = localPath.exists() ? new File(localPath, MANIFEST_FILE_NAME).text : provider.readText(MANIFEST_FILE_NAME)
            if( text ) {
                def config = new ComposedConfigSlurper().setIgnoreIncludes(true).parse(text)
                result = (ConfigObject)config.manifest
            }
        }
        catch( Exception e ) {
            log.debug "Cannot read pipeline manifest -- Cause: ${e.message}"
        }
        // by default return an empty object
        return result ?: new ConfigObject()
    }

    String getBaseName() {
        def result = project.tokenize('/')
        if( result.size() > 2 ) throw new IllegalArgumentException("Not a valid projct name: $project")
        return result.size()==1 ? result[0] : result[1]
    }

    boolean isLocal() {
        localPath.exists()
    }

    /**
     * @return true if no differences exist between the working-tree, the index,
     *         and the current HEAD, false if differences do exist
     */
    boolean isClean() {
        try {
            git.status().call().isClean()
        }
        catch( RepositoryNotFoundException e ) {
            return true
        }
    }

    /**
     * Close the underlying Git repository
     */
    void close() {
        if( _git ) {
            _git.close()
            _git = null
        }
    }

    /**
     * @return The list of available pipelines
     */
    static List<String> list() {
        log.debug "Listing pipelines in folders: $root"

        def result = []
        if( !root.exists() )
            return result

        root.eachDir { File org ->
            org.eachDir { File it ->
                result << "${org.getName()}/${it.getName()}".toString()
            }
        }

        return result
    }

    static protected def find( String name ) {
        def exact = []
        def partial = []

        list().each {
            def items = it.split('/')
            if( items[1] == name )
                exact << it
            else if( items[1].startsWith(name ) )
                partial << it
        }

        def list = exact ?: partial
        return list.size() ==1 ? list[0] : list
    }


    protected Git getGit() {
        if( !_git ) {
            _git = Git.open(localPath)
        }
        return _git
    }

    /**
     * Download a pipeline from a remote Github repository
     *
     * @param revision The revision to download
     * @result A message representing the operation result
     */
    def download(String revision=null) {
        assert project

        /*
         * if the pipeline already exists locally pull it from the remote repo
         */
        if( !localPath.exists() ) {
            localPath.parentFile.mkdirs()
            // make sure it contains a valid repository
            checkValidRemoteRepo()

            log.debug "Pulling $project -- Using remote clone url: ${getGitRepositoryUrl()}"

            // clone it
            def clone = Git.cloneRepository()
            if( provider.hasCredentials() )
                clone.setCredentialsProvider( new UsernamePasswordCredentialsProvider(provider.user, provider.password) )

            clone
                .setURI(getGitRepositoryUrl())
                .setDirectory(localPath)
                .call()

            // return status message
            return "downloaded from ${gitRepositoryUrl}"
        }


        log.debug "Pull pipeline $project  -- Using local path: $localPath"

        // verify that is clean
        if( !isClean() )
            throw new AbortOperationException("$project contains uncommitted changes -- cannot pull from repository")

        if( revision && revision != getCurrentRevision() ) {
            /*
             * check out a revision before the pull operation
             */
            try {
                git.checkout() .setName(revision) .call()
            }
            /*
             * If the specified revision does not exist
             * Try to checkout it from a remote branch and return
             */
            catch ( RefNotFoundException e ) {
                def ref = checkoutRemoteBranch(revision)
                return "checkout-out at ${ref.getObjectId()}"
            }
        }

        // now pull to update it
        def pull = git.pull()
        if( provider.hasCredentials() )
            pull.setCredentialsProvider( new UsernamePasswordCredentialsProvider(provider.user, provider.password))

        def result = pull.call()
        if(!result.isSuccessful())
            throw new AbortOperationException("Cannot pull pipeline: '$project ' -- ${result.toString()}")

        return result?.mergeResult?.mergeStatus?.toString()

    }

    /**
     * Clone a pipeline from a remote pipeline repository to the specified folder
     *
     * @param directory The folder when the pipeline will be cloned
     * @param revision The revision to be cloned. It can be a branch, tag, or git revision number
     */
    void clone(File directory, String revision = null) {

        def clone = Git.cloneRepository()
        def uri = getGitRepositoryUrl()
        log.debug "Clone pipeline $project  -- Using remote URI: ${uri} into: $directory"

        if( !uri )
            throw new AbortOperationException("Cannot find the specified pipeline: $project ")

        clone.setURI(uri)
        clone.setDirectory(directory)
        if( provider.hasCredentials() )
            clone.setCredentialsProvider( new UsernamePasswordCredentialsProvider(provider.user, provider.password))

        if( revision )
            clone.setBranch(revision)

        clone.call()
    }

    /**
     * @return The symbolic name of the current revision i.e. the current checked out branch or tag
     */
    String getCurrentRevision() {
        Ref head = git.getRepository().getRef(Constants.HEAD);
        if( !head )
            return '(unknown)'

        if( head.isSymbolic() )
            return Repository.shortenRefName(head.getTarget().getName())

        if( !head.getObjectId() )
            return '(unknown)'

        // try to resolve the the current object it to a tag name
        Map<ObjectId, String> names = git.nameRev().addPrefix( "refs/tags/" ).add(head.objectId).call()
        names.get( head.objectId ) ?: head.objectId.name()
    }

    String getCurrentRevisionAndName() {
        Ref head = git.getRepository().getRef(Constants.HEAD);
        if( !head )
            return '(unknown)'

        if( head.isSymbolic() ) {
            return "${head.objectId.name()?.substring(0,10)} [${Repository.shortenRefName(head.getTarget().getName())}]"
        }

        if( !head.getObjectId() )
            return '(unknown)'

        // try to resolve the the current object it to a tag name
        Map<ObjectId, String> allNames = git.nameRev().addPrefix( "refs/tags/" ).add(head.objectId).call()
        def name = allNames.get( head.objectId )
        if( name ) {
            return "${head.objectId.name()?.substring(0,10)} [${name}]"
        }
        else {
            return head.objectId.name()?.substring(0,10)
        }
    }


    /**
     * @return A list of existing branches and tags names. For example
     * <pre>
     *     * master (default)
     *       patch-x
     *       v1.0 (t)
     *       v1.1 (t)
     * </pre>
     *
     * The star character on the left highlight the current revision, the string {@code (default)}
     *  ticks that it is the default working branch (usually the master branch), while the string {@code (t)}
     *  shows that the revision is a git tag (instead of a branch)
     */
    List<String> getRevisions(int level) {

        def current = getCurrentRevision()
        def master = getDefaultBranch()

        List<String> branches = git.branchList()
            .setListMode(ListBranchCommand.ListMode.ALL)
            .call()
            .findAll { it.name.startsWith('refs/heads/') || it.name.startsWith('refs/remotes/origin/') }
            .unique { shortenRefName(it.name) }
            .collect { Ref it -> formatRef(it,current,master,false,level) }

        List<String> tags = git.tagList()
                .call()
                .findAll  { it.name.startsWith('refs/tags/') }
                .collect { formatRef(it,current,master,true,level) }

        def result = new ArrayList<String>(branches.size() + tags.size())
        result.addAll(branches)
        result.addAll(tags)
        return result
    }

    protected String formatRef( Ref ref, String current, String master, boolean tag, int level ) {

        def result = new StringBuilder()
        def name = shortenRefName(ref.name)
        result << (name == current ? '*' : ' ')

        if( level ) {
            def peel = git.getRepository().peel(ref)
            def obj = peel.getPeeledObjectId() ?: peel.getObjectId()
            result << ' '
            result << (level == 1 ? obj.name().substring(0,10) : obj.name())
        }

        result << ' ' << name

        if( tag )
            result << ' [t]'
        else if( master == name )
            result << ' (default)'

        return result.toString()
    }

    private String shortenRefName( String name ) {
        if( name.startsWith('refs/remotes/origin/') )
            return name.replace('refs/remotes/origin/', '')

        return Repository.shortenRefName(name)
    }

    /**
     * Checkout a specific revision
     * @param revision The revision to be checked out
     */
    void checkout( String revision = null ) {
        assert localPath

        def current = getCurrentRevision()
        if( current != defaultBranch ) {
            if( !revision ) {
                throw new AbortOperationException("Pipeline '$project ' currently is sticked on revision: $current -- you need to specify explicitly a revision with the option '-r' to use it")
            }
        }
        else if( !revision || revision == current ) {
            // nothing to do
            return
        }

        // verify that is clean
        if( !isClean() )
            throw new AbortOperationException("$project contains uncommitted changes -- Cannot switch to revision: $revision")

        try {
            git.checkout().setName(revision) .call()
        }
        catch( RefNotFoundException e ) {
            checkoutRemoteBranch(revision)
        }

    }


    protected Ref checkoutRemoteBranch( String revision ) {

        git.fetch().call()
        git.checkout()
                .setCreateBranch(true)
                .setName(revision)
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                .setStartPoint("origin/" + revision)
                .call()
    }

    public void updateModules() {

        if( !localPath )
            return // nothing to do

        final marker = new File(localPath, '.gitmodules')
        if( !marker.exists() || marker.empty() )
            return

        // the `gitmodules` attribute in the manifest makes it possible to enable/disable modules updating
        final modules = readManifest().gitmodules
        if( modules == false )
            return

        List<String> filter = []
        if( modules instanceof List ) {
            filter.addAll(modules as List)
        }
        else if( modules instanceof String ) {
            filter.addAll( (modules as String).tokenize(', ') )
        }

        final init = git.submoduleInit()
        final update = git.submoduleUpdate()
        filter.each { String m -> init.addPath(m); update.addPath(m) }
        // call submodule init
        init.call()
        // call submodule update
        def updatedList = update.call()
        log.debug "Update submodules $updatedList"
    }

    protected String getGitConfigRemoteUrl() {
        if( !localPath ) {
            return null
        }

        final gitConfig = new File(localPath,'.git/config')
        if( !gitConfig.exists() ) {
            return null
        }

        final iniFile = new IniFile().load(gitConfig)
        final branch = getDefaultBranch() ?: DEFAULT_BRANCH
        final remote = iniFile.getString("branch \"${branch}\"", "remote", "origin")
        final url = iniFile.getString("remote \"${remote}\"", "url")

        return url
    }

    protected String getGitConfigRemoteServer() {

        def url = getGitConfigRemoteUrl()
        if( !url ) return null

        try {
            return new GitUrlParser(url).location
        }
        catch( IllegalArgumentException e) {
            log.debug e.message ?: e.toString()
            return null
        }

    }


    protected String guessHubProviderFromGitConfig() {

        final server = getGitConfigRemoteServer()
        final result = providerConfigs.find { it -> it.domain == server }

        return result ? result.name : null
    }
}
