package io.moderne.connect.commands;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectTest {
    CommandLine cmd = new CommandLine(new Connect());
    StringWriter sw;

    @BeforeEach
    void setup() {
        sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));
        cmd.setErr(new PrintWriter(sw));
    }

    @Test
    void emptyLeadsToHelp() {
        cmd.execute();
        assertThat(sw.toString())
                .contains("Automated code remediation.")
                .contains("Usage: mod-connect [COMMAND]");
    }
}