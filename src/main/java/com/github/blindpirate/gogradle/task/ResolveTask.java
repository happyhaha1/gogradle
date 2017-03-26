package com.github.blindpirate.gogradle.task;

import com.github.blindpirate.gogradle.GogradleGlobal;
import com.github.blindpirate.gogradle.GolangPluginSetting;
import com.github.blindpirate.gogradle.build.BuildManager;
import com.github.blindpirate.gogradle.common.GoSourceCodeFilter;
import com.github.blindpirate.gogradle.core.GolangConfiguration;
import com.github.blindpirate.gogradle.core.GolangConfigurationManager;
import com.github.blindpirate.gogradle.core.dependency.GolangDependencySet;
import com.github.blindpirate.gogradle.core.dependency.ResolvedDependency;
import com.github.blindpirate.gogradle.core.dependency.lock.DefaultLockedDependencyManager;
import com.github.blindpirate.gogradle.core.dependency.produce.DependencyVisitor;
import com.github.blindpirate.gogradle.core.dependency.produce.ExternalDependencyFactory;
import com.github.blindpirate.gogradle.core.dependency.produce.GoImportExtractor;
import com.github.blindpirate.gogradle.core.dependency.produce.VendorDependencyFactory;
import com.github.blindpirate.gogradle.core.dependency.produce.external.glide.GlideDependencyFactory;
import com.github.blindpirate.gogradle.core.dependency.produce.external.glock.GlockDependencyFactory;
import com.github.blindpirate.gogradle.core.dependency.produce.external.godep.GodepDependencyFactory;
import com.github.blindpirate.gogradle.core.dependency.produce.external.gopm.GopmDependencyFactory;
import com.github.blindpirate.gogradle.core.dependency.produce.external.govendor.GovendorDependencyFactory;
import com.github.blindpirate.gogradle.core.dependency.produce.external.gvtgbvendor.GvtGbvendorDependencyFactory;
import com.github.blindpirate.gogradle.core.dependency.produce.external.trash.TrashDependencyFactory;
import com.github.blindpirate.gogradle.core.dependency.produce.strategy.GogradleRootProduceStrategy;
import com.github.blindpirate.gogradle.core.dependency.tree.DependencyTreeFactory;
import com.github.blindpirate.gogradle.core.dependency.tree.DependencyTreeNode;
import com.github.blindpirate.gogradle.core.pack.LocalDirectoryDependency;
import com.github.blindpirate.gogradle.util.IOUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.github.blindpirate.gogradle.task.GolangTaskContainer.PREPARE_TASK_NAME;

public abstract class ResolveTask extends DefaultTask {
    @Inject
    private GolangPluginSetting setting;

    @Inject
    private DependencyTreeFactory dependencyTreeFactory;

    @Inject
    private DependencyVisitor visitor;

    @Inject
    private GogradleRootProduceStrategy strategy;

    @Inject
    private GolangConfigurationManager configurationManager;

    @Inject
    private BuildManager buildManager;

    private DependencyTreeNode dependencyTree;

    public ResolveTask() {
        dependsOn(PREPARE_TASK_NAME);
    }

    // INPUT 1: dependencies declared in build.gradle
    @Input
    public GolangDependencySet getDependencies() {
        GolangConfiguration configuration =
                (GolangConfiguration) getProject().getConfigurations().getByName(getConfigurationName());
        return configuration.getGolangDependencies();
    }

    // INPUT 2: lockfiles generated by external dependency management tools (including Gogradle itself)
    @InputFiles
    @SkipWhenEmpty
    public List<File> getExternalLockfiles() throws IOException {
        List<Class> externalToolClasses = Arrays.asList(GlideDependencyFactory.class,
                GlockDependencyFactory.class,
                GodepDependencyFactory.class,
                GopmDependencyFactory.class,
                GovendorDependencyFactory.class,
                GvtGbvendorDependencyFactory.class,
                TrashDependencyFactory.class,
                DefaultLockedDependencyManager.class);

        return externalToolClasses.stream()
                .map(clazz -> {
                    Object instance = GogradleGlobal.getInstance(clazz);
                    ExternalDependencyFactory factory = ExternalDependencyFactory.class.cast(instance);
                    return new File(getProject().getRootDir(), factory.identityFileName());
                })
                .filter(File::isFile)
                .filter(File::exists)
                .collect(Collectors.toList());
    }

    // INPUT 3: vendor directory
    @InputFiles
    @SkipWhenEmpty
    public List<File> getVendorDirectory() {
        File ret = new File(getProject().getRootDir(), VendorDependencyFactory.VENDOR_DIRECTORY);
        return ret.exists() ? Arrays.asList(ret) : Collections.emptyList();
    }

    // INPUT 4: all go files in specific configuration
    @InputFiles
    @SkipWhenEmpty
    public Collection<File> getGoSourceFiles() {
        GoSourceCodeFilter filter = GoImportExtractor.FILTERS.get(getConfigurationName());
        return IOUtils.filterFilesRecursively(getProject().getRootDir(), filter);
    }

    @OutputDirectory
    public File getInstallationDirectory() {
        return buildManager.getInstallationDirectory(getConfigurationName()).toFile();
    }

    @TaskAction
    public void resolve() {
        resolveDependencies();
        installDependencies();
    }

    private void installDependencies() {
        dependencyTree.flatten()
                .stream()
                .map(dependency -> (ResolvedDependency) dependency)
                .forEach((dependency) -> buildManager.installDependency(dependency, getConfigurationName()));
    }

    private void resolveDependencies() {
        File rootDir = getProject().getRootDir();
        LocalDirectoryDependency rootProject = LocalDirectoryDependency.fromLocal(
                setting.getPackagePath(),
                rootDir);
        GolangConfiguration configuration = configurationManager.getByName(getConfigurationName());

        GolangDependencySet dependencies = strategy.produce(rootProject, rootDir, visitor, getConfigurationName());
        rootProject.setDependencies(dependencies);
        dependencyTree = dependencyTreeFactory.getTree(configuration, rootProject);
    }

    public DependencyTreeNode getDependencyTree() {
        if (dependencyTree == null) {
            // in case of incremental build
            resolve();
        }
        return dependencyTree;
    }


    protected abstract String getConfigurationName();

}
