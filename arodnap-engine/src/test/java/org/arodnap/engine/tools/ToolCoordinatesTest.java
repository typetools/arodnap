package org.arodnap.engine.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolCoordinatesTest {
    @Test
    void everyToolHasCoordinatesFromTheBuild() {
        for (String tool : java.util.stream.Stream.concat(ToolCoordinates.TOOLCHAIN.stream(), ToolCoordinates.CAPTURE_HOOKS.stream()).toList()) {
            assertThat(ToolCoordinates.of(tool)).matches("[\\w.-]+:[\\w.-]+(:[\\w.-]+)?:[\\w.-]+").doesNotContain("${");
        }
        assertThat(ToolCoordinates.of("rlfixer")).isEqualTo("org.arodnap:arodnap-rlfixer:" + ToolCoordinates.arodnapVersion());
    }

    @Test
    void aToolsFileNameIsMavens() {
        assertThat(ToolCoordinates.fileName("error-prone")).startsWith("error_prone_core-").endsWith("-with-dependencies.jar");
        assertThat(ToolCoordinates.fileName("rlfixer")).isEqualTo("arodnap-rlfixer-" + ToolCoordinates.arodnapVersion() + ".jar");
    }
}
