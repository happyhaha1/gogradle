package com.github.blindpirate.gogradle.core.dependency.lock;

import com.github.blindpirate.gogradle.core.dependency.GolangDependency;
import com.github.blindpirate.gogradle.core.dependency.GolangDependencySet;
import com.github.blindpirate.gogradle.core.dependency.ResolvedDependency;
import com.github.blindpirate.gogradle.core.dependency.parse.MapNotationParser;
import com.github.blindpirate.gogradle.core.dependency.produce.ExternalDependencyFactory;
import com.github.blindpirate.gogradle.util.Assert;
import com.github.blindpirate.gogradle.util.DataExchange;
import com.github.blindpirate.gogradle.util.DependencySetUtils;
import com.github.blindpirate.gogradle.util.IOUtils;
import org.gradle.api.Project;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.blindpirate.gogradle.util.Cast.cast;
import static com.github.blindpirate.gogradle.util.DataExchange.parseYaml;

@Singleton
public class DefaultLockedDependencyManager extends ExternalDependencyFactory implements LockedDependencyManager {

    public static final String WARNING = "# This file is generated by gogradle automatically, "
            + "you should NEVER modify it manually.\n";
    private final Project project;

    @Inject
    public DefaultLockedDependencyManager(MapNotationParser mapNotationParser, Project project) {
        super(mapNotationParser);
        this.project = project;
    }

    private static final String LOCK_FILE = "gogradle.lock";

    @Override
    public GolangDependencySet getLockedDependencies() {
        File lockFile = project.getRootDir().toPath().resolve(LOCK_FILE).toFile();
        if (!lockFile.exists()) {
            return GolangDependencySet.empty();
        }
        GogradleLockModel model = parseYaml(lockFile, GogradleLockModel.class);
        return DependencySetUtils.parseMany(model.getDependencies(), mapNotationParser);
    }

    @Override
    public void lock(Collection<? extends GolangDependency> flatDependencies) {
        List<Map<String, Object>> notations = flatDependencies
                .stream().map(this::toNotation).collect(Collectors.toList());
        GogradleLockModel model = GogradleLockModel.of(notations);
        String content = DataExchange.toYaml(model);
        content = insertWarning(content);
        IOUtils.write(project.getRootDir(), LOCK_FILE, content);
    }

    private String insertWarning(String content) {
        return WARNING + content;
    }

    private Map<String, Object> toNotation(GolangDependency dependency) {
        Assert.isTrue(dependency instanceof ResolvedDependency);
        return cast(ResolvedDependency.class, dependency).toLockedNotation();
    }

    @Override
    protected String identityFileName() {
        return LOCK_FILE;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> adapt(File file) {
        GogradleLockModel model = parseYaml(file, GogradleLockModel.class);
        return model.getDependencies();
    }
}
