/*
 * Copyright 2026 ajaygodbole7
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ajaygodbole7.tokenmanager;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * Architecture rules that guard invariants documented in CLAUDE.md and enforced by the
 * sealed exception hierarchy. Each rule is verified true against the current codebase;
 * a failure here means the architecture has drifted from its documented design.
 */
@AnalyzeClasses(
    packages = "io.github.ajaygodbole7.tokenmanager",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  /**
   * Every permitted subclass of the sealed {@link TokenException} hierarchy must be
   * {@code final} — this is what makes the sealed hierarchy exhaustive and safe to
   * switch over. {@code TokenException} itself is excluded: it is sealed but not final.
   */
  @ArchTest
  static final ArchRule tokenExceptionSubclassesMustBeFinal =
      classes()
          .that().areAssignableTo(TokenException.class)
          .and().doNotHaveSimpleName("TokenException")
          .should().haveModifier(JavaModifier.FINAL)
          .because("the sealed TokenException hierarchy relies on every permitted "
              + "subclass being final so exhaustive switches over it stay safe");

  /**
   * Only {@link OAuth2TokenManager} may execute an HTTP call via
   * {@code OkHttpClient.newCall(Request)}. {@link TokenConfig} legitimately references
   * {@code OkHttpClient} (to hold a caller-supplied client) and {@code HttpUrl} (to
   * validate the token endpoint), but request execution must stay in one place.
   */
  @ArchTest
  static final ArchRule onlyManagerExecutesHttpRequests =
      noClasses()
          .that().resideInAPackage("io.github.ajaygodbole7.tokenmanager")
          .and().doNotHaveSimpleName("OAuth2TokenManager")
          .should().callMethod(OkHttpClient.class, "newCall", Request.class)
          .because("only OAuth2TokenManager should execute HTTP requests; other "
              + "classes may reference OkHttpClient/HttpUrl for configuration only");

  /** No class in this package prints directly to stdout/stderr instead of using SLF4J. */
  @ArchTest
  static final ArchRule noStandardStreamAccess =
      GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
}
