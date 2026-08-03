package com.starlwr.maven.plugin.mojo;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.starlwr.maven.plugin.model.Dependency;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 依赖处理 Mojo
 */
@Mojo(name = "dependency-process", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class DependencyProcessMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}/dependency.json")
    private File outputFile;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("Starting process StarBot plugin dependencies");
        getLog().info(project.getGroupId() + ":" + project.getArtifactId() + "-v" + project.getVersion());

        Map<String, List<Dependency>> result = new LinkedHashMap<>();
        List<Dependency> plugins = new ArrayList<>();
        List<Dependency> dependencies = new ArrayList<>();

        for (Artifact artifact : project.getArtifacts()) {
            String scope = artifact.getScope();
            if (scope == null || "compile".equalsIgnoreCase(scope) || "runtime".equalsIgnoreCase(scope)) {
                if (isPlugin(artifact)) {
                    plugins.add(new Dependency(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()));
                } else {
                    dependencies.add(new Dependency(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()));
                }
            }
        }

        result.put("plugins", plugins);
        result.put("dependencies", dependencies);

        File parent = outputFile.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new MojoExecutionException("Failed create output directory: " + outputFile.getParentFile());
        }

        try {
            Files.writeString(outputFile.toPath(), JSON.toJSONString(result, JSONWriter.Feature.PrettyFormat));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed write dependencies JSON file", e);
        }

        if (!plugins.isEmpty()) {
            getLog().info("Processed " + plugins.size() + " plugins: " + plugins);
        }
        getLog().info("Processed " + dependencies.size() + " dependencies");

        getLog().info("Completed process StarBot plugin dependencies");
    }

    /**
     * 判断依赖是否为 StarBot 插件
     * @param artifact 依赖
     * @return 是否为 StarBot 插件
     */
    private boolean isPlugin(Artifact artifact) throws MojoExecutionException {
        File jar = artifact.getFile();
        try {
            try (JarFile jarFile = new JarFile(jar)) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if ("plugin.json".equals(entry.getName())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed process StarBot plugin dependencies", e);
        }

        return false;
    }
}
