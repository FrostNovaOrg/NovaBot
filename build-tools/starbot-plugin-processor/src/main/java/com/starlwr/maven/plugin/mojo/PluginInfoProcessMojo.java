package com.starlwr.maven.plugin.mojo;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.apache.maven.model.Contributor;
import org.apache.maven.model.License;
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
import java.util.stream.Collectors;

/**
 * 插件信息处理 Mojo
 */
@Mojo(name = "plugin-info-process", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class PluginInfoProcessMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}/plugin.json")
    private File outputFile;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("Starting process StarBot plugin info");
        getLog().info(project.getGroupId() + ":" + project.getArtifactId() + "-v" + project.getVersion());

        JSONObject result = new JSONObject();
        result.put("groupId", project.getGroupId());
        result.put("artifactId", project.getArtifactId());
        result.put("version", project.getVersion());
        result.put("name", project.getName());
        result.put("description", project.getDescription());
        result.put("url", project.getUrl());
        result.put("author", project.getDevelopers().stream().map(Contributor::getName).collect(Collectors.joining(", ")));
        result.put("license", project.getLicenses().stream().map(License::getName).collect(Collectors.joining(", ")));

        File parent = outputFile.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new MojoExecutionException("Failed create output directory: " + outputFile.getParentFile());
        }

        try {
            Files.writeString(outputFile.toPath(), JSON.toJSONString(result, JSONWriter.Feature.PrettyFormat));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed write info JSON file", e);
        }

        getLog().info("Completed process StarBot plugin info");
    }
}
