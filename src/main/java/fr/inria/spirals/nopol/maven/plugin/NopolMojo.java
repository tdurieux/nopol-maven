package fr.inria.spirals.nopol.maven.plugin;

import fr.inria.lille.commons.synthesis.smt.solver.SolverFactory;
import fr.inria.lille.repair.common.config.NopolContext;
import fr.inria.lille.repair.common.patch.Patch;
import fr.inria.lille.repair.common.synth.StatementType;
import fr.inria.lille.repair.nopol.NoPol;
import fr.inria.lille.repair.nopol.NopolResult;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Level;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.surefire.log.api.NullConsoleLogger;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugins.surefire.report.ReportTestSuite;
import org.apache.maven.plugins.surefire.report.SurefireReportParser;
import org.apache.maven.project.MavenProject;
import org.apache.maven.reporting.MavenReportException;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Mojo( name = "nopol", aggregator = true,
        defaultPhase = LifecyclePhase.TEST,
        requiresDependencyResolution = ResolutionScope.TEST)
public class NopolMojo extends AbstractMojo {

    private static String HARDCODED_NOPOL_VERSION = "0.2-SNAPSHOT";

    @Parameter(property = "java.version", defaultValue = "-1")
    protected String javaVersion;

    @Parameter(property = "maven.compiler.source", defaultValue = "-1")
    protected String source;

    @Parameter(property = "maven.compile.source", defaultValue = "-1")
    protected String oldSource;

    @Component
    private ArtifactFactory artifactFactory;

    @Parameter( defaultValue = "${project.build.directory}/nopol", property = "outputDir", required = true )
    private File outputDirectory;

    @Parameter( defaultValue = "${project.build.directory}/nopol", property = "resultDir", required = true )
    private File resultDirectory;

    @Parameter( defaultValue = "pre-then-cond", property = "type", required = true )
    private String type;

    @Parameter( defaultValue = "10", property = "maxTime", required = true )
    private int maxTime;

    @Parameter( defaultValue = "dynamoth", property = "synthesis", required = true )
    private String synthesis;

    @Parameter(defaultValue="${project}", readonly=true, required=true)
    private MavenProject project;

    @Parameter( defaultValue = "${reactorProjects}", readonly = true )
    private List<MavenProject> reactorProjects;

    @Parameter(defaultValue="${localRepository}")
    private ArtifactRepository localRepository;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        List<String> failingTestCases = getFailingTests();
        List<URL> dependencies = getClasspath();
        List<File> sourceFolders = getSourceFolders();

        System.out.println(failingTestCases.size()+" detected failing test classes. ("+ StringUtils.join(failingTestCases,":")+")");

        int complianceLevel = 7;
        if (!source.equals("-1")) {
            complianceLevel = Integer.parseInt(source.substring(2));
        } else if (!oldSource.equals("-1")) {
            complianceLevel = Integer.parseInt(oldSource.substring(2));
        } else if (!javaVersion.equals("-1")) {
            complianceLevel = Integer.parseInt(javaVersion.substring(2, 3));
        }

        NopolContext nopolContext = new NopolContext(sourceFolders.toArray(new File[0]), dependencies.toArray(new URL[0]), failingTestCases.toArray(new String[0]), Collections.<String>emptyList());
        nopolContext.setComplianceLevel(complianceLevel);
        nopolContext.setTimeoutTestExecution(300);
        nopolContext.setMaxTimeEachTypeOfFixInMinutes(15);
        nopolContext.setMaxTimeInMinutes(maxTime);
        nopolContext.setLocalizer(NopolContext.NopolLocalizer.GZOLTAR);
        nopolContext.setSynthesis(NopolContext.NopolSynthesis.DYNAMOTH);
        nopolContext.setType(StatementType.COND_THEN_PRE);
        nopolContext.setSolver(NopolContext.NopolSolver.Z3);
        nopolContext.setSolverPath("/tmp/z3");
        nopolContext.setOnlyOneSynthesisResult(false);
        nopolContext.setJson(true);

        SolverFactory.setSolver(nopolContext.getSolver(), "/tmp/z3");
        final NoPol nopol = new NoPol(nopolContext);
        NopolResult result = nopol.build();

        System.out.println("Nopol executed after: "+result.getDurationInMilliseconds()+" ms.");
        System.out.println("Status: "+result.getNopolStatus());
        System.out.println("Angelic values: "+result.getNbAngelicValues());
        System.out.println("Nb statements: "+result.getNbStatements());
        if (result.getPatches().size() > 0) {
            for (Patch p : result.getPatches()) {
                System.out.println("Obtained patch: "+p.asString());
            }
        }
    }

    private File getSurefireReportsDirectory( MavenProject subProject ) {
        String buildDir = subProject.getBuild().getDirectory();
        return new File( buildDir + "/surefire-reports" );
    }

    private List<String> getFailingTests() {
        List<String> result = new ArrayList<>();

        for (MavenProject mavenProject : reactorProjects) {
            File surefireReportsDirectory = getSurefireReportsDirectory(mavenProject);
            SurefireReportParser parser = new SurefireReportParser(Collections.singletonList(surefireReportsDirectory), Locale.ENGLISH, new NullConsoleLogger());

            try {
                List<ReportTestSuite> testSuites = parser.parseXMLReportFiles();
                for (ReportTestSuite reportTestSuite : testSuites) {
                    if (reportTestSuite.getNumberOfErrors()+reportTestSuite.getNumberOfFailures() > 0) {
                        result.add(reportTestSuite.getFullClassName());
                    }
                }
            } catch (MavenReportException e) {
                e.printStackTrace();;
            }

        }

        return result;
    }

    private List<URL> getClasspath() {
        List<URL> classpath = new ArrayList<URL>();
        for (MavenProject mavenProject : reactorProjects) {
            try {
                for (String s : (List<String>)mavenProject.getTestClasspathElements()) {
                    File f = new File(s);
                    classpath.add(f.toURI().toURL());
                }
            } catch (DependencyResolutionRequiredException e) {
                continue;
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
        }

        final Artifact artifactPom = artifactFactory.createArtifact("fr.inria.lille.adam","nopol", HARDCODED_NOPOL_VERSION, null, "pom");
        File filePom = new File(localRepository.getBasedir() + "/" + localRepository.pathOf(artifactPom));

        if (filePom.exists()) {
            MavenXpp3Reader pomReader = new MavenXpp3Reader();
            try (FileReader reader = new FileReader(filePom)) {
                Model model = pomReader.read(reader);

                List<Dependency> dependencies = model.getDependencies();
                for (Dependency dependency : dependencies) {
                    if (!dependency.isOptional() && dependency.getScope() == null && dependency.getVersion() != null) {
                        Artifact artifact = artifactFactory.createArtifact(dependency.getGroupId(),dependency.getArtifactId(), dependency.getVersion(), null, dependency.getType());
                        File jarFile = new File(localRepository.getBasedir() + "/" + localRepository.pathOf(artifact));

                        classpath.add(jarFile.toURI().toURL());
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.err.println("Error occured, dependency will be passed: "+e.getMessage());
            }
        }
        return classpath;
    }

    private List<File> getSourceFolders() {
        Set<File> sourceFolder = new HashSet<>();
        for (MavenProject mavenProject : reactorProjects) {
            File sourceDirectory = new File(mavenProject.getBuild().getSourceDirectory());
            if (sourceDirectory.exists()) {
                sourceFolder.add(sourceDirectory);
            }

            File generatedSourceDirectory = new File(mavenProject.getBuild().getOutputDirectory() + "/generated-sources");
            if (generatedSourceDirectory.exists()) {
                sourceFolder.add(generatedSourceDirectory);
            }
        }
        return new ArrayList<>(sourceFolder);
    }
}
