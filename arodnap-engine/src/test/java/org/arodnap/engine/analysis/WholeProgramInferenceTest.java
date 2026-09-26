package org.arodnap.engine.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.arodnap.engine.tools.ToolCoordinates;
import org.junit.jupiter.api.Test;

/**
 * Keeps Arodnap's inference loop in step with the Checker Framework's own. Arodnap runs whole-program
 * inference itself instead of through {@code wpi.sh}; the loop it mirrors is do-like-javac's WPI tool,
 * {@code checker/bin/.do-like-javac/do_like_javac/tools/wpi.py} in the Checker Framework's release zip.
 *
 * <p>When the Checker Framework is upgraded, {@link #theLoopWasReviewedForThisCheckerFramework} fails:
 *
 * <ol>
 *   <li>Diff tools/wpi.py of the old and new releases.
 *   <li>Port any change to the loop or its checker flags into {@link WholeProgramInference}
 *       (build-system handling, JDK selection and delombok are wpi.sh and do-like-javac concerns
 *       Arodnap does not need).
 *   <li>Run the end-to-end tests: {@code ARODNAP_E2E=1 mvn install}.
 *   <li>Update {@link #REVIEWED_CHECKER_FRAMEWORK} (and the flags below, if they changed).
 * </ol>
 */
class WholeProgramInferenceTest {
    /** The Checker Framework release whose tools/wpi.py (SHA-256 e7ef6913...) the loop was compared with. */
    private static final String REVIEWED_CHECKER_FRAMEWORK = "4.2.3";

    @Test
    void theLoopWasReviewedForThisCheckerFramework() {
        assertThat(ToolCoordinates.checkerFrameworkVersion())
                .as("the Checker Framework changed: review its WPI loop against WholeProgramInference (see this test's Javadoc)")
                .isEqualTo(REVIEWED_CHECKER_FRAMEWORK);
    }

    @Test
    void eachRoundPassesTheCheckerFlagsUpstreamDoes() {
        // Upstream also passes -Astubs (only when do-like-javac is given --stubs, which wpi.sh never does)
        // and -AsuppressWarnings=type.anno.before.modifier (only for delombok'd sources).
        assertThat(WholeProgramInference.ITERATION_FLAGS).containsExactly("-Ainfer=ajava", "-Awarns");
    }
}
