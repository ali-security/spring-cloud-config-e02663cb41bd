/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.config.server.environment;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jgit.junit.MockSystemReader;
import org.eclipse.jgit.util.SystemReader;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.server.environment.MultipleJGitEnvironmentRepository.PatternMatchingJGitEnvironmentRepository;
import org.springframework.cloud.config.server.environment.SearchPathLocator.Locations;
import org.springframework.cloud.config.server.test.ConfigServerTestUtils;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author Dave Syer
 *
 */
public class MultipleJGitEnvironmentApplicationPlaceholderRepositoryTests {

	private StandardEnvironment environment = new StandardEnvironment();

	private MultipleJGitEnvironmentRepository repository = new MultipleJGitEnvironmentRepository(
			this.environment, new MultipleJGitEnvironmentProperties());

	@BeforeClass
	public static void initClass() {
		// mock Git configuration to make tests independent of local Git configuration
		SystemReader.setInstance(new MockSystemReader());
	}

	@Before
	public void init() throws Exception {
		String defaultUri = ConfigServerTestUtils.prepareLocalRepo("config-repo");
		this.repository.setUri(defaultUri);
		this.repository.setRepos(createRepositories());
	}

	private Map<String, PatternMatchingJGitEnvironmentRepository> createRepositories()
			throws Exception {
		String test1Uri = ConfigServerTestUtils.prepareLocalRepo("test1-config-repo");
		ConfigServerTestUtils.prepareLocalRepo("test2-config-repo");

		Map<String, PatternMatchingJGitEnvironmentRepository> repos = new HashMap<>();
		repos.put("templates", createRepository("test", "*-config-repo",
				test1Uri.replace("test1-config-repo", "{application}")));
		return repos;
	}

	private PatternMatchingJGitEnvironmentRepository createRepository(String name,
			String pattern, String uri) {
		PatternMatchingJGitEnvironmentRepository repo = new PatternMatchingJGitEnvironmentRepository();
		repo.setEnvironment(this.environment);
		repo.setName(name);
		repo.setPattern(new String[] { pattern });
		repo.setUri(uri);
		return repo;
	}

	@Test
	public void defaultRepo() {
		Environment environment = this.repository.findOne("bar", "staging", "master");
		assertThat(environment.getPropertySources().size()).isEqualTo(2);
		assertThat(environment.getPropertySources().get(0).getName())
				.isEqualTo(this.repository.getUri() + "/bar.properties");
		assertVersion(environment);
	}

	@Test
	public void missingRepo() {
		Environment environment = this.repository.findOne("missing-config-repo",
				"staging", "master");
		assertThat(environment.getPropertySources().size())
				.as("Wrong property sources: " + environment).isEqualTo(1);
		assertThat(environment.getPropertySources().get(0).getName())
				.isEqualTo(this.repository.getUri() + "/application.yml");
		assertVersion(environment);
	}

	@Test
	public void mappingRepo() {
		Environment environment = this.repository.findOne("test1-config-repo", "staging",
				"master");
		assertThat(environment.getPropertySources().size()).isEqualTo(1);
		assertThat(environment.getPropertySources().get(0).getName())
				.isEqualTo(getUri("*").replace("{application}", "test1-config-repo")
						+ "/application.yml");
		assertVersion(environment);
	}

	@Test
	public void otherMappingRepo() {
		Environment environment = this.repository.findOne("test2-config-repo", "staging",
				"master");
		assertThat(environment.getPropertySources().size()).isEqualTo(1);
		assertThat(environment.getPropertySources().get(0).getName())
				.isEqualTo(getUri("*").replace("{application}", "test2-config-repo")
						+ "/application.properties");
		assertVersion(environment);
	}

	@Test
	public void invalidAuthorityTests() {
		assertThatThrownBy(() -> createRepository("test", "*-config-repo",
				"http://{profile}:8080/test1-config-repo"))
						.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> createRepository("test", "*-config-repo",
				"http://localhost:{profile}/test1-config-repo"))
						.isInstanceOf(IllegalStateException.class);
	}

	@Test
	public void invalidAuthorityFromPropertiesTests() {
		// the default repository uri is bound from configuration properties
		MultipleJGitEnvironmentProperties hostTemplate = new MultipleJGitEnvironmentProperties();
		hostTemplate.setUri("http://{profile}:8080/test1-config-repo");
		assertThatThrownBy(() -> new MultipleJGitEnvironmentRepository(this.environment,
				hostTemplate)).isInstanceOf(IllegalArgumentException.class);

		// pattern-matching repositories are bound from configuration properties too
		MultipleJGitEnvironmentProperties portTemplate = new MultipleJGitEnvironmentProperties();
		portTemplate.getRepos().put("test",
				new MultipleJGitEnvironmentProperties.PatternMatchingJGitEnvironmentProperties(
						"http://localhost:{profile}/test1-config-repo"));
		assertThatThrownBy(() -> new MultipleJGitEnvironmentRepository(this.environment,
				portTemplate)).isInstanceOf(IllegalStateException.class);

		// placeholders in the path are still supported and cannot change the host
		MultipleJGitEnvironmentProperties pathTemplate = new MultipleJGitEnvironmentProperties();
		pathTemplate.setUri("http://localhost/{profile}-config-repo");
		MultipleJGitEnvironmentRepository pathTemplateRepository = new MultipleJGitEnvironmentRepository(
				this.environment, pathTemplate);
		assertThat(pathTemplateRepository.getRepository(pathTemplateRepository, "app",
				"evil.example.com", "master").getUri())
						.isEqualTo("http://localhost/evil.example.com-config-repo");
	}

	@Test
	@Ignore("not supported yet (placeholders in search paths with lists)")
	public void profilesInSearchPaths() {
		this.repository.setSearchPaths("{profile}");
		Locations locations = this.repository.getLocations("foo", "dev,one,two",
				"master");
		assertThat(locations.getLocations().length).isEqualTo(3);
		assertThat(locations.getLocations()[0]).isEqualTo("classpath:/test/dev/");
	}

	private void assertVersion(Environment environment) {
		String version = environment.getVersion();
		assertThat(version).as("version was null").isNotNull();
		assertThat(version.length() >= 40 && version.length() <= 64)
				.as("version length was wrong").isTrue();
	}

	private String getUri(String pattern) {
		String uri = null;

		Map<String, PatternMatchingJGitEnvironmentRepository> repoMappings = this.repository
				.getRepos();

		for (PatternMatchingJGitEnvironmentRepository repo : repoMappings.values()) {
			String[] mappingPattern = repo.getPattern();
			if (mappingPattern != null && mappingPattern.length != 0) {
				uri = repo.getUri();
				break;
			}
		}

		return uri;
	}

}
