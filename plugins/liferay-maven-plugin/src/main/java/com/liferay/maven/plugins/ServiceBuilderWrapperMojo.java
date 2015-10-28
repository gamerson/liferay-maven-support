/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.maven.plugins;

import static com.liferay.maven.plugins.MojoUtil.createDependency;
import static com.liferay.maven.plugins.MojoUtil.isLiferayProject;

import com.liferay.maven.plugins.util.FileUtil;
import com.liferay.maven.plugins.util.SAXReaderUtil;

import java.io.File;
import java.io.IOException;

import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

import org.codehaus.plexus.util.xml.Xpp3Dom;

import org.dom4j.Document;

import org.twdata.maven.mojoexecutor.MojoExecutor;
import org.twdata.maven.mojoexecutor.MojoExecutor.ExecutionEnvironment;

/**
 * This provides a thin wrapper around the new
 * com.liferay:com.liferay.portal.tools.service.builder:build-service
 * mojo that is provided by extracted portal tools in Liferay 7.
 * It tries to gracefully translate settings previously applied to
 * liferay-maven-plugin version 6.2 to new 7.0 required settings
 * on the com.liferay.portal.tools.service.builder mojo
 *
 * @author Gregory Amerson
 * @goal   build-service
 */
public class ServiceBuilderWrapperMojo extends AbstractMojo {

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (!isLiferayProject(_mavenProject)) {
			getLog().info("Skipping " + _mavenProject.getArtifactId());

			return;
		}

		final Xpp3Dom wrappedMojoConfiguration =
			buildWrappedMojoConfiguration();

		final String inputFileName = wrappedMojoConfiguration.getChild(
			"inputFileName").getValue();

		final File inputFile = new File(_baseDir, inputFileName);

		if (!inputFile.exists()) {
			throw new MojoExecutionException(
				"inputFile does not exist: " + inputFile.getAbsolutePath());
		}

		final Document document;

		try {
			document = SAXReaderUtil.read(inputFile, false);
		}
		catch ( Exception e ) {
			throw new MojoExecutionException(
				"Unable to read intputFile: " + inputFile, e );
		}

		final Matcher matcher = SERVICE_BUILDER_PUBLIC_ID.matcher(
			document.getDocType().getPublicID());

		if (matcher.matches()) {
			final String version = matcher.group(1);

			getLog().info( "Detected Liferay version: " + version );

			if (!version.startsWith("7")) {
				throw new MojoExecutionException(
					"This build-service goal is only compatible with " +
					"Liferay 7.0 and greater.");
			}
		}
		else {
			throw new MojoExecutionException(
				"Unable to determine ServiceBuilder version from Public ID.");
		}

		getLog().info("Building from " + inputFileName);

		try {
			copyServicePropertiesFile();
		}
		catch ( Exception e ) {
			throw new MojoExecutionException( e.getMessage(), e );
		}

		// check to see if wrapper configuration contains a custom
		// service builder version

		final String customServiceBuilderVersion =
			getCustomServiceBuilderVersion();

		final String serviceBuilderMojoPluginVersion =
			customServiceBuilderVersion != null &&
			!customServiceBuilderVersion.isEmpty()
			? customServiceBuilderVersion : "1.0.52";

		getLog().debug(
			"Using service.builder mojo version: " +
			serviceBuilderMojoPluginVersion );

		final Dependency[] dependencies = new Dependency[] {
			createDependency(
				"com.liferay", "org.freemarker", "2.3.17.LIFERAY-PATCHED-1", "",
				"jar" ),
			createDependency(
				"com.thoughtworks.qdox", "qdox", "1.12.1", "", "jar" ),
			createDependency( "jalopy", "jalopy", "1.5rc3", "", "jar" ),
			createDependency( "dom4j", "dom4j", "1.6.1", "", "jar" ),
			createDependency( "jaxen", "jaxen", "1.1.1", "", "jar" ),
			createDependency( "jaxen", "jaxen", "1.1.1", "", "jar" ),
			createDependency( "log4j", "log4j", "1.2.17", "", "jar" )
		};

		final List<Dependency> deps = new ArrayList<>();

		Collections.addAll(deps, dependencies);

		final Plugin serviceBuilderPlugin = MojoExecutor.plugin(
			"com.liferay", "com.liferay.portal.tools.service.builder",
			serviceBuilderMojoPluginVersion, deps );

		final ExecutionEnvironment executionEnvironment =
			MojoExecutor.executionEnvironment(
				_mavenProject, _mavenSession,
				_buildPluginManager
			);

		// call the com.liferay.portal.tools.service.builder Mojo

		MojoExecutor.executeMojo(
			serviceBuilderPlugin, "build-service", wrappedMojoConfiguration,
			executionEnvironment );

		try {
			moveServicePropertiesFile();
		}
		catch ( IOException e ) {
			throw new MojoExecutionException( e.getMessage(), e );
		}
	}

	private Xpp3Dom buildWrappedMojoConfiguration() {
		final Xpp3Dom wrapperConfiguration =
			(Xpp3Dom)getWrapperPlugin().getConfiguration();

		final Xpp3Dom serviceBuilderMojoConfiguration = new Xpp3Dom(
			"configuration" );

		// apiDirName parameter, checks for deprecated apiBaseDir

		final String defaultApiDirName;

		if (apiBaseDir != null) {
			logDeprecationWarning("apiBaseDir", "apiDirName");

			defaultApiDirName = makeRelativeToBaseDir(
				apiBaseDir + "/src/main/java");
		}
		else if (webappBaseDir != null) {
			defaultApiDirName = "src/main/java";
		}
		else {
			defaultApiDirName = null;
		}

		final Xpp3Dom apiDirNameNode = createNode(
			wrapperConfiguration, "apiDirName", defaultApiDirName);

		serviceBuilderMojoConfiguration.addChild(apiDirNameNode);

		// autoImportDefaultReferences parameter, copy if exists

		copyNode(
			wrapperConfiguration, serviceBuilderMojoConfiguration,
			"autoImportDefaultReferences", null);

		// autoNamespaceTables parameter, copy if exists

		copyNode(
			wrapperConfiguration, serviceBuilderMojoConfiguration,
			"autoNamespaceTables", null);

		// beanLocatorUtil parameter, copy if exists, else use default

		copyNode(
			wrapperConfiguration, serviceBuilderMojoConfiguration,
			"beanLocatorUtil", "com.liferay.util.bean.PortletBeanLocatorUtil");

		// buildNumber parameter

		if (serviceBuildNumber != 1) {
			final Xpp3Dom buildNumber = createNode(
				wrapperConfiguration, "buildName", null, "serviceBuildNumber",
				Long.toString(serviceBuildNumber));

			serviceBuilderMojoConfiguration.addChild(buildNumber);
		}

		// buildNumberIncrement parameter

		if (serviceBuildNumberIncrement != true) {
			final Xpp3Dom buildNumberIncrement = createNode(
				wrapperConfiguration, "buildNumberIncrement", null,
				"serviceBuildNumberIncrement", Boolean.toString(
					serviceBuildNumberIncrement));

			serviceBuilderMojoConfiguration.addChild(buildNumberIncrement);
		}

		// hbmFileName parameter

		final String defaultHbmFileName;

		if (_mavenProject.getPackaging().equals("war") ||
			(webappBaseDir != null)) {

			defaultHbmFileName = "src/main/resources/META-INF/portlet-hbm.xml";
		}
		else {
			defaultHbmFileName = "src/main/resources/META-INF/module-hbm.xml";
		}

		copyNode(
			wrapperConfiguration, serviceBuilderMojoConfiguration,
			"hbmFileName", defaultHbmFileName );

		// implDirName parameter

		Xpp3Dom implDirName = wrapperConfiguration.getChild("implDirName");

		if (implDirName == null) {
			implDirName = new Xpp3Dom("implDirName");

			// check for deprecated implBaseDir parameter

			if (implBaseDir != null) {
				getLog().warn(
					"implBaseDir is deprecated and will be removed in " +
					"future releases. Use implDirName instead." );

				implDirName.setValue(
					makeRelativeToBaseDir(
						implBaseDir.concat("/src/main/java")));
			}
			else if (implDir != null) {
				getLog().warn(
					"implDir is deprecated and will be removed in " +
					"future releases. Use implDirName instead." );

				implDirName.setValue(makeRelativeToBaseDir(implDir));
			}
			else if (webappBaseDir != null) {
				implDirName.setValue(
					makeRelativeToBaseDir(webappBaseDir + "/src/main/java"));
			}
			else {
				implDirName.setValue(
					makeRelativeToBaseDir(_baseDir.concat("/src/main/java")));
			}
		}

		serviceBuilderMojoConfiguration.addChild(implDirName);

		// inputFileName parameter

		Xpp3Dom inputFileName = wrapperConfiguration.getChild("inputFileName");

		if (inputFileName == null) {
			inputFileName = new Xpp3Dom("inputFileName");

			// check for deprecated sericeFileName parameter

			if (serviceFileName != null) {
				getLog().warn(
					"serviceFileName is deprecated and will be removed in " +
					"future releases. Use inputFileName instead." );

				inputFileName.setValue(makeRelativeToBaseDir(serviceFileName));
			}
			else if (webappBaseDir != null) {
				inputFileName.setValue(
					makeRelativeToBaseDir(
						webappBaseDir +
						"/src/main/webapp/WEB-INF/service.xml"));
			}
			else {
				inputFileName.setValue("src/main/webapp/WEB-INF/service.xml");
			}
		}

		serviceBuilderMojoConfiguration.addChild(inputFileName);

		// mergeModelHintsConfigs parameter

		copyNode(
			wrapperConfiguration, serviceBuilderMojoConfiguration,
			"mergeModelHintsConfigs", null );

		// mergeReadOnlyPrefixes parameter

		copyNode(
			wrapperConfiguration, serviceBuilderMojoConfiguration,
			"mergeReadOnlyPrefixes", null);

		// mergeResourceActionsConfigs parameter

		copyNode(
			wrapperConfiguration, serviceBuilderMojoConfiguration,
			"mergeResourceActionsConfigs", null);

		// modelHintsConfigs parameter

		copyNode(
			wrapperConfiguration, serviceBuilderMojoConfiguration,
			"modelHintsConfigs", null);

		// modelHintsFileName parameter

		Xpp3Dom modelHintsFileName = wrapperConfiguration.getChild(
			"modelHintsFileName");

		if (modelHintsFileName == null) {
			modelHintsFileName = new Xpp3Dom("modelHintsFileName");

			if (webappBaseDir != null) {
				getLog().warn(
					"webappBaseDir is deprecated and will be removed in " +
					"future releases." );

				modelHintsFileName.setValue(
					makeRelativeToBaseDir(
						webappBaseDir +
						"src/main/resources/META-INF/portlet-model-hints.xml"));
			}
			else {
				if (_mavenProject.getPackaging().equals("war")) {
					modelHintsFileName.setValue(
						"src/main/resources/META-INF/portlet-model-hints.xml");
				}
				else {
					modelHintsFileName.setValue(
						"src/main/resources/META-INF/portlet-model-hints.xml");
				}
			}
		}

		serviceBuilderMojoConfiguration.addChild(modelHintsFileName);

		// osgiModule parameter

		copyNode(
			wrapperConfiguration, serviceBuilderMojoConfiguration, "osgiModule",
			null);

		// pluginName parameter

		final String defaultPluginName = _mavenProject.getArtifactId();

		copyNode(
			wrapperConfiguration, serviceBuilderMojoConfiguration, "pluginName",
			defaultPluginName );

		// propsUtil parameter

		copyNode(
			wrapperConfiguration, serviceBuilderMojoConfiguration, "propsUtil",
			"com.liferay.util.service.ServiceProps");

		// readOnlyPrefixes parameter

		copyNode(
			wrapperConfiguration, serviceBuilderMojoConfiguration,
			"readOnlyPrefixes", null);

		// resourceActionsConfigs parameter

		copyNode(
			wrapperConfiguration, serviceBuilderMojoConfiguration,
			"resourceActionsConfigs", null);

		// resourcesDirName parameter

		copyNode(
			wrapperConfiguration, serviceBuilderMojoConfiguration,
			"resourcesDirName", "src/main/resources");

		// springFileName parameter

		final String defaultSpringFileName; {
			if (_mavenProject.getPackaging().equals("war") ||
				(webappBaseDir != null)) {

				defaultSpringFileName =
					"src/main/resources/META-INF/portlet-spring.xml";
			}
			else {
				defaultSpringFileName =
					"src/main/resources/META-INF/module-spring.xml";
			}
		}

		copyNode(
			wrapperConfiguration, serviceBuilderMojoConfiguration,
			"springFileName", defaultSpringFileName);

		// springNamespaces parameter

		final String defaultSpringNamespaces; {
			if (!_mavenProject.getPackaging().equals( "war" )) {
				defaultSpringNamespaces = "beans,osgi";
			}
			else {
				defaultSpringNamespaces = null;
			}
		}

		copyNode(
			wrapperConfiguration, serviceBuilderMojoConfiguration,
			"springNamespaces", defaultSpringNamespaces );

		// sqlDirName parameter;

		Xpp3Dom sqlDirName = wrapperConfiguration.getChild("sqlDirName");

		if (sqlDirName == null) {
			final String defaultSqlDirName; {

			if (_mavenProject.getPackaging().equals("war")) {
				defaultSqlDirName = "src/main/webapp/WEB-INF/sql";
				}
				else {
					defaultSqlDirName = "src/main/resources/META-INF/sql";
				}
			}

			if (sqlDir != null) {
				logDeprecationWarning("sqlDir", "sqlDirName");

				sqlDirName = createNode(
					wrapperConfiguration, "sqlDirName",
					makeRelativeToBaseDir(sqlDir));
			}
			else {
				sqlDirName = createNode(
					wrapperConfiguration, "sqlDirName", defaultSqlDirName);
			}
		}

		serviceBuilderMojoConfiguration.addChild(sqlDirName);

		// sqlFileName parameter;

		Xpp3Dom sqlFileName = createNode(
			wrapperConfiguration, "sqlFileName", "tables.sql");

		serviceBuilderMojoConfiguration.addChild(sqlFileName);

		// sqlIndexesFileName parameter

		copyNode(
			wrapperConfiguration, serviceBuilderMojoConfiguration,
			"sqlIndexesFileName", null);

		// sqlSequencesFileName parameter

		copyNode(
			wrapperConfiguration, serviceBuilderMojoConfiguration,
			"sqlSequencesFileName", null);

		// testDirName parameter;

		Xpp3Dom testDirName = createNode(
			wrapperConfiguration, "testDirName", "src/test/java");

		serviceBuilderMojoConfiguration.addChild(testDirName);

		return serviceBuilderMojoConfiguration;
	}

	private void copyNode(Xpp3Dom source, Xpp3Dom destination, String nodeName,
		String defaultValue) {

		final Xpp3Dom node = source.getChild(nodeName);

		if (node != null) {
			destination.addChild(node);
		}
		else if (defaultValue != null) {
			final Xpp3Dom newNode = new Xpp3Dom(nodeName);

			newNode.setValue(defaultValue);

			destination.addChild(newNode);
		}
	}

	private void copyServicePropertiesFile() throws Exception {
		File servicePropertiesFile = new File(
			_implResourcesDir, "service.properties");

		if (servicePropertiesFile.exists()) {
			FileUtil.copyFile(
				servicePropertiesFile,
				new File(_implDir, "service.properties"));
		}
	}

	private Xpp3Dom createNode(
		Xpp3Dom configuration, String nodeName, String defaultNodeValue) {

		Xpp3Dom node = configuration.getChild(nodeName);

		if (node == null) {
			node = new Xpp3Dom(nodeName);
			node.setValue(defaultNodeValue);
		}

		return node;
	}

	private Xpp3Dom createNode(
		Xpp3Dom configuration, String nodeName, String defaultNodeValue,
		String deprecatedNodeName, String deprecatedNodeValue) {

		Xpp3Dom node = configuration.getChild(nodeName);

		if (node == null) {
			node = new Xpp3Dom(nodeName);

			if (deprecatedNodeValue != null) {
				getLog().warn(
					deprecatedNodeName + "is deprecated and will be removed " +
					"in future releases. Use " + nodeName + " instead." );

				node.setValue(deprecatedNodeValue);
			}
			else {
				node.setValue(defaultNodeValue);
			}
		}

		return node;
	}

	private String getCustomServiceBuilderVersion() {
		for (Dependency pluginDependency :
				getWrapperPlugin().getDependencies()) {

			getLog().debug( "pluginDependency: " + pluginDependency );

			if (pluginDependency.getGroupId().equals( "com.liferay" ) &&
				pluginDependency.getArtifactId().equals(
					"com.liferay.portal.tools.service.builder" )) {

				return pluginDependency.getVersion();
			}
		}

		return null;
	}

	private Plugin getWrapperPlugin() {
		return _mavenProject.getBuild().getPluginsAsMap().get(
			"com.liferay.maven.plugins:liferay-maven-plugin" );
	}

	private void logDeprecationWarning(String deprecatedNodeName,
			String newNodeName) {

		getLog().warn(
			deprecatedNodeName + " is deprecated and will be removed " +
			"in future releases. Use " + newNodeName + " instead." );
	}

	private String makeRelativeToBaseDir(String pathValue) {
		if (new File(pathValue).isAbsolute()) {
			Path absolutePath = Paths.get(pathValue);
			Path relativePath = Paths.get(_baseDir).relativize(absolutePath);

			return relativePath.toString();
		}

		return pathValue;
	}

	private void moveServicePropertiesFile() throws IOException {
		FileUtil.move(
			new File(_implDir, "service.properties"),
			new File(_implResourcesDir, "service.properties"));
	}

	private static final Pattern SERVICE_BUILDER_PUBLIC_ID = Pattern.compile(
		".*Liferay//DTD Service Builder ([0-9]\\.[0-9]\\.[0-9])//.*" );

	/**
	 * @parameter default-value="${basedir}"
	 * @required
	 */
	private String _baseDir;

	/**
	 * @component
	 */
	private BuildPluginManager _buildPluginManager;

	private String _implDir;
	private String _implResourcesDir;

	/**
	 * @parameter expression="${project}"
	 * @required
	 * @readonly
	 */
	private MavenProject _mavenProject;

	/**
	 * @component
	 */
	private MavenSession _mavenSession;

	/**
	 * @parameter
	 * @deprecated Will be removed in 7.1.0
	 */
	private String apiBaseDir;

	/**
	 * @parameter
	 * @deprecated Will be removed in 7.1.0
	 */
	private String implBaseDir;

	/**
	 * @parameter
	 * @deprecated Will be removed in 7.1.0
	 */
	private String implDir;

	/**
	 * @parameter default-value="1" expression="${serviceBuildNumber}"
	 * @deprecated Will be removed in 7.1.0
	 */
	private long serviceBuildNumber;

	/**
	 * @parameter default-value="true" expression="${serviceBuildNumberIncrement}"
	 * @deprecated Will be removed in 7.1.0
	 */
	private boolean serviceBuildNumberIncrement;

	/**
	 * @parameter default-value="" expression="${serviceFileName}"
	 * @deprecated Will be removed in 7.1.0
	 */
	private String serviceFileName;

	/**
	 * @parameter
	 * @deprecated Will be removed in 7.1.0 use sqlDirName instead
	 */
	private String sqlDir;

	/**
	 * @parameter
	 * @deprecated Will be removed in 7.1.0
	 */
	private String webappBaseDir;

}