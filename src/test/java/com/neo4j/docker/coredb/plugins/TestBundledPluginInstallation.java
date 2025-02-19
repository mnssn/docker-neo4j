package com.neo4j.docker.coredb.plugins;

import com.neo4j.docker.utils.DatabaseIO;
import com.neo4j.docker.utils.Neo4jVersion;
import com.neo4j.docker.utils.TemporaryFolderManager;
import com.neo4j.docker.utils.TestSettings;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TestBundledPluginInstallation
{
    private static final int DEFAULT_BROWSER_PORT = 7474;
    private static final int DEFAULT_BOLT_PORT = 7687;
    private static final Logger log = LoggerFactory.getLogger( TestBundledPluginInstallation.class );
    private static String APOC = "apoc";
    private static String APOC_CORE = "apoc-core";
    private static String BLOOM = "bloom";
    private static String GDS = "graph-data-science";
    @RegisterExtension
    public static TemporaryFolderManager temporaryFolderManager = new TemporaryFolderManager();


    static Stream<Arguments> bundledPluginsArgs() {
        return Stream.of(
                // plugin name key, version it's bundled since, version bundled until, is enterprise only
                Arguments.arguments( APOC_CORE, new Neo4jVersion(4, 3, 15), new Neo4jVersion(5, 0, 0), false ),
                Arguments.arguments( APOC, new Neo4jVersion(5, 0, 0), null, false ),
                // Arguments.arguments( GDS, Neo4jVersion.NEO4J_VERSION_440, null, true ),
                Arguments.arguments( BLOOM, Neo4jVersion.NEO4J_VERSION_440, null, true )
        );
    }

    private GenericContainer createContainerWithBundledPlugin(String pluginName)
    {
        GenericContainer container = new GenericContainer( TestSettings.IMAGE_ID );

        container.withEnv( "NEO4J_AUTH", "none" )
                 .withEnv( "NEO4J_ACCEPT_LICENSE_AGREEMENT", "yes" )
                 .withEnv( Neo4jPluginEnv.get(), "[\"" +pluginName+ "\"]" )
                 .withExposedPorts( DEFAULT_BROWSER_PORT, DEFAULT_BOLT_PORT )
                 .withLogConsumer( new Slf4jLogConsumer( log ) )
                 .waitingFor( Wait.forHttp( "/" )
                                  .forPort( DEFAULT_BROWSER_PORT )
                                  .forStatusCode( 200 )
                                  .withStartupTimeout( Duration.ofSeconds( 60 ) )  );
        return container;
    }

    @ParameterizedTest(name = "testBundledPlugin_{0}")
    @MethodSource("bundledPluginsArgs")
    public void testBundledPlugin(String pluginName, Neo4jVersion bundledSince, Neo4jVersion bundledUntil, boolean isEnterpriseOnly) throws Exception
    {
        Assumptions.assumeTrue( TestSettings.NEO4J_VERSION.isAtLeastVersion( bundledSince ),
                                String.format("plugin %s was not bundled in Neo4j %s", pluginName, bundledSince));
        if(bundledUntil != null) {
            Assumptions.assumeTrue( TestSettings.NEO4J_VERSION.isOlderThan( bundledUntil ),
                                    String.format("plugin %s was not bundled after Neo4j %s", pluginName, bundledUntil));
        }
        if(isEnterpriseOnly)
        {
            Assumptions.assumeTrue( TestSettings.EDITION == TestSettings.Edition.ENTERPRISE,
                                    String.format("plugin %s is enterprise only", pluginName));
        }

        GenericContainer container = null;
        Path pluginsMount = null;
        try
        {
            container = createContainerWithBundledPlugin( pluginName );
            pluginsMount = temporaryFolderManager.createFolderAndMountAsVolume(container, "/plugins");
            container.start();
            DatabaseIO dbio = new DatabaseIO( container );
            dbio.putInitialDataIntoContainer( "neo4j", "none" );
        }
        catch(ContainerLaunchException e)
        {
            // we don't want this test to depend on the plugins actually working (that's outside the scope of
            // the docker tests), so we have to be robust to the container failing to start.
            log.error( String.format("The bundled %s plugin caused Neo4j to fail to start.", pluginName) );
        }
        finally
        {
            // verify the plugins were loaded.
            // This is done in the finally block because after stopping the container, the stdout cannot be retrieved.
            if (pluginsMount != null)
            {
                List<String> plugins = Files.list(pluginsMount).map( fname -> fname.getFileName().toString() )
                                            .filter( fname -> fname.endsWith( ".jar" ) )
                                            .collect(Collectors.toList());
                Assertions.assertTrue(plugins.size() == 1, "more than one plugin was loaded" );
                Assertions.assertTrue( plugins.get( 0 ).contains( pluginName ) );
                // Verify from container logs, that the plugins were loaded locally rather than downloaded.
                String logs = container.getLogs( OutputFrame.OutputType.STDOUT);
                String errlogs = container.getLogs( OutputFrame.OutputType.STDERR);
                Assertions.assertTrue(
                        Stream.of(logs.split( "\n" ))
                              .anyMatch( line -> line.matches( "Installing Plugin '" + pluginName + "' from /var/lib/neo4j/.*" ) ),
                        "Plugin was not installed from neo4j home");
            }
            if(container !=null)
            {
                container.stop();
            }
            else
            {
                Assertions.fail("Test failed before container could even be initialised");
            }
        }
    }

    @ParameterizedTest(name = "testBundledPlugin_downloadsIfNotAvailableLocally_{0}")
    @MethodSource("bundledPluginsArgs")
    public void testBundledPlugin_downloadsIfNotAvailableLocally
            (String pluginName, Neo4jVersion bundledSince, Neo4jVersion bundledUntil, boolean isEnterpriseOnly) throws Exception
    {
        Assumptions.assumeTrue( TestSettings.NEO4J_VERSION.isAtLeastVersion( bundledSince ),
                                String.format("plugin %s was not bundled in Neo4j %s", pluginName, bundledSince.toString()));
        if(bundledUntil != null) {
            Assumptions.assumeTrue( TestSettings.NEO4J_VERSION.isOlderThan( bundledUntil ),
                                    String.format("plugin %s was not bundled after Neo4j %s", pluginName, bundledUntil));
        }
        Assumptions.assumeTrue( isEnterpriseOnly, "Test only applies to enterprise only bundled plugins tested against community edition" );
        Assumptions.assumeTrue( TestSettings.EDITION == TestSettings.Edition.COMMUNITY,
                                "Test only applies to enterprise only bundled plugins tested against community edition" );


        GenericContainer container = null;
        Path pluginsMount = null;
        try
        {
            container = createContainerWithBundledPlugin( pluginName );
            pluginsMount = temporaryFolderManager.createFolderAndMountAsVolume(container, "/plugins");
            container.start();
        }
        catch(ContainerLaunchException e)
        {
            // we don't want this test to depend on the plugins actually working (that's outside the scope of
            // the docker tests), so we have to be robust to the container failing to start.
            log.error( String.format("The %s plugin caused Neo4j to fail to start.", pluginName) );
        }
        finally
        {
            // verify the plugins were loaded.
            // This is done in the finally block because after stopping the container, the stdout cannot be retrieved.
            if (pluginsMount != null)
            {
                List<String> plugins = Files.list(pluginsMount)
                                            .map( fname -> fname.getFileName().toString() )
                                            .filter( fname -> fname.endsWith( ".jar" ) )
                                            .collect(Collectors.toList());
                Assertions.assertTrue(plugins.size() == 1, "more than one plugin was loaded" );
                Assertions.assertTrue( plugins.get( 0 ).contains( pluginName ) );
                // Verify from container logs, that the plugins were loaded locally rather than downloaded.
                String logs = container.getLogs( OutputFrame.OutputType.STDOUT);
                String errlogs = container.getLogs( OutputFrame.OutputType.STDERR);
                Assertions.assertTrue(
                        Stream.of(logs.split( "\n" ))
                              .anyMatch( line -> line.matches( "Fetching versions.json for Plugin '" + pluginName + "' from http[s]?://.*" ) ),
                        "Plugin was not installed from cloud");
            }
            if(container !=null)
            {
                container.stop();
            }
            else
            {
                Assertions.fail("Test failed before container could even be initialised");
            }
        }
    }

    @Test
    void testPluginLoadsWithAuthentication() throws Exception
    {
        Assumptions.assumeTrue( TestSettings.NEO4J_VERSION.isAtLeastVersion( Neo4jVersion.NEO4J_VERSION_500 ) );

        final String PASSWORD = "12345678";

        try( GenericContainer container = createContainerWithBundledPlugin(BLOOM))
        {
            container.withEnv( "NEO4J_AUTH", "neo4j/"+PASSWORD )
                     .withEnv( "NEO4J_dbms_bloom_license__file", "/licenses/bloom.license" );
            // mounting logs because it's useful for debugging
            temporaryFolderManager.createFolderAndMountAsVolume(container, "/logs");
            Path licenseFolder = temporaryFolderManager.createFolderAndMountAsVolume(container, "/licenses");
            Files.writeString( licenseFolder.resolve("bloom.license"), "notareallicense" );
            // make sure the container successfully starts and we can write to it without getting authentication errors
            container.start();
            DatabaseIO dbio = new DatabaseIO( container );
            dbio.putInitialDataIntoContainer( "neo4j", PASSWORD );
        }
    }
}
