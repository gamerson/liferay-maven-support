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

import java.io.File;
import java.io.IOException;

import java.util.ArrayList;
import java.util.List;

import junit.framework.TestCase;

import org.apache.maven.it.VerificationException;
import org.apache.maven.it.Verifier;
import org.apache.maven.it.util.ResourceExtractor;

/**
 * @author Gregory Amerson
 * @author Simon Jiang
 */
public class ServiceBuilderWrapperVerifyTest extends TestCase {

	public void testBadServiceXml() throws Exception {
		final Verifier verifier = executeServiceBuilderOnProject(
			"/projects/servicebuilder/testProjectBadServiceXml");

		verifier.verifyTextInLog(
			"This build-service goal is only compatible with " +
			"Liferay 7.0 and greater.");

		verifier.resetStreams();
	}

	public void testCustomServiceBuilderVersion() throws Exception {
		final Verifier verifier = executeServiceBuilderOnProject(
			"/projects/servicebuilder/testProjectCustomServiceBuilderVersion");

		verifier.verifyTextInLog(
			"com.liferay:com.liferay.portal.tools.service.builder:1.0.48");

		verifier.verifyErrorFreeLog();

		verifier.resetStreams();
	}

	public void testDefaultServiceBuilderVersion() throws Exception {
		final Verifier verifier = executeServiceBuilderOnProject(
			"/projects/servicebuilder/testProject");

		verifier.verifyTextInLog(
			"com.liferay:com.liferay.portal.tools.service.builder:1.0.52");

		verifier.verifyErrorFreeLog();

		verifier.resetStreams();

		verifyServiceBuilderGeneratedFiles(verifier);

		executeCompile(verifier);
	}

	public void testNoServiceXml() throws Exception {
		final Verifier verifier = executeServiceBuilderOnProject(
			"/projects/servicebuilder/testProjectNoServiceXml");

		verifier.verifyTextInLog("inputFile does not exist: ");

		verifier.resetStreams();
	}

	private void executeCompile(Verifier verifier)
		throws VerificationException {

		final List<String> cliOptions = new ArrayList<>();

		cliOptions.add("-P");
		cliOptions.add("7");
		cliOptions.add("-U");

		verifier.setCliOptions(cliOptions);

		verifier.executeGoal("compile");
	}

	private Verifier executeServiceBuilderOnProject(
		final String path) throws IOException {

		final File testDir = ResourceExtractor.simpleExtractResources(
			getClass(), path);

		assertTrue(testDir.exists());

		Verifier verifier = null;

		try {
			verifier = new Verifier(testDir.getAbsolutePath());

			verifier.deleteArtifact("it", "testProject", "1.0", "pom");
			verifier.deleteArtifact("it", "testProject-portlet", "1.0", "war");
			verifier.deleteArtifact(
				"it", "testProject-portlet-service", "1.0", "jar");

			verifier.setMavenDebug(true);

			final List<String> cliOptions = new ArrayList<>();

			cliOptions.add("-P");
			cliOptions.add("7");
			cliOptions.add("-pl");
			cliOptions.add("testProject-portlet");

			verifier.setCliOptions(cliOptions);

			verifier.executeGoal("liferay:build-service");
		}
		catch ( VerificationException e ) {

			// ignore these as we want to continue using the verifier

		}

		return verifier;
	}

	private void verifyServiceBuilderGeneratedFiles(Verifier verifier) {
		verifier.assertFilePresent(
			"testProject-portlet-service/src/main/java/it/" +
			"NoSuchFooException.java");

		verifier.assertFilePresent(
			"testProject-portlet-service/src/main/java/it/model/Foo.java");

		verifier.assertFilePresent(
			"testProject-portlet/src/main/java/it/service/impl/" +
			"FooServiceImpl.java");

		verifier.assertFilePresent(
			"testProject-portlet/src/main/java/it/service/impl/" +
			"FooServiceImpl.java");

		verifier.assertFilePresent(
			"testProject-portlet/src/test/java/it/service/persistence/test/" +
			"FooPersistenceTest.java");

		verifier.assertFilePresent(
			"testProject-portlet/src/main/resources/META-INF/" +
			"portlet-hbm.xml");

		verifier.assertFilePresent(
			"testProject-portlet/src/main/resources/META-INF/" +
			"portlet-spring.xml");

		verifier.assertFilePresent(
			"testProject-portlet/src/main/resources/META-INF/" +
			"portlet-model-hints.xml");
	}

}