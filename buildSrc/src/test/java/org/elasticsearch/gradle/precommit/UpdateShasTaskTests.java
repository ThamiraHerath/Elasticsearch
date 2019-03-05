package org.elasticsearch.gradle.precommit;

import org.apache.commons.io.FileUtils;
import org.elasticsearch.gradle.test.GradleUnitTestCase;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.NoSuchAlgorithmException;

import static org.hamcrest.CoreMatchers.equalTo;

public class UpdateShasTaskTests extends GradleUnitTestCase {

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    private UpdateShasTask task;

    private Project project;

    private Dependency dependency;

    @Before
    public void prepare() throws IOException {
        project = createProject();
        task = createUpdateShasTask(project);
        dependency = project.getDependencies().localGroovy();
    }

    @Test
    public void whenDependencyDoesntExistThenShouldDeleteDependencySha()
        throws IOException, NoSuchAlgorithmException {

        File unusedSha = createFileIn(getLicensesDir(project), "test.sha1", "");
        task.updateShas();

        assertFalse(unusedSha.exists());
    }

    @Test
    public void whenDependencyExistsButShaNotThenShouldCreateNewShaFile()
        throws IOException, NoSuchAlgorithmException {
        project.getDependencies().add("compile", dependency);

        getLicensesDir(project).mkdir();
        task.updateShas();

        Path groovySha = Files
            .list(getLicensesDir(project).toPath())
            .findFirst().get();

        assertTrue(groovySha.toFile().getName().startsWith("groovy-all"));
    }

    @Test
    public void whenDependencyAndWrongShaExistsThenShouldNotOverwriteShaFile()
        throws IOException, NoSuchAlgorithmException {
        project.getDependencies().add("compile", dependency);

        File groovyJar = task.getParentTask().getDependencies().getFiles().iterator().next();
        String groovyShaName = groovyJar.getName() + ".sha1";

        File groovySha = createFileIn(getLicensesDir(project), groovyShaName, "content");
        task.updateShas();

        assertThat(FileUtils.readFileToString(groovySha), equalTo("content"));
    }

    private Project createProject() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply(JavaPlugin.class);

        return project;
    }

    private File getLicensesDir(Project project) {
        return getFile(project, "licenses");
    }

    private File getFile(Project project, String fileName) {
        return project.getProjectDir().toPath().resolve(fileName).toFile();
    }

    private File createFileIn(File parent, String name, String content) throws IOException {
        parent.mkdir();

        Path path = parent.toPath().resolve(name);
        File file = path.toFile();

        Files.write(path, content.getBytes(), StandardOpenOption.CREATE);

        return file;
    }

    private UpdateShasTask createUpdateShasTask(Project project) {
        UpdateShasTask task =  project.getTasks()
            .register("updateShas", UpdateShasTask.class)
            .get();

        task.setParentTask(createDependencyLicensesTask(project));
        return task;
    }

    private DependencyLicensesTask createDependencyLicensesTask(Project project) {
        DependencyLicensesTask task =  project.getTasks()
            .register("dependencyLicenses", DependencyLicensesTask.class)
            .get();

        task.setDependencies(getDependencies(project));
        return task;
    }

    private FileCollection getDependencies(Project project) {
        return project.getConfigurations().getByName("compile");
    }
}
